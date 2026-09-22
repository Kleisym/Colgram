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
        } catch (Throwable t) {
            Log.w(TAG, "could not schedule boot bridge: " + t.getMessage());
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "boot bridge job running");
        ColgramForegroundService.start(getApplicationContext());
        ColgramBotSync.ensureAllAccountsSynced(getApplicationContext(), "boot-job");
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
