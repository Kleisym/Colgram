"""Check the pinned call request and media route use the same selection."""
from pathlib import Path
import subprocess
import unittest

from call_route_patches import patch_call_activity_result, patch_call_helper, patch_private_call_service

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM = ROOT / "Telegram-Src"
SOURCE = "TMessagesProj/src/main/java/org/telegram/"


@unittest.skipUnless((UPSTREAM / ".git").exists(), "requires pinned upstream")
class CallRoutePatchesTest(unittest.TestCase):
    def apply(self, name, transform):
        path = SOURCE + name
        pristine = subprocess.check_output(["git", "-C", UPSTREAM, "show", "HEAD:" + path]).decode()
        for value in (pristine, (UPSTREAM / path).read_text(encoding="utf-8")):
            result = transform(value)
            self.assertEqual(result, transform(result))
            yield result

    def test_outgoing_prepares_the_route_before_service_and_checks_account(self):
        for value in self.apply("ui/Components/voip/VoIPHelper.java", patch_call_helper):
            block = value.split("// COLGRAM_PRIVATE_CALL_ROUTE_PREFLIGHT", 1)[1]
            self.assertLess(block.index("ColgramCallRoutes.prepare"), block.index("activity.startService(intent)"))
            self.assertIn("ColgramCallRoutes.EXTRA_TICKET", block)
            self.assertIn("ColgramCallRoutes.EXTRA_MODE", block)
            self.assertIn("accountInstance.getCurrentAccount()", value)

    def test_android_vpn_consent_is_dispatched(self):
        for value in self.apply("ui/LaunchActivity.java", patch_call_activity_result):
            self.assertIn("ColgramCallRoutes.onActivityResult(this, requestCode, resultCode)", value)

    def test_service_gates_media_and_releases_only_its_ticket(self):
        for value in self.apply("messenger/voip/VoIPService.java", patch_private_call_service):
            self.assertIn("ColgramCallRoutes.release(colgramRouteTicket)", value)
            self.assertIn("ColgramCallRoutes.prepare(activity", value)
            self.assertNotIn("colgramCallProxy()", value)
            media = value.split("private void initiateActualEncryptedCall()", 1)[1]
            self.assertLess(media.index("!colgramRequirePrivateCallRoute()"), media.index("Instance.makeInstance("))

    def test_picker_persists_mode_without_starting_tunnel(self):
        ui = (ROOT / "scripts/templates/ColgramCallRoutes.java").read_text(encoding="utf-8")
        block = ui.split("public static void showPicker", 1)[1].split("private static void save", 1)[0]
        self.assertIn("Mode.values()", block)
        self.assertNotIn("start(", block)
        self.assertIn("putString(MODE_KEY, mode.name())", ui)
        self.assertIn("ColgramCallSocksTunnel.start", ui)
        self.assertIn("ColgramWarpTunnel.bringUp(work.app, work.epoch)", ui)
        self.assertIn("ColgramVpnService.start(work.app, work.subscription)", ui)


if __name__ == "__main__":
    unittest.main()
