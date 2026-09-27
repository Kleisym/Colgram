from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
FLOAT_TEMPLATE = ROOT / "scripts/templates/ColgramFloatWindowManager.java"
FLOAT_GENERATED = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ColgramFloatWindowManager.java"
PATCHER = ROOT / "scripts/apply-patches.py"
BOT_SHEET = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/bots/BotWebViewSheet.java"


def block_between(source: str, start: str, end: str) -> str:
    return source.split(start, 1)[1].split(end, 1)[0]


class MiniAppFloatingWindowRegression(unittest.TestCase):
    def test_overlay_permission_helper_opens_system_settings(self):
        for path in (FLOAT_TEMPLATE, FLOAT_GENERATED):
            with self.subTest(path=path.name):
                source = path.read_text(encoding="utf-8")
                method = block_between(source, "public static boolean requestOverlayPermission(", "\n    }")
                self.assertIn("Settings.ACTION_MANAGE_OVERLAY_PERMISSION", method)
                self.assertIn("startActivity", method)
                self.assertNotIn("checkInlinePermissions(activity)", method)

    def test_pin_button_requests_overlay_permission_before_open_attempt(self):
        for path in (PATCHER, BOT_SHEET):
            with self.subTest(path=path.name):
                source = path.read_text(encoding="utf-8")
                method = block_between(
                    source,
                    "private void colgramPinMiniAppToFloatingWindow()",
                    "private org.telegram.messenger.pip.PipSource colgramPipSource;",
                )
                request = method.find("requestOverlayPermission(activity)")
                open_window = method.find("ColgramFloatWindowManager.open(")
                self.assertGreaterEqual(request, 0)
                self.assertGreater(open_window, request)
                self.assertIn("Settings.canDrawOverlays(activity)", method)

    def test_pip_fallback_enters_system_pip_and_reports_failure(self):
        for path in (PATCHER, BOT_SHEET):
            with self.subTest(path=path.name):
                source = path.read_text(encoding="utf-8")
                method = block_between(
                    source,
                    "private void colgramOpenMiniAppInPip(",
                    "private void colgramRestorePipMiniAppView()",
                )
                self.assertIn("enterPictureInPictureMode", method)
                self.assertIn("if (!entered)", method)

    def test_pip_reparents_the_live_view_and_restores_it_on_exit(self):
        for path in (PATCHER, BOT_SHEET):
            with self.subTest(path=path.name):
                source = path.read_text(encoding="utf-8")
                delegate = block_between(
                    source,
                    "private final org.telegram.messenger.pip.source.IPipSourceDelegate colgramPipDelegate",
                    "public void setFullscreen(boolean fullscreen, boolean animated)",
                )
                self.assertIn("colgramPipOriginalParent.removeView(webViewContainer)", delegate)
                self.assertIn("colgramRestorePipMiniAppView()", delegate)

    def test_overlay_failure_restores_content_to_original_parent(self):
        for path in (FLOAT_TEMPLATE, FLOAT_GENERATED):
            with self.subTest(path=path.name):
                source = path.read_text(encoding="utf-8")
                self.assertIn("oldParent.addView(content, oldLayoutParams)", source)

    def test_generated_bot_sheet_has_one_fullscreen_forwarder(self):
        source = BOT_SHEET.read_text(encoding="utf-8")
        self.assertIn("COLGRAM_MINIFLOAT_PATCH = 3", source)
        self.assertEqual(
            source.count("public void setFullscreen(boolean fullscreen, boolean animated) {"),
            1,
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
