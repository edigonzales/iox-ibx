package ch.interlis.ibx.geometry;

import ch.interlis.iom.IomObject;
import java.math.BigDecimal;
import java.util.*;

/** Independent IOM semantic comparison; only boundary polyline partitioning is normalized. */
public final class GeometrySemantics {
  private GeometrySemantics() {}

  public static Object canonical(IomObject o) {
    SortedMap<String, Object> attrs = new TreeMap<String, Object>();
    if (o.getobjecttag().equals("BOUNDARY")) {
      List<Object> points = new ArrayList<Object>();
      for (int i = 0; i < o.getattrvaluecount("polyline"); i++) {
        IomObject seq = o.getattrobj("polyline", i).getattrobj("sequence", 0);
        for (int j = points.isEmpty() ? 0 : 1; j < seq.getattrvaluecount("segment"); j++)
          points.add(canonical(seq.getattrobj("segment", j)));
      }
      attrs.put("segments", points);
    } else
      for (int a = 0; a < o.getattrcount(); a++) {
        String name = o.getattrname(a);
        List<Object> values = new ArrayList<Object>();
        for (int i = 0; i < o.getattrvaluecount(name); i++) {
          IomObject c = o.getattrobj(name, i);
          String value = c == null ? o.getattrprim(name, i) : null;
          values.add(
              c != null
                  ? canonical(c)
                  : value == null
                      ? null
                      : new BigDecimal(value).stripTrailingZeros().toPlainString());
        }
        attrs.put(name, values);
      }
    return Arrays.asList(
        o.getobjecttag(),
        o.getobjectoid(),
        o.getobjectrefoid(),
        o.getobjectrefbid(),
        o.getobjectreforderpos(),
        o.getobjectoperation(),
        o.getobjectconsistency(),
        attrs);
  }
}
