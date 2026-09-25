package ch.interlis.ibx.spatial;

import ch.interlis.ibx.api.BoundingBox;
import ch.interlis.ibx.container.*;
import java.io.*;
import java.nio.ByteBuffer;

/** Fixed-width, big-endian spatial pages in container format 5. */
public final class SpatialPageCodec {
  public static final int RECTANGLE = 1, POINT = 2, BRANCH = 3, HEADER = 8;
  public static final int PAGE_BYTES = 16 * 1024;

  private SpatialPageCodec() {}

  public static int entrySize(int layout) throws IOException {
    switch (layout) {
      case RECTANGLE:
        return 85;
      case POINT:
        return 69;
      case BRANCH:
        return 48;
      default:
        throw new IOException("Unsupported spatial layout " + layout);
    }
  }

  public static byte[] encode(SpatialIndex.Node node, int layout) throws IOException {
    int size = entrySize(layout);
    if (node.entries.size() > (PAGE_BYTES - HEADER) / size)
      throw new IOException("Spatial page too large");
    ByteBuffer out = ByteBuffer.allocate(HEADER + node.entries.size() * size);
    out.put((byte) 1).put((byte) layout).putShort((short) 0).putInt(node.entries.size());
    for (SpatialIndex.Entry e : node.entries) {
      validateBox(e.box);
      out.putDouble(e.box.minX).putDouble(e.box.minY);
      if (layout == POINT) {
        if (e.box.minX != e.box.maxX || e.box.minY != e.box.maxY)
          throw new IOException("Point index requires degenerate XY bounds");
      } else out.putDouble(e.box.maxX).putDouble(e.box.maxY);
      if (layout == BRANCH) {
        if (e.child < Frames.HEADER_SIZE || e.childLength < Frames.FRAME_HEADER)
          throw new IOException("Invalid spatial child reference");
        out.putLong(e.child).putLong(e.childLength);
      } else {
        if (e.location == null || Location.decode(e.location).ordinal < 0)
          throw new IOException("Invalid spatial location");
        out.put(e.location);
      }
    }
    return out.array();
  }

  public static SpatialIndex.Node decode(byte[] bytes, int expectedLayout, long offset, long end)
      throws IOException {
    if (bytes.length < HEADER || bytes.length > PAGE_BYTES)
      throw new IOException("Invalid spatial page length");
    ByteBuffer in = ByteBuffer.wrap(bytes);
    int version = in.get() & 255, layout = in.get() & 255;
    if (version != 1 || layout != expectedLayout || in.getShort() != 0)
      throw new IOException("Unsupported spatial page version/layout/reserved fields");
    int count = in.getInt(), size = entrySize(layout);
    if (count < 0 || HEADER + (long) count * size != bytes.length)
      throw new IOException("Invalid spatial entry count/length");
    SpatialIndex.Node node = new SpatialIndex.Node();
    for (int i = 0; i < count; i++) {
      SpatialIndex.Entry e = new SpatialIndex.Entry();
      double x = in.getDouble(), y = in.getDouble();
      try {
        e.box =
            new BoundingBox(
                x, y, layout == POINT ? x : in.getDouble(), layout == POINT ? y : in.getDouble());
      } catch (IllegalArgumentException invalid) {
        throw new IOException("Invalid spatial bounds", invalid);
      }
      if (layout == BRANCH) {
        e.child = in.getLong();
        e.childLength = in.getLong();
        new FrameRef(e.child, e.childLength).validate(end);
        if (e.child >= offset || e.childLength > offset - e.child)
          throw new IOException("Invalid/cyclic spatial pointer");
      } else {
        e.location = new byte[53];
        in.get(e.location);
        Location loc = Location.decode(e.location);
        if (loc.ordinal < 0) throw new IOException("Invalid spatial location");
        new FrameRef(loc.chunkOffset, loc.chunkLength).validate(end);
        new FrameRef(loc.basketOffset, loc.basketLength).validate(end);
      }
      if (layout == POINT) {
        try {
          e.box =
              new BoundingBox(Math.nextDown(x), Math.nextDown(y), Math.nextUp(x), Math.nextUp(y));
        } catch (IllegalArgumentException invalid) {
          throw new IOException("Invalid spatial bounds", invalid);
        }
      }
      node.entries.add(e);
    }
    return node;
  }

  private static void validateBox(BoundingBox box) throws IOException {
    if (box == null) throw new IOException("Missing spatial bounds");
    try {
      new BoundingBox(box.minX, box.minY, box.maxX, box.maxY);
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Invalid spatial bounds", invalid);
    }
  }
}
