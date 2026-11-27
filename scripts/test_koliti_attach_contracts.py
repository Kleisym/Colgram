import os
import shutil
import sys
import tempfile
import unittest

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPTS_DIR)
if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)

import koliti_split_patches


def _read_text(path: str) -> str:
    with open(path, "r", encoding="utf-8", errors="ignore", newline="") as fh:
        return fh.read().replace("\r\n", "\n")


def _write_text(path: str, content: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(content)


def _make_recording_patch_helpers(misses: list, mutations: list):
    def patch_file(path: str, replacer, expected_substring: str, label: str) -> None:
        if not os.path.isfile(path):
            misses.append(f"MISSING_FILE:{label}:{path}")
            return
        before = _read_text(path)
        after = replacer(before)
        if expected_substring and expected_substring not in after:
            misses.append(f"MISS_SUBSTR:{label}")
            return
        if after != before:
            mutations.append((label, path))
            _write_text(path, after)

    def eol_patch(path: str, target: str, replacement: str, label: str) -> None:
        if not os.path.isfile(path):
            misses.append(f"MISSING_FILE:{label}:{path}")
            return
        before = _read_text(path)
        t_norm = target.replace("\r\n", "\n")
        r_norm = replacement.replace("\r\n", "\n")
        if r_norm in before:
            return
        if t_norm not in before:
            misses.append(f"MISS_ANCHOR:{label}")
            return
        after = before.replace(t_norm, r_norm, 1)
        if after != before:
            mutations.append((label, path))
            _write_text(path, after)

    return patch_file, eol_patch


class KolitiAttachContractsTest(unittest.TestCase):
    def _t(self, rel: str) -> str:
        return _read_text(os.path.join(REPO_ROOT, *rel.split("/")))

    def test_sheet_and_activity_attach_contracts(self):
        sheet = self._t("scripts/templates/ColgramAiSheet.java")
        act = self._t("scripts/templates/ColgramAiActivity.java")

        self.assertNotIn("AlertDialog.Builder", sheet)
        self.assertNotIn("AlertDialog.Builder", act)

        for marker in (
            "this.sheet.colgramKeepOnPause = true;",
            "public static ColgramAiSheet showWithAttachment(",
            "public static boolean handleActivityResult(",
            "public static boolean handlePermissionResult(",
            "MediaStore.ACTION_PICK_IMAGES",
            "MediaStore.EXTRA_PICK_IMAGES_MAX",
            "Intent.ACTION_OPEN_DOCUMENT",
            "Intent.FLAG_GRANT_READ_URI_PERMISSION",
            "FileProvider.getUriForFile",
            "ClipData.newRawUri",
            "ViewCompat.setOnReceiveContentListener",
            "cr.openAssetFileDescriptor(uri, \"r\")",
            "cr.openFileDescriptor(uri, \"r\")",
            "new java.util.concurrent.CopyOnWriteArrayList<>()",
            "pruneOldKolitiMedia(mediaDir, 250, activeProtected)",
            "pruneOldKolitiMedia(mediaDir, 250, camProtected)",
            "parentFragment != null && parentFragment.getParentLayout() != null",
            "int scanLimit = Math.min(copy.size(), 80);",
            "buildDefaultAttachmentPrompt(attachedPaths)",
            "ColgramAiActivity.REQ_PERM_MIC_KOLITI",
            "ColgramAiActivity.REQ_PERM_CAMERA_KOLITI",
            "ColgramAiActivity.REQ_CAPTURE_CAMERA_FROM_SHEET",
            "ColgramAiActivity.REQ_PICK_FILE_FROM_SHEET",
            "ColgramAiActivity.pendingSheetForVoice = this;",
            "ColgramAiActivity.pendingSheetForCameraPerm = this;",
        ):
            self.assertIn(marker, sheet, f"Missing marker in ColgramAiSheet.java: {marker}")

        for marker in (
            "REQ_CAPTURE_CAMERA_FROM_SHEET = 6406",
            "REQ_PICK_FILE_FROM_SHEET = 6407",
            "REQ_PERM_MIC_KOLITI = 6410",
            "REQ_PERM_CAMERA_KOLITI = 6412",
            "public static ColgramAiSheet pendingSheetForCameraPerm;",
            "public static String pendingCameraOutputPath;",
            "ColgramAiSheet.handleActivityResult(act, requestCode, resultCode, data)",
            "ColgramAiSheet.handlePermissionResult(act, requestCode, permissions, grantResults)",
            "openAssetFileDescriptor(uri, \"r\")",
        ):
            self.assertIn(marker, act, f"Missing marker in ColgramAiActivity.java: {marker}")

    def test_core_vision_doc_and_context_contracts(self):
        vp = self._t("colgram-core/src/main/java/org/colgram/core/ColgramAiVisionProcessor.java")
        mm = self._t("colgram-core/src/main/java/org/colgram/core/ColgramAiModelManager.java")
        ce = self._t("colgram-core/src/main/java/org/colgram/core/ColgramAiContextEngine.java")
        eng = self._t("colgram-core/src/main/java/org/colgram/core/ColgramAiEngine.java")

        for marker in (
            "enum AttachmentKind",
            "class PreparedAttachment",
            "ImageDecoder.ALLOCATOR_SOFTWARE",
            "PdfRenderer",
            "ZipInputStream",
            "\"word/document.xml\"",
            "\"content.xml\"",
            "exifFlipAndRotate(int orientation)",
            "prepareAttachment(Context ctx, String sourcePath)",
        ):
            self.assertIn(marker, vp, f"Missing marker in ColgramAiVisionProcessor.java: {marker}")

        for marker in (
            "hasEmbeddedVisionAsset(Context ctx)",
            "needsEmbeddedVisionExtract(Context ctx)",
            "\"koliti_vision.gguf\"",
        ):
            self.assertIn(marker, mm, f"Missing marker in ColgramAiModelManager.java: {marker}")

        self.assertIn(
            "boolean useActiveChat = hasActiveChat && (strictlyAboutThisChat || draft || (vision && !hasAttachedMedia));",
            ce,
        )
        self.assertIn("sanitizeMediaMarker(String s)", ce)
        self.assertIn("### Прикреплённые документы:", ce)

        self.assertIn("ColgramAiModelManager.hasEmbeddedVisionAsset(ctx)", eng)
        self.assertIn("public static int computeVisionContextBudget(", eng)
        self.assertIn("ColgramAiVisionProcessor.prepareAttachment(", eng)
        # Never feed a fake text-only placeholder claiming an image was attached when Vision is missing.
        self.assertNotIn("### Вложение: изображение", eng)

    def _build_fixture_repo(self, tmp: str, variant: str) -> None:
        # variant in {"pre_gate", "post_gate"}
        for name in (
            "ColgramAiActivity.java",
            "ColgramAiSheet.java",
            "ColgramAiOrbView.java",
            "ColgramAiChatBridge.java",
            "ColgramGlobalOrbController.java",
        ):
            _write_text(
                os.path.join(tmp, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", name),
                f"// {name}\n",
            )
        for fn in koliti_split_patches._KOLITI_CORE_CLASS_FILES:
            _write_text(
                os.path.join(tmp, "colgram-core", "src", "main", "java", "org", "colgram", "core", fn),
                f"package org.colgram.core;\npublic class {fn[:-5]} {{}}\n",
            )

        _write_text(
            os.path.join(tmp, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ActionBar", "BottomSheet.java"),
            "package org.telegram.ui.ActionBar;\npublic class BottomSheet {\n"
            "    public boolean useBackgroundTopPadding = true;\n"
            "}\n",
        )
        _write_text(
            os.path.join(tmp, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ActionBar", "BaseFragment.java"),
            "package org.telegram.ui.ActionBar;\npublic class BaseFragment {\n"
            "    public void onFragmentDestroy() {\n"
            "        isFinished = true;\n"
            "        if (actionBar != null) {}\n"
            "    }\n"
            "    public boolean dismissDialogOnPause(Dialog dialog) {\n"
            "        return true;\n"
            "    }\n"
            "}\n",
        )
        _write_text(
            os.path.join(tmp, "TMessagesProj", "src", "main", "res", "xml", "provider_paths.xml"),
            '<?xml version="1.0" encoding="utf-8"?>\n<paths>\n'
            '    <files-path name="media_history" path="/media_history/"/>\n</paths>\n',
        )

        _write_text(
            os.path.join(tmp, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "ApplicationLoader.java"),
            "package org.telegram.messenger;\npublic class ApplicationLoader {\n"
            "    void onTrimMemory() {\n"
            "                ImageLoader.getInstance().clearMemory();\n"
            "                org.colgram.core.ColgramAiEngine.getInstance().unloadIfIdleInBackground();\n"
            "                org.colgram.core.ColgramUnlimitedMemory.trimTransientMemoryIfNeeded(this);\n"
            "    }\n}\n",
        )
        _write_text(
            os.path.join(tmp, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "DialogsActivity.java"),
            "package org.telegram.ui;\npublic class DialogsActivity {\n    void buildMenu() {\n"
            "        org.telegram.ui.ActionBar.ActionBarMenuSubItem colgramSub;\n"
            '        io.add(R.drawable.msg_bot, "Koliti AI", () -> {\n'
            "            presentFragment(new ColgramAiActivity());\n"
            "        });\n"
            "        if ((colgramSub = io.getLast()) != null) colgramSub.setIconColor(0xFFE11D48);\n"
            "    }\n}\n",
        )
        _write_text(
            os.path.join(tmp, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "SettingsActivity.java"),
            "package org.telegram.ui;\npublic class SettingsActivity {\n    void fillItems() {\n"
            '        items.add(SettingCell.Factory.of(108, 0xFFE11D48, 0xFF9F1239, R.drawable.msg_bot, "Koliti AI", "Встроено офлайн • Vision • Сон 0% нагрузки"));\n'
            '        items.add(SettingCell.Factory.of(109, 0xFF7C3AED, 0xFFE11D48, R.drawable.msg_theme, "Конструктор дизайна", "Цвета, расположение, размер и аватар Koliti"));\n'
            "    }\n    void onClick(int id) {\n        switch (id) {\n"
            "            case 108:\n"
            "                presentSettingFragment(new ColgramAiActivity());\n"
            "                break;\n"
            "        }\n    }\n}\n",
        )

        gate_prefix = "org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED && " if variant == "post_gate" else ""
        launch_src = (
            "package org.telegram.ui;\n"
            "public class LaunchActivity {\n"
            "    void init() {\n"
            "        actionBarLayout.setDelegate(this);\n"
            "        ColgramGlobalOrbController.attach(this, frameLayout);\n"
            "        Theme.loadWallpaper(true);\n"
            "    }\n"
            "    boolean handleDeepLink(String s) {\n"
            "        switch (s) {\n"
            '            case "ai":\n'
            "                presentFragment(new ColgramAiActivity());\n"
            "                return true;\n"
            "        }\n"
            "        return false;\n"
            "    }\n"
            "    protected void onActivityResult(int requestCode, int resultCode, Intent data) {\n"
            "        // COLGRAM_AI_MEDIA_PICKER\n"
            f"        if ({gate_prefix}requestCode == ColgramAiActivity.REQ_PICK_MEDIA_FROM_SHEET) {{\n"
            "            super.onActivityResult(requestCode, resultCode, data);\n"
            "            ColgramAiSheet pending = ColgramAiActivity.pendingSheetForMedia;\n"
            "            ColgramAiActivity.pendingSheetForMedia = null;\n"
            "            if (resultCode == Activity.RESULT_OK && data != null && pending != null) {\n"
            "                if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {\n"
            "                    int count = Math.min(4, data.getClipData().getItemCount());\n"
            "                    for (int i = 0; i < count; i++) {\n"
            "                        Uri u = data.getClipData().getItemAt(i).getUri();\n"
            "                        if (u != null) {\n"
            "                            pending.attachExternalMediaUri(this, u);\n"
            "                        }\n"
            "                    }\n"
            "                } else if (data.getData() != null) {\n"
            "                    pending.attachExternalMediaUri(this, data.getData());\n"
            "                }\n"
            "            }\n"
            "            return;\n"
            "        }\n"
            f"        if ({gate_prefix}requestCode == ColgramAiActivity.REQ_VOICE_RECOGNIZE) {{\n"
            "            super.onActivityResult(requestCode, resultCode, data);\n"
            "            ColgramAiSheet pending = ColgramAiActivity.pendingSheetForVoice;\n"
            "            ColgramAiActivity.pendingSheetForVoice = null;\n"
            "            if (resultCode == Activity.RESULT_OK && data != null && pending != null) {\n"
            "                java.util.ArrayList<String> matches = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);\n"
            '                if (matches != null && !matches.isEmpty() && !android.text.TextUtils.isEmpty(matches.get(0))) {\n'
            "                    pending.onVoiceRecognizedResult(matches.get(0));\n"
            "                }\n"
            "            }\n"
            "            return;\n"
            "        }\n"
            "    }\n"
            "    @Override\n"
            "    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {\n"
            "        super.onRequestPermissionsResult(requestCode, permissions, grantResults);\n"
            "        if (!checkPermissionsResult(requestCode, permissions, grantResults)) return;\n"
            "    }\n"
            "}\n"
        )
        # Re-indent case "ai" to match exact _GATE_PATCH_SPECS indentation (16 spaces).
        launch_src = launch_src.replace(
            '            case "ai":\n                presentFragment(new ColgramAiActivity());\n                return true;',
            '                case "ai":\n                    presentFragment(new ColgramAiActivity());\n                    return true;',
        )
        _write_text(
            os.path.join(tmp, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "LaunchActivity.java"),
            launch_src,
        )

        video_wallpaper_branch = (
            "            if (requestCode == COLGRAM_REQ_PICK_VIDEO_WALLPAPER && data != null && getParentActivity() != null) {\n"
            "                return;\n"
            "            }\n"
            if variant == "video_wallpaper"
            else ""
        )
        legacy_chat_branch = (
            ""
            if variant == "clean_upstream"
            else (
                f"            if ({gate_prefix}requestCode == ColgramAiActivity.REQ_PICK_MEDIA_FROM_SHEET && data != null && data.getData() != null) {{\n"
                "                if (ColgramAiActivity.pendingSheetForMedia != null) {\n"
                "                    ColgramAiActivity.pendingSheetForMedia.attachExternalMediaUri(getParentActivity(), data.getData());\n"
                "                    ColgramAiActivity.pendingSheetForMedia = null;\n"
                "                }\n"
                "                return;\n"
                "            }\n"
            )
        )
        chat_src = (
            "package org.telegram.ui;\n"
            "public class ChatActivity {\n"
            "    void build() {\n"
            "        colgramAiOrb = new ColgramAiOrbView(context);\n"
            "        boolean useLocalOrb = ColgramGlobalOrbController.getInstance() == null;\n"
            "        colgramAiOrb.setVisibility(useLocalOrb && org.colgram.core.ColgramAiConfig.isOrbEnabled(context) && !inPreviewMode && !isInsideContainer ? View.VISIBLE : View.GONE);\n"
            "        colgramAiOrb.setOnOrbClickListener(() -> ColgramAiSheet.show(ChatActivity.this, ChatActivity.this, colgramAiOrb));\n"
            "        contentView.addView(colgramAiOrb, LayoutHelper.createFrame(54, 54, Gravity.LEFT | Gravity.BOTTOM, 10, 0, 0, 0));\n"
            "            float orbClearance = (!inPreviewMode && !isInsideContainer\n"
            "                    && org.colgram.core.ColgramAiConfig.isOrbEnabled(ApplicationLoader.applicationContext)) ? dp(54) : 0;\n"
            "                } else if (currentUser != null && currentUser.self && currentUser.bot) {\n"
            '                    bigEmptyView.setStatusText("Локальные заметки Colgram\\nПишите заметки сюда или спросите Koliti");\n'
            "                }\n"
            "    }\n"
            "    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {\n"
            "        if (resultCode == Activity.RESULT_OK) {\n"
            f"{video_wallpaper_branch}"
            f"{legacy_chat_branch}"
            "        }\n"
            "    }\n"
            "    void fillMessageMenu() {\n"
            '        if (selectedObject != null && selectedObject.getId() > 0) {\n'
            '            items.add("Удалить только у себя");\n'
            '            options.add(9990);\n'
            '            icons.add(R.drawable.msg_delete);\n'
            '        }\n'
            "        final MessageObject message = selectedObject;\n"
            "        final MessageObject.GroupedMessages groupedMessages = selectedObjectGroup;\n"
            "    }\n"
            "    private void processSelectedOption(int option) {\n"
            "        if (selectedObject == null || getParentActivity() == null) {\n"
            "            return;\n"
            "        }\n"
            "        if (option == 9988) {\n"
            "            return;\n"
            "        }\n"
            "    }\n"
            "}\n"
        )
        _write_text(
            os.path.join(tmp, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ChatActivity.java"),
            chat_src,
        )

    def _run_convergence_matrix(self, variant: str, koliti_enabled: bool):
        tmp = tempfile.mkdtemp(prefix=f"koliti_attach_{variant}_{int(koliti_enabled)}_")
        try:
            self._build_fixture_repo(tmp, variant)
            misses1, muts1 = [], []
            p1, e1 = _make_recording_patch_helpers(misses1, muts1)
            koliti_split_patches.apply(tmp, p1, e1, koliti_enabled=koliti_enabled)
            self.assertEqual(misses1, [], f"Pass-1 misses ({variant}, koliti={koliti_enabled}): {misses1}")
            self.assertGreater(len(muts1), 0)

            misses2, muts2 = [], []
            p2, e2 = _make_recording_patch_helpers(misses2, muts2)
            koliti_split_patches.apply(tmp, p2, e2, koliti_enabled=koliti_enabled)
            self.assertEqual(misses2, [], f"Pass-2 misses ({variant}, koliti={koliti_enabled}): {misses2}")
            self.assertEqual(muts2, [], f"Pass-2 should be idempotent ({variant}, koliti={koliti_enabled}): {muts2}")

            bottom_sheet = _read_text(
                os.path.join(tmp, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ActionBar", "BottomSheet.java")
            )
            base_frag = _read_text(
                os.path.join(tmp, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ActionBar", "BaseFragment.java")
            )
            launch = _read_text(
                os.path.join(tmp, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "LaunchActivity.java")
            )
            chat = _read_text(
                os.path.join(tmp, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ChatActivity.java")
            )
            prov = _read_text(
                os.path.join(tmp, "TMessagesProj", "src", "main", "res", "xml", "provider_paths.xml")
            )

            self.assertIn("public boolean colgramKeepOnPause = false;", bottom_sheet)
            self.assertIn("((BottomSheet) dialog).colgramKeepOnPause", base_frag)
            self.assertIn("((BottomSheet) visibleDialog).colgramKeepOnPause", base_frag)
            self.assertIn("ColgramAiSheet.handleActivityResult(this, requestCode, resultCode, data);", launch)
            self.assertIn("ColgramAiSheet.handlePermissionResult(this, requestCode, permissions, grantResults)", launch)
            self.assertIn("ColgramAiSheet.handleActivityResult(getParentActivity(), requestCode, resultCode, data)", chat)
            self.assertNotIn("pendingSheetForMedia.attachExternalMediaUri", chat)
            # Preserves REQ_PICK_MEDIA_FROM_SHEET marker so apply-patches.py won't re-inject legacy branch
            self.assertIn("ColgramAiActivity.REQ_PICK_MEDIA_FROM_SHEET", chat)
            self.assertIn("options.add(9991);", chat)
            self.assertIn("if (org.colgram.core.ColgramBuildFlags.KOLITI_ENABLED && option == 9991)", chat)
            self.assertIn("ColgramAiSheet.showWithAttachment(", chat)
            self.assertIn('name="koliti_media" path="/koliti_media/"', prov)

            noop_sheet = _read_text(
                os.path.join(tmp, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ColgramAiSheet.java")
            )
            if koliti_enabled:
                self.assertIn("// ColgramAiSheet.java", noop_sheet)
            else:
                self.assertIn("showWithAttachment(", noop_sheet)
                self.assertIn("handleActivityResult(", noop_sheet)
                self.assertIn("handlePermissionResult(", noop_sheet)
                core_dir = os.path.join(tmp, "colgram-core", "src", "main", "java", "org", "colgram", "core")
                self.assertEqual(
                    os.listdir(core_dir),
                    ["ColgramBuildFlags.java"],
                    "Main APK must strip all heavy Koliti core classes while keeping ColgramBuildFlags",
                )
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_split_patches_2pass_convergence_all_modes(self):
        for variant in ("pre_gate", "post_gate", "video_wallpaper", "clean_upstream"):
            for enabled in (True, False):
                self._run_convergence_matrix(variant, enabled)

    def test_live_telegram_src_anchors_and_menu_ids(self):
        bottom_sheet = self._t("Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/BottomSheet.java")
        base_frag = self._t("Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/BaseFragment.java")
        launch = self._t("Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java")
        chat = self._t("Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
        prov = self._t("Telegram-Src/TMessagesProj/src/main/res/xml/provider_paths.xml")

        # Live Telegram-Src may be unpatched or already converged by koliti_split_patches.
        self.assertTrue(
            "public boolean colgramKeepOnPause = false;" in bottom_sheet
            or "public boolean useBackgroundTopPadding = true;" in bottom_sheet
        )
        self.assertTrue(
            "((BottomSheet) dialog).colgramKeepOnPause" in base_frag
            or "dismissDialogOnPause(Dialog dialog)" in base_frag
        )
        self.assertTrue(
            "((BottomSheet) visibleDialog).colgramKeepOnPause" in base_frag
            or "isFinished = true;\n        if (actionBar != null) {" in base_frag
        )
        self.assertTrue(
            "ColgramAiSheet.handleActivityResult(this, requestCode, resultCode, data);" in launch
            or "COLGRAM_AI_MEDIA_PICKER" in launch
        )
        self.assertTrue(
            "ColgramAiSheet.handlePermissionResult(this, requestCode, permissions, grantResults)" in launch
            or "checkPermissionsResult(requestCode, permissions, grantResults)" in launch
        )
        self.assertTrue(
            'name="koliti_media" path="/koliti_media/"' in prov
            or 'name="media_history" path="/media_history/"' in prov
        )

        # Verify 9989 belongs to "Копировать ID" and 9991 is dedicated / collision-free in ChatActivity.
        self.assertIn("options.add(9989);", chat)
        self.assertIn("if (option == 9989) {", chat)
        self.assertIn('options.add(9990);', chat)
        self.assertTrue(
            "options.add(9991);" not in chat or 'items.add("Спросить Koliti");' in chat,
            "Option 9991 must only belong to Спросить Koliti",
        )
        self.assertIn("if (resultCode == Activity.RESULT_OK) {", chat)
        self.assertIn("ColgramAiActivity.REQ_PICK_MEDIA_FROM_SHEET", chat)
        self.assertIn("final MessageObject.GroupedMessages groupedMessages = selectedObjectGroup;", chat)
        self.assertIn("if (option == 9988) {", chat)
        for const_name in (
            "REQ_CAPTURE_CAMERA_FROM_SHEET",
            "REQ_PICK_FILE_FROM_SHEET",
            "REQ_PERM_MIC_KOLITI",
            "REQ_PERM_CAMERA_KOLITI",
        ):
            self.assertIn(const_name, koliti_split_patches._NOOP_COLGRAM_AI_ACTIVITY)


if __name__ == "__main__":
    unittest.main()
