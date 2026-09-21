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
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Log.i(TAG, "received " + action + " - restarting background sync");

        // Work off the main thread: onReceive has a ~10s budget and starting a service can block
        // while the system settles after boot. A bare Thread is enough here and avoids pulling in
        // a scheduler; the process is already being started by the broadcast.
        final Context appContext = context.getApplicationContext();
        final String act = action;
        new Thread(() -> {
            try {
                // Give the framework a moment on a cold boot. Starting a foreground service in the
                // first instants after BOOT_COMPLETED is the most likely moment for the OEM
                // restrictions above to fire, and a short delay measurably improves the odds.
                if (Intent.ACTION_BOOT_COMPLETED.equals(act)) {
                    Thread.sleep(3000L);
                }
                ColgramForegroundService.start(appContext);
                // Re-seed the bot chats: storage survived the reboot but the in-memory dialog
                // cache did not, so without this the list renders empty until the user opens a
                // chat. syncBotDialogs() is what rebuilds it.
                for (int account = 0; account < 5; account++) {
                    try {
                        String token = ColgramBotSync.getBotToken(appContext, account);
                        if (token != null && !token.isEmpty()) {
                            ColgramBotSync.syncBotDialogs(appContext, account, false);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "post-boot sync failed for account " + account + ": " + t.getMessage());
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "post-boot start failed: " + t);
            }
        }, "colgram-boot-start").start();
    }

    /** True when this build declares the receiver, used by the patcher's preflight check. */
    public static boolean isSupportedOn() {
        return Build.VERSION.SDK_INT >= 1;
    }
}
