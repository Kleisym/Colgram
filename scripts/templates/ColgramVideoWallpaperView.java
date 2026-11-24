package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.LruCache;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.colgram.core.ColgramConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight, looping, muted center-crop video wallpaper view and local wallpaper storage
 * helper for Colgram chats. Renders directly inside ChatActivity's background layer with
 * automatic lifecycle pause/resume, async MediaPlayer teardown, zero main-thread disk I/O,
 * and zero audio focus interruption.
 */
public final class ColgramVideoWallpaperView extends TextureView implements TextureView.SurfaceTextureListener {

    public interface WallpaperResolveCallback {
        void onResolved(boolean hasVideo, String videoPath, BitmapDrawable drawable, String loadedImagePath);
    }

    private static final int MAX_WALLPAPER_CACHE_KB = Math.min(32 * 1024, Math.max(8 * 1024, (int) (Runtime.getRuntime().maxMemory() / 1024 / 12)));
    private static final LruCache<String, Bitmap> WALLPAPER_BITMAP_CACHE = new LruCache<String, Bitmap>(MAX_WALLPAPER_CACHE_KB) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 1 : Math.max(1, value.getByteCount() / 1024);
        }
    };
    private static final ConcurrentHashMap<String, Boolean> FILE_EXISTS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> VIDEO_POSTER_PATH_CACHE = new ConcurrentHashMap<>();
    private static final DispatchQueue RELEASE_QUEUE = new DispatchQueue("ColgramVideoReleaseQueue");

    private String videoPath;
    private MediaPlayer mediaPlayer;
    private Surface renderSurface;
    private int videoWidth;
    private int videoHeight;
    private boolean pausedByHost;
    private boolean windowVisible = true;
    private boolean surfaceReady;
    private boolean playerPrepared;
    private boolean firstFrameRendered;
    private int playerGeneration;
    private int pendingFileCheckGen;
    private int errorRetryCount;
    private int stallRecoveryCount;
    private Runnable fallbackCheckRunnable;
    private Runnable watchdogCheckRunnable;
    private Runnable retryRunnable;

    public ColgramVideoWallpaperView(@NonNull Context context) {
        super(context);
        setOpaque(true);
        setSurfaceTextureListener(this);
    }

    public String getVideoPath() {
        return videoPath;
    }

    public void setVideoPath(String path) {
        String clean = path == null ? "" : path.trim();
        pendingFileCheckGen++;
        if (clean.isEmpty()) {
            this.videoPath = "";
            releasePlayer();
            setVisibility(View.GONE);
            return;
        }
        Boolean cachedExists = FILE_EXISTS_CACHE.get(clean);
        if (Boolean.FALSE.equals(cachedExists)) {
            this.videoPath = "";
            releasePlayer();
            setVisibility(View.GONE);
            return;
        }
        if (Boolean.TRUE.equals(cachedExists)) {
            applyValidatedVideoPath(clean);
            return;
        }
        // Unknown on cold process start: never stat disk synchronously on UI thread.
        this.videoPath = clean;
        final int checkGen = pendingFileCheckGen;
        Utilities.globalQueue.postRunnable(() -> {
            boolean exists = new File(clean).isFile();
            FILE_EXISTS_CACHE.put(clean, exists);
            AndroidUtilities.runOnUIThread(() -> {
                if (checkGen != pendingFileCheckGen || !clean.equals(videoPath)) {
                    return;
                }
                if (!exists) {
                    this.videoPath = "";
                    releasePlayer();
                    setVisibility(View.GONE);
                } else {
                    applyValidatedVideoPath(clean);
                }
            });
        });
    }

    private void applyValidatedVideoPath(String clean) {
        if (clean.equals(this.videoPath) && mediaPlayer != null) {
            setVisibility(View.VISIBLE);
            if (shouldBePlaying()) {
                if (renderSurface == null || !renderSurface.isValid()) {
                    if (isAvailable() && getSurfaceTexture() != null) {
                        surfaceReady = true;
                        startPlayer(getSurfaceTexture());
                    }
                } else if (playerPrepared) {
                    // CRITICAL: NEVER call start() while MediaPlayer is still in Preparing state!
                    try {
                        if (!mediaPlayer.isPlaying()) {
                            mediaPlayer.start();
                        }
                        if (firstFrameRendered && getAlpha() < 1f) {
                            setAlpha(1f);
                        }
                        scheduleStartFallbackCheck(playerGeneration);
                    } catch (Throwable ignored) {}
                }
            }
            return;
        }
        this.videoPath = clean;
        errorRetryCount = 0;
        stallRecoveryCount = 0;
        setVisibility(View.VISIBLE);
        if (shouldBePlaying() && isAvailable() && getSurfaceTexture() != null) {
            surfaceReady = true;
            startPlayer(getSurfaceTexture());
        }
    }

    public void setHostPaused(boolean paused) {
        pausedByHost = paused;
        if (paused) {
            pausePlaybackOnly();
            return;
        }
        errorRetryCount = 0;
        stallRecoveryCount = 0;
        resumePlaybackIfReady();
    }

    public void onHostConfigurationChanged() {
        applyCenterCropTransform();
        if (shouldBePlaying()) {
            resumePlaybackIfReady();
        }
    }

    private boolean shouldBePlaying() {
        return !pausedByHost
                && windowVisible
                && isAttachedToWindow()
                && getVisibility() == View.VISIBLE
                && videoPath != null
                && !videoPath.isEmpty();
    }

    private void cancelScheduledChecks() {
        if (fallbackCheckRunnable != null) {
            removeCallbacks(fallbackCheckRunnable);
            fallbackCheckRunnable = null;
        }
        if (watchdogCheckRunnable != null) {
            removeCallbacks(watchdogCheckRunnable);
            watchdogCheckRunnable = null;
        }
        if (retryRunnable != null) {
            removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
    }

    private void pausePlaybackOnly() {
        cancelScheduledChecks();
        if (mediaPlayer != null && playerPrepared) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
            } catch (Throwable ignored) {}
        }
    }

    public void resumePlaybackIfReady() {
        if (!shouldBePlaying()) {
            return;
        }
        if (Boolean.FALSE.equals(FILE_EXISTS_CACHE.get(videoPath))) {
            this.videoPath = "";
            releasePlayer();
            setVisibility(View.GONE);
            return;
        }
        if (mediaPlayer == null || renderSurface == null || !renderSurface.isValid()) {
            if (isAvailable() && getSurfaceTexture() != null) {
                surfaceReady = true;
                startPlayer(getSurfaceTexture());
            }
        } else if (playerPrepared) {
            try {
                if (!mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
                if (firstFrameRendered && getAlpha() < 1f) {
                    setAlpha(1f);
                }
                scheduleStartFallbackCheck(playerGeneration);
            } catch (Throwable ignored) {}
        }
    }

    private void scheduleStartFallbackCheck(final int gen) {
        cancelScheduledChecks();
        fallbackCheckRunnable = () -> {
            fallbackCheckRunnable = null;
            if (gen != playerGeneration || mediaPlayer == null || !playerPrepared || !shouldBePlaying()) {
                return;
            }
            try {
                if (!mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
                if (firstFrameRendered && mediaPlayer.isPlaying() && getAlpha() < 1f) {
                    setAlpha(1f);
                }
            } catch (Throwable ignored) {}
        };
        watchdogCheckRunnable = () -> {
            watchdogCheckRunnable = null;
            if (gen != playerGeneration || mediaPlayer == null || !playerPrepared || !shouldBePlaying()) {
                return;
            }
            try {
                if (!firstFrameRendered || renderSurface == null || !renderSurface.isValid()) {
                    if (stallRecoveryCount < 1 && isAvailable() && getSurfaceTexture() != null) {
                        stallRecoveryCount++;
                        startPlayer(getSurfaceTexture());
                    }
                    return;
                }
                if (!mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
                if (mediaPlayer.isPlaying() && getAlpha() < 1f) {
                    setAlpha(1f);
                }
            } catch (Throwable ignored) {}
        };
        postDelayed(fallbackCheckRunnable, 160L);
        postDelayed(watchdogCheckRunnable, 900L);
    }

    public void releasePlayer() {
        playerGeneration++;
        playerPrepared = false;
        firstFrameRendered = false;
        cancelScheduledChecks();
        final MediaPlayer mp = mediaPlayer;
        mediaPlayer = null;
        if (mp != null) {
            try {
                mp.setOnPreparedListener(null);
                mp.setOnErrorListener(null);
                mp.setOnInfoListener(null);
                mp.setOnVideoSizeChangedListener(null);
            } catch (Throwable ignored) {}
            try {
                mp.setSurface(null);
            } catch (Throwable ignored) {}
        }
        Surface s = renderSurface;
        renderSurface = null;
        if (s != null) {
            try {
                s.release();
            } catch (Throwable ignored) {}
        }
        if (mp != null) {
            RELEASE_QUEUE.postRunnable(() -> {
                try {
                    mp.reset();
                } catch (Throwable ignored) {}
                try {
                    mp.release();
                } catch (Throwable ignored) {}
            });
        }
    }

    private void startPlayer(SurfaceTexture texture) {
        releasePlayer();
        if (texture == null || !shouldBePlaying()) {
            return;
        }
        if (Boolean.FALSE.equals(FILE_EXISTS_CACHE.get(videoPath))) {
            return;
        }
        final int gen = playerGeneration;
        try {
            // Keep underlying poster crisp during ~100ms codec warmup without triggering HWUI skip-draw (0.01f > 0).
            setAlpha(0.01f);
            firstFrameRendered = false;
            renderSurface = new Surface(texture);
            final MediaPlayer mp = new MediaPlayer();
            mp.setSurface(renderSurface);
            mp.setDataSource(videoPath);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnVideoSizeChangedListener((player, width, height) -> {
                if (mediaPlayer != player) return;
                videoWidth = width;
                videoHeight = height;
                applyCenterCropTransform();
            });
            mp.setOnInfoListener((player, what, extra) -> {
                if (mediaPlayer == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    firstFrameRendered = true;
                    if (shouldBePlaying() && getAlpha() < 1f) {
                        setAlpha(1f);
                    }
                    applyCenterCropTransform();
                }
                return false;
            });
            mp.setOnPreparedListener(player -> {
                if (mediaPlayer != player) return;
                playerPrepared = true;
                videoWidth = player.getVideoWidth();
                videoHeight = player.getVideoHeight();
                applyCenterCropTransform();
                if (shouldBePlaying()) {
                    try {
                        player.start();
                        scheduleStartFallbackCheck(gen);
                    } catch (Throwable ignored) {}
                }
            });
            mp.setOnErrorListener((player, what, extra) -> {
                if (mediaPlayer != player) return true;
                releasePlayer();
                setAlpha(0.01f);
                if (shouldBePlaying() && errorRetryCount < 2) {
                    errorRetryCount++;
                    final int retryGen = playerGeneration;
                    retryRunnable = () -> {
                        retryRunnable = null;
                        if (retryGen == playerGeneration && shouldBePlaying()
                                && isAvailable() && getSurfaceTexture() != null && mediaPlayer == null) {
                            startPlayer(getSurfaceTexture());
                        }
                    };
                    postDelayed(retryRunnable, 250L * errorRetryCount);
                } else {
                    setVisibility(View.GONE);
                }
                return true;
            });
            mediaPlayer = mp;
            mp.prepareAsync();
        } catch (Throwable ignored) {
            releasePlayer();
            setAlpha(0.01f);
            setVisibility(View.GONE);
        }
    }

    private void applyCenterCropTransform() {
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        float viewRatio = (float) vw / (float) vh;
        float videoRatio = (float) videoWidth / (float) videoHeight;
        float scaleX = 1f;
        float scaleY = 1f;
        if (videoRatio > viewRatio) {
            scaleX = videoRatio / viewRatio;
        } else {
            scaleY = viewRatio / videoRatio;
        }
        Matrix m = new Matrix();
        m.setScale(scaleX, scaleY, vw / 2f, vh / 2f);
        setTransform(m);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyCenterCropTransform();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        windowVisible = getWindowVisibility() == View.VISIBLE;
        if (isAvailable() && getSurfaceTexture() != null) {
            surfaceReady = true;
            if (shouldBePlaying()) {
                resumePlaybackIfReady();
            }
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = visibility == View.VISIBLE;
        if (!windowVisible) {
            pausePlaybackOnly();
        } else {
            errorRetryCount = 0;
            stallRecoveryCount = 0;
            resumePlaybackIfReady();
        }
    }

    @Override
    public void setVisibility(int visibility) {
        int prev = getVisibility();
        super.setVisibility(visibility);
        if (visibility != View.VISIBLE) {
            pausePlaybackOnly();
        } else if (prev != View.VISIBLE || !pausedByHost) {
            resumePlaybackIfReady();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        surfaceReady = false;
        releasePlayer();
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        surfaceReady = true;
        if (shouldBePlaying()) {
            startPlayer(surface);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        applyCenterCropTransform();
        if (shouldBePlaying() && mediaPlayer == null) {
            startPlayer(surface);
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        surfaceReady = false;
        releasePlayer();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        firstFrameRendered = true;
        if (getAlpha() < 1f && mediaPlayer != null && playerPrepared && shouldBePlaying()) {
            setAlpha(1f);
        }
    }

    public static boolean hasCachedFile(String path) {
        return path != null && !path.isEmpty() && Boolean.TRUE.equals(FILE_EXISTS_CACHE.get(path));
    }

    public static BitmapDrawable getCachedImageDrawable(Context ctx, String path) {
        Context safeCtx = ctx != null ? ctx : ApplicationLoader.applicationContext;
        if (safeCtx == null || path == null || path.isEmpty()) {
            return null;
        }
        Bitmap bmp = WALLPAPER_BITMAP_CACHE.get(path);
        if (bmp != null && !bmp.isRecycled()) {
            return new BitmapDrawable(safeCtx.getResources(), bmp);
        }
        return null;
    }

    public static String getCachedVideoPosterPath(String videoPath) {
        if (videoPath == null || videoPath.isEmpty()) {
            return "";
        }
        String cached = VIDEO_POSTER_PATH_CACHE.get(videoPath);
        return cached != null ? cached : "";
    }

    public static void resolveWallpaperAsync(Context ctx, String videoPath, String imagePath, WallpaperResolveCallback callback) {
        if (callback == null) {
            return;
        }
        final Context appCtx = ctx != null ? ctx.getApplicationContext() : ApplicationLoader.applicationContext;
        final String cleanVid = videoPath == null ? "" : videoPath.trim();
        final String cleanImg = imagePath == null ? "" : imagePath.trim();
        Utilities.globalQueue.postRunnable(() -> {
            boolean hasVid = false;
            BitmapDrawable drawable = null;
            String loadedPath = null;
            if (!cleanVid.isEmpty()) {
                hasVid = new File(cleanVid).isFile();
                FILE_EXISTS_CACHE.put(cleanVid, hasVid);
                if (hasVid) {
                    String posterPath = getOrCreateVideoPosterPath(cleanVid);
                    if (!posterPath.isEmpty()) {
                        drawable = loadScaledImageDrawable(appCtx, posterPath);
                        loadedPath = posterPath;
                    }
                }
            }
            if (!hasVid && !cleanImg.isEmpty()) {
                boolean hasImg = new File(cleanImg).isFile();
                FILE_EXISTS_CACHE.put(cleanImg, hasImg);
                if (hasImg) {
                    drawable = loadScaledImageDrawable(appCtx, cleanImg);
                    if (drawable != null) {
                        loadedPath = cleanImg;
                    }
                }
            }
            final boolean finalHasVid = hasVid;
            final BitmapDrawable finalDrawable = drawable;
            final String finalLoadedPath = loadedPath;
            AndroidUtilities.runOnUIThread(() ->
                    callback.onResolved(finalHasVid, finalHasVid ? cleanVid : "", finalDrawable, finalLoadedPath));
        });
    }

    private static void invalidatePathCaches(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        FILE_EXISTS_CACHE.remove(path);
        WALLPAPER_BITMAP_CACHE.remove(path);
        String poster = VIDEO_POSTER_PATH_CACHE.remove(path);
        if (poster != null && !poster.isEmpty()) {
            FILE_EXISTS_CACHE.remove(poster);
            WALLPAPER_BITMAP_CACHE.remove(poster);
        }
        String fallbackPoster = path + ".poster.jpg";
        FILE_EXISTS_CACHE.remove(fallbackPoster);
        WALLPAPER_BITMAP_CACHE.remove(fallbackPoster);
    }

    public static File getWallpapersDir() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "colgram_wallpapers");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** Copies a gallery photo into persistent local storage and records it for dialogId (0 = global). */
    public static String copyImageAndSaveWallpaper(File src, long dialogId) {
        if (src == null || !src.isFile() || src.length() <= 0) {
            return "";
        }
        File dst = new File(getWallpapersDir(), "img_" + dialogId + "_" + System.currentTimeMillis() + ".jpg");
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
        } catch (Throwable ignored) {
            return "";
        }
        if (dst.length() <= 0) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            return "";
        }
        String prevImg = ColgramConfig.getExactChatImageWallpaper(dialogId);
        String prevVid = ColgramConfig.getExactChatVideoWallpaper(dialogId);
        String dstPath = dst.getAbsolutePath();
        ColgramConfig.setChatImageWallpaper(dialogId, dstPath);
        ColgramConfig.setChatVideoWallpaper(dialogId, "");
        FILE_EXISTS_CACHE.put(dstPath, Boolean.TRUE);
        invalidatePathCaches(prevImg);
        invalidatePathCaches(prevVid);
        Utilities.globalQueue.postRunnable(() -> {
            cleanupPreviousWallpaper(prevImg, dstPath);
            cleanupPreviousWallpaper(prevVid, "");
        });
        return dstPath;
    }

    /** Clears saved custom video and/or image wallpaper for dialogId (0 = global) and deletes orphaned files. */
    public static void clearSavedWallpaper(long dialogId, boolean clearVideo, boolean clearImage) {
        final String prevVid = clearVideo ? ColgramConfig.getExactChatVideoWallpaper(dialogId) : "";
        final String prevImg = clearImage ? ColgramConfig.getExactChatImageWallpaper(dialogId) : "";
        if (clearVideo) {
            ColgramConfig.setChatVideoWallpaper(dialogId, "");
            invalidatePathCaches(prevVid);
        }
        if (clearImage) {
            ColgramConfig.setChatImageWallpaper(dialogId, "");
            invalidatePathCaches(prevImg);
        }
        if ((prevVid != null && !prevVid.isEmpty()) || (prevImg != null && !prevImg.isEmpty())) {
            Utilities.globalQueue.postRunnable(() -> {
                if (prevVid != null && !prevVid.isEmpty()) {
                    cleanupPreviousWallpaper(prevVid, "");
                }
                if (prevImg != null && !prevImg.isEmpty()) {
                    cleanupPreviousWallpaper(prevImg, "");
                }
            });
        }
    }

    /** Copies a local video file path directly into persistent wallpaper storage. */
    public static void copyFileAndApplyVideoWallpaper(Context ctx, File src, long dialogId, Runnable onApplied) {
        if (ctx == null || src == null || !src.isFile()) return;
        final Context app = ctx.getApplicationContext();
        Utilities.globalQueue.postRunnable(() -> {
            File dst = new File(getWallpapersDir(), "vid_" + dialogId + "_" + System.currentTimeMillis() + ".mp4");
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[32768];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
                out.flush();
            } catch (Throwable e) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                AndroidUtilities.runOnUIThread(() ->
                        Toast.makeText(app, "Не удалось загрузить видео-обои", Toast.LENGTH_SHORT).show());
                return;
            }
            finishApplyVideoWallpaper(app, dst, dialogId, onApplied);
        });
    }

    /** Copies a SAF content:// video URI into persistent storage and activates looping video wallpaper. */
    public static void copyUriAndApplyVideoWallpaper(Context ctx, Uri uri, long dialogId, Runnable onApplied) {
        if (ctx == null || uri == null) return;
        final Context app = ctx.getApplicationContext();
        Utilities.globalQueue.postRunnable(() -> {
            File dst = new File(getWallpapersDir(), "vid_" + dialogId + "_" + System.currentTimeMillis() + ".mp4");
            try (InputStream in = app.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(dst)) {
                if (in == null) {
                    throw new IllegalStateException("Empty stream");
                }
                byte[] buf = new byte[32768];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
                out.flush();
            } catch (Throwable e) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                AndroidUtilities.runOnUIThread(() ->
                        Toast.makeText(app, "Не удалось скопировать видео-обои", Toast.LENGTH_SHORT).show());
                return;
            }
            finishApplyVideoWallpaper(app, dst, dialogId, onApplied);
        });
    }

    private static void finishApplyVideoWallpaper(Context app, File dst, long dialogId, Runnable onApplied) {
        if (!dst.isFile() || dst.length() <= 0) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            AndroidUtilities.runOnUIThread(() ->
                    Toast.makeText(app, "Выбранный видеофайл пуст", Toast.LENGTH_SHORT).show());
            return;
        }
        String prevVid = ColgramConfig.getExactChatVideoWallpaper(dialogId);
        String prevImg = ColgramConfig.getExactChatImageWallpaper(dialogId);
        String dstPath = dst.getAbsolutePath();
        // Extract first-frame JPEG poster so re-entering the chat NEVER flashes solid gray while preparing
        String posterPath = getOrCreateVideoPosterPath(dstPath);
        if (!posterPath.isEmpty()) {
            loadScaledImageDrawable(app, posterPath);
        }
        ColgramConfig.setChatVideoWallpaper(dialogId, dstPath);
        ColgramConfig.setChatImageWallpaper(dialogId, "");
        FILE_EXISTS_CACHE.put(dstPath, Boolean.TRUE);
        invalidatePathCaches(prevVid);
        invalidatePathCaches(prevImg);
        cleanupPreviousWallpaper(prevVid, dstPath);
        cleanupPreviousWallpaper(prevImg, "");
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didSetNewWallpapper);
            Toast.makeText(app, "Видео-обои применены!", Toast.LENGTH_SHORT).show();
            if (onApplied != null) {
                onApplied.run();
            }
        });
    }

    /** Returns (creating once if absent) a crisp JPEG first-frame poster next to a saved video wallpaper. */
    public static String getOrCreateVideoPosterPath(String videoPath) {
        if (videoPath == null || videoPath.isEmpty()) return "";
        String cached = VIDEO_POSTER_PATH_CACHE.get(videoPath);
        if (cached != null && !cached.isEmpty() && Boolean.TRUE.equals(FILE_EXISTS_CACHE.get(cached))) {
            return cached;
        }
        File vf = new File(videoPath);
        if (!vf.isFile() || vf.length() <= 0) {
            FILE_EXISTS_CACHE.put(videoPath, Boolean.FALSE);
            return "";
        }
        FILE_EXISTS_CACHE.put(videoPath, Boolean.TRUE);
        File poster = new File(videoPath + ".poster.jpg");
        if (poster.isFile() && poster.length() > 0) {
            String abs = poster.getAbsolutePath();
            FILE_EXISTS_CACHE.put(abs, Boolean.TRUE);
            VIDEO_POSTER_PATH_CACHE.put(videoPath, abs);
            return abs;
        }
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(videoPath);
            Bitmap frame = mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                frame = mmr.getFrameAtTime(500_000L);
            }
            if (frame != null) {
                try (FileOutputStream fos = new FileOutputStream(poster)) {
                    frame.compress(Bitmap.CompressFormat.JPEG, 86, fos);
                    fos.flush();
                }
                frame.recycle();
                if (poster.isFile() && poster.length() > 0) {
                    String abs = poster.getAbsolutePath();
                    FILE_EXISTS_CACHE.put(abs, Boolean.TRUE);
                    VIDEO_POSTER_PATH_CACHE.put(videoPath, abs);
                    return abs;
                }
            }
        } catch (Throwable ignored) {
            //noinspection ResultOfMethodCallIgnored
            poster.delete();
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
        return "";
    }

    private static void cleanupPreviousWallpaper(String prevPath, String keepPath) {
        if (prevPath == null || prevPath.isEmpty() || prevPath.equals(keepPath)) return;
        try {
            File f = new File(prevPath);
            if (f.isFile() && f.getParentFile() != null && "colgram_wallpapers".equals(f.getParentFile().getName())) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                File poster = new File(prevPath + ".poster.jpg");
                if (poster.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    poster.delete();
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Loads a memory-safe BitmapDrawable for a saved gallery wallpaper file. */
    public static BitmapDrawable loadScaledImageDrawable(Context ctx, String path) {
        Context safeCtx = ctx != null ? ctx : ApplicationLoader.applicationContext;
        if (safeCtx == null || path == null || path.isEmpty()) return null;
        Bitmap cached = WALLPAPER_BITMAP_CACHE.get(path);
        if (cached != null && !cached.isRecycled()) {
            return new BitmapDrawable(safeCtx.getResources(), cached);
        }
        File f = new File(path);
        if (!f.isFile() || f.length() <= 0) {
            FILE_EXISTS_CACHE.put(path, Boolean.FALSE);
            return null;
        }
        FILE_EXISTS_CACHE.put(path, Boolean.TRUE);
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int maxDim = Math.max(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
            if (maxDim < 1080) maxDim = 1440;
            int sample = 1;
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                    sample *= 2;
                }
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(path, opts);
            if (bmp == null) return null;
            try {
                androidx.exifinterface.media.ExifInterface exif = new androidx.exifinterface.media.ExifInterface(path);
                int orient = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
                int deg = 0;
                if (orient == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
                else if (orient == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
                else if (orient == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
                if (deg != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(deg);
                    Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                    if (rotated != bmp) {
                        bmp.recycle();
                        bmp = rotated;
                    }
                }
            } catch (Throwable ignored) {}
            WALLPAPER_BITMAP_CACHE.put(path, bmp);
            return new BitmapDrawable(safeCtx.getResources(), bmp);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
