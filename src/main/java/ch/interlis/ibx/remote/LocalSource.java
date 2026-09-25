package ch.interlis.ibx.remote;

import ch.interlis.ibx.api.ReadMetrics;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

public final class LocalSource implements RangeSource {
  private final RandomAccessFile file;
  private final ReadMetrics metrics;
  private final Path path;
  private final BasicFileAttributes original;

  public LocalSource(Path path, ReadMetrics metrics) throws IOException {
    this.path = path;
    original = Files.readAttributes(path, BasicFileAttributes.class);
    file = new RandomAccessFile(path.toFile(), "r");
    this.metrics = metrics;
  }

  public long size() throws IOException {
    return file.length();
  }

  public synchronized byte[] read(long offset, int length) throws IOException {
    BasicFileAttributes now = Files.readAttributes(path, BasicFileAttributes.class);
    if (original.size() != now.size()
        || !original.lastModifiedTime().equals(now.lastModifiedTime())
        || !java.util.Objects.equals(original.fileKey(), now.fileKey()))
      throw new IOException("Local IBX source changed; reopen file");
    if (offset < 0 || length < 0 || offset > size() - length)
      throw new EOFException("Range outside container");
    byte[] data = new byte[length];
    file.seek(offset);
    file.readFully(data);
    metrics.bytesRead += length;
    return data;
  }

  public void close() throws IOException {
    file.close();
  }
}
