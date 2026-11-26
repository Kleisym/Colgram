package org.colgram.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * ColgramBypassNotice — the blocked state, made actionable.
 *
 * What the user sees when Telegram's addresses are dropped and no proxy is applied is a header
 * that reads "Соединение..." forever. That is technically accurate and practically useless: the
 * reasonable conclusion is that the app is broken, not that the network is, and the one thing
 * that would help - connecting through a verified node - is buried in settings he has to find.
 *
 * The local foundation is mandatory. This notice reports the measured failure and opens
 * the app; it offers no ON/OFF control and never selects a public proxy by itself.
 */
public final class ColgramBypassNotice {

    private static final String TAG = "ColgramBypassNotice";
    private static final String CHANNEL_ID = "colgram_bypass";
    private static final int NOTIFICATION_ID = 77003;

    /** Handled by ColgramBootReceiver; declared in AndroidManifest.xml for that receiver. */
    public static final String ACTION_ENABLE_BYPASS = "org.colgram.action.ENABLE_BYPASS";

    private static volatile boolean showing = false;

    private ColgramBypassNotice() {}

    /**
     * Previously posted a persistent status banner; "Локальный слой всегда включён", so this is
     * intentionally silent and clears any stale notice instead of nagging the user.
     */
    public static void showBlocked(Context context, String diagnosis) {
        clear(context);
    }

    /** Clear any legacy or stale blocked notice unconditionally. */
    public static void clear(Context context) {
        showing = false;
        if (context == null) return;
        try {
            NotificationManager nm = notificationManager(context);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
        } catch (Throwable ignored) {
        }
    }

    static boolean isShowingForTest() {
        return showing;
    }

    private static Intent openAppIntent(Context context) {
        try {
            Intent open = new Intent();
            open.setClassName(context.getPackageName(), "org.telegram.ui.LaunchActivity");
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return open;
        } catch (Throwable t) {
            return null;
        }
    }

    private static PendingIntent pending(Context context, int requestCode, Intent intent) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(context, requestCode, intent, flags);
    }

    private static NotificationManager notificationManager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
