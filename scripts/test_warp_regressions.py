"""Source integration contracts complement the executable native/JVM tests."""
from pathlib import Path
import os
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(os.environ.get("COLGRAM_TEST_ROOT", Path(__file__).resolve().parents[1]))
CORE = ROOT / "colgram-core/src/main/java/org/colgram/core"


class WarpIntegrationTest(unittest.TestCase):
    def test_ui_process_never_loads_or_measures_native_masque(self):
        text = (CORE / "ColgramWarpMasqueTunnel.java").read_text(encoding="utf-8")
        self.assertFalse(re.search(r"ColgramMasqueNative\.(?:isAvailable|measure|closeSession|setSocksFront)\(", text), "UI process invokes native MASQUE")

    def test_wrapper_does_not_invent_service_success(self):
        text = (CORE / "ColgramWarpTunnel.java").read_text(encoding="utf-8")
        self.assertTrue("up = ColgramWarpMasqueTunnel.isUp()" in text, "wrapper invents up=true")
        self.assertTrue("connected = up" in text, "wrapper invents connected=true")

    def test_service_uses_duplex_ip_and_blocking_tun(self):
        text = (CORE / "ColgramMasqueVpnService.java").read_text(encoding="utf-8")
        for contract in ("receiveIpPacket()", "setBlocking(true)", "tunnelAddress()", "ResultReceiver", "START_NOT_STICKY"):
            self.assertTrue(contract in text, "missing service contract: " + contract)

    def test_all_native_tls_connections_verify_peer(self):
        text = (ROOT / "tools/warpgo/native/main.go").read_text(encoding="utf-8")
        self.assertFalse(re.search(r"InsecureSkipVerify\s*:\s*true", text), "TLS certificate verification disabled")
        self.assertFalse(re.search(r"RootCAs\s*:\s*x509.NewCertPool\(\)", text), "empty trust roots disable real TLS validation")

    def test_exchange_returns_whole_ip_not_tcp_application_payload(self):
        text = (ROOT / "tools/warpgo/native/main.go").read_text(encoding="utf-8")
        method = text.split("func colgram_masque_exchange(", 1)[1].split("//export", 1)[0]
        self.assertFalse("s.peer.inbuf" in method, "exchange returns TCP payload, not IP")
        self.assertFalse("5 * time.Second" in method, "exchange serially blocks outbound packets")
        self.assertTrue("packets" in text and "colgram_masque_receive_slice" in text, "missing duplex IP queue")

    def test_native_teardown_is_owned_and_idempotent(self):
        text = (CORE / "ColgramMasqueVpnService.java").read_text(encoding="utf-8")
        self.assertTrue("closeSessionOwned(nativeOwner)" in text, "old cleanup can close a replacement session")
        self.assertTrue("cleanupScheduled.compareAndSet(false, true)" in text, "teardown is not idempotent")

    def test_masque_service_is_registered_in_an_isolated_process(self):
        manifest = ET.parse(ROOT / "colgram-core/src/main/AndroidManifest.xml").getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        services = [s for s in manifest.findall("application/service") if s.get(android + "name") == "org.colgram.core.ColgramMasqueVpnService"]
        self.assertEqual(1, len(services), "MASQUE service is not packaged")
        service = services[0]
        self.assertEqual(":colgram_masque", service.get(android + "process"))
        self.assertEqual("android.permission.BIND_VPN_SERVICE", service.get(android + "permission"))
        self.assertEqual("specialUse", service.get(android + "foregroundServiceType"))
        self.assertEqual("false", service.get(android + "exported"))

    def test_isolated_service_skips_full_telegram_initialization(self):
        patcher = (ROOT / "scripts/apply-patches.py").read_text(encoding="utf-8")
        self.assertTrue("inject_masque_process_isolation(target_repo)" in patcher, "isolated process still runs Telegram startup")
        hook = (CORE / "ColgramHookHandler.java").read_text(encoding="utf-8")
        self.assertTrue("ColgramProcess.isMasqueProcess(context)" in hook, "isolated process can recursively restore WARP")

    def test_native_probe_is_abi_aware_and_supports_split_apks(self):
        text = (CORE / "ColgramMasqueNative.java").read_text(encoding="utf-8")
        self.assertTrue("ColgramAbiPolicy.compatible" in text, "universal APK mistakes another ABI for this process")
        self.assertTrue("splitSourceDirs" in text and "nativeLibraryDir" in text, "split/extracted native library probe missing")


if __name__ == "__main__":
    unittest.main()
