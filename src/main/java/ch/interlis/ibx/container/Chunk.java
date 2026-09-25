package ch.interlis.ibx.container;

import ch.interlis.ibx.codec.Cbor;
import com.github.luben.zstd.Zstd;
import java.io.*;
import java.util.zip.*;

public final class Chunk {
  public static final class Info {
    public long id, basketPosition, basketOffset, basketLength;

    public long firstFid;

    public String className, topic, bid, compression;
    public int count;
    public long uncompressedLength;
  }

  public final Info info;
  public final byte[] objects;

  public Chunk(Info info, byte[] objects) {
    this.info = info;
    this.objects = objects;
  }

  public static byte[] pack(Info info, byte[] raw, int level) throws IOException {
    info.uncompressedLength = raw.length;
    byte[] compressed;
    if (info.compression.equals("zstd")) compressed = Zstd.compress(raw, level);
    else if (info.compression.equals("deflate")) {
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      Deflater deflater = new Deflater(level);
      try (DeflaterOutputStream z = new DeflaterOutputStream(b, deflater)) {
        z.write(raw);
      } finally {
        deflater.end();
      }
      compressed = b.toByteArray();
    } else if (info.compression.equals("none")) compressed = raw;
    else throw new IOException("Unsupported compression");
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(b);
    byte[] header = Cbor.bytes(info);
    out.writeInt(header.length);
    out.write(header);
    out.write(compressed);
    return b.toByteArray();
  }

  public static Chunk unpack(byte[] bytes) throws IOException {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
    int n = in.readInt();
    if (n < 0 || n > bytes.length - 4) throw new IOException("Invalid chunk header");
    byte[] h = new byte[n];
    in.readFully(h);
    Info info = Cbor.read(h, Info.class);
    if (info.firstFid < 0
        || info.firstFid > Long.MAX_VALUE - info.count
        || info.count < 1
        || info.uncompressedLength < 0
        || info.uncompressedLength > Integer.MAX_VALUE - 32)
      throw new IOException("Invalid chunk dimensions");
    byte[] compressed = new byte[in.available()];
    in.readFully(compressed);
    byte[] raw;
    if (info.compression.equals("zstd")) {
      raw = new byte[(int) info.uncompressedLength];
      long result = Zstd.decompress(raw, compressed);
      if (Zstd.isError(result) || result != raw.length)
        throw new IOException("Invalid Zstandard chunk");
    } else if (info.compression.equals("deflate")) {
      raw = new byte[(int) info.uncompressedLength];
      try (DataInputStream zip =
          new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed)))) {
        zip.readFully(raw);
        if (zip.read() != -1) throw new IOException("Inflated length mismatch");
      }
    } else if (info.compression.equals("none")) raw = compressed;
    else throw new IOException("Unsupported compression " + info.compression);
    if (raw.length != info.uncompressedLength) throw new IOException("Chunk length mismatch");
    return new Chunk(info, raw);
  }
}
