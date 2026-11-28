# -*- coding: utf-8 -*-
"""
Colgram Track 3 Modular Patcher — Zero Text-Overlap UI, Floating HUD, and Client-Side Premium+.

Contract E entrypoint:
    apply(repo_path: str, patch_file_fn, eol_patch_fn, koliti_enabled: bool = True) -> None

Every transform is strictly 2-pass idempotent, CRLF/LF-preserving, and isolated from
Track 1 (`bypass_bg_patches.py`) and Track 2 (`spam_storage_patches.py`) patch anchors.
"""

import os
import re


def _eol_replace(path: str, old: str, new: str, label: str, eol_patch_fn, marker: str = "") -> None:
    if os.path.isfile(path):
        with open(path, "r", encoding="utf-8", errors="ignore", newline="") as fh:
            norm = fh.read().replace("\r\n", "\n")
        if (marker and marker in norm) or new in norm:
            return
    eol_patch_fn(path, old, new, label)


def _upgrade_or_patch(
    path: str,
    legacy_variants,
    raw_old: str,
    new: str,
    label: str,
    eol_patch_fn,
    marker: str = "",
) -> None:
    if os.path.isfile(path):
        with open(path, "r", encoding="utf-8", errors="ignore", newline="") as fh:
            norm = fh.read().replace("\r\n", "\n")
        if (marker and marker in norm) or new in norm:
            return
        for legacy in legacy_variants:
            if legacy and legacy in norm:
                eol_patch_fn(path, legacy, new, label)
                return
    eol_patch_fn(path, raw_old, new, label)


def _patch_ui_cells_and_hud(repo_path: str, patch_file_fn, eol_patch_fn) -> None:
    ui = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui")

    # 1. CheckBoxCell.java — hide empty valueTextView, prevent value/links overlap & fix multiline hit height
    checkbox_cell = os.path.join(ui, "Cells", "CheckBoxCell.java")
    _upgrade_or_patch(
        checkbox_cell,
        (
            (
                "        } else if (isMultiline) {\n"
                "            // colgram-checkbox-layout-v1\n"
                "            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));\n"
                "            int colgramMinH = dp(50) + (needDivider ? 1 : 0);\n"
                "            if (getMeasuredHeight() < colgramMinH) {\n"
                "                setMeasuredDimension(getMeasuredWidth(), colgramMinH);\n"
                "            }\n"
                "        } else {"
            ),
        ),
        (
            "        } else if (isMultiline) {\n"
            "            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));\n"
            "        } else {"
        ),
        (
            "        } else if (isMultiline) {\n"
            "            // colgram-checkbox-layout-v2\n"
            "            int w = MeasureSpec.getSize(widthMeasureSpec);\n"
            "            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));\n"
            "            if (valueTextView != null && valueTextView.getVisibility() == VISIBLE && textView != null && textView.getLayoutParams() instanceof MarginLayoutParams) {\n"
            "                int valW = valueTextView.getMeasuredWidth();\n"
            "                if (valW > 0) {\n"
            "                    MarginLayoutParams tlp = (MarginLayoutParams) textView.getLayoutParams();\n"
            "                    int maxTextW = Math.max(dp(80), w - getPaddingLeft() - getPaddingRight() - tlp.leftMargin - tlp.rightMargin - valW - dp(8));\n"
            "                    if (textView.getMeasuredWidth() > maxTextW) {\n"
            "                        textView.measure(MeasureSpec.makeMeasureSpec(maxTextW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));\n"
            "                        setMeasuredDimension(getMeasuredWidth(), Math.max(getMeasuredHeight(), textView.getMeasuredHeight() + (needDivider ? 1 : 0)));\n"
            "                    }\n"
            "                }\n"
            "            }\n"
            "            int colgramMinH = dp(50) + (needDivider ? 1 : 0);\n"
            "            if (getMeasuredHeight() < colgramMinH) {\n"
            "                setMeasuredDimension(getMeasuredWidth(), colgramMinH);\n"
            "            }\n"
            "        } else {"
        ),
        "CheckBoxCell.java isMultiline min height and value overlap guard",
        eol_patch_fn,
        marker="// colgram-checkbox-layout-v2",
    )
    _eol_replace(
        checkbox_cell,
        (
            "        if (click1Container != null) {\n"
            "            MarginLayoutParams margin = (MarginLayoutParams) click1Container.getLayoutParams();\n"
            "            click1Container.measure(MeasureSpec.makeMeasureSpec(width - margin.leftMargin - margin.rightMargin, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(50), MeasureSpec.EXACTLY));\n"
            "        }\n"
            "        if (click2Container != null) {\n"
            "            click2Container.measure(MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(50), MeasureSpec.EXACTLY));\n"
            "        }"
        ),
        (
            "        int colgramClickH = Math.max(dp(50), getMeasuredHeight());\n"
            "        if (click1Container != null) {\n"
            "            MarginLayoutParams margin = (MarginLayoutParams) click1Container.getLayoutParams();\n"
            "            click1Container.measure(MeasureSpec.makeMeasureSpec(width - margin.leftMargin - margin.rightMargin, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(colgramClickH, MeasureSpec.EXACTLY));\n"
            "        }\n"
            "        if (click2Container != null) {\n"
            "            click2Container.measure(MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(colgramClickH, MeasureSpec.EXACTLY));\n"
            "        }"
        ),
        "CheckBoxCell.java click container dynamic height",
        eol_patch_fn,
    )
    _eol_replace(
        checkbox_cell,
        (
            "        valueTextView.setText(value);\n"
            "        needDivider = divider;"
        ),
        (
            "        valueTextView.setText(value);\n"
            "        valueTextView.setVisibility(android.text.TextUtils.isEmpty(value) ? GONE : VISIBLE);\n"
            "        needDivider = divider;"
        ),
        "CheckBoxCell.java hide empty valueTextView",
        eol_patch_fn,
    )
    _eol_replace(
        checkbox_cell,
        (
            "            if (currentType != TYPE_CHECK_BOX_URL) {\n"
            "//                layoutParams.height = LayoutParams.WRAP_CONTENT;\n"
            "//                layoutParams.gravity = (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP;\n"
            "//                layoutParams.topMargin = dp(14);\n"
            "//                layoutParams.bottomMargin = dp(10);\n"
            "            }"
        ),
        (
            "            if (currentType != TYPE_CHECK_BOX_URL) {\n"
            "                layoutParams.height = LayoutParams.WRAP_CONTENT;\n"
            "                layoutParams.topMargin = 0;\n"
            "                layoutParams.bottomMargin = 0;\n"
            "                linksTextView.setPadding(0, dp(10), 0, dp(10));\n"
            "                textView.setLayoutParams(layoutParams);\n"
            "                requestLayout();\n"
            "            }"
        ),
        "CheckBoxCell.java setMultiline padding",
        eol_patch_fn,
    )

    # 2. TextSettingsCell.java — exact innerW math in release builds + requestLayout on text/icon change + RTL fix
    text_settings_cell = os.path.join(ui, "Cells", "TextSettingsCell.java")
    _eol_replace(
        text_settings_cell,
        "    private boolean betterLayout = BuildVars.DEBUG_PRIVATE_VERSION;",
        "    private boolean betterLayout = true;",
        "TextSettingsCell.java enable betterLayout in release",
        eol_patch_fn,
        marker="// colgram-textsettingscell-layout-v2",
    )
    _v1_tsc_measure = (
        "    @Override\n"
        "    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {\n"
        "        // colgram-textsettingscell-layout-v1\n"
        "        int totalW = MeasureSpec.getSize(widthMeasureSpec);\n"
        "        int cellH = AndroidUtilities.dp(50) + (needDivider ? 1 : 0);\n"
        "        setMeasuredDimension(totalW, cellH);\n\n"
        "        int leftMarginPx = AndroidUtilities.dp(imageView.getVisibility() == VISIBLE ? 58 : padding);\n"
        "        int rightMarginPx = AndroidUtilities.dp(Math.max(12, padding - 4));\n"
        "        int innerW = Math.max(0, totalW - getPaddingLeft() - getPaddingRight() - leftMarginPx - rightMarginPx);\n"
        "        if (valueImageView.getVisibility() == VISIBLE) {\n"
        "            valueImageView.measure(MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY));\n"
        "        }\n"
        "        if (imageView.getVisibility() == VISIBLE) {\n"
        "            if (imageViewIsColorful) {\n"
        "                imageView.measure(MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(28), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(28), MeasureSpec.EXACTLY));\n"
        "            } else {\n"
        "                imageView.measure(MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(28), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.AT_MOST));\n"
        "            }\n"
        "        }\n"
        "        if (valueBackupImageView != null && valueBackupImageView.getVisibility() == VISIBLE) {\n"
        "            valueBackupImageView.measure(MeasureSpec.makeMeasureSpec(valueBackupImageView.getLayoutParams().height, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(valueBackupImageView.getLayoutParams().width, MeasureSpec.EXACTLY));\n"
        "            innerW = Math.max(0, innerW - valueBackupImageView.getMeasuredWidth() - AndroidUtilities.dp(8));\n"
        "        }\n"
        "        int textW = innerW;\n"
        "        if (valueTextView.getVisibility() == VISIBLE) {\n"
        "            int maxValWidth = betterLayout\n"
        "                    ? Math.max(AndroidUtilities.dp(48), Math.min((int) (innerW * 0.44f), innerW - AndroidUtilities.dp(96)))\n"
        "                    : innerW / 2;\n"
        "            maxValWidth = Math.max(0, Math.min(innerW, maxValWidth));\n"
        "            valueTextView.measure(MeasureSpec.makeMeasureSpec(maxValWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY));\n"
        "            int valW = valueTextView.getMeasuredWidth();\n"
        "            textW = Math.max(0, innerW - valW - AndroidUtilities.dp(12));\n"
        "            if (valueImageView.getVisibility() == VISIBLE) {\n"
        "                MarginLayoutParams params = (MarginLayoutParams) valueImageView.getLayoutParams();\n"
        "                if (LocaleController.isRTL) {\n"
        "                    params.leftMargin = AndroidUtilities.dp(padding + 4) + valW;\n"
        "                } else {\n"
        "                    params.rightMargin = AndroidUtilities.dp(padding + 4) + valW;\n"
        "                }\n"
        "            }\n"
        "        }\n"
        "        textView.measure(MeasureSpec.makeMeasureSpec(textW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY));\n"
        "    }"
    )
    _raw_tsc_measure = (
        "    @Override\n"
        "    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {\n"
        "        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), AndroidUtilities.dp(50) + (needDivider ? 1 : 0));\n\n"
        "        int availableWidth = getMeasuredWidth() - getPaddingLeft() - getPaddingRight() - AndroidUtilities.dp(34);\n"
        "        int width = betterLayout ? availableWidth : availableWidth / 2;\n"
        "        if (valueImageView.getVisibility() == VISIBLE) {\n"
        "            valueImageView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));\n"
        "        }\n\n"
        "        if (imageView.getVisibility() == VISIBLE) {\n"
        "            if (imageViewIsColorful) {\n"
        "                imageView.measure(MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(28), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(28), MeasureSpec.EXACTLY));\n"
        "            } else {\n"
        "                imageView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.AT_MOST));\n"
        "            }\n"
        "            if (betterLayout) width -= imageView.getMeasuredWidth() + AndroidUtilities.dp(8);\n"
        "        }\n\n"
        "        if (valueBackupImageView != null) {\n"
        "            valueBackupImageView.measure(MeasureSpec.makeMeasureSpec(valueBackupImageView.getLayoutParams().height, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(valueBackupImageView.getLayoutParams().width, MeasureSpec.EXACTLY));\n"
        "            if (betterLayout) width -= valueBackupImageView.getMeasuredWidth() + AndroidUtilities.dp(8);\n"
        "        }\n"
        "        if (valueTextView.getVisibility() == VISIBLE) {\n"
        "            int maxValWidth = betterLayout ? Math.max(AndroidUtilities.dp(72), (int) (availableWidth * 0.45f)) : width;\n"
        "            valueTextView.measure(MeasureSpec.makeMeasureSpec(Math.min(width, maxValWidth), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));\n"
        "            if (betterLayout) {\n"
        "                width = Math.max(AndroidUtilities.dp(80), width - valueTextView.getMeasuredWidth() - AndroidUtilities.dp(10));\n"
        "            } else {\n"
        "                width = Math.max(AndroidUtilities.dp(80), availableWidth - valueTextView.getMeasuredWidth() - AndroidUtilities.dp(8));\n"
        "            }\n\n"
        "            if (valueImageView.getVisibility() == VISIBLE) {\n"
        "                MarginLayoutParams params = (MarginLayoutParams) valueImageView.getLayoutParams();\n"
        "                if (LocaleController.isRTL) {\n"
        "                    params.leftMargin = AndroidUtilities.dp(padding + 4) + valueTextView.getMeasuredWidth();\n"
        "                } else {\n"
        "                    params.rightMargin = AndroidUtilities.dp(padding + 4) + valueTextView.getMeasuredWidth();\n"
        "                }\n"
        "            }\n"
        "        } else {\n"
        "            width = availableWidth;\n"
        "        }\n"
        "        textView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));\n"
        "    }"
    )
    _new_tsc_measure = (
        "    @Override\n"
        "    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {\n"
        "        // colgram-textsettingscell-layout-v2\n"
        "        int totalW = MeasureSpec.getSize(widthMeasureSpec);\n"
        "        int cellH = AndroidUtilities.dp(50) + (needDivider ? 1 : 0);\n"
        "        setMeasuredDimension(totalW, cellH);\n\n"
        "        int leftMarginPx = AndroidUtilities.dp(imageView.getVisibility() == VISIBLE ? 58 : padding);\n"
        "        int rightMarginPx = AndroidUtilities.dp(Math.max(12, padding - 4));\n"
        "        int innerW = Math.max(0, totalW - getPaddingLeft() - getPaddingRight() - leftMarginPx - rightMarginPx);\n"
        "        if (valueImageView.getVisibility() == VISIBLE) {\n"
        "            valueImageView.measure(MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY));\n"
        "        }\n"
        "        if (imageView.getVisibility() == VISIBLE) {\n"
        "            if (imageViewIsColorful) {\n"
        "                imageView.measure(MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(28), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(28), MeasureSpec.EXACTLY));\n"
        "            } else {\n"
        "                imageView.measure(MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(28), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.AT_MOST));\n"
        "            }\n"
        "        }\n"
        "        if (valueBackupImageView != null && valueBackupImageView.getVisibility() == VISIBLE) {\n"
        "            valueBackupImageView.measure(MeasureSpec.makeMeasureSpec(valueBackupImageView.getLayoutParams().height, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(valueBackupImageView.getLayoutParams().width, MeasureSpec.EXACTLY));\n"
        "            innerW = Math.max(0, innerW - valueBackupImageView.getMeasuredWidth() - AndroidUtilities.dp(8));\n"
        "        }\n"
        "        int textW = innerW;\n"
        "        if (valueTextView.getVisibility() == VISIBLE) {\n"
        "            int maxValWidth = betterLayout\n"
        "                    ? Math.max(AndroidUtilities.dp(48), Math.min((int) (innerW * 0.44f), innerW - AndroidUtilities.dp(96)))\n"
        "                    : innerW / 2;\n"
        "            maxValWidth = Math.max(0, Math.min(innerW, maxValWidth));\n"
        "            valueTextView.measure(MeasureSpec.makeMeasureSpec(maxValWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY));\n"
        "            int valW = valueTextView.getMeasuredWidth();\n"
        "            textW = Math.max(0, innerW - valW - AndroidUtilities.dp(12));\n"
        "            if (valueImageView.getVisibility() == VISIBLE) {\n"
        "                MarginLayoutParams params = (MarginLayoutParams) valueImageView.getLayoutParams();\n"
        "                if (LocaleController.isRTL) {\n"
        "                    params.leftMargin = AndroidUtilities.dp(padding + 4) + valW;\n"
        "                } else {\n"
        "                    params.rightMargin = AndroidUtilities.dp(padding + 4) + valW;\n"
        "                }\n"
        "                textW = Math.max(0, textW - valueImageView.getMeasuredWidth() - AndroidUtilities.dp(6));\n"
        "            }\n"
        "        } else if (valueImageView.getVisibility() == VISIBLE) {\n"
        "            textW = Math.max(0, textW - valueImageView.getMeasuredWidth() - AndroidUtilities.dp(8));\n"
        "        }\n"
        "        textView.measure(MeasureSpec.makeMeasureSpec(textW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY));\n"
        "    }"
    )
    _upgrade_or_patch(
        text_settings_cell,
        (_v1_tsc_measure,),
        _raw_tsc_measure,
        _new_tsc_measure,
        "TextSettingsCell.java release width clamp",
        eol_patch_fn,
        marker="// colgram-textsettingscell-layout-v2",
    )
    _eol_replace(
        text_settings_cell,
        (
            "    public void setText(CharSequence text, boolean divider) {\n"
            "        textView.setText(text);\n"
            "        valueTextView.setVisibility(INVISIBLE);\n"
            "        valueImageView.setVisibility(INVISIBLE);\n"
            "        needDivider = divider;\n"
            "        setWillNotDraw(!divider);\n"
            "    }"
        ),
        (
            "    public void setText(CharSequence text, boolean divider) {\n"
            "        textView.setText(text);\n"
            "        valueTextView.setVisibility(INVISIBLE);\n"
            "        valueImageView.setVisibility(INVISIBLE);\n"
            "        needDivider = divider;\n"
            "        setWillNotDraw(!divider);\n"
            "        requestLayout();\n"
            "    }"
        ),
        "TextSettingsCell.java setText requestLayout",
        eol_patch_fn,
    )
    _eol_replace(
        text_settings_cell,
        (
            "    public void setTextAndIcon(CharSequence text, int resId, boolean divider) {\n"
            "        textView.setText(text);\n"
            "        valueTextView.setVisibility(INVISIBLE);\n"
            "        if (resId != 0) {\n"
            "            valueImageView.setVisibility(VISIBLE);\n"
            "            valueImageView.setImageResource(resId);\n"
            "        } else {\n"
            "            valueImageView.setVisibility(INVISIBLE);\n"
            "        }\n"
            "        needDivider = divider;\n"
            "        setWillNotDraw(!divider);\n"
            "    }"
        ),
        (
            "    public void setTextAndIcon(CharSequence text, int resId, boolean divider) {\n"
            "        textView.setText(text);\n"
            "        valueTextView.setVisibility(INVISIBLE);\n"
            "        if (resId != 0) {\n"
            "            valueImageView.setVisibility(VISIBLE);\n"
            "            valueImageView.setImageResource(resId);\n"
            "        } else {\n"
            "            valueImageView.setVisibility(INVISIBLE);\n"
            "        }\n"
            "        needDivider = divider;\n"
            "        setWillNotDraw(!divider);\n"
            "        requestLayout();\n"
            "    }"
        ),
        "TextSettingsCell.java setTextAndIcon requestLayout",
        eol_patch_fn,
    )
    _eol_replace(
        text_settings_cell,
        "            imageView.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));",
        "            imageView.setColorFilter(new PorterDuffColorFilter(org.colgram.core.ColgramThemePolicy.contrastInkOn(color), PorterDuff.Mode.SRC_IN));",
        "TextSettingsCell.java setColorfulIcon contrastInkOn",
        eol_patch_fn,
    )
    _eol_replace(
        text_settings_cell,
        (
            "    public void updateRTL() {\n"
            "        textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);\n"
            "        removeView(textView);\n"
            "        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, padding, 0, padding, 0));\n\n"
            "        valueTextView.setGravity((LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL);\n"
            "        removeView(valueTextView);\n"
            "        addView(valueTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, padding, 0, padding, 0));\n\n"
            "        removeView(imageView);\n"
            "        addView(imageView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 21, 0, 21, 0));"
        ),
        (
            "    public void updateRTL() {\n"
            "        int textMargin = imageView.getVisibility() == VISIBLE ? 58 : padding;\n"
            "        textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);\n"
            "        removeView(textView);\n"
            "        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? padding : textMargin, 0, LocaleController.isRTL ? textMargin : padding, 0));\n\n"
            "        valueTextView.setGravity((LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL);\n"
            "        removeView(valueTextView);\n"
            "        addView(valueTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, padding - 4, 0, padding - 4, 0));\n\n"
            "        removeView(imageView);\n"
            "        addView(imageView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 16, 0, 16, 0));"
        ),
        "TextSettingsCell.java updateRTL preserve margins",
        eol_patch_fn,
    )
    _eol_replace(
        text_settings_cell,
        (
            "            if (LocaleController.isRTL) {\n"
            "                params.rightMargin = AndroidUtilities.dp(58);\n"
            "            } else {\n"
            "                params.leftMargin = AndroidUtilities.dp(58);\n"
            "            }\n"
            "        }\n"
            "    }\n"
            "\n"
            "    public void setColorfulIcon("
        ),
        (
            "            if (LocaleController.isRTL) {\n"
            "                params.rightMargin = AndroidUtilities.dp(58);\n"
            "            } else {\n"
            "                params.leftMargin = AndroidUtilities.dp(58);\n"
            "            }\n"
            "        }\n"
            "        textView.setLayoutParams(params);\n"
            "        requestLayout();\n"
            "    }\n"
            "\n"
            "    public void setColorfulIcon("
        ),
        "TextSettingsCell.java setIcon requestLayout",
        eol_patch_fn,
    )
    _eol_replace(
        text_settings_cell,
        (
            "            if (LocaleController.isRTL) {\n"
            "                params.rightMargin = AndroidUtilities.dp(58);\n"
            "            } else {\n"
            "                params.leftMargin = AndroidUtilities.dp(58);\n"
            "            }\n"
            "        }\n"
            "    }\n"
            "\n"
            "    public void setEnabled(boolean value, ArrayList<Animator> animators)"
        ),
        (
            "            if (LocaleController.isRTL) {\n"
            "                params.rightMargin = AndroidUtilities.dp(58);\n"
            "            } else {\n"
            "                params.leftMargin = AndroidUtilities.dp(58);\n"
            "            }\n"
            "        }\n"
            "        textView.setLayoutParams(params);\n"
            "        requestLayout();\n"
            "    }\n"
            "\n"
            "    public void setEnabled(boolean value, ArrayList<Animator> animators)"
        ),
        "TextSettingsCell.java setColorfulIcon requestLayout",
        eol_patch_fn,
    )

    # 3. AnimatedTextView.java — clamp AT_MOST width against maxWidth & re-elide only when inner bounds change
    anim_text_view = os.path.join(ui, "Components", "AnimatedTextView.java")
    _upgrade_or_patch(
        anim_text_view,
        (
            (
                "        if (adaptWidth && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {\n"
                "            int specSize = MeasureSpec.getSize(widthMeasureSpec);\n"
                "            if (drawable.getWidth() > specSize) {\n"
                "                int innerSpec = Math.max(0, specSize - getPaddingLeft() - getPaddingRight());\n"
                "                drawable.setBounds(getPaddingLeft(), getPaddingTop(), getPaddingLeft() + innerSpec, height - getPaddingBottom());\n"
                "                drawable.setText(drawable.getText(), false, true);\n"
                "            }\n"
                "            width = Math.min(specSize, getPaddingLeft() + (int) Math.ceil(drawable.getWidth()) + getPaddingRight());\n"
                "        }"
            ),
        ),
        (
            "        if (adaptWidth && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {\n"
            "            width = getPaddingLeft() + (int) Math.ceil(drawable.getWidth()) + getPaddingRight();\n"
            "        }"
        ),
        (
            "        if (adaptWidth && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {\n"
            "            int specSize = width;\n"
            "            int innerSpec = Math.max(0, specSize - getPaddingLeft() - getPaddingRight());\n"
            "            if (drawable.getWidth() > innerSpec && drawable.getBounds().width() != innerSpec) {\n"
            "                drawable.setBounds(getPaddingLeft(), getPaddingTop(), getPaddingLeft() + innerSpec, height - getPaddingBottom());\n"
            "                drawable.setText(drawable.getText(), false, true);\n"
            "            }\n"
            "            width = Math.min(specSize, getPaddingLeft() + (int) Math.ceil(drawable.getWidth()) + getPaddingRight());\n"
            "        }"
        ),
        "AnimatedTextView.java clamp AT_MOST width",
        eol_patch_fn,
        marker="int innerSpec = Math.max(0, specSize - getPaddingLeft() - getPaddingRight());\n            if (drawable.getWidth() > innerSpec",
    )

    # 4. TextCheckCell.java — dynamic height, wrap reset on recycle & dp(padding) divider
    text_check_cell = os.path.join(ui, "Cells", "TextCheckCell.java")
    _eol_replace(
        text_check_cell,
        (
            "    @Override\n"
            "    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {\n"
            "        if (isMultiline) {\n"
            "            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));\n"
            "            if (getMeasuredHeight() < AndroidUtilities.dp(height) + (needDivider ? 1 : 0)) {\n"
            "                setMeasuredDimension(getMeasuredWidth(), AndroidUtilities.dp(height) + (needDivider ? 1 : 0));\n"
            "            }\n"
            "        } else {\n"
            "            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(valueTextView.getVisibility() == VISIBLE ? 64 : height) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));\n"
            "        }\n"
            "    }"
        ),
        (
            "    @Override\n"
            "    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {\n"
            "        // colgram-textcheck-dynamic-layout-v1\n"
            "        int w = MeasureSpec.getSize(widthMeasureSpec);\n"
            "        boolean hasVal = valueTextView != null && valueTextView.getVisibility() == VISIBLE;\n"
            "        if (isMultiline || colgramWrapTitle || hasVal) {\n"
            "            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));\n"
            "            int minH = AndroidUtilities.dp(hasVal ? 64 : height) + (needDivider ? 1 : 0);\n"
            "            int contentH = AndroidUtilities.dp(10) + (textView != null ? textView.getMeasuredHeight() : 0)\n"
            "                    + (hasVal ? AndroidUtilities.dp(3) + valueTextView.getMeasuredHeight() + AndroidUtilities.dp(isMultiline ? 0 : 10) : AndroidUtilities.dp(10))\n"
            "                    + (needDivider ? 1 : 0);\n"
            "            int finalH = Math.max(minH, contentH);\n"
            "            if (finalH != getMeasuredHeight()) {\n"
            "                setMeasuredDimension(w, finalH);\n"
            "            }\n"
            "        } else {\n"
            "            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(height) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));\n"
            "        }\n"
            "    }\n\n"
            "    @Override\n"
            "    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {\n"
            "        super.onLayout(changed, left, top, right, bottom);\n"
            "        if (valueTextView != null && valueTextView.getVisibility() == VISIBLE && textView != null) {\n"
            "            int tTop = Math.max(textView.getTop(), AndroidUtilities.dp(10));\n"
            "            int vTop = tTop + textView.getMeasuredHeight() + AndroidUtilities.dp(3);\n"
            "            int vLeft = valueTextView.getLeft();\n"
            "            valueTextView.layout(vLeft, vTop, vLeft + valueTextView.getMeasuredWidth(), vTop + valueTextView.getMeasuredHeight());\n"
            "        }\n"
            "    }"
        ),
        "TextCheckCell.java dynamic vertical spacing",
        eol_patch_fn,
        marker="// colgram-textcheck-dynamic-layout-v1",
    )
    _old_wrap_fn = (
        "    public void setColgramWrapTitle(boolean wrap) {\n"
        "        colgramWrapTitle = wrap;\n"
        "        if (wrap) {\n"
        "            textView.setSingleLine(false);\n"
        "            textView.setLines(0);\n"
        "            textView.setMaxLines(2);\n"
        "            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);\n"
        "            textView.setEllipsize(TextUtils.TruncateAt.END);\n"
        "            LayoutParams lp = (LayoutParams) textView.getLayoutParams();\n"
        "            if (lp != null) {\n"
        "                lp.height = LayoutParams.WRAP_CONTENT;\n"
        "                lp.topMargin = AndroidUtilities.dp(10);\n"
        "                textView.setLayoutParams(lp);\n"
        "            }\n"
        "            isMultiline = true;\n"
        "            requestLayout();\n"
        "        }\n"
        "    }"
    )
    _raw_wrap_fn = (
        "    public void setColgramWrapTitle(boolean wrap) {\n"
        "        colgramWrapTitle = wrap;\n"
        "        if (wrap) {\n"
        "            textView.setSingleLine(false);\n"
        "            textView.setLines(0);\n"
        "            textView.setMaxLines(2);\n"
        "            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);\n"
        "            textView.setEllipsize(TextUtils.TruncateAt.END);\n"
        "        }\n"
        "    }"
    )
    _new_wrap_fn = (
        "    public void setColgramWrapTitle(boolean wrap) {\n"
        "        colgramWrapTitle = wrap;\n"
        "        if (wrap) {\n"
        "            textView.setSingleLine(false);\n"
        "            textView.setLines(0);\n"
        "            textView.setMaxLines(2);\n"
        "            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);\n"
        "            textView.setEllipsize(TextUtils.TruncateAt.END);\n"
        "            LayoutParams lp = (LayoutParams) textView.getLayoutParams();\n"
        "            if (lp != null) {\n"
        "                lp.height = LayoutParams.WRAP_CONTENT;\n"
        "                lp.topMargin = AndroidUtilities.dp(10);\n"
        "                textView.setLayoutParams(lp);\n"
        "            }\n"
        "            isMultiline = true;\n"
        "            requestLayout();\n"
        "        } else {\n"
        "            textView.setSingleLine(true);\n"
        "            textView.setLines(1);\n"
        "            textView.setMaxLines(1);\n"
        "            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);\n"
        "            textView.setEllipsize(TextUtils.TruncateAt.END);\n"
        "            LayoutParams lp = (LayoutParams) textView.getLayoutParams();\n"
        "            if (lp != null) {\n"
        "                lp.height = LayoutParams.MATCH_PARENT;\n"
        "                lp.topMargin = 0;\n"
        "                textView.setLayoutParams(lp);\n"
        "            }\n"
        "            isMultiline = false;\n"
        "            requestLayout();\n"
        "        }\n"
        "    }"
    )
    _upgrade_or_patch(
        text_check_cell,
        (_old_wrap_fn,),
        _raw_wrap_fn,
        _new_wrap_fn,
        "TextCheckCell.java setColgramWrapTitle wrap and reset state",
        eol_patch_fn,
    )
    _eol_replace(
        text_check_cell,
        (
            "        needDivider = divider;\n"
            "        valueTextView.setVisibility(GONE);"
        ),
        (
            "        needDivider = divider;\n"
            "        valueTextView.setText(\"\");\n"
            "        valueTextView.setVisibility(GONE);"
        ),
        "TextCheckCell.java clear recycled valueTextView",
        eol_patch_fn,
    )
    _eol_replace(
        text_check_cell,
        (
            "        } else {\n"
            "            layoutParams.height = LayoutParams.MATCH_PARENT;\n"
            "            layoutParams.gravity = (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP;\n"
            "            layoutParams.topMargin = 0;\n"
            "            layoutParams.bottomMargin = 0;\n"
            "        }"
        ),
        (
            "        } else {\n"
            "            textView.setSingleLine(true);\n"
            "            textView.setLines(1);\n"
            "            textView.setMaxLines(1);\n"
            "            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);\n"
            "            textView.setEllipsize(TextUtils.TruncateAt.END);\n"
            "            layoutParams.height = LayoutParams.MATCH_PARENT;\n"
            "            layoutParams.gravity = (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP;\n"
            "            layoutParams.topMargin = 0;\n"
            "            layoutParams.bottomMargin = 0;\n"
            "        }"
        ),
        "TextCheckCell.java setTextAndCheck reset single-line and size",
        eol_patch_fn,
    )
    _tvc_core_raw = (
        "        isMultiline = multiline;\n"
        "        if (multiline) {\n"
        "            valueTextView.setLines(0);\n"
        "            valueTextView.setMaxLines(0);\n"
        "            valueTextView.setSingleLine(false);\n"
        "            valueTextView.setEllipsize(null);\n"
        "            valueTextView.setPadding(0, 0, 0, AndroidUtilities.dp(11));\n"
        "        } else {\n"
        "            valueTextView.setLines(1);\n"
        "            valueTextView.setMaxLines(1);\n"
        "            valueTextView.setSingleLine(true);\n"
        "            valueTextView.setEllipsize(TextUtils.TruncateAt.END);\n"
        "            valueTextView.setPadding(0, 0, 0, 0);\n"
        "        }\n"
        "        textView.setSingleLine(true);\n"
        "        textView.setLines(1);\n"
        "        textView.setMaxLines(1);\n"
        "        textView.setEllipsize(TextUtils.TruncateAt.END);"
    )
    _tvc_core_v1 = (
        "        isMultiline = multiline || colgramWrapTitle;\n"
        "        if (multiline) {\n"
        "            valueTextView.setLines(0);\n"
        "            valueTextView.setMaxLines(0);\n"
        "            valueTextView.setSingleLine(false);\n"
        "            valueTextView.setEllipsize(null);\n"
        "            valueTextView.setPadding(0, 0, 0, AndroidUtilities.dp(11));\n"
        "        } else {\n"
        "            valueTextView.setLines(1);\n"
        "            valueTextView.setMaxLines(1);\n"
        "            valueTextView.setSingleLine(true);\n"
        "            valueTextView.setEllipsize(TextUtils.TruncateAt.END);\n"
        "            valueTextView.setPadding(0, 0, 0, 0);\n"
        "        }\n"
        "        if (colgramWrapTitle) {\n"
        "            textView.setSingleLine(false);\n"
        "            textView.setLines(0);\n"
        "            textView.setMaxLines(2);\n"
        "        } else {\n"
        "            textView.setSingleLine(true);\n"
        "            textView.setLines(1);\n"
        "            textView.setMaxLines(1);\n"
        "        }\n"
        "        textView.setEllipsize(TextUtils.TruncateAt.END);"
    )
    _tvc_core_v2 = (
        "        isMultiline = multiline || colgramWrapTitle;\n"
        "        if (multiline) {\n"
        "            valueTextView.setLines(0);\n"
        "            valueTextView.setMaxLines(0);\n"
        "            valueTextView.setSingleLine(false);\n"
        "            valueTextView.setEllipsize(null);\n"
        "            valueTextView.setPadding(0, 0, 0, AndroidUtilities.dp(11));\n"
        "        } else {\n"
        "            valueTextView.setLines(1);\n"
        "            valueTextView.setMaxLines(1);\n"
        "            valueTextView.setSingleLine(true);\n"
        "            valueTextView.setEllipsize(TextUtils.TruncateAt.END);\n"
        "            valueTextView.setPadding(0, 0, 0, colgramWrapTitle ? AndroidUtilities.dp(8) : 0);\n"
        "        }\n"
        "        if (colgramWrapTitle) {\n"
        "            textView.setSingleLine(false);\n"
        "            textView.setLines(0);\n"
        "            textView.setMaxLines(2);\n"
        "            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);\n"
        "        } else {\n"
        "            textView.setSingleLine(true);\n"
        "            textView.setLines(1);\n"
        "            textView.setMaxLines(1);\n"
        "            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);\n"
        "        }\n"
        "        textView.setEllipsize(TextUtils.TruncateAt.END);"
    )
    _tvc_prefix_check = (
        "        checkBox.setVisibility(View.VISIBLE);\n"
        "        checkBox.setChecked(checked, false);\n"
        "        needDivider = divider;\n"
        "        valueTextView.setVisibility(VISIBLE);\n"
    )
    _tvc_prefix_val = (
        "        checkBox.setVisibility(View.GONE);\n"
        "        needDivider = divider;\n"
        "        valueTextView.setVisibility(VISIBLE);\n"
    )
    _upgrade_or_patch(
        text_check_cell,
        (_tvc_prefix_check + _tvc_core_v1,),
        _tvc_prefix_check + _tvc_core_raw,
        _tvc_prefix_check + _tvc_core_v2,
        "TextCheckCell.java setTextAndValueAndCheck wrap",
        eol_patch_fn,
    )
    _upgrade_or_patch(
        text_check_cell,
        (_tvc_prefix_val + _tvc_core_v1,),
        _tvc_prefix_val + _tvc_core_raw,
        _tvc_prefix_val + _tvc_core_v2,
        "TextCheckCell.java setTextAndValue wrap",
        eol_patch_fn,
    )
    _eol_replace(
        text_check_cell,
        "                if (imageView != null) {\n                    canvas.drawLine(LocaleController.isRTL ? 0 : padding, getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? padding : 0), getMeasuredHeight() - 1, dividerPaint);\n                }",
        "                if (imageView != null) {\n                    canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(padding), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(padding) : 0), getMeasuredHeight() - 1, dividerPaint);\n                }",
        "TextCheckCell.java onDraw dp(padding) divider",
        eol_patch_fn,
    )

    # 5. TextDetailSettingsCell.java — 2-line titles, dynamic height, ellipsize & icon/padding reset
    text_detail_cell = os.path.join(ui, "Cells", "TextDetailSettingsCell.java")
    _eol_replace(
        text_detail_cell,
        (
            "        textView.setLines(1);\n"
            "        textView.setMaxLines(1);\n"
            "        textView.setSingleLine(true);\n"
            "        textView.setEllipsize(TextUtils.TruncateAt.END);"
        ),
        (
            "        textView.setLines(0);\n"
            "        textView.setMaxLines(2);\n"
            "        textView.setSingleLine(false);\n"
            "        textView.setEllipsize(TextUtils.TruncateAt.END);"
        ),
        "TextDetailSettingsCell.java 2-line title",
        eol_patch_fn,
        marker="// colgram-textdetail-dynamic-v1",
    )
    _eol_replace(
        text_detail_cell,
        (
            "        valueTextView.setLines(1);\n"
            "        valueTextView.setMaxLines(1);\n"
            "        valueTextView.setSingleLine(true);\n"
            "        valueTextView.setPadding(0, 0, 0, 0);"
        ),
        (
            "        valueTextView.setLines(1);\n"
            "        valueTextView.setMaxLines(1);\n"
            "        valueTextView.setSingleLine(true);\n"
            "        valueTextView.setEllipsize(TextUtils.TruncateAt.END);\n"
            "        valueTextView.setPadding(0, 0, 0, 0);"
        ),
        "TextDetailSettingsCell.java single-line value ellipsize",
        eol_patch_fn,
    )
    _eol_replace(
        text_detail_cell,
        (
            "    @Override\n"
            "    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {\n"
            "        if (!multiline) {\n"
            "            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));\n"
            "        } else {\n"
            "            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));\n"
            "        }\n"
            "    }"
        ),
        (
            "    @Override\n"
            "    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {\n"
            "        // colgram-textdetail-dynamic-v1\n"
            "        int w = MeasureSpec.getSize(widthMeasureSpec);\n"
            "        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));\n"
            "        int minH = AndroidUtilities.dp(64) + (needDivider ? 1 : 0);\n"
            "        int botPad = multiline ? 0 : AndroidUtilities.dp(11);\n"
            "        int contentH = AndroidUtilities.dp(10) + textView.getMeasuredHeight() + AndroidUtilities.dp(3) + valueTextView.getMeasuredHeight() + botPad + (needDivider ? 1 : 0);\n"
            "        setMeasuredDimension(w, Math.max(minH, contentH));\n"
            "    }\n\n"
            "    @Override\n"
            "    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {\n"
            "        super.onLayout(changed, left, top, right, bottom);\n"
            "        if (textView != null && valueTextView != null && valueTextView.getVisibility() == VISIBLE) {\n"
            "            int tTop = Math.max(textView.getTop(), AndroidUtilities.dp(10));\n"
            "            int vTop = tTop + textView.getMeasuredHeight() + AndroidUtilities.dp(3);\n"
            "            int vLeft = valueTextView.getLeft();\n"
            "            valueTextView.layout(vLeft, vTop, vLeft + valueTextView.getMeasuredWidth(), vTop + valueTextView.getMeasuredHeight());\n"
            "        }\n"
            "    }"
        ),
        "TextDetailSettingsCell.java dynamic height",
        eol_patch_fn,
        marker="// colgram-textdetail-dynamic-v1",
    )
    _old_td_reset_v1 = (
        "    public void setTextAndValue(CharSequence text, CharSequence value, boolean divider) {\n"
        "        textView.setText(text);\n"
        "        valueTextView.setText(value);\n"
        "        textView.setPadding(0, 0, 0, 0);\n"
        "        valueTextView.setPadding(0, 0, 0, multiline ? AndroidUtilities.dp(12) : 0);\n"
        "        needDivider = divider;\n"
        "        imageView.setVisibility(GONE);\n"
        "        setWillNotDraw(!divider);\n"
        "    }"
    )
    _raw_td_reset = (
        "    public void setTextAndValue(CharSequence text, CharSequence value, boolean divider) {\n"
        "        textView.setText(text);\n"
        "        valueTextView.setText(value);\n"
        "        needDivider = divider;\n"
        "        imageView.setVisibility(GONE);\n"
        "        setWillNotDraw(!divider);\n"
        "    }"
    )
    _new_td_reset_v2 = (
        "    private void colgramResetImageViewStyle() {\n"
        "        imageView.setBackground(null);\n"
        "        imageView.setPadding(0, 0, 0, 0);\n"
        "        imageView.setScaleType(ImageView.ScaleType.CENTER);\n"
        "        imageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));\n"
        "        imageView.setLayoutParams(LayoutHelper.createFrame(52, 52, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 8, 6, 8, 0));\n"
        "    }\n\n"
        "    public void setTextAndValue(CharSequence text, CharSequence value, boolean divider) {\n"
        "        textView.setText(text);\n"
        "        valueTextView.setText(value);\n"
        "        textView.setPadding(0, 0, 0, 0);\n"
        "        valueTextView.setPadding(0, 0, 0, multiline ? AndroidUtilities.dp(12) : 0);\n"
        "        needDivider = divider;\n"
        "        colgramResetImageViewStyle();\n"
        "        imageView.setVisibility(GONE);\n"
        "        setWillNotDraw(!divider);\n"
        "    }"
    )
    _upgrade_or_patch(
        text_detail_cell,
        (_old_td_reset_v1,),
        _raw_td_reset,
        _new_td_reset_v2,
        "TextDetailSettingsCell.java reset padding and imageView on reuse",
        eol_patch_fn,
        marker="colgramResetImageViewStyle()",
    )
    _eol_replace(
        text_detail_cell,
        (
            "    public void setTextAndValueAndIcon(String text, CharSequence value, int resId, boolean divider) {\n"
            "        textView.setText(text);\n"
            "        valueTextView.setText(value);\n"
            "        imageView.setImageResource(resId);"
        ),
        (
            "    public void setTextAndValueAndIcon(String text, CharSequence value, int resId, boolean divider) {\n"
            "        textView.setText(text);\n"
            "        valueTextView.setText(value);\n"
            "        colgramResetImageViewStyle();\n"
            "        imageView.setImageResource(resId);"
        ),
        "TextDetailSettingsCell.java setTextAndValueAndIcon reset imageView",
        eol_patch_fn,
    )
    _eol_replace(
        text_detail_cell,
        (
            "    public void setTextWithEmojiAnd21Value(String text, CharSequence value, boolean divider) {\n"
            "        textView.setText(Emoji.replaceEmoji(text, textView.getPaint().getFontMetricsInt(), false));\n"
            "        valueTextView.setText(value);\n"
            "        needDivider = divider;\n"
            "        setWillNotDraw(!divider);\n"
            "    }"
        ),
        (
            "    public void setTextWithEmojiAnd21Value(String text, CharSequence value, boolean divider) {\n"
            "        textView.setText(Emoji.replaceEmoji(text, textView.getPaint().getFontMetricsInt(), false));\n"
            "        valueTextView.setText(value);\n"
            "        textView.setPadding(0, 0, 0, 0);\n"
            "        valueTextView.setPadding(0, 0, 0, multiline ? AndroidUtilities.dp(12) : 0);\n"
            "        colgramResetImageViewStyle();\n"
            "        imageView.setVisibility(GONE);\n"
            "        needDivider = divider;\n"
            "        setWillNotDraw(!divider);\n"
            "    }"
        ),
        "TextDetailSettingsCell.java setTextWithEmojiAnd21Value reset padding and icon",
        eol_patch_fn,
    )

    # 6. SettingsActivity.java — SettingCell dynamic 2-line height & add Colgram Premium+ row 110
    settings_act = os.path.join(ui, "SettingsActivity.java")
    _eol_replace(
        settings_act,
        (
            "            titleView = new TextView(context);\n"
            "            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);\n"
            "            textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));\n"
            "\n"
            "            subtitleView = new TextView(context);\n"
            "            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);\n"
            "            textLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));"
        ),
        (
            "            titleView = new TextView(context);\n"
            "            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);\n"
            "            titleView.setMaxLines(2);\n"
            "            titleView.setEllipsize(TextUtils.TruncateAt.END);\n"
            "            textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));\n"
            "\n"
            "            subtitleView = new TextView(context);\n"
            "            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);\n"
            "            subtitleView.setMaxLines(2);\n"
            "            subtitleView.setEllipsize(TextUtils.TruncateAt.END);\n"
            "            textLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));"
        ),
        "SettingsActivity.java SettingCell maxLines",
        eol_patch_fn,
        marker="// colgram-settingcell-dynamic-v1",
    )
    _eol_replace(
        settings_act,
        (
            "        @Override\n"
            "        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {\n"
            "            super.onMeasure(\n"
            "                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),\n"
            "                MeasureSpec.makeMeasureSpec(dp(mini ? 44 : twoLines ? 60 : 50), MeasureSpec.EXACTLY)\n"
            "            );\n"
            "        }"
        ),
        (
            "        @Override\n"
            "        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {\n"
            "            // colgram-settingcell-dynamic-v1\n"
            "            int w = MeasureSpec.getSize(widthMeasureSpec);\n"
            "            textLayout.setPadding(0, dp(mini ? 6 : 8), 0, dp(mini ? 6 : 8));\n"
            "            super.onMeasure(\n"
            "                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),\n"
            "                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)\n"
            "            );\n"
            "            int minH = dp(mini ? 44 : twoLines ? 60 : 50);\n"
            "            if (getMeasuredHeight() < minH) {\n"
            "                super.onMeasure(\n"
            "                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),\n"
            "                    MeasureSpec.makeMeasureSpec(minH, MeasureSpec.EXACTLY)\n"
            "                );\n"
            "            }\n"
            "        }"
        ),
        "SettingsActivity.java SettingCell dynamic height",
        eol_patch_fn,
        marker="// colgram-settingcell-dynamic-v1",
    )
    _eol_replace(
        settings_act,
        "        items.add(SettingCell.Factory.of(101,",
        (
            '        items.add(SettingCell.Factory.of(110, 0xFF7C3AED, 0xFF2563EB, R.drawable.msg_reactions, "Colgram Premium+", "Теги в Избранном, без рекламы, STT и перевод"));\n'
            "        items.add(SettingCell.Factory.of(101,"
        ),
        "SettingsActivity.java add Colgram Premium+ row",
        eol_patch_fn,
        marker="SettingCell.Factory.of(110,",
    )
    _eol_replace(
        settings_act,
        (
            "            case 109:\n"
            "                presentSettingFragment(new ColgramDesignStudioActivity());\n"
            "                break;"
        ),
        (
            "            case 109:\n"
            "                presentSettingFragment(new ColgramDesignStudioActivity());\n"
            "                break;\n"
            "            case 110:\n"
            "                presentSettingFragment(new ColgramPremiumActivity());\n"
            "                break;"
        ),
        "SettingsActivity.java handle Colgram Premium+ click",
        eol_patch_fn,
        marker="case 110:",
    )

    # 7. AlertsCreator.java — upgrade any legacy 2nd-arg purgeCell string to SpannableStringBuilder + setMultiline(true)
    alerts_path = os.path.join(ui, "Components", "AlertsCreator.java")
    if os.path.isfile(alerts_path):
        with open(alerts_path, "r", encoding="utf-8", errors="ignore", newline="") as _af:
            _alerts_norm = _af.read().replace("\r\n", "\n")
        _legacy_pat = re.compile(
            r'                purgeCell\.allowMultiline\(\);\n'
            r'                purgeCell\.setMinimumHeight\(dp\(48\)\);\n'
            r'                purgeCell\.setText\("Удалить полностью у себя", "(?:Стереть локальную копию из сейфа анти-удаления)?", false, false\);'
        )
        _m = _legacy_pat.search(_alerts_norm)
        if _m and "// colgram-purge-cell-multiline-v1" not in _alerts_norm:
            _new_alerts_block = (
                "                // colgram-purge-cell-multiline-v1\n"
                "                purgeCell.setMultiline(true);\n"
                "                purgeCell.setMinimumHeight(dp(56));\n"
                '                String colgramPurgeTitleText = "Удалить полностью у себя";\n'
                '                android.text.SpannableStringBuilder colgramPurgeSb = new android.text.SpannableStringBuilder(colgramPurgeTitleText + "\\nСтереть локальную копию из сейфа анти-удаления");\n'
                "                int colgramSubStart = colgramPurgeTitleText.length() + 1;\n"
                "                colgramPurgeSb.setSpan(new android.text.style.RelativeSizeSpan(0.81f), colgramSubStart, colgramPurgeSb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);\n"
                "                colgramPurgeSb.setSpan(new android.text.style.ForegroundColorSpan(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider)), colgramSubStart, colgramPurgeSb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);\n"
                '                purgeCell.setText(colgramPurgeSb, "", false, false);'
            )
            eol_patch_fn(alerts_path, _m.group(0), _new_alerts_block, "AlertsCreator.java multiline delete prompt safety")

    # 8. LaunchActivity.java — register Contract D NoticeDispatcher for colgram-core
    launch_path = os.path.join(ui, "LaunchActivity.java")
    _eol_replace(
        launch_path,
        "        setContentView(frameLayout);\n",
        (
            "        setContentView(frameLayout);\n"
            "        org.colgram.core.ColgramUiBridge.setNoticeDispatcher(ColgramUiKit::showHudBanner);\n"
        ),
        "LaunchActivity.java register ColgramUiBridge NoticeDispatcher",
        eol_patch_fn,
        marker="ColgramUiBridge.setNoticeDispatcher(",
    )


def _patch_native_saved_tags_symbiosis(repo_path: str, eol_patch_fn) -> None:
    """Unlock all 14+ native Telegram Saved Messages tag gates & wire ColgramSavedTagsStore symbiosis."""
    msg_dir = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger")
    ui = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui")
    comp = os.path.join(ui, "Components")

    chat_act = os.path.join(ui, "ChatActivity.java")
    search_tags_list = os.path.join(comp, "SearchTagsList.java")
    react_layout = os.path.join(comp, "ReactionsContainerLayout.java")
    shared_media = os.path.join(comp, "SharedMediaLayout.java")
    msg_obj = os.path.join(msg_dir, "MessageObject.java")
    mc_path = os.path.join(msg_dir, "MessagesController.java")
    ms_path = os.path.join(msg_dir, "MessagesStorage.java")
    smh_path = os.path.join(msg_dir, "SendMessagesHelper.java")
    mdc_path = os.path.join(msg_dir, "MediaDataController.java")

    # A. ChatActivity.java native tag gates (1..8)
    _eol_replace(
        chat_act,
        (
            "\tprivate boolean isSupportedTags() {\n"
            "\t\treturn getUserConfig().getClientUserId() == getDialogId() && !getMessagesController().getSavedMessagesController().unsupported && getUserConfig().isPremium();\n"
            "\t}"
        ),
        (
            "\tprivate boolean isSupportedTags() {\n"
            "\t\treturn getUserConfig().getClientUserId() == getDialogId() && !getMessagesController().getSavedMessagesController().unsupported && (getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled());\n"
            "\t}"
        ),
        "ChatActivity.java unlock isSupportedTags()",
        eol_patch_fn,
        marker="!getMessagesController().getSavedMessagesController().unsupported && (getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())",
    )
    _eol_replace(
        chat_act,
        (
            "            if (getUserConfig().isPremium()) {\n"
            "                savedMessagesTagHint = new HintView2(context, HintView2.DIRECTION_BOTTOM)"
        ),
        (
            "            if (getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n"
            "                savedMessagesTagHint = new HintView2(context, HintView2.DIRECTION_BOTTOM)"
        ),
        "ChatActivity.java unlock savedMessagesTagHint",
        eol_patch_fn,
        marker="if (getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n                savedMessagesTagHint",
    )
    _eol_replace(
        chat_act,
        "        actionMode.setItemVisibility(tag_message, getUserConfig().isPremium() ? View.VISIBLE : View.GONE);",
        "        actionMode.setItemVisibility(tag_message, (getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) ? View.VISIBLE : View.GONE);",
        "ChatActivity.java unlock actionMode tag_message item",
        eol_patch_fn,
        marker="actionMode.setItemVisibility(tag_message, (getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())",
    )
    _eol_replace(
        chat_act,
        "        if (getDialogId() != getUserConfig().getClientUserId() || !getUserConfig().isPremium()) return;",
        "        if (getDialogId() != getUserConfig().getClientUserId() || (!getUserConfig().isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())) return;",
        "ChatActivity.java unlock showTagSelector()",
        eol_patch_fn,
        marker="if (getDialogId() != getUserConfig().getClientUserId() || (!getUserConfig().isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())) return;",
    )
    _eol_replace(
        chat_act,
        "                if (getDialogId() == getUserConfig().getClientUserId() && !getUserConfig().isPremium()) {",
        "                if (getDialogId() == getUserConfig().getClientUserId() && !getUserConfig().isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {",
        "ChatActivity.java unlock tagSelector.onReactionClicked",
        eol_patch_fn,
        marker="if (getDialogId() == getUserConfig().getClientUserId() && !getUserConfig().isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {",
    )
    _eol_replace(
        chat_act,
        "                    tagItem.setVisibility(getUserConfig().isPremium() && (",
        "                    tagItem.setVisibility((getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) && (",
        "ChatActivity.java unlock ActionMode tagItem visibility",
        eol_patch_fn,
        marker="tagItem.setVisibility((getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) && (",
    )
    _eol_replace(
        chat_act,
        (
            "                if (tags) {\n"
            "                    reactionsLayout.setHint(getUserConfig().isPremium() ? LocaleController.getString(R.string.SavedTagReactionsHint2) : AndroidUtilities.replaceSingleTag("
        ),
        (
            "                if (tags) {\n"
            "                    reactionsLayout.setHint((getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) ? LocaleController.getString(R.string.SavedTagReactionsHint2) : AndroidUtilities.replaceSingleTag("
        ),
        "ChatActivity.java unlock message menu TYPE_TAGS hint",
        eol_patch_fn,
        marker="reactionsLayout.setHint((getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) ? LocaleController.getString(R.string.SavedTagReactionsHint2)",
    )
    _eol_replace(
        chat_act,
        "                if (isReactionsAvailable && (!tags || !getMessagesController().premiumFeaturesBlocked())) {",
        "                if (isReactionsAvailable && (!tags || !getMessagesController().premiumFeaturesBlocked() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())) {",
        "ChatActivity.java unlock message menu TYPE_TAGS interactive container",
        eol_patch_fn,
        marker="!tags || !getMessagesController().premiumFeaturesBlocked() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()",
    )
    _eol_replace(
        chat_act,
        "        if (SharedConfig.multipleReactionsPromoShowed || cell == null || visibleReaction == null || getUserConfig().isPremium()) {",
        "        if (SharedConfig.multipleReactionsPromoShowed || cell == null || visibleReaction == null || getUserConfig().isPremium() || (getDialogId() == getUserConfig().getClientUserId() && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())) {",
        "ChatActivity.java skip showMultipleReactionsPromo in Saved Messages",
        eol_patch_fn,
        marker="getDialogId() == getUserConfig().getClientUserId() && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()",
    )
    _eol_replace(
        chat_act,
        "        if (getDialogId() == getUserConfig().getClientUserId() && !getUserConfig().isPremium() && primaryMessage.messageOwner != null && (primaryMessage.messageOwner.reactions == null || (primaryMessage.messageOwner.reactions.reactions_as_tags || primaryMessage.messageOwner.reactions.results.isEmpty()))) {",
        "        if (getDialogId() == getUserConfig().getClientUserId() && !getUserConfig().isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled() && primaryMessage.messageOwner != null && (primaryMessage.messageOwner.reactions == null || (primaryMessage.messageOwner.reactions.reactions_as_tags || primaryMessage.messageOwner.reactions.results.isEmpty()))) {",
        "ChatActivity.java unlock selectReaction in Saved Messages",
        eol_patch_fn,
        marker="!getUserConfig().isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled() && primaryMessage.messageOwner != null",
    )
    _eol_replace(
        chat_act,
        "        if (getUserConfig().getClientUserId() == getDialogId() && messageObject.areTags() && !getUserConfig().isPremium()) {",
        "        if (getUserConfig().getClientUserId() == getDialogId() && messageObject.areTags() && !getUserConfig().isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {",
        "ChatActivity.java unlock didPressReaction tag pill tap/long-press",
        eol_patch_fn,
        marker="messageObject.areTags() && !getUserConfig().isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()",
    )
    _eol_replace(
        chat_act,
        (
            "                if (getUserConfig().isPremium()) {\n"
            "                    ActionBarMenuSubItem editTag = new ActionBarMenuSubItem(getParentActivity(), false, false);"
        ),
        (
            "                if (getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n"
            "                    ActionBarMenuSubItem editTag = new ActionBarMenuSubItem(getParentActivity(), false, false);"
        ),
        "ChatActivity.java unlock editTag rename popup option",
        eol_patch_fn,
        marker="if (getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n                    ActionBarMenuSubItem editTag",
    )

    # B. SearchTagsList.java (9..11)
    _eol_replace(
        search_tags_list,
        (
            "            if (!UserConfig.getInstance(currentAccount).isPremium()) {\n"
            "                new PremiumFeatureBottomSheet(fragment, PremiumPreviewFragment.PREMIUM_FEATURE_SAVED_TAGS, true).show();\n"
            "                return;\n"
            "            }"
        ),
        (
            "            if (!UserConfig.getInstance(currentAccount).isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n"
            "                new PremiumFeatureBottomSheet(fragment, PremiumPreviewFragment.PREMIUM_FEATURE_SAVED_TAGS, true).show();\n"
            "                return;\n"
            "            }"
        ),
        "SearchTagsList.java unlock tag click filter",
        eol_patch_fn,
        marker="!UserConfig.getInstance(currentAccount).isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n                new PremiumFeatureBottomSheet(fragment, PremiumPreviewFragment.PREMIUM_FEATURE_SAVED_TAGS, true).show();\n                return;",
    )
    _eol_replace(
        search_tags_list,
        (
            "            if (position < 0 || position >= items.size() || !UserConfig.getInstance(currentAccount).isPremium())\n"
            "                return false;\n"
            "            if (!UserConfig.getInstance(currentAccount).isPremium()) {"
        ),
        (
            "            if (position < 0 || position >= items.size())\n"
            "                return false;\n"
            "            if (!UserConfig.getInstance(currentAccount).isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {"
        ),
        "SearchTagsList.java unlock tag long-press rename",
        eol_patch_fn,
        marker="if (!UserConfig.getInstance(currentAccount).isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n                new PremiumFeatureBottomSheet(fragment, PremiumPreviewFragment.PREMIUM_FEATURE_SAVED_TAGS, true).show();\n                return true;",
    )
    _eol_replace(
        search_tags_list,
        (
            "        if (shownPremiumLayout = !UserConfig.getInstance(currentAccount).isPremium()) {\n"
            "            createPremiumLayout();\n"
            "            if (!notify) {\n"
            "                premiumLayout.setVisibility(View.VISIBLE);\n"
            "                premiumLayout.setAlpha(0f);\n"
            "                premiumLayout.animate().alpha(1f).start();\n"
            "            }\n"
            "        } else if (premiumLayout != null) {\n"
            "            if (notify) {\n"
            "                premiumLayout.animate().alpha(0f).withEndAction(() -> {\n"
            "                    premiumLayout.setVisibility(View.GONE);\n"
            "                }).start();\n"
            "            } else {\n"
            "                premiumLayout.setAlpha(1f);\n"
            "                premiumLayout.setVisibility(View.VISIBLE);\n"
            "            }\n"
            "        }"
        ),
        (
            "        if (shownPremiumLayout = (!UserConfig.getInstance(currentAccount).isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())) {\n"
            "            createPremiumLayout();\n"
            "            if (!notify) {\n"
            "                premiumLayout.setVisibility(View.VISIBLE);\n"
            "                premiumLayout.setAlpha(0f);\n"
            "                premiumLayout.animate().alpha(1f).start();\n"
            "            }\n"
            "        } else if (premiumLayout != null) {\n"
            "            if (notify) {\n"
            "                premiumLayout.animate().alpha(0f).withEndAction(() -> {\n"
            "                    premiumLayout.setVisibility(View.GONE);\n"
            "                }).start();\n"
            "            } else {\n"
            "                premiumLayout.setAlpha(0f);\n"
            "                premiumLayout.setVisibility(View.GONE);\n"
            "            }\n"
            "        }"
        ),
        "SearchTagsList.java unlock shownPremiumLayout and hide lock banner",
        eol_patch_fn,
        marker="shownPremiumLayout = (!UserConfig.getInstance(currentAccount).isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())",
    )

    # C. ReactionsContainerLayout.java (12)
    _eol_replace(
        react_layout,
        (
            "        if (type == TYPE_TAGS) {\n"
            "            allReactionsAvailable = UserConfig.getInstance(currentAccount).isPremium();\n"
            "            fillRecentReactionsList(visibleReactions);"
        ),
        (
            "        if (type == TYPE_TAGS) {\n"
            "            allReactionsAvailable = UserConfig.getInstance(currentAccount).isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled();\n"
            "            fillRecentReactionsList(visibleReactions);"
        ),
        "ReactionsContainerLayout.java TYPE_TAGS allReactionsAvailable",
        eol_patch_fn,
        marker="allReactionsAvailable = UserConfig.getInstance(currentAccount).isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()",
    )
    _eol_replace(
        react_layout,
        "        if (type == TYPE_TAGS && !UserConfig.getInstance(currentAccount).isPremium()) {\n            showExpandableReactions = false;",
        "        if (type == TYPE_TAGS && !UserConfig.getInstance(currentAccount).isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n            showExpandableReactions = false;",
        "ReactionsContainerLayout.java TYPE_TAGS showExpandableReactions",
        eol_patch_fn,
        marker="if (type == TYPE_TAGS && !UserConfig.getInstance(currentAccount).isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())",
    )
    _eol_replace(
        react_layout,
        "        if (type != TYPE_TAGS || UserConfig.getInstance(currentAccount).isPremium()) {",
        "        if (type != TYPE_TAGS || UserConfig.getInstance(currentAccount).isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {",
        "ReactionsContainerLayout.java TYPE_TAGS populate recent and enabled emojis",
        eol_patch_fn,
        marker="if (type != TYPE_TAGS || UserConfig.getInstance(currentAccount).isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())",
    )
    _eol_replace(
        react_layout,
        "        if (UserConfig.getInstance(currentAccount).isPremium()) {\n            return;\n        }\n        try {\n            for (int i = 0; i < reactions.size(); i++) {\n                if (reactions.get(i).premium) {",
        "        if (UserConfig.getInstance(currentAccount).isPremium() || (type == TYPE_TAGS && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())) {\n            return;\n        }\n        try {\n            for (int i = 0; i < reactions.size(); i++) {\n                if (reactions.get(i).premium) {",
        "ReactionsContainerLayout.java TYPE_TAGS keep all available reactions unlocked",
        eol_patch_fn,
        marker="UserConfig.getInstance(currentAccount).isPremium() || (type == TYPE_TAGS && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())",
    )
    _eol_replace(
        react_layout,
        (
            "            final boolean userIsPremium = UserConfig.getInstance(currentAccount).isPremium();\n"
            "            isLocked = type == TYPE_TAGS && !userIsPremium || type == TYPE_MESSAGE_EFFECTS && react.premium && !userIsPremium;"
        ),
        (
            "            final boolean userIsPremium = UserConfig.getInstance(currentAccount).isPremium();\n"
            "            final boolean colgramTagsUnlocked = type == TYPE_TAGS && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled();\n"
            "            isLocked = type == TYPE_TAGS && !userIsPremium && !colgramTagsUnlocked || type == TYPE_MESSAGE_EFFECTS && react.premium && !userIsPremium;"
        ),
        "ReactionsContainerLayout.java TYPE_TAGS unlock holder padlock",
        eol_patch_fn,
        marker="colgramTagsUnlocked",
    )

    # D. SharedMediaLayout.java (13 + SavedMessagesSearchAdapter local short-circuit)
    _eol_replace(
        shared_media,
        "                        searchItemIcon.setIcon(hasFilters() && profileActivity.getUserConfig().isPremium() ? R.drawable.navbar_search_tag : R.drawable.outline_header_search, notify);",
        "                        searchItemIcon.setIcon(hasFilters() && (profileActivity.getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) ? R.drawable.navbar_search_tag : R.drawable.outline_header_search, notify);",
        "SharedMediaLayout.java unlock navbar_search_tag icon",
        eol_patch_fn,
        marker="hasFilters() && (profileActivity.getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())",
    )
    _eol_replace(
        shared_media,
        (
            "                    updateMessages(true);\n"
            "                    AndroidUtilities.runOnUIThread(request, 540);\n"
            "                }, false);"
        ),
        (
            "                    if (!UserConfig.getInstance(currentAccount).isPremium() && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n"
            "                        count = cachedMessages.size();\n"
            "                        endReached = true;\n"
            "                        loading = false;\n"
            "                        reqId = -1;\n"
            "                        updateMessages(true);\n"
            "                        return;\n"
            "                    }\n"
            "                    updateMessages(true);\n"
            "                    AndroidUtilities.runOnUIThread(request, 540);\n"
            "                }, false);"
        ),
        "SharedMediaLayout.java SavedMessagesSearchAdapter local-only tag search",
        eol_patch_fn,
        marker="if (!UserConfig.getInstance(currentAccount).isPremium() && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n                        count = cachedMessages.size();",
    )

    # E. MessageObject.java (14: allow >= 3 tags per message in Saved Messages)
    _eol_replace(
        msg_obj,
        "        int maxReactionsCount = MessagesController.getInstance(currentAccount).getMaxUserReactionsCount();",
        (
            "        int maxReactionsCount = MessagesController.getInstance(currentAccount).getMaxUserReactionsCount();\n"
            "        if (getDialogId() == UserConfig.getInstance(currentAccount).getClientUserId() && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n"
            "            maxReactionsCount = Math.max(maxReactionsCount, Math.max(3, MessagesController.getInstance(currentAccount).reactionsUserMaxPremium));\n"
            "        }"
        ),
        "MessageObject.java allow >=3 tags per Saved Message",
        eol_patch_fn,
        marker="Math.max(3, MessagesController.getInstance(currentAccount).reactionsUserMaxPremium)",
    )

    # F. MessagesStorage.java — hydration (getMessages, putMessagesInternal, searchSavedByTag), prune, and onReactionsUpdate null-past fix
    _eol_replace(
        ms_path,
        (
            "                        res.messages.add(message);\n\n"
            "                        addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, animatedEmojiToLoad);"
        ),
        (
            "                        if (dialogId == currentUserId) {\n"
            "                            org.telegram.ui.ColgramNativeTagsBridge.mergeIntoMessage(currentAccount, dialogId, message);\n"
            "                        }\n"
            "                        res.messages.add(message);\n\n"
            "                        addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, animatedEmojiToLoad);"
        ),
        "MessagesStorage.java getMessages hydrate local tags",
        eol_patch_fn,
        marker="ColgramNativeTagsBridge.mergeIntoMessage(currentAccount, dialogId, message);\n                        }\n                        res.messages.add(message);",
    )
    _eol_replace(
        ms_path,
        (
            "                    for (int a = 0; a < count; a++) {\n"
            "                        TLRPC.Message message = messages.messages.get(a);\n"
            "                        if (lastMessageId == null && message != null || lastMessageId != null && lastMessageId < message.id) {"
        ),
        (
            "                    for (int a = 0; a < count; a++) {\n"
            "                        TLRPC.Message message = messages.messages.get(a);\n"
            "                        if (self && message != null) {\n"
            "                            org.telegram.ui.ColgramNativeTagsBridge.mergeIntoMessage(currentAccount, dialogId, message);\n"
            "                        }\n"
            "                        if (lastMessageId == null && message != null || lastMessageId != null && lastMessageId < message.id) {"
        ),
        "MessagesStorage.java putMessagesInternal hydrate local tags before bindMessageTags",
        eol_patch_fn,
        marker="if (self && message != null) {\n                            org.telegram.ui.ColgramNativeTagsBridge.mergeIntoMessage",
    )
    _eol_replace(
        ms_path,
        (
            "            if (getUserConfig().getClientUserId() == dialogId) {\n"
            '                database.executeFast(String.format(Locale.US, "DELETE FROM tag_message_id WHERE mid IN(%s)", TextUtils.join(",", messages))).stepThis().dispose();\n'
            "            }"
        ),
        (
            "            if (getUserConfig().getClientUserId() == dialogId) {\n"
            '                database.executeFast(String.format(Locale.US, "DELETE FROM tag_message_id WHERE mid IN(%s)", TextUtils.join(",", messages))).stepThis().dispose();\n'
            "                org.colgram.core.ColgramSavedTagsStore.pruneDeletedMessages(ApplicationLoader.applicationContext, currentAccount, messages);\n"
            "            }"
        ),
        "MessagesStorage.java pruneDeletedMessages in Saved Messages",
        eol_patch_fn,
        marker="ColgramSavedTagsStore.pruneDeletedMessages(",
    )
    _eol_replace(
        ms_path,
        (
            "                ArrayList<MessageObject> messageObjects = new ArrayList<>();\n"
            "                while (cursor.next()) {\n"
            "                    long group_id = cursor.longValue(2);\n"
            "                    if (group_id != 0 && includeGroups) {\n"
            '                        cursor_groups = database.queryFinalized("SELECT data, replydata, group_id FROM messages_v2 WHERE uid = ? AND group_id = ? ORDER BY mid DESC", selfId, group_id);\n'
            "                        ArrayList<MessageObject> groupmessages = new ArrayList<>();\n"
            "                        while (cursor_groups.next()) {\n"
            "                            NativeByteBuffer data = cursor_groups.byteBufferValue(0);\n"
            "                            TLRPC.Message groupmessage = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);\n"
            "                            groupmessage.readAttachPath(data, selfId);\n"
            "                            data.reuse();\n"
            "                            addUsersAndChatsFromMessage(groupmessage, usersToLoad, chatsToLoad, animatedEmojiToLoad);\n"
            "                            MessageObject messageObject = new MessageObject(currentAccount, groupmessage, null, null, null, null, null, true, true, 0, false, false, true);\n"
            "                            if (groupmessage.reactions != null) {\n"
            "                                messageObject.isPrimaryGroupMessage = true;\n"
            "                            }\n"
            "                            groupmessages.add(messageObject);\n"
            "                        }\n"
            "                        cursor_groups.dispose();\n"
            "                        messageObjects.addAll(groupmessages);\n"
            "                    } else {\n"
            "                        NativeByteBuffer data = cursor.byteBufferValue(0);\n"
            "                        if (data == null) continue;\n"
            "                        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);\n"
            "                        if (message != null) {\n"
            "                            message.readAttachPath(data, selfId);\n"
            "                            data.reuse();\n"
            "                            addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, animatedEmojiToLoad);\n"
            "                            if (message.reply_to != null && (message.reply_to.reply_to_msg_id != 0 || message.reply_to.reply_to_random_id != 0)) {\n"
            "                                if (!cursor.isNull(1)) {\n"
            "                                    data = cursor.byteBufferValue(1);\n"
            "                                    if (data != null) {\n"
            "                                        message.replyMessage = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);\n"
            "                                        message.replyMessage.readAttachPath(data, selfId);\n"
            "                                        data.reuse();\n"
            "                                        if (message.replyMessage != null) {\n"
            "                                            addUsersAndChatsFromMessage(message.replyMessage, usersToLoad, chatsToLoad, animatedEmojiToLoad);\n"
            "                                        }\n"
            "                                    }\n"
            "                                }\n"
            "                            }\n"
            "                            MessageObject messageObject = new MessageObject(currentAccount, message, null, null, null, null, null, true, true, 0, false, false, true);\n"
            "                            messageObjects.add(messageObject);\n"
            "                        }\n"
            "                    }\n"
            "                }\n"
            "                cursor.dispose();"
        ),
        (
            "                ArrayList<MessageObject> messageObjects = new ArrayList<>();\n"
            "                ArrayList<Integer> colgramFoundMids = new ArrayList<>();\n"
            "                while (cursor.next()) {\n"
            "                    long group_id = cursor.longValue(2);\n"
            "                    if (group_id != 0 && includeGroups) {\n"
            '                        cursor_groups = database.queryFinalized("SELECT data, replydata, group_id FROM messages_v2 WHERE uid = ? AND group_id = ? ORDER BY mid DESC", selfId, group_id);\n'
            "                        ArrayList<MessageObject> groupmessages = new ArrayList<>();\n"
            "                        while (cursor_groups.next()) {\n"
            "                            NativeByteBuffer data = cursor_groups.byteBufferValue(0);\n"
            "                            TLRPC.Message groupmessage = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);\n"
            "                            groupmessage.readAttachPath(data, selfId);\n"
            "                            data.reuse();\n"
            "                            org.telegram.ui.ColgramNativeTagsBridge.mergeIntoMessage(currentAccount, selfId, groupmessage);\n"
            "                            colgramFoundMids.add(groupmessage.id);\n"
            "                            addUsersAndChatsFromMessage(groupmessage, usersToLoad, chatsToLoad, animatedEmojiToLoad);\n"
            "                            MessageObject messageObject = new MessageObject(currentAccount, groupmessage, null, null, null, null, null, true, true, 0, false, false, true);\n"
            "                            if (groupmessage.reactions != null) {\n"
            "                                messageObject.isPrimaryGroupMessage = true;\n"
            "                            }\n"
            "                            groupmessages.add(messageObject);\n"
            "                        }\n"
            "                        cursor_groups.dispose();\n"
            "                        messageObjects.addAll(groupmessages);\n"
            "                    } else {\n"
            "                        NativeByteBuffer data = cursor.byteBufferValue(0);\n"
            "                        if (data == null) continue;\n"
            "                        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);\n"
            "                        if (message != null) {\n"
            "                            message.readAttachPath(data, selfId);\n"
            "                            data.reuse();\n"
            "                            org.telegram.ui.ColgramNativeTagsBridge.mergeIntoMessage(currentAccount, selfId, message);\n"
            "                            colgramFoundMids.add(message.id);\n"
            "                            addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, animatedEmojiToLoad);\n"
            "                            if (message.reply_to != null && (message.reply_to.reply_to_msg_id != 0 || message.reply_to.reply_to_random_id != 0)) {\n"
            "                                if (!cursor.isNull(1)) {\n"
            "                                    data = cursor.byteBufferValue(1);\n"
            "                                    if (data != null) {\n"
            "                                        message.replyMessage = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);\n"
            "                                        message.replyMessage.readAttachPath(data, selfId);\n"
            "                                        data.reuse();\n"
            "                                        if (message.replyMessage != null) {\n"
            "                                            addUsersAndChatsFromMessage(message.replyMessage, usersToLoad, chatsToLoad, animatedEmojiToLoad);\n"
            "                                        }\n"
            "                                    }\n"
            "                                }\n"
            "                            }\n"
            "                            MessageObject messageObject = new MessageObject(currentAccount, message, null, null, null, null, null, true, true, 0, false, false, true);\n"
            "                            messageObjects.add(messageObject);\n"
            "                        }\n"
            "                    }\n"
            "                }\n"
            "                cursor.dispose();\n"
            "                if (org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled() && offset == 0) {\n"
            "                    ArrayList<Integer> colgramExtraIds = org.telegram.ui.ColgramNativeTagsBridge.mergeLocalTagIdsIntoSearch(currentAccount, topic_id, tag, colgramFoundMids);\n"
            "                    if (!colgramExtraIds.isEmpty()) {\n"
            "                        SQLiteCursor colgramCursor = null;\n"
            "                        try {\n"
            '                            String colgramQueryNorm = !TextUtils.isEmpty(query) ? LocaleController.getInstance().getTranslitString(query) : "";\n'
            '                            colgramCursor = database.queryFinalized("SELECT data, replydata, group_id FROM messages_v2 WHERE uid = " + selfId + " AND mid IN (" + TextUtils.join(",", colgramExtraIds) + ") ORDER BY mid DESC");\n'
            "                            while (colgramCursor.next()) {\n"
            "                                NativeByteBuffer data = colgramCursor.byteBufferValue(0);\n"
            "                                if (data == null) continue;\n"
            "                                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);\n"
            "                                if (message != null) {\n"
            "                                    message.readAttachPath(data, selfId);\n"
            "                                    data.reuse();\n"
            "                                    if (!TextUtils.isEmpty(colgramQueryNorm)) {\n"
            '                                        String mt = LocaleController.getInstance().getTranslitString(message.message == null ? "" : message.message);\n'
            "                                        if (mt == null || !mt.contains(colgramQueryNorm)) {\n"
            "                                            continue;\n"
            "                                        }\n"
            "                                    }\n"
            "                                    org.telegram.ui.ColgramNativeTagsBridge.mergeIntoMessage(currentAccount, selfId, message);\n"
            "                                    addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, animatedEmojiToLoad);\n"
            "                                    MessageObject messageObject = new MessageObject(currentAccount, message, null, null, null, null, null, true, true, 0, false, false, true);\n"
            "                                    messageObjects.add(messageObject);\n"
            "                                }\n"
            "                            }\n"
            "                            java.util.Collections.sort(messageObjects, (a, b) -> Integer.compare(b.getId(), a.getId()));\n"
            "                        } finally {\n"
            "                            if (colgramCursor != null) {\n"
            "                                colgramCursor.dispose();\n"
            "                            }\n"
            "                        }\n"
            "                    }\n"
            "                }"
        ),
        "MessagesStorage.java searchSavedByTag hydrate & union local tags",
        eol_patch_fn,
        marker="mergeLocalTagIdsIntoSearch(",
    )
    _eol_replace(
        ms_path,
        "                if (dialogId == selfId && pastReactions != null) {\n                    onReactionsUpdate(topicId, pastReactions, reactions);\n                }",
        "                if (dialogId == selfId && (pastReactions != null || reactions != null)) {\n                    onReactionsUpdate(topicId, pastReactions, reactions);\n                }",
        "MessagesStorage.java updateMessageReactions trigger onReactionsUpdate on first tag",
        eol_patch_fn,
        marker="if (dialogId == selfId && (pastReactions != null || reactions != null))",
    )
    _eol_replace(
        ms_path,
        (
            "    private void onReactionsUpdate(long topic_id, TLRPC.TL_messageReactions a, TLRPC.TL_messageReactions b) {\n"
            "        if (a == null || a.results == null || a != null && a.results != null && a.results.isEmpty() && b != null && b.results.isEmpty()) {\n"
            "            return;\n"
            "        }\n"
            "        AndroidUtilities.runOnUIThread(() -> {\n"
            "            LongSparseArray<ReactionsLayoutInBubble.VisibleReaction> oldTags = new LongSparseArray<>();\n"
            "            LongSparseArray<ReactionsLayoutInBubble.VisibleReaction> newTags = new LongSparseArray<>();\n"
            "            if (a != null && a.results != null && a.reactions_as_tags) {\n"
            "                for (int i = 0; i < a.results.size(); ++i) {\n"
            "                    ReactionsLayoutInBubble.VisibleReaction reaction = ReactionsLayoutInBubble.VisibleReaction.fromTL(a.results.get(i).reaction);\n"
            "                    oldTags.put(reaction.hash, reaction);\n"
            "                }\n"
            "            }\n"
            "            if (b != null && b.results != null && b.reactions_as_tags) {\n"
            "                for (int i = 0; i < b.results.size(); ++i) {\n"
            "                    ReactionsLayoutInBubble.VisibleReaction reaction = ReactionsLayoutInBubble.VisibleReaction.fromTL(b.results.get(i).reaction);\n"
            "                    newTags.put(reaction.hash, reaction);\n"
            "                }\n"
            "            }"
        ),
        (
            "    private void onReactionsUpdate(long topic_id, TLRPC.TL_messageReactions a, TLRPC.TL_messageReactions b) {\n"
            "        if ((a == null || a.results == null || a.results.isEmpty()) && (b == null || b.results == null || b.results.isEmpty())) {\n"
            "            return;\n"
            "        }\n"
            "        AndroidUtilities.runOnUIThread(() -> {\n"
            "            LongSparseArray<ReactionsLayoutInBubble.VisibleReaction> oldTags = new LongSparseArray<>();\n"
            "            LongSparseArray<ReactionsLayoutInBubble.VisibleReaction> newTags = new LongSparseArray<>();\n"
            "            if (a != null && a.results != null && a.reactions_as_tags) {\n"
            "                for (int i = 0; i < a.results.size(); ++i) {\n"
            "                    ReactionsLayoutInBubble.VisibleReaction reaction = ReactionsLayoutInBubble.VisibleReaction.fromTL(a.results.get(i).reaction);\n"
            "                    if (reaction != null) oldTags.put(reaction.hash, reaction);\n"
            "                }\n"
            "            }\n"
            "            if (b != null && b.results != null && b.reactions_as_tags) {\n"
            "                for (int i = 0; i < b.results.size(); ++i) {\n"
            "                    ReactionsLayoutInBubble.VisibleReaction reaction = ReactionsLayoutInBubble.VisibleReaction.fromTL(b.results.get(i).reaction);\n"
            "                    if (reaction != null) newTags.put(reaction.hash, reaction);\n"
            "                }\n"
            "            }"
        ),
        "MessagesStorage.java onReactionsUpdate handle null pastReactions",
        eol_patch_fn,
        marker="if ((a == null || a.results == null || a.results.isEmpty()) && (b == null || b.results == null || b.results.isEmpty()))",
    )

    # G. MessagesController.java — processLoadedMessages hydrate, getSavedTagName/Count/Rename/Fetch symbiosis
    _eol_replace(
        mc_path,
        (
            "            final TLRPC.Message message = messagesRes.messages.get(a);\n"
            "            message.dialog_id = dialogId;"
        ),
        (
            "            final TLRPC.Message message = messagesRes.messages.get(a);\n"
            "            message.dialog_id = dialogId;\n"
            "            if (dialogId == getUserConfig().getClientUserId()) {\n"
            "                org.telegram.ui.ColgramNativeTagsBridge.mergeIntoMessage(currentAccount, dialogId, message);\n"
            "            }"
        ),
        "MessagesController.java processLoadedMessages hydrate local tags",
        eol_patch_fn,
        marker="if (dialogId == getUserConfig().getClientUserId()) {\n                org.telegram.ui.ColgramNativeTagsBridge.mergeIntoMessage",
    )
    _eol_replace(
        mc_path,
        (
            "    public String getSavedTagName(ReactionsLayoutInBubble.VisibleReaction reaction) {\n"
            "        if (reactionTags == null) {\n"
            "            return null;\n"
            "        }"
        ),
        (
            "    public String getSavedTagName(ReactionsLayoutInBubble.VisibleReaction reaction) {\n"
            "        if (reaction != null && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n"
            "            String colgramLabel = org.colgram.core.ColgramSavedTagsStore.getTagLabel(currentAccount, org.telegram.ui.ColgramNativeTagsBridge.visibleReactionToKey(reaction));\n"
            "            if (!TextUtils.isEmpty(colgramLabel)) {\n"
            "                return colgramLabel;\n"
            "            }\n"
            "        }\n"
            "        if (reactionTags == null) {\n"
            "            return null;\n"
            "        }"
        ),
        "MessagesController.java getSavedTagName(VisibleReaction) local label fallback",
        eol_patch_fn,
        marker="ColgramNativeTagsBridge.visibleReactionToKey(reaction)",
    )
    _eol_replace(
        mc_path,
        (
            "    public int getSavedTagCount(long topic_id, ReactionsLayoutInBubble.VisibleReaction reaction) {\n"
            "        if (reactionTags == null) {\n"
            "            return 0;\n"
            "        }\n"
            "        TLRPC.TL_messages_savedReactionsTags tags = reactionTags.get(topic_id);\n"
            "        if (tags == null) return 0;\n"
            "        for (int i = 0; i < tags.tags.size(); ++i) {\n"
            "            if (reaction.isSame(tags.tags.get(i).reaction)) {\n"
            "                return tags.tags.get(i).count;\n"
            "            }\n"
            "        }\n"
            "        return 0;\n"
            "    }"
        ),
        (
            "    public int getSavedTagCount(long topic_id, ReactionsLayoutInBubble.VisibleReaction reaction) {\n"
            "        int best = 0;\n"
            "        if (reactionTags != null && reaction != null) {\n"
            "            TLRPC.TL_messages_savedReactionsTags tags = reactionTags.get(topic_id);\n"
            "            if (tags != null && tags.tags != null) {\n"
            "                for (int i = 0; i < tags.tags.size(); ++i) {\n"
            "                    if (reaction.isSame(tags.tags.get(i).reaction)) {\n"
            "                        best = Math.max(best, tags.tags.get(i).count);\n"
            "                    }\n"
            "                }\n"
            "            }\n"
            "        }\n"
            "        if (reaction != null && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n"
            "            String key = org.telegram.ui.ColgramNativeTagsBridge.visibleReactionToKey(reaction);\n"
            "            if (!TextUtils.isEmpty(key)) {\n"
            "                java.util.List<org.colgram.core.ColgramSavedTagsStore.TagCount> local = org.colgram.core.ColgramSavedTagsStore.getTagCounts(currentAccount, topic_id);\n"
            "                for (int i = 0; i < local.size(); i++) {\n"
            "                    if (key.equals(local.get(i).emoji)) {\n"
            "                        best = Math.max(best, local.get(i).count);\n"
            "                        break;\n"
            "                    }\n"
            "                }\n"
            "            }\n"
            "        }\n"
            "        return best;\n"
            "    }"
        ),
        "MessagesController.java getSavedTagCount local count fallback",
        eol_patch_fn,
        marker="ColgramSavedTagsStore.getTagCounts(currentAccount, topic_id)",
    )
    _eol_replace(
        mc_path,
        (
            "    public String getSavedTagName(TLRPC.Reaction reaction) {\n"
            "        if (reactionTags == null) {\n"
            "            return null;\n"
            "        }"
        ),
        (
            "    public String getSavedTagName(TLRPC.Reaction reaction) {\n"
            "        if (reaction != null && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n"
            "            String colgramLabel = org.colgram.core.ColgramSavedTagsStore.getTagLabel(currentAccount, org.telegram.ui.ColgramNativeTagsBridge.reactionToKey(reaction));\n"
            "            if (!TextUtils.isEmpty(colgramLabel)) {\n"
            "                return colgramLabel;\n"
            "            }\n"
            "        }\n"
            "        if (reactionTags == null) {\n"
            "            return null;\n"
            "        }"
        ),
        "MessagesController.java getSavedTagName(TLRPC.Reaction) local label fallback",
        eol_patch_fn,
        marker="ColgramNativeTagsBridge.reactionToKey(reaction)",
    )
    _eol_replace(
        mc_path,
        (
            "    public void renameSavedReactionTag(ReactionsLayoutInBubble.VisibleReaction reaction, String name) {\n"
            "        if (reactionTags == null) {\n"
            "            return;\n"
            "        }"
        ),
        (
            "    public void renameSavedReactionTag(ReactionsLayoutInBubble.VisibleReaction reaction, String name) {\n"
            "        if (reaction != null && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n"
            "            org.colgram.core.ColgramSavedTagsStore.setTagLabel(\n"
            "                    ApplicationLoader.applicationContext,\n"
            "                    currentAccount,\n"
            "                    org.telegram.ui.ColgramNativeTagsBridge.visibleReactionToKey(reaction),\n"
            "                    name);\n"
            "        }\n"
            "        if (reactionTags == null) {\n"
            "            if (!org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n"
            "                return;\n"
            "            }\n"
            "            reactionTags = new LongSparseArray<>();\n"
            "        }"
        ),
        "MessagesController.java renameSavedReactionTag local store persistence",
        eol_patch_fn,
        marker="ColgramSavedTagsStore.setTagLabel(",
    )
    _eol_replace(
        mc_path,
        (
            "            if (!TextUtils.isEmpty(name)) {\n"
            "                tag.title = name;\n"
            "            }\n"
            "            tag.count = 1;\n"
            "            tags.tags.add(tag);\n"
            "            changed = true;\n"
            "        }\n"
            "        if (changed) {\n"
            "            TLRPC.TL_messages_updateSavedReactionTag req = new TLRPC.TL_messages_updateSavedReactionTag();\n"
            "            req.reaction = reaction.toTLReaction();\n"
            "            if (!TextUtils.isEmpty(name)) {\n"
            "                req.flags |= 1;\n"
            "                req.title = name;\n"
            "            }\n"
            "            getConnectionsManager().sendRequest(req, null);"
        ),
        (
            "            if (!TextUtils.isEmpty(name)) {\n"
            "                tag.flags |= 1;\n"
            "                tag.title = name;\n"
            "            }\n"
            "            tag.count = 1;\n"
            "            tags.tags.add(tag);\n"
            "            changed = true;\n"
            "        }\n"
            "        if (changed) {\n"
            "            if (getUserConfig().isPremium()) {\n"
            "                TLRPC.TL_messages_updateSavedReactionTag req = new TLRPC.TL_messages_updateSavedReactionTag();\n"
            "                req.reaction = reaction.toTLReaction();\n"
            "                if (!TextUtils.isEmpty(name)) {\n"
            "                    req.flags |= 1;\n"
            "                    req.title = name;\n"
            "                }\n"
            "                getConnectionsManager().sendRequest(req, null);\n"
            "            }"
        ),
        "MessagesController.java renameSavedReactionTag gate RPC on isPremium()",
        eol_patch_fn,
        marker="if (getUserConfig().isPremium()) {\n                TLRPC.TL_messages_updateSavedReactionTag req =",
    )
    _eol_replace(
        mc_path,
        (
            "        if (loadingReactionTags != null && loadingReactionTags.contains(topic_id) && !force) {\n"
            "            if (reactionTags == null) return null;\n"
            "            return reactionTags.get(topic_id);\n"
            "        }"
        ),
        (
            "        if (loadingReactionTags != null && loadingReactionTags.contains(topic_id) && !force) {\n"
            "            TLRPC.TL_messages_savedReactionsTags cached = reactionTags != null ? reactionTags.get(topic_id) : null;\n"
            "            return org.telegram.ui.ColgramNativeTagsBridge.mergeIntoSavedReactionTags(currentAccount, topic_id, cached);\n"
            "        }"
        ),
        "MessagesController.java getSavedReactionTags merge cached return",
        eol_patch_fn,
        marker="ColgramNativeTagsBridge.mergeIntoSavedReactionTags(currentAccount, topic_id, cached)",
    )
    _eol_replace(
        mc_path,
        (
            "                if (finalResult instanceof TLRPC.TL_messages_savedReactionsTags) {\n"
            "                    reactionTags.put(topic_id, (TLRPC.TL_messages_savedReactionsTags) finalResult);\n"
            "                    getNotificationCenter().postNotificationName(NotificationCenter.savedReactionTagsUpdate, topic_id);\n"
            "                }\n\n"
            "                TLRPC.TL_messages_getSavedReactionTags req = new TLRPC.TL_messages_getSavedReactionTags();\n"
            "                if (finalResult instanceof TLRPC.TL_messages_savedReactionsTags) {\n"
            "                    req.hash = finalResult.hash;\n"
            "                }\n"
            "                if (topic_id != 0) {\n"
            "                    req.flags |= 1;\n"
            "                    req.peer = getInputPeer(topic_id);\n"
            "                }\n"
            "                getConnectionsManager().sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {\n"
            "                    if (res instanceof TLRPC.TL_messages_savedReactionsTags) {\n"
            "                        TLRPC.TL_messages_savedReactionsTags tags = (TLRPC.TL_messages_savedReactionsTags) res;\n"
            "                        reactionTags.put(topic_id, tags);\n"
            "                        getNotificationCenter().postNotificationName(NotificationCenter.savedReactionTagsUpdate, topic_id);\n"
            "                        saveSavedReactionsTags(topic_id, tags);\n"
            "                    } else if (res instanceof TLRPC.TL_messages_savedReactionsTagsNotModified && finalResult == null && req.hash == 0) {\n"
            "                        TLRPC.TL_messages_savedReactionsTags emptyTags = new TLRPC.TL_messages_savedReactionsTags();\n"
            "                        reactionTags.put(topic_id, emptyTags);\n"
            "                        getNotificationCenter().postNotificationName(NotificationCenter.savedReactionTagsUpdate, topic_id);\n"
            "                        saveSavedReactionsTags(topic_id, emptyTags);\n"
            "                    }\n"
            "                }));"
        ),
        (
            "                TLRPC.TL_messages_savedReactionsTags mergedDb = org.telegram.ui.ColgramNativeTagsBridge.mergeIntoSavedReactionTags(\n"
            "                        currentAccount,\n"
            "                        topic_id,\n"
            "                        finalResult instanceof TLRPC.TL_messages_savedReactionsTags ? (TLRPC.TL_messages_savedReactionsTags) finalResult : null);\n"
            "                if (mergedDb != null) {\n"
            "                    reactionTags.put(topic_id, mergedDb);\n"
            "                    getNotificationCenter().postNotificationName(NotificationCenter.savedReactionTagsUpdate, topic_id);\n"
            "                }\n\n"
            "                TLRPC.TL_messages_getSavedReactionTags req = new TLRPC.TL_messages_getSavedReactionTags();\n"
            "                if (finalResult instanceof TLRPC.TL_messages_savedReactionsTags) {\n"
            "                    req.hash = finalResult.hash;\n"
            "                }\n"
            "                if (topic_id != 0) {\n"
            "                    req.flags |= 1;\n"
            "                    req.peer = getInputPeer(topic_id);\n"
            "                }\n"
            "                getConnectionsManager().sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {\n"
            "                    if (res instanceof TLRPC.TL_messages_savedReactionsTags) {\n"
            "                        TLRPC.TL_messages_savedReactionsTags tags = org.telegram.ui.ColgramNativeTagsBridge.mergeIntoSavedReactionTags(\n"
            "                                currentAccount, topic_id, (TLRPC.TL_messages_savedReactionsTags) res);\n"
            "                        reactionTags.put(topic_id, tags);\n"
            "                        getNotificationCenter().postNotificationName(NotificationCenter.savedReactionTagsUpdate, topic_id);\n"
            "                        saveSavedReactionsTags(topic_id, tags);\n"
            "                    } else if (res instanceof TLRPC.TL_messages_savedReactionsTagsNotModified) {\n"
            "                        TLRPC.TL_messages_savedReactionsTags baseTags = finalResult instanceof TLRPC.TL_messages_savedReactionsTags\n"
            "                                ? (TLRPC.TL_messages_savedReactionsTags) finalResult\n"
            "                                : reactionTags.get(topic_id);\n"
            "                        if (baseTags == null && req.hash == 0) {\n"
            "                            baseTags = new TLRPC.TL_messages_savedReactionsTags();\n"
            "                        }\n"
            "                        TLRPC.TL_messages_savedReactionsTags tags = org.telegram.ui.ColgramNativeTagsBridge.mergeIntoSavedReactionTags(\n"
            "                                currentAccount, topic_id, baseTags);\n"
            "                        if (tags != null) {\n"
            "                            reactionTags.put(topic_id, tags);\n"
            "                            getNotificationCenter().postNotificationName(NotificationCenter.savedReactionTagsUpdate, topic_id);\n"
            "                            saveSavedReactionsTags(topic_id, tags);\n"
            "                        }\n"
            "                    } else if (err != null) {\n"
            "                        TLRPC.TL_messages_savedReactionsTags tags = org.telegram.ui.ColgramNativeTagsBridge.mergeIntoSavedReactionTags(\n"
            "                                currentAccount, topic_id, reactionTags.get(topic_id));\n"
            "                        if (tags != null) {\n"
            "                            reactionTags.put(topic_id, tags);\n"
            "                            getNotificationCenter().postNotificationName(NotificationCenter.savedReactionTagsUpdate, topic_id);\n"
            "                        }\n"
            "                    }\n"
            "                }));"
        ),
        "MessagesController.java getSavedReactionTags DB & server merge symbiosis",
        eol_patch_fn,
        marker="TLRPC.TL_messages_savedReactionsTags mergedDb = org.telegram.ui.ColgramNativeTagsBridge.mergeIntoSavedReactionTags(",
    )

    # H. SendMessagesHelper.java — Saved Messages local persistence (!isPremium short-circuit + PREMIUM_ACCOUNT_REQUIRED fallback)
    _eol_replace(
        smh_path,
        "        TLRPC.User colgramBotSelf = getUserConfig().getCurrentUser();",
        (
            "        if (org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled() && messageObject.getDialogId() == getUserConfig().getClientUserId()) {\n"
            "            if (!getUserConfig().isPremium()) {\n"
            "                org.telegram.ui.ColgramNativeTagsBridge.handleLocalSavedReaction(currentAccount, messageObject, visibleReactions, callback);\n"
            "                return;\n"
            "            }\n"
            "        }\n"
            "        TLRPC.User colgramBotSelf = getUserConfig().getCurrentUser();"
        ),
        "SendMessagesHelper.java sendReaction Saved Messages local persistence",
        eol_patch_fn,
        marker="ColgramNativeTagsBridge.handleLocalSavedReaction(currentAccount, messageObject, visibleReactions, callback);",
    )
    _eol_replace(
        smh_path,
        (
            "            if (response != null) {\n"
            "                getMessagesController().processUpdates((TLRPC.Updates) response, false);\n"
            "                if (callback != null) {\n"
            "                    AndroidUtilities.runOnUIThread(callback);\n"
            "                }\n"
            '            } else if (error != null && error.text != null && error.text.contains("BOT_METHOD_INVALID")) {'
        ),
        (
            "            if (response != null) {\n"
            "                if (org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled() && messageObject.getDialogId() == getUserConfig().getClientUserId()) {\n"
            "                    org.telegram.ui.ColgramNativeTagsBridge.syncServerReactionTags(currentAccount, messageObject, visibleReactions);\n"
            "                }\n"
            "                getMessagesController().processUpdates((TLRPC.Updates) response, false);\n"
            "                if (callback != null) {\n"
            "                    AndroidUtilities.runOnUIThread(callback);\n"
            "                }\n"
            '            } else if (error != null && error.text != null && error.text.contains("PREMIUM_ACCOUNT_REQUIRED") && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled() && messageObject.getDialogId() == getUserConfig().getClientUserId()) {\n'
            "                org.telegram.ui.ColgramNativeTagsBridge.handleLocalSavedReaction(currentAccount, messageObject, visibleReactions, callback);\n"
            '            } else if (error != null && error.text != null && error.text.contains("BOT_METHOD_INVALID")) {'
        ),
        "SendMessagesHelper.java sync server tags and handle PREMIUM_ACCOUNT_REQUIRED fallback",
        eol_patch_fn,
        marker='error.text.contains("PREMIUM_ACCOUNT_REQUIRED")',
    )

    # I. MediaDataController.java — local Saved Messages tag search short-circuit + search count & empty fix
    _eol_replace(
        mdc_path,
        (
            "    public int getSearchCount() {\n"
            "        if (searchServerResultMessages.isEmpty()) {\n"
            "            return Math.max(Math.max(messagesSearchCount[0] + messagesSearchCount[1], messagesLocalSearchCount), searchServerResultMessages.size());\n"
            "        }\n"
            "        return Math.max(messagesSearchCount[0] + messagesSearchCount[1], searchServerResultMessages.size());\n"
            "    }"
        ),
        (
            "    public int getSearchCount() {\n"
            "        if (searchServerResultMessages.isEmpty()) {\n"
            "            return Math.max(Math.max(messagesSearchCount[0] + messagesSearchCount[1], Math.max(messagesLocalSearchCount, searchResultMessages.size())), searchServerResultMessages.size());\n"
            "        }\n"
            "        return Math.max(messagesSearchCount[0] + messagesSearchCount[1], Math.max(searchResultMessages.size(), searchServerResultMessages.size()));\n"
            "    }"
        ),
        "MediaDataController.java getSearchCount include local tag searchResultMessages",
        eol_patch_fn,
        marker="Math.max(searchResultMessages.size(), searchServerResultMessages.size())",
    )
    _eol_replace(
        mdc_path,
        (
            "            getMessagesStorage().searchSavedByTag(reaction.toTLReaction(), lastReplyMessageId, query, 300, searchLocalResultMessages == null ? 0 : searchLocalResultMessages.size(), (messages, users, chats, emojis) -> {\n"
            "                if (currentReqId == lastReqId) {\n"
            "                    loadedPredirectedSearchLocal = messages.size() == predictedCount;\n"
            "                    loadingSearchLocal = false;\n"
            "                    getMessagesController().putUsers(users, true);\n"
            "                    getMessagesController().putChats(chats, true);\n"
            "                    AnimatedEmojiDrawable.getDocumentFetcher(currentAccount).processDocuments(emojis);\n"
            "                    searchLocalResultMessages = messages;\n"
            "                    updateSearchResults();\n"
            "                    getNotificationCenter().postNotificationName(NotificationCenter.chatSearchResultsAvailable, guid, 0, getMask(), dialogId, lastReturnedNum, getSearchCount(), true);\n"
            "                }\n"
            "            }, true);\n"
            "        }"
        ),
        (
            "            final boolean colgramLocalTagOnly = !getUserConfig().isPremium() && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled();\n"
            "            getMessagesStorage().searchSavedByTag(reaction.toTLReaction(), lastReplyMessageId, query, 300, searchLocalResultMessages == null ? 0 : searchLocalResultMessages.size(), (messages, users, chats, emojis) -> {\n"
            "                if (currentReqId == lastReqId) {\n"
            "                    loadedPredirectedSearchLocal = colgramLocalTagOnly || messages.size() == predictedCount;\n"
            "                    loadingSearchLocal = false;\n"
            "                    getMessagesController().putUsers(users, true);\n"
            "                    getMessagesController().putChats(chats, true);\n"
            "                    AnimatedEmojiDrawable.getDocumentFetcher(currentAccount).processDocuments(emojis);\n"
            "                    searchLocalResultMessages = messages;\n"
            "                    if (colgramLocalTagOnly) {\n"
            "                        messagesLocalSearchCount = messages.size();\n"
            "                        messagesSearchEndReached[0] = true;\n"
            "                        messagesSearchEndReached[1] = true;\n"
            "                        loadingMoreSearchMessages = false;\n"
            "                    }\n"
            "                    updateSearchResults();\n"
            "                    int colgramFirstId = colgramLocalTagOnly && !searchResultMessages.isEmpty() ? searchResultMessages.get(0).getId() : 0;\n"
            "                    getNotificationCenter().postNotificationName(NotificationCenter.chatSearchResultsAvailable, guid, colgramFirstId, getMask(), dialogId, lastReturnedNum, getSearchCount(), true);\n"
            "                }\n"
            "            }, true);\n"
            "            if (colgramLocalTagOnly) {\n"
            "                return;\n"
            "            }\n"
            "        }\n"
            "        if (isSaved && reaction != null && !getUserConfig().isPremium() && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n"
            "            messagesSearchEndReached[0] = true;\n"
            "            messagesSearchEndReached[1] = true;\n"
            "            loadingMoreSearchMessages = false;\n"
            "            int colgramCurId = !searchResultMessages.isEmpty() ? searchResultMessages.get(Math.min(lastReturnedNum, searchResultMessages.size() - 1)).getId() : 0;\n"
            "            getNotificationCenter().postNotificationName(NotificationCenter.chatSearchResultsAvailable, guid, colgramCurId, getMask(), dialogId, lastReturnedNum, getSearchCount(), jumpToMessage);\n"
            "            return;\n"
            "        }"
        ),
        "MediaDataController.java Saved Messages local tag search short-circuit",
        eol_patch_fn,
        marker="final boolean colgramLocalTagOnly = !getUserConfig().isPremium() && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled();",
    )
    _eol_replace(
        mdc_path,
        "                            if (searchServerResultMessages.isEmpty()) {",
        "                            if (searchResultMessages.isEmpty()) {",
        "MediaDataController.java avoid server empty wiping local tag search results",
        eol_patch_fn,
        marker="if (searchResultMessages.isEmpty()) {\n                                getNotificationCenter().postNotificationName(NotificationCenter.chatSearchResultsAvailable, guid, 0, getMask(), (long) 0, 0, 0, jumpToMessage);",
    )


def _patch_premium_plus_features(repo_path: str, eol_patch_fn) -> None:
    msg_dir = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger")
    ui = os.path.join(repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui")

    # 9. MessagesController.java — ad-blocker short-circuit + noforwards bypass
    mc_path = os.path.join(msg_dir, "MessagesController.java")
    _eol_replace(
        mc_path,
        "    public boolean isChatNoForwards(TLRPC.Chat chat) {\n        if (chat == null) {\n            return false;\n        }",
        (
            "    public boolean isChatNoForwards(TLRPC.Chat chat) {\n"
            "        // colgram-premium-plus-mc-v1\n"
            "        if (org.colgram.core.ColgramPremiumConfig.isBypassNoForwardsEnabled()) {\n"
            "            return false;\n"
            "        }\n"
            "        if (chat == null) {\n"
            "            return false;\n"
            "        }"
        ),
        "MessagesController.isChatNoForwards bypass",
        eol_patch_fn,
        marker="// colgram-premium-plus-mc-v1",
    )
    _eol_replace(
        mc_path,
        "    public boolean isUserNoForwards(TLRPC.UserFull userFull) {\n        if (userFull == null) {\n            return false;\n        }",
        (
            "    public boolean isUserNoForwards(TLRPC.UserFull userFull) {\n"
            "        if (org.colgram.core.ColgramPremiumConfig.isBypassNoForwardsEnabled()) {\n"
            "            return false;\n"
            "        }\n"
            "        if (userFull == null) {\n"
            "            return false;\n"
            "        }"
        ),
        "MessagesController.isUserNoForwards bypass",
        eol_patch_fn,
    )
    _eol_replace(
        mc_path,
        (
            "    public SponsoredMessagesInfo getSponsoredMessages(long dialogId) {\n"
            "        SponsoredMessagesInfo info = sponsoredMessages.get(dialogId);"
        ),
        (
            "    public SponsoredMessagesInfo getSponsoredMessages(long dialogId) {\n"
            "        if (org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled()) {\n"
            "            return null;\n"
            "        }\n"
            "        SponsoredMessagesInfo info = sponsoredMessages.get(dialogId);"
        ),
        "MessagesController.getSponsoredMessages ad-block",
        eol_patch_fn,
    )

    # 10. DialogsSearchAdapter.java — skip TL_contacts_getSponsoredPeers
    search_adapter = os.path.join(ui, "Adapters", "DialogsSearchAdapter.java")
    _eol_replace(
        search_adapter,
        "if (query == null || query.length() < 4 || UserConfig.getInstance(currentAccount).isPremium() && MessagesController.getInstance(currentAccount).isSponsoredDisabled()) {",
        "if (query == null || query.length() < 4 || org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled() || UserConfig.getInstance(currentAccount).isPremium() && MessagesController.getInstance(currentAccount).isSponsoredDisabled()) {",
        "DialogsSearchAdapter.java skip sponsored peers RPC",
        eol_patch_fn,
        marker="ColgramPremiumConfig.isBlockSponsoredAdsEnabled()",
    )

    # 11. ChatActivity.java — ad-block + noforwards bypass + 100% Native Saved Messages Tags Symbiosis
    chat_act = os.path.join(ui, "ChatActivity.java")

    def _strip_eol_variants(path: str, variants, label: str) -> None:
        if not os.path.isfile(path):
            return
        with open(path, "r", encoding="utf-8", errors="ignore", newline="") as _sf:
            _raw = _sf.read()
        _file_eol = "\r\n" if "\r\n" in _raw else "\n"
        _snorm = _raw.replace("\r\n", "\n")
        _changed = False
        for _v in variants:
            _vn = _v.replace("\r\n", "\n") if _v else ""
            if _vn and _vn in _snorm:
                _snorm = _snorm.replace(_vn, "")
                _changed = True
        if _changed:
            with open(path, "w", encoding="utf-8", newline="") as _sf_out:
                _sf_out.write(_snorm.replace("\n", _file_eol))

    # Strip any legacy duplicate Saved Tags UI (ColgramSavedTagsBarView / OPTION_COLGRAM_SAVED_TAG / custom bubble pills)
    _legacy_tag_fields_v2 = (
        "    // colgram-premium-plus-chat-v1\n"
        "    private ColgramSavedTagsBarView colgramSavedTagsBar;\n"
        "    private String colgramActiveSavedTagEmoji;\n"
        "    private boolean colgramSavedTagFiltered;\n"
        "    private org.colgram.core.ColgramSavedTagsStore.ChangeListener colgramSavedTagsListener;\n"
    )
    _legacy_tag_fields_v1 = (
        "    // colgram-premium-plus-chat-v1\n"
        "    private ColgramSavedTagsBarView colgramSavedTagsBar;\n"
        "    private String colgramActiveSavedTagEmoji;\n"
        "    private boolean colgramSavedTagFiltered;\n"
    )
    _legacy_listener_cleanup = (
        "        if (colgramSavedTagsListener != null) {\n"
        "            org.colgram.core.ColgramSavedTagsStore.removeListener(colgramSavedTagsListener);\n"
        "            colgramSavedTagsListener = null;\n"
        "        }\n"
    )
    _legacy_opt_const = "    public final static int OPTION_COLGRAM_SAVED_TAG = 9992;\n"
    _legacy_mount_v2 = (
        "        if (dialog_id == getUserConfig().getClientUserId() && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n"
        "            colgramSavedTagsBar = new ColgramSavedTagsBarView(context, currentAccount);\n"
        "            colgramSavedTagsBar.setCallback(new ColgramSavedTagsBarView.Callback() {\n"
        "                @Override\n"
        "                public void onFilterChanged(String activeEmojiOrNull, java.util.List<Integer> matchingMsgIds) {\n"
        "                    colgramApplySavedTagFilter(activeEmojiOrNull, matchingMsgIds);\n"
        "                }\n"
        "                @Override\n"
        "                public void onJumpToMessage(int msgId) {\n"
        "                    scrollToMessageId(msgId, 0, true, 0, true, 0);\n"
        "                }\n"
        "                @Override\n"
        "                public void onAddTagClicked() {\n"
        "                    ColgramUiKit.showNotice(ChatActivity.this, R.drawable.msg_reactions,\n"
        '                            "Удерживайте сообщение -> «Тег (эмодзи)» для разметки", false);\n'
        "                }\n"
        "            });\n"
        "            topPanelLayout.addView(colgramSavedTagsBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));\n"
        "            topPanelLayout.setPriority(colgramSavedTagsBar, 8);\n"
        "            topPanelLayout.setViewVisible(colgramSavedTagsBar, true, false);\n"
        "            colgramSavedTagsListener = acc -> {\n"
        "                if (acc == currentAccount) {\n"
        "                    AndroidUtilities.runOnUIThread(() -> {\n"
        "                        if (isFinishing()) return;\n"
        "                        if (colgramSavedTagsBar != null) colgramSavedTagsBar.rebuild();\n"
        "                        if (chatAdapter != null) chatAdapter.notifyDataSetChanged();\n"
        "                        if (chatListView != null) chatListView.requestLayout();\n"
        "                    });\n"
        "                }\n"
        "            };\n"
        "            org.colgram.core.ColgramSavedTagsStore.addListener(colgramSavedTagsListener);\n"
        "        }\n"
    )
    _legacy_mount_v1 = (
        "        if (dialog_id == getUserConfig().getClientUserId() && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) {\n"
        "            colgramSavedTagsBar = new ColgramSavedTagsBarView(context, currentAccount);\n"
        "            colgramSavedTagsBar.setCallback(new ColgramSavedTagsBarView.Callback() {\n"
        "                @Override\n"
        "                public void onFilterChanged(String activeEmojiOrNull, java.util.List<Integer> matchingMsgIds) {\n"
        "                    colgramApplySavedTagFilter(activeEmojiOrNull, matchingMsgIds);\n"
        "                }\n"
        "                @Override\n"
        "                public void onJumpToMessage(int msgId) {\n"
        "                    scrollToMessageId(msgId, 0, true, 0, true, 0);\n"
        "                }\n"
        "                @Override\n"
        "                public void onAddTagClicked() {\n"
        "                    ColgramUiKit.showNotice(ChatActivity.this, R.drawable.msg_reactions,\n"
        '                            "Удерживайте сообщение -> «Тег (эмодзи)» для разметки", false);\n'
        "                }\n"
        "            });\n"
        "            topPanelLayout.addView(colgramSavedTagsBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));\n"
        "            topPanelLayout.setPriority(colgramSavedTagsBar, 8);\n"
        "            topPanelLayout.setViewVisible(colgramSavedTagsBar, true, false);\n"
        "        }\n"
    )
    _legacy_tag_filter_v2 = (
        "    private void colgramApplySavedTagFilter(String emoji, java.util.List<Integer> matchingIds) {\n"
        "        if (chatAdapter == null || chatListView == null) {\n"
        "            return;\n"
        "        }\n"
        "        if (emoji == null || emoji.isEmpty()) {\n"
        "            colgramActiveSavedTagEmoji = null;\n"
        "            if (colgramSavedTagFiltered) {\n"
        "                colgramSavedTagFiltered = false;\n"
        "                chatAdapter.isFiltered = false;\n"
        "                chatAdapter.filteredEndReached = false;\n"
        "                chatAdapter.filteredMessages.clear();\n"
        "                if (filteredMessagesDict != null) {\n"
        "                    filteredMessagesDict.clear();\n"
        "                }\n"
        "                updateVisibleRows();\n"
        "                chatAdapter.notifyDataSetChanged();\n"
        "            }\n"
        "            return;\n"
        "        }\n"
        "        colgramActiveSavedTagEmoji = emoji;\n"
        "        java.util.HashSet<Integer> idSet = new java.util.HashSet<>();\n"
        "        if (matchingIds != null) {\n"
        "            idSet.addAll(matchingIds);\n"
        "        }\n"
        "        if (filteredMessagesDict == null) {\n"
        "            filteredMessagesDict = new LongSparseArray<>();\n"
        "        } else {\n"
        "            filteredMessagesDict.clear();\n"
        "        }\n"
        "        chatAdapter.filteredMessages.clear();\n"
        "        for (int i = 0; i < messages.size(); i++) {\n"
        "            MessageObject m = messages.get(i);\n"
        "            if (m != null && !m.isDateObject && idSet.contains(m.getId())) {\n"
        "                chatAdapter.filteredMessages.add(m);\n"
        "                filteredMessagesDict.put(m.getId(), m);\n"
        "            }\n"
        "        }\n"
        "        if (chatAdapter.filteredMessages.isEmpty() && matchingIds != null && !matchingIds.isEmpty()) {\n"
        "            colgramSavedTagFiltered = false;\n"
        "            chatAdapter.isFiltered = false;\n"
        "            chatAdapter.filteredEndReached = false;\n"
        "            filteredMessagesDict.clear();\n"
        "            updateVisibleRows();\n"
        "            chatAdapter.notifyDataSetChanged();\n"
        "            scrollToMessageId(matchingIds.get(0), 0, true, 0, true, 0);\n"
        "            return;\n"
        "        }\n"
        "        colgramSavedTagFiltered = true;\n"
        "        chatAdapter.isFiltered = true;\n"
        "        chatAdapter.filteredEndReached = true;\n"
        "        updateVisibleRows();\n"
        "        chatAdapter.notifyDataSetChanged();\n"
        "    }\n\n"
    )
    _legacy_tag_filter_v1 = (
        "    private void colgramApplySavedTagFilter(String emoji, java.util.List<Integer> matchingIds) {\n"
        "        if (chatAdapter == null || chatListView == null) {\n"
        "            return;\n"
        "        }\n"
        "        if (emoji == null || emoji.isEmpty()) {\n"
        "            colgramActiveSavedTagEmoji = null;\n"
        "            if (colgramSavedTagFiltered) {\n"
        "                colgramSavedTagFiltered = false;\n"
        "                chatAdapter.isFiltered = false;\n"
        "                chatAdapter.filteredMessages.clear();\n"
        "                updateVisibleRows();\n"
        "                chatAdapter.notifyDataSetChanged();\n"
        "            }\n"
        "            return;\n"
        "        }\n"
        "        colgramActiveSavedTagEmoji = emoji;\n"
        "        java.util.HashSet<Integer> idSet = new java.util.HashSet<>();\n"
        "        if (matchingIds != null) {\n"
        "            idSet.addAll(matchingIds);\n"
        "        }\n"
        "        chatAdapter.filteredMessages.clear();\n"
        "        for (int i = 0; i < messages.size(); i++) {\n"
        "            MessageObject m = messages.get(i);\n"
        "            if (m != null && !m.isDateObject && idSet.contains(m.getId())) {\n"
        "                chatAdapter.filteredMessages.add(m);\n"
        "            }\n"
        "        }\n"
        "        if (chatAdapter.filteredMessages.isEmpty() && matchingIds != null && !matchingIds.isEmpty()) {\n"
        "            colgramSavedTagFiltered = false;\n"
        "            chatAdapter.isFiltered = false;\n"
        "            updateVisibleRows();\n"
        "            chatAdapter.notifyDataSetChanged();\n"
        "            scrollToMessageId(matchingIds.get(0), 0, true, 0, true, 0);\n"
        "            return;\n"
        "        }\n"
        "        colgramSavedTagFiltered = true;\n"
        "        chatAdapter.isFiltered = true;\n"
        "        updateVisibleRows();\n"
        "        chatAdapter.notifyDataSetChanged();\n"
        "    }\n\n"
    )
    _legacy_fill_menu = (
        "                if (dialog_id == getUserConfig().getClientUserId() && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled() && selectedObject.getId() > 0 && !selectedObject.isSponsored()) {\n"
        '                    items.add("Тег (эмодзи)");\n'
        "                    options.add(OPTION_COLGRAM_SAVED_TAG);\n"
        "                    icons.add(R.drawable.msg_reactions);\n"
        "                }\n"
    )
    _legacy_case_v2 = (
        "            case OPTION_COLGRAM_SAVED_TAG: {\n"
        "                final MessageObject tagTarget = selectedObject;\n"
        "                selectedObject = null;\n"
        "                selectedObjectToEditCaption = null;\n"
        "                selectedObjectGroup = null;\n"
        "                if (tagTarget != null && getParentActivity() != null && tagTarget.getId() > 0) {\n"
        "                    ColgramUiKit.showEmojiTagPickerSheet(\n"
        "                            getParentActivity(), themeDelegate, currentAccount, tagTarget.getId(),\n"
        "                            () -> {\n"
        "                                if (colgramSavedTagsBar != null) colgramSavedTagsBar.rebuild();\n"
        "                                updateVisibleRows();\n"
        "                                if (chatAdapter != null) chatAdapter.notifyDataSetChanged();\n"
        "                                if (chatListView != null) chatListView.requestLayout();\n"
        "                            }\n"
        "                    );\n"
        "                }\n"
        "                return;\n"
        "            }\n"
    )
    _legacy_case_v1 = (
        "            case OPTION_COLGRAM_SAVED_TAG: {\n"
        "                final MessageObject tagTarget = selectedObject;\n"
        "                selectedObject = null;\n"
        "                selectedObjectToEditCaption = null;\n"
        "                selectedObjectGroup = null;\n"
        "                if (tagTarget != null && getParentActivity() != null && tagTarget.getId() > 0) {\n"
        "                    ColgramUiKit.showEmojiTagPickerSheet(\n"
        "                            getParentActivity(), themeDelegate, currentAccount, tagTarget.getId(),\n"
        "                            () -> {\n"
        "                                if (colgramSavedTagsBar != null) colgramSavedTagsBar.rebuild();\n"
        "                                if (chatListView != null) chatListView.invalidateViews();\n"
        "                            }\n"
        "                    );\n"
        "                }\n"
        "                return;\n"
        "            }\n"
    )
    _legacy_chat_delegate = (
        "        @Override\n"
        "        public void didPressColgramSavedTag(ChatMessageCell cell, String emoji) {\n"
        "            if (colgramSavedTagsBar != null && emoji != null && !emoji.isEmpty()) {\n"
        "                colgramSavedTagsBar.activateEmojiFilter(emoji);\n"
        "            }\n"
        "        }\n\n"
    )
    _strip_eol_variants(
        chat_act,
        (
            _legacy_tag_fields_v2,
            _legacy_tag_fields_v1,
            _legacy_listener_cleanup,
            _legacy_opt_const,
            _legacy_mount_v2,
            _legacy_mount_v1,
            _legacy_tag_filter_v2,
            _legacy_tag_filter_v1,
            _legacy_fill_menu,
            _legacy_case_v2,
            _legacy_case_v1,
            _legacy_chat_delegate,
        ),
        "ChatActivity.java strip legacy duplicate Saved Tags UI",
    )

    _eol_replace(
        chat_act,
        (
            "    private void forwardMessages(ArrayList<MessageObject> arrayList, boolean fromMyName, boolean hideCaption, boolean notify, int scheduleDate, long payStars) {\n"
            "        if (arrayList == null || arrayList.isEmpty()) {\n"
            "            return;\n"
            "        }"
        ),
        (
            "    private void forwardMessages(ArrayList<MessageObject> arrayList, boolean fromMyName, boolean hideCaption, boolean notify, int scheduleDate, long payStars) {\n"
            "        if (arrayList == null || arrayList.isEmpty()) {\n"
            "            return;\n"
            "        }\n"
            "        if (org.colgram.core.ColgramPremiumConfig.isBypassNoForwardsEnabled() && !fromMyName) {\n"
            "            for (int ci = 0; ci < arrayList.size(); ci++) {\n"
            "                MessageObject cm = arrayList.get(ci);\n"
            "                if (cm != null && ((cm.messageOwner != null && cm.messageOwner.noforwards) || (cm.getChatId() != 0 && getMessagesController().getChat(cm.getChatId()) != null && getMessagesController().getChat(cm.getChatId()).noforwards))) {\n"
            "                    fromMyName = true;\n"
            "                    break;\n"
            "                }\n"
            "            }\n"
            "        }"
        ),
        "ChatActivity.java noforwards resend bypass",
        eol_patch_fn,
        marker="org.colgram.core.ColgramPremiumConfig.isBypassNoForwardsEnabled() && !fromMyName",
    )
    _eol_replace(
        chat_act,
        "        MessageObject obj = chatAdapter != null && chatAdapter.isFiltered ? filteredMessagesDict.get(mid) :  messagesDict[loadIndex].get(mid);",
        "        MessageObject obj = chatAdapter != null && chatAdapter.isFiltered ? (filteredMessagesDict != null ? filteredMessagesDict.get(mid) : null) : messagesDict[loadIndex].get(mid);",
        "ChatActivity.java null-safe filteredMessagesDict lookup",
        eol_patch_fn,
    )
    _upgrade_or_patch(
        chat_act,
        (
            "                        if (chatAdapter.isFiltered && !colgramSavedTagFiltered && searchingReaction != null && !messageObject.hasReaction(searchingReaction)) {",
            "                        if (chatAdapter.isFiltered && !messageObject.hasReaction(searchingReaction)) {",
        ),
        "                        if (chatAdapter.isFiltered && !messageObject.hasReaction(searchingReaction)) {",
        "                        if (chatAdapter.isFiltered && searchingReaction != null && !messageObject.hasReaction(searchingReaction)) {",
        "ChatActivity.java restore native searchingReaction filter update",
        eol_patch_fn,
        marker="if (chatAdapter.isFiltered && searchingReaction != null && !messageObject.hasReaction(searchingReaction)) {",
    )
    _eol_replace(
        chat_act,
        "        if (sponsoredMessagesAdded || chatMode != 0 || !ChatObject.isChannel(currentChat) && !UserObject.isBot(currentUser) || !forwardEndReached[0] || getUserConfig().isPremium() && getMessagesController().isSponsoredDisabled() || isReport()) {",
        "        if (sponsoredMessagesAdded || org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled() || chatMode != 0 || !ChatObject.isChannel(currentChat) && !UserObject.isBot(currentUser) || !forwardEndReached[0] || getUserConfig().isPremium() && getMessagesController().isSponsoredDisabled() || isReport()) {",
        "ChatActivity.java addSponsoredMessages ad-block",
        eol_patch_fn,
        marker="|| org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled() || getUserConfig().isPremium()",
    )

    if os.path.isfile(chat_act):
        with open(chat_act, "r", encoding="utf-8", errors="ignore", newline="") as _cf:
            _cnorm = _cf.read().replace("\r\n", "\n")
        for _old_nf, _new_nf, _lbl in (
            (
                "boolean noforwards = !colgramUnlockMedia && (isPeerNoForwards() || message.messageOwner.noforwards || getDialogId() == UserObject.VERIFY);",
                "boolean noforwards = !colgramUnlockMedia && !(org.colgram.core.ColgramPremiumConfig.isBypassNoForwardsEnabled() && getDialogId() != UserObject.VERIFY && !message.isExpiredStory()) && (isPeerNoForwards() || message.messageOwner.noforwards || getDialogId() == UserObject.VERIFY);",
                "ChatActivity.java fillMessageMenu noforwards bypass",
            ),
            (
                "boolean noforwards = isPeerNoForwards() || message.messageOwner.noforwards || getDialogId() == UserObject.VERIFY;",
                "boolean noforwards = !(org.colgram.core.ColgramPremiumConfig.isBypassNoForwardsEnabled() && getDialogId() != UserObject.VERIFY && !message.isExpiredStory()) && (isPeerNoForwards() || message.messageOwner.noforwards || getDialogId() == UserObject.VERIFY);",
                "ChatActivity.java single-menu noforwards bypass",
            ),
            (
                "            final boolean noforwards = (\n                chatActivity != null && chatActivity.isPeerNoForwards() ||\n                selectedView != null && selectedView.getMessageObject() != null && selectedView.getMessageObject().messageOwner != null && selectedView.getMessageObject().messageOwner.noforwards\n            );",
                "            final boolean noforwards = !org.colgram.core.ColgramPremiumConfig.isBypassNoForwardsEnabled() && (\n                chatActivity != null && chatActivity.isPeerNoForwards() ||\n                selectedView != null && selectedView.getMessageObject() != null && selectedView.getMessageObject().messageOwner != null && selectedView.getMessageObject().messageOwner.noforwards\n            );",
                "ChatActivity.java selection quote noforwards bypass",
            ),
            (
                "            return chatActivity == null || !(\n                chatActivity.getDialogId() < 0 && chatActivity.getMessagesController().isPeerNoForwards(chatActivity.getDialogId()) ||\n                selectedView != null && selectedView.getMessageObject() != null && (selectedView.getMessageObject().messageOwner != null && selectedView.getMessageObject().messageOwner.noforwards)\n            );",
                "            return chatActivity == null || org.colgram.core.ColgramPremiumConfig.isBypassNoForwardsEnabled() || !(\n                chatActivity.getDialogId() < 0 && chatActivity.getMessagesController().isPeerNoForwards(chatActivity.getDialogId()) ||\n                selectedView != null && selectedView.getMessageObject() != null && (selectedView.getMessageObject().messageOwner != null && selectedView.getMessageObject().messageOwner.noforwards)\n            );",
                "ChatActivity.java selection copy noforwards bypass",
            ),
            (
                "boolean noforwards = !org.colgram.core.ColgramHookHandler.shouldUnlockOneTimeMedia() && (isPeerNoForwards() || hasSelectedNoforwardsMessage());",
                "boolean noforwards = !org.colgram.core.ColgramHookHandler.shouldUnlockOneTimeMedia() && !org.colgram.core.ColgramPremiumConfig.isBypassNoForwardsEnabled() && (isPeerNoForwards() || hasSelectedNoforwardsMessage());",
                "ChatActivity.java multiselect save noforwards bypass",
            ),
            (
                "boolean noforwards = isPeerNoForwards() || (messageObject != null && messageObject.messageOwner != null && messageObject.messageOwner.noforwards);",
                "boolean noforwards = !org.colgram.core.ColgramPremiumConfig.isBypassNoForwardsEnabled() && (isPeerNoForwards() || (messageObject != null && messageObject.messageOwner != null && messageObject.messageOwner.noforwards));",
                "ChatActivity.java url copy noforwards bypass",
            ),
        ):
            if _old_nf in _cnorm:
                eol_patch_fn(chat_act, _old_nf, _new_nf, _lbl)
        # Second occurrence (mono-span url press at line 37203)
        if _cnorm.count("boolean noforwards = isPeerNoForwards() || (messageObject != null && messageObject.messageOwner != null && messageObject.messageOwner.noforwards);") >= 2:
            eol_patch_fn(
                chat_act,
                "boolean noforwards = isPeerNoForwards() || (messageObject != null && messageObject.messageOwner != null && messageObject.messageOwner.noforwards);",
                "boolean noforwards = !org.colgram.core.ColgramPremiumConfig.isBypassNoForwardsEnabled() && (isPeerNoForwards() || (messageObject != null && messageObject.messageOwner != null && messageObject.messageOwner.noforwards));",
                "ChatActivity.java mono url copy noforwards bypass",
            )

    # 12. ChatMessageCell.java — strip any legacy duplicate Saved Tags custom bubble pills (use native ReactionsLayoutInBubble instead)
    cell_path = os.path.join(ui, "Cells", "ChatMessageCell.java")
    _legacy_cell_delegate = (
        "\n\n        default void didPressColgramSavedTag(ChatMessageCell cell, String emoji) {\n"
        "        }"
    )
    _legacy_cell_touch = (
        "        if (colgramCheckSavedTagTouch(event)) {\n"
        "            return true;\n"
        "        }\n"
    )
    _legacy_cell_measure = (
        "        // colgram-saved-tag-pills-v1\n"
        "        resultHeight += colgramSavedTagExtraHeight();\n"
    )
    _legacy_cell_helpers_prefix = (
        "\n\n    private final java.util.ArrayList<android.graphics.RectF> colgramTagHitRects = new java.util.ArrayList<>(4);\n"
        "    private final java.util.ArrayList<String> colgramTagHitEmojis = new java.util.ArrayList<>(4);\n"
        "    private Paint colgramTagBgPaint;\n"
        "    private android.text.TextPaint colgramTagTextPaint;\n\n"
        "    private int colgramSavedTagExtraHeight() {\n"
        "        if (currentMessageObject == null\n"
        "                || !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()\n"
        "                || currentMessageObject.getDialogId() != UserConfig.getInstance(currentAccount).getClientUserId()) {\n"
        "            return 0;\n"
        "        }\n"
        "        java.util.List<String> tags = org.colgram.core.ColgramSavedTagsStore.getTags(currentAccount, currentMessageObject.getId());\n"
        "        return tags.isEmpty() ? 0 : dp(26);\n"
        "    }\n\n"
        "    private boolean colgramCheckSavedTagTouch(MotionEvent event) {\n"
        "        if (colgramTagHitRects.isEmpty() || delegate == null) return false;\n"
        "        int act = event.getAction();\n"
        "        if (act != MotionEvent.ACTION_DOWN && act != MotionEvent.ACTION_UP) return false;\n"
        "        float ex = getEventX(event);\n"
        "        float ey = getEventY(event);\n"
        "        for (int i = 0; i < colgramTagHitRects.size(); i++) {\n"
        "            if (colgramTagHitRects.get(i).contains(ex, ey)) {\n"
        "                if (act == MotionEvent.ACTION_UP && i < colgramTagHitEmojis.size()) {\n"
        "                    playSoundEffect(android.view.SoundEffectConstants.CLICK);\n"
        "                    delegate.didPressColgramSavedTag(this, colgramTagHitEmojis.get(i));\n"
        "                }\n"
        "                return true;\n"
        "            }\n"
        "        }\n"
        "        return false;\n"
        "    }\n\n"
        "    private void colgramDrawSavedTagPills(Canvas canvas) {\n"
        "        colgramTagHitRects.clear();\n"
        "        colgramTagHitEmojis.clear();\n"
        "        if (currentMessageObject == null\n"
        "                || !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()\n"
        "                || currentMessageObject.getDialogId() != UserConfig.getInstance(currentAccount).getClientUserId()) {\n"
        "            return;\n"
        "        }\n"
        "        java.util.List<String> tags = org.colgram.core.ColgramSavedTagsStore.getTags(currentAccount, currentMessageObject.getId());\n"
        "        if (tags.isEmpty()) return;\n"
        "        if (colgramTagBgPaint == null) {\n"
        "            colgramTagBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);\n"
        "        }\n"
        "        if (colgramTagTextPaint == null) {\n"
        "            colgramTagTextPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);\n"
        "            colgramTagTextPaint.setTextSize(dp(12));\n"
        "        }\n"
        "        int accent = Theme.getColor(Theme.key_chat_inTimeText, resourcesProvider);\n"
        "        colgramTagBgPaint.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 42));\n"
        "        colgramTagTextPaint.setColor(Theme.getColor(Theme.key_chat_messageTextIn, resourcesProvider));\n"
    )
    _legacy_draw_pills = (
        "        int maxShow = Math.min(3, tags.size());\n"
        "        float h = dp(20);\n"
        "        float yTop = Math.max(dp(4), getMeasuredHeight() - dp(23));\n"
        "        float xCursor = Math.max(dp(12), backgroundDrawableLeft);\n"
        "        for (int i = 0; i < maxShow; i++) {\n"
        "            String emoji = tags.get(i);\n"
        "            String label = (i == maxShow - 1 && tags.size() > maxShow)\n"
        '                    ? (emoji + " +" + (tags.size() - maxShow))\n'
        "                    : emoji;\n"
        "            float tw = colgramTagTextPaint.measureText(label);\n"
        "            float pillW = tw + dp(14);\n"
        "            android.graphics.RectF r = new android.graphics.RectF(xCursor, yTop, xCursor + pillW, yTop + h);\n"
        "            canvas.drawRoundRect(r, dp(10), dp(10), colgramTagBgPaint);\n"
        "            Paint.FontMetrics fm = colgramTagTextPaint.getFontMetrics();\n"
        "            float textY = r.centerY() - (fm.ascent + fm.descent) * 0.5f;\n"
        "            canvas.drawText(label, r.left + dp(7), textY, colgramTagTextPaint);\n"
        "            colgramTagHitRects.add(r);\n"
        "            colgramTagHitEmojis.add(emoji);\n"
        "            xCursor += pillW + dp(5);\n"
        "        }\n"
        "    }"
    )
    _new_draw_pills = (
        "        int maxShow = Math.min(3, tags.size());\n"
        "        float h = dp(20);\n"
        "        float yTop = Math.max(dp(4), getMeasuredHeight() - dp(23));\n"
        "        String[] labels = new String[maxShow];\n"
        "        float[] pillWidths = new float[maxShow];\n"
        "        float totalW = 0f;\n"
        "        for (int i = 0; i < maxShow; i++) {\n"
        "            String emoji = tags.get(i);\n"
        "            String label = (i == maxShow - 1 && tags.size() > maxShow)\n"
        '                    ? (emoji + " +" + (tags.size() - maxShow))\n'
        "                    : emoji;\n"
        "            labels[i] = label;\n"
        "            pillWidths[i] = colgramTagTextPaint.measureText(label) + dp(14);\n"
        "            totalW += pillWidths[i];\n"
        "            if (i + 1 < maxShow) totalW += dp(5);\n"
        "        }\n"
        "        float minLeft = dp(12);\n"
        "        float maxRight = Math.max(minLeft + dp(24), getMeasuredWidth() - dp(12));\n"
        "        float xCursor;\n"
        "        if (currentMessageObject.isOutOwner()) {\n"
        "            float rightAnchor = backgroundDrawableRight > minLeft ? Math.min(maxRight, backgroundDrawableRight) : maxRight;\n"
        "            xCursor = Math.max(minLeft, rightAnchor - totalW);\n"
        "        } else {\n"
        "            float leftAnchor = backgroundDrawableLeft > 0 ? Math.max(minLeft, backgroundDrawableLeft) : minLeft;\n"
        "            xCursor = Math.max(minLeft, Math.min(leftAnchor, maxRight - totalW));\n"
        "        }\n"
        "        for (int i = 0; i < maxShow; i++) {\n"
        "            if (xCursor >= maxRight) break;\n"
        "            String emoji = tags.get(i);\n"
        "            String label = labels[i];\n"
        "            float pillW = pillWidths[i];\n"
        "            float pillRight = Math.min(xCursor + pillW, maxRight);\n"
        "            android.graphics.RectF r = new android.graphics.RectF(xCursor, yTop, pillRight, yTop + h);\n"
        "            canvas.drawRoundRect(r, dp(10), dp(10), colgramTagBgPaint);\n"
        "            Paint.FontMetrics fm = colgramTagTextPaint.getFontMetrics();\n"
        "            float textY = r.centerY() - (fm.ascent + fm.descent) * 0.5f;\n"
        "            canvas.drawText(label, r.left + dp(7), textY, colgramTagTextPaint);\n"
        "            colgramTagHitRects.add(r);\n"
        "            colgramTagHitEmojis.add(emoji);\n"
        "            xCursor += pillW + dp(5);\n"
        "        }\n"
        "    }"
    )
    _strip_eol_variants(
        cell_path,
        (
            _legacy_cell_delegate,
            _legacy_cell_touch,
            _legacy_cell_measure,
            _legacy_cell_helpers_prefix + _new_draw_pills,
            _legacy_cell_helpers_prefix + _legacy_draw_pills,
            "        colgramDrawSavedTagPills(canvas);\n",
        ),
        "ChatMessageCell.java strip legacy duplicate Saved Tags pills",
    )

    # 12b. 100% Native Saved Messages Tags Symbiosis (gates + hydration + persistence + local search short-circuit)
    _patch_native_saved_tags_symbiosis(repo_path, eol_patch_fn)

    # 13. TranscribeButton.java — 3-tier local STT fallback without Premium paywall
    transcribe_path = os.path.join(ui, "Components", "TranscribeButton.java")
    _eol_replace(
        transcribe_path,
        "            if ((premium || canTranscribeTrial(parent.getMessageObject())) && parent.getMessageObject().isSent()) {",
        "            if ((premium || org.colgram.core.ColgramPremiumConfig.isLocalVoiceTranscribeEnabled() || canTranscribeTrial(parent.getMessageObject())) && parent.getMessageObject().isSent()) {",
        "TranscribeButton.java onTap loading local STT bypass",
        eol_patch_fn,
        marker="org.colgram.core.ColgramPremiumConfig.isLocalVoiceTranscribeEnabled() || canTranscribeTrial",
    )
    _eol_replace(
        transcribe_path,
        "            if (!premium && toOpen) {",
        "            if (!premium && !org.colgram.core.ColgramPremiumConfig.isLocalVoiceTranscribeEnabled() && toOpen) {",
        "TranscribeButton.java onTap click local STT bypass",
        eol_patch_fn,
        marker="!premium && !org.colgram.core.ColgramPremiumConfig.isLocalVoiceTranscribeEnabled() && toOpen",
    )
    _eol_replace(
        transcribe_path,
        "        if (UserConfig.getInstance(messageObject.currentAccount).isPremium()) {",
        "        if (UserConfig.getInstance(messageObject.currentAccount).isPremium() || org.colgram.core.ColgramPremiumConfig.isLocalVoiceTranscribeEnabled()) {",
        "TranscribeButton.java showTranscribeLock local STT bypass",
        eol_patch_fn,
        marker="isPremium() || org.colgram.core.ColgramPremiumConfig.isLocalVoiceTranscribeEnabled()",
    )
    _eol_replace(
        transcribe_path,
        (
            "            } else {\n"
            "                if (BuildVars.LOGS_ENABLED) {\n"
            '                    FileLog.d("sending Transcription request, msg_id=" + messageId + " dialog_id=" + dialogId);\n'
            "                }"
        ),
        (
            "            } else if (org.colgram.core.ColgramPremiumConfig.isLocalVoiceTranscribeEnabled() && org.colgram.core.ColgramPremiumConfig.isLocalVoiceTranscribeLocalFirst()) {\n"
            "                // colgram-local-stt-v1\n"
            "                colgramRunLocalStt(messageObject);\n"
            "            } else {\n"
            "                if (BuildVars.LOGS_ENABLED) {\n"
            '                    FileLog.d("sending Transcription request, msg_id=" + messageId + " dialog_id=" + dialogId);\n'
            "                }"
        ),
        "TranscribeButton.java local-first STT branch",
        eol_patch_fn,
        marker="// colgram-local-stt-v1",
    )
    _eol_replace(
        transcribe_path,
        (
            "                    } else {\n"
            "                        if (err != null && err.text != null) {\n"
            '                            if (err.text.startsWith("FLOOD_WAIT_")) {'
        ),
        (
            "                    } else {\n"
            "                        if (org.colgram.core.ColgramPremiumConfig.isLocalVoiceTranscribeEnabled()) {\n"
            "                            colgramRunLocalStt(messageObject);\n"
            "                            return;\n"
            "                        }\n"
            "                        if (err != null && err.text != null) {\n"
            '                            if (err.text.startsWith("FLOOD_WAIT_")) {'
        ),
        "TranscribeButton.java local STT RPC fallback",
        eol_patch_fn,
        marker="colgramRunLocalStt(messageObject);\n                            return;",
    )
    _legacy_run_stt = (
        "                TLRPC.InputPeer peer = MessagesController.getInstance(account).getInputPeer(messageObject.messageOwner.peer_id);\n"
        "                long dialogId = DialogObject.getPeerDialogId(peer);\n"
        "                int messageId = messageObject.messageOwner.id;\n"
        "                TranscribeButton.openVideoTranscription(messageObject);\n"
        "                messageObject.messageOwner.voiceTranscriptionOpen = true;\n"
        "                messageObject.messageOwner.voiceTranscriptionFinal = true;\n"
        "                MessagesStorage.getInstance(account).updateMessageVoiceTranscription(dialogId, messageId, text, messageObject.messageOwner);\n"
        "                finishTranscription(messageObject, 0L, text);"
    )
    _new_run_stt = (
        "                if (messageObject.isRoundVideo()) {\n"
        "                    TranscribeButton.openVideoTranscription(messageObject);\n"
        "                }\n"
        "                messageObject.messageOwner.voiceTranscriptionOpen = true;\n"
        "                messageObject.messageOwner.voiceTranscriptionFinal = true;\n"
        "                finishTranscription(messageObject, 0L, text);"
    )
    _tr_norm = ""
    if os.path.isfile(transcribe_path):
        with open(transcribe_path, "r", encoding="utf-8", errors="ignore", newline="") as _trf:
            _tr_norm = _trf.read().replace("\r\n", "\n")
    if _legacy_run_stt in _tr_norm:
        eol_patch_fn(
            transcribe_path,
            _legacy_run_stt,
            _new_run_stt,
            "TranscribeButton.java colgramRunLocalStt round-video guard and single DB write",
        )
    else:
        _eol_replace(
            transcribe_path,
            "    public static boolean finishTranscription(MessageObject messageObject, long transcription_id, String text) {",
            (
                "    private static void colgramRunLocalStt(final MessageObject messageObject) {\n"
                "        if (messageObject == null || messageObject.messageOwner == null) return;\n"
                "        final int account = messageObject.currentAccount;\n"
                "        if (transcribeOperationsByDialogPosition == null) {\n"
                "            transcribeOperationsByDialogPosition = new HashMap<>();\n"
                "        }\n"
                "        transcribeOperationsByDialogPosition.put((Integer) reqInfoHash(messageObject), messageObject);\n"
                "        java.io.File path = org.telegram.messenger.FileLoader.getInstance(account).getPathToMessage(messageObject.messageOwner);\n"
                "        if (path == null || !path.isFile() || path.length() <= 0L) {\n"
                "            TLRPC.Document doc = messageObject.getDocument();\n"
                "            if (doc != null) {\n"
                "                org.telegram.messenger.FileLoader.getInstance(account).loadFile(doc, messageObject, org.telegram.messenger.FileLoader.PRIORITY_HIGH, 1);\n"
                "            }\n"
                "            if (transcribeOperationsByDialogPosition != null) {\n"
                "                transcribeOperationsByDialogPosition.remove((Integer) reqInfoHash(messageObject));\n"
                "            }\n"
                "            org.colgram.core.ColgramUiBridge.postNotice(org.telegram.messenger.ApplicationLoader.applicationContext,\n"
                '                    "Скачивание аудио", "Загружаю голосовое, нажмите ещё раз после загрузки", false);\n'
                "            AndroidUtilities.runOnUIThread(() ->\n"
                "                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, messageObject));\n"
                "            return;\n"
                "        }\n"
                "        org.colgram.core.ColgramLocalStt.transcribeFileAsync(org.telegram.messenger.ApplicationLoader.applicationContext, path, (ok, text, reason) -> {\n"
                "            AndroidUtilities.runOnUIThread(() -> {\n"
                "                if (transcribeOperationsByDialogPosition != null) {\n"
                "                    transcribeOperationsByDialogPosition.remove((Integer) reqInfoHash(messageObject));\n"
                "                }\n"
                "                if (!ok || TextUtils.isEmpty(text)) {\n"
                "                    org.colgram.core.ColgramUiBridge.postNotice(org.telegram.messenger.ApplicationLoader.applicationContext,\n"
                '                            "Озвучка -> Текст", reason != null ? reason : "Не удалось распознать речь локально", true);\n'
                "                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, messageObject);\n"
                "                    return;\n"
                "                }\n"
                + _new_run_stt + "\n"
                "            });\n"
                "        });\n"
                "    }\n\n"
                "    public static boolean finishTranscription(MessageObject messageObject, long transcription_id, String text) {"
            ),
            "TranscribeButton.java colgramRunLocalStt helper",
            eol_patch_fn,
            marker="private static void colgramRunLocalStt(",
        )

    # 14. TranslateController.java & TranslateAlert2.java — local keyless translator bypass, callback & debounce
    tc_path = os.path.join(msg_dir, "TranslateController.java")
    _eol_replace(
        tc_path,
        (
            "    public boolean isFeatureAvailable() {\n"
            "        return isChatTranslateEnabled() && UserConfig.getInstance(currentAccount).isPremium();\n"
            "    }"
        ),
        (
            "    public boolean isFeatureAvailable() {\n"
            "        return isChatTranslateEnabled() && (UserConfig.getInstance(currentAccount).isPremium() || org.colgram.core.ColgramPremiumConfig.isLocalTranslatorEnabled());\n"
            "    }"
        ),
        "TranslateController.java isFeatureAvailable local translator",
        eol_patch_fn,
    )
    _eol_replace(
        tc_path,
        (
            "        return (\n"
            "            UserConfig.getInstance(currentAccount).isPremium() ||\n"
            "            chat != null && chat.autotranslation\n"
            "        );"
        ),
        (
            "        return (\n"
            "            UserConfig.getInstance(currentAccount).isPremium() ||\n"
            "            org.colgram.core.ColgramPremiumConfig.isLocalTranslatorEnabled() ||\n"
            "            chat != null && chat.autotranslation\n"
            "        );"
        ),
        "TranslateController.java isFeatureAvailable(dialogId) local translator",
        eol_patch_fn,
    )
    _eol_replace(
        tc_path,
        "    private Boolean chatTranslateEnabled;",
        "    private Boolean chatTranslateEnabled;\n    private long colgramLastTranslateErrMs;",
        "TranslateController.java colgramLastTranslateErrMs field",
        eol_patch_fn,
        marker="colgramLastTranslateErrMs;",
    )
    _eol_replace(
        tc_path,
        (
            '                if ("alternative".equals(method) || "system".equals(method)) {\n'
            "                    final String toLanguage = pendingTranslation1.language;\n"
            "                    for (int i = 0; i < pendingTranslation1.messageIds.size(); ++i) {\n"
            "                        final int id = pendingTranslation1.messageIds.get(i);\n"
            "                        final Utilities.Callback4<Boolean, Integer, TLRPC.TL_textWithEntities, String> _callback = pendingTranslation1.callbacks.get(i);\n"
            "                        final String _text = pendingTranslation1.messageTexts.get(i).text;\n"
            "                        TranslateAlert2.alternativeTranslate(_text, null, toLanguage, (result, rateLimit) -> {\n"
            "                            if (result != null) {\n"
            "                                final TLRPC.TL_textWithEntities resultWithEntities = new TLRPC.TL_textWithEntities();\n"
            "                                resultWithEntities.text = result;\n"
            "                                _callback.run(isTranscription, id, resultWithEntities, toLanguage);\n"
            "                            } else {\n"
            "                                toggleTranslatingDialog(dialogId, false);\n"
            "                                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_ERROR, getString(rateLimit ? R.string.TranslationFailedAlert1 : R.string.TranslationFailedAlert2));\n"
            "                            }\n"
            "                        });\n"
            "                    }"
        ),
        (
            '                if ("alternative".equals(method) || "system".equals(method) || (!getUserConfig().isPremium() && org.colgram.core.ColgramPremiumConfig.isLocalTranslatorEnabled())) {\n'
            "                    final String toLanguage = pendingTranslation1.language;\n"
            "                    for (int i = 0; i < pendingTranslation1.messageIds.size(); ++i) {\n"
            "                        final int id = pendingTranslation1.messageIds.get(i);\n"
            "                        final Utilities.Callback4<Boolean, Integer, TLRPC.TL_textWithEntities, String> _callback = pendingTranslation1.callbacks.get(i);\n"
            "                        final String _text = pendingTranslation1.messageTexts.get(i).text;\n"
            "                        TranslateAlert2.alternativeTranslate(_text, null, toLanguage, (result, rateLimit) -> {\n"
            "                            synchronized (TranslateController.this) {\n"
            "                                loadingTranslations.remove(id);\n"
            "                            }\n"
            "                            if (result != null) {\n"
            "                                final TLRPC.TL_textWithEntities resultWithEntities = new TLRPC.TL_textWithEntities();\n"
            "                                resultWithEntities.text = result;\n"
            "                                _callback.run(isTranscription, id, resultWithEntities, toLanguage);\n"
            "                            } else {\n"
            "                                _callback.run(isTranscription, id, null, toLanguage);\n"
            "                                long nowMs = System.currentTimeMillis();\n"
            "                                if (nowMs - colgramLastTranslateErrMs >= 4000L) {\n"
            "                                    colgramLastTranslateErrMs = nowMs;\n"
            "                                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_ERROR, getString(rateLimit ? R.string.TranslationFailedAlert1 : R.string.TranslationFailedAlert2));\n"
            "                                }\n"
            "                            }\n"
            "                        });\n"
            "                    }"
        ),
        "TranslateController.java bypass server RPC for non-Premium and invoke _callback(null) cleanly",
        eol_patch_fn,
        marker="!getUserConfig().isPremium() && org.colgram.core.ColgramPremiumConfig.isLocalTranslatorEnabled()",
    )
    _legacy_tc_fallback = (
        "                    } else if (!(res instanceof TLRPC.TL_messages_translateResult) && ((err != null && \"TRANSLATIONS_DISABLED_ALT\".equalsIgnoreCase(err.text)) || org.colgram.core.ColgramPremiumConfig.isLocalTranslatorEnabled())) {\n"
        "                        for (int i = 0; i < ids.size(); ++i) {\n"
        "                            final int id = ids.get(i);\n"
        "                            final Utilities.Callback4<Boolean, Integer, TLRPC.TL_textWithEntities, String> _callback = callbacks.get(i);\n"
        "                            final String _text = texts.get(i).text;\n"
        "                            TranslateAlert2.alternativeTranslate(_text, null, toLanguage, (result, rateLimit) -> {\n"
        "                                if (result != null) {\n"
        "                                    final TLRPC.TL_textWithEntities resultWithEntities = new TLRPC.TL_textWithEntities();\n"
        "                                    resultWithEntities.text = result;\n"
        "                                    _callback.run(isTranscription, id, resultWithEntities, toLanguage);\n"
        "                                } else {\n"
        "                                    toggleTranslatingDialog(dialogId, false);\n"
        "                                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_ERROR, getString(rateLimit ? R.string.TranslationFailedAlert1 : R.string.TranslationFailedAlert2));\n"
        "                                }\n"
        "                            });\n"
        "                        }"
    )
    _raw_tc_fallback = (
        '                    } else if (err != null && "TRANSLATIONS_DISABLED_ALT".equalsIgnoreCase(err.text)) {\n'
        "                        for (int i = 0; i < ids.size(); ++i) {\n"
        "                            final int id = ids.get(i);\n"
        "                            final Utilities.Callback4<Boolean, Integer, TLRPC.TL_textWithEntities, String> _callback = callbacks.get(i);\n"
        "                            final String _text = texts.get(i).text;\n"
        "                            TranslateAlert2.alternativeTranslate(_text, null, toLanguage, (result, rateLimit) -> {\n"
        "                                if (result != null) {\n"
        "                                    final TLRPC.TL_textWithEntities resultWithEntities = new TLRPC.TL_textWithEntities();\n"
        "                                    resultWithEntities.text = result;\n"
        "                                    _callback.run(isTranscription, id, resultWithEntities, toLanguage);\n"
        "                                } else {\n"
        "                                    toggleTranslatingDialog(dialogId, false);\n"
        "                                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_ERROR, getString(rateLimit ? R.string.TranslationFailedAlert1 : R.string.TranslationFailedAlert2));\n"
        "                                }\n"
        "                            });\n"
        "                        }"
    )
    _new_tc_fallback = (
        "                    } else if (!(res instanceof TLRPC.TL_messages_translateResult) && ((err != null && \"TRANSLATIONS_DISABLED_ALT\".equalsIgnoreCase(err.text)) || org.colgram.core.ColgramPremiumConfig.isLocalTranslatorEnabled())) {\n"
        "                        for (int i = 0; i < ids.size(); ++i) {\n"
        "                            final int id = ids.get(i);\n"
        "                            final Utilities.Callback4<Boolean, Integer, TLRPC.TL_textWithEntities, String> _callback = callbacks.get(i);\n"
        "                            final String _text = texts.get(i).text;\n"
        "                            TranslateAlert2.alternativeTranslate(_text, null, toLanguage, (result, rateLimit) -> {\n"
        "                                if (result != null) {\n"
        "                                    final TLRPC.TL_textWithEntities resultWithEntities = new TLRPC.TL_textWithEntities();\n"
        "                                    resultWithEntities.text = result;\n"
        "                                    _callback.run(isTranscription, id, resultWithEntities, toLanguage);\n"
        "                                } else {\n"
        "                                    _callback.run(isTranscription, id, null, toLanguage);\n"
        "                                    long nowMs = System.currentTimeMillis();\n"
        "                                    if (nowMs - colgramLastTranslateErrMs >= 4000L) {\n"
        "                                        colgramLastTranslateErrMs = nowMs;\n"
        "                                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_ERROR, getString(rateLimit ? R.string.TranslationFailedAlert1 : R.string.TranslationFailedAlert2));\n"
        "                                    }\n"
        "                                }\n"
        "                            });\n"
        "                        }"
    )
    _upgrade_or_patch(
        tc_path,
        (_legacy_tc_fallback,),
        _raw_tc_fallback,
        _new_tc_fallback,
        "TranslateController.java local translator fallback callback and debounce",
        eol_patch_fn,
    )

    ta_path = os.path.join(ui, "Components", "TranslateAlert2.java")
    _old_ta_err = 'if (err != null && "TRANSLATIONS_DISABLED_ALT".equalsIgnoreCase(err.text)) {'
    _new_ta_err = 'if (err != null && ("TRANSLATIONS_DISABLED_ALT".equalsIgnoreCase(err.text) || org.colgram.core.ColgramPremiumConfig.isLocalTranslatorEnabled())) {'
    if os.path.isfile(ta_path):
        with open(ta_path, "r", encoding="utf-8", errors="ignore", newline="") as _tf:
            _tnorm = _tf.read().replace("\r\n", "\n")
        for _idx in range(_tnorm.count(_old_ta_err)):
            eol_patch_fn(ta_path, _old_ta_err, _new_ta_err, f"TranslateAlert2.java fallback #{_idx + 1}")
    _eol_replace(
        ta_path,
        "    public static void alternativeTranslate(String text, String fromLng, String toLng, Utilities.Callback2<String, Boolean> done) {\n        if (done == null) return;",
        (
            "    public static void alternativeTranslate(String text, String fromLng, String toLng, Utilities.Callback2<String, Boolean> done) {\n"
            "        if (done == null) return;\n"
            "        // colgram-local-translator-v1\n"
            "        if (org.colgram.core.ColgramPremiumConfig.isLocalTranslatorEnabled()) {\n"
            "            org.colgram.core.ColgramLocalTranslator.translateAsync(\n"
            "                    org.telegram.messenger.ApplicationLoader.applicationContext,\n"
            "                    text, fromLng, toLng,\n"
            "                    (ok, translatedText, rateLimit) -> done.run(ok ? translatedText : null, rateLimit)\n"
            "            );\n"
            "            return;\n"
            "        }"
        ),
        "TranslateAlert2.java local translator routing",
        eol_patch_fn,
        marker="// colgram-local-translator-v1",
    )

    # 15. DialogsActivity.java — hide stories strip, auto-hide archive, default start folder tab
    dialogs_path = os.path.join(ui, "DialogsActivity.java")
    _eol_replace(
        dialogs_path,
        "    private int initialDialogsType;",
        (
            "    private int initialDialogsType;\n"
            "    private boolean colgramAppliedDefaultFolder;"
        ),
        "DialogsActivity.java colgramAppliedDefaultFolder field",
        eol_patch_fn,
        marker="colgramAppliedDefaultFolder",
    )
    _eol_replace(
        dialogs_path,
        "        if (initialDialogsType == DIALOGS_TYPE_DEFAULT) {\n            askAboutContacts =",
        (
            "        // colgram-premium-dialogs-v1\n"
            "        if (initialDialogsType == DIALOGS_TYPE_DEFAULT && folderId == 0 && !onlySelect && org.colgram.core.ColgramPremiumConfig.isAutoHideArchiveEnabled() && !SharedConfig.archiveHidden) {\n"
            "            SharedConfig.toggleArchiveHidden();\n"
            "        }\n"
            "        if (initialDialogsType == DIALOGS_TYPE_DEFAULT) {\n"
            "            askAboutContacts ="
        ),
        "DialogsActivity.java onFragmentCreate auto-hide archive",
        eol_patch_fn,
        marker="// colgram-premium-dialogs-v1",
    )
    _eol_replace(
        dialogs_path,
        "        Theme.colgramSyncDrawableTintsIfNeeded();",
        (
            "        Theme.colgramSyncDrawableTintsIfNeeded();\n"
            "        if (initialDialogsType == DIALOGS_TYPE_DEFAULT && folderId == 0 && !onlySelect && org.colgram.core.ColgramPremiumConfig.isAutoHideArchiveEnabled() && !SharedConfig.archiveHidden) {\n"
            "            SharedConfig.toggleArchiveHidden();\n"
            "        }"
        ),
        "DialogsActivity.java onResume auto-hide archive",
        eol_patch_fn,
        marker="ColgramPremiumConfig.isAutoHideArchiveEnabled() && !SharedConfig.archiveHidden",
    )
    _legacy_start_folder = (
        "                if (!colgramAppliedDefaultFolder && folderId == 0 && communityId == 0 && !onlySelect && initialDialogsType == DIALOGS_TYPE_DEFAULT) {\n"
        "                    colgramAppliedDefaultFolder = true;\n"
        "                    final int defFid = org.colgram.core.ColgramPremiumConfig.getDefaultStartFolderId();\n"
        "                    if (defFid > 0) {\n"
        "                        AndroidUtilities.runOnUIThread(() -> { if (filterTabsView != null) scrollToFolder(defFid); });\n"
        "                    }\n"
        "                }"
    )
    _raw_start_folder = (
        "                if (filterTabsView.isLocked(filterTabsView.getCurrentTabId())) {\n"
        "                    filterTabsView.selectFirstTab();\n"
        "                }\n"
        "            }"
    )
    _new_start_folder_body = (
        "                if (!colgramAppliedDefaultFolder && folderId == 0 && communityId == 0 && !onlySelect && initialDialogsType == DIALOGS_TYPE_DEFAULT) {\n"
        "                    final int defFid = org.colgram.core.ColgramPremiumConfig.getDefaultStartFolderId();\n"
        "                    if (defFid <= 0) {\n"
        "                        colgramAppliedDefaultFolder = true;\n"
        "                    } else if (filterTabsView.hasTab(defFid)) {\n"
        "                        colgramAppliedDefaultFolder = true;\n"
        "                        AndroidUtilities.runOnUIThread(() -> { if (filterTabsView != null) scrollToFolder(defFid); });\n"
        "                    }\n"
        "                }"
    )
    _dl_pre_norm = ""
    if os.path.isfile(dialogs_path):
        with open(dialogs_path, "r", encoding="utf-8", errors="ignore", newline="") as _dlpf:
            _dl_pre_norm = _dlpf.read().replace("\r\n", "\n")
    if _legacy_start_folder in _dl_pre_norm:
        eol_patch_fn(
            dialogs_path,
            _legacy_start_folder,
            _new_start_folder_body,
            "DialogsActivity.java default start folder tab sync-safe upgrade",
        )
    else:
        _eol_replace(
            dialogs_path,
            _raw_start_folder,
            (
                "                if (filterTabsView.isLocked(filterTabsView.getCurrentTabId())) {\n"
                "                    filterTabsView.selectFirstTab();\n"
                "                }\n"
                + _new_start_folder_body + "\n"
                "            }"
            ),
            "DialogsActivity.java default start folder tab sync-safe",
            eol_patch_fn,
            marker="else if (filterTabsView.hasTab(defFid))",
        )
    if os.path.isfile(dialogs_path):
        with open(dialogs_path, "r", encoding="utf-8", errors="ignore", newline="") as _dlf:
            _dl_norm = _dlf.read().replace("\r\n", "\n")
        _legacy_hide_stories = (
            "        boolean newVisibility;\n"
            "        if (communityId != 0) {\n"
            "            newVisibility = false;\n"
            "        } else if (isArchive()) {\n"
            "            newVisibility = !getStoriesController().getHiddenList().isEmpty();\n"
            "        } else {\n"
            "            newVisibility = !onlySelfStories && getStoriesController().hasStories();\n"
            "            onlySelfStories = getStoriesController().hasOnlySelfStories();\n"
            "        }\n\n"
            "        if (org.colgram.core.ColgramPremiumConfig.isHideStoriesEnabled()) {\n"
            "            newVisibility = false;\n"
            "            onlySelfStories = false;\n"
            "        }"
        )
        _clean_hide_stories = (
            "        boolean newVisibility;\n"
            "        if (org.colgram.core.ColgramPremiumConfig.isHideStoriesEnabled()) {\n"
            "            newVisibility = false;\n"
            "            onlySelfStories = false;\n"
            "        } else if (communityId != 0) {\n"
            "            newVisibility = false;\n"
            "        } else if (isArchive()) {\n"
            "            newVisibility = !getStoriesController().getHiddenList().isEmpty();\n"
            "        } else {\n"
            "            newVisibility = !onlySelfStories && getStoriesController().hasStories();\n"
            "            onlySelfStories = getStoriesController().hasOnlySelfStories();\n"
            "        }"
        )
        if _legacy_hide_stories in _dl_norm:
            eol_patch_fn(dialogs_path, _legacy_hide_stories, _clean_hide_stories, "DialogsActivity.java hide stories strip")
        elif "if (org.colgram.core.ColgramPremiumConfig.isHideStoriesEnabled()) {\n            newVisibility = false;" not in _dl_norm:
            eol_patch_fn(
                dialogs_path,
                (
                    "        boolean newVisibility;\n"
                    "        if (communityId != 0) {"
                ),
                (
                    "        boolean newVisibility;\n"
                    "        if (org.colgram.core.ColgramPremiumConfig.isHideStoriesEnabled()) {\n"
                    "            newVisibility = false;\n"
                    "            onlySelfStories = false;\n"
                    "        } else if (communityId != 0) {"
                ),
                "DialogsActivity.java hide stories strip",
            )

    # 16. AppIconsSelectorCell.java — keep all built-in launcher icons unlocked regardless of remote flag
    icons_path = os.path.join(ui, "Cells", "AppIconsSelectorCell.java")
    _eol_replace(
        icons_path,
        "        if (MessagesController.getInstance(currentAccount).premiumFeaturesBlocked()) {",
        "        if (false && MessagesController.getInstance(currentAccount).premiumFeaturesBlocked()) {",
        "AppIconsSelectorCell.java keep all app icons unlocked",
        eol_patch_fn,
        marker="if (false && MessagesController.getInstance(currentAccount).premiumFeaturesBlocked())",
    )

    # 17. MediaDataController.java & Double-Tap disabled NPE guards (ReactionsDoubleTapManageActivity + ChatActivity)
    mdc_path = os.path.join(msg_dir, "MediaDataController.java")
    _eol_replace(
        mdc_path,
        "    public String getDoubleTapReaction() {\n        if (doubleTapReaction != null) {",
        (
            "    public String getDoubleTapReaction() {\n"
            "        String colgramOverride = org.colgram.core.ColgramPremiumConfig.getLocalDoubleTapReaction();\n"
            '        if ("disabled".equals(colgramOverride)) {\n'
            "            return null;\n"
            "        }\n"
            "        if (!android.text.TextUtils.isEmpty(colgramOverride)) {\n"
            "            return colgramOverride;\n"
            "        }\n"
            "        if (doubleTapReaction != null) {"
        ),
        "MediaDataController.java local double-tap reaction override",
        eol_patch_fn,
        marker="ColgramPremiumConfig.getLocalDoubleTapReaction()",
    )
    dt_manage_path = os.path.join(ui, "ReactionsDoubleTapManageActivity.java")
    _eol_replace(
        dt_manage_path,
        "                        reactionCell.bind(react, react.reaction.contains(MediaDataController.getInstance(currentAccount).getDoubleTapReaction()), currentAccount);",
        (
            "                        String colgramCurDoubleTap = MediaDataController.getInstance(currentAccount).getDoubleTapReaction();\n"
            "                        reactionCell.bind(react, colgramCurDoubleTap != null && react.reaction.contains(colgramCurDoubleTap), currentAccount);"
        ),
        "ReactionsDoubleTapManageActivity.java double-tap disabled null guard",
        eol_patch_fn,
        marker="colgramCurDoubleTap",
    )
    _eol_replace(
        chat_act,
        (
            "            String reactionString = getMediaDataController().getDoubleTapReaction();\n"
            '            if (reactionString.startsWith("animated_")) {'
        ),
        (
            "            String reactionString = getMediaDataController().getDoubleTapReaction();\n"
            "            if (TextUtils.isEmpty(reactionString)) {\n"
            "                return;\n"
            "            }\n"
            '            if (reactionString.startsWith("animated_")) {'
        ),
        "ChatActivity.java double-tap disabled null guard",
        eol_patch_fn,
        marker="if (TextUtils.isEmpty(reactionString)) {\n                return;\n            }\n            if (reactionString.startsWith",
    )


def apply(repo_path: str, patch_file_fn, eol_patch_fn, koliti_enabled: bool = True) -> None:
    """Apply all Track 3 UI overhaul, HUD, and Client-Side Premium+ patches idempotently."""
    _ = koliti_enabled  # shared UI/Premium+ hooks are cleanly decoupled from Koliti via ColgramLocalStt reflection
    _patch_ui_cells_and_hud(repo_path, patch_file_fn, eol_patch_fn)
    _patch_premium_plus_features(repo_path, eol_patch_fn)
