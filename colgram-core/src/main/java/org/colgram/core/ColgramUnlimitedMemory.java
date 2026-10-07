package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Pins Telegram's media cache limit so viewed media never gets evicted.
 *
 * AutoDeleteMediaTask reads the "cache_limit" preference from the global settings and prunes the
 * cache down to it; the stock picker's smallest option is 300 MB, and anything the user picks
 * there silently deletes old videos and photos. Writing Integer.MAX_VALUE is the same value the
 * task treats as "no limit", so with this on the cache simply grows inside the app's own storage
 * directory - which is also why the phone's gallery never fills up: nothing is copied out of it.
 */
public final class ColgramUnlimitedMemory {

    private static final String TAG = "ColgramUnlimitedMemory";
    private static final String KEY_CACHE_LIMIT = "cache_limit";

    private ColgramUnlimitedMemory() {
    }

    public static void apply(Context context, boolean enabled) {
        if (context == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences("userconfing", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            if (enabled) {
                editor.putInt(KEY_CACHE_LIMIT, Integer.MAX_VALUE);
            } else {
                editor.remove(KEY_CACHE_LIMIT);
            }
            editor.apply();
            Log.i(TAG, "cache limit " + (enabled ? "pinned to unlimited" : "released to stock"));
        } catch (Throwable t) {
            Log.w(TAG, "cannot write cache limit", t);
        }
    }

    /** Applied once at startup so a reboot cannot resurrect a pruning limit. */
    public static void restore(Context context) {
        if (context == null || !ColgramConfig.isUnlimitedMemoryEnabled()) return;
        apply(context, true);
    }
}
