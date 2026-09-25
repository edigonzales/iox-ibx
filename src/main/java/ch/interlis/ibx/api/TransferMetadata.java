package ch.interlis.ibx.api;

import ch.interlis.iom_j.xtf.XtfStartTransferEvent;
import java.util.*;

public final class TransferMetadata {
  @com.fasterxml.jackson.annotation.JsonIgnore public long geometryVerificationNanos;
  public int mappingVersion = 1;
  public String datasetId = java.util.UUID.randomUUID().toString();
  public int navigationVersion = 1;
  public boolean reverseIndex;
  public Map<String, Map<String, Object>> definitions = new TreeMap<String, Map<String, Object>>();
  public Map<String, String> spatialOrder = new TreeMap<String, String>();
  public String geometryEncoding = "iom", geometryProfile;
  public Map<String, GeometryDescriptor> geometries = new TreeMap<String, GeometryDescriptor>();
  public Map<String, String> scalarTypes = new TreeMap<String, String>();
  public Set<String> concreteClasses = new TreeSet<String>();

  public static final class GeometryDescriptor {
    public String type, crs, domain;
    public int dimension, nullAxis, piHalfAxis;
    public boolean multiSurface, directed, generic;
    public List<String> minimum = new ArrayList<String>(), maximum = new ArrayList<String>();
    public List<Integer> accuracy = new ArrayList<Integer>();
    public List<String> lineForms = new ArrayList<String>();
  }

  public int formatVersion() {
    return 4;
  }

  public void validateFormat(int version) throws java.io.IOException {
    if (version != formatVersion()
        || !("iom".equals(geometryEncoding) || "wkb".equals(geometryEncoding))
        || ("wkb".equals(geometryEncoding) && !"wkb-iso-v1".equals(geometryProfile)))
      throw new java.io.IOException("Unsupported geometry profile/header combination");
  }

  public String sender, comment, version = "2.4", numericEncoding = "lexical";
  public List<String> dictionary = new ArrayList<String>();
  public List<String> transferModels = new ArrayList<String>();
  public Map<String, String> xmlNames = new TreeMap<String, String>();
  public Map<String, List<Property>> classes = new TreeMap<String, List<Property>>();
  public Set<String> topics = new TreeSet<String>();
  public List<ModelInfo> models = new ArrayList<ModelInfo>();
  public List<SourceInfo> sources = new ArrayList<SourceInfo>();
  public Map<String, String> numericTypes = new TreeMap<String, String>();
  public Map<String, String> geometryCrs = new TreeMap<String, String>();

  public XtfStartTransferEvent event() {
    return new XtfStartTransferEvent(sender, comment, version);
  }

  public static final class Property {
    public String name, enumType, baseDefInClass;
    public boolean oid, blackboxXml, blackboxBin, finalType, multiSurfaceOrArea;
  }

  public static final class ModelInfo {
    public String name, version, issuer, sourceDigest;
  }

  public static final class SourceInfo {
    public String name, origin, sha256;
    public byte[] content;
  }
}
