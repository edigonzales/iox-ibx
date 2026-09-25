package ch.interlis.ibx.remote;

import java.io.*;

public interface RangeSource extends Closeable {
  default String revision() throws IOException {
    return Long.toString(size());
  }

  long size() throws IOException;

  byte[] read(long offset, int length) throws IOException;
}
