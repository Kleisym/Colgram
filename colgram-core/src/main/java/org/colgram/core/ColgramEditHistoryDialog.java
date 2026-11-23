package org.colgram.core;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ColgramEditHistoryDialog — Theme-native fallback viewer for message edit history.
 * Displays all prior revisions of an edited message without ever using stock white android.app.AlertDialog.
 */
public class ColgramEditHistoryDialog {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault());

    public static void show(Context context, long dialogId, int messageId) {
        if (context == null) return;

        float density = context.getResources() != null ? context.getResources().getDisplayMetrics().density : 2.5f;
        int dp8 = Math.round(8f * density);
        int dp12 = Math.round(12f * density);
        int dp16 = Math.round(16f * density);
        int dp20 = Math.round(20f * density);
        int dp44 = Math.round(44f * density);

        boolean darkTheme = true;
        try {
            Class<?> themeClass = Class.forName("org.telegram.ui.ActionBar.Theme");
            darkTheme = (Boolean) themeClass.getMethod("isCurrentThemeDark").invoke(null);
        } catch (Throwable ignored) {}
        boolean cyberTheme = darkTheme && ColgramConfig.isCyberThemeEnabled();

        int surfaceColor = cyberTheme ? 0xFF141519 : (darkTheme ? 0xFF1B1F27 : 0xFFFFFFFF);
        int cardColor = darkTheme ? 0xFF1F222B : 0xFFF3F6FA;
        int textColor = darkTheme ? 0xFFF2F4F8 : 0xFF111827;
        int mutedColor = darkTheme ? 0xFF94A0B4 : 0xFF64748B;
        int accentColor = cyberTheme ? 0xFFE11D48 : 0xFF2AABEE;

        List<ColgramDatabase.MessageEditEntry> history = ColgramDatabase.getInstance(context).getEditHistory(dialogId, messageId);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp20, dp20, dp20, dp16);

        TextView title = new TextView(context);
        title.setText(history == null || history.isEmpty()
                ? "История изменений"
                : "История правок (" + history.size() + ")");
        title.setTextColor(textColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        if (history == null || history.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("Предыдущих версий этого сообщения не найдено.");
            empty.setTextColor(mutedColor);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp12;
            lp.bottomMargin = dp16;
            root.addView(empty, lp);
        } else {
            ScrollView scroll = new ScrollView(context);
            LinearLayout list = new LinearLayout(context);
            list.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < history.size(); i++) {
                ColgramDatabase.MessageEditEntry entry = history.get(i);
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp12, dp12, dp12, dp12);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(cardColor);
                bg.setCornerRadius(dp12);
                row.setBackground(bg);

                TextView meta = new TextView(context);
                meta.setText("Редакция #" + (i + 1) + " · " + DATE_FORMAT.format(new Date(entry.timestamp)));
                meta.setTextColor(mutedColor);
                meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
                row.addView(meta);

                TextView body = new TextView(context);
                body.setText(entry.text != null ? entry.text : "");
                body.setTextColor(textColor);
                body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                blp.topMargin = dp8 / 2;
                row.addView(body, blp);

                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp8;
                list.addView(row, rlp);
            }
            scroll.addView(list);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.round(320f * density));
            slp.topMargin = dp8;
            slp.bottomMargin = dp16;
            root.addView(scroll, slp);
        }

        final Dialog[] dialogRef = new Dialog[1];
        TextView closeBtn = new TextView(context);
        closeBtn.setText("Закрыть");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        closeBtn.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(dp20);
        btnBg.setColor(accentColor);
        closeBtn.setBackground(btnBg);
        closeBtn.setOnClickListener(v -> {
            if (dialogRef[0] != null) {
                try {
                    dialogRef[0].dismiss();
                } catch (Throwable ignored) {}
            }
        });
        root.addView(closeBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp44));

        try {
            Class<?> alertBuilderClass = Class.forName("org.telegram.ui.ActionBar.AlertDialog$Builder");
            Object builder = alertBuilderClass.getConstructor(Context.class).newInstance(context);
            alertBuilderClass.getMethod("setView", View.class).invoke(builder, root);
            Object shown = alertBuilderClass.getMethod("show").invoke(builder);
            if (shown instanceof Dialog) {
                dialogRef[0] = (Dialog) shown;
            }
            return;
        } catch (Throwable ignored) {}

        Dialog d = new Dialog(context);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        GradientDrawable surf = new GradientDrawable();
        surf.setColor(surfaceColor);
        surf.setCornerRadius(dp20);
        root.setBackground(surf);
        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialogRef[0] = d;
        d.show();
    }
}
