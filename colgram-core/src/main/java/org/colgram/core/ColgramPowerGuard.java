package org.colgram.core;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/**
 * Battery-aware throttle for Colgram's own background work.
 *
 * Telegram's LiteMode cuts features to save power; this does the opposite of cutting: user-facing
 * behaviour stays exactly as it is, and only Colgram's housekeeping - proxy sweeps, protocol
 * probes, bot polling - slows down when the battery is low and nothing is charging. Those loops
 * are the part of the app that keeps the radio awake on a schedule, so stretching their interval
 * is where the real milliamp-hours are, and nobody notices a proxy verdict arriving a minute
 * later on a dying phone.
 */
public final class ColgramPowerGuard {

    private static final int THROTTLE_BELOW_PERCENT = 20;

    private ColgramPowerGuard() {
    }

    /** True when background loops should stretch their intervals. */
    public static boolean shouldThrottle(Context context) {
        if (context == null) return false;
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

    /** Multiplier for periodic intervals: 1 when plenty of battery, 4 when throttling. */
    public static int intervalFactor(Context context) {
        return shouldThrottle(context) ? 4 : 1;
    }
}
