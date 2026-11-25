import importlib.util
from pathlib import Path
import re
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


def read_text(rel_path: str) -> str:
    return (ROOT / rel_path).read_text(encoding="utf-8")


def extract_method(source: str, signature_prefix: str) -> str:
    idx = source.find(signature_prefix)
    if idx < 0:
        raise AssertionError(f"Method not found: {signature_prefix}")
    brace_open = source.find("{", idx)
    if brace_open < 0:
        raise AssertionError(f"Opening brace not found for: {signature_prefix}")
    depth = 0
    for pos in range(brace_open, len(source)):
        ch = source[pos]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return source[idx : pos + 1]
    raise AssertionError(f"Unbalanced braces for: {signature_prefix}")


class StabilityAndOverlayContractsTest(unittest.TestCase):
    def test_foreground_service_uses_special_use_and_timeout_cooldown(self):
        src = read_text("colgram-core/src/main/java/org/colgram/core/ColgramForegroundService.java")
        self.assertIn("FOREGROUND_SERVICE_TYPE_SPECIAL_USE", src)

        on_timeout_1 = extract_method(src, "public void onTimeout(int startId)")
        self.assertIn("onTimeout(startId, 0)", on_timeout_1)

        on_timeout_2 = extract_method(src, "public void onTimeout(int startId, int fgsType)")
        self.assertIn("fgsCooldownUntilMs", on_timeout_2)
        self.assertIn("scheduleProxyRefreshIfNotPending", on_timeout_2)
        self.assertNotIn("ColgramBootJobService.schedule(", on_timeout_2)

    def test_crash_guard_installed_immediately_in_hook_init(self):
        src = read_text("colgram-core/src/main/java/org/colgram/core/ColgramHookHandler.java")
        init_body = extract_method(src, "public static void init(")
        self.assertIn("ColgramCrashGuard.install(appContext)", init_body)

    def test_unlimited_memory_dispatches_image_loader_clear_to_main_looper(self):
        src = read_text("colgram-core/src/main/java/org/colgram/core/ColgramUnlimitedMemory.java")
        trim_body = extract_method(src, "public static void trimTransientMemoryIfNeeded(")
        self.assertIn("Looper.getMainLooper()", trim_body)
        self.assertIn("org.telegram.messenger.ImageLoader", trim_body)
        self.assertIn("clearMemory", trim_body)

    def test_singbox_vpn_service_shuts_down_executor_on_destroy(self):
        src = read_text("vendor/colgram-singbox/src/main/java/org/colgram/singbox/ColgramVpnService.java")
        destroy_body = extract_method(src, "public void onDestroy()")
        self.assertIn("vpnExecutor.shutdownNow()", destroy_body)

    def test_float_window_manager_supports_zero_perm_in_app_mode(self):
        src = read_text("scripts/templates/ColgramFloatWindowManager.java")
        self.assertIn("hasSystemOverlayPermission(", src)
        self.assertIn("obtainInAppHost(", src)
        self.assertIn("inAppOverlay", src)
        is_supported = extract_method(src, "public static boolean isSupported(Context context)")
        self.assertIn("hasSystemOverlayPermission(context)", is_supported)
        self.assertIn("AndroidUtilities.findActivity(context)", is_supported)

    def test_apply_patches_has_v4_minifloat_special_use_fgs_and_zero_perm_pip(self):
        src = read_text("scripts/apply-patches.py")
        self.assertIn("COLGRAM_MINIFLOAT_PATCH = 4", src)
        self.assertIn('android:foregroundServiceType="specialUse"', src)
        self.assertIn("PROPERTY_SPECIAL_USE_FGS_SUBJECT", src)
        self.assertIn(r'LocationSharingService', src)
        self.assertIn(r'location\|dataSync', src)
        self.assertIn("inject_zero_perm_pip_patches(", src)
        self.assertTrue(
            re.search(r"def inject_zero_perm_pip_patches\(repo_path\):", src) is not None
        )

    def test_inject_zero_perm_pip_patches_upgrades_consolidated_v3_bot_sheet(self):
        spec = importlib.util.spec_from_file_location("apply_patches_mod", ROOT / "scripts/apply-patches.py")
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)

        v3_source = """public class BotWebViewSheet {
    // COLGRAM_MINIFLOAT_PATCH = 3
    private void colgramPinMiniAppToFloatingWindow() {
        final android.app.Activity activity = AndroidUtilities.findActivity(getContext());
        if (activity == null || webViewContainer == null) {
            return;
        }

        final boolean hasOverlayPermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M
                || android.provider.Settings.canDrawOverlays(activity);
        if (!hasOverlayPermission) {
            new org.telegram.ui.ActionBar.AlertDialog.Builder(activity, resourcesProvider)
                    .setTitle("Плавающее мини-приложение")
                    .setPositiveButton("Открыть настройки", (d, w) -> {})
                    .show();
            return;
        }

        // --- Tier 1: real multi-window overlay ---
        if (org.telegram.ui.ColgramFloatWindowManager.isSupported(activity)) {
            org.telegram.ui.ColgramFloatWindowManager.open(activity, webViewContainer, "App", null);
        }
    }
}
"""
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            bot_sheet = repo / "TMessagesProj/src/main/java/org/telegram/ui/bots/BotWebViewSheet.java"
            bot_sheet.parent.mkdir(parents=True, exist_ok=True)
            bot_sheet.write_text(v3_source, encoding="utf-8")

            mod.inject_zero_perm_pip_patches(str(repo))
            once = bot_sheet.read_text(encoding="utf-8")
            self.assertIn("COLGRAM_MINIFLOAT_PATCH = 4", once)
            self.assertNotIn("COLGRAM_MINIFLOAT_PATCH = 3", once)
            self.assertNotIn("hasOverlayPermission", once)
            self.assertNotIn("AlertDialog.Builder", once)
            self.assertIn("ColgramFloatWindowManager.open", once)

            mod.inject_zero_perm_pip_patches(str(repo))
            twice = bot_sheet.read_text(encoding="utf-8")
            self.assertEqual(once, twice)

    def test_ws_bridge_pump_disables_idle_client_ping(self):
        src = read_text("colgram-core/src/main/java/org/colgram/core/ColgramTelegramWsBridge.java")
        pump_body = extract_method(src, "public static long pumpUpstreamFrames(")
        self.assertIn("session.socket.setSoTimeout(0)", pump_body)
        self.assertNotIn("sendPing()", pump_body)
        self.assertNotIn("WS_IDLE_PING_TIMEOUT_MS", src)

    def test_proxy_manager_network_callback_dedup_and_connected_failure_reset(self):
        src = read_text("colgram-core/src/main/java/org/colgram/core/ColgramProxyManager.java")
        self.assertIn("lastActiveNetworkHandle", src)
        inv_body = extract_method(src, "private static void invalidateNetworkProbeState(")
        self.assertIn("ColgramDpiBypass.clearTransientCooldowns()", inv_body)
        self.assertIn("ColgramDpiBypass.clearConnectionFailureBackoff()", inv_body)
        wd_body = extract_method(src, "private static void connectionWatchdogTick()")
        self.assertIn("applied.nativeFailures = 0;", wd_body)

    def test_dpi_bypass_pipe_ws_guards_deprioritize_on_clean_client_close(self):
        src = read_text("colgram-core/src/main/java/org/colgram/core/ColgramDpiBypass.java")
        pipe_ws = extract_method(
            src,
            "private static void pipeOverWebSocket(final Socket client, final ColgramTelegramWsBridge.WsSession session,\n"
            "                                          final CallListener call, final long generation,\n"
            "                                          final boolean viaL3Relay)",
        )
        self.assertIn("clientSentInit", pipe_ws)
        self.assertIn("clientClosedFirst", pipe_ws)
        self.assertIn("clientSentInit.get() && !clientClosedFirst.get()", pipe_ws)

    def test_apply_patches_fixes_proxy_rotation_precedence(self):
        src = read_text("scripts/apply-patches.py")
        self.assertIn("ProxyRotationController.java", src)
        self.assertIn("!SharedConfig.isProxyEnabled() || !SharedConfig.proxyRotationEnabled", src)

    def test_pm_mute_sticky_message_race_eliminated(self):
        bridge = read_text("scripts/templates/ColgramNativeColemailerBridge.java")
        auto_del = extract_method(bridge, "private static void executeDmInstantAutoDelete(")
        self.assertIn("ColgramHookHandler.markForcePurged(dialogId, msgId)", auto_del)
        self.assertIn("NotificationCenter.messagesDeleted", auto_del)
        self.assertIn("220L", auto_del)

        ap = read_text("scripts/apply-patches.py")
        self.assertIn("inject_reliability_and_backup_patches(", ap)
        self.assertIn("isForcePurged(dialog_id, messageObject.getId())", ap)
        self.assertIn("isDmAutoDeleteMuted(currentAccount, dialog_id)", ap)
        self.assertIn("ConnectionsManager.getInstance(currentAccount).getCurrentTime()", ap)

    def test_global_search_is_pure_global_without_local_chats_pollution(self):
        gs = read_text("scripts/templates/ColgramGlobalSearchView.java")
        self.assertNotIn("runLocalSearch", gs)
        self.assertNotIn("Чаты и контакты", gs)
        self.assertNotIn("Недавние чаты", gs)
        self.assertIn("TL_contacts_search", gs)
        self.assertIn("TL_contacts_resolveUsername", gs)
        self.assertIn("TL_channels_searchPosts", gs)
        self.assertIn("isPublicDiscoverablePeer", gs)
        self.assertIn("getTranslitString", gs)
        self.assertIn("getChannelRecommendations(0)", gs)
        self.assertIn("onChannelRecommendationsLoaded()", gs)

    def test_bypass_wake_reaper_and_anti_logout_contracts(self):
        dpi = read_text("colgram-core/src/main/java/org/colgram/core/ColgramDpiBypass.java")
        self.assertIn("reapStaleSessions(", dpi)
        self.assertIn("pinnedTelegramL3Relay", dpi)
        self.assertIn("resetL3WhitelistCache()", dpi)
        self.assertIn("!overlayActive && !telegram && isEligibleForL3RelayCascade", dpi)

        pm = read_text("colgram-core/src/main/java/org/colgram/core/ColgramProxyManager.java")
        self.assertIn("public static void onScreenTurnedOn()", pm)
        self.assertIn("tgnetForceReconnect(", pm)
        self.assertIn("AUTO_EXIT_IP_SWITCH_COOLDOWN_MS", pm)
        self.assertIn("postWakeVerifyRunner", pm)

        defender = read_text("colgram-core/src/main/java/org/colgram/core/ColgramSecurityDefender.java")
        audit_body = extract_method(defender, "public static synchronized AuditReport runFullAuditAndHeal(")
        self.assertNotIn("ColgramConfig.setCloakEnabled(true)", audit_body)

    def test_persistent_account_backup_and_restore_contracts(self):
        pb = read_text("colgram-core/src/main/java/org/colgram/core/ColgramPersistentBackup.java")
        self.assertIn("restoreIfFreshInstallSync(", pb)
        self.assertIn("scheduleAutoBackup(", pb)
        self.assertIn("backupNow(", pb)
        self.assertIn("buildOpenBackupIntent()", pb)
        self.assertIn("Documents/ColgramBackup", pb)
        self.assertNotIn("MANAGE_EXTERNAL_STORAGE", pb)
        self.assertNotIn("READ_PHONE_STATE", pb)

        am = read_text("colgram-core/src/main/java/org/colgram/core/ColgramAccountManager.java")
        self.assertIn("exportDecryptedVaultBundles(", am)
        self.assertIn("importDecryptedVaultBundles(", am)

        ap = read_text("scripts/apply-patches.py")
        self.assertIn('android:hasFragileUserData="true"', ap)
        self.assertIn("restoreIfFreshInstallSync", ap)
        self.assertIn("ColgramPersistentBackup.scheduleAutoBackup", ap)
        self.assertIn("onPersistentBackupActivityResult", ap)
        self.assertIn("restoreBackupTextView", ap)
        self.assertIn("suppressed spurious MTProto onLogout for bot slot=", ap)


if __name__ == "__main__":
    unittest.main()
