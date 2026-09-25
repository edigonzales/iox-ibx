package ch.interlis.ibx.geometry;

import java.util.*;

/** Exact coordinates and curve components; never contains a stroked JTS view. */
public final class Geometry {
  public final int type, dimension;
  public final List<double[]> points;
  public final List<Geometry> parts;

  public Geometry(int type, int dimension, List<double[]> points, List<Geometry> parts) {
    this.type = type;
    this.dimension = dimension;
    this.points = points;
    this.parts = parts;
  }

  public static Geometry points(int type, int dimension, List<double[]> points) {
    return new Geometry(type, dimension, points, Collections.<Geometry>emptyList());
  }

  public static Geometry parts(int type, int dimension, List<Geometry> parts) {
    return new Geometry(type, dimension, Collections.<double[]>emptyList(), parts);
  }

  public double[] first() {
    return points.isEmpty() ? parts.get(0).first() : points.get(0);
  }

  public double[] last() {
    return points.isEmpty() ? parts.get(parts.size() - 1).last() : points.get(points.size() - 1);
  }
}
