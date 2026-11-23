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

    public static void apply(Context context, boolean enabled) {
        if (context == null) return;
        try {
            // Telegram's SharedConfig, CacheByChatsController, and AutoDeleteMediaTask read from "mainconfig"
            SharedPreferences prefs = context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            int[] targetKeepTypes = new int[]{
                    KEEP_MEDIA_FOREVER, KEEP_MEDIA_FOREVER, KEEP_MEDIA_FOREVER, KEEP_MEDIA_FOREVER
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
                int restoredLimit = prefs.getInt("colgram_prev_cache_limit", 0);
                int restoredKeep = prefs.getInt("colgram_prev_keep_media", KEEP_MEDIA_ONE_MONTH);
                targetGlobalKeep = restoredKeep;
                editor.putInt(KEY_CACHE_LIMIT, restoredLimit == Integer.MAX_VALUE ? 0 : restoredLimit);
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
            Log.i(TAG, "cache limit " + (enabled ? "pinned to unlimited (keep forever)" : "restored finite limit"));
        } catch (Throwable t) {
            Log.w(TAG, "cannot write cache limit", t);
        }
    }

    /** Applied once at startup off the critical UI path so a reboot cannot resurrect a pruning limit. */
    public static void restore(Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            ColgramStorageIndex.init(appCtx.getFilesDir());
        } catch (Throwable ignored) {}
        ColgramOptimizer.scheduleAfterStartup(() -> {
            if (ColgramConfig.isUnlimitedMemoryEnabled()) {
                apply(appCtx, true);
                if (ColgramConfig.isAutoCompressStorageEnabled()) {
                    autoCompressOversizedCacheImagesAsync(appCtx);
                }
            } else if (!ColgramConfig.isUnlimitedMemoryConfigured()) {
                apply(appCtx, true);
            }
            try {
                ColgramBootJobService.scheduleStorageOptimize(appCtx);
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
     * Invoked by ColgramBootJobService or startup maintenance. Respects battery & thermal guardrails.
     */
    public static ColgramStorageOptimizer.SweepResult runScheduledMaintenanceSync(Context context, boolean fromIdleJob) {
        if (context == null) return new ColgramStorageOptimizer.SweepResult();
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (ColgramPowerGuard.shouldThrottle(appCtx) || ColgramPowerGuard.isThermalThrottled(appCtx)) {
            return new ColgramStorageOptimizer.SweepResult();
        }
        boolean chargingAndScreenOff = ColgramPowerGuard.isCharging(appCtx)
                && !ColgramPowerGuard.isScreenInteractive(appCtx);
        int batchLimit = (fromIdleJob && chargingAndScreenOff) ? CHARGING_FILES_PER_SWEEP : DEFAULT_FILES_PER_SWEEP;
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
            Set<String> activePaths = ColgramStorageOptimizer.getActiveTelegramFilePaths();
            long nowMs = System.currentTimeMillis();
            int preset = ColgramConfig.isAutoCompressStorageEnabled()
                    ? getCompressionPreset(appCtx)
                    : ColgramStorageOptimizer.PRESET_LOSSLESS;

            // 1. Reclaim regenerable transient waste (acache/.pcache2, stale .temp/.cgtmp, >7d inbox)
            long wasteFreed = ColgramStorageOptimizer.pruneRegenerableWaste(roots, inboxDir, activePaths, nowMs);
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
        try {
            int[] sandboxTypes = new int[]{0, 1, 2, 3, 4, 5, 6, 100, 101};
            for (int type : sandboxTypes) {
                File dir = ColgramStorageSandbox.getSandboxedDirectory(appCtx, type);
                if (dir != null && dir.isDirectory()) {
                    roots.add(dir);
                }
            }
        } catch (Throwable ignored) {}
        try {
            File filesDir = appCtx.getFilesDir();
            if (filesDir != null) {
                File mediaHistory = new File(filesDir, "media_history");
                if (mediaHistory.isDirectory()) roots.add(mediaHistory);
                File inbox = new File(filesDir, "colgram_inbox");
                if (inbox.isDirectory()) roots.add(inbox);
            }
        } catch (Throwable ignored) {}
        try {
            File extCache = appCtx.getExternalCacheDir();
            if (extCache != null && extCache.isDirectory()) roots.add(extCache);
        } catch (Throwable ignored) {}
        try {
            File intCache = appCtx.getCacheDir();
            if (intCache != null && intCache.isDirectory()) roots.add(intCache);
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

    /**
     * EXIF-orientation-preserving, ARGB_8888 color-safe Android image transcoder.
     */
    private static final ColgramStorageOptimizer.ImageTranscoder ANDROID_BITMAP_TRANSCODER =
            new ColgramStorageOptimizer.ImageTranscoder() {
                @Override
                public boolean transcode(File source, File tempDest, int preset) throws Exception {
                    if (source == null || tempDest == null || !source.isFile()) return false;
                    String lower = source.getName().toLowerCase(Locale.ROOT);
                    boolean isPng = lower.endsWith(".png");
                    boolean isJpg = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
                    if (!isPng && !isJpg) return false;

                    int targetMaxDim = (preset == ColgramStorageOptimizer.PRESET_MAX) ? 1920 : 2560;
                    int jpegQuality = (preset == ColgramStorageOptimizer.PRESET_MAX) ? 74 : 82;

                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

                    int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
                    int sampleSize = 1;
                    while ((maxDim / (sampleSize * 2)) >= targetMaxDim) {
                        sampleSize *= 2;
                    }

                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = sampleSize;
                    // Strictly use 32-bit ARGB_8888 (never destructive 16-bit RGB_565)
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

                    Bitmap bmp = BitmapFactory.decodeFile(source.getAbsolutePath(), opts);
                    if (bmp == null) return false;

                    try {
                        if (isPng && bmp.hasAlpha()) {
                            // Never strip transparency or change format of transparent PNGs
                            return false;
                        }

                        int exifOrientation = ExifInterface.ORIENTATION_NORMAL;
                        if (isJpg) {
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
                            Bitmap.CompressFormat fmt = isPng
                                    ? Bitmap.CompressFormat.PNG
                                    : Bitmap.CompressFormat.JPEG;
                            int q = isPng ? 100 : jpegQuality;
                            if (!bmp.compress(fmt, q, fos)) {
                                return false;
                            }
                            fos.flush();
                            try {
                                fos.getFD().sync();
                            } catch (Throwable ignored) {}
                        }
                        return tempDest.isFile() && tempDest.length() > 4096L;
                    } finally {
                        if (bmp != null && !bmp.isRecycled()) {
                            bmp.recycle();
                        }
                    }
                }
            };

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
            // Only trim transient RAM cache under genuine memory pressure (>= 80% of Java heap ceiling)
            if (usedMb >= (maxMb * 80L) / 100L) {
                lastTrimAtMs = now;
                Class<?> ilClass = Class.forName("org.telegram.messenger.ImageLoader");
                Object il = ilClass.getMethod("getInstance").invoke(null);
                if (il != null) {
                    ilClass.getMethod("clearMemory").invoke(il);
                }
                Log.i(TAG, "trimmed transient image memory (heap=" + usedMb + "/" + maxMb + " MB)");
            }
        } catch (Throwable ignored) {}
    }
}
