package org.telegram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.colgram.core.ColgramThemePolicy;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * ColgramFloatWindowManager — true multi-window resizable floating overlays for Telegram Mini Apps.
 *
 * Supports:
 * 1) Free continuous corner drag-to-resize (bottom-right visible grip handle) between min/max screen bounds
 * 2) Explicit "-" (shrink) and "+" (enlarge) header buttons + title tap collapse-to-pill
 * 3) Restore-to-full-sheet without reloading the live WebView, z-order bring-to-front, and auto bounds clamping
 * 4) Non-modal multi-window interaction (FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH) with dynamic IME focus
 */
public class ColgramFloatWindowManager {

    public interface Callbacks {
        void onRestoreFull();
        void onClosed();
    }

    public static class FloatWindow {
        final int id;
        final String appKey;
        final View content;
        final FrameLayout root;
        final LinearLayout bodyHost;
        final View bodyContainer;
        final View resizeGrip;
        final TextView titleView;
        final CharSequence title;
        final Callbacks callbacks;
        WindowManager.LayoutParams params;
        WindowManager windowManager;
        boolean attached;
        boolean collapsed;
        int expandedWidthPx;
        int expandedHeightPx;

        FloatWindow(int id, String appKey, View content, FrameLayout root,
                    LinearLayout bodyHost, View bodyContainer, View resizeGrip, TextView titleView,
                    CharSequence title, Callbacks callbacks) {
            this.id = id;
            this.appKey = appKey;
            this.content = content;
            this.root = root;
            this.bodyHost = bodyHost;
            this.bodyContainer = bodyContainer;
            this.resizeGrip = resizeGrip;
            this.titleView = titleView;
            this.title = title;
            this.callbacks = callbacks;
        }

        public void close() {
            ColgramFloatWindowManager.close(this, true);
        }

        /** Detaches only the overlay chrome so the caller can re-attach {@code content} live. */
        public void closeKeepingContent() {
            ColgramFloatWindowManager.close(this, false);
        }

        public int getId() {
            return id;
        }

        public void bringToFront() {
            ColgramFloatWindowManager.bringToFront(this);
        }
    }

    private static final int MIN_WIDTH_DP = 164;
    private static final int MIN_HEIGHT_DP = 196;
    private static final int DEFAULT_WIDTH_DP = 268;
    private static final int DEFAULT_HEIGHT_DP = 400;
    private static final int STEP_WIDTH_DP = 48;
    private static final int STEP_HEIGHT_DP = 72;

    private static final ArrayList<FloatWindow> windows = new ArrayList<>();
    private static int nextId = 1;
    private static int nextSlot = 0;

    public static int getWindowCount() {
        return windows.size();
    }

    public static ArrayList<FloatWindow> getWindows() {
        return new ArrayList<>(windows);
    }

    public static boolean isSupported(Context context) {
        return context != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context));
    }

    public static FloatWindow open(Context context, View content, CharSequence title) {
        return open(context, content, title, null);
    }

    public static FloatWindow open(Context context, View content, CharSequence title, Callbacks callbacks) {
        if (context == null || content == null) {
            return null;
        }
        final Context appContext = context.getApplicationContext();
        if (!isSupported(appContext)) {
            return null;
        }

        for (int i = 0; i < windows.size(); i++) {
            FloatWindow existing = windows.get(i);
            if (existing.content == content && existing.attached) {
                if (existing.collapsed) {
                    toggleCollapse(existing);
                }
                bringToFront(existing);
                return existing;
            }
        }

        final int max = org.colgram.core.ColgramConfig.getMiniAppPipMaxWindows();
        if (max > 0 && windows.size() >= max) {
            return null;
        }

        ViewGroup oldParent = null;
        ViewGroup.LayoutParams oldLayoutParams = null;
        FrameLayout root = null;
        WindowManager windowManager = null;
        try {
            windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return null;
            }

            final String appKey = (title != null ? title.toString() : "miniapp");
            final int bgColor = Theme.getColor(Theme.key_dialogBackground);
            final int barColor = Theme.getColor(Theme.key_actionBarDefault);
            final int borderColor = Theme.getColor(Theme.key_divider);
            final int resolvedBar = barColor != 0 ? barColor : 0xff16181e;
            final int barInk = ColgramThemePolicy.contrastInkOn(resolvedBar);

            root = new FrameLayout(appContext);
            android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
            cardBg.setColor(bgColor != 0 ? bgColor : 0xff16181e);
            cardBg.setCornerRadius(AndroidUtilities.dp(14));
            cardBg.setStroke(AndroidUtilities.dp(1), borderColor != 0 ? borderColor : 0xff2f3644);
            root.setBackground(cardBg);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                root.setClipToOutline(true);
                root.setElevation(AndroidUtilities.dp(10));
            }

            final LinearLayout column = new LinearLayout(appContext);
            column.setOrientation(LinearLayout.VERTICAL);

            final LinearLayout headerBar = new LinearLayout(appContext);
            headerBar.setOrientation(LinearLayout.HORIZONTAL);
            headerBar.setGravity(Gravity.CENTER_VERTICAL);
            headerBar.setBackgroundColor(resolvedBar);
            headerBar.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(4), 0);

            final TextView titleView = new TextView(appContext);
            titleView.setText(TextUtils.isEmpty(title) ? "Mini App" : title);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setTextColor(barInk);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            headerBar.addView(titleView, LayoutHelper.createLinear(
                    0, LayoutHelper.WRAP_CONTENT, 1.0f, 2, 0, 4, 0));

            oldParent = (ViewGroup) content.getParent();
            if (oldParent != null) {
                oldLayoutParams = content.getLayoutParams();
                oldParent.removeView(content);
            }

            final FrameLayout bodyWrapper = new FrameLayout(appContext);
            bodyWrapper.addView(content, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            // Visible diagonal corner resize grip (bottom-right) for free continuous resizing
            final CornerResizeGripView resizeGrip = new CornerResizeGripView(appContext, barInk);
            resizeGrip.setContentDescription("Изменить размер окна");

            final FloatWindow window = new FloatWindow(
                    nextId++, appKey, content, root, column, bodyWrapper, resizeGrip, titleView, title, callbacks);
            window.windowManager = windowManager;

            // Header action controls: Shrink (-), Enlarge (+), Restore full (<->), Close (X)
            // Tapping the header/title (without dragging) collapses or expands the window cleanly!
            TextView shrinkBtn = makeHeaderActionButton(appContext, "\u2212", barInk, "Уменьшить");
            shrinkBtn.setOnClickListener(v -> {
                bringToFront(window);
                if (window.collapsed) {
                    toggleCollapse(window);
                } else {
                    stepResize(window, -1);
                }
            });
            shrinkBtn.setOnLongClickListener(v -> {
                bringToFront(window);
                toggleCollapse(window);
                return true;
            });
            headerBar.addView(shrinkBtn, LayoutHelper.createLinear(25, 25, 1, 0, 1, 0));

            TextView enlargeBtn = makeHeaderActionButton(appContext, "+", barInk, "Увеличить");
            enlargeBtn.setOnClickListener(v -> {
                bringToFront(window);
                if (window.collapsed) {
                    toggleCollapse(window);
                } else {
                    stepResize(window, +1);
                }
            });
            headerBar.addView(enlargeBtn, LayoutHelper.createLinear(25, 25, 1, 0, 1, 0));

            if (callbacks != null) {
                TextView restoreBtn = makeHeaderActionButton(appContext, "\u2922", barInk, "На весь экран");
                restoreBtn.setOnClickListener(v -> {
                    close(window, false);
                    try {
                        callbacks.onRestoreFull();
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                });
                headerBar.addView(restoreBtn, LayoutHelper.createLinear(25, 25, 1, 0, 1, 0));
            }

            ImageView closeButton = new ImageView(appContext);
            closeButton.setImageResource(R.drawable.msg_close);
            closeButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            closeButton.setColorFilter(barInk, android.graphics.PorterDuff.Mode.SRC_IN);
            closeButton.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(barInk, 0.16f), AndroidUtilities.dp(13), AndroidUtilities.dp(13)));
            closeButton.setContentDescription("Закрыть");
            closeButton.setOnClickListener(v -> close(window, true));
            headerBar.addView(closeButton, LayoutHelper.createLinear(25, 25, 2, 0, 0, 0));

            column.addView(headerBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 34));
            column.addView(bodyWrapper, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1.0f));
            root.addView(column, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            root.addView(resizeGrip, LayoutHelper.createFrame(34, 34, Gravity.BOTTOM | Gravity.RIGHT));

            final int slotKey = Math.abs(appKey.hashCode() % 12) * 100 + (nextSlot++ % 8);
            DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
            int initW = Math.min(AndroidUtilities.dp(DEFAULT_WIDTH_DP), maxWindowWidthPx(dm));
            int initH = Math.min(AndroidUtilities.dp(DEFAULT_HEIGHT_DP), maxWindowHeightPx(dm));

            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.width = Math.max(AndroidUtilities.dp(MIN_WIDTH_DP), initW);
            params.height = Math.max(AndroidUtilities.dp(MIN_HEIGHT_DP), initH);
            window.expandedWidthPx = params.width;
            window.expandedHeightPx = params.height;
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.format = PixelFormat.TRANSLUCENT;
            params.type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

            final String remembered = org.colgram.core.ColgramConfig.getMiniAppPipPosition(slotKey);
            int startX = AndroidUtilities.dp(14 + (windows.size() % 4) * 24);
            int startY = AndroidUtilities.dp(72 + (windows.size() % 4) * 40);
            if (remembered != null) {
                int comma = remembered.indexOf(',');
                if (comma > 0) {
                    try {
                        startX = Integer.parseInt(remembered.substring(0, comma));
                        startY = Integer.parseInt(remembered.substring(comma + 1));
                    } catch (NumberFormatException ignored) {}
                }
            }
            params.x = Math.max(0, Math.min(startX, Math.max(0, dm.widthPixels - params.width)));
            params.y = Math.max(0, Math.min(startY, Math.max(0, dm.heightPixels - params.height)));
            window.params = params;

            root.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    setWindowFocusable(window, false);
                } else if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    bringToFront(window);
                    setWindowFocusable(window, true);
                }
                return false;
            });

            makeDraggable(headerBar, root, params, windowManager, window, slotKey);
            makeResizable(resizeGrip, root, params, windowManager, window);

            windowManager.addView(root, params);
            window.attached = true;
            windows.add(window);

            root.setAlpha(0f);
            root.setScaleX(0.9f);
            root.setScaleY(0.9f);
            root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();

            return window;

        } catch (Throwable t) {
            try {
                if (root != null && root.getParent() != null && windowManager != null) {
                    windowManager.removeViewImmediate(root);
                }
                if (content.getParent() instanceof ViewGroup) {
                    ((ViewGroup) content.getParent()).removeView(content);
                }
                if (oldParent != null && content.getParent() == null) {
                    oldParent.addView(content, oldLayoutParams);
                }
            } catch (Throwable restoreFailure) {
                FileLog.e(restoreFailure);
            }
            FileLog.e(t);
            return null;
        }
    }

    private static int maxWindowWidthPx(DisplayMetrics dm) {
        return Math.max(AndroidUtilities.dp(MIN_WIDTH_DP), Math.min(dm.widthPixels - AndroidUtilities.dp(12), (int) (dm.widthPixels * 0.95f)));
    }

    private static int maxWindowHeightPx(DisplayMetrics dm) {
        return Math.max(AndroidUtilities.dp(MIN_HEIGHT_DP), Math.min(dm.heightPixels - AndroidUtilities.dp(56), (int) (dm.heightPixels * 0.86f)));
    }

    private static TextView makeHeaderActionButton(Context ctx, String label, int ink, String desc) {
        TextView btn = new TextView(ctx);
        btn.setText(label);
        btn.setTextColor(ink);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        btn.setTypeface(AndroidUtilities.bold());
        btn.setGravity(Gravity.CENTER);
        btn.setContentDescription(desc);
        btn.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(ink, 0.16f), AndroidUtilities.dp(13), AndroidUtilities.dp(13)));
        return btn;
    }

    private static void setWindowFocusable(FloatWindow window, boolean focusable) {
        if (window == null || !window.attached || window.windowManager == null || window.params == null) {
            return;
        }
        int want = focusable
                ? (window.params.flags & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                : (window.params.flags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        if (want != window.params.flags) {
            window.params.flags = want;
            try {
                window.windowManager.updateViewLayout(window.root, window.params);
            } catch (Throwable ignored) {}
        }
    }

    private static void animateWindowSize(final FloatWindow window, int targetW, int targetH, Runnable onEnd) {
        if (window == null || !window.attached || window.windowManager == null || window.params == null) {
            if (onEnd != null) onEnd.run();
            return;
        }
        DisplayMetrics dm = window.root.getContext().getResources().getDisplayMetrics();
        final int clampedW = window.collapsed
                ? targetW
                : Math.max(AndroidUtilities.dp(MIN_WIDTH_DP), Math.min(maxWindowWidthPx(dm), targetW));
        final int clampedH = window.collapsed
                ? targetH
                : Math.max(AndroidUtilities.dp(MIN_HEIGHT_DP), Math.min(maxWindowHeightPx(dm), targetH));
        final int startW = window.params.width;
        final int startH = window.params.height;
        final int startX = window.params.x;
        final int startY = window.params.y;
        final int targetX = Math.max(0, Math.min(startX, Math.max(0, dm.widthPixels - clampedW - AndroidUtilities.dp(4))));
        final int targetY = Math.max(0, Math.min(startY, Math.max(0, dm.heightPixels - clampedH - AndroidUtilities.dp(16))));

        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(180);
        anim.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        anim.addUpdateListener(a -> {
            if (!window.attached) {
                a.cancel();
                return;
            }
            float f = (float) a.getAnimatedValue();
            window.params.width = Math.round(startW + (clampedW - startW) * f);
            window.params.height = Math.round(startH + (clampedH - startH) * f);
            window.params.x = Math.round(startX + (targetX - startX) * f);
            window.params.y = Math.round(startY + (targetY - startY) * f);
            try {
                window.windowManager.updateViewLayout(window.root, window.params);
            } catch (Throwable ignored) {}
        });
        if (onEnd != null) {
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    onEnd.run();
                }
            });
        }
        anim.start();
    }

    private static void toggleCollapse(FloatWindow window) {
        if (window == null || !window.attached || window.windowManager == null) return;
        window.collapsed = !window.collapsed;
        if (window.collapsed) {
            window.expandedWidthPx = window.params.width;
            window.expandedHeightPx = window.params.height;
            setWindowFocusable(window, false);
            if (window.resizeGrip != null) window.resizeGrip.setVisibility(View.GONE);
            window.bodyContainer.animate().alpha(0f).setDuration(110).withEndAction(() -> {
                if (window.collapsed) window.bodyContainer.setVisibility(View.GONE);
            }).start();
            animateWindowSize(window, AndroidUtilities.dp(178), AndroidUtilities.dp(36), null);
        } else {
            if (window.resizeGrip != null) window.resizeGrip.setVisibility(View.VISIBLE);
            window.bodyContainer.setAlpha(0f);
            window.bodyContainer.setVisibility(View.VISIBLE);
            window.bodyContainer.animate().alpha(1f).setDuration(170).start();
            int targetW = window.expandedWidthPx > AndroidUtilities.dp(MIN_WIDTH_DP)
                    ? window.expandedWidthPx : AndroidUtilities.dp(DEFAULT_WIDTH_DP);
            int targetH = window.expandedHeightPx > AndroidUtilities.dp(MIN_HEIGHT_DP)
                    ? window.expandedHeightPx : AndroidUtilities.dp(DEFAULT_HEIGHT_DP);
            animateWindowSize(window, targetW, targetH, null);
        }
    }

    private static void stepResize(FloatWindow window, int dir) {
        if (window == null || !window.attached || window.windowManager == null || window.params == null) return;
        DisplayMetrics dm = window.root.getContext().getResources().getDisplayMetrics();
        int minW = AndroidUtilities.dp(MIN_WIDTH_DP);
        int minH = AndroidUtilities.dp(MIN_HEIGHT_DP);
        int maxW = maxWindowWidthPx(dm);
        int maxH = maxWindowHeightPx(dm);
        int nextW = Math.max(minW, Math.min(maxW, window.params.width + dir * AndroidUtilities.dp(STEP_WIDTH_DP)));
        int nextH = Math.max(minH, Math.min(maxH, window.params.height + dir * AndroidUtilities.dp(STEP_HEIGHT_DP)));
        if (dir < 0 && window.params.width <= minW + AndroidUtilities.dp(8) && window.params.height <= minH + AndroidUtilities.dp(8)) {
            toggleCollapse(window);
            return;
        }
        window.expandedWidthPx = nextW;
        window.expandedHeightPx = nextH;
        animateWindowSize(window, nextW, nextH, null);
    }

    public static void bringToFront(FloatWindow window) {
        if (window == null || !window.attached || window.windowManager == null) return;
        int idx = windows.indexOf(window);
        if (idx >= 0 && idx == windows.size() - 1) return;
        try {
            window.windowManager.removeViewImmediate(window.root);
            window.windowManager.addView(window.root, window.params);
            windows.remove(window);
            windows.add(window);
        } catch (Throwable ignored) {}
    }

    private static void makeResizable(final View resizeGrip,
                                      final View root,
                                      final WindowManager.LayoutParams params,
                                      final WindowManager windowManager,
                                      final FloatWindow window) {
        resizeGrip.setOnTouchListener(new View.OnTouchListener() {
            private int startW;
            private int startH;
            private float startRawX;
            private float startRawY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        bringToFront(window);
                        startW = params.width;
                        startH = params.height;
                        startRawX = event.getRawX();
                        startRawY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        DisplayMetrics dm = root.getContext().getResources().getDisplayMetrics();
                        int minW = AndroidUtilities.dp(MIN_WIDTH_DP);
                        int minH = AndroidUtilities.dp(MIN_HEIGHT_DP);
                        int maxW = maxWindowWidthPx(dm);
                        int maxH = maxWindowHeightPx(dm);
                        int dx = Math.round(event.getRawX() - startRawX);
                        int dy = Math.round(event.getRawY() - startRawY);
                        params.width = Math.max(minW, Math.min(maxW, startW + dx));
                        params.height = Math.max(minH, Math.min(maxH, startH + dy));
                        window.expandedWidthPx = params.width;
                        window.expandedHeightPx = params.height;
                        try {
                            windowManager.updateViewLayout(root, params);
                        } catch (Throwable ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private static void makeDraggable(final View dragHandle,
                                      final View root,
                                      final WindowManager.LayoutParams params,
                                      final WindowManager windowManager,
                                      final FloatWindow window,
                                      final int slotKey) {
        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float touchX;
            private float touchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        bringToFront(window);
                        initialX = params.x;
                        initialY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - touchX;
                        float dy = event.getRawY() - touchY;
                        if (!moved && (Math.abs(dx) > AndroidUtilities.dp(5) || Math.abs(dy) > AndroidUtilities.dp(5))) {
                            moved = true;
                        }
                        if (!moved) return true;
                        DisplayMetrics dm = root.getContext().getResources().getDisplayMetrics();
                        int maxX = Math.max(0, dm.widthPixels - AndroidUtilities.dp(72));
                        int maxY = Math.max(0, dm.heightPixels - AndroidUtilities.dp(48));
                        params.x = Math.max(-AndroidUtilities.dp(40), Math.min(maxX, initialX + (int) dx));
                        params.y = Math.max(0, Math.min(maxY, initialY + (int) dy));
                        try {
                            windowManager.updateViewLayout(root, params);
                        } catch (Throwable ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            toggleCollapse(window);
                        } else {
                            try {
                                org.colgram.core.ColgramConfig.setMiniAppPipPosition(slotKey, params.x, params.y);
                            } catch (Throwable ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** Crisp 3-diagonal-line corner resize grip in the bottom-right corner of the floating window. */
    private static final class CornerResizeGripView extends View {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CornerResizeGripView(Context ctx, int ink) {
            super(ctx);
            linePaint.setColor((ink & 0x00FFFFFF) | 0xCC000000);
            linePaint.setStrokeWidth(Math.max(1.5f, AndroidUtilities.dp(1.6f)));
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            bgPaint.setColor(0x55000000);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            // Subtle rounded corner badge so the grip is clearly visible over light OR dark Mini Apps
            float r = AndroidUtilities.dp(10);
            canvas.drawCircle(w - AndroidUtilities.dp(4), h - AndroidUtilities.dp(4), r * 1.45f, bgPaint);
            float pad = AndroidUtilities.dp(5);
            float s1 = AndroidUtilities.dp(6);
            float s2 = AndroidUtilities.dp(11);
            float s3 = AndroidUtilities.dp(16);
            canvas.drawLine(w - pad - s1, h - pad, w - pad, h - pad - s1, linePaint);
            canvas.drawLine(w - pad - s2, h - pad, w - pad, h - pad - s2, linePaint);
            canvas.drawLine(w - pad - s3, h - pad, w - pad, h - pad - s3, linePaint);
        }
    }

    static void close(FloatWindow window, boolean notifyClosed) {
        if (window == null || !window.attached) {
            return;
        }
        window.attached = false;
        windows.remove(window);
        if (notifyClosed && window.root != null && window.root.getParent() != null) {
            window.root.animate().alpha(0f).scaleX(0.90f).scaleY(0.90f)
                    .setDuration(130)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .withEndAction(() -> detachWindowImmediate(window, true))
                    .start();
            return;
        }
        detachWindowImmediate(window, notifyClosed);
    }

    private static void detachWindowImmediate(FloatWindow window, boolean notifyClosed) {
        try {
            WindowManager wm = window.windowManager != null
                    ? window.windowManager
                    : (WindowManager) window.root.getContext().getSystemService(Context.WINDOW_SERVICE);
            if (window.content.getParent() instanceof ViewGroup) {
                ((ViewGroup) window.content.getParent()).removeView(window.content);
            }
            if (wm != null && window.root.getParent() != null) {
                wm.removeViewImmediate(window.root);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        if (notifyClosed && window.callbacks != null) {
            try {
                window.callbacks.onClosed();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    public static void closeAll() {
        for (int i = windows.size() - 1; i >= 0; i--) {
            FloatWindow w = windows.remove(i);
            if (w != null) {
                w.attached = false;
                detachWindowImmediate(w, true);
            }
        }
        windows.clear();
    }

    public static boolean requestOverlayPermission(Activity activity) {
        if (activity == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(activity)) {
            return true;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
            }
            activity.startActivity(intent);
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + activity.getPackageName())));
                return true;
            } catch (Throwable fallbackFailure) {
                FileLog.e(fallbackFailure);
                return false;
            }
        }
    }
}
