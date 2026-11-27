"""Call transport must capture only Colgram and never silently fall back to direct."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "colgram-core/src/main/java/org/colgram/core"
SERVICE = ROOT / "vendor/colgram-singbox/src/main/java/org/colgram/singbox/ColgramVpnService.java"


class CallSocksTunnelTest(unittest.TestCase):
    def test_call_profile_is_an_app_scoped_single_socks5_outbound(self):
        source = (CORE / "ColgramProfileBuilder.java").read_text(encoding="utf-8")
        profile = source.split("static String forCallSocks(", 1)[1].split("private static JSONArray inbounds", 1)[0]
        self.assertIn('.put("type", "socks")', profile)
        self.assertIn('.put("version", "5")', profile)
        self.assertIn('.put("username", username)', profile)
        self.assertIn('.put("password", password)', profile)
        self.assertIn('.put("final", "call-socks")', profile)
        self.assertNotIn('.put("type", "direct")', profile)
        self.assertNotIn('.put("type", "urltest")', profile)
        service = SERVICE.read_text(encoding="utf-8")
        self.assertIn("builder.addAllowedApplication(getPackageName())", service)
        self.assertIn("if (appOnly)", service)

    def test_call_credentials_are_not_persisted_and_modes_do_not_share_status(self):
        api = (CORE / "ColgramCallSocksTunnel.java").read_text(encoding="utf-8")
        self.assertIn("status(Context context, boolean localDpi)", api)
        self.assertIn('"colgram-call-socks"', api)
        self.assertIn('"colgram-call-dpi"', api)
        self.assertNotIn("FileOutputStream", api)
        self.assertNotIn("getSharedPreferences", api)

    def test_local_call_udp_uses_protected_datagrams_not_tcp(self):
        udp = (CORE / "ColgramCallUdpRelay.java").read_text(encoding="utf-8")
        self.assertIn("vpn.protect(upstream)", udp)
        self.assertIn("upstream.connect(target)", udp)
        self.assertIn("relay.send(", udp)
        self.assertIn("upstream.send(", udp)
        self.assertNotIn("ColgramSocketConnectRace", udp)
        dpi = (CORE / "ColgramDpiBypass.java").read_text(encoding="utf-8")
        self.assertIn("ColgramCallUdpRelay.serve", dpi)
        self.assertIn("owner.protect(socket)", dpi)
        self.assertIn("if (ColgramConfig.isDohEnabled()", dpi)

    def test_system_dpi_exposes_teardown_wait_and_redacted_fgs_errors(self):
        api = (CORE / "ColgramCallSocksTunnel.java").read_text(encoding="utf-8")
        self.assertIn("public static boolean awaitStopped(", api)
        self.assertIn("public static String systemDpiLastError(", api)
        service = SERVICE.read_text(encoding="utf-8")
        self.assertIn("public static synchronized String lastError()", service)
        self.assertIn("public static synchronized String activeState()", service)
        self.assertIn("FGS_START_FAILED", service)
        self.assertNotIn("t.getMessage()", service)
        manifest = (ROOT / "colgram-core/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertIn("PROPERTY_SPECIAL_USE_FGS_SUBTYPE", manifest)
        self.assertNotIn("PROPERTY_SPECIAL_USE_FGS_SUBJECT", manifest)


if __name__ == "__main__":
    unittest.main()
