package ch.interlis.ibx.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import java.io.IOException;

public final class Cbor {
  public static final ObjectMapper MAPPER = new ObjectMapper(new CBORFactory());

  private Cbor() {}

  public static byte[] bytes(Object value) throws IOException {
    return MAPPER.writeValueAsBytes(value);
  }

  public static <T> T read(byte[] bytes, Class<T> type) throws IOException {
    return MAPPER.readValue(bytes, type);
  }
}
