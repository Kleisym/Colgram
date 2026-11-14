"""The fresh pre-login probe uses tgnet's genuine Generic RPC in a private service."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class GuestProxyProbeTest(unittest.TestCase):
    def test_uses_a_distinct_process_and_no_account_files(self):
        src = ROOT / "scripts/templates/ColgramGuestProxyService.java"
        self.assertTrue(src.is_file(), "The old checker requires a pre-existing Telegram key")
        s = src.read_text(encoding="utf-8")
        self.assertIn("getCacheDir()", s)
        self.assertNotIn("UserConfig", s)
        self.assertNotIn("getFilesDir", s)
        self.assertIn("0L, false, false, false", s)  # no user, push or initially open network
        self.assertIn("TL_help_getConfig", s)
        self.assertIn("TL_config.TLdeserialize", s)
        self.assertIn("ConnectionTypeGeneric", s)
        self.assertLess(s.index("native_setProxySettings"), s.index("native_setNetworkAvailable(ACCOUNT, true"))
        self.assertNotIn("native_checkProxy", s)

    def test_bridge_is_single_flight_bounded_and_non_exported(self):
        src = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramGuestProxyProbe.java"
        self.assertTrue(src.is_file())
        s = src.read_text(encoding="utf-8")
        self.assertIn("bindService", s)
        self.assertIn("unbindService", s)
        self.assertIn("CHECK_TIMEOUT_MS", s)
        self.assertIn("active != null", s)
        self.assertIn("TIMEOUT", s)
        patch = (ROOT / "scripts/guest_proxy_patches.py").read_text(encoding="utf-8")
        self.assertIn('android:exported="false"', patch)
        self.assertIn('android:process=":colgram_proxy_probe"', patch)
        self.assertIn("onRequestComplete", patch)


if __name__ == "__main__":
    unittest.main()
