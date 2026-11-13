"""Host regression contracts for the app wiring that is not runnable without Android."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "colgram-core/src/main/java/org/colgram/core"
SETTINGS = ROOT / "scripts/templates/ColgramSettingsActivity.java"


def source(name):
    return (CORE / name).read_text(encoding="utf-8")


def method(text, signature):
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 1
    i = opening + 1
    while depth and i < len(text):
        depth += (text[i] == "{") - (text[i] == "}")
        i += 1
    return text[opening + 1:i - 1]


class AppWiringTests(unittest.TestCase):
    def setUp(self):
        self.settings = SETTINGS.read_text(encoding="utf-8")

    def test_network_rows_have_real_positions(self):
        body = method(self.settings, "private void updateRows()")
        for row in ("networkHeaderRow", "dpiBypassRow", "dohRow", "builtinProxyRow",
                    "proxyBrowserRow", "currentProxyRow", "ownProxyRow", "proxyStatusRow",
                    "relayUrlRow", "autoProxyRow", "ipv6BypassRow", "dcRemapRow",
                    "networkSectionRow"):
            self.assertTrue(re.search(rf"\b{row}\s*=\s*rowCount\+\+;", body), row + " must have a real position")

    def test_subscription_and_resource_menus_are_enabled(self):
        body = method(self.settings, "public boolean isEnabled(")
        for row in ("subscriptionRow", "dataSettingsRow", "cacheSettingsRow"):
            self.assertTrue("position == " + row in body, row + " must be wired")

    def test_subscription_and_resource_rows_use_settings_cells(self):
        body = method(self.settings, "public int getItemViewType(")
        for row in ("subscriptionRow", "dataSettingsRow", "cacheSettingsRow"):
            self.assertTrue("position == " + row in body, row + " must be wired")
        self.assertTrue("new DataSettingsActivity()" in self.settings, "data menu must open")
        self.assertTrue("new CacheControlActivity()" in self.settings, "cache menu must open")

    def test_warp_operations_are_generation_checked(self):
        self.assertTrue("ColgramOperationGate" in self.settings, "WARP operation gate missing")
        self.assertTrue("warpOperations.execute" in self.settings, "WARP must run on its worker")
        self.assertTrue("warpOperationsGate.isCurrent" in self.settings, "stale callbacks must be ignored")
        self.assertFalse("new Thread(() ->" in method(self.settings, "private void startWarpTunnel()"), "use the serialized WARP worker")

    def test_optimizer_does_not_open_telegram_databases(self):
        text = source("ColgramOptimizer.java")
        self.assertFalse("SQLiteDatabase.openDatabase" in text, "optimizer must not open Telegram databases")
        self.assertFalse("PRAGMA" in text, "connection-local tuning on closed handles has no effect")
        self.assertTrue("scheduleAfterStartup" in text, "startup scheduling missing")

    def test_explicit_auto_proxy_off_is_not_migrated_back_on(self):
        text = source("ColgramConfig.java")
        self.assertFalse("migrateAutomaticProxyFallbackOn();" in text, "explicit off must be preserved")

    def test_proxy_parsing_and_ranking_use_the_shared_policy(self):
        text = source("ColgramProxyManager.java")
        self.assertTrue("ColgramProxyPolicy.parseEndpoint" in text, "ColgramProxyPolicy.parseEndpoint missing")
        self.assertTrue("ColgramProxyPolicy.compare" in text, "ColgramProxyPolicy.compare missing")
        self.assertTrue("ColgramProxyPolicy.feedIndices" in text, "ColgramProxyPolicy.feedIndices missing")

    def test_proxy_harvest_is_non_overlapping_and_deferred(self):
        text = source("ColgramProxyManager.java")
        self.assertTrue("fetchingSources.compareAndSet(false, true)" in text, "fetchingSources.compareAndSet(false, true) missing")
        self.assertTrue("ColgramOptimizer.scheduleAfterStartup" in text, "ColgramOptimizer.scheduleAfterStartup missing")

    def test_fast_feed_reads_are_size_bounded(self):
        text = source("ColgramHttp.java")
        self.assertTrue("MAX_FEED_BYTES" in text, "MAX_FEED_BYTES missing")
        self.assertTrue("readBounded" in method(text, "private static Response openFastConnection("), "direct feed read must be bounded")
        self.assertTrue("readBounded" in method(text, "private static Response viaConfiguredRelayFast("), "relay feed read must be bounded")

    def test_fast_feed_tunnels_use_standard_http_framing_and_explicit_direct_route(self):
        text = source("ColgramHttp.java")
        self.assertFalse("openViaConnect(" in method(text, "public static Response getFast("),
                         "manual CONNECT reader must not bypass feed body limits/framing")
        self.assertTrue("Proxy.NO_PROXY" in method(text, "private static Response openFast("),
                        "direct feed route must not inherit stale global proxy")


if __name__ == "__main__":
    unittest.main()
