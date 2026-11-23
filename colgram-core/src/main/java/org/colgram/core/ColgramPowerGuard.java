package org.colgram.core;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

/**
 * Battery-, screen-, and Doze-aware eco governor for Colgram's 24/7 background engine.
 *
 * Keeps Colgram alive continuously on any Android OEM (MIUI, HyperOS, OneUI, ColorOS, EMUI, AOSP)
 * while stretching background housekeeping intervals when the screen is off or battery is low so
 * CPU & radio wakeups stay near zero.
 */
public final class ColgramPowerGuard {

    private static final int THROTTLE_BELOW_PERCENT = 20;

    private ColgramPowerGuard() {
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

    /** True when Android's system Battery Saver mode is enabled. */
    public static boolean isPowerSaveMode(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 21) return false;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isPowerSaveMode();
        } catch (Throwable t) {
            return false;
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

    /** True when the device is currently plugged into AC, USB, or wireless charging. */
    public static boolean isCharging(Context context) {
        if (context == null) return false;
        try {
            Intent battery = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return false;
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            return status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL
                    || plugged != 0;
        } catch (Throwable t) {
            return false;
        }
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
        try {
            Intent battery = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int tempTenthsC = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                if (tempTenthsC >= 400) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** True when background loops should stretch their intervals (low battery or Power Saver). */
    public static boolean shouldThrottle(Context context) {
        if (context == null) return false;
        if (isPowerSaveMode(context)) return true;
        try {
            Intent battery = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return false;
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL) {
                return false;
            }
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level < 0 || scale <= 0) return false;
            return (level * 100 / scale) <= THROTTLE_BELOW_PERCENT;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Adaptive eco multiplier for periodic background heartbeats:
     * - 1x (60s) when screen is ON and battery is healthy
     * - 5x (5m) when screen is OFF (pocket/idle; native MTProto socket stays connected in kernel)
     * - 8x (8m) when Power Saver, Doze, or low battery (<= 20%) is active
     */
    public static int intervalFactor(Context context) {
        if (shouldThrottle(context) || isDeviceIdle(context)) {
            return 8;
        }
        if (!isScreenInteractive(context)) {
            return 5;
        }
        return 1;
    }
}
