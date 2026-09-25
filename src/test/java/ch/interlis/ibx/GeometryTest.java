package ch.interlis.ibx;

import static org.junit.Assert.*;

import ch.interlis.ibx.api.BoundingBox;
import ch.interlis.ibx.spatial.GeometryBounds;
import ch.interlis.iom.IomObject;
import ch.interlis.iom_j.Iom_jObject;
import java.util.Random;
import org.junit.Test;

public class GeometryTest {
  private static IomObject coordinate(String tag, double x, double y) {
    IomObject o = new Iom_jObject(tag, null);
    o.setattrvalue("C1", Double.toString(x));
    o.setattrvalue("C2", Double.toString(y));
    return o;
  }

  @Test
  public void analyticCircleSamplesStayInsideArcBounds() throws Exception {
    Random random = new Random(20260915);
    for (int i = 0; i < 300; i++) {
      double cx = 2600000 + random.nextDouble() * 1000,
          cy = 1200000 + random.nextDouble() * 1000,
          r = 1 + random.nextDouble() * 1000,
          start = random.nextDouble() * Math.PI * 2,
          sweep = .05 + random.nextDouble() * 6.1;
      if (i % 2 == 0) sweep = -sweep;
      IomObject line = new Iom_jObject("SEGMENTS", null);
      line.addattrobj(
          "segment", coordinate("COORD", cx + r * Math.cos(start), cy + r * Math.sin(start)));
      IomObject arc =
          coordinate("ARC", cx + r * Math.cos(start + sweep), cy + r * Math.sin(start + sweep));
      arc.setattrvalue("A1", Double.toString(cx + r * Math.cos(start + sweep / 2)));
      arc.setattrvalue("A2", Double.toString(cy + r * Math.sin(start + sweep / 2)));
      line.addattrobj("segment", arc);
      BoundingBox box = GeometryBounds.bounds(line);
      IomObject polyline = new Iom_jObject("POLYLINE", null);
      polyline.addattrobj("sequence", line);
      BoundingBox wkbBox =
          ch.interlis.ibx.geometry.GeometryEnvelope.bounds(
              ch.interlis.ibx.geometry.IsoWkb.read(
                  ch.interlis.ibx.geometry.IomGeometry.encode(polyline, false)));
      for (int j = 0; j <= 200; j++) {
        double angle = start + sweep * j / 200,
            x = cx + r * Math.cos(angle),
            y = cy + r * Math.sin(angle);
        assertTrue(
            "WKB arc " + i + " sample " + j,
            x >= wkbBox.minX && x <= wkbBox.maxX && y >= wkbBox.minY && y <= wkbBox.maxY);
        assertTrue(
            "arc " + i + " sample " + j,
            x >= box.minX && x <= box.maxX && y >= box.minY && y <= box.maxY);
      }
    }
  }

  @Test
  public void heightsDoNotAffectSelectionAndInvalidGeometryFails() throws Exception {
    IomObject low = coordinate("COORD", 1, 2), high = coordinate("COORD", 1, 2);
    low.setattrvalue("C3", "-1000.123");
    high.setattrvalue("C3", "9000.456");
    BoundingBox a = GeometryBounds.bounds(low), b = GeometryBounds.bounds(high);
    assertEquals(a.minX, b.minX, 0);
    assertEquals(a.maxY, b.maxY, 0);
    assertTrue(a.intersects(new BoundingBox(1, 2, 1, 2)));
    IomObject owner = new Iom_jObject("Example", "id");
    assertNull(GeometryBounds.attribute(owner, "missing"));
    IomObject line = new Iom_jObject("SEGMENTS", null);
    line.addattrobj("segment", coordinate("COORD", 0, 0));
    IomObject arc = coordinate("ARC", 2, 0);
    arc.setattrvalue("A1", "1");
    arc.setattrvalue("A2", "0");
    line.addattrobj("segment", arc);
    try {
      GeometryBounds.bounds(line);
      fail("collinear arc silently indexed");
    } catch (java.io.IOException expected) {
      assertTrue(expected.getMessage().contains("arc"));
    }
  }
}
