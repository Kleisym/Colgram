package org.telegram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramDpiBypass;
import org.colgram.core.ColgramPolymorphicStealth;
import org.colgram.core.ColgramTelegramWsBridge;
import org.colgram.core.ColgramUiBridge;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
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
            pill.setMaxWidth(dp(128));
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

    private static final class MaxHeightScrollView extends ScrollView {
        MaxHeightScrollView(Context context) {
            super(context);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int screenH = AndroidUtilities.displaySize != null && AndroidUtilities.displaySize.y > 0
                    ? AndroidUtilities.displaySize.y : dp(720);
            int maxH = Math.max(dp(180), (int) (screenH * 0.58f));
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            int size = MeasureSpec.getSize(heightMeasureSpec);
            if (mode == MeasureSpec.UNSPECIFIED || size > maxH) {
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
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

        MaxHeightScrollView scroll = new MaxHeightScrollView(act);
        scroll.addView(listWrap);
        LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12);
        root.addView(scroll, lp);

        TextView cancelBtn = makeCtaButton(act, LocaleController.getString(R.string.Cancel),
                pal.cardSurface, pal.textSecondary, true, pal.borderSubtle);
        cancelBtn.setOnClickListener(v -> sheet.dismiss());
        root.addView(cancelBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CTA_HEIGHT_DP));

        presentBottomSheet(act, sheet);
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
            MaxHeightScrollView scroll = new MaxHeightScrollView(act);
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

        presentBottomSheet(act, sheet);
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

        MaxHeightScrollView scroll = new MaxHeightScrollView(act);
        scroll.addView(msgCard);
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

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

        presentBottomSheet(act, sheet);
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

        MaxHeightScrollView scroll = new MaxHeightScrollView(act);
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
            if (!sheet.isShowing() || sheet.isDismissed() || !isValidActivity(act)) return;
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 120L));
        presentBottomSheet(act, sheet);
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

        MaxHeightScrollView scroll = new MaxHeightScrollView(act);
        if (customContent != null) {
            if (customContent.getParent() instanceof ViewGroup) {
                ((ViewGroup) customContent.getParent()).removeView(customContent);
            }
            scroll.addView(customContent);
        }
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

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

        presentBottomSheet(act, sheet);
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
            if (hBar.getProgressDrawable() != null) {
                hBar.getProgressDrawable().setColorFilter(pal.accentPrimary, PorterDuff.Mode.SRC_IN);
            }
            if (hBar.getIndeterminateDrawable() != null) {
                hBar.getIndeterminateDrawable().setColorFilter(pal.accentPrimary, PorterDuff.Mode.SRC_IN);
            }
            card.addView(hBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 6, 0, 0, 0, 6));

            pctView = new TextView(act);
            pctView.setText("0%");
            pctView.setTextColor(pal.textSecondary);
            pctView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            pctView.setGravity(Gravity.END);
            card.addView(pctView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            ProgressBar spinner = new ProgressBar(act);
            if (spinner.getIndeterminateDrawable() != null) {
                spinner.getIndeterminateDrawable().setColorFilter(pal.accentPrimary, PorterDuff.Mode.SRC_IN);
            }
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
        presentBottomSheet(act, sheet);
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
        if (TextUtils.isEmpty(text)) return;
        String raw = text.toString().trim();
        String title = raw;
        String subtitle = "";
        int nl = raw.indexOf('\n');
        if (nl > 0) {
            title = raw.substring(0, nl).trim();
            subtitle = raw.substring(nl + 1).trim();
        } else {
            int colon = raw.indexOf(": ");
            if (colon > 0 && colon <= 36 && colon + 2 < raw.length()) {
                title = raw.substring(0, colon).trim();
                subtitle = raw.substring(colon + 2).trim();
            }
        }
        ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        int accent = isError ? pal.semanticCrimson : pal.accentPrimary;
        int effIcon = iconRes != 0 ? iconRes : (isError ? R.drawable.msg_info : R.drawable.msg_check_s);
        Context hostCtx = context != null ? context
                : (fragment != null && fragment.getParentActivity() != null ? fragment.getParentActivity() : ColgramUiBridge.currentActivity());
        showHudBanner(hostCtx, title, subtitle, effIcon, accent, null, null);
    }

    public static void showHudBanner(Context ctx, String title, String subtitle, int iconRes, int accentColor) {
        showHudBanner(ctx, title, subtitle, iconRes, accentColor, null, null);
    }

    public static void showHudBanner(Context ctx, String title, String subtitle, int iconRes, int accentColor,
                                     String actionLabel, Runnable onAction) {
        final String safeTitle = title == null ? "" : title.trim();
        final String safeSub = subtitle == null ? "" : subtitle.trim();
        if (safeTitle.isEmpty() && safeSub.isEmpty()) return;
        Runnable task = () -> {
            Activity act = resolveHudActivity(ctx);
            if (!isValidActivity(act)) {
                Context appCtx = ctx != null ? ctx.getApplicationContext() : ApplicationLoader.applicationContext;
                if (appCtx != null) {
                    String msg = safeSub.isEmpty() ? safeTitle : (safeTitle.isEmpty() ? safeSub : (safeTitle + " — " + safeSub));
                    Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show();
                }
                return;
            }
            ColgramHudManager.getInstance().showBanner(act, safeTitle, safeSub, iconRes, accentColor, actionLabel, onAction);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            UI_HANDLER.post(task);
        }
    }

    public static ProgressHudHandle showProgressHud(Context ctx, int iconRes, CharSequence title,
                                                    CharSequence initialMeta, Runnable onCancel) {
        final ProgressHudHandle handle = new ProgressHudHandle(ctx, iconRes, title, initialMeta, onCancel);
        Runnable startTask = handle::attachAndShowOnUi;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startTask.run();
        } else {
            UI_HANDLER.post(startTask);
        }
        return handle;
    }

    public static void showMalwareWarningSheet(
            Activity act,
            Theme.ResourcesProvider rp,
            String fileName, String ext, long sizeBytes,
            String threatReason, boolean isOpenAction,
            Runnable onProceedConfirmed) {
        if (!isValidActivity(act)) return;
        final ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
        final LinearLayout root = makeSheetRoot(act, pal);
        final String rawName = fileName == null ? "file" : fileName;
        final boolean hasBidiSpoof = hasBidiOverrideChars(rawName);
        final String displayName = sanitizeDisplayFilename(rawName);
        final String extLabel = TextUtils.isEmpty(ext) ? "ФАЙЛ" : ext.trim().toUpperCase(Locale.ROOT);

        root.addView(makeSheetHeader(
                act, pal,
                R.drawable.outline_shield_check, pal.semanticCrimson,
                isOpenAction ? "Опасный файл перед запуском" : "Щит загрузок заблокировал файл",
                isOpenAction ? "Проверьте тип файла перед открытием" : "Потенциально опасный или сверхбольшой файл",
                extLabel
        ), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout infoCard = makeCard(act, pal, false);
        TextView nameTv = new TextView(act);
        nameTv.setText(displayName);
        nameTv.setTextColor(pal.textPrimary);
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        nameTv.setTypeface(AndroidUtilities.bold());
        nameTv.setMaxLines(3);
        nameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        infoCard.addView(nameTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        LinearLayout pillsRow = new LinearLayout(act);
        pillsRow.setOrientation(LinearLayout.HORIZONTAL);
        pillsRow.addView(makePillChip(act, formatBytesRu(sizeBytes),
                withAlpha(pal.semanticCrimson, pal.dark ? 0x26 : 0x14),
                pal.semanticCrimson, withAlpha(pal.semanticCrimson, 0x48)),
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
        pillsRow.addView(makePillChip(act, "Тип: " + extLabel,
                pal.chipSurface, pal.textSecondary, pal.borderSubtle),
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0));
        if (hasBidiSpoof) {
            pillsRow.addView(makePillChip(act, "RLO-подмена!",
                    withAlpha(pal.semanticAmber, 0x28), pal.semanticAmber, withAlpha(pal.semanticAmber, 0x55)),
                    LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }
        infoCard.addView(pillsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        TextView reasonTv = new TextView(act);
        String reason = TextUtils.isEmpty(threatReason)
                ? "Файл содержит исполняемое расширение или превышает безопасный лимит автозагрузки."
                : threatReason;
        if (hasBidiSpoof) {
            reason = reason + "\nОбнаружены скрытые символы направления текста (Unicode RLO), маскирующие расширение.";
        }
        reasonTv.setText(reason);
        reasonTv.setTextColor(pal.textSecondary);
        reasonTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
        reasonTv.setLineSpacing(dp(2.5f), 1.0f);
        infoCard.addView(reasonTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        MaxHeightScrollView scroll = new MaxHeightScrollView(act);
        scroll.addView(infoCard);
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        final BottomSheet sheet = createFlagshipSheet(act, rp, false, false, root);
        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView proceedBtn = makeCtaButton(
                act,
                isOpenAction ? "Всё равно открыть" : "Всё равно скачать",
                pal.cardSurface,
                pal.semanticCrimson,
                true,
                withAlpha(pal.semanticCrimson, 0x60)
        );
        proceedBtn.setOnClickListener(v -> {
            sheet.dismiss();
            if (onProceedConfirmed != null) onProceedConfirmed.run();
        });

        TextView cancelBtn = makeCtaButton(
                act,
                LocaleController.getString(R.string.Cancel),
                pal.accentPrimary,
                0xFFFFFFFF,
                false,
                0
        );
        cancelBtn.setOnClickListener(v -> sheet.dismiss());

        btnRow.addView(proceedBtn, LayoutHelper.createLinear(0, CTA_HEIGHT_DP, 1.05f, 0, 0, 10, 0));
        btnRow.addView(cancelBtn, LayoutHelper.createLinear(0, CTA_HEIGHT_DP, 1f));
        root.addView(btnRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        presentBottomSheet(act, sheet);
    }

    private static void presentBottomSheet(Activity act, BottomSheet sheet) {
        if (sheet == null || !isValidActivity(act)) return;
        if (act instanceof org.telegram.ui.LaunchActivity) {
            try {
                BaseFragment last = org.telegram.ui.LaunchActivity.getSafeLastFragment();
                if (last != null && !last.isFinishing() && last.getParentLayout() != null && last.getParentActivity() == act) {
                    android.app.Dialog visible = last.getVisibleDialog();
                    if (visible == null || (visible instanceof BottomSheet && ((BottomSheet) visible).isDismissed())) {
                        if (last.showDialog(sheet) != null) {
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        sheet.show();
    }

    private static Activity resolveHudActivity(Context ctx) {
        if (ctx instanceof Activity) {
            Activity a = (Activity) ctx;
            if (isValidActivity(a)) return a;
        }
        if (ctx != null) {
            Activity found = AndroidUtilities.findActivity(ctx);
            if (isValidActivity(found)) return found;
        }
        Activity cur = ColgramUiBridge.currentActivity();
        if (isValidActivity(cur)) return cur;
        return isValidActivity(LaunchActivity.instance) ? LaunchActivity.instance : null;
    }

    private static boolean hasBidiOverrideChars(String name) {
        if (name == null) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069') || c == '\u200E' || c == '\u200F') {
                return true;
            }
        }
        return false;
    }

    private static String sanitizeDisplayFilename(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069') || c == '\u200E' || c == '\u200F' || c < 0x20) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String formatBytesRu(long bytes) {
        if (bytes <= 0L) return "0 Б";
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.2f ГБ", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024L) {
            return Math.max(1L, bytes / 1024L) + " КБ";
        }
        return bytes + " Б";
    }

    private static String formatProgressMetaRu(long copied, long total) {
        if (total <= 0L) {
            return copied <= 0L ? "Подготовка…" : formatBytesRu(copied);
        }
        if (total >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.2f / %.2f ГБ",
                    Math.min(copied, total) / (1024.0 * 1024.0 * 1024.0),
                    total / (1024.0 * 1024.0 * 1024.0));
        }
        if (total >= 1024L * 1024L) {
            long cMb = Math.round(Math.min(copied, total) / (1024.0 * 1024.0));
            long tMb = Math.max(1L, Math.round(total / (1024.0 * 1024.0)));
            return cMb + " / " + tMb + " МБ";
        }
        long cKb = Math.round(Math.min(copied, total) / 1024.0);
        long tKb = Math.max(1L, Math.round(total / 1024.0));
        return cKb + " / " + tKb + " КБ";
    }

    public static final class ColgramProgressBarView extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF fillRect = new RectF();
        private final Matrix shimmerMatrix = new Matrix();
        private LinearGradient fillShader;
        private LinearGradient shimmerShader;
        private int lastW;
        private int accentColor;
        private int trackColor;
        private float animatedFraction = 0f;
        private float targetFraction = 0f;
        private boolean indeterminate = false;
        private float shimmerOffset = 0f;
        private ObjectAnimator fractionAnim;
        private ValueAnimator shimmerAnim;

        public ColgramProgressBarView(Context context) {
            super(context);
            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            this.accentColor = pal.accentPrimary;
            this.trackColor = withAlpha(pal.textPrimary, pal.dark ? 0x1A : 0x12);
        }

        public void setAccentColor(int accent, int track) {
            if (this.accentColor != accent || this.trackColor != track) {
                this.accentColor = accent;
                this.trackColor = track;
                this.fillShader = null;
                invalidate();
            }
        }

        public float getAnimatedFraction() {
            return animatedFraction;
        }

        public void setAnimatedFraction(float fraction) {
            this.animatedFraction = Math.max(0f, Math.min(1f, fraction));
            invalidate();
        }

        public void setProgressFraction(float fraction, boolean animate) {
            float clamped = Math.max(0f, Math.min(1f, fraction));
            if (indeterminate) {
                indeterminate = false;
                syncShimmerAnim();
            }
            targetFraction = clamped;
            if (fractionAnim != null) {
                fractionAnim.cancel();
                fractionAnim = null;
            }
            if (animate && Math.abs(clamped - animatedFraction) > 0.002f) {
                fractionAnim = ObjectAnimator.ofFloat(this, "animatedFraction", animatedFraction, clamped);
                fractionAnim.setDuration(180L);
                fractionAnim.setInterpolator(new DecelerateInterpolator());
                fractionAnim.start();
            } else {
                setAnimatedFraction(clamped);
            }
        }

        public void setIndeterminate(boolean ind) {
            if (this.indeterminate != ind) {
                this.indeterminate = ind;
                if (ind && fractionAnim != null) {
                    fractionAnim.cancel();
                    fractionAnim = null;
                }
                syncShimmerAnim();
                invalidate();
            }
        }

        public void stopAnimations() {
            if (fractionAnim != null) {
                fractionAnim.cancel();
                fractionAnim = null;
            }
            if (shimmerAnim != null) {
                shimmerAnim.cancel();
                shimmerAnim = null;
            }
        }

        private void syncShimmerAnim() {
            boolean shouldRun = indeterminate
                    && isAttachedToWindow()
                    && getVisibility() == VISIBLE
                    && getWindowVisibility() == VISIBLE
                    && !ApplicationLoader.mainInterfacePaused;
            if (shouldRun && shimmerAnim == null) {
                shimmerAnim = ValueAnimator.ofFloat(0f, 1f);
                shimmerAnim.setDuration(1100L);
                shimmerAnim.setRepeatCount(ValueAnimator.INFINITE);
                shimmerAnim.setInterpolator(new LinearInterpolator());
                shimmerAnim.addUpdateListener(a -> {
                    shimmerOffset = (float) a.getAnimatedValue();
                    invalidate();
                });
                shimmerAnim.start();
            } else if (!shouldRun && shimmerAnim != null) {
                shimmerAnim.cancel();
                shimmerAnim = null;
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            syncShimmerAnim();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopAnimations();
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            syncShimmerAnim();
        }

        @Override
        protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            syncShimmerAnim();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h * 0.5f;

            trackPaint.setColor(trackColor);
            trackRect.set(0f, 0f, w, h);
            canvas.drawRoundRect(trackRect, r, r, trackPaint);

            if (fillShader == null || lastW != w) {
                lastW = w;
                int c2 = ColgramAeroStyler.Palette.resolve().accentSecondary;
                if (accentColor != ColgramAeroStyler.Palette.resolve().accentPrimary) {
                    c2 = accentColor;
                }
                fillShader = new LinearGradient(0f, 0f, w, 0f, accentColor, c2, Shader.TileMode.CLAMP);
                fillPaint.setShader(fillShader);
                shimmerShader = new LinearGradient(-w * 0.35f, 0f, w * 0.35f, 0f,
                        new int[]{withAlpha(accentColor, 0x33), accentColor, withAlpha(accentColor, 0x33)},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
                shimmerPaint.setShader(shimmerShader);
            }

            if (indeterminate) {
                float barW = w * 0.42f;
                float startX = (w + barW) * shimmerOffset - barW;
                shimmerMatrix.setTranslate(startX, 0f);
                shimmerShader.setLocalMatrix(shimmerMatrix);
                fillRect.set(Math.max(0f, startX), 0f, Math.min(w, startX + barW), h);
                if (fillRect.width() > 0f) {
                    canvas.drawRoundRect(fillRect, r, r, shimmerPaint);
                }
            } else if (animatedFraction > 0f) {
                float fillW = Math.max(h, w * animatedFraction);
                fillRect.set(0f, 0f, Math.min(w, fillW), h);
                canvas.drawRoundRect(fillRect, r, r, fillPaint);
            }
        }
    }

    public static final class ProgressHudHandle {
        private final Context initCtx;
        private final int iconRes;
        private final CharSequence initTitle;
        private final CharSequence initMeta;
        private final Runnable onCancel;

        private boolean attached;
        private boolean finished;
        private boolean dismissed;
        private long lastBytes;
        private long lastTotal;
        private CharSequence latestTitle;
        private CharSequence latestMeta;
        private ColgramHudManager.HudCardView cardView;

        ProgressHudHandle(Context ctx, int iconRes, CharSequence title, CharSequence initialMeta, Runnable onCancel) {
            this.initCtx = ctx;
            this.iconRes = iconRes;
            this.initTitle = title == null ? "" : title;
            this.initMeta = initialMeta == null ? "" : initialMeta;
            this.onCancel = onCancel;
            this.latestTitle = this.initTitle;
            this.latestMeta = this.initMeta;
        }

        void attachAndShowOnUi() {
            if (dismissed || finished) return;
            Activity act = resolveHudActivity(initCtx);
            if (!isValidActivity(act)) return;
            cardView = ColgramHudManager.getInstance().showProgressCard(
                    act, iconRes != 0 ? iconRes : R.drawable.msg_download,
                    latestTitle, latestMeta, () -> {
                        dismiss();
                        if (onCancel != null) {
                            onCancel.run();
                        }
                    });
            attached = cardView != null;
            if (attached && lastTotal > 0L) {
                cardView.updateProgress(lastBytes, lastTotal, latestTitle);
            }
        }

        public void update(long bytesCopied, long totalBytes, CharSequence titleOrNull) {
            Runnable r = () -> {
                if (dismissed || finished) return;
                lastBytes = bytesCopied;
                lastTotal = totalBytes;
                if (titleOrNull != null) {
                    latestTitle = titleOrNull;
                }
                latestMeta = formatProgressMetaRu(bytesCopied, totalBytes);
                if (!attached) {
                    attachAndShowOnUi();
                }
                if (cardView != null) {
                    cardView.updateProgress(bytesCopied, totalBytes, latestTitle);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) r.run();
            else UI_HANDLER.post(r);
        }

        public void setIndeterminate(CharSequence meta) {
            Runnable r = () -> {
                if (dismissed || finished) return;
                latestMeta = meta == null ? "" : meta;
                if (!attached) {
                    attachAndShowOnUi();
                }
                if (cardView != null) {
                    cardView.setProgressIndeterminate(latestMeta);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) r.run();
            else UI_HANDLER.post(r);
        }

        public void complete(CharSequence doneTitle, CharSequence doneSub) {
            Runnable r = () -> {
                if (dismissed || finished) return;
                if (!attached) {
                    attachAndShowOnUi();
                }
                finished = true;
                if (cardView != null) {
                    ColgramHudManager.getInstance().completeProgressCard(cardView, doneTitle, doneSub);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) r.run();
            else UI_HANDLER.post(r);
        }

        public void fail(CharSequence errTitle, CharSequence errSub) {
            Runnable r = () -> {
                if (dismissed || finished) return;
                if (!attached) {
                    attachAndShowOnUi();
                }
                finished = true;
                if (cardView != null) {
                    ColgramHudManager.getInstance().failProgressCard(cardView, errTitle, errSub);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) r.run();
            else UI_HANDLER.post(r);
        }

        public void dismiss() {
            Runnable r = () -> {
                if (dismissed) return;
                dismissed = true;
                if (cardView != null) {
                    ColgramHudManager.getInstance().dismissCard(cardView);
                    cardView = null;
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) r.run();
            else UI_HANDLER.post(r);
        }
    }

    private static final class ColgramHudManager {
        private static final ColgramHudManager INSTANCE = new ColgramHudManager();

        static ColgramHudManager getInstance() {
            return INSTANCE;
        }

        private HudCardView activeBanner;
        private HudCardView activeProgress;
        private final Runnable autoDismissBanner = () -> {
            if (activeBanner != null) {
                dismissCard(activeBanner);
            }
        };
        private final Runnable autoDismissProgress = () -> {
            if (activeProgress != null) {
                dismissCard(activeProgress);
            }
        };

        void showBanner(Activity act, String title, String subtitle, int iconRes, int accentColor,
                        String actionLabel, Runnable onAction) {
            UI_HANDLER.removeCallbacks(autoDismissBanner);
            ViewGroup host = resolveOverlayHost(act);
            if (host == null) return;

            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            int effAccent = accentColor != 0 ? accentColor : pal.accentPrimary;
            int effIcon = iconRes != 0 ? iconRes
                    : (effAccent == pal.semanticCrimson ? R.drawable.msg_info : R.drawable.msg_check_s);

            if (activeBanner != null && activeBanner.getParent() == host && !activeBanner.isDismissing) {
                activeBanner.bindBanner(pal, effIcon, effAccent, title, subtitle, actionLabel, onAction);
                applyHostMargins(host, activeBanner, false);
                long delay = (effAccent == pal.semanticCrimson || safeLen(subtitle) > 48) ? 4000L : 2800L;
                UI_HANDLER.postDelayed(autoDismissBanner, delay);
                return;
            }
            if (activeBanner != null) {
                removeImmediate(activeBanner);
                activeBanner = null;
            }

            final HudCardView[] cardHolder = new HudCardView[1];
            HudCardView card = new HudCardView(act, false, () -> dismissCard(cardHolder[0]));
            cardHolder[0] = card;
            card.bindBanner(pal, effIcon, effAccent, title, subtitle, actionLabel, onAction);
            activeBanner = card;
            attachAndAnimateIn(host, card, false);

            long delay = (effAccent == pal.semanticCrimson || safeLen(subtitle) > 48) ? 4000L : 2800L;
            UI_HANDLER.postDelayed(autoDismissBanner, delay);
        }

        HudCardView showProgressCard(Activity act, int iconRes, CharSequence title, CharSequence meta, Runnable onCancel) {
            UI_HANDLER.removeCallbacks(autoDismissProgress);
            ViewGroup host = resolveOverlayHost(act);
            if (host == null) return null;
            if (activeProgress != null) {
                removeImmediate(activeProgress);
                activeProgress = null;
            }
            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            HudCardView card = new HudCardView(act, true, onCancel);
            card.bindProgress(pal, iconRes, pal.accentPrimary, title, meta);
            activeProgress = card;
            attachAndAnimateIn(host, card, true);
            return card;
        }

        void completeProgressCard(HudCardView card, CharSequence title, CharSequence sub) {
            if (card == null || card.isDismissing) return;
            UI_HANDLER.removeCallbacks(autoDismissProgress);
            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            card.showCompleted(pal, title, sub);
            UI_HANDLER.postDelayed(autoDismissProgress, 1200L);
        }

        void failProgressCard(HudCardView card, CharSequence title, CharSequence sub) {
            if (card == null || card.isDismissing) return;
            UI_HANDLER.removeCallbacks(autoDismissProgress);
            ColgramAeroStyler.Palette pal = ColgramAeroStyler.Palette.resolve();
            card.showFailed(pal, title, sub);
            UI_HANDLER.postDelayed(autoDismissProgress, 3200L);
        }

        void dismissCard(HudCardView card) {
            if (card == null || card.isDismissing) return;
            card.isDismissing = true;
            if (card == activeBanner) {
                UI_HANDLER.removeCallbacks(autoDismissBanner);
            } else if (card == activeProgress) {
                UI_HANDLER.removeCallbacks(autoDismissProgress);
            }
            if (card.progressBar != null) {
                card.progressBar.stopAnimations();
            }
            float targetY = card.topOverlay ? -dp(24) : dp(24);
            card.animate().cancel();
            card.animate()
                    .alpha(0f)
                    .translationY(targetY)
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(190L)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            removeImmediate(card);
                            if (card == activeBanner) activeBanner = null;
                            if (card == activeProgress) activeProgress = null;
                        }
                    })
                    .start();
        }

        private void removeImmediate(HudCardView card) {
            if (card == null) return;
            card.animate().setListener(null).cancel();
            ViewCompat.setOnApplyWindowInsetsListener(card, null);
            if (card.progressBar != null) {
                card.progressBar.stopAnimations();
            }
            if (card.getParent() instanceof ViewGroup) {
                ((ViewGroup) card.getParent()).removeView(card);
            }
            if (card == activeBanner) activeBanner = null;
            if (card == activeProgress) activeProgress = null;
        }

        private ViewGroup resolveOverlayHost(Activity act) {
            if (!isValidActivity(act)) return null;
            try {
                BaseFragment last = LaunchActivity.getSafeLastFragment();
                if (last != null && last.getVisibleDialog() instanceof BottomSheet) {
                    BottomSheet bs = (BottomSheet) last.getVisibleDialog();
                    if (bs.isShowing() && !bs.isDismissed() && bs.getWindow() != null
                            && bs.getWindow().getDecorView() instanceof ViewGroup
                            && bs.getWindow().getDecorView().isAttachedToWindow()) {
                        return (ViewGroup) bs.getWindow().getDecorView();
                    }
                }
            } catch (Throwable ignored) {}
            if (act.getWindow() != null && act.getWindow().getDecorView() instanceof ViewGroup) {
                return (ViewGroup) act.getWindow().getDecorView();
            }
            return null;
        }

        private void attachAndAnimateIn(ViewGroup host, HudCardView card, boolean topOverlay) {
            int hostW = host.getWidth();
            if (hostW <= 0) {
                hostW = AndroidUtilities.displaySize != null && AndroidUtilities.displaySize.x > 0
                        ? AndroidUtilities.displaySize.x : dp(360);
            }
            int maxW = Math.max(dp(200), Math.min(hostW - dp(28), dp(420)));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    maxW,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    (topOverlay ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL
            );
            card.setLayoutParams(lp);
            applyHostMargins(host, card, topOverlay);

            ViewCompat.setOnApplyWindowInsetsListener(card, (v, insets) -> {
                applyHostMargins(host, card, topOverlay, insets);
                return insets;
            });

            host.addView(card);
            card.setAlpha(0f);
            card.setScaleX(0.96f);
            card.setScaleY(0.96f);
            card.setTranslationY(topOverlay ? -dp(20) : dp(20));
            card.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(240L)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .start();
        }

        private void applyHostMargins(ViewGroup host, HudCardView card, boolean topOverlay) {
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(host);
            applyHostMargins(host, card, topOverlay, insets);
        }

        private void applyHostMargins(ViewGroup host, HudCardView card, boolean topOverlay, WindowInsetsCompat insets) {
            if (!(card.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
            int topInset = AndroidUtilities.statusBarHeight;
            int bottomInset = AndroidUtilities.navigationBarHeight;
            if (insets != null) {
                Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                topInset = Math.max(topInset, sys.top);
                bottomInset = Math.max(bottomInset, Math.max(sys.bottom, ime.bottom));
            }
            boolean changed = false;
            if (topOverlay) {
                int targetTop = topInset + dp(10);
                if (lp.topMargin != targetTop) {
                    lp.topMargin = targetTop;
                    changed = true;
                }
            } else {
                int targetBottom = bottomInset + dp(14);
                if (lp.bottomMargin != targetBottom) {
                    lp.bottomMargin = targetBottom;
                    changed = true;
                }
            }
            if (changed) {
                card.setLayoutParams(lp);
            }
        }

        private static int safeLen(String s) {
            return s == null ? 0 : s.length();
        }

        static final class HudCardView extends LinearLayout {
            final boolean topOverlay;
            final FrameLayout iconBadge;
            final ImageView iconView;
            final TextView titleView;
            final TextView subtitleView;
            final TextView metaView;
            final ColgramProgressBarView progressBar;
            final TextView ctaBtn;
            final ImageView closeBtn;
            final Runnable onClose;
            boolean isDismissing;
            private float downX;
            private float downY;
            private boolean swiping;

            HudCardView(Context ctx, boolean isProgress, Runnable onClose) {
                super(ctx);
                this.topOverlay = isProgress;
                this.onClose = onClose;
                setOrientation(HORIZONTAL);
                setGravity(Gravity.CENTER_VERTICAL);
                setPadding(dp(14), dp(11), dp(14), dp(11));
                if (Build.VERSION.SDK_INT >= 21) {
                    setElevation(dp(10));
                }

                iconBadge = new FrameLayout(ctx);
                iconView = new ImageView(ctx);
                iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                iconBadge.addView(iconView, LayoutHelper.createFrame(18, 18, Gravity.CENTER));
                addView(iconBadge, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

                LinearLayout centerCol = new LinearLayout(ctx);
                centerCol.setOrientation(VERTICAL);
                addView(centerCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

                if (isProgress) {
                    LinearLayout topLine = new LinearLayout(ctx);
                    topLine.setOrientation(HORIZONTAL);
                    topLine.setGravity(Gravity.CENTER_VERTICAL);

                    titleView = new TextView(ctx);
                    titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
                    titleView.setTypeface(AndroidUtilities.bold());
                    titleView.setMaxLines(1);
                    titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    topLine.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

                    metaView = new TextView(ctx);
                    metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
                    metaView.setMaxLines(1);
                    topLine.addView(metaView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
                    centerCol.addView(topLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

                    subtitleView = new TextView(ctx);
                    subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
                    subtitleView.setMaxLines(1);
                    subtitleView.setEllipsize(TextUtils.TruncateAt.END);
                    subtitleView.setVisibility(GONE);
                    centerCol.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

                    progressBar = new ColgramProgressBarView(ctx);
                    centerCol.addView(progressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 6, 0, 6, 0, 0));
                    ctaBtn = null;
                } else {
                    titleView = new TextView(ctx);
                    titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
                    titleView.setTypeface(AndroidUtilities.bold());
                    titleView.setMaxLines(2);
                    titleView.setEllipsize(TextUtils.TruncateAt.END);
                    centerCol.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

                    subtitleView = new TextView(ctx);
                    subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
                    subtitleView.setMaxLines(2);
                    subtitleView.setEllipsize(TextUtils.TruncateAt.END);
                    centerCol.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

                    metaView = null;
                    progressBar = null;

                    ctaBtn = new TextView(ctx);
                    ctaBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
                    ctaBtn.setTypeface(AndroidUtilities.bold());
                    ctaBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
                    ctaBtn.setSingleLine(true);
                    ctaBtn.setVisibility(GONE);
                    addView(ctaBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                            Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
                }

                closeBtn = new ImageView(ctx);
                closeBtn.setImageResource(R.drawable.msg_close);
                closeBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                closeBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
                closeBtn.setOnClickListener(v -> {
                    if (onClose != null) onClose.run();
                });
                addView(closeBtn, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
            }

            private void applySurface(ColgramAeroStyler.Palette pal, int accent, int iconRes) {
                int surface = withAlpha(pal.cardElevated, 0xF2);
                int border = withAlpha(accent, pal.dark ? 0x52 : 0x34);
                setBackground(makeRoundedBg(surface, 18f, border, 1f));
                iconBadge.setBackground(makeRoundedBg(withAlpha(accent, 0x22), 10f, withAlpha(accent, 0x44), 1f));
                if (iconRes != 0) {
                    iconView.setImageResource(iconRes);
                }
                iconView.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
                titleView.setTextColor(pal.textPrimary);
                subtitleView.setTextColor(pal.textSecondary);
                if (metaView != null) {
                    metaView.setTextColor(pal.textSecondary);
                }
                closeBtn.setBackground(makeRoundedBg(withAlpha(pal.textPrimary, pal.dark ? 0x16 : 0x0E),
                        PILL_RADIUS_DP, 0, 0f));
                closeBtn.setColorFilter(pal.textSecondary, PorterDuff.Mode.SRC_IN);
            }

            void bindBanner(ColgramAeroStyler.Palette pal, int iconRes, int accent,
                            String title, String subtitle, String actionLabel, Runnable onAction) {
                applySurface(pal, accent, iconRes);
                titleView.setText(title == null ? "" : title);
                if (TextUtils.isEmpty(subtitle)) {
                    subtitleView.setVisibility(GONE);
                } else {
                    subtitleView.setText(subtitle);
                    subtitleView.setVisibility(VISIBLE);
                }
                if (ctaBtn != null) {
                    if (!TextUtils.isEmpty(actionLabel) && onAction != null) {
                        ctaBtn.setText(actionLabel);
                        ctaBtn.setTextColor(accent);
                        ColgramMenuChrome.attachTactileFeedback(ctaBtn, PILL_RADIUS_DP,
                                withAlpha(accent, 0x1E), withAlpha(accent, 0x44), withAlpha(accent, 0x30));
                        ctaBtn.setOnClickListener(v -> {
                            onAction.run();
                            if (onClose != null) onClose.run();
                        });
                        ctaBtn.setVisibility(VISIBLE);
                        closeBtn.setVisibility(GONE);
                    } else {
                        ctaBtn.setVisibility(GONE);
                        closeBtn.setVisibility(VISIBLE);
                    }
                }
            }

            void bindProgress(ColgramAeroStyler.Palette pal, int iconRes, int accent,
                              CharSequence title, CharSequence meta) {
                applySurface(pal, accent, iconRes);
                titleView.setText(title == null ? "" : title);
                if (metaView != null) {
                    metaView.setText(meta == null ? "" : meta);
                }
                if (progressBar != null) {
                    progressBar.setAccentColor(accent, withAlpha(pal.textPrimary, pal.dark ? 0x1A : 0x12));
                    progressBar.setIndeterminate(true);
                }
            }

            void updateProgress(long copied, long total, CharSequence titleOrNull) {
                if (titleOrNull != null) {
                    titleView.setText(titleOrNull);
                }
                if (metaView != null) {
                    metaView.setText(formatProgressMetaRu(copied, total));
                }
                if (progressBar != null) {
                    if (total > 0L) {
                        float frac = Math.max(0f, Math.min(1f, (float) copied / (float) total));
                        progressBar.setProgressFraction(frac, true);
                    } else {
                        progressBar.setIndeterminate(true);
                    }
                }
            }

            void setProgressIndeterminate(CharSequence meta) {
                if (metaView != null && meta != null) {
                    metaView.setText(meta);
                }
                if (progressBar != null) {
                    progressBar.setIndeterminate(true);
                }
            }

            void showCompleted(ColgramAeroStyler.Palette pal, CharSequence doneTitle, CharSequence doneSub) {
                applySurface(pal, pal.semanticEmerald, R.drawable.msg_check_s);
                titleView.setText(TextUtils.isEmpty(doneTitle) ? "Готово" : doneTitle);
                if (!TextUtils.isEmpty(doneSub)) {
                    subtitleView.setText(doneSub);
                    subtitleView.setVisibility(VISIBLE);
                }
                if (metaView != null) {
                    metaView.setText("Готово");
                    metaView.setTextColor(pal.semanticEmerald);
                }
                if (progressBar != null) {
                    progressBar.setAccentColor(pal.semanticEmerald, withAlpha(pal.semanticEmerald, 0x22));
                    progressBar.setProgressFraction(1f, true);
                }
                closeBtn.setVisibility(GONE);
            }

            void showFailed(ColgramAeroStyler.Palette pal, CharSequence errTitle, CharSequence errSub) {
                applySurface(pal, pal.semanticCrimson, R.drawable.msg_info);
                titleView.setText(TextUtils.isEmpty(errTitle) ? "Ошибка" : errTitle);
                if (!TextUtils.isEmpty(errSub)) {
                    subtitleView.setText(errSub);
                    subtitleView.setVisibility(VISIBLE);
                }
                if (metaView != null) {
                    metaView.setText("");
                }
                if (progressBar != null) {
                    progressBar.stopAnimations();
                    progressBar.setVisibility(GONE);
                }
                closeBtn.setVisibility(VISIBLE);
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    swiping = false;
                } else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    if (Math.abs(ev.getRawX() - downX) > dp(10) || Math.abs(ev.getRawY() - downY) > dp(12)) {
                        swiping = true;
                        return true;
                    }
                }
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    return true;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(10)) {
                        swiping = true;
                    }
                    if (swiping) {
                        if (Math.abs(dy) > Math.abs(dx)) {
                            float clampedY = topOverlay ? Math.min(0f, dy) : Math.max(0f, dy);
                            setTranslationX(0f);
                            setTranslationY(clampedY);
                            setAlpha(Math.max(0.25f, 1f - Math.abs(clampedY) / (float) Math.max(1, getHeight() * 2)));
                        } else {
                            setTranslationY(0f);
                            setTranslationX(dx);
                            setAlpha(Math.max(0.25f, 1f - Math.abs(dx) / (float) Math.max(1, getWidth())));
                        }
                    }
                    return true;
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    boolean horizDismiss = Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) > dp(topOverlay ? 72 : 56);
                    boolean vertDismiss = !topOverlay && Math.abs(dy) > Math.abs(dx) && dy > dp(32);
                    if (swiping && (horizDismiss || vertDismiss)) {
                        if (onClose != null) {
                            onClose.run();
                        }
                    } else if (swiping) {
                        animate().translationX(0f).translationY(0f).alpha(1f).setDuration(160L).start();
                    }
                    swiping = false;
                    return true;
                }
                return super.onTouchEvent(ev);
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                if (progressBar != null) {
                    progressBar.stopAnimations();
                }
                ColgramHudManager mgr = ColgramHudManager.getInstance();
                if (mgr.activeBanner == this) mgr.activeBanner = null;
                if (mgr.activeProgress == this) mgr.activeProgress = null;
            }
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
            if (badge.getChildCount() > 0 && badge.getChildAt(0) instanceof ImageView) {
                ((ImageView) badge.getChildAt(0)).setColorFilter(pal.accentPrimary, PorterDuff.Mode.SRC_IN);
            }
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
