package org.colgram.core;

/** Process-local operation generations: cancellation makes every older callback inert. */
public final class ColgramOperationGate {
    private long generation;
    private boolean pending;

    public synchronized long begin() {
        pending = true;
        return ++generation;
    }

    public synchronized void cancel() {
        generation++;
        pending = false;
    }

    public synchronized boolean isCurrent(long token) {
        return token == generation;
    }

    public synchronized boolean isPending() {
        return pending;
    }

    public synchronized boolean finish(long token) {
        if (!isCurrent(token)) return false;
        pending = false;
        return true;
    }
}
