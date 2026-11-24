package org.telegram.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import org.colgram.core.ColgramAiConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Window-level living AI Orb orchestrator hosted inside LaunchActivity:
 * - Cleanly detaches on LaunchActivity.onDestroy() (zero Activity leaks)
 * - Always docks bottom-LEFT on list/settings/chat screens (never blocks right-aligned switches, values, or FABs)
 * - Respects rotation, split-screen, global soft keyboard, and direct-shown modal bottom sheets
 * - Magnetic spring snap to nearest safe horizontal rail after user drag
 */
public final class ColgramGlobalOrbController {

    private static int orbCanvasDp() {
        return org.colgram.core.ColgramDesignConfig.getKolitiOrbSizeDp();
    }

    private static int orbVisualInsetDp() {
        return Math.max(4, Math.round(orbCanvasDp() * 0.125f));
    }

    private static volatile ColgramGlobalOrbController instance;
    private static final AtomicInteger modalSuppressionCount = new AtomicInteger(0);

    private final LaunchActivity activity;
    private final FrameLayout overlayHost;
    private final ColgramAiOrbView orbView;
    private final Rect tmpRect = new Rect();
    private final Rect enterRect = new Rect();
    private final Rect kbRect = new Rect();
    private final int[] hostLoc = new int[2];
    private boolean orbEntranceAnimating = false;

    private ValueAnimator springAnimator;
    private float curX = -1f;
    private float curY = -1f;
    private float velX = 0f;
    private float velY = 0f;
    private float targetX = -1f;
    private float targetY = -1f;
    private float baseTargetX = -1f;
    private float baseTargetY = -1f;
    private float manualDragOffsetX = 0f;
    private float manualDragOffsetY = 0f;
    private long lastTickNs = 0L;
    private String lastScreenTag = "";
    private long lastChatDialogId = Long.MIN_VALUE;
    private boolean initializedPos = false;
    private boolean orbIntendedVisible = false;
    private boolean detached = false;
    private float lastChatInputBottomPx = 0f;
    private boolean lastKeyboardOpen = false;
    private boolean rememberedSnappedRight = false;

    private final Runnable deferredRelayout = this::repositionNow;

    private final View.OnLayoutChangeListener hostLayoutListener =
            (v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
                if ((r - l) != (oldR - oldL) || (b - t) != (oldB - oldT)) {
                    scheduleRelayout();
                }
            };

    private final ViewTreeObserver.OnGlobalLayoutListener keyboardListener = this::onGlobalLayoutChanged;

    private void onGlobalLayoutChanged() {
        if (detached || overlayHost == null) return;
        boolean kbOpen = isGlobalKeyboardVisible();
        if (kbOpen != lastKeyboardOpen) {
            lastKeyboardOpen = kbOpen;
            scheduleRelayout();
        }
    }

    private final View.OnAttachStateChangeListener hostAttachListener = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {}

        @Override
        public void onViewDetachedFromWindow(View v) {
            detachInternal();
            if (instance == ColgramGlobalOrbController.this) {
                instance = null;
            }
        }
    };

    private ColgramGlobalOrbController(LaunchActivity activity, FrameLayout overlayHost) {
        this.activity = activity;
        this.overlayHost = overlayHost;
        this.orbView = new ColgramAiOrbView(activity);

        orbView.setOnOrbClickListener(this::openAssistantSheet);
        orbView.setOnOrbLongClickListener(() -> {
            if (detached || activity == null || activity.isFinishing()) return;
            BaseFragment f = ColgramAiChatBridge.resolveTopFragment(activity);
            ChatActivity chat = ColgramAiChatBridge.resolveActiveChat(f);
            BaseFragment anchorFrag = f != null ? f : LaunchActivity.getSafeLastFragment();
            if (anchorFrag == null) return;
            ColgramAiSheet.showWithQuickPrompt(anchorFrag, chat, orbView,
                    chat != null ? "Сделай краткую и точную сводку последних событий в этом чате"
                            : "Что важного и непрочитанного есть во всех моих чатах?");
        });
        orbView.setDragListener(new ColgramAiOrbView.DragListener() {
            @Override
            public void onDragDelta(float dxPx, float dyPx) {
                if (springAnimator != null) {
                    springAnimator.cancel();
                    springAnimator = null;
                }
                velX = 0f;
                velY = 0f;
                curX = clampX(curX + dxPx);
                curY = clampY(curY + dyPx);
                orbView.setX(curX);
                orbView.setY(curY);
            }

            @Override
            public void onDragEnded() {
                int hostW = overlayHost != null ? overlayHost.getWidth() : AndroidUtilities.displaySize.x;
                int orbPx = dp(orbCanvasDp());
                float leftRail = dp(6);
                float rightRail = Math.max(leftRail, hostW - orbPx - dp(6));
                // Magnetic horizontal snap to nearest clean rail
                boolean snappedRight = (curX + orbPx * 0.5f >= hostW * 0.5f);
                rememberedSnappedRight = snappedRight;
                try {
                    activity.getSharedPreferences("colgram_design_studio", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("koliti_orb_snapped_right", snappedRight).apply();
                } catch (Throwable ignored) {}
                float snappedX = snappedRight ? rightRail : leftRail;
                float clampedY = clampY(curY);

                targetX = clampX(snappedX);
                targetY = clampedY;
                if (org.colgram.core.ColgramDesignConfig.getKolitiOrbDock()
                        == org.colgram.core.ColgramDesignConfig.DOCK_REMEMBER) {
                    baseTargetX = targetX;
                    manualDragOffsetX = 0f;
                } else {
                    manualDragOffsetX = (baseTargetX >= 0f) ? (targetX - baseTargetX) : 0f;
                }
                manualDragOffsetY = (baseTargetY >= 0f) ? (targetY - baseTargetY) : 0f;
                ColgramAiConfig.setOrbExtraOffsetDp(activity, manualDragOffsetY / Math.max(1f, AndroidUtilities.density));
                startSpringFlight();
            }
        });

        manualDragOffsetY = dp(ColgramAiConfig.getOrbExtraOffsetDp(activity));
        try {
            rememberedSnappedRight = activity.getSharedPreferences("colgram_design_studio", android.content.Context.MODE_PRIVATE)
                    .getBoolean("koliti_orb_snapped_right", false);
        } catch (Throwable ignored) {}

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(orbCanvasDp()), dp(orbCanvasDp()), Gravity.TOP | Gravity.LEFT);
        overlayHost.addView(orbView, lp);
        orbView.setVisibility(View.GONE);
        overlayHost.addOnLayoutChangeListener(hostLayoutListener);
        overlayHost.addOnAttachStateChangeListener(hostAttachListener);
        try {
            overlayHost.getViewTreeObserver().addOnGlobalLayoutListener(keyboardListener);
        } catch (Throwable ignored) {}
        // NOTE: Embedded Koliti GGUF weights (~1.5 GB) are unpacked lazily on first AI use,
        // never on cold start, so startup & Intro/Login stay instant with zero flash I/O stalls.
    }

    public static void attach(LaunchActivity activity, FrameLayout host) {
        if (activity == null || host == null) return;
        modalSuppressionCount.set(0);
        ColgramGlobalOrbController prev = instance;
        if (prev != null) {
            prev.detachInternal();
        }
        instance = new ColgramGlobalOrbController(activity, host);
        instance.scheduleRelayout();
    }

    public static ColgramGlobalOrbController getInstance() {
        return instance;
    }

    /** Called by LaunchActivity.onDestroy() to prevent Activity leaks and stop all animators. */
    public void detachIfHost(Activity target) {
        if (target != null && target != this.activity) return;
        detachInternal();
        if (instance == this) {
            instance = null;
        }
    }

    private void detachInternal() {
        if (detached) return;
        detached = true;
        if (springAnimator != null) {
            springAnimator.cancel();
            springAnimator = null;
        }
        if (overlayHost != null) {
            overlayHost.removeCallbacks(deferredRelayout);
            overlayHost.removeOnLayoutChangeListener(hostLayoutListener);
            overlayHost.removeOnAttachStateChangeListener(hostAttachListener);
            try {
                ViewTreeObserver vto = overlayHost.getViewTreeObserver();
                if (vto != null && vto.isAlive()) {
                    vto.removeOnGlobalLayoutListener(keyboardListener);
                }
            } catch (Throwable ignored) {}
            try {
                if (orbView != null) {
                    orbView.animate().cancel();
                    ViewGroup parent = (ViewGroup) orbView.getParent();
                    if (parent != null) parent.removeView(orbView);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Suppresses the floating orb while any direct-shown Colgram modal sheet is visible. */
    public static void pushModalSuppression() {
        modalSuppressionCount.incrementAndGet();
        ColgramGlobalOrbController ctrl = instance;
        if (ctrl != null) ctrl.scheduleRelayout();
    }

    public static void popModalSuppression() {
        int val = modalSuppressionCount.decrementAndGet();
        if (val < 0) modalSuppressionCount.set(0);
        ColgramGlobalOrbController ctrl = instance;
        if (ctrl != null) ctrl.scheduleRelayout();
    }

    public ColgramAiOrbView getOrbView() {
        return orbView;
    }

    public void notifyChatInputMetrics(float inputBottomPx) {
        if (detached) return;
        if (Math.abs(inputBottomPx - lastChatInputBottomPx) >= 2f) {
            lastChatInputBottomPx = inputBottomPx;
            scheduleRelayout();
        }
    }

    public void setHostPaused(boolean paused) {
        if (detached) return;
        if (paused && springAnimator != null) {
            springAnimator.cancel();
            springAnimator = null;
        }
        if (orbView != null) {
            orbView.setHostPaused(paused);
        }
        if (!paused) {
            scheduleRelayout();
        }
    }

    public void scheduleRelayout() {
        if (detached || overlayHost == null) return;
        overlayHost.removeCallbacks(deferredRelayout);
        overlayHost.post(deferredRelayout);
    }

    /** Applies custom Koliti orb size, opacity, dock side, custom avatar, and accent color immediately. */
    public void applyDesignConfig() {
        if (detached || overlayHost == null || orbView == null) return;
        int sz = dp(orbCanvasDp());
        ViewGroup.LayoutParams lp = orbView.getLayoutParams();
        if (lp != null && (lp.width != sz || lp.height != sz)) {
            lp.width = sz;
            lp.height = sz;
            orbView.setLayoutParams(lp);
        }
        orbView.applyDesignConfig();
        if (orbIntendedVisible && orbView.getVisibility() == View.VISIBLE) {
            orbView.setAlpha(org.colgram.core.ColgramDesignConfig.getKolitiOrbAlpha());
        }
        manualDragOffsetX = 0f;
        scheduleRelayout();
    }

    public void openAssistantSheet() {
        if (detached || activity == null || activity.isFinishing()) return;
        BaseFragment top = ColgramAiChatBridge.resolveTopFragment(activity);
        ChatActivity chat = ColgramAiChatBridge.resolveActiveChat(top);
        BaseFragment anchorFrag = top != null ? top : LaunchActivity.getSafeLastFragment();
        if (anchorFrag != null) {
            ColgramAiSheet.show(anchorFrag, chat, orbView);
        }
    }

    private boolean isGlobalKeyboardVisible() {
        try {
            if (overlayHost == null) return false;
            View root = overlayHost.getRootView();
            if (root == null || root.getHeight() <= 0) return false;
            overlayHost.getWindowVisibleDisplayFrame(kbRect);
            int obscured = root.getHeight() - kbRect.bottom;
            return obscured > dp(150);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void repositionNow() {
        if (detached || activity == null || overlayHost == null || activity.isFinishing()) return;

        if (!ColgramAiConfig.isOrbEnabled(activity) || modalSuppressionCount.get() > 0) {
            setOrbVisible(false);
            return;
        }

        int hostW = overlayHost.getWidth();
        int hostH = overlayHost.getHeight();
        if (hostW <= 0 || hostH <= 0) {
            overlayHost.postDelayed(deferredRelayout, 60L);
            return;
        }

        if (isGlobalKeyboardVisible()) {
            setOrbVisible(false);
            return;
        }

        BaseFragment top = ColgramAiChatBridge.resolveTopFragment(activity);
        if (shouldHideForFragment(top)) {
            setOrbVisible(false);
            return;
        }
        try {
            if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) {
                setOrbVisible(false);
                return;
            }
        } catch (Throwable ignored) {}
        if (BaseFragment.hasSheets(top)) {
            setOrbVisible(false);
            return;
        }
        try {
            if (top != null && top.getActionBar() != null
                    && (top.getActionBar().isActionModeShowed() || top.getActionBar().isSearchFieldVisible())) {
                setOrbVisible(false);
                return;
            }
        } catch (Throwable ignored) {}

        ChatActivity activeChat = ColgramAiChatBridge.resolveActiveChat(top);
        if (activeChat != null) {
            long did = activeChat.getDialogId();
            if (did != lastChatDialogId) {
                lastChatDialogId = did;
                lastChatInputBottomPx = 0f;
            }
            try {
                if (activeChat.getActionBar() != null && activeChat.getActionBar().isActionModeShowed()) {
                    setOrbVisible(false);
                    return;
                }
                if (activeChat.getChatActivityEnterView() != null
                        && (activeChat.getChatActivityEnterView().isPopupShowing()
                        || activeChat.getChatActivityEnterView().isKeyboardVisible())) {
                    setOrbVisible(false);
                    return;
                }
            } catch (Throwable ignored) {}
        }

        setOrbVisible(true);
        int orbPx = dp(orbCanvasDp());
        int insetPx = dp(orbVisualInsetDp());
        ViewGroup.LayoutParams curLp = orbView.getLayoutParams();
        if (curLp != null && (curLp.width != orbPx || curLp.height != orbPx)) {
            curLp.width = orbPx;
            curLp.height = orbPx;
            orbView.setLayoutParams(curLp);
        }
        float nx, ny;
        String screenTag;
        int dockMode = org.colgram.core.ColgramDesignConfig.getKolitiOrbDock();
        boolean preferRight = dockMode == org.colgram.core.ColgramDesignConfig.DOCK_RIGHT
                || (dockMode == org.colgram.core.ColgramDesignConfig.DOCK_REMEMBER && rememberedSnappedRight);

        if (activeChat != null && activeChat.chatInputViewsContainer != null
                && activeChat.fragmentView != null && activeChat.fragmentView.isShown()) {
            screenTag = "CHAT:" + activeChat.getDialogId();
            activeChat.fragmentView.getGlobalVisibleRect(tmpRect);
            overlayHost.getLocationOnScreen(hostLoc);

            float chatLeft = Math.max(0, tmpRect.left - hostLoc[0]);
            float chatBottom = tmpRect.bottom > 0 ? Math.min(hostH, tmpRect.bottom - hostLoc[1]) : hostH;
            float baseInputBottom = Math.max(lastChatInputBottomPx,
                    AndroidUtilities.navigationBarHeight + activeChat.chatInputViewsContainer.getInputBubbleHeight() + dp(18));
            if (baseInputBottom < dp(74)) {
                baseInputBottom = dp(74);
            }
            nx = preferRight ? (hostW - orbPx - dp(12) + insetPx) : (chatLeft + dp(10) - insetPx);
            ny = chatBottom - baseInputBottom - orbPx + insetPx;
            try {
                View enterView = activeChat.getChatActivityEnterView();
                if (enterView != null && enterView.isShown() && enterView.getGlobalVisibleRect(enterRect) && enterRect.top > 0) {
                    float aboveEnterY = (enterRect.top - hostLoc[1]) - orbPx - dp(6) + insetPx;
                    ny = Math.min(ny, aboveEnterY);
                }
            } catch (Throwable ignored) {}
        } else if (top instanceof DialogsActivity && top.fragmentView != null) {
            // Bottom-left above MainTabs dock, always clear of right Compose FAB
            screenTag = "DIALOGS";
            top.fragmentView.getGlobalVisibleRect(tmpRect);
            overlayHost.getLocationOnScreen(hostLoc);
            float dLeft = Math.max(0, tmpRect.left - hostLoc[0]);
            float dRight = tmpRect.right > 0 ? Math.min(hostW, tmpRect.right - hostLoc[0]) : hostW;
            float dBottom = tmpRect.bottom > 0 ? Math.min(hostH, tmpRect.bottom - hostLoc[1]) : hostH;
            nx = preferRight ? (dRight - orbPx - dp(14) + insetPx) : (dLeft + dp(14) - insetPx);
            ny = dBottom - AndroidUtilities.navigationBarHeight - dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 14) - orbPx + insetPx;
        } else if (top != null && top.fragmentView != null) {
            // Settings / TempMail / Profile / Colgram pages
            screenTag = top.getClass().getSimpleName();
            top.fragmentView.getGlobalVisibleRect(tmpRect);
            overlayHost.getLocationOnScreen(hostLoc);
            float fLeft = Math.max(0, tmpRect.left - hostLoc[0]);
            float fRight = tmpRect.right > 0 ? Math.min(hostW, tmpRect.right - hostLoc[0]) : hostW;
            float fBottom = tmpRect.bottom > 0 ? Math.min(hostH, tmpRect.bottom - hostLoc[1]) : hostH;
            boolean inTabs = isInsideMainTabs();
            int extraBottom = inTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 14) : dp(16);
            nx = preferRight ? (fRight - orbPx - dp(14) + insetPx) : (fLeft + dp(14) - insetPx);
            ny = fBottom - AndroidUtilities.navigationBarHeight - extraBottom - orbPx + insetPx;
        } else {
            screenTag = "DEFAULT";
            nx = preferRight ? (hostW - orbPx - dp(14) + insetPx) : (dp(14) - insetPx);
            ny = hostH - AndroidUtilities.navigationBarHeight - dp(96) - orbPx + insetPx;
        }

        if (!screenTag.equals(lastScreenTag)) {
            manualDragOffsetX = 0f;
            lastScreenTag = screenTag;
        }

        baseTargetX = clampX(nx);
        baseTargetY = clampY(ny);
        targetX = clampX(baseTargetX + manualDragOffsetX);
        targetY = clampY(baseTargetY + manualDragOffsetY);

        if (!initializedPos || curX < 0f || orbView.getVisibility() != View.VISIBLE) {
            initializedPos = true;
            curX = targetX;
            curY = targetY;
            orbView.setX(curX);
            orbView.setY(curY);
            return;
        }

        double dist = Math.hypot(targetX - curX, targetY - curY);
        if (dist > dp(16)) {
            orbView.setState(ColgramAiOrbView.State.FLYING);
        }
        startSpringFlight();
    }

    private void startSpringFlight() {
        if (detached) return;
        if (springAnimator != null && springAnimator.isRunning()) {
            return;
        }
        lastTickNs = System.nanoTime();
        springAnimator = ValueAnimator.ofFloat(0f, 1f);
        springAnimator.setDuration(100000L);
        springAnimator.setRepeatCount(ValueAnimator.INFINITE);
        springAnimator.setInterpolator(new LinearInterpolator());
        springAnimator.addUpdateListener(anim -> stepSpringPhysics());
        springAnimator.start();
    }

    private void stepSpringPhysics() {
        if (detached) return;
        long now = System.nanoTime();
        float dt = Math.min(0.032f, Math.max(0.004f, (now - lastTickNs) / 1_000_000_000f));
        lastTickNs = now;

        final float stiffness = 245f;
        final float damping = 24f;

        float ax = (targetX - curX) * stiffness - velX * damping;
        float ay = (targetY - curY) * stiffness - velY * damping;
        velX += ax * dt;
        velY += ay * dt;
        curX += velX * dt;
        curY += velY * dt;

        orbView.setX(curX);
        orbView.setY(curY);

        float speed = (float) Math.hypot(velX, velY);
        orbView.setFlightVelocity(velX * 0.015f, velY * 0.015f);
        if (speed > 240f) {
            orbView.setState(ColgramAiOrbView.State.FLYING);
        }

        if (Math.hypot(targetX - curX, targetY - curY) < 0.8f && speed < 8f) {
            curX = targetX;
            curY = targetY;
            velX = 0f;
            velY = 0f;
            orbView.setX(curX);
            orbView.setY(curY);
            orbView.setFlightVelocity(0f, 0f);
            orbView.setState(ColgramAiOrbView.State.IDLE);
            if (springAnimator != null) {
                springAnimator.cancel();
                springAnimator = null;
            }
        }
    }

    private void setOrbVisible(boolean visible) {
        final float targetAlpha = org.colgram.core.ColgramDesignConfig.getKolitiOrbAlpha();
        if (visible == orbIntendedVisible) {
            if (visible && orbView.getVisibility() != View.VISIBLE) {
                orbEntranceAnimating = false;
                orbView.setVisibility(View.VISIBLE);
                orbView.setAlpha(targetAlpha);
                orbView.setScaleX(1f);
                orbView.setScaleY(1f);
            } else if (visible && !orbEntranceAnimating) {
                orbView.setAlpha(targetAlpha);
            }
            return;
        }
        orbIntendedVisible = visible;
        orbView.animate().cancel();
        if (visible) {
            orbEntranceAnimating = true;
            orbView.setVisibility(View.VISIBLE);
            orbView.setScaleX(0.65f);
            orbView.setScaleY(0.65f);
            orbView.setAlpha(0f);
            orbView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(targetAlpha)
                    .setDuration(220L)
                    .setInterpolator(new OvershootInterpolator(1.35f))
                    .withEndAction(() -> orbEntranceAnimating = false)
                    .start();
        } else {
            orbEntranceAnimating = false;
            if (springAnimator != null) {
                springAnimator.cancel();
                springAnimator = null;
            }
            if (orbView.getVisibility() == View.VISIBLE) {
                orbView.animate()
                        .scaleX(0.70f)
                        .scaleY(0.70f)
                        .alpha(0f)
                        .setDuration(140L)
                        .setInterpolator(new DecelerateInterpolator())
                        .withEndAction(() -> {
                            if (!orbIntendedVisible) {
                                orbView.setVisibility(View.GONE);
                            }
                        })
                        .start();
            }
        }
    }

    private boolean shouldHideForFragment(BaseFragment f) {
        if (f == null) return true;
        if (f instanceof ColgramAiActivity || f instanceof QrActivity || f instanceof LoginActivity || f instanceof IntroActivity) {
            return true;
        }
        try {
            INavigationLayout layers = activity.getLayersActionBarLayout();
            if (layers != null && layers.getView() != null && layers.getView().getVisibility() == View.VISIBLE
                    && !layers.getFragmentStack().isEmpty()) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isInsideMainTabs() {
        try {
            BaseFragment f = LaunchActivity.getSafeLastFragment();
            return f instanceof MainTabsActivity;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private float clampX(float x) {
        int hostW = overlayHost != null ? overlayHost.getWidth() : AndroidUtilities.displaySize.x;
        float min = dp(2);
        float max = Math.max(min, hostW - dp(orbCanvasDp()) - dp(2));
        return Math.max(min, Math.min(max, x));
    }

    private float clampY(float y) {
        int hostH = overlayHost != null ? overlayHost.getHeight() : AndroidUtilities.displaySize.y;
        float min = AndroidUtilities.statusBarHeight + dp(52);
        float max = Math.max(min, hostH - dp(orbCanvasDp()) - dp(4));
        return Math.max(min, Math.min(max, y));
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
