package ch.interlis.ibx;

import static org.junit.Assert.*;

import ch.interlis.ibx.api.*;
import ch.interlis.ibx.container.*;
import ch.interlis.ibx.spatial.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import org.junit.Test;

public class SpatialPageCodecTest {
  private byte[] unhex(String s) {
    byte[] b = new byte[s.length() / 2];
    for (int i = 0; i < b.length; i++)
      b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    return b;
  }

  @Test
  public void sharedIndependentFixtures() throws Exception {
    JsonNode cases =
        new ObjectMapper()
            .readTree(Paths.get("src/test/resources/format5/spatial-pages.json").toFile());
    for (JsonNode test : cases) {
      int layout = test.get("layout").asInt();
      byte[] bytes = unhex(test.get("hex").asText());
      SpatialIndex.Node n = SpatialPageCodec.decode(bytes, layout, 1024, 2048);
      if (layout == 2)
        for (SpatialIndex.Entry e : n.entries)
          e.box =
              new BoundingBox(
                  Math.nextUp(e.box.minX),
                  Math.nextUp(e.box.minY),
                  Math.nextDown(e.box.maxX),
                  Math.nextDown(e.box.maxY));
      assertArrayEquals(bytes, SpatialPageCodec.encode(n, layout));
      for (int length = 0; length < bytes.length; length++) {
        try {
          SpatialPageCodec.decode(java.util.Arrays.copyOf(bytes, length), layout, 1024, 2048);
          fail("truncation");
        } catch (IOException expected) {
        }
      }
      for (int at : new int[] {0, 1, 2, 4}) {
        byte[] bad = bytes.clone();
        bad[at] = (byte) 255;
        try {
          SpatialPageCodec.decode(bad, layout, 1024, 2048);
          fail("header");
        } catch (IOException expected) {
        }
      }
      if (!n.entries.isEmpty()) {
        byte[] bad = bytes.clone();
        ByteBuffer.wrap(bad).putDouble(8, Double.NaN);
        try {
          SpatialPageCodec.decode(bad, layout, 1024, 2048);
          fail("NaN");
        } catch (IOException expected) {
        }
      }
    }
  }

  @Test
  public void pageLimitAndPointGuard() throws Exception {
    SpatialIndex.Node n = new SpatialIndex.Node();
    SpatialIndex.Entry e = new SpatialIndex.Entry();
    e.box = new BoundingBox(-1, 2, -1, 2);
    e.location = new Location(128, 16, 0, 0, 0).lengths(64, 64).bytes();
    int capacity = (16384 - 8) / 69;
    for (int i = 0; i < capacity; i++) n.entries.add(e);
    assertEquals(8 + capacity * 69, SpatialPageCodec.encode(n, 2).length);
    n.entries.add(e);
    try {
      SpatialPageCodec.encode(n, 2);
      fail();
    } catch (IOException expected) {
    }
    n.entries.clear();
    e.box = new BoundingBox(0, 0, 1, 1);
    n.entries.add(e);
    try {
      SpatialPageCodec.encode(n, 2);
      fail();
    } catch (IOException expected) {
    }
  }

  @Test
  public void format4RejectedClearly() throws Exception {
    byte[] b = ByteBuffer.allocate(16).putLong(Frames.MAGIC).putInt(4).putInt(0).array();
    try {
      Frames.checkHeader(new DataInputStream(new ByteArrayInputStream(b)));
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("format 4"));
    }
  }

  @Test
  public void nearbyHttpPagesUseOneRequestWithoutExceedingCacheBudget() throws Exception {
    Path path = Files.createTempFile("spatial-prefetch-", ".ibx");
    try {
      FrameRef a, b;
      try (RandomAccessFile out = new RandomAccessFile(path.toFile(), "rw")) {
        Frames.header(out);
        long first = Frames.write(out, Frames.SPATIAL_LEAF, new byte[128]);
        Frames.write(out, Frames.SPATIAL_LEAF, new byte[64]);
        long second = Frames.write(out, Frames.SPATIAL_LEAF, new byte[128]);
        a = FrameRef.at(out, first);
        b = FrameRef.at(out, second);
        Frames.footer(out, first, 0);
      }
      ReadMetrics metrics = new ReadMetrics();
      try (ch.interlis.ibx.benchmark.RangeServer server =
              new ch.interlis.ibx.benchmark.RangeServer(path);
          ch.interlis.ibx.remote.HttpRangeSource source =
              new ch.interlis.ibx.remote.HttpRangeSource(
                  server.uri(), new RemoteOptions(), metrics)) {
        FrameStore store = new FrameStore(source, metrics, 1024);
        metrics.reset();
        store.prefetchSpatial(java.util.Arrays.asList(a, b));
        store.read(a);
        store.read(b);
        assertEquals(1, metrics.requests);
        assertEquals(80, metrics.additionalRangeBytes);
        FrameStore tiny = new FrameStore(source, metrics, 144);
        metrics.reset();
        tiny.prefetchSpatial(java.util.Arrays.asList(a, b));
        assertEquals(0, metrics.requests);
      }
    } finally {
      Files.deleteIfExists(path);
    }
  }
}
