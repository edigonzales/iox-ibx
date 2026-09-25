package ch.interlis.ibx.container;

import java.io.*;
import java.nio.file.*;
import java.util.Iterator;

/** Sampled disk usage, including temporary sort runs and the unpublished output. */
public final class TemporaryUsage implements AutoCloseable {
  private final Path directory, output;
  private final Thread worker;
  private volatile boolean stopped;
  private volatile long peak;

  public TemporaryUsage(Path directory, Path output) {
    this.directory = directory;
    this.output = output;
    worker =
        new Thread(
            () -> {
              while (!stopped) {
                sample();
                try {
                  Thread.sleep(25);
                } catch (InterruptedException e) {
                  return;
                }
              }
            },
            "ibx-temp-metrics");
    worker.setDaemon(true);
    worker.start();
  }

  private void sample() {
    long sum = 0;
    try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
      Iterator<Path> it = files.iterator();
      while (it.hasNext()) {
        Path p = it.next();
        try {
          if (Files.isRegularFile(p)) sum += Files.size(p);
        } catch (IOException ignored) {
        }
      }
    } catch (IOException | UncheckedIOException ignored) {
    }
    try {
      sum += Files.size(output);
    } catch (IOException ignored) {
    }
    peak = Math.max(peak, sum);
  }

  public long peak() {
    return peak;
  }

  public void close() {
    sample();
    stopped = true;
    worker.interrupt();
    try {
      worker.join(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
