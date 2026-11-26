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
    "TMessagesProj/src/main/java/org/telegram/messenger/ImageLoader.java",
)


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

    def test_ws_bridge_native_keepalive_wrap_fix_and_prewarm_safety(self):
        ws = read_text(CORE / "ColgramTelegramWsBridge.java")
        self.assertIn("volatile boolean fromPrewarm = false;", ws)
        self.assertIn("PREWARM_TTL_MS = 8_000L;", ws)
        self.assertIn("!ColgramUiBridge.isAppInForeground()", extract_method(ws, "private static void replenishPrewarmedAsync("))
        self.assertIn(
            "hit.fromPrewarm = true;",
            extract_method(ws, "static WsSession takePrewarmed(int normDc, boolean media, boolean wantProtected)"),
        )
        self.assertIn("public static int drainPrewarmedPool()", ws)
        self.assertIn("public static void evictPrewarmedBucket(int dcId)", ws)
        self.assertIn("public static WsSession dialFreshForDc(", ws)

        wrap = extract_method(ws, "private static Socket wrapSocketForMode(")
        self.assertIn("if (base instanceof ColgramNativeDesync.NativeSocket && isNativeFragMode(fragMode))", wrap)

        race = extract_method(ws, "private static DialedTcp connectEdgeRace(")
        self.assertIn("final boolean useNativeSocket = ColgramNativeDesync.isAvailable()", race)
        self.assertNotIn("setReceiveBufferSize(262144)", race)
        self.assertNotIn("setSendBufferSize(262144)", race)

        pump = extract_method(ws, "public static long pumpUpstreamFrames(")
        self.assertIn("new byte[32768]", pump)
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
        self.assertIn("ColgramTelegramWsBridge.drainPrewarmedPool()", inv)

        vpn = extract_method(pm, "private static boolean hasSystemVpn(Context context)")
        self.assertNotIn("return true;", vpn)

        wd = extract_method(pm, "private static void connectionWatchdogTick()")
        self.assertIn("mainDcStallTimedOut", wd)
        self.assertIn(">= 15_000L", wd)

        stop_bg = extract_method(pm, "private static void stopBackgroundFeedScraping()")
        self.assertIn("watchdogTask", stop_bg)
        self.assertIn("healthCheckTask", stop_bg)

        lwatch = extract_method(pm, "private static void ensureLocalWatchSchedulerRunning()")
        self.assertIn("foreground ? 30L : 300L", lwatch)

    def test_helpers_doze_sync_build_flag_and_native_c(self):
        remap = read_text(CORE / "ColgramDcRemap.java")
        self.assertIn("STREAM_COPY_BUFFER_BYTES = 32768", remap)
        self.assertNotIn("SOCKET_BUFFER_BYTES", remap)
        self.assertIn("if (upstreamBytes < MIN_ANSWER_BYTES) {", remap)

        doh = read_text(CORE / "ColgramDohResolver.java")
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
        self.assertIn("DOZE_WAKELOCK_MS = 12_000L", fgs)
        self.assertIn("DOZE_PULSE_HOLD_MS = 9_000L", fgs)
        self.assertIn("cachedStageQueuePostRunnable", fgs)
        trim = extract_method(fgs, "public void onTrimMemory(int level)")
        self.assertIn("ColgramBuildFlags.KOLITI_ENABLED", trim)
        doze = extract_method(fgs, "public static void handleDozeKeepalivePulse(Context context)")
        self.assertIn("ColgramDpiBypass.reapStaleSessions(10_000L, 0L)", doze)
        self.assertIn("kickActiveHumanAccounts(true, true)", doze)
        self.assertIn("DOZE_PULSE_HOLD_MS", doze)

        c_src = read_text(CPP / "colgram_desync.c")
        self.assertIn("atomic_int window_clamped;", c_src)
        self.assertIn("if (off > arr_len || len > arr_len - off)", c_src)
        self.assertIn("ExceptionCheck", c_src)
        self.assertNotIn("buf_sz = 262144", c_src)

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
            ET.parse(manifest_path)

            al = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[1])
            self.assertIn("ColgramForegroundService.start(applicationContext)", al)
            self.assertIn("colgramLastCapRefreshMs", al)

            cm = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[2])
            self.assertIn("public static void colgramResetProxySignature()", cm)

            mc = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[3])
            self.assertIn("AndroidUtilities.runOnUIThread(() -> colgramDialogsRetryPending.put(folderId, false));", mc)
            self.assertIn("colgramDialogsRetryPending.put(folderId, true);", mc)
            self.assertIn("NotificationCenter.didUpdateConnectionState", mc)

            mdc = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[4])
            self.assertIn("reactionsFallbackActive", mdc)
            self.assertIn("enabledReactionsList.isEmpty() || reactionsFallbackActive", mdc)
            self.assertIn("item.static_icon == null", mdc)
            self.assertIn("reaction.activate_animation != null", mdc)

            rb = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[5])
            self.assertIn("Emoji.getEmojiDrawable(visibleReaction.emojicon)", rb)

            flo = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[6])
            self.assertIn("if (downloadedBytes > 0 && downloadedBytes % currentDownloadChunkSize == 0)", flo)
            self.assertIn("ColgramTelegramWsBridge.hasWorkedOnCurrentNetwork()", flo)
            self.assertIn('error.text.startsWith("CDN_")', flo)
            self.assertIn("requestedBytesCount = Math.max(downloadedBytes, requestedBytesCount - requestInfo.chunkSize);", flo)

            fl = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[7])
            load_int = extract_method(fl, "private FileLoadOperation loadFileInternal(")
            self.assertNotIn("priority = PRIORITY_HIGH;", load_int)
            self.assertIn("priority >= FileLoaderPriorityQueue.PRIORITY_VALUE_MAX", load_int)

            fpq = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[8])
            self.assertIn("type == TYPE_LARGE ? 3 : 6", fpq)

            il = read_text(tmp_path / TRACK_A_TELEGRAM_RELS[9])
            self.assertIn('new DispatchQueuePriority("cacheOutQueue", 2)', il)
            self.assertIn('new DispatchQueuePriority("cacheThumbOutQueue", 1)', il)
            self.assertIn("synchronized (colgramLottiePrecacheLock)", il)
            self.assertIn("int fitzModifier, boolean isSingleChannel", il)
            self.assertNotIn("int[] fitzModifier", il)


if __name__ == "__main__":
    unittest.main()
