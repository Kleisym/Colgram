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
            r"MAX_ACTIVE_SESSIONS\s*=\s*160\b",
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

        # Parallel Telegram + RU whitelist SYN probes (on dedicated dialRacePool)
        wl = extract_method(dpi, "public static boolean probeStrictL3WhitelistStatus(")
        self.assertIn("executeDialRace(", wl)
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
        self.assertIn("long wsHeadStartMs = cellularNetwork ? 0L : 25L;", dpi)
        self.assertIn("ColgramSocks5Codec.readFully(in, 2)", dpi)
        self.assertNotIn("WS_C2S_CHUNK", dpi)

    def test_ws_bridge_batching_unroll_and_zero_copy_contracts(self):
        ws = read_text(CORE / "ColgramTelegramWsBridge.java")
        self.assertIn("void sendBinaryUnflushed(", ws)
        self.assertIn("wrapBufferedOutput(", ws)
        self.assertIn("PREWARM_TTL_MS = 38_000L;", ws)
        self.assertIn("PREWARM_REFRESH_MS = 28_000L;", ws)
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
        self.assertNotIn("SOCKET_BUFFER_BYTES = 262144", remap)
        self.assertIn("STREAM_COPY_BUFFER_BYTES = 32768", remap)
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
        self.assertIn("TLRPC.TL_inputPeerPhotoFileLocation", update_params)
        self.assertIn("TLRPC.TL_inputStickerSetThumb", update_params)
        self.assertIn("TLRPC.TL_inputPhotoFileLocation", update_params)
        self.assertIn("!allowDisordererFileSave", update_params)
        self.assertIn("totalBytesCount < 256 * 1024", update_params)
        self.assertIn("downloadChunkSize = isSmallOrThumbOrAvatar ? (totalBytesCount >= 1024 * 32 ? 1024 * 64 : 1024 * 32) : 1024 * 128;", update_params)
        self.assertIn("downloadChunkSizeBig = totalBytesCount >= 1024 * 1024 ? 1024 * 512 : 1024 * 128;", update_params)
        self.assertIn("maxDownloadRequests = 4;", update_params)
        self.assertIn("maxDownloadRequestsBig = 4;", update_params)
        self.assertIn("maxDownloadRequestsAnimation = Math.max(maxDownloadRequestsAnimation, 6);", update_params)
        self.assertNotIn("maxDownloadRequests = boost ? 6 : 8;", update_params)
        # Prebuilt libtmessages.49.so uses DOWNLOAD_CONNECTIONS_COUNT == 2
        self.assertNotIn("waitingDownloadSize = new int[4]", flo)
        self.assertNotIn("ConnectionTypeDownload | (connIndex << 16)", flo)

    def test_avatar_priority_elevation_and_queue_slots(self):
        fl = read_text(MSG / "FileLoader.java")
        load_int = extract_method(fl, "private FileLoadOperation loadFileInternal(")
        self.assertNotIn("priority = PRIORITY_HIGH;", load_int)
        self.assertNotIn("operation.setForceRequest(priority > 0);", load_int)
        self.assertIn("if (priority >= FileLoaderPriorityQueue.PRIORITY_VALUE_MAX)", load_int)
        self.assertIn("operation.setForceRequest(true);", load_int)

        ir = read_text(MSG / "ImageReceiver.java")
        get_prio = extract_method(ir, "public int getFileLoadingPriority()")
        self.assertIn("currentParentObject instanceof TLRPC.User", get_prio)
        self.assertIn("currentParentObject instanceof TLRPC.UserFull", get_prio)
        self.assertIn("currentParentObject instanceof TLRPC.ChatFull", get_prio)
        self.assertIn("currentImageLocation.thumbSize != null", get_prio)
        self.assertIn("currentImageLocation.stickerSet != null", get_prio)
        self.assertIn("currentThumbLocation != null", get_prio)
        self.assertIn("return FileLoader.PRIORITY_HIGH;", get_prio)

        fpq = read_text(MSG / "FileLoaderPriorityQueue.java")
        self.assertIn("type == TYPE_LARGE ? 3 : 12", fpq)
        self.assertIn("isDownloadSpeedBoostEnabled() ? 1 : 20", fpq)

    def test_image_loader_parallel_decode_and_http_concurrency(self):
        il = read_text(MSG / "ImageLoader.java")
        self.assertIn('new DispatchQueuePriority("cacheOutQueue", 3)', il)
        self.assertIn('new DispatchQueuePriority("cacheThumbOutQueue", 2)', il)
        self.assertIn('new DispatchQueuePriority("colgramLottieOutQueue", 1)', il)
        self.assertIn("colgramLottiePrecacheLock", il)
        self.assertIn("cacheThumbOutQueue.postRunnable(task, task.cacheImage.priority)", il)
        self.assertIn("cacheThumbOutQueue.postRunnable(img.cacheTask, img.priority)", il)
        self.assertIn("ThreadLocal<byte[]> header = new ThreadLocal<>()", il)
        self.assertIn("ThreadLocal<byte[]> headerThumb = new ThreadLocal<>()", il)
        self.assertIn("public synchronized SparseArray<File> createMediaPaths()", il)
        self.assertIn("currentHttpTasksCount < 12", il)
        self.assertIn("currentArtworkTasksCount < 12", il)
        self.assertIn("currentHttpFileLoadTasksCount < 6", il)
        self.assertIn("new byte[1024 * 64]", il)
        self.assertNotIn("new byte[1024 * 32]", il)
        self.assertIn("setConnectTimeout(3500)", il)
        self.assertIn("setReadTimeout(5000)", il)
        self.assertNotIn("setConnectTimeout(4500)", il)
        self.assertNotIn("setReadTimeout(6000)", il)
        self.assertIn("maxSize = unlimited ? 30 : 24;", il)
        self.assertIn("maxSize = unlimited ? 15 : 12;", il)
        self.assertIn("img.priority = 2;", il)
        self.assertIn("parentObject instanceof TLRPC.UserFull", il)
        self.assertIn("parentObject instanceof TLRPC.ChatFull", il)
        self.assertIn("imageLocation.thumbSize != null", il)
        self.assertIn("imageLocation.stickerSet != null", il)

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
        self.assertIn("colgramHasPendingDeleteAnim(", msg_cell)
        self.assertIn("colgramConsumePendingDeleteAnim(", msg_cell)
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


class TurboMessagingAndPushContractsTest(unittest.TestCase):
    def test_send_messages_helper_instant_tx_and_nonblocking_bypass_wake(self):
        sm = read_text(MSG / "SendMessagesHelper.java")
        prep = extract_method(sm, "private void colgramPrepareInstantSend()")
        self.assertIn("connectionsManager.getPauseTime() != 0", prep)
        self.assertIn("connectionsManager.resumeNetworkMaybe()", prep)
        if "colgramIsRawDisconnected()" in prep:
            self.assertIn("Utilities.globalQueue.postRunnable(connectionsManager::checkConnection);", prep)
            self.assertNotIn("connectionsManager.checkConnection()", prep)
        else:
            self.assertIn("connectionsManager.checkConnection()", prep)
        self.assertIn("!org.colgram.core.ColgramDpiBypass.isBound()", prep)
        self.assertIn("Utilities.globalQueue.postRunnable(org.colgram.core.ColgramDpiBypass::restartIfDead)", prep)
        self.assertNotIn("Thread.sleep", prep)

        perf_send = extract_method(sm, "protected void performSendMessageRequest(")
        self.assertIn("colgramPrepareInstantSend();", perf_send)
        self.assertIn("ConnectionsManager.ConnectionTypeGeneric, true);", perf_send)
        self.assertIn("if (req instanceof TLRPC.TL_messages_editMessage)", perf_send)
        self.assertIn("TLRPC.TL_messages_sendReaction", perf_send)
        self.assertIn("ConnectionsManager.RequestFlagNeedQuickAck", perf_send)

        perf_multi = extract_method(sm, "protected void performSendMessageRequestMulti(")
        self.assertIn("colgramPrepareInstantSend();", perf_multi)
        self.assertIn("ConnectionsManager.RequestFlagNeedQuickAck, ConnectionsManager.DEFAULT_DATACENTER_ID, ConnectionsManager.ConnectionTypeGeneric, true);", perf_multi)

        for method_sig in (
            "public int editMessage(",
            "private int sendEditRichMessageRequest(",
            "public int sendVote(",
            "public int toggleTodo(",
            "public void sendReaction(",
            "public void sendGame(",
        ):
            body = extract_method(sm, method_sig)
            self.assertIn("colgramPrepareInstantSend();", body, method_sig)
            self.assertIn("ConnectionsManager.RequestFlagNeedQuickAck, ConnectionsManager.DEFAULT_DATACENTER_ID, ConnectionsManager.ConnectionTypeGeneric, true);", body, method_sig)

    def test_connections_manager_instant_wake_push_keepalive_and_jni_abi(self):
        cm = read_text(ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java")
        self.assertIn(
            "public static native void native_sendRequest(int currentAccount, long object, int flags, int datacenterId, int connectionType, boolean immediate, int requestToken);",
            cm,
        )
        push_fn = extract_method(cm, "public boolean isPushConnectionEnabled()")
        self.assertIn("ColgramConfig.isAlwaysBackgroundEnabled()", push_fn)
        self.assertIn('getBoolean("backgroundConnection", true)', push_fn)

        set_push = extract_method(cm, "public void setPushConnectionEnabled(boolean value)")
        self.assertIn("ColgramConfig.isAlwaysBackgroundEnabled()", set_push)

        init_fn = extract_method(cm, "public void init(")
        self.assertIn("boolean effectivePush = enablePushConnection || org.colgram.core.ColgramConfig.isAlwaysBackgroundEnabled();", init_fn)
        self.assertIn("native_setPushConnectionEnabled(currentAccount, true);", init_fn)

        paused_fn = extract_method(cm, "public void setAppPaused(")
        self.assertIn("if (!value && byScreenState)", paused_fn)
        self.assertIn("resumeNetworkMaybe();", paused_fn)
        self.assertIn("native_resumeNetwork(currentAccount, false);", paused_fn)
        self.assertIn("checkConnection();", paused_fn)
        self.assertIn("isPushConnectionEnabled()", paused_fn)

        send_internal = extract_method(cm, "private void sendRequestInternal(")
        self.assertIn("colgramInstantWake", send_internal)
        self.assertIn("colgramIsInstantSendRequest(object)", send_internal)
        self.assertIn("!org.colgram.core.ColgramDpiBypass.isBound()", send_internal)

    def test_application_loader_and_messages_controller_fast_sync(self):
        al = read_text(MSG / "ApplicationLoader.java")
        self.assertIn("NotificationCenter.screenStateChanged", al)
        self.assertIn("cm.resumeNetworkMaybe();", al)
        refresh_fn = extract_method(al, "private static void refreshNetworkAndNotify()")
        self.assertIn("cm.checkConnection();", refresh_fn)
        self.assertIn("cm.resumeNetworkMaybe();", refresh_fn)
        fg_fn = extract_method(al, "public void onActivityStarted(")
        self.assertIn("refreshNetworkAndNotify();", fg_fn)
        push_svc = extract_method(al, "public static void startPushService()")
        self.assertIn("ColgramConfig.isAlwaysBackgroundEnabled()", push_svc)
        self.assertIn("setPushConnectionEnabled(true)", push_svc)

        mc = read_text(MSG / "MessagesController.java")
        self.assertIn("connectionsManager.isPushConnectionEnabled() || org.colgram.core.ColgramConfig.isAlwaysBackgroundEnabled()", mc)
        self.assertIn("updatesStartWaitTime) <= 500", mc)
        self.assertIn("updatesStartWaitTimePts) <= 500", mc)
        self.assertIn("updatesStartWaitTimeSeq) <= 500", mc)
        self.assertIn("updatesStartWaitTimeQts) <= 500", mc)
        self.assertNotIn("updatesStartWaitTime) <= 1500", mc)
        self.assertNotIn("updatesStartWaitTimePts) <= 1500", mc)
        self.assertNotIn("updatesStartWaitTimeSeq) <= 1500", mc)
        self.assertNotIn("updatesStartWaitTimeQts) <= 1500", mc)
        self.assertNotIn("updatesStartWaitTime) >= 1500", mc)
        self.assertNotIn("getUpdatesStartTime(a)) >= 1500", mc)
        self.assertNotIn("getUpdatesStartTime(type)) <= 1500", mc)

    def test_apply_patches_registers_and_converges_turbo_messaging_patches(self):
        import importlib.util
        import shutil
        import tempfile

        ap = ROOT / "scripts/apply-patches.py"
        src = read_text(ap)
        self.assertIn("def inject_turbo_messaging_patches(repo_path):", src)
        self.assertIn("inject_turbo_messaging_patches(target_repo)", src.split("def main():", 1)[1])

        spec = importlib.util.spec_from_file_location("apply_patches_turbo_mod", ap)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)

        owned_rels = {rel for rel, _, _, _ in mod._TURBO_MESSAGING_PATCH_SPECS} | {
            "TMessagesProj/src/main/java/org/telegram/messenger/FileLoaderPriorityQueue.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/FileLoadOperation.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/ImageReceiver.java",
        }
        with tempfile.TemporaryDirectory() as tmp:
            tmp_tg = Path(tmp) / "Telegram-Src"
            for rel in owned_rels:
                src_file = ROOT / "Telegram-Src" / rel
                dst_file = tmp_tg / rel
                dst_file.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src_file, dst_file)
            mod.PATCH_MISSES.clear()
            for rel, label, target, replacement in mod._PERFORMANCE_PATCH_SPECS:
                if rel in owned_rels:
                    if mod._has_superseded_marker(str(tmp_tg / rel), label, mod._SUPERSEDED_PERF_MARKERS):
                        continue
                    mod._patch_file_eol(str(tmp_tg / rel), target, replacement, label)
            mod.inject_network_switch_patches(str(tmp_tg))
            mod.inject_turbo_messaging_patches(str(tmp_tg))
            self.assertEqual(mod.PATCH_MISSES, [])

    def test_foreground_service_stops_heartbeat_in_background_without_bots(self):
        src = read_text(ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramForegroundService.java")
        on_start = extract_method(src, "public int onStartCommand(Intent intent, int flags, int startId)")
        self.assertIn("if (ColgramUiBridge.isAppInForeground() || hasAnyBotTokens())", on_start)
        self.assertIn("stopHeartbeat();", on_start)

        start_hb = extract_method(src, "private void startHeartbeat()")
        self.assertIn("if (!fg && !hasAnyBotTokens())", start_hb)
        self.assertIn("stopHeartbeat();", start_hb)
        idx_stop = start_hb.index("stopHeartbeat();")
        idx_post = start_hb.index("handler.postDelayed(this, delay);")
        self.assertLess(idx_stop, idx_post)
        self.assertIn("return;", start_hb[idx_stop:idx_post])


class StartupSqliteAndPriorityOptimizationContractsTest(unittest.TestCase):
    def test_core_cold_start_offload_zero_regex_and_sqlite_pragmas(self):
        db = read_text(CORE / "ColgramDatabase.java")
        on_cfg = extract_method(db, "public void onConfigure(SQLiteDatabase db)")
        for pragma in (
            "PRAGMA synchronous=NORMAL",
            "PRAGMA temp_store=MEMORY",
            "PRAGMA cache_size=-4096",
            "PRAGMA mmap_size=16777216",
            "PRAGMA busy_timeout=5000",
        ):
            self.assertIn(pragma, on_cfg)
        self.assertIn("THREAD_PRIORITY_BACKGROUND", extract_method(db, "private void startPreloadDeletedCachesAsync()"))

        hh = read_text(CORE / "ColgramHookHandler.java")
        init_fn = extract_method(hh, "public static void init(Context context)")
        self.assertNotIn("\n            ColgramStorageIndex.init(", init_fn)
        self.assertIn("ColgramOptimizer.scheduleAfterStartup(ColgramHookHandler::invokeBridgeStartScheduler);", init_fn)
        self.assertIn("hookIoExecutor.execute(", init_fn)
        sched_fn = extract_method(hh, "private static void invokeBridgeStartScheduler()")
        self.assertIn("if (!ColgramConfig.isBroadcasterEnabled())", sched_fn)
        self.assertEqual(hh.count("THREAD_PRIORITY_BACKGROUND"), 3)

        um = read_text(CORE / "ColgramUnlimitedMemory.java")
        restore_fn = extract_method(um, "public static void restore(Context context)")
        before_sched, after_sched = restore_fn.split("ColgramOptimizer.scheduleAfterStartup(", 1)
        self.assertNotIn("ColgramStorageIndex.init(", before_sched)
        self.assertIn("ColgramStorageIndex.init(", after_sched)

        si = read_text(CORE / "ColgramStorageIndex.java")
        load_fn = extract_method(si, "private static void loadLocked(File file)")
        self.assertNotIn('.split("\\t"', load_fn)
        self.assertIn("line.indexOf('\\t')", load_fn)

        sb = read_text(CORE / "ColgramStorageSandbox.java")
        sb_init = extract_method(sb, "public static void init(Context context)")
        before_thread, in_thread = sb_init.split("Thread t = new Thread(", 1)
        self.assertNotIn("ensureDirWithNoMedia(", before_thread)
        self.assertIn("ensureDirWithNoMedia(resolvedRoot);", in_thread)
        self.assertIn("ensureDirWithNoMedia(resolvedCache);", in_thread)

        fi = read_text(CORE / "ColgramFileImport.java")
        self.assertIn("THREAD_PRIORITY_BACKGROUND", fi)
        bs = read_text(CORE / "ColgramBotSync.java")
        self.assertGreaterEqual(bs.count("THREAD_PRIORITY_BACKGROUND"), 5)

    def test_release_publish_mentions_10x_optimizations(self):
        rp = read_text(ROOT / "scripts/release_publish.py")
        self.assertIn("10x Optimization Pass", rp)
        self.assertIn("64 МБ `mmap`", rp)

    def test_dialogs_blur_coalesce_upgrade_skips_clean_upstream_without_miss(self):
        import importlib.util
        import tempfile

        ap = ROOT / "scripts/apply-patches.py"
        spec = importlib.util.spec_from_file_location("apply_patches_dlg_mod", ap)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)

        upstream_dlg = (
            "class DialogsActivity {\n"
            "            blurScrimPaint.setAlpha(oldScrimAlpha);\n"
            "        }\n\n"
            "        @Override\n"
            "        protected void dispatchDraw(Canvas canvas) {\n"
            "            if (Build.VERSION.SDK_INT >= 31 && scrollableViewNoiseSuppressor != null) {\n"
            "                blur3_InvalidateBlur();\n"
            "            }\n\n"
            "            if (invalidateScrollY && (rightSlidingDialogContainer == null || !rightSlidingDialogContainer.hasFragment()) && progressToActionMode == 0) {\n"
            "        }\n"
            "        @Override\n"
            "        public void invalidateBlur() {\n"
            "            super.invalidateBlur();\n"
            "            blur3_InvalidateBlur();\n"
            "        }\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            dlg_dir = Path(tmp) / "TMessagesProj/src/main/java/org/telegram/ui"
            dlg_dir.mkdir(parents=True, exist_ok=True)
            dlg = dlg_dir / "DialogsActivity.java"
            dlg.write_text(upstream_dlg, encoding="utf-8")
            mod.PATCH_MISSES.clear()
            # Only verify DialogsActivity perf #1 / #1b + pre-upgrade guard on clean upstream
            ap_src = read_text(ap)
            self.assertIn('if "private boolean colgramBlurredLastFrame;" in _dlg_src and "colgramLastBlurInvalidateMs" not in _dlg_src:', ap_src)
            for rel, label, target, replacement in mod._PERFORMANCE_PATCH_SPECS:
                if label in ("DialogsActivity.java perf #1", "DialogsActivity.java perf #1b"):
                    mod._patch_file_eol(str(dlg), target, replacement, label)
            self.assertEqual(mod.PATCH_MISSES, [])
            out = dlg.read_text(encoding="utf-8")
            self.assertIn("colgramLastBlurInvalidateMs = nowMs;", out)
            self.assertIn("colgramLastBlurInvalidateMs = 0L;", out)


if __name__ == "__main__":
    unittest.main()
