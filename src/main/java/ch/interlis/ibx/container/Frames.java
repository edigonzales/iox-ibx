package ch.interlis.ibx.container;

import ch.interlis.ibx.remote.RangeSource;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

public final class Frames {
  public static final long MAGIC = 0x494258434f4e5431L, FOOTER_MAGIC = 0x494258464f4f5431L;
  public static final int VERSION = 5, HEADER_SIZE = 16, FRAME_HEADER = 16, FOOTER_SIZE = 64;
  public static final int METADATA = 1,
      BASKET = 2,
      CHUNK = 3,
      END_BASKET = 4,
      END_TRANSFER = 5,
      LEAF = 6,
      BRANCH = 7,
      SPATIAL_LEAF = 8,
      SPATIAL_BRANCH = 9,
      SPATIAL_MANIFEST = 10,
      OVERFLOW = 11;

  private Frames() {}

  public static int crc(byte[] data) {
    CRC32 c = new CRC32();
    c.update(data);
    return (int) c.getValue();
  }

  public static void header(DataOutput out) throws IOException {
    out.writeLong(MAGIC);
    out.writeInt(VERSION);
    out.writeInt(0);
  }

  public static int checkHeader(DataInput in) throws IOException {
    long magic = in.readLong();
    int version = in.readInt();
    int features = in.readInt();
    if (magic == 0x494c49434f4e5431L)
      throw new IOException(
          "Containerformat "
              + version
              + " wird nicht mehr unterstützt; aus dem ursprünglichen XTF neu erstellen.");
    if (magic == MAGIC && version != VERSION)
      throw new IOException(
          "Unsupported IBX format " + version + "; expected " + VERSION + "; recreate from source");
    if (magic != MAGIC || version != VERSION || features != 0)
      throw new IOException("Unsupported IBX header/version/features");
    return version;
  }

  public static long write(RandomAccessFile out, int type, byte[] data) throws IOException {
    long offset = out.getFilePointer();
    out.writeInt(type);
    out.writeLong(data.length);
    out.writeInt(crc(data));
    out.write(data);
    return offset;
  }

  public static final class Frame {
    public int type;
    public byte[] data;
    public long end;
  }

  public static Frame read(RangeSource source, long offset) throws IOException {
    if (offset < HEADER_SIZE || offset > source.size() - FRAME_HEADER)
      throw new IOException("Invalid frame offset " + offset);
    byte[] header = source.read(offset, FRAME_HEADER);
    ByteBuffer h = ByteBuffer.wrap(header);
    Frame f = new Frame();
    f.type = h.getInt();
    long length = h.getLong();
    int checksum = h.getInt();
    if (length < 0
        || length > Integer.MAX_VALUE - 32
        || length > source.size() - offset - FRAME_HEADER)
      throw new IOException("Invalid frame length");
    f.data = source.read(offset + FRAME_HEADER, (int) length);
    f.end = offset + FRAME_HEADER + length;
    if (crc(f.data) != checksum) throw new IOException("Frame checksum mismatch at " + offset);
    return f;
  }

  public static Frame read(DataInputStream in) throws IOException {
    Frame f = new Frame();
    f.type = in.readInt();
    long length = in.readLong();
    int checksum = in.readInt();
    if (length < 0 || length > Integer.MAX_VALUE - 32)
      throw new IOException("Invalid frame length");
    f.data = new byte[(int) length];
    in.readFully(f.data);
    if (crc(f.data) != checksum) throw new IOException("Frame checksum mismatch");
    return f;
  }

  public static Frame read(RangeSource source, FrameRef ref) throws IOException {
    ref.validate(source.size() - FOOTER_SIZE);
    byte[] bytes = source.read(ref.offset, (int) ref.length);
    return decode(bytes, ref);
  }

  public static Frame decode(byte[] bytes, FrameRef ref) throws IOException {
    if (bytes.length < FRAME_HEADER
        || ByteBuffer.wrap(bytes).getLong(4) != bytes.length - FRAME_HEADER)
      throw new IOException("Invalid frame length");
    if (bytes.length != ref.length) throw new IOException("Frame reference length mismatch");
    Frame frame = read(new DataInputStream(new ByteArrayInputStream(bytes)));
    if (frame.data.length + FRAME_HEADER != bytes.length)
      throw new IOException("Frame length mismatch");
    frame.end = ref.offset + ref.length;
    return frame;
  }

  public static void footer(RandomAccessFile out, long root, long spatial) throws IOException {
    FrameRef a = FrameRef.at(out, root), b = FrameRef.at(out, spatial);
    ByteBuffer bytes = ByteBuffer.allocate(FOOTER_SIZE);
    bytes
        .putLong(FOOTER_MAGIC)
        .put(a.bytes())
        .put(b.bytes())
        .putLong(out.getFilePointer() + FOOTER_SIZE)
        .putInt(VERSION)
        .putInt(0);
    bytes.putInt(crc(java.util.Arrays.copyOf(bytes.array(), 56))).putInt(0);
    out.write(bytes.array());
  }

  public static long[] footer(RangeSource source) throws IOException {
    long size = source.size();
    if (size < HEADER_SIZE + FOOTER_SIZE) throw new IOException("Truncated container");
    byte[] bytes = source.read(size - FOOTER_SIZE, FOOTER_SIZE);
    ByteBuffer b = ByteBuffer.wrap(bytes);
    long magic = b.getLong(),
        root = b.getLong(),
        rootLength = b.getLong(),
        spatial = b.getLong(),
        spatialLength = b.getLong(),
        storedSize = b.getLong();
    int version = b.getInt(), reserved = b.getInt(), checksum = b.getInt(), tail = b.getInt();
    if (magic != FOOTER_MAGIC
        || storedSize != size
        || version != VERSION
        || reserved != 0
        || tail != 0
        || checksum != crc(java.util.Arrays.copyOf(bytes, 56)))
      throw new IOException("Invalid/truncated footer");
    new FrameRef(root, rootLength).validate(size - FOOTER_SIZE);
    if (spatial != 0) new FrameRef(spatial, spatialLength).validate(size - FOOTER_SIZE);
    else if (spatialLength != 0) throw new IOException("Invalid empty spatial reference");
    return new long[] {root, spatial, rootLength, spatialLength};
  }
}
