"""Source-level regressions accompanying the real MuMu instrumentation evidence."""
from pathlib import Path
import argparse
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]


def source(name):
    return (ROOT / "colgram-core/src/main/java/org/colgram/core" / name).read_text(encoding="utf-8")


def method(text, signature):
    start = text.index(signature)
    end = re.search(r"\n    }", text[start:])
    if end is None:
        raise AssertionError("Unterminated method " + signature)
    return text[start:start + end.end()]


class MuMuContractsTest(unittest.TestCase):
    def test_direct_off_is_persisted_without_getter_side_effects(self):
        text = source("ColgramConfig.java")
        for getter, key in (("isDpiBypassEnabled", "KEY_DPI_BYPASS_ENABLED"), ("isDcRemapEnabled", "KEY_DC_REMAP")):
            body = method(text, "public static boolean " + getter + "()")
            self.assertIn("getBoolean(" + key + ", false)", body)
            self.assertNotIn("prefs.edit()", body)
            self.assertNotIn("return true;", body)
        self.assertIn("putBoolean(KEY_DC_REMAP, enabled)", method(text, "public static void setDcRemapEnabled"))

    def test_production_bridge_uses_the_pinned_upstream_class(self):
        text = source("ColgramProxyManager.java")
        self.assertNotIn('"org.telegram.proxy.ProxySettings', text)
        self.assertIn('"org.telegram.utils.proxy.ProxySettings"', text)
        self.assertIn('"org.telegram.utils.proxy.ProxySettings$Type"', text)

    def test_an_explicit_local_route_is_not_suppressed_by_a_tcp_probe(self):
        body = method(source("ColgramProxyManager.java"), "public static void setDpiBypassEnabled")
        self.assertNotIn("telegramDcProbe()", body)
        self.assertIn("awaitReady", body)
        self.assertIn("forceApplyProxy(local)", body)
        self.assertIn("ColgramRouteEpoch.runIfCurrent", body)

    def test_private_path_only_uses_public_relays_after_opt_in(self):
        text = source("ColgramDpiBypass.java")
        condition = re.search(r"if\s*\(([^{};]+hasReachableRelays\(\))\)\s*\{", text)
        self.assertIsNotNone(condition)
        self.assertIn("ColgramConfig.isAutoProxyEnabled()", condition[1])

    def test_listener_binding_and_readiness_are_owned_by_one_generation(self):
        text = source("ColgramDpiBypass.java")
        start = method(text, "private static synchronized void startNow()")
        self.assertIn('candidate.bind(new InetSocketAddress("127.0.0.1", 0))', start)
        self.assertIn("workerPool.execute(() -> acceptClients(listener, generation))", start)
        self.assertIn("serverSocket == listener && listenerGeneration == generation", text)
        self.assertIn("++listenerGeneration", method(text, "public static synchronized void stop()"))

    def test_every_native_check_has_its_own_bounded_deadline(self):
        body = method(source("ColgramProxyManager.java"), "private static boolean checkOne")
        self.assertIn("mainHandler.postDelayed(timeout, NATIVE_CHECK_TIMEOUT_MS)", body)
        self.assertIn("finishNativeProbe(item, owner)", body)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=ROOT)
    args, rest = parser.parse_known_args()
    ROOT = args.repo.resolve()
    unittest.main(argv=[__file__, *rest])
