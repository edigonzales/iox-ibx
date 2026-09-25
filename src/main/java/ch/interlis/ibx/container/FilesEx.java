package ch.interlis.ibx.container;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class FilesEx {
  private FilesEx() {}

  public static String sha256(byte[] data) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256").digest(data);
      StringBuilder s = new StringBuilder();
      for (byte v : bytes) s.append(String.format("%02x", v & 255));
      return s.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static void publish(Path temporary, Path target, boolean overwrite) throws IOException {
    if (!overwrite && Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
    if (overwrite) Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    else Files.move(temporary, target);
  }

  public static void deleteTree(Path path) throws IOException {
    if (path == null || !Files.exists(path)) return;
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(
              Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException failure)
              throws IOException {
            if (failure != null) throw failure;
            Files.deleteIfExists(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  public static String number(long v) {
    return String.format(java.util.Locale.ROOT, "%020d", v);
  }
}
