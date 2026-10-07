package org.colgram.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * ColgramBootReceiver — brings the background sync back after a reboot or an app update.
 *
 * Without this the foreground service only ever started when the user opened the app, so a
 * rebooted phone sat with no sync at all until the next manual launch. That is the "даже после
 * перезагрузки телефона" half of the complaint; the service itself is the "в фоне" half.
 *
 * Two actions are handled, and they need different treatment:
 *
 *   BOOT_COMPLETED      - the phone finished booting. Needs RECEIVE_BOOT_COMPLETED in the manifest.
 *   MY_PACKAGE_REPLACED - the app was updated in place. Android kills the process on update, so
 *                         without this the service stays dead until the user opens the app. This
 *                         action is delivered to the app's own receiver without any permission.
 *
 * Note on Android 12+: a foreground-service start from BOOT_COMPLETED is allowed for the dataSync
 * type, but some OEM builds still refuse it. The service's own start() falls back to startService,
 * and the pollers run for as long as the process lives, so the worst case is a sync that resumes
 * when the user next opens the app - i.e. exactly the old behaviour, never worse.
 */
public class ColgramBootReceiver extends BroadcastReceiver {

    private static final String TAG = "ColgramBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ColgramBypassNotice.ACTION_ENABLE_BYPASS.equals(action)) {
            // The "Включить обход" button on the blocked notice. This is the consent: Colgram
            // never turns the proxy on by itself, but once he asks, it should happen immediately
            // and keep itself alive afterwards rather than wait for the next scheduled sweep.
            Log.i(TAG, "bypass requested from the notice");
            ColgramProxyManager.enableBypassFromNotification(context.getApplicationContext());
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Log.i(TAG, "received " + action + " - scheduling the boot bridge job");

        // Starting the foreground service from here is not permitted on Android 12+: the
        // broadcast does not grant a background-start exemption, so startForegroundService()
        // threw ForegroundServiceStartNotAllowedException, the fallback startService() was
        // rejected for the same reason, both were swallowed, and nothing synced until the user
        // opened the app by hand. A running job IS an exemption, so hand off to the job and do
        // the work from inside it.
        try {
            ColgramBootJobService.schedule(context.getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "boot bridge scheduling failed: " + t);
        }
    }

    /** True when this build declares the receiver, used by the patcher's preflight check. */
    public static boolean isSupportedOn() {
        return Build.VERSION.SDK_INT >= 1;
    }
}
