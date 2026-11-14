package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/** Colgram's six-page art, rendered on the UI canvas without a native intro/EGL loop. */
public final class ColgramIntroArtView extends View {
    private static final int RED = 0xFFEF3D50;
    private static final int RED_LIGHT = 0xFFFF6879;
    private static final int RED_DARK = 0xFFB51D36;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path avatarClip = new Path();
    private final RectF rect = new RectF();
    private final Drawable avatar;
    private final Drawable cloud;

    private float pageProgress;
    private int selectedPage;
    private float entryProgress = 1f;
    private boolean scrolling;
    private boolean motionEnabled = true;
    private ValueAnimator entryAnimator;

    private int background;
    private int ink;
    private int panel;
    private int muted;
    private int accent;

    public ColgramIntroArtView(Context context) {
        super(context);
        avatar = context.getResources().getDrawable(R.drawable.colgram_app_avatar, context.getTheme()).mutate();
        cloud = context.getResources().getDrawable(R.drawable.colgram_intro_cloud, context.getTheme()).mutate();
        avatar.setBounds(56, 26, 184, 154);
        cloud.setBounds(30, 27, 210, 153);
        avatarClip.addRoundRect(new RectF(56, 26, 184, 154), 28, 28, Path.Direction.CW);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setPageProgress(float progress) {
        if (Float.isNaN(progress) || Float.isInfinite(progress)) {
            return;
        }
        pageProgress = Math.max(0f, Math.min(5f, progress));
        invalidate();
    }

    public void setSelectedPage(int page) {
        if (selectedPage != page) {
            selectedPage = Math.max(0, Math.min(5, page));
            if (!scrolling) {
                startEntryAnimation();
            }
        }
    }

    public void setScrolling(boolean value) {
        if (scrolling == value) {
            return;
        }
        scrolling = value;
        if (value) {
            cancelEntryAnimation();
            entryProgress = 1f;
        } else {
            startEntryAnimation();
        }
    }

    public void setMotionEnabled(boolean enabled) {
        motionEnabled = enabled;
        if (enabled) {
            startEntryAnimation();
        } else {
            cancelEntryAnimation();
            entryProgress = 1f;
            invalidate();
        }
    }

    private void startEntryAnimation() {
        cancelEntryAnimation();
        if (!motionEnabled || scrolling || !isAttachedToWindow() || !isShown()
                || (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            entryProgress = 1f;
            invalidate();
            return;
        }
        entryAnimator = ValueAnimator.ofFloat(0f, 1f);
        entryAnimator.setDuration(900);
        entryAnimator.setInterpolator(new DecelerateInterpolator());
        entryAnimator.addUpdateListener(animation -> {
            entryProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        entryAnimator.start();
    }

    private void cancelEntryAnimation() {
        if (entryAnimator != null) {
            entryAnimator.cancel();
            entryAnimator.removeAllUpdateListeners();
            entryAnimator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startEntryAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelEntryAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            startEntryAnimation();
        } else {
            cancelEntryAnimation();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            startEntryAnimation();
        } else {
            cancelEntryAnimation();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        background = Theme.getColor(Theme.key_windowBackgroundWhite);
        ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        panel = blend(background, ink, .085f);
        muted = blend(background, ink, .38f);
        accent = brightness(background) < .45f ? RED_LIGHT : RED_DARK;

        float scale = Math.min(getWidth() / 240f, getHeight() / 180f);
        int outerSave = canvas.save();
        canvas.translate((getWidth() - 240 * scale) / 2f, (getHeight() - 180 * scale) / 2f);
        canvas.scale(scale, scale);
        for (int page = 0; page < 6; page++) {
            float distance = Math.abs(pageProgress - page);
            if (distance >= 1f) {
                continue;
            }
            int save = canvas.saveLayerAlpha(-24, -20, 264, 200, Math.round((1f - distance) * 255));
            float offset = pageProgress - page;
            canvas.translate(-offset * 52, 0);
            float zoom = 1f - .12f * distance;
            canvas.scale(zoom, zoom, 120, 90);
            float pulse = (float) Math.sin(entryProgress * Math.PI) + distance * .65f;
            switch (page) {
                case 0: drawWelcome(canvas); break;
                case 1: drawFast(canvas, pulse, offset); break;
                case 2: drawFree(canvas, pulse, offset); break;
                case 3: drawPowerful(canvas, pulse, offset); break;
                case 4: drawPrivate(canvas, pulse, offset); break;
                case 5: drawCloud(canvas); break;
            }
            canvas.restoreToCount(save);
        }
        canvas.restoreToCount(outerSave);
    }

    private void drawWelcome(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(avatarClip);
        fill(Color.BLACK);
        canvas.drawRect(56, 26, 184, 154, paint);
        avatar.draw(canvas);
        canvas.restoreToCount(save);
    }

    /** Express lanes and a red data pulse, rather than Telegram's clock/arrow. */
    private void drawFast(Canvas canvas, float pulse, float swipe) {
        softOrbit(canvas, 120, 91, 66, RED);
        int save = canvas.save();
        canvas.rotate(-10 - swipe * 8, 120, 90);
        message(canvas, 28 - pulse * 4, 39, 104, 49, false);
        message(canvas, 117 + pulse * 5, 104, 93, 44, true);
        line(canvas, 37, 115, 102, 115, muted, 2);
        line(canvas, 25, 124, 84, 124, muted, 2);
        line(canvas, 46, 133, 74, 133, muted, 2);
        line(canvas, 161, 52, 207, 52, muted, 2);
        line(canvas, 180, 61, 216, 61, muted, 2);
        path.reset();
        path.moveTo(68, 96);
        path.cubicTo(101, 114, 130, 58, 170, 80);
        stroke(RED, 6);
        canvas.drawPath(path, paint);
        float travel = .52f + pulse * .18f - swipe * .24f;
        float x = 68 + 102 * travel;
        float y = 98 - 26 * (float) Math.sin(travel * Math.PI);
        fill(blend(background, RED, .15f));
        canvas.drawCircle(x, y, 14, paint);
        fill(RED);
        canvas.drawCircle(x, y, 8, paint);
        line(canvas, 163, 73, 172, 80, RED, 4);
        line(canvas, 164, 87, 172, 80, RED, 4);
        canvas.restoreToCount(save);
        sparkle(canvas, 36, 70, 4, accent);
        dot(canvas, 195, 143, 3, accent);
    }

    /** Open, connected conversations: no coin, knot or infinity glyph. */
    private void drawFree(Canvas canvas, float pulse, float swipe) {
        softOrbit(canvas, 120, 90, 67, RED);
        float spread = 5 * pulse + 12 * swipe;
        line(canvas, 80 - spread, 52, 161 + spread, 53, muted, 2);
        line(canvas, 66 - spread, 71, 62 - spread, 119, muted, 2);
        line(canvas, 176 + spread, 71, 181 + spread, 118, muted, 2);
        line(canvas, 79 - spread, 136, 163 + spread, 139, muted, 2);
        avatarNode(canvas, 64 - spread, 47, 19, false);
        avatarNode(canvas, 177 + spread, 49, 19, true);
        avatarNode(canvas, 58 - spread, 134, 16, true);
        avatarNode(canvas, 185 + spread, 137, 16, false);
        message(canvas, 78, 67 - pulse * 3, 86, 56, true);
        fill(RED);
        path.reset();
        path.moveTo(101, 119 - pulse * 3);
        path.lineTo(94, 133 - pulse * 3);
        path.lineTo(120, 120 - pulse * 3);
        path.close();
        canvas.drawPath(path, paint);
        sparkle(canvas, 119, 35, 5 + pulse, accent);
        dot(canvas, 119, 150, 3, accent);
        dot(canvas, 30, 92, 2, muted);
        dot(canvas, 208, 92, 2, muted);
    }

    /** Layered media modules and a live waveform: a wholly custom power scene. */
    private void drawPowerful(Canvas canvas, float pulse, float swipe) {
        softOrbit(canvas, 120, 91, 68, RED);
        int save = canvas.save();
        canvas.rotate(swipe * 6, 120, 90);
        card(canvas, 46, 41 - pulse * 4, 145, 96, 17, panel);
        stroke(blend(background, ink, .16f), 1.5f);
        rect.set(46, 41 - pulse * 4, 191, 137 - pulse * 4);
        canvas.drawRoundRect(rect, 17, 17, paint);
        gradientCard(canvas, 30, 68 + pulse * 3, 82, 74, 15);
        fill(0xFFFDE9ED);
        path.reset();
        path.moveTo(63, 89 + pulse * 3);
        path.lineTo(63, 120 + pulse * 3);
        path.lineTo(86, 104 + pulse * 3);
        path.close();
        canvas.drawPath(path, paint);
        card(canvas, 135, 27 - pulse * 5, 69, 61, 14, blend(background, ink, .12f));
        line(canvas, 149, 45 - pulse * 5, 189, 45 - pulse * 5, muted, 3);
        for (int i = 0; i < 5; i++) {
            float h = 7 + (i % 3) * 5 + pulse * (i % 2 == 0 ? 3 : -2);
            line(canvas, 151 + i * 8, 65 - h / 2 - pulse * 5,
                    151 + i * 8, 65 + h / 2 - pulse * 5, accent, 3);
        }
        card(canvas, 124, 105 + pulse * 2, 83, 43, 12, blend(background, ink, .11f));
        line(canvas, 137, 120 + pulse * 2, 192, 120 + pulse * 2, muted, 3);
        line(canvas, 137, 131 + pulse * 2, 173, 131 + pulse * 2, accent, 3);
        canvas.restoreToCount(save);
        sparkle(canvas, 28, 44, 4, accent);
        sparkle(canvas, 217, 107, 4, accent);
        dot(canvas, 112, 156, 3, muted);
    }

    /** A biometric circuit tile; intentionally not the stock safe-door/lock. */
    private void drawPrivate(Canvas canvas, float pulse, float swipe) {
        softOrbit(canvas, 120, 90, 68, RED);
        int save = canvas.save();
        canvas.rotate(-5 + swipe * 7, 120, 90);
        card(canvas, 63, 31, 114, 120, 23, panel);
        stroke(blend(background, ink, .2f), 1.5f);
        rect.set(63, 31, 177, 151);
        canvas.drawRoundRect(rect, 23, 23, paint);
        for (int i = 0; i < 3; i++) {
            float y = 62 + i * 27;
            line(canvas, 40, y, 63, y, muted, 2);
            line(canvas, 177, y, 200, y, muted, 2);
            dot(canvas, 37, y, 4, i == 1 ? accent : muted);
            dot(canvas, 203, y, 4, i == 1 ? muted : accent);
        }
        stroke(accent, 3.4f);
        rect.set(85, 51, 155, 121);
        canvas.drawArc(rect, 174, 258, false, paint);
        rect.set(94, 61, 146, 114);
        canvas.drawArc(rect, 168, 264, false, paint);
        rect.set(103, 71, 137, 105);
        canvas.drawArc(rect, 160, 270, false, paint);
        path.reset();
        path.moveTo(120, 89);
        path.cubicTo(122, 111, 119, 124, 110, 134);
        canvas.drawPath(path, paint);
        path.reset();
        path.moveTo(103, 91);
        path.cubicTo(105, 111, 100, 120, 95, 125);
        canvas.drawPath(path, paint);
        path.reset();
        path.moveTo(136, 89);
        path.cubicTo(140, 112, 135, 128, 126, 136);
        canvas.drawPath(path, paint);
        float scanY = 68 + pulse * 49 + swipe * 14;
        line(canvas, 79, scanY, 161, scanY, blend(background, RED, .26f), 9);
        line(canvas, 79, scanY, 161, scanY, accent, 1.5f);
        canvas.restoreToCount(save);
        sparkle(canvas, 185, 35, 4, accent);
        dot(canvas, 53, 146, 3, accent);
    }

    private void drawCloud(Canvas canvas) {
        cloud.draw(canvas);
    }

    private void softOrbit(Canvas canvas, float x, float y, float radius, int color) {
        fill(blend(background, color, .06f));
        canvas.drawCircle(x, y, radius, paint);
        stroke(blend(background, color, .14f), 1);
        rect.set(x - radius - 8, y - radius - 8, x + radius + 8, y + radius + 8);
        canvas.drawArc(rect, 208, 86, false, paint);
        canvas.drawArc(rect, 34, 75, false, paint);
    }

    private void message(Canvas canvas, float x, float y, float width, float height, boolean red) {
        if (red) {
            gradientCard(canvas, x, y, width, height, 13);
        } else {
            card(canvas, x, y, width, height, 13, panel);
            stroke(blend(background, ink, .15f), 1);
            rect.set(x, y, x + width, y + height);
            canvas.drawRoundRect(rect, 13, 13, paint);
        }
        int text = red ? 0xFFFFE7EC : muted;
        line(canvas, x + 14, y + 16, x + width - 15, y + 16, text, 3);
        line(canvas, x + 14, y + 27, x + width - 27, y + 27, text, 3);
        if (height > 50) {
            dot(canvas, x + 17, y + 40, 2, text);
            dot(canvas, x + 26, y + 40, 2, text);
            dot(canvas, x + 35, y + 40, 2, text);
        }
    }

    private void avatarNode(Canvas canvas, float x, float y, float radius, boolean red) {
        fill(red ? RED : blend(background, ink, .16f));
        canvas.drawCircle(x, y, radius, paint);
        int color = red ? 0xFFFFE7EC : muted;
        dot(canvas, x, y - 4, radius * .24f, color);
        fill(color);
        rect.set(x - radius * .44f, y + 3, x + radius * .44f, y + radius * .56f);
        canvas.drawRoundRect(rect, radius * .24f, radius * .24f, paint);
    }

    private void gradientCard(Canvas canvas, float x, float y, float width, float height, float radius) {
        fill(RED);
        paint.setShader(new LinearGradient(x, y + height, x + width, y, RED_DARK, RED_LIGHT, Shader.TileMode.CLAMP));
        rect.set(x, y, x + width, y + height);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);
    }

    private void card(Canvas canvas, float x, float y, float width, float height, float radius, int color) {
        fill(color);
        rect.set(x, y, x + width, y + height);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    private void dot(Canvas canvas, float x, float y, float radius, int color) {
        fill(color);
        canvas.drawCircle(x, y, radius, paint);
    }

    private void sparkle(Canvas canvas, float x, float y, float radius, int color) {
        line(canvas, x - radius, y, x + radius, y, color, 2);
        line(canvas, x, y - radius, x, y + radius, color, 2);
    }

    private void line(Canvas canvas, float x1, float y1, float x2, float y2, int color, float width) {
        stroke(color, width);
        canvas.drawLine(x1, y1, x2, y2, paint);
    }

    private void fill(int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha(255);
    }

    private void stroke(int color, float width) {
        fill(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private static int blend(int from, int to, float amount) {
        return Color.rgb(Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }

    private static float brightness(int color) {
        return (.2126f * Color.red(color) + .7152f * Color.green(color) + .0722f * Color.blue(color)) / 255f;
    }
}
