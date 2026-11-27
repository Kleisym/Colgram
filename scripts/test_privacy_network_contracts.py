#!/usr/bin/env python3
"""Contract tests for Wave 1 network isolation, proxy security, P2P leak prevention, and browser routing."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORE = ROOT / "colgram-core/src/main/java/org/colgram/core"
UI = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui"
MSG = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/messenger"


class PrivacyNetworkContractTests(unittest.TestCase):
    def test_webview_sites_apply_browser_routes(self):
        webview_files = [
            UI / "WebviewActivity.java",
            UI / "web/BotWebViewContainer.java",
            UI / "web/WebInstantView.java",
            UI / "web/WebBrowserSettings.java",
            UI / "Components/EmbedBottomSheet.java",
            UI / "Components/PhotoViewerWebView.java",
            UI / "Components/WebPlayerView.java",
            UI / "PaymentFormActivity.java",
            UI / "ArticleViewer.java",
        ]
        for path in webview_files:
            content = path.read_text(encoding="utf-8")
            self.assertIn("ColgramBrowserRoutes.applyToWebView", content, f"{path.name} missing applyToWebView")

    def test_http_fetch_sites_use_browser_routes_open_connection(self):
        http_files = [
            UI / "web/HttpGetTask.java",
            UI / "web/HttpPostTask.java",
            UI / "web/HttpGetFileTask.java",
            UI / "web/HttpGetBitmapTask.java",
            UI / "web/BotDownloads.java" if (UI / "web/BotDownloads.java").exists() else UI / "bots/BotDownloads.java",
            UI / "Components/TranslateAlert2.java",
            UI / "Components/WebPlayerView.java",
            UI / "ColgramVersionsActivity.java",
            MSG / "ImageLoader.java",
        ]
        for path in http_files:
            content = path.read_text(encoding="utf-8")
            self.assertIn("ColgramBrowserRoutes.openConnection", content, f"{path.name} missing openConnection")

    def test_browser_route_ui_present_in_both_settings_and_proxy_list(self):
        for path in (UI / "ProxyListActivity.java", UI / "ColgramSettingsActivity.java"):
            content = path.read_text(encoding="utf-8")
            self.assertIn("browserRow", content, f"{path.name} missing browserRow")
            self.assertTrue(
                "Браузер и ссылки" in content or "Использовать для браузера" in content,
                f"{path.name} missing browser label",
            )
            self.assertIn("ColgramBrowserRoutes.showPicker", content, f"{path.name} missing ColgramBrowserRoutes.showPicker")

    def test_voip_p2p_and_group_call_guards(self):
        voip = (MSG / "voip/VoIPService.java").read_text(encoding="utf-8")
        self.assertIn("private boolean colgramAllowP2p()", voip)
        self.assertIn("reqCall.protocol.udp_p2p = colgramAllowP2p();", voip)
        self.assertIn("req.protocol.udp_p2p = colgramAllowP2p();", voip)
        self.assertIn("req1.protocol.udp_p2p = colgramAllowP2p();", voip)
        self.assertIn("colgramAllowP2p() && privateCall.p2p_allowed", voip)
        self.assertIn("chatID != 0 || intent.hasExtra(\"joinConference\")", voip)

    def test_doh_and_http_isolation_contracts(self):
        doh = (CORE / "ColgramDohResolver.java").read_text(encoding="utf-8")
        self.assertIn("matchesCertificateName", doh)
        self.assertIn("Proxy.NO_PROXY", doh)
        self.assertIn("isStrictEncryptedDnsOnly()", doh)
        http = (CORE / "ColgramHttp.java").read_text(encoding="utf-8")
        self.assertIn("allowThirdPartyRelays()", http)
        self.assertIn("isStrictProxySecurityEnabled()", http)
        self.assertIn("preferProxyFirst()", http)
        self.assertNotIn('"Colgram/3.0"', http)
        self.assertNotIn('"Colgram/1.0"', http)

        ghost = (CORE / "ColgramGhostMode.java").read_text(encoding="utf-8")
        self.assertIn("runAllowingSpamMentionClear(Runnable action)", ghost)
        self.assertIn("isAllowingSpamMentionClear()", ghost)

        cloak = (CORE / "ColgramCloak.java").read_text(encoding="utf-8")
        self.assertIn("stripColgramSuffix(", cloak)

        cfg = (CORE / "ColgramConfig.java").read_text(encoding="utf-8")
        self.assertIn("ghostReadEnabled = true;", cfg)
        self.assertIn("ghostTypingEnabled = true;", cfg)
        self.assertIn("autoUpdateEnabled = false;", cfg)
        self.assertIn("colgram_defaults_v4_cap_migrated", cfg)

        routes = (ROOT / "scripts/templates/ColgramBrowserRoutes.java").read_text(encoding="utf-8")
        self.assertIn("whenProxyReady(", routes)
        self.assertIn("public static void loadUrl(", routes)
        self.assertNotIn("AlertDialog.Builder", routes)

        updater = (CORE / "ColgramUpdater.java").read_text(encoding="utf-8")
        self.assertNotIn("DownloadManager", updater)

        defender = (CORE / "ColgramSecurityDefender.java").read_text(encoding="utf-8")
        self.assertNotIn("ColgramConfig.setAutoUpdateEnabled(true)", defender)


    def test_defender_webview_voip_browser_update_and_service_contracts(self):
        voip = (MSG / "voip/VoIPService.java").read_text(encoding="utf-8")
        self.assertIn("shouldBlockVoipP2p()", voip)
        self.assertIn("enableStunMarking && colgramAllowP2p()", voip)

        webview_act = (UI / "WebviewActivity.java").read_text(encoding="utf-8")
        self.assertIn("MIXED_CONTENT_ALWAYS_ALLOW", webview_act)
        self.assertIn("ColgramBrowserRoutes.applyToWebView(webView);", webview_act)
        self.assertGreater(
            webview_act.index("ColgramBrowserRoutes.applyToWebView(webView);"),
            webview_act.index("MIXED_CONTENT_ALWAYS_ALLOW"),
        )

        article = (UI / "ArticleViewer.java").read_text(encoding="utf-8")
        self.assertIn("MIXED_CONTENT_ALWAYS_ALLOW", article)
        self.assertIn("ColgramBrowserRoutes.applyToWebView(webView);", article)
        self.assertGreater(
            article.index("ColgramBrowserRoutes.applyToWebView(webView);"),
            article.index("MIXED_CONTENT_ALWAYS_ALLOW"),
        )

        bot_web = (UI / "web/BotWebViewContainer.java").read_text(encoding="utf-8")
        self.assertIn("setGeolocationEnabled(!org.colgram.core.ColgramHookHandler.shouldBlockBotLocation())", bot_web)
        self.assertIn("cloakWebViewUserAgent(", bot_web)

        bot_loc = (UI / "bots/BotLocation.java").read_text(encoding="utf-8")
        self.assertIn("shouldBlockBotLocation()", bot_loc)

        browser = (MSG / "browser/Browser.java").read_text(encoding="utf-8")
        self.assertIn("sanitizeOutgoingOrOpenedUrl(", browser)
        self.assertIn("!internalUri", browser)
        self.assertIn("openInTelegramBrowser(context, uri.toString(), inCaseLoading)", browser)

        launch = (UI / "LaunchActivity.java").read_text(encoding="utf-8")
        self.assertIn("shouldBlockForeignAppUpdate()", launch)
        self.assertIn("consumePendingUpdate()", launch)

        profile = (UI / "ProfileActivity.java").read_text(encoding="utf-8")
        self.assertIn("ColgramBrowserRoutes.applyToWebView(webView);", profile)

        settings = (UI / "SettingsActivity.java").read_text(encoding="utf-8")
        self.assertIn("ColgramBrowserRoutes.applyToWebView(webView);", settings)

        fg_service = (
            Path(__file__).resolve().parent.parent
            / "colgram-core/src/main/java/org/colgram/core/ColgramForegroundService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("public void onTimeout(int startId, int fgsType)", fg_service)


if __name__ == "__main__":
    unittest.main()
