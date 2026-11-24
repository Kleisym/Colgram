package org.telegram.messenger;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import androidx.core.app.NotificationManagerCompat;

import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramNotificationFilter;

import java.util.ArrayList;
import java.util.Set;

/**
 * Android NotificationListenerService that dismisses purely-commercial spam group/channel
 * notifications without touching 1-on-1 DMs, OTP/login codes, summaries, or mixed bundles.
 */
public class ColgramNotificationListenerService extends NotificationListenerService {

    public static boolean isPermissionGranted(Context context) {
        if (context == null) return false;
        try {
            Set<String> enabled = NotificationManagerCompat.getEnabledListenerPackages(context);
            return enabled != null && enabled.contains(context.getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean openNotificationListenerSettings(Context context) {
        if (context == null) return false;
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        try {
            ColgramConfig.init(getApplicationContext());
            if (!ColgramConfig.isNotificationAdFilterEnabled()) return;

            String pkg = sbn.getPackageName();
            if (!ColgramNotificationFilter.supportsPackage(pkg)) return;

            Notification notif = sbn.getNotification();
            if (notif == null) return;

            boolean isSummary = (notif.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
            boolean isOngoing = (notif.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0;
            Bundle extras = notif.extras;
            if (extras == null) return;

            boolean isGroup = extras.getBoolean("android.isGroupConversation", false);
            if (!isGroup && extras.containsKey("android.conversationTitle")) {
                CharSequence title = extras.getCharSequence("android.conversationTitle");
                isGroup = !TextUtils.isEmpty(title);
            }

            ArrayList<String> lines = new ArrayList<>();
            CharSequence[] textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (textLines != null) {
                for (CharSequence cs : textLines) {
                    if (!TextUtils.isEmpty(cs)) lines.add(cs.toString());
                }
            }
            if (lines.isEmpty()) {
                CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (!TextUtils.isEmpty(bigText)) {
                    lines.add(bigText.toString());
                } else {
                    CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
                    if (!TextUtils.isEmpty(text)) lines.add(text.toString());
                }
            }

            String[] payload = lines.toArray(new String[0]);
            if (ColgramNotificationFilter.shouldDismiss(pkg, true, isSummary, isOngoing, isGroup, payload)) {
                if (Build.VERSION.SDK_INT >= 21) {
                    cancelNotification(sbn.getKey());
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
