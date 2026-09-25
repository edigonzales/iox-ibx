package ch.interlis.ibx.container;

import ch.interlis.ibx.codec.*;
import ch.interlis.iom.IomObject;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;

public final class ObjectCursor implements CloseableIterator<IomObject> {
  private final JsonParser parser;
  private final ObjectCodec codec;
  private int remaining;
  public final long firstFid;

  public ObjectCursor(Chunk chunk, ObjectCodec codec) throws IOException {
    parser = Cbor.MAPPER.getFactory().createParser(chunk.objects);
    this.codec = codec;
    remaining = chunk.info.count;
    firstFid = chunk.info.firstFid;
  }

  public boolean hasNext() {
    return remaining > 0;
  }

  public IomObject next() {
    try {
      return codec.object(nextRecord());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public JsonNode nextRecord() {
    if (!hasNext()) throw new NoSuchElementException();
    try {
      JsonNode node = Cbor.MAPPER.readTree(parser);
      if (node == null) throw new EOFException("Too few objects in chunk");

      remaining--;
      if (remaining == 0 && parser.nextToken() != null)
        throw new IOException("Too many objects in chunk");
      return node;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void close() throws IOException {
    parser.close();
    remaining = 0;
  }
}
