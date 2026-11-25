package org.colgram.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Battery-, screen-, and Doze-aware eco governor for Colgram's 24/7 background engine.
 *
 * Keeps Colgram alive continuously on any Android OEM (MIUI, HyperOS, OneUI, ColorOS, EMUI, AOSP)
 * while stretching background housekeeping intervals when the screen is off or battery is low so
 * CPU & radio wakeups stay near zero.
 */
public final class ColgramPowerGuard {

    private static final int THROTTLE_BELOW_PERCENT = 20;
    private static final long BATTERY_CACHE_MS = 15_000L;
    private static final long POWER_SAVE_CACHE_MS = 15_000L;

    private static volatile long cachedBatteryUpdatedAtMs = 0L;
    private static volatile boolean cachedCharging = false;
    private static volatile int cachedBatteryPercent = 100;
    private static volatile int cachedTempTenthsC = 0;

    private static volatile long cachedPowerSaveUpdatedAtMs = 0L;
    private static volatile boolean cachedPowerSaveMode = false;
    private static final AtomicBoolean powerSaveReceiverRegistered = new AtomicBoolean(false);

    private ColgramPowerGuard() {
    }

    private static long elapsedMs() {
        try {
            return SystemClock.elapsedRealtime();
        } catch (Throwable ignored) {
            return System.currentTimeMillis();
        }
    }

    private static void refreshBatterySnapshotIfNeeded(Context context) {
        if (context == null) return;
        long now = elapsedMs();
        long prev = cachedBatteryUpdatedAtMs;
        if (prev > 0L && now - prev >= 0L && now - prev < BATTERY_CACHE_MS) {
            return;
        }
        try {
            Intent battery = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return;
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            cachedCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL
                    || plugged != 0;
            cachedBatteryPercent = (level >= 0 && scale > 0) ? (level * 100 / scale) : 100;
            cachedTempTenthsC = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            cachedBatteryUpdatedAtMs = now;
        } catch (Throwable ignored) {}
    }

    /** True when the device display is currently on / user-interactive. */
    public static boolean isScreenInteractive(Context context) {
        if (context == null) return true;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return true;
            return Build.VERSION.SDK_INT >= 20 ? pm.isInteractive() : pm.isScreenOn();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Clears cached Battery Saver state so the next {@link #isPowerSaveMode(Context)} call refreshes immediately. */
    public static void invalidatePowerSaveCache() {
        cachedPowerSaveUpdatedAtMs = 0L;
    }

    private static void registerPowerSaveReceiverOnce(Context context) {
        if (powerSaveReceiverRegistered.get() || Build.VERSION.SDK_INT < 21) return;
        Context app = context.getApplicationContext();
        if (app == null || !powerSaveReceiverRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    invalidatePowerSaveCache();
                }
            };
            IntentFilter filter = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
        } catch (Throwable ignored) {
        }
    }

    /** True when Android's system Battery Saver mode is enabled (cached for 15s + broadcast-invalidated). */
    public static boolean isPowerSaveMode(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 21) return false;
        long now = elapsedMs();
        long prev = cachedPowerSaveUpdatedAtMs;
        if (prev > 0L && now - prev >= 0L && now - prev < POWER_SAVE_CACHE_MS) {
            return cachedPowerSaveMode;
        }
        registerPowerSaveReceiverOnce(context);
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean mode = pm != null && pm.isPowerSaveMode();
            cachedPowerSaveMode = mode;
            cachedPowerSaveUpdatedAtMs = now > 0L ? now : 1L;
            return mode;
        } catch (Throwable t) {
            return cachedPowerSaveMode;
        }
    }

    /** True when Doze / deep device idle mode is active. */
    public static boolean isDeviceIdle(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 23) return false;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isDeviceIdleMode();
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the user has granted "Unrestricted / Ignore battery optimizations" to Colgram. */
    public static boolean isBatteryExemptionGranted(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Cleanly offers Android's battery-optimization exemption ONCE per install on first foreground
     * launch (only if not yet granted) so OEM killers (HiOS/MIUI/OneUI) never freeze Colgram in sleep.
     */
    public static void offerBatteryExemptionOnce(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 23) return;
        if (isBatteryExemptionGranted(context)) return;
        try {
            Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            android.content.SharedPreferences prefs = app.getSharedPreferences("colgram_power_guard", Context.MODE_PRIVATE);
            if (prefs.getBoolean("battery_opt_prompted_v1", false)) return;
            prefs.edit().putBoolean("battery_opt_prompted_v1", true).apply();
            ColgramForegroundService.requestBatteryExemption(context);
        } catch (Throwable ignored) {}
    }

    /** True when the device is currently plugged into AC, USB, or wireless charging. */
    public static boolean isCharging(Context context) {
        if (context == null) return false;
        refreshBatterySnapshotIfNeeded(context);
        return cachedCharging;
    }

    /**
     * True when the device is under thermal pressure (Android 10+ THERMAL_STATUS_MODERATE or higher,
     * or battery temperature >= 40.0 C), so CPU-heavy background work like image transcoding should pause.
     */
    public static boolean isThermalThrottled(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        refreshBatterySnapshotIfNeeded(context);
        return cachedTempTenthsC >= 400;
    }

    /** True when background loops should stretch their intervals (low battery or Power Saver). */
    public static boolean shouldThrottle(Context context) {
        if (context == null) return false;
        if (isPowerSaveMode(context)) return true;
        refreshBatterySnapshotIfNeeded(context);
        if (cachedCharging) return false;
        return cachedBatteryPercent <= THROTTLE_BELOW_PERCENT;
    }

    /**
     * Adaptive eco multiplier for periodic background heartbeats:
     * - 1x (60s) when screen is ON and battery is healthy
     * - 5x (5m) when screen is OFF (pocket/idle; native MTProto socket stays connected in kernel)
     * - 6x (6m) when the device is warm (>= 40C or THERMAL_STATUS_MODERATE)
     * - 8x (8m) when Power Saver, Doze, or low battery (<= 20%) is active
     */
    public static int intervalFactor(Context context) {
        if (shouldThrottle(context) || isDeviceIdle(context)) {
            return 8;
        }
        if (isThermalThrottled(context)) {
            return 6;
        }
        if (!isScreenInteractive(context)) {
            return 5;
        }
        return 1;
    }
}
