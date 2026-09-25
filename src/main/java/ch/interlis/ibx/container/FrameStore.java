package ch.interlis.ibx.container;

import ch.interlis.ibx.api.ReadMetrics;
import ch.interlis.ibx.remote.RangeSource;
import java.io.*;
import java.util.*;

public final class FrameStore {
  public final RangeSource source;
  public final ReadMetrics metrics;
  private final long budget;
  private long used;
  private final LinkedHashMap<Long, Frames.Frame> cache =
      new LinkedHashMap<Long, Frames.Frame>(16, .75f, true);

  public FrameStore(RangeSource source, ReadMetrics metrics, long budget) {
    this.source = source;
    this.metrics = metrics;
    this.budget = budget;
  }

  public synchronized Frames.Frame read(long offset) throws IOException {
    return read(new FrameRef(offset, 0));
  }

  public synchronized Frames.Frame read(FrameRef ref) throws IOException {
    metrics.maxCacheBytes = Math.max(metrics.maxCacheBytes, used);
    metrics.logicalReads++;
    if (ref.length > 0) metrics.logicalBytes += ref.length;
    long offset = ref.offset;
    if (ref.length > 0) ref.validate(source.size() - Frames.FOOTER_SIZE);
    Frames.Frame f = cache.get(offset);
    if (f != null) {
      if (ref.length != 0 && f.data.length + Frames.FRAME_HEADER != ref.length)
        throw new IOException("Cached reference length mismatch");
      metrics.cacheHits++;
      return f;
    }
    f = ref.length == 0 ? Frames.read(source, offset) : Frames.read(source, ref);
    if (ref.length == 0) metrics.logicalBytes += f.data.length + Frames.FRAME_HEADER;
    record(f);
    cache(offset, f);
    return f;
  }

  private void record(Frames.Frame f) {
    long size = f.data.length + Frames.FRAME_HEADER;
    if (f.type == Frames.CHUNK) {
      metrics.chunkBytes += size;
      metrics.chunksRead++;
    } else if (f.type >= Frames.LEAF) {
      metrics.indexBytes += size;
      metrics.indexPages++;
    } else metrics.metadataBytes += size;
  }

  private void cache(long offset, Frames.Frame f) {
    long size = f.data.length + Frames.FRAME_HEADER;
    if (size <= budget && !cache.containsKey(offset)) {
      while (used + size > budget && !cache.isEmpty()) {
        Map.Entry<Long, Frames.Frame> e = cache.entrySet().iterator().next();
        used -= e.getValue().data.length + Frames.FRAME_HEADER;
        cache.remove(e.getKey());
      }
      cache.put(offset, f);
      used += size;
      metrics.maxCacheBytes = Math.max(metrics.maxCacheBytes, used);
    }
  }

  /** Merged ranges are temporary; only verified frames enter the shared byte-budgeted cache. */
  public synchronized void prefetch(List<FrameRef> references, int gap, int maximum)
      throws IOException {
    TreeMap<Long, FrameRef> pending = new TreeMap<Long, FrameRef>();
    for (FrameRef ref : references) {
      ref.validate(source.size() - Frames.FOOTER_SIZE);
      if (ref.length <= budget && ref.length <= maximum && !cache.containsKey(ref.offset))
        pending.put(ref.offset, ref);
    }
    List<FrameRef> group = new ArrayList<FrameRef>();
    long start = 0, end = 0, total = 0;
    for (FrameRef ref : pending.values()) {
      if (!group.isEmpty()
          && (ref.offset - end > gap
              || ref.offset + ref.length - start > maximum
              || total + ref.length > budget)) {
        fetch(group, start, end);
        group.clear();
        total = 0;
      }
      if (group.isEmpty()) start = ref.offset;
      group.add(ref);
      end = ref.offset + ref.length;
      total += ref.length;
    }
    if (!group.isEmpty()) fetch(group, start, end);
  }

  /** Prefetch nearby HTTP sibling pages when every page fits together in cache. */
  public synchronized void prefetchSpatial(List<FrameRef> refs) throws IOException {
    long total = 0;
    for (FrameRef ref : refs) total += ref.length;
    if (refs.size() > 1 && total <= budget && total <= 1024 * 1024) prefetch(refs, 0, 1024 * 1024);
  }

  private void fetch(List<FrameRef> group, long start, long end) throws IOException {
    byte[] bytes = source.read(start, (int) (end - start));
    long useful = 0;
    List<Frames.Frame> frames = new ArrayList<Frames.Frame>();
    for (FrameRef ref : group) {
      frames.add(
          Frames.decode(
              Arrays.copyOfRange(
                  bytes, (int) (ref.offset - start), (int) (ref.offset - start + ref.length)),
              ref));
      useful += ref.length;
    }
    metrics.prefetchedBytes += useful;
    metrics.additionalRangeBytes += bytes.length - useful;
    for (int i = 0; i < group.size(); i++) {
      record(frames.get(i));
      cache(group.get(i).offset, frames.get(i));
    }
  }

  public synchronized void clear() {
    cache.clear();
    used = 0;
  }
}
