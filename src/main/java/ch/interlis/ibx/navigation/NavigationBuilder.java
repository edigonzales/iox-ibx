package ch.interlis.ibx.navigation;

import ch.interlis.ibx.api.*;
import ch.interlis.ibx.codec.Cbor;
import ch.interlis.ibx.container.*;
import ch.interlis.ibx.geometry.*;
import ch.interlis.ibx.index.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Derived directory entries. Sorting bounds memory independently of transfer size. */
public final class NavigationBuilder implements AutoCloseable {
  private final ExternalSort references, statistics;
  private final TransferMetadata metadata;
  private final boolean reverse;

  public NavigationBuilder(Path temp, WriterOptions options, TransferMetadata metadata) {
    references = new ExternalSort(temp, options.sortMemoryBytes);
    statistics = new ExternalSort(temp, options.sortMemoryBytes);
    this.metadata = metadata;
    reverse = options.reverseIndex;
  }

  public void accept(JsonNode record, long fid, BasketContext basket) throws IOException {
    String cls = metadata.dictionary.get(record.get(0).intValue());
    if (!record.get(1).isNull()) {
      Map<String, Object> target = new LinkedHashMap<String, Object>();
      target.put("fid", fid);
      target.put("bid", basket.bid);
      references.add(record.get(1).asText() + "\0!", Cbor.bytes(target));
    }
    if (reverse) references(record, fid, "");
    Map<String, Object> stat = new LinkedHashMap<String, Object>();
    stat.put("className", cls);
    stat.put("bid", basket.bid);
    stat.put("count", 1L);
    Map<String, Object> geometries = new TreeMap<String, Object>();
    for (JsonNode field : record.get(7)) {
      String name = metadata.dictionary.get(field.get(0).intValue());
      for (JsonNode value : field.get(1))
        if (value.has("wkb")) {
          Geometry g = IsoWkb.read(value.get("wkb").binaryValue());
          Map<String, Object> summary = new LinkedHashMap<String, Object>();
          summary.put("extent", GeometryEnvelope.bounds(g));
          summary.put("types", Arrays.asList(g.type + (g.dimension == 3 ? 1000 : 0)));
          geometries.put(name, summary);
        }
    }
    stat.put("geometries", geometries);
    statistics.add(cls + "\0" + basket.bid + "\0" + FilesEx.number(fid), Cbor.bytes(stat));
  }

  private void references(JsonNode record, long fid, String path) throws IOException {
    if (!record.get(2).isNull()) {
      Map<String, Object> edge = new LinkedHashMap<String, Object>();
      edge.put("sourceFid", fid);
      edge.put("path", path);
      edge.put("targetTid", record.get(2).asText());
      edge.put("targetBid", record.get(3).isNull() ? null : record.get(3).asText());
      edge.put("order", record.get(4).asInt());
      references.add(
          record.get(2).asText() + "\0R" + FilesEx.number(fid) + "\0" + path, Cbor.bytes(edge));
    }
    for (JsonNode field : record.get(7)) {
      String name = metadata.dictionary.get(field.get(0).intValue());
      int i = 0;
      for (JsonNode value : field.get(1)) {
        if (value.isArray()) references(value, fid, path + "/" + name + "/" + i);
        i++;
      }
    }
  }

  public void finish(ExternalSort index) throws IOException {
    String tid = null;
    JsonNode target = null;
    try (CloseableIterator<ExternalSort.Entry> it = references.finish()) {
      while (it.hasNext()) {
        ExternalSort.Entry e = it.next();
        String t = e.key.substring(0, e.key.indexOf('\0'));
        if (!t.equals(tid)) {
          tid = t;
          target = null;
        }
        JsonNode value = Cbor.MAPPER.readTree(e.value);
        if (e.key.endsWith("\0!")) {
          target = value;
          continue;
        }
        if (target != null
            && (value.get("targetBid").isNull()
                || value.get("targetBid").asText().equals(target.get("bid").asText()))) {
          index.add(
              "R\0"
                  + target.get("fid").asLong()
                  + "\0"
                  + value.get("sourceFid").asLong()
                  + "\0"
                  + value.get("path").asText(),
              e.value);
        }
      }
    }
    String group = null;
    com.fasterxml.jackson.databind.node.ObjectNode total = null;
    try (CloseableIterator<ExternalSort.Entry> it = statistics.finish()) {
      while (it.hasNext()) {
        ExternalSort.Entry e = it.next();
        String g = e.key.substring(0, e.key.lastIndexOf('\0'));
        com.fasterxml.jackson.databind.node.ObjectNode value =
            (com.fasterxml.jackson.databind.node.ObjectNode) Cbor.MAPPER.readTree(e.value);
        if (!g.equals(group)) {
          if (total != null) index.add("Q\0" + group, Cbor.bytes(total));
          group = g;
          total = value;
        } else {
          total.put("count", total.get("count").asLong() + 1);
          com.fasterxml.jackson.databind.node.ObjectNode gs =
              (com.fasterxml.jackson.databind.node.ObjectNode) total.get("geometries");
          Iterator<String> names = value.get("geometries").fieldNames();
          while (names.hasNext()) {
            String name = names.next();
            JsonNode incoming = value.get("geometries").get(name);
            if (!gs.has(name)) {
              gs.set(name, incoming);
              continue;
            }
            com.fasterxml.jackson.databind.node.ObjectNode summary =
                (com.fasterxml.jackson.databind.node.ObjectNode) gs.get(name);
            BoundingBox box = Cbor.MAPPER.treeToValue(summary.get("extent"), BoundingBox.class);
            box.expand(Cbor.MAPPER.treeToValue(incoming.get("extent"), BoundingBox.class));
            summary.set("extent", Cbor.MAPPER.valueToTree(box));
            com.fasterxml.jackson.databind.node.ArrayNode types =
                (com.fasterxml.jackson.databind.node.ArrayNode) summary.get("types");
            for (JsonNode type : incoming.get("types")) {
              boolean found = false;
              for (JsonNode old : types) if (old.equals(type)) found = true;
              if (!found) types.add(type);
            }
          }
        }
      }
      if (total != null) index.add("Q\0" + group, Cbor.bytes(total));
    }
  }

  public void close() throws IOException {
    try {
      references.close();
    } finally {
      statistics.close();
    }
  }
}
