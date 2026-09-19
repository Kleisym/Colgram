package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ColgramEditHistorySheet — Telegram-native revision history viewer.
 *
 * Renders every stored revision of an edited message as a chat-bubble styled
 * card inside a BottomSheet, matching Telegram's own dialog surface tokens
 * (Theme.key_dialogBackground, dialogTextBlack, dialogTextGray, chat_messagePanelBackground).
 *
 * Replaces the old stock AlertDialog + android.R.layout.simple_list_item_2 look.
 */
public class ColgramEditHistorySheet {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault());

    public static void show(final Context context, final long dialogId, final int messageId) {
        if (context == null) return;

        final boolean isRu = LocaleController.getInstance().getCurrentLocaleInfo() != null
                && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);

        final List<org.colgram.core.ColgramDatabase.MessageEditEntry> history =
                org.colgram.core.ColgramHookHandler.getEditHistory(dialogId, messageId);

        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false);
        builder.setApplyTopPadding(false);

        int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton);
        if (accentColor == 0) accentColor = 0xFF2AABEE;

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(14), AndroidUtilities.dp(20), AndroidUtilities.dp(20));

        // Drag handle
        View pill = new View(context);
        pill.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(2.5f), 0x33888888, 0x55888888));
        container.addView(pill, LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        // Title
        TextView titleView = new TextView(context);
        titleView.setText(isRu ? "История изменений" : "Edit history");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        container.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        // Subtitle
        TextView subtitleView = new TextView(context);
        if (history == null || history.isEmpty()) {
            subtitleView.setText(isRu ? "Предыдущих версий не найдено" : "No previous revisions found");
        } else {
            subtitleView.setText(isRu
                    ? ("Найдено " + history.size() + " предыдущих редакций")
                    : (history.size() + " previous revisions"));
        }
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
        subtitleView.setGravity(Gravity.CENTER_HORIZONTAL);
        container.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        if (history == null || history.isEmpty()) {
            builder.setCustomView(container);
            builder.show();
            return;
        }

        // Scrollable list of revision cards
        final ScrollView scroll = new ScrollView(context);
        scroll.setClipToPadding(false);
        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        final int cardBg = Theme.getColor(Theme.key_chat_messagePanelBackground);
        final int textPrimary = Theme.getColor(Theme.key_dialogTextBlack);
        final int textSecondary = Theme.getColor(Theme.key_dialogTextGray);

        // Newest revision first is more useful than oldest-first.
        for (int i = history.size() - 1; i >= 0; i--) {
            final org.colgram.core.ColgramDatabase.MessageEditEntry entry = history.get(i);
            final int revisionNumber = i + 1;

            final LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12),
                    cardBg == 0 ? 0x22888888 : cardBg));
            card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));

            // Header row: "Revision #N" + relative position badge
            final TextView header = new TextView(context);
            header.setText((isRu ? "Редакция #" : "Revision #") + revisionNumber);
            header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            header.setTypeface(AndroidUtilities.bold());
            header.setTextColor(accentColor);
            card.addView(header, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

            // Message text
            final TextView body = new TextView(context);
            body.setText(entry.text == null ? "" : entry.text);
            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            body.setTextColor(textPrimary);
            body.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
            card.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

            // Timestamp
            final TextView timestamp = new TextView(context);
            String dateStr;
            try {
                dateStr = DATE_FORMAT.format(new Date(entry.timestamp));
            } catch (Throwable t) {
                dateStr = "";
            }
            timestamp.setText(dateStr);
            timestamp.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            timestamp.setTextColor(textSecondary);
            card.addView(timestamp, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            list.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }

        container.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        builder.setCustomView(container);
        builder.show();
    }
}
