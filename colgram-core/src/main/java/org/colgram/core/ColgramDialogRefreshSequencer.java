package org.colgram.core;

import java.util.concurrent.Executor;

/** Keeps the bot dialog cache seed ahead of the UI's storage/cache reload. */
public final class ColgramDialogRefreshSequencer {

    private ColgramDialogRefreshSequencer() {
    }

    /**
     * Runs the potentially expensive cache seed on a worker, then queues the UI refresh.
     * The finally block keeps the UI from remaining stale if seeding reports an exception.
     */
    public static void seedThenRefresh(
            Executor workerExecutor,
            Executor mainExecutor,
            Runnable seedCache,
            Runnable refreshDialogs) {
        workerExecutor.execute(() -> {
            try {
                seedCache.run();
            } finally {
                mainExecutor.execute(refreshDialogs);
            }
        });
    }
}
