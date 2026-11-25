package org.colgram.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JVM regression guards for long-uptime stability fixes:
 * - Bounded DoH DNS cache capacity
 * - Bounded ColgramDpiBypass worker pool & system-wide client concurrency caps
 * - Persistent WebSocket upstream pump never sending RFC 6455 client PING (0x89)
 */
public final class ColgramStabilityGuardsTest {
    public static void main(String[] args) throws Exception {
        testDohCacheBoundedCapacity();
        testDpiBypassPoolAndClientCaps();
        testWsPumpNeverSendsClientPingOnIdle();
        testActiveSessionReaperAndOverflowEviction();
        testStickyTelegramL3RelayPreventsDualIpScatter();
        System.out.println("ColgramStabilityGuardsTest PASSED");
    }

    private static void testDohCacheBoundedCapacity() {
        ColgramDohResolver.clearDnsCacheForTest();
        try {
            long now = System.currentTimeMillis();
            for (int i = 0; i < 2500; i++) {
                ColgramDohResolver.putDnsCacheEntry("host-" + i + ".example", new String[]{"1.2.3.4"}, now + i);
            }
            int size = ColgramDohResolver.dnsCacheSizeForTest();
            assertTrue(size > 0 && size <= 2048,
                    "DoH cache must stay bounded <= 2048 entries, got " + size);
        } finally {
            ColgramDohResolver.clearDnsCacheForTest();
        }
    }

    private static void testDpiBypassPoolAndClientCaps() throws Exception {
        int maxThreads = ColgramDpiBypass.maxWorkerPoolThreadsForTest();
        int sysCap = ColgramDpiBypass.systemWideClientCapForTest();
        assertTrue(maxThreads > 0 && maxThreads <= 256,
                "workerPool max threads must be bounded <= 256, got " + maxThreads);
        assertTrue(sysCap > 0 && sysCap <= 192,
                "system-wide CallListener cap must be bounded <= 192, got " + sysCap);

        CountDownLatch latch = new CountDownLatch(1);
        assertTrue(ColgramDpiBypass.executeWorker(latch::countDown),
                "executeWorker should accept task under normal load");
        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "executeWorker task must execute within timeout");
    }

    private static void testWsPumpNeverSendsClientPingOnIdle() throws Exception {
        ByteArrayOutputStream wireIn = new ByteArrayOutputStream();
        // Binary frame: FIN=1, opcode=0x2, len=2 ("ok")
        wireIn.write(new byte[]{(byte) 0x82, 0x02, 'o', 'k'});
        // Server Ping frame: FIN=1, opcode=0x9, len=2 ("hi")
        wireIn.write(new byte[]{(byte) 0x89, 0x02, 'h', 'i'});

        final List<Integer> recordedTimeouts = new ArrayList<>();
        Socket recordingSocket = new Socket() {
            @Override
            public void setSoTimeout(int timeout) {
                recordedTimeouts.add(timeout);
            }
        };

        ByteArrayInputStream wsIn = new ByteArrayInputStream(wireIn.toByteArray());
        ByteArrayOutputStream wsOut = new ByteArrayOutputStream();
        ByteArrayOutputStream downstream = new ByteArrayOutputStream();
        ColgramTelegramWsBridge.WsSession session = new ColgramTelegramWsBridge.WsSession(
                2, "149.154.167.220", "kws2.web.telegram.org", recordingSocket, wsIn, wsOut);

        long bytes = ColgramTelegramWsBridge.pumpUpstreamFrames(session, downstream, null);
        assertTrue(bytes == 2L, "expected 2 downstream bytes, got " + bytes);
        assertTrue("ok".equals(new String(downstream.toByteArray(), StandardCharsets.US_ASCII)),
                "downstream payload mismatch");
        assertTrue(recordedTimeouts.contains(0), "pumpUpstreamFrames must call setSoTimeout(0)");
        for (int t : recordedTimeouts) {
            assertTrue(t == 0, "pumpUpstreamFrames must never arm positive idle SO_TIMEOUT, saw " + t);
        }

        byte[] outbound = wsOut.toByteArray();
        assertTrue(outbound.length == 8, "expected single 8-byte masked Pong frame, got " + outbound.length);
        assertTrue((outbound[0] & 0xFF) == 0x8A && (outbound[1] & 0xFF) == 0x82,
                "expected masked Pong opcode 0x8A len 2");
        byte[] decodedPong = new byte[]{
                (byte) (outbound[6] ^ outbound[2]),
                (byte) (outbound[7] ^ outbound[3])
        };
        assertTrue("hi".equals(new String(decodedPong, StandardCharsets.US_ASCII)),
                "masked Pong payload must echo server ping");
    }

    private static void testActiveSessionReaperAndOverflowEviction() throws Exception {
        ColgramDpiBypass.closeAllActiveSessions();
        try {
            final boolean[] closedFlags = new boolean[4];
            Socket c1 = new Socket();
            Socket c2 = new Socket();
            Socket c3 = new Socket();
            java.io.Closeable u1 = () -> closedFlags[0] = true;
            java.io.Closeable u2 = () -> closedFlags[1] = true;
            java.io.Closeable u3 = () -> closedFlags[2] = true;

            long t0 = 100_000L;
            ColgramDpiBypass.ActiveSession idleLive = ColgramDpiBypass.registerActiveSessionAt(c1, u1, true, t0);
            ColgramDpiBypass.ActiveSession halfOpen = ColgramDpiBypass.registerActiveSessionAt(c2, u2, true, t0);
            ColgramDpiBypass.ActiveSession activeTx = ColgramDpiBypass.registerActiveSessionAt(c3, u3, true, t0);

            // idleLive received data and has no unanswered writes -> MUST stay open during normal watchdog!
            idleLive.onUpstreamRx(t0 + 100L);
            // halfOpen wrote at t0 + 500ms and got zero bytes back for 9s -> MUST be reaped!
            halfOpen.onClientTx(t0 + 500L);
            // activeTx wrote at t0 + 7000ms (only 2.5s ago) -> MUST NOT be reaped at 8s threshold!
            activeTx.onClientTx(t0 + 7_000L);

            int reaped1 = ColgramDpiBypass.reapStaleSessionsAt(t0 + 9_500L, 8_000L, 0L);
            assertTrue(reaped1 == 1, "expected exactly 1 half-open session reaped, got " + reaped1);
            assertTrue(!closedFlags[0], "live idle session must not be closed during normal watchdog");
            assertTrue(closedFlags[1], "half-open session with 9s unanswered write must be closed");
            assertTrue(!closedFlags[2], "fresh pending-write session must stay open");

            // Wake-from-sleep idle sweep (maxIdleMs=12_000L) MUST reap NAT-expired pre-sleep idle sockets.
            int reaped2 = ColgramDpiBypass.reapStaleSessionsAt(t0 + 15_000L, 5_000L, 12_000L);
            assertTrue(reaped2 == 2, "wake sweep must reap both pre-sleep idle and stalled sockets, got " + reaped2);
            assertTrue(closedFlags[0] && closedFlags[2], "both remaining sessions must be closed on wake sweep");
            assertTrue(ColgramDpiBypass.activeSessionCountForTest() == 0, "registry must be empty after reap");

            // Overflow cap test: registering > MAX_ACTIVE_SESSIONS must keep active count bounded <= MAX_ACTIVE_SESSIONS.
            for (int i = 0; i < ColgramDpiBypass.MAX_ACTIVE_SESSIONS + 12; i++) {
                ColgramDpiBypass.ActiveSession s = ColgramDpiBypass.registerActiveSessionAt(
                        new Socket(), () -> {}, true, t0 + i);
                if (i < 5) s.onClientTx(t0 + i);
            }
            assertTrue(ColgramDpiBypass.activeSessionCountForTest() <= ColgramDpiBypass.MAX_ACTIVE_SESSIONS,
                    "active sessions must never exceed MAX_ACTIVE_SESSIONS=" + ColgramDpiBypass.MAX_ACTIVE_SESSIONS);
        } finally {
            ColgramDpiBypass.closeAllActiveSessions();
        }
    }

    private static void testStickyTelegramL3RelayPreventsDualIpScatter() throws Exception {
        ColgramDpiBypass.clearPinnedTelegramL3Relay();
        ColgramDpiBypass.clearConnectionFailureBackoff();
        java.net.ServerSocket mockRelayA = new java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
        java.net.ServerSocket mockRelayB = new java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
        final java.util.concurrent.atomic.AtomicInteger hitsA = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger hitsB = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);

        Runnable serveA = () -> serveMinimalSocks5(mockRelayA, hitsA, running);
        Runnable serveB = () -> serveMinimalSocks5(mockRelayB, hitsB, running);
        Thread tA = new Thread(serveA, "mock-socks-a");
        Thread tB = new Thread(serveB, "mock-socks-b");
        tA.setDaemon(true);
        tB.setDaemon(true);
        tA.start();
        tB.start();
        try {
            ColgramProxyChain.Relay rA = new ColgramProxyChain.Relay("127.0.0.1", mockRelayA.getLocalPort(), true, null, null);
            rA.rttMs = 15;
            ColgramProxyChain.Relay rB = new ColgramProxyChain.Relay("127.0.0.1", mockRelayB.getLocalPort(), true, null, null);
            rB.rttMs = 20;
            List<ColgramProxyChain.Relay> relays = new ArrayList<>();
            relays.add(rA);
            relays.add(rB);

            // First Telegram DC dial wins one relay and pins it.
            Socket s1 = ColgramDpiBypass.connectViaL3RelayCascade("149.154.167.50", 443, 2000, null, relays);
            assertTrue(s1 != null, "first Telegram L3 relay dial must succeed");
            String firstPinned = ColgramDpiBypass.pinnedTelegramL3RelayKeyForTest();
            assertTrue(!firstPinned.isEmpty(), "winning Telegram L3 relay must be pinned");
            s1.close();

            int aAfter1 = hitsA.get();
            int bAfter1 = hitsB.get();
            // Second Telegram DC dial MUST reuse the exact pinned exit relay (zero dual-IP scatter!).
            Socket s2 = ColgramDpiBypass.connectViaL3RelayCascade("149.154.167.51", 443, 2000, null, relays);
            assertTrue(s2 != null, "second Telegram L3 relay dial must succeed via pinned relay");
            assertTrue(firstPinned.equals(ColgramDpiBypass.pinnedTelegramL3RelayKeyForTest()),
                    "second Telegram dial must keep exact same pinned exit relay");
            if (firstPinned.endsWith(":" + mockRelayA.getLocalPort())) {
                assertTrue(hitsA.get() == aAfter1 + 1 && hitsB.get() == bAfter1,
                        "pinned relay A must handle second dial without hitting relay B");
                mockRelayA.close(); // Kill pinned relay A -> next dial must fail over cleanly to relay B!
            } else {
                assertTrue(hitsB.get() == bAfter1 + 1 && hitsA.get() == aAfter1,
                        "pinned relay B must handle second dial without hitting relay A");
                mockRelayB.close();
            }
            s2.close();

            Socket s3 = ColgramDpiBypass.connectViaL3RelayCascade("149.154.175.50", 443, 2000, null, relays);
            assertTrue(s3 != null, "failover to surviving L3 relay must succeed after pinned relay dies");
            String secondPinned = ColgramDpiBypass.pinnedTelegramL3RelayKeyForTest();
            assertTrue(!secondPinned.isEmpty() && !firstPinned.equals(secondPinned),
                    "surviving L3 relay must become the new pinned exit relay (first=" + firstPinned + ", second=" + secondPinned + ")");
            s3.close();
        } finally {
            running.set(false);
            try { mockRelayA.close(); } catch (Throwable ignored) {}
            try { mockRelayB.close(); } catch (Throwable ignored) {}
            ColgramDpiBypass.clearPinnedTelegramL3Relay();
            ColgramDpiBypass.clearConnectionFailureBackoff();
        }
    }

    private static void serveMinimalSocks5(java.net.ServerSocket ss,
                                           java.util.concurrent.atomic.AtomicInteger hits,
                                           java.util.concurrent.atomic.AtomicBoolean running) {
        while (running.get() && !ss.isClosed()) {
            try {
                Socket c = ss.accept();
                hits.incrementAndGet();
                Thread worker = new Thread(() -> {
                    try (Socket sock = c) {
                        java.io.InputStream in = sock.getInputStream();
                        java.io.OutputStream out = sock.getOutputStream();
                        int ver = in.read();
                        int nMethods = in.read();
                        if (ver != 5 || nMethods < 1) return;
                        for (int i = 0; i < nMethods; i++) in.read();
                        out.write(new byte[]{5, 0});
                        out.flush();
                        byte[] hdr = new byte[4];
                        for (int i = 0; i < 4; i++) {
                            int b = in.read();
                            if (b < 0) return;
                            hdr[i] = (byte) b;
                        }
                        int atyp = hdr[3] & 0xFF;
                        int addrLen = (atyp == 1) ? 4 : (atyp == 4 ? 16 : (in.read() & 0xFF));
                        for (int i = 0; i < addrLen + 2; i++) {
                            if (in.read() < 0) return;
                        }
                        out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                        out.flush();
                        while (in.read() != -1) {}
                    } catch (Throwable ignored) {}
                });
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable ignored) {
                break;
            }
        }
    }

    private static void assertTrue(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }
}
