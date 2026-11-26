package org.colgram.core;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Bridges a boot broadcast to the foreground service.
 *
 * Starting the service directly from BroadcastReceiver.onReceive() does not work on Android
 * 12+: startForegroundService() from the background throws ForegroundServiceStartNotAllowedException
 * unless the app holds one of the documented exemptions, and BOOT_COMPLETED is not one of
 * them. The start was therefore caught, logged and silently lost — which is why nothing
 * synced until the user opened the app.
 *
 * A running job IS one of those exemptions, so the receiver schedules this job instead and the
 * service starts from inside it. setPersisted(true) re-registers the job across reboots, so
 * this does not depend on the broadcast being delivered at all.
 */
public class ColgramBootJobService extends JobService {

    private static final String TAG = "ColgramBootJob";
    static final int JOB_ID = 8801;
    static final int PROXY_REFRESH_JOB_ID = 8802;
    static final int STORAGE_OPTIMIZE_JOB_ID = 8803;
    static final long PROXY_REFRESH_INTERVAL_MS = 15L * 60L * 1000L;
    static final long STORAGE_OPTIMIZE_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    /** Queue the bridge job. Safe to call from any context and any thread. */
    public static void schedule(Context context) {
        if (context == null) {
            return;
        }
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                Log.w(TAG, "JobScheduler unavailable");
                return;
            }
            // No override deadline: with no functional constraints it only produced a JobInfo
            // warning and did not improve latency.
            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context.getPackageName(), ColgramBootJobService.class.getName()))
                    .setMinimumLatency(3000L)
                    .setPersisted(true)
                    .build();
            int result = js.schedule(job);
            Log.i(TAG, "boot bridge job scheduled: "
                    + (result == JobScheduler.RESULT_SUCCESS ? "ok" : "REJECTED"));
            scheduleProxyRefresh(context);
            scheduleStorageOptimize(context);
        } catch (Throwable t) {
            Log.w(TAG, "could not schedule boot bridge: " + t.getMessage());
        }
    }

    /** Register a persistent 6-hour background storage deduplication & compression job. */
    public static void scheduleStorageOptimize(Context context) {
        if (context == null) return;
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 24 && js.getPendingJob(STORAGE_OPTIMIZE_JOB_ID) != null) {
                return;
            }
            JobInfo job = new JobInfo.Builder(STORAGE_OPTIMIZE_JOB_ID,
                    new ComponentName(context.getPackageName(), ColgramBootJobService.class.getName()))
                    .setPeriodic(STORAGE_OPTIMIZE_INTERVAL_MS)
                    .setRequiresCharging(true)
                    .setPersisted(true)
                    .build();
            int result = js.schedule(job);
            Log.i(TAG, "persistent storage optimizer job scheduled (6h): "
                    + (result == JobScheduler.RESULT_SUCCESS ? "ok" : "REJECTED"));
        } catch (Throwable t) {
            Log.w(TAG, "could not schedule persistent storage optimizer: " + t.getMessage());
        }
    }

    /** Register a persistent network-constrained refresh even when the app process is stopped. */
    public static void scheduleProxyRefresh(Context context) {
        if (context == null) return;
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 24 && js.getPendingJob(PROXY_REFRESH_JOB_ID) != null) {
                return;
            }
            JobInfo job = new JobInfo.Builder(PROXY_REFRESH_JOB_ID,
                    new ComponentName(context.getPackageName(), ColgramBootJobService.class.getName()))
                    .setPeriodic(PROXY_REFRESH_INTERVAL_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build();
            int result = js.schedule(job);
            Log.i(TAG, "persistent proxy refresh scheduled (15m minimum): "
                    + (result == JobScheduler.RESULT_SUCCESS ? "ok" : "REJECTED"));
        } catch (Throwable t) {
            Log.w(TAG, "could not schedule persistent proxy refresh: " + t.getMessage());
        }
    }

    private static boolean canStartForegroundServiceFromJob(Context app) {
        return android.os.Build.VERSION.SDK_INT < 31
                || ColgramUiBridge.isAppInForeground()
                || ColgramPowerGuard.isBatteryExemptionGranted(app);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (params != null && params.getJobId() == STORAGE_OPTIMIZE_JOB_ID) {
            Log.i(TAG, "persistent storage optimizer job running");
            final android.content.Context app = getApplicationContext();
            Thread t = new Thread(() -> {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
                try {
                    ColgramUnlimitedMemory.runScheduledMaintenanceSync(app, true);
                    ColgramUnlimitedMemory.trimTransientMemoryIfNeeded(app);
                } catch (Throwable ignored) {
                } finally {
                    jobFinished(params, false);
                }
            }, "colgram-storage-job");
            t.setDaemon(true);
            t.start();
            return true;
        }
        if (params != null && params.getJobId() == PROXY_REFRESH_JOB_ID) {
            final android.content.Context app = getApplicationContext();
            ColgramConfig.init(app);
            boolean canSkipInBackground = !ColgramUiBridge.isAppInForeground()
                    && !ColgramForegroundService.hasConfiguredBotTokens()
                    && ColgramDpiBypass.isBound()
                    && ColgramProxyManager.isTelegramConnected()
                    && ColgramDpiBypass.hasRecentRxWithin(180_000L);
            if (canSkipInBackground) {
                jobFinished(params, false);
                return false;
            }
            Log.i(TAG, "persistent proxy refresh job running");
            ColgramProxyManager.refreshProxySourcesInBackground(app, () -> {
                try {
                    if (ColgramConfig.isAlwaysBackgroundEnabled()) {
                        if (!ColgramForegroundService.isServiceRunning()
                                && canStartForegroundServiceFromJob(app)) {
                            ColgramForegroundService.start(app);
                        }
                        ColgramBotSync.ensureAllAccountsSynced(app, "periodic-job");
                    }
                    if (ColgramConfig.isDefenderEnabled() && ColgramUiBridge.isAppInForeground()) {
                        ColgramSecurityDefender.runFullAuditAndHeal(app, "periodic-job");
                    }
                    ColgramUnlimitedMemory.trimTransientMemoryIfNeeded(app);
                } catch (Throwable ignored) {
                } finally {
                    jobFinished(params, false);
                }
            });
            return true;
        }
        Log.i(TAG, "boot bridge job running");
        final android.content.Context app = getApplicationContext();
        if (canStartForegroundServiceFromJob(app)) {
            ColgramForegroundService.start(app);
        }
        ColgramBotSync.ensureAllAccountsSynced(app, "boot-job");
        if (ColgramConfig.isDefenderEnabled()) {
            ColgramSecurityDefender.runStartupAutoHeal(app);
        }
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (params != null && params.getJobId() == PROXY_REFRESH_JOB_ID)
            ColgramProxyManager.cancelScheduledCatalogCheck();
        return false;
    }
}
