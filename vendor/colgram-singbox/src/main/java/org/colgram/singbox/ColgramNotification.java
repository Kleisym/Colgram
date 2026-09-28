package org.colgram.singbox;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * Surfaces what the engine has to say.
 *
 * Without this, a profile that fails to start produces a tunnel that looks connected and refuses
 * every connection - the hardest kind of failure to notice and the easiest to misread as "the
 * network is slow". The engine raises a notification for exactly the cases worth saying out
 * loud, and this turns it into something the user can see and act on.
 */
final class ColgramNotification {

    private static final String CHANNEL = "colgram_vpn";

    private final Context context;

    ColgramNotification(Context context) {
        this.context = context.getApplicationContext();
    }

    void show(io.nekohasekai.libbox.Notification source) {
        if (source == null) return;
        try {
            String identifier = source.getIdentifier();
            if (identifier == null || identifier.isEmpty()) return;
            // Android keys notifications by an int, and the engine names them with a string, so
            // the same name has to hash to the same slot on every post or a new notification
            // would stack up instead of replacing the one it is about.
            int slot = identifier.hashCode();
            ensureChannel();
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;

            android.app.Notification.Builder builder =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? new android.app.Notification.Builder(context, CHANNEL)
                            : new android.app.Notification.Builder(context);
            builder.setContentTitle(text(source.getTitle(), "Colgram VPN"))
                    .setContentText(text(source.getBody(), null))
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(false)
                    .setAutoCancel(true);
            String subtitle = source.getSubtitle();
            if (subtitle != null && !subtitle.isEmpty()) builder.setSubText(subtitle);
            manager.notify(slot, builder.build());
        } catch (Throwable ignored) {
            // A notification that cannot be posted must never take the tunnel down with it.
        }
    }

    void cancel(String tag, int id) {
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            // Cancel under the same name the engine used, so it removes the slot notify filled
            // rather than an unrelated one.
            int slot = tag == null ? id : tag.hashCode();
            if (manager != null) manager.cancel(slot);
        } catch (Throwable ignored) {
            // Nothing to do; the notification stays until the user swipes it.
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null || manager.getNotificationChannel(CHANNEL) != null) return;
            NotificationChannel channel = new NotificationChannel(CHANNEL, "VPN",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Сообщения туннеля Colgram");
            manager.createNotificationChannel(channel);
        } catch (Throwable ignored) {
            // A channel that will not create just means no channel-scoped post on this build.
        }
    }

    private static String text(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /**
     * A failure the user has to know about.
     *
     * A tunnel that starts and then quietly stops looks identical to a slow network from the
     * outside, so a refusal to start is stated plainly rather than left in the log where nobody
     * will read it.
     */
    void fail(String reason) {
        try {
            ensureChannel();
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            android.app.Notification.Builder builder =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? new android.app.Notification.Builder(context, CHANNEL)
                            : new android.app.Notification.Builder(context);
            builder.setContentTitle("Colgram VPN не запустился")
                    .setContentText(text(reason, "неизвестная причина"))
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setAutoCancel(true);
            manager.notify("colgram_vpn_failed".hashCode(), builder.build());
        } catch (Throwable ignored) {
            // The log already has the reason; a notification is a courtesy, not the record.
        }
    }
}
