package ch.interlis.ibx.container;

import ch.interlis.ibx.api.*;
import ch.interlis.ibx.codec.*;
import ch.interlis.iom.IomObject;
import ch.interlis.iom_j.Iom_jObject;
import ch.interlis.iox.*;
import java.io.*;

/** Forward-only IOX reader. Stops at the data end, before any index. Owns its input. */
public final class SequentialReader implements IoxReader {
  private final DataInputStream in;
  private final TransferMetadata metadata;
  private final ObjectCodec codec;
  private ObjectCursor objects;
  private boolean started, ended, closed;
  private BasketContext basket;
  private IoxFactoryCollection factory;

  public SequentialReader(InputStream input) throws IOException {
    in = new DataInputStream(new BufferedInputStream(input, 65536));
    try {
      int formatVersion = Frames.checkHeader(in);
      Frames.Frame f = Frames.read(in);
      if (f.type != Frames.METADATA) throw new IOException("Missing metadata");
      metadata = Cbor.read(f.data, TransferMetadata.class);
      if ((!"2.4".equals(metadata.version) && !"2.3".equals(metadata.version)) || metadata.mappingVersion != 1)
        throw new IOException("Unsupported transfer/mapping version");
      metadata.validateFormat(formatVersion);
      codec = new ObjectCodec(metadata, true);
    } catch (IOException e) {
      in.close();
      throw e;
    }
  }

  public TransferMetadata metadata() {
    return metadata;
  }

  public IoxEvent read() throws IoxException {
    if (closed) throw new IoxException("Reader is closed");
    if (ended) return null;
    if (!started) {
      started = true;
      return metadata.event();
    }
    try {
      if (objects != null) {
        if (objects.hasNext()) return new ch.interlis.iox_j.ObjectEvent(objects.next());
        objects.close();
        objects = null;
      }
      Frames.Frame f = Frames.read(in);
      switch (f.type) {
        case Frames.BASKET:
          if (basket != null) throw new IOException("Nested basket");
          basket = Cbor.read(f.data, BasketContext.class);
          return basket.event();
        case Frames.CHUNK:
          if (basket == null) throw new IOException("Chunk outside basket");
          Chunk chunk = Chunk.unpack(f.data);
          if (!basket.bid.equals(chunk.info.bid)
              || basket.position != chunk.info.basketPosition
              || !basket.topic.equals(chunk.info.topic))
            throw new IOException("Chunk/basket mismatch");
          objects = new ObjectCursor(chunk, codec);
          return new ch.interlis.iox_j.ObjectEvent(objects.next());
        case Frames.END_BASKET:
          if (basket == null) throw new IOException("Unexpected basket end");
          basket = null;
          return new ch.interlis.iox_j.EndBasketEvent();
        case Frames.END_TRANSFER:
          if (basket != null) throw new IOException("Unclosed basket");
          ended = true;
          return new ch.interlis.iox_j.EndTransferEvent();
        default:
          throw new IOException("Unexpected data frame " + f.type);
      }
    } catch (IOException | UncheckedIOException e) {
      throw new IoxException(e);
    }
  }

  public IomObject createIomObject(String type, String oid) {
    return new Iom_jObject(type, oid);
  }

  public IoxFactoryCollection getFactory() {
    return factory;
  }

  public void setFactory(IoxFactoryCollection factory) {
    this.factory = factory;
  }

  public void close() throws IoxException {
    if (closed) return;
    closed = true;
    try {
      if (objects != null) objects.close();
      in.close();
    } catch (IOException e) {
      throw new IoxException(e);
    }
  }
}
