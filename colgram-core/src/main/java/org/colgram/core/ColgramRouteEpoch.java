package org.colgram.core;

/** Shared process-local ownership for asynchronous network-mode selections. */
public final class ColgramRouteEpoch {
    private static long generation;
    private ColgramRouteEpoch() { }
    public static synchronized long next() { return ++generation; }
    public static synchronized long snapshot() { return generation; }
    public static synchronized boolean isCurrent(long token) { return generation == token; }
    /** The action must be short: install a route/request a service, never probe the network. */
    public static synchronized boolean runIfCurrent(long token, Runnable action) {
        if (generation != token) return false;
        action.run();
        return true;
    }
}
