package org.colgram.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * Mobile Vision & Video Frame Compressor:
 * - Downscales oversized photos cleanly to max 768px edge JPEG to cut mmproj encoding latency 3-5x.
 * - Extracts 4 evenly spaced video keyframes and renders them into a labeled 2x2 timestamped storyboard grid,
 *   letting Qwen3.5 mmproj understand an entire video clip in a single fast vision pass!
 */
public final class ColgramAiVisionProcessor {

    public static final class PreparedMedia {
        public final File file;
        public final boolean isVideoGrid;
        public final String summary;

        public PreparedMedia(File file, boolean isVideoGrid, String summary) {
            this.file = file;
            this.isVideoGrid = isVideoGrid;
            this.summary = summary;
        }
    }

    private ColgramAiVisionProcessor() {}

    public static PreparedMedia prepareMediaFile(Context ctx, String sourcePath) {
        if (ctx == null || sourcePath == null || sourcePath.isEmpty()) return null;
        File src = new File(sourcePath);
        if (!src.isFile() || src.length() == 0) return null;

        File cacheDir = new File(ctx.getCacheDir(), "colgram_ai_media");
        if (!cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        }
        cleanupOldFiles(cacheDir);

        String lower = src.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".3gp")) {
            return buildVideoStoryboard(cacheDir, src);
        }
        return normalizeImage(cacheDir, src);
    }

    private static final java.util.concurrent.atomic.AtomicInteger IMG_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(1);

    private static int readExifRotationDegrees(File src) {
        try {
            android.media.ExifInterface exif = new android.media.ExifInterface(src.getAbsolutePath());
            int orientation = exif.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90:
                case android.media.ExifInterface.ORIENTATION_TRANSPOSE:
                    return 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180:
                case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    return 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270:
                case android.media.ExifInterface.ORIENTATION_TRANSVERSE:
                    return 270;
                default:
                    return 0;
            }
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static PreparedMedia normalizeImage(File cacheDir, File src) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (maxDim / sample > 1400) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(src.getAbsolutePath(), opts);
            if (bmp == null) return null;

            int rotDeg = readExifRotationDegrees(src);
            if (rotDeg != 0) {
                try {
                    android.graphics.Matrix matrix = new android.graphics.Matrix();
                    matrix.postRotate(rotDeg);
                    Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
                    if (rotated != null && rotated != bmp) {
                        bmp.recycle();
                        bmp = rotated;
                    }
                } catch (Throwable ignored) {}
            }

            Bitmap scaled = scaleDownTo(bmp, 768);
            if (scaled != bmp) {
                bmp.recycle();
            }
            File out = new File(cacheDir, "img_" + System.currentTimeMillis() + "_" + IMG_SEQ.getAndIncrement() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, fos);
            }
            String desc = "Фото " + scaled.getWidth() + "x" + scaled.getHeight();
            scaled.recycle();
            return new PreparedMedia(out, false, desc);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static PreparedMedia buildVideoStoryboard(File cacheDir, File src) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(src.getAbsolutePath());
            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durMs = 4000L;
            if (durStr != null && !durStr.isEmpty()) {
                durMs = Math.max(1000L, Long.parseLong(durStr));
            }
            final int cellW = 384;
            final int cellH = 288;
            Bitmap grid = Bitmap.createBitmap(cellW * 2, cellH * 2, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(grid);
            canvas.drawColor(Color.BLACK);

            Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelBg.setColor(0xB8000000);
            Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelText.setColor(Color.WHITE);
            labelText.setTextSize(20f);

            int extracted = 0;
            for (int i = 0; i < 4; i++) {
                long tUs = ((durMs * (2 * i + 1)) / 8L) * 1000L;
                Bitmap frame = mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) {
                    frame = mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST);
                }
                if (frame != null) {
                    int col = i % 2;
                    int row = i / 2;
                    int left = col * cellW;
                    int top = row * cellH;
                    Bitmap fit = Bitmap.createScaledBitmap(frame, cellW, cellH, true);
                    canvas.drawBitmap(fit, left, top, null);
                    if (fit != frame) fit.recycle();
                    frame.recycle();

                    long sec = (tUs / 1000000L);
                    String stamp = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
                    canvas.drawRect(left + 8, top + 8, left + 86, top + 36, labelBg);
                    canvas.drawText(stamp, left + 16, top + 29, labelText);
                    extracted++;
                }
            }
            if (extracted == 0) {
                grid.recycle();
                return null;
            }
            File out = new File(cacheDir, "vid_grid_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                grid.compress(Bitmap.CompressFormat.JPEG, 86, fos);
            }
            grid.recycle();
            long totalSec = Math.max(1L, durMs / 1000L);
            return new PreparedMedia(out, true, "Видео (" + totalSec + " сек, 4 ключевых кадра)");
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {}
        }
    }

    private static Bitmap scaleDownTo(Bitmap src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= maxEdge) return src;
        float ratio = maxEdge / (float) max;
        return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * ratio)), Math.max(1, Math.round(h * ratio)), true);
    }

    private static void cleanupOldFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 16) return;
        long cutoff = System.currentTimeMillis() - 3600_000L;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
