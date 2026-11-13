package org.colgram.core;

/** Generation-checked service verdicts. No Android dependencies, I/O or native loads. */
public final class ColgramWarpState {
    public static final int STARTING = 0, UP = 1, FAILED = 2, STOPPED = 3;
    public static final long HEARTBEAT_TTL_MS = 35_000L;
    private long generation;
    private int state = STOPPED;
    private long confirmedAt = -1;

    public synchronized long begin() {
        generation++;
        state = STARTING;
        confirmedAt = -1;
        return generation;
    }

    public synchronized boolean accept(long attempt, int result, long now) {
        if (attempt != generation || result < STARTING || result > STOPPED) return false;
        if (state == STOPPED || state == FAILED) return false;
        state = result;
        confirmedAt = result == UP ? now : -1;
        return true;
    }

    public synchronized void stop() {
        generation++;
        state = STOPPED;
        confirmedAt = -1;
    }

    public synchronized boolean isCurrent(long attempt) { return generation == attempt; }

    public synchronized boolean isUp(long now) {
        long age = now - confirmedAt;
        return state == UP && confirmedAt >= 0 && age >= 0 && age <= HEARTBEAT_TTL_MS;
    }
}
