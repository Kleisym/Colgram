"""Bot sheet regressions: private entry and bounded, single-route authorization."""
from pathlib import Path
import re
import unittest

TEMPLATE = Path(__file__).with_name("templates") / "ColgramBotLoginBottomSheet.java"


class BotLoginTemplateTest(unittest.TestCase):
    def test_does_not_display_or_synthesize_secrets(self):
        source = TEMPLATE.read_text(encoding="utf-8")
        self.assertIn("InputType.TYPE_TEXT_VARIATION_PASSWORD", source)
        self.assertIn("IME_FLAG_NO_PERSONALIZED_LEARNING", source)
        self.assertIn("setSaveEnabled(false)", source)
        self.assertNotIn("TYPE_TEXT_VARIATION_VISIBLE_PASSWORD", source)
        self.assertNotIn("equalsIgnoreCase(token)", source)
        self.assertNotIn("UserConfig.selectedAccount", source)
        self.assertIsNone(re.search(r'"\d{5,}:"\s*\+\s*token', source))

    def test_single_request_timeout_and_failures_stay_on_selected_route(self):
        source = TEMPLATE.read_text(encoding="utf-8")
        self.assertIn("BuildVars.APP_ID", source)
        self.assertNotIn("BUILTIN_API_IDS", source)
        self.assertEqual(1, source.count("manager.sendRequest("))
        self.assertIn("requestTimeoutMs = 30_000", source)
        self.assertIn("manager.cancelRequest", source)
        self.assertIn("ACCESS_TOKEN_INVALID", source)
        self.assertIn("API_ID_INVALID", source)
        self.assertIn("colgram_bot_status", source)
        self.assertNotIn("switchToNextProxy", source)


if __name__ == "__main__":
    unittest.main()
