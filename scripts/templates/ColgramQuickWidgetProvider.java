package org.telegram.messenger;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

import org.colgram.core.ColgramConfig;
import org.telegram.ui.LaunchActivity;

/**
 * ColgramQuickWidgetProvider — Home-screen quick-access widget for Colgram privacy,
 * local DPI bypass, TempMail, Colemailer anti-spam, saved media vault, and plugins.
 */
public class ColgramQuickWidgetProvider extends AppWidgetProvider {

    public static void updateAllWidgets(Context context) {
        if (context == null) return;
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            if (manager == null) return;
            ComponentName cn = new ComponentName(context, ColgramQuickWidgetProvider.class);
            int[] ids = manager.getAppWidgetIds(cn);
            if (ids != null && ids.length > 0) {
                for (int id : ids) {
                    updateWidget(context, manager, id);
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        if (appWidgetIds == null) return;
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        ColgramConfig.init(context);
        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.colgram_quick_widget);

        boolean dpiOn = ColgramConfig.isDpiBypassEnabled();
        boolean stealthOn = ColgramConfig.isGhostReadEnabled()
                || ColgramConfig.isGhostOnlineEnabled()
                || ColgramConfig.isMaxAnonymityEnabled();
        String badge = (dpiOn ? "DPI: ВКЛ" : "DPI: ВЫКЛ") + (stealthOn ? " • Призрак" : "");
        rv.setTextViewText(R.id.widget_status_badge, badge);

        rv.setOnClickPendingIntent(R.id.widget_header, buildDeepLinkIntent(context, "colgram://privacy", 100));
        rv.setOnClickPendingIntent(R.id.btn_widget_proxy, buildDeepLinkIntent(context, "colgram://proxy", 101));
        rv.setOnClickPendingIntent(R.id.btn_widget_privacy, buildDeepLinkIntent(context, "colgram://privacy", 102));
        rv.setOnClickPendingIntent(R.id.btn_widget_tempmail, buildDeepLinkIntent(context, "colgram://tempmail", 103));
        rv.setOnClickPendingIntent(R.id.btn_widget_antispam, buildDeepLinkIntent(context, "colgram://antispam", 104));
        rv.setOnClickPendingIntent(R.id.btn_widget_saved, buildDeepLinkIntent(context, "colgram://saved", 105));
        rv.setOnClickPendingIntent(R.id.btn_widget_plugins, buildDeepLinkIntent(context, "colgram://plugins", 106));

        appWidgetManager.updateAppWidget(appWidgetId, rv);
    }

    private static PendingIntent buildDeepLinkIntent(Context context, String uriString, int requestCode) {
        Intent intent = new Intent(context, LaunchActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(uriString));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }
}
