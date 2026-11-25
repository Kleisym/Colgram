package org.colgram.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JVM regression guards for long-uptime stability fixes:
 * - Bounded DoH DNS cache capacity
 * - Bounded ColgramDpiBypass worker pool & system-wide client concurrency caps
 */
public final class ColgramStabilityGuardsTest {
    public static void main(String[] args) throws Exception {
        testDohCacheBoundedCapacity();
        testDpiBypassPoolAndClientCaps();
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

    private static void assertTrue(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }
}
