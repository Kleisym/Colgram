#!/usr/bin/env python3
"""
Contract and dry-run regression tests for Track B:
  1. 2GB/4GB Downloads attach pipeline
  2. Deleted large-file infinite send/load loop elimination
  3. Saved Messages & big-chat SQLite performance
  4. PersistentBackup / AccountManager / Cloak / FloatWindow / EditHistory / JNI fixes
  5. Idempotent zero-miss dry-run verification of scripts/attach_del_perf_patches.py
"""

import importlib.util
import os
import shutil
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TELEGRAM_SRC = os.path.join(ROOT, "Telegram-Src")

TARGET_FILES = [
    "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java",
    "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlertDocumentLayout.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/FileUploadOperation.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java",
    "TMessagesProj/src/main/java/org/telegram/ui/Components/AlertsCreator.java",
    "TMessagesProj/src/main/java/org/telegram/ui/Components/DeleteMessagesBottomSheet.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/SavedMessagesController.java",
    "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java",
    "TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/FileRefController.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/AutoDeleteMediaTask.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java",
    "TMessagesProj/src/main/java/org/telegram/ui/SecretMediaViewer.java",
]


def read_text(rel: str) -> str:
    p = os.path.join(ROOT, *rel.split("/"))
    with open(p, "r", encoding="utf-8", errors="ignore") as f:
        return f.read()


class TrackBContractsTest(unittest.TestCase):
    def test_colgram_core_and_template_hardening_contracts(self):
        fi = read_text("colgram-core/src/main/java/org/colgram/core/ColgramFileImport.java")
        for token in (
            "LARGE_FILE_DEDUP_SKIP_BYTES = 32L * 1024L * 1024L",
            "OpenableColumns.SIZE",
            "StatFs",
            "takePersistableUriPermission",
            "cleanupRejectedStagedFile(",
            "shouldSkipDedupHash(",
            "hasSufficientSpace(",
            "ColgramStorageSandbox.ensureDirWithNoMedia(dir);",
        ):
            self.assertIn(token, fi)
        self.assertIn('getFilesDir(), "colgram_inbox"', fi.split("cleanupRejectedStagedFile(", 1)[1].split("openStagedUriSync", 1)[0])

        um = read_text("colgram-core/src/main/java/org/colgram/core/ColgramUnlimitedMemory.java")
        self.assertIn('appCtx.getExternalFilesDir("colgram_inbox")', um)

        fa = read_text("scripts/templates/ColgramFilesActivity.java")
        self.assertIn("IMPORT_EXECUTOR", fa)
        self.assertIn(".cgtmp", fa)
        self.assertNotIn("bgExecutor.shutdownNow();", fa)

        hh = read_text("colgram-core/src/main/java/org/colgram/core/ColgramHookHandler.java")
        self.assertIn("dialogId == 0L || messageId <= 0", hh)
        self.assertIn("if (mid <= 0) continue;", hh)

        db = read_text("colgram-core/src/main/java/org/colgram/core/ColgramDatabase.java")
        self.assertIn("DATABASE_VERSION = 6;", db)
        self.assertIn("peekPoisonedNonPositiveEntries()", db)
        self.assertIn("purgePoisonedNonPositiveEntries()", db)
        self.assertIn("DeletedCacheRefreshListener", db)
        self.assertIn("deletedCacheLoadStarted = false;", db)
        cache_load_body = db.split("private void ensureDeletedCachesLoadedSync() {", 1)[1].split("prepareCacheForLookup()", 1)[0]
        self.assertNotIn(".delete(TABLE_DELETED", cache_load_body)
        self.assertNotIn("deletedMessagesByDialog.clear()", cache_load_body)

        sa = read_text("scripts/templates/ColgramSettingsActivity.java")
        self.assertNotIn("backupNow(appCtx, null)", sa)
        self.assertIn("promptDocumentsBackupPassphrase(", sa)

        ash = read_text("scripts/templates/ColgramAccountSwitcherHelper.java")
        self.assertNotIn("backupNow(ctx, null)", ash)

        pb = read_text("colgram-core/src/main/java/org/colgram/core/ColgramPersistentBackup.java")
        self.assertIn("KEYSTORE_LOCAL_ALIAS", pb)
        self.assertIn("PRIVATE_BACKUP_DIR", pb)
        self.assertIn("filesCanon", pb)
        self.assertNotIn("private static char[] deviceKeyMaterial()", pb)

        am = read_text("colgram-core/src/main/java/org/colgram/core/ColgramAccountManager.java")
        self.assertIn("canOverwriteWrappedMasterKey(", am)
        self.assertIn("MAX_VAULT_BUNDLE_FIELD_BYTES", am)
        self.assertIn("Failed to unwrap existing vault master key", am)

        ck = read_text("colgram-core/src/main/java/org/colgram/core/ColgramCloak.java")
        sp = read_text("colgram-core/src/main/java/org/colgram/core/ColgramSecurityPolicy.java")
        self.assertNotIn('"iPhone 15 Pro Max"', ck)
        self.assertIn("Keep Android WebView UA strictly coherent", sp)

        eh = read_text("colgram-core/src/main/java/org/colgram/core/ColgramEditHistoryDialog.java")
        self.assertIn("ThreadLocal<SimpleDateFormat>", eh)

        fw = read_text("scripts/templates/ColgramFloatWindowManager.java")
        self.assertNotIn("window.windowManager.removeViewImmediate(window.root);\n            window.windowManager.addView", fw.replace("\r\n", "\n"))
        self.assertIn("updateViewLayout(window.root, window.params)", fw)
        self.assertIn("onAnimationCancel(Animator animation)", fw)

        vw = read_text("scripts/templates/ColgramVideoWallpaperView.java")
        self.assertIn("Utilities.globalQueue.postRunnable(() -> {", vw)
        self.assertIn("releasePlayerAsync(preparedMp)", vw)

    def test_native_jni_hardening_contracts(self):
        jb = read_text("tools/warpgo/native/jni_bridge.c")
        self.assertIn("g_exchange_lock", jb)
        self.assertIn("pthread_mutex_lock(&g_exchange_lock)", jb)
        self.assertIn("new_jstring_from_utf8_safe", jb)
        self.assertIn("ExceptionCheck", jb)

        lj = read_text("vendor/colgram-llama/cpp/colgram_llama_jni.cpp")
        self.assertIn("std::call_once(g_backend_once", lj)
        self.assertIn("const bool hadVision = !rawBitmaps.empty();", lj)
        self.assertIn("if (!hadVision)", lj)
        self.assertIn("0x10FFFFu", lj)
        self.assertIn("native_init_impl", lj)

    def test_attach_del_perf_patches_dry_run_and_idempotency(self):
        if not os.path.isdir(os.path.join(TELEGRAM_SRC, "TMessagesProj")):
            self.skipTest("Telegram-Src checkout absent")

        spec = importlib.util.spec_from_file_location(
            "attach_del_perf_patches",
            os.path.join(ROOT, "scripts", "attach_del_perf_patches.py"),
        )
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)

        misses = []
        changed = []

        def patch_file(filepath, search_pattern, replacement, description=""):
            with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
                raw = f.read()
            if replacement and replacement.strip() in raw:
                return True
            if callable(search_pattern):
                new_c = search_pattern(raw)
                if new_c == raw:
                    misses.append(description)
                    return False
                with open(filepath, "w", encoding="utf-8", newline="") as f:
                    f.write(new_c)
                changed.append(description)
                return True
            if search_pattern not in raw:
                misses.append(description)
                return False
            with open(filepath, "w", encoding="utf-8", newline="") as f:
                f.write(raw.replace(search_pattern, replacement, 1))
            changed.append(description)
            return True

        def eol_patch(filepath, target, replacement, description=""):
            with open(filepath, "r", encoding="utf-8", errors="ignore", newline="") as f:
                raw = f.read()
            eol = "\r\n" if "\r\n" in raw else "\n"
            c = raw.replace("\r\n", "\n")
            t = target.replace("\r\n", "\n")
            r = replacement.replace("\r\n", "\n")
            if r in c:
                return True
            if t not in c:
                misses.append(description)
                return False
            with open(filepath, "w", encoding="utf-8", newline="") as f:
                f.write(c.replace(t, r, 1).replace("\n", eol))
            changed.append(description)
            return True

        with tempfile.TemporaryDirectory() as tmp:
            for rel in TARGET_FILES:
                src = os.path.join(TELEGRAM_SRC, *rel.split("/"))
                dst = os.path.join(tmp, *rel.split("/"))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(src, dst)

            mod.apply(tmp, patch_file, eol_patch)
            self.assertEqual(misses, [], f"Dry-run pass 1 had misses: {misses}")

            def patched_text(rel):
                with open(os.path.join(tmp, *rel.split("/")), "r", encoding="utf-8", errors="ignore") as f:
                    return f.read()

            self.assertIn("didSelectFiles(colgramPathsList,", patched_text("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java"))
            self.assertIn("java.util.Locale.US", patched_text("TMessagesProj/src/main/java/org/telegram/messenger/SavedMessagesController.java"))
            self.assertIn("org.telegram.messenger.SendMessagesHelper.colgramCancelMessageTransfers(", patched_text("TMessagesProj/src/main/java/org/telegram/ui/Components/DeleteMessagesBottomSheet.java"))
            self.assertIn("org.telegram.messenger.Utilities.globalQueue.postRunnable(", patched_text("TMessagesProj/src/main/java/org/telegram/ui/SecretMediaViewer.java"))
            self.assertIn("colgramDocPrepExecutor.execute(colgramPrepTask);", patched_text("TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java"))
            fl_text = patched_text("TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java")
            self.assertIn('tmpFile = File.createTempFile("colgram_dec_", ".tmp", outDir);', fl_text)
            self.assertIn("final File tmpFile;", fl_text)
            ca_text = patched_text("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java")
            self.assertIn("Math.max(totalBytes, bytesCopied)", ca_text)
            self.assertIn("colgramAppCtx", ca_text)
            mc_text = patched_text("TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java")
            self.assertIn("(channelId == 0) ? dialogMessagesByIds.get(id) : null;", mc_text)
            self.assertIn("delObj.getDialogId() != targetDid", mc_text)
            self.assertIn("SendMessagesHelper.getInstance(currentAccount).isSendingMessage(id)", mc_text)
            self.assertIn("SendMessagesHelper.colgramCancelFileDownloads(currentAccount, delObj);", mc_text)
            ms_text = patched_text("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")
            self.assertIn("colgram_purged_neg_mids_acc_", ms_text)
            self.assertIn("peekPoisonedNonPositiveEntries()", ms_text)
            self.assertIn("purgePoisonedNonPositiveEntries()", ms_text)
            self.assertIn('database.executeFast("DELETE FROM messages_topics WHERE uid = " + did + " AND mid != " + last_mid_i + " AND mid != " + last_mid + keepClause)', ms_text)
            self.assertIn('database.executeFast("DELETE FROM messages_topics WHERE uid = " + did + keepClauseFull)', ms_text)

            changed.clear()
            mod.apply(tmp, patch_file, eol_patch)
            self.assertEqual(misses, [], f"Dry-run pass 2 had misses: {misses}")
            self.assertEqual(changed, [], f"Dry-run pass 2 was not idempotent: {changed}")


if __name__ == "__main__":
    unittest.main()
