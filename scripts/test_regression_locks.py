"""Master Hard Non-Regression Lock Matrix for ci194 Preservation + cg12 Fixes across Workstreams A, B, and C."""
from __future__ import annotations

import os
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "colgram-core/src/main/java/org/colgram/core"
SCRIPTS = ROOT / "scripts"
TEMPLATES = SCRIPTS / "templates"
TG_SRC = ROOT / "Telegram-Src"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="ignore")


def _extract_method(source: str, signature_prefix: str) -> str:
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


class Ci194HardPreservationLocksTest(unittest.TestCase):
    """Locks all 16 verified ci194 fixes so future edits or merges never regress them."""

    def test_01_r8_go_keep_and_ci_usage_gate(self):
        ksp = _read(SCRIPTS / "koliti_split_patches.py")
        for rule in ("-keep class go.** { *; }", "-keep class io.nekohasekai.** { *; }", "-keep class org.colgram.** { *; }"):
            self.assertIn(rule, ksp)
        wf = _read(ROOT / ".github/workflows/build-colgram.yml")
        self.assertGreaterEqual(wf.count(r"^go\.(Seq|Universe|error)"), 2)
        if TG_SRC.is_dir():
            pro = _read(TG_SRC / "TMessagesProj/proguard-rules.pro")
            self.assertIn("-keep class go.** { *; }", pro)
            self.assertIn("-keep class io.nekohasekai.** { *; }", pro)

    def test_02_fgs_subtype_and_vpn_intent_filter(self):
        core_manifest = _read(ROOT / "colgram-core/src/main/AndroidManifest.xml")
        self.assertIn("PROPERTY_SPECIAL_USE_FGS_SUBTYPE", core_manifest)
        self.assertNotIn("PROPERTY_SPECIAL_USE_FGS_SUBJECT", core_manifest)
        self.assertIn('<action android:name="android.net.VpnService" />', core_manifest)
        ap = _read(SCRIPTS / "apply-patches.py")
        self.assertIn("PROPERTY_SPECIAL_USE_FGS_SUBTYPE", ap)
        self.assertIn("org.colgram.singbox.ColgramVpnService", ap)
        if TG_SRC.is_dir():
            tg_manifest = _read(TG_SRC / "TMessagesProj/src/main/AndroidManifest.xml")
            self.assertNotIn("PROPERTY_SPECIAL_USE_FGS_SUBJECT", tg_manifest)
            self.assertGreaterEqual(tg_manifest.count("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"), 2)
            self.assertIn('<action android:name="android.net.VpnService" />', tg_manifest)

    def test_03_standalone_release_sourceset_and_stale_native_cleanup(self):
        ksp = _read(SCRIPTS / "koliti_split_patches.py")
        self.assertIn("sourceSets.release", ksp)
        self.assertIn("AndroidManifest_standalone.xml", ksp)
        wf = _read(ROOT / ".github/workflows/build-colgram.yml")
        self.assertIn("merged_native_libs", wf)
        self.assertIn("stripped_native_libs", wf)

    def test_04_dpi_bypass_daemon_explicit_stop_and_transport_vpn(self):
        dpi = _read(CORE / "ColgramDpiBypass.java")
        for token in ("setDaemon(true)", "stoppedExplicitly", "MAX_WORKER_POOL_THREADS = 384", "MAX_ACTIVE_SESSIONS = 160", "NetworkCapabilities.TRANSPORT_VPN"):
            self.assertIn(token, dpi)
        hh = _read(CORE / "ColgramHookHandler.java")
        auto_restart = _extract_method(hh, "public static boolean canAutoRestartDpiBypass()")
        self.assertIn("isWarpEnabled()", auto_restart)
        self.assertIn("wasExplicitlyStopped()", auto_restart)

    def test_05_proxy_list_activity_stable_ids_and_system_dpi(self):
        ksp = _read(SCRIPTS / "koliti_split_patches.py")
        self.assertIn("colgramTelemetryRow", ksp)
        self.assertIn("return -26;", ksp)
        self.assertIn("return -25;", ksp)
        if TG_SRC.is_dir():
            pa = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java")
            self.assertIn("colgramTelemetryRow", pa)
            self.assertIn("-26", pa)
            self.assertIn("-25", pa)
            self.assertIn("ColgramCallSocksTunnel.awaitStopped(app, 3000L)", pa)
            self.assertIn("ColgramProxyManager.recheckBypassListener()", pa)

    def test_06_proxy_manager_detach_recovery_and_backoff(self):
        pm = _read(CORE / "ColgramProxyManager.java")
        for token in ("isTgnetProxyDetached()", '"colgramGetLastProxySignature"', "onTelegramProxyCleared()", "tgnetSoftResume", "FORCE_RECONNECT_COOLDOWN_MS = 4_500L", "FORCE_RECONNECT_MAX_BACKOFF_MS = 30_000L"):
            self.assertIn(token, pm)

    def test_07_doh_resolver_strict_encrypted_dns_and_ru_fallback(self):
        doh = _read(CORE / "ColgramDohResolver.java")
        self.assertIn("isStrictEncryptedDnsOnly()", doh)
        self.assertIn("ColgramConfig.isMaxAnonymityEnabled()", doh)
        self.assertIn("141.133.184.24", doh)

    def test_08_explicit_is_auto_download_and_manual_safety_sheet(self):
        ssp = _read(SCRIPTS / "spam_storage_patches.py")
        self.assertIn("final boolean isAutoDownload", ssp)
        self.assertIn("ColgramUiKit.showMalwareWarningSheet(", ssp)
        if TG_SRC.is_dir():
            fl = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java")
            self.assertIn("final boolean isAutoDownload", fl)

    def test_09_native_tgnet_check_pending_tasks_before_push(self):
        ap = _read(SCRIPTS / "apply-patches.py")
        self.assertIn("networkManager->checkPendingTasks();", ap)
        if TG_SRC.is_dir():
            cpp = _read(TG_SRC / "TMessagesProj/jni/tgnet/ConnectionsManager.cpp")
            self.assertLess(cpp.index("networkManager->checkPendingTasks();"), cpp.index("createPushConnection()"))

    def test_10_application_loader_isolated_bootstrap_order(self):
        ap = _read(SCRIPTS / "apply-patches.py")
        self.assertIn("ColgramUiBridge.install(this)", ap)
        self.assertIn("ColgramHookHandler.init(applicationContext)", ap)
        if TG_SRC.is_dir():
            al = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java")
            self.assertLess(al.index("ColgramUiBridge.install(this)"), al.index("ColgramHookHandler.init(applicationContext)"))

    def test_11_storage_sandbox_single_root_and_nomedia(self):
        sb = _read(CORE / "ColgramStorageSandbox.java")
        for token in ("ensureDirWithNoMedia(", "canUseRootFast(", "getCanonicalPath()"):
            self.assertIn(token, sb)

    def test_12_auto_delete_media_task_dedup_and_protections(self):
        ssp = _read(SCRIPTS / "spam_storage_patches.py")
        for token in ("colgramMergeSandboxDirsDedup(", "isProtectedDirectoryName(", "isProtectedFile(", "maxCacheGb < 0"):
            self.assertIn(token, ssp)
        if TG_SRC.is_dir():
            adt = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/messenger/AutoDeleteMediaTask.java")
            for token in ("colgramMergeSandboxDirsDedup(", "isProtectedDirectoryName(", "maxCacheGb < 0"):
                self.assertIn(token, adt)

    def test_13_file_upload_operation_concurrency_and_64bit_offset(self):
        adp = _read(SCRIPTS / "attach_del_perf_patches.py")
        for token in ("colgram-unlock-in-cleanup", "volatile int state", "colgramFailedNotified", "(long) currentRequestPartNum * (long) uploadChunkSize"):
            self.assertIn(token, adp)
        if TG_SRC.is_dir():
            fuo = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/messenger/FileUploadOperation.java")
            self.assertIn("colgram-unlock-in-cleanup", fuo)
            self.assertIn("volatile int state", fuo)

    def test_14_unlimited_memory_strict_codec_fidelity(self):
        um = _read(CORE / "ColgramUnlimitedMemory.java")
        for token in ("!outWebp && !outJpg", "Bitmap.CompressFormat.JPEG", "Bitmap.CompressFormat.WEBP"):
            self.assertIn(token, um)

    def test_15_messages_storage_tx_outside_loop_and_self_heal(self):
        adp = _read(SCRIPTS / "attach_del_perf_patches.py")
        self.assertIn("colgram-tx-outside-loop", adp)
        self.assertIn("colgram_purged_neg_mids_acc_", adp)
        if TG_SRC.is_dir():
            ms = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")
            self.assertIn("colgram-tx-outside-loop", ms)
            self.assertIn("colgram_purged_neg_mids_acc_", ms)

    def test_16_chat_attach_alert_qualified_hud_and_dismiss(self):
        adp = _read(SCRIPTS / "attach_del_perf_patches.py")
        self.assertIn("org.telegram.ui.ColgramUiKit.ProgressHudHandle", adp)
        self.assertIn("colgramImportHud.dismiss();", adp)
        if TG_SRC.is_dir():
            ca = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java")
            self.assertIn("org.telegram.ui.ColgramUiKit.ProgressHudHandle", ca)
            self.assertIn("colgramImportHud.dismiss();", ca)


class Cg12FixLocksTest(unittest.TestCase):
    """Locks every cg12 fix across Workstream A (Upload/Transport), Workstream B (Channels/Storage), and Workstream C (Tags/Read)."""

    def test_workstream_a_core_transport_and_import_locks(self):
        ws = _read(CORE / "ColgramTelegramWsBridge.java")
        send_internal = _extract_method(ws, "private void sendBinaryInternal(")
        self.assertNotIn("MAX_OUTBOUND_FRAME_BYTES", send_internal)
        self.assertIn("writeFrameLocked(0x82", send_internal)
        dc135_cd = re.search(r"DC135_DEAD_COOLDOWN_MS\s*=\s*([\d_]+)L;", ws)
        self.assertIsNotNone(dc135_cd)
        self.assertLessEqual(int(dc135_cd.group(1).replace("_", "")), 4000)
        dc135_max = re.search(r"DC135_MAX_BACKOFF_MS\s*=\s*([\d_]+)L;", ws)
        self.assertIsNotNone(dc135_max)
        self.assertLessEqual(int(dc135_max.group(1).replace("_", "")), 5000)
        build_cands = _extract_method(ws, "static List<EdgeEndpoint> buildCandidateEndpoints(")
        self.assertNotIn('lastWorking.sniHost.toLowerCase(Locale.US).contains("-1.")', build_cands)

        dpi = _read(CORE / "ColgramDpiBypass.java")
        self.assertIn("volatile boolean writingUpstream", dpi)
        pipe_ws = _extract_method(dpi, "                                          final boolean viaL3Relay) {")
        self.assertLess(pipe_ws.index("tracked.onClientTx("), pipe_ws.index("splitter.feedToSession("))
        self.assertLess(pipe_ws.index("tracked.writingUpstream = true"), pipe_ws.index("splitter.feedToSession("))

        fi = _read(CORE / "ColgramFileImport.java")
        import_fn = _extract_method(fi, "private static boolean importUrisAsync(")
        self.assertLess(import_fn.index("openAssetFileDescriptor"), import_fn.index("IMPORT_EXECUTOR.execute("))

        tr = _read(CORE / "ColgramLocalTranslator.java")
        self.assertIn("ColgramConfig.isMaxAnonymityEnabled()", tr)

    def test_workstream_a_telegram_src_locks(self):
        if not TG_SRC.is_dir():
            self.skipTest("Telegram-Src not cloned")
        cm = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java")
        self.assertNotIn("colgramIsDirectSendResponseSafe", cm)
        self.assertIn("ColgramHookHandler.canAutoRestartDpiBypass()", cm)
        integrity = _extract_method(cm, "public static void onIntegrityCheckClassic(")
        self.assertIn("ColgramConfig.isMaxAnonymityEnabled()", integrity)

        ca = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
        self.assertNotIn("waitingForLoad.remove(scheduledLoadIndex)", ca)

        fl = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java")
        fl_internal = fl.split("private FileLoadOperation loadFileInternal(", 1)[1].split("\n    public static boolean canSaveAsFile(", 1)[0]
        self.assertIn("priority >= FileLoaderPriorityQueue.PRIORITY_VALUE_NORMAL_UP", fl_internal)

        il = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/messenger/ImageLoader.java")
        self.assertIn("512 * 512 * 2 * 4 * 5", il)
        self.assertNotIn("512 * 512 * 2 * 4 * 2", il)

    def test_workstream_b_channels_spam_and_storage_locks(self):
        br = _read(TEMPLATES / "ColgramNativeColemailerBridge.java")
        intercept = _extract_method(br, "public static boolean interceptIncomingSpamPing(")
        self.assertIn("ChatObject.isChannelAndNotMegaGroup(chat)", intercept)
        self.assertIn("if (msg.post) return false;", intercept)
        self.assertIn("if (!isPing) return false;", intercept)
        self.assertIn("86400", intercept)

        sg = _read(CORE / "ColgramSpamGuard.java")
        diag = _extract_method(sg[sg.rfind("public static SpamReport diagnose("):], "public static SpamReport diagnose(")
        self.assertIn("if (viaBot && isPing)", diag)
        self.assertIn("isPing && hasInvisibleGhostChars(text)", diag)

        cfg = _read(CORE / "ColgramConfig.java")
        self.assertIn("colgram_pre_v4_unlimited_optin", cfg)
        self.assertIn("wasPreV4UnlimitedOptIn()", cfg)
        sc = _read(CORE / "ColgramShieldConfig.java")
        self.assertIn("ColgramConfig.wasPreV4UnlimitedOptIn()", sc)
        ce = _read(CORE / "ColgramStorageCapEnforcer.java")
        self.assertIn("isUiEssentialCacheAsset(", ce)
        self.assertIn("1.15d", ce)

        if TG_SRC.is_dir():
            dc = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/messenger/DownloadController.java")
            self.assertNotIn("Math.min(600,", dc)
            self.assertNotIn("!org.colgram.core.ColgramShieldConfig.isMalwareDownloadShieldEnabled()", dc)
            au = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java")
            self.assertIn("ColgramStorageSandbox.getCacheRootDir(", au)
            self.assertIn("ColgramStorageSandbox.getSandboxRootDir(", au)

    def test_workstream_c_native_tags_and_read_symbiosis_locks(self):
        self.assertFalse((TEMPLATES / "ColgramSavedTagsBarView.java").exists(), "Duplicate ColgramSavedTagsBarView must be deleted")
        self.assertTrue((TEMPLATES / "ColgramNativeTagsBridge.java").is_file())
        self.assertTrue((TEMPLATES / "ColgramReadReceiptsHelper.java").is_file())
        self.assertTrue((CORE / "ColgramLocalReadStore.java").is_file())
        self.assertTrue((SCRIPTS / "read_symbiosis_patches.py").is_file())

        ap = _read(SCRIPTS / "apply-patches.py")
        self.assertNotIn("ColgramSavedTagsBarView.java", ap)
        self.assertIn("ColgramNativeTagsBridge.java", ap)
        self.assertIn("ColgramReadReceiptsHelper.java", ap)
        self.assertIn("read_symbiosis_patches", ap)

        seen_sheet = _read(TEMPLATES / "ColgramMessageSeenSheet.java")
        self.assertIn("ColgramReadReceiptsHelper", seen_sheet)

        if TG_SRC.is_dir():
            ca = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
            cmc = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java")
            for forbidden in ("ColgramSavedTagsBarView", "OPTION_COLGRAM_SAVED_TAG", "colgramDrawSavedTagPills"):
                self.assertNotIn(forbidden, ca)
                self.assertNotIn(forbidden, cmc)

            for rel in (
                "TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java",
                "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java",
                "TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java",
            ):
                self.assertIn("ColgramNativeTagsBridge", _read(TG_SRC / rel), rel)
            self.assertIn(
                "colgramLocalTagOnly",
                _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java"),
            )

            for rel in (
                "TMessagesProj/src/main/java/org/telegram/ui/MessageSeenView.java",
                "TMessagesProj/src/main/java/org/telegram/ui/Components/ReactedHeaderView.java",
                "TMessagesProj/src/main/java/org/telegram/ui/Components/MessagePrivateSeenView.java",
            ):
                self.assertIn("ColgramReadReceiptsHelper", _read(TG_SRC / rel), rel)

            dc = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java")
            self.assertIn("isOutboxMessageRead(", dc)
            self.assertIn("isOutboxMessageRead(", cmc)

            ms = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java")
            mc = _read(TG_SRC / "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java")
            self.assertIn("ColgramLocalReadStore", ms)
            self.assertIn("ColgramLocalReadStore", mc)


if __name__ == "__main__":
    unittest.main()
