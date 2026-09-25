package ch.interlis.ibx.benchmark;

import ch.interlis.ibx.api.*;
import ch.interlis.ibx.codec.Cbor;
import ch.interlis.ibx.container.*;
import ch.interlis.ibx.index.ExternalSort;
import ch.interlis.ibx.iox.ModelBridge;
import ch.interlis.iom.IomObject;
import ch.interlis.iom_j.xtf.Xtf24Reader;
import ch.interlis.iom_j.xtf.XtfStartTransferEvent;
import ch.interlis.iom_j.xtf.impl.MyHandler;
import ch.interlis.iox.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

/** Compares two independently parsed XTFs, without consulting the container decoder. */
public final class RoundtripVerifier {
  private RoundtripVerifier() {}

  public static long verify(Path original, Path exported, ModelBridge bridge, Path temporary)
      throws Exception {
    try (ExternalSort left = new ExternalSort(temporary, 8L * 1024 * 1024);
        ExternalSort right = new ExternalSort(temporary, 8L * 1024 * 1024)) {
      parse(original, bridge, left);
      parse(exported, bridge, right);
      long count = 0;
      try (CloseableIterator<ExternalSort.Entry> a = left.finish();
          CloseableIterator<ExternalSort.Entry> b = right.finish()) {
        while (a.hasNext() && b.hasNext()) {
          ExternalSort.Entry x = a.next(), y = b.next();
          if (!x.key.equals(y.key) || !Arrays.equals(x.value, y.value))
            throw new IOException("Semantic roundtrip mismatch at " + x.key.replace('\0', '/'));
          count++;
        }
        if (a.hasNext() || b.hasNext())
          throw new IOException("Semantic roundtrip record count mismatch");
      }
      return count;
    }
  }

  private static void parse(Path file, ModelBridge bridge, ExternalSort records) throws Exception {
    IoxReader reader = Xtf24Reader.createReader(file.toFile());
    ((ch.interlis.iox_j.IoxIliReader) reader).setModel(bridge.model);
    long basket = -1;
    boolean ended = false;
    try {
      IoxEvent e;
      while ((e = reader.read()) != null) {
        if (e instanceof StartTransferEvent) {
          StartTransferEvent s = (StartTransferEvent) e;
          List<String> models = new ArrayList<String>();
          if (s instanceof XtfStartTransferEvent) {
            Map<String, IomObject> header = ((XtfStartTransferEvent) s).getHeaderObjects();
            if (header != null)
              for (IomObject o : header.values()) {
                String model = o.getattrvalue(MyHandler.HEADER_OBJECT_MODELENTRY_NAME);
                if (model != null) models.add(model);
              }
          }
          Collections.sort(models);
          records.add("H", Cbor.bytes(Arrays.asList(s.getSender(), s.getComment(), models)));
        } else if (e instanceof StartBasketEvent) {
          BasketContext context = new BasketContext((StartBasketEvent) e, ++basket);
          records.add("B" + FilesEx.number(basket), Cbor.bytes(context));
        } else if (e instanceof ObjectEvent) {
          IomObject o = ((ObjectEvent) e).getIomObject();
          byte[] canonical = Cbor.bytes(canonical(o, bridge.metadata));
          String id = o.getobjectoid();
          if (id == null) id = FilesEx.sha256(canonical);
          records.add(
              "O" + FilesEx.number(basket) + "\0" + o.getobjecttag() + "\0" + id, canonical);
        } else if (e instanceof EndTransferEvent) {
          ended = true;
          break;
        }
      }
      if (!ended) throw new IOException("Incomplete XTF during semantic comparison");
    } finally {
      reader.close();
    }
  }

  private static Object canonical(IomObject o, TransferMetadata metadata) {
    if ("BOUNDARY".equals(o.getobjecttag()))
      return ch.interlis.ibx.geometry.GeometrySemantics.canonical(o);
    List<Object> value = new ArrayList<Object>();
    Collections.addAll(
        value,
        o.getobjecttag(),
        o.getobjectoid(),
        o.getobjectrefoid(),
        o.getobjectrefbid(),
        o.getobjectreforderpos(),
        o.getobjectoperation(),
        o.getobjectconsistency());
    SortedMap<String, List<Object>> attrs = new TreeMap<String, List<Object>>();
    for (int a = 0; a < o.getattrcount(); a++) {
      String key = o.getattrname(a);
      List<Object> values = new ArrayList<Object>();
      for (int i = 0; i < o.getattrvaluecount(key); i++) {
        IomObject child = o.getattrobj(key, i);
        if (child != null) values.add(canonical(child, metadata));
        else {
          String v = o.getattrprim(key, i);
          boolean numeric =
              metadata.numericTypes.containsKey(o.getobjecttag() + "." + key)
                  || ((o.getobjecttag().equals("COORD") || o.getobjecttag().equals("ARC"))
                      && key.matches("[CAR][123]?"));
          if (v != null && numeric) v = new BigDecimal(v).stripTrailingZeros().toPlainString();
          values.add(v);
        }
      }
      attrs.put(key, values);
    }
    value.add(attrs);
    return value;
  }
}
