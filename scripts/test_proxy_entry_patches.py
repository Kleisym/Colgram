"""Previously unguarded stock proxy entry points must not activate a dead route."""
from pathlib import Path
import subprocess
import unittest

from proxy_entry_patches import patch_proxy_link, patch_proxy_startup, patch_saved_proxy_form

ROOT = Path(__file__).resolve().parents[1] / "Telegram-Src"
SRC = "TMessagesProj/src/main/java/org/telegram/"


@unittest.skipUnless((ROOT / ".git").exists(), "requires the pinned upstream source")
class StockProxyEntryTests(unittest.TestCase):
    def check(self, path, transform, anchor, marker, forbidden):
        rel = SRC + path
        pristine = subprocess.check_output(["git", "-C", ROOT, "show", "HEAD:" + rel]).decode("utf-8")
        generated = (ROOT / rel).read_text(encoding="utf-8")
        for source in (pristine, generated):
            patched = transform(source)
            self.assertEqual(patched, transform(patched))
            block = patched.split(marker, 1)[1].split(anchor, 1)[0]
            self.assertNotIn(forbidden, block)
            yield block

    def test_manual_editor_is_the_only_editor_that_marks_manual_provenance(self):
        for block in self.check("ui/ProxySettingsActivity.java", patch_saved_proxy_form,
                                "finishFragment();", "COLGRAM_PROXY_SAVE_VALIDATE", "setProxySettings("):
            self.assertIn("markManuallySavedProxy(getParentActivity(), currentProxyInfo)", block)
            self.assertIn("selectUserProxy(getParentActivity()", block)

    def test_manual_link_waits_for_protocol(self):
        for block in self.check("messenger/AndroidUtilities.java", patch_proxy_link,
                                "if (activity instanceof LaunchActivity)", "COLGRAM_PROXY_LINK_VALIDATE",
                                "setProxySettings("):
            self.assertIn("markManuallySavedProxy(activity, saved)", block)
            self.assertIn("selectUserProxy(activity", block)

    def test_startup_does_not_apply_persisted_unchecked_proxy(self):
        for block in self.check("tgnet/ConnectionsManager.java", patch_proxy_startup,
                                'String installer = "";', "COLGRAM_PROXY_STARTUP_VALIDATE",
                                "native_setProxySettings("):
            self.assertIn("revalidates saved endpoints", block)


if __name__ == "__main__":
    unittest.main()
