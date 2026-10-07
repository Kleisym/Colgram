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
 * A toast was the first attempt and it is the wrong shape: it fires once per session, disappears
 * in two seconds, and offers nothing to press. This is a notification that stays until the
 * connection comes back, says what was measured, and has a button that turns the bypass on.
 *
 * It never turns anything on by itself. He switched the proxy off by hand once and an automatic
 * switch back on was the complaint; the button is the consent.
 */
public final class ColgramBypassNotice {

    private static final String TAG = "ColgramBypassNotice";
    private static final String CHANNEL_ID = "colgram_bypass";
    private static final int NOTIFICATION_ID = 77003;

    /** Handled by ColgramBootReceiver; declared in AndroidManifest.xml for that receiver. */
    public static final String ACTION_ENABLE_BYPASS = "org.colgram.action.ENABLE_BYPASS";

    private static volatile boolean showing = false;

    private ColgramBypassNotice() {}

    /** Tell the user the direct route is dead and offer the bypass. Safe to call repeatedly. */
    public static void showBlocked(Context context, String diagnosis) {
        if (context == null) return;
        try {
            NotificationManager nm = notificationManager(context);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                            "Доступ к Telegram", NotificationManager.IMPORTANCE_DEFAULT);
                    ch.setShowBadge(false);
                    ch.setDescription("Показывает, когда Telegram недоступен напрямую и есть чем это заменить");
                    nm.createNotificationChannel(ch);
                }
            }
            if (!nm.areNotificationsEnabled()) {
                // Without permission the notification is silently dropped. The caller still
                // toasts, so say so in the log rather than pretending the user was told.
                Log.i(TAG, "notifications are disabled; cannot offer the bypass");
                return;
            }

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);
            b.setContentTitle("Telegram недоступен напрямую")
                    .setStyle(new Notification.BigTextStyle().bigText(
                            diagnosis + "\n\nОбход включится сам и будет сам переключаться на живую "
                                    + "ноду, если она отвалится. Отключить — Настройки Colgram → Сеть."))
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setAutoCancel(false);

            Intent open = openAppIntent(context);
            if (open != null) {
                b.setContentIntent(pending(context, 0, open));
            }
            Intent enable = new Intent(ACTION_ENABLE_BYPASS).setPackage(context.getPackageName());
            b.addAction(new Notification.Action.Builder(android.R.drawable.ic_popup_sync,
                    "Включить обход", pending(context, 1, enable)).build());

            nm.notify(NOTIFICATION_ID, b.build());
            showing = true;
        } catch (Throwable t) {
            Log.w(TAG, "could not post the notice: " + t);
        }
    }

    /** Remove it: the connection came back, or the user turned the bypass on himself. */
    public static void clear(Context context) {
        if (context == null || !showing) return;
        showing = false;
        try {
            NotificationManager nm = notificationManager(context);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
        } catch (Throwable ignored) {
        }
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
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    private static NotificationManager notificationManager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
