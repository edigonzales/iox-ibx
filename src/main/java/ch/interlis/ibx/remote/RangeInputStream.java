package ch.interlis.ibx.remote;

import java.io.*;

/** Independent forward cursor over an already pinned source; does not own the source. */
public final class RangeInputStream extends InputStream {
  private final RangeSource source;
  private long offset;

  public RangeInputStream(RangeSource source) {
    this.source = source;
  }

  public int read() throws IOException {
    byte[] b = new byte[1];
    return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
  }

  public int read(byte[] b, int off, int len) throws IOException {
    if (len == 0) return 0;
    long available = source.size() - offset;
    if (available <= 0) return -1;
    int n = (int) Math.min(available, len);
    byte[] data = source.read(offset, n);
    System.arraycopy(data, 0, b, off, n);
    offset += n;
    return n;
  }
}
