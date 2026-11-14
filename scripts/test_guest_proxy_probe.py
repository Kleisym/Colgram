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
        normalized = s.replace("ACCOUNT", "account")
        self.assertLess(normalized.index("native_setProxySettings"), normalized.index("native_setNetworkAvailable(account, true"))
        self.assertNotIn("native_checkProxy", s)
        self.assertIn("AtomicReferenceArray<Probe>", s)
        self.assertIn('"guest-route-probe"', s)
        self.assertIn('"guest-route-probe-" + account', s)
        self.assertIn("account < 0 || account >= ColgramGuestProxyProbe.MAX_PARALLEL", s)

    def test_bridge_has_bounded_owned_parallel_slots_and_is_non_exported(self):
        src = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramGuestProxyProbe.java"
        self.assertTrue(src.is_file())
        s = src.read_text(encoding="utf-8")
        self.assertIn("bindService", s)
        self.assertIn("unbindService", s)
        self.assertIn("CHECK_TIMEOUT_MS", s)
        self.assertIn("MAX_PARALLEL = 3", s)
        self.assertIn("LANES.acquire()", s)
        self.assertIn("LANES.release(ticket)", s)
        self.assertIn('target.putInt("lane", ticket.lane())', s)
        self.assertNotIn("private static Attempt active;", s)
        self.assertIn("TIMEOUT", s)
        patch = (ROOT / "scripts/guest_proxy_patches.py").read_text(encoding="utf-8")
        self.assertIn('android:exported="false"', patch)
        self.assertIn('android:process=":colgram_proxy_probe"', patch)
        self.assertIn("onRequestComplete", patch)

    def test_background_checker_fills_slots_without_calling_tcp_a_protocol_success(self):
        source = (ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramProxyManager.java").read_text(encoding="utf-8")
        self.assertIn("selection.guestInFlight < ColgramGuestProxyProbe.MAX_PARALLEL", source)
        self.assertIn("selection.guestInFlight++", source)
        self.assertIn("selection.guestInFlight--", source)
        self.assertIn("MAX_BACKGROUND_GUEST_CHECKS = 18", source)
        self.assertIn("selection.guestPending = selection.guestInFlight > 0", source)

    def test_explicit_selection_is_not_limited_to_two_serial_guesses(self):
        source = (ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramProxyManager.java").read_text(encoding="utf-8")
        body = source.split("private static boolean startGuestSelectionProbe(", 1)[1].split("private static String selectionProgress", 1)[0]
        self.assertNotIn("selection.guestAttempts >= 2", body)
        self.assertIn("selection.guestInFlight >= ColgramGuestProxyProbe.MAX_PARALLEL", body)
        self.assertIn("MAX_SELECTION_GUEST_CHECKS = 9", source)


if __name__ == "__main__":
    unittest.main()
