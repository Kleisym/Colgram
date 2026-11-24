package org.colgram.core;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Embedded + Resumable GGUF Model & Vision Projector (mmproj) Manager.
 * Priority 1: Built-in APK assets/koliti/koliti_core.gguf + koliti_vision.gguf (zero external download!).
 * Priority 2: User-supplied custom GGUF path.
 * Priority 3: Resumable download directly from official Colgram GitHub Release
 *             (Kleisym/Colgram, tag koliti-models-v3) with cross-host redirect handling,
 *             auto-retry Range resume, SOCKS5 DOMAIN (ATYP=0x03) routing & DoH fallback.
 */
public final class ColgramAiModelManager {

    public interface DownloadListener {
        void onProgress(String phase, long downloadedBytes, long totalBytes, double speedMbPerSec);
        void onComplete(boolean success, String message);
    }

    /** Immutable global snapshot of active/paused/failed model download state for UI restoration. */
    public static final class DownloadState {
        public static final DownloadState IDLE = new DownloadState(
                false, false, "", "", "", 0L, 0L, 0.0, false, "");

        public final boolean active;
        public final boolean extracting;
        public final String modelId;
        public final String modelTitle;
        public final String phase;
        public final long downloadedBytes;
        public final long totalBytes;
        public final double speedMbPerSec;
        public final boolean pausedByUser;
        public final String lastError;

        public DownloadState(
                boolean active,
                boolean extracting,
                String modelId,
                String modelTitle,
                String phase,
                long downloadedBytes,
                long totalBytes,
                double speedMbPerSec,
                boolean pausedByUser,
                String lastError
        ) {
            this.active = active;
            this.extracting = extracting;
            this.modelId = modelId == null ? "" : modelId;
            this.modelTitle = modelTitle == null ? "" : modelTitle;
            this.phase = phase == null ? "" : phase;
            this.downloadedBytes = Math.max(0L, downloadedBytes);
            this.totalBytes = Math.max(0L, totalBytes);
            this.speedMbPerSec = Math.max(0.0, speedMbPerSec);
            this.pausedByUser = pausedByUser;
            this.lastError = lastError == null ? "" : lastError;
        }

        public int percent() {
            if (totalBytes <= 0L) return -1;
            return (int) Math.min(100L, (downloadedBytes * 100L) / totalBytes);
        }
    }

    public interface DownloadObserver {
        void onDownloadStateChanged(DownloadState state);
    }

    public static final class ResolvedModelPaths {
        public final String modelPath;
        public final String mmprojPath;
        public final String displayName;
        public final boolean isCustom;

        public ResolvedModelPaths(String modelPath, String mmprojPath, String displayName, boolean isCustom) {
            this.modelPath = modelPath;
            this.mmprojPath = mmprojPath;
            this.displayName = displayName;
            this.isCustom = isCustom;
        }

        public boolean isReady() {
            return modelPath != null && !modelPath.isEmpty() && isValidGgufFile(new File(modelPath));
        }

        public boolean hasVision() {
            return mmprojPath != null && !mmprojPath.isEmpty() && isValidGgufFile(new File(mmprojPath));
        }
    }

    /** Bumped whenever the bundled APK koliti_core.gguf weights are upgraded so old phones auto-refresh. */
    public static final int EMBEDDED_CORE_REVISION = 3;
    public static final int MAX_REDIRECT_HOPS = 8;

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 18000;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 6;
    private static final long RETRY_DELAY_MS = 1500L;
    private static final long PROGRESS_INTERVAL_MS = 250L;
    private static final long DISK_SAFETY_MARGIN_BYTES = 64L * 1024L * 1024L;

    private static final int ROUTE_DIRECT_PLAIN = 0;
    private static final int ROUTE_SOCKS5_DOMAIN = 1;
    private static final int ROUTE_DIRECT_DOH = 2;

    private static final AtomicBoolean CANCEL_FLAG = new AtomicBoolean(false);
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);
    private static final Object EXTRACT_LOCK = new Object();
    private static final CopyOnWriteArraySet<DownloadObserver> OBSERVERS = new CopyOnWriteArraySet<>();

    private static volatile boolean extracting = false;
    private static volatile Boolean cachedHasEmbeddedAssets = null;
    private static volatile DownloadState currentState = DownloadState.IDLE;
    private static volatile Closeable activeStreamCloseable = null;
    private static volatile Thread activeDownloadThread = null;
    private static volatile int preferredRouteIndex = ROUTE_DIRECT_PLAIN;

    private static final java.util.concurrent.ConcurrentHashMap<String, String> VALID_GGUF_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ColgramAiModelManager() {}

    public static DownloadState getDownloadState() {
        return currentState;
    }

    public static void addDownloadObserver(DownloadObserver observer) {
        if (observer == null) return;
        OBSERVERS.add(observer);
        try {
            observer.onDownloadStateChanged(currentState);
        } catch (Throwable ignored) {}
    }

    public static void removeDownloadObserver(DownloadObserver observer) {
        if (observer != null) {
            OBSERVERS.remove(observer);
        }
    }

    private static void publishState(DownloadState state) {
        currentState = state != null ? state : DownloadState.IDLE;
        for (DownloadObserver obs : OBSERVERS) {
            try {
                obs.onDownloadStateChanged(currentState);
            } catch (Throwable ignored) {}
        }
    }

    public static File modelsRootDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "colgram_ai_models");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static File modelFileFor(Context ctx, ColgramAiModels.ModelSpec spec) {
        File sub = new File(modelsRootDir(ctx), spec.id);
        if (!sub.exists()) {
            //noinspection ResultOfMethodCallIgnored
            sub.mkdirs();
        }
        return new File(sub, spec.filename);
    }

    public static File mmprojFileFor(Context ctx, ColgramAiModels.ModelSpec spec) {
        if (!spec.supportsVision()) return null;
        File sub = new File(modelsRootDir(ctx), spec.id);
        if (!sub.exists()) {
            //noinspection ResultOfMethodCallIgnored
            sub.mkdirs();
        }
        return new File(sub, spec.mmprojFilename);
    }

    /** Returns valid partial GGUF byte count waiting on disk for resume, or 0 if none. */
    public static long getPartialBytes(Context ctx, ColgramAiModels.ModelSpec spec) {
        if (ctx == null || spec == null) return 0L;
        File modelFile = modelFileFor(ctx, spec);
        File partFile = new File(modelFile.getParentFile(), modelFile.getName() + ".part");
        if (partFile.isFile() && partFile.length() >= 4L && hasGgufMagic(partFile)) {
            return partFile.length();
        }
        return 0L;
    }

    /** Removes incomplete `.part`/`.unpack` files for {@code spec} when no operation is active. */
    public static void clearPartialDownload(Context ctx, ColgramAiModels.ModelSpec spec) {
        if (ctx == null || spec == null || isDownloading()) return;
        File modelFile = modelFileFor(ctx, spec);
        File parent = modelFile.getParentFile();
        if (parent != null) {
            deleteQuietly(new File(parent, modelFile.getName() + ".part"));
            deleteQuietly(new File(parent, modelFile.getName() + ".unpack"));
        }
        File visFile = mmprojFileFor(ctx, spec);
        if (visFile != null && visFile.getParentFile() != null) {
            deleteQuietly(new File(visFile.getParentFile(), visFile.getName() + ".part"));
            deleteQuietly(new File(visFile.getParentFile(), visFile.getName() + ".copy"));
            deleteQuietly(new File(visFile.getParentFile(), visFile.getName() + ".unpack"));
        }
        DownloadState cur = currentState;
        if (!cur.active && (cur.modelId.isEmpty() || spec.id.equals(cur.modelId))) {
            publishState(DownloadState.IDLE);
        }
    }

    /** Returns true when this APK bundles koliti_core.gguf directly inside assets/koliti/. */
    public static boolean hasEmbeddedAssets(Context ctx) {
        if (ctx == null) return false;
        Boolean cached = cachedHasEmbeddedAssets;
        if (cached != null) return cached;
        try {
            AssetManager am = ctx.getAssets();
            String[] list = am.list("koliti");
            boolean found = false;
            if (list != null) {
                for (String name : list) {
                    if ("koliti_core.gguf".equals(name)) {
                        found = true;
                        break;
                    }
                }
            }
            cachedHasEmbeddedAssets = found;
            return found;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isExtractingEmbedded() {
        return extracting;
    }

    /** Returns true when the given catalog model's GGUF file is installed and valid on disk. */
    public static boolean isModelDownloaded(Context ctx, ColgramAiModels.ModelSpec spec) {
        if (ctx == null || spec == null) return false;
        return isValidGgufFile(modelFileFor(ctx, spec));
    }

    /** Returns true when the given catalog model's Vision projector file is installed and valid on disk. */
    public static boolean isVisionDownloaded(Context ctx, ColgramAiModels.ModelSpec spec) {
        if (ctx == null || spec == null || !spec.supportsVision()) return false;
        File vf = mmprojFileFor(ctx, spec);
        return vf != null && isValidGgufFile(vf);
    }

    /** Returns true when this APK bundles a newer koliti_core.gguf revision than what is extracted on disk. */
    public static boolean needsEmbeddedUpgrade(Context ctx) {
        if (ctx == null || !hasEmbeddedAssets(ctx)) return false;
        if (!ColgramAiConfig.getCustomModelPath(ctx).isEmpty()) return false;
        String activeId = ColgramAiConfig.getActiveModelId(ctx);
        if (!ColgramAiModels.KOLITI_MODEL_ID.equals(activeId)) {
            ColgramAiModels.ModelSpec activeSpec = ColgramAiModels.findById(activeId);
            if (isModelDownloaded(ctx, activeSpec)) {
                return false;
            }
        }
        File destModel = modelFileFor(ctx, ColgramAiModels.primary());
        return needsEmbeddedCoreUpgrade(ctx, destModel);
    }

    private static boolean needsEmbeddedCoreUpgrade(Context ctx, File destModel) {
        if (!isValidGgufFile(destModel) || destModel.length() < 100_000_000L) {
            return true;
        }
        int installedRev = ColgramAiConfig.getEmbeddedCoreRevision(ctx);
        if (installedRev < EMBEDDED_CORE_REVISION) {
            // Upgrade old damaged 937MB Q3_K_M abliterated GGUF to clean official core GGUF
            return true;
        }
        return false;
    }

    /**
     * Synchronously ensures embedded APK weights (koliti_core.gguf + koliti_vision.gguf)
     * are unpacked once into private storage so native mmap can load them with zero download.
     */
    public static ResolvedModelPaths ensureEmbeddedReadySync(Context ctx, DownloadListener progressListener) {
        if (ctx == null) {
            return new ResolvedModelPaths("", "", ColgramAiModels.primary().title, false);
        }
        ColgramAiModels.ModelSpec spec = ColgramAiModels.primary();
        File checkModel = modelFileFor(ctx, spec);
        boolean upgradeNeeded = hasEmbeddedAssets(ctx) && needsEmbeddedCoreUpgrade(ctx, checkModel);
        ResolvedModelPaths existing = resolveActiveModel(ctx);
        if (!upgradeNeeded && existing.isReady() && (existing.hasVision() || !hasEmbeddedAssets(ctx))) {
            return existing;
        }
        if (!hasEmbeddedAssets(ctx)) {
            return existing;
        }

        synchronized (EXTRACT_LOCK) {
            existing = resolveActiveModel(ctx);
            upgradeNeeded = needsEmbeddedCoreUpgrade(ctx, modelFileFor(ctx, spec));
            if (!upgradeNeeded && existing.isReady() && existing.hasVision()) {
                return existing;
            }
            extracting = true;
            publishState(new DownloadState(
                    true, true, spec.id, spec.title,
                    "Распаковка встроенного ядра…", 0L, spec.approxModelBytes, 0.0, false, ""));
            try {
                File destModel = modelFileFor(ctx, spec);
                if (needsEmbeddedCoreUpgrade(ctx, destModel)) {
                    extractAssetFile(ctx.getAssets(), spec.bundledModelAsset, destModel,
                            spec.id, spec.title,
                            "Обновление встроенного ядра Koliti", spec.approxModelBytes, progressListener);
                    if (isValidGgufFile(destModel)) {
                        ColgramAiConfig.setEmbeddedCoreRevision(ctx, EMBEDDED_CORE_REVISION);
                    }
                }
                File destVis = mmprojFileFor(ctx, spec);
                if (destVis != null && spec.bundledMmprojAsset != null && !spec.bundledMmprojAsset.isEmpty()) {
                    if (!isValidGgufFile(destVis) || destVis.length() < 10_000_000L) {
                        try {
                            extractAssetFile(ctx.getAssets(), spec.bundledMmprojAsset, destVis,
                                    spec.id, spec.title,
                                    "Распаковка Vision-модуля (фото/видео)", spec.approxMmprojBytes, progressListener);
                        } catch (Throwable ignored) {
                            // Vision asset optional if stripped
                        }
                    }
                }
                ColgramAiConfig.setActiveModelId(ctx, spec.id);
                boolean ok = isValidGgufFile(destModel);
                extracting = false;
                publishState(ok
                        ? DownloadState.IDLE
                        : new DownloadState(false, false, spec.id, spec.title, "", 0L, spec.approxModelBytes,
                                0.0, false, "Не удалось распаковать ядро"));
                if (progressListener != null) {
                    progressListener.onComplete(ok,
                            ok ? ("Встроенное ядро " + spec.title + " готово!") : "Не удалось распаковать ядро");
                }
            } catch (Throwable err) {
                extracting = false;
                String msg = "Ошибка распаковки ядра: " + err.getMessage();
                publishState(new DownloadState(false, false, spec.id, spec.title, "", 0L, spec.approxModelBytes,
                        0.0, false, msg));
                if (progressListener != null) {
                    progressListener.onComplete(false, msg);
                }
            } finally {
                extracting = false;
            }
        }
        return resolveActiveModel(ctx);
    }

    /** Unpacks embedded APK model weights on a low-priority background thread (zero UI-thread disk I/O). */
    public static void ensureEmbeddedReadyAsync(final Context ctx, final DownloadListener listener) {
        if (ctx == null) {
            if (listener != null) listener.onComplete(false, "Контекст недоступен");
            return;
        }
        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            if (!hasEmbeddedAssets(appCtx)) {
                if (listener != null) {
                    ResolvedModelPaths cur = resolveActiveModel(appCtx);
                    listener.onComplete(cur != null && cur.isReady(),
                            cur != null && cur.isReady() ? "Ядро готово" : "Встроенные веса не найдены");
                }
                return;
            }
            ResolvedModelPaths existing = resolveActiveModel(appCtx);
            File checkModel = modelFileFor(appCtx, ColgramAiModels.primary());
            if (!needsEmbeddedCoreUpgrade(appCtx, checkModel) && existing.isReady() && existing.hasVision()) {
                if (listener != null) listener.onComplete(true, "Встроенное ядро " + ColgramAiModels.primary().title + " готово!");
                return;
            }
            ensureEmbeddedReadySync(appCtx, listener);
        }, "colgram-koliti-unpack");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    private static void extractAssetFile(
            AssetManager am,
            String assetPath,
            File destFile,
            String modelId,
            String modelTitle,
            String phase,
            long expectedTotal,
            DownloadListener listener
    ) throws IOException {
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File partFile = new File(parent, destFile.getName() + ".unpack");
        byte[] buf = new byte[131072]; // 128 KB smooth flash copy without starving UI I/O
        long copied = 0L;
        long lastYieldAt = 0L;
        long windowStart = System.currentTimeMillis();
        long windowBytes = 0L;
        try (InputStream is = am.open(assetPath, AssetManager.ACCESS_STREAMING);
             FileOutputStream fos = new FileOutputStream(partFile)) {
            int n;
            while ((n = is.read(buf)) != -1) {
                fos.write(buf, 0, n);
                copied += n;
                windowBytes += n;
                if (copied - lastYieldAt >= 4L * 1024L * 1024L) {
                    lastYieldAt = copied;
                    Thread.yield();
                }
                long now = System.currentTimeMillis();
                long dt = now - windowStart;
                if (dt >= 250L) {
                    double speed = (windowBytes / (1024.0 * 1024.0)) / (dt / 1000.0);
                    long tot = Math.max(expectedTotal, copied);
                    publishState(new DownloadState(true, true, modelId, modelTitle, phase, copied, tot, speed, false, ""));
                    if (listener != null) {
                        listener.onProgress(phase, copied, tot, speed);
                    }
                    windowStart = now;
                    windowBytes = 0L;
                }
            }
            fos.flush();
        }
        if (!isValidGgufFile(partFile)) {
            //noinspection ResultOfMethodCallIgnored
            partFile.delete();
            throw new IOException("Повреждённый файл модели: " + assetPath);
        }
        renameSafe(partFile, destFile);
        publishState(new DownloadState(true, true, modelId, modelTitle, phase, destFile.length(), destFile.length(), 0.0, false, ""));
        if (listener != null) {
            listener.onProgress(phase, destFile.length(), destFile.length(), 0.0);
        }
    }

    public static ResolvedModelPaths resolveActiveModel(Context ctx) {
        String custom = ColgramAiConfig.getCustomModelPath(ctx);
        if (!custom.isEmpty() && isValidGgufFile(new File(custom))) {
            String customMm = ColgramAiConfig.getCustomMmprojPath(ctx);
            return new ResolvedModelPaths(custom, isValidGgufFile(new File(customMm)) ? customMm : "", new File(custom).getName(), true);
        }

        String activeId = ColgramAiConfig.getActiveModelId(ctx);
        ColgramAiModels.ModelSpec spec = ColgramAiModels.findById(activeId);
        File mFile = modelFileFor(ctx, spec);
        File vFile = mmprojFileFor(ctx, spec);
        if (isValidGgufFile(mFile)) {
            String vPath = (vFile != null && isValidGgufFile(vFile)) ? vFile.getAbsolutePath() : "";
            return new ResolvedModelPaths(mFile.getAbsolutePath(), vPath, spec.title, false);
        }

        // Fall back to any other already-extracted/downloaded catalog model if present.
        for (ColgramAiModels.ModelSpec candidate : ColgramAiModels.catalog()) {
            File cm = modelFileFor(ctx, candidate);
            if (isValidGgufFile(cm)) {
                File cv = mmprojFileFor(ctx, candidate);
                String vPath = (cv != null && isValidGgufFile(cv)) ? cv.getAbsolutePath() : "";
                return new ResolvedModelPaths(cm.getAbsolutePath(), vPath, candidate.title, false);
            }
        }
        return new ResolvedModelPaths("", "", spec.title, false);
    }

    public static boolean isValidGgufFile(File f) {
        if (f == null || !f.isFile()) {
            return false;
        }
        long len = f.length();
        if (len < 65536L) {
            return false;
        }
        String path = f.getAbsolutePath();
        String sig = len + ":" + f.lastModified();
        if (sig.equals(VALID_GGUF_CACHE.get(path))) {
            return true;
        }
        byte[] hdr = new byte[4];
        try (FileInputStream fis = new FileInputStream(f)) {
            int n = fis.read(hdr);
            boolean ok = n == 4 && hdr[0] == 'G' && hdr[1] == 'G' && hdr[2] == 'U' && hdr[3] == 'F';
            if (ok) {
                VALID_GGUF_CACHE.put(path, sig);
            } else {
                VALID_GGUF_CACHE.remove(path);
            }
            return ok;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isDownloading() {
        return DOWNLOADING.get() || extracting;
    }

    public static void cancelDownload() {
        CANCEL_FLAG.set(true);
        Closeable active = activeStreamCloseable;
        if (active != null) {
            try {
                active.close();
            } catch (Throwable ignored) {}
        }
        Thread t = activeDownloadThread;
        if (t != null) {
            try {
                t.interrupt();
            } catch (Throwable ignored) {}
        }
        DownloadState cur = currentState;
        if (cur.active && !cur.extracting) {
            publishState(new DownloadState(
                    true, false, cur.modelId, cur.modelTitle,
                    "Остановка загрузки…", cur.downloadedBytes, cur.totalBytes, 0.0, true, ""));
        }
    }

    public static void deleteModelFiles(Context ctx, ColgramAiModels.ModelSpec spec) {
        File sub = new File(modelsRootDir(ctx), spec.id);
        deleteRecursive(sub);
    }

    public static void startDownloadAsync(final Context ctx, final ColgramAiModels.ModelSpec spec, final boolean includeVision, final DownloadListener listener) {
        if (ctx == null || spec == null) {
            if (listener != null) listener.onComplete(false, "Неверные параметры загрузки");
            return;
        }
        // If this spec's weights are bundled inside the APK, unpack offline instantly instead of hitting the network!
        if (spec.hasBundledAssets() && hasEmbeddedAssets(ctx)) {
            ensureEmbeddedReadyAsync(ctx, listener);
            return;
        }
        if (!DOWNLOADING.compareAndSet(false, true)) {
            if (listener != null) listener.onComplete(false, "Операция уже выполняется");
            return;
        }

        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        CANCEL_FLAG.set(false);

        long partialBytes = getPartialBytes(appCtx, spec);
        long neededBytes = Math.max(0L, spec.approxModelBytes - partialBytes)
                + (includeVision && spec.supportsVision() && !isVisionDownloaded(appCtx, spec) ? spec.approxMmprojBytes : 0L);
        long freeBytes = modelsRootDir(appCtx).getUsableSpace();
        if (freeBytes > 0L && freeBytes < neededBytes + DISK_SAFETY_MARGIN_BYTES) {
            DOWNLOADING.set(false);
            String msg = "Недостаточно свободной памяти: нужно "
                    + formatSizeMb(neededBytes + DISK_SAFETY_MARGIN_BYTES)
                    + ", доступно " + formatSizeMb(freeBytes);
            publishState(new DownloadState(false, false, spec.id, spec.title, "", partialBytes, spec.approxModelBytes, 0.0, false, msg));
            if (listener != null) listener.onComplete(false, msg);
            return;
        }

        publishState(new DownloadState(
                true, false, spec.id, spec.title,
                "Подключение к серверу…", partialBytes, spec.approxModelBytes, 0.0, false, ""));

        Thread t = new Thread(() -> {
            activeDownloadThread = Thread.currentThread();
            boolean finishedOk = false;
            boolean cancelled = false;
            String errMessage = "";
            try {
                File destModel = modelFileFor(appCtx, spec);
                downloadFileResumable(
                        "Модель " + spec.title,
                        spec.modelDownloadUrl(),
                        destModel,
                        spec.approxModelBytes,
                        null,
                        listener,
                        spec.id,
                        spec.title
                );
                if (CANCEL_FLAG.get()) {
                    cancelled = true;
                    return;
                }
                if (!isValidGgufFile(destModel)) {
                    throw new IOException("Файл модели повреждён (неверный формат)");
                }
                if (includeVision && spec.supportsVision() && !CANCEL_FLAG.get()) {
                    File destMm = mmprojFileFor(appCtx, spec);
                    if (destMm != null && !tryReuseSharedVisionFile(appCtx, spec, destMm, spec.id, spec.title, listener)) {
                        downloadFileResumable(
                                "Vision-проектор (фото/видео)",
                                spec.mmprojDownloadUrl(),
                                destMm,
                                spec.approxMmprojBytes,
                                null,
                                listener,
                                spec.id,
                                spec.title
                        );
                        if (CANCEL_FLAG.get()) {
                            cancelled = true;
                            return;
                        }
                        if (!isValidGgufFile(destMm)) {
                            throw new IOException("Vision-файл повреждён (неверный формат)");
                        }
                    }
                }
                if (CANCEL_FLAG.get()) {
                    cancelled = true;
                } else {
                    ColgramAiConfig.setCustomModelPath(appCtx, "");
                    ColgramAiConfig.setCustomMmprojPath(appCtx, "");
                    ColgramAiConfig.setActiveModelId(appCtx, spec.id);
                    finishedOk = true;
                }
            } catch (Throwable err) {
                if (CANCEL_FLAG.get()) {
                    cancelled = true;
                } else {
                    errMessage = err.getMessage() != null && !err.getMessage().isEmpty()
                            ? err.getMessage() : err.getClass().getSimpleName();
                }
            } finally {
                activeStreamCloseable = null;
                activeDownloadThread = null;
                Thread.interrupted(); // Clear any leftover interrupt from cancelDownload()
                DOWNLOADING.set(false);
                long savedPartial = getPartialBytes(appCtx, spec);
                if (finishedOk) {
                    publishState(DownloadState.IDLE);
                } else if (cancelled) {
                    publishState(new DownloadState(
                            false, false, spec.id, spec.title,
                            "Загрузка приостановлена", savedPartial, spec.approxModelBytes, 0.0, true, ""));
                } else {
                    publishState(new DownloadState(
                            false, false, spec.id, spec.title,
                            "", savedPartial, spec.approxModelBytes, 0.0, false, errMessage));
                }
            }

            if (listener != null) {
                if (finishedOk) {
                    listener.onComplete(true, spec.title + " и Vision готовы к работе!");
                } else if (cancelled) {
                    listener.onComplete(false, "Загрузка приостановлена (можно продолжить позже)");
                } else {
                    listener.onComplete(false, "Ошибка загрузки: " + errMessage);
                }
            }
        }, "colgram-ai-dl");
        t.start();
    }

    /**
     * Reuses an already-installed identical Vision projector file (same downloadAssetName)
     * from another catalog tier on disk to avoid re-downloading 223 MB unnecessarily.
     */
    private static boolean tryReuseSharedVisionFile(
            Context ctx,
            ColgramAiModels.ModelSpec targetSpec,
            File destMm,
            String modelId,
            String modelTitle,
            DownloadListener listener
    ) {
        if (ctx == null || targetSpec == null || destMm == null) return false;
        if (isValidGgufFile(destMm) && destMm.length() >= Math.max(50_000_000L, targetSpec.approxMmprojBytes - 1024L)) {
            return true;
        }
        String wantAsset = targetSpec.mmprojDownloadAssetName;
        if (wantAsset == null || wantAsset.isEmpty()) return false;
        for (ColgramAiModels.ModelSpec other : ColgramAiModels.catalog()) {
            if (other == targetSpec || !wantAsset.equals(other.mmprojDownloadAssetName)) continue;
            File srcMm = mmprojFileFor(ctx, other);
            if (srcMm == null || !isValidGgufFile(srcMm) || srcMm.length() < 50_000_000L) continue;
            File tmp = new File(destMm.getParentFile(), destMm.getName() + ".copy");
            try (FileInputStream fis = new FileInputStream(srcMm);
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[131072];
                long total = srcMm.length();
                int n;
                while ((n = fis.read(buf)) != -1) {
                    if (CANCEL_FLAG.get()) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                        return false;
                    }
                    fos.write(buf, 0, n);
                }
                fos.flush();
                if (isValidGgufFile(tmp)) {
                    renameSafe(tmp, destMm);
                    publishState(new DownloadState(true, false, modelId, modelTitle,
                            "Vision-проектор (локальная копия)", total, total, 0.0, false, ""));
                    if (listener != null) {
                        listener.onProgress("Vision-проектор (локальная копия)", total, total, 0.0);
                    }
                    return true;
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            } catch (Throwable ignored) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        return false;
    }

    private static boolean hasGgufMagic(File f) {
        if (f == null || !f.isFile() || f.length() < 4L) return false;
        byte[] hdr = new byte[4];
        try (FileInputStream fis = new FileInputStream(f)) {
            return fis.read(hdr) == 4
                    && hdr[0] == 'G' && hdr[1] == 'G' && hdr[2] == 'U' && hdr[3] == 'F';
        } catch (Throwable ignored) {
            return false;
        }
    }

    static final class SocksOverride {
        final String host;
        final int port;

        SocksOverride(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static final class FatalHttpException extends IOException {
        FatalHttpException(String message) {
            super(message);
        }
    }

    private static final class StreamResponse implements Closeable {
        final int statusCode;
        final long contentLength;
        final String contentRange;
        final String location;
        final InputStream body;
        final Closeable closeHandle;

        StreamResponse(int statusCode, long contentLength, String contentRange,
                       String location, InputStream body, Closeable closeHandle) {
            this.statusCode = statusCode;
            this.contentLength = contentLength;
            this.contentRange = contentRange;
            this.location = location;
            this.body = body;
            this.closeHandle = closeHandle;
        }

        @Override
        public void close() {
            if (closeHandle != null) {
                try {
                    closeHandle.close();
                } catch (Throwable ignored) {}
            }
        }
    }

    /** Host JVM unit-test entrypoint exercising full redirects, Range resume, SOCKS5 DOMAIN & cancel state. */
    static void downloadFileForTest(
            String urlStr,
            File destFile,
            long expectedTotalBytes,
            String socksHost,
            int socksPort,
            String modelId,
            String modelTitle,
            DownloadListener listener
    ) throws IOException {
        CANCEL_FLAG.set(false);
        DOWNLOADING.set(true);
        activeDownloadThread = Thread.currentThread();
        boolean ok = false;
        boolean cancelled = false;
        String errMsg = "";
        try {
            publishState(new DownloadState(
                    true, false, modelId, modelTitle, "Подключение к серверу…",
                    destFile.getParentFile() != null && new File(destFile.getParentFile(), destFile.getName() + ".part").isFile()
                            ? new File(destFile.getParentFile(), destFile.getName() + ".part").length() : 0L,
                    expectedTotalBytes, 0.0, false, ""));
            SocksOverride override = (socksHost != null && !socksHost.isEmpty() && socksPort > 0)
                    ? new SocksOverride(socksHost, socksPort) : null;
            downloadFileResumable("Тестовая загрузка", urlStr, destFile, expectedTotalBytes,
                    override, listener, modelId, modelTitle);
            if (CANCEL_FLAG.get()) {
                cancelled = true;
            } else {
                ok = true;
            }
        } catch (Throwable err) {
            if (CANCEL_FLAG.get()) {
                cancelled = true;
            } else {
                errMsg = err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
                throw (err instanceof IOException) ? (IOException) err : new IOException(errMsg, err);
            }
        } finally {
            activeStreamCloseable = null;
            activeDownloadThread = null;
            Thread.interrupted();
            DOWNLOADING.set(false);
            if (ok) {
                publishState(DownloadState.IDLE);
            } else if (cancelled) {
                File p = destFile.getParentFile() != null
                        ? new File(destFile.getParentFile(), destFile.getName() + ".part") : null;
                long saved = (p != null && p.isFile() && hasGgufMagic(p)) ? p.length() : 0L;
                publishState(new DownloadState(false, false, modelId, modelTitle,
                        "Загрузка приостановлена", saved, expectedTotalBytes, 0.0, true, ""));
            } else {
                publishState(new DownloadState(false, false, modelId, modelTitle,
                        "", 0L, expectedTotalBytes, 0.0, false, errMsg));
            }
        }
    }

    static void resetDownloadStateForTest() {
        CANCEL_FLAG.set(false);
        DOWNLOADING.set(false);
        extracting = false;
        activeStreamCloseable = null;
        activeDownloadThread = null;
        preferredRouteIndex = ROUTE_DIRECT_PLAIN;
        currentState = DownloadState.IDLE;
        OBSERVERS.clear();
        VALID_GGUF_CACHE.clear();
    }

    static void downloadFileResumable(
            String phase,
            String urlStr,
            File destFile,
            long expectedTotalBytes,
            SocksOverride socksOverride,
            DownloadListener listener,
            String modelId,
            String modelTitle
    ) throws IOException {
        if (isValidGgufFile(destFile) && destFile.length() > 50_000_000L) {
            long remoteLen = probeContentLength(urlStr, socksOverride);
            if (remoteLen > 0 && destFile.length() >= remoteLen) {
                emitProgress(modelId, modelTitle, phase, destFile.length(), remoteLen, 0.0, listener);
                return;
            }
        }

        File parentDir = destFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parentDir.mkdirs();
        }
        File partFile = new File(parentDir, destFile.getName() + ".part");

        IOException lastErr = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            if (CANCEL_FLAG.get()) return;

            long existing = partFile.isFile() ? partFile.length() : 0L;
            if (existing >= 4L && !hasGgufMagic(partFile)) {
                // Wipe corrupt non-GGUF partial file (e.g. captive portal HTML) before retrying cleanly.
                //noinspection ResultOfMethodCallIgnored
                partFile.delete();
                existing = 0L;
            }

            long initialDisplayTotal = Math.max(expectedTotalBytes, existing);
            emitProgress(modelId, modelTitle,
                    attempt == 1 ? phase : (phase + " (попытка " + attempt + "/" + MAX_DOWNLOAD_ATTEMPTS + ")"),
                    existing, initialDisplayTotal, 0.0, listener);

            try (StreamResponse sr = openStreamWithRedirects(
                    urlStr, existing > 0 ? ("bytes=" + existing + "-") : null, socksOverride)) {
                activeStreamCloseable = sr;
                if (CANCEL_FLAG.get()) {
                    return;
                }
                int code = sr.statusCode;
                if (code == 416 && partFile.isFile() && hasGgufMagic(partFile)) {
                    if (expectedTotalBytes <= 0L || partFile.length() >= expectedTotalBytes) {
                        renameSafe(partFile, destFile);
                        return;
                    }
                    // Partial file is somehow corrupt or stale against upstream size
                    //noinspection ResultOfMethodCallIgnored
                    partFile.delete();
                    throw new IOException("Сервер отклонил диапазон (HTTP 416), начинаю заново");
                }
                if (code == 403 || code == 404 || code == 410) {
                    throw new FatalHttpException("HTTP " + code + " (файл недоступен на сервере)");
                }
                if (code != 200 && code != 206) {
                    throw new IOException("HTTP " + code + " при загрузке файла");
                }

                boolean append = (code == 206 && existing > 0);
                if (!append) {
                    existing = 0L;
                }
                long contentLen = parseTotalBytesFromHeaders(sr.contentLength, sr.contentRange, existing, append);
                long displayTotal = contentLen > 0 ? contentLen : Math.max(expectedTotalBytes, existing);

                emitProgress(modelId, modelTitle, phase, existing, displayTotal, 0.0, listener);

                byte[] buf = new byte[65536];
                long transferred = existing;
                long windowStart = System.currentTimeMillis();
                long windowBytes = 0L;
                boolean firstChunkSent = false;

                try (RandomAccessFile raf = new RandomAccessFile(partFile, "rw")) {
                    if (append) {
                        raf.seek(existing);
                    } else {
                        raf.setLength(0);
                    }
                    int n;
                    while ((n = sr.body.read(buf)) != -1) {
                        if (CANCEL_FLAG.get() || Thread.currentThread().isInterrupted()) {
                            return;
                        }
                        raf.write(buf, 0, n);
                        transferred += n;
                        windowBytes += n;

                        long now = System.currentTimeMillis();
                        long dt = now - windowStart;
                        if (!firstChunkSent || dt >= PROGRESS_INTERVAL_MS) {
                            firstChunkSent = true;
                            double speed = dt > 0 ? (windowBytes / (1024.0 * 1024.0)) / (dt / 1000.0) : 0.0;
                            emitProgress(modelId, modelTitle, phase, transferred,
                                    Math.max(displayTotal, transferred), speed, listener);
                            windowStart = now;
                            windowBytes = 0L;
                        }
                    }
                }

                if (CANCEL_FLAG.get() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                if (contentLen > 0 && transferred < contentLen) {
                    throw new IOException("Обрыв соединения: скачано " + formatSizeMb(transferred) + " из " + formatSizeMb(contentLen));
                }
                if (!isValidGgufFile(partFile)) {
                    //noinspection ResultOfMethodCallIgnored
                    partFile.delete();
                    throw new IOException("Получен повреждённый файл модели");
                }
                renameSafe(partFile, destFile);
                long finalSize = destFile.length();
                emitProgress(modelId, modelTitle, phase, finalSize, Math.max(contentLen, finalSize), 0.0, listener);
                return;
            } catch (FatalHttpException fatal) {
                throw fatal;
            } catch (IOException err) {
                activeStreamCloseable = null;
                if (CANCEL_FLAG.get() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                lastErr = err;
                if (attempt >= MAX_DOWNLOAD_ATTEMPTS) {
                    break;
                }
                // Rotate route so next attempt skips whichever direct/SOCKS route failed mid-stream
                preferredRouteIndex = (preferredRouteIndex + 1) % 3;
                long savedNow = (partFile.isFile() && hasGgufMagic(partFile)) ? partFile.length() : 0L;
                emitProgress(modelId, modelTitle,
                        "Переподключение (" + (attempt + 1) + "/" + MAX_DOWNLOAD_ATTEMPTS + ")…",
                        savedNow, Math.max(expectedTotalBytes, savedNow), 0.0, listener);
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    return;
                }
            } finally {
                activeStreamCloseable = null;
            }
        }
        throw lastErr != null ? lastErr : new IOException("Не удалось завершить загрузку файла");
    }

    private static void emitProgress(
            String modelId,
            String modelTitle,
            String phase,
            long downloaded,
            long total,
            double speed,
            DownloadListener listener
    ) {
        publishState(new DownloadState(
                true, false, modelId, modelTitle, phase, downloaded, total, speed, false, ""));
        if (listener != null) {
            listener.onProgress(phase, downloaded, total, speed);
        }
    }

    static String resolveRedirectTarget(String currentUrl, String locationHeader) throws IOException {
        if (locationHeader == null || locationHeader.trim().isEmpty()) {
            throw new IOException("Empty redirect location");
        }
        URL base = new URL(currentUrl);
        URL resolved = new URL(base, locationHeader.trim());
        String proto = resolved.getProtocol();
        if (!"https".equalsIgnoreCase(proto) && !"http".equalsIgnoreCase(proto)) {
            throw new IOException("Unsupported redirect protocol: " + proto);
        }
        return resolved.toString();
    }

    private static StreamResponse openStreamWithRedirects(
            String initialUrl,
            String rangeHeader,
            SocksOverride socksOverride
    ) throws IOException {
        if (socksOverride != null) {
            return openRedirectChainOnRoute(initialUrl, rangeHeader, ROUTE_SOCKS5_DOMAIN, socksOverride);
        }
        ColgramProxyManager.ProxyItem active = null;
        boolean bypassBound = false;
        try {
            active = ColgramProxyManager.getCurrentActiveProxy();
            bypassBound = ColgramDpiBypass.isBound();
        } catch (Throwable ignored) {
            // Host JVM unit tests without full native/Android stack stay direct
        }
        boolean preferExternalProxy = active != null && active.type == 0 && !active.isLocalDpi();
        int[] order;
        if (preferExternalProxy) {
            order = new int[]{ROUTE_SOCKS5_DOMAIN, ROUTE_DIRECT_PLAIN, ROUTE_DIRECT_DOH};
        } else {
            int start = preferredRouteIndex;
            order = new int[]{
                    (start) % 3,
                    (start + 1) % 3,
                    (start + 2) % 3
            };
        }

        IOException lastErr = null;
        for (int route : order) {
            if (CANCEL_FLAG.get()) {
                throw new IOException("Загрузка приостановлена");
            }
            if (route == ROUTE_SOCKS5_DOMAIN && !preferExternalProxy && !bypassBound) {
                continue;
            }
            try {
                StreamResponse resp = openRedirectChainOnRoute(initialUrl, rangeHeader, route, null);
                preferredRouteIndex = route;
                return resp;
            } catch (FatalHttpException fatal) {
                throw fatal;
            } catch (IOException err) {
                lastErr = err;
            }
        }
        throw lastErr != null ? lastErr : new IOException("Не удалось подключиться к серверу загрузки");
    }

    private static StreamResponse openRedirectChainOnRoute(
            String initialUrl,
            String rangeHeader,
            int route,
            SocksOverride socksOverride
    ) throws IOException {
        String current = initialUrl;
        for (int hop = 0; hop < MAX_REDIRECT_HOPS; hop++) {
            if (CANCEL_FLAG.get()) {
                throw new IOException("Загрузка приостановлена");
            }
            StreamResponse sr = openViaRawHttpSocket(current, rangeHeader, route, socksOverride);
            int code = sr.statusCode;
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = sr.location;
                activeStreamCloseable = null;
                sr.close();
                current = resolveRedirectTarget(current, loc);
                continue;
            }
            return sr;
        }
        throw new IOException("Too many redirects (>" + MAX_REDIRECT_HOPS + ")");
    }

    private static StreamResponse openViaRawHttpSocket(
            String urlStr,
            String rangeHeader,
            int route,
            SocksOverride socksOverride
    ) throws IOException {
        URL url = new URL(urlStr);
        boolean tls = "https".equalsIgnoreCase(url.getProtocol());
        String host = url.getHost();
        int port = url.getPort() > 0 ? url.getPort() : (tls ? 443 : 80);
        String filePath = (url.getFile() == null || url.getFile().isEmpty()) ? "/" : url.getFile();

        Socket raw = new Socket();
        activeStreamCloseable = raw;
        Socket activeSock = raw;
        try {
            if (CANCEL_FLAG.get()) {
                throw new IOException("Загрузка приостановлена");
            }
            if (route == ROUTE_SOCKS5_DOMAIN) {
                String socksHost;
                int socksPort;
                if (socksOverride != null) {
                    socksHost = socksOverride.host;
                    socksPort = socksOverride.port;
                } else {
                    ColgramProxyManager.ProxyItem relay = ColgramProxyManager.getCurrentActiveProxy();
                    if (relay != null && relay.type == 0) {
                        socksHost = relay.address;
                        socksPort = relay.port;
                    } else if (ColgramDpiBypass.isBound()) {
                        socksHost = "127.0.0.1";
                        socksPort = ColgramDpiBypass.activePort();
                    } else {
                        throw new IOException("SOCKS5 proxy unavailable");
                    }
                }
                raw.connect(new InetSocketAddress(socksHost, socksPort), CONNECT_TIMEOUT_MS);
                raw.setSoTimeout(READ_TIMEOUT_MS);
                performSocks5DomainConnect(raw, host, port);
            } else if (route == ROUTE_DIRECT_DOH) {
                InetAddress resolved = ColgramDohResolver.resolveOrNull(host);
                if (resolved == null) {
                    throw new IOException("DoH resolve returned null for " + host);
                }
                raw.connect(new InetSocketAddress(resolved, port), CONNECT_TIMEOUT_MS);
                raw.setSoTimeout(READ_TIMEOUT_MS);
            } else {
                raw.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                raw.setSoTimeout(READ_TIMEOUT_MS);
            }

            if (tls) {
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(raw, host, port, true);
                activeSock = ssl;
                activeStreamCloseable = ssl;
                try {
                    ColgramDohResolver.hardenTlsSocket(ssl);
                } catch (Throwable ignored) {}
                ssl.startHandshake();
                HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
                if (!verifier.verify(host, ssl.getSession())) {
                    throw new SSLPeerUnverifiedException("Hostname verification failed for " + host);
                }
            }

            OutputStream out = activeSock.getOutputStream();
            StringBuilder req = new StringBuilder();
            req.append("GET ").append(filePath).append(" HTTP/1.1\r\n");
            if (url.getPort() > 0 && url.getPort() != (tls ? 443 : 80)) {
                req.append("Host: ").append(host).append(':').append(port).append("\r\n");
            } else {
                req.append("Host: ").append(host).append("\r\n");
            }
            req.append("User-Agent: Colgram/3.0\r\n");
            req.append("Accept: application/octet-stream, */*\r\n");
            req.append("Accept-Encoding: identity\r\n");
            if (rangeHeader != null && !rangeHeader.isEmpty()) {
                req.append("Range: ").append(rangeHeader).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");
            out.write(req.toString().getBytes("US-ASCII"));
            out.flush();

            BufferedInputStream bis = new BufferedInputStream(activeSock.getInputStream(), 65536);
            String statusLine = readAsciiLine(bis);
            if (statusLine == null) {
                throw new EOFException("Empty HTTP response");
            }
            int statusCode = parseHttpStatusCode(statusLine);
            long contentLength = -1L;
            String contentRange = null;
            String location = null;
            boolean chunked = false;

            String line;
            while ((line = readAsciiLine(bis)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String val = line.substring(colon + 1).trim();
                if ("location".equals(key)) {
                    location = val;
                } else if ("content-range".equals(key)) {
                    contentRange = val;
                } else if ("content-length".equals(key)) {
                    try {
                        contentLength = Long.parseLong(val);
                    } catch (Throwable ignored) {}
                } else if ("transfer-encoding".equals(key) && val.toLowerCase(Locale.US).contains("chunked")) {
                    chunked = true;
                }
            }

            InputStream bodyStream = chunked ? new ChunkedInputStream(bis) : bis;
            final Socket finalSock = activeSock;
            // Keep activeSock assigned to activeStreamCloseable across return so cancelDownload()
            // aborts immediately without a null window.
            return new StreamResponse(statusCode, contentLength, contentRange, location, bodyStream, finalSock);
        } catch (IOException err) {
            activeStreamCloseable = null;
            try { activeSock.close(); } catch (Throwable ignored) {}
            try { raw.close(); } catch (Throwable ignored) {}
            throw err;
        }
    }

    private static void performSocks5DomainConnect(Socket sock, String host, int port) throws IOException {
        OutputStream out = sock.getOutputStream();
        InputStream in = sock.getInputStream();

        out.write(new byte[]{0x05, 0x01, 0x00});
        out.flush();
        byte[] greeting = new byte[2];
        readFully(in, greeting);
        if (greeting[0] != 0x05 || greeting[1] != 0x00) {
            throw new IOException("SOCKS5 greeting rejected: method=" + (greeting[1] & 0xFF));
        }

        byte[] hostBytes = host.getBytes("UTF-8");
        if (hostBytes.length == 0 || hostBytes.length > 255) {
            throw new IOException("Invalid SOCKS5 hostname length: " + hostBytes.length);
        }
        byte[] req = new byte[7 + hostBytes.length];
        req[0] = 0x05;
        req[1] = 0x01; // CONNECT
        req[2] = 0x00;
        req[3] = 0x03; // ATYP = DOMAINNAME (never leaks/fails on local DNS)
        req[4] = (byte) hostBytes.length;
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.length);
        req[5 + hostBytes.length] = (byte) ((port >> 8) & 0xFF);
        req[6 + hostBytes.length] = (byte) (port & 0xFF);
        out.write(req);
        out.flush();

        byte[] hdr = new byte[4];
        readFully(in, hdr);
        if (hdr[0] != 0x05 || hdr[1] != 0x00) {
            throw new IOException("SOCKS5 CONNECT failed: rep=" + (hdr[1] & 0xFF));
        }
        int atyp = hdr[3] & 0xFF;
        if (atyp == 0x01) {
            readFully(in, new byte[6]);
        } else if (atyp == 0x03) {
            int len = in.read();
            if (len < 0) throw new EOFException("SOCKS5 EOF reading domain length");
            readFully(in, new byte[len + 2]);
        } else if (atyp == 0x04) {
            readFully(in, new byte[18]);
        }
    }

    private static void readFully(InputStream in, byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int n = in.read(dst, off, dst.length - off);
            if (n < 0) {
                throw new EOFException("Unexpected EOF on SOCKS5 control stream");
            }
            off += n;
        }
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                sb.append((char) (b & 0xFF));
            }
        }
        if (!any && sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }

    private static int parseHttpStatusCode(String statusLine) throws IOException {
        int sp1 = statusLine.indexOf(' ');
        if (sp1 < 0 || sp1 + 4 > statusLine.length()) {
            throw new IOException("Malformed HTTP status: " + statusLine);
        }
        try {
            return Integer.parseInt(statusLine.substring(sp1 + 1, sp1 + 4));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid HTTP status code: " + statusLine, e);
        }
    }

    private static final class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private long remaining = 0L;
        private boolean eof = false;

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n == -1 ? -1 : (b[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) return -1;
            if (remaining == 0L) {
                String szLine = readAsciiLine(in);
                if (szLine == null) {
                    eof = true;
                    return -1;
                }
                int semi = szLine.indexOf(';');
                String hex = (semi >= 0 ? szLine.substring(0, semi) : szLine).trim();
                long chunkSz;
                try {
                    chunkSz = Long.parseLong(hex, 16);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid chunk size: " + szLine, e);
                }
                if (chunkSz == 0L) {
                    String trailer;
                    while ((trailer = readAsciiLine(in)) != null && !trailer.isEmpty()) {
                        // consume trailers
                    }
                    eof = true;
                    return -1;
                }
                remaining = chunkSz;
            }
            int toRead = (int) Math.min(len, remaining);
            int n = in.read(b, off, toRead);
            if (n < 0) {
                throw new EOFException("Unexpected EOF inside HTTP chunk");
            }
            remaining -= n;
            if (remaining == 0L) {
                readAsciiLine(in); // trailing CRLF after chunk body
            }
            return n;
        }
    }

    private static long probeContentLength(String urlStr, SocksOverride socksOverride) {
        StreamResponse c = null;
        try {
            c = openStreamWithRedirects(urlStr, "bytes=0-0", socksOverride);
            if (c.statusCode == 206 || c.statusCode == 200) {
                return parseTotalBytesFromHeaders(c.contentLength, c.contentRange, 0L, false);
            }
            return -1L;
        } catch (Throwable ignored) {
            return -1L;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private static long parseTotalBytesFromHeaders(long cl, String cr, long existing, boolean append) {
        if (cr != null) {
            int slash = cr.lastIndexOf('/');
            if (slash > 0) {
                String tail = cr.substring(slash + 1).trim();
                if (!"*".equals(tail)) {
                    try {
                        return Long.parseLong(tail);
                    } catch (Throwable ignored) {}
                }
            }
        }
        if (cl > 0) {
            return append ? (existing + cl) : cl;
        }
        return -1L;
    }

    private static void renameSafe(File src, File dst) throws IOException {
        if (dst.exists() && !dst.delete()) {
            throw new IOException("Не удалось заменить файл " + dst.getName());
        }
        if (!src.renameTo(dst)) {
            throw new IOException("Не удалось переименовать файл " + dst.getName());
        }
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] list = f.listFiles();
        if (list != null) {
            for (File c : list) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static String formatSizeMb(long bytes) {
        if (bytes <= 0) return "0 МБ";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024.0) {
            return String.format(Locale.US, "%.2f ГБ", mb / 1024.0);
        }
        return String.format(Locale.US, "%.1f МБ", mb);
    }
}
