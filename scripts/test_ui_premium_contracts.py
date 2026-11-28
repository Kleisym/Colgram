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
        ):
            self.assertIn(token, uikit)
        self.assertNotIn("showEmojiTagPickerSheet(", uikit)
        self.assertNotIn("ColgramSavedTagsStore", uikit)
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

        # Native Saved Tags Bridge, Read Receipts Helper & Premium Activity register cleanly; legacy bar deleted
        self.assertFalse((TEMPLATES / "ColgramSavedTagsBarView.java").exists())
        ap = (SCRIPTS / "apply-patches.py").read_text(encoding="utf-8")
        self.assertGreaterEqual(ap.count('"ColgramPremiumActivity.java"'), 2)
        self.assertGreaterEqual(ap.count('"ColgramNativeTagsBridge.java"'), 2)
        self.assertGreaterEqual(ap.count('"ColgramReadReceiptsHelper.java"'), 2)
        self.assertEqual(ap.count("ColgramSavedTagsBarView.java"), 0)
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
                "    public SponsoredMessagesInfo getSponsoredMessages(long dialogId) {\n        SponsoredMessagesInfo info = sponsoredMessages.get(dialogId);\n    }\n"
                "        for (int a = 0; a < messagesRes.messages.size(); a++) {\n"
                "            final TLRPC.Message message = messagesRes.messages.get(a);\n"
                "            message.dialog_id = dialogId;\n"
                "        }\n"
                "    public String getSavedTagName(ReactionsLayoutInBubble.VisibleReaction reaction) {\n"
                "        if (reactionTags == null) {\n"
                "            return null;\n"
                "        }\n"
                "        return null;\n"
                "    }\n"
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
                "    }\n"
                "    public String getSavedTagName(TLRPC.Reaction reaction) {\n"
                "        if (reactionTags == null) {\n"
                "            return null;\n"
                "        }\n"
                "        return null;\n"
                "    }\n"
                "    public void renameSavedReactionTag(ReactionsLayoutInBubble.VisibleReaction reaction, String name) {\n"
                "        if (reactionTags == null) {\n"
                "            return;\n"
                "        }\n"
                "        if (changed) {\n"
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
                "            getConnectionsManager().sendRequest(req, null);\n"
                "        }\n"
                "    }\n"
                "    public TLRPC.TL_messages_savedReactionsTags getSavedReactionTags(long topic_id, boolean force) {\n"
                "        if (loadingReactionTags != null && loadingReactionTags.contains(topic_id) && !force) {\n"
                "            if (reactionTags == null) return null;\n"
                "            return reactionTags.get(topic_id);\n"
                "        }\n"
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
                "                }));\n"
                "    }\n",
                encoding="utf-8",
            )
            (adap / "DialogsSearchAdapter.java").write_text(
                "if (query == null || query.length() < 4 || UserConfig.getInstance(currentAccount).isPremium() && MessagesController.getInstance(currentAccount).isSponsoredDisabled()) {}\n",
                encoding="utf-8",
            )
            _chat_native_tag_gates = (
                "\tprivate boolean isSupportedTags() {\n"
                "\t\treturn getUserConfig().getClientUserId() == getDialogId() && !getMessagesController().getSavedMessagesController().unsupported && getUserConfig().isPremium();\n"
                "\t}\n"
                "            if (getUserConfig().isPremium()) {\n"
                "                savedMessagesTagHint = new HintView2(context, HintView2.DIRECTION_BOTTOM);\n"
                "            }\n"
                "        actionMode.setItemVisibility(tag_message, getUserConfig().isPremium() ? View.VISIBLE : View.GONE);\n"
                "        if (getDialogId() != getUserConfig().getClientUserId() || !getUserConfig().isPremium()) return;\n"
                "                if (getDialogId() == getUserConfig().getClientUserId() && !getUserConfig().isPremium()) {\n"
                "                }\n"
                "                    tagItem.setVisibility(getUserConfig().isPremium() && (canTag));\n"
                "                if (tags) {\n"
                "                    reactionsLayout.setHint(getUserConfig().isPremium() ? LocaleController.getString(R.string.SavedTagReactionsHint2) : AndroidUtilities.replaceSingleTag(\"a\", () -> {}));\n"
                "                }\n"
                "                if (isReactionsAvailable && (!tags || !getMessagesController().premiumFeaturesBlocked())) {\n"
                "                }\n"
                "        if (SharedConfig.multipleReactionsPromoShowed || cell == null || visibleReaction == null || getUserConfig().isPremium()) {\n"
                "        }\n"
                "        if (getDialogId() == getUserConfig().getClientUserId() && !getUserConfig().isPremium() && primaryMessage.messageOwner != null && (primaryMessage.messageOwner.reactions == null || (primaryMessage.messageOwner.reactions.reactions_as_tags || primaryMessage.messageOwner.reactions.results.isEmpty()))) {\n"
                "        }\n"
                "        if (getUserConfig().getClientUserId() == getDialogId() && messageObject.areTags() && !getUserConfig().isPremium()) {\n"
                "        }\n"
                "                if (getUserConfig().isPremium()) {\n"
                "                    ActionBarMenuSubItem editTag = new ActionBarMenuSubItem(getParentActivity(), false, false);\n"
                "                }\n"
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
                "                        if (chatAdapter.isFiltered && !messageObject.hasReaction(searchingReaction)) {\n"
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
                '            if (reactionString.startsWith("animated_")) {\n'
                + _chat_native_tag_gates,
                encoding="utf-8",
            )
            (comps / "SearchTagsList.java").write_text(
                "            if (!UserConfig.getInstance(currentAccount).isPremium()) {\n"
                "                new PremiumFeatureBottomSheet(fragment, PremiumPreviewFragment.PREMIUM_FEATURE_SAVED_TAGS, true).show();\n"
                "                return;\n"
                "            }\n"
                "            if (position < 0 || position >= items.size() || !UserConfig.getInstance(currentAccount).isPremium())\n"
                "                return false;\n"
                "            if (!UserConfig.getInstance(currentAccount).isPremium()) {\n"
                "                new PremiumFeatureBottomSheet(fragment, PremiumPreviewFragment.PREMIUM_FEATURE_SAVED_TAGS, true).show();\n"
                "                return true;\n"
                "            }\n"
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
                "        }\n",
                encoding="utf-8",
            )
            (comps / "ReactionsContainerLayout.java").write_text(
                "        if (type == TYPE_TAGS) {\n"
                "            allReactionsAvailable = UserConfig.getInstance(currentAccount).isPremium();\n"
                "            fillRecentReactionsList(visibleReactions);\n"
                "        }\n"
                "        if (type == TYPE_TAGS && !UserConfig.getInstance(currentAccount).isPremium()) {\n"
                "            showExpandableReactions = false;\n"
                "        }\n"
                "        if (type != TYPE_TAGS || UserConfig.getInstance(currentAccount).isPremium()) {\n"
                "        }\n"
                "        if (UserConfig.getInstance(currentAccount).isPremium()) {\n"
                "            return;\n"
                "        }\n"
                "        try {\n"
                "            for (int i = 0; i < reactions.size(); i++) {\n"
                "                if (reactions.get(i).premium) {\n"
                "                }\n"
                "            }\n"
                "        } catch (Exception ignored) {}\n"
                "            final boolean userIsPremium = UserConfig.getInstance(currentAccount).isPremium();\n"
                "            isLocked = type == TYPE_TAGS && !userIsPremium || type == TYPE_MESSAGE_EFFECTS && react.premium && !userIsPremium;\n",
                encoding="utf-8",
            )
            (comps / "SharedMediaLayout.java").write_text(
                "                        searchItemIcon.setIcon(hasFilters() && profileActivity.getUserConfig().isPremium() ? R.drawable.navbar_search_tag : R.drawable.outline_header_search, notify);\n"
                "                    updateMessages(true);\n"
                "                    AndroidUtilities.runOnUIThread(request, 540);\n"
                "                }, false);\n",
                encoding="utf-8",
            )
            (msg / "MessageObject.java").write_text(
                "        int maxReactionsCount = MessagesController.getInstance(currentAccount).getMaxUserReactionsCount();\n",
                encoding="utf-8",
            )
            (msg / "MessagesStorage.java").write_text(
                "                        res.messages.add(message);\n\n"
                "                        addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, animatedEmojiToLoad);\n"
                "                    for (int a = 0; a < count; a++) {\n"
                "                        TLRPC.Message message = messages.messages.get(a);\n"
                "                        if (lastMessageId == null && message != null || lastMessageId != null && lastMessageId < message.id) {\n"
                "                        }\n"
                "                    }\n"
                "            if (getUserConfig().getClientUserId() == dialogId) {\n"
                '                database.executeFast(String.format(Locale.US, "DELETE FROM tag_message_id WHERE mid IN(%s)", TextUtils.join(",", messages))).stepThis().dispose();\n'
                "            }\n"
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
                "                cursor.dispose();\n"
                "                if (dialogId == selfId && pastReactions != null) {\n"
                "                    onReactionsUpdate(topicId, pastReactions, reactions);\n"
                "                }\n"
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
                "            }\n"
                "        });\n"
                "    }\n",
                encoding="utf-8",
            )
            (msg / "SendMessagesHelper.java").write_text(
                "        TLRPC.User colgramBotSelf = getUserConfig().getCurrentUser();\n"
                "            if (response != null) {\n"
                "                getMessagesController().processUpdates((TLRPC.Updates) response, false);\n"
                "                if (callback != null) {\n"
                "                    AndroidUtilities.runOnUIThread(callback);\n"
                "                }\n"
                '            } else if (error != null && error.text != null && error.text.contains("BOT_METHOD_INVALID")) {\n'
                "            }\n",
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
                "    public String getDoubleTapReaction() {\n        if (doubleTapReaction != null) {\n        }\n    }\n"
                "    public int getSearchCount() {\n"
                "        if (searchServerResultMessages.isEmpty()) {\n"
                "            return Math.max(Math.max(messagesSearchCount[0] + messagesSearchCount[1], messagesLocalSearchCount), searchServerResultMessages.size());\n"
                "        }\n"
                "        return Math.max(messagesSearchCount[0] + messagesSearchCount[1], searchServerResultMessages.size());\n"
                "    }\n"
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
                "        }\n"
                "                            if (searchServerResultMessages.isEmpty()) {\n"
                "                                getNotificationCenter().postNotificationName(NotificationCenter.chatSearchResultsAvailable, guid, 0, getMask(), (long) 0, 0, 0, jumpToMessage);\n"
                "                            }\n",
                encoding="utf-8",
            )

            def _apply_eol(path, old, new, label):
                txt = Path(path).read_text(encoding="utf-8")
                if new and new in txt:
                    return True
                if not new and old not in txt:
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

            # Verify Track 3 & Native Saved Tags Symbiosis invariants in snap1
            self.assertIn("colgramCurDoubleTap != null", snap1["ReactionsDoubleTapManageActivity.java"])
            self.assertIn("if (TextUtils.isEmpty(reactionString))", snap1["ChatActivity.java"])
            self.assertIn("if (chatAdapter.isFiltered && searchingReaction != null && !messageObject.hasReaction(searchingReaction)) {", snap1["ChatActivity.java"])
            self.assertNotIn("ColgramSavedTagsBarView", snap1["ChatActivity.java"])
            self.assertNotIn("colgramDrawSavedTagPills", snap1["ChatMessageCell.java"])
            self.assertIn("fromMyName = true;", snap1["ChatActivity.java"])
            self.assertIn("!getMessagesController().getSavedMessagesController().unsupported && (getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())", snap1["ChatActivity.java"])
            self.assertIn("actionMode.setItemVisibility(tag_message, (getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()) ? View.VISIBLE : View.GONE);", snap1["ChatActivity.java"])
            self.assertIn("shownPremiumLayout = (!UserConfig.getInstance(currentAccount).isPremium() && !org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled())", snap1["SearchTagsList.java"])
            self.assertIn("colgramTagsUnlocked", snap1["ReactionsContainerLayout.java"])
            self.assertIn("profileActivity.getUserConfig().isPremium() || org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled()", snap1["SharedMediaLayout.java"])
            self.assertIn("Math.max(3, MessagesController.getInstance(currentAccount).reactionsUserMaxPremium)", snap1["MessageObject.java"])
            self.assertIn("ColgramNativeTagsBridge.mergeIntoMessage(currentAccount, dialogId, message);", snap1["MessagesStorage.java"])
            self.assertIn("ColgramSavedTagsStore.pruneDeletedMessages(", snap1["MessagesStorage.java"])
            self.assertIn("mergeLocalTagIdsIntoSearch(", snap1["MessagesStorage.java"])
            self.assertIn("ColgramNativeTagsBridge.mergeIntoSavedReactionTags(", snap1["MessagesController.java"])
            self.assertIn("ColgramSavedTagsStore.setTagLabel(", snap1["MessagesController.java"])
            self.assertIn("ColgramNativeTagsBridge.handleLocalSavedReaction(", snap1["SendMessagesHelper.java"])
            self.assertIn("colgramLocalTagOnly", snap1["MediaDataController.java"])
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

            # Verify legacy v1/v2 duplicate Saved Tags code upgrades & strips cleanly without duplicating anchors
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
                    "    private org.colgram.core.ColgramSavedTagsStore.ChangeListener colgramSavedTagsListener;\n"
                    "    public final static int OPTION_COPY = 3;\n"
                    "    public final static int OPTION_COLGRAM_SAVED_TAG = 9992;\n"
                    "        if (colgramSavedTagsListener != null) {\n"
                    "            org.colgram.core.ColgramSavedTagsStore.removeListener(colgramSavedTagsListener);\n"
                    "            colgramSavedTagsListener = null;\n"
                    "        }\n"
                    "        if (starReactionsOverlay != null) {\n"
                    "            starReactionsOverlay.setMessageCell(null);\n"
                    "            AndroidUtilities.removeFromParent(starReactionsOverlay);\n"
                    "            starReactionsOverlay = null;\n"
                    "        }\n"
                    "    }\n"
                    "        contentView.addView(topPanelLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));\n"
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
                    "        if (org.colgram.core.ColgramPremiumConfig.isBypassNoForwardsEnabled() && !fromMyName) { fromMyName = true; }\n"
                    "        MessageObject obj = chatAdapter != null && chatAdapter.isFiltered ? filteredMessagesDict.get(mid) :  messagesDict[loadIndex].get(mid);\n"
                    "                        if (chatAdapter.isFiltered && !colgramSavedTagFiltered && searchingReaction != null && !messageObject.hasReaction(searchingReaction)) {\n"
                    "        if (sponsoredMessagesAdded || org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled() || getUserConfig().isPremium()) {}\n"
                    "                if (dialog_id == getUserConfig().getClientUserId() && org.colgram.core.ColgramPremiumConfig.isSavedTagsEnabled() && selectedObject.getId() > 0 && !selectedObject.isSponsored()) {\n"
                    '                    items.add("Тег (эмодзи)");\n'
                    "                    options.add(OPTION_COLGRAM_SAVED_TAG);\n"
                    "                    icons.add(R.drawable.msg_reactions);\n"
                    "                }\n"
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
                    "        @Override\n"
                    "        public void didPressColgramSavedTag(ChatMessageCell cell, String emoji) {\n"
                    "            if (colgramSavedTagsBar != null && emoji != null && !emoji.isEmpty()) {\n"
                    "                colgramSavedTagsBar.activateEmojiFilter(emoji);\n"
                    "            }\n"
                    "        }\n\n"
                    "            String reactionString = getMediaDataController().getDoubleTapReaction();\n"
                    '            if (reactionString.startsWith("animated_")) {\n'
                    + _chat_native_tag_gates,
                    encoding="utf-8",
                )
                (lcells / "ChatMessageCell.java").write_text(
                    "\n\n        default void didPressColgramSavedTag(ChatMessageCell cell, String emoji) {\n"
                    "        }\n"
                    "        if (colgramCheckSavedTagTouch(event)) {\n"
                    "            return true;\n"
                    "        }\n"
                    "        // colgram-saved-tag-pills-v1\n"
                    "        resultHeight += colgramSavedTagExtraHeight();\n"
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
                self.assertEqual(lsnap1["ChatActivity.java"].count("colgramSavedTagsBar"), 0)
                self.assertEqual(lsnap1["ChatActivity.java"].count("colgramSavedTagsListener"), 0)
                self.assertEqual(lsnap1["ChatActivity.java"].count("colgramApplySavedTagFilter"), 0)
                self.assertEqual(lsnap1["ChatActivity.java"].count("OPTION_COLGRAM_SAVED_TAG"), 0)
                self.assertEqual(lsnap1["ChatActivity.java"].count("didPressColgramSavedTag"), 0)
                self.assertEqual(lsnap1["ChatActivity.java"].count("colgramSavedTagFiltered"), 0)
                self.assertEqual(lsnap1["ChatMessageCell.java"].count("colgramDrawSavedTagPills"), 0)
                self.assertEqual(lsnap1["ChatMessageCell.java"].count("colgramSavedTagExtraHeight"), 0)
                self.assertEqual(lsnap1["ChatMessageCell.java"].count("colgramCheckSavedTagTouch"), 0)
                self.assertEqual(lsnap1["ChatMessageCell.java"].count("didPressColgramSavedTag"), 0)
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
