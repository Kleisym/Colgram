"""Contract & 2-pass temp-fixture idempotency tests for Track 2 (Anti-Ping, Colemailer, Hard 1 GB Cap, Malware Shield)."""
from __future__ import annotations

import os
import re
import shutil
import sys
import tempfile
import unittest

_THIS_DIR = os.path.dirname(os.path.abspath(__file__))
if _THIS_DIR not in sys.path:
    sys.path.insert(0, _THIS_DIR)

import spam_storage_patches

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TG_SRC = os.path.join(REPO_ROOT, "Telegram-Src")
CORE_DIR = os.path.join(REPO_ROOT, "colgram-core", "src", "main", "java", "org", "colgram", "core")
TMPL_DIR = os.path.join(REPO_ROOT, "scripts", "templates")


def _read(path: str) -> str:
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def _fixture_eol_patch(filepath: str, target: str, replacement: str, description: str) -> None:
    assert os.path.exists(filepath), f"Missing fixture target for {description}: {filepath}"
    with open(filepath, "rb") as f:
        raw = f.read()
    use_crlf = b"\r\n" in raw
    text = raw.decode("utf-8", errors="ignore").replace("\r\n", "\n")
    norm_target = target.replace("\r\n", "\n")
    norm_repl = replacement.replace("\r\n", "\n")
    if norm_repl in text and norm_target != norm_repl:
        updated = text
    else:
        assert norm_target in text, f"Missing anchor for {description} in {filepath}:\n{norm_target[:240]}"
        updated = text.replace(norm_target, norm_repl, 1)
    out = updated.replace("\n", "\r\n") if use_crlf else updated
    with open(filepath, "wb") as f:
        f.write(out.encode("utf-8"))


class SpamStorageContractsTest(unittest.TestCase):
    def test_contract_b_shield_config_signatures(self):
        src = _read(os.path.join(CORE_DIR, "ColgramShieldConfig.java"))
        required = [
            "public static void init(Context",
            "public static int getStorageHardCapMb()",
            "public static void setStorageHardCapMb(int",
            "public static boolean isStorageCapEnabled()",
            "public static void setStorageCapEnabled(boolean",
            "public static boolean isLazyShadowVaultEnabled()",
            "public static void setLazyShadowVaultEnabled(boolean",
            "public static boolean isMalwareDownloadShieldEnabled()",
            "public static void setMalwareDownloadShieldEnabled(boolean",
            "public static int getAutoDownloadMaxMobileMb()",
            "public static void setAutoDownloadMaxMobileMb(int",
            "public static int getAutoDownloadMaxWifiMb()",
            "public static void setAutoDownloadMaxWifiMb(int",
            "public static boolean isAntiPingStrangersBigGroupsEnabled()",
            "public static void setAntiPingStrangersBigGroupsEnabled(boolean",
            "public static boolean isCollapseSpamPingsEnabled()",
            "public static void setCollapseSpamPingsEnabled(boolean",
            "public static boolean isAntiPingAutoClearServerMentionsEnabled()",
            "public static void setAntiPingAutoClearServerMentionsEnabled(boolean",
            "public static boolean isInfiniteVirtualStorageEnabled()",
            "public static void setInfiniteVirtualStorageEnabled(boolean",
            "public static long getStorageHardCapBytes()",
        ]
        for sig in required:
            self.assertIn(sig, src, f"Contract B signature missing: {sig}")

    def test_database_v7_and_performance_test_sync(self):
        db_src = _read(os.path.join(CORE_DIR, "ColgramDatabase.java"))
        self.assertIn("DATABASE_VERSION = 7;", db_src)
        self.assertIn("collapsed_spam_messages", db_src)
        self.assertIn("shadow_messages", db_src)

        perf_test = _read(os.path.join(
            REPO_ROOT, "tests", "java", "org", "colgram", "core", "ColgramPerformanceRegressionTest.java"
        ))
        self.assertIn("dbVerF.getInt(null) == 7", perf_test)

    def test_templates_no_alert_dialog_and_icon_ordering(self):
        for fname in (
            "ColgramAntiSpamActivity.java",
            "ColgramFilesActivity.java",
            "ColgramNativeColemailerBridge.java",
            "ColgramNotificationListenerService.java",
        ):
            src = _read(os.path.join(TMPL_DIR, fname))
            self.assertNotIn("AlertDialog.Builder", src, f"Forbidden AlertDialog.Builder in {fname}")

        for ui_file in ("ColgramAntiSpamActivity.java", "ColgramFilesActivity.java"):
            src = _read(os.path.join(TMPL_DIR, ui_file))
            lines = src.splitlines()
            for i, line in enumerate(lines):
                if re.search(r"\b(cell|tc|checkCell)\.setText(AndValue|AndCheck)?\(", line):
                    window = "\n".join(lines[max(0, i - 5): i + 1])
                    if "TextSettingsCell" in window or "HeaderCell" in window:
                        continue
                    self.assertTrue(
                        "applySettingIcon" in window or "applyCheckIcon" in window,
                        f"{ui_file}:{i + 1} setText* must be preceded by applySettingIcon/applyCheckIcon:\n{window}",
                    )

    def test_no_koliti_imports_in_shared_core(self):
        for fname in (
            "ColgramShieldConfig.java",
            "ColgramFileSafetyPolicy.java",
            "ColgramStorageCapEnforcer.java",
            "ColgramStorageOptimizer.java",
            "ColgramStorageIndex.java",
            "ColgramStorageSandbox.java",
            "ColgramDatabase.java",
            "ColgramSpamGuard.java",
            "ColgramNotificationFilter.java",
            "ColgramColemailerEngine.java",
            "ColgramHookHandler.java",
        ):
            src = _read(os.path.join(CORE_DIR, fname))
            self.assertNotRegex(src, r"(?i)koliti\.", f"Unexpected Koliti import in shared {fname}")

    def test_spam_storage_patches_two_pass_idempotency_on_temp_fixture(self):
        rel_targets = [
            "TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/DownloadController.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/AutoDeleteMediaTask.java",
            "TMessagesProj/src/main/java/org/telegram/ui/CacheControlActivity.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/MessageObject.java",
            "TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java",
            "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java",
        ]
        with tempfile.TemporaryDirectory(prefix="cg_t2_fixture_") as tmp_dir:
            for rel in rel_targets:
                src_file = os.path.join(TG_SRC, rel)
                dst_file = os.path.join(tmp_dir, rel)
                os.makedirs(os.path.dirname(dst_file), exist_ok=True)
                shutil.copy2(src_file, dst_file)

            spam_storage_patches.apply(tmp_dir, _fixture_eol_patch, _fixture_eol_patch)
            pass1 = {}
            for rel in rel_targets:
                with open(os.path.join(tmp_dir, rel), "rb") as f:
                    pass1[rel] = f.read()

            # Second pass must be 100% byte-for-byte idempotent
            spam_storage_patches.apply(tmp_dir, _fixture_eol_patch, _fixture_eol_patch)
            for rel in rel_targets:
                with open(os.path.join(tmp_dir, rel), "rb") as f:
                    pass2 = f.read()
                self.assertEqual(pass1[rel], pass2, f"Pass 2 was not idempotent on {rel}")

            dl_text = pass1["TMessagesProj/src/main/java/org/telegram/messenger/DownloadController.java"].decode("utf-8")
            self.assertIn("ColgramPremiumConfig.isBlockSponsoredAdsEnabled()", dl_text)
            self.assertIn("ColgramFileSafetyPolicy.shouldBlockAutoDownload", dl_text)
            self.assertIn("getFileLoader().loadFile(document, downloadObject.parent, FileLoader.PRIORITY_LOW, downloadObject.secret ? 2 : 0, true);", dl_text)

            fl_text = pass1["TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java"].decode("utf-8")
            self.assertNotIn("document != null && priority <= PRIORITY_LOW", fl_text)
            self.assertIn("loadFile(document, parentObject, priority, cacheType, false);", fl_text)
            self.assertEqual(fl_text.count("private FileLoadOperation loadFileInternal("), 1)
            self.assertIn(", cacheType, false);", fl_text.split("protected FileLoadOperation loadStreamFile(final FileLoadOperationStream stream, final TLRPC.Document document, final ImageLocation location, final Object parentObject, final long offset, final boolean priority, int loadingPriority, int cacheType)", 1)[1].split("awaitFileLoadOperation(", 1)[0])
            fl_internal = fl_text.split("private FileLoadOperation loadFileInternal(", 1)[1].split("FileLoadOperation operation = loadOperationPaths.get(", 1)[0]
            self.assertIn("final boolean isAutoDownload", fl_internal)
            self.assertIn("if (isAutoDownload && isColgramAutoDownloadBlocked(document)) {", fl_internal)
            self.assertIn("priority >= FileLoaderPriorityQueue.PRIORITY_VALUE_MAX", fl_text.split("private FileLoadOperation loadFileInternal(", 1)[1].split("\n    public static boolean canSaveAsFile(", 1)[0])
            self.assertLess(
                fl_internal.index("if (isAutoDownload && isColgramAutoDownloadBlocked(document)) {"),
                fl_internal.index("loadOperationPathsUI.put("),
            )
            self.assertLess(
                fl_internal.index("if (isAutoDownload && isColgramAutoDownloadBlocked(document)) {"),
                fl_internal.index("getDownloadController().startDownloadFile("),
            )

            ms_text = pass1["TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java"].decode("utf-8")
            self.assertEqual(ms_text.count("public void putMessages(ArrayList<TLRPC.Message> messages, boolean withTransaction, boolean useQueue, boolean doNotUpdateDialogDate, int downloadMask, boolean ifNoLastMessage, int mode, long threadMessageId)"), 1)
            self.assertIn("colgramPreFilterSpamMentions(messages);", ms_text)
            self.assertIn("ColgramNativeColemailerBridge.isStorageAutoDownloadBlocked", ms_text)
            self.assertIn("ColgramHookHandler.beginShadowCaptureBatch();", ms_text)
            self.assertIn("ColgramHookHandler.flushShadowCaptureBatch();", ms_text)
            self.assertIn("MessageObject.getDialogId(m);", ms_text)
            self.assertNotIn("markMentionMessageAsRead(m.dialog_id, m.id, m.dialog_id);", ms_text)

            adt_text = pass1["TMessagesProj/src/main/java/org/telegram/messenger/AutoDeleteMediaTask.java"].decode("utf-8")
            self.assertIn("colgramMergeSandboxDirsDedup(paths,", adt_text)
            self.assertIn("1000 + i", adt_text)
            self.assertIn("ColgramStorageCapEnforcer.isProtectedDirectoryName(", adt_text)

            cc_text = pass1["TMessagesProj/src/main/java/org/telegram/ui/CacheControlActivity.java"].decode("utf-8")
            self.assertIn("options.add(-100);", cc_text)
            self.assertIn("if (opt == -100 || opt == 0)", cc_text)
            self.assertIn("if (currentLimit == 0) currentLimit = -100;", cc_text)

            au_text = pass1["TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java"].decode("utf-8")
            self.assertIn("String colgramDocName = FileLoader.getDocumentFileName(document);", au_text)

            mo_text = pass1["TMessagesProj/src/main/java/org/telegram/messenger/MessageObject.java"].decode("utf-8")
            self.assertIn("public boolean colgramLayoutCollapsed;", mo_text)
            self.assertIn("colgramLayoutCollapsed = colgramCollapsed;", mo_text)

            cmc_text = pass1["TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java"].decode("utf-8").replace("\r\n", "\n")
            self.assertEqual(cmc_text.count("boolean colgramNowCollapsedCheck ="), 1)
            self.assertIn("photoImage.setImage(null, null, ImageLocation.getForPath(colgramShadowPreview), currentPhotoFilter, null, 0, null, messageObject, 0);", cmc_text)
            shadow_idx = cmc_text.index("String colgramShadowPreview = org.colgram.core.ColgramHookHandler.getShadowPreviewPath(")
            self.assertNotIn("photoNotSet = false;", cmc_text[max(0, shadow_idx - 120):shadow_idx + 350])
            self.assertIn("messageObject.colgramLayoutCollapsed != colgramNowCollapsedCheck", cmc_text)
            for motion_fn in ("checkTextBlockMotionEvent(", "checkCaptionMotionEvent(", "checkLinkPreviewMotionEvent("):
                slice_body = cmc_text.split(motion_fn, 1)[1][:220]
                self.assertIn("if (colgramCollapsedSpamVisible) {", slice_body, motion_fn)
            self.assertIn("final MessageObject targetMsg = currentMessageObject;", cmc_text)
            self.assertIn("final TLRPC.Document targetDoc = documentAttach;", cmc_text)
            self.assertIn("targetMsg.putInDownloadsStore = true;", cmc_text)

    def test_track2_hotfix_core_and_bridge_contracts(self):
        sc_src = _read(os.path.join(CORE_DIR, "ColgramShieldConfig.java"))
        self.assertIn("ColgramConfig.isUnlimitedMemoryEnabled()", sc_src)

        um_src = _read(os.path.join(CORE_DIR, "ColgramUnlimitedMemory.java"))
        self.assertIn('destName.endsWith(".webp")', um_src)
        self.assertIn("Bitmap.CompressFormat.JPEG", um_src)
        self.assertIn("maxDecodePixels", um_src)

        ce_src = _read(os.path.join(CORE_DIR, "ColgramStorageCapEnforcer.java"))
        self.assertIn("isCompressibleLogFile(", ce_src)
        self.assertIn("public static boolean isProtectedDirectoryName(String dirName)", ce_src)
        self.assertIn("pruneAbandonedInbox(", ce_src)
        self.assertIn("computeDirBytes(shadowDir)", ce_src)

        hh_src = _read(os.path.join(CORE_DIR, "ColgramHookHandler.java"))
        shadow_getter = hh_src.split("public static String getShadowPreviewPath(", 1)[1].split("\n    public static void notifyMediaFileLoaded(", 1)[0]
        self.assertIn("hookIoExecutor.execute(", shadow_getter)
        self.assertNotIn(".isFile()", shadow_getter.split("hookIoExecutor.execute(", 1)[0])
        self.assertIn("ColgramProxyManager.activateBuiltinProxy(appContext);", hh_src)
        init_body = hh_src.split("public static void init(Context context) {", 1)[1].split("public static boolean canAutoRestartDpiBypass()", 1)[0]
        self.assertGreaterEqual(init_body.count("catch (Throwable"), 6)

        sb_src = _read(os.path.join(CORE_DIR, "ColgramStorageSandbox.java"))
        self.assertIn("canUseRootFast(", sb_src)
        self.assertIn("getCanonicalPath()", sb_src)

        db_src = _read(os.path.join(CORE_DIR, "ColgramDatabase.java"))
        self.assertIn("upsertShadowMessagesBatch(", db_src)
        batch_body = db_src.split("public void upsertShadowMessagesBatch(", 1)[1].split("public void clearShadowPreviewPath(", 1)[0]
        self.assertNotIn("CONFLICT_REPLACE", batch_body)

        eng_src = _read(os.path.join(CORE_DIR, "ColgramColemailerEngine.java"))
        self.assertIn("public synchronized void recordCampaignDispatchOutcome(", eng_src)

        bs_src = _read(os.path.join(CORE_DIR, "ColgramBotSync.java"))
        self.assertIn("AtomicInteger permitRefs", bs_src)

        br_src = _read(os.path.join(TMPL_DIR, "ColgramNativeColemailerBridge.java"))
        self.assertIn("TLRPC.TL_inputPeerEmpty", br_src)
        self.assertIn("markup.rows == null", br_src)
        self.assertIn("collectOwnUsernames(", br_src)
        self.assertIn("cc.isContact(userId)", br_src)


if __name__ == "__main__":
    unittest.main()
