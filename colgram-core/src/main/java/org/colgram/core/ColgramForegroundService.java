package org.colgram.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * ColgramForegroundService — keeps the app process alive so bot chats keep syncing.
 *
 * WHY THIS EXISTS
 *
 * The fork has no working push: FCM is blocked for this package (the Firebase project does not
 * contain org.colgram.messenger), so the app only ever learns about new messages while it is in
 * the foreground. Everything downstream of that followed:
 *
 *   * "чаты пусто пока не перезапущу приложение" - the bot poller died with the process.
 *   * "при перезаходе в colgram он фулл перезагружается" - nothing kept the process resident, so
 *     every re-entry was a cold start.
 *   * "должен работать в фоне всегда даже после перезагрузки телефона" - there was no foreground
 *     service and no boot receiver, so nothing restarted it.
 *
 * Upstream Telegram gets away without a foreground service because it has push. Without push, a
 * foreground service is the only supported way to keep a process alive across backgrounding, and a
 * BOOT_COMPLETED receiver is the only way to come back after a reboot.
 *
 * Android version notes, all deliberate:
 *   * API 26+  - must call startForeground() within ~5s of startForegroundService(), and the
 *                notification must belong to a channel. Both are handled in ensureForeground().
 *   * API 34+  - a foreground service must declare a TYPE, and the matching
 *                FOREGROUND_SERVICE_* permission must be in the manifest. We use dataSync.
 *   * API 33+  - POST_NOTIFICATIONS may be denied. startForeground() still succeeds; the
 *                notification is simply not drawn. The service is NOT killed, so the sync keeps
 *                working - which is the point. The manifest comment for this permission says it was
 *                stripped for privacy; a foreground service is the one legitimate reason to want it,
 *                so the request is best-effort and its absence is non-fatal.
 *
 * Failure is always non-fatal: if the service cannot start, the app behaves exactly as before.
 */
public class ColgramForegroundService extends Service {

    private static final String TAG = "ColgramFgService";
    private static final String CHANNEL_ID = "colgram_background";
    private static final int NOTIFICATION_ID = 771001;

    /** How often to re-assert the pollers. Cheap: startBotUpdatesPoller is idempotent. */
    private static final long HEARTBEAT_MS = 60_000L;

    private Handler handler;
    private Runnable heartbeat;
    private boolean foregroundStarted = false;

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        ensureForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: if the system kills us for memory, restart with a null intent rather than
        // leaving the app dead until the user opens it again.
        ensureForeground();
        ensurePollers();
        startHeartbeat();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopHeartbeat();
        // Do NOT tear the pollers down here. onDestroy can fire on a config change or a
        // system-initiated restart, and killing the pollers would then lose updates until the
        // user next opened the app - the exact failure this service exists to prevent.
        // They are daemon threads and are re-asserted by ensurePollers() on the next start.
        Log.i(TAG, "service destroyed; pollers left running for restart");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Foreground notification
    // ---------------------------------------------------------------------------------------

    private void ensureForeground() {
        if (foregroundStarted) {
            return;
        }
        try {
            createChannel();

            // Tapping the notification should open the app, not a blank activity.
            Intent open = null;
            try {
                open = new Intent(this, Class.forName("org.telegram.ui.LaunchActivity"));
                open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            } catch (Throwable ignored) {
                // If LaunchActivity cannot be resolved we simply ship a non-clickable
                // notification; the service is what matters, not the tap target.
            }
            PendingIntent pi = null;
            if (open != null) {
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) {
                    piFlags |= PendingIntent.FLAG_IMMUTABLE;
                }
                pi = PendingIntent.getActivity(this, 0, open, piFlags);
            }

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(this, CHANNEL_ID);
            } else {
                b = new Notification.Builder(this);
            }
            b.setContentTitle("Colgram");
            b.setContentText("Синхронизация чатов в фоне");
            b.setSmallIcon(android.R.drawable.stat_notify_sync);
            b.setOngoing(true);
            b.setShowWhen(false);
            if (pi != null) {
                b.setContentIntent(pi);
            }
            // LOW: present but silent. A syncing client has nothing to alert about, and an
            // IMPORTANCE_LOW channel is what keeps the system from treating this as a nuisance.
            if (Build.VERSION.SDK_INT >= 26) {
                b.setChannelId(CHANNEL_ID);
            }

            Notification n = Build.VERSION.SDK_INT >= 16 ? b.build() : b.getNotification();

            // 🔴 THREE-STEP FALLBACK, because a wrong or unsupported type is FATAL, not cosmetic.
            //
            // `startForeground(id, notification, type)` throws IllegalArgumentException when
            // `type` is not a subset of the `android:foregroundServiceType` declared in the
            // manifest. The first version of this file passed the literal 0x00000040, which is
            // FOREGROUND_SERVICE_TYPE_CAMERA, while the manifest declares dataSync (= 1).
            // The throw meant the service never reached the foreground, and Android then killed
            // the whole process:
            //
            //   android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException:
            //     Context.startForegroundService() did not then call Service.startForeground()
            //
            // So: use the real constant, and if anything still goes wrong fall back rather than
            // letting the exception escape into a process kill.
            boolean wentForeground = false;
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startForeground(NOTIFICATION_ID, n,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    wentForeground = true;
                } catch (Throwable typed) {
                    Log.w(TAG, "typed startForeground refused (" + typed.getMessage()
                            + "), retrying untyped");
                }
            }
            if (!wentForeground) {
                startForeground(NOTIFICATION_ID, n);
                wentForeground = true;
            }
            foregroundStarted = true;
            Log.i(TAG, "foreground started");
        } catch (Throwable t) {
            // Most likely cause on a modern device: a missing FOREGROUND_SERVICE_* permission or
            // an FGS-start restriction. The pollers still run for as long as the process lives,
            // so this degrades rather than fails.
            Log.w(TAG, "could not go foreground: " + t);
            // 🔴 CRITICAL: if we were started via startForegroundService() and never managed to
            // call startForeground(), the platform kills the process. Stopping ourselves first
            // avoids that - the pollers keep running because they are daemon threads owned by
            // the process, not by this service.
            try {
                stopSelf();
                Log.i(TAG, "stopped self to avoid ForegroundServiceDidNotStartInTime");
            } catch (Throwable ignored) {
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Фоновая синхронизация",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Держит Colgram активным, чтобы чаты синхронизировались в фоне");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            Log.w(TAG, "channel creation failed: " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Poller supervision
    // ---------------------------------------------------------------------------------------

    /**
     * Make sure a poller is running for every account that has a bot token.
     *
     * startBotUpdatesPoller() is already idempotent (it tracks claimed accounts and in-flight
     * spawns), so calling it on a timer is safe and is how a poller that died gets replaced
     * without the user noticing.
     */
    private void ensurePollers() {
        try {
            for (int account = 0; account < 5; account++) {
                try {
                    String token = ColgramBotSync.getBotToken(this, account);
                    if (token == null || token.isEmpty()) {
                        continue;
                    }
                    ColgramBotSync.startBotUpdatesPoller(this, account);
                } catch (Throwable t) {
                    // One bad account must not stop the others.
                    Log.w(TAG, "poller ensure failed for account " + account + ": " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensurePollers failed: " + t);
        }
    }

    private void startHeartbeat() {
        if (heartbeat != null) {
            return;
        }
        heartbeat = new Runnable() {
            @Override
            public void run() {
                ensurePollers();
                if (handler != null) {
                    handler.postDelayed(this, HEARTBEAT_MS);
                }
            }
        };
        handler.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    private void stopHeartbeat() {
        if (heartbeat != null && handler != null) {
            handler.removeCallbacks(heartbeat);
        }
        heartbeat = null;
    }

    // ---------------------------------------------------------------------------------------
    // Static control surface
    // ---------------------------------------------------------------------------------------

    /** Start (or re-assert) the service. Safe to call from any thread and any component. */
    public static void start(Context context) {
        if (context == null) {
            return;
        }
        try {
            Intent i = new Intent(context, ColgramForegroundService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
            Log.i(TAG, "start requested");
        } catch (Throwable t) {
            // Android 12+ blocks a foreground-service start from the background in some states.
            // Falling back to startService at least keeps the pollers alive while the process is
            // up; a later foreground entry (or the boot receiver) will promote it properly.
            try {
                context.startService(new Intent(context, ColgramForegroundService.class));
                Log.i(TAG, "start fell back to startService");
            } catch (Throwable t2) {
                Log.w(TAG, "could not start service: " + t2);
            }
        }
    }

    /** Ask the OS to stop exempting us from battery optimisation, so the service survives Doze. */
    public static void requestBatteryExemption(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 23) {
            return;
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            String pkg = context.getPackageName();
            if (pm.isIgnoringBatteryOptimizations(pkg)) {
                Log.i(TAG, "already exempt from battery optimisation");
                return;
            }
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable t) {
            // Some OEM builds refuse this intent. Not fatal - the service simply may be subject
            // to Doze, which delays the heartbeat rather than stopping it.
            Log.w(TAG, "battery exemption request failed: " + t.getMessage());
        }
    }
}
