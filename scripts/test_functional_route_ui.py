"""Executable source transforms for the pinned Telegram route screens."""
import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PROXY = "TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java"
VOIP = "TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java"
spec = importlib.util.spec_from_file_location("patch_proxylist_warp", ROOT / "scripts/patch_proxylist_warp.py")
patcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patcher)


def method(text, signature):
    start = text.index(signature)
    opening = text.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[opening + 1:end - 1]


class FunctionalRouteUiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pristine = subprocess.check_output(
            ["git", "-C", str(ROOT / "Telegram-Src"), "show", "HEAD:" + PROXY],
        ).decode("utf-8")
        cls.pristine_voip = subprocess.check_output(
            ["git", "-C", str(ROOT / "Telegram-Src"), "show", "HEAD:" + VOIP],
        ).decode("utf-8")
        cls.existing = (ROOT / "Telegram-Src" / PROXY).read_text(encoding="utf-8")

    def transformed(self, text=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for path, source in ((PROXY, text or self.pristine), (VOIP, self.pristine_voip)):
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(source, encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                patcher.apply(str(root))
            proxy = (root / PROXY).read_text(encoding="utf-8")
            voip = (root / VOIP).read_text(encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                patcher.apply(str(root))
            self.assertEqual(proxy, (root / PROXY).read_text(encoding="utf-8"), "second application changed proxy screen")
            self.assertEqual(voip, (root / VOIP).read_text(encoding="utf-8"), "second application changed call wiring")
            return proxy, voip

    def test_tap_sets_real_desired_state_before_async_start(self):
        for original in (self.pristine, self.existing):
            with self.subTest(pristine=original is self.pristine):
                proxy, _ = self.transformed(original)
                body = method(proxy, "private void startWarpFromProxyScreen()")
                self.assertIn("warpStartPending = true", body)
                self.assertIn("setWarpEnabled(true)", body)
                self.assertIn("ColgramRouteEpoch.next()", body)
                self.assertLess(body.index("setWarpEnabled(true)"), body.index("warpOperations.execute"))

    def test_masque_start_does_not_call_legacy_registration(self):
        proxy, _ = self.transformed()
        body = method(proxy, "private void startWarpFromProxyScreen()")
        self.assertIn("!org.colgram.core.ColgramMasqueNative.isAvailableForProbe()", body)
        self.assertIn("warpRequested(token)", body)

    def test_cancel_and_consent_results_have_operation_ownership(self):
        proxy, _ = self.transformed()
        self.assertIn("private void cancelWarpFromProxyScreen()", proxy)
        cancel = method(proxy, "private void cancelWarpFromProxyScreen()")
        self.assertIn("warpOperationsGate.cancel()", cancel)
        self.assertIn("cancelPendingStart()", cancel)
        self.assertIn("warpStartPending = false", cancel)
        result = method(proxy, "public void onActivityResultFragment(")
        self.assertIn("warpRequested(token)", result)
        self.assertIn("RESULT_OK", result)
        self.assertIn("cancelWarpFromProxyScreen()", result)
        self.assertIn("bringUp(context, routeEpoch)", method(proxy, "private void bringWarpUp()"))
        completion = method(proxy, "private void completeWarpAttempt(")
        self.assertIn("ColgramRouteEpoch.isCurrent(warpRouteEpoch)", completion)
        self.assertIn("warpOperationsGate.finish(token)", completion)

    def test_stock_switch_and_saved_row_validate_before_installing(self):
        proxy, _ = self.transformed()
        click = proxy.split("listView.setOnItemClickListener((view, position) -> {", 1)[1]
        toggle = click.split("if (position == useProxyRow) {", 1)[1].split("} else if (position == rotationRow)", 1)[0]
        self.assertIn("selectPublicProxy(getContext())", toggle)
        self.assertNotIn("setProxySettings(useProxySettings", toggle)
        node = click.split("} else if (position >= proxyStartRow && position < proxyEndRow)", 1)[1].split("} else if (position ==", 1)[0]
        self.assertIn("selectUserProxy(", node)
        self.assertNotIn("SharedConfig.currentProxy = info", node)
        self.assertNotIn("setProxySettings(", node)

    def test_calls_row_is_a_route_picker_not_a_switch(self):
        proxy, voip = self.transformed()
        self.assertIn("callsRow = rowCount++", proxy)
        self.assertIn("position == callsRow", method(proxy, "public boolean isEnabled("))
        self.assertIn('"Использовать для звонков"', proxy)
        self.assertIn("ColgramCallRoutes.showPicker", proxy)
        self.assertIn("textCell.setTextAndValue", proxy)
        self.assertNotIn("networkModesRow", proxy)
        self.assertNotIn('putBoolean("proxy_enabled_calls",', proxy)
        self.assertNotIn("colgramCallProxy()", voip)
        self.assertIn("colgramRequirePrivateCallRoute()", method(voip, "private void initiateActualEncryptedCall()"))
        self.assertIn("ColgramCallRoutes.prepare", method(voip, "public void acceptIncomingCall()"))
        self.assertIn("ColgramCallRoutes.release(colgramRouteTicket)", method(voip, "public void onDestroy()"))

    def test_subscription_select_writes_selected_node_not_same_auto_group(self):
        source = (ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramSubscriptionStore.java").read_text(encoding="utf-8")
        select = method(source, "public static State select(")
        self.assertIn("writeProfile(context, nodes.get(index))", select)
        writer = method(source, "private static void writeProfile(")
        self.assertIn("ColgramProfileBuilder.forSelectedNode(node)", writer)
        save = method(source, "public static State save(")
        self.assertIn("stateOf(nodes, 0)", save)
        self.assertIn("recordState(", save)
        self.assertNotIn("select(", method(source, "public static String profilePath("))
        self.assertIn("android.util.AtomicFile", writer)

    def test_settings_exposes_real_node_picker_and_public_route(self):
        settings = (ROOT / "scripts/templates/ColgramSettingsActivity.java").read_text(encoding="utf-8")
        self.assertIn("publicProxyRow = rowCount++", settings)
        self.assertIn("selectPublicProxy(getContext())", settings)
        self.assertIn("private void showSubscriptionNodes()", settings)
        self.assertIn("ColgramSubscriptionStore.nodes(context)", settings)
        self.assertIn("ColgramSubscriptionStore.select(context, which)", settings)
        self.assertIn("startSubscriptionTunnel(context, token)", method(settings, "private void selectSubscriptionNode("))
        self.assertIn("ColgramCallRoutes.showPicker", settings)
        self.assertIn("ColgramCallRoutes.summary()", settings)
        for row in ("publicProxyRow", "subscriptionNodeRow", "callsRow"):
            self.assertIn("position == " + row, method(settings, "public boolean isEnabled("))
            self.assertIn("position == " + row, method(settings, "public void onBindViewHolder("))
        for row in ("publicProxyRow", "subscriptionNodeRow"):
            self.assertIn("position == " + row, method(settings, "public int getItemViewType("))
        self.assertIn("caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)",
                      method(settings, "private boolean isAndroidVpnActive("))
        body = method(settings, "public void onBindViewHolder(")
        self.assertIn('!"running".equals(subscription)', body)
        self.assertIn("!vpnActive", body)

    def test_selected_profile_has_no_automatic_or_direct_fallback(self):
        source = (ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramProfileBuilder.java").read_text(encoding="utf-8")
        self.assertIn("public static String forSelectedNode(", source)
        body = method(source, "public static String forSelectedNode(")
        self.assertIn('put("tag", "selected")', body)
        self.assertIn('put("final", "selected")', body)
        self.assertIn('put("auto_detect_interface", true)', body)
        self.assertNotIn("urltest", body)

    def test_https_subscription_is_fetched_with_bounds_before_parsing(self):
        source = (ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramSubscriptionStore.java").read_text(encoding="utf-8")
        self.assertIn("fetchSubscription(", method(source, "public static State save("))
        self.assertIn("MAX_SUBSCRIPTION_BYTES", source)
        self.assertIn("Proxy.NO_PROXY", source)
        self.assertIn("setInstanceFollowRedirects(false)", source)

    def test_ui_screens_share_serial_warp_control_and_ignore_stale_completion(self):
        settings = (ROOT / "scripts/templates/ColgramSettingsActivity.java").read_text(encoding="utf-8")
        self.assertIn("ColgramRouteControl.WARP_GATE", settings)
        self.assertIn("ColgramRouteControl.OPERATIONS", settings)
        body = method(settings, "private void completeWarpAttempt(")
        self.assertIn("ColgramRouteEpoch.isCurrent(warpRouteEpoch)", body)
        start = method(settings, "private void startWarpTunnel(")
        self.assertIn("final long routeEpoch = warpRouteEpoch", start)
        self.assertIn("setIpv6BypassEnabled(false)", start)
        self.assertIn("bringUp(context.getApplicationContext(), routeEpoch)", start)

    def test_subscription_service_reports_only_observed_tun_state_and_can_reload(self):
        source = (ROOT / "vendor/colgram-singbox/src/main/java/org/colgram/singbox/ColgramVpnService.java").read_text(encoding="utf-8")
        self.assertIn("public static synchronized String status(", source)
        start = method(source, "private void startProfile(")
        self.assertLess(start.index("if (!tunEstablished)"), start.index('publishState("running")'))
        self.assertIn('publishState("stopped")', method(source, "public void onDestroy("))
        self.assertIn("controlGeneration", source)
        self.assertIn("requestedGeneration != controlGeneration", method(source, "public int onStartCommand("))
        self.assertIn("START_NOT_STICKY", method(source, "public int onStartCommand("))
        self.assertIn("if (createServer) server.start()", method(source, "private void bringUp()"))
        self.assertIn("return protect(fd)", method(source, "public boolean protectOutboundSocket("))
        platform = (ROOT / "vendor/colgram-singbox/src/main/java/org/colgram/singbox/ColgramPlatformInterface.java").read_text(encoding="utf-8")
        self.assertIn("target.protectOutboundSocket(fd)", method(platform, "public void autoDetectInterfaceControl("))
        self.assertIn("closeTun()", method(platform, "public int openTun("))
        self.assertIn("NET_CAPABILITY_NOT_VPN", method(platform, "public synchronized void startDefaultInterfaceMonitor("))
        self.assertIn("publishPhysicalNetwork(manager, listener)", method(platform, "public synchronized void startDefaultInterfaceMonitor("))
        self.assertIn("unregisterNetworkCallback", method(platform, "public synchronized void closeDefaultInterfaceMonitor("))


if __name__ == "__main__":
    unittest.main()
