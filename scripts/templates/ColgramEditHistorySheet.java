package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
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
 * Two kinds of revision are shown, in one chronological timeline:
 *   * TEXT   — a previous caption / body, from ColgramDatabase.getMessageEdits()
 *   * MEDIA  — a previous photo / video / document that the edit replaced, from
 *              ColgramDatabase.getMediaRevisions()
 *
 * Telegram itself has no attachment history: an edit that swaps the photo destroys
 * the old reference, so Colgram captures a sandbox copy at edit time. Revisions with
 * a usable copy get an "Открыть" / "Open" action; ones without still appear in the
 * timeline so the user can at least see that the attachment changed.
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
        final List<org.colgram.core.ColgramDatabase.MediaRevision> mediaRevisions =
                org.colgram.core.ColgramHookHandler.getMediaRevisions(dialogId, messageId);

        final boolean hasText = history != null && !history.isEmpty();
        final boolean hasMedia = mediaRevisions != null && !mediaRevisions.isEmpty();

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

        // Subtitle — counts both kinds of revision
        TextView subtitleView = new TextView(context);
        if (!hasText && !hasMedia) {
            subtitleView.setText(isRu ? "Предыдущих версий не найдено" : "No previous revisions found");
        } else {
            int total = (hasText ? history.size() : 0) + (hasMedia ? mediaRevisions.size() : 0);
            StringBuilder sb = new StringBuilder();
            if (isRu) {
                sb.append("Найдено ").append(total).append(" предыдущих редакций");
                if (hasMedia) sb.append(" · вложений: ").append(mediaRevisions.size());
            } else {
                sb.append(total).append(total == 1 ? " previous revision" : " previous revisions");
                if (hasMedia) sb.append(" · attachments: ").append(mediaRevisions.size());
            }
            subtitleView.setText(sb.toString());
        }
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
        subtitleView.setGravity(Gravity.CENTER_HORIZONTAL);
        container.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        if (!hasText && !hasMedia) {
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

        // Media revisions render FIRST (newest at top) — they are the harder thing to
        // recover, so they should not be buried under a long caption history.
        if (hasMedia) {
            for (int i = mediaRevisions.size() - 1; i >= 0; i--) {
                final org.colgram.core.ColgramDatabase.MediaRevision rev = mediaRevisions.get(i);
                list.addView(buildMediaCard(context, isRu, rev, cardBg, textPrimary,
                        textSecondary, accentColor), LayoutHelper.createLinear(
                        LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
            }
        }

        // Text revisions, newest first
        if (hasText) {
            for (int i = history.size() - 1; i >= 0; i--) {
                final org.colgram.core.ColgramDatabase.MessageEditEntry entry = history.get(i);
                final int revisionNumber = i + 1;

                final LinearLayout card = new LinearLayout(context);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12),
                        cardBg == 0 ? 0x22888888 : cardBg));
                card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));

                final TextView header = new TextView(context);
                header.setText((isRu ? "Редакция #" : "Revision #") + revisionNumber);
                header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                header.setTypeface(AndroidUtilities.bold());
                header.setTextColor(accentColor);
                card.addView(header, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

                final TextView body = new TextView(context);
                body.setText(entry.text == null ? "" : entry.text);
                body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                body.setTextColor(textPrimary);
                body.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
                card.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

                final TextView timestamp = new TextView(context);
                timestamp.setText(formatTs(entry.timestamp));
                timestamp.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                timestamp.setTextColor(textSecondary);
                card.addView(timestamp, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

                list.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
            }
        }

        container.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        builder.setCustomView(container);
        builder.show();
    }

    private static String formatTs(long ts) {
        try {
            return DATE_FORMAT.format(new Date(ts));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * One media-revision card: what it was, when it was replaced, and an action to
     * open the recovered copy when one exists.
     */
    private static View buildMediaCard(final Context context, final boolean isRu,
                                       final org.colgram.core.ColgramDatabase.MediaRevision rev,
                                       int cardBg, int textPrimary, int textSecondary, int accentColor) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12),
                cardBg == 0 ? 0x22888888 : cardBg));
        card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));

        // Kind badge — colour-coded so the timeline scans quickly
        String kind;
        int kindColor = accentColor;
        if (rev.isVideo()) {
            kind = isRu ? "ВИДЕО" : "VIDEO";
            kindColor = 0xFFE8833A;
        } else if (rev.isPhoto()) {
            kind = isRu ? "ФОТО" : "PHOTO";
            kindColor = 0xFF2AABEE;
        } else {
            kind = isRu ? "ФАЙЛ" : "FILE";
            kindColor = 0xFF6BC26B;
        }

        final TextView header = new TextView(context);
        header.setText(kind + "  ·  " + (isRu ? "заменено" : "replaced"));
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(kindColor);
        card.addView(header, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        if (!TextUtils.isEmpty(rev.fileName)) {
            final TextView name = new TextView(context);
            name.setText(rev.fileName);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            name.setTextColor(textPrimary);
            name.setMaxLines(2);
            card.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 2));
        }

        final TextView meta = new TextView(context);
        StringBuilder mb = new StringBuilder();
        if (rev.fileSize > 0) mb.append(AndroidUtilities.formatFileSize(rev.fileSize));
        if (!TextUtils.isEmpty(rev.mimeType)) {
            if (mb.length() > 0) mb.append("  ·  ");
            mb.append(rev.mimeType);
        }
        if (mb.length() > 0) {
            if (mb.length() > 0) mb.append("  ·  ");
        }
        mb.append(formatTs(rev.timestamp));
        meta.setText(mb.toString());
        meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        meta.setTextColor(textSecondary);
        card.addView(meta, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, rev.isRetrievable() ? 8 : 0));

        if (rev.isRetrievable()) {
            // "Open" hands the sandbox copy to whatever viewer owns the MIME type.
            // We deliberately do NOT use Telegram's own photo viewer here: the sheet is
            // opened from ChatActivity and the copy is a plain File outside Telegram's
            // media model, so an Intent is both simpler and cannot desync ChatActivity's
            // pager state.
            final TextView open = new TextView(context);
            open.setText(isRu ? "Открыть" : "Open");
            open.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            open.setTypeface(AndroidUtilities.bold());
            open.setTextColor(accentColor);
            open.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
            open.setOnClickListener(v -> {
                try {
                    File f = new File(rev.localPath);
                    if (!f.exists()) {
                        Toast.makeText(context, isRu ? "Файл больше недоступен" : "File is no longer available",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(context,
                            context.getPackageName() + ".provider", f);
                    Intent view = new Intent(Intent.ACTION_VIEW);
                    view.setDataAndType(uri, rev.mimeType != null ? rev.mimeType : "*/*");
                    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    context.startActivity(view);
                } catch (Throwable t) {
                    // No viewer for this type is the common case for a niche MIME.
                    FileLog.e(t);
                    Toast.makeText(context, isRu ? "Нет приложения для этого файла" : "No app can open this file",
                            Toast.LENGTH_SHORT).show();
                }
            });
            card.addView(open, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        } else {
            final TextView gone = new TextView(context);
            gone.setText(isRu ? "Копия не сохранена" : "No local copy saved");
            gone.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            gone.setTextColor(textSecondary);
            gone.setPadding(0, AndroidUtilities.dp(4), 0, 0);
            card.addView(gone, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }

        return card;
    }
}
