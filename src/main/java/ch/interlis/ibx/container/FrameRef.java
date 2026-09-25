package ch.interlis.ibx.container;

import java.io.*;
import java.nio.ByteBuffer;

/** Absolute frame address including its 16-byte framing. */
public final class FrameRef {
  public final long offset, length;

  public FrameRef(long offset, long length) {
    this.offset = offset;
    this.length = length;
  }

  public byte[] bytes() {
    return ByteBuffer.allocate(16).putLong(offset).putLong(length).array();
  }

  public static FrameRef decode(byte[] bytes) throws IOException {
    if (bytes.length != 16) throw new IOException("Invalid frame reference");
    ByteBuffer b = ByteBuffer.wrap(bytes);
    FrameRef ref = new FrameRef(b.getLong(), b.getLong());
    ref.validate(Long.MAX_VALUE);
    return ref;
  }

  public static FrameRef at(RandomAccessFile file, long offset) throws IOException {
    if (offset == 0) return new FrameRef(0, 0);
    long saved = file.getFilePointer();
    try {
      file.seek(offset + 4);
      long length = file.readLong();
      return new FrameRef(offset, Math.addExact(16, length));
    } catch (ArithmeticException e) {
      throw new IOException("Frame length overflow", e);
    } finally {
      file.seek(saved);
    }
  }

  public void validate(long limit) throws IOException {
    if (offset < Frames.HEADER_SIZE
        || length < Frames.FRAME_HEADER
        || length > Integer.MAX_VALUE
        || offset > limit
        || length > limit - offset) throw new IOException("Invalid frame reference bounds");
  }
}
