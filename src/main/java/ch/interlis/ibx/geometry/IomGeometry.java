package ch.interlis.ibx.geometry;

import ch.interlis.iom.*;
import ch.interlis.iom_j.Iom_jObject;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;

/** Strict structural INTERLIS adapter. Unknown attributes are never discarded. */
public final class IomGeometry {
  private IomGeometry() {}

  public static boolean isGeometry(String tag) {
    return Arrays.asList(
            "COORD", "MULTICOORD", "POLYLINE", "MULTIPOLYLINE", "MULTISURFACE", "SURFACE")
        .contains(tag);
  }

  private static void fields(IomObject o, String... names) throws IOException {
    if (o == null) throw new IOException("Missing geometry component");
    if (o.getobjectoid() != null
        || o.getobjectrefoid() != null
        || o.getobjectrefbid() != null
        || o.getobjectreforderpos() != 0
        || o.getobjectconsistency() != IomConstants.IOM_COMPLETE
        || o.getobjectoperation() != IomConstants.IOM_OP_INSERT)
      throw new IOException("Unsupported geometry identity/state");
    for (int i = 0; i < o.getattrcount(); i++)
      if (!Arrays.asList(names).contains(o.getattrname(i)))
        throw new IOException("Unsupported geometry attribute " + o.getattrname(i));
  }

  private static IomObject child(IomObject o, String attr, int i, String tag) throws IOException {
    IomObject c = o.getattrobj(attr, i);
    if (c == null || !tag.equals(c.getobjecttag()))
      throw new IOException("Expected " + tag + " in " + attr);
    return c;
  }

  private static double number(IomObject o, String attr) throws IOException {
    if (o.getattrvaluecount(attr) != 1 || o.getattrobj(attr, 0) != null)
      throw new IOException("Missing/invalid " + attr);
    try {
      BigDecimal n = new BigDecimal(o.getattrvalue(attr));
      double d = n.doubleValue();
      if (!Double.isFinite(d) || n.compareTo(new BigDecimal(Double.toString(d))) != 0)
        throw new IOException("Coordinate loses decimal precision: " + attr + "=" + n);
      return d == 0 ? 0 : d;
    } catch (NumberFormatException e) {
      throw new IOException("Invalid ordinate " + attr, e);
    }
  }

  private static double[] coord(IomObject o) throws IOException {
    boolean z = o.getattrvaluecount("C3") > 0;
    return z
        ? new double[] {number(o, "C1"), number(o, "C2"), number(o, "C3")}
        : new double[] {number(o, "C1"), number(o, "C2")};
  }

  public static Geometry fromIom(IomObject o, boolean multiSurface) throws IOException {
    Geometry g = from(o, multiSurface, 0);
    IsoWkb.validate(g, 0);
    return g;
  }

  private static Geometry from(IomObject o, boolean multi, int depth) throws IOException {
    if (depth > 64) throw new IOException("Geometry nesting exceeds 64");
    String tag = o.getobjecttag();
    if (tag.equals("COORD")) {
      fields(o, "C1", "C2", "C3");
      double[] p = coord(o);
      return Geometry.points(1, p.length, Collections.singletonList(p));
    }
    if (tag.equals("POLYLINE")) return line(o);
    if (tag.equals("SURFACE")) return surface(o);
    String attr, expected;
    int type;
    if (tag.equals("MULTICOORD")) {
      attr = "coord";
      expected = "COORD";
      type = 4;
    } else if (tag.equals("MULTIPOLYLINE")) {
      attr = "polyline";
      expected = "POLYLINE";
      type = 5;
    } else if (tag.equals("MULTISURFACE")) {
      attr = "surface";
      expected = "SURFACE";
      type = 6;
    } else throw new IOException("Unsupported geometry " + tag);
    fields(o, attr);
    List<Geometry> ps = new ArrayList<Geometry>();
    boolean curves = false;
    for (int i = 0; i < o.getattrvaluecount(attr); i++) {
      Geometry g = from(child(o, attr, i, expected), false, depth + 1);
      ps.add(g);
      curves |= g.type >= 8;
    }
    if (ps.isEmpty()) throw new IOException("Empty geometry");
    if (type == 6 && !multi) {
      if (ps.size() != 1) throw new IOException("Multiple surfaces in single surface attribute");
      return ps.get(0);
    }
    return Geometry.parts(curves ? (type == 5 ? 11 : 12) : type, ps.get(0).dimension, ps);
  }

  private static Geometry line(IomObject o) throws IOException {
    fields(o, "sequence");
    if (o.getattrvaluecount("sequence") != 1)
      throw new IOException("Incomplete/multiple line sequences");
    IomObject seq = child(o, "sequence", 0, "SEGMENTS");
    fields(seq, "segment");
    int n = seq.getattrvaluecount("segment");
    if (n < 2) throw new IOException("Empty/short polyline");
    IomObject start = child(seq, "segment", 0, "COORD");
    fields(start, "C1", "C2", "C3");
    double[] prev = coord(start);
    int dim = prev.length;
    List<Geometry> parts = new ArrayList<Geometry>();
    List<double[]> run = new ArrayList<double[]>();
    run.add(prev);
    for (int i = 1; i < n; i++) {
      IomObject s = seq.getattrobj("segment", i);
      if (s == null) throw new IOException("Missing segment");
      boolean arc = s.getobjecttag().equals("ARC");
      if (!arc && !s.getobjecttag().equals("COORD")) throw new IOException("Custom line form");
      if (arc) fields(s, "C1", "C2", "C3", "A1", "A2");
      else fields(s, "C1", "C2", "C3");
      double[] end = coord(s);
      if (end.length != dim) throw new IOException("Mixed line dimensions");
      if (arc) {
        if (run.size() > 1) parts.add(Geometry.points(2, dim, run));
        double[] mid = new double[dim];
        mid[0] = number(s, "A1");
        mid[1] = number(s, "A2");
        double f = ArcMath.fraction(prev, mid, end);
        if (dim == 3) mid[2] = prev[2] * (1 - f) + end[2] * f;
        parts.add(Geometry.points(8, dim, Arrays.asList(prev, mid, end)));
        run = new ArrayList<double[]>();
        run.add(end);
      } else run.add(end);
      prev = end;
    }
    if (parts.isEmpty()) return Geometry.points(2, dim, run);
    if (run.size() > 1) parts.add(Geometry.points(2, dim, run));
    return Geometry.parts(9, dim, parts);
  }

  private static Geometry surface(IomObject o) throws IOException {
    fields(o, "boundary");
    List<Geometry> rings = new ArrayList<Geometry>();
    boolean curves = false;
    for (int i = 0; i < o.getattrvaluecount("boundary"); i++) {
      IomObject b = child(o, "boundary", i, "BOUNDARY");
      fields(b, "polyline");
      List<Geometry> parts = new ArrayList<Geometry>();
      for (int j = 0; j < b.getattrvaluecount("polyline"); j++) {
        Geometry l = line(child(b, "polyline", j, "POLYLINE"));
        if (l.type == 9) parts.addAll(l.parts);
        else parts.add(l);
      }
      if (parts.isEmpty()) throw new IOException("Empty boundary");
      boolean curved = false;
      for (Geometry p : parts) curved |= p.type == 8;
      Geometry joined = Geometry.parts(9, parts.get(0).dimension, parts);
      IsoWkb.validate(joined, 0);
      if (!curved) {
        List<double[]> points = new ArrayList<double[]>();
        for (Geometry p : parts)
          points.addAll(p.points.subList(points.isEmpty() ? 0 : 1, p.points.size()));
        joined = Geometry.points(2, joined.dimension, points);
      }
      rings.add(joined);
      curves |= curved;
    }
    if (rings.isEmpty()) throw new IOException("Empty surface");
    return Geometry.parts(curves ? 10 : 3, rings.get(0).dimension, rings);
  }

  private static IomObject object(String tag) {
    return new Iom_jObject(tag, null);
  }

  private static IomObject coordinate(double[] p, String tag) {
    IomObject o = object(tag);
    for (int i = 0; i < p.length; i++) o.setattrvalue("C" + (i + 1), Double.toString(p[i]));
    return o;
  }

  private static void segments(Geometry g, IomObject seq) {
    if (g.type == 9) {
      for (Geometry p : g.parts) segments(p, seq);
      return;
    }
    if (seq.getattrvaluecount("segment") == 0)
      seq.addattrobj("segment", coordinate(g.first(), "COORD"));
    for (int i = 1; i < g.points.size(); i += g.type == 8 ? 2 : 1) {
      IomObject p =
          coordinate(g.points.get(g.type == 8 ? i + 1 : i), g.type == 8 ? "ARC" : "COORD");
      if (g.type == 8) {
        p.setattrvalue("A1", Double.toString(g.points.get(i)[0]));
        p.setattrvalue("A2", Double.toString(g.points.get(i)[1]));
      }
      seq.addattrobj("segment", p);
    }
  }

  public static IomObject toIom(Geometry g, String root) throws IOException {
    if (g.type == 1) return coordinate(g.first(), "COORD");
    if (g.type == 2 || g.type == 8 || g.type == 9) {
      IomObject o = object("POLYLINE"), seq = object("SEGMENTS");
      segments(g, seq);
      o.addattrobj("sequence", seq);
      return o;
    }
    if (g.type == 3 || g.type == 10) {
      IomObject s = object("SURFACE");
      for (Geometry ring : g.parts) {
        IomObject b = object("BOUNDARY");
        b.addattrobj("polyline", toIom(ring, "POLYLINE"));
        s.addattrobj("boundary", b);
      }
      if ("SURFACE".equals(root)) return s;
      IomObject m = object("MULTISURFACE");
      m.addattrobj("surface", s);
      return m;
    }
    String tag =
        g.type == 4 ? "MULTICOORD" : g.type == 5 || g.type == 11 ? "MULTIPOLYLINE" : "MULTISURFACE";
    String attr = g.type == 4 ? "coord" : g.type == 5 || g.type == 11 ? "polyline" : "surface";
    IomObject o = object(tag);
    for (Geometry p : g.parts) o.addattrobj(attr, toIom(p, "SURFACE"));
    return o;
  }

  public static byte[] encode(IomObject o, boolean multi) throws IOException {
    byte[] bytes = IsoWkb.write(fromIom(o, multi));
    IomObject reconstructed = toIom(IsoWkb.read(bytes), o.getobjecttag());
    if (!GeometrySemantics.canonical(o).equals(GeometrySemantics.canonical(reconstructed)))
      throw new IOException("Geometry roundtrip changed coordinates/components");
    return bytes;
  }
}
