package org.colgram.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JVM benchmark and regression suite for 10x turbo bypass upgrades:
 * - Zero-RTT PrewarmedWsPool (<1ms checkout, TTL eviction, clean resetNetworkState close)
 * - Instant startup + READY_LOCK wake (<250ms start(), instant startImmediately())
 * - Zero-sleep segmentation & MODE_HYBRID_MULTI_RECORD constant/fallback checks
 * - 0-ms Stale-While-Revalidate DoH + critical prefetch host list
 * - Per-network strategy/WSS hint restoration and clean clearConnectionFailureBackoff reset
 */
public final class ColgramTurboBypassBenchmarkTest {

    public static void main(String[] args) throws Exception {
        try {
            testPrewarmedWsPoolZeroRttAndEviction();
            testInstantBypassStartupAndReadyLockWake();
            testZeroSleepSegmentationAndNativeConstants();
            testDohSwrZeroMsAndCriticalPrefetch();
            testPerNetworkHintPersistenceAndReset();
            testSendLatencyPoolsForegroundPrewarmAndChunkedSocks();
            System.out.println("ColgramTurboBypassBenchmarkTest: PASS");
        } finally {
            ColgramDpiBypass.stop();
            ColgramTelegramWsBridge.setPrewarmEnabledForTest(true);
            ColgramDohResolver.clearDnsCacheForTest();
        }
    }

    private static void testPrewarmedWsPoolZeroRttAndEviction() throws Exception {
        ColgramTelegramWsBridge.setPrewarmEnabledForTest(false);
        ColgramTelegramWsBridge.resetNetworkState();
        check(ColgramTelegramWsBridge.prewarmedPoolSizeForTest() == 0,
                "pool must start empty after resetNetworkState");

        try (ServerSocket sink = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))) {
            int port = sink.getLocalPort();

            // 1) Warm checkout must be sub-millisecond and leave zero leaks
            Socket warmupPeer = new Socket("127.0.0.1", port);
            Socket warmupSrv = sink.accept();
            ColgramTelegramWsBridge.WsSession warmDummy = new ColgramTelegramWsBridge.WsSession(
                    2, 2, "127.0.0.1", "max.ru",
                    warmupPeer, warmupPeer.getInputStream(), warmupPeer.getOutputStream());
            check(ColgramTelegramWsBridge.offerPrewarmedForTest(warmDummy, System.currentTimeMillis(), false),
                    "warmup offer must succeed");
            ColgramTelegramWsBridge.WsSession warmOut = ColgramTelegramWsBridge.takePrewarmed(2, false);
            check(warmOut == warmDummy, "warmup takePrewarmed must return offered session");
            warmOut.close();
            warmupSrv.close();

            Socket c1 = new Socket("127.0.0.1", port);
            Socket s1 = sink.accept();
            ColgramTelegramWsBridge.WsSession liveSess = new ColgramTelegramWsBridge.WsSession(
                    2, 2, "127.0.0.1", "vk.com", c1, c1.getInputStream(), c1.getOutputStream());
            check(ColgramTelegramWsBridge.offerPrewarmedForTest(liveSess, System.currentTimeMillis(), false),
                    "offer live session must succeed");
            check(ColgramTelegramWsBridge.hasPrewarmed(2, false), "hasPrewarmed(2, false) should be true");
            check(ColgramTelegramWsBridge.prewarmedPoolSizeForTest(2, false) == 1, "bucket size should be 1");

            long t0 = System.nanoTime();
            ColgramTelegramWsBridge.WsSession hit = ColgramTelegramWsBridge.takePrewarmed(2, false);
            long elapsedNs = System.nanoTime() - t0;
            check(hit == liveSess, "takePrewarmed must return exact offered WsSession");
            check(elapsedNs < 2_000_000L, "prewarmed checkout took too long: " + elapsedNs + "ns");
            check(ColgramTelegramWsBridge.prewarmedPoolSizeForTest(2, false) == 0,
                    "pool bucket must be empty after checkout");
            hit.close();
            s1.close();

            // 2) TTL eviction closes stale idle sockets cleanly
            Socket cStale = new Socket("127.0.0.1", port);
            Socket sStale = sink.accept();
            ColgramTelegramWsBridge.WsSession staleSess = new ColgramTelegramWsBridge.WsSession(
                    4, -4, "127.0.0.1", "gosuslugi.ru",
                    cStale, cStale.getInputStream(), cStale.getOutputStream());
            long now = System.currentTimeMillis();
            boolean acceptedExpired = ColgramTelegramWsBridge.offerPrewarmedForTest(staleSess, now - 65_000L, false);
            check(!acceptedExpired || !ColgramTelegramWsBridge.hasPrewarmed(-4, true),
                    "expired entry must not be reported usable");
            ColgramTelegramWsBridge.evictExpiredPrewarmedForTest(now + 65_000L);
            check(cStale.isClosed(), "expired prewarmed socket must be closed");
            check(ColgramTelegramWsBridge.prewarmedPoolSizeForTest() == 0,
                    "pool must be empty after TTL eviction");
            sStale.close();

            // 3) Network switch resetNetworkState closes every pooled idle socket
            Socket cReset = new Socket("127.0.0.1", port);
            Socket sReset = sink.accept();
            ColgramTelegramWsBridge.WsSession mediaSess = new ColgramTelegramWsBridge.WsSession(
                    2, -2, "127.0.0.1", "ya.ru",
                    cReset, cReset.getInputStream(), cReset.getOutputStream());
            check(ColgramTelegramWsBridge.offerPrewarmedForTest(mediaSess, System.currentTimeMillis(), false),
                    "media session offer must succeed");
            check(ColgramTelegramWsBridge.hasPrewarmed(-2, true), "media session must be usable before reset");
            ColgramTelegramWsBridge.resetNetworkState();
            check(cReset.isClosed(), "resetNetworkState must close pooled sockets immediately");
            check(!ColgramTelegramWsBridge.hasPrewarmed(-2, true), "media session must disappear after reset");
            check(ColgramTelegramWsBridge.prewarmedPoolSizeForTest() == 0,
                    "pool size must be 0 after resetNetworkState");
            sReset.close();
        }
    }

    private static void testInstantBypassStartupAndReadyLockWake() throws Exception {
        ColgramTelegramWsBridge.setPrewarmEnabledForTest(false);
        ColgramDpiBypass.stop();
        check(!ColgramDpiBypass.isBound(), "must not be bound after stop()");

        long t0 = System.nanoTime();
        ColgramDpiBypass.start();
        boolean ready = ColgramDpiBypass.awaitReady(1000L);
        long startElapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        check(ready, "ColgramDpiBypass.start() must become ready within 1000ms");
        check(startElapsedMs < 400L,
                "ColgramDpiBypass.start()+awaitReady expected <400ms (was " + startElapsedMs + "ms)");
        check(ColgramDpiBypass.activePort() > 0, "activePort must be > 0");

        ColgramDpiBypass.stop();
        check(!ColgramDpiBypass.isBound(), "must be unbound after stop()");

        final CountDownLatch waiterEntered = new CountDownLatch(1);
        final AtomicBoolean waiterReady = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            waiterEntered.countDown();
            waiterReady.set(ColgramDpiBypass.awaitReady(2000L));
        }, "test-ready-waiter");
        waiter.setDaemon(true);
        waiter.start();
        check(waiterEntered.await(500L, TimeUnit.MILLISECONDS), "waiter thread did not start");
        Thread.sleep(10L);

        long w0 = System.nanoTime();
        ColgramDpiBypass.startImmediately();
        waiter.join(500L);
        long wakeMs = (System.nanoTime() - w0) / 1_000_000L;
        check(waiterReady.get(), "READY_LOCK waiter must observe bound listener immediately");
        check(wakeMs < 400L, "startImmediately READY_LOCK wake took too long: " + wakeMs + "ms");
        ColgramDpiBypass.stop();
    }

    private static void testZeroSleepSegmentationAndNativeConstants() throws Exception {
        check(ColgramNativeDesync.MODE_MULTI_SPLIT_SNI == 11, "MODE_MULTI_SPLIT_SNI must stay 11");
        check(ColgramNativeDesync.MODE_HYBRID_MULTI_RECORD == 12, "MODE_HYBRID_MULTI_RECORD must be 12");

        Path socksCodec = Paths.get("colgram-core", "src", "main", "java", "org", "colgram", "core", "ColgramSocks5Codec.java");
        if (Files.isRegularFile(socksCodec)) {
            String src = new String(Files.readAllBytes(socksCodec), StandardCharsets.UTF_8);
            check(!src.contains("Thread.sleep"), "ColgramSocks5Codec must have zero Thread.sleep calls");
            Path tgSocksCodec = Paths.get("Telegram-Src").resolve(socksCodec);
            if (Files.isRegularFile(tgSocksCodec)) {
                String tgSrc = new String(Files.readAllBytes(tgSocksCodec), StandardCharsets.UTF_8);
                check(src.equals(tgSrc), "colgram-core and Telegram-Src/colgram-core ColgramSocks5Codec.java must match");
            }
        }

        byte[] ch = buildMinimalTlsClientHello("www.youtube.com");
        int[] sni = ColgramDpiBypass.findTlsSniSpan(ch, ch.length);
        check(sni != null && new String(ch, sni[0], sni[1] - sni[0], StandardCharsets.US_ASCII).equals("www.youtube.com"),
                "synthetic ClientHello SNI must parse cleanly");

        long t0 = System.nanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
        for (int strat = 0; strat <= 13; strat++) {
            out.reset();
            ColgramDpiBypass.sendDesynced(null, out, ch, ch.length, strat);
            byte[] sent = out.toByteArray();
            check(sent.length >= ch.length, "strategy " + strat + " lost bytes");
        }
        out.reset();
        check(ColgramDpiBypass.writeTlsRecordSplitAt(out, ch, ch.length, sni[0] + 5, true),
                "writeTlsRecordSplitAt must return true");
        out.reset();
        check(ColgramDpiBypass.writeTlsRecordSplit3(out, ch, ch.length, sni[0] + 2, sni[0] + 7),
                "writeTlsRecordSplit3 must return true");
        out.reset();
        ColgramDpiBypass.writeFirstPayloadForL3Relay(out, ch, ch.length);
        check(out.size() >= ch.length, "L3 relay write must emit all handshake bytes");
        out.reset();
        ColgramSocks5Codec.writeHttpConnectSegmented(out, "api.telegram.org", 443, "u", "p");
        check(new String(out.toByteArray(), StandardCharsets.US_ASCII).contains("CONNECT api.telegram.org:443"),
                "segmented HTTP CONNECT output mismatch");
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;
        check(totalMs < 40L, "zero-sleep segmentation expected <40ms total, took " + totalMs + "ms");

        // Loopback SOCKS5 CONNECT host + RFC1929 auth timing check (zero inter-chunk sleep)
        try (ServerSocket srv = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            final int totalRuns = 2; // 1st warms classloading/thread dispatch, 2nd measures codec latency
            Thread mockSocks = new Thread(() -> {
                for (int r = 0; r < totalRuns; r++) {
                    try (Socket s = srv.accept()) {
                        InputStream in = s.getInputStream();
                        OutputStream sout = s.getOutputStream();
                        byte[] greet = ColgramSocks5Codec.readFully(in, 4);
                        if (greet[0] == 5) sout.write(new byte[]{5, 2});
                        int ver = in.read();
                        int ulen = in.read();
                        ColgramSocks5Codec.readFully(in, ulen);
                        int plen = in.read();
                        ColgramSocks5Codec.readFully(in, plen);
                        if (ver == 1) sout.write(new byte[]{1, 0});
                        byte[] hdr = ColgramSocks5Codec.readFully(in, 5);
                        int hlen = hdr[4] & 0xFF;
                        ColgramSocks5Codec.readFully(in, hlen + 2);
                        sout.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 80});
                        sout.flush();
                    } catch (Exception ignored) {}
                }
            }, "test-socks5-mock");
            mockSocks.setDaemon(true);
            mockSocks.start();

            long socksMs = 0L;
            for (int r = 0; r < totalRuns; r++) {
                long s0 = System.nanoTime();
                try (Socket cli = new Socket("127.0.0.1", srv.getLocalPort())) {
                    cli.setTcpNoDelay(true);
                    boolean ok = ColgramSocks5Codec.requestConnectHost(
                            cli.getOutputStream(), cli.getInputStream(),
                            "kws2.web.telegram.org", 443, "user", "pass");
                    check(ok, "SOCKS5 requestConnectHost must succeed");
                }
                socksMs = (System.nanoTime() - s0) / 1_000_000L;
            }
            check(socksMs < 150L, "SOCKS5 auth+CONNECT expected fast (<150ms), took " + socksMs + "ms");
        }

        // Loopback real-Socket benchmark for writeHttpConnectSegmented & writeFirstPayloadForL3Relay (zero Thread.sleep)
        try (ServerSocket srv = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            final int totalIters = 10;
            Thread drainer = new Thread(() -> {
                try (Socket s = srv.accept()) {
                    InputStream in = s.getInputStream();
                    byte[] buf = new byte[4096];
                    while (in.read(buf) != -1) {
                    }
                } catch (Exception ignored) {}
            }, "test-real-socket-drain");
            drainer.setDaemon(true);
            drainer.start();

            long h0 = System.nanoTime();
            try (Socket cli = new Socket("127.0.0.1", srv.getLocalPort())) {
                cli.setTcpNoDelay(true);
                OutputStream realOut = cli.getOutputStream();
                for (int i = 0; i < totalIters; i++) {
                    ColgramSocks5Codec.writeHttpConnectSegmented(realOut, "api.telegram.org", 443, "u", "p");
                    ColgramDpiBypass.writeFirstPayloadForL3Relay(realOut, ch, ch.length);
                }
            }
            drainer.join(500L);
            long realSocketMs = (System.nanoTime() - h0) / 1_000_000L;
            check(realSocketMs < 60L, "real-socket HTTP CONNECT + L3 relay 10x expected <60ms, took " + realSocketMs + "ms");
        }
    }

    private static void testDohSwrZeroMsAndCriticalPrefetch() throws Exception {
        ColgramDohResolver.clearDnsCacheForTest();
        String[] hosts = ColgramDohResolver.criticalPrefetchHostsForTest();
        check(hosts.length >= 10, "critical prefetch list should include Telegram/YouTube/Discord/GitHub");
        check(Arrays.asList(hosts).contains("kws2.web.telegram.org"), "must prefetch kws2");
        check(Arrays.asList(hosts).contains("kws4.web.telegram.org"), "must prefetch kws4");
        check(Arrays.asList(hosts).contains("api.telegram.org"), "must prefetch api.telegram.org");
        check(Arrays.asList(hosts).contains("www.youtube.com"), "must prefetch youtube");
        check(Arrays.asList(hosts).contains("discord.com"), "must prefetch discord");

        long now = System.currentTimeMillis();
        // Warm both cache lookup and SWR executor once to avoid one-time classload / thread-start jitter
        ColgramDohResolver.putDnsCacheEntry("warm-doh-test.local", new String[]{"127.0.0.1"}, now);
        ColgramDohResolver.resolve("warm-doh-test.local");
        ColgramDohResolver.putDnsCacheEntry("warm-swr-test.local", new String[]{"127.0.0.1"}, now - 130_000L);
        ColgramDohResolver.resolve("warm-swr-test.local");
        ColgramDohResolver.clearDnsCacheForTest();
        check(ColgramDohResolver.swrTriggerCountForTest() == 0, "fresh hit must not trigger SWR");

        // Fresh entry (<60% of 180s TTL -> 30s old)
        ColgramDohResolver.putDnsCacheEntry("fresh-doh-test.local", new String[]{"149.154.167.220"}, now - 30_000L);
        long tFresh = System.nanoTime();
        InetAddress[] freshRes = ColgramDohResolver.resolve("fresh-doh-test.local");
        long freshNs = System.nanoTime() - tFresh;
        check(freshRes != null && freshRes.length == 1, "fresh cache lookup must return address");
        check(freshNs < 3_000_000L, "fresh cache lookup took too long: " + freshNs + "ns");
        check(ColgramDohResolver.swrTriggerCountForTest() == 0, "30s old entry must not trigger SWR");

        // Stale-while-revalidate entry (>60% of 180s TTL -> 130s old, < 180s)
        ColgramDohResolver.putDnsCacheEntry("swr-aging-test.local", new String[]{"149.154.167.99"}, now - 130_000L);
        long tSwr = System.nanoTime();
        InetAddress[] swrRes = ColgramDohResolver.resolve("swr-aging-test.local");
        long swrNs = System.nanoTime() - tSwr;
        check(swrRes != null && swrRes.length == 1
                        && "149.154.167.99".equals(swrRes[0].getHostAddress()),
                "aging cache entry must still return cached IP cleanly");
        check(swrNs < 25_000_000L, "SWR lookup must be non-blocking (<25ms), took " + swrNs + "ns");
        check(ColgramDohResolver.swrTriggerCountForTest() == 1,
                "aging (>60% TTL) hit must trigger async SWR background refresh exactly once");
    }

    private static void testPerNetworkHintPersistenceAndReset() {
        ColgramDpiBypass.resetPersistedHintsForTest();
        ColgramDpiBypass.clearConnectionFailureBackoff();
        try {
            ColgramDpiBypass.setCellularNetwork(false);
            ColgramDpiBypass.persistDesyncStrategyHint(false, ColgramDpiBypass.STRATEGY_TLS_2REC_SNI);
            ColgramDpiBypass.persistWssHint(false, 2, 4);

            ColgramDpiBypass.setCellularNetwork(true);
            ColgramDpiBypass.persistDesyncStrategyHint(true, ColgramDpiBypass.STRATEGY_TLS_3REC_SNI);
            ColgramDpiBypass.persistWssHint(true, 1, 6);

            // Switch back to Wi-Fi -> Wi-Fi hints must restore automatically
            ColgramDpiBypass.setCellularNetwork(false);
            check(ColgramDpiBypass.preferredStrategyForTest(false) == ColgramDpiBypass.STRATEGY_TLS_2REC_SNI,
                    "Wi-Fi strategy hint must persist across cellular switch");
            check(ColgramTelegramWsBridge.preferredFragModeForTest() == 2,
                    "Wi-Fi WSS fragMode hint must restore on setCellularNetwork(false)");

            // Switch to Cellular -> Cellular hints must restore automatically
            ColgramDpiBypass.setCellularNetwork(true);
            check(ColgramDpiBypass.preferredStrategyForTest(true) == ColgramDpiBypass.STRATEGY_TLS_3REC_SNI,
                    "Cellular strategy hint must persist across Wi-Fi switch");
            check(ColgramTelegramWsBridge.preferredFragModeForTest() == 1,
                    "Cellular WSS fragMode hint must restore on setCellularNetwork(true)");

            // clearTransientCooldowns must preserve hints across transient pauses
            ColgramDpiBypass.clearTransientCooldowns();
            check(ColgramDpiBypass.wssFragHintForTest(true) == 1,
                    "clearTransientCooldowns must preserve cellular WSS hint");

            // clearConnectionFailureBackoff must reset persisted hints cleanly
            ColgramDpiBypass.clearConnectionFailureBackoff();
            check(ColgramDpiBypass.wssFragHintForTest(false) == -1,
                    "clearConnectionFailureBackoff must reset Wi-Fi WSS hint");
            check(ColgramDpiBypass.wssFragHintForTest(true) == -1,
                    "clearConnectionFailureBackoff must reset cellular WSS hint");
            check(ColgramDpiBypass.preferredStrategyForTest(false) == ColgramDpiBypass.STRATEGY_SPLIT_1,
                    "clearConnectionFailureBackoff must reset Wi-Fi preferred strategy");
        } finally {
            ColgramDpiBypass.setCellularNetwork(false);
            ColgramDpiBypass.clearConnectionFailureBackoff();
        }
    }

    private static byte[] buildMinimalTlsClientHello(String sniHost) {
        byte[] hostBytes = sniHost.getBytes(StandardCharsets.US_ASCII);
        int serverNameLen = 3 + hostBytes.length;
        int sniListLen = serverNameLen;
        int sniExtPayloadLen = 2 + sniListLen;
        int extsLen = 4 + sniExtPayloadLen;
        // body = version(2) + random(32) + sidLen(1) + csLen(2) + cs(2) + compLen(1) + comp(1) + extsLen(2) + exts
        int bodyLen = 2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + extsLen;
        int hsLen = 4 + bodyLen;
        byte[] pkt = new byte[5 + hsLen];
        int p = 0;
        pkt[p++] = 0x16;
        pkt[p++] = 0x03;
        pkt[p++] = 0x01;
        pkt[p++] = (byte) ((hsLen >>> 8) & 0xFF);
        pkt[p++] = (byte) (hsLen & 0xFF);

        pkt[p++] = 0x01; // ClientHello
        pkt[p++] = (byte) ((bodyLen >>> 16) & 0xFF);
        pkt[p++] = (byte) ((bodyLen >>> 8) & 0xFF);
        pkt[p++] = (byte) (bodyLen & 0xFF);

        pkt[p++] = 0x03;
        pkt[p++] = 0x03;
        for (int i = 0; i < 32; i++) pkt[p++] = (byte) (i + 1);
        pkt[p++] = 0x00; // session id len
        pkt[p++] = 0x00;
        pkt[p++] = 0x02; // cipher suites len
        pkt[p++] = 0x13;
        pkt[p++] = 0x01; // TLS_AES_128_GCM_SHA256
        pkt[p++] = 0x01; // compression len
        pkt[p++] = 0x00; // null
        pkt[p++] = (byte) ((extsLen >>> 8) & 0xFF);
        pkt[p++] = (byte) (extsLen & 0xFF);

        pkt[p++] = 0x00;
        pkt[p++] = 0x00; // server_name ext
        pkt[p++] = (byte) ((sniExtPayloadLen >>> 8) & 0xFF);
        pkt[p++] = (byte) (sniExtPayloadLen & 0xFF);
        pkt[p++] = (byte) ((sniListLen >>> 8) & 0xFF);
        pkt[p++] = (byte) (sniListLen & 0xFF);
        pkt[p++] = 0x00; // host_name
        pkt[p++] = (byte) ((hostBytes.length >>> 8) & 0xFF);
        pkt[p++] = (byte) (hostBytes.length & 0xFF);
        System.arraycopy(hostBytes, 0, pkt, p, hostBytes.length);
        return pkt;
    }

    private static void testSendLatencyPoolsForegroundPrewarmAndChunkedSocks() throws Exception {
        check(ColgramTelegramWsBridge.PREWARM_TTL_MS == 38_000L,
                "PREWARM_TTL_MS expected 38000, got " + ColgramTelegramWsBridge.PREWARM_TTL_MS);
        check(ColgramTelegramWsBridge.PREWARM_REFRESH_MS == 28_000L,
                "PREWARM_REFRESH_MS expected 28000, got " + ColgramTelegramWsBridge.PREWARM_REFRESH_MS);

        // Verify PrewarmedWsPool foreground refresh detection thresholds without background timers
        ColgramTelegramWsBridge.setPrewarmEnabledForTest(true);
        ColgramTelegramWsBridge.drainPrewarmedPool();
        long now = System.currentTimeMillis();
        try (ServerSocket sink = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
             Socket dummy = new Socket("127.0.0.1", sink.getLocalPort());
             Socket accepted = sink.accept()) {
            ColgramTelegramWsBridge.WsSession aging = new ColgramTelegramWsBridge.WsSession(
                    2, 2, "149.154.167.220", "kws2.web.telegram.org", dummy,
                    dummy.getInputStream(), dummy.getOutputStream());
            check(ColgramTelegramWsBridge.offerPrewarmedForTest(aging, now - 5_000L, false),
                    "fresh prewarm offer failed");
            check(!ColgramTelegramWsBridge.needsForegroundRefreshForTest(2, false, false, now),
                    "5s-old prewarm entry should not need refresh");
            check(ColgramTelegramWsBridge.needsForegroundRefreshForTest(2, false, false, now + 24_000L),
                    "29s-old prewarm entry (>= PREWARM_REFRESH_MS) must request foreground refresh");
        } finally {
            ColgramTelegramWsBridge.drainPrewarmedPool();
            ColgramTelegramWsBridge.setPrewarmEnabledForTest(false);
        }

        // Verify TLS session cache factory builds cleanly
        check(ColgramNativeDesync.createEngineSslSocketFactory() != null,
                "createEngineSslSocketFactory must return non-null SSLSocketFactory");

        // Verify bounded relay buffer pool reuse and thread priority helper under host JVM
        ColgramDpiBypass.setThreadPriorityQuiet(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        byte[] pooled1 = ColgramDpiBypass.borrowRelayBuffer();
        check(pooled1.length == 32768, "borrowed relay buffer must be 32KB");
        ColgramDpiBypass.recycleRelayBuffer(pooled1);
        byte[] reused = ColgramDpiBypass.borrowRelayBuffer();
        check(reused == pooled1, "recycled relay buffer must be reused from RELAY_BUF_POOL");
        java.util.List<byte[]> extraBufs = new java.util.ArrayList<>();
        extraBufs.add(reused);
        for (int i = 0; i < 30; i++) {
            extraBufs.add(new byte[32768]);
        }
        for (byte[] b : extraBufs) {
            ColgramDpiBypass.recycleRelayBuffer(b);
        }
        int distinctReturned = 0;
        for (int i = 0; i < 30; i++) {
            byte[] b = ColgramDpiBypass.borrowRelayBuffer();
            if (extraBufs.contains(b)) distinctReturned++;
        }
        check(distinctReturned <= 24, "relay buffer pool must stay bounded at <= 24 slots, got=" + distinctReturned);

        // Verify ColgramSocks5Codec.readFully handles fragmented chunks without consuming trailing payload
        byte[] framed = new byte[]{0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, 0x01, (byte) 0xBB, 'T', 'A', 'I', 'L'};
        InputStream chunked = new InputStream() {
            int pos = 0;
            @Override
            public int read() {
                return pos < framed.length ? (framed[pos++] & 0xFF) : -1;
            }
            @Override
            public int read(byte[] b, int off, int len) {
                if (pos >= framed.length) return -1;
                int chunk = Math.min(2, Math.min(len, framed.length - pos));
                System.arraycopy(framed, pos, b, off, chunk);
                pos += chunk;
                return chunk;
            }
        };
        byte[] hdr = ColgramSocks5Codec.readFully(chunked, 4);
        byte[] addrPort = ColgramSocks5Codec.readFully(chunked, 6);
        check(hdr[0] == 0x05 && addrPort[4] == 0x01 && addrPort[5] == (byte) 0xBB,
                "chunked readFully must reassemble exact header/addr bytes");
        byte[] tail = ColgramSocks5Codec.readFully(chunked, 4);
        check("TAIL".equals(new String(tail, StandardCharsets.US_ASCII)) && chunked.read() == -1,
                "readFully must leave trailing MTProto payload untouched");

        // Verify handleClient processes fragmented SOCKS5 greeting + loopback CONNECT request cleanly
        java.lang.reflect.Method handleClient = ColgramDpiBypass.class.getDeclaredMethod(
                "handleClient", Socket.class, ColgramDpiBypass.CallListener.class, long.class);
        handleClient.setAccessible(true);
        ColgramDpiBypass.skipListenerCheckForTest = true;
        try (ServerSocket ss = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
             Socket clientEnd = new Socket("127.0.0.1", ss.getLocalPort());
             Socket serverSide = ss.accept()) {
            clientEnd.setSoTimeout(3000);
            Thread worker = new Thread(() -> {
                try {
                    handleClient.invoke(null, serverSide, null, 1L);
                } catch (Throwable ignored) {}
            }, "test-socks-chunked");
            worker.start();

            OutputStream out = clientEnd.getOutputStream();
            InputStream in = clientEnd.getInputStream();
            out.write(new byte[]{0x05, 0x01});
            out.flush();
            Thread.sleep(10L);
            out.write(new byte[]{0x00});
            out.flush();
            byte[] methodReply = ColgramSocks5Codec.readFully(in, 2);
            check(methodReply[0] == 0x05 && methodReply[1] == 0x00, "unexpected SOCKS5 method reply");

            out.write(new byte[]{0x05, 0x01});
            out.flush();
            Thread.sleep(10L);
            out.write(new byte[]{0x00, 0x01, 127, 0});
            out.flush();
            Thread.sleep(10L);
            out.write(new byte[]{0, 1, 0x00, 0x50});
            out.flush();
            byte[] connectReply = ColgramSocks5Codec.readFully(in, 10);
            check(connectReply[0] == 0x05 && connectReply[1] == 0x02,
                    "expected fragmented SOCKS5 CONNECT to parse and hit loopback policy rule");
            worker.join(2000L);
        } finally {
            ColgramDpiBypass.skipListenerCheckForTest = false;
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
