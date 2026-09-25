package ch.interlis.ibx.api;

public final class RemoteOptions {
  public int prefetchPositions = 32;
  public int prefetchMaxGap = 4096;
  public int prefetchMaxBytes = 1024 * 1024;

  public void validate() {
    if (cacheBytes < 0
        || prefetchPositions < 0
        || prefetchPositions > 32
        || prefetchMaxGap < 0
        || prefetchMaxGap > 4096
        || prefetchMaxBytes < 1
        || prefetchMaxBytes > 1024 * 1024)
      throw new IllegalArgumentException("Invalid cache/prefetch limits");
  }

  public boolean immutableUrl;
  public boolean allowFullDownload;
  public int connectTimeoutMillis = 15000;
  public int readTimeoutMillis = 60000;
  public long cacheBytes = 32L * 1024 * 1024;
}
