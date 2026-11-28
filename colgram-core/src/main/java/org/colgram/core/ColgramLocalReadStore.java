package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent monotonic local Ghost-Read watermark store per (account, dialogId).
 * Prevents server dialog syncs and cold restarts from resurrecting unread badges
 * or unread dividers for chats already read locally under Ghost Mode.
 * Pure Android Context/SharedPreferences only so pure-JVM tests run cleanly.
 */
public final class ColgramLocalReadStore {

    private static final String PREFS_NAME = "colgram_local_read_watermarks";
    private static final String KEY_PREFIX = "wm_";
    private static final Object LOCK = new Object();

    private static final ConcurrentHashMap<Integer, ConcurrentHashMap<Long, Integer>> WATERMARKS =
            new ConcurrentHashMap<>();

    private static volatile SharedPreferences prefs;
    private static volatile boolean initialized = false;

    private ColgramLocalReadStore() {}

    public static void init(Context ctx) {
        ensureInit(ctx);
    }

    public static void ensureInit(Context ctx) {
        if (ctx == null || initialized) {
            return;
        }
        synchronized (LOCK) {
            if (initialized) {
                return;
            }
            try {
                Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
                SharedPreferences sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs = sp;
                if (sp != null) {
                    Map<String, ?> all = sp.getAll();
                    if (all != null) {
                        for (Map.Entry<String, ?> entry : all.entrySet()) {
                            String k = entry.getKey();
                            if (k == null || !k.startsWith(KEY_PREFIX)) {
                                continue;
                            }
                            int sep = k.indexOf('_', KEY_PREFIX.length());
                            if (sep <= KEY_PREFIX.length() || sep + 1 >= k.length()) {
                                continue;
                            }
                            Object v = entry.getValue();
                            if (!(v instanceof Number)) {
                                continue;
                            }
                            int maxId = ((Number) v).intValue();
                            if (maxId <= 0 || maxId == Integer.MAX_VALUE) {
                                continue;
                            }
                            try {
                                int account = Integer.parseInt(k.substring(KEY_PREFIX.length(), sep));
                                long dialogId = Long.parseLong(k.substring(sep + 1));
                                if (dialogId != 0L) {
                                    mergeWatermarkLocked(account, dialogId, maxId);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                initialized = true;
            }
        }
    }

    private static boolean mergeWatermarkLocked(int account, long dialogId, int maxId) {
        ConcurrentHashMap<Long, Integer> map =
                WATERMARKS.computeIfAbsent(account, k -> new ConcurrentHashMap<>());
        Integer prev = map.get(dialogId);
        if (prev != null && prev >= maxId) {
            return false;
        }
        map.put(dialogId, maxId);
        return true;
    }

    private static String prefKey(int account, long dialogId) {
        return KEY_PREFIX + account + "_" + dialogId;
    }

    public static boolean recordLocalRead(Context ctx, int account, long dialogId, int maxId) {
        if (dialogId == 0L || maxId <= 0 || maxId == Integer.MAX_VALUE) {
            return false;
        }
        ensureInit(ctx);
        synchronized (LOCK) {
            boolean updated = mergeWatermarkLocked(account, dialogId, maxId);
            if (!updated) {
                return false;
            }
            SharedPreferences sp = prefs;
            if (sp != null) {
                try {
                    sp.edit().putInt(prefKey(account, dialogId), maxId).apply();
                } catch (Throwable ignored) {}
            }
            return true;
        }
    }

    public static int getLocalReadMax(int account, long dialogId) {
        return getLocalReadMax(null, account, dialogId);
    }

    public static int getLocalReadMax(Context ctx, int account, long dialogId) {
        if (dialogId == 0L) {
            return 0;
        }
        if (ctx != null && !initialized) {
            ensureInit(ctx);
        }
        ConcurrentHashMap<Long, Integer> map = WATERMARKS.get(account);
        if (map == null) {
            return 0;
        }
        Integer val = map.get(dialogId);
        return val != null ? val : 0;
    }

    public static boolean clearIfCaughtUp(Context ctx, int account, long dialogId, int serverReadInboxMax) {
        if (dialogId == 0L || serverReadInboxMax <= 0) {
            return false;
        }
        ensureInit(ctx);
        synchronized (LOCK) {
            ConcurrentHashMap<Long, Integer> map = WATERMARKS.get(account);
            if (map == null) {
                return false;
            }
            Integer cur = map.get(dialogId);
            if (cur == null || serverReadInboxMax < cur) {
                return false;
            }
            map.remove(dialogId);
            if (map.isEmpty()) {
                WATERMARKS.remove(account, map);
            }
            SharedPreferences sp = prefs;
            if (sp != null) {
                try {
                    sp.edit().remove(prefKey(account, dialogId)).apply();
                } catch (Throwable ignored) {}
            }
            return true;
        }
    }

    public static void clearDialog(Context ctx, int account, long dialogId) {
        if (dialogId == 0L) {
            return;
        }
        ensureInit(ctx);
        synchronized (LOCK) {
            ConcurrentHashMap<Long, Integer> map = WATERMARKS.get(account);
            if (map != null) {
                map.remove(dialogId);
                if (map.isEmpty()) {
                    WATERMARKS.remove(account, map);
                }
            }
            SharedPreferences sp = prefs;
            if (sp != null) {
                try {
                    sp.edit().remove(prefKey(account, dialogId)).apply();
                } catch (Throwable ignored) {}
            }
        }
    }

    public static void resetForTests() {
        synchronized (LOCK) {
            WATERMARKS.clear();
            prefs = null;
            initialized = false;
        }
    }
}
