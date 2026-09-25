package ch.interlis.ibx;

import static org.junit.Assert.*;

import ch.interlis.ibx.api.*;
import ch.interlis.ibx.codec.ObjectCodec;
import ch.interlis.ibx.container.*;
import ch.interlis.ibx.spatial.*;
import ch.interlis.iom.*;
import ch.interlis.iom_j.Iom_jObject;
import ch.interlis.iox.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

@org.junit.runner.RunWith(org.junit.runners.Parameterized.class)
public class ContainerTest {
  @org.junit.runners.Parameterized.Parameters(name = "geometry={0}")
  public static java.util.Collection<Object[]> encodings() {
    return java.util.Arrays.asList(new Object[][] {{"iom"}, {"wkb"}});
  }

  @org.junit.runners.Parameterized.Parameter public String geometryEncoding;

  private void geometryOptions(WriterOptions w) {
    w.geometryEncoding = geometryEncoding;
    w.geometryCrs.put("Tiny.Data.Item.point", "EPSG:2056");
    w.geometryCrs.put("Tiny.Data.Item.line", "EPSG:2056");
  }

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  static Path fixture(String name) {
    return Paths.get("src/test/resources", name);
  }

  WriterOptions options() {
    WriterOptions o = new WriterOptions();
    geometryOptions(o);
    o.modelFiles.add(fixture("Tiny.ili").toAbsolutePath().toString());
    o.modelPaths.add(fixture("Tiny.ili").toAbsolutePath().getParent().toString());
    return o;
  }

  Path create(String numeric, int size) throws Exception {
    Path out = tmp.getRoot().toPath().resolve(UUID.randomUUID() + ".ibx");
    WriterOptions o = options();
    o.chunkSize = size;
    o.numericEncoding = numeric;
    ContainerWriter.create(fixture("tiny.xtf"), out, o);
    return out;
  }

  @Test
  public void roundtripAndIndependentFragmentViews() throws Exception {
    Path p = create("lexical", 256);
    try (IbxContainer c = IbxContainer.open(p)) {
      assertEquals("original comment", c.metadata().comment);
      try (Fragment f = c.getTopic("Tiny.Data")) {
        assertEquals(
            Arrays.asList("empty", "b1", "b2"),
            f.baskets().map(b -> b.bid).collect(Collectors.toList()));
        assertEquals(3, f.objects().count());
        assertEquals(3, f.objects().count());
      }
      try (Fragment f = c.getBasket("empty")) {
        assertEquals(1, f.baskets().count());
        assertEquals(0, f.objects().count());
      }
      try (Fragment f = c.getObject("absent")) {
        assertEquals(0, f.objects().count());
      }
      c.clearCache();
      c.metrics().reset();
      try (Fragment f = c.getObject("o1")) {
        SelectedObject selected = f.objects().findFirst().get();
        assertEquals("b1", selected.getBasket().bid);
        assertEquals("Grüezi", selected.getObject().getattrvalue("name"));
        assertEquals("1.23450", selected.getObject().getattrvalue("amount"));
        assertEquals("second", selected.getObject().getattrobj("details", 1).getattrvalue("label"));
      }
      assertEquals(1, c.metrics().chunksRead);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      c.export(out);
      String xml = out.toString("UTF-8");
      assertTrue(xml.contains("original comment"));
      assertTrue(xml.contains("empty"));
      assertTrue(xml.contains("geom:arc"));
      Path xtf = tmp.newFile("roundtrip.xtf").toPath();
      Files.write(xtf, out.toByteArray());
      Path verifyDir = tmp.newFolder().toPath();
      ch.interlis.ibx.iox.ModelBridge bridge =
          ch.interlis.ibx.iox.ModelBridge.load(fixture("tiny.xtf"), options(), verifyDir);
      assertTrue(
          ch.interlis.ibx.benchmark.RoundtripVerifier.verify(
                  fixture("tiny.xtf"), xtf, bridge, verifyDir)
              > 0);
      // Re-import exercises the reconstructed lightweight writer mapping.
      Path again = tmp.getRoot().toPath().resolve("again.ibx");
      ContainerWriter.create(xtf, again, options());
      try (IbxContainer copy = IbxContainer.open(again);
          Fragment f = copy.getClass("Tiny.Data.Item")) {
        assertEquals(3, f.objects().count());
      }
      ByteArrayOutputStream fragment = new ByteArrayOutputStream();
      try (Fragment f = c.getBasket("b1")) {
        c.exportFragment(f, fragment);
      }
      assertTrue(fragment.toString("UTF-8").contains("IBX fragment"));
    }
  }

  @Test
  public void decimalAndOversizedObjects() throws Exception {
    Path p = create("decimal", 1);
    try (IbxContainer c = IbxContainer.open(p);
        Fragment f = c.getObject("o1")) {
      assertEquals("1.23450", f.objects().findFirst().get().getObject().getattrvalue("amount"));
    }
  }

  @Test
  public void sequentialReaderNeverNeedsIndex() throws Exception {
    Path p = create("lexical", 1024);
    byte[] bytes = Files.readAllBytes(p);
    int cut;
    try (RandomAccessFile file = new RandomAccessFile(p.toFile(), "r")) {
      file.seek(Frames.HEADER_SIZE);
      while (true) {
        int type = file.readInt();
        long n = file.readLong();
        file.readInt();
        file.seek(file.getFilePointer() + n);
        if (type == Frames.END_TRANSFER) {
          cut = (int) file.getFilePointer();
          break;
        }
      }
    }
    IoxReader reader = IbxContainer.stream(new ByteArrayInputStream(Arrays.copyOf(bytes, cut)));
    int objects = 0, baskets = 0;
    try {
      IoxEvent e;
      while ((e = reader.read()) != null) {
        if (e instanceof ObjectEvent) objects++;
        if (e instanceof StartBasketEvent) baskets++;
      }
    } finally {
      reader.close();
    }
    assertEquals(3, objects);
    assertEquals(3, baskets);
  }

  @Test
  public void duplicateIdsAndOldVersionAreRejected() throws Exception {
    String text = new String(Files.readAllBytes(fixture("tiny.xtf")), "UTF-8");
    for (String bad :
        Arrays.asList(
            text.replace("ili:tid=\"o2\"", "ili:tid=\"o1\""),
            text.replace("ili:tid=\"o2\"", ""),
            text.replace("ili:bid=\"b2\"", ""),
            text.replace("ili:bid=\"b1\"", "ili:bid=\"b1\" ili:kind=\"INITIAL\""),
            text.replace("ili:bid=\"b2\"", "ili:bid=\"b1\""),
            text.replace(
                "http://www.interlis.ch/xtf/2.4/INTERLIS", "http://www.interlis.ch/INTERLIS2.3"),
            text.replace("ili:bid=\"b1\"", "ili:bid=\"b1\" ili:kind=\"UPDATE\""))) {
      Path input = tmp.newFile().toPath();
      Files.write(input, bad.getBytes("UTF-8"));
      Path output = tmp.getRoot().toPath().resolve(UUID.randomUUID() + ".ibx");
      try {
        ContainerWriter.create(input, output, options());
        fail("accepted bad input");
      } catch (Exception expected) {
        assertFalse(Files.exists(output));
      }
    }
  }

  @Test
  public void spatialSelectionAndFailedIndexPreserveCore() throws Exception {
    Path p = create("lexical", 1024);
    try (IbxContainer c = IbxContainer.open(p)) {
      try {
        c.querySpatialCandidates("Tiny.Data.Item", "point", new BoundingBox(0, 0, 1, 1));
        fail("missing index accepted");
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("index"));
      }
    }
    SpatialIndex.add(p, "Tiny.Data.Item", "point", "EPSG:2056");
    try (IbxContainer c = IbxContainer.open(p);
        Fragment f =
            c.querySpatialCandidates("Tiny.Data.Item", "point", new BoundingBox(0, 0, 10, 10))) {
      assertEquals(
          Arrays.asList("o1"),
          f.objects().map(o -> o.getObject().getobjectoid()).collect(Collectors.toList()));
    }
    SpatialIndex.add(p, "Tiny.Data.Item", "line", "EPSG:2056");
    try (IbxContainer c = IbxContainer.open(p);
        Fragment f =
            c.querySpatialCandidates(
                "Tiny.Data.Item", "line", new BoundingBox(-.1, .99, .1, 1.1))) {
      assertEquals("o2", f.objects().findFirst().get().getObject().getobjectoid());
    }
    byte[] before = Files.readAllBytes(p);
    try {
      SpatialIndex.add(p, "Tiny.Data.Item", "missing", "EPSG:2056");
      fail();
    } catch (IOException expected) {
      assertArrayEquals(before, Files.readAllBytes(p));
    }
  }

  @Test
  public void checksumAndTruncation() throws Exception {
    Path p = create("lexical", 1024);
    byte[] data = Files.readAllBytes(p);
    data[40] ^= 1;
    Path bad = tmp.newFile().toPath();
    Files.write(bad, data);
    try {
      IbxContainer.open(bad);
      fail();
    } catch (IOException expected) {
    }
    Files.write(bad, Arrays.copyOf(data, data.length - 1));
    try {
      IbxContainer.open(bad);
      fail();
    } catch (IOException expected) {
    }
  }

  @Test
  public void referenceAndRepeatedPrimitiveCodec() throws Exception {
    TransferMetadata m = new TransferMetadata();
    ObjectCodec codec = new ObjectCodec(m, false);
    Iom_jObject o = new Iom_jObject("Example", "o1");
    o.addattrvalue("values", "first");
    o.addattrvalue("values", "second");
    IomObject ref = o.addattrobj("link", "REF");
    ref.setobjectrefoid("outside");
    ref.setobjectrefbid("elsewhere");
    ref.setobjectreforderpos(5);
    IomObject result = codec.decode(codec.encode(o));
    assertEquals("second", result.getattrprim("values", 1));
    assertEquals("elsewhere", result.getattrobj("link", 0).getobjectrefbid());
    assertEquals(5, result.getattrobj("link", 0).getobjectreforderpos());
  }

  @Test
  public void alternativeChunkCompressionRoundtrips() throws Exception {
    for (String compression : Arrays.asList("deflate", "none")) {
      WriterOptions w = options();
      w.compression = compression;
      Path file = tmp.getRoot().toPath().resolve(compression + ".ibx");
      ContainerWriter.create(fixture("tiny.xtf"), file, w);
      try (IbxContainer c = IbxContainer.open(file);
          Fragment f = c.getObject("o1")) {
        assertEquals("Grüezi", f.objects().findFirst().get().getObject().getattrvalue("name"));
      }
    }
  }
}
