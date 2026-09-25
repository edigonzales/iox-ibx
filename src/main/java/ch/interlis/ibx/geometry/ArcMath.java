package ch.interlis.ibx.geometry;

import java.io.IOException;

/** Circle through three exact control points; no linearization. */
public final class ArcMath {
  private ArcMath() {}

  private static double positive(double a) {
    double t = 2 * Math.PI;
    return (a % t + t) % t;
  }

  public static double fraction(double[] a, double[] m, double[] b) throws IOException {
    if (a[0] == b[0] && a[1] == b[1]) {
      if (a[0] == m[0] && a[1] == m[1]) throw new IOException("Degenerate arc");
      return .5;
    }
    double ux = m[0] - a[0], uy = m[1] - a[1], vx = b[0] - a[0], vy = b[1] - a[1];
    double cross = ux * vy - uy * vx, scale = Math.max(Math.abs(ux * vy), Math.abs(uy * vx));
    if (!Double.isFinite(cross) || Math.abs(cross) <= 64 * Math.ulp(Math.max(1, scale)))
      throw new IOException("Degenerate or numerically ambiguous arc");
    double aa = ux * ux + uy * uy, bb = vx * vx + vy * vy;
    double cx = (aa * vy - bb * uy) / (2 * cross), cy = (ux * bb - vx * aa) / (2 * cross);
    double start = Math.atan2(-cy, -cx),
        mid = Math.atan2(uy - cy, ux - cx),
        end = Math.atan2(vy - cy, vx - cx);
    double total = cross > 0 ? positive(end - start) : positive(start - end);
    double part = cross > 0 ? positive(mid - start) : positive(start - mid);
    double f = part / total;
    if (!Double.isFinite(f) || f <= 0 || f >= 1)
      throw new IOException("Unstable arc interpolation");
    return f;
  }
}
