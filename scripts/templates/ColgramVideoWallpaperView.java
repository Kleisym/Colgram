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
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.colgram.core.ColgramConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Lightweight, looping, muted center-crop video wallpaper view and local wallpaper storage
 * helper for Colgram chats. Renders directly inside ChatActivity's background layer with
 * automatic lifecycle pause/resume and zero audio focus interruption.
 */
public final class ColgramVideoWallpaperView extends TextureView implements TextureView.SurfaceTextureListener {

    private String videoPath;
    private MediaPlayer mediaPlayer;
    private Surface renderSurface;
    private int videoWidth;
    private int videoHeight;
    private boolean pausedByHost;
    private boolean surfaceReady;
    private boolean playerPrepared = false;
    private int playerGeneration = 0;

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
        if (clean.isEmpty() || !new File(clean).isFile()) {
            this.videoPath = "";
            releasePlayer();
            setVisibility(View.GONE);
            return;
        }
        if (clean.equals(this.videoPath) && mediaPlayer != null) {
            setVisibility(View.VISIBLE);
            // CRITICAL: NEVER call start() while MediaPlayer is still in Preparing state!
            // Calling start() before onPrepared() fires Android MEDIA_ERROR (-38, 0) and hides the video!
            if (playerPrepared && !pausedByHost) {
                try {
                    if (!mediaPlayer.isPlaying()) {
                        mediaPlayer.start();
                    }
                    scheduleStartFallbackCheck(playerGeneration);
                } catch (Throwable ignored) {}
            }
            return;
        }
        this.videoPath = clean;
        setVisibility(View.VISIBLE);
        if (isAvailable() && getSurfaceTexture() != null) {
            surfaceReady = true;
            startPlayer(getSurfaceTexture());
        }
    }

    public void setHostPaused(boolean paused) {
        pausedByHost = paused;
        if (paused) {
            if (mediaPlayer != null && playerPrepared) {
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.pause();
                    }
                } catch (Throwable ignored) {}
            }
            return;
        }
        resumePlaybackIfReady();
    }

    public void resumePlaybackIfReady() {
        if (pausedByHost || videoPath == null || videoPath.isEmpty() || getVisibility() != View.VISIBLE) {
            return;
        }
        if (mediaPlayer == null) {
            if (isAvailable() && getSurfaceTexture() != null) {
                surfaceReady = true;
                startPlayer(getSurfaceTexture());
            }
        } else if (playerPrepared) {
            try {
                if (!mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
                scheduleStartFallbackCheck(playerGeneration);
            } catch (Throwable ignored) {}
        }
    }

    private void scheduleStartFallbackCheck(final int gen) {
        final Runnable check = () -> {
            if (gen != playerGeneration || mediaPlayer == null || !playerPrepared
                    || pausedByHost || getVisibility() != View.VISIBLE || !isAttachedToWindow()) {
                return;
            }
            try {
                if (!mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
                if (mediaPlayer.isPlaying() && getAlpha() < 1f) {
                    setAlpha(1f);
                }
            } catch (Throwable ignored) {}
        };
        postDelayed(check, 120L);
        postDelayed(check, 350L);
    }

    public void releasePlayer() {
        playerGeneration++;
        playerPrepared = false;
        MediaPlayer mp = mediaPlayer;
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
            try {
                if (mp.isPlaying()) {
                    mp.stop();
                }
            } catch (Throwable ignored) {}
            try {
                mp.release();
            } catch (Throwable ignored) {}
        }
        Surface s = renderSurface;
        renderSurface = null;
        if (s != null) {
            try {
                s.release();
            } catch (Throwable ignored) {}
        }
    }

    private void startPlayer(SurfaceTexture texture) {
        releasePlayer();
        if (texture == null || videoPath == null || videoPath.isEmpty() || !new File(videoPath).isFile()) {
            return;
        }
        final int gen = playerGeneration;
        try {
            // Keep underlying poster crisp during ~100ms codec warmup without triggering HWUI skip-draw (0.01f > 0).
            setAlpha(0.01f);
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
                    if (getAlpha() < 1f) {
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
                if (!pausedByHost && getVisibility() == View.VISIBLE) {
                    try {
                        player.start();
                        scheduleStartFallbackCheck(gen);
                    } catch (Throwable ignored) {}
                }
            });
            mp.setOnErrorListener((player, what, extra) -> {
                if (mediaPlayer != player) return true;
                releasePlayer();
                setAlpha(1f);
                setVisibility(View.GONE);
                return true;
            });
            mediaPlayer = mp;
            mp.prepareAsync();
        } catch (Throwable ignored) {
            releasePlayer();
            setAlpha(1f);
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
        if (isAvailable() && getSurfaceTexture() != null) {
            surfaceReady = true;
            if (videoPath != null && !videoPath.isEmpty() && getVisibility() == View.VISIBLE
                    && (mediaPlayer == null || renderSurface == null || !renderSurface.isValid())) {
                startPlayer(getSurfaceTexture());
            } else {
                resumePlaybackIfReady();
            }
        }
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == View.VISIBLE && !pausedByHost) {
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
        if (videoPath != null && !videoPath.isEmpty() && getVisibility() == View.VISIBLE) {
            startPlayer(surface);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        applyCenterCropTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        surfaceReady = false;
        releasePlayer();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        if (getAlpha() < 1f && mediaPlayer != null && playerPrepared && !pausedByHost) {
            setAlpha(1f);
        }
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
        ColgramConfig.setChatImageWallpaper(dialogId, dst.getAbsolutePath());
        ColgramConfig.setChatVideoWallpaper(dialogId, "");
        cleanupPreviousWallpaper(prevImg, dst.getAbsolutePath());
        cleanupPreviousWallpaper(prevVid, "");
        return dst.getAbsolutePath();
    }

    /** Clears saved custom video and/or image wallpaper for dialogId (0 = global) and deletes orphaned files. */
    public static void clearSavedWallpaper(long dialogId, boolean clearVideo, boolean clearImage) {
        if (clearVideo) {
            String prevVid = ColgramConfig.getExactChatVideoWallpaper(dialogId);
            ColgramConfig.setChatVideoWallpaper(dialogId, "");
            cleanupPreviousWallpaper(prevVid, "");
        }
        if (clearImage) {
            String prevImg = ColgramConfig.getExactChatImageWallpaper(dialogId);
            ColgramConfig.setChatImageWallpaper(dialogId, "");
            cleanupPreviousWallpaper(prevImg, "");
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
        // Extract first-frame JPEG poster so re-entering the chat NEVER flashes solid gray while preparing
        getOrCreateVideoPosterPath(dst.getAbsolutePath());
        ColgramConfig.setChatVideoWallpaper(dialogId, dst.getAbsolutePath());
        ColgramConfig.setChatImageWallpaper(dialogId, "");
        cleanupPreviousWallpaper(prevVid, dst.getAbsolutePath());
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
        File vf = new File(videoPath);
        if (!vf.isFile() || vf.length() <= 0) return "";
        File poster = new File(videoPath + ".poster.jpg");
        if (poster.isFile() && poster.length() > 0) {
            return poster.getAbsolutePath();
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
                    return poster.getAbsolutePath();
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
        File f = new File(path);
        if (!f.isFile() || f.length() <= 0) return null;
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
            return new BitmapDrawable(safeCtx.getResources(), bmp);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
