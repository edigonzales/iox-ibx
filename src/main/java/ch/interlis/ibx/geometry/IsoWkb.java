package ch.interlis.ibx.geometry;

import java.io.*;
import java.nio.*;
import java.util.*;

/** SQL/MM codec adapted from hop-geometry-type-plugin f4d4747; see THIRD_PARTY.md. */
public final class IsoWkb {
  private IsoWkb() {}

  public static byte[] write(Geometry g) throws IOException {
    validate(g, 0);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    write(out, g);
    return out.toByteArray();
  }

  private static void integer(ByteArrayOutputStream out, int n) {
    for (int i = 0; i < 4; i++) out.write(n >>> (8 * i));
  }

  private static void coordinates(ByteArrayOutputStream out, Geometry g) {
    for (double[] p : g.points)
      for (double v : p) {
        long n = Double.doubleToLongBits(v);
        for (int i = 0; i < 8; i++) out.write((int) (n >>> (8 * i)));
      }
  }

  private static void write(ByteArrayOutputStream out, Geometry g) {
    out.write(1);
    integer(out, g.type + (g.dimension == 3 ? 1000 : 0));
    if (g.type == 1) coordinates(out, g);
    else if (g.type == 2 || g.type == 8) {
      integer(out, g.points.size());
      coordinates(out, g);
    } else {
      integer(out, g.parts.size());
      for (Geometry p : g.parts) {
        if (g.type == 3) {
          integer(out, p.points.size());
          coordinates(out, p);
        } else write(out, p);
      }
    }
  }

  public static Geometry read(byte[] bytes) throws IOException {
    if (bytes == null) throw new IOException("Missing WKB");
    ByteBuffer in = ByteBuffer.wrap(bytes);
    try {
      Geometry g = read(in, 0);
      if (in.hasRemaining()) throw new IOException("Trailing WKB bytes");
      validate(g, 0);
      return g;
    } catch (BufferUnderflowException | IllegalArgumentException e) {
      throw new IOException("Invalid/truncated WKB", e);
    }
  }

  private static int count(ByteBuffer in, int minimumBytes) throws IOException {
    int n = in.getInt();
    if (n < 1 || n > in.remaining() / minimumBytes) throw new IOException("Invalid WKB count");
    return n;
  }

  private static Geometry points(ByteBuffer in, int type, int dim, int n) throws IOException {
    if (n > in.remaining() / (8 * dim)) throw new IOException("Truncated coordinates");
    List<double[]> ps = new ArrayList<double[]>(n);
    for (int i = 0; i < n; i++) {
      double[] p = new double[dim];
      for (int j = 0; j < dim; j++) p[j] = in.getDouble();
      ps.add(p);
    }
    return Geometry.points(type, dim, ps);
  }

  private static Geometry read(ByteBuffer in, int depth) throws IOException {
    if (depth > 64) throw new IOException("WKB nesting exceeds 64");
    int order = in.get() & 255;
    if (order > 1) throw new IOException("Invalid WKB byte order");
    ByteOrder endian = order == 0 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    in.order(endian);
    int raw = in.getInt(), type = raw % 1000, dim = raw / 1000;
    if (raw < 0 || dim > 1 || !supported(type))
      throw new IOException("Unsupported ISO-WKB type " + raw);
    dim += 2;
    if (type == 1) return points(in, type, dim, 1);
    if (type == 2 || type == 8) return points(in, type, dim, count(in, 8 * dim));
    int n = count(in, type == 3 ? 4 : 5);
    List<Geometry> parts = new ArrayList<Geometry>(n);
    for (int i = 0; i < n; i++) {
      in.order(endian);
      parts.add(type == 3 ? points(in, 2, dim, count(in, 8 * dim)) : read(in, depth + 1));
    }
    return Geometry.parts(type, dim, parts);
  }

  private static boolean supported(int t) {
    return t >= 1 && t <= 6 || t >= 8 && t <= 12;
  }

  public static void validate(Geometry g, int depth) throws IOException {
    if (depth > 64 || !supported(g.type) || g.dimension < 2 || g.dimension > 3)
      throw new IOException("Unsupported geometry/dimension/nesting");
    if (g.type == 1 || g.type == 2 || g.type == 8) {
      int n = g.points.size();
      if (!g.parts.isEmpty() || (g.type == 1 ? n != 1 : g.type == 2 ? n < 2 : n < 3 || n % 2 == 0))
        throw new IOException("Invalid coordinate count");
      for (double[] p : g.points) {
        if (p.length != g.dimension) throw new IOException("Mixed coordinate dimensions");
        for (double v : p) if (!Double.isFinite(v)) throw new IOException("Non-finite coordinate");
      }
      if (g.type == 8)
        for (int i = 0; i + 2 < n; i += 2)
          ArcMath.fraction(g.points.get(i), g.points.get(i + 1), g.points.get(i + 2));
      return;
    }
    if (!g.points.isEmpty() || g.parts.isEmpty()) throw new IOException("Empty geometry");
    Geometry previous = null;
    for (Geometry p : g.parts) {
      validate(p, depth + 1);
      if (p.dimension != g.dimension) throw new IOException("Mixed geometry dimensions");
      boolean valid =
          g.type == 3
              ? p.type == 2
              : g.type == 4
                  ? p.type == 1
                  : g.type == 5
                      ? p.type == 2
                      : g.type == 6
                          ? p.type == 3
                          : g.type == 9
                              ? p.type == 2 || p.type == 8
                              : g.type == 10 || g.type == 11
                                  ? p.type == 2 || p.type == 8 || p.type == 9
                                  : p.type == 3 || p.type == 10;
      if (!valid) throw new IOException("Invalid WKB component type");
      if (g.type == 3 || g.type == 10) {
        if (!Arrays.equals(p.first(), p.last())) throw new IOException("Unclosed ring");
        if (p.type == 2 && p.points.size() < 4) throw new IOException("Ring too short");
      }
      if (g.type == 9 && previous != null && !Arrays.equals(previous.last(), p.first()))
        throw new IOException("Disconnected compound curve");
      previous = p;
    }
  }
}
