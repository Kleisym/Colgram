package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** The fallback and dynamic socket addresses Telegram assigns to each production DC (generic & media_only). */
public final class ColgramTelegramDcAddresses {

    private static final String PREFS_NAME = "colgram_dc_map";
    private static final String KEY_SIGNED_DC_MAP = "signed_dc_map_v1";

    /** Generic (non-media_only) DC endpoints for production DC 1..5. Never includes media_only IPs. */
    private static final String[][] DC_ADDRESSES = {
            {"149.154.175.50", "149.154.175.53", "149.154.175.56", "149.154.175.40", "2001:b28:f23d:f001:0:0:0:a"},
            {"149.154.167.51", "95.161.76.100", "149.154.167.41", "149.154.167.50", "149.154.167.40", "2001:67c:4e8:f002:0:0:0:a"},
            {"149.154.175.100", "149.154.174.100", "149.154.175.117", "2001:b28:f23d:f003:0:0:0:a"},
            {"149.154.167.91", "194.221.250.50", "2001:67c:4e8:f004:0:0:0:a"},
            {"149.154.171.5", "91.108.56.100", "91.108.56.112", "91.108.56.130", "149.154.170.96", "2001:b28:f23f:f005:0:0:0:a"}
    };

    /** Known media_only DC endpoints for production DC 1..5 (signed DC -1..-5). Never includes generic IPs. */
    private static final String[][] MEDIA_DC_ADDRESSES = {
            {"149.154.175.54", "2001:b28:f23d:f001:0:0:0:b", "2001:b28:f23d:f001:0:0:0:e"},
            {"149.154.167.222", "149.154.167.151", "2001:67c:4e8:f002:0:0:0:b", "2001:67c:4e8:f002:0:0:0:e"},
            {"149.154.175.118", "2001:b28:f23d:f003:0:0:0:b", "2001:b28:f23d:f003:0:0:0:e"},
            {"149.154.167.43", "149.154.167.92", "149.154.165.120", "2001:67c:4e8:f004:0:0:0:b", "2001:67c:4e8:f004:0:0:0:e"},
            {"91.108.56.131", "91.108.56.140", "91.108.56.170", "2001:b28:f23f:f005:0:0:0:b", "2001:b28:f23f:f005:0:0:0:e"}
    };

    /** Dynamic signed DC map learned from live TL_config.dc_options (+1..+5 generic, -1..-5 media_only, 0 CDN). */
    private static final ConcurrentHashMap<String, Integer> DYNAMIC_SIGNED_DC = new ConcurrentHashMap<>();
    private static final AtomicBoolean prefsLoaded = new AtomicBoolean(false);

    /** Built-in cold-start exact IP -> signed DC map, checked before prefix heuristics. */
    private static final Map<String, Integer> COLD_START_SIGNED_DC = buildColdStartSignedDcMap();

    private static Map<String, Integer> buildColdStartSignedDcMap() {
        Map<String, Integer> m = new HashMap<>();
        // Media-only (< 0)
        putCold(m, "149.154.167.222", -2);
        putCold(m, "149.154.167.151", -2);
        putCold(m, "149.154.167.43", -4);
        putCold(m, "149.154.167.92", -4);
        putCold(m, "149.154.165.120", -4);
        putCold(m, "149.154.175.54", -1);
        putCold(m, "91.108.56.131", -5);
        putCold(m, "91.108.56.140", -5);
        putCold(m, "91.108.56.170", -5);
        // IPv6 media-only equivalents
        putCold(m, "2001:b28:f23d:f001::b", -1);
        putCold(m, "2001:b28:f23d:f001::e", -1);
        putCold(m, "2001:67c:4e8:f002::b", -2);
        putCold(m, "2001:67c:4e8:f002::e", -2);
        putCold(m, "149.154.175.118", -3);
        putCold(m, "2001:b28:f23d:f003::b", -3);
        putCold(m, "2001:b28:f23d:f003::e", -3);
        putCold(m, "2001:67c:4e8:f004::b", -4);
        putCold(m, "2001:67c:4e8:f004::e", -4);
        putCold(m, "2001:b28:f23f:f005::b", -5);
        putCold(m, "2001:b28:f23f:f005::e", -5);

        // Generic (> 0)
        putCold(m, "149.154.175.50", 1);
        putCold(m, "149.154.175.53", 1);
        putCold(m, "149.154.175.56", 1);
        putCold(m, "149.154.175.40", 1);
        putCold(m, "149.154.167.41", 2);
        putCold(m, "149.154.167.50", 2);
        putCold(m, "149.154.167.51", 2);
        putCold(m, "149.154.167.40", 2);
        putCold(m, "95.161.76.100", 2);
        putCold(m, "149.154.175.100", 3);
        putCold(m, "149.154.174.100", 3);
        putCold(m, "149.154.175.117", 3);
        putCold(m, "149.154.167.91", 4);
        putCold(m, "194.221.250.50", 4);
        putCold(m, "91.108.56.112", 5);
        putCold(m, "91.108.56.130", 5);
        putCold(m, "91.108.56.100", 5);
        putCold(m, "149.154.171.5", 5);
        putCold(m, "149.154.170.96", 5);
        // IPv6 generic equivalents
        putCold(m, "2001:b28:f23d:f001::a", 1);
        putCold(m, "2001:67c:4e8:f002::a", 2);
        putCold(m, "2001:b28:f23d:f003::a", 3);
        putCold(m, "2001:67c:4e8:f004::a", 4);
        putCold(m, "2001:b28:f23f:f005::a", 5);
        return Collections.unmodifiableMap(m);
    }

    private static void putCold(Map<String, Integer> map, String host, int signedDc) {
        String norm = normalizeAddress(host);
        if (!norm.isEmpty()) map.put(norm, signedDc);
    }

    private ColgramTelegramDcAddresses() {}

    /** Isolated Android SharedPreferences helper so host-JVM tests never load Android classes. */
    private static final class PrefsStore {
        static void load(Context context) {
            try {
                Context app = context.getApplicationContext();
                if (app == null) app = context;
                SharedPreferences prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String blob = prefs.getString(KEY_SIGNED_DC_MAP, "");
                if (blob == null || blob.isEmpty()) return;
                for (String line : blob.split("\n")) {
                    int sep = line.indexOf('|');
                    if (sep <= 0 || sep + 1 >= line.length()) continue;
                    String host = normalizeAddress(line.substring(0, sep));
                    if (host.isEmpty()) continue;
                    try {
                        int val = Integer.parseInt(line.substring(sep + 1).trim());
                        if (val >= -5 && val <= 5) {
                            DYNAMIC_SIGNED_DC.putIfAbsent(host, val);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        static void save(Context context) {
            try {
                Context app = context.getApplicationContext();
                if (app == null) app = context;
                StringBuilder sb = new StringBuilder();
                int count = 0;
                for (Map.Entry<String, Integer> e : DYNAMIC_SIGNED_DC.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    if (count++ > 0) sb.append('\n');
                    sb.append(e.getKey()).append('|').append(e.getValue());
                    if (count >= 512) break;
                }
                app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putString(KEY_SIGNED_DC_MAP, sb.toString()).apply();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Loads persisted dynamic DC mapping on app startup when Context is available. */
    public static void init(Context context) {
        if (context == null) return;
        if (prefsLoaded.compareAndSet(false, true)) {
            PrefsStore.load(context);
        }
    }

    /**
     * Updates the dynamic signed DC map from live Telegram {@code TL_config.dc_options}.
     * CDN entries are explicitly stored as {@code 0} (never WSS-bridged); production DC 1..5
     * entries are stored as {@code -dcId} when {@code mediaOnly} and {@code +dcId} otherwise.
     */
    public static void updateFromDcOptions(Context context, int[] ids, String[] ips,
                                           boolean[] mediaOnly, boolean[] cdn, boolean[] ipv6) {
        if (context != null) init(context);
        boolean changed = applyDcOptions(ids, ips, mediaOnly, cdn, ipv6);
        if (changed && context != null) {
            PrefsStore.save(context);
        }
    }

    /** Pure-JVM core of {@link #updateFromDcOptions} without Android {@code Context} persistence. */
    public static boolean applyDcOptions(int[] ids, String[] ips,
                                         boolean[] mediaOnly, boolean[] cdn, boolean[] ipv6) {
        if (ids == null || ips == null) return false;
        int n = Math.min(ids.length, ips.length);
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            String rawIp = ips[i];
            if (rawIp == null || rawIp.isEmpty()) continue;
            String norm = normalizeAddress(rawIp);
            if (norm.isEmpty()) continue;
            boolean isCdn = cdn != null && i < cdn.length && cdn[i];
            if (isCdn) {
                Integer prev = DYNAMIC_SIGNED_DC.put(norm, 0);
                if (prev == null || prev != 0) changed = true;
                continue;
            }
            int dcId = ids[i];
            if (dcId >= 1 && dcId <= 5) {
                boolean isMedia = mediaOnly != null && i < mediaOnly.length && mediaOnly[i];
                int signed = isMedia ? -dcId : dcId;
                Integer prev = DYNAMIC_SIGNED_DC.put(norm, signed);
                if (prev == null || prev != signed) changed = true;
            }
        }
        return changed;
    }

    static void clearDynamicMapForTest() {
        DYNAMIC_SIGNED_DC.clear();
        prefsLoaded.set(false);
    }

    static byte[] bootstrapIpv4() {
        String[] octets = DC_ADDRESSES[1][0].split("\\.");
        byte[] address = new byte[4];
        for (int i = 0; i < address.length; i++) address[i] = (byte) Integer.parseInt(octets[i]);
        return address;
    }

    /**
     * Return signed Telegram production DC id:
     * {@code +1..+5} for generic DCs, {@code -1..-5} for {@code media_only} DCs, or {@code 0}
     * for CDN / unknown addresses.
     */
    public static int signedDcIdFor(String host) {
        if (host == null || host.isEmpty()) return 0;
        String norm = normalizeAddress(host);
        if (norm.isEmpty()) return 0;

        Integer dynamic = DYNAMIC_SIGNED_DC.get(norm);
        if (dynamic != null) return dynamic;

        Integer exact = COLD_START_SIGNED_DC.get(norm);
        if (exact != null) return exact;

        String lower = norm;
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

    /** True when {@code host} is a known Telegram {@code media_only} DC endpoint. */
    public static boolean isMediaOnlyHost(String host) {
        return signedDcIdFor(host) < 0;
    }

    /** Return 1-based Telegram production DC id (1..5), or 0 for CDN/unknown. */
    public static int dcIdFor(String host) {
        return Math.abs(signedDcIdFor(host));
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
     * Return only configured aliases for the same DC and same class (generic vs media_only).
     * Generic and media_only IPs are NEVER mixed; unknown or CDN addresses stay on their own.
     */
    public static String[] candidatesFor(String host) {
        if (host == null || host.isEmpty()) return new String[0];
        String trimmed = host.trim();
        String norm = normalizeAddress(trimmed);
        int signed = signedDcIdFor(trimmed);
        if (signed == 0) return new String[]{trimmed};

        if (signed < 0) {
            int idx = Math.abs(signed) - 1;
            Set<String> out = new LinkedHashSet<>();
            Set<String> seenNorm = new LinkedHashSet<>();
            seenNorm.add(norm);
            out.add(trimmed);
            for (Map.Entry<String, Integer> e : DYNAMIC_SIGNED_DC.entrySet()) {
                Integer val = e.getValue();
                if (val != null && val == signed && seenNorm.add(e.getKey())) {
                    out.add(e.getKey());
                }
            }
            if (idx >= 0 && idx < MEDIA_DC_ADDRESSES.length) {
                for (String address : MEDIA_DC_ADDRESSES[idx]) {
                    String addrNorm = normalizeAddress(address);
                    Integer dyn = DYNAMIC_SIGNED_DC.get(addrNorm);
                    if (dyn != null && dyn != signed) continue;
                    if (seenNorm.add(addrNorm)) {
                        out.add(address);
                    }
                }
            }
            return out.toArray(new String[0]);
        }

        // Generic DC (+1..+5): only expand configured/known generic addresses, never prefix-only guesses
        int idx = signed - 1;
        boolean knownGeneric = false;
        Integer dynSelf = DYNAMIC_SIGNED_DC.get(norm);
        Integer coldSelf = COLD_START_SIGNED_DC.get(norm);
        if ((dynSelf != null && dynSelf == signed) || (coldSelf != null && coldSelf == signed)) {
            knownGeneric = true;
        } else if (idx >= 0 && idx < DC_ADDRESSES.length) {
            for (String address : DC_ADDRESSES[idx]) {
                if (sameAddress(trimmed, address)) {
                    knownGeneric = true;
                    break;
                }
            }
        }
        if (!knownGeneric) return new String[]{trimmed};

        Set<String> out = new LinkedHashSet<>();
        Set<String> seenNorm = new LinkedHashSet<>();
        seenNorm.add(norm);
        out.add(trimmed);
        for (Map.Entry<String, Integer> e : DYNAMIC_SIGNED_DC.entrySet()) {
            Integer val = e.getValue();
            if (val != null && val == signed && seenNorm.add(e.getKey())) {
                out.add(e.getKey());
            }
        }
        if (idx >= 0 && idx < DC_ADDRESSES.length) {
            for (String address : DC_ADDRESSES[idx]) {
                String addrNorm = normalizeAddress(address);
                Integer dyn = DYNAMIC_SIGNED_DC.get(addrNorm);
                if (dyn != null && dyn != signed) continue;
                Integer cold = COLD_START_SIGNED_DC.get(addrNorm);
                if (cold != null && cold < 0) continue;
                if (seenNorm.add(addrNorm)) {
                    out.add(address);
                }
            }
        }
        return out.toArray(new String[0]);
    }

    public static boolean isKnownAddress(String host) {
        if (host == null || host.isEmpty()) return false;
        if (dcIdFor(host) > 0) return true;
        String lower = host.trim().toLowerCase(Locale.US);
        return lower.startsWith("149.154.") || lower.startsWith("91.108.") || lower.startsWith("185.76.151.");
    }

    private static boolean isIpv6LiteralChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || c == ':' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeAddress(String host) {
        if (host == null) return "";
        String s = host.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("[") && s.endsWith("]") && s.length() > 2) {
            s = s.substring(1, s.length() - 1).trim();
        }
        String lower = s.toLowerCase(Locale.US);
        if (lower.indexOf(':') >= 0 && isIpv6LiteralChars(lower)) {
            try {
                return InetAddress.getByName(lower).getHostAddress().toLowerCase(Locale.US);
            } catch (Exception ignored) {
            }
        }
        return lower;
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
