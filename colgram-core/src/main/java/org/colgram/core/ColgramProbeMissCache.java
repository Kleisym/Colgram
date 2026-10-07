package org.colgram.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Coalesces concurrent address scans and cools down candidate sets that just failed. */
public final class ColgramProbeMissCache {

    private static final int MAX_FAILURES = 128;

    private final long cooldownMs;
    private final Map<String, Long> failedAt = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Long> inFlightGeneration = new HashMap<>();
    private long generation;

    public ColgramProbeMissCache(long cooldownMs) {
        if (cooldownMs <= 0L) throw new IllegalArgumentException("cooldownMs must be positive");
        this.cooldownMs = cooldownMs;
    }

    /** Returns true only for the caller that owns the one active scan for this key. */
    public synchronized boolean tryBegin(String key, long nowMs) {
        if (key == null || key.isEmpty() || inFlightGeneration.containsKey(key)) return false;
        Long failed = failedAt.get(key);
        if (failed != null) {
            if (nowMs - failed < cooldownMs) return false;
            failedAt.remove(key);
        }
        inFlightGeneration.put(key, generation);
        return true;
    }

    /** Stores a miss only if no network-change invalidation happened during the scan. */
    public synchronized void finish(String key, boolean succeeded, long nowMs) {
        Long scanGeneration = inFlightGeneration.remove(key);
        if (scanGeneration == null || scanGeneration != generation) return;
        if (succeeded) {
            failedAt.remove(key);
            return;
        }
        failedAt.put(key, nowMs);
        while (failedAt.size() > MAX_FAILURES) {
            Iterator<String> oldest = failedAt.keySet().iterator();
            if (!oldest.hasNext()) break;
            oldest.next();
            oldest.remove();
        }
    }

    /** A network transition invalidates misses without starting duplicate active scans. */
    public synchronized void clearFailures() {
        generation++;
        failedAt.clear();
    }

    /** Used to report a skipped request without claiming that another scan just ran. */
    public synchronized boolean isSuppressed(String key, long nowMs) {
        if (inFlightGeneration.containsKey(key)) return true;
        Long failed = failedAt.get(key);
        if (failed == null) return false;
        if (nowMs - failed < cooldownMs) return true;
        failedAt.remove(key);
        return false;
    }
}
