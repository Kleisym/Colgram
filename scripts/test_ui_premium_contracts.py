# -*- coding: utf-8 -*-
"""Contract tests for Track 3 UI overhaul, Floating HUD/ProgressBar, and Client-Side Premium+."""

import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
TEMPLATES = SCRIPTS / "templates"
CORE = ROOT / "colgram-core/src/main/java/org/colgram/core"
sys.path.insert(0, str(SCRIPTS))


class UiPremiumContractsTests(unittest.TestCase):
    def test_contract_c_premium_config_signatures(self):
        src = (CORE / "ColgramPremiumConfig.java").read_text(encoding="utf-8")
        for sig in (
            "public static void init(Context ctx)",
            "public static boolean isBlockSponsoredAdsEnabled()",
            "public static void setBlockSponsoredAdsEnabled(boolean v)",
            "public static boolean isLocalVoiceTranscribeEnabled()",
            "public static void setLocalVoiceTranscribeEnabled(boolean v)",
            "public static boolean isLocalVoiceTranscribeLocalFirst()",
            "public static void setLocalVoiceTranscribeLocalFirst(boolean v)",
            "public static boolean isLocalTranslatorEnabled()",
            "public static void setLocalTranslatorEnabled(boolean v)",
            "public static boolean isBypassNoForwardsEnabled()",
            "public static void setBypassNoForwardsEnabled(boolean v)",
            "public static boolean isSavedTagsEnabled()",
            "public static void setSavedTagsEnabled(boolean v)",
            "public static boolean isHideStoriesEnabled()",
            "public static void setHideStoriesEnabled(boolean v)",
            "public static boolean isAutoHideArchiveEnabled()",
            "public static void setAutoHideArchiveEnabled(boolean v)",
            "public static int getDefaultStartFolderId()",
            "public static void setDefaultStartFolderId(int folderId)",
            "public static String getLocalDoubleTapReaction()",
            "public static void setLocalDoubleTapReaction(String emoji)",
        ):
            self.assertIn(sig, src)

    def test_contract_d_uikit_hud_and_core_bridge_decoupling(self):
        bridge = (CORE / "ColgramUiBridge.java").read_text(encoding="utf-8")
        for token in (
            "public interface NoticeDispatcher",
            "public static void setNoticeDispatcher(NoticeDispatcher dispatcher)",
            "public static void postNotice(Context ctx, String title, String subtitle, boolean isError)",
            "public static void postNotice(Context ctx, String title, String subtitle, int iconRes, int accentColor)",
            "ColgramPremiumConfig.init(app);",
            "ColgramSavedTagsStore.init(app);",
        ):
            self.assertIn(token, bridge)

        uikit = (TEMPLATES / "ColgramUiKit.java").read_text(encoding="utf-8")
        for token in (
            "class MaxHeightScrollView",
            "class ColgramHudManager",
            "class ColgramProgressBarView",
            "class ProgressHudHandle",
            "public static void showHudBanner(",
            "public static ProgressHudHandle showProgressHud(",
            "public static void showMalwareWarningSheet(",
            "public static BottomSheet showEmojiTagPickerSheet(",
        ):
            self.assertIn(token, uikit)
        # No BlurMaskFilter/RenderEffect API calls or Koliti compile dependency inside shared UIKit
        self.assertNotIn("new BlurMaskFilter", uikit)
        self.assertNotIn("BlurMaskFilter(", uikit)
        self.assertNotIn("setRenderEffect", uikit)
        self.assertNotIn("RenderEffect.", uikit)
        self.assertNotIn("ColgramKoliti", uikit)
        self.assertNotIn("ColgramAi", uikit)

        stt = (CORE / "ColgramLocalStt.java").read_text(encoding="utf-8")
        self.assertIn("Class.forName(\"org.colgram.core.ColgramKolitiVoice\")", stt)
        self.assertNotIn("import org.colgram.core.ColgramKolitiVoice;", stt)
        self.assertIn("createOnDeviceSpeechRecognizer", stt)
        self.assertIn("Manifest.permission.RECORD_AUDIO", stt)
        self.assertNotIn("ColgramUiBridge.postNotice(", stt)

        tr = (CORE / "ColgramLocalTranslator.java").read_text(encoding="utf-8")
        self.assertIn("pickBrowserUserAgent", tr)
        self.assertIn("pickRelayProxy", tr)
        self.assertIn("DISK_WRITE_LOCK", tr)
        self.assertIn("if (via != null || effectiveProxy.type() == Proxy.Type.SOCKS)", tr)

        self.assertIn("presentBottomSheet(act, sheet)", uikit)
        self.assertIn("!bs.isDismissed()", uikit)

    def test_owned_templates_no_raw_toast_or_ua_leaks(self):
        no_toast_files = (
            "ColgramDesignStudioActivity.java",
            "ColgramAiActivity.java",
            "ColgramAccountSwitcherHelper.java",
            "ColgramTempMailActivity.java",
            "ColgramVersionsActivity.java",
            "ColgramDefenderActivity.java",
            "ColgramPluginsActivity.java",
            "ColgramSettingsActivity.java",
            "ColgramPremiumActivity.java",
            "ColgramAntiSpamActivity.java",
        )
        for name in no_toast_files:
            txt = (TEMPLATES / name).read_text(encoding="utf-8")
            self.assertNotIn("Toast.makeText(", txt, f"{name} must use ColgramUiKit HUD instead of Toast.makeText")
            self.assertNotIn('"Colgram-Android"', txt, f"{name} must not leak Colgram-Android User-Agent")

        # Saved Tags bar & Premium Activity register cleanly in apply-patches.py
        ap = (SCRIPTS / "apply-patches.py").read_text(encoding="utf-8")
        self.assertGreaterEqual(ap.count('"ColgramPremiumActivity.java"'), 2)
        self.assertGreaterEqual(ap.count('"ColgramSavedTagsBarView.java"'), 2)
        self.assertIn("from ui_premium_patches import apply as inject_ui_premium_patches", ap)

        # Progress HUD wired into ChatAttachAlert replacing percentage Toasts (fully qualified for Components package)
        adp = (SCRIPTS / "attach_del_perf_patches.py").read_text(encoding="utf-8")
        self.assertIn("org.telegram.ui.ColgramUiKit.ProgressHudHandle", adp)
        self.assertIn("org.telegram.ui.ColgramUiKit.showProgressHud(", adp)
        self.assertIn("org.telegram.ui.ColgramUiKit.showNotice(", adp)
        self.assertIn("colgramImportHud.complete(", adp)
        self.assertNotIn('Toast.makeText(colgramAppCtx, "Подготовка файла', adp)

    def test_ui_premium_patches_apply_two_pass_idempotency(self):
        import ui_premium_patches as upp

        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / "Telegram-Src"
            base = repo / "TMessagesProj/src/main/java/org/telegram"
            ui = base / "ui"
            cells = ui / "Cells"
            comps = ui / "Components"
            adap = ui / "Adapters"
            msg = base / "messenger"
            for d in (ui, cells, comps, adap, msg):
                d.mkdir(parents=True, exist_ok=True)

            # Populate minimal synthetic anchors matching Telegram-Src for all 17 files
            (cells / "CheckBoxCell.java").write_text(
                "public class CheckBoxCell {\n"
                "        } else if (isMultiline) {\n"
                "            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));\n"
                "        } else {\n"
                "        }\n"
                "        if (click1Container != null) {\n"
                "            MarginLayoutParams margin = (MarginLayoutParams) click1Container.getLayoutParams();\n"
                "            click1Container.measure(MeasureSpec.makeMeasureSpec(width - margin.leftMargin - margin.rightMargin, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(50), MeasureSpec.EXACTLY));\n"
                "        }\n"
                "        if (click2Container != null) {\n"
                "            click2Container.measure(MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(50), MeasureSpec.EXACTLY));\n"
                "        }\n"
                "        valueTextView.setText(value);\n"
                "        needDivider = divider;\n"
                "            if (currentType != TYPE_CHECK_BOX_URL) {\n"
                "//                layoutParams.height = LayoutParams.WRAP_CONTENT;\n"
                "//                layoutParams.gravity = (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP;\n"
                "//                layoutParams.topMargin = dp(14);\n"
                "//                layoutParams.bottomMargin = dp(10);\n"
                "            }\n"
                "}\n",
                encoding="utf-8",
            )
            (cells / "TextSettingsCell.java").write_text(
                "public class TextSettingsCell {\n"
                "    private boolean betterLayout = BuildVars.DEBUG_PRIVATE_VERSION;\n"
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
                "    }\n"
                "    public void setText(CharSequence text, boolean divider) {\n"
                "        textView.setText(text);\n"
                "        valueTextView.setVisibility(INVISIBLE);\n"
                "        valueImageView.setVisibility(INVISIBLE);\n"
                "        needDivider = divider;\n"
                "        setWillNotDraw(!divider);\n"
                "    }\n"
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
                "    }\n"
                "    public void setIcon() {\n"
                "            if (LocaleController.isRTL) {\n"
                "                params.rightMargin = AndroidUtilities.dp(58);\n"
                "            } else {\n"
                "                params.leftMargin = AndroidUtilities.dp(58);\n"
                "            }\n"
                "        }\n"
                "    }\n\n"
                "    public void setColorfulIcon(int resId, int color) {\n"
                "            imageView.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));\n"
                "            if (LocaleController.isRTL) {\n"
                "                params.rightMargin = AndroidUtilities.dp(58);\n"
                "            } else {\n"
                "                params.leftMargin = AndroidUtilities.dp(58);\n"
                "            }\n"
                "        }\n"
                "    }\n\n"
                "    public void setEnabled(boolean value, ArrayList<Animator> animators) {}\n"
                "    public void updateRTL() {\n"
                "        textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);\n"
                "        removeView(textView);\n"
                "        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, padding, 0, padding, 0));\n\n"
                "        valueTextView.setGravity((LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL);\n"
                "        removeView(valueTextView);\n"
                "        addView(valueTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, padding, 0, padding, 0));\n\n"
                "        removeView(imageView);\n"
                "        addView(imageView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 21, 0, 21, 0));\n"
                "    }\n"
                "}\n",
                encoding="utf-8",
            )
            (comps / "AnimatedTextView.java").write_text(
                "public class AnimatedTextView {\n"
                "        if (adaptWidth && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {\n"
                "            width = getPaddingLeft() + (int) Math.ceil(drawable.getWidth()) + getPaddingRight();\n"
                "        }\n"
                "}\n",
                encoding="utf-8",
            )
            multi_block = (
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
                "        textView.setEllipsize(TextUtils.TruncateAt.END);\n"
            )
            (cells / "TextCheckCell.java").write_text(
                "public class TextCheckCell {\n"
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
                "    }\n"
                "    public void setColgramWrapTitle(boolean wrap) {\n"
                "        colgramWrapTitle = wrap;\n"
                "        if (wrap) {\n"
                "            textView.setSingleLine(false);\n"
                "            textView.setLines(0);\n"
                "            textView.setMaxLines(2);\n"
                "            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);\n"
                "            textView.setEllipsize(TextUtils.TruncateAt.END);\n"
                "        }\n"
                "    }\n"
                "        needDivider = divider;\n"
                "        valueTextView.setVisibility(GONE);\n"
                "        } else {\n"
                "            layoutParams.height = LayoutParams.MATCH_PARENT;\n"
                "            layoutParams.gravity = (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP;\n"
                "            layoutParams.topMargin = 0;\n"
                "            layoutParams.bottomMargin = 0;\n"
                "        }\n"
                "        checkBox.setVisibility(View.VISIBLE);\n"
                "        checkBox.setChecked(checked, false);\n"
                "        needDivider = divider;\n"
                "        valueTextView.setVisibility(VISIBLE);\n"
                + multi_block +
                "        checkBox.setVisibility(View.GONE);\n"
                "        needDivider = divider;\n"
                "        valueTextView.setVisibility(VISIBLE);\n"
                + multi_block +
                "                if (imageView != null) {\n"
                "                    canvas.drawLine(LocaleController.isRTL ? 0 : padding, getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? padding : 0), getMeasuredHeight() - 1, dividerPaint);\n"
                "                }\n"
                "}\n",
                encoding="utf-8",
            )
            (cells / "TextDetailSettingsCell.java").write_text(
                "public class TextDetailSettingsCell {\n"
                "        textView.setLines(1);\n"
                "        textView.setMaxLines(1);\n"
                "        textView.setSingleLine(true);\n"
                "        textView.setEllipsize(TextUtils.TruncateAt.END);\n"
                "        valueTextView.setLines(1);\n"
                "        valueTextView.setMaxLines(1);\n"
                "        valueTextView.setSingleLine(true);\n"
                "        valueTextView.setPadding(0, 0, 0, 0);\n"
                "    @Override\n"
                "    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {\n"
                "        if (!multiline) {\n"
                "            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));\n"
                "        } else {\n"
                "            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));\n"
                "        }\n"
                "    }\n"
                "    public void setTextAndValue(CharSequence text, CharSequence value, boolean divider) {\n"
                "        textView.setText(text);\n"
                "        valueTextView.setText(value);\n"
                "        needDivider = divider;\n"
                "        imageView.setVisibility(GONE);\n"
                "        setWillNotDraw(!divider);\n"
                "    }\n"
                "    public void setTextAndValueAndIcon(String text, CharSequence value, int resId, boolean divider) {\n"
                "        textView.setText(text);\n"
                "        valueTextView.setText(value);\n"
                "        imageView.setImageResource(resId);\n"
                "    }\n"
                "    public void setTextWithEmojiAnd21Value(String text, CharSequence value, boolean divider) {\n"
                "        textView.setText(Emoji.replaceEmoji(text, textView.getPaint().getFontMetricsInt(), false));\n"
                "        valueTextView.setText(value);\n"
                "        needDivider = divider;\n"
                "        setWillNotDraw(!divider);\n"
                "    }\n"
                "}\n",
                encoding="utf-8",
            )
            (ui / "SettingsActivity.java").write_text(
                "public class SettingsActivity {\n"
                "        items.add(SettingCell.Factory.of(109, 0xFF7C3AED, 0xFFE11D48, R.drawable.msg_theme, \"X\", \"Y\"));\n"
                "        items.add(SettingCell.Factory.of(101, 0xFFFF3344, 0xFFCC1122, R.drawable.msg_settings, \"S\", \"D\"));\n"
                "            case 109:\n"
                "                presentSettingFragment(new ColgramDesignStudioActivity());\n"
                "                break;\n"
                "            titleView = new TextView(context);\n"
                "            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);\n"
                "            textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));\n\n"
                "            subtitleView = new TextView(context);\n"
                "            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);\n"
                "            textLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));\n"
                "        @Override\n"
                "        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {\n"
                "            super.onMeasure(\n"
                "                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),\n"
                "                MeasureSpec.makeMeasureSpec(dp(mini ? 44 : twoLines ? 60 : 50), MeasureSpec.EXACTLY)\n"
                "            );\n"
                "        }\n"
                "}\n",
                encoding="utf-8",
            )
            (comps / "AlertsCreator.java").write_text("public class AlertsCreator {}\n", encoding="utf-8")
            (ui / "LaunchActivity.java").write_text("        setContentView(frameLayout);\n", encoding="utf-8")
            (msg / "MessagesController.java").write_text(
                "    public boolean isChatNoForwards(TLRPC.Chat chat) {\n        if (chat == null) {\n            return false;\n        }\n    }\n"
                "    public boolean isUserNoForwards(TLRPC.UserFull userFull) {\n        if (userFull == null) {\n            return false;\n        }\n    }\n"
                "    public SponsoredMessagesInfo getSponsoredMessages(long dialogId) {\n        SponsoredMessagesInfo info = sponsoredMessages.get(dialogId);\n    }\n",
                encoding="utf-8",
            )
            (adap / "DialogsSearchAdapter.java").write_text(
                "if (query == null || query.length() < 4 || UserConfig.getInstance(currentAccount).isPremium() && MessagesController.getInstance(currentAccount).isSponsoredDisabled()) {}\n",
                encoding="utf-8",
            )
            (ui / "ChatActivity.java").write_text(
                "    private AnimatedTextView searchOtherButton;\n"
                "    public final static int OPTION_COPY = 3;\n"
                "        if (starReactionsOverlay != null) {\n"
                "            starReactionsOverlay.setMessageCell(null);\n"
                "            AndroidUtilities.removeFromParent(starReactionsOverlay);\n"
                "            starReactionsOverlay = null;\n"
                "        }\n"
                "    }\n"
                "        contentView.addView(topPanelLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));\n\n"
                "        contentView.addView(actionBar);\n"
                "    private void forwardMessages(ArrayList<MessageObject> arrayList, boolean fromMyName, boolean hideCaption, boolean notify, int scheduleDate, long payStars) {\n"
                "        if (arrayList == null || arrayList.isEmpty()) {\n"
                "            return;\n"
                "        }\n"
                "        MessageObject obj = chatAdapter != null && chatAdapter.isFiltered ? filteredMessagesDict.get(mid) :  messagesDict[loadIndex].get(mid);\n"
                "        if (chatAdapter.isFiltered && !messageObject.hasReaction(searchingReaction)) {\n"
                "        if (sponsoredMessagesAdded || chatMode != 0 || !ChatObject.isChannel(currentChat) && !UserObject.isBot(currentUser) || !forwardEndReached[0] || getUserConfig().isPremium() && getMessagesController().isSponsoredDisabled() || isReport()) {\n"
                "                if (canForward) {\n"
                "                    items.add(LocaleController.getString(R.string.Forward));\n"
                "                    options.add(OPTION_FORWARD);\n"
                "                    icons.add(R.drawable.msg_forward);\n"
                "                }\n"
                "            case OPTION_FORWARD: {\n"
                "        @Override\n"
                "        public boolean canPerformActions() {\n"
                "            String reactionString = getMediaDataController().getDoubleTapReaction();\n"
                '            if (reactionString.startsWith("animated_")) {\n',
                encoding="utf-8",
            )
            (cells / "ChatMessageCell.java").write_text(
                "        default void didPressBoostCounter(ChatMessageCell cell) {\n        }\n"
                "        if (checkReactionsTouchEvent(event)) {\n            return true;\n        }\n"
                "        additionalPaddingHeight = Math.max(0, resultHeight - normHeight);\n\n        setMeasuredDimension(\n"
                "    public int getAdditionalPaddingHeight() {\n        return additionalPaddingHeight;\n    }\n"
                "        updateSelectionTextPosition();\n\n        if (restoreToSponosoredAlpha != -1) {\n",
                encoding="utf-8",
            )
            (comps / "TranscribeButton.java").write_text(
                "            if ((premium || canTranscribeTrial(parent.getMessageObject())) && parent.getMessageObject().isSent()) {\n"
                "            if (!premium && toOpen) {\n"
                "        if (UserConfig.getInstance(messageObject.currentAccount).isPremium()) {\n"
                "            } else {\n"
                "                if (BuildVars.LOGS_ENABLED) {\n"
                '                    FileLog.d("sending Transcription request, msg_id=" + messageId + " dialog_id=" + dialogId);\n'
                "                }\n"
                "                    } else {\n"
                "                        if (err != null && err.text != null) {\n"
                '                            if (err.text.startsWith("FLOOD_WAIT_")) {\n'
                "    public static boolean finishTranscription(MessageObject messageObject, long transcription_id, String text) {}\n",
                encoding="utf-8",
            )
            (msg / "TranslateController.java").write_text(
                "    public boolean isFeatureAvailable() {\n"
                "        return isChatTranslateEnabled() && UserConfig.getInstance(currentAccount).isPremium();\n"
                "    }\n"
                "        return (\n"
                "            UserConfig.getInstance(currentAccount).isPremium() ||\n"
                "            chat != null && chat.autotranslation\n"
                "        );\n"
                "    private Boolean chatTranslateEnabled;\n"
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
                "                    }\n"
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
                "                        }\n",
                encoding="utf-8",
            )
            (ui / "ReactionsDoubleTapManageActivity.java").write_text(
                "                        reactionCell.bind(react, react.reaction.contains(MediaDataController.getInstance(currentAccount).getDoubleTapReaction()), currentAccount);\n",
                encoding="utf-8",
            )
            (comps / "TranslateAlert2.java").write_text(
                'if (err != null && "TRANSLATIONS_DISABLED_ALT".equalsIgnoreCase(err.text)) {\n'
                "    public static void alternativeTranslate(String text, String fromLng, String toLng, Utilities.Callback2<String, Boolean> done) {\n"
                "        if (done == null) return;\n",
                encoding="utf-8",
            )
            (ui / "DialogsActivity.java").write_text(
                "    private int initialDialogsType;\n"
                "        if (initialDialogsType == DIALOGS_TYPE_DEFAULT) {\n            askAboutContacts =\n"
                "        Theme.colgramSyncDrawableTintsIfNeeded();\n"
                "                if (filterTabsView.isLocked(filterTabsView.getCurrentTabId())) {\n"
                "                    filterTabsView.selectFirstTab();\n"
                "                }\n"
                "            }\n"
                "        boolean newVisibility;\n"
                "        if (communityId != 0) {\n"
                "        hasOnlySlefStories = onlySelfStories;\n",
                encoding="utf-8",
            )
            (cells / "AppIconsSelectorCell.java").write_text(
                "        if (MessagesController.getInstance(currentAccount).premiumFeaturesBlocked()) {\n",
                encoding="utf-8",
            )
            (msg / "MediaDataController.java").write_text(
                "    public String getDoubleTapReaction() {\n        if (doubleTapReaction != null) {\n",
                encoding="utf-8",
            )

            def _apply_eol(path, old, new, label):
                txt = Path(path).read_text(encoding="utf-8")
                if new in txt:
                    return True
                self.assertIn(old, txt, f"Missing target for {label} in {Path(path).name}")
                Path(path).write_text(txt.replace(old, new, 1), encoding="utf-8")
                return True

            def _noop_patch(path, fn, marker, label):
                txt = Path(path).read_text(encoding="utf-8")
                out = fn(txt)
                if out and out != "CONVERGED" and out != txt:
                    Path(path).write_text(out, encoding="utf-8")
                return True

            upp.apply(str(repo), _noop_patch, _apply_eol, koliti_enabled=False)
            snap1 = {p.name: p.read_text(encoding="utf-8") for p in repo.rglob("*.java")}

            # Verify key Track 3 hotfix invariants in snap1
            self.assertIn("colgramCurDoubleTap != null", snap1["ReactionsDoubleTapManageActivity.java"])
            self.assertIn("if (TextUtils.isEmpty(reactionString))", snap1["ChatActivity.java"])
            self.assertIn("filteredMessagesDict.put(m.getId(), m);", snap1["ChatActivity.java"])
            self.assertIn("chatAdapter.filteredEndReached = true;", snap1["ChatActivity.java"])
            self.assertIn("!colgramSavedTagFiltered && searchingReaction != null", snap1["ChatActivity.java"])
            self.assertIn("fromMyName = true;", snap1["ChatActivity.java"])
            self.assertIn("float maxRight = Math.max(minLeft + dp(24), getMeasuredWidth() - dp(12));", snap1["ChatMessageCell.java"])
            self.assertIn("else if (filterTabsView.hasTab(defFid))", snap1["DialogsActivity.java"])
            self.assertIn("AndroidUtilities.dp(padding)", snap1["TextCheckCell.java"])
            self.assertIn("contrastInkOn(color)", snap1["TextSettingsCell.java"])
            self.assertIn("colgramResetImageViewStyle()", snap1["TextDetailSettingsCell.java"])
            self.assertIn("isLocalVoiceTranscribeEnabled() || canTranscribeTrial", snap1["TranscribeButton.java"])
            self.assertIn("if (messageObject.isRoundVideo())", snap1["TranscribeButton.java"])
            self.assertIn("colgramLastTranslateErrMs", snap1["TranslateController.java"])

            # Pass 2: strictly idempotent
            upp.apply(str(repo), _noop_patch, _apply_eol, koliti_enabled=False)
            snap2 = {p.name: p.read_text(encoding="utf-8") for p in repo.rglob("*.java")}
            self.assertEqual(snap1, snap2)

            # Verify legacy v1 (already-patched 010-consolidated style) upgrades cleanly without duplicating anchors
            with tempfile.TemporaryDirectory() as ltmp:
                lrepo = Path(ltmp) / "Telegram-Src"
                for src in repo.rglob("*.java"):
                    dst = lrepo / src.relative_to(repo)
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    dst.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")
                lui = lrepo / "TMessagesProj/src/main/java/org/telegram/ui"
                lcells = lui / "Cells"
                (lui / "ChatActivity.java").write_text(
                    "    private AnimatedTextView searchOtherButton;\n"
                    "    // colgram-premium-plus-chat-v1\n"
                    "    private ColgramSavedTagsBarView colgramSavedTagsBar;\n"
                    "    private String colgramActiveSavedTagEmoji;\n"
                    "    private boolean colgramSavedTagFiltered;\n"
                    "    public final static int OPTION_COPY = 3;\n"
                    "    public final static int OPTION_COLGRAM_SAVED_TAG = 9992;\n"
                    "        if (starReactionsOverlay != null) {\n"
                    "            starReactionsOverlay.setMessageCell(null);\n"
                    "            AndroidUtilities.removeFromParent(starReactionsOverlay);\n"
                    "            starReactionsOverlay = null;\n"
                    "        }\n"
                    "    }\n"
                    "            topPanelLayout.addView(colgramSavedTagsBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));\n"
                    "            topPanelLayout.setPriority(colgramSavedTagsBar, 8);\n"
                    "            topPanelLayout.setViewVisible(colgramSavedTagsBar, true, false);\n"
                    "        }\n\n"
                    "        contentView.addView(actionBar);\n"
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
                    "    }\n"
                    "        if (org.colgram.core.ColgramPremiumConfig.isBypassNoForwardsEnabled() && !fromMyName) { fromMyName = true; }\n"
                    "        MessageObject obj = chatAdapter != null && chatAdapter.isFiltered ? filteredMessagesDict.get(mid) :  messagesDict[loadIndex].get(mid);\n"
                    "        if (chatAdapter.isFiltered && !messageObject.hasReaction(searchingReaction)) {\n"
                    "        if (sponsoredMessagesAdded || org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled() || getUserConfig().isPremium()) {}\n"
                    "                    options.add(OPTION_COLGRAM_SAVED_TAG);\n"
                    "            case OPTION_COLGRAM_SAVED_TAG:\n"
                    "                                if (colgramSavedTagsBar != null) colgramSavedTagsBar.rebuild();\n"
                    "                                if (chatListView != null) chatListView.invalidateViews();\n"
                    "        public void didPressColgramSavedTag() {}\n"
                    "            String reactionString = getMediaDataController().getDoubleTapReaction();\n"
                    '            if (reactionString.startsWith("animated_")) {\n',
                    encoding="utf-8",
                )
                (lcells / "ChatMessageCell.java").write_text(
                    "    void didPressColgramSavedTag(ChatMessageCell cell, String emoji) {}\n"
                    "    colgramCheckSavedTagTouch(event);\n"
                    "    // colgram-saved-tag-pills-v1\n"
                    "    private void colgramDrawSavedTagPills(Canvas canvas) {\n"
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
                    "    }\n"
                    "        colgramDrawSavedTagPills(canvas);\n",
                    encoding="utf-8",
                )
                (lui / "DialogsActivity.java").write_text(
                    "    private boolean colgramAppliedDefaultFolder;\n"
                    "        // colgram-premium-dialogs-v1\n"
                    "        ColgramPremiumConfig.isAutoHideArchiveEnabled() && !SharedConfig.archiveHidden;\n"
                    "                if (!colgramAppliedDefaultFolder && folderId == 0 && communityId == 0 && !onlySelect && initialDialogsType == DIALOGS_TYPE_DEFAULT) {\n"
                    "                    colgramAppliedDefaultFolder = true;\n"
                    "                    final int defFid = org.colgram.core.ColgramPremiumConfig.getDefaultStartFolderId();\n"
                    "                    if (defFid > 0) {\n"
                    "                        AndroidUtilities.runOnUIThread(() -> { if (filterTabsView != null) scrollToFolder(defFid); });\n"
                    "                    }\n"
                    "                }\n"
                    "        if (org.colgram.core.ColgramPremiumConfig.isHideStoriesEnabled()) {\n            newVisibility = false;\n",
                    encoding="utf-8",
                )
                upp.apply(str(lrepo), _noop_patch, _apply_eol, koliti_enabled=False)
                lsnap1 = {p.name: p.read_text(encoding="utf-8") for p in lrepo.rglob("*.java")}
                self.assertEqual(lsnap1["ChatActivity.java"].count("private AnimatedTextView searchOtherButton;"), 1)
                self.assertEqual(lsnap1["ChatActivity.java"].count("colgramSavedTagsListener;"), 1)
                self.assertEqual(lsnap1["ChatActivity.java"].count("addListener(colgramSavedTagsListener)"), 1)
                self.assertEqual(lsnap1["ChatMessageCell.java"].count("private void colgramDrawSavedTagPills("), 1)
                self.assertEqual(lsnap1["DialogsActivity.java"].count("else if (filterTabsView.hasTab(defFid))"), 1)
                upp.apply(str(lrepo), _noop_patch, _apply_eol, koliti_enabled=False)
                lsnap2 = {p.name: p.read_text(encoding="utf-8") for p in lrepo.rglob("*.java")}
                self.assertEqual(lsnap1, lsnap2)

            # Verify templates have ThemeDescription & DPI recheck wiring
            settings_tpl = (TEMPLATES / "ColgramSettingsActivity.java").read_text(encoding="utf-8")
            self.assertIn("ColgramProxyManager.recheckBypassListener();", settings_tpl)
            self.assertIn("|| position == dpiBypassRow;", settings_tpl)
            files_tpl = (TEMPLATES / "ColgramFilesActivity.java").read_text(encoding="utf-8")
            self.assertIn("boolean curOn = ColgramConfig.isUnlimitedMemoryEnabled() && !ColgramShieldConfig.isStorageCapEnabled();", files_tpl)
            tempmail_tpl = (TEMPLATES / "ColgramTempMailActivity.java").read_text(encoding="utf-8")
            self.assertIn("private boolean executeBg(Runnable task)", tempmail_tpl)


if __name__ == "__main__":
    unittest.main()
