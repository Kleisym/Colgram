"""The catalog verifier and proxy activation are independent operations."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "colgram-core/src/main/java/org/colgram/core"


def method(source, marker):
    start = source.index("{", source.index(marker))
    depth = 1
    for end in range(start + 1, len(source)):
        depth += (source[end] == "{") - (source[end] == "}")
        if depth == 0:
            return source[start + 1:end]
    raise AssertionError(marker)


class BackgroundProxyCatalogTest(unittest.TestCase):
    manager = (CORE / "ColgramProxyManager.java").read_text(encoding="utf-8")

    def test_starts_and_reschedules_independently_of_proxy_switch(self):
        self.assertIn("scheduleBackgroundProxyCheck(PROXY_START_DELAY_MS)", self.manager)
        start = method(self.manager, "private static void runBackgroundProxyCheck()")
        for guard in ("isBuiltinProxyEnabled()", "ColgramPowerGuard.shouldThrottle", "isProxySelectionPending()"):
            self.assertIn(guard, start)
        self.assertNotIn("isProxyEnabled(", start.split("Log.i(TAG,", 1)[0])
        self.assertNotIn("isUserProxyDisabled()", start)
        self.assertIn("scheduleBackgroundProxyCheck(", start)
        self.assertIn("fetchAndVerifyAllSources(", start)
        self.assertIn('"PROXY_BACKGROUND_START network="', start)
        self.assertIn('" proxy_enabled=" + isProxyEnabled(context)', start)

    def test_guests_own_catalog_without_touching_applied_route(self):
        check = method(self.manager, "private static boolean startBackgroundGuestProbe(")
        self.assertIn("ColgramGuestProxyProbe.check", check)
        self.assertRegex(check, r"time\s*>=\s*0")
        self.assertIn("networkProbeEpoch.get()", check)
        self.assertIn("publishPoolToStock()", check)
        self.assertIn("notifyProxySettingsChanged()", check)
        for side_effect in ("forceApplyProxy(", "onVerdict(", "setUserProxyDisabled(", "autoConnectIfBlocked("):
            self.assertNotIn(side_effect, check)

    def test_legacy_native_pools_do_not_compete_for_catalog_verdict(self):
        fast = method(self.manager, "private static final Runnable fastNativeTick")
        next_target = method(self.manager, "private static ProxyItem nextProberTarget()")
        self.assertIn("isBackgroundCatalogCandidate(item)", fast)
        self.assertIn("isBackgroundCatalogCandidate(p)", next_target)
        self.assertIn("public static boolean usesCatalogVerifier(Object proxyInfo)", self.manager)

    def test_background_retries_unseen_candidates_and_finishes_with_a_public_status(self):
        tick = method(self.manager, "private static void backgroundProxyTick(")
        self.assertIn("sortCatalogProbeOrder", tick)
        self.assertIn("scheduleSelectionTcp", tick)
        self.assertIn("startBackgroundGuestProbe", tick)
        self.assertIn("finishBackgroundProxyCheck", tick)
        self.assertIn("networkProbeEpoch.get()", tick)
        finish = method(self.manager, "private static void finishBackgroundProxyCheck(")
        self.assertIn("PROXY_BACKGROUND", finish)
        self.assertIn("scheduleBackgroundProxyCheck", finish)
        self.assertIn("public static String getBackgroundProxyStatus()", self.manager)


if __name__ == "__main__":
    unittest.main()
