package org.colgram.core;

/** Serial UI route work shared by every screen; an older screen cannot outlive its replacement. */
public final class ColgramRouteControl {
    public static final ColgramOperationGate WARP_GATE = new ColgramOperationGate();
    public static final java.util.concurrent.ExecutorService OPERATIONS =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "colgram-route-control");
                thread.setDaemon(true);
                return thread;
            });

    private ColgramRouteControl() { }
}
