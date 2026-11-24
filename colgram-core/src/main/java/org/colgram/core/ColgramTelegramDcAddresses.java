package org.colgram.core;

import java.net.InetAddress;

/** The fallback socket addresses Telegram's stock client assigns to each production DC. */
public final class ColgramTelegramDcAddresses {

    private static final String[][] DC_ADDRESSES = {
            {"149.154.175.50", "149.154.175.53", "149.154.175.40", "2001:b28:f23d:f001:0:0:0:a"},
            {"149.154.167.51", "95.161.76.100", "149.154.167.41", "149.154.167.50", "149.154.167.40", "2001:67c:4e8:f002:0:0:0:a"},
            {"149.154.175.100", "149.154.174.100", "149.154.175.117", "2001:b28:f23d:f003:0:0:0:a"},
            {"149.154.167.91", "149.154.167.92", "194.221.250.50", "2001:67c:4e8:f004:0:0:0:a"},
            {"149.154.171.5", "91.108.56.100", "91.108.56.130", "149.154.170.96", "2001:b28:f23f:f005:0:0:0:a"}
    };

    private ColgramTelegramDcAddresses() {}

    static byte[] bootstrapIpv4() {
        String[] octets = DC_ADDRESSES[1][0].split("\\.");
        byte[] address = new byte[4];
        for (int i = 0; i < address.length; i++) address[i] = (byte) Integer.parseInt(octets[i]);
        return address;
    }

    /** Return 1-based Telegram production DC id (1..5), or 0 when unknown. */
    public static int dcIdFor(String host) {
        if (host == null || host.isEmpty()) return 0;
        String normalized = host.trim();
        for (int i = 0; i < DC_ADDRESSES.length; i++) {
            for (String address : DC_ADDRESSES[i]) {
                if (sameAddress(normalized, address)) return i + 1;
            }
        }
        String lower = normalized.toLowerCase(java.util.Locale.US);
        if (lower.startsWith("149.154.175.10") || lower.startsWith("149.154.175.11")) return 3;
        if (lower.startsWith("149.154.175.")) return 1;
        if (lower.startsWith("149.154.174.")) return 3;
        if (lower.startsWith("149.154.167.9")) return 4;
        if (lower.startsWith("149.154.167.") || lower.startsWith("95.161.76.") || lower.startsWith("185.76.151.")) return 2;
        if (lower.startsWith("194.221.250.") || lower.startsWith("149.154.162.") || lower.startsWith("149.154.163.")
                || lower.startsWith("149.154.164.") || lower.startsWith("149.154.165.")
                || lower.startsWith("149.154.166.")) return 4;
        if (lower.startsWith("149.154.170.") || lower.startsWith("149.154.171.")
                || lower.startsWith("91.108.56.")) return 5;
        int cidrDc = dcIdForIpv4Ranges(lower);
        if (cidrDc > 0) return cidrDc;
        if (lower.startsWith("2001:b28:f23d:f001:")) return 1;
        if (lower.startsWith("2001:67c:4e8:f002:")) return 2;
        if (lower.startsWith("2001:b28:f23d:f003:")) return 3;
        if (lower.startsWith("2001:67c:4e8:f004:")) return 4;
        if (lower.startsWith("2001:b28:f23f:f005:")) return 5;
        return 0;
    }

    private static int dcIdForIpv4Ranges(String host) {
        if (host == null || host.indexOf(':') >= 0) return 0;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return 0;
        int[] oct = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return 0;
            int v = 0;
            for (int j = 0; j < parts[i].length(); j++) {
                char ch = parts[i].charAt(j);
                if (ch < '0' || ch > '9') return 0;
                v = v * 10 + (ch - '0');
            }
            if (v > 255) return 0;
            oct[i] = v;
        }
        int a = oct[0], b = oct[1], c = oct[2];
        // 95.161.64.0/20 -> DC2
        if (a == 95 && b == 161 && c >= 64 && c <= 79) return 2;
        // 91.108.4.0/22 + 91.108.8.0/22 -> DC4; 91.108.12.0/22 + 91.108.20.0/22 -> DC2;
        // 91.108.16.0/22 + 91.108.56.0/22 -> DC5
        if (a == 91 && b == 108) {
            if (c >= 4 && c <= 11) return 4;
            if ((c >= 12 && c <= 15) || (c >= 20 && c <= 23)) return 2;
            if ((c >= 16 && c <= 19) || (c >= 56 && c <= 59)) return 5;
        }
        return 0;
    }

    /**
     * Return only configured aliases for the same DC. Unknown addresses stay on their own;
     * guessing a sibling from a shared /24 or trying another DC breaks auth-key creation.
     */
    public static String[] candidatesFor(String host) {
        if (host == null || host.isEmpty()) return new String[0];
        for (String[] dc : DC_ADDRESSES) {
            for (String address : dc) {
                if (sameAddress(host, address)) return dc.clone();
            }
        }
        return new String[]{host};
    }

    public static boolean isKnownAddress(String host) {
        if (host == null || host.isEmpty()) return false;
        if (dcIdFor(host) > 0) return true;
        String lower = host.trim().toLowerCase(java.util.Locale.US);
        return lower.startsWith("149.154.") || lower.startsWith("91.108.") || lower.startsWith("185.76.151.");
    }

    private static boolean sameAddress(String first, String second) {
        if (first.equalsIgnoreCase(second)) return true;
        if (first.indexOf(':') < 0 || second.indexOf(':') < 0) return false;
        try {
            byte[] firstBytes = InetAddress.getByName(first).getAddress();
            byte[] secondBytes = InetAddress.getByName(second).getAddress();
            if (firstBytes.length != 16 || secondBytes.length != 16) return false;
            for (int i = 0; i < firstBytes.length; i++) {
                if (firstBytes[i] != secondBytes[i]) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
