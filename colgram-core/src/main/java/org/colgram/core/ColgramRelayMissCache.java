package org.colgram.core;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Short-lived, destination-specific cooldowns for failed relay tunnels.
 * A relay that cannot reach one Telegram DC is not necessarily dead for every other DC.
 */
public final class ColgramRelayMissCache {
    private static final long RETRY_DELAY_MS = 30_000L;
    private static final int MAX_ENTRIES = 512;
    private static final LinkedHashMap<String, Long> misses =
            new LinkedHashMap<>(64, 0.75f, true);

    private ColgramRelayMissCache() {
    }

    static synchronized boolean shouldSkip(String relayKey, String host, int port, long nowMs) {
        String key = key(relayKey, host, port);
        Long retryAt = misses.get(key);
        if (retryAt == null) return false;
        if (retryAt > nowMs) return true;
        misses.remove(key);
        return false;
    }

    static synchronized void recordMiss(String relayKey, String host, int port, long nowMs) {
        String key = key(relayKey, host, port);
        misses.put(key, nowMs + RETRY_DELAY_MS);
        while (misses.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<String, Long>> iterator = misses.entrySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    static synchronized void recordSuccess(String relayKey, String host, int port) {
        misses.remove(key(relayKey, host, port));
    }

    private static String key(String relayKey, String host, int port) {
        String relay = relayKey == null ? "" : relayKey;
        String target = host == null ? "" : host.trim().toLowerCase(Locale.US);
        return relay + '\u0000' + target + '\u0000' + port;
    }
}
