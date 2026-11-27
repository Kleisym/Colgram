package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramBuildFlags;
import org.colgram.core.ColgramConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * Flagship dynamic visual chrome & motion system for Colgram screens:
 * - Zero-overlap `setBetterLayout(true)` squircle rows with unified 30dp icon alignment
 * - Adaptive Light / Dark / Cyber HeroBannerCell with zero-allocation Matrix shimmer,
 *   horizontally scrollable chips (no clipping on 360dp / split-screen), and zero flicker
 * - Staggered CubicBezier list entrances, tactile press-scale + ripple feedback,
 *   typing-wave indicator, and themed dialog input helpers
 */
public final class ColgramMenuChrome {

    public static final int COLOR_CRIMSON = 0xFFE11D48;
    public static final int COLOR_EMERALD = 0xFF10B981;
    public static final int COLOR_BLUE = 0xFF2563EB;
    public static final int COLOR_CYAN = 0xFF0891B2;
    public static final int COLOR_VIOLET = 0xFF7C3AED;
    public static final int COLOR_AMBER = 0xFFD97706;
    public static final int COLOR_INDIGO = 0xFF4F46E5;
    public static final int COLOR_TEAL = 0xFF0D9488;
    public static final int COLOR_ROSE = 0xFFF43F5E;
    public static final int COLOR_SLATE = 0xFF475569;

    private ColgramMenuChrome() {}

    public static void applySettingIcon(TextSettingsCell cell, int iconRes, int iconColor) {
        if (cell == null) return;
        // CRITICAL: enable betterLayout so title & value NEVER collide on release builds!
        cell.setBetterLayout(true);
        cell.setColorfulIcon(iconRes, iconColor);
        attachSpringPress(cell);
    }

    public static void applyCheckIcon(TextCheckCell cell, int iconColor, int iconRes) {
        if (cell == null) return;
        cell.setColgramWrapTitle(true);
        cell.setColorfullIcon(iconColor, iconRes);
        attachSpringPress(cell);
    }

    /** Styles a two-line detail row with unified 30dp squircle icon badge so stacked sections align cleanly. */
    public static void applyDetailIcon(org.telegram.ui.Cells.TextDetailSettingsCell cell,
                                       CharSequence title, CharSequence value,
                                       int iconRes, int iconColor, boolean divider) {
        if (cell == null) return;
        cell.setMultilineDetail(true);
        cell.setTextAndValueAndIcon(title == null ? "" : title.toString(), value == null ? "" : value, iconRes, divider);
        if (cell.getChildCount() > 2 && cell.getChildAt(2) instanceof ImageView) {
            ImageView icon = (ImageView) cell.getChildAt(2);
            android.view.ViewGroup.LayoutParams rawLp = icon.getLayoutParams();
            if (rawLp instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rawLp;
                lp.width = AndroidUtilities.dp(30);
                lp.height = AndroidUtilities.dp(30);
                lp.gravity = (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP;
                lp.leftMargin = AndroidUtilities.dp(17);
                lp.rightMargin = AndroidUtilities.dp(17);
                lp.topMargin = AndroidUtilities.dp(13);
                icon.setLayoutParams(lp);
            }
            int pad = AndroidUtilities.dp(5);
            icon.setPadding(pad, pad, pad, pad);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setColorFilter(org.colgram.core.ColgramThemePolicy.contrastInkOn(iconColor), android.graphics.PorterDuff.Mode.SRC_IN);
            icon.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(9), iconColor));
        }
        attachSpringPress(cell);
    }

    public static void styleHeader(HeaderCell cell, int lightAccent, int darkAccent) {
        if (cell == null) return;
        boolean dark = Theme.isCurrentThemeDark();
        int raw = (dark && ColgramConfig.isCyberThemeEnabled()) ? 0xFFFF5566 : (dark ? darkAccent : lightAccent);
        int bg = Theme.getColor(Theme.key_windowBackgroundWhite);
        cell.setTextColor(org.colgram.core.ColgramThemePolicy.ensureAccentReadable(
                raw, bg, 0xFF7EC8FF, 0xFF1565C0, 4.5));
    }

    /** Applies Colgram's unified Obsidian / Porcelain / Cyber ActionBar styling cleanly. */
    public static void styleActionBar(org.telegram.ui.ActionBar.ActionBar actionBar) {
        if (actionBar == null) return;
        ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        actionBar.setBackgroundColor(pal.sheetBgTop);
        actionBar.setTitleColor(pal.textPrimary);
        actionBar.setItemsColor(pal.textPrimary, false);
        actionBar.setItemsBackgroundColor(pal.dark ? 0x1EFFFFFF : 0x140F172A, false);
        actionBar.setCastShadows(false);
    }

    /** Creates a properly themed, padded Telegram EditTextBoldCursor for AlertDialogs. */
    public static EditTextBoldCursor makeDialogEditText(Context ctx, Theme.ResourcesProvider rp, String hint, boolean multiline) {
        EditTextBoldCursor et = new EditTextBoldCursor(ctx);
        et.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        int textColor = Theme.getColor(Theme.key_dialogTextBlack, rp);
        int hintColor = Theme.getColor(Theme.key_dialogTextHint, rp);
        int lineColor = Theme.getColor(Theme.key_dialogInputField, rp);
        int activeLine = Theme.getColor(Theme.key_dialogInputFieldActivated, rp);
        et.setTextColor(textColor);
        et.setHintTextColor(hintColor);
        et.setCursorColor(activeLine);
        et.setCursorSize(dp(18));
        et.setCursorWidth(1.5f);
        et.setLineColors(lineColor, activeLine, Theme.getColor(Theme.key_text_RedBold, rp));
        if (hint != null) et.setHint(hint);
        if (multiline) {
            et.setSingleLine(false);
            et.setMinLines(3);
            et.setMaxLines(8);
            et.setGravity(Gravity.TOP | Gravity.START);
        } else {
            et.setSingleLine(true);
            et.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        }
        et.setPadding(dp(4), dp(8), dp(4), dp(8));
        return et;
    }

    /** Wraps multi-field AlertDialog forms in a padded ScrollView so keyboards/landscape never clip fields. */
    public static ScrollView wrapDialogScroll(Context ctx, View content) {
        ScrollView sv = new ScrollView(ctx);
        sv.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        sv.setClipToPadding(false);
        sv.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    /** Adds crisp subtle vertical rise on RecyclerListView entrance without ever dimming item alpha. */
    public static void attachDynamicListAnimations(RecyclerListView listView) {
        if (listView == null || Boolean.TRUE.equals(listView.getTag(0x636f6c02))) return;
        listView.setTag(0x636f6c02, Boolean.TRUE);
        listView.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            private int lastAnimatedPos = -1;

            @Override
            public void onChildViewAttachedToWindow(@NonNull View view) {
                view.animate().cancel();
                final float targetAlpha = (view.getAlpha() > 0f && view.getAlpha() < 1f) ? view.getAlpha() : 1f;
                view.setAlpha(targetAlpha);
                view.setScaleX(1f);
                view.setScaleY(1f);
                view.setTranslationX(0f);
                if (view instanceof HeroBannerCell || !listView.isShown()) {
                    view.setTranslationY(0f);
                    return;
                }
                int pos = listView.getChildAdapterPosition(view);
                if (pos > lastAnimatedPos && pos < 10) {
                    lastAnimatedPos = pos;
                    view.setTranslationY(dp(4));
                    view.animate()
                            .translationY(0f)
                            .setDuration(140L)
                            .setStartDelay(0L)
                            .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                            .withEndAction(() -> {
                                view.setAlpha(targetAlpha);
                                view.setTranslationY(0f);
                            })
                            .start();
                } else {
                    view.setTranslationY(0f);
                }
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
                view.animate().cancel();
                view.setAlpha(1f);
                view.setTranslationX(0f);
                view.setTranslationY(0f);
                view.setScaleX(1f);
                view.setScaleY(1f);
            }
        });
    }

    /** Attaches ripple background + tactile spring press-scale feedback to pill/card buttons. */
    public static void attachTactileFeedback(View target, float cornerDp, int fillColor, int strokeColor, int rippleColor) {
        if (target == null) return;
        GradientDrawable base = new GradientDrawable();
        base.setCornerRadius(dp(cornerDp));
        base.setColor(fillColor);
        if (strokeColor != 0) {
            base.setStroke(Math.max(1, dp(0.8f)), strokeColor);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(dp(cornerDp));
            mask.setColor(Color.WHITE);
            target.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), base, mask));
        } else {
            target.setBackground(base);
        }
        attachSpringPress(target);
    }

    public static void attachSpringPress(View target) {
        if (target == null || Boolean.TRUE.equals(target.getTag(0x636f6c01))) return;
        if (target instanceof TextCheckCell
                || target instanceof TextSettingsCell
                || target instanceof org.telegram.ui.Cells.TextDetailSettingsCell
                || target instanceof HeaderCell
                || target instanceof org.telegram.ui.Cells.DialogCell) {
            target.setScaleX(1f);
            target.setScaleY(1f);
            return;
        }
        target.setTag(0x636f6c01, Boolean.TRUE);
        target.setOnTouchListener((v, ev) -> {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.965f).scaleY(0.965f).setDuration(85L)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (ev.getX() < -dp(8) || ev.getY() < -dp(8) || ev.getX() > v.getWidth() + dp(8) || ev.getY() > v.getHeight() + dp(8)) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140L)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(220L)
                        .setInterpolator(new OvershootInterpolator(1.35f)).start();
            }
            return false;
        });
    }

    /** Smooth 3-dot sine-wave thinking indicator for Koliti AI chat bubbles. */
    public static final class TypingWaveView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator anim;
        private float phase = 0f;
        private int dotColor = COLOR_CRIMSON;

        public TypingWaveView(Context ctx) {
            super(ctx);
        }

        public TypingWaveView(Context ctx, int color) {
            super(ctx);
            this.dotColor = color;
        }

        public void setDotColor(int color) {
            dotColor = color;
            invalidate();
        }

        private void syncAnim() {
            boolean run = isAttachedToWindow() && getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE
                    && !org.telegram.messenger.ApplicationLoader.mainInterfacePaused;
            if (run && anim == null) {
                anim = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2.0));
                anim.setDuration(980L);
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setInterpolator(new LinearInterpolator());
                anim.addUpdateListener(a -> {
                    phase = (float) a.getAnimatedValue();
                    invalidate();
                });
                anim.start();
            } else if (!run && anim != null) {
                anim.cancel();
                anim = null;
            }
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            syncAnim();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(dp(42), dp(20));
        }

        @Override
        public void setVisibility(int visibility) {
            super.setVisibility(visibility);
            syncAnim();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            syncAnim();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cy = getHeight() * 0.54f;
            float baseR = dp(3.2f);
            float spacing = dp(11);
            float startX = (getWidth() - spacing * 2f) * 0.5f;
            for (int i = 0; i < 3; i++) {
                float s = (float) Math.sin(phase - i * 0.85f);
                float lift = Math.max(0f, s) * dp(4.5f);
                float scale = 0.82f + 0.28f * ((s + 1f) * 0.5f);
                int alpha = Math.round((0.45f + 0.55f * ((s + 1f) * 0.5f)) * 255f);
                paint.setColor(dotColor);
                paint.setAlpha(alpha);
                canvas.drawCircle(startX + i * spacing, cy - lift, baseR * scale, paint);
            }
        }
    }

    /**
     * Dynamic flagship HeroBannerCell for all Colgram menus:
     * - Living dual-layer animated liquid sine waves along the bottom edge + gentle micro-bubbles
     * - Soft cached specular glass sheen gliding smoothly across the card + ambient emblem glow
     * - Interactive liquid touch-ripple ring radiating from finger tap
     * - 100% lifecycle-aware: pauses immediately when hidden or in Battery Saver (0% background drain)
     */
    public static final class HeroBannerCell extends FrameLayout {
        private final LinearLayout card;
        private final ImageView badgeIcon;
        private final TextView titleView;
        private final TextView subtitleView;
        private final TextView statusPill;
        private final TextView metaLine;
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint waveCrestPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path[] cachedWavePaths = new android.graphics.Path[]{
                new android.graphics.Path(), new android.graphics.Path()
        };
        private final android.graphics.Path[] cachedCrestPaths = new android.graphics.Path[]{
                new android.graphics.Path(), new android.graphics.Path()
        };
        private int cachedWaveW;
        private int cachedWaveH;
        private float cachedWavePhase = -1f;
        private final Matrix sheenMatrix = new Matrix();
        private android.graphics.RadialGradient cachedGlow;
        private LinearGradient cachedSheen;
        private int cachedGlowW;
        private int cachedGlowH;
        private int cachedGlowAccent;
        private boolean cachedGlowDark;
        private int currentAccent = COLOR_VIOLET;
        private int lastIconRes;
        private String lastTitle = "";
        private String lastSubtitle = "";
        private String lastPill = "";
        private String[] lastChips;
        private ValueAnimator waveAnim;
        private float wavePhase = 0f;
        private long lastWaveDrawMs = 0L;
        private float rippleX = -1f;
        private float rippleY = -1f;
        private float rippleProgress = 1f;
        private ValueAnimator rippleAnim;

        public HeroBannerCell(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
            ));
            setPadding(dp(14), dp(10), dp(14), dp(8));

            waveCrestPaint.setStyle(Paint.Style.STROKE);
            waveCrestPaint.setStrokeWidth(Math.max(1f, dp(1.0f)));
            ripplePaint.setStyle(Paint.Style.STROKE);
            ripplePaint.setStrokeWidth(dp(1.6f));

            card = new LinearLayout(context) {
                @Override
                protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                    int w = getWidth();
                    int h = getHeight();
                    if (w <= 0 || h <= 0) return;

                    boolean dark = Theme.isCurrentThemeDark();
                    boolean cyber = dark && ColgramConfig.isCyberThemeEnabled();
                    int effAccent = cyber ? 0xFFFF3344 : currentAccent;
                    if (cachedGlow == null || cachedGlowW != w || cachedGlowH != h
                            || cachedGlowAccent != effAccent || cachedGlowDark != dark) {
                        cachedGlowW = w;
                        cachedGlowH = h;
                        cachedGlowAccent = effAccent;
                        cachedGlowDark = dark;
                        int glowCenter = withAlpha(effAccent, dark ? 0x2C : 0x18);
                        cachedGlow = new android.graphics.RadialGradient(
                                dp(38), dp(34), Math.max(dp(130), w * 0.48f),
                                new int[]{glowCenter, 0x00000000},
                                new float[]{0f, 1f},
                                Shader.TileMode.CLAMP
                        );
                        glowPaint.setShader(cachedGlow);
                        int sheenMid = dark ? 0x16FFFFFF : 0x24FFFFFF;
                        cachedSheen = new LinearGradient(
                                -w * 0.22f, 0, w * 0.22f, h * 0.6f,
                                new int[]{0x00FFFFFF, sheenMid, 0x00FFFFFF},
                                new float[]{0f, 0.5f, 1f},
                                Shader.TileMode.CLAMP
                        );
                        sheenPaint.setShader(cachedSheen);
                        float sheenFrac = (wavePhase * 1.6f) % 1.6f;
                        if (sheenFrac < 1.0f) {
                            sheenMatrix.setTranslate((sheenFrac * 1.8f - 0.4f) * w, 0f);
                            cachedSheen.setLocalMatrix(sheenMatrix);
                        }
                    }

                    // 1. Soft radial emblem ambient glow (card uses ViewOutlineProvider dp(16) HW clip)
                    canvas.drawRect(0, 0, w, h, glowPaint);

                    // 2. Calm cached dual-layer monochromatic sine waves
                    if (cachedWaveW != w || cachedWaveH != h || cachedWavePhase != wavePhase) {
                        cachedWaveW = w;
                        cachedWaveH = h;
                        cachedWavePhase = wavePhase;
                        float t = wavePhase * (float) (Math.PI * 2.0);
                        int steps = 28;
                        for (int layer = 0; layer < 2; layer++) {
                            float baseY = h - dp(layer == 0 ? 13f : 8f);
                            float amp = dp(layer == 0 ? 3.8f : 2.6f);
                            float phase = t + layer * 1.85f;
                            android.graphics.Path wp = cachedWavePaths[layer];
                            android.graphics.Path cp = cachedCrestPaths[layer];
                            wp.rewind();
                            cp.rewind();
                            wp.moveTo(0, h);
                            float y0 = baseY + (float) Math.sin(-phase) * amp;
                            wp.lineTo(0, y0);
                            cp.moveTo(0, y0);
                            float px = 0f;
                            float py = y0;
                            for (int i = 1; i <= steps; i++) {
                                float f = (float) i / steps;
                                float x = f * w;
                                float y = baseY + (float) Math.sin(f * Math.PI * (1.45 + layer * 0.35) - phase) * amp;
                                float mx = (px + x) * 0.5f;
                                float my = (py + y) * 0.5f;
                                wp.quadTo(px, py, mx, my);
                                cp.quadTo(px, py, mx, my);
                                px = x;
                                py = y;
                            }
                            wp.lineTo(w, py);
                            cp.lineTo(w, py);
                            wp.lineTo(w, h);
                            wp.close();
                        }
                        if (cachedSheen != null) {
                            float sheenFrac = (wavePhase * 1.6f) % 1.6f;
                            if (sheenFrac < 1.0f) {
                                sheenMatrix.setTranslate((sheenFrac * 1.8f - 0.4f) * w, 0f);
                                cachedSheen.setLocalMatrix(sheenMatrix);
                            }
                        }
                    }

                    for (int layer = 0; layer < 2; layer++) {
                        int wAlpha = dark ? (layer == 0 ? 0x18 : 0x22) : (layer == 0 ? 0x10 : 0x16);
                        wavePaint.setColor(withAlpha(layer == 0 ? effAccent : lighten(effAccent, 0.20f), wAlpha));
                        canvas.drawPath(cachedWavePaths[layer], wavePaint);

                        waveCrestPaint.setColor(withAlpha(lighten(effAccent, 0.30f), dark ? 0x3C : 0x28));
                        canvas.drawPath(cachedCrestPaths[layer], waveCrestPaint);
                    }

                    // 3. Gentle diagonal glass sheen glide
                    if (((wavePhase * 1.6f) % 1.6f) < 1.0f) {
                        canvas.drawRect(0, 0, w, h, sheenPaint);
                    }

                    // 5. Interactive liquid ripple ring when card is tapped
                    if (rippleProgress < 1f && rippleX >= 0f) {
                        float maxR = Math.max(w, h) * 0.75f;
                        float curR = maxR * rippleProgress;
                        int ra = Math.round((1f - rippleProgress) * (dark ? 110f : 75f));
                        ripplePaint.setColor(withAlpha(lighten(effAccent, 0.3f), ra));
                        canvas.drawCircle(rippleX, rippleY, curR, ripplePaint);
                    }
                }
            };
            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
                }
            });
            card.setClipToOutline(true);
            card.setWillNotDraw(false);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(15));
            card.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    triggerRipple(ev.getX(), ev.getY());
                }
                return false;
            });
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout topRow = new LinearLayout(context);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            badgeIcon = new ImageView(context);
            badgeIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            badgeIcon.setPadding(dp(9), dp(9), dp(9), dp(9));
            badgeIcon.setColorFilter(Color.WHITE);
            badgeIcon.setOnClickListener(ColgramEasterEggs::onHeroBadgeTap);
            attachSpringPress(badgeIcon);
            topRow.addView(badgeIcon, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

            LinearLayout textCol = new LinearLayout(context);
            textCol.setOrientation(LinearLayout.VERTICAL);
            topRow.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setMaxLines(2);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            subtitleView.setMaxLines(3);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            statusPill = new TextView(context);
            statusPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f);
            statusPill.setTypeface(AndroidUtilities.bold());
            statusPill.setPadding(dp(8), dp(4), dp(8), dp(4));
            statusPill.setMaxLines(1);
            statusPill.setMaxWidth(dp(128));
            statusPill.setEllipsize(TextUtils.TruncateAt.END);
            topRow.addView(statusPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

            metaLine = new TextView(context);
            metaLine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            metaLine.setMaxLines(2);
            metaLine.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(metaLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        private void triggerRipple(float x, float y) {
            rippleX = x;
            rippleY = y;
            if (rippleAnim != null) {
                rippleAnim.cancel();
            }
            rippleAnim = ValueAnimator.ofFloat(0f, 1f);
            rippleAnim.setDuration(520L);
            rippleAnim.setInterpolator(new DecelerateInterpolator());
            rippleAnim.addUpdateListener(a -> {
                rippleProgress = (float) a.getAnimatedValue();
                card.invalidate();
            });
            rippleAnim.start();
        }

        private void syncWaveAnim(boolean visible) {
            // Static crisp wave phase (0.35f) -> 0 invalidations/sec, 0% idle GPU/CPU drain.
            if (waveAnim != null) {
                waveAnim.cancel();
                waveAnim = null;
            }
            wavePhase = 0.35f;
            if (visible && isAttachedToWindow()) {
                card.invalidate();
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (getParent() instanceof RecyclerListView) {
                attachDynamicListAnimations((RecyclerListView) getParent());
            }
            syncWaveAnim(getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (waveAnim != null) {
                waveAnim.cancel();
                waveAnim = null;
            }
            if (rippleAnim != null) {
                rippleAnim.cancel();
                rippleAnim = null;
            }
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            syncWaveAnim(visibility == VISIBLE && getVisibility() == VISIBLE);
        }

        @Override
        protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            syncWaveAnim(visibility == VISIBLE && getWindowVisibility() == VISIBLE);
        }

        /** Re-applies Light / Dark / Cyber styling on live theme switch. */
        public void updateTheme() {
            if (lastIconRes != 0) {
                bind(lastIconRes, currentAccent, lastTitle, lastSubtitle, lastPill, lastChips);
            }
        }

        public void bind(int iconRes, int accentColor, String title, String subtitle, String pillText, String... chips) {
            lastIconRes = iconRes;
            currentAccent = accentColor;
            lastTitle = title;
            lastSubtitle = subtitle;
            lastPill = pillText;
            lastChips = chips;

            boolean dark = Theme.isCurrentThemeDark();
            boolean cyber = dark && ColgramConfig.isCyberThemeEnabled();
            int effectiveAccent = cyber ? 0xFFFF3344 : accentColor;
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

            int surfaceCol;
            int strokeCol;
            int titleCol;
            int subCol;
            int pillBgCol;
            int pillStrokeCol;
            int pillTextCol;
            int metaCol;

            if (cyber) {
                surfaceCol = 0xFF14161D;
                strokeCol = 0x1FFFFFFF;
                titleCol = 0xFFF8FAFC;
                subCol = 0xFF9EA6B8;
                pillBgCol = withAlpha(effectiveAccent, 0x24);
                pillStrokeCol = withAlpha(effectiveAccent, 0x55);
                pillTextCol = 0xFFFFB4BE;
                metaCol = 0xFF8C95A8;
            } else if (dark) {
                surfaceCol = ColgramAeroStyler.Palette.resolve().cardSurface;
                strokeCol = 0x1CFFFFFF;
                titleCol = 0xFFF3F6FC;
                subCol = 0xFF9AA5BA;
                pillBgCol = withAlpha(effectiveAccent, 0x24);
                pillStrokeCol = withAlpha(effectiveAccent, 0x4E);
                pillTextCol = lighten(effectiveAccent, 0.35f);
                metaCol = 0xFF8B97AD;
            } else {
                surfaceCol = 0xFFFFFFFF;
                strokeCol = 0x160F172A;
                titleCol = 0xFF0F172A;
                subCol = 0xFF526075;
                pillBgCol = withAlpha(effectiveAccent, 0x14);
                pillStrokeCol = withAlpha(effectiveAccent, 0x38);
                pillTextCol = darken(effectiveAccent, 0.16f);
                metaCol = 0xFF64748B;
            }

            titleCol = org.colgram.core.ColgramThemePolicy.ensureReadableOnSurface(
                    titleCol, surfaceCol, 0xFFF8FAFC, 0xFF0F172A, 7.0);
            subCol = org.colgram.core.ColgramThemePolicy.ensureReadableOnSurface(
                    subCol, surfaceCol, 0xFFDCE4F0, 0xFF2C3542, 4.5);
            metaCol = org.colgram.core.ColgramThemePolicy.ensureReadableOnSurface(
                    metaCol, surfaceCol, 0xFFD0DAE8, 0xFF334155, 4.5);
            int pillEffectiveBg = org.colgram.core.ColgramThemePolicy.compositeOver(pillBgCol, surfaceCol);
            pillTextCol = org.colgram.core.ColgramThemePolicy.ensureAccentReadable(
                    pillTextCol, pillEffectiveBg, 0xFFF8FAFC, 0xFF14181E, 4.5);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(surfaceCol);
            bg.setCornerRadius(dp(16));
            bg.setStroke(Math.max(1, dp(0.75f)), strokeCol);
            card.setBackground(bg);

            int effectiveIconRes = (!ColgramBuildFlags.KOLITI_ENABLED
                    && iconRes == org.telegram.messenger.R.drawable.koliti_avatar)
                    ? org.telegram.messenger.R.drawable.colgram_brand_logo
                    : iconRes;
            boolean fullColorAsset = (ColgramBuildFlags.KOLITI_ENABLED
                    && effectiveIconRes == org.telegram.messenger.R.drawable.koliti_avatar)
                    || effectiveIconRes == org.telegram.messenger.R.drawable.colgram_brand_logo;
            GradientDrawable badgeBg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{lighten(effectiveAccent, 0.08f), darken(effectiveAccent, 0.18f)}
            );
            badgeBg.setCornerRadius(dp(fullColorAsset ? 20 : 11));
            badgeIcon.setBackground(badgeBg);
            if (fullColorAsset) {
                badgeIcon.clearColorFilter();
                badgeIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                badgeIcon.setPadding(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f));
            } else {
                badgeIcon.setColorFilter(org.colgram.core.ColgramThemePolicy.contrastInkOn(effectiveAccent));
                badgeIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                badgeIcon.setPadding(dp(9), dp(9), dp(9), dp(9));
            }
            badgeIcon.setImageResource(effectiveIconRes);

            titleView.setTextColor(titleCol);
            titleView.setText(title);
            subtitleView.setTextColor(subCol);
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(TextUtils.isEmpty(subtitle) ? GONE : VISIBLE);

            if (TextUtils.isEmpty(pillText)) {
                statusPill.setVisibility(GONE);
            } else {
                GradientDrawable pillBg = new GradientDrawable();
                pillBg.setCornerRadius(dp(999));
                pillBg.setColor(pillBgCol);
                pillBg.setStroke(Math.max(1, dp(0.75f)), pillStrokeCol);
                statusPill.setBackground(pillBg);
                statusPill.setTextColor(pillTextCol);
                statusPill.setText(pillText);
                statusPill.setVisibility(VISIBLE);
            }

            StringBuilder meta = new StringBuilder();
            if (chips != null) {
                for (String c : chips) {
                    if (!TextUtils.isEmpty(c)) {
                        if (meta.length() > 0) meta.append(" · ");
                        meta.append(c);
                    }
                }
            }
            if (meta.length() == 0) {
                metaLine.setVisibility(GONE);
            } else {
                metaLine.setTextColor(metaCol);
                metaLine.setText(meta.toString());
                metaLine.setVisibility(VISIBLE);
            }
            cachedGlowAccent = 0; // refresh subtle radial tint cleanly
            card.invalidate();
        }
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int blendDark(int fg, int bg, float fgWeight) {
        float inv = 1f - fgWeight;
        int r = Math.min(255, Math.round(Color.red(fg) * fgWeight + Color.red(bg) * inv));
        int g = Math.min(255, Math.round(Color.green(fg) * fgWeight + Color.green(bg) * inv));
        int b = Math.min(255, Math.round(Color.blue(fg) * fgWeight + Color.blue(bg) * inv));
        return Color.rgb(r, g, b);
    }

    private static int lighten(int color, float factor) {
        int r = Math.min(255, Math.round(Color.red(color) + (255 - Color.red(color)) * factor));
        int g = Math.min(255, Math.round(Color.green(color) + (255 - Color.green(color)) * factor));
        int b = Math.min(255, Math.round(Color.blue(color) + (255 - Color.blue(color)) * factor));
        return Color.rgb(r, g, b);
    }

    private static int darken(int color, float factor) {
        float scale = 1f - factor;
        return Color.rgb(
                Math.max(0, Math.round(Color.red(color) * scale)),
                Math.max(0, Math.round(Color.green(color) * scale)),
                Math.max(0, Math.round(Color.blue(color) * scale))
        );
    }
}
