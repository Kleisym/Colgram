"""Contract tests for Colgram file attachment fixes, device spoofing, and permanent local delete."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "colgram-core/src/main/java/org/colgram/core"
TEMPLATES = ROOT / "scripts/templates"
MSG = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/messenger"
TGNET = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/tgnet"
UI = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui"


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="ignore")


class AttachSpoofDeleteContractsTest(unittest.TestCase):
    def test_file_attachment_contracts(self):
        file_import = read_text(CORE / "ColgramFileImport.java")
        for token in (
            'getExternalFilesDir("colgram_inbox")',
            "openTypedAssetFileDescriptor",
            "MimeTypeMap",
            "uniqueFile(",
            "LARGE_FILE_DEDUP_SKIP_BYTES",
            "OpenableColumns.SIZE",
            "StatFs",
        ):
            self.assertIn(token, file_import)

        android_utils = read_text(MSG / "AndroidUtilities.java")
        for token in (
            "isInternalUri(Uri uri, int fd)",
            'replace("/./", "/")',
            'ApplicationLoader.applicationContext.getExternalFilesDir("colgram_inbox")',
        ):
            self.assertIn(token, android_utils)

        attach_alert = read_text(UI / "Components/ChatAttachAlert.java")
        self.assertIn("dismiss(true)", attach_alert)
        self.assertIn("didSelectFiles(", attach_alert)

        chat_activity = read_text(UI / "ChatActivity.java")
        for token in (
            "final boolean hasForwardMessages = fmessages != null && !fmessages.isEmpty();",
            "if (!hasFiles && !hasForwardMessages)",
            "void sendUriAsDocument(",
            "ColgramHookHandler.purgeMessagesCompletely(",
        ):
            self.assertIn(token, chat_activity)

        send_helper = read_text(MSG / "SendMessagesHelper.java")
        self.assertIn("checkFileSize(", send_helper)
        self.assertIn("prepareSendingDocuments(", send_helper)

        files_template = read_text(TEMPLATES / "ColgramFilesActivity.java")
        for token in ("openTypedAssetFileDescriptor", "file.exists()", "file.isFile()"):
            self.assertIn(token, files_template)

    def test_device_spoofing_contracts(self):
        cloak = read_text(CORE / "ColgramCloak.java")
        for token in (
            "class DeviceProfile",
            "findPresetForModel(",
            "getSystemVersionForModel(",
            "getEffectiveDeviceModel()",
            "getEffectiveSystemVersion()",
            "getEffectiveManufacturer()",
            "getCloakedConnectionParams(",
            '"system_lang_code"',
            '"manufacturer"',
        ):
            self.assertIn(token, cloak)

        config = read_text(CORE / "ColgramConfig.java")
        for token in (
            "KEY_CLOAK_INIT_BUMP",
            "colgram_cloak_reinit_v2",
            "getCloakInitVersionBump()",
            "bumpCloakInitVersion()",
        ):
            self.assertIn(token, config)

        sec_policy = read_text(CORE / "ColgramSecurityPolicy.java")
        for token in (
            "buildCloakedWebViewUserAgent(",
            "buildWebViewSpoofJs(",
            "getHighEntropyValues",
            "WebGLRenderingContext",
            "WebGL2RenderingContext",
        ):
            self.assertIn(token, sec_policy)

        hook_handler = read_text(CORE / "ColgramHookHandler.java")
        self.assertIn("public static String buildWebViewSpoofJs()", hook_handler)

        conn_mgr = read_text(TGNET / "ConnectionsManager.java")
        for token in (
            "ColgramCloak",
            "SharedConfig.buildVersion() + Math.max(0, org.colgram.core.ColgramConfig.getCloakInitVersionBump())",
            '"com.android.vending"',
            "PERFORMANCE_CLASS_HIGH",
        ):
            self.assertIn(token, conn_mgr)

        routes = read_text(TEMPLATES / "ColgramBrowserRoutes.java")
        for token in (
            "import androidx.webkit.WebViewCompat;",
            "public static void injectSpoofScript(WebView webView)",
            "public static String filterRequestHeaderValue(String headerName, String headerValue)",
            '"sec-ch-ua-model"',
            '"x-requested-with"',
        ):
            self.assertIn(token, routes)

        settings = read_text(TEMPLATES / "ColgramSettingsActivity.java")
        for token in (
            "import android.app.Activity;",
            "import org.colgram.core.ColgramCloak;",
            "private void showModelSelector",
            "showCustomModelInput(",
            "promptRestartForCloakChange(",
            "restartApplicationCleanly(",
        ):
            self.assertIn(token, settings)

        bot_wv = read_text(UI / "web/BotWebViewContainer.java")
        self.assertIn("ColgramBrowserRoutes.filterRequestHeaderValue(", bot_wv)
        self.assertIn("ColgramBrowserRoutes.injectSpoofScript(", bot_wv)

        for rel in (
            "WebviewActivity.java",
            "Components/EmbedBottomSheet.java",
            "Components/PhotoViewerWebView.java",
            "PaymentFormActivity.java",
            "ArticleViewer.java",
        ):
            self.assertIn("ColgramBrowserRoutes.injectSpoofScript(", read_text(UI / rel), rel)

    def test_permanent_local_delete_contracts(self):
        db = read_text(CORE / "ColgramDatabase.java")
        self.assertIn("public void purgeMessagesCompletely(long dialogId, Collection<Integer> messageIds)", db)
        self.assertIn("drainPoisonedNonPositiveEntries()", db)

        hook_handler = read_text(CORE / "ColgramHookHandler.java")
        self.assertIn("public static void purgeMessagesCompletely(long dialogId, java.util.Collection<Integer> messageIds)", hook_handler)
        self.assertIn("messageId <= 0", hook_handler)

        cloak = read_text(CORE / "ColgramCloak.java")
        self.assertNotIn('"iPhone 15 Pro Max"', cloak)

        mc = read_text(MSG / "MessagesController.java")
        for token in (
            "colgramDeleteMessages(",
            "alreadyDeletedPurge",
            "ColgramHookHandler.purgeMessagesCompletely(",
        ):
            self.assertIn(token, mc)

        alerts = read_text(UI / "Components/AlertsCreator.java")
        self.assertTrue(
            'purgeCell.setText("Удалить полностью у себя", "", false, false);' in alerts
            or 'purgeCell.setText("Удалить полностью у себя", "Стереть локальную копию из сейфа анти-удаления", false, false);' in alerts,
            "AlertsCreator.java must render permanent local delete checkbox",
        )
        self.assertIn("CheckBoxCell", alerts)

        sheet = read_text(UI / "Components/DeleteMessagesBottomSheet.java")
        for token in (
            "private static final int OPTION_COLGRAM_PURGE_LOCAL = 105;",
            'UItem.asRoundCheckbox(OPTION_COLGRAM_PURGE_LOCAL, "Удалить полностью у себя")',
            "item.id == OPTION_COLGRAM_PURGE_LOCAL",
        ):
            self.assertIn(token, sheet)

    def test_patcher_specs_contract(self):
        patcher = read_text(ROOT / "scripts/apply-patches.py")
        for token in (
            "_ATTACH_SPOOF_DELETE_PATCH_SPECS",
            "def inject_attach_spoof_delete_patches(",
            "inject_attach_spoof_delete_patches(",
        ):
            self.assertIn(token, patcher)


if __name__ == "__main__":
    unittest.main()
