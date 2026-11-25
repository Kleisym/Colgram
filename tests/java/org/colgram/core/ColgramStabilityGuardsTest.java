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

    private static void assertTrue(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }
}
