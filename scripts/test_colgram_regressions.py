"""Regression checks for the Colgram plugin and direct-network wiring."""

from pathlib import Path
import os
import re
import runpy
import subprocess
import tempfile
import unittest


ROOT = Path(os.environ.get("COLGRAM_SOURCE_ROOT", Path(__file__).resolve().parents[1]))
PATCHER = ROOT / "scripts/apply-patches.py"
MANAGER = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramPluginManager.java"
UI = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ColgramPluginsActivity.java"
PROXY = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramProxyManager.java"
HTTP = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramHttp.java"
CONFIG = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramConfig.java"
SECRETS = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramMtprotoSecrets.java"
ATTACH = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java"
CHAT = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
SETTINGS = ROOT / "scripts/templates/ColgramSettingsActivity.java"
LOGIN_ACTIVITY = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java"
DIALOGS_ACTIVITY = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java"
PROXY_LIST_ACTIVITY = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java"
STANDALONE_GRADLE = ROOT / "Telegram-Src/TMessagesProj_AppStandalone/build.gradle"
INTRO = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/IntroActivity.java"
USER_INFO = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/UserInfoActivity.java"
TL_BOTS = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/tgnet/tl/TL_bots.java"
TGNET_DEFINES = ROOT / "Telegram-Src/TMessagesProj/jni/tgnet/Defines.h"
TGNET_CONNECTIONS_MANAGER = ROOT / "Telegram-Src/TMessagesProj/jni/tgnet/ConnectionsManager.cpp"
BOT_SYNC = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramBotSync.java"
ANTI_SPAM_UI = ROOT / "scripts/templates/ColgramAntiSpamActivity.java"
TEMP_MAIL_UI = ROOT / "scripts/templates/ColgramTempMailActivity.java"
STORAGE = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramStorageSandbox.java"
SEARCH_PAGER = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/Components/SearchViewPager.java"
SEARCH_ADAPTER = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/Adapters/DialogsSearchAdapter.java"
WARP = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramWarp.java"
WARP_TUNNEL = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramWarpTunnel.java"
WARP_STATS = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramWarpStatistics.java"
CHAT_ACTIVITY = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
CHAT_CELL = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java"
MESSAGES_STORAGE_SOURCE = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java"
MESSAGES_CONTROLLER = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java"
RU_STRINGS = ROOT / "Telegram-Src/TMessagesProj/src/main/res/values-ru/strings.xml"
UNDO_VIEW = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/Components/UndoView.java"
HINT_VIEW = ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/Components/HintView.java"
DPI_BYPASS = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramDpiBypass.java"
INITIAL_PACKET_READER = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramInitialPacketReader.java"
FIRST_RESPONSE_READER = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramFirstResponseReader.java"
SOCKET_CONNECT_RACE = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramSocketConnectRace.java"
SOCKS5_CODEC = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramSocks5Codec.java"
PROXY_CHAIN = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramProxyChain.java"
TLS_MIMIC = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramTlsMimic.java"
RELAY_MISS_CACHE = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramRelayMissCache.java"
DIALOG_REFRESH_SEQUENCER = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramDialogRefreshSequencer.java"
DC_REMAP = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramDcRemap.java"
DC_ADDRESSES = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramTelegramDcAddresses.java"
PROBE_MISS_CACHE = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramProbeMissCache.java"
CONNECT_FAILURE_BACKOFF = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramConnectFailureBackoff.java"
PROXY_DOCTOR = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramProxyDoctor.java"


class ColgramRegressionTests(unittest.TestCase):
    def test_failed_direct_dc_dials_back_off_exponentially_and_network_change_clears_misses(self):
        self.assertTrue(CONNECT_FAILURE_BACKOFF.is_file(), "repeated unreachable DC dials need a bounded backoff")
        harness = r"""package org.colgram.core;
public final class ConnectFailureBackoffHarness {
  public static void main(String[] args) {
    ColgramConnectFailureBackoff backoff = new ColgramConnectFailureBackoff(1000L, 4000L);
    String dc = "149.154.167.51:443";
    if (backoff.shouldSkip(dc, 100L)) throw new AssertionError("fresh endpoint was suppressed");
    long ticket = backoff.tryBegin(dc, 100L);
    if (ticket < 0L || backoff.tryBegin(dc, 100L) >= 0L) throw new AssertionError("duplicate dial was not coalesced");
    if (backoff.finish(dc, ticket, false, 100L) != 1000L) throw new AssertionError("first delay must be 1s");
    if (!backoff.shouldSkip(dc, 1099L)) throw new AssertionError("first miss was not cooled down");
    if (backoff.shouldSkip(dc, 1100L)) throw new AssertionError("first cooldown did not expire");
    ticket = backoff.tryBegin(dc, 1100L);
    if (ticket < 0L || backoff.finish(dc, ticket, false, 1100L) != 2000L)
      throw new AssertionError("second delay must double");
    if (!backoff.shouldSkip(dc, 3099L)) throw new AssertionError("second miss was not cooled down");
    if (backoff.shouldSkip(dc, 3100L)) throw new AssertionError("second cooldown did not expire");
    ticket = backoff.tryBegin(dc, 3100L);
    if (ticket < 0L || backoff.finish(dc, ticket, false, 3100L) != 4000L)
      throw new AssertionError("delay must cap at 4s");
    if (!backoff.shouldSkip(dc, 7099L)) throw new AssertionError("capped cooldown ended early");
    ticket = backoff.tryBegin(dc, 7100L);
    backoff.clear();
    if (backoff.shouldSkip(dc, 3101L)) throw new AssertionError("network change did not clear failures");
    if (backoff.finish(dc, ticket, false, 7100L) != 0L)
      throw new AssertionError("a stale network failure was reinserted after clearing");
    ticket = backoff.tryBegin(dc, 7101L);
    if (ticket < 0L) throw new AssertionError("new network could not probe the endpoint");
    backoff.finish(dc, ticket, true, 7102L);
    if (backoff.shouldSkip(dc, 4001L)) throw new AssertionError("successful dial retained failure state");
    if (backoff.shouldSkip("149.154.167.52:443", 4001L)) throw new AssertionError("miss leaked across endpoints");
    System.out.println("CONNECT_FAILURE_BACKOFF_OK");
  }
}"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            harness_file = temp / "ConnectFailureBackoffHarness.java"
            harness_file.write_text(harness, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-d", str(temp), str(CONNECT_FAILURE_BACKOFF), str(harness_file)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", str(temp), "org.colgram.core.ConnectFailureBackoffHarness"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("CONNECT_FAILURE_BACKOFF_OK", result.stdout)

    def test_local_route_dial_misses_use_backoff_and_network_transitions_clear_it(self):
        bypass = DPI_BYPASS.read_text(encoding="utf-8")
        direct = bypass.split("if (!ColgramDcRemap.shouldSkipDirectAddress(destHost))", 1)[1].split(
            "if (targetSocket == null)", 1
        )[0]
        alternative = bypass.split("String live = ColgramDcRemap.liveAlternativeFor(destHost, destPort);", 1)[1].split(
            "if (targetSocket == null)", 1
        )[0]
        self.assertIn("connectWithBackoff(destHost, destPort)", direct)
        self.assertIn("connectWithBackoff(live, destPort)", alternative)
        self.assertIn("clearConnectionFailureBackoff()", PROXY.read_text(encoding="utf-8").split(
            "private static void invalidateNetworkProbeState(", 1
        )[1].split("private static final ExecutorService STARTUP_DEFERRED", 1)[0])

    def test_relay_target_miss_cache_expires_and_success_clears_it(self):
        self.assertTrue(RELAY_MISS_CACHE.is_file(), "relay misses need a bounded retry cache")
        harness = r"""package org.colgram.core;
public final class RelayMissCacheHarness {
  public static void main(String[] args) {
    String relay = "connect://relay.test:8080";
    String host = "149.154.167.51";
    if (ColgramRelayMissCache.shouldSkip(relay, host, 443, 1000L)) throw new AssertionError("fresh route was skipped");
    ColgramRelayMissCache.recordMiss(relay, host, 443, 1000L);
    if (!ColgramRelayMissCache.shouldSkip(relay, host, 443, 1000L)) throw new AssertionError("failed route was not cooled down");
    if (!ColgramRelayMissCache.shouldSkip(relay, host, 443, 30999L)) throw new AssertionError("cooldown ended early");
    if (ColgramRelayMissCache.shouldSkip(relay, host, 443, 31000L)) throw new AssertionError("expired route stayed blocked");
    ColgramRelayMissCache.recordMiss(relay, host, 443, 40000L);
    ColgramRelayMissCache.recordSuccess(relay, host, 443);
    if (ColgramRelayMissCache.shouldSkip(relay, host, 443, 40001L)) throw new AssertionError("successful route retained its miss");
    if (ColgramRelayMissCache.shouldSkip(relay, "149.154.167.52", 443, 40001L)) throw new AssertionError("miss leaked to a different DC");
    System.out.println("RELAY-MISS-CACHE-PASS");
  }
}"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            source = temp / "RelayMissCacheHarness.java"
            source.write_text(harness, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-d", str(temp), str(RELAY_MISS_CACHE), str(source)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", str(temp), "org.colgram.core.RelayMissCacheHarness"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("RELAY-MISS-CACHE-PASS", result.stdout)

    def test_failed_relay_target_does_not_poison_other_telegram_dc_routes(self):
        harness = r"""package org.colgram.core;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
public final class RelayTargetFailoverHarness {
  static String readHeaders(InputStream in) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int ch;
    while ((ch = in.read()) >= 0) {
      bytes.write(ch);
      byte[] value = bytes.toByteArray(); int n = value.length;
      if (n >= 4 && value[n - 4] == '\r' && value[n - 3] == '\n' && value[n - 2] == '\r' && value[n - 1] == '\n') break;
    }
    return bytes.toString("US-ASCII");
  }
  public static void main(String[] args) throws Exception {
    ServerSocket listener = new ServerSocket(0);
    AtomicInteger accepts = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread server = new Thread(() -> {
      try {
        for (int i = 0; i < 2; i++) {
          try (Socket peer = listener.accept()) {
            int n = accepts.incrementAndGet();
            String request = readHeaders(peer.getInputStream());
            String expected = n == 1 ? "CONNECT blocked-dc.test:443 " : "CONNECT reachable-dc.test:443 ";
            if (!request.startsWith(expected)) throw new AssertionError("unexpected destination: " + request);
            String status = n == 1 ? "HTTP/1.1 502 Bad Gateway\r\n\r\n" : "HTTP/1.1 200 Connection established\r\n\r\n";
            OutputStream out = peer.getOutputStream(); out.write(status.getBytes(StandardCharsets.US_ASCII)); out.flush();
          }
        }
      } catch (Throwable t) { failure.set(t); }
    });
    server.setDaemon(true); server.start();
    ColgramProxyChain.Relay relay = new ColgramProxyChain.Relay("127.0.0.1", listener.getLocalPort(), false);
    try {
      if (ColgramProxyChain.probe(relay, "blocked-dc.test", 443, 1000) >= 0) throw new AssertionError("blocked DC unexpectedly passed");
      if (relay.dead) throw new AssertionError("failure to one target marked the relay itself dead");
      if (ColgramProxyChain.probe(relay, "blocked-dc.test", 443, 1000) >= 0) throw new AssertionError("cached miss unexpectedly passed");
      if (accepts.get() != 1) throw new AssertionError("same failed route was retried instead of cooled down: " + accepts.get());
      if (ColgramProxyChain.probe(relay, "reachable-dc.test", 443, 1000) < 0) throw new AssertionError("the same relay did not try a second DC");
      if (relay.dead) throw new AssertionError("successful second DC left relay marked dead");
      if (failure.get() != null) throw new AssertionError("fake relay failed", failure.get());
      System.out.println("RELAY-TARGET-FAILOVER-PASS");
    } finally { ColgramProxyChain.closeAll(); listener.close(); }
  }
}"""
        log_stub = r"""package android.util;
public final class Log {
  public static int i(String t, String m) { return 0; }
  public static int w(String t, String m) { return 0; }
  public static int w(String t, String m, Throwable e) { return 0; }
  public static int e(String t, String m, Throwable e) { return 0; }
}"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            harness_file = temp / "RelayTargetFailoverHarness.java"
            stub_file = temp / "android/util/Log.java"
            harness_file.parent.mkdir(parents=True, exist_ok=True)
            stub_file.parent.mkdir(parents=True, exist_ok=True)
            harness_file.write_text(harness, encoding="utf-8")
            stub_file.write_text(log_stub, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-d", str(temp), str(stub_file), str(SOCKS5_CODEC), str(RELAY_MISS_CACHE), str(INITIAL_PACKET_READER), str(TLS_MIMIC), str(PROXY_CHAIN), str(harness_file)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", str(temp), "org.colgram.core.RelayTargetFailoverHarness"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("RELAY-TARGET-FAILOVER-PASS", result.stdout)

    def test_socks5_codec_handles_fragmented_reads_and_truncated_streams(self):
        self.assertTrue(SOCKS5_CODEC.is_file(), "the SOCKS5 byte codec must be present")
        harness = r"""package org.colgram.core;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.util.Arrays;
public final class Socks5CodecHarness {
    private static final class OneByteInputStream extends ByteArrayInputStream {
        OneByteInputStream(byte[] bytes) { super(bytes); }
        @Override public synchronized int read(byte[] out, int offset, int length) {
            return super.read(out, offset, Math.min(1, length));
        }
    }
    public static void main(String[] args) throws Exception {
        byte[] actual = ColgramSocks5Codec.readFully(
            new OneByteInputStream(new byte[] { 5, 1, 0, 4 }), 4);
        if (!Arrays.equals(actual, new byte[] { 5, 1, 0, 4 })) {
            throw new AssertionError("short reads were not assembled");
        }
        try {
            ColgramSocks5Codec.readFully(new OneByteInputStream(new byte[] { 5 }), 2);
            throw new AssertionError("truncated streams must fail");
        } catch (EOFException expected) { }
        try {
            ColgramSocks5Codec.readByte(new ByteArrayInputStream(new byte[0]));
            throw new AssertionError("missing bytes must fail");
        } catch (EOFException expected) { }
        System.out.println("SOCKS5-CODEC-PASS");
    }
}"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            harness_file = temp / "Socks5CodecHarness.java"
            harness_file.write_text(harness, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-d", str(temp), str(SOCKS5_CODEC), str(harness_file)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", str(temp), "org.colgram.core.Socks5CodecHarness"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("SOCKS5-CODEC-PASS", result.stdout)

    def test_local_socks5_handler_uses_exact_reads_for_variable_fields(self):
        source = DPI_BYPASS.read_text(encoding="utf-8")
        self.assertIn("ColgramSocks5Codec.readFully(in, nmethods)", source)
        self.assertIn("ColgramSocks5Codec.readFully(in, 4)", source)
        self.assertIn("ColgramSocks5Codec.readFully(in, len)", source)
        self.assertIn("ColgramSocks5Codec.readFully(in, 16)", source)
        self.assertIn("ColgramSocks5Codec.readFully(in, 2)", source)

    def test_direct_telegram_dc_remap_is_always_on_and_hidden_from_network_toggles(self):
        config = CONFIG.read_text(encoding="utf-8")
        getter = config.split("public static boolean isDcRemapEnabled()", 1)[1].split(
            "public static void setDcRemapEnabled", 1
        )[0]
        setter = config.split("public static void setDcRemapEnabled(boolean enabled)", 1)[1].split(
            "public static String getRelayUrl", 1
        )[0]
        self.assertIn("return true;", getter)
        self.assertIn("putBoolean(KEY_DC_REMAP, true)", setter)

        manager = PROXY.read_text(encoding="utf-8")
        startup = manager.split("private static void activateBuiltinProxyNow()", 1)[1].split(
            "private static void initVerifiedPool()", 1
        )[0]
        self.assertIn(
            "else if (ColgramConfig.isDcRemapEnabled() && ColgramConfig.isBuiltinProxyEnabled())",
            startup,
        )
        self.assertIn("executor.execute(() -> applyDcRemap(true));", startup)
        self.assertIn("private static final int DC_REMAP_VERDICT_TICKS = 3;", manager)

        settings = SETTINGS.read_text(encoding="utf-8")
        rows = settings.split("private void updateRows()", 1)[1].split("if (listAdapter != null)", 1)[0]
        self.assertIn("ipv6BypassRow = dcRemapRow = networkSectionRow = -1;", rows)
        self.assertNotIn("dcRemapRow = rowCount++", rows)

    def test_dc_remap_suppresses_repeated_failed_scans_and_rechecks_after_network_change(self):
        self.assertTrue(PROBE_MISS_CACHE.is_file(), "negative probe cache must be implemented")
        remap = DC_REMAP.read_text(encoding="utf-8")
        manager = PROXY.read_text(encoding="utf-8")
        self.assertIn("probeMisses.tryBegin(missKey", remap)
        self.assertIn("probeMisses.finish(missKey, chosen != null", remap)
        self.assertIn("public static void invalidateFailedProbes()", remap)
        self.assertIn("registerDefaultNetworkCallback", manager)
        self.assertIn("ColgramDcRemap.invalidateFailedProbes()", manager)

        harness = r"""package org.colgram.core;
public final class ProbeMissCacheHarness {
    public static void main(String[] args) {
        ColgramProbeMissCache cache = new ColgramProbeMissCache(30_000L);
        String key = "149.154.175.*:443";
        if (!cache.tryBegin(key, 1_000L)) throw new AssertionError("first scan must start");
        if (cache.tryBegin(key, 1_001L)) throw new AssertionError("duplicate scan must coalesce");
        cache.finish(key, false, 1_100L);
        if (cache.tryBegin(key, 30_999L)) throw new AssertionError("failure must cool down");
        if (!cache.tryBegin(key, 31_100L)) throw new AssertionError("scan must resume at expiry");
        cache.finish(key, true, 31_101L);
        if (!cache.tryBegin(key, 31_102L)) throw new AssertionError("success must clear miss");
        cache.finish(key, false, 31_103L);
        cache.clearFailures();
        if (!cache.tryBegin(key, 31_104L)) throw new AssertionError("network change must clear miss");
        cache.finish(key, false, 31_105L);
        cache.clearFailures();
        if (!cache.tryBegin(key, 31_106L)) throw new AssertionError("stale in-flight result must not restore miss");
        cache.finish(key, true, 31_107L);
        System.out.println("DC-PROBE-MISS-CACHE-PASS");
    }
}"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            harness_file = temp / "ProbeMissCacheHarness.java"
            harness_file.write_text(harness, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-d", str(temp), str(PROBE_MISS_CACHE), str(harness_file)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", str(temp), "org.colgram.core.ProbeMissCacheHarness"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("DC-PROBE-MISS-CACHE-PASS", result.stdout)

    def test_plugin_picker_uses_document_display_name_and_allows_plugin_extension(self):
        source = UI.read_text(encoding="utf-8")
        self.assertTrue("OpenableColumns.DISPLAY_NAME" in source)
        self.assertNotIn("Intent.EXTRA_MIME_TYPES", source)
        self.assertTrue('lowerName.endsWith(".plugin")' in source)

    def test_commands_import_filename_not_display_title(self):
        source = MANAGER.read_text(encoding="utf-8")
        self.assertTrue("String module = plugin.fileName.substring" in source)
        self.assertNotIn('"import " + plugin.name', source)
        self.assertTrue("plugin.builtIn ? Long.toString(dialogId)" in source)

    def test_bundled_features_are_not_user_plugins(self):
        source = MANAGER.read_text(encoding="utf-8")
        self.assertTrue("isBundledFeature(fileName)" in source)
        self.assertTrue('if ("extera_compat.py".equals(fileName)) continue;' in source)
        self.assertNotIn("createDefaultPluginsIfEmpty(internalPluginsDir);", source)

    def test_direct_connection_watchdog_reaches_recovery(self):
        source = PROXY.read_text(encoding="utf-8")
        self.assertTrue("directUnconnectedTicks" in source)
        self.assertTrue('requestFreshDcAddresses("direct connection stalled")' in source)
        self.assertTrue("autoConnectIfBlocked();" in source)

    def test_manual_proxy_off_cannot_be_undone_by_watchdog_or_remap(self):
        source = PROXY.read_text(encoding="utf-8")
        watchdog = source.split("private static void connectionWatchdogTick()", 1)[1].split(
            "private static int tgnetConnectionState()", 1
        )[0]
        self.assertIn(
            "if (ColgramConfig.isDcRemapEnabled() && !isUserProxyDisabled())",
            watchdog,
        )
        remap = source.split("public static void applyDcRemap(final boolean enabled)", 1)[1].split(
            "/** Explicit settings action", 1
        )[0]
        self.assertIn("if (isUserProxyDisabled())", remap)
        self.assertNotIn("setUserProxyDisabled(false)", remap)
        self.assertIn("public static void applyDcRemapFromUser(final boolean enabled)", source)
        settings = SETTINGS.read_text(encoding="utf-8")
        self.assertIn("ColgramProxyManager.applyDcRemapFromUser(remap)", settings)

    def test_runtime_proxy_is_applied_from_selected_item_not_stale_preferences(self):
        source = PROXY.read_text(encoding="utf-8")
        apply_method = source.split("public static void forceApplyProxy(ProxyItem proxy)", 1)[1]
        apply_method = apply_method.split("public static boolean isProxyEnabled", 1)[0]
        self.assertTrue("Object settings = buildProxySettings(proxy);" in apply_method)
        self.assertNotIn('getMethod("fromSharedPreferences"', apply_method)
        self.assertTrue("proxy.effectiveHost(), proxy.effectivePort()" in apply_method)

    def test_runtime_proxy_gets_watchdog_and_direct_remap_fallback(self):
        source = PROXY.read_text(encoding="utf-8")
        self.assertTrue("!isDcRemapActive() && currentActiveProxy == null" in source)
        self.assertTrue("if (ColgramConfig.isAutoProxyEnabled()) autoConnectIfBlocked();" in source)
        self.assertTrue("active != null && active.autoSelected && !shouldFallbackFromLocalDpi(active)" in source)
        config = CONFIG.read_text(encoding="utf-8")
        # Auto fallback is ON by default now: on a network that refuses Telegram's own
        # addresses, an off default left the app on "Соединение..." forever.
        self.assertIn("prefs.getBoolean(KEY_AUTO_PROXY, true)", config.split("public static boolean isAutoProxyEnabled()", 1)[1]
                      .split("public static void setAutoProxyEnabled", 1)[0])

    def test_automatic_proxy_fallback_is_off_by_default_and_restores_remembered_proxies(self):
        config = CONFIG.read_text(encoding="utf-8")
        auto = config.split("public static boolean isAutoProxyEnabled()", 1)[1].split("public static void setAutoProxyEnabled", 1)[0]
        self.assertIn("prefs.getBoolean(KEY_AUTO_PROXY, true)", auto)
        setter = config.split("public static void setAutoProxyEnabled(boolean enabled)", 1)[1].split("/**", 1)[0]
        self.assertIn("putBoolean(KEY_AUTO_PROXY, enabled)", setter)
        # The once-only "force the fallback off" migration is gone together with the off
        # default it served; the migration anchor must stay removed.
        self.assertNotIn("migrateAutomaticProxyFallbackOff", config)
        source = PROXY.read_text(encoding="utf-8")
        block = source.split("private static void autoConnectIfBlocked()", 1)[1].split("private static void connectThroughBestNode()", 1)[0]
        self.assertIn("isProxyEnabled(ctx) && carriesProxy", block)
        self.assertIn("active != dcRemapItem", block)
        self.assertIn("!active.isLocalDpi()", block)
        self.assertNotIn("if (isProxyEnabled(ctx)) return;", block)
        self.assertIn("autoConnectPending.compareAndSet(false, true)", source)
        pool = source.split("private static void initVerifiedPool()", 1)[1].split("private static final long NATIVE_CHECK_TIMEOUT_MS", 1)[0]
        self.assertIn('new ProxyItem("127.0.0.1"', pool)
        self.assertIn("loadRemembered();", pool)
        self.assertNotIn("remove(KEY_ALIVE)", pool)
        self.assertNotIn("hardcoded", pool)

    def test_failed_local_bypass_does_not_auto_apply_or_rotate_to_a_public_proxy(self):
        source = PROXY.read_text(encoding="utf-8")
        rotation = source.split("public static synchronized void switchToNextProxy(boolean force)", 1)[1].split(
            "if (verifiedPool.isEmpty())", 1
        )[0]
        self.assertIn("!force && currentActiveProxy != null && currentActiveProxy.isLocalDpi()", rotation)
        self.assertIn("!ColgramConfig.isAutoProxyEnabled()", rotation)
        verdict = source.split("private static void onVerdict(ProxyItem item, boolean alive)", 1)[1].split(
            "private static void prunePool()", 1
        )[0]
        self.assertIn("&& ColgramConfig.isAutoProxyEnabled()", verdict)
        watchdog = source.split("private static void connectionWatchdogTick()", 1)[1].split(
            "private static int tgnetConnectionState()", 1
        )[0]
        self.assertIn("active.isLocalDpi() && !ColgramConfig.isAutoProxyEnabled()", watchdog)

    def test_enabling_local_bypass_notification_does_not_reenable_proxy_fallback(self):
        source = PROXY.read_text(encoding="utf-8")
        notice = source.split("public static void enableBypassFromNotification(", 1)[1].split(
            "/** Live UI refresh", 1
        )[0]
        self.assertNotIn("setAutoProxyEnabled(true)", notice)
        self.assertIn("setDpiBypassEnabled(appContext, true)", notice)

    def test_proxy_feeds_have_persistent_os_scheduled_background_refresh(self):
        source = PROXY.read_text(encoding="utf-8")
        self.assertIn("ColgramBootJobService.scheduleProxyRefresh(appContext)", source)
        background = source.split("public static void refreshProxySourcesInBackground(", 1)[1].split("private static void harvestRelays()", 1)[0]
        self.assertIn("fetchAndVerifyAllSources()", background)
        service = (ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramBootJobService.java").read_text(encoding="utf-8")
        self.assertIn("PROXY_REFRESH_JOB_ID", service)
        self.assertIn("setPeriodic(PROXY_REFRESH_INTERVAL_MS)", service)
        self.assertIn("setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)", service)
        self.assertIn("setPersisted(true)", service)
        self.assertIn("refreshProxySourcesInBackground", service)

    def test_remembered_proxy_cache_is_rechecked_and_can_auto_connect(self):
        source = PROXY.read_text(encoding="utf-8")
        init = source.split("private static void initVerifiedPool()", 1)[1].split(
            "private static final long NATIVE_CHECK_TIMEOUT_MS", 1
        )[0]
        self.assertLess(init.index('verifiedPool.add(localDpi);'), init.index("loadRemembered();"))
        loader = source.split("private static void loadRemembered()", 1)[1].split(
            "private static final long REMEMBERED_VALID_MS", 1
        )[0]
        self.assertIn('getString(KEY_ALIVE, "")', loader)
        self.assertIn("p.isAvailable = false;", loader)
        self.assertIn("p.lastCheckAt = staleBefore;", loader)
        self.assertIn("addCandidate(p);", loader)
        startup = source.split("private static void activateBuiltinProxyNow()", 1)[1].split(
            "private static void initVerifiedPool()", 1
        )[0]
        self.assertIn("sweepFast(null);", startup)
        self.assertLess(startup.index("sweepFast(null);"), startup.index("fetchAndVerifyAllSources();"))
        apply_method = source.split("public static void forceApplyProxy(ProxyItem proxy)", 1)[1].split(
            "public static boolean isProxyEnabled", 1
        )[0]
        self.assertIn("if (isInternalLoopbackEndpoint(proxy) || proxy == dcRemapItem)", apply_method)
        self.assertIn("disableStockRotation(ctx);", apply_method)
        shared_state = source.split("private static void updateSharedConfigProxyState(ProxyItem proxy)", 1)[1].split(
            "private static void notifyProxySettingsChanged", 1
        )[0]
        self.assertIn("else {\n                enableStockRotation(appContext);", shared_state)
        rotation = source.split("private static void disableStockRotation(Context ctx)", 1)[1]
        self.assertIn('putBoolean("proxyRotationEnabled", false)', rotation)

    def test_startup_checks_local_candidates_before_remote_harvest(self):
        source = PROXY.read_text(encoding="utf-8")
        startup = source.split("private static void activateBuiltinProxyNow()", 1)[1].split("private static void initVerifiedPool()", 1)[0]
        self.assertLess(startup.index("sweepFast(null);"), startup.index("fetchAndVerifyAllSources();"))

    def test_local_desync_is_applied_without_direct_probe_gate_and_keeps_proxy_recovery_available(self):
        source = PROXY.read_text(encoding="utf-8")
        startup = source.split("private static void activateBuiltinProxyNow()", 1)[1].split(
            "// 2. Start with the local path", 1
        )[0]
        self.assertNotIn("if (localReady && !telegramDirectlyReachable())", startup)
        self.assertNotIn("localRouteUnavailableUntil", startup)
        self.assertIn("if (localBypassUsable() && ColgramConfig.isBuiltinProxyEnabled())", source)
        setter = source.split("public static void setDpiBypassEnabled(", 1)[1].split("private static void toast", 1)[0]
        self.assertNotIn("telegramDirectlyReachable()", setter)
        self.assertLess(setter.index("ColgramDpiBypass.awaitReady(20000)"), setter.index("forceApplyProxy(local);"))
        publisher = source.split("private static void publishPoolToStock()", 1)[1].split("private static void enableStockRotation", 1)[0]
        self.assertNotIn("if (ColgramConfig.isDpiBypassEnabled() ||", publisher)

    def test_proxy_feeds_stay_published_during_bypass_without_arming_rotation(self):
        source = PROXY.read_text(encoding="utf-8")
        publisher = source.split("private static void publishPoolToStock()", 1)[1].split(
            "private static void enableStockRotation", 1
        )[0]
        self.assertIn("boolean bypassOwnsRoute =", publisher)
        self.assertIn("addProxy.invoke(null, piCtor.newInstance(settings));", publisher)
        self.assertIn("if (bypassOwnsRoute)", publisher)
        self.assertIn('published " + published', publisher)
        self.assertIn("stock rotation remains paused", publisher)
        self.assertIn("disableStockRotation(ctx);", publisher)
        self.assertIn("enableStockRotation(ctx);", publisher)
        self.assertLess(publisher.index("addProxy.invoke(null, piCtor.newInstance(settings));"),
                        publisher.index("if (bypassOwnsRoute)"))

    def test_internal_loopback_routes_never_persist_as_public_proxies(self):
        source = PROXY.read_text(encoding="utf-8")
        publisher = source.split("private static void publishPoolToStock()", 1)[1].split(
            "private static void enableStockRotation", 1
        )[0]
        self.assertIn("buildProxyListSettings(p)", publisher)
        self.assertNotIn("buildProxySettings(p)", publisher)
        self.assertIn("if (!p.nativeVerified && p.tcpMs < 0) continue;", publisher)
        self.assertIn("if (isInternalLoopbackEndpoint(p)) continue;", publisher)
        self.assertIn("publishPoolToStock();", source.split("private static void finishSweep", 1)[1].split("private static final int FAST_NATIVE_PARALLEL", 1)[0])
        self.assertIn("MAX_PUBLISHED_TO_STOCK = 30", source)
        apply_method = source.split("public static void forceApplyProxy(ProxyItem proxy)", 1)[1].split(
            "public static boolean isProxyEnabled", 1
        )[0]
        self.assertIn("updateSharedConfigProxyState(proxy)", apply_method)
        self.assertIn("isInternalLoopbackEndpoint(proxy)", apply_method)
        shared_state = source.split("private static void updateSharedConfigProxyState(", 1)[1].split(
            "private static void notifyProxySettingsChanged", 1
        )[0]
        self.assertIn("removeInternalLoopbackProxies(scClass)", shared_state)
        self.assertIn("buildProxyListSettings(proxy)", shared_state)
        self.assertIn("Looper.myLooper() == Looper.getMainLooper()", apply_method)
        cleanup = source.split("private static int removeInternalLoopbackProxies(", 1)[1].split(
            "private static void enableStockRotation", 1
        )[0]
        self.assertIn("getAddress", cleanup)
        self.assertIn("proxyList.remove(i)", cleanup)
        self.assertIn("item == null", cleanup)
        self.assertIn("saveProxyList", cleanup)
        verdict = source.split("private static void onVerdict(", 1)[1].split(
            "private static void prunePool", 1
        )[0]
        self.assertIn("mainHandler.post(() -> publishPoolToStock());", verdict)

    def test_ephemeral_loopback_relay_routes_are_never_remembered_across_restarts(self):
        source = PROXY.read_text(encoding="utf-8")
        remember = source.split("private static void rememberAlive()", 1)[1].split(
            "private static void loadRemembered()", 1
        )[0]
        load = source.split("private static void loadRemembered()", 1)[1].split(
            "private static final long REMEMBERED_VALID_MS", 1
        )[0]
        self.assertIn("isInternalLoopbackEndpoint(p)", remember)
        self.assertIn("isInternalLoopbackEndpoint(p)", load)
        self.assertIn("discarding stale process-local proxy cache entry", load)

    def test_external_loopback_proxy_candidates_are_rejected(self):
        source = PROXY.read_text(encoding="utf-8")
        add = source.split("private static boolean addCandidate(", 1)[1].split(
            "/** Plain HTTP GET as a string", 1
        )[0]
        self.assertIn("isLoopbackHost(item.address)", add)
        self.assertIn("!item.isLocalDpi()", add)
        self.assertIn("!isLocalRelayRoute(item)", add)

    def test_tcp_dead_proxy_is_invalidated_and_skipped_by_native_probe(self):
        source = PROXY.read_text(encoding="utf-8")
        sweep = source.split("public static void sweepFast(final Runnable onDone)", 1)[1].split(
            "private static void finishSweep", 1
        )[0]
        failed_tcp = sweep.split("item.tcpMs = -2;", 1)[1].split(
            "item.tcpCheckedAt = SystemClock.elapsedRealtime();", 1
        )[0]
        self.assertIn("item.chainRelay = null;", failed_tcp)
        self.assertIn("item.chainPort = 0;", failed_tcp)
        self.assertIn("item.isAvailable = false;", failed_tcp)
        self.assertIn("item.nativeVerified = false;", failed_tcp)
        self.assertIn("item.pingMs = -2;", failed_tcp)

        prober = source.split("private static ProxyItem nextProberTarget()", 1)[1].split(
            "private static boolean betterCandidate", 1
        )[0]
        self.assertIn("applied.tcpMs != -2", prober)
        self.assertIn("if (p.tcpMs == -2) continue;", prober)

    def test_fast_proxy_checks_leave_tgnet_slots_for_serial_prober_and_ui(self):
        manager = PROXY.read_text(encoding="utf-8")
        defines = TGNET_DEFINES.read_text(encoding="utf-8")
        native_capacity = int(re.search(r"#define PROXY_CONNECTIONS_COUNT (\d+)", defines).group(1))
        fast_capacity = int(re.search(r"FAST_NATIVE_PARALLEL = (\d+)", manager).group(1))
        self.assertLessEqual(
            fast_capacity,
            native_capacity - 2,
            "Fast sweeps must leave tgnet slots for its single-flight prober and the user's proxy check",
        )

    def test_relay_fallback_is_native_checked_even_when_candidate_is_duplicate_or_pool_is_full(self):
        source = PROXY.read_text(encoding="utf-8")
        fallback = source.split("private static void probeRelayFallbacks()", 1)[1].split(
            "private static void scheduleRelayRouteAutoApply", 1
        )[0]
        self.assertIn("findPoolCandidate(candidate)", fallback)
        self.assertIn("checkOne(verifiedRoute, alive -> onVerdict(verifiedRoute, alive))", fallback)
        self.assertNotIn("if (added && !checkOne(candidate", fallback)

    def test_tcp_open_relay_is_never_auto_applied_without_native_telegram_verdict(self):
        source = PROXY.read_text(encoding="utf-8")
        fallback = source.split("private static void scheduleRelayRouteAutoApply(", 1)[1].split(
            "private static void collectRelays(", 1
        )[0]
        self.assertIn("if (!candidate.nativeVerified)", fallback)
        self.assertLess(fallback.index("if (!candidate.nativeVerified)"), fallback.index("forceApplyProxy(candidate)"))
        self.assertIn("scheduleRelayRouteAutoApply(candidate, attemptsRemaining - 1)", fallback)
        self.assertIn("native-verified Telegram relay route", fallback)
        self.assertNotIn("auto-applied the TCP-proven Telegram relay route", fallback)

    def test_loopback_route_status_never_displays_ephemeral_localhost_port(self):
        manager = PROXY.read_text(encoding="utf-8")
        doctor = PROXY_DOCTOR.read_text(encoding="utf-8")
        remap = DC_REMAP.read_text(encoding="utf-8")
        to_string = manager.split("public String toString()", 1)[1].split("\n        }", 1)[0]
        self.assertIn("Внутренний локальный маршрут", to_string)
        self.assertIn("Локальный мост через ретранслятор", to_string)
        self.assertIn('return "Активный маршрут: " + current', doctor)
        self.assertNotIn('return "127.0.0.1:" + boundPort', remap)

    def test_local_relay_front_is_not_chained_back_through_its_own_relay(self):
        source = PROXY.read_text(encoding="utf-8")
        resolve = source.split("private static void resolveChain(ProxyItem proxy)", 1)[1].split(
            "private static volatile long lastApplyAt", 1
        )[0]
        self.assertIn("isInternalLoopbackEndpoint(proxy)", resolve)
        self.assertLess(resolve.index("isInternalLoopbackEndpoint(proxy)"),
                        resolve.index("ColgramProxyChain.open(proxy.address"))

    def test_dc_remap_is_not_classified_as_a_user_configured_proxy_during_failover(self):
        source = PROXY.read_text(encoding="utf-8")
        rotation = source.split("public static synchronized void switchToNextProxy(boolean force)", 1)[1].split(
            "long now = SystemClock.elapsedRealtime();", 1
        )[0]
        self.assertIn("currentActiveProxy != dcRemapItem", rotation)

    def test_native_failed_relay_route_retries_the_next_reachable_relay(self):
        source = PROXY.read_text(encoding="utf-8")
        fallback = source.split("private static void probeRelayFallbacks()", 1)[1].split(
            "private static void scheduleRelayRouteAutoApply", 1
        )[0]
        verdict = source.split("private static void onVerdict(", 1)[1].split(
            "private static void prunePool", 1
        )[0]
        self.assertIn("relayFallbackCursor", fallback)
        self.assertIn("relayFallbackCursor.set((relayIndex + 1) % relays.size())", fallback)
        self.assertIn("scheduleRelayFallbackRetry()", verdict)
        self.assertIn("failedVerdicts == 1", verdict)

    def test_periodic_proxy_feed_refresh_respects_low_battery_guard(self):
        source = PROXY.read_text(encoding="utf-8")
        refresh = source.split("// 6. Refresh public proxy feeds", 1)[1]
        refresh = refresh.split("/** tgnet's ConnectionStateConnected", 1)[0]
        self.assertIn("ColgramPowerGuard.shouldThrottle(appContext)", refresh)
        self.assertLess(refresh.index("ColgramPowerGuard.shouldThrottle(appContext)"),
                        refresh.index("fetchAndVerifyAllSources();"))

    def test_wallpaper_stays_in_chat_header_not_every_message_menu(self):
        source = CHAT.read_text(encoding="utf-8")
        menu = source.split("public void fillMessageMenu(", 1)[1].split("final MessageObject message = selectedObject;", 1)[0]
        self.assertNotIn("Обои чата", menu)
        self.assertNotIn("options.add(9987)", menu)
        self.assertIn("R.string.SetWallpapers", source)
        patcher = (ROOT / "scripts/apply-patches.py").read_text(encoding="utf-8")
        self.assertNotIn("Chat Wallpaper Menu Option", patcher)

    def test_edit_message_handles_stale_fragment_and_missing_suggestion_payload(self):
        source = CHAT.read_text(encoding="utf-8")
        method = source.split(
            "private void startEditingMessageObject(MessageObject messageObject, boolean asSuggestion)", 1
        )[1].split("public void setupStickerVibrationAndSound", 1)[0]
        guard = method.index("messageObject == null || messageObject.messageOwner == null")
        self.assertLess(guard, method.index("mentionContainer.getAdapter().setNeedBotContext(false)"))
        self.assertIn("chatActivityEnterView == null", method[:method.index("selectionReactionsOverlay")])
        self.assertIn("asSuggestion && messageOwner.suggested_post == null", method)
        self.assertIn("if (inputPeer == null)", method)

    def test_anti_delete_keeps_live_rows_and_uses_a_clear_label(self):
        activity = CHAT_ACTIVITY.read_text(encoding="utf-8")
        method = activity.split(
            "private void processDeletedMessages(ArrayList<Integer> markAsDeletedMessages, long channelId, boolean sent, boolean thanos)",
            1,
        )[1].split("private final BotForumHelper.BotDraftAnimationsPool", 1)[0]
        after_channel_guard = method.split("int loadIndex = 0;", 1)[1].split(
            "if (replyingMessageObject != null", 1
        )[0]
        self.assertIn("ColgramConfig.isAntiDeleteEnabled()", after_channel_guard)
        self.assertIn("ColgramHookHandler.hookShouldPreventDelete", method)
        self.assertIn("chatAdapter.notifyItemChanged", after_channel_guard)
        self.assertIn("return;", after_channel_guard)

        patcher = (ROOT / "scripts/apply-patches.py").read_text(encoding="utf-8")
        self.assertIn("ChatActivity Keep Anti-Deleted Rows In Live Adapter", patcher)
        self.assertIn("anti_delete_live_anchor", patcher)

        storage = MESSAGES_STORAGE_SOURCE.read_text(encoding="utf-8")
        delete_gate = storage.split(
            "if (org.colgram.core.ColgramConfig.isAntiDeleteEnabled() && messages != null) {", 1
        )[1].split("if (messages.isEmpty())", 1)[0]
        self.assertIn("new java.util.ArrayList<>(messages)", delete_gate)
        self.assertIn("messagesToDelete.removeAll(toRemove)", delete_gate)
        self.assertNotIn("messages.removeAll(toRemove)", delete_gate)
        self.assertIn("messagesToDelete.removeAll(toRemove);", patcher)

        cell = CHAT_CELL.read_text(encoding="utf-8")
        self.assertIn("R.string.ColgramDeletedMessage", cell)
        self.assertNotIn('TextUtils.concat("🗑 "', cell)
        settings = (ROOT / "scripts/templates/ColgramSettingsActivity.java").read_text(encoding="utf-8")
        self.assertIn('"Показывать метку «Удалено»"', settings)
        self.assertNotIn('"Помечать удалённые значком 🗑"', settings)
        english = (ROOT / "Telegram-Src/TMessagesProj/src/main/res/values/strings.xml").read_text(encoding="utf-8")
        russian = RU_STRINGS.read_text(encoding="utf-8")
        self.assertIn('<string name="ColgramDeletedMessage">Deleted</string>', english)
        self.assertIn('<string name="ColgramDeletedMessage">Удалено</string>', russian)

    def test_bot_api_private_peer_is_cached_without_clobbering_known_identity(self):
        source = BOT_SYNC.read_text(encoding="utf-8")
        method = source.split("private static int processUpdatesJson(", 1)[1].split(
            "private static JSONObject botApiPost", 1
        )[0]
        self.assertIn('"private".equals(chatObj.optString("type")) && chatId != fromId', method)
        self.assertIn("ensureBotApiUser(mc, mcClass, userClass, userStatusClass, usersList,", method)
        helper = source.split("private static void ensureBotApiUser(", 1)[1].split(
            "private static JSONObject botApiPost", 1
        )[0]
        self.assertIn('getUser", Long.class)', helper)
        self.assertIn("if (existing != null)", helper)
        self.assertIn("usersList.add(existing)", helper)
        self.assertIn('if (isBot) setBotApiBoolean(existing, "bot", true);', helper)
        self.assertIn('if (isSelf) setBotApiBoolean(existing, "self", true);', helper)
        flag_setter = source.split("private static void setBotApiBoolean(", 1)[1].split(
            "private static JSONObject botApiPost", 1
        )[0]
        self.assertIn('getField(field).setBoolean(user, value)', flag_setter)

    def test_bot_start_state_survives_reopening_the_chat(self):
        source = CHAT.read_text(encoding="utf-8")
        self.assertIn("getBotStartPreferenceKey(dialog_id)", source)
        self.assertIn("getBoolean(getBotStartPreferenceKey(dialog_id), false)", source)
        self.assertIn("putBoolean(getBotStartPreferenceKey(dialog_id), true)", source)
        self.assertRegex(source, r"botUser = null;\s*sentBotStart = true;\s*persistBotStartSent\(\);")

    def test_bot_dialog_refresh_always_reloads_the_local_cache(self):
        source = MESSAGES_CONTROLLER.read_text(encoding="utf-8")
        load = source.split(
            "public void loadDialogs(final int folderId, int offset, int count, boolean fromCache, Runnable onEmptyCallback) {",
            1,
        )[1].split("if (loadingDialogs.get(folderId)", 1)[0]
        bot = load.split(
            "if (getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().bot) {",
            1,
        )[1].split("return;", 1)[0]
        cache_load = "getMessagesStorage().getDialogs(folderId, offset == 0 ? 0 : nextDialogsCacheOffset.get(folderId, 0), count, folderId == 0 && offset == 0);"
        self.assertIn(cache_load, bot)
        self.assertNotIn("if (fromCache)", bot)
        self.assertLess(bot.index(cache_load), bot.index("ColgramBotSync.syncBotDialogs"))

        patcher = (ROOT / "scripts/apply-patches.py").read_text(encoding="utf-8")
        injection = patcher.split("bot_dialogs_inject = ", 1)[1].split("patch_file(", 1)[0]
        self.assertNotIn("if (fromCache)", injection)

    def test_bot_dialog_cache_is_seeded_before_ui_refresh(self):
        self.assertTrue(DIALOG_REFRESH_SEQUENCER.is_file(), "dialog refresh sequencer must exist")
        source = BOT_SYNC.read_text(encoding="utf-8")
        self.assertIn("ColgramDialogRefreshSequencer.seedThenRefresh(", source)
        dispatch = source.split("ColgramDialogRefreshSequencer.seedThenRefresh(", 1)[1].split(
            "// Hand the batch's incoming messages", 1
        )[0]
        self.assertIn("command -> mainHandler.post(command)", dispatch)
        self.assertIn("seedCache", dispatch)
        self.assertIn("loadDialogs.invoke(mc, 0, 0, 100, true, null)", dispatch)
        self.assertNotIn("executor.execute(seedCache)", source)

        harness = r"""import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.colgram.core.ColgramDialogRefreshSequencer;
public final class DialogRefreshSequencerHarness {
    private static final class QueueExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void runNext() { tasks.remove().run(); }
    }
    private static void expect(List<String> actual, String... expected) {
        if (!actual.equals(Arrays.asList(expected))) {
            throw new AssertionError("expected " + Arrays.toString(expected) + " but got " + actual);
        }
    }
    public static void main(String[] args) {
        QueueExecutor worker = new QueueExecutor();
        QueueExecutor main = new QueueExecutor();
        List<String> events = new ArrayList<>();
        ColgramDialogRefreshSequencer.seedThenRefresh(worker, main,
                () -> events.add("seed"), () -> events.add("refresh"));
        expect(events);
        worker.runNext();
        expect(events, "seed");
        if (main.tasks.size() != 1) throw new AssertionError("refresh was not queued on main");
        main.runNext();
        expect(events, "seed", "refresh");

        ColgramDialogRefreshSequencer.seedThenRefresh(worker, main, () -> {
            events.add("seed-failed");
            throw new IllegalStateException("expected test failure");
        }, () -> events.add("refresh-after-failure"));
        try {
            worker.runNext();
            throw new AssertionError("seed exception should propagate to worker");
        } catch (IllegalStateException expected) { }
        main.runNext();
        expect(events, "seed", "refresh", "seed-failed", "refresh-after-failure");
        System.out.println("BOT-DIALOG-REFRESH-SEQUENCE-PASS");
    }
}"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            harness_file = temp / "DialogRefreshSequencerHarness.java"
            harness_file.write_text(harness, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-d", str(temp), str(DIALOG_REFRESH_SEQUENCER), str(harness_file)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", str(temp), "DialogRefreshSequencerHarness"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("BOT-DIALOG-REFRESH-SEQUENCE-PASS", result.stdout)

    def test_network_bypass_is_always_enabled_and_network_config_rows_are_hidden(self):
        config = CONFIG.read_text(encoding="utf-8")
        getter = config.split("public static boolean isDpiBypassEnabled()", 1)[1].split(
            "public static void setDpiBypassEnabled", 1
        )[0]
        self.assertIn("prefs.getBoolean(KEY_DPI_BYPASS_ENABLED", getter)
        self.assertNotIn("return true;", getter)
        settings = SETTINGS.read_text(encoding="utf-8")
        self.assertRegex(
            settings,
            r"networkHeaderRow = dpiBypassRow = dohRow = builtinProxyRow = proxyBrowserRow =\s+"
            r"currentProxyRow = ownProxyRow = proxyStatusRow = relayUrlRow = autoProxyRow =\s+"
            r"ipv6BypassRow = dcRemapRow = networkSectionRow = -1;",
        )
        proxy = PROXY.read_text(encoding="utf-8")
        setter = proxy.split("public static void setDpiBypassEnabled(", 1)[1].split(
            "private static void toast", 1
        )[0]
        self.assertIn("ColgramConfig.setDpiBypassEnabled(enabled);", setter)
        self.assertIn("if (!enabled)", setter)
        self.assertIn("ColgramDpiBypass.stop();", setter)
        generated = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ColgramSettingsActivity.java").read_text(encoding="utf-8")
        self.assertEqual(settings, generated)

    def test_startup_keeps_warp_and_manual_off_ahead_of_stale_proxy_restore(self):
        source = PROXY.read_text(encoding="utf-8")
        startup = source.split("private static void activateBuiltinProxyNow()", 1)[1].split(
            "// 4. Background", 1
        )[0]
        self.assertLess(startup.index("if (ColgramConfig.isWarpEnabled())"), startup.index("forceApplyProxy("))
        self.assertIn("testProxy(saved.address, saved.port", startup)
        self.assertIn("discarding unreachable saved proxy", startup)
        auto_connect = source.split("private static void autoConnectIfBlocked()", 1)[1].split(
            "private static", 1
        )[0]
        self.assertIn("ColgramConfig.isWarpEnabled()", auto_connect)
        relay_apply = source.split("private static void scheduleRelayRouteAutoApply(", 1)[1].split(
            "private static", 1
        )[0]
        self.assertIn("ColgramConfig.isWarpEnabled()", relay_apply)

    def test_every_automatic_route_application_preserves_selected_warp(self):
        source = PROXY.read_text(encoding="utf-8")
        apply_method = source.split("public static void forceApplyProxy(ProxyItem proxy)", 1)[1].split(
            "public static boolean isProxyEnabled", 1
        )[0]
        self.assertIn("if (ColgramConfig.isWarpEnabled())", apply_method)
        self.assertNotIn("ColgramConfig.setWarpEnabled(false)", apply_method)
        self.assertNotIn("ColgramWarpTunnel.bringDown(appContext)", apply_method)
        direct_fallback = source.split("private static void connectThroughBestNode()", 1)[1].split(
            "private static boolean shouldFallbackFromLocalDpi", 1
        )[0]
        self.assertIn("ColgramConfig.isWarpEnabled()", direct_fallback)
        auto_connect = source.split("private static void autoConnectIfBlocked()", 1)[1].split(
            "private static", 1
        )[0]
        self.assertIn("ColgramConfig.isWarpEnabled()", auto_connect)
        self.assertIn("ColgramConfig.isWarpEnabled()", source.split("private static void onVerdict(", 1)[1].split("private static", 1)[0])
        self.assertIn("ColgramConfig.isWarpEnabled()", source.split("private static void probeRelayFallbacks(", 1)[1].split("private static", 1)[0])

    def test_explicit_proxy_and_remap_choices_disable_warp_before_applying(self):
        source = PROXY.read_text(encoding="utf-8")
        toggle = source.split("public static synchronized void toggleProxy(", 1)[1].split(
            "private static boolean findSavedProxy", 1
        )[0]
        self.assertIn("ColgramConfig.setWarpEnabled(false)", toggle)
        self.assertIn("ColgramWarpTunnel.bringDown(ctx)", toggle)
        self.assertLess(toggle.index("ColgramWarpTunnel.bringDown(ctx)"), toggle.index("forceApplyProxy(target)"))
        remap = source.split("public static void applyDcRemapFromUser(", 1)[1].split("/**", 1)[0]
        self.assertIn("ColgramConfig.setWarpEnabled(false)", remap)
        self.assertIn("ColgramWarpTunnel.bringDown(appContext)", remap)

    def test_proxy_disable_clears_stale_saved_endpoint_without_erasing_call_preference(self):
        source = PROXY.read_text(encoding="utf-8")
        disable = source.split("public static void disableProxy(Context context)", 1)[1].split(
            "public static", 1
        )[0]
        self.assertIn('.putBoolean("proxy_enabled", false)', disable)
        self.assertNotIn('"proxy_enabled_calls"', disable)
        self.assertIn("ColgramConfig.isWarpEnabled()", source.split("private static void autoConnectIfBlocked()", 1)[1].split("private static", 1)[0])

    def test_warp_row_uses_selected_state_and_refreshes_after_async_result(self):
        source = PROXY_LIST_ACTIVITY.read_text(encoding="utf-8")
        voip = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java").read_text(encoding="utf-8")
        self.assertIn("setTextAndValueAndCheck(warpLabel, warpState, org.colgram.core.ColgramConfig.isWarpEnabled()", source)
        self.assertIn("org.colgram.core.ColgramProxyManager.notifyProxySettingsChanged();", source)
        self.assertIn("else if (requestCode == REQ_WARP_CONSENT)", source)
        self.assertIn('useProxyForCalls = preferences.getBoolean("proxy_enabled_calls", true);', source)
        self.assertIn('useProxyForCalls = prefs.getBoolean("proxy_enabled_calls", true);', source)
        self.assertIn('preferences.getBoolean("proxy_enabled_calls", true)', voip)
        warp_click = source.split("} else if (position == warpRow)", 1)[1].split(
            "} else if (position >= proxyStartRow", 1
        )[0]
        self.assertNotIn('editor.putBoolean("proxy_enabled_calls", false);', warp_click)
        self.assertNotIn("useProxyForCalls = false;", warp_click)

    def test_proxy_for_calls_default_is_not_erased_by_proxy_selection_rotation_or_removal(self):
        activity = PROXY_LIST_ACTIVITY.read_text(encoding="utf-8")
        rotation = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/messenger/ProxyRotationController.java").read_text(encoding="utf-8")
        shared = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java").read_text(encoding="utf-8")
        launch = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java").read_text(encoding="utf-8")
        patcher = (ROOT / "scripts/patch_proxylist_warp.py").read_text(encoding="utf-8")
        self.assertIn('preferences.getBoolean("proxy_enabled_calls", true)', activity)
        self.assertNotIn("useProxyForCalls = false;", activity)
        self.assertNotIn('putBoolean("proxy_enabled_calls", false)', activity + rotation + shared + launch)
        self.assertIn("apply_call_proxy_defaults", patcher)
        selected_proxy = activity.split("} else if (position >= proxyStartRow", 1)[1].split("} else if (position == proxyAddRow", 1)[0]
        self.assertNotIn("setWarpEnabled(false);\n                org.colgram.core.ColgramConfig.setWarpEnabled(false);", selected_proxy)
        self.assertNotRegex(selected_proxy, r'if \(!info\.settings\.getSecret\(\)\.isEmpty\(\)\) \{\s*\}')

    def test_proxy_discovery_startup_has_bounded_network_and_socket_work(self):
        source = PROXY.read_text(encoding="utf-8")
        self.assertIn("Executors.newFixedThreadPool(4)", source.split("sourceFetchers =", 1)[1].split(";", 1)[0])
        self.assertRegex(source, r"sweeper = Executors\.newFixedThreadPool\((8|12)\)")
        fetch = source.split("private static void fetchSourceFeedsConcurrently()", 1)[1].split("private static void runSourceFetchTasks", 1)[0]
        self.assertIn("MAX_STARTUP_MTPROTO_FEEDS", fetch)
        self.assertIn("MAX_STARTUP_JSON_FEEDS", fetch)
        self.assertIn("MAX_STARTUP_SOCKS_FEEDS", fetch)
        relays = source.split("private static void harvestRelays()", 1)[1].split("private static void collectRelayFeed", 1)[0]
        self.assertIn("MAX_STARTUP_RELAY_FEEDS", relays)
        self.assertRegex(source, r"MAX_MTPROTO_CANDIDATES = (1[0-9]{2}|[2-3][0-9]{2});")
        self.assertRegex(source, r"MAX_SOCKS_CANDIDATES = (1[0-9]{2}|[2-3][0-9]{2});")
        self.assertIn("MAX_RELAYS = 16", source)

    def test_cloudflare_reserved_client_id_reaches_wireguard_before_mac1(self):
        go_backend = (ROOT / "vendor/colgram-wireguard/src/main/java/com/wireguard/android/backend/GoBackend.java").read_text(encoding="utf-8")
        go_device = (ROOT / "vendor/colgram-wireguard/wireguard-go/device/warp_reserved.go").read_text(encoding="utf-8")
        go_uapi = (ROOT / "vendor/colgram-wireguard/wireguard-go/device/uapi.go").read_text(encoding="utf-8")
        tunnel = WARP_TUNNEL.read_text(encoding="utf-8")
        self.assertIn('setClientReserved', go_backend)
        self.assertIn('"reserved=" + reserved.toLowerCase(java.util.Locale.ROOT) + "\\n"', go_backend)
        self.assertIn('case "reserved":', go_uapi)
        self.assertIn('composeMessageType(messageType, reserved)', go_device)
        noise = (ROOT / "vendor/colgram-wireguard/wireguard-go/device/noise-protocol.go").read_text(encoding="utf-8")
        self.assertIn('device.messageType(MessageInitiationType)', noise)
        self.assertIn('msg.Type = device.messageType(MessageResponseType)', noise)
        sender = (ROOT / "vendor/colgram-wireguard/wireguard-go/device/send.go").read_text(encoding="utf-8")
        self.assertIn('device.writeMessageType(fieldType, MessageTransportType)', sender)
        self.assertIn('reply.Type = device.messageType(MessageCookieReplyType)', sender)
        self.assertLess(sender.index("peer.cookieGenerator.AddMacs(packet)"), sender.index("peer.SendBuffer(packet)"))
        active_wireguard = ROOT / "vendor/colgram-wireguard/wireguard-go/device"
        active_noise = (active_wireguard / "noise-protocol.go").read_text(encoding="utf-8")
        active_sender = (active_wireguard / "send.go").read_text(encoding="utf-8")
        self.assertIn('msg.Type = device.messageType(MessageResponseType)', active_noise)
        self.assertIn('reply.Type = device.messageType(MessageCookieReplyType)', active_sender)
        self.assertIn('setClientReserved', tunnel)
        self.assertIn("clientReservedUapiLine + config.toWgUserspaceString()", go_backend)

    def test_stalled_warp_has_a_finite_retry_budget_and_clears_selected_route(self):
        tunnel = WARP_TUNNEL.read_text(encoding="utf-8")
        manager = PROXY.read_text(encoding="utf-8")
        self.assertIn("private static final int STALE_AFTER_SECS = 8;", tunnel)
        self.assertIn("int endpointCount = ColgramWarp.endpointPortCount();", tunnel)
        self.assertIn("while (up && failedEndpoints < endpointCount)", tunnel)
        self.assertIn("if (failedEndpoints >= endpointCount) break;", tunnel)
        self.assertIn("if (up && failedEndpoints >= endpointCount)", tunnel)
        self.assertIn("distinct endpoint attempts", tunnel)
        self.assertIn('if (conf == null) {', tunnel)
        self.assertIn('disableStalledTunnel(ctx, "endpoint watchdog failed")', tunnel)
        self.assertIn("private static void disableStalledTunnel(Context ctx, String reason)", tunnel)
        self.assertIn("ColgramConfig.setWarpEnabled(false);", tunnel)
        self.assertIn("ColgramProxyManager.notifyProxySettingsChanged();", tunnel)
        self.assertIn("ColgramWarpTunnel.bringDown(ctx)", manager)
        force_apply = manager.split("public static void forceApplyProxy(ProxyItem proxy)", 1)[1].split(
            "public static boolean isProxyEnabled", 1
        )[0]
        self.assertIn("ignoring background proxy application", force_apply)

    def test_device_test_runner_reads_the_device_not_the_broken_utp_stream(self):
        """gradle fails these runs even when every test passed, so the build is not the evidence.

        On two consecutive runs the emulator reported `OK (3 tests)` with every per-test
        INSTRUMENTATION_STATUS_CODE at 0, and gradle still failed with "Failed to receive the UTP
        test results". A runner that trusted the exit code would report a failure that never
        happened, and a green/red signal nobody can trust is worse than none.
        """
        runner = (ROOT / "scripts/device-tests.py").read_text(encoding="utf-8")
        self.assertIn("ColgramProxyAutonomyDeviceTest", runner)
        self.assertIn("ColgramGlobalSearchHistoryDeviceTest", runner)
        self.assertIn("ColgramGlobalSearchRestoreDeviceTest", runner)
        self.assertIn("ColgramThemeContrastDeviceTest", runner)
        self.assertIn("ColgramWarpUdpReachabilityDeviceTest", runner)
        self.assertIn("test-results.log", runner)
        self.assertIn("Failed to receive the UTP test results", runner)
        # It must clear stale results first, or a previous run reads as this one's outcome.
        self.assertIn("a stale log cannot be read as this run", runner)
        self.assertIn('item.unlink()', runner)
        # And it must decide on per-test status, not on gradle's exit code.
        self.assertIn("INSTRUMENTATION_STATUS_CODE", runner)
        self.assertNotIn("return proc.returncode", runner)

    def test_device_tests_are_mirrored_so_they_survive_a_fresh_checkout(self):
        """Telegram-Src/ is gitignored, so the device tests need a tracked copy.

        The on-device tests found two real defects that the source-level suite could not: a
        NullPointerException in the night palette, and a history entry that changed its own
        spelling when re-searched. If they only lived in the ignored tree they would be gone on
        the next clone, and the tests that found real bugs would be the first thing lost.
        """
        installed = ROOT / "Telegram-Src/TMessagesProj_AppTests/src/androidTest/java/org/colgram/core"
        for name in ("ColgramThemeContrastDeviceTest.java",
                     "ColgramGlobalSearchHistoryDeviceTest.java",
                     "ColgramGlobalSearchRestoreDeviceTest.java",
                     "ColgramProxyAutonomyDeviceTest.java",
                     "ColgramWarpUdpReachabilityDeviceTest.java"):
            template = (ROOT / "scripts/templates" / name).read_text(encoding="utf-8")
            self.assertTrue((installed / name).exists(), name + " is not installed into the test tree")
            self.assertEqual(template, (installed / name).read_text(encoding="utf-8"),
                             name + " has drifted from its tracked template")
            self.assertIn("@RunWith(AndroidJUnit4.class)", template)

    def test_search_history_keeps_the_spelling_it_was_first_saved_with(self):
        """Found by the new on-device history test: expected:<[cats]> but was:<[CATS]>.

        Re-running an old search removed its entry case-insensitively and then re-added whatever
        the user had just typed, so a query saved as "Colgram" came back as "COLGRAM" after one
        more search - the list silently changing under the user who is trying to re-run it.
        """
        pager = SEARCH_PAGER.read_text(encoding="utf-8")
        save = pager.split("private void saveGlobalSearchHistory(String text)", 1)[1].split(
            "private void removeGlobalSearchHistory", 1
        )[0]
        self.assertIn("String canonical = query;", save)
        self.assertIn("canonical = history.get(i);", save)
        self.assertIn("history.add(0, canonical);", save)
        self.assertNotIn("history.add(0, query);", save)
        # And the device test that found it stays in the tree.
        history_test = (ROOT / "Telegram-Src/TMessagesProj_AppTests/src/androidTest/java/org/colgram/core/ColgramGlobalSearchHistoryDeviceTest.java").read_text(encoding="utf-8")
        self.assertIn("historyIsCappedAndOldestEntriesFallOff", history_test)
        self.assertIn("two\nlines", history_test.replace("\\n", "\n"))

    def test_night_palette_lookup_cannot_throw_while_the_loader_runs(self):
        """getColor is called from draw passes, so it must not raise.

        Found by running the new on-device contrast test: it crashed with
        NullPointerException: Attempt to read from null array in colgramNightColor. The has-key
        check read the volatile field safely, but the colour read a few lines later indexed the
        raw field again, and the background loader had not finished assigning it yet. This is one
        of the confirmed causes behind "the app often crashes".
        """
        theme = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java").read_text(encoding="utf-8")
        injector = PATCHER.read_text(encoding="utf-8")
        accessor = theme.split("private static int colgramNightColor(int key)", 1)[1].split("\n    }", 1)[0]
        self.assertNotIn("return colgramNightColors[key];", accessor)
        self.assertIn("int[] night = colgramNightColors;", accessor)
        self.assertIn("if (night == null || key < 0 || key >= night.length)", accessor)
        # The injector owns this body, so it has to carry the same fix or the next regenerate
        # would put the crashing version straight back.
        self.assertNotIn('"        return colgramNightColors[key];\\n"', injector)
        self.assertIn("int[] night = colgramNightColors;", injector)
        # And the device test that caught it must stay in the tree.
        contrast = (ROOT / "Telegram-Src/TMessagesProj_AppTests/src/androidTest/java/org/colgram/core/ColgramThemeContrastDeviceTest.java").read_text(encoding="utf-8")
        self.assertIn("MIN_CONTRAST = 60", contrast)
        self.assertIn("everyDarkThemeKeepsTextOffItsOwnBackground", contrast)

    def test_injector_can_never_truncate_a_source_file(self):
        """A bad replacer return must not become an empty file on disk.

        During this fix the self-heal kept the FIRST init() call and dropped the rest, which on a
        fresh file removed the anchor line the whole patch hangs off. patch_file then wrote the
        empty result and the next run only complained about a missing anchor - a 25KB source file
        silently reduced to 0 bytes. The guard makes that impossible, whatever a replacer returns.
        """
        injector = PATCHER.read_text(encoding="utf-8")
        self.assertIn("CONVERGED = object()", injector)
        self.assertIn("if new_content is CONVERGED:", injector)
        self.assertIn("if not isinstance(new_content, str) or len(new_content) < max(64, len(content) // 2):", injector)
        self.assertIn("Refusing to write implausible result for", injector)
        # The self-heal must keep the first call, which is the one holding the patch anchor.
        self.assertIn("keep_at = content.index(rotation_call)", injector)
        self.assertIn("head = content[:keep_at + len(rotation_call)]", injector)
        # And it has to run on the fresh-file path too, not only when already patched.
        fresh = injector.split('if "ColgramUiBridge.install(this)" not in content:', 1)[1].split("        if not fresh:", 1)[0]
        self.assertIn("content = content.replace(anchor, piece_init + piece_ipv4 + piece_service, 1)", fresh)
        self.assertIn("fresh = True", fresh)

    def test_startup_registers_the_rotation_observer_exactly_once(self):
        """A second init() call is not free: it registers every observer twice.

        ProxyRotationController.initInternal() adds itself to didUpdateConnectionState for
        every account plus proxyCheckDone and proxySettingsChanged. Running it twice means every
        proxy notification is handled twice, and the file really did carry a second call at the
        end of onCreate. The patcher now removes the extra call site instead of adding one.
        """
        loader = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java").read_text(encoding="utf-8")
        rotation = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/messenger/ProxyRotationController.java").read_text(encoding="utf-8")
        injector = PATCHER.read_text(encoding="utf-8")
        self.assertEqual(1, loader.count("ProxyRotationController.init();"))
        internal = rotation.split("private void initInternal()", 1)[1].split("@Override", 1)[0]
        self.assertIn("addObserver(this", internal)
        self.assertIn("Removed duplicate ProxyRotationController.init()", injector)
        self.assertIn('if content.count(rotation_call) > 1:', injector)

    def test_dead_warp_route_is_named_instead_of_leaving_a_lying_active_toggle(self):
        """A route that cannot carry traffic must say why, not sit on "подключается".

        Measured on the emulator 2026-09-27: with every Cloudflare UDP ingress filtered, the
        WireGuard handshake still left the device (tx=444B) and rx never moved. The old code
        reported only "подключается" for the full 4x15s rotation, then switched itself off
        silently - the exact "варп не работает, беск соединение" complaint.
        """
        tunnel = WARP_TUNNEL.read_text(encoding="utf-8")
        settings = SETTINGS.read_text(encoding="utf-8")
        activity = PROXY_LIST_ACTIVITY.read_text(encoding="utf-8")

        # A verdict exists, is cleared per attempt, and survives to the UI.
        self.assertIn("private static volatile String lastFailure;", tunnel)
        self.assertIn("public static String lastFailureReason()", tunnel)
        self.assertIn("public static void clearFailure()", tunnel)
        self.assertIn("// A verdict from a previous attempt must never be shown against a fresh one.\n        lastFailure = null;", tunnel)
        self.assertIn("lastFailure = reason;", tunnel)

        # Rotating ports cannot help a route whose datagrams never left, so say so and stop.
        self.assertIn("MIN_HANDSHAKE_TX_BYTES = 64L;", tunnel)
        self.assertIn("if (tx < MIN_HANDSHAKE_TX_BYTES) {", tunnel)
        self.assertIn("no UDP egress: handshake never left the device", tunnel)
        self.assertIn("not rotating ports", tunnel)
        self.assertIn("failedEndpoints = endpointCount;", tunnel)

        # Both surfaces report the reason rather than reverting to a bare "off".
        self.assertIn("ColgramWarpTunnel.lastFailureReason()", settings)
        self.assertIn('return "не работает: " + failure;', settings)
        self.assertIn("private void watchWarpVerdict(final Context context)", settings)
        self.assertIn("ColgramWarpTunnel.lastFailureReason()", activity)
        self.assertIn('"не работает: " + failure', activity)
        self.assertIn('"WARP не работает: " + failure', activity)
        self.assertIn("private void watchWarpVerdict(final android.content.Context context)", activity)
        self.assertIn("failWarpStart(context, \"WARP не работает: \" + failure);", activity)

        # The 8s window still spans wireguard-go's 5s RekeyTimeout, so it cannot give up early.
        timers = (ROOT / "vendor/colgram-wireguard/wireguard-go/device/constants.go").read_text(encoding="utf-8")
        self.assertRegex(timers, r"RekeyTimeout\s+= time\.Second \* 5")
        self.assertRegex(tunnel, r"STALE_AFTER_SECS = 8;")

    def test_warp_device_consent_test_yields_foreground_and_restores_it(self):
        source = (ROOT / "scripts/templates/ColgramWarpDeviceIntegrationTest.java").read_text(encoding="utf-8")
        self.assertIn("String previousPackage = device.getCurrentPackageName();", source)
        self.assertIn("device.pressHome();", source)
        self.assertIn('"ОК"', source)
        self.assertIn('By.res("android", "button1")', source)
        self.assertIn("restorePreviousForeground(device, context, previousPackage,", source)
        self.assertIn("VpnService.prepare(context) != null", source)
        self.assertIn("invoke(tunnelClass, \"bringDown\"", source)
        self.assertIn("invoke(configClass, \"setWarpEnabled\"", source)
        self.assertIn("FLAG_ACTIVITY_RESET_TASK_IF_NEEDED", source)
        self.assertIn("X25519 public key differs from RFC 7748 test vector", source)
        self.assertIn("WARP fail-closed after all advertised UDP endpoints stayed silent", source)

    def test_wireguard_backend_is_reproducibly_sourced_from_vendor_module(self):
        vendor = ROOT / "vendor/colgram-wireguard"
        patcher = PATCHER.read_text(encoding="utf-8")
        self.assertTrue((vendor / "src/main/java/com/wireguard/android/backend/GoBackend.java").is_file())
        self.assertTrue((vendor / "src/main/jniLibs/arm64-v8a/libwg-go.so").is_file())
        self.assertIn('os.path.join(root_dir, "vendor", "colgram-wireguard")', patcher)
        self.assertIn("sync_wireguard_module(", patcher)
        self.assertIn('os.path.join(root_dir, "vendor", "colgram-wireguard")', patcher)
        self.assertIn("include ':colgram-wireguard'", patcher)
        self.assertIn("com\\.wireguard\\.android:tunnel:", patcher)
        self.assertIn("implementation project(':colgram-wireguard')", patcher)

    def test_warp_watchdog_reads_embedded_wireguard_statistics_api(self):
        tunnel = WARP_TUNNEL.read_text(encoding="utf-8")
        stats = WARP_STATS.read_text(encoding="utf-8")
        self.assertIn("ColgramWarpStatistics.statistics(be, t)", tunnel)
        self.assertIn('"getStatistics"', stats)
        self.assertIn("method.getParameterTypes()[0].isInstance(tunnel)", stats)
        self.assertIn('"totalRx"', stats)
        self.assertIn('"totalTx"', stats)

        harness = r"""package org.colgram.core;
public final class WarpStatisticsHarness {
    public static final class CurrentApi {
        public long totalRx() { return 123456L; }
        public long totalTx() { return 98765L; }
    }
    public static final class LegacyApi {
        public long getTotalRx() { return 4321L; }
        public long getTotalTx() { return 8765L; }
    }
    public static final class MissingApi { }
    public static final class FakeTunnel { }
    public static final class FakeBackend {
        public CurrentApi getStatistics(FakeTunnel tunnel) {
            if (tunnel == null) throw new AssertionError("tunnel argument missing");
            return new CurrentApi();
        }
    }
    public static void main(String[] args) {
        Object current = ColgramWarpStatistics.statistics(new FakeBackend(), new FakeTunnel());
        if (current == null || ColgramWarpStatistics.totalRx(current) != 123456L ||
                ColgramWarpStatistics.totalTx(current) != 98765L)
            throw new AssertionError("backend getStatistics(Tunnel) was not invoked");
        if (ColgramWarpStatistics.statistics(new Object(), new FakeTunnel()) != null)
            throw new AssertionError("missing backend statistics should return null");
        if (ColgramWarpStatistics.totalRx(new CurrentApi()) != 123456L ||
                ColgramWarpStatistics.totalTx(new CurrentApi()) != 98765L)
            throw new AssertionError("current WireGuard API counters were not read");
        if (ColgramWarpStatistics.totalRx(new LegacyApi()) != 4321L ||
                ColgramWarpStatistics.totalTx(new LegacyApi()) != 8765L)
            throw new AssertionError("legacy WireGuard API counters were not read");
        if (ColgramWarpStatistics.totalRx(new MissingApi()) != 0L ||
                ColgramWarpStatistics.totalTx(new MissingApi()) != 0L)
            throw new AssertionError("missing counter API should degrade to zero");
        System.out.println("WARP-STATISTICS-API-PASS");
    }
}"""
        with tempfile.TemporaryDirectory() as temp:
            harness_file = Path(temp) / "WarpStatisticsHarness.java"
            harness_file.write_text(harness, encoding="utf-8")
            javac = os.environ.get("COLGRAM_JAVAC", "javac")
            java = os.environ.get("COLGRAM_JAVA", "java")
            subprocess.run(
                [javac, "-d", temp, str(WARP_STATS), str(harness_file)],
                check=True, capture_output=True, text=True
            )
            result = subprocess.run(
                [java, "-cp", temp, "org.colgram.core.WarpStatisticsHarness"],
                check=True, capture_output=True, text=True
            )
            self.assertIn("WARP-STATISTICS-API-PASS", result.stdout)

    def test_dark_theme_guard_covers_global_grey_text_families_in_generated_and_injector(self):
        theme = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java").read_text(encoding="utf-8")
        injector = PATCHER.read_text(encoding="utf-8")
        self.assertIn("COLGRAM_THEME_PATCH = 14", theme)
        self.assertIn('COLGRAM_THEME_PATCH_VERSION = "14"', injector)
        self.assertIn("colgramReadableGrayTextKey(key)", theme)
        self.assertIn("colgramReadableGrayTextKey(key)", injector)
        self.assertIn("colgramReadableSelectedTextKey(key)", theme)
        self.assertIn("colgramReadableSelectedTextKey(key)", injector)
        self.assertIn("key_profile_tabSelectedText", theme)
        self.assertIn("key_profile_tabSelectedText", injector)
        self.assertIn("colgramHasDarkSurface()", theme)
        self.assertIn("colgramHasDarkSurface()", injector)
        self.assertIn("colgramLuma(colgramGetColorInternal(key_windowBackgroundWhite, null, true)) < 128", theme)
        self.assertIn('"        return colgramLuma(colgramGetColorInternal(key_windowBackgroundWhite, null, true)) < 128;\\n"', injector)
        self.assertIn("Math.min(worst, colgramContrast(color, colgramGetColorInternal(key_dialogBackground", theme)
        self.assertIn('"        worst = Math.min(worst, colgramContrast(color, colgramGetColorInternal(key_dialogBackground', injector)
        for key in (
            "key_windowBackgroundWhiteGrayText",
            "key_windowBackgroundWhiteGrayText8",
            "key_dialogTextGray",
            "key_dialogTextGray4",
            "key_graySectionText",
            "key_profile_tabText",
            "key_actionBarDefaultSubtitle",
        ):
            self.assertIn("key == " + key, theme)
            self.assertIn("key == " + key, injector)

    def test_warp_ui_upgrade_replaces_old_patch_version_without_duplicate_fields(self):
        patcher = runpy.run_path(str(ROOT / "scripts/patch_proxylist_warp.py"))
        old = "    private static final int REQ_WARP_CONSENT = 9182;\n" \
              "    private static final int COLGRAM_WARP_UI_PATCH = 2;\n"
        upgraded = patcher["upgrade_generated"](old)
        self.assertEqual(upgraded.count("COLGRAM_WARP_UI_PATCH = 4"), 1)
        self.assertNotIn("COLGRAM_WARP_UI_PATCH = 2", upgraded)

    def test_global_search_history_counts_more_results_and_preserves_parent_query(self):
        pager = SEARCH_PAGER.read_text(encoding="utf-8")
        helper = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/Adapters/SearchAdapterHelper.java").read_text(encoding="utf-8")
        channels = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/Components/DialogsChannelsAdapter.java").read_text(encoding="utf-8")
        bots = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/Components/DialogsBotsAdapter.java").read_text(encoding="utf-8")
        dialogs = DIALOGS_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("global_search_history", pager)
        self.assertIn("removeGlobalSearchHistory", pager)
        self.assertIn("formatPluralStringSpaced(\"Subscribers\", chat.participants_count)", pager)
        self.assertIn("GLOBAL_SEARCH_PREVIEW_COUNT = 10", SEARCH_ADAPTER.read_text(encoding="utf-8"))
        self.assertIn("req.limit = 50;", helper)
        self.assertIn("req2.limit = 50;", channels)
        self.assertIn("req2.limit = 50;", bots)
        self.assertIn("openGlobalSearchResult", pager)
        self.assertIn("public void openGlobalSearchResult(long did)", dialogs)
        open_search_delegate = dialogs.split("public void openGlobalSearchResult(long did)", 1)[1].split("public void scrollToFolder", 1)[0]
        self.assertNotIn("closeSearch();", open_search_delegate)

    def test_cloudflare_warp_shortcut_opens_official_android_app(self):
        # The WARP row is a built-in tunnel now: Colgram registers its own device and drives
        # the embedded WireGuard backend. The official-app shortcut became the fallback only
        # when the backend artifact is missing from the build.
        settings = SETTINGS.read_text(encoding="utf-8")
        self.assertIn("cloudflareWarpRow = rowCount++;", settings)
        self.assertIn("position == cloudflareWarpRow", settings)
        self.assertIn("ColgramWarp.isRegistered()", settings)
        self.assertIn("ColgramWarpTunnel.isBackendAvailable()", settings)
        self.assertIn("ColgramWarpTunnel.bringUp(", settings)
        self.assertIn("ColgramWarpTunnel.bringDown(", settings)
        self.assertIn("android.net.VpnService.prepare(", settings)
        self.assertIn("REQ_WARP_VPN", settings)
        core = (ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramWarp.java").read_text(encoding="utf-8")
        self.assertIn("api.cloudflareclient.com/v0a2158/reg", core)
        self.assertIn("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", core)
        self.assertIn("java.util.LinkedHashSet<Integer> unique", core)
        self.assertIn("public static int endpointPortCount()", core)

    def test_public_proxy_sources_are_harvested_at_startup_and_refresh(self):
        source = PROXY.read_text(encoding="utf-8")
        for url in (
            "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
            "https://raw.githubusercontent.com/ALIILAPRO/MTProtoProxy/main/mtproto.txt",
            "https://zakky8.github.io/mtproto-proxy-pro/censorship_resistant.txt",
            "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt",
        ):
            self.assertIn(url, source)
        sources = source.split("private static final String[] PROXY_SOURCES_MTPROTO_LINKS", 1)[1].split(
            "private static final String[] PROXY_SOURCES_SOCKS", 1
        )[0]
        self.assertLess(
            sources.index("https://zakky8.github.io/mtproto-proxy-pro/censorship_resistant.txt"),
            sources.index("https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt"),
        )
        harvest = source.split("private static void fetchAndVerifyAllSources()", 1)[1].split(
            "private static void fetchSourceFeedsConcurrently()", 1
        )[0]
        self.assertIn("fetchSourceFeedsConcurrently();", harvest)
        self.assertIn("harvestRelays();", harvest)
        self.assertIn("startProber();", harvest)
        self.assertIn("publishPoolToStock();", harvest)
        self.assertIn("sweepFast(null);", harvest)
        concurrent = source.split("private static void fetchSourceFeedsConcurrently()", 1)[1].split(
            "private static void runSourceFetchTasks", 1
        )[0]
        self.assertIn("fetchProxiesLinkList(url, 24)", concurrent)
        self.assertIn("fetchProxiesJson(url, 30)", concurrent)
        self.assertIn("fetchSocksList(url, 24)", concurrent)

    def test_proxy_harvest_uses_expanded_feeds_and_parallel_fetching(self):
        source = PROXY.read_text(encoding="utf-8")
        expected_feeds = (
            "https://raw.githubusercontent.com/tgmtproxy/telegram-mtproto-proxy-list/main/proxies.txt",
            "https://zakky8.github.io/mtproto-proxy-pro/all_proxies.txt",
            "https://raw.githubusercontent.com/dubblebyte/free-mtproto-proxies/main/all_proxies.txt",
            "https://zakky8.github.io/mtproto-proxy-pro/proxies.json",
            "https://raw.githubusercontent.com/r00tee/Proxy-List/main/Socks5.txt",
            "https://raw.githubusercontent.com/mzyui/proxy-list/main/socks5.txt",
            "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
            "https://raw.githubusercontent.com/proxmint/free-proxy-list/main/proxies/socks5.txt",
            "https://raw.githubusercontent.com/prxchk/proxy-list/main/socks5.txt",
            "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/socks5.txt",
            "https://raw.githubusercontent.com/proxmint/free-proxy-list/main/proxies/http.txt",
            "https://raw.githubusercontent.com/r00tee/Proxy-List/main/Https.txt",
            "https://raw.githubusercontent.com/hproxy-com/free-proxy-list/main/socks5.txt",
            "https://raw.githubusercontent.com/databay-labs/free-proxy-list/master/socks5.txt",
            "https://raw.githubusercontent.com/neobxod/mtproto-for-telegram/master/all_proxies.txt",
        )
        for feed in expected_feeds:
            self.assertIn(feed, source)
        mtproto_links = source.split("private static final String[] PROXY_SOURCES_MTPROTO_LINKS", 1)[1].split(
            "private static final String[] PROXY_SOURCES_MTPROTO_JSON", 1
        )[0]
        socks = source.split("private static final String[] PROXY_SOURCES_SOCKS", 1)[1].split(
            "private static final String[] RELAY_SOURCES_HTTP", 1
        )[0]
        http_relays = source.split("private static final String[] RELAY_SOURCES_HTTP", 1)[1].split(
            "private static final String[] RELAY_SOURCES_SOCKS", 1
        )[0]
        socks_relays = source.split("private static final String[] RELAY_SOURCES_SOCKS", 1)[1].split(
            "private static final int MAX_RELAYS", 1
        )[0]
        self.assertGreaterEqual(mtproto_links.count("https://"), 6)
        self.assertGreaterEqual(socks.count("https://"), 8)
        self.assertGreaterEqual(http_relays.count("https://"), 4)
        self.assertGreaterEqual(socks_relays.count("https://"), 4)
        harvest = source.split("private static void fetchAndVerifyAllSources()", 1)[1].split(
            "private static void harvestRelays()", 1
        )[0]
        self.assertIn("fetchSourceFeedsConcurrently()", harvest)
        self.assertIn("CountDownLatch", source)

    def test_mtproto_json_feeds_accept_both_array_and_proxies_object_envelopes(self):
        source = PROXY.read_text(encoding="utf-8")
        parser = source.split("private static void fetchProxiesJson(", 1)[1].split(
            "public static int probeTcp(", 1
        )[0]
        self.assertIn("new JSONArray(body)", parser)
        self.assertIn('optJSONArray("proxies")', parser)

    def test_bot_account_theme_page_uses_any_active_non_bot_account_for_remote_themes(self):
        source = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ThemeActivity.java").read_text(encoding="utf-8")
        self.assertIn("private int resolveThemeDataAccount()", source)
        helper = source.split("private int resolveThemeDataAccount()", 1)[1].split("@Override", 1)[0]
        self.assertIn("UserConfig.MAX_ACCOUNT_COUNT", helper)
        self.assertIn("!themeUser.bot", helper)
        create = source.split("public boolean onFragmentCreate()", 1)[1].split("public void onFragmentDestroy()", 1)[0]
        self.assertIn("resolveThemeDataAccount()", create)

    def test_bot_keyboard_text_contrast_is_checked_against_its_actual_button_surface(self):
        source = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/bots/BotKeyboardView.java").read_text(encoding="utf-8")
        button = source.split("private class Button extends FrameLayout", 1)[1]
        colors = button.split("public void updateColors()", 1)[1].split("\n        }", 1)[0]
        self.assertIn("ColorUtils.calculateContrast", colors)
        self.assertIn("ColorUtils.compositeColors", colors)
        self.assertIn("Color.WHITE", colors)
        self.assertIn("Color.BLACK", colors)

    def test_hint_view_corrects_custom_theme_text_contrast_for_white_bubbles(self):
        source = HINT_VIEW.read_text(encoding="utf-8")
        self.assertIn("resolveHintTextColor(", source)
        self.assertIn("ColorUtils.calculateContrast", source)
        constructor = source.split("textView = new CorrectlyMeasuringTextView(context);", 1)[1].split("if (currentType == TYPE_SEARCH_AS_LIST)", 1)[0]
        self.assertIn("getThemedColor(Theme.key_chat_gifSaveHintBackground)", constructor)
        self.assertIn("resolveHintTextColor", constructor)
        drawing = source.split("protected void dispatchDraw(Canvas canvas)", 1)[1].split("@Override", 1)[0]
        self.assertIn("updateHintTextColor()", drawing)
        self.assertIn("customHintBackground", source)

    def test_proxy_sources_include_independent_telegram_and_socks_sources(self):
        source = PROXY.read_text(encoding="utf-8")
        mtproto_sources = source.split("PROXY_SOURCES_MTPROTO_LINKS = {", 1)[1].split("};", 1)[0]
        socks_sources = source.split("PROXY_SOURCES_SOCKS = {", 1)[1].split("};", 1)[0]
        relay_http_sources = source.split("RELAY_SOURCES_HTTP = {", 1)[1].split("};", 1)[0]
        relay_socks_sources = source.split("RELAY_SOURCES_SOCKS = {", 1)[1].split("};", 1)[0]
        self.assertIn("neobxod/mtproto-for-telegram/master/all_proxies.txt", mtproto_sources)
        self.assertIn("hproxy-com/free-proxy-list/main/socks5.txt", socks_sources)
        self.assertIn("databay-labs/free-proxy-list/master/socks5.txt", socks_sources)
        self.assertIn("hproxy-com/free-proxy-list/main/http.txt", relay_http_sources)
        self.assertIn("databay-labs/free-proxy-list/master/http.txt", relay_http_sources)
        self.assertIn("hproxy-com/free-proxy-list/main/socks5.txt", relay_socks_sources)
        self.assertIn("databay-labs/free-proxy-list/master/socks5.txt", relay_socks_sources)

    def test_deleted_message_marker_fades_live_and_resets_when_recycled(self):
        source = CHAT_CELL.read_text(encoding="utf-8")
        binder = source.split("private void setMessageContent(", 1)[1].split("if (messageObject.checkLayout()", 1)[0]
        self.assertIn("deleteMarkerChanged", binder)
        self.assertIn("animate().alpha(0.62f).setDuration(260).start()", binder)
        self.assertIn("setAlpha(antiDeleteMarked ? 0.62f : 1f)", binder)
        patcher = PATCHER.read_text(encoding="utf-8")
        self.assertIn("ChatMessageCell Anti-Delete Fade State", patcher)
        self.assertIn("ChatMessageCell Anti-Delete Fade", patcher)
        self.assertIn("colgram_deleted_message_14.xml", patcher)

    def test_proxy_source_fetches_use_a_bounded_fast_relay_fallback(self):
        manager = PROXY.read_text(encoding="utf-8")
        fetch = manager.split("private static String httpGet(", 1)[1].split("/** t.me/proxy?", 1)[0]
        self.assertIn("mirrorUrls(sourceUrl)", fetch)
        self.assertIn("ColgramHttp.getFast(candidate)", fetch)
        http = HTTP.read_text(encoding="utf-8")
        fast = http.split("public static Response getFast(", 1)[1].split("public static Response post(", 1)[0]
        self.assertIn("FAST_CONNECT_TIMEOUT_MS", fast)
        self.assertIn("FAST_READ_TIMEOUT_MS", fast)
        self.assertIn("MAX_FAST_RELAY_ATTEMPTS", fast)

    def test_deleted_message_marker_uses_a_tinted_vector_glyph_not_text_or_emoji(self):
        source = CHAT_CELL.read_text(encoding="utf-8")
        marker = source.split("String deletedLabel = getString(R.string.ColgramDeletedMessage);", 1)[1].split("currentTimeString = deletedTime;", 1)[0]
        self.assertIn("ColoredImageSpan", marker)
        self.assertIn("R.drawable.colgram_deleted_message_14", marker)
        self.assertIn("Theme.key_text_RedRegular", marker)
        self.assertNotIn("🗑", marker)

    def test_bot_dialogs_do_not_append_a_paging_skeleton_to_the_terminal_list(self):
        adapter = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/Adapters/DialogsAdapter.java").read_text(encoding="utf-8")
        self.assertIn("private boolean isBotAccount()", adapter)
        flicker_gate = adapter.split("if (communityId == 0 && !forceShowEmptyCell", 1)[1].split("itemInternals.add(new ItemInternal(VIEW_TYPE_LAST_EMPTY))", 1)[0]
        self.assertIn("!isBotAccount()", flicker_gate)

    def test_initial_dpi_packet_reader_coalesces_fragmented_handshake_prefix(self):
        self.assertTrue(INITIAL_PACKET_READER.is_file(), "DPI strategy needs a stable handshake prefix, not one arbitrary socket read")
        harness = r"""package org.colgram.core;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
public final class InitialPacketReaderHarness {
  public static void main(String[] args) throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      Thread sender = new Thread(() -> {
        try (Socket peer = listener.accept()) {
          peer.getOutputStream().write(new byte[] {1, 2, 3});
          peer.getOutputStream().flush();
          Thread.sleep(8);
          peer.getOutputStream().write(new byte[] {4, 5, 6, 7, 8, 9});
          peer.getOutputStream().flush();
        } catch (Exception e) { throw new RuntimeException(e); }
      });
      sender.start();
      try (Socket client = new Socket("127.0.0.1", listener.getLocalPort())) {
        byte[] prefix = ColgramInitialPacketReader.readPrefix(client, 8, 250);
        if (!Arrays.equals(prefix, new byte[] {1, 2, 3, 4, 5, 6, 7, 8})) throw new AssertionError(Arrays.toString(prefix));
        int tail = client.getInputStream().read();
        if (tail != 9) throw new AssertionError("reader consumed beyond the requested prefix: " + tail);
      }
      sender.join();
    }
    System.out.println("FRAGMENTED_INITIAL_PREFIX_OK");
  }
}"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            source = temp / "InitialPacketReaderHarness.java"
            source.write_text(harness, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-d", str(temp), str(INITIAL_PACKET_READER), str(source)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", str(temp), "org.colgram.core.InitialPacketReaderHarness"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("FRAGMENTED_INITIAL_PREFIX_OK", result.stdout)

    def test_dpi_tunnel_uses_a_bounded_first_reply_wait_then_restores_idle_reads(self):
        self.assertTrue(FIRST_RESPONSE_READER.is_file(), "silent DCs must not pin the UI on Connecting forever")
        helper = FIRST_RESPONSE_READER.read_text(encoding="utf-8")
        self.assertIn("socket.setSoTimeout(timeoutMs)", helper)
        self.assertIn("socket.setSoTimeout(previousTimeout)", helper)
        bypass = DPI_BYPASS.read_text(encoding="utf-8")
        self.assertIn("FIRST_REPLY_TIMEOUT_MS = 3000", bypass)
        pipe = bypass.split("private static void pipeWithAdvancedDesync(", 1)[1]
        self.assertIn(
            "ColgramFirstResponseReader.readFirst(dest, buffer, FIRST_REPLY_TIMEOUT_MS)",
            pipe,
        )
        self.assertIn("upstream stayed silent", pipe)
        harness = r"""package org.colgram.core;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
public final class FirstResponseReaderHarness {
  public static void main(String[] args) throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      Thread silent = new Thread(() -> {
        try (Socket peer = listener.accept()) { Thread.sleep(300); }
        // The client closes first on purpose, so a peer-side error here is the expected end of
        // the scenario, not a failure. Throwing it on an uncaught handler made a passing run
        // report a read timeout when the machine was busy.
        catch (Exception ignored) { }
      });
      silent.setDaemon(true);
      silent.start();
      try (Socket client = new Socket("127.0.0.1", listener.getLocalPort())) {
        byte[] b = new byte[16];
        long started = System.nanoTime();
        try {
          ColgramFirstResponseReader.readFirst(client, b, 75);
          throw new AssertionError("silent peer passed the deadline");
        } catch (SocketTimeoutException expected) {
          long elapsedMs = (System.nanoTime() - started) / 1_000_000;
          if (elapsedMs > 1000) throw new AssertionError("deadline took " + elapsedMs + "ms");
          if (client.getSoTimeout() != 0) throw new AssertionError("timeout state leaked");
        }
      }
      silent.join(2000);
    }
    try (ServerSocket listener = new ServerSocket(0)) {
      Thread replies = new Thread(() -> {
        try (Socket peer = listener.accept()) {
          Thread.sleep(20); peer.getOutputStream().write(0x7f); peer.getOutputStream().flush();
          Thread.sleep(250);
        } catch (Exception ignored) { }
      });
      replies.setDaemon(true);
      replies.start();
      try (Socket client = new Socket("127.0.0.1", listener.getLocalPort())) {
        int count = ColgramFirstResponseReader.readFirst(client, new byte[16], 100);
        if (count != 1 || client.getSoTimeout() != 0)
          throw new AssertionError("first response should restore an unbounded idle read");
      }
      replies.join(2000);
    }
    System.out.println("FIRST_RESPONSE_DEADLINE_OK");
  }
}"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            harness_file = temp / "FirstResponseReaderHarness.java"
            harness_file.write_text(harness, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-d", str(temp), str(FIRST_RESPONSE_READER), str(harness_file)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", str(temp), "org.colgram.core.FirstResponseReaderHarness"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("FIRST_RESPONSE_DEADLINE_OK", result.stdout)

    def test_silent_dc_peer_is_parked_and_cached_route_moves_to_next_candidate(self):
        remap = DC_REMAP.read_text(encoding="utf-8")
        self.assertIn("public static void reportSilentAddress(", remap)
        report = remap.split("public static void reportSilentAddress(", 1)[1].split(
            "private static String probeMissKey(", 1
        )[0]
        self.assertIn("targetCache.remove(entry.getKey(), address)", report)
        self.assertIn("targetCacheAt.remove(entry.getKey())", report)
        self.assertIn("probeMisses.clearFailures()", report)
        self.assertIn("SILENT_ADDRESS_COOLDOWN_MS", report)
        self.assertIn("public static boolean shouldSkipDirectAddress(", remap)
        bypass = DPI_BYPASS.read_text(encoding="utf-8")
        self.assertIn("FIRST_REPLY_TIMEOUT_MS = 3000", bypass)
        self.assertIn("ColgramDcRemap.shouldSkipDirectAddress(destHost)", bypass)
        pipe = bypass.split("private static void pipeWithAdvancedDesync(", 1)[1]
        self.assertIn("ColgramDcRemap.reportSilentAddress(", pipe)
        self.assertIn("dest.getInetAddress().getHostAddress()", pipe)
        self.assertIn("dest.getPort()", pipe)

    def test_dc_fallback_ports_race_in_parallel_under_one_deadline(self):
        self.assertTrue(SOCKET_CONNECT_RACE.is_file(), "DC port fallbacks must share one dial budget")
        bypass = DPI_BYPASS.read_text(encoding="utf-8")
        connect = bypass.split("private static Socket establishConnection(", 1)[1].split(
            "private static boolean isMtProtoHost(", 1
        )[0]
        self.assertIn("ColgramSocketConnectRace.connect(host, candidatePorts", connect)
        helper = SOCKET_CONNECT_RACE.read_text(encoding="utf-8")
        self.assertIn("CountDownLatch", helper)
        self.assertIn("candidate.connect", helper)
        harness = r"""package org.colgram.core;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
public final class SocketConnectRaceHarness {
  public static void main(String[] args) throws Exception {
    int closedPort;
    try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      closedPort = closed.getLocalPort();
    }
    try (ServerSocket live = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      long started = System.nanoTime();
      try (Socket winner = ColgramSocketConnectRace.connect("127.0.0.1",
              new int[] {closedPort, live.getLocalPort()}, 1000)) {
        if (winner == null || winner.getPort() != live.getLocalPort())
          throw new AssertionError("race did not select the listening port");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        if (elapsedMs >= 1000) throw new AssertionError("race exceeded the shared timeout: " + elapsedMs);
      }
      try (Socket accepted = live.accept()) { }
    }
    System.out.println("SOCKET_CONNECT_RACE_OK");
  }
}"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            harness_file = temp / "SocketConnectRaceHarness.java"
            harness_file.write_text(harness, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-d", str(temp), str(SOCKET_CONNECT_RACE), str(harness_file)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", str(temp), "org.colgram.core.SocketConnectRaceHarness"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("SOCKET_CONNECT_RACE_OK", result.stdout)

    def test_dc_remap_only_uses_official_addresses_for_the_same_dc(self):
        self.assertTrue(DC_ADDRESSES.is_file(), "fallback IPs must be pinned to the requested DC")
        helper = DC_ADDRESSES.read_text(encoding="utf-8")
        source = DC_REMAP.read_text(encoding="utf-8")
        chooser = source.split("static List<String> candidatesFor(", 1)[1].split(
            "private static String subnetPrefix(", 1
        )[0]
        self.assertIn("ColgramTelegramDcAddresses.candidatesFor(host)", chooser)
        self.assertNotIn("scheduleDiscovery", chooser)
        self.assertNotIn("TELEGRAM_ADDRESSES", source)
        self.assertIn("ColgramTelegramDcAddresses.isKnownAddress(host)", source)
        chooser_method = source.split("private static String chooseTarget(String host, int port, boolean includeRequestedAddress)", 1)[1].split(
            "private static String probeMissKey(", 1
        )[0]
        self.assertIn("if (!includeRequestedAddress) candidates.remove(host);", chooser_method)
        self.assertIn("PROBE_BUDGET_MS = 1800L", source)
        self.assertIn("liveAlternativeFor(destHost, destPort)", DPI_BYPASS.read_text(encoding="utf-8"))
        harness = r"""package org.colgram.core;
import java.util.Arrays;
public final class TelegramDcAddressesHarness {
  static void has(String[] values, String expected) {
    if (!Arrays.asList(values).contains(expected)) throw new AssertionError("missing " + expected + " in " + Arrays.toString(values));
  }
  static void lacks(String[] values, String forbidden) {
    if (Arrays.asList(values).contains(forbidden)) throw new AssertionError("wrong-DC candidate " + forbidden + " in " + Arrays.toString(values));
  }
  public static void main(String[] args) {
    String[] dc2 = ColgramTelegramDcAddresses.candidatesFor("149.154.167.51");
    has(dc2, "149.154.167.51"); has(dc2, "95.161.76.100");
    has(dc2, "2001:67c:4e8:f002:0:0:0:a");
    lacks(dc2, "149.154.167.91"); lacks(dc2, "5.142.134.227");
    String[] unknown = ColgramTelegramDcAddresses.candidatesFor("149.154.167.220");
    if (unknown.length != 1 || !unknown[0].equals("149.154.167.220"))
      throw new AssertionError("unknown address must not be remapped across DCs");
    if (!ColgramTelegramDcAddresses.isKnownAddress("2001:67c:4e8:f002:0:0:0:a"))
      throw new AssertionError("official IPv6 DC address was not recognized");
    System.out.println("DC_SCOPED_OFFICIAL_ADDRESSES_OK");
  }
}"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            harness_file = temp / "TelegramDcAddressesHarness.java"
            harness_file.write_text(harness, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-d", str(temp), str(DC_ADDRESSES), str(harness_file)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", str(temp), "org.colgram.core.TelegramDcAddressesHarness"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("DC_SCOPED_OFFICIAL_ADDRESSES_OK", result.stdout)

    def test_blocked_proxy_candidate_is_chained_before_native_verification(self):
        source = PROXY.read_text(encoding="utf-8")
        sweep = source.split("public static void sweepFast(final Runnable onDone)", 1)[1].split(
            "private static void finishSweep", 1
        )[0]
        self.assertIn("pickRelay(relaySnapshot, item)", sweep)
        self.assertIn("ColgramProxyChain.open(item.address, item.port, relay)", sweep)
        check = source.split("private static boolean checkOne(", 1)[1].split(
            "private interface AvailabilityHandler", 1
        )[0]
        self.assertIn("buildProxySettings(item)", check)
        connect = source.split("private static void connectThroughBestNode()", 1)[1].split(
            "private static volatile ProxyItem dcRemapItem", 1
        )[0]
        # The fallback now picks only candidates that are alive RIGHT NOW (fresh verdicts
        # ordered, fresh TCP dial each) - stale nativeVerified flags parked the header on
        # "Соединение..." against long-dead nodes.
        self.assertIn("chosen = pickVerifiedAliveNow(6);", connect)
        self.assertIn("private static ProxyItem pickVerifiedAliveNow(int maxTcpChecks) {", source)
        verdict = source.split("private static void onVerdict(", 1)[1].split(
            "private static void prunePool()", 1
        )[0]
        self.assertIn("item.nativeVerified = true;", verdict)

    def test_failed_dc_remap_does_not_mask_the_verified_proxy_fallback(self):
        source = PROXY.read_text(encoding="utf-8")
        auto_connect = source.split("private static void autoConnectIfBlocked()", 1)[1].split(
            "private static void connectThroughBestNode()", 1
        )[0]
        self.assertIn("active != dcRemapItem", auto_connect)
        self.assertIn("!active.isLocalDpi()", auto_connect)
        self.assertIn("if (isProxyEnabled(ctx) && carriesProxy) return;", auto_connect)

    def test_local_socks_front_carries_telegram_tcp_through_an_http_connect_relay(self):
        source = PROXY_CHAIN.read_text(encoding="utf-8")
        self.assertIn("openSocks5Front", source)
        harness = r"""package org.colgram.core;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
public final class SocksFrontHarness {
  static byte[] readExact(InputStream in, int n) throws Exception {
    byte[] b = new byte[n]; int off = 0;
    while (off < n) { int k = in.read(b, off, n - off); if (k < 0) throw new AssertionError("truncated reply"); off += k; }
    return b;
  }
  public static void main(String[] args) throws Exception {
    ServerSocket relay = new ServerSocket(0);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread remote = new Thread(() -> {
      try (Socket s = relay.accept()) {
        InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int ch; boolean headerEnd = false;
        while ((ch = in.read()) >= 0) {
          line.write(ch);
          byte[] v = line.toByteArray(); int n = v.length;
          if (n >= 4 && v[n-4] == '\r' && v[n-3] == '\n' && v[n-2] == '\r' && v[n-1] == '\n') { headerEnd = true; break; }
        }
        if (!headerEnd || !line.toString("UTF-8").startsWith("CONNECT proxy.test:4443 ")) throw new AssertionError("bad CONNECT: " + line.toString("UTF-8"));
        out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
        while ((ch = in.read()) >= 0) { out.write(ch); out.flush(); }
      } catch (Throwable t) { failure.set(t); }
    });
    remote.setDaemon(true); remote.start();
    int port = ColgramProxyChain.openSocks5Front(new ColgramProxyChain.Relay("127.0.0.1", relay.getLocalPort(), false));
    if (port <= 0) throw new AssertionError("SOCKS front did not bind");
    ColgramProxyChain.Relay sharedRelay = new ColgramProxyChain.Relay("127.0.0.1", relay.getLocalPort(), false);
    for (int i = 0; i < 7; i++) {
      if (ColgramProxyChain.open("candidate-" + i + ".test", 443, sharedRelay) <= 0) throw new AssertionError("chain listener did not bind");
    }
    Field mapField = ColgramProxyChain.class.getDeclaredField("forwarders"); mapField.setAccessible(true);
    Map<?, ?> forwarders = (Map<?, ?>) mapField.get(null);
    for (Object forwarder : forwarders.values()) {
      if (forwarder.getClass().getSimpleName().equals("SocksFront")) continue;
      Field served = forwarder.getClass().getDeclaredField("served"); served.setAccessible(true); served.setInt(forwarder, 1);
    }
    if (ColgramProxyChain.open("eviction-trigger.test", 443, sharedRelay) <= 0) throw new AssertionError("eviction-trigger listener did not bind");
    if (!ColgramProxyChain.isOpen(port)) throw new AssertionError("active SOCKS relay front was evicted by candidate sweep");
    try (Socket client = new Socket("127.0.0.1", port)) {
      InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream();
      out.write(new byte[] {5, 1, 0}); out.flush();
      if (!Arrays.equals(readExact(in, 2), new byte[] {5, 0})) throw new AssertionError("no-auth negotiation failed");
      byte[] host = "proxy.test".getBytes(StandardCharsets.US_ASCII);
      out.write(new byte[] {5, 1, 0, 3, (byte) host.length}); out.write(host); out.write(new byte[] {(byte) (4443 >> 8), (byte) 4443}); out.flush();
      byte[] reply = readExact(in, 10);
      if (reply[1] != 0) throw new AssertionError("CONNECT rejected: " + reply[1]);
      byte[] payload = new byte[] {11, 22, 33}; out.write(payload); out.flush();
      if (!Arrays.equals(readExact(in, payload.length), payload)) throw new AssertionError("tunnel payload was not forwarded");
    } finally { ColgramProxyChain.closeAll(); relay.close(); }
    if (failure.get() != null) throw new AssertionError("fake HTTP relay failed", failure.get());
    System.out.println("SOCKS-FRONT-RELAY-PASS");
  }
}"""
        log_stub = r"""package android.util;
public final class Log {
  public static int i(String t, String m) { return 0; }
  public static int w(String t, String m) { return 0; }
  public static int w(String t, String m, Throwable e) { return 0; }
  public static int e(String t, String m, Throwable e) { return 0; }
}"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            harness_file = temp / "SocksFrontHarness.java"
            stub_file = temp / "android/util/Log.java"
            harness_file.parent.mkdir(parents=True, exist_ok=True)
            stub_file.parent.mkdir(parents=True, exist_ok=True)
            harness_file.write_text(harness, encoding="utf-8")
            stub_file.write_text(log_stub, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-d", str(temp), str(stub_file), str(SOCKS5_CODEC), str(RELAY_MISS_CACHE), str(INITIAL_PACKET_READER), str(TLS_MIMIC), str(PROXY_CHAIN), str(harness_file)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", str(temp), "org.colgram.core.SocksFrontHarness"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("SOCKS-FRONT-RELAY-PASS", result.stdout)

    def test_blocked_fastest_relay_falls_back_to_the_next_relay_for_a_proxy(self):
        harness = r"""package org.colgram.core;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
public final class RelaySelectionHarness {
  static final class FakeRelay implements AutoCloseable {
    final ServerSocket server;
    final boolean canReachTarget;
    final AtomicInteger requests = new AtomicInteger();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    FakeRelay(boolean canReachTarget) throws Exception {
      this.server = new ServerSocket(0);
      this.canReachTarget = canReachTarget;
      Thread thread = new Thread(() -> {
        try {
          while (!server.isClosed()) try (Socket socket = server.accept()) {
            requests.incrementAndGet();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream headers = new ByteArrayOutputStream();
            int ch;
            while ((ch = in.read()) >= 0) {
              headers.write(ch);
              byte[] bytes = headers.toByteArray(); int n = bytes.length;
              if (n >= 4 && bytes[n-4] == '\r' && bytes[n-3] == '\n' && bytes[n-2] == '\r' && bytes[n-1] == '\n') break;
            }
            String response = canReachTarget
                ? "HTTP/1.1 200 Connection established\r\n\r\n"
                : "HTTP/1.1 502 Bad Gateway\r\n\r\n";
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
          }
        } catch (Throwable t) { if (!server.isClosed()) failure.set(t); }
      });
      thread.setDaemon(true); thread.start();
    }
    public void close() throws Exception { server.close(); }
  }
  public static void main(String[] args) throws Exception {
    FakeRelay fastestButBlocked = new FakeRelay(false);
    FakeRelay slowerWorking = new FakeRelay(true);
    ColgramProxyChain.Relay first = new ColgramProxyChain.Relay("127.0.0.1", fastestButBlocked.server.getLocalPort(), false);
    ColgramProxyChain.Relay second = new ColgramProxyChain.Relay("127.0.0.1", slowerWorking.server.getLocalPort(), false);
    first.rttMs = 5; second.rttMs = 30;
    try {
      ColgramProxyChain.Relay selected = ColgramProxyChain.pickReachableRelay(
          Arrays.asList(first, second), "blocked-proxy.test", 443, 1000);
      if (selected != second) throw new AssertionError("did not try the second relay after the fastest failed");
      if (fastestButBlocked.requests.get() != 1 || slowerWorking.requests.get() != 1)
        throw new AssertionError("expected one route test per relay, got " + fastestButBlocked.requests + "/" + slowerWorking.requests);
      if (fastestButBlocked.failure.get() != null || slowerWorking.failure.get() != null)
        throw new AssertionError("fake relay failed", fastestButBlocked.failure.get() != null ? fastestButBlocked.failure.get() : slowerWorking.failure.get());
      System.out.println("RELAY-SELECTION-FAILOVER-PASS");
    } finally { ColgramProxyChain.closeAll(); fastestButBlocked.close(); slowerWorking.close(); }
  }
}"""
        log_stub = r"""package android.util;
public final class Log {
  public static int i(String t, String m) { return 0; }
  public static int w(String t, String m) { return 0; }
  public static int w(String t, String m, Throwable e) { return 0; }
  public static int e(String t, String m, Throwable e) { return 0; }
}"""
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            harness_file = temp / "RelaySelectionHarness.java"
            stub_file = temp / "android/util/Log.java"
            stub_file.parent.mkdir(parents=True, exist_ok=True)
            harness_file.write_text(harness, encoding="utf-8")
            stub_file.write_text(log_stub, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-d", str(temp), str(stub_file), str(SOCKS5_CODEC), str(RELAY_MISS_CACHE), str(INITIAL_PACKET_READER), str(TLS_MIMIC), str(PROXY_CHAIN), str(harness_file)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", str(temp), "org.colgram.core.RelaySelectionHarness"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("RELAY-SELECTION-FAILOVER-PASS", result.stdout)

    def test_blocked_proxy_sweep_repeats_after_relay_reachability_checks_finish(self):
        source = PROXY.read_text(encoding="utf-8")
        harvest = source.split("private static void harvestRelays()", 1)[1].split(
            "private static void probeRelayFallbacks()", 1
        )[0]
        self.assertIn("relayChecksRemaining", harvest)
        self.assertIn("relayDiscoveryFinished()", harvest)
        finished = source.split("private static void relayDiscoveryFinished()", 1)[1].split(
            "private static void probeRelayFallbacks()", 1
        )[0]
        self.assertIn("probeRelayFallbacks", finished)
        self.assertIn("sweepFast(null)", finished)

    def test_reachable_http_relay_is_native_checked_as_a_telegram_socks_route(self):
        source = PROXY.read_text(encoding="utf-8")
        relay_probe = source.split("private static void probeRelayFallbacks()", 1)[1].split(
            "private static void collectRelays(", 1
        )[0]
        self.assertIn("ColgramProxyChain.probe(relay", relay_probe)
        self.assertIn("ColgramProxyChain.openSocks5Front(relay)", relay_probe)
        self.assertIn("addCandidate(candidate)", relay_probe)
        self.assertIn("findPoolCandidate(candidate)", relay_probe)
        self.assertIn("checkOne(verifiedRoute, alive -> onVerdict(verifiedRoute, alive))", relay_probe)
        self.assertNotIn("added && !checkOne(candidate", relay_probe)
        self.assertIn("removeDormantRelayRoute()", relay_probe)
        self.assertIn("scheduleRelayRouteAutoApply(verifiedRoute, 12)", relay_probe)
        self.assertIn("startProber();", relay_probe)
        self.assertIn("sweepFast(null);", relay_probe)
        fallback = source.split("private static void scheduleRelayRouteAutoApply(", 1)[1].split(
            "private static void collectRelays(", 1
        )[0]
        self.assertIn("isDcRemapActive() && !dcRemapGaveUp()", fallback)
        self.assertIn("candidate.failedVerdicts > 0", fallback)
        self.assertIn("userSelectedRoute", fallback)
        self.assertIn("forceApplyProxy(candidate)", fallback)

    def test_public_socks_feed_cannot_consume_reserved_relay_fallback_slot(self):
        source = PROXY.read_text(encoding="utf-8")
        self.assertIn("private static final int MAX_SOCKS_CANDIDATES = 120;", source)
        self.assertIn("MAX_SOCKS_CANDIDATES + (isLocalRelayRoute(item) ? 1 : 0)", source)
        helper = source.split("private static boolean isLocalRelayRoute(", 1)[1].split(
            "private static boolean addCandidate(", 1
        )[0]
        self.assertIn("isLoopbackHost(item.address)", helper)
        self.assertIn("ColgramProxyChain.isOpen(item.port)", helper)

    def test_media_cache_stays_in_app_specific_storage(self):
        source = STORAGE.read_text(encoding="utf-8")
        self.assertIn('new File(external, "Colgram/Cache")', source)
        self.assertIn("if (type == 4) return cacheRoot;", source)
        self.assertIn("canonicalPath.startsWith(canonicalCache + File.separator)", source)

    def test_mtproto_secret_decoding_matches_native_formats(self):
        self.assertTrue(SECRETS.is_file())
        harness = """import org.colgram.core.ColgramMtprotoSecrets;
public class SecretCheck {
  public static void main(String[] args) {
    check("0123456789abcdef0123456789abcdef", "0123456789abcdef0123456789abcdef", false);
    check("dd10400103324995b07c030386e886e7f1", "dd10400103324995b07c030386e886e7f1", false);
    check("ee6c083120393936fb881456da3ec073777777772e676f6f676c652e636f6d", "ee6c083120393936fb881456da3ec073777777772e676f6f676c652e636f6d", true);
    check("eeNEgYdJvXrFGRMCIMJdCQ", "79e344818749bd7ac519130220c25d09", false);
    check("AAAAAAAAAAAAAAAAAAAAAA%3D%3D", "00000000000000000000000000000000", false);
    if (ColgramMtprotoSecrets.canonicalHex("invalid!") != null) throw new AssertionError("invalid secret accepted");
    System.out.println("SECRET_FORMATS_OK=6");
  }
  private static void check(String input, String expected, boolean tls) {
    if (!expected.equals(ColgramMtprotoSecrets.canonicalHex(input))) throw new AssertionError(input);
    if (ColgramMtprotoSecrets.isFakeTls(input) != tls) throw new AssertionError("tls: " + input);
  }
}"""
        with tempfile.TemporaryDirectory() as temp:
            test_file = Path(temp) / "SecretCheck.java"
            test_file.write_text(harness, encoding="utf-8")
            javac = os.environ.get("COLGRAM_JAVAC", "javac")
            java = os.environ.get("COLGRAM_JAVA", "java")
            subprocess.run([javac, "-d", temp, str(SECRETS), str(test_file)], check=True, capture_output=True)
            result = subprocess.run([java, "-cp", temp, "SecretCheck"], check=True, capture_output=True, text=True)
            self.assertEqual("SECRET_FORMATS_OK=6", result.stdout.strip())

    def test_pool_keeps_mtproto_entries_when_socks_lists_arrive(self):
        source = PROXY.read_text(encoding="utf-8")
        self.assertTrue("MAX_MTPROTO_CANDIDATES" in source)
        self.assertTrue("MAX_SOCKS_CANDIDATES" in source)
        self.assertTrue("ColgramMtprotoSecrets.canonicalHex" in source)

    def test_proxy_pool_capacity_holds_every_candidate_batch_from_configured_feeds(self):
        source = PROXY.read_text(encoding="utf-8")
        self.assertIn("MAX_MTPROTO_CANDIDATES = 120", source)
        self.assertIn("MAX_SOCKS_CANDIDATES = 120", source)
        self.assertIn("MAX_POOL_SIZE = 1 + MAX_MTPROTO_CANDIDATES", source)
        self.assertIn("MAX_PUBLISHED_TO_STOCK = 30", source)

    def test_old_proxy_verdict_is_not_treated_as_current_and_custom_can_fail_over(self):
        source = PROXY.read_text(encoding="utf-8")
        remembered = source.split("private static void loadRemembered()", 1)[1].split("private static final long REMEMBERED_VALID_MS", 1)[0]
        self.assertNotIn("p.isAvailable = true;", remembered)
        selection = source.split("private static ProxyItem selectProxy(ProxyItem skip)", 1)[1].split("private static boolean betterCandidate", 1)[0]
        self.assertIn("if (p.isAvailable && p.nativeVerified)", selection)
        self.assertNotIn("ProxyItem tcpCandidate", selection)
        rotation = source.split("public static synchronized void switchToNextProxy(boolean force)", 1)[1].split("long now = SystemClock.elapsedRealtime();", 1)[0]
        self.assertTrue("!ColgramConfig.isAutoProxyEnabled()" in rotation)
        self.assertTrue("failed user-configured proxy" in rotation)

    def test_automatic_proxy_needs_native_verdict_and_checks_candidates_in_parallel(self):
        source = PROXY.read_text(encoding="utf-8")
        connect = source.split("private static void connectThroughBestNode()", 1)[1].split("private static volatile ProxyItem dcRemapItem", 1)[0]
        self.assertNotIn("p.tcpMs < 0", connect)
        self.assertIn("p.nativeVerified", connect)
        self.assertIn("FAST_NATIVE_PARALLEL = 2", source)
        self.assertIn("startFastNativeChecks();", source)
        self.assertIn("sweepAgain.set(true);", source)
        self.assertIn("if (sweepAgain.getAndSet(false)) sweepFast(null);", source)

    def test_stock_proxy_screen_checks_only_visible_entries_with_bounded_native_load(self):
        source = PROXY_LIST_ACTIVITY.read_text(encoding="utf-8")
        check = source.split("private void checkProxyList()", 1)[1].split("@Override\n    protected void onDialogDismiss", 1)[0]
        self.assertTrue("MAX_STOCK_PROXY_CHECKS = 2" in source, "stock proxy check cap is missing")
        self.assertTrue("pendingProxyChecks.size() >= MAX_STOCK_PROXY_CHECKS" in check, "stock proxy check cap is not enforced")
        self.assertTrue("listView.getChildCount()" in check and "getChildAdapterPosition" in check, "checks are not limited to visible rows")
        self.assertTrue("pendingProxyChecks.remove(proxyInfo)" in check, "completed checks do not release a native slot")
        self.assertNotIn("for (int a = 0, count = proxyList.size()", check)

    def test_stock_proxy_checks_time_out_instead_of_remaining_checking_forever(self):
        source = PROXY_LIST_ACTIVITY.read_text(encoding="utf-8")
        check = source.split("private void checkProxyList()", 1)[1].split("@Override\\n    protected void onDialogDismiss", 1)[0]
        self.assertTrue("STOCK_PROXY_CHECK_TIMEOUT_MS" in source, "native proxy checks need a finite timeout")
        self.assertIn("completed.compareAndSet(false, true)", check)
        self.assertIn("AndroidUtilities.runOnUIThread(timeout, STOCK_PROXY_CHECK_TIMEOUT_MS)", check)
        self.assertIn("AndroidUtilities.cancelRunOnUIThread(timeout)", check)
        self.assertIn("finishProxyCheck(proxyInfo, -1)", check)
        self.assertIn("private void finishProxyCheck(SharedConfig.ProxyInfo proxyInfo, long time)", source)

    def test_proxy_check_scheduler_patcher_uses_current_stock_field_anchor(self):
        patcher = PATCHER.read_text(encoding="utf-8")
        self.assertTrue("def proxy_check_scheduler_patcher(content):" in patcher, "the proxy-check scheduler patch is missing")
        scheduler = patcher.split("def proxy_check_scheduler_patcher(content):", 1)[1].split(
            "# 36. Browser.java", 1
        )[0]
        self.assertIn('"    private final static boolean IS_PROXY_ROTATION_AVAILABLE = true;"', scheduler)
        self.assertIn("private static final int MAX_STOCK_PROXY_CHECKS = 2;", scheduler)
        self.assertIn("listView.post(() -> checkProxyList());", scheduler)
        self.assertIn("STOCK_PROXY_CHECK_TIMEOUT_MS = 15_000L", scheduler)
        self.assertIn("finishProxyCheck(proxyInfo, -1)", scheduler)
        self.assertIn("AndroidUtilities.cancelRunOnUIThread(timeout)", scheduler)
        self.assertIn('"private static final long STOCK_PROXY_CHECK_TIMEOUT_MS = 15_000L;"', scheduler)

    def test_standalone_release_variant_uses_release_signing_config(self):
        build = STANDALONE_GRADLE.read_text(encoding="utf-8")
        self.assertIn("    buildTypes {", build, "standalone application build types are missing")
        build_types = build.split("    buildTypes {", 1)[1]
        self.assertIn("        release {", build_types, "afatrelease packaging must declare its signing block")
        release = build_types.split("        release {", 1)[1].split("        }", 1)[0]
        self.assertIn("signingConfig signingConfigs.release", release)

    def test_failed_local_dpi_route_yields_to_native_verified_proxy(self):
        source = PROXY.read_text(encoding="utf-8")
        auto_connect = source.split("private static void autoConnectIfBlocked()", 1)[1].split(
            "private static void connectThroughBestNode()", 1
        )[0]
        self.assertIn("shouldFallbackFromLocalDpi(active)", auto_connect)
        self.assertNotIn("if (isUserProxyDisabled()) return;", auto_connect)
        self.assertIn("if (!ColgramConfig.isAutoProxyEnabled()) return;", auto_connect)
        connect = source.split("private static void connectThroughBestNode()", 1)[1].split(
            "private static volatile ProxyItem dcRemapItem", 1
        )[0]
        self.assertIn("routeInUse == null || shouldFallbackFromLocalDpi(routeInUse)", connect)
        self.assertNotIn("if (isUserProxyDisabled())", connect)
        fallback = source.split("private static boolean shouldFallbackFromLocalDpi(", 1)[1].split(
            "private static volatile ProxyItem dcRemapItem", 1
        )[0]
        self.assertIn("active.isLocalDpi()", fallback)
        self.assertIn("active.nativeFailures >= 3", fallback)
        self.assertIn("!localBypassUsable()", fallback)
        startup = source.split("private static void activateBuiltinProxyNow()", 1)[1].split(
            "private static void initVerifiedPool()", 1
        )[0]
        self.assertIn("if (ColgramConfig.isDpiBypassEnabled())", startup)
        self.assertNotIn("ColgramConfig.isDpiBypassEnabled() && !isUserProxyDisabled()", startup)

    def test_local_bypass_is_applied_even_when_direct_telegram_probe_fails(self):
        source = PROXY.read_text(encoding="utf-8")
        setter = source.split("public static void setDpiBypassEnabled(", 1)[1].split(
            "private static void toast", 1
        )[0]
        self.assertNotIn("telegramDirectlyReachable()", setter,
                         "a raw direct probe must not gate the local transport")
        self.assertIn("forceApplyProxy(local);", setter)
        self.assertNotIn("localRouteUnavailableUntil =", setter,
                         "the direct TCP probe is not a verdict on the local desync route")
        self.assertNotIn("autoConnectIfBlocked();", setter,
                         "enabling local bypass must not switch to public proxies")

        startup = source.split("private static void activateBuiltinProxyNow()", 1)[1].split(
            "// 2. Start with the local path", 1
        )[0]
        self.assertNotIn("if (localReady && !telegramDirectlyReachable())", startup,
                         "startup must not suppress the local route from a direct-only probe")
        self.assertNotIn("localRouteUnavailableUntil =", startup)

    def test_automatic_runtime_proxy_is_visible_in_settings(self):
        source = SETTINGS.read_text(encoding="utf-8")
        self.assertIn("boolean proxyOn = ColgramProxyManager.isProxyEnabled(getContext())\n                                || active != null;", source)
        self.assertIn("ColgramProxyManager.getCurrentActiveProxy() != null", source)

    def test_proxy_status_icon_and_stock_toggle_follow_colgram_runtime_route(self):
        manager = PROXY.read_text(encoding="utf-8")
        self.assertIn("public static boolean hasActiveRouteForUi()", manager)
        self.assertIn("public static void onStockProxyToggle(boolean enabled)", manager)

        login = LOGIN_ACTIVITY.read_text(encoding="utf-8")
        login_status = login.split("private void updateProxyButton(", 1)[1].split(
            "private boolean proxyButtonVisible", 1
        )[0]
        self.assertIn("hasActiveRouteForUi()", login_status)
        self.assertIn("NotificationCenter.proxySettingsChanged", login)

        dialogs = DIALOGS_ACTIVITY.read_text(encoding="utf-8")
        dialog_status = dialogs.split("private void updateProxyButton(", 1)[1].split(
            "private AnimatorSet doneItemAnimator", 1
        )[0]
        self.assertIn("hasActiveRouteForUi()", dialog_status)

        proxy_list = PROXY_LIST_ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("ColgramProxyManager.hasActiveRouteForUi()", proxy_list)
        self.assertIn("ColgramProxyManager.onStockProxyToggle(useProxySettings)", proxy_list)

    def test_intro_language_badge_tracks_theme_button_bounds(self):
        source = INTRO.read_text(encoding="utf-8")
        self.assertIn("introLanguageBadge.layout(badgeX, badgeY", source)
        self.assertIn("themeFrameLayout.layout(themeX, newTopMargin", source)

    def test_bot_profile_save_runs_before_missing_user_full_guard(self):
        source = USER_INFO.read_text(encoding="utf-8")
        block = source.split("private void processDone(boolean error)", 1)[1]
        self.assertLess(block.index("if (user != null && user.bot)"),
                        block.index("if (user == null || userFull == null)"))
        self.assertIn("TL_bots.setBotInfo req = new TL_bots.setBotInfo()", source)
        self.assertIn("TL_bots.getBotInfo req = new TL_bots.getBotInfo()", source)
        self.assertIn("class TL_botInfoLocalized extends BotInfo", TL_BOTS.read_text(encoding="utf-8"))

    def test_bot_profile_save_honors_flood_wait_and_keeps_unsaved_values_local(self):
        source = USER_INFO.read_text(encoding="utf-8")
        template = (ROOT / "scripts/templates/ColgramBotProfileSave.java.inc").read_text(encoding="utf-8")
        block = source.split("if (user != null && user.bot)", 1)[1].split(
            "if (user == null || userFull == null)", 1
        )[0]
        self.assertNotIn(
            "RequestFlagDoNotWaitFloodWait",
            block,
            "setBotInfo must let tgnet wait and retry FLOOD_WAIT instead of surfacing it immediately",
        )
        self.assertNotIn("RequestFlagDoNotWaitFloodWait", template)
        fail_branch = block.split("if (rpcError != null || !(response instanceof TLRPC.TL_boolTrue))", 1)[1]
        self.assertLess(fail_branch.index("return;"), fail_branch.index("user.first_name = botFirst;"))
        native_flags = TGNET_DEFINES.read_text(encoding="utf-8")
        native_manager = TGNET_CONNECTIONS_MANAGER.read_text(encoding="utf-8")
        self.assertIn("RequestFlagIgnoreFloodWait = 1024", native_flags)
        self.assertIn("error->error_code == 420 && (request->requestFlags & RequestFlagIgnoreFloodWait) == 0", native_manager)
        self.assertIn("request->failedByFloodWait = waitTime;", native_manager)

    def test_bot_realtime_sync_prefers_authenticated_mtproto(self):
        source = BOT_SYNC.read_text(encoding="utf-8")
        sync = source.split("public static void syncBotDialogs(final Context context, final int account, final boolean userInitiated)", 1)[1]
        self.assertLess(sync.index("ensureMtprotoBotLoop("), sync.index("getBotToken(context, account)"))
        self.assertIn('controller.getMethod("getDifference").invoke(instance)', source)
        self.assertIn("mtprotoBotLoops.remove(account);", source)

    def test_mtproto_bot_manual_sync_reloads_dialog_cache_immediately(self):
        source = BOT_SYNC.read_text(encoding="utf-8")
        sync = source.split("public static void syncBotDialogs(final Context context, final int account, final boolean userInitiated)", 1)[1]
        branch = sync.split("final String token = getBotToken(context, account);", 1)[0]
        self.assertIn("if (userInitiated) refreshMtprotoBotDialogs(context.getApplicationContext(), account);", branch)
        refresh = source.split("private static void refreshMtprotoBotDialogs(", 1)[1].split(
            "public static void syncBotDialogs(final Context context, final int account)", 1
        )[0]
        self.assertIn('controller.getMethod("loadDialogs"', refresh)
        self.assertIn("boolean.class, Runnable.class).invoke(instance, 0, 0, 100, true, null);", refresh)
        self.assertIn('controller.getMethod("getDifference").invoke(instance)', refresh)
        self.assertIn("mtprotoDialogRefreshPending", refresh)

    def test_mtproto_bot_first_loop_start_refreshes_dialog_cache(self):
        source = BOT_SYNC.read_text(encoding="utf-8")
        loop = source.split("private static void ensureMtprotoBotLoop(", 1)[1].split(
            "/** Refresh the bot account's local dialog cache", 1
        )[0]
        self.assertIn("if (!mtprotoBotLoops.add(account)) return;", loop)
        self.assertIn("refreshMtprotoBotDialogs(context, account);", loop)

    def test_antispam_screen_uses_in_app_guard_without_userbot_login(self):
        source = ANTI_SPAM_UI.read_text(encoding="utf-8")
        self.assertIn("ColgramConfig.isSpamGuardEnabled()", source)
        self.assertIn("ColgramConfig.setSpamGuardEnabled(", source)
        self.assertNotIn("ColgramAntiSpam.getApiId", source)
        self.assertNotIn("ColgramAntiSpam.start(", source)
        generated = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ColgramAntiSpamActivity.java").read_text(encoding="utf-8")
        self.assertEqual(source, generated)

    def test_temp_mail_loads_each_provider_page_and_persists_selected_host(self):
        source = TEMP_MAIL_UI.read_text(encoding="utf-8")
        self.assertIn('"https://api.mail.tm"', source)
        self.assertIn('"https://api.mail.gw"', source)
        self.assertIn('"/domains?page=" + page', source)
        self.assertIn('"hydra:next"', source)
        self.assertIn('"api_base"', source)
        restore = source.split("private boolean restoreSavedMailboxLocal()", 1)[1].split("private void manualRefresh()", 1)[0]
        self.assertIn("ApplicationLoader.applicationContext", restore)
        generated = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ColgramTempMailActivity.java").read_text(encoding="utf-8")
        self.assertEqual(source, generated)

    def test_temp_mail_websites_are_primary_and_need_no_api_key(self):
        source = TEMP_MAIL_UI.read_text(encoding="utf-8")
        self.assertLess(source.index('{"smailpro.com"'), source.index('{"22.do"'))
        self.assertNotIn("anything that promises one is a trap", source)
        click = source.split("listView.setOnItemClickListener", 1)[1].split("return fragmentView", 1)[0]
        self.assertIn("position >= 1 && position <= WEB_TEMP_SERVICES.length", click)
        self.assertIn("int webIndex = position - 1", click)
        self.assertIn('h.setText("Сайты без API-ключей")', source)
        self.assertIn("return 1 + WEB_TEMP_SERVICES.length + 1 + 1 + 1 + (msgCount == 0 ? 1 : msgCount);", source)
        generated = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/ui/ColgramTempMailActivity.java").read_text(encoding="utf-8")
        self.assertEqual(source, generated)

    def test_search_tabs_expose_official_global_search_and_keep_telegram_global_query(self):
        pager = SEARCH_PAGER.read_text(encoding="utf-8")
        adapter = SEARCH_ADAPTER.read_text(encoding="utf-8")
        russian = RU_STRINGS.read_text(encoding="utf-8")
        self.assertIn("return getString(R.string.GlobalSearch);", pager)
        self.assertIn("dialogsSearchAdapter.searchDialogs(query, includeFolder ? 1 : 0, true);", pager)
        self.assertIn("new TLRPC.TL_messages_searchGlobal()", adapter)
        self.assertIn('<string name="GlobalSearch">Глобальный поиск</string>', russian)

    def test_global_search_is_an_additional_tab_and_chats_tab_is_preserved(self):
        pager = SEARCH_PAGER.read_text(encoding="utf-8")
        adapter = SEARCH_ADAPTER.read_text(encoding="utf-8")
        english = (ROOT / "Telegram-Src/TMessagesProj/src/main/res/values/strings.xml").read_text(encoding="utf-8")
        russian = RU_STRINGS.read_text(encoding="utf-8")
        chats_tab = "items.add(new Item(DIALOGS_TYPE));"
        global_tab = "items.add(new Item(GLOBAL_SEARCH_TYPE));"
        self.assertTrue(chats_tab in pager, "The Chats tab must remain as a page of its own.")
        self.assertTrue(global_tab in pager, "Global Search must be an additional page.")
        self.assertLess(pager.index(chats_tab), pager.index(global_tab), "Chats stays before Global Search.")
        self.assertLess(pager.index("items.add(new Item(CHANNELS_TYPE));"), pager.index(global_tab))
        self.assertLess(pager.index("items.add(new Item(BOTS_TYPE));"), pager.index(global_tab))
        self.assertLess(pager.index("items.add(new Item(POSTS_TYPE));"), pager.index(global_tab))
        titles = pager.split("public CharSequence getItemTitle(int position)", 1)[1].split("public int getItemCount()", 1)[0]
        self.assertIn("items.get(position).type == DIALOGS_TYPE", titles)
        self.assertIn("return getString(R.string.ChatsTab);", titles)
        self.assertIn("items.get(position).type == GLOBAL_SEARCH_TYPE", titles)
        self.assertIn("return getString(R.string.GlobalSearch);", titles)
        self.assertIn("return getString(R.string.ChatsTab);", pager)
        self.assertIn("private final static int GLOBAL_SEARCH_TYPE = 7;", pager)
        self.assertIn(global_tab, pager)
        self.assertIn("items.get(position).type == GLOBAL_SEARCH_TYPE", pager)
        self.assertIn("globalSearchContainer", pager)
        self.assertIn("tabsView.scrollToTab(1, 1);", pager)
        self.assertIn("viewPagerAdapter.getItemViewType(position) == 2", pager)
        self.assertNotIn("(expandedPublicPosts ? 1 : 0) + 6", pager)
        self.assertIn("getGlobalSearchResults()", adapter)
        self.assertIn('<string name="ChatsTab">Chats</string>', english)
        self.assertIn('<string name="ChatsTab">Чаты</string>', russian)

    def test_voice_transcription_hint_uses_text_contrast_of_its_theme_background(self):
        source = UNDO_VIEW.read_text(encoding="utf-8")
        action = source.split("} else if (currentAction == ACTION_PREMIUM_TRANSCRIPTION) {", 1)[1].split(
            "} else if (currentAction == ACTION_HINT_SWIPE_TO_REPLY)", 1
        )[0]
        action_switch = source.split("currentAction = action;", 1)[1].split("timeLeft = 5000;", 1)[0]
        helper = source.split("private void applyPremiumTranscriptionContrast()", 1)[1].split(
            "private void ", 1
        )[0]
        self.assertRegex(
            action_switch,
            r"if \(action == ACTION_PREMIUM_TRANSCRIPTION\) \{\s*applyPremiumTranscriptionContrast\(\);",
        )
        self.assertIn("else if (premiumTranscriptionContrastApplied)", action_switch)
        self.assertIn("restoreThemedTextColors();", action_switch)
        self.assertIn("Theme.key_undo_background", helper)
        self.assertIn("ColorUtils.calculateLuminance(background)", helper)
        self.assertIn("Color.BLACK", helper)
        self.assertIn("Color.WHITE", helper)
        self.assertIn("undoTextView.setTextColor(textColor);", helper)

    def test_google_photos_is_a_native_attachment_action(self):
        attach = ATTACH.read_text(encoding="utf-8")
        chat = CHAT.read_text(encoding="utf-8")
        self.assertTrue('"Google Фото"' in attach)
        self.assertTrue("googlePhotosButton = buttonsCount++" in attach)
        self.assertTrue("REQ_GOOGLE_PHOTOS" in chat)
        self.assertTrue('setPackage("com.google.android.apps.photos")' in chat)
        self.assertTrue("SendMessagesHelper.prepareSendingMedia" in chat)


if __name__ == "__main__":
    unittest.main()
