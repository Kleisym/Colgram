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

            ms_text = pass1["TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java"].decode("utf-8")
            self.assertIn("colgramPreFilterSpamMentions(messages);", ms_text)
            self.assertIn("ColgramNativeColemailerBridge.isStorageAutoDownloadBlocked", ms_text)


if __name__ == "__main__":
    unittest.main()
