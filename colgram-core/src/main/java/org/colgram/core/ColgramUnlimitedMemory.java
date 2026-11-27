package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pins Telegram's media cache limit so viewed media never gets evicted, while multiplying
 * effective storage capacity through content-addressed SHA-256 deduplication, EXIF/color-safe
 * single-pass image compression, regenerable waste pruning, and an emergency low-disk safety valve.
 */
public final class ColgramUnlimitedMemory {

    private static final String TAG = "ColgramUnlimitedMemory";
    private static final String TELEGRAM_MAINCONFIG = "mainconfig";
    private static final String KEY_CACHE_LIMIT = "cache_limit";
    private static final String KEY_KEEP_MEDIA = "keep_media";
    private static final String KEY_KEEP_MEDIA_TYPE_PREFIX = "keep_media_type_";
    private static final int KEEP_MEDIA_FOREVER = 2;
    private static final int KEEP_MEDIA_ONE_MONTH = 1;

    private static volatile boolean compressionRunning = false;
    private static final String PREF_COMPRESS_STATS = "colgram_storage_stats";
    private static final String KEY_COMPRESSED_FILES = "compressed_files_total";
    private static final String KEY_COMPRESSED_SAVED_BYTES = "compressed_saved_bytes";
    private static final String KEY_DEDUPED_FILES = "deduped_files_total";
    private static final String KEY_DEDUPED_SAVED_BYTES = "deduped_saved_bytes";
    private static final String KEY_RECLAIMED_WASTE_BYTES = "reclaimed_waste_bytes";
    private static final String KEY_CLOUD_OFFLOADED_FILES = "cloud_offloaded_files";
    private static final String KEY_CLOUD_OFFLOADED_BYTES = "cloud_offloaded_bytes";
    private static final String KEY_COMPRESSION_PRESET = "compression_preset";
    private static final String KEY_SMART_CLOUD_OFFLOAD = "smart_cloud_offload_enabled";
    private static final String KEY_LAST_AUTO_SWEEP_MS = "last_auto_sweep_ms";
    private static final long AUTO_SWEEP_MIN_INTERVAL_MS = 4L * 60L * 60L * 1000L;

    private static final int DEFAULT_FILES_PER_SWEEP = 32;
    private static final int CHARGING_FILES_PER_SWEEP = 150;

    public interface OptimizeCallback {
        void onComplete(ColgramStorageOptimizer.SweepResult result, String summaryText);
    }

    private ColgramUnlimitedMemory() {
    }

    /**
     * Returns true if {@code file} was compressed or deduplicated in-place by Colgram so
     * FileLoadOperation never treats its smaller byte length as a corrupt download.
     */
    public static boolean isColgramManagedFile(File file) {
        return ColgramStorageIndex.isManagedModifiedFile(file);
    }

    public static int toMainConfigCacheLimit(int capMb) {
        if (capMb <= 0) return Integer.MAX_VALUE;
        if (capMb == 512) return -512;
        if (capMb == 1024) return -1024;
        if (capMb == 2048) return 2;
        if (capMb == 5120) return 5;
        if (capMb % 1024 == 0 && (capMb / 1024) >= 2 && (capMb / 1024) <= 64) {
            return capMb / 1024;
        }
        return -capMb;
    }

    /** Applies a finite hard storage cap (MB) in sync with Telegram's mainconfig & ColgramShieldConfig. */
    public static void applyStorageCap(Context context, int capMb) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (capMb <= 0) {
            ColgramConfig.setUnlimitedMemoryEnabled(true);
            apply(appCtx, true);
            return;
        }
        ColgramShieldConfig.init(appCtx);
        ColgramShieldConfig.setStorageCapEnabled(true);
        ColgramShieldConfig.setStorageHardCapMb(capMb);
        ColgramConfig.setUnlimitedMemoryEnabled(false);
        try {
            SharedPreferences prefs = appCtx.getSharedPreferences(TELEGRAM_MAINCONFIG, Context.MODE_PRIVATE);
            prefs.edit()
                    .putInt(KEY_CACHE_LIMIT, toMainConfigCacheLimit(capMb))
                    .putInt(KEY_KEEP_MEDIA, KEEP_MEDIA_ONE_MONTH)
                    .apply();
        } catch (Throwable ignored) {}
        apply(appCtx, false);
        try {
            ColgramBootJobService.scheduleStorageOptimize(appCtx);
        } catch (Throwable ignored) {}
        ColgramStorageCapEnforcer.enforceIfNeededAsync(appCtx, false);
    }

    /** Syncs cache limits and Shield hard cap across all accounts. */
    public static void apply(Context context, boolean enabled) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            ColgramShieldConfig.init(appCtx);
            ColgramShieldConfig.setStorageCapEnabled(!enabled);
            SharedPreferences prefs = appCtx.getSharedPreferences(TELEGRAM_MAINCONFIG, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            int[] targetKeepTypes = new int[]{
                    KEEP_MEDIA_ONE_MONTH, KEEP_MEDIA_ONE_MONTH, KEEP_MEDIA_ONE_MONTH, KEEP_MEDIA_ONE_MONTH
            };
            int targetGlobalKeep = KEEP_MEDIA_FOREVER;

            if (enabled) {
                int currentLimit = prefs.getInt(KEY_CACHE_LIMIT, 0);
                if (currentLimit != Integer.MAX_VALUE && !prefs.contains("colgram_prev_cache_limit")) {
                    editor.putInt("colgram_prev_cache_limit", currentLimit);
                }
                if (!prefs.contains("colgram_prev_keep_media")) {
                    editor.putInt("colgram_prev_keep_media", prefs.getInt(KEY_KEEP_MEDIA, KEEP_MEDIA_ONE_MONTH));
                    for (int t = 0; t < 4; t++) {
                        String tk = KEY_KEEP_MEDIA_TYPE_PREFIX + t;
                        if (prefs.contains(tk)) {
                            editor.putInt("colgram_prev_" + tk, prefs.getInt(tk, -1));
                        }
                    }
                }
                editor.putInt(KEY_CACHE_LIMIT, Integer.MAX_VALUE);
                editor.putInt(KEY_KEEP_MEDIA, KEEP_MEDIA_FOREVER);
                for (int t = 0; t < 4; t++) {
                    editor.putInt(KEY_KEEP_MEDIA_TYPE_PREFIX + t, KEEP_MEDIA_FOREVER);
                }
            } else {
                int capMb = ColgramShieldConfig.getStorageHardCapMb();
                if (capMb <= 0) capMb = ColgramShieldConfig.DEFAULT_STORAGE_HARD_CAP_MB;
                int finiteLimit = toMainConfigCacheLimit(capMb);
                int restoredKeep = prefs.getInt("colgram_prev_keep_media", KEEP_MEDIA_ONE_MONTH);
                targetGlobalKeep = restoredKeep;
                editor.putInt(KEY_CACHE_LIMIT, finiteLimit);
                editor.putInt(KEY_KEEP_MEDIA, restoredKeep);
                for (int t = 0; t < 4; t++) {
                    String pk = "colgram_prev_" + KEY_KEEP_MEDIA_TYPE_PREFIX + t;
                    String tk = KEY_KEEP_MEDIA_TYPE_PREFIX + t;
                    if (prefs.contains(pk)) {
                        int val = prefs.getInt(pk, restoredKeep);
                        editor.putInt(tk, val);
                        editor.remove(pk);
                        targetKeepTypes[t] = val >= 0 ? val : restoredKeep;
                    } else {
                        editor.remove(tk);
                        targetKeepTypes[t] = restoredKeep;
                    }
                }
                editor.remove("colgram_prev_cache_limit");
                editor.remove("colgram_prev_keep_media");
            }
            editor.apply();

            // Refresh Telegram's live in-memory SharedConfig.keepMedia and per-account
            // MessagesController.getInstance(acc).getCacheByChatsController().setKeepMedia(t, val)
            // across all 5 account slots (0 .. UserConfig.MAX_ACCOUNT_COUNT) for both ON and OFF.
            try {
                Class<?> scCls = Class.forName("org.telegram.messenger.SharedConfig");
                scCls.getField("keepMedia").setInt(null, targetGlobalKeep);
            } catch (Throwable ignored) {}
            try {
                int maxAccounts = 5;
                try {
                    Class<?> ucCls = Class.forName("org.telegram.messenger.UserConfig");
                    maxAccounts = ucCls.getField("MAX_ACCOUNT_COUNT").getInt(null);
                } catch (Throwable ignored) {}
                Class<?> mcCls = Class.forName("org.telegram.messenger.MessagesController");
                java.lang.reflect.Method getMc = mcCls.getMethod("getInstance", int.class);
                java.lang.reflect.Method getCbc = mcCls.getMethod("getCacheByChatsController");
                Class<?> cbcCls = Class.forName("org.telegram.messenger.CacheByChatsController");
                java.lang.reflect.Method setKeep = cbcCls.getMethod("setKeepMedia", int.class, int.class);
                for (int acc = 0; acc < maxAccounts; acc++) {
                    try {
                        Object mc = getMc.invoke(null, acc);
                        if (mc != null) {
                            Object cbc = getCbc.invoke(mc);
                            if (cbc != null) {
                                for (int t = 0; t < 4; t++) {
                                    setKeep.invoke(cbc, t, targetKeepTypes[t]);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            Log.i(TAG, "cache limit " + (enabled ? "pinned to unlimited (keep forever)" : "synced to hard cap " + ColgramShieldConfig.getStorageHardCapMb() + " MB"));
        } catch (Throwable t) {
            Log.w(TAG, "cannot write cache limit", t);
        }
    }

    /** Applied once at startup off the critical UI path so hard 1 GB cap or Unlimited stays consistent. */
    public static void restore(Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        ColgramOptimizer.scheduleAfterStartup(() -> {
            try {
                ColgramShieldConfig.init(appCtx);
                ColgramStorageIndex.init(appCtx.getFilesDir());
            } catch (Throwable ignored) {}
            if (!ColgramShieldConfig.isStorageCapEnabled() && ColgramConfig.isUnlimitedMemoryEnabled()) {
                apply(appCtx, true);
                if (ColgramConfig.isAutoCompressStorageEnabled()) {
                    autoCompressOversizedCacheImagesAsync(appCtx);
                }
            } else {
                // Default 3-tier virtual storage hard cap (default 1024 MB)
                apply(appCtx, false);
                if (ColgramConfig.isUnlimitedMemoryEnabled()) {
                    ColgramConfig.setUnlimitedMemoryEnabled(false);
                }
            }
            try {
                ColgramBootJobService.scheduleStorageOptimize(appCtx);
            } catch (Throwable ignored) {}
            try {
                ColgramStorageCapEnforcer.scheduleInitialAccountingAndEnforce(appCtx);
            } catch (Throwable ignored) {}
        });
    }

    public static int getCompressedFilesCount(Context context) {
        if (context == null) return 0;
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(PREF_COMPRESS_STATS, Context.MODE_PRIVATE)
                    .getInt(KEY_COMPRESSED_FILES, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static long getCompressedSavedBytes(Context context) {
        if (context == null) return 0L;
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(PREF_COMPRESS_STATS, Context.MODE_PRIVATE)
                    .getLong(KEY_COMPRESSED_SAVED_BYTES, 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static int getDedupedFilesCount(Context context) {
        if (context == null) return 0;
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(PREF_COMPRESS_STATS, Context.MODE_PRIVATE)
                    .getInt(KEY_DEDUPED_FILES, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static long getDedupedSavedBytes(Context context) {
        if (context == null) return 0L;
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(PREF_COMPRESS_STATS, Context.MODE_PRIVATE)
                    .getLong(KEY_DEDUPED_SAVED_BYTES, 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static long getReclaimedWasteBytes(Context context) {
        if (context == null) return 0L;
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(PREF_COMPRESS_STATS, Context.MODE_PRIVATE)
                    .getLong(KEY_RECLAIMED_WASTE_BYTES, 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static long getTotalSavedBytes(Context context) {
        return getCompressedSavedBytes(context)
                + getDedupedSavedBytes(context)
                + getReclaimedWasteBytes(context);
    }

    public static int getCompressionPreset(Context context) {
        if (context == null) return ColgramStorageOptimizer.PRESET_BALANCED;
        try {
            int p = context.getApplicationContext()
                    .getSharedPreferences(PREF_COMPRESS_STATS, Context.MODE_PRIVATE)
                    .getInt(KEY_COMPRESSION_PRESET, ColgramStorageOptimizer.PRESET_BALANCED);
            return Math.max(0, Math.min(2, p));
        } catch (Throwable ignored) {
            return ColgramStorageOptimizer.PRESET_BALANCED;
        }
    }

    public static void setCompressionPreset(Context context, int preset) {
        if (context == null) return;
        try {
            int clamped = Math.max(0, Math.min(2, preset));
            context.getApplicationContext()
                    .getSharedPreferences(PREF_COMPRESS_STATS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_COMPRESSION_PRESET, clamped)
                    .apply();
        } catch (Throwable ignored) {}
    }

    public static String getCompressionPresetLabel(Context context) {
        int p = getCompressionPreset(context);
        if (p == ColgramStorageOptimizer.PRESET_LOSSLESS) return "Без потерь (дедупликация)";
        if (p == ColgramStorageOptimizer.PRESET_MAX) return "Максимальное (Q74)";
        return "Сбалансированное (Q82)";
    }

    public static boolean isSmartCloudOffloadEnabled(Context context) {
        if (context == null) return true;
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(PREF_COMPRESS_STATS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_SMART_CLOUD_OFFLOAD, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setSmartCloudOffloadEnabled(Context context, boolean enabled) {
        if (context == null) return;
        try {
            context.getApplicationContext()
                    .getSharedPreferences(PREF_COMPRESS_STATS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_SMART_CLOUD_OFFLOAD, enabled)
                    .apply();
        } catch (Throwable ignored) {}
    }

    public static void recordDedupSaved(Context context, int files, long bytes) {
        if (context == null || (files <= 0 && bytes <= 0L)) return;
        try {
            SharedPreferences sp = context.getApplicationContext()
                    .getSharedPreferences(PREF_COMPRESS_STATS, Context.MODE_PRIVATE);
            sp.edit()
                    .putInt(KEY_DEDUPED_FILES, sp.getInt(KEY_DEDUPED_FILES, 0) + Math.max(0, files))
                    .putLong(KEY_DEDUPED_SAVED_BYTES, sp.getLong(KEY_DEDUPED_SAVED_BYTES, 0L) + Math.max(0L, bytes))
                    .apply();
        } catch (Throwable ignored) {}
    }

    public static void recordWasteReclaimed(Context context, long bytes) {
        if (context == null || bytes <= 0L) return;
        try {
            SharedPreferences sp = context.getApplicationContext()
                    .getSharedPreferences(PREF_COMPRESS_STATS, Context.MODE_PRIVATE);
            sp.edit()
                    .putLong(KEY_RECLAIMED_WASTE_BYTES, sp.getLong(KEY_RECLAIMED_WASTE_BYTES, 0L) + bytes)
                    .apply();
        } catch (Throwable ignored) {}
    }

    public static String buildCompressionStatsLine(Context context) {
        int count = getCompressedFilesCount(context);
        int dedup = getDedupedFilesCount(context);
        long saved = getTotalSavedBytes(context);
        if (count <= 0 && dedup <= 0 && saved <= 0L) {
            return "Безлимит кеша + умное сжатие и дедупликация";
        }
        String mb = String.format(Locale.US, "%.1f МБ", saved / (1024.0 * 1024.0));
        return "Сжато: " + count + " • Дублей: " + dedup + " • Сэкономлено: " + mb;
    }

    public static String buildStatusSummary(Context context) {
        return buildCompressionStatsLine(context);
    }

    /**
     * Triggers an immediate manual optimization sweep (waste reclamation + SHA-256 deduplication +
     * EXIF/color-safe photo compression + vault DB compaction) and delivers results on the UI thread.
     */
    public static void optimizeNowAsync(Context context, final OptimizeCallback listener) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Thread worker = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            final ColgramStorageOptimizer.SweepResult res = runOptimizationInternal(appCtx, true, CHARGING_FILES_PER_SWEEP);
            final String summary = buildCompressionStatsLine(appCtx);
            if (listener != null) {
                new Handler(Looper.getMainLooper()).post(() -> listener.onComplete(res, summary));
            }
        }, "colgram-storage-optimize-now");
        worker.setDaemon(true);
        worker.start();
    }

    public static void optimizeNowAsync(Context context, final Runnable onDone) {
        optimizeNowAsync(context, (res, summary) -> {
            if (onDone != null) onDone.run();
        });
    }

    /**
     * Visually-transparent background auto-compression and deduplication for cached media
     * across Colgram's sandboxed directories AND external/internal cache so
     * Unlimited Storage stays compact without ever deleting user media.
     */
    public static void autoCompressOversizedCacheImagesAsync(Context context) {
        if (context == null || compressionRunning) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!ColgramPowerGuard.isCharging(appCtx)
                || ColgramPowerGuard.isThermalThrottled(appCtx)
                || ColgramPowerGuard.isPowerSaveMode(appCtx)) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            runScheduledMaintenanceSync(appCtx, false);
        }, "colgram-storage-compress");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Invoked by ColgramBootJobService or startup maintenance.
     * STRICT thermal & battery guardrail: automatic background SHA-256 deduplication and JPEG
     * compression ONLY run while plugged in / charging and cool (never burning CPU on battery!).
     */
    public static ColgramStorageOptimizer.SweepResult runScheduledMaintenanceSync(Context context, boolean fromIdleJob) {
        if (context == null) return new ColgramStorageOptimizer.SweepResult();
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!ColgramPowerGuard.isCharging(appCtx)
                || ColgramPowerGuard.isThermalThrottled(appCtx)
                || ColgramPowerGuard.isPowerSaveMode(appCtx)) {
            return new ColgramStorageOptimizer.SweepResult();
        }
        if (!fromIdleJob) {
            try {
                long lastSweep = appCtx.getSharedPreferences(PREF_COMPRESS_STATS, Context.MODE_PRIVATE)
                        .getLong(KEY_LAST_AUTO_SWEEP_MS, 0L);
                long now = System.currentTimeMillis();
                if (lastSweep > 0L && now >= lastSweep && (now - lastSweep) < AUTO_SWEEP_MIN_INTERVAL_MS) {
                    return new ColgramStorageOptimizer.SweepResult();
                }
            } catch (Throwable ignored) {}
        }
        boolean screenOff = !ColgramPowerGuard.isScreenInteractive(appCtx);
        int batchLimit = (fromIdleJob && screenOff) ? CHARGING_FILES_PER_SWEEP : DEFAULT_FILES_PER_SWEEP;
        return runOptimizationInternal(appCtx, false, batchLimit);
    }

    private static ColgramStorageOptimizer.SweepResult runOptimizationInternal(Context appCtx,
                                                                               boolean manualForce,
                                                                               int maxFilesPerSweep) {
        ColgramStorageOptimizer.SweepResult total = new ColgramStorageOptimizer.SweepResult();
        if (appCtx == null) return total;
        if (!manualForce && compressionRunning) return total;
        compressionRunning = true;
        try {
            ColgramStorageIndex.init(appCtx.getFilesDir());
            Collection<File> roots = collectOptimizationRoots(appCtx);
            File casDir = ColgramStorageSandbox.getCasDirectory(appCtx);
            File inboxDir = new File(appCtx.getFilesDir(), "colgram_inbox");
            File externalInboxDir = appCtx.getExternalFilesDir("colgram_inbox");
            Set<String> activePaths = ColgramStorageOptimizer.getActiveTelegramFilePaths();
            long nowMs = System.currentTimeMillis();
            int preset = ColgramConfig.isAutoCompressStorageEnabled()
                    ? getCompressionPreset(appCtx)
                    : ColgramStorageOptimizer.PRESET_LOSSLESS;

            // 1. Reclaim regenerable transient waste (acache/.pcache2, stale .temp/.cgtmp, >7d inbox)
            long wasteFreed = ColgramStorageOptimizer.pruneRegenerableWaste(roots, inboxDir, activePaths, nowMs);
            if (externalInboxDir != null && !externalInboxDir.equals(inboxDir)) {
                wasteFreed += ColgramStorageOptimizer.pruneRegenerableWaste(
                        java.util.Collections.emptyList(), externalInboxDir, activePaths, nowMs);
            }
            total.wasteReclaimedBytes += wasteFreed;

            // 2. Content-addressed SHA-256 deduplication across sandbox, media_history, and inbox
            int maxHashes = manualForce ? 300 : maxFilesPerSweep * 2;
            ColgramStorageOptimizer.SweepResult dedup = ColgramStorageOptimizer.runDeduplicationSweep(
                    roots, casDir, activePaths, nowMs, maxHashes);
            total.add(dedup);

            // 3. EXIF-safe, ARGB_8888 single-pass indexed image compression
            ColgramStorageOptimizer.SweepResult comp = ColgramStorageOptimizer.runImageCompressionSweep(
                    roots, preset, activePaths, nowMs, maxFilesPerSweep, ANDROID_BITMAP_TRANSCODER);
            total.add(comp);

            // 4. Own ColgramDatabase incremental vacuum & WAL truncate checkpoint
            try {
                ColgramDatabase.getInstance(appCtx).compactOwnDatabase();
            } catch (Throwable ignored) {}

            // 5. Emergency low-disk Virtual Cloud Guard (< 1.5 GB free space safety valve)
            File storageProbe = ColgramStorageSandbox.getSandboxRootDir(appCtx);
            if (storageProbe == null) storageProbe = appCtx.getFilesDir();
            long[] diskSpace = queryDiskSpace(storageProbe);
            if (diskSpace[0] > 0L && diskSpace[1] > 0L) {
                ColgramStorageOptimizer.SweepResult lowDisk = ColgramStorageOptimizer.runEmergencyLowDiskGuard(
                        roots,
                        casDir,
                        inboxDir,
                        activePaths,
                        nowMs,
                        diskSpace[0],
                        diskSpace[1],
                        isSmartCloudOffloadEnabled(appCtx),
                        preset,
                        ANDROID_BITMAP_TRANSCODER,
                        file -> ColgramHookHandler.shouldPreventMediaDeletion(file));
                total.add(lowDisk);
            }

            if (casDir != null) {
                ColgramStorageIndex.flushToDirectory(casDir);
            } else {
                ColgramStorageIndex.flush();
            }
            if (!manualForce) {
                try {
                    appCtx.getSharedPreferences(PREF_COMPRESS_STATS, Context.MODE_PRIVATE)
                            .edit().putLong(KEY_LAST_AUTO_SWEEP_MS, nowMs).apply();
                } catch (Throwable ignored) {}
            }
            persistSweepStats(appCtx, total);
        } catch (Throwable t) {
            Log.w(TAG, "storage optimization sweep failed: " + t.getMessage());
        } finally {
            compressionRunning = false;
        }
        return total;
    }

    private static Collection<File> collectOptimizationRoots(Context appCtx) {
        LinkedHashSet<File> roots = new LinkedHashSet<>();
        if (appCtx == null) return roots;
        try {
            roots.addAll(ColgramStorageCapEnforcer.collectAllManagedRoots(appCtx));
        } catch (Throwable ignored) {}
        try {
            int[] sandboxTypes = new int[]{0, 1, 2, 3, 4, 5, 6, 100, 101};
            for (int type : sandboxTypes) {
                File dir = ColgramStorageSandbox.getSandboxedDirectory(appCtx, type);
                if (dir != null && dir.isDirectory()) {
                    roots.add(dir);
                }
            }
        } catch (Throwable ignored) {}
        return roots;
    }

    private static long[] queryDiskSpace(File path) {
        if (path == null) return new long[]{0L, 0L};
        try {
            if (!path.exists()) path.mkdirs();
            StatFs stat = new StatFs(path.getAbsolutePath());
            return new long[]{stat.getAvailableBytes(), stat.getTotalBytes()};
        } catch (Throwable ignored) {
            return new long[]{0L, 0L};
        }
    }

    private static void persistSweepStats(Context appCtx, ColgramStorageOptimizer.SweepResult res) {
        if (appCtx == null || res == null) return;
        if (res.compressedFiles <= 0 && res.dedupedFiles <= 0
                && res.wasteReclaimedBytes <= 0L && res.cloudOffloadedFiles <= 0) {
            return;
        }
        try {
            SharedPreferences sp = appCtx.getSharedPreferences(PREF_COMPRESS_STATS, Context.MODE_PRIVATE);
            sp.edit()
                    .putInt(KEY_COMPRESSED_FILES, sp.getInt(KEY_COMPRESSED_FILES, 0) + res.compressedFiles)
                    .putLong(KEY_COMPRESSED_SAVED_BYTES, sp.getLong(KEY_COMPRESSED_SAVED_BYTES, 0L) + res.compressedSavedBytes)
                    .putInt(KEY_DEDUPED_FILES, sp.getInt(KEY_DEDUPED_FILES, 0) + res.dedupedFiles)
                    .putLong(KEY_DEDUPED_SAVED_BYTES, sp.getLong(KEY_DEDUPED_SAVED_BYTES, 0L) + res.dedupedSavedBytes)
                    .putLong(KEY_RECLAIMED_WASTE_BYTES, sp.getLong(KEY_RECLAIMED_WASTE_BYTES, 0L) + res.wasteReclaimedBytes)
                    .putInt(KEY_CLOUD_OFFLOADED_FILES, sp.getInt(KEY_CLOUD_OFFLOADED_FILES, 0) + res.cloudOffloadedFiles)
                    .putLong(KEY_CLOUD_OFFLOADED_BYTES, sp.getLong(KEY_CLOUD_OFFLOADED_BYTES, 0L) + res.cloudOffloadedBytes)
                    .apply();
            Log.i(TAG, "Storage sweep: compressed=" + res.compressedFiles
                    + " deduped=" + res.dedupedFiles
                    + " savedKB=" + (res.totalSavedBytes() / 1024L));
        } catch (Throwable ignored) {}
    }

    /** Creates an EXIF-safe ARGB_8888 image transcoder preserving extension-to-codec fidelity (WebP for .webp / shadow_previews, JPEG for .jpg/.jpeg). */
    @SuppressWarnings("deprecation")
    public static ColgramStorageOptimizer.ImageTranscoder createWebpTranscoder(final int defaultQuality, final int defaultMaxDim) {
        return new ColgramStorageOptimizer.ImageTranscoder() {
            @Override
            public boolean transcode(File source, File tempDest, int preset) throws Exception {
                if (source == null || tempDest == null || !source.isFile()) return false;
                String lower = source.getName().toLowerCase(Locale.ROOT);
                boolean srcPng = lower.endsWith(".png");
                boolean srcJpg = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
                boolean srcWebp = lower.endsWith(".webp");
                if (!srcPng && !srcJpg && !srcWebp) return false;

                String destName = tempDest.getName().toLowerCase(Locale.ROOT);
                if (destName.endsWith(".cgtmp")) {
                    destName = destName.substring(0, destName.length() - 6);
                } else if (destName.endsWith(".tmp")) {
                    destName = destName.substring(0, destName.length() - 4);
                }
                boolean outWebp = destName.endsWith(".webp");
                boolean outJpg = destName.endsWith(".jpg") || destName.endsWith(".jpeg");
                // Never overwrite .png or .jpg files with raw WebP bytes under their original extension.
                if (!outWebp && !outJpg) return false;

                int targetMaxDim = defaultMaxDim > 0
                        ? ((preset == ColgramStorageOptimizer.PRESET_MAX) ? Math.min(defaultMaxDim, 1600) : defaultMaxDim)
                        : ((preset == ColgramStorageOptimizer.PRESET_MAX) ? 1600 : 1920);
                int quality = defaultQuality > 0
                        ? ((preset == ColgramStorageOptimizer.PRESET_MAX) ? Math.min(defaultQuality, 62) : defaultQuality)
                        : ((preset == ColgramStorageOptimizer.PRESET_MAX) ? 62 : 78);

                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

                int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
                int sampleSize = 1;
                final long maxDecodePixels = 4_000_000L;
                while ((maxDim / (sampleSize * 2)) >= targetMaxDim
                        || ((long) (bounds.outWidth / sampleSize) * (long) (bounds.outHeight / sampleSize)) > maxDecodePixels) {
                    if (sampleSize >= 64) break;
                    sampleSize *= 2;
                }

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sampleSize;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

                Bitmap bmp = BitmapFactory.decodeFile(source.getAbsolutePath(), opts);
                if (bmp == null) return false;

                try {
                    if (srcPng && !outWebp && bmp.hasAlpha()) {
                        return false;
                    }

                    int exifOrientation = ExifInterface.ORIENTATION_NORMAL;
                    if (srcJpg) {
                        try {
                            ExifInterface exif = new ExifInterface(source.getAbsolutePath());
                            exifOrientation = exif.getAttributeInt(
                                    ExifInterface.TAG_ORIENTATION,
                                    ExifInterface.ORIENTATION_NORMAL);
                        } catch (Throwable ignored) {}
                    }

                    Matrix matrix = buildExifAndScaleMatrix(
                            bmp.getWidth(), bmp.getHeight(), targetMaxDim, exifOrientation);
                    if (matrix != null) {
                        Bitmap transformed = Bitmap.createBitmap(
                                bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
                        if (transformed != null && transformed != bmp) {
                            bmp.recycle();
                            bmp = transformed;
                        }
                    }

                    try (FileOutputStream fos = new FileOutputStream(tempDest)) {
                        Bitmap.CompressFormat fmt;
                        if (outWebp) {
                            fmt = android.os.Build.VERSION.SDK_INT >= 30
                                    ? Bitmap.CompressFormat.WEBP_LOSSY
                                    : Bitmap.CompressFormat.WEBP;
                        } else {
                            fmt = Bitmap.CompressFormat.JPEG;
                        }
                        if (!bmp.compress(fmt, quality, fos)) {
                            return false;
                        }
                        fos.flush();
                        try {
                            fos.getFD().sync();
                        } catch (Throwable ignored) {}
                    }
                    return tempDest.isFile() && tempDest.length() > 512L;
                } finally {
                    if (bmp != null && !bmp.isRecycled()) {
                        bmp.recycle();
                    }
                }
            }
        };
    }

    /**
     * EXIF-orientation-preserving, ARGB_8888 color-safe Android image transcoder (WebP lossy).
     */
    private static final ColgramStorageOptimizer.ImageTranscoder ANDROID_BITMAP_TRANSCODER =
            createWebpTranscoder(78, 1920);

    private static Matrix buildExifAndScaleMatrix(int width, int height, int targetMaxDim, int exifOrientation) {
        if (width <= 0 || height <= 0) return null;
        int maxCurrent = Math.max(width, height);
        float scale = (targetMaxDim > 0 && maxCurrent > targetMaxDim)
                ? ((float) targetMaxDim / (float) maxCurrent)
                : 1.0f;

        boolean hasExifTransform = exifOrientation != ExifInterface.ORIENTATION_NORMAL
                && exifOrientation != ExifInterface.ORIENTATION_UNDEFINED;
        if (!hasExifTransform && scale >= 0.999f) {
            return null;
        }

        Matrix m = new Matrix();
        if (scale < 0.999f) {
            m.postScale(scale, scale);
        }
        switch (exifOrientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.postRotate(90f);
                m.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.postRotate(270f);
                m.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270f);
                break;
            default:
                break;
        }
        return m;
    }

    private static volatile long lastTrimAtMs = 0L;
    private static final java.util.concurrent.atomic.AtomicBoolean trimPosted =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Keeps transient image/bitmap RAM inside a <=100 MB ceiling without ever deleting cached
     * disk media, keeping Colgram responsive on low-RAM devices even in Unlimited Storage mode.
     */
    public static void trimTransientMemoryIfNeeded(Context context) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastTrimAtMs < 45_000L) return;
        try {
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
            long maxMb = Math.max(128L, rt.maxMemory() / (1024L * 1024L));
            // Only trim transient RAM cache under genuine memory pressure (>= 90% of Java heap ceiling).
            // ImageLoader.clearMemory() touches UI-thread-only LruCache/mapFilters/bitmaps, so ALWAYS
            // dispatch it on the main Looper rather than a background worker thread.
            if (usedMb >= (maxMb * 90L) / 100L) {
                lastTrimAtMs = now;
                Runnable clearTask = () -> {
                    try {
                        Class<?> ilClass = Class.forName("org.telegram.messenger.ImageLoader");
                        Object il = ilClass.getMethod("getInstance").invoke(null);
                        if (il != null) {
                            ilClass.getMethod("clearMemory").invoke(il);
                        }
                        Log.i(TAG, "trimmed transient image memory (heap=" + usedMb + "/" + maxMb + " MB)");
                    } catch (Throwable ignored) {
                    } finally {
                        trimPosted.set(false);
                    }
                };
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    clearTask.run();
                } else if (trimPosted.compareAndSet(false, true)) {
                    if (!new Handler(Looper.getMainLooper()).post(clearTask)) {
                        trimPosted.set(false);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}
