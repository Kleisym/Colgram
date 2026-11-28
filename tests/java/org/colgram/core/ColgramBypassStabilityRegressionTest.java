package org.colgram.core;

import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JVM regression suite for Track A bypass stability, session eviction ordering,
 * RX freshness tracking, prewarm pool resets, and build flag gating.
 */
public final class ColgramBypassStabilityRegressionTest {

    public static void main(String[] args) throws Exception {
        testBuildFlagAndNullContextSystemVpnGuard();
        testReversedOverflowEvictionProtectsFreshPendingSessions();
        testHasRecentRxWithinTracksAndResetsCleanly();
        testDialRacePoolIsolatedAndResponsive();
        testPrewarmPoolDrainAndBucketEvict();
        testLoopbackGuardTelemetryAndStealthContracts();
        testPipeOverWebSocketFailoverSynchronization();
        testActiveUploadProtectedFromReaperAndSetUpstreamClosesHandoff();
        testWsSessionCloseClosesRawSocketBeforeSlowSslSocket();
        testPipeOverWebSocketClientCancelDoesNotPoisonEdgeOrPool();
        testPipeStandardOrWsSessionClientCancelDoesNotPoisonRawDcOrStrategy();
        testSilentEndpointTtlExpiry();
        testDefaultEdgeDeprioritizeTtlCapped();
        testRestartIfDeadClearsStoppedExplicitlyAndMainDcRx();
        testColgramPinnedConnectionContract();
        testSendBinaryLargeSingleFrameAndPacketLimit();
        testForcePatchDcOverridesCollidingObfuscated2Header();
        testWritingUpstreamProtectsUploadWithStaleLastTxUntilHardCap();
        testMediaLastWorkingEndpointReusedWithoutDashOneHost();
        testDc135AndWifiMediaWsFastPathDecision();
        System.out.println("ColgramBypassStabilityRegressionTest PASSED");
    }

    private static void testBuildFlagAndNullContextSystemVpnGuard() throws Exception {
        check(ColgramBuildFlags.KOLITI_ENABLED || !ColgramBuildFlags.KOLITI_ENABLED,
                "ColgramBuildFlags.KOLITI_ENABLED must be accessible");
        java.lang.reflect.Method hasSysVpn =
                ColgramProxyManager.class.getDeclaredMethod("hasSystemVpn", android.content.Context.class);
        hasSysVpn.setAccessible(true);
        boolean res = (Boolean) hasSysVpn.invoke(null, new Object[]{null});
        check(!res, "hasSystemVpn(null) must return false instead of blinding watchdog");
    }

    private static long safeElapsedOrMillis() {
        try {
            return android.os.SystemClock.elapsedRealtime();
        } catch (Throwable ignored) {
            return System.currentTimeMillis();
        }
    }

    private static void testReversedOverflowEvictionProtectsFreshPendingSessions() throws Exception {
        ColgramDpiBypass.closeAllActiveSessions();
        List<Socket> socketsToClose = new ArrayList<>();
        try {
            long baseMs = safeElapsedOrMillis();
            int cap = ColgramDpiBypass.MAX_ACTIVE_SESSIONS;

            // Fill cap - 3 with fresh active pending sessions (waiting < 5s for server reply).
            List<ColgramDpiBypass.ActiveSession> freshPending = new ArrayList<>();
            for (int i = 0; i < cap - 3; i++) {
                Socket c = new Socket();
                Socket u = new Socket();
                socketsToClose.add(c);
                socketsToClose.add(u);
                ColgramDpiBypass.ActiveSession s =
                        ColgramDpiBypass.registerActiveSessionAt(c, u, true, baseMs + i);
                s.onClientTx(baseMs);
                freshPending.add(s);
            }

            // Add 1 dead (closed client), 1 stale pending (>20s old), and 1 idle session.
            Socket deadClient = new Socket();
            deadClient.close();
            Socket deadUp = new Socket();
            socketsToClose.add(deadUp);
            ColgramDpiBypass.ActiveSession deadSess =
                    ColgramDpiBypass.registerActiveSessionAt(deadClient, deadUp, true, baseMs + 10_000L);

            Socket staleClient = new Socket();
            Socket staleUp = new Socket();
            socketsToClose.add(staleClient);
            socketsToClose.add(staleUp);
            ColgramDpiBypass.ActiveSession staleSess =
                    ColgramDpiBypass.registerActiveSessionAt(staleClient, staleUp, true, baseMs);
            staleSess.onClientTx(1L); // Much older than 15s relative to safeElapsedRealtime()

            Socket idleClient = new Socket();
            Socket idleUp = new Socket();
            socketsToClose.add(idleClient);
            socketsToClose.add(idleUp);
            ColgramDpiBypass.ActiveSession idleSess =
                    ColgramDpiBypass.registerActiveSessionAt(idleClient, idleUp, true, baseMs);

            check(ColgramDpiBypass.activeSessionCountForTest() == cap, "expected exact cap before overflow");

            // Register 3 more fresh active sessions -> dead, stale, and idle MUST be evicted before any fresh pending!
            for (int k = 0; k < 3; k++) {
                Socket oc = new Socket();
                Socket ou = new Socket();
                socketsToClose.add(oc);
                socketsToClose.add(ou);
                ColgramDpiBypass.ActiveSession extra =
                        ColgramDpiBypass.registerActiveSessionAt(oc, ou, true, baseMs + 20_000L + k);
                extra.onClientTx(baseMs + 20_000L + k);
            }

            check(ColgramDpiBypass.activeSessionCountForTest() <= cap,
                    "active sessions must remain capped <= MAX_ACTIVE_SESSIONS");
            check(staleClient.isClosed(), "stale pending session must be evicted before fresh pending");
            check(idleClient.isClosed(), "idle session must be evicted before fresh pending");
            for (int i = 0; i < freshPending.size(); i++) {
                check(!freshPending.get(i).client.isClosed(),
                        "fresh active pending session #" + i + " must survive overflow eviction");
            }
            check(deadSess.upstream != null, "dead session object valid after close");
        } finally {
            ColgramDpiBypass.closeAllActiveSessions();
            for (Socket s : socketsToClose) {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void testHasRecentRxWithinTracksAndResetsCleanly() throws Exception {
        ColgramDpiBypass.closeAllActiveSessions();
        ColgramDpiBypass.resetCarriageCounters();
        check(!ColgramDpiBypass.hasRecentRxWithin(180_000L),
                "hasRecentRxWithin must be false right after reset");

        Socket c = new Socket();
        Socket u = new Socket();
        try {
            long now = safeElapsedOrMillis();
            ColgramDpiBypass.ActiveSession sess =
                    ColgramDpiBypass.registerActiveSessionAt(c, u, true, now);
            sess.onUpstreamRx(now);
            check(ColgramDpiBypass.hasRecentRxWithin(180_000L),
                    "hasRecentRxWithin must turn true after onUpstreamRx");

            sess.onUpstreamRx(Math.max(1L, now - 250_000L));
            check(!ColgramDpiBypass.hasRecentRxWithin(180_000L),
                    "hasRecentRxWithin must turn false when latest RX is older than window");

            sess.onUpstreamRx(now);
            ColgramDpiBypass.closeAllActiveSessions();
            check(!ColgramDpiBypass.hasRecentRxWithin(180_000L),
                    "closeAllActiveSessions must clear recent RX timestamp");
        } finally {
            ColgramDpiBypass.closeAllActiveSessions();
            try { c.close(); } catch (Throwable ignored) {}
            try { u.close(); } catch (Throwable ignored) {}
        }
    }

    private static void testDialRacePoolIsolatedAndResponsive() throws Exception {
        check(ColgramDpiBypass.maxWorkerPoolThreadsForTest() == 384, "workerPool cap expected 384");
        check(ColgramDpiBypass.MAX_ACTIVE_SESSIONS == 160, "MAX_ACTIVE_SESSIONS expected 160");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean(false);
        boolean submitted = ColgramDpiBypass.executeDialRace(() -> {
            ran.set(true);
            latch.countDown();
        });
        check(submitted, "executeDialRace must accept tasks");
        check(latch.await(2, TimeUnit.SECONDS) && ran.get(), "executeDialRace task must execute promptly");
    }

    private static void testPrewarmPoolDrainAndBucketEvict() throws Exception {
        ColgramTelegramWsBridge.setPrewarmEnabledForTest(false);
        ColgramTelegramWsBridge.resetNetworkState();
        try {
            ColgramTelegramWsBridge.WsSession dummy = new ColgramTelegramWsBridge.WsSession(
                    2, 2, "127.0.0.1", "max.ru", null, null, null);
            check(!dummy.fromPrewarm, "freshly constructed WsSession must default fromPrewarm=false");
            ColgramTelegramWsBridge.evictPrewarmedBucket(2);
            int drained = ColgramTelegramWsBridge.drainPrewarmedPool();
            check(drained >= 0, "drainPrewarmedPool must return non-negative count");
        } finally {
            ColgramTelegramWsBridge.setPrewarmEnabledForTest(true);
        }
    }

    private static void testLoopbackGuardTelemetryAndStealthContracts() {
        check(ColgramDpiBypass.isBlockedLoopbackTarget("127.0.0.1", 9876), "127.0.0.1:9876 blocked");
        check(ColgramDpiBypass.isBlockedLoopbackTarget("::1", 443), "::1 blocked");
        check(ColgramDpiBypass.isBlockedLoopbackTarget("localhost", 80), "localhost blocked");
        check(ColgramDpiBypass.isBlockedLoopbackTarget("0.0.0.0", 443), "0.0.0.0 blocked");
        check(!ColgramDpiBypass.isBlockedLoopbackTarget("149.154.167.220", 443), "public DC IP allowed");

        check(ColgramNativeDesync.MODE_COMPACT_4_RECORD == 13, "MODE_COMPACT_4_RECORD must be 13");
        check(ColgramNativeDesync.MODE_HDR2_SNI2_BULK == 14, "MODE_HDR2_SNI2_BULK must be 14");

        String modeLabel = ColgramDpiBypass.getLiveModeLabel();
        check(modeLabel != null && !modeLabel.isEmpty(), "getLiveModeLabel non-empty");
        check(ColgramDpiBypass.getActiveSessionCount() >= 0, "getActiveSessionCount non-negative");

        String wsSummary = ColgramTelegramWsBridge.getLiveTelemetrySummary();
        check(wsSummary != null && !wsSummary.isEmpty(), "getLiveTelemetrySummary non-empty");
        check(ColgramTelegramWsBridge.getPrewarmedTotalCount() >= 0, "getPrewarmedTotalCount non-negative");
        ColgramTelegramWsBridge.getLastHandshakeRttMs();

        List<ColgramTelegramWsBridge.EdgeEndpoint> eps = ColgramTelegramWsBridge.buildCandidateEndpoints(
                2, null, java.util.Collections.emptyMap(), java.util.Collections.emptySet(), false);
        for (ColgramTelegramWsBridge.EdgeEndpoint ep : eps) {
            check(!"149.154.167.99".equals(ep.ip), "dead .99 edge IP must remain retired: " + ep);
        }

        check("googlevideo.com".equals(ColgramDpiBypass.baseDomainKey("rr3---sn-test.googlevideo.com")),
                "baseDomainKey collapses YouTube subdomain");
        check(ColgramPolymorphicStealth.isMultiTenantSharedDomain("d111111abcdef8.cloudfront.net"),
                "cloudfront classified as multi-tenant CDN");
        ColgramDpiBypass.reportHostStrategyResult("d111111abcdef8.cloudfront.net",
                ColgramDpiBypass.STRATEGY_SPLIT_1, true, false);
        check(!ColgramDpiBypass.isHostStrategyConfirmed("d111111abcdef8.cloudfront.net", false),
                "multi-tenant CDN must not pollute hostStrategies");
        check(ColgramDpiBypass.STRATEGY_NATIVE_COMPACT_4_RECORD == 14,
                "STRATEGY_NATIVE_COMPACT_4_RECORD must be 14");
        check(ColgramDpiBypass.STRATEGY_NATIVE_HDR2_SNI2_BULK == 15,
                "STRATEGY_NATIVE_HDR2_SNI2_BULK must be 15");
    }

    private static byte[] buildTestObfuscated2Header() throws Exception {
        byte[] sInit = new byte[64];
        new java.util.Random(42).nextBytes(sInit);
        byte[] sKey = java.util.Arrays.copyOfRange(sInit, 8, 40);
        byte[] sIv = java.util.Arrays.copyOfRange(sInit, 40, 56);
        byte[] rawSeed = sInit.clone();
        rawSeed[56] = (byte) 0xef;
        rawSeed[57] = (byte) 0xef;
        rawSeed[58] = (byte) 0xef;
        rawSeed[59] = (byte) 0xef;
        rawSeed[60] = 2;
        rawSeed[61] = 0;
        javax.crypto.Cipher enc = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding");
        enc.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(sKey, "AES"),
                new javax.crypto.spec.IvParameterSpec(sIv));
        byte[] hdrCipher = enc.update(rawSeed);
        System.arraycopy(hdrCipher, 56, sInit, 56, 8);
        return sInit;
    }

    private static byte[] buildUnmaskedWsBinaryFrame(byte[] payload) {
        byte[] frame = new byte[2 + payload.length];
        frame[0] = (byte) 0x82;
        frame[1] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, 2, payload.length);
        return frame;
    }

    private static void testPipeOverWebSocketFailoverSynchronization() throws Exception {
        java.lang.reflect.Method pipeWs = ColgramDpiBypass.class.getDeclaredMethod(
                "pipeOverWebSocket",
                Socket.class,
                ColgramTelegramWsBridge.WsSession.class,
                ColgramDpiBypass.CallListener.class,
                long.class,
                boolean.class);
        pipeWs.setAccessible(true);

        byte[] initHdr = buildTestObfuscated2Header();
        byte[] expectedReply = new byte[]{
                0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88,
                (byte) 0x99, (byte) 0xaa, (byte) 0xbb, (byte) 0xcc, 0x01, 0x02, 0x03, 0x04
        };
        byte[] wsReplyFrame = buildUnmaskedWsBinaryFrame(expectedReply);

        try {
            ColgramDpiBypass.skipListenerCheckForTest = true;

            // Case 1: Dead initial prewarm fails on C2S write while S2C immediately sees EOF;
            // S2C must wait for the 120ms redial instead of closing client prematurely.
            runPipeFailoverScenario(pipeWs, initHdr, expectedReply, wsReplyFrame, true, false);

            // Case 2: Dead initial prewarm sends EOF on S2C 50ms BEFORE client finishes sending 64B init;
            // S2C must wait on initPhasePending and fail over cleanly once init arrives.
            runPipeFailoverScenario(pipeWs, initHdr, expectedReply, wsReplyFrame, false, true);
        } finally {
            ColgramDpiBypass.skipListenerCheckForTest = false;
            ColgramTelegramWsBridge.dialFreshOverrideForTest = null;
        }
    }

    private static void runPipeFailoverScenario(java.lang.reflect.Method pipeWs,
                                                byte[] initHdr,
                                                byte[] expectedReply,
                                                byte[] wsReplyFrame,
                                                boolean failC2sWrite,
                                                boolean delayClientInit) throws Exception {
        java.util.concurrent.atomic.AtomicInteger dialCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger freshOutWrites = new java.util.concurrent.atomic.AtomicInteger(0);

        ColgramTelegramWsBridge.dialFreshOverrideForTest = (signedDc, prot, fdProt) -> {
            dialCalls.incrementAndGet();
            try {
                Thread.sleep(120L);
            } catch (InterruptedException ignored) {}
            java.io.OutputStream freshOut = new java.io.OutputStream() {
                @Override
                public void write(int b) {}

                @Override
                public void write(byte[] b, int off, int len) {
                    freshOutWrites.incrementAndGet();
                }
            };
            return new ColgramTelegramWsBridge.WsSession(
                    Math.abs(signedDc), signedDc, "149.154.167.220", "kws2.web.telegram.org",
                    new Socket(), new java.io.ByteArrayInputStream(wsReplyFrame), freshOut);
        };

        java.io.OutputStream deadOut = new java.io.OutputStream() {
            @Override
            public void write(int b) throws java.io.IOException {
                if (failC2sWrite) throw new java.io.IOException("simulated dead prewarm socket");
            }

            @Override
            public void write(byte[] b, int off, int len) throws java.io.IOException {
                if (failC2sWrite) throw new java.io.IOException("simulated dead prewarm socket");
            }
        };
        ColgramTelegramWsBridge.WsSession staleSession = new ColgramTelegramWsBridge.WsSession(
                2, 2, "149.154.167.220", "kws2.web.telegram.org",
                new Socket(), new java.io.ByteArrayInputStream(new byte[0]), deadOut);
        staleSession.fromPrewarm = true;

        try (java.net.ServerSocket listener = new java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress());
             Socket clientEnd = new Socket("127.0.0.1", listener.getLocalPort());
             Socket proxySide = listener.accept()) {
            clientEnd.setSoTimeout(5000);
            pipeWs.invoke(null, proxySide, staleSession, null, 1L, false);

            if (delayClientInit) {
                Thread.sleep(50L);
            }
            java.io.OutputStream cOut = clientEnd.getOutputStream();
            cOut.write(initHdr);
            cOut.flush();

            java.io.InputStream cIn = clientEnd.getInputStream();
            byte[] got = new byte[expectedReply.length];
            int total = 0;
            while (total < got.length) {
                int r = cIn.read(got, total, got.length - total);
                if (r < 0) break;
                total += r;
            }
            check(total == expectedReply.length && java.util.Arrays.equals(got, expectedReply),
                    "client must survive concurrent C2S/S2C failover and receive fresh WSS reply (got="
                            + total + ", failC2s=" + failC2sWrite + ", delayInit=" + delayClientInit + ")");
            check(dialCalls.get() == 1, "exactly one fresh redial expected, got " + dialCalls.get());
            check(freshOutWrites.get() >= 1, "fresh WSS session must receive replayed init packet");
        }
    }

    private static void testActiveUploadProtectedFromReaperAndSetUpstreamClosesHandoff() throws Exception {
        ColgramDpiBypass.closeAllActiveSessions();
        List<Socket> toClose = new ArrayList<>();
        try {
            Socket client = new Socket();
            Socket rawUp = new Socket();
            Socket wsUp = new Socket();
            toClose.add(client);
            toClose.add(rawUp);
            toClose.add(wsUp);

            ColgramDpiBypass.ActiveSession uploadSess =
                    ColgramDpiBypass.registerActiveSessionAt(client, rawUp, true, 10_000L);
            for (long t = 11_000L; t <= 35_000L; t += 1_000L) {
                uploadSess.onClientTx(t);
            }
            int reapedActive = ColgramDpiBypass.reapStaleSessionsAt(36_000L, 12_000L, 0L);
            check(reapedActive == 0, "actively transmitting upload must NOT be reaped after 25s without reply, got=" + reapedActive);
            check(!client.isClosed() && !rawUp.isClosed(), "active upload sockets must remain open");

            // Simulate WS/L3 handoff replacing upstream, then continuing TX past 60s without any reply
            uploadSess.setUpstream(wsUp);
            for (long t = 36_000L; t <= 74_000L; t += 2_000L) {
                uploadSess.onClientTx(t);
            }
            int reapedHardCap = ColgramDpiBypass.reapStaleSessionsAt(75_000L, 12_000L, 0L);
            check(reapedHardCap == 1, "upload stalled >60s with zero server RX must still be reaped");
            check(wsUp.isClosed() && client.isClosed(), "handoff upstream and client must close on reap");
        } finally {
            ColgramDpiBypass.closeAllActiveSessions();
            for (Socket s : toClose) {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void testWsSessionCloseClosesRawSocketBeforeSlowSslSocket() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger closeSeq = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger rawOrder = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger sslOrder = new java.util.concurrent.atomic.AtomicInteger(0);
        final AtomicBoolean rawClosed = new AtomicBoolean(false);
        Socket rawSock = new Socket() {
            @Override
            public synchronized void close() {
                rawOrder.compareAndSet(0, closeSeq.incrementAndGet());
                rawClosed.set(true);
            }
        };
        Socket slowSslSock = new Socket() {
            @Override
            public synchronized void close() {
                try {
                    Thread.sleep(60L);
                } catch (InterruptedException ignored) {}
                sslOrder.compareAndSet(0, closeSeq.incrementAndGet());
            }
        };
        ColgramTelegramWsBridge.WsSession session = new ColgramTelegramWsBridge.WsSession(
                2, 2, "149.154.167.220", "kws2.web.telegram.org",
                slowSslSock, rawSock,
                new java.io.ByteArrayInputStream(new byte[0]),
                new java.io.ByteArrayOutputStream());
        session.close();
        check(rawClosed.get(), "rawSocket must be closed by WsSession.close()");
        check(rawOrder.get() == 1 && sslOrder.get() == 2,
                "rawSocket must close BEFORE sslSocket so blocking SSL close_notify cannot stall cancel (raw="
                        + rawOrder.get() + ", ssl=" + sslOrder.get() + ")");
    }

    private static void testPipeOverWebSocketClientCancelDoesNotPoisonEdgeOrPool() throws Exception {
        java.lang.reflect.Method pipeWs = ColgramDpiBypass.class.getDeclaredMethod(
                "pipeOverWebSocket",
                Socket.class,
                ColgramTelegramWsBridge.WsSession.class,
                ColgramDpiBypass.CallListener.class,
                long.class,
                boolean.class);
        pipeWs.setAccessible(true);

        java.util.concurrent.atomic.AtomicInteger dialCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        ColgramTelegramWsBridge.setPrewarmEnabledForTest(true);
        ColgramTelegramWsBridge.drainPrewarmedPool();
        ColgramTelegramWsBridge.clearCooldowns();
        ColgramDpiBypass.skipListenerCheckForTest = true;
        ColgramTelegramWsBridge.dialFreshOverrideForTest = (signedDc, prot, fdProt) -> {
            dialCalls.incrementAndGet();
            return null;
        };

        String edgeIp = "149.154.167.220";
        String sni = "kws2-1.web.telegram.org";
        ColgramTelegramWsBridge.setLastWorkingForTest(-2, edgeIp, sni);

        try (java.net.ServerSocket edgeServer = new java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress());
             java.net.ServerSocket clientListener = new java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress());
             Socket prewarmDummy = new Socket("127.0.0.1", edgeServer.getLocalPort());
             Socket prewarmAccepted = edgeServer.accept();
             Socket edgeClient = new Socket("127.0.0.1", edgeServer.getLocalPort());
             Socket edgeAccepted = edgeServer.accept();
             Socket clientEnd = new Socket("127.0.0.1", clientListener.getLocalPort());
             Socket proxySide = clientListener.accept()) {
            ColgramTelegramWsBridge.WsSession pooled = new ColgramTelegramWsBridge.WsSession(
                    2, -2, edgeIp, sni, prewarmDummy,
                    prewarmDummy.getInputStream(), prewarmDummy.getOutputStream());
            edgeAccepted.setSoTimeout(5000);
            check(ColgramTelegramWsBridge.offerPrewarmedForTest(pooled, System.currentTimeMillis(), false),
                    "expected media prewarm offer to succeed");
            check(ColgramTelegramWsBridge.prewarmedPoolSizeForTest(2, true) == 1,
                    "expected DC2 media prewarm bucket size 1");

            ColgramTelegramWsBridge.WsSession activeSess = new ColgramTelegramWsBridge.WsSession(
                    2, -2, edgeIp, sni, edgeClient, edgeClient,
                    edgeClient.getInputStream(), edgeClient.getOutputStream());
            activeSess.fromPrewarm = true;

            pipeWs.invoke(null, proxySide, activeSess, null, 1L, false);

            java.io.OutputStream cOut = clientEnd.getOutputStream();
            cOut.write(buildTestObfuscated2Header());
            cOut.write(new byte[256]);
            cOut.flush();

            // Wait until upstream WS server receives the initial frame before client aborts upload
            byte[] drainBuf = new byte[512];
            int got = edgeAccepted.getInputStream().read(drainBuf);
            check(got > 0, "expected upstream WS server to receive upload frame");

            long abortStartMs = System.currentTimeMillis();
            clientEnd.setSoLinger(true, 0);
            clientEnd.close();

            long deadline = System.currentTimeMillis() + 1500L;
            while (!edgeClient.isClosed() && System.currentTimeMillis() < deadline) {
                Thread.sleep(5L);
            }
            long closeMs = System.currentTimeMillis() - abortStartMs;
            Thread.sleep(40L);

            check(edgeClient.isClosed(), "client cancel must close active upload WsSession");
            check(closeMs < 1000L, "client cancel cleanup took too long: " + closeMs + "ms");
            check(dialCalls.get() == 0, "client cancel must NOT trigger prewarm redial, got=" + dialCalls.get());
            check(!ColgramTelegramWsBridge.isDeprioritizedForTest(edgeIp, sni),
                    "client cancel must NOT deprioritize working WSS edge");
            check((edgeIp + "|" + sni).equals(ColgramTelegramWsBridge.lastWorkingForTest(-2)),
                    "client cancel must NOT clear lastWorkingByDc");
            check(ColgramTelegramWsBridge.prewarmedPoolSizeForTest(2, true) == 1,
                    "client cancel must NOT evict DC2 media prewarm pool");
        } finally {
            ColgramDpiBypass.skipListenerCheckForTest = false;
            ColgramTelegramWsBridge.dialFreshOverrideForTest = null;
            ColgramTelegramWsBridge.drainPrewarmedPool();
            ColgramTelegramWsBridge.clearCooldowns();
            ColgramTelegramWsBridge.setPrewarmEnabledForTest(false);
        }
    }

    private static void testPipeStandardOrWsSessionClientCancelDoesNotPoisonRawDcOrStrategy() throws Exception {
        java.lang.reflect.Method pipeStd = ColgramDpiBypass.class.getDeclaredMethod(
                "pipeStandardOrWsSession",
                Socket.class,
                Socket.class,
                ColgramDpiBypass.CallListener.class,
                long.class,
                boolean.class,
                String.class,
                int.class,
                boolean.class,
                byte[].class,
                boolean.class,
                String.class,
                boolean.class,
                long.class);
        pipeStd.setAccessible(true);

        ColgramDpiBypass.closeAllActiveSessions();
        ColgramDpiBypass.resetStrategyStateForTest(false, ColgramDpiBypass.STRATEGY_TLS_2REC_SNI);
        ColgramDpiBypass.clearSilentEndpointsForTest();
        ColgramDpiBypass.skipListenerCheckForTest = true;

        try (java.net.ServerSocket dcServer = new java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress());
             java.net.ServerSocket clientListener = new java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress())) {
            int dcPort = dcServer.getLocalPort();
            long epoch = ColgramDpiBypass.currentNetworkEpochForTest();
            byte[] initHdr = buildTestObfuscated2Header();

            for (int attempt = 0; attempt < 2; attempt++) {
                try (Socket destEnd = new Socket("127.0.0.1", dcPort);
                     Socket dcAccepted = dcServer.accept();
                     Socket clientEnd = new Socket("127.0.0.1", clientListener.getLocalPort());
                     Socket proxySide = clientListener.accept()) {
                    dcAccepted.setSoTimeout(5000);
                    final Throwable[] pipeErr = new Throwable[1];
                    Thread pipeThread = new Thread(() -> {
                        try {
                            pipeStd.invoke(null, proxySide, destEnd, null, 1L, true,
                                    "127.0.0.1", dcPort, false, initHdr, false, "dc2-upload-cancel", true, epoch);
                        } catch (Throwable t) {
                            pipeErr[0] = t;
                        }
                    }, "test-pipe-std-cancel");
                    pipeThread.start();

                    // Wait for desynced firstPayload to arrive at DC without sending any server reply
                    byte[] rx = new byte[256];
                    int n = dcAccepted.getInputStream().read(rx);
                    check(n > 0, "expected upload init payload at DC server");

                    clientEnd.getOutputStream().write(new byte[128]);
                    clientEnd.getOutputStream().flush();
                    Thread.sleep(20L);

                    clientEnd.setSoLinger(true, 0);
                    clientEnd.close();

                    pipeThread.join(3000L);
                    check(!pipeThread.isAlive(), "pipeStandardOrWsSession must finish promptly on client cancel");
                    if (pipeErr[0] != null) throw new AssertionError("pipeStandardOrWsSession failed", pipeErr[0]);
                    long deadline = System.currentTimeMillis() + 1500L;
                    while ((!destEnd.isClosed() || ColgramDpiBypass.activeSessionCountForTest() > 0)
                            && System.currentTimeMillis() < deadline) {
                        Thread.sleep(5L);
                    }
                    Thread.sleep(30L);
                    check(destEnd.isClosed(), "dest socket must close promptly on client cancel");
                }
            }

            check(!ColgramDpiBypass.isEndpointSilentForTest("127.0.0.1", dcPort),
                    "repeated client upload cancel must NOT add raw DC endpoint to silentEndpoints");
            check(ColgramDpiBypass.strategyFailCountForTest(ColgramDpiBypass.STRATEGY_TLS_2REC_SNI) == 0,
                    "client upload cancel must NOT increment strategy fail count");
        } finally {
            ColgramDpiBypass.skipListenerCheckForTest = false;
            ColgramDpiBypass.clearSilentEndpointsForTest();
            ColgramDpiBypass.closeAllActiveSessions();
        }
    }

    @SuppressWarnings("unchecked")
    private static void testSilentEndpointTtlExpiry() throws Exception {
        ColgramDpiBypass.clearSilentEndpointsForTest();
        try {
            java.lang.reflect.Field ttlField = ColgramDpiBypass.class.getDeclaredField("SILENT_ENDPOINT_TTL_MS");
            ttlField.setAccessible(true);
            check(ttlField.getLong(null) == 30_000L, "SILENT_ENDPOINT_TTL_MS must be 30_000L");

            java.lang.reflect.Field mapField = ColgramDpiBypass.class.getDeclaredField("silentEndpoints");
            mapField.setAccessible(true);
            java.util.Map<String, Long> silentMap = (java.util.Map<String, Long>) mapField.get(null);

            silentMap.put("127.0.0.1:443", System.currentTimeMillis() - 10L);
            check(!ColgramDpiBypass.isEndpointSilentForTest("127.0.0.1", 443),
                    "expired silent endpoint must clear immediately on lookup");
            check(!silentMap.containsKey("127.0.0.1:443"),
                    "expired silent endpoint entry must be removed from map");

            silentMap.put("127.0.0.1:443", System.currentTimeMillis() + 30_000L);
            check(ColgramDpiBypass.isEndpointSilentForTest("127.0.0.1", 443),
                    "unexpired silent endpoint must remain active");
        } finally {
            ColgramDpiBypass.clearSilentEndpointsForTest();
        }
    }

    @SuppressWarnings("unchecked")
    private static void testDefaultEdgeDeprioritizeTtlCapped() throws Exception {
        ColgramTelegramWsBridge.clearCooldowns();
        try {
            java.lang.reflect.Field deprioField = ColgramTelegramWsBridge.class.getDeclaredField("deprioritizedUntil");
            deprioField.setAccessible(true);
            java.util.Map<String, Long> deprioMap = (java.util.Map<String, Long>) deprioField.get(null);

            long beforePrimary = System.currentTimeMillis();
            ColgramTelegramWsBridge.WsSession primary = new ColgramTelegramWsBridge.WsSession(
                    2, "149.154.167.220", "kws2.web.telegram.org", null, null, null);
            ColgramTelegramWsBridge.deprioritizeEndpoint(primary);
            Long primaryUntil = deprioMap.get("149.154.167.220|kws2.web.telegram.org");
            check(primaryUntil != null, "primary .220 endpoint should record deprioritize deadline");
            long primaryTtl = primaryUntil - beforePrimary;
            check(primaryTtl > 0L && primaryTtl <= 8_500L,
                    "primary .220 deprioritize TTL must be capped <= 8s, got=" + primaryTtl);

            long beforeSecondary = System.currentTimeMillis();
            ColgramTelegramWsBridge.WsSession secondary = new ColgramTelegramWsBridge.WsSession(
                    2, "149.154.167.221", "kws2.web.telegram.org", null, null, null);
            ColgramTelegramWsBridge.deprioritizeEndpoint(secondary);
            Long secondaryUntil = deprioMap.get("149.154.167.221|kws2.web.telegram.org");
            check(secondaryUntil != null && (secondaryUntil - beforeSecondary) > 8_500L,
                    "non-primary endpoint should retain longer deprioritize TTL");

            java.lang.reflect.Field dc135CdField = ColgramTelegramWsBridge.class.getDeclaredField("DC135_DEAD_COOLDOWN_MS");
            dc135CdField.setAccessible(true);
            check(dc135CdField.getLong(null) == 2_000L, "DC135_DEAD_COOLDOWN_MS must be 2_000L");

            java.lang.reflect.Field dc135MaxField = ColgramTelegramWsBridge.class.getDeclaredField("DC135_MAX_BACKOFF_MS");
            dc135MaxField.setAccessible(true);
            check(dc135MaxField.getLong(null) == 4_000L, "DC135_MAX_BACKOFF_MS must be 4_000L");

            java.lang.reflect.Field dc135WifiField = ColgramTelegramWsBridge.class.getDeclaredField("DC135_WIFI_CONNECT_TIMEOUT_MS");
            dc135WifiField.setAccessible(true);
            check(dc135WifiField.getInt(null) == 700, "DC135_WIFI_CONNECT_TIMEOUT_MS must be 700");

            java.lang.reflect.Field dc135CellField = ColgramTelegramWsBridge.class.getDeclaredField("DC135_CELLULAR_CONNECT_TIMEOUT_MS");
            dc135CellField.setAccessible(true);
            check(dc135CellField.getInt(null) == 1000, "DC135_CELLULAR_CONNECT_TIMEOUT_MS must be 1000");
        } finally {
            ColgramTelegramWsBridge.clearCooldowns();
        }
    }

    private static void testRestartIfDeadClearsStoppedExplicitlyAndMainDcRx() throws Exception {
        ColgramDpiBypass.stop();
        try {
            java.lang.reflect.Method stoppedMethod = ColgramDpiBypass.class.getDeclaredMethod("wasExplicitlyStopped");
            stoppedMethod.setAccessible(true);
            check((Boolean) stoppedMethod.invoke(null), "stop() must set wasExplicitlyStopped=true");
            check(!ColgramDpiBypass.isBound(), "stop() must unbind listener");

            check(ColgramDpiBypass.restartIfDeadAndWait(5000L), "restartIfDeadAndWait() must rebind cleanly");
            check(ColgramDpiBypass.isBound(), "listener must be bound after restartIfDeadAndWait");
            check(!(Boolean) stoppedMethod.invoke(null),
                    "restartIfDead()/startNow() must clear stoppedExplicitly=false");

            ColgramDpiBypass.resetCarriageCounters();
            check(!ColgramDpiBypass.hasRecentMainDcRxWithin(10_000L),
                    "resetCarriageCounters must clear main-DC RX timestamp");
            Socket mediaClient = new Socket();
            ColgramDpiBypass.ActiveSession mediaSession =
                    ColgramDpiBypass.registerActiveSessionAt(mediaClient, null, true, false, safeElapsedOrMillis());
            try {
                mediaSession.onUpstreamRx(safeElapsedOrMillis());
                check(!ColgramDpiBypass.hasRecentMainDcRxWithin(10_000L),
                        "media-DC RX must NOT reset main-DC RX freshness");
                check(ColgramDpiBypass.hasRecentRxWithin(10_000L),
                        "media-DC RX still updates general RX freshness");

                Socket mainClient = new Socket();
                ColgramDpiBypass.ActiveSession mainSession =
                        ColgramDpiBypass.registerActiveSessionAt(mainClient, null, true, true, safeElapsedOrMillis());
                try {
                    mainSession.onUpstreamRx(safeElapsedOrMillis());
                    check(ColgramDpiBypass.hasRecentMainDcRxWithin(10_000L),
                            "main-DC RX must update main-DC RX freshness");
                } finally {
                    ColgramDpiBypass.unregisterActiveSession(mainSession);
                }
            } finally {
                ColgramDpiBypass.unregisterActiveSession(mediaSession);
            }
        } finally {
            ColgramDpiBypass.resetCarriageCounters();
            ColgramDpiBypass.stop();
        }
    }

    private static void testColgramPinnedConnectionContract() throws Exception {
        java.net.URL target = new java.net.URL("https://example.com/translate");
        javax.net.ssl.HttpsURLConnection orig =
                (javax.net.ssl.HttpsURLConnection) target.openConnection(java.net.Proxy.NO_PROXY);

        javax.net.ssl.HttpsURLConnection passthrough =
                ColgramPinnedConnection.create(orig, target, null, java.net.Proxy.NO_PROXY);
        check(passthrough == orig,
                "null address + NO_PROXY must return original connection unchanged (no 0.0.0.0:443)");

        java.net.InetAddress loopback = java.net.InetAddress.getByName("127.0.0.1");
        javax.net.ssl.HttpsURLConnection pinned =
                ColgramPinnedConnection.create(orig, target, loopback, java.net.Proxy.NO_PROXY);
        check(pinned != orig, "direct non-null address must return pinned HttpsURLConnection");
        check("127.0.0.1".equals(pinned.getURL().getHost()),
                "direct pin must target resolved IP on Android OkHttp path, got=" + pinned.getURL().getHost());
        String hostProp = pinned.getRequestProperty("Host");
        check(hostProp == null || "example.com".equals(hostProp),
                "direct pin Host header (when not filtered by desktop JDK) must equal original host, got=" + hostProp);

        java.net.Proxy socks = new java.net.Proxy(
                java.net.Proxy.Type.SOCKS, new java.net.InetSocketAddress("127.0.0.1", 1080));
        javax.net.ssl.HttpsURLConnection socksOrig =
                (javax.net.ssl.HttpsURLConnection) target.openConnection(socks);
        javax.net.ssl.HttpsURLConnection socksPinned =
                ColgramPinnedConnection.create(socksOrig, target, null, socks);
        check(socksPinned != socksOrig, "SOCKS proxy with null address must wrap SSF/verifier without error");
        check("example.com".equals(socksPinned.getURL().getHost()),
                "SOCKS pin must keep domain host intact for remote proxy resolution");
    }

    private static void verifySingleUnfragmentedWsBinaryFrame(int payloadLen) throws Exception {
        java.io.ByteArrayOutputStream wireOut = new java.io.ByteArrayOutputStream();
        ColgramTelegramWsBridge.WsSession session = new ColgramTelegramWsBridge.WsSession(
                2, -2, "149.154.167.220", "kws2-1.web.telegram.org", null,
                new java.io.ByteArrayInputStream(new byte[0]), wireOut);
        byte[] payload = new byte[payloadLen];
        for (int i = 0; i < payloadLen; i++) {
            payload[i] = (byte) ((i * 31 + 7) & 0xff);
        }
        session.sendBinary(payload, 0, payloadLen);
        byte[] wire = wireOut.toByteArray();
        check(wire.length == 14 + payloadLen,
                "single unfragmented RFC 6455 frame length mismatch: got=" + wire.length + " expected=" + (14 + payloadLen));
        check((wire[0] & 0xff) == 0x82,
                "first byte must be 0x82 (FIN=1, opcode=0x2), got=0x" + Integer.toHexString(wire[0] & 0xff));
        check((wire[1] & 0xff) == (0x80 | 127),
                "second byte must be MASK=1 + len=127, got=0x" + Integer.toHexString(wire[1] & 0xff));
        long encodedLen = 0L;
        for (int i = 2; i < 10; i++) {
            encodedLen = (encodedLen << 8) | (wire[i] & 0xffL);
        }
        check(encodedLen == payloadLen, "64-bit extended length mismatch: got=" + encodedLen + " expected=" + payloadLen);
        for (int i = 0; i < payloadLen; i++) {
            byte unmasked = (byte) (wire[14 + i] ^ wire[10 + (i & 3)]);
            if (unmasked != payload[i]) {
                throw new AssertionError("payload corruption at offset " + i + " for len=" + payloadLen);
            }
        }
        // Subsequent small packet shrinks oversized writeScratch back to 8 KB
        session.sendBinary(new byte[]{1, 2, 3, 4}, 0, 4);
        java.lang.reflect.Field scratchField = ColgramTelegramWsBridge.WsSession.class.getDeclaredField("writeScratch");
        scratchField.setAccessible(true);
        byte[] scratch = (byte[]) scratchField.get(session);
        check(scratch.length <= 8192, "writeScratch must shrink back to <= 8192 after small packet, got=" + scratch.length);
    }

    private static void testSendBinaryLargeSingleFrameAndPacketLimit() throws Exception {
        verifySingleUnfragmentedWsBinaryFrame(131_124);
        verifySingleUnfragmentedWsBinaryFrame(524_340);

        java.io.ByteArrayOutputStream wireOut = new java.io.ByteArrayOutputStream();
        ColgramTelegramWsBridge.WsSession session = new ColgramTelegramWsBridge.WsSession(
                2, -2, "149.154.167.220", "kws2-1.web.telegram.org", null,
                new java.io.ByteArrayInputStream(new byte[0]), wireOut);
        boolean threw = false;
        try {
            session.sendBinary(new byte[1], 0, ColgramTelegramWsBridge.MAX_PACKET_BYTES + 1);
        } catch (java.io.IOException expected) {
            threw = true;
        }
        check(threw, "sendBinary must reject len > MAX_PACKET_BYTES");
    }

    private static byte[] buildObfuscated2HeaderWithDc(short rawDcInHeader, byte protoByte) throws Exception {
        byte[] hdr = new byte[64];
        for (int i = 8; i < 56; i++) {
            hdr[i] = (byte) ((i * 19 + 3) & 0xff);
        }
        byte[] key = java.util.Arrays.copyOfRange(hdr, 8, 40);
        byte[] iv = java.util.Arrays.copyOfRange(hdr, 40, 56);
        javax.crypto.Cipher ctr = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding");
        ctr.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.IvParameterSpec(iv));
        byte[] ks = ctr.update(new byte[64]);
        for (int i = 56; i < 60; i++) {
            hdr[i] = (byte) ((protoByte & 0xff) ^ (ks[i] & 0xff));
        }
        hdr[60] = (byte) ((rawDcInHeader & 0xff) ^ (ks[60] & 0xff));
        hdr[61] = (byte) (((rawDcInHeader >> 8) & 0xff) ^ (ks[61] & 0xff));
        return hdr;
    }

    private static void testForcePatchDcOverridesCollidingObfuscated2Header() throws Exception {
        // Simulate local tgnet random [60..61] bytes accidentally matching valid DC 4 instead of target media DC -2
        byte[] initKeep = buildObfuscated2HeaderWithDc((short) 4, (byte) 0xef);
        ColgramTelegramWsBridge.Obfuscated2FrameSplitter sKeep =
                ColgramTelegramWsBridge.Obfuscated2FrameSplitter.create(initKeep, 64, -2, false);
        check(sKeep != null && sKeep.rawDcId() == 4
                        && ColgramTelegramWsBridge.extractRawDcFromObfuscated2(initKeep, 64) == 4,
                "forcePatchDc=false must preserve existing valid DC 4");

        byte[] initPatch = buildObfuscated2HeaderWithDc((short) 4, (byte) 0xef);
        ColgramTelegramWsBridge.Obfuscated2FrameSplitter sForce =
                ColgramTelegramWsBridge.Obfuscated2FrameSplitter.create(initPatch, 64, -2, true);
        check(sForce != null && sForce.rawDcId() == -2
                        && ColgramTelegramWsBridge.extractRawDcFromObfuscated2(initPatch, 64) == -2,
                "forcePatchDc=true must unconditionally overwrite [60..61] with target signed DC -2");
    }

    private static void testWritingUpstreamProtectsUploadWithStaleLastTxUntilHardCap() throws Exception {
        ColgramDpiBypass.closeAllActiveSessions();
        List<Socket> toClose = new ArrayList<>();
        try {
            Socket client = new Socket();
            Socket upstream = new Socket();
            toClose.add(client);
            toClose.add(upstream);

            ColgramDpiBypass.ActiveSession s =
                    ColgramDpiBypass.registerActiveSessionAt(client, upstream, true, false, 10_000L);
            s.onClientTx(10_000L);
            s.writingUpstream = true;

            // Even with lastTxAtMs 35s stale (> 12s maxPendingReplyMs), writingUpstream protects up to 60s
            int reapedMid = ColgramDpiBypass.reapStaleSessionsAt(45_000L, 12_000L, 0L);
            check(reapedMid == 0, "writingUpstream=true must protect live upload before 60s cap, got=" + reapedMid);
            check(!client.isClosed() && !upstream.isClosed(), "protected upload sockets must remain open");

            // At 60s unreplied hard ceiling, reaper MUST still reclaim stuck upload
            int reapedCap = ColgramDpiBypass.reapStaleSessionsAt(70_000L, 12_000L, 0L);
            check(reapedCap == 1, "writingUpstream=true must still reap at 60s hard ceiling, got=" + reapedCap);
            check(client.isClosed() && upstream.isClosed(), "reaped upload sockets must close at 60s cap");
        } finally {
            ColgramDpiBypass.closeAllActiveSessions();
            for (Socket sock : toClose) {
                try { sock.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void testMediaLastWorkingEndpointReusedWithoutDashOneHost() {
        ColgramTelegramWsBridge.EdgeEndpoint proven =
                new ColgramTelegramWsBridge.EdgeEndpoint("149.154.167.220", "kws2.web.telegram.org");
        List<ColgramTelegramWsBridge.EdgeEndpoint> cands =
                ColgramTelegramWsBridge.buildCandidateEndpoints(
                        2, proven, java.util.Collections.emptyMap(), java.util.Collections.emptySet(), true, false);
        check(!cands.isEmpty() && proven.key().equals(cands.get(0).key()),
                "proven working media lastWorking must be reused even when SNI host omits -1.");
    }

    private static void testDc135AndWifiMediaWsFastPathDecision() {
        // DC1/DC3/DC5 tries raw desync once before dcRawFailed > 0
        check(!ColgramDpiBypass.shouldSkipRawForWs(false, true, true, false, false, false, true, 0, 0, false),
                "DC1/3/5 initial attempt with rawFailed=0 should allow raw desync probe first");
        // Once raw fails without reply on working network or cellular, DC1/3/5 switches to WSS fast-path
        check(ColgramDpiBypass.shouldSkipDcBackoffSleep(true, true, true, false, false, 1, 0, false),
                "DC1/3/5 after rawFailed>0 on working WS network must skip DC backoff sleep");
        check(ColgramDpiBypass.shouldSkipRawForWs(false, true, true, false, false, false, true, 1, 0, false),
                "DC1/3/5 after rawFailed>0 on working WS network must switch to WSS fast-path");
        check(ColgramDpiBypass.shouldSkipDcBackoffSleep(true, true, false, false, false, 1, 0, true),
                "DC1/3/5 after rawFailed>0 on cellular must skip DC backoff sleep");
        check(ColgramDpiBypass.shouldSkipRawForWs(false, true, true, false, false, false, false, 1, 0, true),
                "DC1/3/5 after rawFailed>0 on cellular must switch to WSS fast-path");
        // Wi-Fi media DCs (DC2/DC4) fast-path onto WSS once network WSS has worked & 0 raw replies
        check(ColgramDpiBypass.shouldSkipDcBackoffSleep(true, false, true, false, false, 0, 0, false),
                "Wi-Fi media DC must skip DC backoff sleep once WSS has worked");
        check(ColgramDpiBypass.shouldSkipRawForWs(false, true, false, false, false, false, true, 0, 0, false),
                "Wi-Fi media DC must use WSS fast-path once WSS has worked and rawReplied==0");
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
