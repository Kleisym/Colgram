package org.colgram.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Coalesces failed direct TCP dials and briefly cools endpoints that cannot be reached. */
public final class ColgramConnectFailureBackoff {

    private static final int MAX_ENDPOINTS = 128;

    private static final class Failure {
        int count;
        long retryAtMs;
    }

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final Map<String, Failure> failures = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Long> inFlightGeneration = new HashMap<>();
    private long generation;

    public ColgramConnectFailureBackoff(long baseDelayMs, long maxDelayMs) {
        if (baseDelayMs <= 0L || maxDelayMs < baseDelayMs) {
            throw new IllegalArgumentException("invalid connect failure backoff bounds");
        }
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    /** Returns this generation's ticket, or -1 if another dial/cooldown should be honored. */
    public synchronized long tryBegin(String endpoint, long nowMs) {
        if (endpoint == null || endpoint.isEmpty()) return -1L;
        Long active = inFlightGeneration.get(endpoint);
        if (active != null && active == generation) return -1L;
        Failure failure = failures.get(endpoint);
        if (failure != null && nowMs < failure.retryAtMs) return -1L;
        inFlightGeneration.put(endpoint, generation);
        return generation;
    }

    /** Completes only the matching dial, so an old network's late failure cannot poison a new one. */
    public synchronized long finish(String endpoint, long ticket, boolean connected, long nowMs) {
        if (endpoint == null || endpoint.isEmpty()) return 0L;
        Long active = inFlightGeneration.get(endpoint);
        if (active == null || active != ticket || ticket != generation) return 0L;
        inFlightGeneration.remove(endpoint);
        if (connected) {
            failures.remove(endpoint);
            return 0L;
        }

        Failure failure = failures.get(endpoint);
        if (failure == null) {
            failure = new Failure();
            failures.put(endpoint, failure);
        }
        failure.count = Math.min(failure.count + 1, 63);
        long delay = delayFor(failure.count);
        failure.retryAtMs = nowMs > Long.MAX_VALUE - delay ? Long.MAX_VALUE : nowMs + delay;
        trimFailures();
        return delay;
    }

    public synchronized boolean shouldSkip(String endpoint, long nowMs) {
        if (endpoint == null || endpoint.isEmpty()) return false;
        Long active = inFlightGeneration.get(endpoint);
        if (active != null && active == generation) return true;
        Failure failure = failures.get(endpoint);
        return failure != null && nowMs < failure.retryAtMs;
    }

    /** A network transition discards misses; late completions from old sockets are ignored. */
    public synchronized void clear() {
        generation++;
        failures.clear();
        inFlightGeneration.clear();
    }

    private long delayFor(int count) {
        long delay = baseDelayMs;
        for (int i = 1; i < count && delay < maxDelayMs; i++) {
            delay = delay > maxDelayMs / 2L ? maxDelayMs : delay * 2L;
        }
        return Math.min(delay, maxDelayMs);
    }

    private void trimFailures() {
        while (failures.size() > MAX_ENDPOINTS) {
            Iterator<String> oldest = failures.keySet().iterator();
            if (!oldest.hasNext()) return;
            oldest.next();
            oldest.remove();
        }
    }
}
