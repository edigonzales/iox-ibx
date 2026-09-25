package ch.interlis.ibx.api;

import ch.interlis.ibx.container.*;
import ch.interlis.iom.IomObject;
import java.io.*;
import java.util.*;
import java.util.stream.*;

/** Explicit partial transfer; each view opens its own lazy cursor. */
public final class Fragment implements AutoCloseable {
  public interface Locations {
    CloseableIterator<Location> open() throws IOException;
  }

  private final IbxContainer container;
  private final Locations locations;
  private final String description;
  private final Set<Closeable> active = new HashSet<Closeable>();
  private boolean closed;

  public Fragment(IbxContainer container, Locations locations, String description) {
    this.container = container;
    this.locations = locations;
    this.description = description;
  }

  public TransferMetadata transferMetadata() {
    return container.metadata();
  }

  public String description() {
    return description;
  }

  public CloseableIterator<Location> locations() throws IOException {
    check();
    CloseableIterator<Location> it = locations.open();
    return description.startsWith("bbox candidates ") ? container.prefetch(it) : it;
  }

  private void check() {
    if (closed) throw new IllegalStateException("Fragment closed");
    container.checkOpen();
  }

  private <T> Stream<T> stream(CloseableIterator<T> it) {
    active.add(it);
    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false)
        .onClose(
            () -> {
              try {
                it.close();
                active.remove(it);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
  }

  public Stream<BasketContext> baskets() {
    check();
    try {
      final CloseableIterator<Location> refs = locations.open();
      return stream(
          new CloseableIterator<BasketContext>() {
            long previous = -1;
            BasketContext next;

            public boolean hasNext() {
              check();
              if (next != null) return true;
              try {
                while (refs.hasNext()) {
                  Location loc = refs.next();
                  if (loc.basketPosition != previous) {
                    previous = loc.basketPosition;
                    next = container.basket(loc);
                    return true;
                  }
                }
                close();
                return false;
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            }

            public BasketContext next() {
              if (!hasNext()) throw new NoSuchElementException();
              BasketContext out = next;
              next = null;
              return out;
            }

            public void close() throws IOException {
              refs.close();
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public Stream<SelectedObject> objects() {
    check();
    try {
      final CloseableIterator<Location> refs = locations();
      return stream(
          new CloseableIterator<SelectedObject>() {
            ObjectCursor cursor;
            Location location;
            int ordinal;
            SelectedObject next;
            BasketContext context;
            long contextOffset = -1, chunkOffset = -1;

            public boolean hasNext() {
              check();
              if (next != null) return true;
              try {
                while (true) {
                  if (location == null) {
                    if (!refs.hasNext()) {
                      close();
                      return false;
                    }
                    location = refs.next();
                  }
                  if (location.chunkOffset == 0) {
                    location = null;
                    continue;
                  }
                  if (contextOffset != location.basketOffset) {
                    context = container.basket(location);
                    contextOffset = location.basketOffset;
                  }
                  if (cursor == null || chunkOffset != location.chunkOffset) {
                    if (cursor != null) cursor.close();
                    cursor = container.objects(location);
                    chunkOffset = location.chunkOffset;
                    ordinal = 0;
                  }
                  boolean targeted = location.ordinal >= 0;
                  while (cursor.hasNext()) {
                    IomObject o = cursor.next();
                    int current = ordinal++;
                    if (!targeted || current == location.ordinal) {
                      next = new SelectedObject(context, o);
                      if (targeted) location = null;
                      return true;
                    }
                  }
                  if (targeted)
                    throw new IOException(
                        "Spatial/object locations are not ordered or ordinal is invalid");
                  location = null;
                }
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            }

            public SelectedObject next() {
              if (!hasNext()) throw new NoSuchElementException();
              SelectedObject result = next;
              next = null;
              return result;
            }

            public void close() throws IOException {
              if (cursor != null) {
                cursor.close();
                cursor = null;
              }
              refs.close();
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void close() throws IOException {
    if (closed) return;
    closed = true;
    IOException failure = null;
    for (Closeable c : active)
      try {
        c.close();
      } catch (IOException e) {
        failure = e;
      }
    active.clear();
    if (failure != null) throw failure;
  }
}
