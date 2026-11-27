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

        tr = (CORE / "ColgramLocalTranslator.java").read_text(encoding="utf-8")
        self.assertIn("pickBrowserUserAgent", tr)
        self.assertIn("pickRelayProxy", tr)

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
                "    public void setIcon() {\n"
                "            if (LocaleController.isRTL) {\n"
                "                params.rightMargin = AndroidUtilities.dp(58);\n"
                "            } else {\n"
                "                params.leftMargin = AndroidUtilities.dp(58);\n"
                "            }\n"
                "        }\n"
                "    }\n\n"
                "    public void setColorfulIcon(int resId, int color) {\n"
                "            if (LocaleController.isRTL) {\n"
                "                params.rightMargin = AndroidUtilities.dp(58);\n"
                "            } else {\n"
                "                params.leftMargin = AndroidUtilities.dp(58);\n"
                "            }\n"
                "        }\n"
                "    }\n\n"
                "    public void setEnabled(boolean value, ArrayList<Animator> animators) {}\n"
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
                + multi_block + multi_block + "}\n",
                encoding="utf-8",
            )
            (cells / "TextDetailSettingsCell.java").write_text(
                "public class TextDetailSettingsCell {\n"
                "        textView.setLines(1);\n"
                "        textView.setMaxLines(1);\n"
                "        textView.setSingleLine(true);\n"
                "        textView.setEllipsize(TextUtils.TruncateAt.END);\n"
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
                "        contentView.addView(topPanelLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));\n\n"
                "        contentView.addView(actionBar);\n"
                "    private void forwardMessages(ArrayList<MessageObject> arrayList, boolean fromMyName, boolean hideCaption, boolean notify, int scheduleDate, long payStars) {\n"
                "        if (arrayList == null || arrayList.isEmpty()) {\n"
                "            return;\n"
                "        }\n"
                "        if (sponsoredMessagesAdded || chatMode != 0 || !ChatObject.isChannel(currentChat) && !UserObject.isBot(currentUser) || !forwardEndReached[0] || getUserConfig().isPremium() && getMessagesController().isSponsoredDisabled() || isReport()) {\n"
                "                if (canForward) {\n"
                "                    items.add(LocaleController.getString(R.string.Forward));\n"
                "                    options.add(OPTION_FORWARD);\n"
                "                    icons.add(R.drawable.msg_forward);\n"
                "                }\n"
                "            case OPTION_FORWARD: {\n"
                "        @Override\n"
                "        public boolean canPerformActions() {\n",
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
                '                    } else if (err != null && "TRANSLATIONS_DISABLED_ALT".equalsIgnoreCase(err.text)) {\n',
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

            # Pass 2: strictly idempotent
            upp.apply(str(repo), _noop_patch, _apply_eol, koliti_enabled=False)
            snap2 = {p.name: p.read_text(encoding="utf-8") for p in repo.rglob("*.java")}
            self.assertEqual(snap1, snap2)


if __name__ == "__main__":
    unittest.main()
