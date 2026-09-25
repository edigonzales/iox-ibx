package ch.interlis.ibx.index;

import ch.interlis.ibx.container.CloseableIterator;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounded-memory runs with bounded fan-in; caller owns the temporary directory. */
public final class ExternalSort implements AutoCloseable {
  public static final class Entry {
    public final String key;
    public final byte[] binaryKey;
    public final byte[] value;

    public Entry(String key, byte[] value) {
      this(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), value);
    }

    public Entry(byte[] key, byte[] value) {
      this.binaryKey = key;
      this.key = new String(key, java.nio.charset.StandardCharsets.UTF_8);
      this.value = value;
    }
  }

  private final Path directory;
  private final long budget;
  private long used;
  private final List<Entry> pending = new ArrayList<Entry>();
  private final List<Path> runs = new ArrayList<Path>();
  private final List<List<Path>> levels = new ArrayList<List<Path>>();
  private boolean finished;
  private boolean structured;

  public ExternalSort(Path directory, long budget) {
    this.directory = directory;
    this.budget = budget;
    // 32^16 runs exceeds the 64-bit file/address space; every bucket stays below 32.
    for (int i = 0; i < 16; i++) levels.add(new ArrayList<Path>());
  }

  public ExternalSort(Path directory, long budget, boolean structured) {
    this(directory, budget);
    this.structured = structured;
  }

  public void add(String key, byte[] value) throws IOException {
    add(
        structured ? Keys.encode(key) : key.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        value);
  }

  public void add(byte[] key, byte[] value) throws IOException {
    if (finished) throw new IllegalStateException("Sort already finished");
    pending.add(new Entry(key, value));
    used += 64 + key.length * 1L + value.length;
    if (used >= budget) flush();
  }

  public static void write(DataOutput out, Entry e) throws IOException {
    byte[] k = e.binaryKey;
    out.writeInt(k.length);
    out.write(k);
    out.writeInt(e.value.length);
    out.write(e.value);
  }

  public static Entry read(DataInputStream in) throws IOException {
    int first = in.read();
    if (first < 0) return null;
    int n =
        (first << 24)
            | (in.readUnsignedByte() << 16)
            | (in.readUnsignedByte() << 8)
            | in.readUnsignedByte();
    if (n < 0) throw new IOException("Invalid sort key length");
    byte[] k = new byte[n];
    in.readFully(k);
    int size = in.readInt();
    if (size < 0) throw new IOException("Invalid sort value length");
    byte[] v = new byte[size];
    in.readFully(v);
    return new Entry(k, v);
  }

  private void flush() throws IOException {
    if (pending.isEmpty()) return;
    pending.sort((a, b) -> Keys.compare(a.binaryKey, b.binaryKey));
    Path run = Files.createTempFile(directory, "sort-", ".run");
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(run)))) {
      for (Entry e : pending) write(out, e);
    }
    addRun(run, 0);
    pending.clear();
    used = 0;
  }

  private void addRun(Path run, int level) throws IOException {
    if (level >= levels.size())
      throw new IOException("External sort exceeds addressable run count");
    List<Path> bucket = levels.get(level);
    bucket.add(run);
    if (bucket.size() == 32) {
      Path merged = Files.createTempFile(directory, "merge-", ".run");
      try (CloseableIterator<Entry> it = new Merge(bucket);
          DataOutputStream out =
              new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(merged)))) {
        while (it.hasNext()) write(out, it.next());
      }
      for (Path p : bucket) Files.delete(p);
      bucket.clear();
      addRun(merged, level + 1);
    }
  }

  public CloseableIterator<Entry> finish() throws IOException {
    if (finished) throw new IllegalStateException("Sort already finished");
    flush();
    finished = true;
    for (List<Path> bucket : levels) {
      runs.addAll(bucket);
      bucket.clear();
    }
    while (runs.size() > 32) {
      List<Path> next = new ArrayList<Path>();
      for (int i = 0; i < runs.size(); i += 32) {
        List<Path> part = new ArrayList<Path>(runs.subList(i, Math.min(i + 32, runs.size())));
        Path run = Files.createTempFile(directory, "merge-", ".run");
        try (CloseableIterator<Entry> it = new Merge(part);
            DataOutputStream out =
                new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(run)))) {
          while (it.hasNext()) write(out, it.next());
        }
        for (Path p : part) Files.delete(p);
        next.add(run);
      }
      runs.clear();
      runs.addAll(next);
    }
    return new Merge(runs);
  }

  public void close() throws IOException {
    pending.clear();
    for (List<Path> bucket : levels) {
      for (Path p : bucket) Files.deleteIfExists(p);
      bucket.clear();
    }
    for (Path p : runs) Files.deleteIfExists(p);
    runs.clear();
  }

  private static final class Merge implements CloseableIterator<Entry> {
    private final List<DataInputStream> inputs = new ArrayList<DataInputStream>();
    private final PriorityQueue<Item> heap =
        new PriorityQueue<Item>((a, b) -> Keys.compare(a.entry.binaryKey, b.entry.binaryKey));

    private static final class Item {
      int input;
      Entry entry;

      Item(int i, Entry e) {
        input = i;
        entry = e;
      }
    }

    Merge(List<Path> runs) throws IOException {
      try {
        for (Path p : runs) {
          DataInputStream in =
              new DataInputStream(new BufferedInputStream(Files.newInputStream(p)));
          inputs.add(in);
          Entry e = read(in);
          if (e != null) heap.add(new Item(inputs.size() - 1, e));
        }
      } catch (IOException e) {
        close();
        throw e;
      }
    }

    public boolean hasNext() {
      return !heap.isEmpty();
    }

    public Entry next() {
      if (heap.isEmpty()) throw new NoSuchElementException();
      Item i = heap.remove();
      Entry result = i.entry;
      try {
        i.entry = read(inputs.get(i.input));
        if (i.entry != null) heap.add(i);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return result;
    }

    public void close() throws IOException {
      IOException failure = null;
      for (DataInputStream in : inputs)
        try {
          in.close();
        } catch (IOException e) {
          failure = e;
        }
      heap.clear();
      if (failure != null) throw failure;
    }
  }
}
