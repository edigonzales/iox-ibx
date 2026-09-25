package ch.interlis.ibx.navigation;

import ch.interlis.ibx.api.*;
import ch.interlis.ibx.codec.Cbor;
import ch.interlis.ibx.container.*;
import ch.interlis.ibx.index.*;
import ch.interlis.ibx.spatial.SpatialIndex;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.math.*;
import java.util.*;

/** GUI-independent read-only object and navigation API. Caller serializes access per container. */
public final class Navigation {
  private final IbxContainer container;
  private final TransferMetadata meta;
  private final BTree tree;

  public Navigation(IbxContainer c) {
    container = c;
    meta = c.metadata();
    tree = new BTree(c.frameStore(), c.indexRef());
  }

  public Map<String, Object> describe() throws IOException {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("metadata", meta);
    result.put("spatial", SpatialIndex.manifest(container));
    return result;
  }

  public Map<String, Object> baskets(String after, int limit) throws IOException {
    if (limit < 1 || limit > 256) throw new IOException("Page size must be 1..256");
    List<Object> items = new ArrayList<Object>();
    byte[] last = null;
    boolean more;
    try (CloseableIterator<ExternalSort.Entry> it =
        tree.rangeAfter("P\0", after == null ? null : Base64.getUrlDecoder().decode(after))) {
      while (items.size() < limit && it.hasNext()) {
        ExternalSort.Entry e = it.next();
        last = e.binaryKey;
        BasketContext b = container.basket(Location.decode(e.value));
        items.add(map("bid", b.bid, "topic", b.topic));
      }
      more = it.hasNext();
    }
    return map("items", items, "next", more ? Base64.getUrlEncoder().encodeToString(last) : null);
  }

  public Map<String, Object> catalog(String after, int limit) throws IOException {
    return indexPage("Q\0", after, limit, false);
  }

  public Map<String, Object> related(long fid, String after, int limit) throws IOException {
    if (!meta.reverseIndex) return map("indexed", false, "items", Collections.emptyList());
    Map<String, Object> page = indexPage("R\0" + fid + "\0", after, limit, true);
    page.put("indexed", true);
    return page;
  }

  private Map<String, Object> indexPage(String prefix, String after, int limit, boolean objects)
      throws IOException {
    if (limit < 1 || limit > 256) throw new IOException("Page size must be 1..256");
    List<Object> rows = new ArrayList<Object>();
    byte[] last = null;
    boolean more;
    try (CloseableIterator<ExternalSort.Entry> it =
        tree.rangeAfter(prefix, after == null ? null : Base64.getUrlDecoder().decode(after))) {
      while (rows.size() < limit && it.hasNext()) {
        ExternalSort.Entry e = it.next();
        last = e.binaryKey;
        Map<String, Object> row = Cbor.read(e.value, LinkedHashMap.class);
        if (objects) row.put("object", object(((Number) row.get("sourceFid")).longValue()));
        rows.add(row);
      }
      more = it.hasNext();
    }
    return map("items", rows, "next", more ? Base64.getUrlEncoder().encodeToString(last) : null);
  }

  public Map<String, Object> object(long fid) throws IOException {
    try (Fragment f = container.getFid(fid);
        FeatureCursor cursor = new FeatureCursor(f, null, Collections.<String>emptySet())) {
      return cursor.hasNext() ? cursor.next() : null;
    }
  }

  public Map<String, Object> resolve(String tid, String bid) throws IOException {
    try (Fragment f = container.getObject(tid);
        FeatureCursor cursor = new FeatureCursor(f, null, Collections.<String>emptySet())) {
      if (!cursor.hasNext()) return null;
      Map<String, Object> obj = cursor.next();
      return bid == null || bid.equals(obj.get("bid")) ? obj : null;
    }
  }

  public FeatureCursor query(String cls, String geometry, BoundingBox box, Set<String> bids)
      throws IOException {
    if (!meta.concreteClasses.contains(cls))
      throw new IOException("Unknown concrete class: " + cls);
    Fragment f =
        box == null
            ? container.getClass(cls)
            : container.querySpatialCandidates(cls, geometry, box);
    return new FeatureCursor(f, cls, bids);
  }

  public final class FeatureCursor implements CloseableIterator<Map<String, Object>> {
    private final Fragment fragment;
    private final CloseableIterator<Location> locations;
    private final String cls;
    private final Set<String> bids;
    private ObjectCursor cursor;
    private Location location;
    private BasketContext basket;
    private long offset = -1;
    private int ordinal;
    private boolean closed;
    private Map<String, Object> next;

    FeatureCursor(Fragment fragment, String cls, Set<String> bids) throws IOException {
      this.fragment = fragment;
      this.locations = fragment.locations();
      this.cls = cls;
      this.bids = bids;
    }

    public boolean hasNext() {
      if (next != null) return true;
      if (closed) return false;
      try {
        while (true) {
          if (location == null) {
            if (!locations.hasNext()) {
              close();
              return false;
            }
            location = locations.next();
          }
          if (location.chunkOffset == 0) {
            location = null;
            continue;
          }
          if (cursor == null || offset != location.chunkOffset) {
            if (cursor != null) cursor.close();
            cursor = container.objects(location);
            offset = location.chunkOffset;
            ordinal = 0;
            basket = container.basket(location);
          }
          if (!bids.isEmpty() && !bids.contains(basket.bid)) {
            location = null;
            continue;
          }
          boolean targeted = location.ordinal >= 0;
          while (cursor.hasNext()) {
            int current = ordinal++;
            JsonNode record = cursor.nextRecord();
            if (targeted && current != location.ordinal) continue;
            if (targeted) location = null;
            String type = meta.dictionary.get(record.get(0).asInt());
            if (cls == null || cls.equals(type)) {
              next = decode(record);
              next.put("fid", cursor.firstFid + current);
              next.put("bid", basket.bid);
              return true;
            }
            if (targeted) break;
          }
          location = null;
        }
      } catch (IOException ex) {
        try {
          close();
        } catch (IOException suppressed) {
          ex.addSuppressed(suppressed);
        }
        throw new UncheckedIOException(ex);
      }
    }

    public Map<String, Object> next() {
      if (!hasNext()) throw new NoSuchElementException();
      Map<String, Object> out = next;
      next = null;
      return out;
    }

    public void close() throws IOException {
      if (closed) return;
      closed = true;
      try {
        if (cursor != null) cursor.close();
      } finally {
        try {
          locations.close();
        } finally {
          fragment.close();
        }
      }
    }
  }

  public Map<String, Object> decode(JsonNode record) throws IOException {
    String cls = meta.dictionary.get(record.get(0).asInt());
    Map<String, Object> fields = new LinkedHashMap<String, Object>();
    for (JsonNode f : record.get(7)) {
      String name = meta.dictionary.get(f.get(0).asInt());
      List<Object> values = new ArrayList<Object>();
      for (JsonNode v : f.get(1)) {
        if (v.isArray()) {
          Map<String, Object> child = decode(v);
          child.put("kind", v.get(2).isNull() ? "structure" : "reference");
          if (!v.get(2).isNull()) {
            child.put("tid", v.get(2).asText());
            child.put("bid", v.get(3).isNull() ? null : v.get(3).asText());
            child.put("order", v.get(4).asInt());
          }
          values.add(child);
        } else if (v.has("wkb"))
          values.add(
              map(
                  "kind",
                  "geometry",
                  "descriptor",
                  v.get("geometry").asText(),
                  "wkb",
                  Base64.getEncoder().encodeToString(v.get("wkb").binaryValue())));
        else {
          String type = meta.scalarTypes.get(cls + "." + name);
          String value =
              v.isNull()
                  ? null
                  : v.has("m")
                      ? new BigDecimal(new BigInteger(v.get("m").binaryValue()), v.get("s").asInt())
                          .toPlainString()
                      : v.asText();
          values.add(map("kind", "scalar", "type", type, "value", value));
        }
      }
      fields.put(name, values);
    }
    return map(
        "className",
        cls,
        "tid",
        record.get(1).isNull() ? null : record.get(1).asText(),
        "fields",
        fields);
  }

  public static Map<String, Object> map(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
    return m;
  }
}
