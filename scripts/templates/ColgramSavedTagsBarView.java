package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.colgram.core.ColgramSavedTagsStore;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Collections;
import java.util.List;

/**
 * Sleek horizontal emoji-tag filter strip displayed in Saved Messages ("Избранное").
 * Supports instant local filtering, next/prev jump across tagged messages, and opening the
 * ColgramUiKit 24-emoji picker sheet.
 */
public final class ColgramSavedTagsBarView extends FrameLayout implements ColgramSavedTagsStore.ChangeListener {

    public interface Callback {
        void onFilterChanged(String activeEmojiOrNull, List<Integer> matchingMsgIds);
        void onJumpToMessage(int msgId);
        void onAddTagClicked();
    }

    private final int currentAccount;
    private final HorizontalScrollView scrollView;
    private final LinearLayout pillsRow;
    private Callback callback;
    private String activeEmoji = null;
    private List<Integer> activeMatchIds = Collections.emptyList();
    private int activeMatchIndex = 0;

    public ColgramSavedTagsBarView(Context context, int currentAccount) {
        super(context);
        this.currentAccount = currentAccount;

        scrollView = new HorizontalScrollView(context);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(5), AndroidUtilities.dp(10), AndroidUtilities.dp(5));

        pillsRow = new LinearLayout(context);
        pillsRow.setOrientation(LinearLayout.HORIZONTAL);
        pillsRow.setGravity(Gravity.CENTER_VERTICAL);
        scrollView.addView(pillsRow, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rebuild();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public String getActiveEmoji() {
        return activeEmoji;
    }

    public void activateEmojiFilter(String emoji) {
        if (TextUtils.equals(activeEmoji, emoji)) {
            activeEmoji = null;
        } else {
            activeEmoji = emoji;
        }
        activeMatchIndex = 0;
        refreshActiveMatchesAndNotify();
        rebuild();
    }

    public void clearFilter() {
        if (activeEmoji == null) return;
        activeEmoji = null;
        activeMatchIndex = 0;
        refreshActiveMatchesAndNotify();
        rebuild();
    }

    private void refreshActiveMatchesAndNotify() {
        if (activeEmoji == null || activeEmoji.isEmpty()) {
            activeMatchIds = Collections.emptyList();
        } else {
            activeMatchIds = ColgramSavedTagsStore.getTaggedMessageIds(currentAccount, activeEmoji);
        }
        if (callback != null) {
            callback.onFilterChanged(activeEmoji, activeMatchIds);
            if (!activeMatchIds.isEmpty()) {
                activeMatchIndex = Math.max(0, Math.min(activeMatchIds.size() - 1, activeMatchIndex));
                callback.onJumpToMessage(activeMatchIds.get(activeMatchIndex));
            }
        }
    }

    public void rebuild() {
        pillsRow.removeAllViews();
        ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        setBackgroundColor(ColgramUiKit.withAlpha(pal.sheetBgTop, 0xEC));

        int totalTagged = ColgramSavedTagsStore.getTotalTaggedMessagesCount(currentAccount);
        List<ColgramSavedTagsStore.TagCount> counts = ColgramSavedTagsStore.getTagCounts(currentAccount);

        boolean allSelected = activeEmoji == null || activeEmoji.isEmpty();
        pillsRow.addView(makePill(
                "Все · " + totalTagged,
                allSelected,
                pal,
                v -> {
                    activeEmoji = null;
                    activeMatchIndex = 0;
                    refreshActiveMatchesAndNotify();
                    rebuild();
                }
        ), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 6, 0));

        if (!allSelected && !activeMatchIds.isEmpty()) {
            int posDisplay = activeMatchIndex + 1;
            pillsRow.addView(makePill(
                    "↑ " + posDisplay + "/" + activeMatchIds.size(),
                    false,
                    pal,
                    v -> {
                        if (activeMatchIds.isEmpty()) return;
                        activeMatchIndex = (activeMatchIndex + 1) % activeMatchIds.size();
                        if (callback != null) callback.onJumpToMessage(activeMatchIds.get(activeMatchIndex));
                        rebuild();
                    }
            ), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 6, 0));
        }

        for (final ColgramSavedTagsStore.TagCount tc : counts) {
            boolean selected = tc.emoji.equals(activeEmoji);
            String label = tc.label != null && !tc.label.isEmpty()
                    ? (tc.emoji + " " + tc.label + " · " + tc.count)
                    : (tc.emoji + " " + tc.count);
            pillsRow.addView(makePill(
                    label,
                    selected,
                    pal,
                    v -> activateEmojiFilter(tc.emoji)
            ), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 6, 0));
        }

        pillsRow.addView(makePill(
                "+ Тег",
                false,
                pal,
                v -> {
                    if (callback != null) callback.onAddTagClicked();
                }
        ), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30));
    }

    private TextView makePill(String text, boolean selected, ColgramAeroStyler.Palette pal, OnClickListener listener) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
        tv.setTypeface(selected ? AndroidUtilities.bold() : Typeface.DEFAULT);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(AndroidUtilities.dp(11), 0, AndroidUtilities.dp(11), 0);
        int bg = selected ? ColgramUiKit.withAlpha(pal.accentPrimary, pal.dark ? 0x33 : 0x1A) : pal.cardSurface;
        int stroke = selected ? pal.accentPrimary : pal.borderSubtle;
        tv.setTextColor(selected ? pal.accentPrimary : pal.textPrimary);
        ColgramMenuChrome.attachTactileFeedback(tv, ColgramUiKit.PILL_RADIUS_DP, bg, stroke,
                ColgramUiKit.withAlpha(pal.accentPrimary, 0x24));
        tv.setOnClickListener(listener);
        return tv;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ColgramSavedTagsStore.addListener(this);
        rebuild();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ColgramSavedTagsStore.removeListener(this);
    }

    @Override
    public void onTagsChanged(int account) {
        if (account != currentAccount) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (activeEmoji != null && !activeEmoji.isEmpty()) {
                activeMatchIds = ColgramSavedTagsStore.getTaggedMessageIds(currentAccount, activeEmoji);
                if (activeMatchIds.isEmpty()) {
                    activeEmoji = null;
                }
                if (callback != null) {
                    callback.onFilterChanged(activeEmoji, activeMatchIds);
                }
            }
            rebuild();
        });
    }
}
