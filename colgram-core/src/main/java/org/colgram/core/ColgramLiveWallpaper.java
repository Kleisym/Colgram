package org.colgram.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;

/**
 * Calm, architectural, zero-allocation live wave renderer for Colgram chats and main menu.
 * Uses cohesive monochromatic-accent ribbons flowing gently in one unified direction —
 * crisp, understated, readable, and completely free of visual noise or clashing gradients.
 */
public final class ColgramLiveWallpaper {

    public static final int STYLE_OFF = 0;
    public static final int STYLE_AURORA = 1;
    public static final int STYLE_CYBER_GRID = 2;
    public static final int STYLE_CONSTELLATION = 3;
    public static final int STYLE_COLA_FIZZ = 4;

    private static final float[] STAR_X = {
            0.12f, 0.28f, 0.45f, 0.63f, 0.81f,
            0.20f, 0.39f, 0.56f, 0.74f, 0.89f
    };
    private static final float[] STAR_Y = {
            0.18f, 0.32f, 0.22f, 0.36f, 0.26f,
            0.58f, 0.70f, 0.62f, 0.74f, 0.64f
    };
    private static final float[] FIZZ_X = {
            0.14f, 0.29f, 0.43f, 0.58f, 0.73f, 0.86f, 0.36f
    };
    private static final float[] FIZZ_SPEED = {
            0.06f, 0.08f, 0.055f, 0.075f, 0.065f, 0.085f, 0.07f
    };
    private static final float[] FIZZ_RADIUS = {
            1.8f, 2.5f, 1.6f, 2.2f, 2.8f, 1.9f, 2.1f
    };

    private static final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint glowStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path sharedPath = new Path();
    private static final Path crestPath = new Path();
    private static final float[] starPx = new float[STAR_X.length];
    private static final float[] starPy = new float[STAR_X.length];

    private static final class TintSnapshot {
        final int baseTint;
        final boolean isDark;
        final boolean isCyber;
        final int deepTint;
        final int crestTint;

        TintSnapshot(int baseTint, boolean isDark, boolean isCyber, int deepTint, int crestTint) {
            this.baseTint = baseTint;
            this.isDark = isDark;
            this.isCyber = isCyber;
            this.deepTint = deepTint;
            this.crestTint = crestTint;
        }
    }

    private static volatile TintSnapshot cachedTintSnapshot;

    private static final float[] MENU_BASE_FRACS = {0.69f, 0.81f};
    private static final float[] CHAT_BASE_FRACS = {0.67f, 0.80f};
    private static final float[] MENU_AMPS = {0.026f, 0.022f};
    private static final float[] CHAT_AMPS = {0.032f, 0.026f};
    private static final float[] WAVE_SPEEDS = {0.22f, 0.30f};
    private static final double[] WAVE_FREQS = {Math.PI * 1.35, Math.PI * 1.65};
    private static final float[] WAVE_PHASE_OFFSETS = {0.0f, 1.9f};
    private static final float[] COLA_MENU_FRACS = {0.69f, 0.81f};
    private static final float[] COLA_CHAT_FRACS = {0.67f, 0.79f};

    // Separate shader caches for Menu vs Chat so dual-pane/tablet layouts never reallocate shaders per frame
    private static final int[] cachedW = {-1, -1};
    private static final int[] cachedH = {-1, -1};
    private static final int[] cachedAccent = {0, 0};
    private static final boolean[] cachedDark = {false, false};
    private static final boolean[] cachedCyber = {false, false};
    private static final LinearGradient[][] waveShaders = new LinearGradient[2][2];

    private static final int[] cachedColaW = {-1, -1};
    private static final int[] cachedColaH = {-1, -1};
    private static final boolean[] cachedColaDark = {false, false};
    private static final LinearGradient[][] colaShaders = new LinearGradient[2][2];

    // 0.5x offscreen Bitmap cache for static chat wallpaper overlays (timeSec == 0f)
    private static final float STATIC_CACHE_SCALE = 0.5f;
    private static final Rect STATIC_DST_RECT = new Rect();
    private static final Paint STATIC_BLIT_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static Bitmap staticChatBitmap;
    private static Canvas staticChatCanvas;
    private static int staticCacheW = -1;
    private static int staticCacheH = -1;
    private static int staticCacheScaledW = -1;
    private static int staticCacheScaledH = -1;
    private static int staticCacheStyle = -1;
    private static int staticCacheTint = 0;
    private static boolean staticCacheDark = false;
    private static boolean staticCacheCyber = false;
    private static float staticCacheDensity = 0f;

    static {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        glowStrokePaint.setStyle(Paint.Style.STROKE);
        glowStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        glowStrokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    private ColgramLiveWallpaper() {}

    public static String styleLabel(int style) {
        switch (style) {
            case STYLE_AURORA:
                return "Живые волны (Aurora)";
            case STYLE_CYBER_GRID:
                return "Кибер-сетка (Wave Grid)";
            case STYLE_CONSTELLATION:
                return "Созвездия (Constellation)";
            case STYLE_COLA_FIZZ:
                return "Кола-волны (Cola Fizz)";
            default:
                return "Выкл (Стандартные обои)";
        }
    }

    /** Renders the selected calm live wave overlay onto a standard chat background. */
    public static void drawOverlay(Canvas canvas, int width, int height, int accentColor, boolean isDark, float timeSec) {
        drawOverlay(canvas, width, height, accentColor, isDark, timeSec, 2.5f);
    }

    /** Density-aware variant for standard chats & tablet empty pane. */
    public static void drawOverlay(Canvas canvas, int width, int height, int accentColor,
                                   boolean isDark, float timeSec, float density) {
        int style = ColgramConfig.getLiveWallpaperStyle();
        if (style == STYLE_OFF || width <= 0 || height <= 0 || canvas == null) {
            if (style == STYLE_OFF) {
                releaseStaticCache();
            }
            return;
        }
        boolean isCyber = isDark && ColgramConfig.isCyberThemeEnabled();
        drawScene(canvas, width, height, style, accentColor, isDark, isCyber, false, timeSec, Math.max(1f, density), true);
    }

    /** Calm, restrained live wave backdrop behind the main chat list (DialogsActivity). */
    public static void drawMainMenuAmbient(Canvas canvas, int width, int height, int accentColor,
                                           boolean isDark, boolean isCyber, float timeSec, float density) {
        int style = ColgramConfig.getLiveWallpaperStyle();
        if (style == STYLE_OFF || width <= 0 || height <= 0 || canvas == null) {
            return;
        }
        drawScene(canvas, width, height, style, accentColor, isDark, isCyber, true, timeSec, Math.max(1f, density), false);
    }

    private static void releaseStaticCache() {
        staticChatCanvas = null;
        if (staticChatBitmap != null) {
            try {
                staticChatBitmap.recycle();
            } catch (Throwable ignored) {}
            staticChatBitmap = null;
        }
        staticCacheW = -1;
        staticCacheH = -1;
        staticCacheScaledW = -1;
        staticCacheScaledH = -1;
        staticCacheStyle = -1;
    }

    private static boolean drawStaticCached(Canvas canvas, int width, int height, int style,
                                            int accentColor, TintSnapshot snap, boolean isDark,
                                            boolean isCyber, float d) {
        try {
            int sw = Math.max(1, Math.round(width * STATIC_CACHE_SCALE));
            int sh = Math.max(1, Math.round(height * STATIC_CACHE_SCALE));
            if (staticChatBitmap == null || staticChatBitmap.isRecycled()
                    || staticCacheScaledW != sw || staticCacheScaledH != sh) {
                releaseStaticCache();
                staticChatBitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                if (staticChatBitmap == null) {
                    return false;
                }
                staticChatCanvas = new Canvas(staticChatBitmap);
                staticCacheScaledW = sw;
                staticCacheScaledH = sh;
                staticCacheStyle = -1;
            }
            if (staticCacheW != width || staticCacheH != height || staticCacheStyle != style
                    || staticCacheTint != snap.baseTint || staticCacheDark != isDark
                    || staticCacheCyber != isCyber || staticCacheDensity != d) {
                staticChatBitmap.eraseColor(Color.TRANSPARENT);
                staticChatCanvas.save();
                staticChatCanvas.scale(sw / (float) width, sh / (float) height);
                drawScene(staticChatCanvas, width, height, style, accentColor, isDark, isCyber, false, 0f, d, false);
                staticChatCanvas.restore();
                staticCacheW = width;
                staticCacheH = height;
                staticCacheStyle = style;
                staticCacheTint = snap.baseTint;
                staticCacheDark = isDark;
                staticCacheCyber = isCyber;
                staticCacheDensity = d;
            }
            STATIC_DST_RECT.set(0, 0, width, height);
            canvas.drawBitmap(staticChatBitmap, null, STATIC_DST_RECT, STATIC_BLIT_PAINT);
            return true;
        } catch (Throwable ignored) {
            releaseStaticCache();
            return false;
        }
    }

    private static void drawScene(Canvas canvas, int width, int height, int style,
                                  int accentColor, boolean isDark, boolean isCyber,
                                  boolean menuMode, float timeSec, float d, boolean allowStaticCache) {
        int baseTint = resolveMonochromeBase(accentColor, isDark, isCyber);
        TintSnapshot snap = cachedTintSnapshot;
        if (snap == null || snap.baseTint != baseTint || snap.isDark != isDark || snap.isCyber != isCyber) {
            float[] hsv = new float[3];
            int deepTint = shadeTone(baseTint, isDark ? 0.62f : 0.88f, isDark ? 0.90f : 0.72f, hsv);
            int crestTint = isCyber ? 0xFFFF4D6A : shadeTone(baseTint, isDark ? 1.18f : 0.95f, isDark ? 0.82f : 0.95f, hsv);
            snap = new TintSnapshot(baseTint, isDark, isCyber, deepTint, crestTint);
            cachedTintSnapshot = snap;
        }
        if (allowStaticCache && !menuMode && timeSec == 0f) {
            if (drawStaticCached(canvas, width, height, style, accentColor, snap, isDark, isCyber, d)) {
                return;
            }
        }
        int deepTint = snap.deepTint;
        int crestTint = snap.crestTint;

        switch (style) {
            case STYLE_AURORA:
                drawCalmWaves(canvas, width, height, deepTint, baseTint, crestTint, isDark, isCyber, menuMode, timeSec, d);
                break;
            case STYLE_CYBER_GRID:
                drawCyberGrid(canvas, width, height, baseTint, isDark, menuMode, timeSec, d);
                break;
            case STYLE_CONSTELLATION:
                drawConstellations(canvas, width, height, baseTint, crestTint, isDark, menuMode, timeSec, d);
                break;
            case STYLE_COLA_FIZZ:
                drawColaFizzWaves(canvas, width, height, isDark, menuMode, timeSec, d);
                break;
            default:
                break;
        }
    }

    private static int resolveMonochromeBase(int accentColor, boolean isDark, boolean isCyber) {
        if (isCyber) return 0xFFC81E3A;
        if (accentColor != 0 && Color.alpha(accentColor) > 120) {
            return (accentColor & 0x00FFFFFF) | 0xFF000000;
        }
        return isDark ? 0xFF2B6CB0 : 0xFF3B82F6;
    }

    private static int shadeTone(int color, float valueScale, float satScale, float[] hsv) {
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0f, Math.min(1f, hsv[1] * satScale));
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * valueScale));
        return Color.HSVToColor(hsv);
    }

    private static void ensureWaveShaders(int slot, int w, int h, int deepTint, int baseTint,
                                          boolean isDark, boolean isCyber, boolean menuMode) {
        if (w == cachedW[slot] && h == cachedH[slot] && baseTint == cachedAccent[slot]
                && isDark == cachedDark[slot] && isCyber == cachedCyber[slot]
                && waveShaders[slot][0] != null) {
            return;
        }
        cachedW[slot] = w;
        cachedH[slot] = h;
        cachedAccent[slot] = baseTint;
        cachedDark[slot] = isDark;
        cachedCyber[slot] = isCyber;

        // Understated, crisp monochrome-accent gradient ribbons restricted to the lower canvas
        float m = menuMode ? 0.76f : 1.0f;
        int backTop = Math.round((isDark ? 54 : 38) * m);
        int backMid = Math.round((isDark ? 24 : 16) * m);
        int frontTop = Math.round((isDark ? 70 : 48) * m);
        int frontMid = Math.round((isDark ? 32 : 22) * m);

        float y0 = h * (menuMode ? 0.62f : 0.60f);
        float y1 = h * (menuMode ? 0.74f : 0.73f);

        waveShaders[slot][0] = new LinearGradient(
                0f, y0, 0f, h,
                new int[]{withAlpha(deepTint, backTop), withAlpha(deepTint, backMid), withAlpha(deepTint, 0)},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP);
        waveShaders[slot][1] = new LinearGradient(
                0f, y1, 0f, h,
                new int[]{withAlpha(baseTint, frontTop), withAlpha(baseTint, frontMid), withAlpha(baseTint, 0)},
                new float[]{0f, 0.58f, 1f},
                Shader.TileMode.CLAMP);
    }

    /**
     * Two calm, spacious, razor-clean monochromatic waves gliding steadily in one direction
     * across the lower ~36% of the screen. Zero random dot spam, zero rainbow clutter.
     */
    private static void drawCalmWaves(Canvas canvas, int w, int h, int deepTint, int baseTint, int crestTint,
                                      boolean isDark, boolean isCyber, boolean menuMode, float t, float d) {
        int slot = menuMode ? 1 : 0;
        ensureWaveShaders(slot, w, h, deepTint, baseTint, isDark, isCyber, menuMode);

        float[] baseFracs = menuMode ? MENU_BASE_FRACS : CHAT_BASE_FRACS;
        float[] amps = menuMode ? MENU_AMPS : CHAT_AMPS;

        int steps = 36;
        glowStrokePaint.setStrokeWidth(Math.max(2.4f, 3.0f * d));
        strokePaint.setStrokeWidth(Math.max(1.0f, 1.15f * d));

        for (int band = 0; band < 2; band++) {
            // Unified calm laminar drift (both waves move smoothly in the same direction)
            float phase = t * WAVE_SPEEDS[band] + WAVE_PHASE_OFFSETS[band];
            float baseY = h * baseFracs[band];
            float amp = h * amps[band];
            double freq = WAVE_FREQS[band];

            sharedPath.rewind();
            crestPath.rewind();
            sharedPath.moveTo(0f, h);

            float firstY = baseY
                    + (float) Math.sin(-phase) * amp
                    + (float) Math.sin(-phase * 0.5f + 0.7f) * (amp * 0.18f);
            sharedPath.lineTo(0f, firstY);
            crestPath.moveTo(0f, firstY);

            float prevX = 0f;
            float prevY = firstY;
            for (int i = 1; i <= steps; i++) {
                float frac = (float) i / steps;
                float x = frac * w;
                float y = baseY
                        + (float) Math.sin(frac * freq - phase) * amp
                        + (float) Math.sin(frac * (freq * 0.55) - phase * 0.5f + 0.7f) * (amp * 0.18f);
                float mx = (prevX + x) * 0.5f;
                float my = (prevY + y) * 0.5f;
                sharedPath.quadTo(prevX, prevY, mx, my);
                crestPath.quadTo(prevX, prevY, mx, my);
                prevX = x;
                prevY = y;
            }
            sharedPath.lineTo(w, prevY);
            crestPath.lineTo(w, prevY);
            sharedPath.lineTo(w, h);
            sharedPath.close();

            fillPaint.setShader(waveShaders[slot][band]);
            canvas.drawPath(sharedPath, fillPaint);

            // Subtle soft halo + razor-sharp 1.15dp ridge hairline
            float m = menuMode ? 0.78f : 1.0f;
            int ridgeColor = (band == 0) ? baseTint : crestTint;
            int haloAlpha = Math.round((isDark ? (band == 0 ? 22 : 32) : (band == 0 ? 16 : 24)) * m);
            int coreAlpha = Math.round((isDark ? (band == 0 ? 82 : 128) : (band == 0 ? 64 : 104)) * m);

            glowStrokePaint.setColor(withAlpha(ridgeColor, haloAlpha));
            canvas.drawPath(crestPath, glowStrokePaint);
            strokePaint.setColor(withAlpha(ridgeColor, coreAlpha));
            canvas.drawPath(crestPath, strokePaint);
        }
    }

    private static void ensureColaShaders(int slot, int w, int h, boolean isDark, boolean menuMode) {
        if (w == cachedColaW[slot] && h == cachedColaH[slot] && isDark == cachedColaDark[slot]
                && colaShaders[slot][0] != null) {
            return;
        }
        cachedColaW[slot] = w;
        cachedColaH[slot] = h;
        cachedColaDark[slot] = isDark;
        float m = menuMode ? 0.76f : 1.0f;
        int a0 = Math.round((isDark ? 68 : 48) * m);
        int a1 = Math.round((isDark ? 82 : 58) * m);
        colaShaders[slot][0] = new LinearGradient(0, h * 0.60f, 0, h,
                new int[]{withAlpha(0xFF7F1D1D, a0), withAlpha(0xFF450A0A, a0 / 2), 0x00000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        colaShaders[slot][1] = new LinearGradient(0, h * 0.72f, 0, h,
                new int[]{withAlpha(0xFFB91C1C, a1), withAlpha(0xFF78350F, a1 / 2), 0x00000000},
                new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP);
    }

    private static void drawColaFizzWaves(Canvas canvas, int w, int h, boolean isDark, boolean menuMode, float t, float d) {
        int slot = menuMode ? 1 : 0;
        ensureColaShaders(slot, w, h, isDark, menuMode);
        glowStrokePaint.setStrokeWidth(Math.max(2.4f, 3.0f * d));
        strokePaint.setStrokeWidth(Math.max(1.1f, 1.3f * d));
        float[] baseFracs = menuMode ? COLA_MENU_FRACS : COLA_CHAT_FRACS;
        int steps = 36;

        for (int band = 0; band < 2; band++) {
            float phase = t * (0.28f + band * 0.09f) + band * 1.8f;
            float baseY = h * baseFracs[band];
            float amp = h * (0.028f - band * 0.004f);

            sharedPath.rewind();
            crestPath.rewind();
            sharedPath.moveTo(0f, h);
            float firstY = baseY + (float) Math.sin(-phase) * amp;
            sharedPath.lineTo(0f, firstY);
            crestPath.moveTo(0f, firstY);
            float prevX = 0f;
            float prevY = firstY;
            for (int i = 1; i <= steps; i++) {
                float frac = (float) i / steps;
                float x = frac * w;
                float y = baseY + (float) Math.sin(frac * Math.PI * (1.45 + band * 0.35) - phase) * amp;
                float mx = (prevX + x) * 0.5f;
                float my = (prevY + y) * 0.5f;
                sharedPath.quadTo(prevX, prevY, mx, my);
                crestPath.quadTo(prevX, prevY, mx, my);
                prevX = x;
                prevY = y;
            }
            sharedPath.lineTo(w, prevY);
            crestPath.lineTo(w, prevY);
            sharedPath.lineTo(w, h);
            sharedPath.close();

            fillPaint.setShader(colaShaders[slot][band]);
            canvas.drawPath(sharedPath, fillPaint);

            int foam = band == 0 ? 0xFFFCA5A5 : 0xFFFDE68A;
            glowStrokePaint.setColor(withAlpha(foam, isDark ? 28 : 22));
            canvas.drawPath(crestPath, glowStrokePaint);
            strokePaint.setColor(withAlpha(foam, isDark ? 118 : 92));
            canvas.drawPath(crestPath, strokePaint);
        }

        // Delicate, sparse rising champagne fizz bubbles in the lower half
        int bubbleCount = menuMode ? 4 : FIZZ_X.length;
        for (int i = 0; i < bubbleCount; i++) {
            float progress = (t * FIZZ_SPEED[i] + i * 0.143f) % 1f;
            float fy = 1.0f - progress * 0.48f;
            float fx = FIZZ_X[i] + (float) Math.sin(t * 0.9f + i * 1.4f) * 0.01f;
            float r = FIZZ_RADIUS[i] * d;
            int alpha = Math.round((1f - Math.abs(progress - 0.5f) * 1.8f) * (isDark ? 78 : 58));
            if (alpha <= 6) continue;
            dotPaint.setColor(withAlpha(i % 2 == 0 ? 0xFFFDE68A : 0xFFFFE4E6, alpha));
            canvas.drawCircle(fx * w, fy * h, r, dotPaint);
        }
    }

    private static void drawCyberGrid(Canvas canvas, int w, int h, int c1,
                                      boolean isDark, boolean menuMode, float t, float d) {
        strokePaint.setStrokeWidth(Math.max(1.0f, 0.85f * d));
        int lineAlpha = menuMode ? (isDark ? 28 : 20) : (isDark ? 38 : 26);
        strokePaint.setColor(withAlpha(c1, lineAlpha));

        int cols = menuMode ? 5 : 7;
        int rows = menuMode ? 9 : 12;
        float driftY = ((t * 8f * d) % (h / (float) rows));
        float waveAmp = 3.2f * d;
        for (int r = -1; r <= rows + 1; r++) {
            float y = (r / (float) rows) * h + driftY;
            sharedPath.rewind();
            for (int c = 0; c <= 18; c++) {
                float fx = c / 18f;
                float wave = (float) Math.sin(fx * Math.PI * 1.8 - t * 0.45f + r * 0.35f) * waveAmp;
                if (c == 0) sharedPath.moveTo(0, y + wave);
                else sharedPath.lineTo(fx * w, y + wave);
            }
            canvas.drawPath(sharedPath, strokePaint);
        }
        strokePaint.setColor(withAlpha(c1, Math.max(14, lineAlpha - 12)));
        for (int c = 1; c < cols; c++) {
            float x = (c / (float) cols) * w;
            canvas.drawLine(x, 0, x, h, strokePaint);
        }
    }

    private static void drawConstellations(Canvas canvas, int w, int h, int c1, int c2,
                                           boolean isDark, boolean menuMode, float t, float d) {
        strokePaint.setStrokeWidth(Math.max(1.0f, 0.8f * d));
        int linkAlpha = menuMode ? (isDark ? 32 : 22) : (isDark ? 46 : 32);
        int dotAlpha = menuMode ? (isDark ? 98 : 74) : (isDark ? 132 : 96);

        int count = menuMode ? 7 : STAR_X.length;
        for (int i = 0; i < count; i++) {
            starPx[i] = STAR_X[i] * w + (float) Math.sin(t * 0.28f + i * 1.3f) * (w * 0.022f);
            starPy[i] = STAR_Y[i] * h + (float) Math.cos(t * 0.24f + i * 0.9f) * (h * 0.018f);
        }
        float maxDist = Math.min(w, h) * 0.36f;
        float maxDistSq = maxDist * maxDist;
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                float dx = starPx[i] - starPx[j];
                float dy = starPy[i] - starPy[j];
                float d2 = dx * dx + dy * dy;
                if (d2 < maxDistSq) {
                    float fade = 1f - (float) Math.sqrt(d2) / maxDist;
                    strokePaint.setColor(withAlpha(c1, Math.round(linkAlpha * fade)));
                    canvas.drawLine(starPx[i], starPy[i], starPx[j], starPy[j], strokePaint);
                }
            }
        }
        for (int i = 0; i < count; i++) {
            int col = (i % 2 == 0) ? c1 : c2;
            dotPaint.setColor(withAlpha(col, dotAlpha));
            canvas.drawCircle(starPx[i], starPy[i], Math.max(1.4f, 1.7f * d), dotPaint);
        }
    }

    public static float currentAnimationSeconds() {
        return (SystemClock.uptimeMillis() % 3600_000L) / 1000f;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
