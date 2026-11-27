package org.colgram.core;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * 3-Tier Virtual Infinite Storage & Hard Cache Cap Enforcer.
 *
 * Unifies disk accounting across ALL app, internal, cache, and Android/media roots,
 * keeping physical storage inside the configured hard cap (default 1 GB) while preserving
 * compressed catalog metadata (Tier 0) and progressive shadow micro-previews (Tier 1).
 *
 * Event-driven with zero idle background polling: triggered on startup and real-time file writes
 * once tracked bytes cross 85% of the configured cap, rate-limited to >=5 min (unless >=100% full).
 */
public final class ColgramStorageCapEnforcer {

    public static final long MB = 1024L * 1024L;
    public static final long ACACHE_PHASE_A_MAX_BYTES = 64L * MB;
    public static final long MESSAGES_LOG_ROTATE_BYTES = 8L * MB;
    public static final long IMAGE_COMPRESS_MIN_BYTES = 120L * 1024L;
    public static final long TEXT_COMPRESS_MIN_BYTES = 32L * 1024L;
    public static final long CACHE_HOG_THRESHOLD_BYTES = 10L * MB;
    public static final long YOUNG_FILE_GRACE_MS = 5L * 60L * 1000L;
    public static final long PHASE_B_SETTLE_MS = 30L * 1000L;
    public static final long MIN_ENFORCE_COOLDOWN_MS = 5L * 60L * 1000L;
    public static final long OVERCAP_DEBOUNCE_MS = 20L * 1000L;
    public static final long SHADOW_PREVIEW_SOFT_CAP_BYTES = 128L * MB;
    public static final long SHADOW_PREVIEW_HARD_CAP_BYTES = 180L * MB;
    public static final int BATTERY_PHASE_B_MAX_FILES = 8;
    public static final int CHARGER_PHASE_B_MAX_FILES = 80;
    public static final long BATTERY_PHASE_B_YIELD_MS = 25L;

    public static final class EnforcementReport {
        public long bytesBefore;
        public long bytesAfter;
        public long phaseAReclaimedBytes;
        public long phaseBCompressedSavedBytes;
        public int compressedFilesCount;
        public long phaseCEvictedBytes;
        public int dehydratedStubsRecorded;
        public long logRotatedSavedBytes;
        public int shadowPreviewsCompacted;
        public long shadowPreviewsEvictedBytes;
        public boolean triggered;
    }

    public static final class VirtualStorageStats {
        public long physicalBytes;
        public long capBytes;
        public long virtualCatalogBytes;
        public long savedByCompressionBytes;
        public long dehydratedBytes;
        public int dehydratedFilesCount;
        public long shadowVaultBytes;
        public int shadowVaultFilesCount;
    }

    private static final class EvictCandidate {
        final File file;
        final long size;
        final long lruTimestamp;
        final boolean isHog;

        EvictCandidate(File file, long size, long lruTimestamp) {
            this.file = file;
            this.size = size;
            this.lruTimestamp = lruTimestamp;
            this.isHog = size >= CACHE_HOG_THRESHOLD_BYTES;
        }
    }

    private static final AtomicLong trackedCacheBytes = new AtomicLong(-1L);
    private static final AtomicBoolean enforceRunning = new AtomicBoolean(false);
    private static volatile long lastEnforceAtMs = 0L;
    private static volatile ScheduledExecutorService executor;

    private ColgramStorageCapEnforcer() {
    }

    private static ScheduledExecutorService getExecutor() {
        ScheduledExecutorService ex = executor;
        if (ex == null || ex.isShutdown()) {
            synchronized (ColgramStorageCapEnforcer.class) {
                ex = executor;
                if (ex == null || ex.isShutdown()) {
                    ex = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "colgram-cap-enforcer");
                        t.setDaemon(true);
                        return t;
                    });
                    executor = ex;
                }
            }
        }
        return ex;
    }

    public static long getTrackedCacheBytes() {
        return trackedCacheBytes.get();
    }

    /** Called immediately when FileLoader finishes writing/downloading a cached file. */
    public static void onFileWritten(Context context, File file, long bytesWritten) {
        if (file == null) return;
        try {
            ColgramStorageIndex.markHydrated(file);
        } catch (Throwable ignored) {}
        long delta = bytesWritten > 0L ? bytesWritten : (file.isFile() ? file.length() : 0L);
        if (delta > 0L && trackedCacheBytes.get() >= 0L) {
            long updated = trackedCacheBytes.addAndGet(delta);
            if (context != null && ColgramShieldConfig.isStorageCapEnabled()) {
                long cap = ColgramShieldConfig.getStorageHardCapBytes();
                if (cap > 0L && updated >= (long) (cap * 0.85d)) {
                    enforceIfNeededAsync(context, false);
                }
            }
        }
    }

    /** Called when Telegram or Colgram deletes a cache/media file. */
    public static void onFileDeleted(File file, long sizeBytes) {
        if (file != null) {
            try {
                ColgramStorageIndex.Entry e = ColgramStorageIndex.getEntry(file);
                if (e != null && !e.isDehydrated()) {
                    ColgramStorageIndex.removeEntry(file.getAbsolutePath());
                }
            } catch (Throwable ignored) {}
        }
        if (sizeBytes > 0L) {
            trackedCacheBytes.updateAndGet(cur -> cur < 0L ? -1L : Math.max(0L, cur - sizeBytes));
        }
    }

    /** Runs one background measurement + enforcement pass shortly after startup (no idle polling). */
    public static void scheduleInitialAccountingAndEnforce(Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            getExecutor().schedule(() -> {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
                runAccountingAndMaybeEnforce(appCtx);
            }, 4, TimeUnit.SECONDS);
        } catch (Throwable ignored) {}
    }

    public static void enforceIfNeededAsync(Context context, boolean force) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            getExecutor().execute(() -> {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
                enforceIfNeeded(appCtx, force);
            });
        } catch (Throwable ignored) {}
    }

    private static void runAccountingAndMaybeEnforce(Context appCtx) {
        try {
            ColgramShieldConfig.init(appCtx);
            ColgramStorageIndex.init(appCtx.getFilesDir());
            List<File> roots = collectAllManagedRoots(appCtx);
            Set<String> active = ColgramStorageOptimizer.getActiveTelegramFilePaths();
            long bytes = computeManagedBytes(roots, appCtx.getFilesDir(), active);
            trackedCacheBytes.set(bytes);
            if (ColgramShieldConfig.isStorageCapEnabled()) {
                long cap = ColgramShieldConfig.getStorageHardCapBytes();
                if (cap > 0L && bytes >= (long) (cap * 0.85d)) {
                    enforceIfNeeded(appCtx, false);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static boolean enforceIfNeeded(Context context, boolean force) {
        if (context == null) return false;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        ColgramShieldConfig.init(appCtx);
        boolean capEnabled = ColgramShieldConfig.isStorageCapEnabled();
        long capBytes = capEnabled ? ColgramShieldConfig.getStorageHardCapBytes() : 0L;
        if (!force && (!capEnabled || capBytes <= 0L)) {
            return false;
        }
        long nowMs = System.currentTimeMillis();
        long tracked = trackedCacheBytes.get();
        if (!force && capEnabled && capBytes > 0L && tracked >= 0L) {
            boolean hardOver = tracked >= capBytes;
            long minWait = hardOver ? OVERCAP_DEBOUNCE_MS : MIN_ENFORCE_COOLDOWN_MS;
            if (lastEnforceAtMs > 0L && nowMs > lastEnforceAtMs && (nowMs - lastEnforceAtMs) < minWait) {
                return false;
            }
            if (tracked < (long) (capBytes * 0.85d)) {
                return false;
            }
        }
        if (!enforceRunning.compareAndSet(false, true)) {
            return false;
        }
        try {
            lastEnforceAtMs = nowMs;
            ColgramStorageIndex.init(appCtx.getFilesDir());
            List<File> roots = collectAllManagedRoots(appCtx);
            File shadowDir = ColgramStorageSandbox.getShadowPreviewsDir(appCtx);
            Set<String> activePaths = ColgramStorageOptimizer.getActiveTelegramFilePaths();
            boolean charging = isDeviceCharging(appCtx);
            boolean thermalThrottled = isThermalSevere(appCtx);
            ColgramStorageOptimizer.ImageTranscoder transcoder =
                    ColgramUnlimitedMemory.createWebpTranscoder(78, 1920);
            EnforcementReport rep = enforceCapSync(
                    roots, appCtx.getFilesDir(), shadowDir, capBytes, activePaths,
                    transcoder, charging, thermalThrottled, capEnabled, nowMs);
            trackedCacheBytes.set(rep.bytesAfter);
            try {
                ColgramDatabase db = ColgramDatabase.getInstance(appCtx);
                if (db != null) db.compactOwnDatabase();
            } catch (Throwable ignored) {}
            return rep.triggered || rep.phaseAReclaimedBytes > 0L || rep.phaseBCompressedSavedBytes > 0L;
        } finally {
            enforceRunning.set(false);
        }
    }

    /** Runs a forced synchronous maintenance/eviction cycle for UI "Сжать и виртуализировать сейчас". */
    public static EnforcementReport runManualOptimizationNow(Context context) {
        EnforcementReport empty = new EnforcementReport();
        if (context == null) return empty;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!enforceRunning.compareAndSet(false, true)) {
            return empty;
        }
        try {
            ColgramShieldConfig.init(appCtx);
            ColgramStorageIndex.init(appCtx.getFilesDir());
            lastEnforceAtMs = System.currentTimeMillis();
            boolean capEnabled = ColgramShieldConfig.isStorageCapEnabled();
            long capBytes = capEnabled ? ColgramShieldConfig.getStorageHardCapBytes() : 0L;
            List<File> roots = collectAllManagedRoots(appCtx);
            File shadowDir = ColgramStorageSandbox.getShadowPreviewsDir(appCtx);
            Set<String> activePaths = ColgramStorageOptimizer.getActiveTelegramFilePaths();
            boolean charging = isDeviceCharging(appCtx);
            ColgramStorageOptimizer.ImageTranscoder transcoder =
                    ColgramUnlimitedMemory.createWebpTranscoder(78, 1920);
            EnforcementReport rep = enforceCapSync(
                    roots, appCtx.getFilesDir(), shadowDir, capBytes, activePaths,
                    transcoder, charging || true, isThermalSevere(appCtx), capEnabled, System.currentTimeMillis(), true);
            trackedCacheBytes.set(rep.bytesAfter);
            try {
                ColgramDatabase db = ColgramDatabase.getInstance(appCtx);
                if (db != null) db.compactOwnDatabase();
            } catch (Throwable ignored) {}
            return rep;
        } finally {
            enforceRunning.set(false);
        }
    }

    /** Collects ALL unified app/internal/external/Android-media cache & media roots (deduped by canonical path). */
    public static List<File> collectAllManagedRoots(Context context) {
        List<File> result = new ArrayList<>();
        if (context == null) return result;
        Set<String> seen = new HashSet<>();

        addRootIfValid(result, seen, ColgramStorageSandbox.getSandboxRootDir(context));
        addRootIfValid(result, seen, ColgramStorageSandbox.getCacheRootDir(context));

        File filesDir = context.getFilesDir();
        if (filesDir != null) {
            addRootIfValid(result, seen, new File(filesDir, "Colgram"));
            addRootIfValid(result, seen, new File(filesDir, "Telegram"));
            addRootIfValid(result, seen, new File(filesDir, "colgram_inbox"));
            addRootIfValid(result, seen, new File(filesDir, "media_history"));
            addRootIfValid(result, seen, new File(filesDir, "koliti_media"));
        }

        addRootIfValid(result, seen, context.getCacheDir());
        addRootIfValid(result, seen, context.getExternalCacheDir());
        if (Build.VERSION.SDK_INT >= 19) {
            try {
                File[] extCaches = context.getExternalCacheDirs();
                if (extCaches != null) {
                    for (File f : extCaches) addRootIfValid(result, seen, f);
                }
            } catch (Throwable ignored) {}
            try {
                File[] extFiles = context.getExternalFilesDirs(null);
                if (extFiles != null) {
                    for (File ef : extFiles) {
                        if (ef == null) continue;
                        addRootIfValid(result, seen, new File(ef, "Telegram"));
                        addRootIfValid(result, seen, new File(ef, "Colgram"));
                        addRootIfValid(result, seen, new File(ef, "colgram_inbox"));
                    }
                }
            } catch (Throwable ignored) {}
        } else {
            File ext = context.getExternalFilesDir(null);
            if (ext != null) {
                addRootIfValid(result, seen, new File(ext, "Telegram"));
                addRootIfValid(result, seen, new File(ext, "Colgram"));
            }
        }

        if (Build.VERSION.SDK_INT >= 21) {
            try {
                File[] mediaDirs = context.getExternalMediaDirs();
                if (mediaDirs != null) {
                    for (File md : mediaDirs) {
                        if (md == null) continue;
                        addRootIfValid(result, seen, new File(md, "Telegram"));
                        addRootIfValid(result, seen, new File(md, "Colgram"));
                    }
                }
            } catch (Throwable ignored) {}
        }
        return result;
    }

    private static void addRootIfValid(List<File> out, Set<String> seen, File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        String key;
        try {
            key = ColgramStorageIndex.normalizePath(dir.getCanonicalPath());
        } catch (Throwable ignored) {
            key = ColgramStorageIndex.normalizePath(dir.getAbsolutePath());
        }
        if (key.isEmpty() || !seen.add(key)) return;
        out.add(dir);
    }

    public static EnforcementReport enforceCapSync(Collection<File> roots,
                                                   File filesDir,
                                                   File shadowPreviewsDir,
                                                   long capBytes,
                                                   Set<String> activePaths,
                                                   ColgramStorageOptimizer.ImageTranscoder imageTranscoder,
                                                   boolean isCharging,
                                                   boolean thermalThrottled,
                                                   boolean evictEnabled,
                                                   long nowMs) {
        return enforceCapSync(roots, filesDir, shadowPreviewsDir, capBytes, activePaths,
                imageTranscoder, isCharging, thermalThrottled, evictEnabled, nowMs, false);
    }

    public static EnforcementReport enforceCapSync(Collection<File> roots,
                                                   File filesDir,
                                                   File shadowPreviewsDir,
                                                   long capBytes,
                                                   Set<String> activePaths,
                                                   ColgramStorageOptimizer.ImageTranscoder imageTranscoder,
                                                   boolean isCharging,
                                                   boolean thermalThrottled,
                                                   boolean evictEnabled,
                                                   long nowMs,
                                                   boolean runCompressionEvenUnderCap) {
        EnforcementReport report = new EnforcementReport();
        List<File> safeRoots = dedupRoots(roots);
        Set<String> safeActive = activePaths == null ? Collections.emptySet() : activePaths;
        report.bytesBefore = computeManagedBytes(safeRoots, filesDir, safeActive)
                + computeDirBytes(shadowPreviewsDir);

        long highWatermark = capBytes > 0L ? (long) (capBytes * 0.85d) : Long.MAX_VALUE;
        long targetBytes = capBytes > 0L ? (long) (capBytes * 0.75d) : Long.MAX_VALUE;
        boolean overWatermark = evictEnabled && capBytes > 0L && report.bytesBefore >= highWatermark;
        if (!overWatermark && !runCompressionEvenUnderCap) {
            report.bytesAfter = report.bytesBefore;
            return report;
        }
        report.triggered = true;

        // Phase A (<15ms, battery-safe): prune acache > 64 MB, stale .temp/.preload/.cgtmp,
        // orphan .colgram_cas blobs, stale inbox, and rotate messages_log.jsonl at 8 MB.
        report.phaseAReclaimedBytes += runPhaseA(safeRoots, filesDir, safeActive, nowMs);

        // Phase B (Tier 1): Incremental adaptive WebP + GZIP + Progressive Shadow Micro-Vault
        if (!thermalThrottled) {
            runPhaseB(safeRoots, shadowPreviewsDir, capBytes, safeActive, imageTranscoder,
                    isCharging, nowMs, report);
        }

        // Phase C (Tier 0 + 2): Smart dehydration stub catalog + Hard LRU eviction down to <= 75% cap
        long afterAB = computeManagedBytes(safeRoots, filesDir, safeActive) + computeDirBytes(shadowPreviewsDir);
        if (evictEnabled && capBytes > 0L && afterAB > targetBytes) {
            long freed = runPhaseCEviction(safeRoots, afterAB - targetBytes, safeActive, nowMs, report);
            report.phaseCEvictedBytes += freed;
        }

        ColgramStorageIndex.flush();
        report.bytesAfter = computeManagedBytes(safeRoots, filesDir, safeActive) + computeDirBytes(shadowPreviewsDir);
        return report;
    }

    private static long runPhaseA(List<File> roots, File filesDir, Set<String> activePaths, long nowMs) {
        long reclaimed = 0L;
        Set<File> casCleaned = new HashSet<>();
        for (File root : roots) {
            if (root == null || !root.isDirectory()) continue;
            File acache = "acache".equalsIgnoreCase(root.getName()) ? root : new File(root, "acache");
            if (acache.isDirectory()) {
                reclaimed += ColgramStorageOptimizer.pruneAcacheDirectory(
                        acache, activePaths, nowMs, ACACHE_PHASE_A_MAX_BYTES);
            }
            reclaimed += pruneStaleTempRecursive(root, 0, activePaths, nowMs);
            File cas = new File(root, ".colgram_cas");
            if (cas.isDirectory() && casCleaned.add(cas)) {
                reclaimed += ColgramStorageOptimizer.pruneOrphanCasBlobs(cas);
            }
        }
        if (filesDir != null) {
            File inbox = new File(filesDir, "colgram_inbox");
            if (inbox.isDirectory()) {
                reclaimed += ColgramStorageOptimizer.pruneRegenerableWaste(
                        Collections.emptyList(), inbox, activePaths, nowMs);
            }
            File rootCas = new File(filesDir, ".colgram_cas");
            if (rootCas.isDirectory() && casCleaned.add(rootCas)) {
                reclaimed += ColgramStorageOptimizer.pruneOrphanCasBlobs(rootCas);
            }
            reclaimed += rotateMessagesLogIfNeeded(filesDir);
            reclaimed += rotateMessagesLogIfNeeded(new File(filesDir, "Colgram"));
        }
        return reclaimed;
    }

    /** Rotates files/messages_log.jsonl into compressed messages_log.1.jsonl.gz when >= 8 MB. */
    public static long rotateMessagesLogIfNeeded(File filesDir) {
        if (filesDir == null || !filesDir.isDirectory()) return 0L;
        File logFile = new File(filesDir, "messages_log.jsonl");
        if (!logFile.isFile()) return 0L;
        long origLen = logFile.length();
        if (origLen < MESSAGES_LOG_ROTATE_BYTES) return 0L;
        File gzTarget = new File(filesDir, "messages_log.1.jsonl.gz");
        File gzTmp = new File(filesDir, "messages_log.1.jsonl.gz.cgtmp");
        try (FileInputStream in = new FileInputStream(logFile);
             GZIPOutputStream gzos = new GZIPOutputStream(new FileOutputStream(gzTmp))) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                gzos.write(buf, 0, n);
            }
            gzos.finish();
            gzos.flush();
        } catch (Throwable ignored) {
            //noinspection ResultOfMethodCallIgnored
            gzTmp.delete();
            return 0L;
        }
        long gzLen = gzTmp.isFile() ? gzTmp.length() : origLen;
        if (!ColgramStorageOptimizer.atomicReplaceFile(gzTmp, gzTarget)) {
            //noinspection ResultOfMethodCallIgnored
            gzTmp.delete();
            return 0L;
        }
        // Truncate active messages_log.jsonl cleanly
        try (FileOutputStream trunc = new FileOutputStream(logFile, false)) {
            trunc.flush();
        } catch (Throwable ignored) {
            //noinspection ResultOfMethodCallIgnored
            logFile.delete();
        }
        return Math.max(0L, origLen - gzLen);
    }

    private static long pruneStaleTempRecursive(File dir, int depth, Set<String> activePaths, long nowMs) {
        if (dir == null || !dir.isDirectory() || depth > 5) return 0L;
        String dirName = dir.getName().toLowerCase(Locale.ROOT);
        if ("shadow_previews".equals(dirName) || "colgram_vault".equals(dirName)
                || "colgram_wallpapers".equals(dirName) || "koliti_models".equals(dirName)
                || "shared_prefs".equals(dirName)) {
            return 0L;
        }
        File[] children = dir.listFiles();
        if (children == null) return 0L;
        long reclaimed = 0L;
        for (File f : children) {
            if (f == null) continue;
            if (f.isDirectory()) {
                if ("acache".equalsIgnoreCase(f.getName())) {
                    reclaimed += ColgramStorageOptimizer.pruneAcacheDirectory(
                            f, activePaths, nowMs, ACACHE_PHASE_A_MAX_BYTES);
                } else {
                    reclaimed += pruneStaleTempRecursive(f, depth + 1, activePaths, nowMs);
                }
            } else {
                String lower = f.getName().toLowerCase(Locale.ROOT);
                boolean isTemp = lower.endsWith(".temp") || lower.endsWith(".preload")
                        || lower.endsWith(".cgtmp") || lower.endsWith(".cglink");
                if (!isTemp) continue;
                if (ColgramStorageOptimizer.isPathActive(f, activePaths)) continue;
                long age = (nowMs > 0L && f.lastModified() > 0L) ? (nowMs - f.lastModified()) : Long.MAX_VALUE;
                if (age >= YOUNG_FILE_GRACE_MS) {
                    long len = f.length();
                    if (f.delete()) {
                        reclaimed += len;
                        ColgramStorageIndex.removeEntry(f.getAbsolutePath());
                    }
                }
            }
        }
        return reclaimed;
    }

    private static void runPhaseB(List<File> roots,
                                  File shadowPreviewsDir,
                                  long capBytes,
                                  Set<String> activePaths,
                                  ColgramStorageOptimizer.ImageTranscoder imageTranscoder,
                                  boolean isCharging,
                                  long nowMs,
                                  EnforcementReport report) {
        int maxFiles = isCharging ? CHARGER_PHASE_B_MAX_FILES : BATTERY_PHASE_B_MAX_FILES;
        long yieldMs = isCharging ? 0L : BATTERY_PHASE_B_YIELD_MS;
        int processed = 0;

        // 1. Incremental image compression (>120 KB) + gzip text compression (>32 KB)
        List<File> candidates = new ArrayList<>();
        for (File root : roots) {
            collectPhaseBCandidates(root, 0, activePaths, nowMs, candidates);
        }
        Collections.sort(candidates, (a, b) -> Long.compare(b.length(), a.length()));

        for (File f : candidates) {
            if (processed >= maxFiles) break;
            if (ColgramStorageOptimizer.isPathActive(f, activePaths)) continue;
            String lower = f.getName().toLowerCase(Locale.ROOT);
            if (isImageName(lower) && imageTranscoder != null && f.length() >= IMAGE_COMPRESS_MIN_BYTES) {
                if (!ColgramStorageIndex.isAlreadyProcessed(f)) {
                    long origLen = f.length();
                    long mtime = f.lastModified();
                    File tmp = new File(f.getAbsolutePath() + ".cgtmp");
                    try {
                        boolean ok = imageTranscoder.transcode(f, tmp, ColgramStorageOptimizer.PRESET_BALANCED);
                        long newLen = (ok && tmp.isFile()) ? tmp.length() : 0L;
                        if (ok && newLen > 4096L && newLen <= (origLen * 88L) / 100L) {
                            if (ColgramStorageOptimizer.atomicReplaceFile(tmp, f)) {
                                if (mtime > 0L) {
                                    //noinspection ResultOfMethodCallIgnored
                                    f.setLastModified(mtime);
                                }
                                long finalLen = f.length();
                                ColgramStorageIndex.recordEntry(f, origLen, finalLen, "",
                                        ColgramStorageIndex.STATE_COMPRESSED);
                                report.compressedFilesCount++;
                                report.phaseBCompressedSavedBytes += Math.max(0L, origLen - finalLen);
                            } else {
                                ColgramStorageIndex.recordEntry(f, origLen, origLen, "",
                                        ColgramStorageIndex.STATE_SKIPPED_OPTIMAL);
                            }
                        } else {
                            ColgramStorageIndex.recordEntry(f, origLen, origLen, "",
                                    ColgramStorageIndex.STATE_SKIPPED_OPTIMAL);
                        }
                    } catch (Throwable ignored) {
                        ColgramStorageIndex.recordEntry(f, origLen, origLen, "",
                                ColgramStorageIndex.STATE_SKIPPED_OPTIMAL);
                    } finally {
                        if (tmp.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            tmp.delete();
                        }
                    }
                    processed++;
                    sleepYield(yieldMs);
                }
            } else if (isCompressibleTextName(lower) && f.length() >= TEXT_COMPRESS_MIN_BYTES) {
                if (compressTextFileWithGzip(f, report)) {
                    processed++;
                    sleepYield(yieldMs);
                }
            }
        }

        // 2. Progressive Shadow Micro-Vault Tier-1 compaction (keeps 15k-30k+ previews inside budget)
        if (shadowPreviewsDir != null && shadowPreviewsDir.isDirectory()) {
            long softCap = capBytes > 0L
                    ? Math.min(SHADOW_PREVIEW_SOFT_CAP_BYTES, Math.max(16L * MB, (long) (capBytes * 0.125d)))
                    : SHADOW_PREVIEW_SOFT_CAP_BYTES;
            long hardCap = capBytes > 0L
                    ? Math.min(SHADOW_PREVIEW_HARD_CAP_BYTES, Math.max(24L * MB, (long) (capBytes * 0.18d)))
                    : SHADOW_PREVIEW_HARD_CAP_BYTES;
            compactShadowPreviews(shadowPreviewsDir, softCap, hardCap, imageTranscoder, report);
        }
    }

    private static void compactShadowPreviews(File shadowDir,
                                              long softCapBytes,
                                              long hardCapBytes,
                                              ColgramStorageOptimizer.ImageTranscoder transcoder,
                                              EnforcementReport report) {
        long current = computeDirBytes(shadowDir);
        if (current <= softCapBytes) return;

        File[] files = shadowDir.listFiles();
        if (files == null || files.length == 0) return;
        List<File> previews = new ArrayList<>();
        for (File f : files) {
            if (f != null && f.isFile() && !".nomedia".equals(f.getName())) {
                previews.add(f);
            }
        }

        // Pass 1: Compact larger micro-previews (>22 KB) to smaller density when over softCap
        if (transcoder != null) {
            previews.sort((a, b) -> Long.compare(b.length(), a.length()));
            int tier1Budget = 64;
            for (File f : previews) {
                if (current <= softCapBytes || tier1Budget <= 0) break;
                long orig = f.length();
                if (orig <= 22L * 1024L) continue;
                File tmp = new File(f.getAbsolutePath() + ".cgtmp");
                try {
                    boolean ok = transcoder.transcode(f, tmp, ColgramStorageOptimizer.PRESET_MAX);
                    long newLen = (ok && tmp.isFile()) ? tmp.length() : 0L;
                    if (ok && newLen > 512L && newLen < (orig * 85L) / 100L) {
                        if (ColgramStorageOptimizer.atomicReplaceFile(tmp, f)) {
                            long saved = Math.max(0L, orig - f.length());
                            current -= saved;
                            report.shadowPreviewsCompacted++;
                            report.phaseBCompressedSavedBytes += saved;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (tmp.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                }
                tier1Budget--;
            }
        }

        // Pass 2: Hard LRU eviction of oldest micro-previews ONLY if still above hardCapBytes
        if (current > hardCapBytes) {
            previews.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : previews) {
                if (current <= hardCapBytes) break;
                if (!f.isFile()) continue;
                long len = f.length();
                if (f.delete()) {
                    current -= len;
                    report.shadowPreviewsEvictedBytes += len;
                }
            }
        }
    }

    private static boolean compressTextFileWithGzip(File file, EnforcementReport report) {
        if (file == null || !file.isFile()) return false;
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".gz")) return false;
        long origLen = file.length();
        if (origLen < TEXT_COMPRESS_MIN_BYTES || isGzipFile(file)) return false;

        File targetGz = new File(file.getAbsolutePath() + ".gz");
        if (targetGz.exists()) return false;
        File tmpGz = new File(file.getAbsolutePath() + ".gz.cgtmp");
        try (FileInputStream in = new FileInputStream(file);
             GZIPOutputStream gzos = new GZIPOutputStream(new FileOutputStream(tmpGz)) {
                 {
                     def.setLevel(Deflater.BEST_COMPRESSION);
                 }
             }) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                gzos.write(buf, 0, n);
            }
            gzos.finish();
            gzos.flush();
        } catch (Throwable ignored) {
            //noinspection ResultOfMethodCallIgnored
            tmpGz.delete();
            return false;
        }
        long gzLen = tmpGz.isFile() ? tmpGz.length() : origLen;
        if (gzLen <= 0L || gzLen > (origLen * 85L) / 100L) {
            //noinspection ResultOfMethodCallIgnored
            tmpGz.delete();
            return false;
        }
        if (!ColgramStorageOptimizer.atomicReplaceFile(tmpGz, targetGz)) {
            //noinspection ResultOfMethodCallIgnored
            tmpGz.delete();
            return false;
        }
        long mtime = file.lastModified();
        if (mtime > 0L) {
            //noinspection ResultOfMethodCallIgnored
            targetGz.setLastModified(mtime);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        ColgramStorageIndex.recordEntry(targetGz, origLen, gzLen, "", ColgramStorageIndex.STATE_COMPRESSED);
        report.compressedFilesCount++;
        report.phaseBCompressedSavedBytes += Math.max(0L, origLen - gzLen);
        return true;
    }

    private static boolean isGzipFile(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            return in.read() == 0x1f && in.read() == 0x8b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void sleepYield(long yieldMs) {
        if (yieldMs <= 0L) return;
        try {
            Thread.sleep(yieldMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void collectPhaseBCandidates(File dir, int depth, Set<String> activePaths,
                                                long nowMs, List<File> out) {
        if (dir == null || !dir.isDirectory() || depth > 5) return;
        if (isProtectedDirectory(dir, depth)) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                collectPhaseBCandidates(child, depth + 1, activePaths, nowMs, out);
            } else {
                if (isProtectedFile(child, activePaths, nowMs, false)) continue;
                long age = (nowMs > 0L && child.lastModified() > 0L) ? (nowMs - child.lastModified()) : Long.MAX_VALUE;
                if (age < PHASE_B_SETTLE_MS) continue;
                String lower = child.getName().toLowerCase(Locale.ROOT);
                if ((isImageName(lower) && child.length() >= IMAGE_COMPRESS_MIN_BYTES)
                        || (isCompressibleTextName(lower) && child.length() >= TEXT_COMPRESS_MIN_BYTES)) {
                    out.add(child);
                }
            }
        }
    }

    private static long runPhaseCEviction(List<File> roots,
                                          long bytesToFree,
                                          Set<String> activePaths,
                                          long nowMs,
                                          EnforcementReport report) {
        if (bytesToFree <= 0L) return 0L;
        List<EvictCandidate> candidates = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        for (File root : roots) {
            collectEvictCandidates(root, 0, activePaths, nowMs, seenPaths, candidates);
        }
        // Prioritize large >10 MB cache hogs first, then ascending LRU timestamp
        Collections.sort(candidates, new Comparator<EvictCandidate>() {
            @Override
            public int compare(EvictCandidate a, EvictCandidate b) {
                if (a.isHog != b.isHog) {
                    return a.isHog ? -1 : 1;
                }
                int cmp = Long.compare(a.lruTimestamp, b.lruTimestamp);
                if (cmp != 0) return cmp;
                return Long.compare(b.size, a.size);
            }
        });

        long freed = 0L;
        for (EvictCandidate c : candidates) {
            if (freed >= bytesToFree) break;
            if (!c.file.isFile() || ColgramStorageOptimizer.isPathActive(c.file, activePaths)) continue;
            long len = c.file.length();
            if (len <= 0L) continue;
            ColgramStorageIndex.Entry existing = ColgramStorageIndex.getEntry(c.file);
            long virtualOrigSize = existing != null ? Math.max(existing.origSize, len) : len;
            if (c.file.delete()) {
                ColgramStorageIndex.markDehydrated(c.file, virtualOrigSize, 0L, 0, "");
                freed += len;
                report.dehydratedStubsRecorded++;
            }
        }
        return freed;
    }

    private static void collectEvictCandidates(File dir, int depth, Set<String> activePaths,
                                               long nowMs, Set<String> seenPaths, List<EvictCandidate> out) {
        if (dir == null || !dir.isDirectory() || depth > 5) return;
        if (isProtectedDirectory(dir, depth)) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                collectEvictCandidates(child, depth + 1, activePaths, nowMs, seenPaths, out);
            } else {
                if (isProtectedFile(child, activePaths, nowMs, true)) continue;
                String norm = ColgramStorageIndex.normalizePath(child);
                if (!seenPaths.add(norm)) continue;
                long len = child.length();
                if (len <= 0L) continue;
                ColgramStorageIndex.Entry entry = ColgramStorageIndex.getEntry(norm);
                long indexedTime = entry != null ? entry.updatedAtMs : 0L;
                long lru = Math.max(indexedTime, child.lastModified());
                out.add(new EvictCandidate(child, len, lru));
            }
        }
    }

    /** Strictly protects user vaults, models, DBs, keys, wallpapers, active files, and <5 min files. */
    public static boolean isProtectedFile(File file, Set<String> activePaths, long nowMs, boolean checkYoungGrace) {
        if (file == null || !file.isFile()) return true;
        String name = file.getName();
        String lowerName = name.toLowerCase(Locale.ROOT);
        if (".nomedia".equals(lowerName)
                || "colgram_storage_index_v1.tsv.gz".equals(lowerName)
                || "messages_log.jsonl".equals(lowerName)
                || "messages_log.1.jsonl.gz".equals(lowerName)
                || "colgram_config.xml".equals(lowerName)) {
            return true;
        }
        if (lowerName.endsWith(".db")
                || lowerName.endsWith(".db-wal")
                || lowerName.endsWith(".db-shm")
                || lowerName.endsWith(".db-journal")
                || lowerName.endsWith(".enc")
                || lowerName.endsWith(".key")
                || lowerName.endsWith(".keystore")
                || lowerName.endsWith(".gguf")
                || lowerName.endsWith(".onnx")
                || lowerName.endsWith(".tflite")
                || lowerName.endsWith(".cgorig")) {
            return true;
        }
        String normLower = ColgramStorageIndex.normalizePath(file).toLowerCase(Locale.ROOT);
        if (normLower.contains("/shadow_previews/")
                || normLower.contains("/colgram_vault/")
                || normLower.contains("/colgram_wallpapers/")
                || normLower.contains("/media_history/")
                || normLower.contains("/colgram_preserved/")
                || normLower.contains("/koliti_models/")
                || normLower.contains("/models/")
                || normLower.contains("/shared_prefs/")) {
            return true;
        }
        if (normLower.contains("/koliti_media/") && (lowerName.endsWith(".bin") || lowerName.endsWith(".pt"))) {
            return true;
        }
        if (ColgramStorageOptimizer.isPathActive(file, activePaths)) {
            return true;
        }
        if (checkYoungGrace && nowMs > 0L) {
            long mtime = file.lastModified();
            if (mtime > 0L && (nowMs - mtime) < YOUNG_FILE_GRACE_MS) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProtectedDirectory(File dir, int depth) {
        if (dir == null) return true;
        String lower = dir.getName().toLowerCase(Locale.ROOT);
        if ("shadow_previews".equals(lower)
                || "colgram_vault".equals(lower)
                || "colgram_wallpapers".equals(lower)
                || "media_history".equals(lower)
                || "colgram_preserved".equals(lower)
                || "koliti_models".equals(lower)
                || "models".equals(lower)
                || "shared_prefs".equals(lower)
                || "databases".equals(lower)
                || ".colgram_cas".equals(lower)) {
            return true;
        }
        return false;
    }

    private static boolean isImageName(String lower) {
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    private static boolean isCompressibleTextName(String lower) {
        if (lower.startsWith("colgram_") || lower.startsWith("messages_log")) return false;
        return lower.endsWith(".log") || lower.endsWith(".txt")
                || lower.endsWith(".jsonl") || lower.endsWith(".csv")
                || lower.endsWith(".json");
    }

    public static long computeManagedBytes(Collection<File> roots, File filesDir, Set<String> activePaths) {
        long total = 0L;
        Set<String> seen = new HashSet<>();
        if (roots != null) {
            for (File root : roots) {
                total += accumulateBytesDedup(root, 0, seen);
            }
        }
        if (filesDir != null) {
            File log = new File(filesDir, "messages_log.jsonl");
            if (log.isFile() && seen.add(ColgramStorageIndex.normalizePath(log))) {
                total += log.length();
            }
            File logGz = new File(filesDir, "messages_log.1.jsonl.gz");
            if (logGz.isFile() && seen.add(ColgramStorageIndex.normalizePath(logGz))) {
                total += logGz.length();
            }
        }
        return total;
    }

    private static long accumulateBytesDedup(File dir, int depth, Set<String> seenFiles) {
        if (dir == null || !dir.isDirectory() || depth > 6) return 0L;
        String lower = dir.getName().toLowerCase(Locale.ROOT);
        if ("shadow_previews".equals(lower) || "colgram_vault".equals(lower)
                || "colgram_wallpapers".equals(lower) || "media_history".equals(lower)
                || "koliti_models".equals(lower) || "shared_prefs".equals(lower) || "databases".equals(lower)) {
            return 0L;
        }
        File[] children = dir.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                total += accumulateBytesDedup(child, depth + 1, seenFiles);
            } else {
                String norm = ColgramStorageIndex.normalizePath(child);
                if (seenFiles.add(norm)) {
                    total += Math.max(0L, child.length());
                }
            }
        }
        return total;
    }

    public static long computeDirBytes(File dir) {
        if (dir == null || !dir.isDirectory()) return 0L;
        long[] stats = ColgramStorageSandbox.computeDirStats(dir);
        return stats.length > 1 ? stats[1] : 0L;
    }

    public static VirtualStorageStats getVirtualStorageStats(Context context) {
        VirtualStorageStats stats = new VirtualStorageStats();
        if (context == null) return stats;
        ColgramShieldConfig.init(context);
        ColgramStorageIndex.init(context.getFilesDir());

        List<File> roots = collectAllManagedRoots(context);
        File shadowDir = ColgramStorageSandbox.getShadowPreviewsDir(context);
        long managed = computeManagedBytes(roots, context.getFilesDir(), Collections.emptySet());
        long[] shadowStats = shadowDir != null ? ColgramStorageSandbox.computeDirStats(shadowDir) : new long[]{0L, 0L};

        stats.shadowVaultFilesCount = (int) shadowStats[0];
        stats.shadowVaultBytes = shadowStats[1];
        stats.physicalBytes = managed + stats.shadowVaultBytes;
        trackedCacheBytes.set(stats.physicalBytes);

        stats.capBytes = ColgramShieldConfig.isStorageCapEnabled() ? ColgramShieldConfig.getStorageHardCapBytes() : 0L;
        stats.savedByCompressionBytes = ColgramStorageIndex.getTotalCompressionSavedBytes();
        stats.dehydratedBytes = ColgramStorageIndex.getTotalDehydratedBytes();
        stats.dehydratedFilesCount = ColgramStorageIndex.getDehydratedCount();
        stats.virtualCatalogBytes = Math.max(stats.physicalBytes,
                stats.physicalBytes + stats.savedByCompressionBytes + stats.dehydratedBytes);
        return stats;
    }

    public static String getVirtualStorageStatsSummary(Context context) {
        VirtualStorageStats s = getVirtualStorageStats(context);
        StringBuilder sb = new StringBuilder();
        sb.append("Физически: ").append(formatSize(s.physicalBytes));
        if (s.capBytes > 0L) {
            sb.append(" / ").append(formatSize(s.capBytes));
        } else {
            sb.append(" / Безлимит");
        }
        sb.append(" · Виртуально: ").append(formatSize(s.virtualCatalogBytes));
        if (s.savedByCompressionBytes > 0L) {
            sb.append(" · Сжато: ").append(formatSize(s.savedByCompressionBytes));
        }
        if (s.shadowVaultFilesCount > 0) {
            sb.append(" · Shadow превью: ").append(s.shadowVaultFilesCount)
              .append(" (").append(formatSize(s.shadowVaultBytes)).append(")");
        }
        return sb.toString();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < MB) return String.format(Locale.US, "%.1f KB", bytes / 1024.0d);
        if (bytes < 1024L * MB) return String.format(Locale.US, "%.1f MB", bytes / (double) MB);
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0d * MB));
    }

    private static List<File> dedupRoots(Collection<File> roots) {
        List<File> out = new ArrayList<>();
        if (roots == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (File r : roots) {
            if (r == null || !r.isDirectory()) continue;
            String key;
            try {
                key = ColgramStorageIndex.normalizePath(r.getCanonicalPath());
            } catch (Throwable ignored) {
                key = ColgramStorageIndex.normalizePath(r.getAbsolutePath());
            }
            if (seen.add(key)) out.add(r);
        }
        return out;
    }

    private static boolean isDeviceCharging(Context ctx) {
        if (ctx == null) return false;
        try {
            Intent status = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (status == null) return false;
            int plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            return plugged == BatteryManager.BATTERY_PLUGGED_AC
                    || plugged == BatteryManager.BATTERY_PLUGGED_USB
                    || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isThermalSevere(Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < 29) return false;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return false;
            Method m = pm.getClass().getMethod("getCurrentThermalStatus");
            Object val = m.invoke(pm);
            return (val instanceof Integer) && ((Integer) val) >= 3; // THERMAL_STATUS_SEVERE
        } catch (Throwable ignored) {
            return false;
        }
    }
}
