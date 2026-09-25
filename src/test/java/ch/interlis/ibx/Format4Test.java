package ch.interlis.ibx;

import static org.junit.Assert.*;

import ch.interlis.ibx.api.*;
import ch.interlis.ibx.benchmark.RangeServer;
import ch.interlis.ibx.container.*;
import ch.interlis.ibx.index.Keys;
import ch.interlis.ibx.spatial.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class Format4Test {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void independentBinaryFixtureAndWriterAgree() throws Exception {
    byte[] fixture = Files.readAllBytes(Paths.get("src/test/resources/format4/binary-index.ibx"));
    Path output = temp.getRoot().toPath().resolve("fixture.ibx");
    try (ch.interlis.ibx.index.ExternalSort sort =
            new ch.interlis.ibx.index.ExternalSort(temp.getRoot().toPath(), 16384, true);
        RandomAccessFile out = new RandomAccessFile(output.toFile(), "rw")) {
      Frames.header(out);
      sort.add("F\0" + 0, new byte[] {1});
      sort.add("F\0" + 1, new byte[] {2});
      try (CloseableIterator<ch.interlis.ibx.index.ExternalSort.Entry> entries =
          sort.finish()) {
        long root =
            ch.interlis.ibx.index.BTree.build(out, entries, temp.getRoot().toPath());
        Frames.footer(out, root, 0);
      }
    }
    assertArrayEquals(fixture, Files.readAllBytes(output));
    ReadMetrics m = new ReadMetrics();
    try (ch.interlis.ibx.remote.LocalSource source =
        new ch.interlis.ibx.remote.LocalSource(output, m)) {
      long[] roots = Frames.footer(source);
      ch.interlis.ibx.index.BTree tree =
          new ch.interlis.ibx.index.BTree(
              new FrameStore(source, m, 1000), new FrameRef(roots[0], roots[2]));
      assertArrayEquals(new byte[] {2}, tree.get("F\0" + 1));
    }
  }

  @Test
  public void legacyHeadersAreExplicitlyRejected() throws Exception {
    for (int version = 1; version <= 3; version++) {
      ByteBuffer bytes = ByteBuffer.allocate(16).putLong(0x494c49434f4e5431L).putInt(version).putInt(0);
      try {
        Frames.checkHeader(new DataInputStream(new ByteArrayInputStream(bytes.array())));
        fail();
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("neu erstellen"));
      }
    }
  }

  @Test
  public void binaryKeysAndAddressesHaveFixedWireEncoding() throws Exception {
    assertArrayEquals(new byte[] {'F', 2, 0, 0, 0, 0, 0, 0, 1, 0}, Keys.encode("F\0" + 256));
    assertArrayEquals(new byte[] {'O', 1, 'a', 0, 0}, Keys.encode("O\0a"));
    assertTrue(Keys.compare(Keys.encode("F\0" + Long.MAX_VALUE), Keys.encode("F\0" + 256)) > 0);
    Location loc = new Location(16, 80, 3, 5, 7).lengths(64, 32);
    byte[] bytes = loc.bytes();
    assertEquals(53, bytes.length);
    ByteBuffer b = ByteBuffer.wrap(bytes);
    assertEquals(1, b.get());
    assertEquals(16, b.getLong());
    assertEquals(64, b.getLong());
    assertEquals(80, b.getLong());
    assertEquals(32, b.getLong());
    assertEquals(3, b.getLong());
    assertEquals(5, b.getLong());
    assertEquals(7, b.getInt());
    assertEquals(7, Location.decode(bytes).ordinal);
  }

  @Test
  public void maliciousFrameLengthRejectedBeforeAllocation() throws Exception {
    byte[] bytes =
        ByteBuffer.allocate(16)
            .putInt(Frames.CHUNK)
            .putLong(Integer.MAX_VALUE - 64)
            .putInt(0)
            .array();
    try {
      Frames.decode(bytes, new FrameRef(16, 16));
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("length"));
    }
    try {
      new FrameRef(Long.MAX_VALUE - 2, 32).validate(Long.MAX_VALUE);
      fail();
    } catch (IOException expected) {
    }
  }

  private Path create(String encoding, boolean order) throws Exception {
    WriterOptions w = new WriterOptions();
    w.modelFiles.add(ContainerTest.fixture("Tiny.ili").toString());
    w.geometryEncoding = encoding;
    w.chunkSize = 1;
    w.geometryCrs.put("Tiny.Data.Item.point", "EPSG:2056");
    w.geometryCrs.put("Tiny.Data.Item.line", "EPSG:2056");
    if (order) w.spatialOrder.put("Tiny.Data.Item", "point");
    Path target = temp.getRoot().toPath().resolve(encoding + order + ".ibx");
    ContainerWriter.create(ContainerTest.fixture("tiny.xtf"), target, w);
    return target;
  }

  @Test
  public void knownFramesUseOneRequestAndPrefetchSharesCache() throws Exception {
    Path file = create("wkb", false);
    try (RangeServer server = new RangeServer(file);
        IbxContainer c = IbxContainer.open(server.uri())) {
      assertEquals(4, c.metrics().requests); // header, footer, metadata header and payload
      FrameRef root = c.indexRef();
      c.clearCache();
      long before = c.metrics().requests;
      c.frameStore().read(root);
      assertEquals(1, c.metrics().requests - before);
      List<FrameRef> refs = new ArrayList<FrameRef>();
      try (Fragment f = c.getClass("Tiny.Data.Item");
          CloseableIterator<Location> it = f.locations()) {
        while (it.hasNext()) {
          Location l = it.next();
          refs.add(new FrameRef(l.chunkOffset, l.chunkLength));
        }
      }
      c.clearCache();
      before = c.metrics().requests;
      c.frameStore().prefetch(refs, 4096, 1024 * 1024);
      assertEquals(1, c.metrics().requests - before);
      for (FrameRef ref : refs) c.frameStore().read(ref);
      assertEquals(1, c.metrics().requests - before);
      assertTrue(c.metrics().maxCacheBytes <= 32L * 1024 * 1024);
      c.clearCache();
      server.mode = "changed";
      try {
        c.frameStore().prefetch(refs, 4096, 1024 * 1024);
        fail();
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("changed"));
      }
    }
  }

  @Test
  public void mergedCorruptionNeverCachesAnUnverifiedBatch() throws Exception {
    Path path = temp.getRoot().toPath().resolve("corrupt-batch.ibx");
    FrameRef first, second;
    try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
      Frames.header(file);
      first = new FrameRef(Frames.write(file, Frames.METADATA, new byte[] {1, 2, 3}), 19);
      second = new FrameRef(Frames.write(file, Frames.METADATA, new byte[] {4, 5, 6}), 19);
      long root = Frames.write(file, Frames.LEAF, new byte[] {0, 0, 0, 0});
      Frames.footer(file, root, 0);
      file.seek(second.offset + Frames.FRAME_HEADER);
      file.writeByte(99);
    }
    try (RangeServer server = new RangeServer(path)) {
      ReadMetrics metrics = new ReadMetrics();
      try (ch.interlis.ibx.remote.HttpRangeSource source =
          new ch.interlis.ibx.remote.HttpRangeSource(
              server.uri(), new RemoteOptions(), metrics)) {
        FrameStore store = new FrameStore(source, metrics, 64);
        try {
          store.prefetch(Arrays.asList(first, second), 4096, 1024);
          fail();
        } catch (IOException expected) {
          assertTrue(expected.getMessage().contains("checksum"));
        }
        long before = metrics.requests;
        store.read(first);
        assertEquals(1, metrics.requests - before);
        assertTrue(metrics.maxCacheBytes <= 64);
        store.clear();
        before = metrics.requests;
        // Frames exceeding this cache's budget are read on demand and never cached.
        FrameStore tiny = new FrameStore(source, metrics, 16);
        tiny.prefetch(Collections.singletonList(first), 4096, 1024);
        assertEquals(before, metrics.requests);
        tiny.read(first);
        tiny.read(first);
        assertEquals(2, metrics.requests - before);
      }
    }
  }

  @Test
  public void fidRangesAndBothPackingsPreserveResults() throws Exception {
    for (String encoding : Arrays.asList("iom", "wkb")) {
      Path path = create(encoding, true);
      for (String packing : Arrays.asList("x", "str")) {
        SpatialIndexOptions options = new SpatialIndexOptions();
        options.packing = packing;
        SpatialIndex.add(path, "Tiny.Data.Item", "point", "EPSG:2056", options);
        try (IbxContainer c = IbxContainer.open(path)) {
          assertEquals(
              packing, SpatialIndex.manifest(c).indexes.values().iterator().next().packing);
          for (long fid = 0; fid < 3; fid++) {
            c.clearCache();
            c.metrics().reset();
            try (Fragment f = c.getFid(fid);
                Stream<SelectedObject> objects = f.objects()) {
              assertEquals(1, objects.count());
              assertEquals(1, c.metrics().chunksRead);
            }
          }
          for (long fid : new long[] {-1, 3, Long.MAX_VALUE})
            try (Fragment f = c.getFid(fid);
                Stream<SelectedObject> objects = f.objects()) {
              assertEquals(0, objects.count());
            }
          try (Fragment f =
                  c.querySpatialCandidates(
                      "Tiny.Data.Item", "point", new BoundingBox(0, 0, 100, 100));
              Stream<SelectedObject> objects = f.objects()) {
            assertEquals(
                Arrays.asList("o1", "o2"),
                objects.map(x -> x.getObject().getobjectoid()).collect(Collectors.toList()));
          }
        }
      }
    }
  }

  @Test
  public void hilbertCornerOrientationAndUniqueness() {
    long max = 0xffffffffL;
    assertEquals(0, SpatialOrder.hilbert(0, 0));
    assertEquals(0x5555555555555555L, SpatialOrder.hilbert(0, max));
    assertEquals(0xaaaaaaaaaaaaaaaaL, SpatialOrder.hilbert(max, max));
    assertEquals(-1L, SpatialOrder.hilbert(max, 0));
    Set<Long> keys = new HashSet<Long>();
    for (int x = 0; x < 32; x++)
      for (int y = 0; y < 32; y++) assertTrue(keys.add(SpatialOrder.hilbert(x, y)));
  }

  @Test
  public void spatialOrderingAndMultiPageStrAgreeWithIndependentPointScan() throws Exception {
    Path source = temp.getRoot().toPath().resolve("grid.xtf");
    String fixture = new String(Files.readAllBytes(ContainerTest.fixture("tiny.xtf")), "UTF-8");
    StringBuilder xml =
        new StringBuilder(fixture.substring(0, fixture.indexOf("<ili:datasection>")));
    xml.append("<ili:datasection><Tiny:Data ili:bid=\"grid\">");
    for (int i = 1599; i >= 0; i--) {
      int x = i % 40, y = i / 40;
      xml.append("<Tiny:Item ili:tid=\"p")
          .append(i)
          .append("\"><Tiny:point><geom:coord><geom:c1>")
          .append(x)
          .append("</geom:c1><geom:c2>")
          .append(y)
          .append("</geom:c2></geom:coord></Tiny:point></Tiny:Item>");
    }
    xml.append("<Tiny:Item ili:tid=\"missing\"/></Tiny:Data></ili:datasection></ili:transfer>");
    Files.write(source, xml.toString().getBytes("UTF-8"));
    for (String encoding : Arrays.asList("iom", "wkb")) {
      WriterOptions w = new WriterOptions();
      w.modelFiles.add(ContainerTest.fixture("Tiny.ili").toString());
      w.geometryEncoding = encoding;
      w.geometryCrs.put("Tiny.Data.Item.point", "EPSG:2056");
      w.geometryCrs.put("Tiny.Data.Item.line", "EPSG:2056");
      w.spatialOrder.put("Tiny.Data.Item", "point");
      w.sortMemoryBytes = 16384;
      w.chunkSize = 4096;
      Path file = temp.getRoot().toPath().resolve("grid-" + encoding + ".ibx");
      ContainerWriter.create(source, file, w);
      List<String> order;
      try (IbxContainer c = IbxContainer.open(file);
          Fragment f = c.getClass("Tiny.Data.Item");
          Stream<SelectedObject> objects = f.objects()) {
        order = objects.map(o -> o.getObject().getobjectoid()).collect(Collectors.toList());
        assertEquals("p0", order.get(0));
        assertEquals("missing", order.get(order.size() - 1));
        assertEquals(1601, new HashSet<String>(order).size());
        for (long fid : new long[] {0, 1, 39, 40, 799, 1599, 1600}) {
          c.clearCache();
          c.metrics().reset();
          try (Fragment selected = c.getFid(fid);
              Stream<SelectedObject> values = selected.objects()) {
            assertEquals(order.get((int) fid), values.findFirst().get().getObject().getobjectoid());
            assertEquals(1, c.metrics().chunksRead);
          }
        }
        StorageDiagnostics stats = StorageDiagnostics.inspect(c);
        long total = stats.headerFooterBytes;
        for (long n : stats.sectionBytes.values()) total += n;
        assertEquals(Files.size(file), total);
        long pages = stats.sharedIndexPageBytes;
        for (long n : stats.keyBytes.values()) pages += n;
        for (long n : stats.valueBytes.values()) pages += n;
        assertEquals(
            stats.sectionBytes.get(Frames.LEAF) + stats.sectionBytes.get(Frames.BRANCH), pages);
      }
      for (String packing : Arrays.asList("x", "str")) {
        SpatialIndexOptions opts = new SpatialIndexOptions();
        opts.packing = packing;
        SpatialIndex.add(file, "Tiny.Data.Item", "point", "EPSG:2056", opts);
        try (IbxContainer c = IbxContainer.open(file)) {
          for (int q = 0; q < 8; q++) {
            final int lo = q * 3, hi = lo + 7;
            List<String> expected =
                order.stream()
                    .filter(
                        id -> {
                          if (id.equals("missing")) return false;
                          int n = Integer.parseInt(id.substring(1));
                          return n % 40 >= lo && n % 40 <= hi && n / 40 >= lo && n / 40 <= hi;
                        })
                    .collect(Collectors.toList());
            try (Fragment f =
                    c.querySpatialCandidates(
                        "Tiny.Data.Item", "point", new BoundingBox(lo, lo, hi, hi));
                Stream<SelectedObject> objects = f.objects()) {
              assertEquals(
                  expected,
                  objects.map(o -> o.getObject().getobjectoid()).collect(Collectors.toList()));
            }
          }
        }
      }
    }
  }
}
