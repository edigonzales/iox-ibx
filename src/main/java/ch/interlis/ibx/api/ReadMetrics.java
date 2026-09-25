package ch.interlis.ibx.api;

public final class ReadMetrics {
  public long logicalReads, logicalBytes, prefetchedBytes, additionalRangeBytes, maxCacheBytes;
  public long requests,
      bytesRead,
      indexBytes,
      chunkBytes,
      metadataBytes,
      cacheHits,
      chunksRead,
      indexPages;

  public void reset() {
    logicalReads = logicalBytes = prefetchedBytes = additionalRangeBytes = maxCacheBytes = 0;
    requests =
        bytesRead =
            indexBytes = chunkBytes = metadataBytes = cacheHits = chunksRead = indexPages = 0;
  }
}
