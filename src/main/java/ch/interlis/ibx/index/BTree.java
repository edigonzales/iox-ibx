package ch.interlis.ibx.index;

import ch.interlis.ibx.container.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/** Immutable B+ tree; leaves and branch levels are built from sequential disk streams. */
public final class BTree {
  private static final int PAGE = 16 * 1024;
  private final FrameStore store;
  private final FrameRef root;

  public BTree(FrameStore store, long root) {
    this(store, new FrameRef(root, 0));
  }

  public BTree(FrameStore store, FrameRef root) {
    this.store = store;
    this.root = root;
  }

  public static long build(
      RandomAccessFile out, CloseableIterator<ExternalSort.Entry> sorted, Path tmp)
      throws IOException {
    Path current = Files.createTempFile(tmp, "level-", ".run");
    long count = 0;
    byte[] previous = null;
    try (DataOutputStream refs =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(current)))) {
      List<ExternalSort.Entry> page = new ArrayList<ExternalSort.Entry>();
      int bytes = 4;
      while (sorted.hasNext()) {
        ExternalSort.Entry e = sorted.next();
        if (Arrays.equals(e.binaryKey, previous))
          throw new IOException("Duplicate identity/index key: " + e.key.replace('\0', '/'));
        previous = e.binaryKey;
        if (bytes + entrySize(page, e) > PAGE && !page.isEmpty()) {
          ref(refs, out, page, true);
          count++;
          page.clear();
          bytes = 4;
        }
        bytes += entrySize(page, e);
        page.add(e);
      }
      if (!page.isEmpty() || count == 0) {
        ref(refs, out, page, true);
        count++;
      }
    }
    while (count > 1) {
      Path next = Files.createTempFile(tmp, "level-", ".run");
      long n = 0;
      try (DataInputStream in =
              new DataInputStream(new BufferedInputStream(Files.newInputStream(current)));
          DataOutputStream refs =
              new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(next)))) {
        List<ExternalSort.Entry> page = new ArrayList<ExternalSort.Entry>();
        int bytes = 4;
        ExternalSort.Entry e;
        while ((e = ExternalSort.read(in)) != null) {
          if (bytes + entrySize(page, e) > PAGE && page.size() >= 2) {
            ref(refs, out, page, false);
            n++;
            page.clear();
            bytes = 4;
          }
          bytes += entrySize(page, e);
          page.add(e);
        }
        if (!page.isEmpty()) {
          ref(refs, out, page, false);
          n++;
        }
      }
      Files.delete(current);
      current = next;
      count = n;
    }
    try (DataInputStream in = new DataInputStream(Files.newInputStream(current))) {
      return ByteBuffer.wrap(ExternalSort.read(in).value).getLong();
    } finally {
      Files.delete(current);
    }
  }

  private static int entrySize(List<ExternalSort.Entry> page, ExternalSort.Entry entry) {
    int prefix =
        page.isEmpty() ? 0 : Keys.common(page.get(page.size() - 1).binaryKey, entry.binaryKey);
    int suffix = entry.binaryKey.length - prefix;
    return 12
        + (suffix > PAGE / 2 ? 16 : suffix)
        + (entry.value.length > PAGE / 2 ? 16 : entry.value.length);
  }

  private static void ref(
      DataOutputStream refs, RandomAccessFile out, List<ExternalSort.Entry> entries, boolean leaf)
      throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream data = new DataOutputStream(bytes);
    data.writeInt(entries.size());
    byte[] previous = new byte[0];
    for (ExternalSort.Entry e : entries) {
      int prefix = Keys.common(previous, e.binaryKey);
      data.writeInt(prefix);
      writeField(data, out, Arrays.copyOfRange(e.binaryKey, prefix, e.binaryKey.length));
      previous = e.binaryKey;
      writeField(data, out, e.value);
    }
    long offset = Frames.write(out, leaf ? Frames.LEAF : Frames.BRANCH, bytes.toByteArray());
    ExternalSort.write(
        refs,
        new ExternalSort.Entry(
            entries.isEmpty() ? new byte[0] : entries.get(0).binaryKey,
            FrameRef.at(out, offset).bytes()));
  }

  private static void writeField(DataOutputStream data, RandomAccessFile out, byte[] value)
      throws IOException {
    if (value.length > PAGE / 2) {
      data.writeInt(-1);
      long offset = Frames.write(out, Frames.OVERFLOW, value);
      data.write(FrameRef.at(out, offset).bytes());
    } else {
      data.writeInt(value.length);
      data.write(value);
    }
  }

  private byte[] readField(DataInputStream in) throws IOException {
    int size = in.readInt();
    if (size == -1) {
      FrameRef ref = new FrameRef(in.readLong(), in.readLong());
      ref.validate(store.source.size() - Frames.FOOTER_SIZE);
      Frames.Frame f = store.read(ref);
      if (f.type != Frames.OVERFLOW) throw new IOException("Invalid overflow reference");
      return f.data;
    }
    if (size < 0 || size > in.available()) throw new IOException("Invalid index field length");
    byte[] b = new byte[size];
    in.readFully(b);
    return b;
  }

  private List<ExternalSort.Entry> entries(Frames.Frame frame) throws IOException {
    if (frame.type != Frames.LEAF && frame.type != Frames.BRANCH)
      throw new IOException("Invalid B-tree page type");
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame.data));
    int n = in.readInt();
    if (n < 0 || n > frame.data.length / 8) throw new IOException("Invalid B-tree page count");
    List<ExternalSort.Entry> list = new ArrayList<ExternalSort.Entry>();
    byte[] prev = new byte[0];
    for (int i = 0; i < n; i++) {
      int prefix = in.readInt();
      if (prefix < 0 || prefix > prev.length || (i == 0 && prefix != 0))
        throw new IOException("Invalid key prefix");
      byte[] suffix = readField(in);
      if (suffix.length > Integer.MAX_VALUE - prefix) throw new IOException("Index key too large");
      byte[] key = Arrays.copyOf(prev, prefix + suffix.length);
      System.arraycopy(suffix, 0, key, prefix, suffix.length);
      ExternalSort.Entry e = new ExternalSort.Entry(key, readField(in));
      if (i > 0 && Keys.compare(prev, e.binaryKey) >= 0)
        throw new IOException("Invalid B-tree ordering");
      list.add(e);
      prev = e.binaryKey;
    }
    if (in.available() != 0) throw new IOException("Trailing index bytes");
    return list;
  }

  public byte[] get(String key) throws IOException {
    try (CloseableIterator<ExternalSort.Entry> it = range(key)) {
      if (it.hasNext()) {
        ExternalSort.Entry e = it.next();
        if (Arrays.equals(e.binaryKey, Keys.encode(key))) return e.value;
      }
      return null;
    }
  }

  public CloseableIterator<ExternalSort.Entry> range(String prefix) throws IOException {
    return new Cursor(Keys.encode(prefix), null);
  }

  public CloseableIterator<ExternalSort.Entry> rangeAfter(String prefix, byte[] after)
      throws IOException {
    byte[] p = Keys.encode(prefix);
    if (after != null && !Keys.startsWith(after, p)) throw new IOException("Cursor outside query");
    return new Cursor(p, after);
  }

  public ExternalSort.Entry floor(String key) throws IOException {
    byte[] wanted = Keys.encode(key);
    FrameRef ref = root;
    for (int depth = 0; depth <= 64; depth++) {
      Frames.Frame frame = store.read(ref);
      List<ExternalSort.Entry> es = entries(frame);
      ExternalSort.Entry found = null;
      for (ExternalSort.Entry e : es) {
        if (Keys.compare(e.binaryKey, wanted) > 0) break;
        found = e;
      }
      if (found == null) return null;
      if (frame.type == Frames.LEAF) return found;
      FrameRef child = FrameRef.decode(found.value);
      if (child.offset >= ref.offset) throw new IOException("Invalid/cyclic branch reference");
      ref = child;
    }
    throw new IOException("Excessive B-tree depth");
  }

  private final class Cursor implements CloseableIterator<ExternalSort.Entry> {
    final byte[] prefix;
    final byte[] seekKey;
    final Deque<Level> stack = new ArrayDeque<Level>();
    ExternalSort.Entry next;

    final class Level {
      List<ExternalSort.Entry> entries;
      int i;
      boolean leaf;
      long offset;

      Level(List<ExternalSort.Entry> e, int i, boolean l, long offset) {
        entries = e;
        this.i = i;
        leaf = l;
        this.offset = offset;
      }
    }

    Cursor(byte[] prefix, byte[] after) throws IOException {
      this.prefix = prefix;
      this.seekKey = after == null ? prefix : after;
      descend(root, true, 0);
      advance();
      if (after != null && next != null && Keys.compare(next.binaryKey, after) == 0) advance();
    }

    void descend(FrameRef ref, boolean seek, int depth) throws IOException {
      if (depth + stack.size() > 64) throw new IOException("B-tree cycle/excessive depth");
      long offset = ref.offset;
      Frames.Frame f = store.read(ref);
      List<ExternalSort.Entry> es = entries(f);
      int i = 0;
      if (f.type == Frames.LEAF) {
        if (seek) while (i < es.size() && Keys.compare(es.get(i).binaryKey, seekKey) < 0) i++;
        stack.push(new Level(es, i, true, offset));
      } else {
        if (es.isEmpty()) throw new IOException("Empty branch");
        if (seek)
          while (i + 1 < es.size() && Keys.compare(es.get(i + 1).binaryKey, seekKey) <= 0) i++;
        stack.push(new Level(es, i + 1, false, offset));
        FrameRef child = pointer(es.get(i), offset);
        descend(child, seek, depth + 1);
      }
    }

    FrameRef pointer(ExternalSort.Entry e, long parent) throws IOException {
      if (e.value.length != 16) throw new IOException("Invalid branch reference");
      FrameRef ref = FrameRef.decode(e.value);
      long offset = ref.offset;
      if (offset >= parent || offset < Frames.HEADER_SIZE)
        throw new IOException("Invalid/cyclic branch reference");
      return ref;
    }

    void advance() throws IOException {
      next = null;
      while (!stack.isEmpty()) {
        Level l = stack.peek();
        if (l.i >= l.entries.size()) {
          stack.pop();
          continue;
        }
        ExternalSort.Entry e = l.entries.get(l.i++);
        if (l.leaf) {
          if (Keys.startsWith(e.binaryKey, prefix)) {
            next = e;
            return;
          }
          stack.clear();
          return;
        }
        FrameRef ptr = pointer(e, l.offset);
        descend(ptr, false, 0);
      }
    }

    public boolean hasNext() {
      return next != null;
    }

    public ExternalSort.Entry next() {
      if (next == null) throw new NoSuchElementException();
      ExternalSort.Entry result = next;
      try {
        advance();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return result;
    }

    public void close() {
      stack.clear();
      next = null;
    }
  }
}
