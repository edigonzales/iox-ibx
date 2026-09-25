package ch.interlis.ibx.spatial;

import ch.interlis.ibx.api.BoundingBox;
import ch.interlis.iom.IomObject;
import com.vividsolutions.jts.geom.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/** Geometry structure traversal; arcs are not linearized. */
public final class GeometryBounds {
  private GeometryBounds() {}

  public static BoundingBox attribute(IomObject object, String name) throws IOException {
    BoundingBox result = null;
    for (int i = 0; i < object.getattrvaluecount(name); i++) {
      IomObject g = object.getattrobj(name, i);
      if (g == null) {
        if (object.getattrprim(name, i) != null)
          throw new IOException("Non-geometry value in " + name);
        continue;
      }
      BoundingBox box = bounds(g);
      if (box != null) {
        if (result == null) result = box;
        else result.expand(box);
      }
    }
    return result;
  }

  public static BoundingBox bounds(IomObject geometry) throws IOException {
    Envelope envelope = new Envelope();
    walk(geometry, envelope);
    if (envelope.isNull()) return null;
    return new BoundingBox(
        envelope.getMinX(), envelope.getMinY(), envelope.getMaxX(), envelope.getMaxY());
  }

  private static Coordinate coord(IomObject o, String x, String y) throws IOException {
    try {
      String sx = o.getattrvalue(x), sy = o.getattrvalue(y);
      if (sx == null || sy == null) throw new IOException("Incomplete coordinate");
      double dx = new BigDecimal(sx).doubleValue(), dy = new BigDecimal(sy).doubleValue();
      if (!Double.isFinite(dx) || !Double.isFinite(dy))
        throw new IOException("Non-finite coordinate");
      return new Coordinate(dx, dy);
    } catch (NumberFormatException e) {
      throw new IOException("Invalid coordinate", e);
    }
  }

  private static void include(Envelope env, Coordinate c) {
    env.expandToInclude(Math.nextDown(c.x), Math.nextDown(c.y));
    env.expandToInclude(Math.nextUp(c.x), Math.nextUp(c.y));
  }

  private static void walk(IomObject o, Envelope env) throws IOException {
    String tag = o.getobjecttag();
    if (tag.equals("COORD")) {
      include(env, coord(o, "C1", "C2"));
      return;
    }
    if (tag.equals("SEGMENTS")) {
      Coordinate previous = null;
      IomObject previousObject = null;
      for (int i = 0; i < o.getattrvaluecount("segment"); i++) {
        IomObject segment = o.getattrobj("segment", i);
        if (segment == null) throw new IOException("Missing line segment");
        Coordinate end = coord(segment, "C1", "C2");
        if (segment.getobjecttag().equals("ARC")) {
          if (previous == null) throw new IOException("Arc without starting coordinate");
          Coordinate mid = coord(segment, "A1", "A2");
          if (segment.getattrvalue("R") != null)
            throw new IOException("Explicit-radius arc cannot yet be reliably indexed");
          ch.interlis.ibx.geometry.GeometryEnvelope.Envelope exact =
              new ch.interlis.ibx.geometry.GeometryEnvelope.Envelope();
          ch.interlis.ibx.geometry.GeometryEnvelope.arc(
              new String[] {previousObject.getattrvalue("C1"), previousObject.getattrvalue("C2")},
              new String[] {segment.getattrvalue("A1"), segment.getattrvalue("A2")},
              new String[] {segment.getattrvalue("C1"), segment.getattrvalue("C2")},
              exact);
          BoundingBox box = exact.box();
          env.expandToInclude(box.minX, box.minY);
          env.expandToInclude(box.maxX, box.maxY);
          include(env, mid);
        } else if (!segment.getobjecttag().equals("COORD"))
          throw new IOException("Unsupported line segment: " + segment.getobjecttag());
        include(env, end);
        previous = end;
        previousObject = segment;
      }
      return;
    }
    if (!Arrays.asList(
            "MULTICOORD", "POLYLINE", "MULTIPOLYLINE", "MULTISURFACE", "SURFACE", "BOUNDARY")
        .contains(tag)) throw new IOException("Unsupported geometry node: " + tag);
    for (int a = 0; a < o.getattrcount(); a++) {
      String name = o.getattrname(a);
      if (name.equals("lineattr")) continue;
      for (int i = 0; i < o.getattrvaluecount(name); i++) {
        IomObject child = o.getattrobj(name, i);
        if (child == null) throw new IOException("Invalid geometry member: " + name);
        walk(child, env);
      }
    }
  }
}
