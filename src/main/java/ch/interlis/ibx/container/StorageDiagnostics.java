package ch.interlis.ibx.container;

import ch.interlis.ibx.api.*;
import java.io.*;
import java.util.*;

/** Counts physical bytes, keeping shared page framing separate from per-entry fields. */
public final class StorageDiagnostics {
  public final Map<Integer, Long> sectionBytes = new TreeMap<Integer, Long>();
  public final Map<String, Long> keyBytes = new TreeMap<String, Long>();
  public final Map<String, Long> valueBytes = new TreeMap<String, Long>();
  public long sharedIndexPageBytes;
  public long headerFooterBytes = Frames.HEADER_SIZE + Frames.FOOTER_SIZE;
  public long fileBytes;

  private static <K> void add(Map<K, Long> map, K key, long value) {
    Long old = map.get(key);
    map.put(key, (old == null ? 0 : old) + value);
  }

  public static StorageDiagnostics inspect(IbxContainer container) throws IOException {
    StorageDiagnostics result = new StorageDiagnostics();
    result.fileBytes = container.size();
    long end = result.fileBytes - Frames.FOOTER_SIZE;
    for (long offset = Frames.HEADER_SIZE; offset < end; ) {
      byte[] header = container.frameStore().source.read(offset, Frames.FRAME_HEADER);
      java.nio.ByteBuffer h = java.nio.ByteBuffer.wrap(header);
      int type = h.getInt();
      long length = h.getLong();
      if (length < 0 || length > end - offset - Frames.FRAME_HEADER)
        throw new IOException("Invalid section length");
      long size = Frames.FRAME_HEADER + length;
      add(result.sectionBytes, type, size);
      if (type == Frames.LEAF || type == Frames.BRANCH) {
        Frames.Frame frame = container.frameStore().read(new FrameRef(offset, size));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.data));
        int count = in.readInt();
        result.sharedIndexPageBytes += Frames.FRAME_HEADER + 4;
        String kind = null;
        if (count < 0 || count > frame.data.length / 12)
          throw new IOException("Invalid page count");
        for (int i = 0; i < count; i++) {
          int prefix = in.readInt();
          int n = in.readInt();
          byte[] key;
          long physicalKeyBytes = 8;
          if (n == -1) {
            FrameRef ref = new FrameRef(in.readLong(), in.readLong());
            key = container.frameStore().read(ref).data;
            physicalKeyBytes += 16;
          } else {
            if (n < 0 || n > in.available()) throw new IOException("Invalid key size");
            key = new byte[n];
            in.readFully(key);
            physicalKeyBytes += n;
          }
          if (prefix == 0) {
            if (key.length == 0) throw new IOException("Empty directory key");
            kind = Character.toString((char) key[0]);
          }
          if (kind == null) throw new IOException("Missing first key");
          String category = (type == Frames.LEAF ? "leaf:" : "branch:") + kind;
          add(result.keyBytes, category, physicalKeyBytes);
          int v = in.readInt();
          long physicalValueBytes = 4;
          if (v == -1) {
            in.readLong();
            in.readLong();
            physicalValueBytes += 16;
          } else {
            if (v < 0 || v > in.available()) throw new IOException("Invalid value size");
            in.skipBytes(v);
            physicalValueBytes += v;
          }
          add(result.valueBytes, category, physicalValueBytes);
        }
        if (in.available() != 0) throw new IOException("Trailing page bytes");
      }
      offset += size;
    }
    return result;
  }
}
