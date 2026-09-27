package org.colgram.core;

import java.net.URLDecoder;
import java.util.Base64;

/** Decodes the same hex and base64url MTProxy secrets accepted by native tgnet. */
public final class ColgramMtprotoSecrets {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ColgramMtprotoSecrets() {}

    public static String canonicalHex(String value) {
        byte[] bytes = decode(value);
        if (!valid(bytes)) return null;
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            result[i * 2] = HEX[b >>> 4];
            result[i * 2 + 1] = HEX[b & 15];
        }
        return new String(result);
    }

    public static boolean isFakeTls(String value) {
        byte[] bytes = decode(value);
        return valid(bytes) && bytes.length > 17 && (bytes[0] & 0xff) == 0xee;
    }

    private static boolean valid(byte[] bytes) {
        if (bytes == null) return false;
        return bytes.length == 16
                || bytes.length == 17 && (bytes[0] & 0xff) == 0xdd
                || bytes.length > 17 && (bytes[0] & 0xff) == 0xee;
    }

    private static byte[] decode(String value) {
        if (value == null || value.isEmpty() || value.length() > 512) return null;
        try {
            // URLDecoder treats a literal '+' as a space. Preserve it for base64 links while
            // still accepting %-encoded secrets from published t.me URLs.
            String secret = URLDecoder.decode(value.trim().replace("+", "%2B"), "UTF-8");
            boolean hex = true;
            for (int i = 0; i < secret.length(); i++) {
                if (Character.digit(secret.charAt(i), 16) < 0) {
                    hex = false;
                    break;
                }
            }
            if (hex) {
                if ((secret.length() & 1) != 0) return null;
                byte[] bytes = new byte[secret.length() / 2];
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) ((Character.digit(secret.charAt(2 * i), 16) << 4)
                            | Character.digit(secret.charAt(2 * i + 1), 16));
                }
                return bytes;
            }
            return Base64.getUrlDecoder().decode(secret.replace('+', '-').replace('/', '_'));
        } catch (Exception invalid) {
            return null;
        }
    }
}
