"""Contract and convergence tests for Track A (bypass stability, background sync, reactions, media, and native desync)."""
from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "colgram-core/src/main/java/org/colgram/core"
JNI_DIR = ROOT / "colgram-core/src/main/jniLibs"
TG_JNI_DIR = ROOT / "Telegram-Src/colgram-core/src/main/jniLibs"
CPP = ROOT / "colgram-core/src/main/cpp"

TRACK_A_TELEGRAM_RELS = (
    "TMessagesProj/src/main/AndroidManifest.xml",
    "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java",
    "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java",
    "TMessagesProj/src/main/java/org/telegram/ui/Components/Reactions/ReactionsLayoutInBubble.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/FileLoadOperation.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/FileLoaderPriorityQueue.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java",
    "TMessagesProj/src/main/java/org/telegram/ui/Components/AnimatedEmojiDrawable.java",
    "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/video/VideoAds.java",
    "TMessagesProj/src/main/java/org/telegram/ui/Stories/recorder/DualCameraView.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/PushListenerController.java",
    "TMessagesProj/src/main/java/org/telegram/ui/PremiumPreviewFragment.java",
    "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java",
    "TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java",
    "TMessagesProj/src/main/java/org/webrtc/NetworkMonitorAutoDetect.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/secretmedia/ExtendedDefaultDataSourceFactory.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/secretmedia/ExtendedDefaultDataSource.java",
    "TMessagesProj/src/main/java/org/telegram/ui/web/BotWebViewContainer.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/ImageLoader.java",
)
IMAGE_LOADER_REL = TRACK_A_TELEGRAM_RELS[-1]


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="ignore")


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


class CoreBypassAndBackgroundContractsTest(unittest.TestCase):
    def test_dpi_bypass_pool_split_reverse_eviction_prewarm_and_buffers(self):
        dpi = read_text(CORE / "ColgramDpiBypass.java")
        self.assertIn("MAX_WORKER_POOL_THREADS = 384", dpi)
        self.assertIn("MAX_ACTIVE_SESSIONS = 160", dpi)
        self.assertIn("handshakePool", dpi)
        self.assertIn("dialRacePool", dpi)
        self.assertIn("static boolean executeDialRace(", dpi)
        self.assertIn("public static boolean hasRecentRxWithin(long windowMs)", dpi)
        self.assertIn("STREAM_COPY_BUFFER_BYTES = 32768", dpi)
        self.assertNotIn("SOCKET_BUFFER_BYTES", dpi)
        self.assertIn("public static String getLiveModeLabel()", dpi)
        self.assertIn("public static int getActiveSessionCount()", dpi)
        self.assertIn("isBlockedLoopbackTarget(", dpi)
        self.assertIn("isMultiTenantSharedDomain(", dpi)

        evict = extract_method(dpi, "private static void evictOverflowSessionsLocked()")
        self.assertIn("sessionEvictTier(s, nowMs)", evict)
        tier = extract_method(dpi, "private static int sessionEvictTier(")
        self.assertIn("isClosed()", tier)
        self.assertIn(">= 15_000L", tier)

        close_all = extract_method(dpi, "public static int closeAllActiveSessions()")
        self.assertIn("ColgramTelegramWsBridge.drainPrewarmedPool()", close_all)

        pipe = extract_method(
            dpi,
            "private static void pipeOverWebSocket(final Socket client, final ColgramTelegramWsBridge.WsSession session,\n"
            "                                          final CallListener call, final long generation,\n"
            "                                          final boolean viaL3Relay)",
        )
        self.assertIn("if (!viaL3Relay && totalS2C == 0L && clientSentInit.get() && !clientClosedFirst.get())", pipe)
        self.assertIn("stepSession.fromPrewarm", pipe)
        self.assertIn("tryRedialFreshPrewarmSession(", pipe)
        self.assertIn("evictPrewarmedBucket(", pipe)
        self.assertIn("lastTxAtMs", dpi)
        self.assertIn("void setUpstream(", dpi)
        self.assertIn("probeClientClosedQuick(", dpi)
        self.assertIn("long wsHeadStartMs = cellularNetwork ? 0L : 25L;", dpi)
        self.assertIn("ColgramSocks5Codec.readFully(in, 2)", dpi)
        self.assertIn("RELAY_BUF_POOL_MAX = 24;", dpi)
        self.assertIn("UDP_BUF_POOL_MAX = 4;", dpi)
        self.assertIn("borrowRelayBuffer()", dpi)
        self.assertIn("recycleRelayBuffer(", dpi)
        self.assertIn("borrowUdpBuffer()", dpi)
        self.assertIn("setThreadPriorityQuiet(", dpi)
        self.assertIn("THREAD_PRIORITY_LESS_FAVORABLE", dpi)
        self.assertIn("THREAD_PRIORITY_BACKGROUND", dpi)
        # Ensure hot relay / UDP loops no longer allocate per-loop 32KB/65KB arrays
        pump_assoc = extract_method(dpi, "private static void pumpAssociated(")
        self.assertIn("borrowUdpBuffer()", pump_assoc)
        self.assertNotIn("new byte[65500]", pump_assoc)

    def test_ws_bridge_native_keepalive_wrap_fix_and_prewarm_safety(self):
        ws = read_text(CORE / "ColgramTelegramWsBridge.java")
        self.assertIn("volatile boolean fromPrewarm = false;", ws)
        self.assertIn("PREWARM_MAX_PER_BUCKET = 3;", ws)
        self.assertIn("PREWARM_MEDIA_MAX_PER_BUCKET = 4;", ws)
        self.assertIn("DEFAULT_EDGE_DEPRIORITIZE_TTL_MS = 8_000L;", ws)
        self.assertIn("DC135_DEAD_COOLDOWN_MS = 12_000L;", ws)
        self.assertIn("DC135_WIFI_CONNECT_TIMEOUT_MS = 1200;", ws)
        self.assertIn("DC135_CELLULAR_CONNECT_TIMEOUT_MS = 1600;", ws)
        self.assertIn("PREWARM_TTL_MS = 38_000L;", ws)
        self.assertIn("PREWARM_REFRESH_MS = 28_000L;", ws)
        self.assertIn("maintainForegroundPrewarmIfNeeded(", ws)
        self.assertIn("replenishPrewarmedAsync(normDc, media, 1);", extract_method(ws, "public static void maintainForegroundPrewarmIfNeeded("))
        self.assertIn("wrapBufferedOutput(", ws)
        self.assertIn("public final Socket rawSocket;", ws)
        self.assertIn("EMPTY_BYTES = new byte[0];", ws)
        self.assertIn("createWsDaemonPool(", ws)
        self.assertIn("THREAD_PRIORITY_LESS_FAVORABLE", ws)
        self.assertIn("THREAD_PRIORITY_BACKGROUND", ws)
        self.assertIn("public static void onBackgroundPause()", ws)
        self.assertIn("public static void onForegroundResume()", ws)
        self.assertIn("public static String getLiveTelemetrySummary()", ws)
        self.assertIn("public static int getLastHandshakeRttMs()", ws)
        self.assertIn("public static int getPrewarmedTotalCount()", ws)
        replenish = extract_method(ws, "private static void replenishPrewarmedAsync(")
        self.assertIn("!ColgramUiBridge.isAppInForeground()", replenish)
        self.assertIn("int deficit = Math.min(Math.max(1, maxToSpawn), freeSlots);", replenish)
        self.assertIn(
            "hit.fromPrewarm = true;",
            extract_method(ws, "static WsSession takePrewarmed(int normDc, boolean media, boolean wantProtected)"),
        )
        self.assertIn("public static int drainPrewarmedPool()", ws)
        self.assertIn("public static void evictPrewarmedBucket(int dcId)", ws)
        self.assertIn("public static WsSession dialFreshForDc(", ws)
        build_cands = extract_method(
            ws,
            "static List<EdgeEndpoint> buildCandidateEndpoints(\n"
            "            int dcId,\n"
            "            EdgeEndpoint lastWorking,\n"
            "            Map<String, List<String>> hostIps,\n"
            "            Set<String> deprioritizedKeys,\n"
            "            boolean media,\n"
            "            boolean includeIpv6)",
        )
        self.assertIn("if (!includeIpv6 && ip.indexOf(':') >= 0) continue;", build_cands)
        self.assertIn("if (!includeIpv6 && secIp.indexOf(':') >= 0) continue;", build_cands)

        wrap = extract_method(ws, "private static Socket wrapSocketForMode(")
        self.assertIn("if (base instanceof ColgramNativeDesync.NativeSocket && isNativeFragMode(fragMode))", wrap)

        race = extract_method(ws, "private static DialedTcp connectEdgeRace(")
        self.assertIn("final boolean useNativeSocket = ColgramNativeDesync.isAvailable()", race)
        self.assertNotIn("setReceiveBufferSize(262144)", race)
        self.assertNotIn("setSendBufferSize(262144)", race)

        pump = extract_method(ws, "public static long pumpUpstreamFrames(")
        self.assertIn("ColgramDpiBypass.borrowRelayBuffer()", pump)
        self.assertIn("ColgramDpiBypass.recycleRelayBuffer(buf)", pump)
        self.assertIn("session.socket.setSoTimeout(0)", pump)
        self.assertNotIn("sendPing(", pump)

        write_frame = extract_method(ws, "private void writeFrameLocked(")
        self.assertIn("writeScratch.length > 8192 && needed <= 8192", write_frame)

    def test_proxy_manager_network_diff_watchdog_and_battery_timers(self):
        pm = read_text(CORE / "ColgramProxyManager.java")
        self.assertIn("pool.allowCoreThreadTimeOut(true);", pm)
        self.assertIn("sched.allowCoreThreadTimeOut(true);", pm)
        self.assertIn("lastCapTransportMask", pm)
        self.assertIn("mainDcStallSinceMs", pm)
        self.assertIn("colgramResetProxySignatureCached()", pm)

        inv = extract_method(pm, "private static void invalidateNetworkProbeState(String reason)")
        self.assertNotIn('"link properties changed".equals(reason)\n', inv)
        self.assertNotIn('"network validated".equals(reason)', inv)
        self.assertIn("ColgramTelegramWsBridge.drainPrewarmedPool()", inv)

        vpn = extract_method(pm, "private static boolean hasSystemVpn(Context context)")
        self.assertNotIn("return true;", vpn)

        wd = extract_method(pm, "private static void connectionWatchdogTick()")
        self.assertIn("mainDcStallTimedOut", wd)
        self.assertIn(">= 40_000L", wd)
        self.assertIn("hasRecentRxWithin(25_000L)", wd)
        self.assertIn("maintainForegroundPrewarmIfNeeded(", wd)

        screen_on = extract_method(pm, "public static void onScreenTurnedOn(")
        self.assertIn("120_000L", screen_on)
        self.assertNotIn("tgnetForceReconnect(", screen_on)
        fg = extract_method(pm, "public static void onAppForegrounded(")
        self.assertIn(">= 45_000L", fg)
        self.assertIn("120_000L", fg)
        self.assertIn("6_500L", fg)
        self.assertNotIn("tgnetForceReconnect(", fg)
        fr = extract_method(pm, "private static void tgnetForceReconnect(String reason)")
        self.assertIn("currentForceReconnectBackoffMs()", fr)
        self.assertIn("FORCE_RECONNECT_MAX_BACKOFF_MS = 120_000L;", pm)
        self.assertIn("tgCmNativeResumeNetwork", pm)

        stop_bg = extract_method(pm, "private static void stopBackgroundFeedScraping()")
        self.assertIn("watchdogTask", stop_bg)
        self.assertIn("healthCheckTask", stop_bg)

        lwatch = extract_method(pm, "private static void ensureLocalWatchSchedulerRunning()")
        self.assertIn("foreground ? 30L : 900L", lwatch)
        self.assertIn("onBackgroundPause()", extract_method(pm, "public static void onAppBackgrounded()"))
        self.assertNotIn("awayMs >= 20_000L", extract_method(pm, "public static void onAppForegrounded()"))

        act = extract_method(pm, "public static void activateBuiltinProxy(final Context context)")
        exec_idx = act.index("executor.execute(() -> {")
        pre_exec = act[:exec_idx]
        post_exec = act[exec_idx:]
        self.assertNotIn("registerNetworkCallback(", pre_exec)
        self.assertNotIn("scheduleProxyRefresh(", pre_exec)
        self.assertNotIn("mainconfig", pre_exec)
        self.assertIn("ColgramDpiBypass.setAppContext(appContext);", post_exec)
        self.assertIn("registerNetworkCallback(appContext);", post_exec)
        self.assertIn("ColgramBootJobService.scheduleProxyRefresh(appContext);", post_exec)
        self.assertIn("ensureLocalWatchSchedulerRunning();", post_exec)

        act_now = extract_method(pm, "private static void activateBuiltinProxyNow(long startupEpoch)")
        self.assertLess(act_now.index("ColgramDpiBypass.startImmediately();"), act_now.index("mainHandler.post("))

    def test_helpers_doze_sync_build_flag_and_native_c(self):
        remap = read_text(CORE / "ColgramDcRemap.java")
        self.assertIn("STREAM_COPY_BUFFER_BYTES = 32768", remap)
        self.assertNotIn("SOCKET_BUFFER_BYTES", remap)
        self.assertIn("if (upstreamBytes < MIN_ANSWER_BYTES) {", remap)
        self.assertGreaterEqual(remap.count("setThreadPriorityQuiet(android.os.Process.THREAD_PRIORITY_BACKGROUND)"), 5)

        front = read_text(CORE / "ColgramLocalTcpFront.java")
        self.assertIn("setThreadPriorityQuiet(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE)", front)

        doh = read_text(CORE / "ColgramDohResolver.java")
        self.assertGreaterEqual(doh.count("setThreadPriorityQuiet(android.os.Process.THREAD_PRIORITY_BACKGROUND)"), 2)
        pref = extract_method(doh, "public static void prefetchCriticalHosts()")
        self.assertIn("ColgramDpiBypass.activeSystemDpiVpn() != null", pref)
        self.assertIn('".telegram.dog"', pref)

        job = read_text(CORE / "ColgramBootJobService.java")
        self.assertIn("ColgramDpiBypass.hasRecentRxWithin(180_000L)", job)
        self.assertNotIn("bytesFromTarget()", job)

        bot = read_text(CORE / "ColgramBotSync.java")
        bot_loop = extract_method(bot, "private static void ensureMtprotoBotLoop(")
        self.assertIn("ColgramPowerGuard.shouldThrottle(context)", bot_loop)
        self.assertIn("180_000L", bot_loop)
        self.assertIn("stageQueue", bot)

        fgs = read_text(CORE / "ColgramForegroundService.java")
        self.assertIn("DOZE_KEEPALIVE_INTERVAL_MS = 900_000L", fgs)
        self.assertIn("DOZE_WAKELOCK_MS = 1_500L", fgs)
        self.assertIn("DOZE_PULSE_HOLD_MS = 250L", fgs)
        self.assertIn("cachedStageQueuePostRunnable", fgs)
        trim = extract_method(fgs, "public void onTrimMemory(int level)")
        self.assertIn("ColgramBuildFlags.KOLITI_ENABLED", trim)
        doze = extract_method(fgs, "public static void handleDozeKeepalivePulse(Context context)")
        self.assertIn("ColgramDpiBypass.reapStaleSessions(10_000L, 0L)", doze)
        self.assertNotIn("kickActiveHumanAccounts(true, true)", doze)
        self.assertIn("DOZE_PULSE_HOLD_MS", doze)

        c_src = read_text(CPP / "colgram_desync.c")
        self.assertIn("atomic_int window_clamped;", c_src)
        self.assertIn("if (off > arr_len || len > arr_len - off)", c_src)
        self.assertIn("ExceptionCheck", c_src)
        self.assertNotIn("buf_sz = 262144", c_src)
        self.assertIn("int buf_sz = 131072;", c_src)
        self.assertIn("int keepidle = 180;", c_src)
        self.assertIn("int keepintvl = 30;", c_src)
        self.assertIn("int user_timeout = 45000;", c_src)
        self.assertIn("wait_pollout_slices(", c_src)
        self.assertIn("TCP_QUICKACK", c_src)
        self.assertIn("nativeIsSocketIdleClean(", c_src)
        self.assertIn("nativeSetKeepalive(", c_src)
        native_java = read_text(CORE / "ColgramNativeDesync.java")
        self.assertIn("setSessionCacheSize(32)", native_java)
        self.assertIn("setSessionTimeout(3600)", native_java)

        for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
            so_core = JNI_DIR / abi / "libcolgramdesync.so"
            so_tg = TG_JNI_DIR / abi / "libcolgramdesync.so"
            self.assertTrue(so_core.exists() and so_core.stat().st_size > 16384, str(so_core))
            self.assertTrue(so_tg.exists() and so_tg.stat().st_size == so_core.stat().st_size, str(so_tg))


class BypassBgPatchesDryRunTest(unittest.TestCase):
    def test_bypass_bg_patches_converge_and_satisfy_contracts(self):
        sys.path.insert(0, str(ROOT / "scripts"))
        import bypass_bg_patches

        ap_spec = importlib.util.spec_from_file_location("apply_patches_track_a", ROOT / "scripts/apply-patches.py")
        ap = importlib.util.module_from_spec(ap_spec)
        ap_spec.loader.exec_module(ap)

        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            for rel in TRACK_A_TELEGRAM_RELS:
                dst = tmp_path / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / "Telegram-Src" / rel, dst)

            ap.PATCH_MISSES.clear()
            bypass_bg_patches.apply(str(tmp_path), ap.patch_file, ap._patch_file_eol)
            self.assertEqual(ap.PATCH_MISSES, [])
            bypass_bg_patches.apply(str(tmp_path), ap.patch_file, ap._patch_file_eol)
            self.assertEqual(ap.PATCH_MISSES, [])

            manifest_path = tmp_path / TRACK_A_TELEGRAM_RELS[0]
            manifest_txt = read_text(manifest_path)
            self.assertIn('android.permission.POST_NOTIFICATIONS', manifest_txt)
            self.assertNotIn("stripped POST_NOTIFICATIONS", manifest_txt)
            self.assertIn('android:allowBackup="false"', manifest_txt)
            self.assertNotIn("READ_CLIPBOARD", manifest_txt)
            self.assertNotIn(".BackupAgent", manifest_txt)
            self.assertNotIn(".SmsReceiver", manifest_txt)
            self.assertNotIn(".RefererReceiver", manifest_txt)
            self.assertNotIn(".WearAuthListenerService", manifest_txt)
            ET.parse(manifest_path)

            al = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[1])
            self.assertIn("ColgramForegroundService.start(applicationContext)", al)
            self.assertIn("colgramLastCapRefreshMs", al)

            cm = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[2])
            self.assertIn("public static void colgramResetProxySignature()", cm)
            self.assertIn("TLRPC.TL_inputMediaGame", extract_method(cm, "private static boolean colgramIsDirectSendResponseSafe("))
            self.assertIn("colgramShouldDropFirewallRequest", cm)
            self.assertIn("isAllowingSpamMentionClear()", cm)
            self.assertIn('packageId = "org.telegram.messenger";', cm)

            mc = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[3])
            self.assertIn("AndroidUtilities.runOnUIThread(() -> colgramDialogsRetryPending.put(folderId, false));", mc)
            self.assertIn("colgramDialogsRetryPending.put(folderId, true);", mc)
            self.assertIn("NotificationCenter.didUpdateConnectionState", mc)
            self.assertIn("if (false && collectDeviceStats && !loggedDeviceStats)", mc)

            mdc = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[4])
            self.assertIn("reactionsFallbackActive", mdc)
            self.assertIn("enabledReactionsList.isEmpty() || reactionsFallbackActive", mdc)
            self.assertIn("item.static_icon == null", mdc)
            self.assertIn("reaction.activate_animation != null", mdc)

            rb = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[5])
            self.assertIn("Emoji.getEmojiDrawable(visibleReaction.emojicon)", rb)

            flo = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[6])
            self.assertIn("if (downloadedBytes > 0 && downloadedBytes % currentDownloadChunkSize == 0)", flo)
            self.assertIn("req.cdn_supported = !org.colgram.core.ColgramDpiBypass.isBound();", flo)
            self.assertIn('error.text.startsWith("CDN_")', flo)
            self.assertIn("requestedBytesCount = Math.max(downloadedBytes, requestedBytesCount - requestInfo.chunkSize);", flo)

            fl = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[7])
            self.assertIn("operation.totalBytesCount > 2 * 1024 * 1024 || isStory", fl)
            load_int = extract_method(fl, "private FileLoadOperation loadFileInternal(")
            self.assertNotIn("priority = PRIORITY_HIGH;", load_int)
            self.assertIn("priority >= FileLoaderPriorityQueue.PRIORITY_VALUE_MAX", load_int)

            fpq = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[8])
            self.assertIn("type == TYPE_LARGE ? 3 : 12", fpq)

            sm = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[9])
            self.assertIn("connectionsManager.colgramIsRawDisconnected()", sm)

            il = read_text(tmp_path / IMAGE_LOADER_REL)
            self.assertIn('new DispatchQueuePriority("cacheOutQueue", 3)', il)
            self.assertIn('new DispatchQueuePriority("cacheThumbOutQueue", 2)', il)
            self.assertIn('new DispatchQueuePriority("colgramLottieOutQueue", 2)', il)
            self.assertIn("synchronized (colgramLottiePrecacheLock)", il)
            self.assertIn("colgramLottiePrecacheLockFor(", il)
            self.assertIn("int fitzModifier, boolean isSingleChannel", il)
            self.assertNotIn("int[] fitzModifier", il)

            chat_src = read_text(tmp_path / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
            self.assertIn("ColgramPremiumConfig.isBlockSponsoredAdsEnabled()", chat_src)
            self.assertIn("350", chat_src)

            va_src = read_text(tmp_path / "TMessagesProj/src/main/java/org/telegram/messenger/video/VideoAds.java")
            self.assertIn("ColgramPremiumConfig.isBlockSponsoredAdsEnabled()", va_src)

            ae_src = read_text(tmp_path / "TMessagesProj/src/main/java/org/telegram/ui/Components/AnimatedEmojiDrawable.java")
            self.assertIn("loadFromServerIds.size() > 100", ae_src)

            ext_ds_src = read_text(tmp_path / "TMessagesProj/src/main/java/org/telegram/messenger/secretmedia/ExtendedDefaultDataSource.java")
            self.assertNotIn("Mode.DIRECT", ext_ds_src)
            self.assertIn("org.colgram.core.ColgramBrowserRoutePolicy.Mode.CURRENT", ext_ds_src)
            self.assertEqual(ext_ds_src.count("dataSource = getColgramHttpDataSource();"), 1)

    def test_image_loader_converges_on_fresh_upstream_clone(self):
        sys.path.insert(0, str(ROOT / "scripts"))
        import bypass_bg_patches

        ap_spec = importlib.util.spec_from_file_location("apply_patches_fresh_il", ROOT / "scripts/apply-patches.py")
        ap = importlib.util.module_from_spec(ap_spec)
        ap_spec.loader.exec_module(ap)

        fresh_upstream_image_loader = """package org.telegram.messenger;

public class ImageLoader {
    private DispatchQueuePriority cacheOutQueue = new DispatchQueuePriority("cacheOutQueue", 4);
    private DispatchQueuePriority cacheThumbOutQueue = new DispatchQueuePriority("cacheThumbOutQueue", 2);
    void schedule1() {
                        queue.postRunnable(new Runnable() {
                            @Override
                            public void run() {
                                if (task.cacheImage.type == ImageReceiver.TYPE_THUMB) {
                                    cacheThumbOutQueue.postRunnable(task, task.cacheImage.priority);
                                } else {
                                    cacheOutQueue.postRunnable(task, task.cacheImage.priority);
                                }
                            }
                        });
                    }
                    long currentTime = SystemClock.elapsedRealtime();
    void schedule2() {
                        if (thumb != 0) {
                            cacheThumbOutQueue.postRunnable(img.cacheTask, img.priority);
                        } else {
                            img.runningTask = cacheOutQueue.postRunnable(img.cacheTask, img.priority);
                        }
    }
    void schedule3() {
                if (task.cacheImage.type == ImageReceiver.TYPE_THUMB) {
                    cacheThumbOutQueue.postRunnable(task, task.cacheImage.priority);
                } else {
                    cacheOutQueue.postRunnable(task, task.cacheImage.priority);
                }
            }
        });
    }
    void makeLottie() {
                    if (compressed) {
                        lottieDrawable = new RLottieDrawable(cacheImage.finalFilePath, decompressGzip(cacheImage.finalFilePath), w, h, cacheOptions, limitFps, null, fitzModifier, isSingleChannel);
                    } else {
                        lottieDrawable = new RLottieDrawable(cacheImage.finalFilePath, null, w, h, cacheOptions, limitFps, null, fitzModifier, isSingleChannel);
                    }
    }
}
"""

        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            for rel in TRACK_A_TELEGRAM_RELS[:-1]:
                dst = tmp_path / rel
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / "Telegram-Src" / rel, dst)

            il_path = tmp_path / IMAGE_LOADER_REL
            il_path.parent.mkdir(parents=True, exist_ok=True)
            il_path.write_text(fresh_upstream_image_loader, encoding="utf-8")

            ap.PATCH_MISSES.clear()
            bypass_bg_patches.apply(str(tmp_path), ap.patch_file, ap._patch_file_eol)
            self.assertEqual(ap.PATCH_MISSES, [])

            cm_pass1 = read_text(tmp_path / "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java")
            direct_safe = extract_method(cm_pass1, "private static boolean colgramIsDirectSendResponseSafe(")
            self.assertIn("TLRPC.TL_inputMediaGame", direct_safe)
            self.assertIn("colgramNeedConnCheck = colgramIsRawDisconnected();", cm_pass1)

            sm_pass1 = read_text(tmp_path / "TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java")
            prep_pass1 = extract_method(sm_pass1, "private void colgramPrepareInstantSend()")
            self.assertIn("connectionsManager.colgramIsRawDisconnected()", prep_pass1)
            self.assertIn("Utilities.globalQueue.postRunnable(connectionsManager::checkConnection);", prep_pass1)
            self.assertNotIn("connectionsManager.checkConnection()", prep_pass1)

            il_pass1 = read_text(il_path)
            self.assertIn('new DispatchQueuePriority("cacheOutQueue", 3)', il_pass1)
            self.assertIn('new DispatchQueuePriority("cacheThumbOutQueue", 2)', il_pass1)
            self.assertIn('new DispatchQueuePriority("colgramLottieOutQueue", 2)', il_pass1)
            self.assertIn("synchronized (colgramLottiePrecacheLock)", il_pass1)
            self.assertIn("colgramLottiePrecacheLockFor(", il_pass1)
            self.assertEqual(il_pass1.count("private static RLottieDrawable colgramCreateRLottieDrawable("), 1)
            self.assertGreaterEqual(il_pass1.count("colgramCreateRLottieDrawable("), 2)
            self.assertIn("int fitzModifier, boolean isSingleChannel", il_pass1)
            self.assertNotIn("int[] fitzModifier", il_pass1)

            bypass_bg_patches.apply(str(tmp_path), ap.patch_file, ap._patch_file_eol)
            self.assertEqual(ap.PATCH_MISSES, [])
            self.assertEqual(read_text(il_path), il_pass1)
            self.assertEqual(
                read_text(tmp_path / "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java"),
                cm_pass1,
            )
            self.assertEqual(
                read_text(tmp_path / "TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java"),
                sm_pass1,
            )


if __name__ == "__main__":
    unittest.main()
