"""Contract tests for Colgram performance invariants:
- Bypass concurrency, non-blocking dial groups, WS no-sleep / batching, socket buffers
- ColgramConfig volatile cache & ColgramDatabase blocked-users cache / batched deletes
- MessagesController / MessagesStorage pin caching, async edit hooks, batched anti-delete
- Media-priority, Theme-cache, and DialogsAdapter-background-diff invariants
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "colgram-core/src/main/java/org/colgram/core"
MSG = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/messenger"
UI = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui"


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


class BypassConcurrencyAndNoSleepContractsTest(unittest.TestCase):
    def test_dpi_bypass_concurrency_timeouts_and_no_sleep_fast_paths(self):
        dpi = read_text(CORE / "ColgramDpiBypass.java")

        # Concurrent permit backoff (maxConcurrent = 4) and raised pool/session limits
        self.assertRegex(
            dpi,
            r"new\s+ColgramConnectFailureBackoff\(\s*CONNECT_FAILURE_BASE_DELAY_MS\s*,\s*CONNECT_FAILURE_MAX_DELAY_MS\s*,\s*4\s*\)",
        )
        for const_pattern in (
            r"MAX_WORKER_POOL_THREADS\s*=\s*384\b",
            r"SYSTEM_WIDE_CLIENT_CAP\s*=\s*256\b",
            r"HANDSHAKE_SLOTS\s*=\s*new\s+java\.util\.concurrent\.Semaphore\(\s*96\s*\)",
            r"DIAL_GROUPS\s*=\s*new\s+java\.util\.concurrent\.Semaphore\(\s*28\s*\)",
            r"MAX_ACTIVE_SESSIONS\s*=\s*192\b",
            r"REJECT_RETRY_DELAY_MS\s*=\s*120L\b",
            r"CONNECT_FAILURE_BASE_DELAY_MS\s*=\s*1200L\b",
            r"CONNECT_FAILURE_MAX_DELAY_MS\s*=\s*15000L\b",
            r"FIRST_REPLY_TIMEOUT_MS\s*=\s*750\b",
            r"CELLULAR_FIRST_REPLY_TIMEOUT_MS\s*=\s*950\b",
            r"TELEGRAM_DIAL_BUDGET_MS\s*=\s*1100\b",
            r"CELLULAR_TELEGRAM_DIAL_BUDGET_MS\s*=\s*1400\b",
            r"FAILS_BEFORE_SWITCH\s*=\s*1\b",
            r"STRATEGY_BACKOFF_MS\s*=\s*3000L\b",
            r"MAX_BACKOFF_SLEEP_MS\s*=\s*300L\b",
        ):
            self.assertRegex(dpi, const_pattern)

        # resolveBypassEndpoint runs BEFORE bounded DIAL_GROUPS.tryAcquire(250, MILLISECONDS)
        cwb = extract_method(dpi, "private static Socket connectWithBackoff(")
        self.assertIn("resolveBypassEndpoint(host, port)", cwb)
        self.assertIn("DIAL_GROUPS.tryAcquire(250, java.util.concurrent.TimeUnit.MILLISECONDS)", cwb)
        self.assertLess(
            cwb.index("resolveBypassEndpoint(host, port)"),
            cwb.index("DIAL_GROUPS.tryAcquire(250"),
        )

        # No dcBackoffUntil sleep when WSS is already working on network / preferred for bucket
        self.assertIn("skipDcBackoffSleep", dpi)
        self.assertIn("if (!skipDcBackoffSleep)", dpi)
        self.assertIn("paceMs = Math.max(REJECT_RETRY_DELAY_MS, eco ? 600L : 250L);", dpi)

        # Probe socket handoff directly into dialWithConnectedSocket instead of discarding TCP socket
        l3_race = extract_method(dpi, "private static L3RaceResult raceLayer2WithFastDirectProbe(")
        self.assertIn("ColgramTelegramWsBridge.dialWithConnectedSocket(probe, destHost, isMedia)", l3_race)
        self.assertIn("tuneSocketBuffers(probe);", l3_race)

        # Parallel Telegram + RU whitelist SYN probes
        wl = extract_method(dpi, "public static boolean probeStrictL3WhitelistStatus(")
        self.assertIn("executeWorker(", wl)
        self.assertIn("CountDownLatch", wl)

        # Hot WS pipes use batched feed*ToSession and drop hot-loop WS_C2S_CHUNK debug logging
        ws_pipe = extract_method(
            dpi,
            "private static void pipeOverWebSocket(final Socket client, final ColgramTelegramWsBridge.WsSession session,\n"
            "                                          final CallListener call, final long generation,\n"
            "                                          final boolean viaL3Relay)",
        )
        ws_after_init = extract_method(dpi, "private static void pipeOverWebSocketAfterInit(")
        self.assertIn("splitter.feedInitAndTailToSession(", ws_pipe)
        self.assertIn("splitter.feedToSession(", ws_pipe)
        self.assertIn("splitter.feedToSession(", ws_after_init)
        self.assertNotIn("WS_C2S_CHUNK", dpi)

    def test_ws_bridge_batching_unroll_and_zero_copy_contracts(self):
        ws = read_text(CORE / "ColgramTelegramWsBridge.java")
        self.assertIn("void sendBinaryUnflushed(", ws)
        self.assertIn("void flushOut(", ws)
        self.assertIn("private int nextMaskInt()", ws)
        self.assertIn("sink.onFrame(data, off + hdrStart, fullLen);", ws)
        self.assertIn("public synchronized void feedInitAndTailToSession(", ws)
        self.assertIn("public synchronized void feedToSession(", ws)
        unmask = extract_method(ws, "private static void unmask(")
        self.assertIn("int limit4 = len - 3;", unmask)
        self.assertIn("BufferedInputStream", extract_method(ws, "public static long pumpUpstreamFrames("))

    def test_tcp_front_remap_native_desync_and_doh_concurrency_contracts(self):
        front = read_text(CORE / "ColgramLocalTcpFront.java")
        self.assertIn("SOCKET_BUFFER_BYTES = 262144", front)
        self.assertIn("STREAM_COPY_BUFFER_BYTES = 64 * 1024", front)
        self.assertIn("flushIfDrained(in, out);", front)

        remap = read_text(CORE / "ColgramDcRemap.java")
        self.assertIn("SOCKET_BUFFER_BYTES = 262144", remap)
        self.assertIn("STREAM_COPY_BUFFER_BYTES = 65536", remap)
        self.assertIn("flushIfDrained(in, out);", remap)

        native = read_text(CORE / "ColgramNativeDesync.java")
        self.assertIn("private volatile boolean handshakeDone;", native)
        first_locked = extract_method(native, "private synchronized void writeFirstLocked(")
        self.assertRegex(first_locked, r"finally\s*\{\s*handshakeDone\s*=\s*true;")

        doh = read_text(CORE / "ColgramDohResolver.java")
        self.assertIn("DOH_RACE_POOL", doh)
        self.assertIn("inflightLookups", doh)
        self.assertIn("MAX_POOLED_PER_ENDPOINT = 4", doh)
        sni_sock = extract_method(doh, "private static final class SniFragmentingSocket")
        self.assertRegex(sni_sock, r"finally\s*\{\s*firstHelloSent\s*=\s*true;")


class CoreCacheAndDbSyncContractsTest(unittest.TestCase):
    def test_colgram_config_volatile_caches_and_lazy_listener(self):
        cfg = read_text(CORE / "ColgramConfig.java")
        # Ensure listener is created lazily inside init(), not as a static field initializer
        self.assertIn("private static volatile Object prefChangeListener;", cfg)
        self.assertNotIn("private static final SharedPreferences.OnSharedPreferenceChangeListener", cfg)
        init_body = extract_method(cfg, "public static void init(Context context)")
        self.assertIn("loadAllCachedPrefs(sp);", init_body)
        self.assertIn("registerOnSharedPreferenceChangeListener", init_body)

        for getter in (
            "public static boolean isAntiDeleteEnabled()",
            "public static boolean isAntiDeleteHighlightEnabled()",
            "public static boolean isCyberThemeEnabled()",
            "public static boolean isUnlimitedMemoryEnabled()",
            "public static boolean isSandboxStorageEnabled()",
            "public static boolean isGhostReadEnabled()",
            "public static boolean isGhostOnlineEnabled()",
        ):
            body = extract_method(cfg, getter)
            self.assertNotIn("prefs.get", body, f"{getter} must read volatile cache, not SharedPreferences")

    def test_colgram_database_blocked_cache_and_batched_mark_messages_deleted(self):
        db = read_text(CORE / "ColgramDatabase.java")
        self.assertIn("DATABASE_VERSION = 6", db)
        self.assertIn("blockedUsersCache", db)
        blocked_fn = extract_method(db, "public boolean isNotificationsBlockedFrom(long userId)")
        self.assertIn("blockedUsersCache.contains(userId)", blocked_fn)
        self.assertNotIn("getReadableDatabase()", blocked_fn)

        batch_fn = extract_method(db, "public void markMessagesDeleted(long dialogId, Collection<Integer> messageIds)")
        self.assertIn("compileStatement(", batch_fn)
        self.assertIn("db.beginTransaction();", batch_fn)
        self.assertIn("db.setTransactionSuccessful();", batch_fn)
        self.assertIn("db.endTransaction();", batch_fn)

        get_ids = extract_method(db, "public java.util.List<Integer> getDeletedMessageIdsForDialog(long dialogId)")
        self.assertIn("deletedMessagesByDialog.get(dialogId)", get_ids)
        self.assertIn("false,", get_ids)

    def test_messages_controller_and_storage_sync_optimizations(self):
        mc = read_text(MSG / "MessagesController.java")
        mark_read = extract_method(mc, "public void markDialogAsRead(")
        head_before_thread = mark_read.split("if (threadId != 0)", 1)[0]
        self.assertNotIn("NotificationCenter.dialogsNeedReload", head_before_thread)

        self.assertIn("colgramCachedLocalPins", mc)
        self.assertIn("colgramCachedFolderPins", mc)
        self.assertIn("hookOnMessageEditedWithNewAsync(", mc)
        self.assertIn("hookOnMediaReplacedAsync(", mc)

        ms = read_text(MSG / "MessagesStorage.java")
        self.assertIn("hookShouldPreventDeleteBatch(", ms)
        self.assertIn("colgramAntiDeleteNotInClause(long did, java.util.Collection<Integer> candidateMids)", ms)
        self.assertIn("colgram_keep_mids", ms)


class MediaPriorityAndSandboxContractsTest(unittest.TestCase):
    def test_file_load_operation_boost_and_two_conn_abi_safety(self):
        flo = read_text(MSG / "FileLoadOperation.java")
        update_params = extract_method(flo, "private void updateParams()")
        self.assertIn("maxDownloadRequests = 8;", update_params)
        self.assertIn("maxDownloadRequestsBig = 8;", update_params)
        self.assertIn("maxDownloadRequestsAnimation = Math.max(maxDownloadRequestsAnimation, 8);", update_params)
        self.assertIn("downloadChunkSizeBig = 1024 * 512;", update_params)
        self.assertIn("totalBytesCount >= 256 * 1024 ? 1024 * 128 : 1024 * 64", update_params)
        self.assertNotIn("maxDownloadRequests = boost ? 6 : 8;", update_params)
        # Prebuilt libtmessages.49.so uses DOWNLOAD_CONNECTIONS_COUNT == 2
        self.assertNotIn("waitingDownloadSize = new int[4]", flo)
        self.assertNotIn("ConnectionTypeDownload | (connIndex << 16)", flo)

    def test_avatar_priority_elevation_and_queue_slots(self):
        fl = read_text(MSG / "FileLoader.java")
        load_int = extract_method(fl, "private FileLoadOperation loadFileInternal(")
        self.assertIn("parentObject instanceof TLRPC.User", load_int)
        self.assertIn("imageLocation.photoPeer != null", load_int)
        self.assertIn("locationSize <= 256 * 1024", load_int)
        self.assertIn("priority = PRIORITY_HIGH;", load_int)

        ir = read_text(MSG / "ImageReceiver.java")
        get_prio = extract_method(ir, "public int getFileLoadingPriority()")
        self.assertIn("currentParentObject instanceof TLRPC.User", get_prio)
        self.assertIn("return FileLoader.PRIORITY_HIGH;", get_prio)

        fpq = read_text(MSG / "FileLoaderPriorityQueue.java")
        self.assertIn("type == TYPE_LARGE ? 6 : 12", fpq)
        self.assertIn("isDownloadSpeedBoostEnabled() ? 4 : 20", fpq)

    def test_image_loader_parallel_decode_and_http_concurrency(self):
        il = read_text(MSG / "ImageLoader.java")
        self.assertIn('new DispatchQueuePriority("cacheOutQueue", 3)', il)
        self.assertIn("ThreadLocal<byte[]> header = new ThreadLocal<>()", il)
        self.assertIn("ThreadLocal<byte[]> headerThumb = new ThreadLocal<>()", il)
        self.assertIn("public synchronized SparseArray<File> createMediaPaths()", il)
        self.assertIn("currentHttpTasksCount < 8", il)
        self.assertIn("currentArtworkTasksCount < 8", il)
        self.assertIn("currentHttpFileLoadTasksCount < 4", il)
        self.assertIn("new byte[1024 * 32]", il)
        self.assertIn("setConnectTimeout(4500)", il)
        self.assertIn("setReadTimeout(6000)", il)
        self.assertIn("maxSize = unlimited ? 30 : 24;", il)
        self.assertIn("maxSize = unlimited ? 15 : 12;", il)
        self.assertIn("img.priority = 2;", il)

        cache_task = extract_method(il, "private class CacheOutTask implements Runnable")
        self.assertIn("runningThread = null;", cache_task)
        self.assertIn("Thread.interrupted();", cache_task)
        on_post = extract_method(cache_task, "private void onPostExecute(")
        self.assertIn("if (isCancelled)", on_post)

    def test_storage_sandbox_unlimited_memory_and_bot_sync(self):
        sb = read_text(CORE / "ColgramStorageSandbox.java")
        self.assertIn("sandboxedDirCache", sb)
        um = read_text(CORE / "ColgramUnlimitedMemory.java")
        self.assertIn("90L", um)
        bs = read_text(CORE / "ColgramBotSync.java")
        self.assertNotIn("Executors.newCachedThreadPool()", bs)


class ThemeCacheAndDialogsAdapterContractsTest(unittest.TestCase):
    def test_theme_o1_color_cache_and_zero_boolean_alloc(self):
        theme = read_text(UI / "ActionBar/Theme.java")
        self.assertIn("ColgramColorCache", theme)
        self.assertIn("colgramBumpThemeRevision", theme)
        self.assertIn("colgramGetThemeRevision", theme)
        self.assertIn("colgramMemoPolicy", theme)
        self.assertIn("COLGRAM_OP_BUBBLE_INK", theme)
        self.assertIn("colgramSyncDrawableTintsIfNeeded", theme)
        self.assertNotIn("new boolean[1]", theme)

    def test_dialog_cell_draw_counter_cache_folder_cap_and_dirty_invalidate(self):
        cell = read_text(UI / "Cells/DialogCell.java")
        self.assertIn("colgramLastThemeRev", cell)
        self.assertIn("colgramCounterContrastThemeRev", cell)
        self.assertIn("colgramCounterMutedValid", cell)
        self.assertIn("colgramCounterActiveValid", cell)
        self.assertIn("drawCounterBackground", cell)
        self.assertIn("findFolderTopMessage", cell)
        self.assertIn("dialogs.get(mid).pinnedNum == 0", cell)
        self.assertNotIn("Theme.colgramSyncDrawableTintsIfNeeded()", cell)
        self.assertNotIn("//if (invalidate)", cell)

    def test_dialogs_adapter_background_diff_and_activity_blur_gating(self):
        adapter = read_text(UI / "Adapters/DialogsAdapter.java")
        self.assertIn("ALLOW_UPDATE_IN_BACKGROUND = true;", adapter)
        self.assertIn("pendingRegularHeightCells", adapter)
        self.assertIn("committedRegularHeightCells", adapter)

        act = read_text(UI / "DialogsActivity.java")
        self.assertIn("colgramBlurredLastFrame", act)
        self.assertIn("!contentView.blurBehindViews.isEmpty()", act)
        self.assertIn("Theme.colgramSyncDrawableTintsIfNeeded()", act)

    def test_chat_cell_aero_power_guard_and_wallpaper_caches(self):
        msg_cell = read_text(UI / "Cells/ChatMessageCell.java")
        self.assertIn("colgramReplyCacheBg", msg_cell)
        self.assertIn("!colgramPendingDeleteAnims.isEmpty()", msg_cell)
        self.assertIn("COLGRAM_SHARED_WHITE_CHECK_FILTER", msg_cell)
        self.assertIn("colgramSharedCheckTintFilter", msg_cell)
        self.assertIn("colgramSyncCheckDrawable(", msg_cell)

        aero = read_text(ROOT / "scripts/templates/ColgramAeroStyler.java")
        self.assertIn("cachedBadgeShader", aero)
        self.assertIn("AmbientMenuBackdropView", aero)
        self.assertIn("cachedBitmap", aero)
        self.assertIn("styleMainTabsDock", aero)

        power = read_text(CORE / "ColgramPowerGuard.java")
        self.assertIn("BATTERY_CACHE_MS", power)
        self.assertIn("POWER_SAVE_CACHE_MS", power)
        self.assertIn("invalidatePowerSaveCache", power)
        self.assertIn("refreshBatterySnapshotIfNeeded", power)

        wall = read_text(CORE / "ColgramLiveWallpaper.java")
        self.assertIn("TintSnapshot", wall)
        self.assertIn("cachedTintSnapshot", wall)
        self.assertIn("waveShaders", wall)
        self.assertIn("colaShaders", wall)

    def test_apply_patches_registers_unconditional_eol_safe_performance_patches(self):
        import importlib.util
        import tempfile

        ap = ROOT / "scripts/apply-patches.py"
        src = read_text(ap)
        self.assertIn("def inject_performance_patches(repo_path):", src)
        self.assertIn("inject_performance_patches(target_repo)", src.split("def main():", 1)[1])

        spec = importlib.util.spec_from_file_location("apply_patches_perf_mod", ap)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)

        with tempfile.TemporaryDirectory() as tmp:
            lf_file = Path(tmp) / "sample.java"
            lf_file.write_bytes(b"class A {\n    int x = 1;\n}\n")
            mod.PATCH_MISSES.clear()
            ok = mod._patch_file_eol(str(lf_file), "    int x = 1;\n", "    int x = 2;\n", "eol test")
            self.assertTrue(ok)
            self.assertEqual(mod.PATCH_MISSES, [])
            self.assertEqual(lf_file.read_bytes(), b"class A {\n    int x = 2;\n}\n")
            # Second pass converges with zero changes
            ok2 = mod._patch_file_eol(str(lf_file), "    int x = 1;\n", "    int x = 2;\n", "eol test")
            self.assertTrue(ok2)
            self.assertEqual(lf_file.read_bytes(), b"class A {\n    int x = 2;\n}\n")


if __name__ == "__main__":
    unittest.main()
