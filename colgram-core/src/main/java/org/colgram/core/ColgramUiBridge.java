package org.colgram.core;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * Knows which screen is currently on top, without this module depending on Telegram.
 *
 * WHY THIS EXISTS
 * ---------------
 * {@code colgram-core} compiles before {@code TMessagesProj}, so it cannot reference
 * {@code org.telegram.ui.ActionBar.BaseFragment} or {@code LaunchActivity} at compile
 * time. Several Telegram APIs that plugin features need — notably
 * {@code SendMessagesHelper.editMessage()} — demand a live {@code BaseFragment} and
 * silently do nothing (or NPE deep inside Telegram) when handed one that is not
 * attached to a resumed activity.
 *
 * Rather than have every caller re-implement Activity-tracking, this class registers a
 * single {@link Application.ActivityLifecycleCallbacks} and exposes the top activity.
 * Callers reflect on it and pass the result wherever a fragment is required; Telegram's
 * fragments expose {@code getParentActivity()}, so an attached fragment resolves to the
 * same activity.
 *
 * Everything here is defensive: a missing registration is a normal state (the app may
 * not have started an activity yet) and never an exception.
 */
public final class ColgramUiBridge {

    private static final String TAG = "ColgramUiBridge";

    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static volatile boolean registered = false;
    private static volatile boolean isForeground = false;

    private ColgramUiBridge() {}

    /**
     * Start tracking the top activity. Safe to call repeatedly.
     *
     * @param app the application instance (usually obtained via reflection from the
     *            calling Telegram class, since this module has no context of its own)
     */
    public static void install(Application app) {
        if (app == null || registered) return;
        try {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    currentActivity = new WeakReference<>(activity);
                }

                @Override
                public void onActivityStarted(Activity activity) {
                    currentActivity = new WeakReference<>(activity);
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    currentActivity = new WeakReference<>(activity);
                    isForeground = true;
                }

                @Override
                public void onActivityPaused(Activity activity) {
                    // Do not clear here: a paused activity is still the right anchor for a
                    // pending edit/delete, and clearing would break edits triggered from a
                    // dialog that was just dismissed.
                }

                @Override
                public void onActivityStopped(Activity activity) {
                    if (isCurrent(activity)) {
                        currentActivity = new WeakReference<>(null);
                    }
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    if (isCurrent(activity)) {
                        currentActivity = new WeakReference<>(null);
                    }
                }
            });
            registered = true;
        } catch (Throwable t) {
            Log.w(TAG, "could not register activity lifecycle callbacks: " + t.getMessage());
        }
    }

    private static boolean isCurrent(Activity activity) {
        Activity cur = currentActivity.get();
        return cur != null && cur == activity;
    }

    /** The activity currently on top, or null when the app is backgrounded. */
    public static Activity currentActivity() {
        return currentActivity.get();
    }

    /**
     * Whether any Colgram activity is currently resumed.
     * Used to decide whether an operation needs a UI anchor at all.
     */
    public static boolean isAppInForeground() {
        return isForeground && currentActivity.get() != null;
    }

    /**
     * Whether the app is still eligible for background work.
     *
     * Colgram keeps its bot-update poller and plugin timers alive when backgrounded, so
     * this is intentionally NOT "is there UI". With no foreground activity there is
     * simply no fragment to hand to UI-bound Telegram APIs.
     */
    public static boolean canRunBackgroundWork() {
        return true;
    }
}
