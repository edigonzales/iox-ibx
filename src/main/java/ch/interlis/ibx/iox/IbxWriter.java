package ch.interlis.ibx.iox;

import ch.interlis.ibx.api.*;
import ch.interlis.ibx.container.*;
import ch.interlis.ili2c.metamodel.*;
import ch.interlis.iom.IomObject;
import ch.interlis.iox.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;

/** Writes FULL IOX transfers directly to an IBX container with bounded memory. */
public final class IbxWriter implements IoxWriter, AutoCloseable {
  private final Path target;
  private final TransferDescription model;
  private final WriterOptions options;
  private ModelBridge bridge;
  private ContainerWriter.Session session;
  private List<String> models;
  private boolean started, ended, closed;
  private IoxFactoryCollection factory = new ch.interlis.iox_j.DefaultIoxFactoryCollection();

  public IbxWriter(File target, TransferDescription model, WriterOptions options)
      throws IoxException {
    this(target.toPath(), model, options);
  }

  public IbxWriter(Path target, TransferDescription model, WriterOptions options)
      throws IoxException {
    if (target == null || model == null || options == null)
      throw new IoxException("Target, model and options are required");
    this.target = target;
    this.model = model;
    this.options = options;
    try {
      options.validate();
    } catch (RuntimeException e) {
      throw new IoxException(e);
    }
  }

  /** File-adapter entry point retaining resolved source metadata. */
  public IbxWriter(Path target, ModelBridge bridge, WriterOptions options) throws IoxException {
    this(target, bridge.model, options);
    this.bridge = bridge;
  }

  public void setModels(String[] names) throws IoxException {
    if (started || closed) throw new IoxException("Models must be set before the transfer starts");
    models = names == null ? null : new ArrayList<String>(Arrays.asList(names));
  }

  @Override
  public void write(IoxEvent event) throws IoxException {
    try {
      if (closed || ended) throw new IoxException("Writer is finished or closed");
      if (event instanceof StartTransferEvent) {
        if (started) throw new IoxException("Duplicate transfer start");
        started = true;
        StartTransferEvent start = (StartTransferEvent) event;
        if (start instanceof ch.interlis.iox_j.StartTransferEvent) {
          String declared = ((ch.interlis.iox_j.StartTransferEvent) start).getVersion();
          if (declared != null && !"2.3".equals(declared) && !"2.4".equals(declared))
            throw new IoxException("Only INTERLIS 2.3/2.4 FULL transfers are supported");
        }
        if (bridge == null) {
          TransferMetadata meta = new TransferMetadata();
          meta.sender = start.getSender();
          meta.comment = start.getComment();
          String version = null;
          for (Iterator<?> it = model.iterator(); it.hasNext(); ) {
            Object e = it.next();
            if (!(e instanceof Model) || e instanceof PredefinedModel) continue;
            Model m = (Model) e;
            if (models == null || models.contains(m.getName())) {
              meta.transferModels.add(m.getName());
              if (!"2.3".equals(m.getIliVersion()) && !"2.4".equals(m.getIliVersion()))
                throw new IoxException("Only INTERLIS 2.3/2.4 models are supported");
              if (version == null || "2.4".equals(m.getIliVersion())) version = m.getIliVersion();
            }
          }
          if (models != null) {
            meta.transferModels.clear();
            meta.transferModels.addAll(models);
          }
          if (version == null || meta.transferModels.isEmpty())
            throw new IoxException("No transfer models selected");
          meta.version = version;
          Path temp =
              options.temporaryDirectory == null
                  ? Paths.get(System.getProperty("java.io.tmpdir"))
                  : options.temporaryDirectory;
          bridge = ModelBridge.fromModel(model, options, meta, temp, options.modelPaths);
        }
        session = new ContainerWriter.Session(target, bridge, options);
      } else {
        if (!started) throw new IoxException("Expected StartTransferEvent");
        session.accept(event);
        if (event instanceof EndTransferEvent) {
          ended = true;
          session.close();
        }
      }
    } catch (Exception e) {
      try {
        close();
      } catch (IoxException cleanup) {
        e.addSuppressed(cleanup);
      }
      throw e instanceof IoxException ? (IoxException) e : new IoxException(e);
    }
  }

  @Override
  public void flush() throws IoxException {
    if (closed) throw new IoxException("Writer is closed");
  }

  @Override
  public void close() throws IoxException {
    if (closed) return;
    closed = true;
    if (session != null)
      try {
        session.close();
      } catch (Exception e) {
        throw new IoxException(e);
      }
  }

  @Override
  public IoxFactoryCollection getFactory() {
    return factory;
  }

  @Override
  public void setFactory(IoxFactoryCollection factory) throws IoxException {
    if (factory == null) throw new IoxException("Factory is required");
    this.factory = factory;
  }

  @Override
  public IomObject createIomObject(String tag, String oid) throws IoxException {
    return factory.createIomObject(tag, oid);
  }
}
