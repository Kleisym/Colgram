package org.telegram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import org.telegram.messenger.AndroidUtilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Lightweight 60fps celebratory confetti + neon spark burst overlay.
 * Automatically removes itself from parent / pauses on completion so idle cost is 0%.
 */
public final class ColgramConfettiView extends View {

    private static final int[] PALETTE = {
            0xFF7C3AED, 0xFF06B6D4, 0xFFEC4899, 0xFF10B981, 0xFFF59E0B, 0xFF38BDF8, 0xFFF43F5E
    };

    private static final class Particle {
        float x, y;
        float vx, vy;
        float size;
        float rot, rotSpeed;
        int color;
        int shape; // 0 = ribbon rect, 1 = circle, 2 = diamond spark
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    private ValueAnimator animator;
    private float globalAlpha = 1f;
    private long lastTickMs;
    private boolean detaching = false;

    public ColgramConfettiView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
    }

    public static void burstOver(ViewGroup root, float originX, float originY, int count) {
        if (root == null || root.getContext() == null) return;
        // Prefer FrameLayout / RelativeLayout ancestor so match_parent overlay never breaks LinearLayout
        ViewGroup host = root;
        while (!(host instanceof android.widget.FrameLayout) && host.getParent() instanceof ViewGroup) {
            host = (ViewGroup) host.getParent();
        }
        final ViewGroup finalHost = host;
        ColgramConfettiView view = new ColgramConfettiView(finalHost.getContext());
        finalHost.addView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        view.bringToFront();
        view.post(() -> view.startBurst(
                originX > 0 ? originX : finalHost.getWidth() * 0.5f,
                originY > 0 ? originY : finalHost.getHeight() * 0.35f,
                count));
    }

    public void startBurst(float originX, float originY, int count) {
        particles.clear();
        float density = Math.max(1f, AndroidUtilities.density);
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            p.x = originX + (random.nextFloat() - 0.5f) * 24f * density;
            p.y = originY + (random.nextFloat() - 0.5f) * 24f * density;
            double angle = -Math.PI + random.nextDouble() * (2.0 * Math.PI);
            float speed = (180f + random.nextFloat() * 440f) * density;
            p.vx = (float) (Math.cos(angle) * speed);
            p.vy = (float) (Math.sin(angle) * speed) - 180f * density;
            p.size = (5f + random.nextFloat() * 6.5f) * density;
            p.rot = random.nextFloat() * 360f;
            p.rotSpeed = (random.nextFloat() - 0.5f) * 480f;
            p.color = PALETTE[random.nextInt(PALETTE.length)];
            p.shape = random.nextInt(3);
            particles.add(p);
        }

        if (animator != null) {
            animator.cancel();
        }
        globalAlpha = 1f;
        lastTickMs = android.os.SystemClock.uptimeMillis();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2200L);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(anim -> {
            long now = android.os.SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, Math.max(0.001f, (now - lastTickMs) / 1000f));
            lastTickMs = now;
            float frac = anim.getAnimatedFraction();
            globalAlpha = frac > 0.72f ? Math.max(0f, 1f - (frac - 0.72f) / 0.28f) : 1f;
            float gravity = 980f * Math.max(1f, AndroidUtilities.density);
            for (Particle p : particles) {
                p.vx *= (1f - 0.8f * dt);
                p.vy += gravity * dt;
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.rot += p.rotSpeed * dt;
            }
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                detachFromParent();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                detachFromParent();
            }
        });
        animator.start();
    }

    private void detachFromParent() {
        if (detaching) return;
        post(() -> {
            if (getParent() instanceof ViewGroup) {
                try {
                    ((ViewGroup) getParent()).removeView(this);
                } catch (Throwable ignored) {}
            }
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        detaching = true;
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (particles.isEmpty()) return;
        int alpha = Math.max(0, Math.min(255, Math.round(globalAlpha * 255f)));
        for (Particle p : particles) {
            paint.setColor(p.color);
            paint.setAlpha(alpha);
            canvas.save();
            canvas.translate(p.x, p.y);
            canvas.rotate(p.rot);
            if (p.shape == 1) {
                canvas.drawCircle(0, 0, p.size * 0.45f, paint);
            } else if (p.shape == 2) {
                path.reset();
                float s = p.size * 0.65f;
                path.moveTo(0, -s);
                path.lineTo(s * 0.55f, 0);
                path.lineTo(0, s);
                path.lineTo(-s * 0.55f, 0);
                path.close();
                canvas.drawPath(path, paint);
            } else {
                // 3D fluttering ribbon tumble
                float flip = 0.25f + 0.75f * Math.abs((float) Math.cos(p.rot * 0.045f));
                canvas.scale(1f, flip);
                canvas.drawRoundRect(-p.size * 0.65f, -p.size * 0.3f, p.size * 0.65f, p.size * 0.3f, 2f, 2f, paint);
            }
            canvas.restore();
        }
    }
}
