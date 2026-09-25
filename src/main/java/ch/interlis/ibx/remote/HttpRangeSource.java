package ch.interlis.ibx.remote;

import ch.interlis.ibx.api.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.regex.*;

/** Byte ranges pinned by strong ETag or explicitly immutable URL. */
public final class HttpRangeSource implements RangeSource {
  private final URI uri;
  private final RemoteOptions options;
  private final ReadMetrics metrics;
  private long length = -1;
  private byte[] initialHeader;
  private String etag;
  private boolean closed;
  private LocalSource snapshot;
  private Path temporary;
  private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

  public HttpRangeSource(URI uri, RemoteOptions options, ReadMetrics metrics) throws IOException {
    if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
      throw new IOException("Expected HTTP(S) URL");
    this.uri = uri;
    this.options = options;
    this.metrics = metrics;
    try {
      initialHeader = fetch(0, 16, true);
    } catch (IOException e) {
      close();
      throw e;
    }
  }

  public String revision() throws IOException {
    return (etag == null ? "snapshot-or-immutable" : etag) + ":" + size();
  }

  public long size() throws IOException {
    ensureOpen();
    return snapshot == null ? length : snapshot.size();
  }

  private void ensureOpen() throws IOException {
    if (closed) throw new IOException("Source closed");
  }

  public synchronized byte[] read(long offset, int count) throws IOException {
    ensureOpen();
    if (offset < 0 || count < 0 || offset > size() - count)
      throw new EOFException("Range outside remote container");
    if (count == 0) return new byte[0];
    if (offset == 0 && count == 16 && initialHeader != null) return initialHeader.clone();
    if (snapshot != null) return snapshot.read(offset, count);
    return fetch(offset, count, false);
  }

  private byte[] fetch(long offset, int count, boolean initial) throws IOException {
    URI current = uri;
    HttpURLConnection c = null;
    int status;
    for (int redirects = 0; ; redirects++) {
      c = (HttpURLConnection) current.toURL().openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(options.connectTimeoutMillis);
      c.setReadTimeout(options.readTimeoutMillis);
      c.setRequestProperty("Accept-Encoding", "identity");
      c.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + count - 1));
      if (etag != null) c.setRequestProperty("If-Match", etag);
      metrics.requests++;
      try {
        status = c.getResponseCode();
      } catch (IOException e) {
        c.disconnect();
        throw e;
      }
      if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) break;
      String location = c.getHeaderField("Location");
      c.disconnect();
      if (redirects >= 5 || location == null)
        throw new IOException("Invalid/excessive HTTP redirects");
      URI next = current.resolve(location);
      if (!("https".equalsIgnoreCase(next.getScheme()) || "http".equalsIgnoreCase(next.getScheme()))
          || next.getUserInfo() != null
          || ("https".equalsIgnoreCase(current.getScheme())
              && !"https".equalsIgnoreCase(next.getScheme())))
        throw new IOException("Unsafe HTTP redirect");
      current = next;
    }
    try {
      String responseTag = c.getHeaderField("ETag");
      String encoding = c.getHeaderField("Content-Encoding");
      if (encoding != null && !encoding.equalsIgnoreCase("identity"))
        throw new IOException("Encoded HTTP response invalidates byte ranges");
      if (status == 412 || (etag != null && !etag.equals(responseTag)))
        throw new IOException("Remote container changed (ETag)");
      if (status == 200) {
        if (!initial || !options.allowFullDownload)
          throw new IOException(
              "Server returned a full response; range access required (explicit full download only"
                  + " at open)");
        temporary = Files.createTempFile("ibx-snapshot-", ".ibx");
        try (InputStream in = c.getInputStream();
            OutputStream out = Files.newOutputStream(temporary)) {
          byte[] b = new byte[65536];
          int n;
          while ((n = in.read(b)) != -1) {
            metrics.bytesRead += n;
            out.write(b, 0, n);
          }
        }
        long expected = c.getContentLengthLong();
        if (expected >= 0 && Files.size(temporary) != expected)
          throw new IOException("Truncated full download");
        snapshot = new LocalSource(temporary, new ReadMetrics());
        length = snapshot.size();
        return snapshot.read(0, (int) Math.min(count, length));
      }
      if (status != 206) throw new IOException("HTTP range failed: " + status);
      String value = c.getHeaderField("Content-Range");
      Matcher m = RANGE.matcher(value == null ? "" : value);
      if (!m.matches()) throw new IOException("Missing/invalid Content-Range");
      long start = Long.parseLong(m.group(1)),
          end = Long.parseLong(m.group(2)),
          total = Long.parseLong(m.group(3));
      if (start != offset
          || end != offset + count - 1
          || total <= end
          || (length >= 0 && length != total))
        throw new IOException("Content-Range mismatch/container changed");
      if (initial) {
        length = total;
        if (responseTag != null
            && !responseTag.startsWith("W/")
            && responseTag.startsWith("\"")
            && responseTag.endsWith("\"")) etag = responseTag;
        else if (!options.immutableUrl)
          throw new IOException("A strong ETag or explicit immutable URL is required");
      }
      long declared = c.getContentLengthLong();
      if (declared >= 0 && declared != count)
        throw new IOException("HTTP response length mismatch");
      byte[] bytes = new byte[count];
      try (DataInputStream in = new DataInputStream(c.getInputStream())) {
        int done = 0;
        while (done < count) {
          int n = in.read(bytes, done, count - done);
          if (n < 0) throw new EOFException("Truncated HTTP range");
          done += n;
          metrics.bytesRead += n;
        }
        if (in.read() != -1) throw new IOException("Oversized HTTP range");
      }
      return bytes;
    } catch (NumberFormatException e) {
      throw new IOException("Invalid HTTP range numbers", e);
    } finally {
      c.disconnect();
    }
  }

  public void close() throws IOException {
    closed = true;
    try {
      if (snapshot != null) snapshot.close();
    } finally {
      if (temporary != null) Files.deleteIfExists(temporary);
    }
  }
}
