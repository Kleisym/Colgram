package org.colgram.core;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedded + Resumable GGUF Model & Vision Projector (mmproj) Manager.
 * Priority 1: Built-in APK assets/koliti/koliti_core.gguf + koliti_vision.gguf (zero external download!).
 * Priority 2: User-supplied custom GGUF path.
 * Priority 3: Resumable HuggingFace download fallback if embedded assets were stripped.
 */
public final class ColgramAiModelManager {

    public interface DownloadListener {
        void onProgress(String phase, long downloadedBytes, long totalBytes, double speedMbPerSec);
        void onComplete(boolean success, String message);
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
    public static final int EMBEDDED_CORE_REVISION = 2;

    private static final AtomicBoolean CANCEL_FLAG = new AtomicBoolean(false);
    private static final Object EXTRACT_LOCK = new Object();
    private static volatile boolean downloading = false;
    private static volatile boolean extracting = false;
    private static volatile Boolean cachedHasEmbeddedAssets = null;
    private static final java.util.concurrent.ConcurrentHashMap<String, String> VALID_GGUF_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ColgramAiModelManager() {}

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

    /** Returns true when this APK bundles a newer koliti_core.gguf revision than what is extracted on disk. */
    public static boolean needsEmbeddedUpgrade(Context ctx) {
        if (ctx == null || !hasEmbeddedAssets(ctx)) return false;
        if (!ColgramAiConfig.getCustomModelPath(ctx).isEmpty()) return false;
        File destModel = modelFileFor(ctx, ColgramAiModels.primary());
        return needsEmbeddedCoreUpgrade(ctx, destModel);
    }

    private static boolean needsEmbeddedCoreUpgrade(Context ctx, File destModel) {
        if (!isValidGgufFile(destModel) || destModel.length() < 100_000_000L) {
            return true;
        }
        int installedRev = ColgramAiConfig.getEmbeddedCoreRevision(ctx);
        if (installedRev < EMBEDDED_CORE_REVISION) {
            // Upgrade old damaged 937MB Q3_K_M abliterated GGUF to clean official Qwen3.5-2B UD-Q4_K_XL
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
            try {
                File destModel = modelFileFor(ctx, spec);
                if (needsEmbeddedCoreUpgrade(ctx, destModel)) {
                    extractAssetFile(ctx.getAssets(), spec.bundledModelAsset, destModel,
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
                                    "Распаковка Vision-модуля (фото/видео)", spec.approxMmprojBytes, progressListener);
                        } catch (Throwable ignored) {
                            // Vision asset optional if stripped
                        }
                    }
                }
                ColgramAiConfig.setActiveModelId(ctx, spec.id);
                if (progressListener != null) {
                    progressListener.onComplete(isValidGgufFile(destModel),
                            isValidGgufFile(destModel) ? "Встроенное ядро Koliti Core Ultra готово!" : "Не удалось распаковать ядро");
                }
            } catch (Throwable err) {
                if (progressListener != null) {
                    progressListener.onComplete(false, "Ошибка распаковки ядра: " + err.getMessage());
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
        final Context appCtx = ctx.getApplicationContext();
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
                if (listener != null) listener.onComplete(true, "Встроенное ядро Koliti Core Ultra готово!");
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
                    if (listener != null) {
                        listener.onProgress(phase, copied, Math.max(expectedTotal, copied), speed);
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
            throw new IOException("Повреждённый GGUF актив: " + assetPath);
        }
        renameSafe(partFile, destFile);
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
        return downloading || extracting;
    }

    public static void cancelDownload() {
        CANCEL_FLAG.set(true);
    }

    public static void deleteModelFiles(Context ctx, ColgramAiModels.ModelSpec spec) {
        File sub = new File(modelsRootDir(ctx), spec.id);
        deleteRecursive(sub);
    }

    public static void startDownloadAsync(final Context ctx, final ColgramAiModels.ModelSpec spec, final boolean includeVision, final DownloadListener listener) {
        if (ctx == null || spec == null || downloading) {
            if (listener != null) listener.onComplete(false, "Операция уже выполняется");
            return;
        }
        // If this spec's weights are bundled inside the APK, unpack offline instantly instead of hitting the network!
        if (spec.hasBundledAssets() && hasEmbeddedAssets(ctx)) {
            ensureEmbeddedReadyAsync(ctx, listener);
            return;
        }
        downloading = true;
        CANCEL_FLAG.set(false);
        Thread t = new Thread(() -> {
            try {
                File destModel = modelFileFor(ctx, spec);
                downloadWithResume("Основная модель " + spec.title, spec.modelDownloadUrl(), destModel, listener);
                if (CANCEL_FLAG.get()) {
                    if (listener != null) listener.onComplete(false, "Загрузка приостановлена (можно продолжить позже)");
                    return;
                }
                if (!isValidGgufFile(destModel)) {
                    throw new IOException("Файл модели повреждён (отсутствует заголовок GGUF)");
                }
                if (includeVision && spec.supportsVision() && !CANCEL_FLAG.get()) {
                    File destMm = mmprojFileFor(ctx, spec);
                    if (destMm != null) {
                        downloadWithResume("Vision-проектор (фото/видео)", spec.mmprojDownloadUrl(), destMm, listener);
                    }
                }
                if (CANCEL_FLAG.get()) {
                    if (listener != null) listener.onComplete(false, "Загрузка приостановлена (можно продолжить позже)");
                } else {
                    ColgramAiConfig.setCustomModelPath(ctx, "");
                    ColgramAiConfig.setActiveModelId(ctx, spec.id);
                    if (listener != null) listener.onComplete(true, "Модель и Vision готовы к работе!");
                }
            } catch (Throwable err) {
                String msg = err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
                if (listener != null) listener.onComplete(false, "Ошибка загрузки: " + msg);
            } finally {
                downloading = false;
            }
        }, "colgram-ai-dl");
        t.start();
    }

    private static void downloadWithResume(String phase, String urlStr, File destFile, DownloadListener listener) throws IOException {
        if (isValidGgufFile(destFile) && destFile.length() > 150_000_000L) {
            long remoteLen = probeContentLength(urlStr);
            if (remoteLen > 0 && destFile.length() >= remoteLen) {
                if (listener != null) listener.onProgress(phase, destFile.length(), remoteLen, 0.0);
                return;
            }
        }

        File partFile = new File(destFile.getParentFile(), destFile.getName() + ".part");
        long existing = partFile.isFile() ? partFile.length() : 0L;

        HttpURLConnection conn = openWithRedirects(urlStr, existing);
        try {
            int code = conn.getResponseCode();
            if (code == 416 && partFile.isFile() && isValidGgufFile(partFile)) {
                renameSafe(partFile, destFile);
                return;
            }
            if (code != 200 && code != 206) {
                throw new IOException("HTTP " + code + " для " + urlStr);
            }
            boolean append = (code == 206 && existing > 0);
            if (!append) {
                existing = 0L;
            }
            long contentLen = parseTotalBytes(conn, existing, append);

            byte[] buf = new byte[65536];
            long transferred = existing;
            long windowStart = System.currentTimeMillis();
            long windowBytes = 0L;

            try (BufferedInputStream bis = new BufferedInputStream(conn.getInputStream(), 65536);
                 RandomAccessFile raf = new RandomAccessFile(partFile, "rw")) {
                if (append) {
                    raf.seek(existing);
                } else {
                    raf.setLength(0);
                }
                int n;
                while ((n = bis.read(buf)) != -1) {
                    if (CANCEL_FLAG.get()) {
                        return;
                    }
                    raf.write(buf, 0, n);
                    transferred += n;
                    windowBytes += n;

                    long now = System.currentTimeMillis();
                    long dt = now - windowStart;
                    if (dt >= 350L) {
                        double speed = (windowBytes / (1024.0 * 1024.0)) / (dt / 1000.0);
                        if (listener != null) {
                            listener.onProgress(phase, transferred, contentLen, speed);
                        }
                        windowStart = now;
                        windowBytes = 0L;
                    }
                }
            }
            if (contentLen > 0 && transferred < contentLen) {
                throw new IOException("Обрыв соединения: скачано " + transferred + " из " + contentLen + " байт");
            }
            renameSafe(partFile, destFile);
            if (listener != null) {
                listener.onProgress(phase, destFile.length(), Math.max(contentLen, destFile.length()), 0.0);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openWithRedirects(String initialUrl, long rangeStart) throws IOException {
        String current = initialUrl;
        for (int hop = 0; hop < 7; hop++) {
            HttpURLConnection conn = ColgramHttp.openBinaryStream(current, 15000, 30000);
            conn.setInstanceFollowRedirects(false);
            if (rangeStart > 0) {
                conn.setRequestProperty("Range", "bytes=" + rangeStart + "-");
            }
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new IOException("Empty redirect location");
                }
                if (loc.startsWith("/")) {
                    java.net.URL base = new java.net.URL(current);
                    loc = base.getProtocol() + "://" + base.getAuthority() + loc;
                }
                current = loc;
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }

    private static long probeContentLength(String urlStr) {
        try {
            HttpURLConnection c = openWithRedirects(urlStr, 0);
            long len = c.getContentLengthLong();
            c.disconnect();
            return len;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static long parseTotalBytes(HttpURLConnection conn, long existing, boolean append) {
        String cr = conn.getHeaderField("Content-Range");
        if (cr != null) {
            int slash = cr.lastIndexOf('/');
            if (slash > 0) {
                try {
                    return Long.parseLong(cr.substring(slash + 1).trim());
                } catch (Throwable ignored) {}
            }
        }
        long cl = conn.getContentLengthLong();
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
