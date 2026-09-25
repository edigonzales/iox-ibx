package ch.interlis.ibx.api;

import ch.interlis.ibx.codec.*;
import ch.interlis.ibx.container.*;
import ch.interlis.ibx.index.*;
import ch.interlis.ibx.iox.ModelBridge;
import ch.interlis.ibx.remote.*;
import ch.interlis.iom_j.xtf.XtfWriterBase;
import ch.interlis.iox.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class IbxContainer implements AutoCloseable {
  private final RangeSource source;
  private final FrameStore store;
  private final TransferMetadata metadata;
  private final ObjectCodec codec;
  private final BTree tree;
  private final long[] roots;
  private final ReadMetrics metrics;
  private boolean closed;
  private final RemoteOptions readOptions;

  public static IbxContainer open(Path path) throws IOException {
    return open(path, new RemoteOptions());
  }

  public static IbxContainer open(Path path, RemoteOptions options) throws IOException {
    options.validate();
    ReadMetrics m = new ReadMetrics();
    return new IbxContainer(new LocalSource(path, m), m, options);
  }

  public static IbxContainer open(URI uri) throws IOException {
    return open(uri, new RemoteOptions());
  }

  public static IbxContainer open(URI uri, RemoteOptions options) throws IOException {
    options.validate();
    if ("file".equals(uri.getScheme())) return open(Paths.get(uri), options);
    ReadMetrics m = new ReadMetrics();
    return new IbxContainer(new HttpRangeSource(uri, options, m), m, options);
  }

  private IbxContainer(RangeSource source, ReadMetrics metrics, RemoteOptions options)
      throws IOException {
    this.source = source;
    this.metrics = metrics;
    readOptions = options;
    store = new FrameStore(source, metrics, options.cacheBytes);
    try {
      int formatVersion =
          Frames.checkHeader(
              new DataInputStream(new ByteArrayInputStream(source.read(0, Frames.HEADER_SIZE))));
      roots = Frames.footer(source);
      Frames.Frame m = store.read(Frames.HEADER_SIZE);
      if (m.type != Frames.METADATA) throw new IOException("Missing metadata");
      metadata = Cbor.read(m.data, TransferMetadata.class);
      if ((!"2.4".equals(metadata.version) && !"2.3".equals(metadata.version)) || metadata.mappingVersion != 1)
        throw new IOException("Unsupported transfer/mapping version");
      metadata.validateFormat(formatVersion);
      codec = new ObjectCodec(metadata, true);
      tree = new BTree(store, new FrameRef(roots[0], roots[2]));
    } catch (IOException e) {
      source.close();
      throw e;
    }
  }

  public CloseableIterator<Location> prefetch(final CloseableIterator<Location> input) {
    if (readOptions.prefetchPositions == 0) return input;
    return new CloseableIterator<Location>() {
      final Deque<Location> ready = new ArrayDeque<Location>();

      public boolean hasNext() {
        return !ready.isEmpty() || input.hasNext();
      }

      public Location next() {
        if (ready.isEmpty()) {
          List<FrameRef> refs = new ArrayList<FrameRef>();
          for (int i = 0; i < readOptions.prefetchPositions && input.hasNext(); i++) {
            Location loc = input.next();
            ready.add(loc);
            refs.add(new FrameRef(loc.basketOffset, loc.basketLength));
            if (loc.chunkOffset != 0) refs.add(new FrameRef(loc.chunkOffset, loc.chunkLength));
          }
          try {
            store.prefetch(refs, readOptions.prefetchMaxGap, readOptions.prefetchMaxBytes);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
        if (ready.isEmpty()) throw new NoSuchElementException();
        return ready.removeFirst();
      }

      public void close() throws IOException {
        ready.clear();
        input.close();
      }
    };
  }

  public void checkOpen() {
    if (closed) throw new IllegalStateException("Container closed");
  }

  public TransferMetadata metadata() {
    checkOpen();
    return metadata;
  }

  public ReadMetrics metrics() {
    return metrics;
  }

  public FrameStore frameStore() {
    return store;
  }

  public long indexRoot() {
    return roots[0];
  }

  public FrameRef indexRef() {
    return new FrameRef(roots[0], roots[2]);
  }

  public FrameRef spatialRef() {
    return new FrameRef(roots[1], roots[3]);
  }

  public long spatialRoot() {
    return roots[1];
  }

  public String state() throws IOException {
    return metadata.datasetId + ":" + source.revision();
  }

  public long size() throws IOException {
    return source.size();
  }

  public void clearCache() {
    store.clear();
  }

  public IoxReader openTransferReader() throws IOException {
    checkOpen();
    return new SequentialReader(new RangeInputStream(source));
  }

  public static IoxReader stream(InputStream input) throws IOException {
    return new SequentialReader(input);
  }

  private CloseableIterator<Location> range(String prefix) throws IOException {
    final CloseableIterator<ExternalSort.Entry> it = tree.range(prefix);
    return new CloseableIterator<Location>() {
      public boolean hasNext() {
        checkOpen();
        return it.hasNext();
      }

      public Location next() {
        try {
          return Location.decode(it.next().value);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }

      public void close() throws IOException {
        it.close();
      }
    };
  }

  public List<String> layers() {
    checkOpen();
    if (!"wkb".equals(metadata.geometryEncoding))
      throw new IllegalStateException("GIS API requires WKB geometry profile");
    List<String> layers = new ArrayList<String>();
    for (String id : metadata.geometries.keySet())
      if (metadata.concreteClasses.contains(id.substring(0, id.lastIndexOf('.')))) layers.add(id);
    return Collections.unmodifiableList(layers);
  }

  public GisLayer openLayer(String id) {
    if (!layers().contains(id)) throw new IllegalArgumentException("Unknown GIS layer " + id);
    return new GisLayer(this, id);
  }

  public Fragment getFid(long fid) {
    checkOpen();
    return new Fragment(
        this,
        () -> {
          ExternalSort.Entry e = fid < 0 ? null : tree.floor("F\0" + FilesEx.number(fid));
          if (e == null || !Keys.startsWith(e.binaryKey, Keys.encode("F\0")))
            return singleton(null);
          if (e.value.length != 57) throw new IOException("Invalid FID range");
          long first = java.nio.ByteBuffer.wrap(e.binaryKey, e.binaryKey.length - 8, 8).getLong();
          int count = java.nio.ByteBuffer.wrap(e.value, 53, 4).getInt();
          if (count < 1 || first < 0 || first > Long.MAX_VALUE - count)
            throw new IOException("Invalid FID count");
          if (fid - first >= count) return singleton(null);
          Location loc = Location.decode(Arrays.copyOf(e.value, 53));
          loc.ordinal = (int) (fid - first);
          return singleton(loc.bytes());
        },
        "FID " + fid);
  }

  public Fragment getTopic(String name) {
    checkOpen();
    if (!metadata.topics.contains(name))
      throw new IllegalArgumentException("Unknown topic: " + name);
    return new Fragment(this, () -> range("T\0" + name + "\0"), "topic " + name);
  }

  public Fragment getClass(String name) {
    checkOpen();
    if (!metadata.classes.containsKey(name))
      throw new IllegalArgumentException("Unknown class: " + name);
    return new Fragment(this, () -> range("C\0" + name + "\0"), "class " + name);
  }

  public Fragment getObject(String tid) {
    checkOpen();
    return new Fragment(this, () -> singleton(tree.get("O\0" + tid)), "object " + tid);
  }

  public Fragment getBasket(String bid) {
    checkOpen();
    return new Fragment(
        this,
        () -> {
          byte[] value = tree.get("B\0" + bid);
          if (value == null) return singleton(null);
          Location loc = Location.decode(value);
          final CloseableIterator<Location> rest =
              range("D\0" + FilesEx.number(loc.basketPosition) + "\0");
          return new CloseableIterator<Location>() {
            boolean first = true;

            public boolean hasNext() {
              return first || rest.hasNext();
            }

            public Location next() {
              if (first) {
                first = false;
                return loc;
              }
              return rest.next();
            }

            public void close() throws IOException {
              rest.close();
            }
          };
        },
        "basket " + bid);
  }

  private static CloseableIterator<Location> singleton(byte[] bytes) throws IOException {
    final Location loc = bytes == null ? null : Location.decode(bytes);
    return new CloseableIterator<Location>() {
      boolean available = loc != null;

      public boolean hasNext() {
        return available;
      }

      public Location next() {
        if (!available) throw new NoSuchElementException();
        available = false;
        return loc;
      }

      public void close() {
        available = false;
      }
    };
  }

  public BasketContext basket(Location loc) throws IOException {
    checkOpen();
    Frames.Frame f = store.read(new FrameRef(loc.basketOffset, loc.basketLength));
    if (f.type != Frames.BASKET) throw new IOException("Invalid basket reference");
    BasketContext b = Cbor.read(f.data, BasketContext.class);
    if (b.position != loc.basketPosition) throw new IOException("Basket position mismatch");
    return b;
  }

  public ObjectCursor objects(Location loc) throws IOException {
    checkOpen();
    Frames.Frame f = store.read(new FrameRef(loc.chunkOffset, loc.chunkLength));
    if (f.type != Frames.CHUNK) throw new IOException("Invalid chunk reference");
    Chunk c = Chunk.unpack(f.data);
    if (c.info.id != loc.chunkId
        || c.info.basketPosition != loc.basketPosition
        || c.info.basketOffset != loc.basketOffset
        || loc.ordinal >= c.info.count) throw new IOException("Invalid object/chunk reference");
    return new ObjectCursor(c, codec);
  }

  public Fragment querySpatialCandidates(String className, String attribute, BoundingBox box)
      throws IOException {
    checkOpen();
    return ch.interlis.ibx.spatial.SpatialIndex.query(this, className, attribute, box);
  }

  public void export(OutputStream output) throws Exception {
    XtfWriterBase writer = ModelBridge.writer(output, metadata);
    IoxReader reader = openTransferReader();
    try {
      IoxEvent e;
      while ((e = reader.read()) != null) writer.write(e);
      writer.flush();
    } finally {
      reader.close();
    } // caller owns output
  }

  public void exportFragment(Fragment fragment, OutputStream output) throws Exception {
    XtfWriterBase writer = ModelBridge.writer(output, metadata);
    ch.interlis.iom_j.xtf.XtfStartTransferEvent start = metadata.event();
    start.setComment(
        "IBX fragment; "
            + fragment.description()
            + "; may be incomplete and not model-conformant."
            + (metadata.comment == null ? "" : "\n" + metadata.comment));
    writer.write(start);
    try (Stream<BasketContext> bs = fragment.baskets();
        Stream<SelectedObject> os = fragment.objects()) {
      Iterator<SelectedObject> objects = os.iterator();
      SelectedObject next = objects.hasNext() ? objects.next() : null;
      Iterator<BasketContext> baskets = bs.iterator();
      while (baskets.hasNext()) {
        BasketContext basket = baskets.next();
        writer.write(basket.event());
        while (next != null && next.getBasket().position == basket.position) {
          writer.write(new ch.interlis.iox_j.ObjectEvent(next.getObject()));
          next = objects.hasNext() ? objects.next() : null;
        }
        writer.write(new ch.interlis.iox_j.EndBasketEvent());
      }
      if (next != null) throw new IOException("Fragment object without basket");
    }
    writer.write(new ch.interlis.iox_j.EndTransferEvent());
    writer.flush();
  }

  public void close() throws IOException {
    if (!closed) {
      closed = true;
      store.clear();
      source.close();
    }
  }
}
