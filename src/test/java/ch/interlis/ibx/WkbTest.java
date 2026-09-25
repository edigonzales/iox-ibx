package ch.interlis.ibx;

import static org.junit.Assert.*;

import ch.interlis.ibx.api.*;
import ch.interlis.ibx.container.*;
import ch.interlis.ibx.geometry.*;
import ch.interlis.ibx.spatial.*;
import ch.interlis.iom.*;
import ch.interlis.iom_j.Iom_jObject;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class WkbTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  static IomObject coord(String tag, String x, String y, String z) {
    IomObject o = new Iom_jObject(tag, null);
    o.setattrvalue("C1", x);
    o.setattrvalue("C2", y);
    if (z != null) o.setattrvalue("C3", z);
    return o;
  }

  static IomObject line(boolean z) {
    IomObject o = new Iom_jObject("POLYLINE", null), s = new Iom_jObject("SEGMENTS", null);
    o.addattrobj("sequence", s);
    s.addattrobj("segment", coord("COORD", "-1", "0", z ? "10" : null));
    IomObject a = coord("ARC", "1", "0", z ? "20" : null);
    a.setattrvalue("A1", "0");
    a.setattrvalue("A2", "1");
    s.addattrobj("segment", a);
    return o;
  }

  @Test
  public void curvesRetainControlsAndInterpolateHeight() throws Exception {
    for (boolean z : new boolean[] {false, true}) {
      byte[] bytes = IomGeometry.encode(line(z), false);
      Geometry g = IsoWkb.read(bytes);
      assertEquals(z ? 1009 : 9, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(1));
      assertEquals(8, g.parts.get(0).type);
      assertEquals(3, g.parts.get(0).points.size());
      if (z) assertEquals(15, g.parts.get(0).points.get(1)[2], 0);
      assertTrue(GeometryEnvelope.bounds(g).intersects(new BoundingBox(0, 1, 0, 1)));
      IomObject result =
          IomGeometry.toIom(g, "POLYLINE").getattrobj("sequence", 0).getattrobj("segment", 1);
      assertEquals("1.0", result.getattrvalue("A2"));
      assertNull(result.getattrvalue("A3"));
    }
  }

  @Test
  public void strictPrecisionAndRadius() throws Exception {
    for (IomObject bad :
        Arrays.asList(coord("COORD", "1.00000000000000001", "0", null), line(false))) {
      if (bad.getobjecttag().equals("POLYLINE"))
        bad.getattrobj("sequence", 0).getattrobj("segment", 1).setattrvalue("R", "1");
      try {
        IomGeometry.encode(bad, false);
        fail();
      } catch (IOException expected) {
      }
    }
    IomGeometry.encode(coord("COORD", "1.2300E2", "-0.000", null), false);
  }

  @Test
  public void malformedCountsTypesAndTrailingBytes() throws Exception {
    for (byte[] bytes :
        Arrays.asList(
            new byte[] {1, 2, 0, 0, 0, -1, -1, -1, 127},
            new byte[] {1, 8, 0, 0, 0},
            new byte[] {2, 1, 0, 0, 0},
            new byte[] {1, 1, 0, 0, -128})) {
      try {
        IsoWkb.read(bytes);
        fail();
      } catch (IOException expected) {
      }
    }
    byte[] valid = IomGeometry.encode(coord("COORD", "1", "2", null), false);
    try {
      IsoWkb.read(Arrays.copyOf(valid, valid.length + 1));
      fail();
    } catch (IOException expected) {
    }
  }

  static WriterOptions options() {
    WriterOptions w = new WriterOptions();
    w.geometryEncoding = "wkb";
    w.chunkSize = 1;
    w.modelFiles.add(Paths.get("src/test/resources/Tiny.ili").toAbsolutePath().toString());
    w.geometryCrs.put("Tiny.Data.Item.point", "EPSG:2056");
    w.geometryCrs.put("Tiny.Data.Item.line", "EPSG:2056");
    return w;
  }

  @Test
  public void containerRoundtripAndDirectGis() throws Exception {
    Path output = temp.getRoot().toPath().resolve("data.ibx");
    ContainerWriter.create(Paths.get("src/test/resources/tiny.xtf"), output, options());
    SpatialIndex.add(output, "Tiny.Data.Item", "point", null);
    try (IbxContainer c = IbxContainer.open(output);
        GisLayer layer = c.openLayer("Tiny.Data.Item.point")) {
      assertEquals(4, c.metadata().formatVersion());
      assertEquals(2, c.layers().size());
      List<GisFeature> features;
      try (Stream<GisFeature> s = layer.features()) {
        features = s.collect(Collectors.toList());
      }
      assertEquals(3, features.size());
      assertNull(features.get(2).getWkb());
      assertEquals(
          new java.math.BigDecimal("1.23450"), features.get(0).getAttributes().get("amount"));
      c.clearCache();
      long chunks = c.metrics().chunkBytes;
      assertEquals("o2", layer.getFeature(features.get(1).getFid()).get().getTid());
      assertTrue(c.metrics().chunkBytes > chunks);
      assertFalse(layer.getFeature(999).isPresent());
      try (Stream<GisFeature> s = layer.queryCandidates(new BoundingBox(0, 0, 0, 0))) {
        assertEquals("o1", s.findFirst().get().getTid());
      }
      Path xtf = temp.getRoot().toPath().resolve("roundtrip.xtf");
      try (OutputStream out = Files.newOutputStream(xtf)) {
        c.export(out);
      }
      assertTrue(new String(Files.readAllBytes(xtf), "UTF-8").contains("original comment"));
      try (Fragment f = c.getObject("o2");
          Stream<SelectedObject> s = f.objects()) {
        assertEquals(
            "ARC",
            s.findFirst()
                .get()
                .getObject()
                .getattrobj("line", 0)
                .getattrobj("sequence", 0)
                .getattrobj("segment", 1)
                .getobjecttag());
      }
    }
  }

  @Test
  public void nestedGeometryAndAtomicFailure() throws Exception {
    Path model = temp.getRoot().toPath().resolve("Tiny.ili"),
        input = temp.getRoot().toPath().resolve("nested.xtf"),
        output = temp.getRoot().toPath().resolve("nested.ibx");
    String ili =
        new String(Files.readAllBytes(Paths.get("src/test/resources/Tiny.ili")), "UTF-8")
            .replace("label : TEXT*100;", "label : TEXT*100; position : Coord;");
    Files.write(model, ili.getBytes("UTF-8"));
    String xml =
        new String(Files.readAllBytes(Paths.get("src/test/resources/tiny.xtf")), "UTF-8")
            .replace(
                "<Tiny:label>first</Tiny:label>",
                "<Tiny:label>first</Tiny:label><Tiny:position><geom:coord><geom:c1>12.3400</geom:c1><geom:c2>5E1</geom:c2></geom:coord></Tiny:position>");
    Files.write(input, xml.getBytes("UTF-8"));
    WriterOptions w = options();
    w.modelFiles.clear();
    w.modelFiles.add(model.toString());
    w.geometryCrs.put("Tiny.Data.Details.position", "EPSG:2056");
    ContainerWriter.create(input, output, w);
    try (IbxContainer c = IbxContainer.open(output);
        Fragment f = c.getObject("o1");
        Stream<SelectedObject> stream = f.objects()) {
      assertEquals(2, c.layers().size());
      assertFalse(c.metadata().dictionary.contains("COORD"));
      assertEquals(
          "12.34",
          stream
              .findFirst()
              .get()
              .getObject()
              .getattrobj("details", 0)
              .getattrobj("position", 0)
              .getattrvalue("C1"));
      try (GisLayer layer = c.openLayer("Tiny.Data.Item.point");
          Stream<GisFeature> a = layer.features();
          Stream<GisFeature> b = layer.features()) {
        assertEquals(a.findFirst().get().getFid(), b.findFirst().get().getFid());
        c.clearCache();
        c.metrics().reset();
        assertTrue(layer.getFeature(0).isPresent());
        assertEquals(1, c.metrics().chunksRead);
        try {
          layer.queryCandidates(new BoundingBox(0, 0, 0, 0));
          fail();
        } catch (IOException expected) {
        }
      }
    }
    byte[] original = Files.readAllBytes(output);
    w.overwrite = true;
    Files.write(input, xml.replace("12.3400", "12.340000000000000001").getBytes("UTF-8"));
    try {
      ContainerWriter.create(input, output, w);
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("BID=b1 TID=o1"));
      assertTrue(expected.getMessage().contains("Details.position"));
    }
    assertArrayEquals(original, Files.readAllBytes(output));
  }

  @Test
  public void iomFilesRequireExplicitConversion() throws Exception {
    WriterOptions w = options();
    w.geometryEncoding = "iom";
    Path output = temp.getRoot().toPath().resolve("iom.ibx");
    ContainerWriter.create(Paths.get("src/test/resources/tiny.xtf"), output, w);
    try (IbxContainer c = IbxContainer.open(output)) {
      try {
        c.layers();
        fail();
      } catch (IllegalStateException expected) {
      }
      Frames.Frame metadata = c.frameStore().read(Frames.HEADER_SIZE);
      assertTrue(
          ch.interlis.ibx.codec.Cbor.MAPPER
              .readTree(metadata.data)
              .has("geometryEncoding"));
    }
  }

  @Test
  public void unresolvedCrsFailsAndWrongDimensionsAreRejected() throws Exception {
    WriterOptions w = options();
    w.geometryCrs.clear();
    Path output = temp.getRoot().toPath().resolve("unknown.ibx");
    try {
      ContainerWriter.create(Paths.get("src/test/resources/tiny.xtf"), output, w);
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("Unresolved CRS"));
    }
    assertFalse(Files.exists(output));
    Geometry bad =
        Geometry.parts(
            4, 2, Arrays.asList(Geometry.points(1, 3, Arrays.asList(new double[] {1, 2, 3}))));
    try {
      IsoWkb.write(bad);
      fail();
    } catch (IOException expected) {
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < 66; i++) out.write(new byte[] {1, 11, 0, 0, 0, 1, 0, 0, 0});
    out.write(IomGeometry.encode(coord("COORD", "1", "2", null), false));
    try {
      IsoWkb.read(out.toByteArray());
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("nesting"));
    }
  }

  @Test
  public void directRemoteGisRejectsChangedSnapshot() throws Exception {
    Path output = temp.getRoot().toPath().resolve("remote.ibx");
    ContainerWriter.create(Paths.get("src/test/resources/tiny.xtf"), output, options());
    try (ch.interlis.ibx.benchmark.RangeServer server =
            new ch.interlis.ibx.benchmark.RangeServer(output);
        IbxContainer c = IbxContainer.open(server.uri());
        GisLayer layer = c.openLayer("Tiny.Data.Item.point")) {
      assertEquals("o1", layer.getFeature(0).get().getTid());
      c.clearCache();
      server.mode = "changed";
      try {
        layer.getFeature(1);
        fail();
      } catch (IOException | UncheckedIOException expected) {
        assertTrue(expected.getMessage().contains("changed"));
      }
    }
  }

  @Test
  public void basketCrsIsNotBorrowedFromAnEarlierBasket() throws Exception {
    WriterOptions w = options();
    w.geometryCrs.clear();
    Path model = temp.getRoot().toPath().resolve("Tiny.ili");
    String text = new String(Files.readAllBytes(Paths.get("src/test/resources/Tiny.ili")), "UTF-8");
    text =
        text.replace(
            "  TOPIC Data =",
            "    !!@CRS=EPSG:2056\n"
                + "    Known = COORD -10000.000 .. 10000.000, -10000.000 .. 10000.000;\n"
                + "  TOPIC Data =");
    Files.write(model, text.getBytes("UTF-8"));
    w.modelFiles.clear();
    w.modelFiles.add(model.toString());
    ch.interlis.ibx.iox.ModelBridge bridge =
        ch.interlis.ibx.iox.ModelBridge.load(
            Paths.get("src/test/resources/tiny.xtf"), w, temp.newFolder().toPath());
    bridge.metadata.geometries.get("Tiny.Data.Item.point").generic = true;
    IomObject o = new Iom_jObject("Tiny.Data.Item", "id");
    o.addattrobj("point", coord("COORD", "1", "2", null));
    BasketContext first = new BasketContext();
    first.domains.put("Tiny.Coord", "Tiny.Known");
    bridge.resolveGeometryCrs(o, first);
    assertEquals("EPSG:2056", bridge.metadata.geometries.get("Tiny.Data.Item.point").crs);
    try {
      bridge.resolveGeometryCrs(o, new BasketContext());
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("Unresolved CRS"));
    }
  }
}
