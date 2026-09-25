package ch.interlis.ibx.api;

import ch.interlis.ibx.container.*;
import ch.interlis.ibx.geometry.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.math.*;
import java.util.*;
import java.util.stream.*;

/** Lazy direct CBOR/WKB view, without constructing IOM objects. */
public final class GisLayer implements AutoCloseable {
  private final IbxContainer container;
  private final String id, className, attribute;
  private final Set<Closeable> active = new HashSet<Closeable>();
  private boolean closed;

  GisLayer(IbxContainer container, String id) {
    this.container = container;
    this.id = id;
    int dot = id.lastIndexOf('.');
    className = id.substring(0, dot);
    attribute = id.substring(dot + 1);
  }

  public String getId() {
    return id;
  }

  public String getClassName() {
    return className;
  }

  public String getGeometryAttribute() {
    return attribute;
  }

  public TransferMetadata.GeometryDescriptor getGeometryDescriptor() {
    check();
    return container.metadata().geometries.get(id);
  }

  public Map<String, String> fields() {
    check();
    Map<String, String> fields = new LinkedHashMap<String, String>();
    for (TransferMetadata.Property p : container.metadata().classes.get(className)) {
      String type = container.metadata().scalarTypes.get(className + "." + p.name);
      if (type != null) fields.put(p.name, type);
    }
    return Collections.unmodifiableMap(fields);
  }

  private void check() {
    if (closed) throw new IllegalStateException("Layer closed");
    container.checkOpen();
  }

  public Stream<GisFeature> features() throws IOException {
    check();
    return stream(container.getClass(className), null);
  }

  public Stream<GisFeature> queryCandidates(BoundingBox box) throws IOException {
    check();
    return stream(container.querySpatialCandidates(className, attribute, box), box);
  }

  public Optional<GisFeature> getFeature(long fid) throws IOException {
    check();
    try (Fragment f = container.getFid(fid);
        Stream<GisFeature> stream = stream(f, null)) {
      Optional<GisFeature> result = stream.findFirst();
      if (result.isPresent() && result.get().getFid() != fid)
        throw new IOException("Invalid FID reference");
      return result;
    }
  }

  private GisFeature feature(JsonNode record, long fid, BasketContext basket) throws IOException {
    TransferMetadata m = container.metadata();
    if (!record.isArray() || record.size() != 8) throw new IOException("Invalid object record");
    int tag = record.get(0).intValue();
    if (tag < 0 || tag >= m.dictionary.size()) throw new IOException("Invalid dictionary id");
    if (!className.equals(m.dictionary.get(tag))) return null;
    byte[] wkb = null;
    Map<String, Object> values = new LinkedHashMap<String, Object>();
    Map<String, String> types = fields();
    for (String name : types.keySet()) values.put(name, null);
    for (JsonNode field : record.get(7)) {
      int key = field.get(0).intValue();
      if (key < 0 || key >= m.dictionary.size()) throw new IOException("Invalid dictionary id");
      String name = m.dictionary.get(key);
      JsonNode vs = field.get(1);
      if (name.equals(attribute)) {
        if (vs.size() > 1) throw new IOException("Multiple values for geometry attribute " + id);
        if (vs.size() == 1 && !vs.get(0).isNull()) {
          JsonNode v = vs.get(0);
          if (!id.equals(v.path("geometry").asText()) || !v.path("wkb").isBinary())
            throw new IOException("Invalid geometry record");
          wkb = v.get("wkb").binaryValue();
        }
      } else if (types.containsKey(name)) {
        List<Object> list = new ArrayList<Object>();
        for (JsonNode v : vs) {
          if (v.isArray() || v.has("wkb"))
            throw new IOException("Unexpected structured scalar " + name);
          String value =
              v.isNull()
                  ? null
                  : v.has("m")
                      ? new BigDecimal(
                              new BigInteger(v.get("m").binaryValue()), v.get("s").intValue())
                          .toPlainString()
                      : v.asText();
          String type = types.get(name);
          Object parsed = value;
          if (value != null) {
            if (type.equals("integer")) parsed = new BigDecimal(value).toBigIntegerExact();
            else if (type.equals("decimal")) parsed = new BigDecimal(value);
            else if (type.equals("boolean")) {
              if (!value.equals("true") && !value.equals("false"))
                throw new IOException("Invalid boolean");
              parsed = Boolean.valueOf(value);
            }
          }
          list.add(parsed);
        }
        values.put(
            name,
            list.isEmpty()
                ? null
                : list.size() == 1 ? list.get(0) : Collections.unmodifiableList(list));
      }
    }
    return new GisFeature(
        fid, record.get(1).isNull() ? null : record.get(1).asText(), basket, wkb, values);
  }

  private Stream<GisFeature> stream(final Fragment fragment, final BoundingBox box)
      throws IOException {
    final CloseableIterator<Location> refs = fragment.locations();
    final CloseableIterator<GisFeature> it =
        new CloseableIterator<GisFeature>() {
          ObjectCursor cursor;
          Location location;
          long offset = -1, lastFid = -1;
          int ordinal;
          BasketContext basket;
          GisFeature next;
          boolean done;

          public boolean hasNext() {
            check();
            if (next != null) return true;
            if (done) return false;
            try {
              while (true) {
                if (location == null) {
                  if (!refs.hasNext()) {
                    close();
                    return false;
                  }
                  location = refs.next();
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
                boolean targeted = location.ordinal >= 0;
                while (cursor.hasNext()) {
                  int current = ordinal++;
                  JsonNode record = cursor.nextRecord();
                  if (targeted && current != location.ordinal) continue;
                  if (cursor.firstFid < 0 || cursor.firstFid > Long.MAX_VALUE - current)
                    throw new IOException("Invalid FID range");
                  long fid = cursor.firstFid + current;
                  GisFeature candidate = feature(record, fid, basket);
                  if (targeted) location = null;
                  if (candidate != null && fid != lastFid) {
                    byte[] bytes = candidate.getWkb();
                    if (box == null
                        || bytes != null
                            && GeometryEnvelope.bounds(IsoWkb.read(bytes)).intersects(box)) {
                      next = candidate;
                      lastFid = fid;
                      return true;
                    }
                  }
                  if (targeted) break;
                }
                if (targeted && location != null)
                  throw new IOException("Invalid/unordered object ordinal");
                location = null;
              }
            } catch (IOException e) {
              try {
                close();
              } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
              }
              throw new UncheckedIOException(e);
            }
          }

          public GisFeature next() {
            if (!hasNext()) throw new NoSuchElementException();
            GisFeature n = next;
            next = null;
            return n;
          }

          public void close() throws IOException {
            if (done) return;
            done = true;
            if (cursor != null) cursor.close();
            refs.close();
            fragment.close();
            active.remove(this);
          }
        };
    active.add(it);
    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false)
        .onClose(
            () -> {
              try {
                it.close();
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
  }

  public void close() throws IOException {
    if (closed) return;
    closed = true;
    IOException failure = null;
    for (Closeable c : new ArrayList<Closeable>(active))
      try {
        c.close();
      } catch (IOException e) {
        failure = e;
      }
    active.clear();
    if (failure != null) throw failure;
  }
}
