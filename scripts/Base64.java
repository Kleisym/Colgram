package android.util;
public final class Base64 {
  private Base64() {}
  public static final int DEFAULT = 0;
  public static final int NO_PADDING = 1;
  public static byte[] decode(String s, int flags) { return java.util.Base64.getMimeDecoder().decode(s); }
  public static byte[] decode(byte[] b, int flags) { return java.util.Base64.getMimeDecoder().decode(b); }
  public static String encodeToString(byte[] b, int flags) { return java.util.Base64.getEncoder().encodeToString(b); }
  public static String encodeToString(byte[] b, int f, int o) { return java.util.Base64.getEncoder().encodeToString(b); }
}
