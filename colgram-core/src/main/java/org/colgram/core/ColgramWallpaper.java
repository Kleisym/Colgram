package org.colgram.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/**
 * ColgramWallpaper — the pattern that replaces Telegram's default doodle field.
 *
 * Upstream renders the chats list, the in-chat background, the login shell and the side menu from
 * one screen-sized SVG (res/raw/default_pattern.svg) handed to MotionBackgroundDrawable. That
 * drawable does not tile a small bitmap - for a positive intensity it scales the image to fill the
 * view - so the pattern is generated at the requested size instead of as a repeating tile.
 *
 * The bitmap is drawn in opaque white geometry over transparency. Telegram tints it afterwards
 * with a PorterDuff SRC_IN filter and dims it by the pattern intensity, so whatever colour is
 * chosen here only shapes the geometry; the actual hue comes from setPatternColorFilter at the
 * call site.
 */
public final class ColgramWallpaper {

    private ColgramWallpaper() {}

    /** Grid pitch. The motif alternates per cell and the rows are staggered by half a pitch. */
    private static final int CELL = 96;

    private static final float HEX_RADIUS = 17f;
    private static final float DIAMOND_RADIUS = 13f;
    private static final float DOT_RADIUS = 3.4f;
    private static final float STROKE_WIDTH = 2.2f;

    public static Bitmap create(int width, int height) {
        return create(width, height, Color.WHITE);
    }

    /**
     * @param geometry the colour to draw the motif in. The live background passes white and lets
     *                 Telegram's SRC_IN filter tint it; the wallpaper thumbnail and the theme
     *                 previews draw the bitmap straight onto a canvas, so they need the colour
     *                 already applied.
     */
    public static Bitmap create(int width, int height, int geometry) {
        int w = width > 0 ? width : 720;
        int h = height > 0 ? height : 1280;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(STROKE_WIDTH);
        stroke.setColor(geometry);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(geometry);

        int cols = w / CELL + 2;
        int rows = h / CELL + 2;
        for (int row = 0; row < rows; row++) {
            float stagger = (row % 2 == 0) ? 0f : CELL / 2f;
            for (int col = 0; col < cols; col++) {
                float cx = col * CELL + stagger + CELL / 2f;
                float cy = row * CELL + CELL / 2f;
                // Deterministic rather than random: a fresh RNG per call would make the field
                // jump between the two times this is rendered during a theme change.
                switch ((row * 3 + col * 5) % 4) {
                    case 0:
                        hexagon(canvas, stroke, cx, cy, HEX_RADIUS);
                        break;
                    case 1:
                        diamond(canvas, stroke, cx, cy, DIAMOND_RADIUS);
                        break;
                    case 2:
                        canvas.drawCircle(cx, cy, DOT_RADIUS, fill);
                        break;
                    default:
                        ticks(canvas, stroke, cx, cy);
                        break;
                }
            }
        }
        return bitmap;
    }

    private static void hexagon(Canvas canvas, Paint paint, float cx, float cy, float r) {
        float[] pts = new float[12];
        for (int i = 0; i < 6; i++) {
            double a = Math.PI / 3.0 * i - Math.PI / 6.0;
            pts[i * 2] = cx + (float) (r * Math.cos(a));
            pts[i * 2 + 1] = cy + (float) (r * Math.sin(a));
        }
        for (int i = 0; i < 5; i++) {
            canvas.drawLine(pts[i * 2], pts[i * 2 + 1], pts[i * 2 + 2], pts[i * 2 + 3], paint);
        }
        canvas.drawLine(pts[10], pts[11], pts[0], pts[1], paint);
    }

    private static void diamond(Canvas canvas, Paint paint, float cx, float cy, float r) {
        canvas.drawLine(cx, cy - r, cx + r, cy, paint);
        canvas.drawLine(cx + r, cy, cx, cy + r, paint);
        canvas.drawLine(cx, cy + r, cx - r, cy, paint);
        canvas.drawLine(cx - r, cy, cx, cy - r, paint);
    }

    /** Three short parallel strokes - the "signal" mark, and the lightest of the four motifs. */
    private static void ticks(Canvas canvas, Paint paint, float cx, float cy) {
        for (int i = -1; i <= 1; i++) {
            float ox = cx + i * 7f;
            canvas.drawLine(ox - 4f, cy + 6f, ox + 4f, cy - 6f, paint);
        }
    }
}
