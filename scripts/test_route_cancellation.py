"""Regressions found by independent specification review, before device testing."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "colgram-core/src/main/java/org/colgram/core"


class RouteCancellationTest(unittest.TestCase):
    def test_direct_also_clears_ipv6_only_and_subscription(self):
        s = (ROOT / "scripts/templates/ColgramSettingsActivity.java").read_text(encoding="utf-8")
        body = s.split("private void selectDirectConnection()", 1)[1].split("private void showModelSelector", 1)[0]
        self.assertTrue("setIpv6BypassEnabled(false)" in body, "Direct leaves IPv6-only mode enabled")
        self.assertTrue("stopSubscriptionTunnel" in body, "Direct leaves subscription VPN running")

    def test_subscription_parse_and_consent_share_route_epoch(self):
        s = (ROOT / "scripts/templates/ColgramSettingsActivity.java").read_text(encoding="utf-8")
        self.assertTrue("waitingSubscriptionConsent" in s, "subscription consent has no owner")
        self.assertTrue("ColgramRouteEpoch.isCurrent" in s, "stale subscription result can install a VPN")
        self.assertTrue("startSubscriptionTunnel(context, token)" in s, "subscription start loses its generation")

    def test_delayed_public_and_dpi_routes_recheck_generation(self):
        s = (CORE / "ColgramProxyManager.java").read_text(encoding="utf-8")
        self.assertTrue("ColgramRouteEpoch.isCurrent(routeEpoch)" in s, "auto fallback can restore a cancelled route")
        self.assertTrue("dpiRequestGeneration.get() != dpiRequest" in s, "late DPI probe ignores explicit off")

    def test_old_startup_restore_cannot_clear_new_request(self):
        s = (CORE / "ColgramHookHandler.java").read_text(encoding="utf-8")
        self.assertTrue("ColgramRouteEpoch.isCurrent(restoreEpoch)" in s, "old restore failure can clear a replacement request")

    def test_clean_installs_do_not_use_a_shared_bot_token(self):
        s = (CORE / "ColgramConfig.java").read_text(encoding="utf-8")
        self.assertTrue(re.search(r'DEFAULT_BOT_TOKEN\s*=\s*""\s*;', s), "clean installs prefill a shared credential")

    def test_unbounded_cache_is_opt_in(self):
        s = (CORE / "ColgramConfig.java").read_text(encoding="utf-8")
        self.assertTrue("getBoolean(KEY_UNLIMITED_MEMORY, false)" in s, "unbounded cache is still the default")
        s = (CORE / "ColgramUnlimitedMemory.java").read_text(encoding="utf-8")
        self.assertTrue("isUnlimitedMemoryConfigured()" in s, "old implicit unlimited cache is never released")

    def test_warp_start_keeps_the_selected_route_generation(self):
        s = (CORE / "ColgramWarpMasqueTunnel.java").read_text(encoding="utf-8")
        self.assertTrue("ColgramRouteEpoch.runIfCurrent(routeEpoch" in s, "late WARP start can outrun a stop")
        s = (CORE / "ColgramHookHandler.java").read_text(encoding="utf-8")
        self.assertTrue("bringUp(appContext, restoreEpoch)" in s, "restore discards its owner before start")

    def test_posted_rotation_and_toggle_cannot_restore_disabled_proxy(self):
        s = (CORE / "ColgramProxyManager.java").read_text(encoding="utf-8")
        rotation = s.split("void switchToNextProxy(boolean force)", 1)[1].split("private static ProxyItem findLocalDpiProxy", 1)[0]
        self.assertTrue("runIfCurrent(routeEpoch" in rotation, "posted rotation has no owner")
        toggle = s.split("void toggleProxy(final Context context)", 1)[1].split("private static ProxyItem findSavedProxy", 1)[0]
        self.assertTrue("ColgramRouteEpoch.next()" in toggle, "toggle off does not invalidate pending rotation")

    def test_masque_does_not_require_legacy_wireguard_registration(self):
        s = (ROOT / "scripts/templates/ColgramSettingsActivity.java").read_text(encoding="utf-8")
        self.assertTrue("!org.colgram.core.ColgramMasqueNative.isAvailableForProbe()" in s, "MASQUE waits for unnecessary WireGuard registration")

    def test_route_commit_does_not_probe_network_on_ui_thread(self):
        s = (CORE / "ColgramProxyManager.java").read_text(encoding="utf-8")
        resolve = s.split("private static void resolveChain(", 1)[1].split("private static volatile long lastApplyAt", 1)[0]
        self.assertFalse("testProxy(" in resolve, "route commit blocks UI while holding cancellation lock")


if __name__ == "__main__": unittest.main()
