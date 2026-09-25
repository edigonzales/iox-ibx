package ch.interlis.ibx.container;

public final class Location {
  public long chunkLength, basketLength;
  public long chunkOffset, basketOffset, basketPosition, chunkId;
  public int ordinal = -1;

  public Location() {}

  public Location(long chunk, long basket, long position, long id, int ordinal) {
    chunkOffset = chunk;
    basketOffset = basket;
    basketPosition = position;
    chunkId = id;
    this.ordinal = ordinal;
  }

  public Location lengths(long chunk, long basket) {
    chunkLength = chunk;
    basketLength = basket;
    return this;
  }

  public byte[] bytes() {
    return java.nio.ByteBuffer.allocate(53)
        .put((byte) 1)
        .putLong(chunkOffset)
        .putLong(chunkLength)
        .putLong(basketOffset)
        .putLong(basketLength)
        .putLong(basketPosition)
        .putLong(chunkId)
        .putInt(ordinal)
        .array();
  }

  public static Location decode(byte[] bytes) throws java.io.IOException {
    if (bytes.length != 53) throw new java.io.IOException("Invalid location length");
    java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(bytes);
    if (b.get() != 1) throw new java.io.IOException("Unsupported location codec");
    long chunk = b.getLong(),
        cl = b.getLong(),
        basket = b.getLong(),
        bl = b.getLong(),
        position = b.getLong(),
        id = b.getLong();
    int ordinal = b.getInt();
    if (basket < Frames.HEADER_SIZE
        || bl < Frames.FRAME_HEADER
        || bl > Integer.MAX_VALUE
        || (chunk == 0
            ? cl != 0 || ordinal != -1
            : chunk < Frames.HEADER_SIZE || cl < Frames.FRAME_HEADER || cl > Integer.MAX_VALUE)
        || ordinal < -1
        || position < 0
        || id < 0) throw new java.io.IOException("Invalid location ordinal");
    return new Location(chunk, basket, position, id, ordinal).lengths(cl, bl);
  }
}
