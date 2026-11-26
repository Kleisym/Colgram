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

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
