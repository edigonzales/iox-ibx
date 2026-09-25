package ch.interlis.ibx.spatial;

import ch.interlis.ibx.api.*;
import ch.interlis.ibx.codec.*;
import ch.interlis.ibx.container.*;
import ch.interlis.ibx.index.ExternalSort;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;

/** External Hilbert ordering, processing only one basket/class group at a time. */
public final class SpatialOrder {
  private SpatialOrder() {}

  public static long hilbert(long x, long y) {
    long result = 0;
    for (long s = 1L << 31; s > 0; s >>= 1) {
      long rx = (x & s) == 0 ? 0 : 1, ry = (y & s) == 0 ? 0 : 1;
      result += s * s * ((3 * rx) ^ ry);
      if (ry == 0) {
        if (rx == 1) {
          x = s - 1 - x;
          y = s - 1 - y;
        }
        long swap = x;
        x = y;
        y = swap;
      }
    }
    return result;
  }

  private static double center(double a, double b) {
    return a / 2 + b / 2;
  }

  private static long normalize(double value, double low, double high) {
    if (low == high) return 0;
    BigDecimal lo = BigDecimal.valueOf(low);
    return BigDecimal.valueOf(value)
        .subtract(lo)
        .multiply(BigDecimal.valueOf(0xffffffffL))
        .divide(BigDecimal.valueOf(high).subtract(lo), 0, RoundingMode.FLOOR)
        .longValueExact();
  }

  public static CloseableIterator<ExternalSort.Entry> reorder(
      final CloseableIterator<ExternalSort.Entry> input,
      final ObjectCodec codec,
      final TransferMetadata metadata,
      final WriterOptions options,
      final Path temp) {
    if (options.spatialOrder.isEmpty()) return input;
    return new CloseableIterator<ExternalSort.Entry>() {
      ExternalSort.Entry pending;
      ExternalSort sorter;
      CloseableIterator<ExternalSort.Entry> sorted;
      Path groupFile;
      boolean loaded;

      String group(ExternalSort.Entry e) {
        int first = e.key.indexOf('\0'), second = e.key.indexOf('\0', first + 1);
        return second < 0 ? e.key : e.key.substring(0, second);
      }

      BoundingBox bounds(byte[] value, String attr) throws IOException {
        return "wkb".equals(metadata.geometryEncoding)
            ? codec.geometryBounds(Cbor.MAPPER.readTree(value), attr)
            : GeometryBounds.attribute(codec.decode(value), attr);
      }

      void cleanupGroup() throws IOException {
        if (sorted != null) {
          sorted.close();
          sorted = null;
        }
        if (sorter != null) {
          sorter.close();
          sorter = null;
        }
        if (groupFile != null) {
          Files.deleteIfExists(groupFile);
          groupFile = null;
        }
      }

      void load() throws IOException {
        if (loaded) return;
        if (sorted != null && sorted.hasNext()) {
          loaded = true;
          return;
        }
        cleanupGroup();
        if (pending == null && input.hasNext()) pending = input.next();
        if (pending == null) return;
        String prefix = group(pending), cls = prefix.substring(prefix.indexOf('\0') + 1);
        String attr = options.spatialOrder.get(cls);
        if (attr == null) {
          loaded = true;
          return;
        }
        groupFile = Files.createTempFile(temp, "hilbert-group-", ".run");
        BoundingBox extent = null;
        try (DataOutputStream out =
            new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(groupFile)))) {
          do {
            BoundingBox box;
            try {
              box = bounds(pending.value, attr);
            } catch (IOException | RuntimeException e) {
              throw new IOException(
                  "Spatial ordering "
                      + cls
                      + "."
                      + attr
                      + " at "
                      + pending.key.replace('\0', '/')
                      + ": "
                      + e.getMessage(),
                  e);
            }
            if (box != null) {
              double x = center(box.minX, box.maxX), y = center(box.minY, box.maxY);
              BoundingBox point = new BoundingBox(x, y, x, y);
              if (extent == null) extent = point;
              else extent.expand(point);
            }
            ExternalSort.write(out, pending);
            pending = input.hasNext() ? input.next() : null;
          } while (pending != null && prefix.equals(group(pending)));
        }
        sorter = new ExternalSort(temp, options.sortMemoryBytes);
        try (DataInputStream in =
            new DataInputStream(new BufferedInputStream(Files.newInputStream(groupFile)))) {
          ExternalSort.Entry record;
          while ((record = ExternalSort.read(in)) != null) {
            BoundingBox box = bounds(record.value, attr);
            String rank = "1";
            if (box != null) {
              long x = normalize(center(box.minX, box.maxX), extent.minX, extent.maxX);
              long y = normalize(center(box.minY, box.maxY), extent.minY, extent.maxY);
              rank = "0" + String.format(Locale.ROOT, "%016x", hilbert(x, y));
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ExternalSort.write(new DataOutputStream(bytes), record);
            sorter.add(
                rank + record.key.substring(record.key.lastIndexOf('\0') + 1), bytes.toByteArray());
          }
        }
        sorted = sorter.finish();
        loaded = true;
      }

      public boolean hasNext() {
        try {
          load();
          return loaded;
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }

      public ExternalSort.Entry next() {
        if (!hasNext()) throw new NoSuchElementException();
        loaded = false;
        if (sorted != null) {
          try {
            return ExternalSort.read(
                new DataInputStream(new ByteArrayInputStream(sorted.next().value)));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
        ExternalSort.Entry result = pending;
        pending = null;
        return result;
      }

      public void close() throws IOException {
        try {
          cleanupGroup();
        } finally {
          input.close();
        }
      }
    };
  }
}
