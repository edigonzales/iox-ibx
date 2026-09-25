package ch.interlis.ibx.api;

/** Options for externally sorted spatial index construction. */
public final class SpatialIndexOptions {
  public String packing = "str";

  public void validate() {
    if (!"str".equals(packing) && !"x".equals(packing))
      throw new IllegalArgumentException("Spatial packing must be str or x");
  }
}
