package ch.interlis.ibx.spatial;

import ch.interlis.ibx.api.*;
import ch.interlis.ibx.codec.Cbor;
import ch.interlis.ibx.container.*;
import ch.interlis.ibx.index.ExternalSort;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Packed immutable R-tree; leaves carry physical object locations. */
public final class SpatialIndex {
  public static final class Manifest {
    public Map<String, Info> indexes = new TreeMap<String, Info>();
  }

  public static final class Info {
    public String className, attribute, crs, axes = "C1,C2";
    public long root, rootLength, count;
    public String packing = "str";
    public int leafLayout;
  }

  public static final class Entry {
    public BoundingBox box;
    public byte[] location;
    public long child, childLength;
  }

  public static final class Node {
    public List<Entry> entries = new ArrayList<Entry>();
  }

  private static String key(String cls, String attr) {
    return cls + "\0" + attr;
  }

  public static Manifest manifest(IbxContainer c) throws IOException {
    if (c.spatialRoot() == 0) return new Manifest();
    Frames.Frame f = c.frameStore().read(c.spatialRef());
    if (f.type != Frames.SPATIAL_MANIFEST) throw new IOException("Invalid spatial manifest");
    return Cbor.read(f.data, Manifest.class);
  }

  public static void add(Path path, String cls, String attr, String explicitCrs) throws Exception {
    add(path, cls, attr, explicitCrs, new SpatialIndexOptions());
  }

  public static void add(
      Path path, String cls, String attr, String explicitCrs, SpatialIndexOptions options)
      throws Exception {
    options.validate();
    Path parent = path.toAbsolutePath().getParent(),
        temp = Files.createTempDirectory(parent, ".ibx-spatial-"),
        output = Files.createTempFile(parent, ".ibx-spatial-", ".tmp");
    try {
      try (IbxContainer c = IbxContainer.open(path);
          ExternalSort entries = new ExternalSort(temp, 16L * 1024 * 1024)) {
        if (!c.metadata().classes.containsKey(cls))
          throw new IOException("Unknown spatial class " + cls);
        String known = c.metadata().geometryCrs.get(cls + "." + attr);
        if (known == null) throw new IOException("Unknown geometry attribute " + cls + "." + attr);
        String crs = explicitCrs != null ? explicitCrs : known;
        if (crs == null || crs.trim().isEmpty())
          throw new IOException("An unambiguous CRS is required; supply --crs");
        if (explicitCrs != null
            && !known.isEmpty()
            && !normalize(known).equals(normalize(explicitCrs)))
          throw new IOException("Explicit CRS conflicts with model CRS");
        TransferMetadata.GeometryDescriptor descriptor =
            c.metadata().geometries.get(cls + "." + attr);
        int leafLayout =
            descriptor != null && "CoordType".equals(descriptor.type)
                ? SpatialPageCodec.POINT
                : SpatialPageCodec.RECTANGLE;
        long count = 0;
        String domainSignature = null;
        try (Fragment fragment = c.getClass(cls);
            CloseableIterator<Location> refs = fragment.locations()) {
          while (refs.hasNext()) {
            Location loc = refs.next();
            if (loc.chunkOffset == 0) continue;
            BasketContext basket = c.basket(loc);
            if (!basket.domains.isEmpty()
                && explicitCrs == null
                && !"wkb".equals(c.metadata().geometryEncoding))
              throw new IOException(
                  "Basket generic-domain assignments require an explicit verified CRS");
            String signature = new TreeMap<String, String>(basket.domains).toString();
            if (domainSignature == null) domainSignature = signature;
            else if (!domainSignature.equals(signature))
              throw new IOException(
                  "Mixed basket coordinate-domain assignments cannot share an index");
            try (ObjectCursor objects = c.objects(loc)) {
              int ordinal = 0;
              while (objects.hasNext()) {
                BoundingBox box;
                if ("wkb".equals(c.metadata().geometryEncoding))
                  box =
                      new ch.interlis.ibx.codec.ObjectCodec(c.metadata(), true)
                          .geometryBounds(objects.nextRecord(), attr);
                else box = GeometryBounds.attribute(objects.next(), attr);
                if (box != null) {
                  Entry e = new Entry();
                  if (leafLayout == SpatialPageCodec.POINT) {
                    double x = Math.nextUp(box.minX), y = Math.nextUp(box.minY);
                    if (x != Math.nextDown(box.maxX) || y != Math.nextDown(box.maxY))
                      throw new IOException("Point index requires single-coordinate XY bounds");
                    box = new BoundingBox(x, y, x, y);
                  }
                  e.box = box;
                  if (leafLayout == SpatialPageCodec.POINT
                      && (box.minX != box.maxX || box.minY != box.maxY))
                    throw new IOException("Point index requires degenerate XY bounds");
                  e.location =
                      new Location(
                              loc.chunkOffset,
                              loc.basketOffset,
                              loc.basketPosition,
                              loc.chunkId,
                              ordinal)
                          .lengths(loc.chunkLength, loc.basketLength)
                          .bytes();
                  entries.add(sortKey(box.minX) + FilesEx.number(count), Cbor.bytes(e));
                  count++;
                }
                ordinal++;
              }
            }
          }
        }
        Files.copy(path, output, StandardCopyOption.REPLACE_EXISTING);
        try (RandomAccessFile out = new RandomAccessFile(output.toFile(), "rw")) {
          out.setLength(out.length() - Frames.FOOTER_SIZE);
          out.seek(out.length());
          long root;
          try (CloseableIterator<ExternalSort.Entry> sorted = entries.finish()) {
            root = build(out, sorted, temp, options, leafLayout);
          }
          Manifest manifest = manifest(c);
          Info info = new Info();
          info.className = cls;
          info.attribute = attr;
          info.crs = crs;
          info.root = root;
          info.rootLength = FrameRef.at(out, root).length;
          info.count = count;
          info.packing = options.packing;
          info.leafLayout = leafLayout;
          manifest.indexes.put(key(cls, attr), info);
          long manifestOffset = Frames.write(out, Frames.SPATIAL_MANIFEST, Cbor.bytes(manifest));
          Frames.footer(out, c.indexRoot(), manifestOffset);
          out.getFD().sync();
        }
      }
      FilesEx.publish(output, path.toAbsolutePath(), true);
    } finally {
      Files.deleteIfExists(output);
      FilesEx.deleteTree(temp);
    }
  }

  private static String normalize(String s) {
    return s.toUpperCase(Locale.ROOT).replace(" ", "");
  }

  private static String sortKey(double value) {
    long bits = Double.doubleToLongBits(value);
    bits = bits < 0 ? ~bits : bits ^ Long.MIN_VALUE;
    return String.format(Locale.ROOT, "%016x", bits);
  }

  private static Entry writeNode(RandomAccessFile out, Node node, int layout) throws IOException {
    long offset =
        Frames.write(
            out,
            layout == SpatialPageCodec.BRANCH ? Frames.SPATIAL_BRANCH : Frames.SPATIAL_LEAF,
            SpatialPageCodec.encode(node, layout));
    Entry ref = new Entry();
    ref.child = offset;
    ref.childLength = FrameRef.at(out, offset).length;
    for (Entry e : node.entries) {
      BoundingBox b =
          layout == SpatialPageCodec.POINT
              ? new BoundingBox(
                  Math.nextDown(e.box.minX),
                  Math.nextDown(e.box.minY),
                  Math.nextUp(e.box.maxX),
                  Math.nextUp(e.box.maxY))
              : e.box;
      if (ref.box == null) ref.box = new BoundingBox(b.minX, b.minY, b.maxX, b.maxY);
      else ref.box.expand(b);
    }
    return ref;
  }

  private static final int PAGE_BYTES = 16 * 1024;

  private static double center(double low, double high) {
    return low / 2 + high / 2;
  }

  /** Each level is independently sorted; only a stripe sorter and one node are resident. */
  private static long build(
      RandomAccessFile out,
      CloseableIterator<ExternalSort.Entry> input,
      Path temp,
      SpatialIndexOptions options,
      int leafLayout)
      throws IOException {
    Path current = Files.createTempFile(temp, "spatial-level-", ".run");
    try (DataOutputStream data =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(current)))) {
      while (input.hasNext()) ExternalSort.write(data, input.next());
    }
    boolean leaf = true;
    while (true) {
      Path next = Files.createTempFile(temp, "spatial-level-", ".run");
      long count = 0;
      int layout = leaf ? leafLayout : SpatialPageCodec.BRANCH;
      int capacity = (PAGE_BYTES - SpatialPageCodec.HEADER) / SpatialPageCodec.entrySize(layout);
      try (ExternalSort x = new ExternalSort(temp, 16L * 1024 * 1024)) {
        try (DataInputStream in =
            new DataInputStream(new BufferedInputStream(Files.newInputStream(current)))) {
          ExternalSort.Entry record;
          while ((record = ExternalSort.read(in)) != null) {
            Entry entry = Cbor.read(record.value, Entry.class);
            double coordinate =
                "str".equals(options.packing)
                    ? center(entry.box.minX, entry.box.maxX)
                    : entry.box.minX;
            x.add(sortKey(coordinate) + FilesEx.number(count++), record.value);
          }
        }
        long pages = Math.max(1, (count + capacity - 1) / capacity);
        long stripes = "str".equals(options.packing) ? (long) Math.ceil(Math.sqrt(pages)) : 1;
        long stripeCount = Math.max(1, (count + stripes - 1) / stripes);
        try (CloseableIterator<ExternalSort.Entry> sorted = x.finish();
            DataOutputStream refs =
                new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(next)))) {
          NodeWriter writer = new NodeWriter(out, refs, layout);
          if ("x".equals(options.packing)) {
            while (sorted.hasNext()) writer.add(Cbor.read(sorted.next().value, Entry.class));
          } else {
            while (sorted.hasNext()) {
              try (ExternalSort y = new ExternalSort(temp, 16L * 1024 * 1024)) {
                for (long i = 0; i < stripeCount && sorted.hasNext(); i++) {
                  ExternalSort.Entry record = sorted.next();
                  Entry entry = Cbor.read(record.value, Entry.class);
                  y.add(sortKey(center(entry.box.minY, entry.box.maxY)) + record.key, record.value);
                }
                try (CloseableIterator<ExternalSort.Entry> ys = y.finish()) {
                  while (ys.hasNext()) writer.add(Cbor.read(ys.next().value, Entry.class));
                }
              }
              writer.flush();
            }
          }
          writer.flush();
          if (writer.count == 0) writer.write();
          count = writer.count;
        }
      }
      Files.delete(current);
      current = next;
      if (count == 1) {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(current))) {
          return Cbor.read(ExternalSort.read(in).value, Entry.class).child;
        } finally {
          Files.delete(current);
        }
      }
      leaf = false;
    }
  }

  private static final class NodeWriter {
    final RandomAccessFile out;
    final DataOutputStream refs;
    final int layout;
    Node node = new Node();
    long count;

    NodeWriter(RandomAccessFile out, DataOutputStream refs, int layout) {
      this.out = out;
      this.refs = refs;
      this.layout = layout;
    }

    void add(Entry entry) throws IOException {
      node.entries.add(entry);
      if (SpatialPageCodec.HEADER + node.entries.size() * SpatialPageCodec.entrySize(layout)
          > PAGE_BYTES) {
        node.entries.remove(node.entries.size() - 1);
        write();
        node.entries.add(entry);
      }
    }

    void flush() throws IOException {
      if (!node.entries.isEmpty()) write();
    }

    void write() throws IOException {
      ExternalSort.write(
          refs, new ExternalSort.Entry("", Cbor.bytes(writeNode(out, node, layout))));
      count++;
      node = new Node();
    }
  }

  public static Fragment query(IbxContainer c, String cls, String attr, BoundingBox box)
      throws IOException {
    Info info = manifest(c).indexes.get(key(cls, attr));
    if (info == null) throw new IOException("No spatial index for " + cls + "." + attr);
    if (info.leafLayout != SpatialPageCodec.RECTANGLE && info.leafLayout != SpatialPageCodec.POINT)
      throw new IOException("Unsupported spatial leaf layout " + info.leafLayout);
    return new Fragment(
        c,
        () -> candidates(c, info, box),
        "bbox candidates "
            + cls
            + "."
            + attr
            + " ["
            + box.minX
            + ","
            + box.minY
            + ","
            + box.maxX
            + ","
            + box.maxY
            + "] "
            + info.crs);
  }

  private static CloseableIterator<Location> candidates(IbxContainer c, Info info, BoundingBox box)
      throws IOException {
    Path temp = Files.createTempDirectory("ibx-candidates-");
    ExternalSort sorted = new ExternalSort(temp, 4L * 1024 * 1024);
    try {
      visit(c, new FrameRef(info.root, info.rootLength), box, sorted, 0, info.leafLayout);
      final CloseableIterator<ExternalSort.Entry> it = sorted.finish();
      return new CloseableIterator<Location>() {
        public boolean hasNext() {
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
          try {
            it.close();
            sorted.close();
          } finally {
            FilesEx.deleteTree(temp);
          }
        }
      };
    } catch (IOException | RuntimeException e) {
      sorted.close();
      FilesEx.deleteTree(temp);
      throw e;
    }
  }

  private static void visit(
      IbxContainer c,
      FrameRef reference,
      BoundingBox box,
      ExternalSort sorted,
      int depth,
      int leafLayout)
      throws IOException {
    if (depth > 64) throw new IOException("Spatial tree excessive depth");
    reference.validate(c.size() - Frames.FOOTER_SIZE);
    long offset = reference.offset;
    Frames.Frame f = c.frameStore().read(reference);
    if (f.type != Frames.SPATIAL_LEAF && f.type != Frames.SPATIAL_BRANCH)
      throw new IOException("Invalid spatial node");
    Node n =
        SpatialPageCodec.decode(
            f.data,
            f.type == Frames.SPATIAL_BRANCH ? SpatialPageCodec.BRANCH : leafLayout,
            offset,
            c.size() - Frames.FOOTER_SIZE);
    // The new fanout changes leaf boundaries. Fetch adjacent matching sibling pages
    // together over HTTP, using the existing bounded cache and range coalescing.
    if (f.type == Frames.SPATIAL_BRANCH) {
      List<FrameRef> matches = new ArrayList<FrameRef>();
      for (Entry e : n.entries)
        if (e.box.intersects(box)) matches.add(new FrameRef(e.child, e.childLength));
      c.frameStore().prefetchSpatial(matches);
    }
    for (Entry e : n.entries) {
      if (e.box == null) throw new IOException("Missing spatial bounds");
      try {
        new BoundingBox(e.box.minX, e.box.minY, e.box.maxX, e.box.maxY);
      } catch (IllegalArgumentException invalid) {
        throw new IOException("Invalid spatial bounds", invalid);
      }
      if (!e.box.intersects(box)) continue;
      if (f.type == Frames.SPATIAL_LEAF) {
        if (e.location == null || Location.decode(e.location).ordinal < 0)
          throw new IOException("Invalid spatial location");
        Location loc = Location.decode(e.location);
        sorted.add(
            FilesEx.number(loc.basketPosition)
                + FilesEx.number(loc.chunkId)
                + FilesEx.number(loc.ordinal),
            loc.bytes());
      } else {
        if (e.child < Frames.HEADER_SIZE || e.child >= offset)
          throw new IOException("Invalid/cyclic spatial pointer");
        visit(c, new FrameRef(e.child, e.childLength), box, sorted, depth + 1, leafLayout);
      }
    }
  }
}
