package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramDpiBypass;
import org.colgram.core.ColgramPolymorphicStealth;
import org.colgram.core.ColgramTelegramWsBridge;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ColgramUiKit — unified 120fps custom Design System for Colgram Next-Gen.
 *
 * Strictly zero Koliti core imports so shared templates compile cleanly in both
 * `--koliti` and `--no-koliti` build flavors. Pure hardware-accelerated
 * GradientDrawable / RippleDrawable / Canvas primitives; zero infinite idle animators
 * or BlurMaskFilter / RenderEffect overhead.
 */
public final class ColgramUiKit {

    public static final float SHEET_TOP_RADIUS_DP = 24f;
    public static final float CARD_RADIUS_DP = 16f;
    public static final float INNER_CARD_RADIUS_DP = 14f;
    public static final float ICON_BADGE_RADIUS_DP = 10f;
    public static final float PILL_RADIUS_DP = 999f;
    public static final int ICON_BADGE_SIZE_DP = 32;
    public static final int CTA_HEIGHT_DP = 46;
    public static final int HANDLE_WIDTH_DP = 38;
    public static final int HANDLE_HEIGHT_DP = 4;
    public static final int SHEET_PAD_H_DP = 18;
    public static final int SHEET_PAD_T_DP = 12;
    public static final int SHEET_PAD_B_DP = 20;

    private static final Handler UI_HANDLER = new Handler(Looper.getMainLooper());

    private ColgramUiKit() {
    }

    public interface OnChoiceSelected {
        void onSelected(int index);
    }

    public interface OnInputConfirmed {
        void onConfirmed(String value);
    }

    public interface OnSheetButtonClick {
        boolean onClick(BottomSheet sheet);
    }

    public static final class ChoiceItem {
        public final CharSequence title;
        public final CharSequence subtitle;
        public final CharSequence badge;
        public final int iconRes;
        public final int iconTint;
        public final boolean selected;
        public final boolean destructive;

        public ChoiceItem(CharSequence title, CharSequence subtitle, int iconRes, int iconTint,
                          boolean selected, boolean destructive, CharSequence badge) {
            this.title = title == null ? "" : title;
            this.subtitle = subtitle;
            this.iconRes = iconRes;
            this.iconTint = iconTint;
            this.selected = selected;
            this.destructive = destructive;
            this.badge = badge;
        }

        public static ChoiceItem of(CharSequence title) {
            return new ChoiceItem(title, null, 0, 0, false, false, null);
        }

        public static ChoiceItem of(CharSequence title, CharSequence subtitle) {
            return new ChoiceItem(title, subtitle, 0, 0, false, false, null);
        }

        public static ChoiceItem of(CharSequence title, CharSequence subtitle, int iconRes) {
            return new ChoiceItem(title, subtitle, iconRes, 0, false, false, null);
        }

        public static ChoiceItem selected(CharSequence title, CharSequence subtitle, int iconRes) {
            return new ChoiceItem(title, subtitle, iconRes, 0, true, false, null);
        }

        public static ChoiceItem destructive(CharSequence title, CharSequence subtitle, int iconRes) {
            return new ChoiceItem(title, subtitle, iconRes, 0, false, true, null);
        }
    }

    public static int dp(float value) {
        return AndroidUtilities.dp(value);
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    public static GradientDrawable makeRoundedBg(int fillColor, float radiusDp, int strokeColor, float strokeWidthDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(radiusDp));
        if (strokeWidthDp > 0f && strokeColor != 0) {
            d.setStroke(Math.max(1, dp(strokeWidthDp)), strokeColor);
        }
        return d;
    }

    public static Drawable makeRippleCardBg(int fillColor, int rippleColor, float radiusDp,
                                          int strokeColor, float strokeWidthDp) {
        GradientDrawable content = makeRoundedBg(fillColor, radiusDp, strokeColor, strokeWidthDp);
        GradientDrawable mask = makeRoundedBg(0xFFFFFFFF, radiusDp, 0, 0f);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    public static View makeDragHandle(Context ctx, ColgramAeroStyler.Palette pal) {
        View pill = new View(ctx);
        pill.setBackground(makeRoundedBg(pal.dark ? 0x3DFFFFFF : 0x260F172A, PILL_RADIUS_DP, 0, 0f));
        return pill;
    }

    public static FrameLayout makeIconBadge(Context ctx, int iconRes, int tintColor, int bgColor, int strokeColor) {
        FrameLayout badge = new FrameLayout(ctx);
        badge.setBackground(makeRoundedBg(bgColor, ICON_BADGE_RADIUS_DP, strokeColor, strokeColor != 0 ? 1f : 0f));
        if (iconRes != 0) {
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setImageResource(iconRes);
            iv.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
            badge.addView(iv, LayoutHelper.createFrame(18, 18, Gravity.CENTER));
        }
        return badge;
    }

    public static TextView makePillChip(Context ctx, CharSequence text, int bgColor, int textColor, int strokeColor) {
        TextView chip = new TextView(ctx);
        chip.setText(text);
        chip.setTextColor(textColor);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        chip.setBackground(makeRoundedBg(bgColor, PILL_RADIUS_DP, strokeColor, strokeColor != 0 ? 1f : 0f));
        return chip;
    }

    public static TextView makeCtaButton(Context ctx, CharSequence text, int fillColor, int textColor,
                                         boolean outlined, int strokeColor) {
        ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
        btn.setTypeface(AndroidUtilities.bold());
        btn.setGravity(Gravity.CENTER);
        btn.setMinHeight(dp(CTA_HEIGHT_DP));
        btn.setPadding(dp(14), dp(10), dp(14), dp(10));
        btn.setMaxLines(1);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        int bg = outlined ? pal.cardSurface : fillColor;
        int stroke = outlined ? (strokeColor != 0 ? strokeColor : pal.borderSubtle) : 0;
        int ripple = outlined ? withAlpha(pal.accentPrimary, pal.dark ? 0x28 : 0x1A) : 0x33FFFFFF;
        ColgramMenuChrome.attachTactileFeedback(btn, INNER_CARD_RADIUS_DP, bg, stroke, ripple);
        return btn;
    }

    public static LinearLayout makeCard(Context ctx, ColgramAeroStyler.Palette pal, boolean elevated) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(makeRoundedBg(
                elevated ? pal.cardElevated : pal.cardSurface,
                CARD_RADIUS_DP,
                pal.borderSubtle,
                1f
        ));
        return card;
    }

    public static LinearLayout makeSheetRoot(Context ctx, ColgramAeroStyler.Palette pal) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(SHEET_PAD_H_DP), dp(SHEET_PAD_T_DP), dp(SHEET_PAD_H_DP),
                dp(SHEET_PAD_B_DP) + AndroidUtilities.navigationBarHeight);
        root.addView(makeDragHandle(ctx, pal),
                LayoutHelper.createLinear(HANDLE_WIDTH_DP, HANDLE_HEIGHT_DP, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));
        return root;
    }

    public static LinearLayout makeSheetHeader(Context ctx, ColgramAeroStyler.Palette pal,
                                             int iconRes, int iconTint,
                                             CharSequence title, CharSequence subtitle, CharSequence badgeText) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        wrap.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        int tint = iconTint != 0 ? iconTint : pal.accentPrimary;
        if (iconRes != 0) {
            FrameLayout badge = makeIconBadge(
                    ctx, iconRes, tint,
                    withAlpha(tint, pal.dark ? 0x28 : 0x16),
                    withAlpha(tint, pal.dark ? 0x45 : 0x2A)
            );
            row.addView(badge, LayoutHelper.createLinear(ICON_BADGE_SIZE_DP, ICON_BADGE_SIZE_DP,
                    Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        }

        LinearLayout titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        row.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(ctx);
        titleView.setText(title == null ? "" : title);
        titleView.setTextColor(pal.textPrimary);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f);
        titleView.setTypeface(AndroidUtilities.bold());
        titles.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (!TextUtils.isEmpty(subtitle)) {
            TextView subView = new TextView(ctx);
            subView.setText(subtitle);
            subView.setTextColor(pal.textSecondary);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            subView.setLineSpacing(dp(2), 1.0f);
            titles.addView(subView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        if (!TextUtils.isEmpty(badgeText)) {
            TextView pill = makePillChip(ctx, badgeText, withAlpha(tint, pal.dark ? 0x26 : 0x14),
                    tint, withAlpha(tint, 0x44));
            row.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        }

        View sep = new View(ctx);
        sep.setBackgroundColor(pal.borderSubtle);
        wrap.addView(sep, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 12, 0, 12));
        return wrap;
    }

    public static EditTextBoldCursor makeStyledEditText(Context ctx, Theme.ResourcesProvider rp,
                                                      ColgramAeroStyler.Palette pal,
                                                      CharSequence hint, CharSequence initial,
                                                      int inputType, boolean singleLine) {
        EditTextBoldCursor et = new EditTextBoldCursor(ctx);
        et.setHint(hint == null ? "" : hint);
        if (!TextUtils.isEmpty(initial)) {
            et.setText(initial);
            et.setSelection(et.getText().length());
        }
        et.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        et.setTextColor(pal.textPrimary);
        et.setHintTextColor(pal.textSecondary);
        et.setCursorColor(pal.accentPrimary);
        et.setCursorSize(dp(18));
        et.setCursorWidth(1.6f);
        et.setBackground(null);
        et.setPadding(0, 0, 0, 0);
        if (inputType != 0) {
            et.setInputType(inputType);
        }
        et.setSingleLine(singleLine);
        if (!singleLine) {
            et.setMinLines(2);
            et.setMaxLines(6);
            et.setGravity(Gravity.TOP | Gravity.START);
        } else {
            et.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        }
        return et;
    }

    public static LinearLayout makeInputContainer(Context ctx, ColgramAeroStyler.Palette pal,
                                                EditTextBoldCursor editText, boolean passwordMode) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(10), dp(10), dp(10));
        box.setBackground(makeRoundedBg(pal.inputSurface, INNER_CARD_RADIUS_DP, pal.borderSubtle, 1f));
        box.addView(editText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        if (passwordMode) {
            final boolean[] revealed = new boolean[]{false};
            editText.setTransformationMethod(PasswordTransformationMethod.getInstance());
            ImageView eye = new ImageView(ctx);
            eye.setImageResource(R.drawable.msg_viewchats);
            eye.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            eye.setPadding(dp(6), dp(6), dp(6), dp(6));
            eye.setColorFilter(pal.textSecondary, PorterDuff.Mode.SRC_IN);
            eye.setOnClickListener(v -> {
                revealed[0] = !revealed[0];
                int sel = editText.getSelectionEnd();
                editText.setTransformationMethod(revealed[0]
                        ? null
                        : PasswordTransformationMethod.getInstance());
                eye.setColorFilter(revealed[0] ? pal.accentPrimary : pal.textSecondary, PorterDuff.Mode.SRC_IN);
                if (sel >= 0 && sel <= editText.length()) {
                    editText.setSelection(sel);
                }
            });
            box.addView(eye, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));
        }
        return box;
    }

    private static boolean isValidActivity(Activity act) {
        return act != null && !act.isFinishing() && !act.isDestroyed();
    }

    private static BottomSheet createFlagshipSheet(Activity act, Theme.ResourcesProvider rp, boolean needFocus,
                                                   boolean secureWindow, LinearLayout root) {
        BottomSheet.Builder b = new BottomSheet.Builder(act, needFocus, rp);
        b.setApplyTopPadding(false);
        b.setApplyBottomPadding(false);
        b.setCustomView(root);
        BottomSheet sheet = b.create();
        ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        ColgramAeroStyler.applyFlagshipSheetChrome(sheet, root, pal);
        if (secureWindow && sheet.getWindow() != null) {
            sheet.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        return sheet;
    }

    public static BottomSheet showChoiceSheet(Activity act, Theme.ResourcesProvider rp, int iconRes,
                                              CharSequence title, CharSequence subtitle,
                                              CharSequence[] labels, int selectedIndex,
                                              OnChoiceSelected listener) {
        if (labels == null) return null;
        List<ChoiceItem> items = new ArrayList<>(labels.length);
        for (int i = 0; i < labels.length; i++) {
            items.add(new ChoiceItem(labels[i], null, 0, 0, i == selectedIndex, false, null));
        }
        return showChoiceSheet(act, rp, iconRes, title, subtitle, items, listener);
    }

    public static BottomSheet showChoiceSheet(Activity act, Theme.ResourcesProvider rp, int iconRes,
                                              CharSequence title, CharSequence subtitle,
                                              List<ChoiceItem> items, OnChoiceSelected listener) {
        if (!isValidActivity(act) || items == null) return null;
        final ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        LinearLayout root = makeSheetRoot(act, pal);
        root.addView(makeSheetHeader(act, pal, iconRes, pal.accentPrimary, title, subtitle, null),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout listWrap = new LinearLayout(act);
        listWrap.setOrientation(LinearLayout.VERTICAL);

        final BottomSheet sheet = createFlagshipSheet(act, rp, false, false, root);

        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            ChoiceItem item = items.get(i);
            int accent = item.destructive
                    ? pal.semanticCrimson
                    : (item.iconTint != 0 ? item.iconTint : pal.accentPrimary);

            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), dp(11), dp(14), dp(11));

            int fill = item.selected
                    ? withAlpha(accent, pal.dark ? 0x24 : 0x12)
                    : pal.cardSurface;
            int stroke = item.selected
                    ? withAlpha(accent, pal.dark ? 0x66 : 0x48)
                    : pal.borderSubtle;
            int ripple = withAlpha(accent, pal.dark ? 0x2A : 0x18);
            ColgramMenuChrome.attachTactileFeedback(card, INNER_CARD_RADIUS_DP, fill, stroke, ripple);

            if (item.iconRes != 0) {
                FrameLayout badge = makeIconBadge(
                        act, item.iconRes, accent,
                        withAlpha(accent, pal.dark ? 0x24 : 0x14),
                        withAlpha(accent, 0x38)
                );
                card.addView(badge, LayoutHelper.createLinear(ICON_BADGE_SIZE_DP, ICON_BADGE_SIZE_DP,
                        Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
            }

            LinearLayout texts = new LinearLayout(act);
            texts.setOrientation(LinearLayout.VERTICAL);
            card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            TextView tvTitle = new TextView(act);
            tvTitle.setText(item.title);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            tvTitle.setTypeface(AndroidUtilities.bold());
            tvTitle.setTextColor(item.destructive ? pal.semanticCrimson : pal.textPrimary);
            texts.addView(tvTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            if (!TextUtils.isEmpty(item.subtitle)) {
                TextView tvSub = new TextView(act);
                tvSub.setText(item.subtitle);
                tvSub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
                tvSub.setTextColor(pal.textSecondary);
                tvSub.setLineSpacing(dp(1.5f), 1.0f);
                texts.addView(tvSub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            }

            if (!TextUtils.isEmpty(item.badge)) {
                TextView pill = makePillChip(act, item.badge, withAlpha(accent, pal.dark ? 0x24 : 0x14),
                        accent, withAlpha(accent, 0x40));
                card.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
            } else if (item.selected) {
                TextView checkPill = makePillChip(act, "Активно", withAlpha(accent, pal.dark ? 0x2C : 0x18),
                        accent, withAlpha(accent, 0x4A));
                card.addView(checkPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
            }

            card.setOnClickListener(v -> {
                sheet.dismiss();
                if (listener != null) {
                    listener.onSelected(idx);
                }
            });
            listWrap.addView(card, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    0, 0, 0, i == items.size() - 1 ? 0 : 8
            ));
        }

        if (items.size() > 5) {
            ScrollView scroll = new ScrollView(act);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(listWrap);
            LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12);
            root.addView(scroll, lp);
        } else {
            root.addView(listWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
        }

        TextView cancelBtn = makeCtaButton(act, LocaleController.getString(R.string.Cancel),
                pal.cardSurface, pal.textSecondary, true, pal.borderSubtle);
        cancelBtn.setOnClickListener(v -> sheet.dismiss());
        root.addView(cancelBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CTA_HEIGHT_DP));

        sheet.show();
        return sheet;
    }

    public static BottomSheet showConfirmSheet(Activity act, Theme.ResourcesProvider rp, int iconRes,
                                               CharSequence title, CharSequence message,
                                               CharSequence confirmText, boolean destructive,
                                               Runnable onConfirm, CharSequence cancelText, Runnable onCancel) {
        return showConfirmSheet(act, rp, iconRes, title, message, confirmText, destructive,
                onConfirm, null, null, cancelText, onCancel);
    }

    public static BottomSheet showConfirmSheet(Activity act, Theme.ResourcesProvider rp, int iconRes,
                                               CharSequence title, CharSequence message,
                                               CharSequence confirmText, boolean destructive, Runnable onConfirm,
                                               CharSequence neutralText, Runnable onNeutral,
                                               CharSequence cancelText, Runnable onCancel) {
        if (!isValidActivity(act)) return null;
        final ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        LinearLayout root = makeSheetRoot(act, pal);
        int headerTint = destructive ? pal.semanticCrimson : pal.accentPrimary;
        root.addView(makeSheetHeader(act, pal, iconRes, headerTint, title, null, null),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (!TextUtils.isEmpty(message)) {
            LinearLayout msgCard = makeCard(act, pal, false);
            TextView msgView = new TextView(act);
            msgView.setText(message);
            msgView.setTextColor(pal.textPrimary);
            msgView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            msgView.setLineSpacing(dp(3), 1.0f);
            msgCard.addView(msgView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            ScrollView scroll = new ScrollView(act);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(msgCard);
            root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));
        }

        final BottomSheet sheet = createFlagshipSheet(act, rp, false, false, root);
        int ctaColor = destructive ? pal.semanticCrimson : pal.accentPrimary;
        String posLabel = TextUtils.isEmpty(confirmText) ? LocaleController.getString(R.string.OK) : confirmText.toString();
        String negLabel = TextUtils.isEmpty(cancelText) ? LocaleController.getString(R.string.Cancel) : cancelText.toString();

        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView btnNeg = makeCtaButton(act, negLabel, pal.cardSurface, pal.textSecondary, true, pal.borderSubtle);
        btnNeg.setOnClickListener(v -> {
            sheet.dismiss();
            if (onCancel != null) onCancel.run();
        });
        TextView btnPos = makeCtaButton(act, posLabel, ctaColor, 0xFFFFFFFF, false, 0);
        btnPos.setOnClickListener(v -> {
            sheet.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        btnRow.addView(btnNeg, LayoutHelper.createLinear(0, CTA_HEIGHT_DP, 1f, 0, 0, 10, 0));
        btnRow.addView(btnPos, LayoutHelper.createLinear(0, CTA_HEIGHT_DP, 1.25f));
        root.addView(btnRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (!TextUtils.isEmpty(neutralText)) {
            TextView btnNeutral = makeCtaButton(act, neutralText, pal.cardSurface, pal.accentPrimary, true,
                    withAlpha(pal.accentPrimary, 0x45));
            btnNeutral.setOnClickListener(v -> {
                sheet.dismiss();
                if (onNeutral != null) onNeutral.run();
            });
            root.addView(btnNeutral, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CTA_HEIGHT_DP, 0, 8, 0, 0));
        }

        sheet.show();
        return sheet;
    }

    public static BottomSheet showInfoSheet(Activity act, Theme.ResourcesProvider rp, int iconRes,
                                            CharSequence title, CharSequence message,
                                            CharSequence primaryText, Runnable onPrimary,
                                            CharSequence secondaryText, Runnable onSecondary,
                                            boolean selectableText) {
        if (!isValidActivity(act)) return null;
        final ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        LinearLayout root = makeSheetRoot(act, pal);
        root.addView(makeSheetHeader(act, pal, iconRes, pal.accentPrimary, title, null, null),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout msgCard = makeCard(act, pal, false);
        TextView msgView = new TextView(act);
        msgView.setText(message == null ? "" : message);
        msgView.setTextColor(pal.textPrimary);
        msgView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        msgView.setLineSpacing(dp(3), 1.0f);
        msgView.setTextIsSelectable(selectableText);
        msgCard.addView(msgView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(msgCard);
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f, 0, 0, 0, 14));

        final BottomSheet sheet = createFlagshipSheet(act, rp, false, false, root);

        if (!TextUtils.isEmpty(primaryText) || !TextUtils.isEmpty(secondaryText)) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            if (!TextUtils.isEmpty(secondaryText)) {
                TextView btnSec = makeCtaButton(act, secondaryText, pal.cardSurface, pal.textPrimary, true, pal.borderSubtle);
                btnSec.setOnClickListener(v -> {
                    sheet.dismiss();
                    if (onSecondary != null) onSecondary.run();
                });
                row.addView(btnSec, LayoutHelper.createLinear(0, CTA_HEIGHT_DP, 1f, 0, 0,
                        !TextUtils.isEmpty(primaryText) ? 10 : 0, 0));
            }
            if (!TextUtils.isEmpty(primaryText)) {
                TextView btnPri = makeCtaButton(act, primaryText, pal.accentPrimary, 0xFFFFFFFF, false, 0);
                btnPri.setOnClickListener(v -> {
                    sheet.dismiss();
                    if (onPrimary != null) onPrimary.run();
                });
                row.addView(btnPri, LayoutHelper.createLinear(0, CTA_HEIGHT_DP, 1.2f));
            }
            root.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            TextView closeBtn = makeCtaButton(act, LocaleController.getString(R.string.Close),
                    pal.accentPrimary, 0xFFFFFFFF, false, 0);
            closeBtn.setOnClickListener(v -> sheet.dismiss());
            root.addView(closeBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CTA_HEIGHT_DP));
        }

        sheet.show();
        return sheet;
    }

    public static BottomSheet showInputSheet(Activity act, Theme.ResourcesProvider rp, int iconRes,
                                             CharSequence title, CharSequence subtitle,
                                             CharSequence hint, CharSequence initialText,
                                             int inputType, boolean passwordMode, boolean secureWindow,
                                             boolean singleLine, CharSequence confirmText,
                                             OnInputConfirmed listener,
                                             CharSequence neutralText, OnInputConfirmed onNeutral) {
        if (!isValidActivity(act)) return null;
        final ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        LinearLayout root = makeSheetRoot(act, pal);
        root.addView(makeSheetHeader(act, pal, iconRes, pal.accentPrimary, title, subtitle, null),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final EditTextBoldCursor editText = makeStyledEditText(act, rp, pal, hint, initialText, inputType, singleLine);
        LinearLayout inputBox = makeInputContainer(act, pal, editText, passwordMode);

        LinearLayout inner = new LinearLayout(act);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.addView(inputBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(inner);
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final BottomSheet sheet = createFlagshipSheet(act, rp, true, secureWindow, root);

        Runnable submit = () -> {
            AndroidUtilities.hideKeyboard(editText);
            String val = editText.getText() == null ? "" : editText.getText().toString();
            sheet.dismiss();
            if (listener != null) listener.onConfirmed(val);
        };

        if (singleLine) {
            editText.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit.run();
                    return true;
                }
                return false;
            });
        }

        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancelBtn = makeCtaButton(act, LocaleController.getString(R.string.Cancel),
                pal.cardSurface, pal.textSecondary, true, pal.borderSubtle);
        cancelBtn.setOnClickListener(v -> {
            AndroidUtilities.hideKeyboard(editText);
            sheet.dismiss();
        });
        String okLabel = TextUtils.isEmpty(confirmText) ? LocaleController.getString(R.string.Save) : confirmText.toString();
        TextView confirmBtn = makeCtaButton(act, okLabel, pal.accentPrimary, 0xFFFFFFFF, false, 0);
        confirmBtn.setOnClickListener(v -> submit.run());
        btnRow.addView(cancelBtn, LayoutHelper.createLinear(0, CTA_HEIGHT_DP, 1f, 0, 0, 10, 0));
        btnRow.addView(confirmBtn, LayoutHelper.createLinear(0, CTA_HEIGHT_DP, 1.3f));
        root.addView(btnRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (!TextUtils.isEmpty(neutralText)) {
            TextView nBtn = makeCtaButton(act, neutralText, pal.cardSurface, pal.accentPrimary, true,
                    withAlpha(pal.accentPrimary, 0x45));
            nBtn.setOnClickListener(v -> {
                AndroidUtilities.hideKeyboard(editText);
                String val = editText.getText() == null ? "" : editText.getText().toString();
                sheet.dismiss();
                if (onNeutral != null) onNeutral.onConfirmed(val);
            });
            root.addView(nBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CTA_HEIGHT_DP, 0, 8, 0, 0));
        }

        sheet.setOnShowListener(d -> UI_HANDLER.postDelayed(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 120L));
        sheet.show();
        return sheet;
    }

    public static BottomSheet showCustomViewSheet(Activity act, Theme.ResourcesProvider rp, int iconRes,
                                                  CharSequence title, CharSequence subtitle,
                                                  View customContent,
                                                  CharSequence positiveText, OnSheetButtonClick onPositive,
                                                  CharSequence neutralText, OnSheetButtonClick onNeutral,
                                                  CharSequence negativeText,
                                                  boolean needFocus, boolean secureWindow) {
        if (!isValidActivity(act)) return null;
        final ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        LinearLayout root = makeSheetRoot(act, pal);
        root.addView(makeSheetHeader(act, pal, iconRes, pal.accentPrimary, title, subtitle, null),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        if (customContent != null) {
            if (customContent.getParent() instanceof ViewGroup) {
                ((ViewGroup) customContent.getParent()).removeView(customContent);
            }
            scroll.addView(customContent);
        }
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f, 0, 0, 0, 12));

        final BottomSheet sheet = createFlagshipSheet(act, rp, needFocus, secureWindow, root);

        if (!TextUtils.isEmpty(positiveText) || !TextUtils.isEmpty(negativeText)) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            if (!TextUtils.isEmpty(negativeText)) {
                TextView btnNeg = makeCtaButton(act, negativeText, pal.cardSurface, pal.textSecondary, true, pal.borderSubtle);
                btnNeg.setOnClickListener(v -> {
                    AndroidUtilities.hideKeyboard(root);
                    sheet.dismiss();
                });
                row.addView(btnNeg, LayoutHelper.createLinear(0, CTA_HEIGHT_DP, 1f, 0, 0,
                        !TextUtils.isEmpty(positiveText) ? 10 : 0, 0));
            }
            if (!TextUtils.isEmpty(positiveText)) {
                TextView btnPos = makeCtaButton(act, positiveText, pal.accentPrimary, 0xFFFFFFFF, false, 0);
                btnPos.setOnClickListener(v -> {
                    if (onPositive == null || onPositive.onClick(sheet)) {
                        AndroidUtilities.hideKeyboard(root);
                        sheet.dismiss();
                    }
                });
                row.addView(btnPos, LayoutHelper.createLinear(0, CTA_HEIGHT_DP, 1.25f));
            }
            root.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        if (!TextUtils.isEmpty(neutralText)) {
            TextView btnNeutral = makeCtaButton(act, neutralText, pal.cardSurface, pal.accentPrimary, true,
                    withAlpha(pal.accentPrimary, 0x45));
            btnNeutral.setOnClickListener(v -> {
                if (onNeutral == null || onNeutral.onClick(sheet)) {
                    AndroidUtilities.hideKeyboard(root);
                    sheet.dismiss();
                }
            });
            root.addView(btnNeutral, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CTA_HEIGHT_DP, 0, 8, 0, 0));
        }

        sheet.show();
        return sheet;
    }

    public static final class ProgressSheetHandle {
        private final BottomSheet sheet;
        private final TextView statusView;
        private final ProgressBar horizontalBar;
        private final TextView percentView;

        ProgressSheetHandle(BottomSheet sheet, TextView statusView, ProgressBar horizontalBar, TextView percentView) {
            this.sheet = sheet;
            this.statusView = statusView;
            this.horizontalBar = horizontalBar;
            this.percentView = percentView;
        }

        public void setStatus(String text) {
            UI_HANDLER.post(() -> {
                if (statusView != null) statusView.setText(text == null ? "" : text);
            });
        }

        public void setProgress(int percent, String detail) {
            UI_HANDLER.post(() -> {
                int clamped = Math.max(0, Math.min(100, percent));
                if (horizontalBar != null) {
                    horizontalBar.setIndeterminate(false);
                    horizontalBar.setProgress(clamped);
                }
                if (percentView != null) {
                    percentView.setText(clamped + "%");
                }
                if (statusView != null && detail != null) {
                    statusView.setText(detail);
                }
            });
        }

        public boolean isShowing() {
            return sheet != null && sheet.isShowing();
        }

        public BottomSheet getSheet() {
            return sheet;
        }

        public void dismiss() {
            UI_HANDLER.post(() -> {
                try {
                    if (sheet != null && sheet.isShowing()) {
                        sheet.dismiss();
                    }
                } catch (Throwable ignored) {
                }
            });
        }
    }

    public static ProgressSheetHandle showProgressSheet(Activity act, Theme.ResourcesProvider rp, int iconRes,
                                                        CharSequence title, CharSequence initialStatus,
                                                        boolean horizontalBar, Runnable onCancel) {
        if (!isValidActivity(act)) return null;
        final ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        LinearLayout root = makeSheetRoot(act, pal);
        root.addView(makeSheetHeader(act, pal, iconRes, pal.accentPrimary, title, null, null),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout card = makeCard(act, pal, false);
        TextView statusView = new TextView(act);
        statusView.setText(initialStatus == null ? "" : initialStatus);
        statusView.setTextColor(pal.textPrimary);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        card.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        ProgressBar hBar = null;
        TextView pctView = null;
        if (horizontalBar) {
            hBar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
            hBar.setMax(100);
            hBar.setIndeterminate(true);
            hBar.getProgressDrawable().setColorFilter(pal.accentPrimary, PorterDuff.Mode.SRC_IN);
            hBar.getIndeterminateDrawable().setColorFilter(pal.accentPrimary, PorterDuff.Mode.SRC_IN);
            card.addView(hBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 6, 0, 0, 0, 6));

            pctView = new TextView(act);
            pctView.setText("0%");
            pctView.setTextColor(pal.textSecondary);
            pctView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            pctView.setGravity(Gravity.END);
            card.addView(pctView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            ProgressBar spinner = new ProgressBar(act);
            spinner.getIndeterminateDrawable().setColorFilter(pal.accentPrimary, PorterDuff.Mode.SRC_IN);
            card.addView(spinner, LayoutHelper.createLinear(32, 32, Gravity.CENTER_HORIZONTAL));
        }
        root.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        final BottomSheet sheet = createFlagshipSheet(act, rp, false, false, root);
        if (onCancel != null) {
            sheet.setCancelable(true);
            sheet.setOnCancelListener(d -> onCancel.run());
            TextView cancelBtn = makeCtaButton(act, LocaleController.getString(R.string.Cancel),
                    pal.cardSurface, pal.textSecondary, true, pal.borderSubtle);
            cancelBtn.setOnClickListener(v -> {
                sheet.dismiss();
                onCancel.run();
            });
            root.addView(cancelBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CTA_HEIGHT_DP));
        } else {
            sheet.setCancelable(false);
            sheet.setCanceledOnTouchOutside(false);
        }
        sheet.show();
        return new ProgressSheetHandle(sheet, statusView, hBar, pctView);
    }

    public static void showNotice(BaseFragment fragment, CharSequence text, boolean isError) {
        showNotice(fragment, 0, text, isError);
    }

    public static void showNotice(Context context, CharSequence text, boolean isError) {
        showNotice(context, 0, text, isError);
    }

    public static void showNotice(Context context, int iconRes, CharSequence text, boolean isError) {
        if (TextUtils.isEmpty(text)) return;
        BaseFragment last = null;
        try {
            last = LaunchActivity.getSafeLastFragment();
        } catch (Throwable ignored) {
        }
        showNoticeInternal(last, context, iconRes, text, isError);
    }

    public static void showNotice(BaseFragment fragment, int iconRes, CharSequence text, boolean isError) {
        if (TextUtils.isEmpty(text)) return;
        Context ctx = fragment != null ? fragment.getParentActivity() : null;
        showNoticeInternal(fragment, ctx, iconRes, text, isError);
    }

    private static void showNoticeInternal(BaseFragment fragment, Context context, int iconRes,
                                           CharSequence text, boolean isError) {
        Runnable task = () -> {
            try {
                BaseFragment target = fragment;
                if (target == null) {
                    target = LaunchActivity.getSafeLastFragment();
                }
                BulletinFactory bf = null;
                Theme.ResourcesProvider rp = target != null ? target.getResourceProvider() : null;
                if (target != null && target.getVisibleDialog() instanceof BottomSheet) {
                    BottomSheet bs = (BottomSheet) target.getVisibleDialog();
                    if (bs.container != null) {
                        bf = BulletinFactory.of(bs.container, rp);
                    }
                } else if (BulletinFactory.canShowBulletin(target)) {
                    bf = BulletinFactory.of(target);
                }
                if (bf != null) {
                    Context resCtx = context != null ? context
                            : (target != null ? target.getParentActivity() : ApplicationLoader.applicationContext);
                    if (iconRes != 0 && resCtx != null) {
                        Drawable d = ContextCompat.getDrawable(resCtx, iconRes);
                        if (d != null) {
                            Drawable mut = d.mutate();
                            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
                            mut.setColorFilter(isError ? pal.semanticCrimson : pal.accentPrimary, PorterDuff.Mode.SRC_IN);
                            bf.createSimpleBulletin(mut, text).show();
                            return;
                        }
                    }
                    if (isError) {
                        bf.createErrorBulletin(text, rp).show();
                    } else {
                        bf.createSuccessBulletin(text, rp).show();
                    }
                    return;
                }
            } catch (Throwable ignored) {
            }
            Context appCtx = context != null ? context.getApplicationContext() : ApplicationLoader.applicationContext;
            if (appCtx != null) {
                Toast.makeText(appCtx, text, isError ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            UI_HANDLER.post(task);
        }
    }

    /**
     * LiveTelemetryCardView — compact 120fps telemetry card mounted in Colgram Settings and ProxyList.
     *
     * Strictly uses Track 1 public static snapshots (no blocking I/O on main thread,
     * no infinite idle animators; auto-refresh runs ONLY while attached & visible).
     */
    public static final class LiveTelemetryCardView extends FrameLayout {
        private static final long REFRESH_INTERVAL_MS = 2500L;

        private final LinearLayout card;
        private final FrameLayout badge;
        private final TextView titleView;
        private final TextView modePill;
        private final TextView chipRtt;
        private final TextView chipSni;
        private final TextView chipSessions;
        private final TextView lineStealth;
        private final TextView lineBridge;
        private final Handler refreshHandler = new Handler(Looper.getMainLooper());
        private final Runnable refreshTick = new Runnable() {
            @Override
            public void run() {
                refreshTelemetry();
                if (isVisibleOnScreen()) {
                    refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
                }
            }
        };

        public LiveTelemetryCardView(@NonNull Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
            ));
            setPadding(dp(14), dp(6), dp(14), dp(8));

            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();

            card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

            badge = makeIconBadge(context, R.drawable.msg_speed_fast, pal.accentPrimary,
                    withAlpha(pal.accentPrimary, 0x22), withAlpha(pal.accentPrimary, 0x44));
            header.addView(badge, LayoutHelper.createLinear(ICON_BADGE_SIZE_DP, ICON_BADGE_SIZE_DP,
                    Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

            titleView = new TextView(context);
            titleView.setText("Телеметрия сети и транспорта");
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            titleView.setTypeface(AndroidUtilities.bold());
            header.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            modePill = makePillChip(context, "DPI", withAlpha(pal.semanticEmerald, 0x22),
                    pal.semanticEmerald, withAlpha(pal.semanticEmerald, 0x44));
            header.addView(modePill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

            LinearLayout chipsRow = new LinearLayout(context);
            chipsRow.setOrientation(LinearLayout.HORIZONTAL);
            card.addView(chipsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

            chipRtt = makePillChip(context, "RTT —", pal.chipSurface, pal.textPrimary, pal.borderSubtle);
            chipSni = makePillChip(context, "SNI —", pal.chipSurface, pal.textPrimary, pal.borderSubtle);
            chipSessions = makePillChip(context, "Сессии 0", pal.chipSurface, pal.textPrimary, pal.borderSubtle);
            chipsRow.addView(chipRtt, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 6, 0));
            chipsRow.addView(chipSni, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 6, 0));
            chipsRow.addView(chipSessions, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            lineStealth = new TextView(context);
            lineStealth.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
            lineStealth.setMaxLines(2);
            lineStealth.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(lineStealth, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 3));

            lineBridge = new TextView(context);
            lineBridge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
            lineBridge.setMaxLines(2);
            lineBridge.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(lineBridge, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            card.setOnClickListener(v -> {
                refreshTelemetry();
                showNotice(getContext(), R.drawable.msg_speed_fast, "Телеметрия обновлена", false);
            });

            bindTheme();
            refreshTelemetry();
        }

        public void bindTheme() {
            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            ColgramMenuChrome.attachTactileFeedback(card, CARD_RADIUS_DP, pal.cardSurface, pal.borderSubtle,
                    withAlpha(pal.accentPrimary, pal.dark ? 0x26 : 0x16));
            badge.setBackground(makeRoundedBg(withAlpha(pal.accentPrimary, pal.dark ? 0x26 : 0x16),
                    ICON_BADGE_RADIUS_DP, withAlpha(pal.accentPrimary, 0x42), 1f));
            titleView.setTextColor(pal.textPrimary);
            lineStealth.setTextColor(pal.textSecondary);
            lineBridge.setTextColor(pal.textSecondary);
            chipRtt.setBackground(makeRoundedBg(pal.chipSurface, PILL_RADIUS_DP, pal.borderSubtle, 1f));
            chipSni.setBackground(makeRoundedBg(pal.chipSurface, PILL_RADIUS_DP, pal.borderSubtle, 1f));
            chipSessions.setBackground(makeRoundedBg(pal.chipSurface, PILL_RADIUS_DP, pal.borderSubtle, 1f));
            chipRtt.setTextColor(pal.textPrimary);
            chipSni.setTextColor(pal.textPrimary);
            chipSessions.setTextColor(pal.textPrimary);
        }

        public void refreshTelemetry() {
            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            String modeLabel = "DPI";
            int activeSessions = 0;
            boolean bound = false;
            int port = 0;
            String upstream = "";
            try {
                modeLabel = ColgramDpiBypass.getLiveModeLabel();
                activeSessions = ColgramDpiBypass.getActiveSessionCount();
                bound = ColgramDpiBypass.isBound();
                port = ColgramDpiBypass.activePort();
                upstream = ColgramDpiBypass.upstreamStatus();
            } catch (Throwable ignored) {
            }

            String stealthSummary = "Stealth v6";
            int sniCount = 0;
            try {
                stealthSummary = ColgramPolymorphicStealth.getStealthStatsSummary();
                sniCount = ColgramPolymorphicStealth.getBenignSniCount();
            } catch (Throwable ignored) {
            }

            String wsSummary = "WS Bridge готов";
            int rttMs = -1;
            int prewarmed = 0;
            try {
                wsSummary = ColgramTelegramWsBridge.getLiveTelemetrySummary();
                rttMs = ColgramTelegramWsBridge.getLastHandshakeRttMs();
                prewarmed = ColgramTelegramWsBridge.getPrewarmedTotalCount();
            } catch (Throwable ignored) {
            }

            int pillColor = bound ? pal.semanticEmerald : pal.semanticAmber;
            modePill.setText(TextUtils.isEmpty(modeLabel) ? (bound ? "LOCAL" : "WAIT") : modeLabel);
            modePill.setTextColor(pillColor);
            modePill.setBackground(makeRoundedBg(withAlpha(pillColor, pal.dark ? 0x26 : 0x16),
                    PILL_RADIUS_DP, withAlpha(pillColor, 0x48), 1f));

            chipRtt.setText(rttMs >= 0 ? ("RTT " + rttMs + "мс") : "RTT —");
            chipSni.setText("SNI " + sniCount);
            chipSessions.setText("Конн " + activeSessions + (prewarmed > 0 ? ("+" + prewarmed) : ""));

            String flags = (ColgramConfig.isDohEnabled() ? "DoH" : "DNS")
                    + " · " + (ColgramConfig.isWarpEnabled() ? "WARP ON" : "DIRECT")
                    + (ColgramConfig.isAntiDeleteEnabled() ? " · AntiDel" : "");
            lineStealth.setText(stealthSummary + " · " + flags);
            lineBridge.setText(wsSummary + (bound && port > 0 ? (" · :" + port) : "")
                    + (!TextUtils.isEmpty(upstream) ? (" · " + upstream) : ""));
        }

        private boolean isVisibleOnScreen() {
            return isAttachedToWindow()
                    && getWindowVisibility() == VISIBLE
                    && getVisibility() == VISIBLE
                    && !ApplicationLoader.mainInterfacePaused;
        }

        private void syncPolling() {
            refreshHandler.removeCallbacks(refreshTick);
            if (isVisibleOnScreen()) {
                refreshTelemetry();
                refreshHandler.postDelayed(refreshTick, REFRESH_INTERVAL_MS);
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            syncPolling();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            refreshHandler.removeCallbacks(refreshTick);
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            syncPolling();
        }
    }
}
