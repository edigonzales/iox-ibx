package ch.interlis.ibx.iox;

import ch.interlis.ibx.api.TransferMetadata;
import ch.interlis.iom.IomObject;
import ch.interlis.iom_j.Iom_jObject;
import ch.interlis.iom_j.xtf.XtfWriterBase;
import ch.interlis.iox.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Adapter for iox-ili 1.24.4's escaped XML-blackbox output. All XTF structure stays with iox-ili.
 */
public final class LosslessXtfWriter extends XtfWriterBase {
  private final EventBuffer buffer;
  private final TransferMetadata metadata;

  public LosslessXtfWriter(OutputStream output, TransferMetadata metadata)
      throws IOException, IoxException {
    this(new EventBuffer(output), metadata);
  }

  private LosslessXtfWriter(EventBuffer buffer, TransferMetadata metadata)
      throws IOException, IoxException {
    super(buffer, ModelBridge.mapping(metadata), metadata.version);
    this.buffer = buffer;
    this.metadata = metadata;
  }

  @Override
  public void write(IoxEvent event) throws IoxException {
    if (event instanceof ObjectEvent)
      event = new ch.interlis.iox_j.ObjectEvent(replace(((ObjectEvent) event).getIomObject()));
    super.write(event);
    flush();
  }

  private IomObject replace(IomObject original) throws IoxException {
    List<TransferMetadata.Property> props = metadata.classes.get(original.getobjecttag());
    if (props == null) return original;
    Iom_jObject copy = null;
    for (TransferMetadata.Property p : props) {
      for (int i = 0; i < original.getattrvaluecount(p.name); i++) {
        IomObject child = original.getattrobj(p.name, i);
        if (child != null) {
          IomObject transformed = replace(child);
          if (transformed != child) {
            if (copy == null) copy = new Iom_jObject(original);
            copy.changeattrobj(p.name, i, transformed);
          }
        } else if (p.blackboxXml) {
          String xml = original.getattrprim(p.name, i);
          if (xml != null) {
            validateXml(xml);
            if (copy == null) copy = new Iom_jObject(original);
            String token = "ILICXML" + UUID.randomUUID().toString().replace("-", "") + "END";
            buffer.replacements.put(token, xml);
            copy.setattrvalue(p.name, i, token);
          }
        }
      }
    }
    return copy == null ? original : copy;
  }

  private static void validateXml(String xml) throws IoxException {
    try {
      javax.xml.stream.XMLInputFactory factory = javax.xml.stream.XMLInputFactory.newFactory();
      factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false);
      factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
      javax.xml.stream.XMLStreamReader reader =
          factory.createXMLStreamReader(new StringReader("<wrapper>" + xml + "</wrapper>"));
      try {
        while (reader.hasNext()) reader.next();
      } finally {
        reader.close();
      }
    } catch (javax.xml.stream.XMLStreamException e) {
      throw new IoxException("Invalid/unbound XML blackbox content", e);
    }
  }

  @Override
  public void flush() throws IoxException {
    super.flush();
    try {
      buffer.drain();
    } catch (IOException e) {
      throw new IoxException(e);
    }
  }

  private static final class EventBuffer extends OutputStream {
    final OutputStream output;
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final Map<String, String> replacements = new LinkedHashMap<String, String>();

    EventBuffer(OutputStream output) {
      this.output = output;
    }

    public void write(int b) {
      bytes.write(b);
    }

    public void write(byte[] b, int off, int len) {
      bytes.write(b, off, len);
    }

    void drain() throws IOException {
      if (replacements.isEmpty()) bytes.writeTo(output);
      else {
        String xml = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        for (Map.Entry<String, String> e : replacements.entrySet())
          xml = xml.replace(e.getKey(), e.getValue());
        output.write(xml.getBytes(StandardCharsets.UTF_8));
      }
      bytes.reset();
      replacements.clear();
      output.flush();
    }
  }
}
