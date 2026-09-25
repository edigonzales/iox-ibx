package ch.interlis.ibx.index;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Unsigned byte keys; text components are escaped and terminated, ordinals are fixed-width. */
public final class Keys {
  private Keys() {}

  public static int compare(byte[] a, byte[] b) {
    int n = Math.min(a.length, b.length);
    for (int i = 0; i < n; i++) {
      int c = Integer.compare(a[i] & 255, b[i] & 255);
      if (c != 0) return c;
    }
    return Integer.compare(a.length, b.length);
  }

  public static boolean startsWith(byte[] a, byte[] p) {
    if (a.length < p.length) return false;
    for (int i = 0; i < p.length; i++) if (a[i] != p[i]) return false;
    return true;
  }

  public static int common(byte[] a, byte[] b) {
    int i = 0;
    while (i < a.length && i < b.length && a[i] == b[i]) i++;
    return i;
  }

  public static byte[] encode(String key) {
    if (key.indexOf('\0') < 0) return key.getBytes(StandardCharsets.UTF_8);
    String[] parts = key.split("\u0000", -1);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(parts[0].charAt(0));
    for (int i = 1; i < parts.length; i++) {
      String part = parts[i];
      if (part.isEmpty() && i == parts.length - 1) break;
      boolean text =
          (parts[0].equals("O") || parts[0].equals("B") || parts[0].equals("Q"))
              || (parts[0].equals("R") && i == 3)
              || ((parts[0].equals("C") || parts[0].equals("T")) && i == 1)
              || part.equals("!basket");
      if (!text) {
        long value = Long.parseLong(part);
        if (value < 0) throw new IllegalArgumentException("Negative position key");
        out.write(2);
        for (int j = 7; j >= 0; j--) out.write((int) (value >>> (j * 8)));
      } else {
        out.write(1);
        for (byte b : part.getBytes(StandardCharsets.UTF_8)) {
          out.write(b);
          if (b == 0) out.write(255);
        }
        out.write(0);
        out.write(0);
      }
    }
    return out.toByteArray();
  }
}
