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
    /** Eco-cold Doze keepalive (15m interval, bounded <=1.5s wake only when bots/recovery truly need it). */
    private static final long DOZE_KEEPALIVE_INTERVAL_MS = 900_000L;
    private static final long DOZE_WAKELOCK_MS = 1_500L;
    private static final long DOZE_PULSE_HOLD_MS = 250L;

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
        // Unload Koliti GGUF weights under genuine OS memory pressure OR when backgrounded while
        // the screen is off, while keeping instant re-entry during active screen-on switching.
        boolean screenOffBg = level >= TRIM_MEMORY_UI_HIDDEN && !ColgramPowerGuard.isScreenInteractive(this);
        if (ColgramBuildFlags.KOLITI_ENABLED
                && (level >= TRIM_MEMORY_BACKGROUND
                    || level == TRIM_MEMORY_RUNNING_CRITICAL
                    || screenOffBg)) {
            try {
                Class<?> aiCls = Class.forName("org.colgram.core.ColgramAiEngine");
                Object aiInst = aiCls.getMethod("getInstance").invoke(null);
                if (aiInst != null) {
                    aiCls.getMethod("unloadIfIdleInBackground").invoke(aiInst);
                }
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
        if (ColgramUiBridge.isAppInForeground() || hasAnyBotTokens()) {
            startHeartbeat();
        } else {
            stopHeartbeat();
        }
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
            if (anyBotToken) {
                ensureHumanAccountBackgroundSync();
            } else if (!ColgramUiBridge.isAppInForeground()) {
                return;
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

    private static volatile Class<?> cachedUserConfigClass;
    private static volatile java.lang.reflect.Method cachedUcGetInstance;
    private static volatile java.lang.reflect.Method cachedUcIsActivated;
    private static volatile java.lang.reflect.Method cachedUcGetCurrentUser;
    private static volatile java.lang.reflect.Field cachedUserBotField;
    private static volatile Class<?> cachedConnectionsManagerClass;
    private static volatile java.lang.reflect.Method cachedCmGetInstance;
    private static volatile java.lang.reflect.Method cachedCmSetPushConnectionEnabled;
    private static volatile java.lang.reflect.Method cachedCmResumeNetworkMaybe;
    private static volatile java.lang.reflect.Method cachedCmCheckConnection;
    private static volatile Class<?> cachedMessagesControllerClass;
    private static volatile java.lang.reflect.Method cachedMcGetInstance;
    private static volatile java.lang.reflect.Method cachedMcGetDifference;
    private static volatile java.lang.reflect.Field cachedStageQueueField;
    private static volatile java.lang.reflect.Method cachedStageQueuePostRunnable;
    private static volatile boolean reflectionInitDone = false;

    private static void ensureReflectionHandles() {
        if (reflectionInitDone) return;
        synchronized (ColgramForegroundService.class) {
            if (reflectionInitDone) return;
            try {
                Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
                cachedUserConfigClass = ucClass;
                cachedUcGetInstance = ucClass.getMethod("getInstance", int.class);
                cachedUcIsActivated = ucClass.getMethod("isClientActivated");
                cachedUcGetCurrentUser = ucClass.getMethod("getCurrentUser");
            } catch (Throwable ignored) {}
            try {
                Class<?> userClass = Class.forName("org.telegram.tgnet.TLRPC$User");
                cachedUserBotField = userClass.getField("bot");
            } catch (Throwable ignored) {}
            try {
                Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
                cachedConnectionsManagerClass = cmClass;
                cachedCmGetInstance = cmClass.getMethod("getInstance", int.class);
                try { cachedCmSetPushConnectionEnabled = cmClass.getMethod("setPushConnectionEnabled", boolean.class); } catch (Throwable ignored) {}
                try { cachedCmResumeNetworkMaybe = cmClass.getMethod("resumeNetworkMaybe"); } catch (Throwable ignored) {}
                try { cachedCmCheckConnection = cmClass.getMethod("checkConnection"); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
            try {
                Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
                cachedMessagesControllerClass = mcClass;
                cachedMcGetInstance = mcClass.getMethod("getInstance", int.class);
                cachedMcGetDifference = mcClass.getMethod("getDifference");
            } catch (Throwable ignored) {}
            try {
                Class<?> utilClass = Class.forName("org.telegram.messenger.Utilities");
                java.lang.reflect.Field sqField = utilClass.getField("stageQueue");
                Object sq = sqField.get(null);
                if (sq != null) {
                    cachedStageQueuePostRunnable = sq.getClass().getMethod("postRunnable", Runnable.class);
                    cachedStageQueueField = sqField;
                }
            } catch (Throwable ignored) {}
            reflectionInitDone = true;
        }
    }

    private static void kickActiveHumanAccounts(boolean triggerCheckConnection, boolean triggerGetDifference) {
        try {
            ensureReflectionHandles();
            if (!cachedUcGetInstanceMethodAvailable() || cachedCmGetInstance == null) {
                return;
            }
            for (int a = 0; a < 5; a++) {
                Object uc = cachedUcGetInstance.invoke(null, a);
                if (uc == null) continue;
                Boolean active = (Boolean) cachedUcIsActivated.invoke(uc);
                if (active == null || !active) continue;
                Object user = cachedUcGetCurrentUser != null ? cachedUcGetCurrentUser.invoke(uc) : null;
                if (user != null && cachedUserBotField != null) {
                    try {
                        if (cachedUserBotField.getBoolean(user)) continue;
                    } catch (Throwable ignored) {}
                }
                Object cm = cachedCmGetInstance.invoke(null, a);
                if (cm != null) {
                    if (cachedCmSetPushConnectionEnabled != null) {
                        try { cachedCmSetPushConnectionEnabled.invoke(cm, true); } catch (Throwable ignored) {}
                    }
                    if (cachedCmResumeNetworkMaybe != null) {
                        try { cachedCmResumeNetworkMaybe.invoke(cm); } catch (Throwable ignored) {}
                    }
                    if (triggerCheckConnection && cachedCmCheckConnection != null) {
                        try { cachedCmCheckConnection.invoke(cm); } catch (Throwable ignored) {}
                    }
                }
                if (triggerGetDifference && cachedMcGetInstance != null && cachedMcGetDifference != null) {
                    final int acct = a;
                    Runnable diffTask = () -> {
                        try {
                            Object mc = cachedMcGetInstance.invoke(null, acct);
                            if (mc != null) cachedMcGetDifference.invoke(mc);
                        } catch (Throwable ignored) {}
                    };
                    if (cachedStageQueueField != null && cachedStageQueuePostRunnable != null) {
                        try {
                            Object sq = cachedStageQueueField.get(null);
                            if (sq != null) {
                                cachedStageQueuePostRunnable.invoke(sq, diffTask);
                            }
                        } catch (Throwable ignored) {}
                    } else {
                        diffTask.run();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "human account sync kick error: " + t.getMessage());
        }
    }

    private void ensureHumanAccountBackgroundSync() {
        if (ColgramUiBridge.isAppInForeground()
                || !ColgramConfig.isAlwaysBackgroundEnabled()
                || !hasAnyBotTokens()
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
        // Eco cadence: 5m interactive background, 15m screen-off Doze.
        long minIntervalMs = screenOn ? 300_000L : DOZE_KEEPALIVE_INTERVAL_MS;
        if (lastHumanSyncAtMs != 0L && now - lastHumanSyncAtMs < minIntervalMs) {
            return;
        }
        lastHumanSyncAtMs = now;
        kickActiveHumanAccounts(false, false);
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
                final boolean fg = ColgramUiBridge.isAppInForeground();
                if (!fg && !hasAnyBotTokens()) {
                    // Zero-drain background policy: stop periodic wake timer once backgrounded with no bots.
                    stopHeartbeat();
                    return;
                }
                ensurePollers();
                if (handler != null && heartbeat == this) {
                    long baseMs;
                    if (fg) {
                        baseMs = HEARTBEAT_MS;
                    } else {
                        baseMs = ColgramPowerGuard.isScreenInteractive(ColgramForegroundService.this)
                                ? 60_000L : 120_000L;
                    }
                    long delay = baseMs * Math.max(1, ColgramPowerGuard.intervalFactor(ColgramForegroundService.this));
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
            if (ColgramUiBridge.isAppInForeground()) return;
            ColgramTelegramWsBridge.onBackgroundPause();
            if (inst.hasAnyBotTokens()) {
                inst.startHeartbeat();
                if (ColgramConfig.isAlwaysBackgroundEnabled()) {
                    scheduleDozeKeepalive(inst.getApplicationContext());
                }
            } else {
                // Zero-drain sleep when there are no active bot pollers: stop periodic wake loops.
                inst.stopHeartbeat();
            }
            try {
                ColgramUnlimitedMemory.trimTransientMemoryIfNeeded(inst);
            } catch (Throwable ignored) {}
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
    private static void finishDozeKeepalivePulse(PowerManager.WakeLock wakeLock) {
        dozePulseInFlight.set(false);
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (Throwable ignored) {}
        }
    }

    public static void handleDozeKeepalivePulse(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        ColgramConfig.init(app);
        if (!ColgramConfig.isAlwaysBackgroundEnabled()) return;
        final boolean hasBots = hasConfiguredBotTokens();
        if (hasBots) {
            scheduleDozeKeepalive(app);
        }
        if (!hasBots && ColgramProxyManager.isTelegramConnected() && ColgramDpiBypass.isBound()) {
            ColgramDpiBypass.reapStaleSessions(10_000L, 0L);
            return;
        }
        if (!dozePulseInFlight.compareAndSet(false, true)) return;
        PowerManager.WakeLock wl = null;
        if (hasBots && !ColgramPowerGuard.shouldThrottle(app)) {
            try {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "colgram:doze_keepalive");
                    wl.setReferenceCounted(false);
                    wl.acquire(DOZE_WAKELOCK_MS);
                }
            } catch (Throwable ignored) {}
        }
        final PowerManager.WakeLock wakeLock = wl;
        Thread t = new Thread(() -> {
            boolean scheduledAsyncRelease = false;
            try {
                ColgramDpiBypass.reapStaleSessions(10_000L, 0L);
                if (!ColgramDpiBypass.wasExplicitlyStopped() && !ColgramDpiBypass.isBound()) {
                    ColgramDpiBypass.restartIfDeadAndWait(2000L);
                }
                if (hasBots) {
                    kickActiveHumanAccounts(false, false);
                }
                if (wakeLock != null) {
                    try {
                        Looper mainLooper = Looper.getMainLooper();
                        if (mainLooper != null) {
                            new Handler(mainLooper).postDelayed(
                                    () -> finishDozeKeepalivePulse(wakeLock), DOZE_PULSE_HOLD_MS);
                            scheduledAsyncRelease = true;
                        }
                    } catch (Throwable ignored) {}
                    if (!scheduledAsyncRelease) {
                        Thread releaser = new Thread(() -> {
                            try {
                                Thread.sleep(DOZE_PULSE_HOLD_MS);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            } finally {
                                finishDozeKeepalivePulse(wakeLock);
                            }
                        }, "colgram-doze-release");
                        releaser.setDaemon(true);
                        releaser.start();
                        scheduledAsyncRelease = true;
                    }
                }
            } catch (Throwable err) {
                Log.w(TAG, "doze keepalive tick failed: " + err.getMessage());
            } finally {
                if (!scheduledAsyncRelease) {
                    finishDozeKeepalivePulse(wakeLock);
                }
            }
        }, "colgram-doze-pulse");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    private static boolean cachedUcGetInstanceMethodAvailable() {
        return cachedUcGetInstance != null && cachedUcIsActivated != null;
    }

    /** Resumes lightweight foreground heartbeat and triggers instant <1s reconnect on UI return. */
    public static void onAppForegrounded() {
        ColgramForegroundService inst = activeInstance;
        if (inst == null || inst.handler == null) return;
        inst.handler.post(() -> {
            if (ColgramUiBridge.isAppInForeground()) {
                inst.screenOffSinceMs = 0L;
                ColgramTelegramWsBridge.onForegroundResume();
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
            kickActiveHumanAccounts(true, false);
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
