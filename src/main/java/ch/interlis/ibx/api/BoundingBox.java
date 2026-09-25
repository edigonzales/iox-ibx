package ch.interlis.ibx.api;

public final class BoundingBox {
  public double minX, minY, maxX, maxY;

  public BoundingBox() {}

  public BoundingBox(double minX, double minY, double maxX, double maxY) {
    if (!Double.isFinite(minX)
        || !Double.isFinite(minY)
        || !Double.isFinite(maxX)
        || !Double.isFinite(maxY)
        || minX > maxX
        || minY > maxY) throw new IllegalArgumentException("Invalid bounding box");
    this.minX = minX;
    this.minY = minY;
    this.maxX = maxX;
    this.maxY = maxY;
  }

  public boolean intersects(BoundingBox b) {
    return minX <= b.maxX && maxX >= b.minX && minY <= b.maxY && maxY >= b.minY;
  }

  public void expand(BoundingBox b) {
    minX = Math.min(minX, b.minX);
    minY = Math.min(minY, b.minY);
    maxX = Math.max(maxX, b.maxX);
    maxY = Math.max(maxY, b.maxY);
  }
}
