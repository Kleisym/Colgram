package org.colgram.core;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Defers optional Colgram housekeeping instead of opening Telegram-owned databases. */
public final class ColgramOptimizer {
    private static final AtomicBoolean started = new AtomicBoolean();
    private static volatile long startupAt = SystemClock.elapsedRealtime();
    private static final ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "colgram-housekeeping");
        thread.setDaemon(true);
        return thread;
    });

    private ColgramOptimizer() {}

    public static void optimizeStartup(Context context) {
        if (context != null && started.compareAndSet(false, true)) {
            startupAt = SystemClock.elapsedRealtime();
        }
    }

    /** One bounded worker handles optional startup jobs; no disk or network work runs on the UI. */
    public static void scheduleAfterStartup(Runnable task) {
        if (task == null) return;
        housekeeping.schedule(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            } catch (Throwable failure) {
                Log.w("ColgramOptimizer", "optional housekeeping failed", failure);
            }
        }, ColgramProxyPolicy.startupDelay(startupAt, SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
    }

    public static boolean shouldDeferTask() {
        return ColgramProxyPolicy.startupDelay(startupAt, SystemClock.elapsedRealtime()) > 0;
    }
}
