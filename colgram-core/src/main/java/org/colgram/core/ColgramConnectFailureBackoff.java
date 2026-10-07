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

    /**
     * What {@link #tryBegin} decided about one endpoint.
     *
     * The old contract returned a bare ticket, where {@code -1} meant two different things at once:
     * "another dial for this endpoint is already in flight, do not start a second" and "this
     * endpoint is cooling down after a failure, do not try it yet". The caller could not tell them
     * apart, so tgnet's normal retry burst - several connections to the same DC within a second -
     * was logged as a failure for every retry but the first, and each of those fake failures grew
     * the cooldown. Observed on the device:
     *
     * <pre>
     *   19:26:54.441  skipping direct TCP dial during endpoint cooldown: 149.154.167.41:443
     *   19:26:54.576  skipping direct TCP dial during endpoint cooldown: 149.154.167.41:443
     *   19:26:54.586  skipping direct TCP dial during endpoint cooldown: 149.154.167.41:443
     *   19:26:54.586  skipping direct TCP dial during endpoint cooldown: 149.154.167.41:443
     * </pre>
     *
     * One real failure there, three refusals that never touched the network. The two cases now
     * carry their own verdict so only an actual failed dial can extend a cooldown.
     */
    public static final class Decision {
        /** A ticket was granted; {@link #finish} must be called with it. */
        private final boolean granted;
        private final long ticket;
        /** True when the refusal was concurrency, not a recorded failure. */
        private final boolean coalesced;

        private Decision(boolean granted, long ticket, boolean coalesced) {
            this.granted = granted;
            this.ticket = ticket;
            this.coalesced = coalesced;
        }

        public boolean granted() {
            return granted;
        }

        public long ticket() {
            return ticket;
        }

        /** True when another dial for this endpoint is already running; this one never opened. */
        public boolean coalesced() {
            return coalesced;
        }
    }

    public ColgramConnectFailureBackoff(long baseDelayMs, long maxDelayMs) {
        if (baseDelayMs <= 0L || maxDelayMs < baseDelayMs) {
            throw new IllegalArgumentException("invalid connect failure backoff bounds");
        }
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    /**
     * Ask for permission to dial one endpoint.
     *
     * @return a granted ticket, or a refusal that says whether it was concurrency or a cooldown
     */
    public synchronized Decision acquire(String endpoint, long nowMs) {
        if (endpoint == null || endpoint.isEmpty()) return new Decision(false, -1L, false);
        Long active = inFlightGeneration.get(endpoint);
        if (active != null && active == generation) return new Decision(false, -1L, true);
        Failure failure = failures.get(endpoint);
        if (failure != null && nowMs < failure.retryAtMs) return new Decision(false, -1L, false);
        inFlightGeneration.put(endpoint, generation);
        return new Decision(true, generation, false);
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
