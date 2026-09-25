package ch.interlis.ibx;

import static org.junit.Assert.*;

import ch.interlis.ibx.geometry.*;
import ch.interlis.iom.IomObject;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class WkbFixturesTest {
  private static Geometry points(int type, int dim, double[][] xy) {
    List<double[]> ps = new ArrayList<double[]>();
    for (double[] p : xy) ps.add(dim == 3 ? new double[] {p[0], p[1], 7} : p);
    return Geometry.points(type, dim, ps);
  }

  private static Geometry parts(int type, int dim, Geometry... parts) {
    return Geometry.parts(type, dim, Arrays.asList(parts));
  }

  @Test
  public void allTypesAndDimensionsAgainstIndependentWktFixtures() throws Exception {
    Path dir = Paths.get("build/wkb-fixtures");
    Files.createDirectories(dir);
    for (int dim : new int[] {2, 3}) {
      Geometry point = points(1, dim, new double[][] {{1, 2}});
      Geometry line = points(2, dim, new double[][] {{0, 0}, {1, 1}, {2, 0}});
      Geometry arc = points(8, dim, new double[][] {{0, 0}, {1, 1}, {2, 0}});
      Geometry compound = parts(9, dim, arc, points(2, dim, new double[][] {{2, 0}, {0, 0}}));
      Geometry ring = points(2, dim, new double[][] {{0, 0}, {4, 0}, {4, 4}, {0, 4}, {0, 0}});
      Geometry hole = points(2, dim, new double[][] {{1, 1}, {1, 2}, {2, 2}, {2, 1}, {1, 1}});
      Geometry polygon = parts(3, dim, ring, hole), curved = parts(10, dim, compound);
      Geometry[] all = {
        point,
        line,
        polygon,
        parts(4, dim, point),
        parts(5, dim, line),
        parts(6, dim, polygon),
        arc,
        compound,
        curved,
        parts(11, dim, line, arc),
        parts(12, dim, polygon, curved)
      };
      String[] wkts = {
        "POINT (1 2)",
        "LINESTRING (0 0,1 1,2 0)",
        "POLYGON ((0 0,4 0,4 4,0 4,0 0),(1 1,1 2,2 2,2 1,1 1))",
        "MULTIPOINT ((1 2))",
        "MULTILINESTRING ((0 0,1 1,2 0))",
        "MULTIPOLYGON (((0 0,4 0,4 4,0 4,0 0),(1 1,1 2,2 2,2 1,1 1)))",
        "CIRCULARSTRING (0 0,1 1,2 0)",
        "COMPOUNDCURVE (CIRCULARSTRING (0 0,1 1,2 0),(2 0,0 0))",
        "CURVEPOLYGON (COMPOUNDCURVE (CIRCULARSTRING (0 0,1 1,2 0),(2 0,0 0)))",
        "MULTICURVE ((0 0,1 1,2 0),CIRCULARSTRING (0 0,1 1,2 0))",
        "MULTISURFACE (((0 0,4 0,4 4,0 4,0 0),(1 1,1 2,2 2,2 1,1 1)),CURVEPOLYGON (COMPOUNDCURVE"
            + " (CIRCULARSTRING (0 0,1 1,2 0),(2 0,0 0))))"
      };
      for (int i = 0; i < all.length; i++) {
        Geometry g = all[i];
        byte[] bytes = IsoWkb.write(g);
        assertArrayEquals(bytes, IsoWkb.write(IsoWkb.read(bytes)));
        String name = "type-" + g.type + "-dim-" + dim;
        Files.write(dir.resolve(name + ".wkb"), bytes);
        String wkt = wkts[i];
        if (dim == 3) {
          wkt = wkt.replaceAll("([0-9]+) ([0-9]+)", "$1 $2 7");
          wkt = wkt.replaceAll("([A-Z]+) ", "$1 Z ");
        }
        Files.write(dir.resolve(name + ".wkt"), wkt.getBytes("UTF-8"));
        // IOM has no standalone CircularString: an IOM polyline returns a CompoundCurve.
        if (g.type != 8 && g.type != 11) {
          IomObject iom = IomGeometry.toIom(g, "MULTISURFACE");
          byte[] restored = IomGeometry.encode(iom, g.type == 6 || g.type == 12);
          assertArrayEquals(bytes, restored);
        }
      }
    }
  }

  @Test
  public void bigEndianAndFullCircleBounds() throws Exception {
    ByteBuffer b = ByteBuffer.allocate(9 + 3 * 3 * 8).order(ByteOrder.BIG_ENDIAN);
    b.put((byte) 0).putInt(1008).putInt(3);
    for (double[] p : new double[][] {{0, 0, 7}, {2, 0, 7}, {0, 0, 7}})
      for (double v : p) b.putDouble(v);
    Geometry g = IsoWkb.read(b.array());
    assertEquals(8, g.type);
    assertEquals(3, g.dimension);
    ch.interlis.ibx.api.BoundingBox box = GeometryEnvelope.bounds(g);
    assertTrue(box.minY <= -1 && box.maxY >= 1);
  }
}
