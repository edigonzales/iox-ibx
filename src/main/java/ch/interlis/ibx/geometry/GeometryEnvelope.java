package ch.interlis.ibx.geometry;

import ch.interlis.ibx.api.BoundingBox;
import java.io.IOException;
import java.math.*;

/** Shared exact-decimal arc extrema and outward-rounded bounds, independent of IOM/JTS. */
public final class GeometryEnvelope {
  private GeometryEnvelope() {}

  public static final class Envelope {
    double minX = Double.POSITIVE_INFINITY,
        minY = Double.POSITIVE_INFINITY,
        maxX = Double.NEGATIVE_INFINITY,
        maxY = Double.NEGATIVE_INFINITY;

    public void expandToInclude(double x, double y) {
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
    }

    public BoundingBox box() {
      return new BoundingBox(minX, minY, maxX, maxY);
    }
  }

  private static String[] text(double[] p) {
    return new String[] {Double.toString(p[0]), Double.toString(p[1])};
  }

  public static BoundingBox bounds(Geometry g) throws IOException {
    Envelope e = new Envelope();
    walk(g, e);
    return e.box();
  }

  private static void walk(Geometry g, Envelope e) throws IOException {
    for (double[] p : g.points) {
      e.expandToInclude(Math.nextDown(p[0]), Math.nextDown(p[1]));
      e.expandToInclude(Math.nextUp(p[0]), Math.nextUp(p[1]));
    }
    if (g.type == 8)
      for (int i = 0; i + 2 < g.points.size(); i += 2)
        arc(text(g.points.get(i)), text(g.points.get(i + 1)), text(g.points.get(i + 2)), e);
    for (Geometry p : g.parts) walk(p, e);
  }

  private static int angleCompare(BigDecimal x1, BigDecimal y1, BigDecimal x2, BigDecimal y2) {
    int h1 = y1.signum() > 0 || (y1.signum() == 0 && x1.signum() >= 0) ? 0 : 1;
    int h2 = y2.signum() > 0 || (y2.signum() == 0 && x2.signum() >= 0) ? 0 : 1;
    if (h1 != h2) return Integer.compare(h1, h2);
    return -x1.multiply(y2).subtract(y1.multiply(x2)).signum();
  }

  private static boolean between(
      BigDecimal sx, BigDecimal sy, BigDecimal ex, BigDecimal ey, BigDecimal dx, BigDecimal dy) {
    int ends = angleCompare(sx, sy, ex, ey);
    boolean after = angleCompare(sx, sy, dx, dy) <= 0;
    boolean before = angleCompare(dx, dy, ex, ey) <= 0;
    return ends <= 0 ? after && before : after || before;
  }

  public static void arc(String[] start, String[] mid, String[] end, Envelope env)
      throws IOException {
    for (String[] point : new String[][] {start, mid, end}) {
      double px = new BigDecimal(point[0]).doubleValue(),
          py = new BigDecimal(point[1]).doubleValue();
      env.expandToInclude(Math.nextDown(px), Math.nextDown(py));
      env.expandToInclude(Math.nextUp(px), Math.nextUp(py));
    }
    BigDecimal x = new BigDecimal(start[0]), y = new BigDecimal(start[1]);
    BigDecimal ax = new BigDecimal(mid[0]).subtract(x), ay = new BigDecimal(mid[1]).subtract(y);
    BigDecimal bx = new BigDecimal(end[0]).subtract(x), by = new BigDecimal(end[1]).subtract(y);
    if (bx.signum() == 0 && by.signum() == 0) {
      BigDecimal cx = x.add(ax.divide(BigDecimal.valueOf(2))),
          cy = y.add(ay.divide(BigDecimal.valueOf(2)));
      BigDecimal square = ax.multiply(ax).add(ay.multiply(ay)).divide(BigDecimal.valueOf(4));
      double r = Math.sqrt(square.doubleValue());
      if (!Double.isFinite(r) || r <= 0) throw new IOException("Invalid full circle");
      while (new BigDecimal(r).pow(2).compareTo(square) < 0) r = Math.nextUp(r);
      BigDecimal radius = new BigDecimal(r);
      env.expandToInclude(
          Math.nextDown(cx.subtract(radius).doubleValue()),
          Math.nextDown(cy.subtract(radius).doubleValue()));
      env.expandToInclude(
          Math.nextUp(cx.add(radius).doubleValue()), Math.nextUp(cy.add(radius).doubleValue()));
      return;
    }
    BigDecimal determinant = ax.multiply(by).subtract(ay.multiply(bx));
    if (determinant.signum() == 0) throw new IOException("Collinear arc");
    BigDecimal den = determinant.multiply(BigDecimal.valueOf(2));
    BigDecimal aa = ax.multiply(ax).add(ay.multiply(ay)), bb = bx.multiply(bx).add(by.multiply(by));
    BigDecimal nx = aa.multiply(by).subtract(bb.multiply(ay));
    BigDecimal ny = ax.multiply(bb).subtract(bx.multiply(aa));
    if (den.signum() < 0) {
      den = den.negate();
      nx = nx.negate();
      ny = ny.negate();
    }
    BigDecimal sx = nx.negate(), sy = ny.negate();
    BigDecimal ex = bx.multiply(den).subtract(nx), ey = by.multiply(den).subtract(ny);
    BigDecimal radiusNumerator = nx.multiply(nx).add(ny.multiply(ny)),
        denSquared = den.multiply(den);
    double radius =
        Math.sqrt(radiusNumerator.divide(denSquared, MathContext.DECIMAL128).doubleValue());
    if (!Double.isFinite(radius) || radius <= 0)
      throw new IOException("Unrepresentable arc radius");
    while (new BigDecimal(radius).pow(2).multiply(denSquared).compareTo(radiusNumerator) < 0)
      radius = Math.nextUp(radius);
    BigDecimal r = new BigDecimal(radius);
    double lowerRadius = Math.nextDown(radius);
    while (new BigDecimal(lowerRadius).pow(2).multiply(denSquared).compareTo(radiusNumerator) > 0)
      lowerRadius = Math.nextDown(lowerRadius);
    BigDecimal lowerR = new BigDecimal(lowerRadius);
    BigDecimal cx = x.multiply(den).add(nx), cy = y.multiply(den).add(ny);
    if (determinant.signum() < 0) {
      BigDecimal t = sx;
      sx = ex;
      ex = t;
      t = sy;
      sy = ey;
      ey = t;
    }
    int[][] directions = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    for (int[] d : directions) {
      if (!between(sx, sy, ex, ey, BigDecimal.valueOf(d[0]), BigDecimal.valueOf(d[1]))) continue;
      BigDecimal lowX = cx.divide(den, 40, RoundingMode.FLOOR);
      BigDecimal highX = cx.divide(den, 40, RoundingMode.CEILING);
      BigDecimal lowY = cy.divide(den, 40, RoundingMode.FLOOR);
      BigDecimal highY = cy.divide(den, 40, RoundingMode.CEILING);
      if (d[0] < 0) {
        lowX = lowX.subtract(r);
        highX = highX.subtract(lowerR);
      }
      if (d[0] > 0) {
        lowX = lowX.add(lowerR);
        highX = highX.add(r);
      }
      if (d[1] < 0) {
        lowY = lowY.subtract(r);
        highY = highY.subtract(lowerR);
      }
      if (d[1] > 0) {
        lowY = lowY.add(lowerR);
        highY = highY.add(r);
      }
      env.expandToInclude(Math.nextDown(lowX.doubleValue()), Math.nextDown(lowY.doubleValue()));
      env.expandToInclude(Math.nextUp(highX.doubleValue()), Math.nextUp(highY.doubleValue()));
    }
  }
}
