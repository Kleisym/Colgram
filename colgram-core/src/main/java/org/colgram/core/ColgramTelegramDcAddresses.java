package org.colgram.core;

import java.net.InetAddress;

/** The fallback socket addresses Telegram's stock client assigns to each production DC. */
public final class ColgramTelegramDcAddresses {

    private static final String[][] DC_ADDRESSES = {
            {"149.154.175.50", "2001:b28:f23d:f001:0:0:0:a"},
            {"149.154.167.51", "95.161.76.100", "2001:67c:4e8:f002:0:0:0:a"},
            {"149.154.175.100", "2001:b28:f23d:f003:0:0:0:a"},
            {"149.154.167.91", "2001:67c:4e8:f004:0:0:0:a"},
            {"149.154.171.5", "2001:b28:f23f:f005:0:0:0:a"}
    };

    private ColgramTelegramDcAddresses() {}

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
        if (host == null) return false;
        for (String[] dc : DC_ADDRESSES) {
            for (String address : dc) {
                if (sameAddress(host, address)) return true;
            }
        }
        return false;
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
