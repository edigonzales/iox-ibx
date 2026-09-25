package ch.interlis.ibx.benchmark;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Controlled HTTP fixture for reproducible range tests/benchmarks, bound to loopback only. */
public final class RangeServer implements AutoCloseable {
  private final ServerSocket server;
  private final Thread worker;
  private final Path file;
  private volatile boolean closed;
  public volatile String mode = "ranges";
  public volatile int requests;
  public volatile int changeAfterRequests = Integer.MAX_VALUE;
  public volatile long bytesSent;
  public volatile int delayMillis;

  public RangeServer(Path file) throws IOException {
    this.file = file;
    server = new ServerSocket(0, 32, InetAddress.getLoopbackAddress());
    worker = new Thread(() -> loop(), "ibx-range-server");
    worker.setDaemon(true);
    worker.start();
  }

  public URI uri() {
    return URI.create("http://localhost:" + server.getLocalPort() + "/data.ibx");
  }

  private void loop() {
    while (!closed)
      try (Socket socket = server.accept()) {
        socket.setSoTimeout(10000);
        serve(socket);
      } catch (IOException e) {
        if (!closed) {
          /* client cancellation is expected in rejection tests */
        }
      }
  }

  private void serve(Socket socket) throws IOException {
    BufferedReader in =
        new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
    if (in.readLine() == null) return;
    Map<String, String> headers = new HashMap<String, String>();
    String line;
    while ((line = in.readLine()) != null && !line.isEmpty()) {
      int colon = line.indexOf(':');
      if (colon > 0)
        headers.put(
            line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
    }
    requests++;
    if (delayMillis > 0)
      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
    String tag = mode.equals("changed") || requests > changeAfterRequests ? "\"v2\"" : "\"v1\"";
    OutputStream out = socket.getOutputStream();
    if (headers.containsKey("if-match") && !headers.get("if-match").equals(tag)) {
      out.write(
          "HTTP/1.1 412 Precondition Failed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
              .getBytes("US-ASCII"));
      return;
    }
    long size = Files.size(file), start = 0, end = size - 1;
    boolean range = headers.containsKey("range") && !mode.equals("full");
    if (range) {
      String[] values = headers.get("range").substring(6).split("-");
      start = Long.parseLong(values[0]);
      end = Long.parseLong(values[1]);
    }
    String response =
        "HTTP/1.1 "
            + (range ? "206 Partial Content" : "200 OK")
            + "\r\nConnection: close\r\nContent-Length: "
            + (end - start + 1)
            + "\r\n";
    if (!mode.equals("no-etag")) response += "ETag: " + tag + "\r\n";
    if (range)
      response +=
          "Content-Range: bytes "
              + (mode.equals("bad-range") ? start + 1 : start)
              + "-"
              + end
              + "/"
              + size
              + "\r\n";
    out.write((response + "\r\n").getBytes("US-ASCII"));
    try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
      f.seek(start);
      byte[] b = new byte[65536];
      long left = end - start + 1;
      while (left > 0) {
        int n = f.read(b, 0, (int) Math.min(left, b.length));
        if (n < 0) break;
        out.write(b, 0, n);
        left -= n;
        bytesSent += n;
      }
    }
    out.flush();
  }

  public void close() throws IOException {
    closed = true;
    server.close();
    try {
      worker.join(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
