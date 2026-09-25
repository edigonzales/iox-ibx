package ch.interlis.ibx.codec;

import ch.interlis.ibx.api.TransferMetadata;
import ch.interlis.ibx.geometry.*;
import ch.interlis.iom.IomObject;
import ch.interlis.iom_j.Iom_jObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.math.*;
import java.util.*;

/** Explicit recursive IOM representation, never Java serialization. */
public final class ObjectCodec {
  private final TransferMetadata metadata;
  private final Map<String, Integer> ids = new HashMap<String, Integer>();
  private boolean frozen;

  public ObjectCodec(TransferMetadata metadata, boolean frozen) {
    this.metadata = metadata;
    this.frozen = frozen;
    for (int i = 0; i < metadata.dictionary.size(); i++) ids.put(metadata.dictionary.get(i), i);
  }

  private int id(String name) {
    Integer n = ids.get(name);
    if (n == null) {
      if (frozen) throw new IllegalArgumentException("Unknown dictionary name: " + name);
      n = ids.size();
      ids.put(name, n);
      metadata.dictionary.add(name);
    }
    return n;
  }

  private String name(int id) throws IOException {
    if (id < 0 || id >= metadata.dictionary.size())
      throw new IOException("Invalid dictionary id " + id);
    return metadata.dictionary.get(id);
  }

  public byte[] encode(IomObject o) throws IOException {
    return Cbor.bytes(node(o));
  }

  private ArrayNode node(IomObject o) throws IOException {
    ArrayNode a = Cbor.MAPPER.createArrayNode();
    a.add(id(o.getobjecttag()));
    a.add(o.getobjectoid());
    a.add(o.getobjectrefoid());
    a.add(o.getobjectrefbid());
    a.add(o.getobjectreforderpos());
    a.add(o.getobjectoperation());
    a.add(o.getobjectconsistency());
    ArrayNode attrs = a.addArray();
    for (int i = 0; i < o.getattrcount(); i++) {
      String key = o.getattrname(i);
      // IOX internal database identity is not an INTERLIS transfer attribute.
      if (ch.interlis.iom_j.Iom_jObject.INTERNAL_T_ID.equals(key)) continue;
      ArrayNode field = attrs.addArray();
      field.add(id(key));
      ArrayNode values = field.addArray();
      for (int j = 0; j < o.getattrvaluecount(key); j++) {
        IomObject child = o.getattrobj(key, j);
        if (child != null
            && "wkb".equals(metadata.geometryEncoding)
            && (IomGeometry.isGeometry(child.getobjecttag())
                || metadata.geometries.containsKey(o.getobjecttag() + "." + key))) {
          String path = o.getobjecttag() + "." + key;
          TransferMetadata.GeometryDescriptor descriptor = metadata.geometries.get(path);
          if (descriptor == null) throw new IOException("Unknown geometry descriptor " + path);
          try {
            if (o.getattrvaluecount(key) != 1)
              throw new IOException("Multiple geometry attribute values");
            String expected =
                descriptor.type.equals("CoordType")
                    ? "COORD"
                    : descriptor.type.equals("MultiCoordType")
                        ? "MULTICOORD"
                        : descriptor.type.equals("PolylineType")
                            ? "POLYLINE"
                            : descriptor.type.equals("MultiPolylineType")
                                ? "MULTIPOLYLINE"
                                : "MULTISURFACE";
            if (!expected.equals(child.getobjecttag()))
              throw new IOException("Model/geometry type mismatch");
            long verificationStart = System.nanoTime();
            byte[] wkb;
            try {
              wkb = IomGeometry.encode(child, descriptor.multiSurface);
            } finally {
              metadata.geometryVerificationNanos += System.nanoTime() - verificationStart;
            }
            Geometry geometry = IsoWkb.read(wkb);
            if (geometry.dimension != descriptor.dimension)
              throw new IOException("Model/geometry dimension mismatch");
            if (descriptor.crs == null || descriptor.crs.trim().isEmpty())
              throw new IOException("Unresolved CRS; supply --geometry-crs " + path + "=CRS");
            ObjectNode marker = values.addObject();
            marker.put("geometry", path);
            marker.put("root", child.getobjecttag());
            marker.put("wkb", wkb);
          } catch (IOException ex) {
            throw new IOException(path + ": " + ex.getMessage(), ex);
          }
        } else if (child != null) values.add(node(child));
        else {
          String value = o.getattrprim(key, j);
          if (value != null
              && "wkb".equals(metadata.geometryEncoding)
              && metadata.geometries.containsKey(o.getobjecttag() + "." + key))
            throw new IOException("Primitive geometry value: " + o.getobjecttag() + "." + key);
          boolean number =
              metadata.numericTypes.containsKey(o.getobjecttag() + "." + key)
                  || ((o.getobjecttag().equals("COORD") || o.getobjecttag().equals("ARC"))
                      && key.matches("[CAR][123]?"));
          if (value != null && number && metadata.numericEncoding.equals("decimal")) {
            BigDecimal d = new BigDecimal(value);
            ObjectNode n = values.addObject();
            n.put("m", d.unscaledValue().toByteArray());
            n.put("s", d.scale());
          } else values.add(value);
        }
      }
    }
    return a;
  }

  public IomObject decode(byte[] bytes) throws IOException {
    return object(Cbor.MAPPER.readTree(bytes));
  }

  public IomObject object(JsonNode a) throws IOException {
    if (!a.isArray() || a.size() != 8) throw new IOException("Invalid object record");
    Iom_jObject o = new Iom_jObject(name(a.get(0).intValue()), str(a.get(1)));
    o.setobjectrefoid(str(a.get(2)));
    o.setobjectrefbid(str(a.get(3)));
    o.setobjectreforderpos(a.get(4).longValue());
    o.setobjectoperation(a.get(5).intValue());
    o.setobjectconsistency(a.get(6).intValue());
    for (JsonNode field : a.get(7)) {
      String key = name(field.get(0).intValue());
      JsonNode values = field.get(1);
      if (values.size() == 0) o.setattrundefined(key);
      for (JsonNode v : values) {
        if (v.isArray()) o.addattrobj(key, object(v));
        else if (v.isObject() && v.has("wkb")) {
          if (!"wkb".equals(metadata.geometryEncoding)
              || !metadata.geometries.containsKey(v.path("geometry").asText())
              || !(o.getobjecttag() + "." + key).equals(v.path("geometry").asText()))
            throw new IOException("Invalid geometry descriptor reference");
          Geometry g = IsoWkb.read(v.get("wkb").binaryValue());
          if (g.dimension != metadata.geometries.get(v.get("geometry").asText()).dimension)
            throw new IOException("Geometry dimension mismatch");
          o.addattrobj(key, IomGeometry.toIom(g, v.path("root").asText()));
        } else if (v.isObject())
          o.addattrvalue(
              key,
              new BigDecimal(new BigInteger(v.get("m").binaryValue()), v.get("s").intValue())
                  .toPlainString());
        else o.addattrvalue(key, str(v));
      }
    }
    return o;
  }

  public ch.interlis.ibx.api.BoundingBox geometryBounds(JsonNode record, String attribute)
      throws IOException {
    ch.interlis.ibx.api.BoundingBox result = null;
    String tag = name(record.get(0).intValue());
    for (JsonNode field : record.get(7))
      if (attribute.equals(name(field.get(0).intValue()))) {
        for (JsonNode v : field.get(1)) {
          if (v.isNull()) continue;
          String path = tag + "." + attribute;
          if (!v.has("wkb")
              || !path.equals(v.path("geometry").asText())
              || !metadata.geometries.containsKey(path))
            throw new IOException("Invalid geometry marker");
          Geometry geometry = IsoWkb.read(v.get("wkb").binaryValue());
          if (geometry.dimension != metadata.geometries.get(path).dimension)
            throw new IOException("Geometry dimension mismatch");
          ch.interlis.ibx.api.BoundingBox box = GeometryEnvelope.bounds(geometry);
          if (result == null) result = box;
          else result.expand(box);
        }
      }
    return result;
  }

  private static String str(JsonNode n) {
    return n == null || n.isNull() ? null : n.asText();
  }
}
