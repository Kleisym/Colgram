package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

import java.util.Random;

/**
 * Interactive 60fps Easter Egg playground: Koliti jumping, rolling, and surfing down
 * a sparkling, foaming Coca-Cola fizz slide with bobbing ice cubes, squash-and-stretch
 * physics, splash particles, and tap-to-super-jump combo tricks.
 * Zero per-frame allocations; pauses automatically when hidden or detached.
 */
public final class ColgramColaSlideView extends View {

    private static final int MAX_BUBBLES = 48;
    private static final int MAX_SPLASH = 64;
    private static final int MAX_TRAIL = 10;

    private final float[] bubX = new float[MAX_BUBBLES];
    private final float[] bubY = new float[MAX_BUBBLES];
    private final float[] bubR = new float[MAX_BUBBLES];
    private final float[] bubVy = new float[MAX_BUBBLES];
    private final float[] bubPhase = new float[MAX_BUBBLES];

    private final float[] spX = new float[MAX_SPLASH];
    private final float[] spY = new float[MAX_SPLASH];
    private final float[] spVx = new float[MAX_SPLASH];
    private final float[] spVy = new float[MAX_SPLASH];
    private final float[] spLife = new float[MAX_SPLASH];
    private final float[] spR = new float[MAX_SPLASH];
    private final int[] spCol = new int[MAX_SPLASH];
    private int splashCursor = 0;

    private final float[] trailX = new float[MAX_TRAIL];
    private final float[] trailY = new float[MAX_TRAIL];
    private int trailCount = 0;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint colaBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint colaMidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint foamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iceFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iceStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleHiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint splashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint avatarRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path backPath = new Path();
    private final Path midPath = new Path();
    private final Path foamPath = new Path();
    private final RectF iceRect = new RectF();
    private final RectF hudRect = new RectF();
    private final Matrix avatarMatrix = new Matrix();
    private final Random rnd = new Random();

    private Bitmap kolitiBitmap;
    private BitmapShader kolitiShader;

    private int cachedW = -1;
    private int cachedH = -1;
    private ValueAnimator ticker;
    private long lastTickMs = 0L;
    private float timeSec = 0f;

    // Koliti physics state along normalized slide coordinate u in [0..1]
    private float slideU = 0.04f;
    private float slideSpeed = 0.24f;
    private float jumpOffsetY = 0f;   // negative = up in the air
    private float jumpVelY = 0f;
    private float spinDeg = 0f;
    private float spinVelDeg = 220f;
    private float squashX = 1f;
    private float squashY = 1f;
    private boolean airborne = true;
    private boolean rampLaunched = false;

    private int comboCount = 0;
    private float comboBannerAlpha = 0f;
    private String comboBannerText = "";

    public ColgramColaSlideView(Context context) {
        super(context);
        foamPaint.setStyle(Paint.Style.STROKE);
        foamPaint.setStrokeCap(Paint.Cap.ROUND);
        foamPaint.setStrokeWidth(dp(4.2f));
        foamPaint.setColor(0xE6FFF4DC);

        iceFillPaint.setColor(0x3BFFFFFF);
        iceStrokePaint.setStyle(Paint.Style.STROKE);
        iceStrokePaint.setStrokeWidth(dp(1.2f));
        iceStrokePaint.setColor(0x78FFE8E8);

        bubbleHiPaint.setColor(0xCCFFFFFF);

        avatarRimPaint.setStyle(Paint.Style.STROKE);
        avatarRimPaint.setStrokeWidth(dp(2.4f));
        avatarRimPaint.setColor(0xFFFFD580);

        hudBgPaint.setColor(0x8818080C);
        hudTextPaint.setColor(0xFFFFF4DC);
        hudTextPaint.setTextSize(dp(12.5f));
        hudTextPaint.setTypeface(AndroidUtilities.bold());
        hudTextPaint.setTextAlign(Paint.Align.CENTER);

        try {
            kolitiBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.koliti_avatar);
            if (kolitiBitmap != null) {
                kolitiShader = new BitmapShader(kolitiBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                avatarPaint.setShader(kolitiShader);
            }
        } catch (Throwable ignored) {}

        for (int i = 0; i < MAX_BUBBLES; i++) {
            bubX[i] = rnd.nextFloat();
            bubY[i] = rnd.nextFloat();
            bubR[i] = dp(1.8f + rnd.nextFloat() * 4.4f);
            bubVy[i] = 0.08f + rnd.nextFloat() * 0.20f;
            bubPhase[i] = rnd.nextFloat() * 6.28f;
        }
        // Start with a cheerful opening hop!
        jumpOffsetY = -dp(36);
        jumpVelY = -dp(120);
    }

    /** Bursts extra sparkling Coca-Cola carbonation & foam across the whole slide! */
    public void triggerFizzEruption() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        for (int i = 0; i < 36; i++) {
            float sx = w * (0.12f + rnd.nextFloat() * 0.76f);
            float sy = surfaceY(sx / Math.max(1f, w), h, timeSec);
            emitSplash(sx, sy, (rnd.nextFloat() - 0.5f) * dp(180), -dp(90 + rnd.nextFloat() * 210),
                    i % 3 == 0 ? 0xFFFFF4DC : (i % 2 == 0 ? 0xFFFFB74D : 0xFFFF4D5E));
        }
        performJumpBoost(true);
    }

    private void performJumpBoost(boolean fromEruption) {
        jumpVelY = -dp(fromEruption ? 340f : 310f);
        airborne = true;
        spinVelDeg = 540f + rnd.nextFloat() * 240f;
        squashX = 0.80f;
        squashY = 1.24f;
        slideSpeed = Math.min(0.42f, slideSpeed + 0.05f);
        comboCount++;
        comboBannerAlpha = 1f;
        if (comboCount >= 10) comboBannerText = "КОРОЛЬ КОЛЫ ×" + comboCount + "!";
        else if (comboCount >= 5) comboBannerText = "МЕГА-ГАЗИРОВКА ×" + comboCount + "!";
        else if (comboCount >= 3) comboBannerText = "КОЛА-БУСТ ×" + comboCount + "!";
        else comboBannerText = "ПРЫЖОК ×" + comboCount;

        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            float kx = slideU * w;
            float ky = surfaceY(slideU, h, timeSec) + jumpOffsetY;
            for (int i = 0; i < 18; i++) {
                emitSplash(kx, ky + dp(14),
                        (rnd.nextFloat() - 0.5f) * dp(200),
                        -dp(60 + rnd.nextFloat() * 190),
                        i % 2 == 0 ? 0xFFFFF4DC : 0xFFFFB74D);
            }
        }
    }

    private void emitSplash(float x, float y, float vx, float vy, int color) {
        int idx = splashCursor++ % MAX_SPLASH;
        spX[idx] = x;
        spY[idx] = y;
        spVx[idx] = vx;
        spVy[idx] = vy;
        spLife[idx] = 1f;
        spR[idx] = dp(2.0f + rnd.nextFloat() * 3.8f);
        spCol[idx] = color;
    }

    /** Parametric curved Coca-Cola slide height (top-left down to sweeping launch ramp on right). */
    private float surfaceY(float u, int h, float t) {
        // Sweeping S-slide from 0.30h on the left down to 0.78h, then slight curved ramp kick at right
        float baseSlope = 0.31f + 0.49f * (float) Math.pow(Math.max(0f, Math.min(1f, u)), 0.85);
        float rampLift = u > 0.72f ? -0.14f * (float) Math.pow((u - 0.72f) / 0.28f, 1.8) : 0f;
        float wave = (float) Math.sin(u * 9.5f - t * 4.2f) * 0.032f
                + (float) Math.cos(u * 5.2f - t * 2.6f) * 0.015f;
        return h * (baseSlope + rampLift + wave);
    }

    private void syncTicker(boolean run) {
        if (run && isAttachedToWindow()) {
            if (ticker == null) {
                lastTickMs = SystemClock.uptimeMillis();
                ticker = ValueAnimator.ofFloat(0f, 1f);
                ticker.setDuration(1000L);
                ticker.setRepeatCount(ValueAnimator.INFINITE);
                ticker.setInterpolator(new LinearInterpolator());
                ticker.addUpdateListener(a -> {
                    stepPhysics();
                    invalidate();
                });
                ticker.start();
            } else if (ticker.isPaused()) {
                lastTickMs = SystemClock.uptimeMillis();
                ticker.resume();
            }
        } else if (ticker != null && ticker.isRunning()) {
            ticker.pause();
        }
    }

    private void stepPhysics() {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.045f, Math.max(0.008f, (now - lastTickMs) / 1000f));
        lastTickMs = now;
        timeSec += dt;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Gravity acceleration downhill, smoothly decaying excess boost
        float targetSpeed = 0.23f + (slideU > 0.15f && slideU < 0.72f ? 0.11f : 0.02f);
        slideSpeed += (targetSpeed - slideSpeed) * Math.min(1f, dt * 3.5f);
        slideU += slideSpeed * dt;

        // Auto-launch off the right ramp!
        if (slideU >= 0.78f && !rampLaunched && !airborne) {
            rampLaunched = true;
            airborne = true;
            jumpVelY = -dp(270f);
            spinVelDeg = 520f;
            squashX = 0.84f;
            squashY = 1.20f;
            float rx = slideU * w;
            float ry = surfaceY(slideU, h, timeSec);
            for (int i = 0; i < 14; i++) {
                emitSplash(rx, ry, -dp(30 + rnd.nextFloat() * 110), -dp(80 + rnd.nextFloat() * 160),
                        i % 2 == 0 ? 0xFFFFF4DC : 0xFFFF6B7A);
            }
        }

        // Loop smoothly back to top of slide with an energetic entry hop!
        if (slideU > 1.08f) {
            slideU = -0.04f;
            trailCount = 0;
            rampLaunched = false;
            airborne = true;
            jumpOffsetY = -dp(44f);
            jumpVelY = -dp(95f);
            spinVelDeg = 280f;
            squashX = 0.90f;
            squashY = 1.12f;
        }

        // Vertical jump & landing physics
        if (airborne || jumpOffsetY < 0f) {
            jumpVelY += dp(880f) * dt;
            jumpOffsetY += jumpVelY * dt;
            if (jumpOffsetY >= 0f) {
                jumpOffsetY = 0f;
                if (jumpVelY > dp(110f)) {
                    // Splashdown squash & fizzy spray!
                    squashX = 1.24f;
                    squashY = 0.78f;
                    float lx = slideU * w;
                    float ly = surfaceY(slideU, h, timeSec);
                    for (int i = 0; i < 12; i++) {
                        emitSplash(lx, ly, (rnd.nextFloat() - 0.5f) * dp(150), -dp(55 + rnd.nextFloat() * 140),
                                i % 3 == 0 ? 0xFFFFF4DC : 0xFFFFB74D);
                    }
                    // Playful mini-bounce on hard landing
                    if (jumpVelY > dp(250f)) {
                        jumpVelY = -jumpVelY * 0.28f;
                        airborne = true;
                    } else {
                        jumpVelY = 0f;
                        airborne = false;
                    }
                } else {
                    jumpVelY = 0f;
                    airborne = false;
                }
            }
        }

        // Smooth squash-and-stretch elastic recovery
        squashX += (1f - squashX) * Math.min(1f, dt * 11f);
        squashY += (1f - squashY) * Math.min(1f, dt * 11f);

        // Rolling spin speed matches downhill surf speed when on wave
        float targetSpin = airborne ? spinVelDeg : (260f + slideSpeed * 620f);
        spinVelDeg += (targetSpin - spinVelDeg) * Math.min(1f, dt * 5f);
        spinDeg = (spinDeg + spinVelDeg * dt) % 360f;

        // Emit subtle fizz spray behind Koliti while surfing the wave
        if (!airborne && slideU > 0.02f && slideU < 0.96f && rnd.nextFloat() < 0.45f) {
            float kx = slideU * w;
            float ky = surfaceY(slideU, h, timeSec);
            emitSplash(kx - dp(10), ky + dp(2),
                    -dp(40 + rnd.nextFloat() * 65),
                    -dp(25 + rnd.nextFloat() * 70),
                    rnd.nextBoolean() ? 0xFFFFF4DC : 0xFFFF9E40);
        }

        // Record motion trail
        float curKx = slideU * w;
        float curKy = surfaceY(slideU, h, timeSec) + jumpOffsetY - dp(20);
        for (int i = MAX_TRAIL - 1; i > 0; i--) {
            trailX[i] = trailX[i - 1];
            trailY[i] = trailY[i - 1];
        }
        trailX[0] = curKx;
        trailY[0] = curKy;
        if (trailCount < MAX_TRAIL) trailCount++;

        // Update rising carbonation bubbles
        for (int i = 0; i < MAX_BUBBLES; i++) {
            bubY[i] -= bubVy[i] * dt;
            if (bubY[i] < -0.06f) {
                bubY[i] = 1.04f;
                bubX[i] = rnd.nextFloat();
            }
        }

        // Update splash particles
        for (int i = 0; i < MAX_SPLASH; i++) {
            if (spLife[i] <= 0f) continue;
            spLife[i] -= dt * 1.45f;
            spVy[i] += dp(420f) * dt;
            spX[i] += spVx[i] * dt;
            spY[i] += spVy[i] * dt;
        }

        if (comboBannerAlpha > 0f) {
            comboBannerAlpha = Math.max(0f, comboBannerAlpha - dt * 0.55f);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            performJumpBoost(false);
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncTicker(getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncTicker(visibility == VISIBLE && getVisibility() == VISIBLE);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        syncTicker(visibility == VISIBLE && getWindowVisibility() == VISIBLE);
    }

    private void ensureShaders(int w, int h) {
        if (w == cachedW && h == cachedH) return;
        cachedW = w;
        cachedH = h;
        bgPaint.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0xFF1A070C, 0xFF2E0B14, 0xFF12060A},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        glowPaint.setShader(new RadialGradient(w * 0.25f, h * 0.22f, Math.max(w, h) * 0.65f,
                new int[]{0x44FF3344, 0x18F59E0B, 0x00000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        colaBackPaint.setShader(new LinearGradient(0, h * 0.20f, w, h,
                new int[]{0xFF6B101B, 0xFF3E0A10, 0xFF210508},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        colaMidPaint.setShader(new LinearGradient(0, h * 0.25f, w, h,
                new int[]{0xFFE11D48, 0xFF9F1239, 0xFF5B0E17},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        ensureShaders(w, h);

        // 1. Rich frosted Coca-Cola studio backdrop + warm crimson-amber glow
        canvas.drawRect(0, 0, w, h, bgPaint);
        canvas.drawRect(0, 0, w, h, glowPaint);

        // 2. Background rising micro-bubbles
        for (int i = 0; i < MAX_BUBBLES / 2; i++) {
            float bx = (bubX[i] + (float) Math.sin(timeSec * 1.9f + bubPhase[i]) * 0.014f) * w;
            float by = bubY[i] * h;
            bubblePaint.setColor(0x36FFD59E);
            canvas.drawCircle(bx, by, bubR[i] * 0.85f, bubblePaint);
        }

        // 3. Back caramel-cola wave layer
        int steps = 28;
        backPath.rewind();
        backPath.moveTo(0, h);
        float prevX = 0f;
        float prevY = surfaceY(0f, h, timeSec - 0.55f) - dp(10);
        backPath.lineTo(0, prevY);
        for (int i = 1; i <= steps; i++) {
            float u = (float) i / steps;
            float x = u * w;
            float y = surfaceY(u, h, timeSec - 0.55f) - dp(10) + (float) Math.sin(u * 6.5f - timeSec * 3.1f) * dp(4);
            backPath.quadTo(prevX, prevY, (prevX + x) * 0.5f, (prevY + y) * 0.5f);
            prevX = x;
            prevY = y;
        }
        backPath.lineTo(w, prevY);
        backPath.lineTo(w, h);
        backPath.close();
        canvas.drawPath(backPath, colaBackPaint);

        // 4. Translucent bobbing ice cubes floating in the cola slide!
        for (int c = 0; c < 3; c++) {
            float iceU = (0.18f + c * 0.31f + timeSec * 0.045f) % 1.0f;
            float ix = iceU * w;
            float iy = surfaceY(iceU, h, timeSec) + dp(8) + (float) Math.sin(timeSec * 3.4f + c * 2.1f) * dp(4);
            float sz = dp(18 + c * 3);
            canvas.save();
            canvas.translate(ix, iy);
            canvas.rotate((float) Math.sin(timeSec * 2.2f + c) * 18f + c * 15f);
            iceRect.set(-sz * 0.5f, -sz * 0.5f, sz * 0.5f, sz * 0.5f);
            canvas.drawRoundRect(iceRect, dp(5), dp(5), iceFillPaint);
            canvas.drawRoundRect(iceRect, dp(5), dp(5), iceStrokePaint);
            canvas.restore();
        }

        // 5. Primary fizzy crimson-caramel Coca-Cola wave + creamy foam crest
        midPath.rewind();
        foamPath.rewind();
        midPath.moveTo(0, h);
        float mPrevX = 0f;
        float mPrevY = surfaceY(0f, h, timeSec);
        midPath.lineTo(0, mPrevY);
        foamPath.moveTo(0, mPrevY);
        for (int i = 1; i <= steps; i++) {
            float u = (float) i / steps;
            float x = u * w;
            float y = surfaceY(u, h, timeSec);
            float mx = (mPrevX + x) * 0.5f;
            float my = (mPrevY + y) * 0.5f;
            midPath.quadTo(mPrevX, mPrevY, mx, my);
            foamPath.quadTo(mPrevX, mPrevY, mx, my);
            mPrevX = x;
            mPrevY = y;
        }
        midPath.lineTo(w, mPrevY);
        foamPath.lineTo(w, mPrevY);
        midPath.lineTo(w, h);
        midPath.close();
        canvas.drawPath(midPath, colaMidPaint);
        canvas.drawPath(foamPath, foamPaint);

        // 6. Foreground carbonation bubbles inside the liquid
        for (int i = MAX_BUBBLES / 2; i < MAX_BUBBLES; i++) {
            float bx = (bubX[i] + (float) Math.cos(timeSec * 2.3f + bubPhase[i]) * 0.016f) * w;
            float by = bubY[i] * h;
            float surf = surfaceY(Math.max(0f, Math.min(1f, bx / Math.max(1f, w))), h, timeSec);
            if (by < surf - dp(6)) continue;
            float r = bubR[i];
            bubblePaint.setColor(0x55FFF3D6);
            canvas.drawCircle(bx, by, r, bubblePaint);
            canvas.drawCircle(bx - r * 0.30f, by - r * 0.30f, Math.max(1f, r * 0.28f), bubbleHiPaint);
        }

        // 7. Sparkling wake trail behind Koliti
        float kolitiR = dp(22f);
        for (int i = trailCount - 1; i >= 1; i--) {
            float frac = 1f - (float) i / MAX_TRAIL;
            splashPaint.setColor(0xFFFFD580);
            splashPaint.setAlpha(Math.round(frac * 95f));
            canvas.drawCircle(trailX[i], trailY[i], kolitiR * 0.52f * frac, splashPaint);
        }

        // 8. Koliti surfing / rolling / jumping with squash & stretch!
        float kx = slideU * w;
        float ky = surfaceY(slideU, h, timeSec) + jumpOffsetY - kolitiR * 0.92f;
        canvas.save();
        canvas.translate(kx, ky);
        canvas.scale(squashX, squashY);
        canvas.rotate(spinDeg);
        if (kolitiBitmap != null && kolitiShader != null) {
            float bw = kolitiBitmap.getWidth();
            float bh = kolitiBitmap.getHeight();
            float scale = (kolitiR * 2f) / Math.max(1f, Math.min(bw, bh));
            avatarMatrix.reset();
            avatarMatrix.setTranslate(-bw * 0.5f, -bh * 0.5f);
            avatarMatrix.postScale(scale, scale);
            kolitiShader.setLocalMatrix(avatarMatrix);
            canvas.drawCircle(0, 0, kolitiR, avatarPaint);
        } else {
            splashPaint.setColor(0xFFFFB74D);
            splashPaint.setAlpha(255);
            canvas.drawCircle(0, 0, kolitiR, splashPaint);
        }
        canvas.drawCircle(0, 0, kolitiR, avatarRimPaint);
        canvas.restore();

        // 9. Fizzy splash droplets & foam sparks
        for (int i = 0; i < MAX_SPLASH; i++) {
            if (spLife[i] <= 0f) continue;
            splashPaint.setColor(spCol[i]);
            splashPaint.setAlpha(Math.min(255, Math.round(spLife[i] * 255f)));
            canvas.drawCircle(spX[i], spY[i], spR[i] * (0.45f + 0.55f * spLife[i]), splashPaint);
        }

        // 10. Animated combo badge top-right when jumping!
        if (comboBannerAlpha > 0.02f && !comboBannerText.isEmpty()) {
            float tw = hudTextPaint.measureText(comboBannerText);
            float pw = tw + dp(24);
            float ph = dp(28);
            float rx = w - dp(12);
            float ty = dp(12) - (1f - comboBannerAlpha) * dp(6);
            hudRect.set(rx - pw, ty, rx, ty + ph);
            hudBgPaint.setAlpha(Math.round(comboBannerAlpha * 185f));
            hudTextPaint.setAlpha(Math.round(comboBannerAlpha * 255f));
            canvas.drawRoundRect(hudRect, ph * 0.5f, ph * 0.5f, hudBgPaint);
            Paint.FontMetrics fm = hudTextPaint.getFontMetrics();
            canvas.drawText(comboBannerText, hudRect.centerX(), hudRect.centerY() - (fm.ascent + fm.descent) * 0.5f, hudTextPaint);
        }
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
