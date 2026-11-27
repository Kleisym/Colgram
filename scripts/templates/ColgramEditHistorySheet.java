package org.telegram.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.colgram.core.ColgramBuildFlags;
import org.json.JSONArray;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramEditHistorySheet — Unified, sleek chronological timeline viewer for message text/caption
 * revisions AND replaced photos/videos/files.
 */
public class ColgramEditHistorySheet {

    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("dd.MM.yyyy · HH:mm:ss", Locale.getDefault());
        }
    };
    private static final ExecutorService THUMB_EXEC = Executors.newFixedThreadPool(2);

    private static final class TimelineItem {
        final long timestamp;
        final boolean isMedia;
        final org.colgram.core.ColgramDatabase.MessageEditEntry textEntry;
        final org.colgram.core.ColgramDatabase.MediaRevision mediaEntry;
        final int seqNumber;

        TimelineItem(long timestamp, org.colgram.core.ColgramDatabase.MessageEditEntry textEntry, int seqNumber) {
            this.timestamp = timestamp;
            this.isMedia = false;
            this.textEntry = textEntry;
            this.mediaEntry = null;
            this.seqNumber = seqNumber;
        }

        TimelineItem(long timestamp, org.colgram.core.ColgramDatabase.MediaRevision mediaEntry, int seqNumber) {
            this.timestamp = timestamp;
            this.isMedia = true;
            this.textEntry = null;
            this.mediaEntry = mediaEntry;
            this.seqNumber = seqNumber;
        }
    }

    public static void show(final Context context, final long dialogId, final int messageId) {
        show(context, dialogId, messageId, null, null, null, false);
    }

    public static void show(final Context context, final long dialogId, final int messageId,
                            final String currentText, final String currentMediaPath, final boolean currentIsVideo) {
        show(context, dialogId, messageId, currentText, null, currentMediaPath, currentIsVideo);
    }

    public static void show(final Context context, final long dialogId, final int messageId,
                            final String currentText, final ArrayList<TLRPC.MessageEntity> currentEntities,
                            final String currentMediaPath, final boolean currentIsVideo) {
        if (context == null) return;
        final android.app.Activity act = AndroidUtilities.findActivity(context);
        if (act == null || act.isFinishing() || act.isDestroyed()) return;

        final boolean isRu = LocaleController.getInstance().getCurrentLocaleInfo() != null
                && "ru".equalsIgnoreCase(LocaleController.getInstance().getCurrentLocaleInfo().shortName);
        final ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        final boolean dark = pal.dark;

        final boolean hasCurrentText = !TextUtils.isEmpty(currentText);

        final int cardBg = pal.cardSurface;
        final int currentCardBg = dark ? pal.cardElevated : 0xFFF3F8FF;
        final int cardStroke = pal.borderSubtle;
        final int textPrimary = pal.textPrimary;
        final int textSecondary = pal.textSecondary;
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        if (accent == 0) {
            accent = pal.accentPrimary;
        }
        final int accentColor = pal.cyber ? pal.accentPrimary : accent;

        final boolean[] allowHandleDismiss = {false};
        final BottomSheet sheet = new BottomSheet(act, false) {
            @Override
            protected boolean canDismissWithSwipe() {
                return allowHandleDismiss[0];
            }
        };

        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(18) + AndroidUtilities.navigationBarHeight);
        ColgramAeroStyler.applyFlagshipSheetChrome(sheet, root, pal);

        // Top handle zone (exclusive swipe-to-dismiss trigger)
        FrameLayout handleZone = new FrameLayout(act);
        handleZone.setPadding(0, dp(4), 0, dp(10));
        handleZone.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) allowHandleDismiss[0] = true;
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) allowHandleDismiss[0] = false;
            return false;
        });
        View pill = new View(act);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(dark ? 0x44FFFFFF : 0x300F172A);
        pillBg.setCornerRadius(dp(99));
        pill.setBackground(pillBg);
        handleZone.addView(pill, LayoutHelper.createFrame(42, 4.5f, Gravity.CENTER));
        root.addView(handleZone, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Header row
        LinearLayout headerRow = new LinearLayout(act);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        LinearLayout titleCol = new LinearLayout(act);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        headerRow.addView(titleCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(act);
        titleView.setText(isRu ? "История изменений" : "Edit History");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19.5f);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(textPrimary);
        titleCol.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        final TextView subtitleView = new TextView(act);
        subtitleView.setText(isRu ? "Загрузка истории…" : "Loading history…");
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        subtitleView.setTextColor(textSecondary);
        titleCol.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        TextView closeBtn = new TextView(act);
        closeBtn.setText("✕");
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setTextColor(textSecondary);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        ColgramMenuChrome.attachTactileFeedback(closeBtn, 99, pal.chipSurface, cardStroke, dark ? 0x28FFFFFF : 0x180F172A);
        closeBtn.setOnClickListener(v -> sheet.dismiss());
        headerRow.addView(closeBtn, LayoutHelper.createLinear(34, 34));

        final int maxScrollH = Math.max(dp(220), Math.min(dp(540), (int) (AndroidUtilities.displaySize.y * 0.66f)));
        final ScrollView scroll = new ScrollView(act) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int hSize = MeasureSpec.getSize(heightMeasureSpec);
                int capped = hSize > 0 ? Math.min(hSize, maxScrollH) : maxScrollH;
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST));
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                allowHandleDismiss[0] = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                allowHandleDismiss[0] = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return super.onTouchEvent(ev);
            }
        };
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(2), 0, dp(8));
        scroll.addView(list, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        android.widget.ProgressBar loadingPb = new android.widget.ProgressBar(act);
        loadingPb.setIndeterminate(true);
        list.addView(loadingPb, LayoutHelper.createLinear(28, 28, Gravity.CENTER_HORIZONTAL, 0, 28, 0, 28));

        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final java.util.concurrent.atomic.AtomicBoolean popped = new java.util.concurrent.atomic.AtomicBoolean(false);
        final Runnable popOnce = () -> {
            if (ColgramBuildFlags.KOLITI_ENABLED && popped.compareAndSet(false, true)) {
                ColgramGlobalOrbController.popModalSuppression();
            }
        };
        if (ColgramBuildFlags.KOLITI_ENABLED) {
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {}

                @Override
                public void onViewDetachedFromWindow(View v) {
                    popOnce.run();
                }
            });
            ColgramGlobalOrbController.pushModalSuppression();
            sheet.setOnDismissListener(d -> popOnce.run());
        }
        sheet.setCustomView(root);
        try {
            sheet.show();
        } catch (Throwable showErr) {
            popOnce.run();
            return;
        }

        THUMB_EXEC.execute(() -> {
            final List<org.colgram.core.ColgramDatabase.MessageEditEntry> history =
                    org.colgram.core.ColgramHookHandler.getEditHistory(dialogId, messageId);
            final List<org.colgram.core.ColgramDatabase.MediaRevision> mediaRevisions =
                    org.colgram.core.ColgramHookHandler.getMediaRevisions(dialogId, messageId);
            final boolean hasCurrentMedia = !TextUtils.isEmpty(currentMediaPath) && new File(currentMediaPath).isFile();

            AndroidUtilities.runOnUIThread(() -> {
                if (sheet.isDismissed() || act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                final boolean hasText = history != null && !history.isEmpty();
                final boolean hasMedia = mediaRevisions != null && !mediaRevisions.isEmpty();
                int totalSaved = (hasText ? history.size() : 0) + (hasMedia ? mediaRevisions.size() : 0);
                if (totalSaved == 0) {
                    subtitleView.setText(isRu ? "Предыдущих правок и замен медиа пока нет" : "No prior edits or replaced media saved");
                } else {
                    StringBuilder sb = new StringBuilder();
                    if (isRu) {
                        sb.append("Сохранено версий: ").append(totalSaved);
                        if (hasText) sb.append(" · текст: ").append(history.size());
                        if (hasMedia) sb.append(" · медиа: ").append(mediaRevisions.size());
                    } else {
                        sb.append("Saved revisions: ").append(totalSaved);
                        if (hasText) sb.append(" · text: ").append(history.size());
                        if (hasMedia) sb.append(" · media: ").append(mediaRevisions.size());
                    }
                    subtitleView.setText(sb.toString());
                }

                list.removeAllViews();
                int animIdx = 0;

                if (hasCurrentMedia || hasCurrentText) {
                    View curCard = buildCurrentVersionCard(
                            act, isRu, pal, currentText, currentEntities, currentMediaPath, currentIsVideo,
                            currentCardBg, accentColor, textPrimary, textSecondary);
                    animateCard(curCard, animIdx++);
                    list.addView(curCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
                }

                if (!hasText && !hasMedia) {
                    TextView emptyCard = new TextView(act);
                    emptyCard.setText(isRu
                            ? "Как только текст, подпись или фото/видео этого сообщения будут изменены (даже несколько раз за секунду), все предыдущие версии появятся в этой ленте."
                            : "Whenever text, caption, photo, or video is edited (even multiple times per second), all prior versions will be preserved in this timeline.");
                    emptyCard.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
                    emptyCard.setTextColor(textSecondary);
                    emptyCard.setGravity(Gravity.CENTER);
                    emptyCard.setPadding(dp(18), dp(20), dp(18), dp(20));
                    GradientDrawable emptyBg = new GradientDrawable();
                    emptyBg.setColor(cardBg);
                    emptyBg.setCornerRadius(dp(18));
                    emptyBg.setStroke(dp(1), cardStroke);
                    emptyCard.setBackground(emptyBg);
                    list.addView(emptyCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 6));
                } else {
                    List<TimelineItem> timeline = new ArrayList<>();
                    if (hasText) {
                        for (int i = 0; i < history.size(); i++) {
                            org.colgram.core.ColgramDatabase.MessageEditEntry e = history.get(i);
                            timeline.add(new TimelineItem(e.timestamp, e, i + 1));
                        }
                    }
                    if (hasMedia) {
                        for (int i = 0; i < mediaRevisions.size(); i++) {
                            org.colgram.core.ColgramDatabase.MediaRevision m = mediaRevisions.get(i);
                            timeline.add(new TimelineItem(m.timestamp, m, i + 1));
                        }
                    }
                    Collections.sort(timeline, (a, b) -> {
                        int cmp = Long.compare(b.timestamp, a.timestamp);
                        if (cmp != 0) return cmp;
                        return Integer.compare(b.seqNumber, a.seqNumber);
                    });

                    String newerTextCompare = hasCurrentText ? currentText.trim() : null;
                    for (int i = 0; i < timeline.size(); i++) {
                        TimelineItem item = timeline.get(i);
                        View card;
                        if (item.isMedia) {
                            card = buildMediaCard(act, isRu, pal, item.mediaEntry, item.seqNumber,
                                    cardBg, cardStroke, textPrimary, textSecondary, accentColor);
                        } else {
                            String label = item.seqNumber == 1
                                    ? (isRu ? "Оригинал #1" : "Original #1")
                                    : ((isRu ? "Версия #" : "Edit #") + item.seqNumber);
                            card = buildTextCard(act, isRu, pal, item.textEntry, label, newerTextCompare,
                                    cardBg, cardStroke, textPrimary, textSecondary, accentColor);
                            newerTextCompare = item.textEntry.text == null ? null : item.textEntry.text.trim();
                        }
                        View rowWithRail = wrapWithTimelineRail(act, card, i == timeline.size() - 1,
                                item.isMedia ? pal.semanticCyan : accentColor, dark);
                        animateCard(rowWithRail, animIdx++);
                        list.addView(rowWithRail, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
                    }
                }
            });
        });
    }

    private static View wrapWithTimelineRail(Context ctx, View card, boolean isLast, int dotColor, boolean dark) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout rail = new LinearLayout(ctx);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(0, dp(14), 0, 0);

        View dot = new View(ctx);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(dotColor);
        dot.setBackground(dotBg);
        rail.addView(dot, LayoutHelper.createLinear(9, 9));

        if (!isLast) {
            View line = new View(ctx);
            line.setBackgroundColor(dark ? 0x24FFFFFF : 0x1E0F172A);
            rail.addView(line, LayoutHelper.createLinear(2, LayoutHelper.MATCH_PARENT, 0, 5, 0, 0));
        }

        row.addView(rail, LayoutHelper.createLinear(18, LayoutHelper.MATCH_PARENT, 0, 0, 8, 0));
        row.addView(card, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        return row;
    }

    private static View buildCurrentVersionCard(
            final Context context,
            final boolean isRu,
            final ColgramAeroStyler.Palette pal,
            final String currentText,
            final ArrayList<TLRPC.MessageEntity> currentEntities,
            final String currentMediaPath,
            final boolean currentIsVideo,
            int bgColor,
            int accentColor,
            int textPrimary,
            int textSecondary
    ) {
        final boolean dark = pal.dark;
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1.2f), (accentColor & 0x00FFFFFF) | 0x55000000);
        card.setBackground(bg);

        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        TextView badge = new TextView(context);
        badge.setText(isRu ? "ТЕКУЩАЯ ВЕРСИЯ" : "CURRENT");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f);
        badge.setTypeface(AndroidUtilities.bold());
        badge.setTextColor(Color.WHITE);
        badge.setPadding(dp(9), dp(3), dp(9), dp(3));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(accentColor);
        badgeBg.setCornerRadius(dp(999));
        badge.setBackground(badgeBg);
        topRow.addView(badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        if (!TextUtils.isEmpty(currentMediaPath) && new File(currentMediaPath).isFile()) {
            View previewBox = buildAsyncMediaPreview(context, currentMediaPath, currentIsVideo, dark, 190,
                    () -> {
                        if (currentIsVideo) {
                            openExternalFile(context, currentMediaPath, "video/*", isRu);
                        } else {
                            showFullScreenImageDialog(context, currentMediaPath, isRu ? "Текущее фото" : "Current photo");
                        }
                    });
            card.addView(previewBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 190, 0, 0, 0, 8));
        }

        if (!TextUtils.isEmpty(currentText)) {
            AnimatedEmojiSpan.TextViewEmojis tv = new AnimatedEmojiSpan.TextViewEmojis(context);
            tv.setCacheType(AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            tv.setTextColor(textPrimary);
            tv.setLineSpacing(dp(2.5f), 1.0f);
            tv.setTextIsSelectable(true);
            tv.setText(buildRichHistoryText(currentText, currentEntities, tv.getPaint().getFontMetricsInt(), null, dark));
            card.addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            TextView cap = new TextView(context);
            cap.setText(isRu ? "(без подписи)" : "(no caption)");
            cap.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            cap.setTextColor(textSecondary);
            card.addView(cap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        return card;
    }

    private static View buildAsyncMediaPreview(Context context, String path, boolean isVideo, boolean dark, int heightDp, Runnable onClick) {
        FrameLayout box = new FrameLayout(context);
        GradientDrawable clipBg = new GradientDrawable();
        clipBg.setCornerRadius(dp(14));
        clipBg.setColor(dark ? 0xFF0E111A : 0xFFE2E8F0);
        box.setBackground(clipBg);
        box.setClipToOutline(true);

        final ImageView iv = new ImageView(context);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setAlpha(0f);
        box.addView(iv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        if (isVideo) {
            TextView playBadge = new TextView(context);
            playBadge.setText("▶");
            playBadge.setTextColor(Color.WHITE);
            playBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            playBadge.setGravity(Gravity.CENTER);
            playBadge.setPadding(dp(2), 0, 0, 0);
            GradientDrawable pBg = new GradientDrawable();
            pBg.setShape(GradientDrawable.OVAL);
            pBg.setColor(0xA6000000);
            pBg.setStroke(dp(1.2f), 0x80FFFFFF);
            playBadge.setBackground(pBg);
            box.addView(playBadge, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
        }

        ColgramMenuChrome.attachSpringPress(box);
        box.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });

        THUMB_EXEC.execute(() -> {
            Bitmap bmp = isVideo ? decodeVideoThumb(path) : decodeSafePreview(path, 800);
            if (bmp != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!box.isAttachedToWindow() || !iv.isAttachedToWindow()) {
                        if (!bmp.isRecycled()) bmp.recycle();
                        return;
                    }
                    iv.setImageBitmap(bmp);
                    iv.animate().alpha(1f).setDuration(180L).start();
                });
            }
        });
        return box;
    }

    private static View buildTextCard(
            final Context context,
            final boolean isRu,
            final ColgramAeroStyler.Palette pal,
            final org.colgram.core.ColgramDatabase.MessageEditEntry entry,
            final String label,
            final String newerCompareText,
            int cardBg,
            int cardStroke,
            int textPrimary,
            int textSecondary,
            int accentColor
    ) {
        final boolean dark = pal.dark;
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardBg);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), cardStroke);
        card.setBackground(bg);

        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        TextView badge = new TextView(context);
        badge.setText(label);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        badge.setTypeface(AndroidUtilities.bold());
        badge.setTextColor(accentColor);
        badge.setSingleLine(true);
        badge.setPadding(dp(8), dp(2.5f), dp(8), dp(2.5f));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor((accentColor & 0x00FFFFFF) | 0x20000000);
        badgeBg.setCornerRadius(dp(999));
        badge.setBackground(badgeBg);
        topRow.addView(badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));

        TextView timeTv = new TextView(context);
        timeTv.setText(formatTs(entry.timestamp));
        timeTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        timeTv.setTextColor(textSecondary);
        timeTv.setMaxLines(1);
        timeTv.setEllipsize(TextUtils.TruncateAt.END);
        topRow.addView(timeTv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 6, 0));

        final String rawText = entry.text == null ? "" : entry.text.trim();
        if (!rawText.isEmpty()) {
            TextView copyBtn = new TextView(context);
            copyBtn.setText(isRu ? "Копировать" : "Copy");
            copyBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            copyBtn.setTypeface(AndroidUtilities.bold());
            copyBtn.setTextColor(accentColor);
            copyBtn.setSingleLine(true);
            copyBtn.setPadding(dp(9), dp(3.5f), dp(9), dp(3.5f));
            ColgramMenuChrome.attachTactileFeedback(copyBtn, 999,
                    pal.chipSurface, cardStroke, dark ? 0x28FFFFFF : 0x160F172A);
            copyBtn.setOnClickListener(v -> {
                try {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("colgram_edit", rawText));
                    Toast.makeText(context, isRu ? "Скопировано" : "Copied", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            });
            topRow.addView(copyBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }

        final AnimatedEmojiSpan.TextViewEmojis body = new AnimatedEmojiSpan.TextViewEmojis(context);
        body.setCacheType(AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        body.setLineSpacing(dp(2.5f), 1.0f);
        body.setTextIsSelectable(true);
        final String exactRaw = entry.text == null ? "" : entry.text;
        if (rawText.isEmpty()) {
            body.setText(isRu ? "(текст удалён)" : "(text removed)");
            body.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
            body.setTextColor(textSecondary);
        } else {
            ArrayList<TLRPC.MessageEntity> parsedEnts = deserializeEntities(entry.entitiesJson);
            body.setTextColor(textPrimary);
            body.setText(buildRichHistoryText(exactRaw, parsedEnts, body.getPaint().getFontMetricsInt(), newerCompareText, dark));
        }
        card.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return card;
    }

    private static ArrayList<TLRPC.MessageEntity> deserializeEntities(String entitiesJson) {
        if (TextUtils.isEmpty(entitiesJson)) return null;
        try {
            JSONArray arr = new JSONArray(entitiesJson);
            ArrayList<Object> raw = org.colgram.core.ColgramBotSync.parseBotEntitiesToTl(arr, null);
            if (raw == null || raw.isEmpty()) return null;
            ArrayList<TLRPC.MessageEntity> out = new ArrayList<>(raw.size());
            for (int i = 0; i < raw.size(); i++) {
                if (raw.get(i) instanceof TLRPC.MessageEntity) {
                    out.add((TLRPC.MessageEntity) raw.get(i));
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static CharSequence buildRichHistoryText(
            String exactText,
            ArrayList<TLRPC.MessageEntity> entities,
            Paint.FontMetricsInt fontMetrics,
            String newerCompareText,
            boolean dark
    ) {
        if (exactText == null) return "";
        SpannableStringBuilder ssb = new SpannableStringBuilder(exactText);
        if (entities != null && !entities.isEmpty()) {
            try {
                MessageObject.addEntitiesToText(ssb, entities, false, false, false, false);
            } catch (Throwable ignored) {}
        }
        CharSequence replaced = Emoji.replaceEmoji(ssb, fontMetrics, false);
        SpannableStringBuilder rich = replaced instanceof SpannableStringBuilder
                ? (SpannableStringBuilder) replaced
                : new SpannableStringBuilder(replaced);
        if (entities != null && !entities.isEmpty()) {
            try {
                MessageObject.replaceAnimatedEmoji(rich, entities, fontMetrics);
            } catch (Throwable ignored) {}
        }
        if (!TextUtils.isEmpty(newerCompareText) && !exactText.trim().equals(newerCompareText.trim())) {
            applyRemovedDiffSpans(rich, exactText, newerCompareText, dark);
        }
        return rich;
    }

    /** Highlights spans in the older revision that were changed or removed in the newer revision, preserving animated emoji. */
    private static void applyRemovedDiffSpans(SpannableStringBuilder ssb, String older, String newer, boolean dark) {
        try {
            int prefix = 0;
            int minLen = Math.min(older.length(), newer.length());
            while (prefix < minLen && older.charAt(prefix) == newer.charAt(prefix)) {
                prefix++;
            }
            int oldEnd = older.length();
            int newEnd = newer.length();
            while (oldEnd > prefix && newEnd > prefix && older.charAt(oldEnd - 1) == newer.charAt(newEnd - 1)) {
                oldEnd--;
                newEnd--;
            }
            int len = ssb.length();
            int clampedStart = Math.max(0, Math.min(prefix, len));
            int clampedEnd = Math.max(clampedStart, Math.min(oldEnd, len));
            if (clampedEnd > clampedStart) {
                int bg = dark ? 0x38EF4444 : 0x26DC2626;
                int fg = dark ? 0xFFFCA5A5 : 0xFFB91C1C;
                ssb.setSpan(new BackgroundColorSpan(bg), clampedStart, clampedEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(fg), clampedStart, clampedEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (clampedEnd - clampedStart < len) {
                    ssb.setSpan(new StrikethroughSpan(), clampedStart, clampedEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static View buildMediaCard(
            final Context context,
            final boolean isRu,
            final ColgramAeroStyler.Palette pal,
            final org.colgram.core.ColgramDatabase.MediaRevision rev,
            final int revNum,
            int cardBg,
            int cardStroke,
            int textPrimary,
            int textSecondary,
            int accentColor
    ) {
        final boolean dark = pal.dark;
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardBg);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), cardStroke);
        card.setBackground(bg);

        String kind;
        int kindColor;
        if (rev.isVideo()) {
            kind = (isRu ? "ВИДЕО #" : "VIDEO #") + revNum;
            kindColor = pal.semanticAmber;
        } else if (rev.isPhoto()) {
            kind = (isRu ? "ФОТО #" : "PHOTO #") + revNum;
            kindColor = pal.semanticCyan;
        } else {
            kind = (isRu ? "ФАЙЛ #" : "FILE #") + revNum;
            kindColor = pal.semanticEmerald;
        }

        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        TextView badge = new TextView(context);
        badge.setText(kind);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f);
        badge.setTypeface(AndroidUtilities.bold());
        badge.setTextColor(kindColor);
        badge.setSingleLine(true);
        badge.setPadding(dp(8), dp(2.5f), dp(8), dp(2.5f));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor((kindColor & 0x00FFFFFF) | 0x20000000);
        badgeBg.setCornerRadius(dp(999));
        badge.setBackground(badgeBg);
        headerRow.addView(badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));

        TextView timeTv = new TextView(context);
        timeTv.setText(formatTs(rev.timestamp));
        timeTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        timeTv.setTextColor(textSecondary);
        timeTv.setMaxLines(1);
        timeTv.setEllipsize(TextUtils.TruncateAt.END);
        headerRow.addView(timeTv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        // Async non-blocking preview for BOTH photos AND video frames
        if (rev.isRetrievable() && (rev.isPhoto() || rev.isVideo())) {
            View preview = buildAsyncMediaPreview(context, rev.localPath, rev.isVideo(), dark, 200, () -> {
                if (rev.isVideo()) {
                    openExternalFile(context, rev.localPath, rev.mimeType != null ? rev.mimeType : "video/*", isRu);
                } else {
                    showFullScreenImageDialog(context, rev.localPath, rev.fileName);
                }
            });
            card.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 200, 0, 0, 0, 8));
        }

        if (!TextUtils.isEmpty(rev.fileName)) {
            TextView name = new TextView(context);
            name.setText(rev.fileName);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            name.setTextColor(textPrimary);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            card.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));
        }

        StringBuilder mb = new StringBuilder();
        if (rev.fileSize > 0) mb.append(AndroidUtilities.formatFileSize(rev.fileSize));
        if (!TextUtils.isEmpty(rev.mimeType)) {
            if (mb.length() > 0) mb.append(" · ");
            mb.append(rev.mimeType);
        }
        if (mb.length() > 0) {
            TextView meta = new TextView(context);
            meta.setText(mb.toString());
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            meta.setTextColor(textSecondary);
            card.addView(meta, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }

        if (rev.isRetrievable()) {
            LinearLayout btnRow = new LinearLayout(context);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            card.addView(btnRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            if (rev.isPhoto()) {
                TextView previewBtn = makePillButton(context, isRu ? "Открыть фото" : "View photo", accentColor, Color.WHITE);
                previewBtn.setOnClickListener(v -> showFullScreenImageDialog(context, rev.localPath, rev.fileName));
                btnRow.addView(previewBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
            }

            TextView openBtn = makePillButton(context, isRu ? "Открыть файл" : "Open file",
                    dark ? 0xFF23293A : 0xFFE5EAF2, textPrimary);
            openBtn.setOnClickListener(v -> openExternalFile(context, rev.localPath, rev.mimeType, isRu));
            btnRow.addView(openBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        } else {
            TextView gone = new TextView(context);
            gone.setText(isRu ? "Файл не успел загрузиться в кэш до замены" : "File was not cached before replacement");
            gone.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            gone.setTextColor(textSecondary);
            card.addView(gone, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }
        return card;
    }

    private static void openExternalFile(Context context, String localPath, String mimeType, boolean isRu) {
        try {
            File f = new File(localPath);
            if (!f.exists()) {
                Toast.makeText(context, isRu ? "Файл больше недоступен" : "File no longer exists", Toast.LENGTH_SHORT).show();
                return;
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(context,
                    context.getPackageName() + ".provider", f);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, !TextUtils.isEmpty(mimeType) ? mimeType : "*/*");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(view);
        } catch (Throwable t) {
            FileLog.e(t);
            Toast.makeText(context.getApplicationContext(), isRu ? "Нет приложения для этого файла" : "No viewer found", Toast.LENGTH_SHORT).show();
        }
    }

    private static void showFullScreenImageDialog(Context ctx, String path, String title) {
        final android.app.Activity act = AndroidUtilities.findActivity(ctx);
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        final Context appCtx = act.getApplicationContext();
        THUMB_EXEC.execute(() -> {
            Bitmap bmp = decodeSafePreview(path, 1800);
            AndroidUtilities.runOnUIThread(() -> {
                if (bmp == null) {
                    ColgramUiKit.showNotice(appCtx, "Не удалось загрузить изображение", true);
                    return;
                }
                if (act.isFinishing() || act.isDestroyed()) {
                    if (!bmp.isRecycled()) bmp.recycle();
                    return;
                }
                ImageView iv = new ImageView(act);
                iv.setImageBitmap(bmp);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setPadding(dp(4), dp(4), dp(4), dp(4));
                iv.setClipToOutline(true);
                iv.setBackground(ColgramUiKit.makeRoundedBg(0, 14f, 0, 0f));
                try {
                    BottomSheet previewSheet = ColgramUiKit.showCustomViewSheet(
                            act, null, org.telegram.messenger.R.drawable.msg_photos,
                            TextUtils.isEmpty(title) ? "Сохранённое фото" : title,
                            null, iv, null, null, null, null, "Закрыть", false, false
                    );
                    if (previewSheet != null) {
                        previewSheet.setOnDismissListener(d -> {
                            iv.setImageDrawable(null);
                            if (!bmp.isRecycled()) bmp.recycle();
                        });
                    } else if (!bmp.isRecycled()) {
                        bmp.recycle();
                    }
                } catch (Throwable ignored) {
                    if (!bmp.isRecycled()) bmp.recycle();
                }
            });
        });
    }

    private static Bitmap decodeVideoThumb(String path) {
        if (TextUtils.isEmpty(path)) return null;
        MediaMetadataRetriever mmr = null;
        try {
            mmr = new MediaMetadataRetriever();
            mmr.setDataSource(path);
            return mmr.getFrameAtTime(0);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (mmr != null) {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private static Bitmap decodeSafePreview(String path, int maxSide) {
        if (TextUtils.isEmpty(path)) return null;
        try {
            File f = new File(path);
            if (!f.isFile() || f.length() == 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (o.outWidth / sample > maxSide || o.outHeight / sample > maxSide) {
                sample *= 2;
            }
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, d);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static TextView makePillButton(Context ctx, String label, int bgColor, int fgColor) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setTextColor(fgColor);
        tv.setPadding(dp(13), dp(7), dp(13), dp(7));
        ColgramMenuChrome.attachTactileFeedback(tv, 999, bgColor, 0, 0x26FFFFFF);
        return tv;
    }

    private static void animateCard(View v, int idx) {
        v.setAlpha(0f);
        v.setTranslationY(dp(8));
        v.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(idx * 28L, 160L))
                .setDuration(190L).setInterpolator(new DecelerateInterpolator()).start();
    }

    private static String formatTs(long ts) {
        try {
            long ms = org.colgram.core.ColgramDatabase.normalizeEpochMillis(ts);
            SimpleDateFormat fmt = DATE_FORMAT.get();
            return fmt != null ? fmt.format(new Date(ms)) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }
}
