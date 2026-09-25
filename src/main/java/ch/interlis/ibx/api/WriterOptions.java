package ch.interlis.ibx.api;

import java.nio.file.Path;
import java.util.*;

public final class WriterOptions {
  public long geometryVerificationNanos;
  public long temporaryPeakSampledBytes;
  public long objectDirectoryEntryBytes;
  public int chunkSize = 256 * 1024;
  public int compressionLevel = 3;
  public String compression = "zstd";
  public String numericEncoding = "lexical";
  public String geometryEncoding = "iom";
  public final Map<String, String> geometryCrs = new TreeMap<String, String>();
  public final Map<String, String> spatialOrder = new TreeMap<String, String>();
  public boolean reverseIndex = true;
  public boolean embedModels;
  public boolean overwrite;
  public long sortMemoryBytes = 16L * 1024 * 1024;
  public final List<String> modelPaths = new ArrayList<String>();
  public final List<String> modelFiles = new ArrayList<String>();
  public Path temporaryDirectory;

  public void validate() {
    if (!Arrays.asList("iom", "wkb").contains(geometryEncoding))
      throw new IllegalArgumentException("Unknown geometry encoding");
    if (chunkSize < 1 || sortMemoryBytes < 16384)
      throw new IllegalArgumentException("Invalid chunk/sort size");
    if (!Arrays.asList("lexical", "decimal").contains(numericEncoding))
      throw new IllegalArgumentException("Unknown numeric encoding");
    if (!Arrays.asList("zstd", "deflate", "none").contains(compression))
      throw new IllegalArgumentException("Unknown compression");
  }
}
