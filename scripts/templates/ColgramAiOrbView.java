package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;

/**
 * Animated Signature Orb for Koliti:
 * - Zero-allocation 60fps shader rendering (cached Radial/Linear/Sweep shaders + Matrix rotation)
 * - Expanded 56dp canvas around a crisp 40dp glass lens so halo glow, radar pulse, and wake trails never clip
 * - Pauses animator automatically when window/view is hidden to save battery
 */
public final class ColgramAiOrbView extends View {

    public enum State {
        IDLE,
        FLYING,
        THINKING,
        SEARCHING,
        VISION
    }

    public interface DragListener {
        void onDragDelta(float dxPx, float dyPx);
        void onDragEnded();
    }

    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wakePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Matrix rotMatrix = new Matrix();
    private final Matrix avatarMatrix = new Matrix();
    private final Matrix shadowMatrix = new Matrix();
    private final RectF arcRect = new RectF();
    private final RectF sheenRect = new RectF();
    private final float[] wakeDx = new float[5];
    private final float[] wakeDy = new float[5];

    private Bitmap avatarBitmap;
    private BitmapShader avatarShader;
    private RadialGradient cachedShadowShader;
    private LinearGradient cachedSheenShader;
    private SweepGradient cachedRimShader;
    private State cachedShaderState;
    private int cachedW = -1;
    private int cachedH = -1;
    private int cachedCustomAccent = -1;
    private int cachedPaletteSecondary = 0xFFF59E0B;
    private int avatarLoadGen = 0;

    private ValueAnimator orbAnimator;
    private float phase = 0f;
    private long lastFrameInvalidateMs = 0L;
    private State state = State.IDLE;
    private Runnable onClickAction;
    private Runnable onLongClickAction;
    private DragListener dragListener;

    private float velocityX = 0f;
    private float velocityY = 0f;

    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private boolean dragging = false;
    private boolean longPressed = false;
    private float baseTranslationY = 0f;
    private boolean windowVisible = true;
    private boolean hostPaused = false;

    private final Runnable longPressTrigger = () -> {
        if (!dragging && onLongClickAction != null) {
            longPressed = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            onLongClickAction.run();
        }
    };

    public ColgramAiOrbView(Context context) {
        super(context);
        setContentDescription("Koliti AI Ассистент");
        setClickable(true);

        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(dp(1.8f));

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(dp(2.3f));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeWidth(dp(1.35f));

        reloadCustomAvatar();
        applyDesignConfig();
    }

    public void reloadCustomAvatar() {
        avatarLoadGen++;
        try {
            String customPath = org.colgram.core.ColgramDesignConfig.getKolitiCustomAvatarPath();
            if (customPath != null && !customPath.isEmpty()) {
                final int gen = avatarLoadGen;
                final Context appCtx = getContext() != null ? getContext().getApplicationContext() : ApplicationLoader.applicationContext;
                Utilities.globalQueue.postRunnable(() -> {
                    Bitmap loaded = null;
                    try {
                        loaded = ColgramAeroStyler.loadKolitiAvatarBitmap(appCtx);
                    } catch (Throwable ignored) {}
                    final Bitmap finalLoaded = loaded;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (gen != avatarLoadGen) return;
                        applyLoadedAvatarBitmap(finalLoaded);
                    });
                });
                return;
            }
            applyLoadedAvatarBitmap(ColgramAeroStyler.loadKolitiAvatarBitmap(getContext()));
        } catch (Throwable ignored) {}
    }

    private void applyLoadedAvatarBitmap(Bitmap bmp) {
        avatarBitmap = bmp;
        if (avatarBitmap != null && !avatarBitmap.isRecycled()) {
            avatarShader = new BitmapShader(avatarBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            avatarPaint.setShader(avatarShader);
        } else {
            avatarShader = null;
            avatarPaint.setShader(null);
        }
        invalidate();
    }

    public void applyDesignConfig() {
        setContentDescription(org.colgram.core.ColgramDesignConfig.getKolitiDisplayName() + " AI Ассистент");
        cachedShaderState = null;
        cachedW = -1;
        cachedCustomAccent = org.colgram.core.ColgramDesignConfig.getAccentColor();
        cachedPaletteSecondary = cachedCustomAccent != 0
                ? ColgramAeroStyler.Palette.resolve().accentSecondary
                : 0xFFF59E0B;
        reloadCustomAvatar();
        invalidate();
    }

    public void setOnOrbClickListener(Runnable listener) {
        this.onClickAction = listener;
    }

    public void setOnOrbLongClickListener(Runnable listener) {
        this.onLongClickAction = listener;
    }

    public void setDragListener(DragListener listener) {
        this.dragListener = listener;
    }

    public void setFlightVelocity(float vx, float vy) {
        this.velocityX = vx;
        this.velocityY = vy;
        for (int i = wakeDx.length - 1; i >= 1; i--) {
            wakeDx[i] = wakeDx[i - 1];
            wakeDy[i] = wakeDy[i - 1];
        }
        wakeDx[0] = Math.max(-dp(16), Math.min(dp(16), -vx * 0.20f));
        wakeDy[0] = Math.max(-dp(16), Math.min(dp(16), -vy * 0.20f));
        syncAnimatorState();
        invalidate();
    }

    public void setState(State newState) {
        State target = newState == null ? State.IDLE : newState;
        if (this.state == target) return;
        this.state = target;
        cachedShaderState = null;
        if (orbAnimator != null) {
            orbAnimator.setDuration(durationForState(state));
        }
        syncAnimatorState();
        invalidate();
    }

    public void setBaseTranslationY(float baseY) {
        this.baseTranslationY = baseY;
        setTranslationY(baseY);
    }

    private static long durationForState(State s) {
        switch (s) {
            case FLYING: return 1100L;
            case THINKING:
            case SEARCHING:
            case VISION: return 1350L;
            default: return 4200L;
        }
    }

    private boolean hasActiveWake() {
        if (dragging) return true;
        if (Math.hypot(velocityX, velocityY) > 0.6f) return true;
        for (int i = 0; i < wakeDx.length; i++) {
            if (Math.abs(wakeDx[i]) > 0.35f || Math.abs(wakeDy[i]) > 0.35f) {
                return true;
            }
        }
        return false;
    }

    private void syncAnimatorState() {
        boolean powerSave = org.colgram.core.ColgramPowerGuard.isPowerSaveMode(getContext());
        // 100% static (0 invalidations/sec, 0.0% CPU/GPU) while IDLE with no active drag/flight wake;
        // animates ONLY while Koliti is actively generating/searching/flying or being dragged!
        boolean activeMotion = (state != State.IDLE) || hasActiveWake();
        boolean shouldRun = !hostPaused && isAttachedToWindow() && windowVisible
                && getVisibility() == VISIBLE && !powerSave && activeMotion;
        if (shouldRun && orbAnimator == null) {
            orbAnimator = ValueAnimator.ofFloat(0f, 1f);
            orbAnimator.setDuration(durationForState(state));
            orbAnimator.setRepeatCount(ValueAnimator.INFINITE);
            orbAnimator.setInterpolator(new LinearInterpolator());
            orbAnimator.addUpdateListener(a -> {
                long nowMs = android.os.SystemClock.uptimeMillis();
                if (nowMs - lastFrameInvalidateMs < 24L) {
                    return;
                }
                lastFrameInvalidateMs = nowMs;
                phase = (float) a.getAnimatedValue();
                velocityX *= 0.80f;
                velocityY *= 0.80f;
                for (int i = 0; i < wakeDx.length; i++) {
                    wakeDx[i] *= 0.80f;
                    wakeDy[i] *= 0.80f;
                }
                if (state == State.IDLE && !hasActiveWake()) {
                    velocityX = 0f;
                    velocityY = 0f;
                    for (int i = 0; i < wakeDx.length; i++) {
                        wakeDx[i] = 0f;
                        wakeDy[i] = 0f;
                    }
                    invalidate();
                    syncAnimatorState();
                    return;
                }
                invalidate();
            });
            orbAnimator.start();
        } else if (!shouldRun && orbAnimator != null) {
            orbAnimator.cancel();
            orbAnimator = null;
        }
    }

    public void setHostPaused(boolean paused) {
        if (this.hostPaused == paused) return;
        this.hostPaused = paused;
        syncAnimatorState();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        windowVisible = getWindowVisibility() == VISIBLE;
        syncAnimatorState();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(longPressTrigger);
        animate().cancel();
        if (orbAnimator != null) {
            orbAnimator.cancel();
            orbAnimator = null;
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = (visibility == VISIBLE);
        syncAnimatorState();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        syncAnimatorState();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cachedW = -1;
        cachedH = -1;
        cachedShaderState = null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downRawX = ev.getRawX();
            downRawY = ev.getRawY();
            lastRawX = downRawX;
            lastRawY = downRawY;
            dragging = false;
            longPressed = false;
            postDelayed(longPressTrigger, 420L);
            animate().scaleX(0.90f).scaleY(0.90f).setDuration(85L).start();
            return true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            float dxTotal = ev.getRawX() - downRawX;
            float dyTotal = ev.getRawY() - downRawY;
            if (!dragging && (Math.hypot(dxTotal, dyTotal) > dp(8))) {
                dragging = true;
                removeCallbacks(longPressTrigger);
            }
            if (dragging) {
                float stepX = ev.getRawX() - lastRawX;
                float stepY = ev.getRawY() - lastRawY;
                lastRawX = ev.getRawX();
                lastRawY = ev.getRawY();
                if (dragListener != null) {
                    dragListener.onDragDelta(stepX, stepY);
                } else {
                    baseTranslationY += stepY;
                    setTranslationY(baseTranslationY);
                }
                setFlightVelocity(stepX * 2.0f, stepY * 2.0f);
            }
            return true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longPressTrigger);
            animate().scaleX(1f).scaleY(1f).setDuration(240L)
                    .setInterpolator(new OvershootInterpolator(1.65f)).start();
            if (dragging) {
                if (dragListener != null) {
                    dragListener.onDragEnded();
                }
            } else if (!longPressed && action == MotionEvent.ACTION_UP) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (onClickAction != null) {
                    onClickAction.run();
                }
            }
            dragging = false;
            syncAnimatorState();
            return true;
        }
        return super.onTouchEvent(ev);
    }

    private void ensureShaders(int w, int h, int accentMain, int accentSoft) {
        if (cachedW == w && cachedH == h && cachedShaderState == state
                && cachedShadowShader != null && cachedSheenShader != null && cachedRimShader != null) {
            return;
        }
        cachedW = w;
        cachedH = h;
        cachedShaderState = state;
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float baseR = Math.min(w, h) * 0.34f;

        cachedShadowShader = new RadialGradient(
                cx, cy + dp(1.5f), Math.max(1f, baseR * 1.44f),
                new int[]{0x7A000000, withAlpha(accentMain, 0x46), 0x00000000},
                new float[]{0.50f, 0.76f, 1f},
                Shader.TileMode.CLAMP
        );
        shadowPaint.setShader(cachedShadowShader);

        cachedSheenShader = new LinearGradient(
                cx, cy - baseR * 0.88f, cx, cy - baseR * 0.16f,
                new int[]{0x3CFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP
        );
        sheenPaint.setShader(cachedSheenShader);

        cachedRimShader = new SweepGradient(
                cx, cy,
                new int[]{0x44FFFFFF, withAlpha(accentSoft, 0xCC), withAlpha(accentMain, 0xEE), 0x44FFFFFF},
                new float[]{0f, 0.33f, 0.68f, 1f}
        );
        rimPaint.setShader(cachedRimShader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        int accentMain;
        int accentSoft;
        int customAccent = cachedCustomAccent >= 0 ? cachedCustomAccent : org.colgram.core.ColgramDesignConfig.getAccentColor();
        switch (state) {
            case SEARCHING:
                accentMain = 0xFF38BDF8;
                accentSoft = 0xFF93C5FD;
                break;
            case VISION:
                accentMain = 0xFFF59E0B;
                accentSoft = 0xFFFDE68A;
                break;
            case THINKING:
                accentMain = customAccent != 0 ? customAccent : 0xFFF43F5E;
                accentSoft = 0xFFFDE047;
                break;
            case FLYING:
                accentMain = customAccent != 0 ? customAccent : 0xFFFB7185;
                accentSoft = 0xFFFBBF24;
                break;
            default:
                accentMain = customAccent != 0 ? customAccent : 0xFFE11D48;
                accentSoft = customAccent != 0 ? cachedPaletteSecondary : 0xFFF59E0B;
                break;
        }

        ensureShaders(w, h, accentMain, accentSoft);

        float cx = w * 0.5f;
        float cy = h * 0.5f;
        double rad = phase * Math.PI * 2.0;
        float breath = 1f + (state == State.IDLE ? 0.020f : 0.042f) * (float) Math.sin(rad);
        // 0.34 * 56dp ~= 19dp radius -> 38-40dp crisp lens with ~9dp glow/pulse halo safety margin inside view bounds
        float baseR = Math.min(w, h) * 0.34f;
        float r = baseR * breath;

        // 0. Subtle glowing wake during inter-screen spring flight
        float speed = (float) Math.hypot(velocityX, velocityY);
        if (speed > 0.6f || Math.abs(wakeDx[0]) > 0.4f || Math.abs(wakeDy[0]) > 0.4f) {
            float accX = 0f, accY = 0f;
            for (int i = 0; i < wakeDx.length; i++) {
                accX += wakeDx[i] * 0.34f;
                accY += wakeDy[i] * 0.34f;
                int alpha = Math.max(12, 100 - i * 20);
                wakePaint.setColor(i == 0 ? accentMain : accentSoft);
                wakePaint.setAlpha(alpha);
                float trailR = Math.max(dp(2f), r * (0.28f - i * 0.045f));
                canvas.drawCircle(cx + accX, cy + accY, trailR, wakePaint);
            }
        }

        // 1. Ambient drop shadow + halo glow (cached shader scaled gently with breath)
        if (cachedShadowShader != null) {
            shadowMatrix.setScale(breath, breath, cx, cy + dp(1.5f));
            cachedShadowShader.setLocalMatrix(shadowMatrix);
            canvas.drawCircle(cx, cy + dp(1.5f), r * 1.44f, shadowPaint);
        }

        // 2. Expanding radar pulse ring when active (fits cleanly inside 56dp canvas!)
        if (state == State.SEARCHING || state == State.THINKING || state == State.VISION || state == State.FLYING) {
            float p = phase;
            pulsePaint.setColor(withAlpha(accentMain, (int) ((1f - p) * 155)));
            canvas.drawCircle(cx, cy, r * (1.06f + 0.34f * p), pulsePaint);
        }

        // 3. Signature Koliti Avatar clipped perfectly inside circular glass lens
        if (avatarShader != null && avatarBitmap != null && avatarBitmap.getWidth() > 0) {
            float scale = (r * 2.02f) / Math.min(avatarBitmap.getWidth(), avatarBitmap.getHeight());
            avatarMatrix.reset();
            avatarMatrix.setScale(scale, scale);
            avatarMatrix.postTranslate(cx - avatarBitmap.getWidth() * scale * 0.5f,
                    cy - avatarBitmap.getHeight() * scale * 0.5f);
            avatarShader.setLocalMatrix(avatarMatrix);
            canvas.drawCircle(cx, cy, r, avatarPaint);
        } else {
            avatarPaint.setShader(null);
            avatarPaint.setColor(0xFF4C0A15);
            canvas.drawCircle(cx, cy, r, avatarPaint);
        }

        // 4. Curved top specular lens gloss (cached LinearGradient)
        sheenRect.set(cx - r * 0.62f, cy - r * 0.88f, cx + r * 0.62f, cy - r * 0.18f);
        canvas.drawOval(sheenRect, sheenPaint);

        // 5. Rotating metallic rim (cached SweepGradient + Matrix rotation) + dynamic status arc
        if (cachedRimShader != null) {
            rotMatrix.setRotate(phase * 360f, cx, cy);
            cachedRimShader.setLocalMatrix(rotMatrix);
            canvas.drawCircle(cx, cy, r, rimPaint);
        }

        float arcPad = r + dp(2.0f);
        arcRect.set(cx - arcPad, cy - arcPad, cx + arcPad, cy + arcPad);
        arcPaint.setColor(withAlpha(accentMain, state == State.IDLE ? 0xD8 : 0xFF));
        float sweepDeg = state == State.IDLE ? 74f : 138f;
        canvas.drawArc(arcRect, phase * 360f - 40f, sweepDeg, false, arcPaint);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((Math.max(0, Math.min(255, alpha))) << 24);
    }

    private static float dp(float v) {
        return AndroidUtilities.dpf2(v);
    }
}
