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
    public static final String ACTION_DOZE_KEEPALIVE = "org.colgram.action.DOZE_KEEPALIVE";
    private static final int DOZE_KEEPALIVE_REQUEST_CODE = 771009;
    /** Quiet Doze-proof alarm keepalive every 5 minutes for reliable screen-off delivery & Anti-Delete without heating. */
    private static final long DOZE_KEEPALIVE_INTERVAL_MS = 300_000L;
    private static final long DOZE_WAKELOCK_MS = 4_000L;

    /** Lightweight passive 30-second bypass/poller check interval. */
    private static final long HEARTBEAT_MS = 30_000L;

    private Handler handler;
    private Runnable heartbeat;
    private boolean foregroundStarted = false;
    private static volatile boolean serviceRunning = false;
    private static volatile ColgramForegroundService activeInstance;
    private static volatile long lastDefenderSweepAtMs = 0L;
    private static volatile long screenOffSinceMs = 0L;
    private static volatile long fgsCooldownUntilMs = 0L;
    private static volatile long startInFlightUntilMs = 0L;
    private static final long FGS_TIMEOUT_COOLDOWN_MS = 30L * 60L * 1000L;
    private static final long START_DEBOUNCE_MS = 4_000L;
    private static final java.util.concurrent.atomic.AtomicBoolean dozePulseInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static boolean isServiceRunning() {
        return serviceRunning;
    }

    private static long safeElapsedRealtime() {
        try {
            return android.os.SystemClock.elapsedRealtime();
        } catch (Throwable ignored) {
            return System.currentTimeMillis();
        }
    }

    static boolean isInFgsCooldown(long nowMs) {
        long until = fgsCooldownUntilMs;
        return until > 0L && nowMs < until;
    }

    static long getFgsCooldownUntilMs() {
        return fgsCooldownUntilMs;
    }

    static void resetStateForTest() {
        fgsCooldownUntilMs = 0L;
        startInFlightUntilMs = 0L;
        serviceRunning = false;
        activeInstance = null;
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        startInFlightUntilMs = 0L;
        handler = new Handler(Looper.getMainLooper());
        activeInstance = this;
        ensureForeground();
        if (!foregroundStarted) {
            serviceRunning = false;
            if (activeInstance == this) activeInstance = null;
            return;
        }
        serviceRunning = true;
        // Register persistent self-healing JobScheduler watchdog + Doze-proof 5m keepalive pulse
        // so OEM killers / screen-off Doze can never silently stall incoming messages or Anti-Delete.
        if (ColgramConfig.isAlwaysBackgroundEnabled()) {
            try {
                ColgramBootJobService.scheduleProxyRefresh(getApplicationContext());
            } catch (Throwable ignored) {}
            scheduleDozeKeepalive(getApplicationContext());
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // DO NOT unload Koliti weights on normal background switch (TRIM_MEMORY_UI_HIDDEN=20):
        // only release GGUF weights under genuine critical OS memory pressure so reopening Koliti
        // stays instant instead of triggering a 10-15s cold disk reload.
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE) {
            try {
                ColgramAiEngine.getInstance().unloadIfIdleInBackground();
            } catch (Throwable ignored) {}
        }
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            try {
                ColgramUnlimitedMemory.trimTransientMemoryIfNeeded(this);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: if the system kills us for memory, restart with a null intent rather than
        // leaving the app dead until the user opens it again.
        startInFlightUntilMs = 0L;
        activeInstance = this;
        ensureForeground();
        if (!foregroundStarted) {
            serviceRunning = false;
            if (activeInstance == this) activeInstance = null;
            return START_NOT_STICKY;
        }
        serviceRunning = true;
        ensurePollers();
        startHeartbeat();
        if (ColgramConfig.isAlwaysBackgroundEnabled()) {
            scheduleDozeKeepalive(getApplicationContext());
        }
        return START_STICKY;
    }

    /** Android 14+ (API 34/35+) FGS timeout callback: back off safely instead of tight restart loop. */
    public void onTimeout(int startId) {
        onTimeout(startId, 0);
    }

    /** Android 15 (API 35+) timed FGS callback: set cooldown, stop cleanly, and reschedule periodic job only. */
    public void onTimeout(int startId, int fgsType) {
        Log.i(TAG, "foreground quota timeout reached (type=" + fgsType + "), entering cooldown and stopping safely");
        fgsCooldownUntilMs = safeElapsedRealtime() + FGS_TIMEOUT_COOLDOWN_MS;
        stopHeartbeat();
        foregroundStarted = false;
        serviceRunning = false;
        if (activeInstance == this) activeInstance = null;
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) {}
        try {
            scheduleProxyRefreshIfNotPending(getApplicationContext());
        } catch (Throwable ignored) {}
        try {
            stopSelf(startId);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {
        stopHeartbeat();
        serviceRunning = false;
        if (activeInstance == this) activeInstance = null;
        // Do NOT tear the pollers down here. onDestroy can fire on a config change or a
        // system-initiated restart, and killing the pollers would then lose updates until the
        // user next opened the app - the exact failure this service exists to prevent.
        // They are daemon threads and are re-asserted by ensurePollers() on the next start.
        Log.i(TAG, "service destroyed; pollers left running for restart");
        if (ColgramConfig.isAlwaysBackgroundEnabled()) {
            try {
                ColgramBootJobService.scheduleProxyRefresh(getApplicationContext());
            } catch (Throwable ignored) {}
        }
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
            b.setContentText("Тихий эко-фон 24/7");
            b.setSmallIcon(android.R.drawable.stat_notify_sync);
            b.setOngoing(true);
            b.setShowWhen(false);
            if (Build.VERSION.SDK_INT >= 16) {
                b.setPriority(Notification.PRIORITY_MIN);
            }
            if (Build.VERSION.SDK_INT >= 21) {
                b.setVisibility(Notification.VISIBILITY_SECRET);
            }
            if (pi != null) {
                b.setContentIntent(pi);
            }
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
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                    wentForeground = true;
                } catch (Throwable typed) {
                    Log.w(TAG, "typed specialUse startForeground refused (" + typed.getMessage()
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
            // an FGS-start restriction. Enter cooldown and stop cleanly rather than looping.
            foregroundStarted = false;
            serviceRunning = false;
            if (activeInstance == this) activeInstance = null;
            fgsCooldownUntilMs = safeElapsedRealtime() + FGS_TIMEOUT_COOLDOWN_MS;
            Log.w(TAG, "could not go foreground: " + t);
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
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Удерживает Colgram в эко-фоне без расхода заряда");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.enableLights(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
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
    private static long lastHumanSyncAtMs = 0L;

    /**
     * Make sure a poller is running for every bot account AND keep active human accounts synced
     * cleanly in the background so anti-delete / edit history / vaults capture every chat update
     * without exceeding RAM/battery budgets.
     */
    private boolean hasAnyBotTokens() {
        for (int account = 0; account < 5; account++) {
            try {
                String token = ColgramBotSync.getBotToken(this, account);
                if (token != null && !token.isEmpty()) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private void ensurePollers() {
        try {
            boolean anyBotToken = false;
            for (int account = 0; account < 5; account++) {
                try {
                    String token = ColgramBotSync.getBotToken(this, account);
                    if (token == null || token.isEmpty()) {
                        continue;
                    }
                    anyBotToken = true;
                    ColgramBotSync.startBotUpdatesPoller(this, account);
                } catch (Throwable t) {
                    // One bad account must not stop the others.
                    Log.w(TAG, "poller ensure failed for account " + account + ": " + t.getMessage());
                }
            }
            if (anyBotToken || ColgramConfig.isAlwaysBackgroundEnabled()) {
                ensureHumanAccountBackgroundSync();
            }
            ColgramUnlimitedMemory.trimTransientMemoryIfNeeded(this);
            if (!ColgramDpiBypass.wasExplicitlyStopped() && !ColgramDpiBypass.isBound()) {
                ColgramOptimizer.scheduleAfterStartup(() -> {
                    if (ColgramDpiBypass.restartIfDeadAndWait(2000L)) {
                        kickHumanConnectionsNow();
                    }
                });
            }
            if (!ColgramUiBridge.isAppInForeground()) {
                return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (ColgramConfig.isDefenderEnabled()
                    && (lastDefenderSweepAtMs == 0L || now - lastDefenderSweepAtMs > 15L * 60L * 1000L)) {
                lastDefenderSweepAtMs = now;
                ColgramOptimizer.scheduleAfterStartup(
                        () -> ColgramSecurityDefender.runFullAuditAndHeal(getApplicationContext(), "background-watchdog"));
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensurePollers failed: " + t);
        }
    }

    private void ensureHumanAccountBackgroundSync() {
        if (ColgramUiBridge.isAppInForeground()
                || !ColgramConfig.isAlwaysBackgroundEnabled()
                || ColgramPowerGuard.shouldThrottle(this)) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        boolean screenOn = ColgramPowerGuard.isScreenInteractive(this);
        if (!screenOn) {
            if (screenOffSinceMs == 0L) {
                screenOffSinceMs = now;
            }
        } else {
            screenOffSinceMs = 0L;
        }
        // Zero-drain eco cadence: 5 min when screen is ON, 3 min Doze-aware tick when screen is OFF.
        long minIntervalMs = screenOn ? 300_000L : DOZE_KEEPALIVE_INTERVAL_MS;
        if (lastHumanSyncAtMs != 0L && now - lastHumanSyncAtMs < minIntervalMs) {
            return;
        }
        lastHumanSyncAtMs = now;
        try {
            Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
            Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
            Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
            for (int a = 0; a < 5; a++) {
                Object uc = ucClass.getMethod("getInstance", int.class).invoke(null, a);
                if (uc == null) continue;
                Boolean active = (Boolean) ucClass.getMethod("isClientActivated").invoke(uc);
                if (active == null || !active) continue;
                Object user = ucClass.getMethod("getCurrentUser").invoke(uc);
                if (user != null) {
                    try {
                        boolean isBot = user.getClass().getField("bot").getBoolean(user);
                        if (isBot) continue; // bot pollers already handled via Bot API loop
                    } catch (Throwable ignored) {}
                }
                Object cm = cmClass.getMethod("getInstance", int.class).invoke(null, a);
                if (cm != null) {
                    try { cmClass.getMethod("setPushConnectionEnabled", boolean.class).invoke(cm, true); } catch (Throwable ignored) {}
                    try { cmClass.getMethod("resumeNetworkMaybe").invoke(cm); } catch (Throwable ignored) {}
                    cmClass.getMethod("checkConnection").invoke(cm);
                }
                if (screenOn && handler != null) {
                    final int acc = a;
                    handler.postDelayed(() -> {
                        try {
                            Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, acc);
                            if (mc != null) {
                                mcClass.getMethod("loadDialogs", int.class, int.class, boolean.class)
                                        .invoke(mc, 0, 15, false);
                            }
                        } catch (Throwable ignored) {}
                    }, 300L * (a + 1));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "human background sync tick error: " + t.getMessage());
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (!ColgramConfig.isAlwaysBackgroundEnabled() && ColgramDpiBypass.wasExplicitlyStopped()) return;
        try {
            ColgramBootJobService.schedule(this);
            // Only schedule a PendingIntent.getForegroundService alarm when battery-optimization
            // exemption is granted; on Android 12+ (targetSdk 36), an inexact alarm without
            // battery exemption throws ForegroundServiceStartNotAllowedException.
            if (Build.VERSION.SDK_INT >= 31 && !ColgramPowerGuard.isBatteryExemptionGranted(this)) {
                return;
            }
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                Intent restart = new Intent(this, ColgramForegroundService.class);
                int flags = android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) {
                    flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
                }
                android.app.PendingIntent pi = Build.VERSION.SDK_INT >= 26
                        ? android.app.PendingIntent.getForegroundService(this, 1007, restart, flags)
                        : android.app.PendingIntent.getService(this, 1007, restart, flags);
                long triggerAt = android.os.SystemClock.elapsedRealtime() + 4_000L;
                if (Build.VERSION.SDK_INT >= 23) {
                    am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                } else {
                    am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onTaskRemoved reschedule failed: " + t.getMessage());
        }
    }

    private void startHeartbeat() {
        if (heartbeat != null || handler == null) {
            return;
        }
        heartbeat = new Runnable() {
            @Override
            public void run() {
                if (!ColgramUiBridge.isAppInForeground() && !hasAnyBotTokens()
                        && !ColgramConfig.isAlwaysBackgroundEnabled()) {
                    if (!ColgramDpiBypass.wasExplicitlyStopped() && !ColgramDpiBypass.isBound()) {
                        ColgramOptimizer.scheduleAfterStartup(() -> {
                            if (ColgramDpiBypass.restartIfDeadAndWait(2000L)) {
                                kickHumanConnectionsNow();
                            }
                        });
                    }
                } else {
                    ensurePollers();
                }
                if (handler != null && heartbeat == this) {
                    long delay = HEARTBEAT_MS * Math.max(1, ColgramPowerGuard.intervalFactor(ColgramForegroundService.this));
                    handler.postDelayed(this, delay);
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

    /** Stops periodic CPU wake timers immediately when backgrounded without active bots. */
    static boolean hasConfiguredBotTokens() {
        ColgramForegroundService inst = activeInstance;
        return inst != null && inst.hasAnyBotTokens();
    }

    public static void onAppBackgrounded() {
        ColgramForegroundService inst = activeInstance;
        if (inst == null || inst.handler == null) return;
        inst.handler.post(() -> {
            // Keep START_STICKY service, passive 30s bypass watchdog, 3m Doze keepalive, and
            // 127.0.0.1:9876 ALWAYS alive in background so chats, notifications, and Anti-Delete continue 24/7.
            // Do NOT unload Koliti weights on normal background switch (only on critical memory trim).
            inst.startHeartbeat();
            if (ColgramConfig.isAlwaysBackgroundEnabled()) {
                scheduleDozeKeepalive(inst.getApplicationContext());
            }
            if (!ColgramUiBridge.isAppInForeground()) {
                try {
                    ColgramUnlimitedMemory.trimTransientMemoryIfNeeded(inst);
                } catch (Throwable ignored) {}
            }
        });
    }

    /** Schedules a lightweight Doze-proof 3-minute AlarmManager keepalive pulse via ColgramBootReceiver. */
    public static void scheduleDozeKeepalive(Context context) {
        if (context == null || !ColgramConfig.isAlwaysBackgroundEnabled()) return;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(app, ColgramBootReceiver.class);
            intent.setAction(ACTION_DOZE_KEEPALIVE);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(app, DOZE_KEEPALIVE_REQUEST_CODE, intent, flags);
            long triggerAt = android.os.SystemClock.elapsedRealtime() + DOZE_KEEPALIVE_INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            } else {
                am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            }
        } catch (Throwable t) {
            Log.w(TAG, "scheduleDozeKeepalive failed: " + t.getMessage());
        }
    }

    /**
     * Executed on the 3-minute Doze keepalive tick: acquires a brief 4s PARTIAL_WAKE_LOCK only for
     * the pulse, confirms fixed loopback 127.0.0.1:9876 is bound, ensures push connections stay ON,
     * and wakes tgnet via resumeNetworkMaybe() so messages & Anti-Delete save even with screen OFF.
     */
    public static void handleDozeKeepalivePulse(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        ColgramConfig.init(app);
        if (!ColgramConfig.isAlwaysBackgroundEnabled()) return;
        scheduleDozeKeepalive(app);
        if (!dozePulseInFlight.compareAndSet(false, true)) return;
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "colgram:doze_keepalive");
                wl.setReferenceCounted(false);
                wl.acquire(DOZE_WAKELOCK_MS);
            }
        } catch (Throwable ignored) {}
        final PowerManager.WakeLock wakeLock = wl;
        Thread t = new Thread(() -> {
            try {
                if (!ColgramDpiBypass.wasExplicitlyStopped() && !ColgramDpiBypass.isBound()) {
                    ColgramDpiBypass.restartIfDeadAndWait(2000L);
                }
                Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
                Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
                for (int a = 0; a < 5; a++) {
                    Object uc = ucClass.getMethod("getInstance", int.class).invoke(null, a);
                    if (uc == null) continue;
                    Boolean activated = (Boolean) ucClass.getMethod("isClientActivated").invoke(uc);
                    if (activated == null || !activated) continue;
                    Object cm = cmClass.getMethod("getInstance", int.class).invoke(null, a);
                    if (cm != null) {
                        try { cmClass.getMethod("setPushConnectionEnabled", boolean.class).invoke(cm, true); } catch (Throwable ignored) {}
                        try { cmClass.getMethod("resumeNetworkMaybe").invoke(cm); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable err) {
                Log.w(TAG, "doze keepalive tick failed: " + err.getMessage());
            } finally {
                dozePulseInFlight.set(false);
                if (wakeLock != null) {
                    try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
                }
            }
        }, "colgram-doze-pulse");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    /** Resumes lightweight foreground heartbeat and triggers instant <1s reconnect on UI return. */
    public static void onAppForegrounded() {
        ColgramForegroundService inst = activeInstance;
        if (inst == null || inst.handler == null) return;
        inst.handler.post(() -> {
            if (ColgramUiBridge.isAppInForeground()) {
                inst.screenOffSinceMs = 0L;
                inst.ensurePollers();
                inst.startHeartbeat();
                if (!ColgramProxyManager.isTelegramConnected()) {
                    ColgramOptimizer.scheduleAfterStartup(inst::kickHumanConnectionsNow);
                }
            }
        });
    }

    private void kickHumanConnectionsNow() {
        try {
            if (!ColgramDpiBypass.wasExplicitlyStopped() && !ColgramDpiBypass.isBound()) {
                ColgramDpiBypass.startImmediately();
            }
            Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
            Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
            for (int a = 0; a < 5; a++) {
                Object uc = ucClass.getMethod("getInstance", int.class).invoke(null, a);
                boolean activated = (boolean) ucClass.getMethod("isClientActivated").invoke(uc);
                if (!activated) continue;
                Object cm = cmClass.getMethod("getInstance", int.class).invoke(null, a);
                if (cm != null) {
                    try { cmClass.getMethod("setPushConnectionEnabled", boolean.class).invoke(cm, true); } catch (Throwable ignored) {}
                    try { cmClass.getMethod("resumeNetworkMaybe").invoke(cm); } catch (Throwable ignored) {}
                    cmClass.getMethod("checkConnection").invoke(cm);
                }
            }
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------------------------------
    // Static control surface
    // ---------------------------------------------------------------------------------------

    private static void scheduleProxyRefreshIfNotPending(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 21) return;
        try {
            android.app.job.JobScheduler js =
                    (android.app.job.JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null && Build.VERSION.SDK_INT >= 24 && js.getPendingJob(8802) != null) {
                return;
            }
            ColgramBootJobService.scheduleProxyRefresh(context);
        } catch (Throwable ignored) {}
    }

    /** Start (or re-assert) the service. Safe to call from any thread and any component. */
    public static void start(Context context) {
        if (context == null || serviceRunning) {
            return;
        }
        long now = safeElapsedRealtime();
        if (isInFgsCooldown(now) || now < startInFlightUntilMs) {
            return;
        }
        startInFlightUntilMs = now + START_DEBOUNCE_MS;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            Intent i = new Intent(app, ColgramForegroundService.class);
            if (Build.VERSION.SDK_INT >= 31
                    && !ColgramUiBridge.isAppInForeground()
                    && !ColgramPowerGuard.isBatteryExemptionGranted(app)) {
                // On Android 12+ (targetSdk 36), calling startForegroundService() from the background
                // without battery-optimization exemption throws ForegroundServiceStartNotAllowedException.
                // Defer cleanly via JobScheduler rather than scheduling an un-exempt FGS crash.
                startInFlightUntilMs = 0L;
                scheduleProxyRefreshIfNotPending(app);
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(i);
            } else {
                app.startService(i);
            }
            Log.i(TAG, "start requested");
        } catch (Throwable t) {
            startInFlightUntilMs = 0L;
            fgsCooldownUntilMs = now + 60_000L;
            Log.w(TAG, "could not start foreground service directly; deferring via JobScheduler: " + t);
            scheduleProxyRefreshIfNotPending(app);
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
