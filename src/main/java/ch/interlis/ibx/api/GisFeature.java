package ch.interlis.ibx.api;

import java.util.*;

/** Direct feature view. Geometry bytes use ISO-WKB; CRS belongs to its layer. */
public final class GisFeature {
  private final long fid;
  private final String tid;
  private final BasketContext basket;
  private final byte[] wkb;
  private final Map<String, Object> attributes;

  public GisFeature(
      long fid, String tid, BasketContext basket, byte[] wkb, Map<String, Object> attributes) {
    this.fid = fid;
    this.tid = tid;
    this.basket = basket;
    this.wkb = wkb;
    this.attributes = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(attributes));
  }

  public long getFid() {
    return fid;
  }

  public String getTid() {
    return tid;
  }

  public String getBid() {
    return basket.bid;
  }

  public BasketContext getBasket() {
    return basket;
  }

  public byte[] getWkb() {
    return wkb == null ? null : wkb.clone();
  }

  public Map<String, Object> getAttributes() {
    return attributes;
  }
}
