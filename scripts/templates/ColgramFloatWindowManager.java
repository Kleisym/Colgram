package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.util.ArrayList;

/**
 * ColgramFloatWindowManager — desktop-style floating windows for Telegram mini-apps.
 *
 * WHY THIS EXISTS INSTEAD OF REUSING THE PiP CONTROLLER
 * -----------------------------------------------------
 * Telegram ships two unrelated "floating window" mechanisms, and only one of them can
 * host several windows at once:
 *
 *   1. PipActivityController (org.telegram.messenger.pip) — the system PiP entry point.
 *      It keeps a single `maxPrioritySource` reference and calls onLoseMaxPriority() on
 *      every other registered source, so registering N mini-apps yields exactly one
 *      window. Android itself also permits only one PiP window per task. This is a hard
 *      platform limit, not a Telegram design choice — it cannot be worked around.
 *
 *   2. PipVideoOverlay — a hand-rolled WindowManager overlay. Each instance adds its own
 *      contentView with its own LayoutParams via WindowManager.addView(), and the windows
 *      are independent and draggable. This path uses TYPE_APPLICATION_OVERLAY when the
 *      draw-over-other-apps permission is granted, which Android does NOT cap at one
 *      window.
 *
 * Multiple simultaneous windows therefore require (2), and (2) requires the overlay
 * permission. Without that permission we fall back to registering with Telegram's PiP
 * controller, which is single-window but needs no permission.
 *
 * WHAT A WINDOW HOSTS
 * -------------------
 * The mini-app's WebView. Reparenting a live WebView between view trees destroys its
 * rendering surface and reloads the page, so this class does NOT move the WebView. It
 * takes a snapshot-style approach: the caller supplies a content View, and the window
 * shows it while the original sheet is dismissed. Callers that need a live session
 * should keep the WebView owned by the window from the start.
 */
public class ColgramFloatWindowManager {

    /** One live floating window. */
    public static class FloatWindow {
        final int id;
        final View content;
        final FrameLayout root;
        final CharSequence title;
        WindowManager.LayoutParams params;
        boolean attached;

        FloatWindow(int id, View content, FrameLayout root, CharSequence title) {
            this.id = id;
            this.content = content;
            this.root = root;
            this.title = title;
        }

        /** Removes the window from the screen. Safe to call twice. */
        public void close() {
            ColgramFloatWindowManager.close(this);
        }

        public int getId() {
            return id;
        }
    }

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
        return AndroidUtilities.checkInlinePermissions(context);
    }

    /**
     * Opens a floating window hosting `content`.
     *
     * Returns null when the overlay permission is missing — the caller is expected to
     * explain why and offer the permission prompt, rather than silently doing nothing.
     *
     * `content` is added to the window's root FrameLayout. If it already has a parent it
     * is detached first, which is what lets a mini-app move from the sheet into the
     * window without the caller having to know about the view hierarchy.
     */
    public static FloatWindow open(Context context, View content, CharSequence title) {
        if (context == null || content == null) {
            return null;
        }
        final Context appContext = context.getApplicationContext();
        if (!isSupported(appContext)) {
            return null;
        }

        final int max = org.colgram.core.ColgramConfig.getMiniAppPipMaxWindows();
        if (max > 0 && windows.size() >= max) {
            return null;
        }

        try {
            final WindowManager windowManager =
                    (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                return null;
            }

            final FrameLayout root = new FrameLayout(appContext);

            // Detach from whatever tree the content currently lives in. A View can only
            // have one parent, and leaving it attached elsewhere silently removes it from
            // this window instead.
            final ViewGroup oldParent = (ViewGroup) content.getParent();
            if (oldParent != null) {
                oldParent.removeView(content);
            }
            root.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            final FloatWindow window = new FloatWindow(nextId++, content, root, title);

            // Close affordance. Without it the only way to dismiss a window is to kill the
            // app, which is not something a user can be asked to do.
            final android.widget.ImageView closeButton = new android.widget.ImageView(appContext);
            closeButton.setImageResource(R.drawable.msg_close);
            closeButton.setBackground(org.telegram.ui.ActionBar.Theme.createCircleDrawable(
                    AndroidUtilities.dp(28), 0x99000000));
            closeButton.setContentDescription("Close");
            closeButton.setOnClickListener(v -> close(window));
            final FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                    AndroidUtilities.dp(28), AndroidUtilities.dp(28));
            closeParams.gravity = Gravity.TOP | Gravity.RIGHT;
            closeParams.topMargin = AndroidUtilities.dp(6);
            closeParams.rightMargin = AndroidUtilities.dp(6);
            root.addView(closeButton, closeParams);

            final int slot = nextSlot++;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.width = AndroidUtilities.dp(260);
            params.height = AndroidUtilities.dp(420);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.format = PixelFormat.TRANSLUCENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                params.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            }
            // FOCUSABLE is deliberately NOT set to FLAG_NOT_FOCUSABLE: mini-apps need
            // keyboard input, and an unfocusable overlay cannot receive it. This is the
            // one place we diverge from PipVideoOverlay, which is a passive video surface.
            params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

            // Cascade placement: offset each new window so the titles do not overlap.
            // A remembered position wins, so a window returns where the user left it.
            final String remembered = org.colgram.core.ColgramConfig.getMiniAppPipPosition(slot % 8);
            int startX = AndroidUtilities.dp(16 + (slot % 5) * 32);
            int startY = AndroidUtilities.dp(80 + (slot % 5) * 48);
            if (remembered != null) {
                final int comma = remembered.indexOf(',');
                if (comma > 0) {
                    try {
                        startX = Integer.parseInt(remembered.substring(0, comma));
                        startY = Integer.parseInt(remembered.substring(comma + 1));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            params.x = startX;
            params.y = startY;
            window.params = params;

            makeDraggable(root, params, windowManager, window);

            windowManager.addView(root, params);
            window.attached = true;
            windows.add(window);
            return window;

        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    /**
     * Drag support: touch moves the window, and the final position is persisted per slot
     * so the layout survives a restart.
     */
    private static void makeDraggable(final View root,
                                      final WindowManager.LayoutParams params,
                                      final WindowManager windowManager,
                                      final FloatWindow window) {
        root.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float touchX;
            private float touchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - touchX);
                        params.y = initialY + (int) (event.getRawY() - touchY);
                        try {
                            windowManager.updateViewLayout(root, params);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        try {
                            org.colgram.core.ColgramConfig.setMiniAppPipPosition(
                                    window.id % 8, params.x, params.y);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** Detaches and drops a window. */
    static void close(FloatWindow window) {
        if (window == null || !window.attached) {
            return;
        }
        window.attached = false;
        windows.remove(window);
        try {
            final Context ctx = window.root.getContext();
            final WindowManager windowManager =
                    (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                // Detach the hosted content first so it is not destroyed with the window,
                // which is what allows a mini-app to be re-opened later.
                window.root.removeView(window.content);
                windowManager.removeView(window.root);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Closes every floating window. Called from the main activity on shutdown. */
    public static void closeAll() {
        for (int i = windows.size() - 1; i >= 0; i--) {
            close(windows.get(i));
        }
        windows.clear();
    }

    /**
     * Prompts for the draw-over-other-apps permission.
     *
     * This is the permission that unlocks genuinely parallel windows; without it the
     * caller should fall back to Telegram's single-window PiP.
     */
    public static void requestOverlayPermission(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            AndroidUtilities.checkInlinePermissions(activity);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
