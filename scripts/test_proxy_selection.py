"""Public proxy activation contracts: TCP reachability never installs a route."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "colgram-core/src/main/java/org/colgram/core"


def method(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


class ProxySelectionTest(unittest.TestCase):
    def setUp(self):
        self.source = (CORE / "ColgramProxyManager.java").read_text(encoding="utf-8")

    def test_catalog_requires_current_native_verdict(self):
        publication = method(self.source, "private static void publishPoolToStock()", "private static void updateSharedConfigProxyState")
        self.assertIn("isPubliclySelectable(p)", publication, "TCP-only candidates still enter the ordinary proxy catalog")
        self.assertNotIn("tcpOnlyPublished", publication)

    def test_catalog_removes_expired_managed_entries(self):
        self.assertIn("removeUnselectableManagedProxies(scClass)", self.source,
                      "old harvested dead proxies remain persisted and selectable")

    def test_selection_has_pending_and_bounded_terminal_state(self):
        self.assertIn("isProxySelectionPending()", self.source)
        self.assertIn("getProxySelectionStatus()", self.source)
        self.assertIn("PROXY_SELECTION_TIMEOUT_MS", self.source)
        self.assertIn("selectPublicProxy(Context context)", self.source)
        self.assertIn("selectionIsCurrent", self.source)

    def test_manual_socks_credentials_survive_apply(self):
        self.assertIn('.putString("proxy_user", proxy.user)', self.source)
        self.assertIn('.putString("proxy_pass", proxy.password)', self.source)

    def test_single_probe_timeout_owns_inflight_release(self):
        fast = method(self.source, "private static final Runnable fastNativeTick", "/** How often the node")
        steady = method(self.source, "private static final Runnable proberTick", "/**\n     * Next entry")
        self.assertNotIn("nativeProbeInFlight.set(false)", fast)
        self.assertNotIn("nativeProbeInFlight.set(false)", steady)
        self.assertNotIn("NATIVE_CHECK_TIMEOUT_MS", fast, "outer fast timeout races the owner timeout")
        self.assertNotIn("NATIVE_CHECK_TIMEOUT_MS", steady, "outer steady timeout races the owner timeout")

    def test_protocol_failure_is_immediately_unselectable(self):
        verdict = method(self.source, "private static void onVerdict", "/**\n     * Give a node")
        failure = verdict.split("} else {", 1)[1]
        self.assertIn("item.isAvailable = false;", failure.split("tryMimicFront", 1)[0])

    def test_force_apply_rejects_unverified_public_routes(self):
        apply = method(self.source, "public static void forceApplyProxy(ProxyItem proxy)", "public static boolean isProxyEnabled")
        self.assertIn("isFreshProtocolVerified(proxy)", apply)
        self.assertGreater(apply.index('.putBoolean("proxy_enabled", true)'),
                           apply.index("if (!appliedThroughStockPath && !appliedThroughNativePath)"),
                           "proxy_enabled is persisted before the route API succeeds")

    def test_restore_never_chooses_pool_head(self):
        restore = method(self.source, "private static void activateBuiltinProxyNow()", "// Optional source discovery")
        self.assertNotIn("forceApplyProxy(first)", restore)
        self.assertIn("selectUserProxyForEpoch", restore)

    def test_no_candidate_rotation_releases_dead_route(self):
        rotation = method(self.source, "void switchToNextProxy(boolean force)", "private static ProxyItem findLocalDpiProxy")
        self.assertIn("disableFailedProxyRoute(routeEpoch)", rotation,
                      "dead selected route spins forever after the last candidate fails")


if __name__ == "__main__":
    unittest.main()
