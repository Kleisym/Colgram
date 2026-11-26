package org.colgram.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JVM regression tests for network switch recovery, epoch isolation, dialing socket abort,
 * and silent blocked-route notice behavior.
 */
public final class ColgramNetworkSwitchAndNoticeTest {
    public static void main(String[] args) throws Exception {
        testCloseAllActiveSessionsAbortsTrackedDialSocketsAndBumpsEpoch();
        testStaleEpochFailuresNeverPoisonNewNetworkStrategies();
        testBypassNoticeShowBlockedRemainsSilent();
        System.out.println("ColgramNetworkSwitchAndNoticeTest PASSED");
    }

    private static void testCloseAllActiveSessionsAbortsTrackedDialSocketsAndBumpsEpoch() throws Exception {
        ColgramDpiBypass.closeAllActiveSessions();
        long epochBefore = ColgramDpiBypass.currentNetworkEpoch();

        final AtomicBoolean closedOnAbort = new AtomicBoolean(false);
        final CountDownLatch dialEntered = new CountDownLatch(1);
        final CountDownLatch dialExited = new CountDownLatch(1);
        Socket stallingDial = new Socket() {
            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                dialEntered.countDown();
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (isClosed()) {
                    throw new IOException("aborted on network change");
                }
            }
        };

        Thread dialThread = new Thread(() -> {
            try {
                ColgramDpiBypass.connectTracked(stallingDial, new InetSocketAddress("127.0.0.1", 1), 10_000);
            } catch (IOException expected) {
                closedOnAbort.set(stallingDial.isClosed());
            } finally {
                dialExited.countDown();
            }
        }, "test-dial-abort");
        dialThread.setDaemon(true);
        dialThread.start();

        assertTrue(dialEntered.await(2, TimeUnit.SECONDS), "dial should enter connectTracked");
        assertTrue(ColgramDpiBypass.activeDialSocketCountForTest() >= 1,
                "in-flight dial socket must be tracked before closeAllActiveSessions()");

        // Also register an established ActiveSession so both collections are swept together.
        Socket s1 = new Socket();
        Socket s2 = new Socket();
        ColgramDpiBypass.registerActiveSessionAt(s1, s2, true, 1_000L);
        try {
            int closed = ColgramDpiBypass.closeAllActiveSessions();
            assertTrue(closed >= 2, "expected both ActiveSession and tracked dial socket closed, got " + closed);
            assertTrue(stallingDial.isClosed(), "tracked dial socket must be closed immediately");
            assertTrue(s1.isClosed() && s2.isClosed(), "active session sockets must be closed");
            assertTrue(ColgramDpiBypass.activeSessionCountForTest() == 0,
                    "ACTIVE_SESSIONS must be empty after closeAllActiveSessions()");
            assertTrue(ColgramDpiBypass.activeDialSocketCountForTest() == 0,
                    "ACTIVE_DIAL_SOCKETS must be empty after closeAllActiveSessions()");
            assertTrue(ColgramDpiBypass.currentNetworkEpoch() > epochBefore,
                    "closeAllActiveSessions() must increment networkEpoch");
        } finally {
            try { s1.close(); } catch (IOException ignored) {}
            try { s2.close(); } catch (IOException ignored) {}
            dialThread.interrupt();
            assertTrue(dialExited.await(2, TimeUnit.SECONDS), "dial thread should exit cleanly");
            assertTrue(closedOnAbort.get(), "aborted dial thread should observe closed socket");
        }
    }

    private static void testStaleEpochFailuresNeverPoisonNewNetworkStrategies() {
        ColgramDpiBypass.resetStrategyStateForTest(false, ColgramDpiBypass.STRATEGY_SPLIT_1);
        ColgramDpiBypass.setL3RawMtprotoSilentStreakForTest(4);

        long oldEpoch = ColgramDpiBypass.currentNetworkEpoch();
        ColgramDpiBypass.clearConnectionFailureBackoff();
        long newEpoch = ColgramDpiBypass.currentNetworkEpoch();

        assertTrue(newEpoch > oldEpoch, "clearConnectionFailureBackoff() must increment networkEpoch");
        assertTrue(ColgramDpiBypass.l3RawMtprotoSilentStreakForTest() == 0,
                "clearConnectionFailureBackoff() must reset l3RawMtprotoSilentStreak");

        int[] ladder = new int[]{
                ColgramDpiBypass.STRATEGY_SPLIT_1,
                ColgramDpiBypass.STRATEGY_NATIVE_DISORDER_TLSREC
        };
        // Stale epoch must not mutate strategyFails (even for current OR non-current strategy),
        // currentStrategy/dcBackoffUntil, or hostStrategies.
        for (int i = 0; i < 6; i++) {
            ColgramDpiBypass.reportStrategyResultForEpochForTest(
                    ColgramDpiBypass.STRATEGY_SPLIT_1, false, 2, ladder, oldEpoch);
            ColgramDpiBypass.reportStrategyResultForEpochForTest(
                    ColgramDpiBypass.STRATEGY_NATIVE_DISORDER_TLSREC, false, 2, ladder, oldEpoch);
            ColgramDpiBypass.reportHostStrategyResult(
                    "stale.example.com", ColgramDpiBypass.STRATEGY_SPLIT_1, false, false, oldEpoch);
        }

        assertTrue(ColgramDpiBypass.strategyFailCountForTest(ColgramDpiBypass.STRATEGY_SPLIT_1) == 0
                        && ColgramDpiBypass.strategyFailCountForTest(ColgramDpiBypass.STRATEGY_NATIVE_DISORDER_TLSREC) == 0,
                "stale epoch failures must not increment strategyFails on new network");
        assertTrue(ColgramDpiBypass.dcBackoffUntilForTest(2) == 0L,
                "stale epoch failures must not trigger dcBackoffUntil on new network");
        assertTrue(ColgramDpiBypass.hostStrategyCountForTest() == 0,
                "stale epoch failures must not populate hostStrategies on new network");

        // Verify current-epoch reports still update normally.
        ColgramDpiBypass.reportStrategyResultForEpochForTest(
                ColgramDpiBypass.STRATEGY_NATIVE_DISORDER_TLSREC, false, 2, ladder, newEpoch);
        assertTrue(ColgramDpiBypass.strategyFailCountForTest(ColgramDpiBypass.STRATEGY_NATIVE_DISORDER_TLSREC) == 1,
                "current epoch failure must update strategyFails");
        ColgramDpiBypass.reportHostStrategyResult(
                "fresh.example.com", ColgramDpiBypass.STRATEGY_SPLIT_1, false, false, newEpoch);
        assertTrue(ColgramDpiBypass.hostStrategyCountForTest() == 1,
                "current epoch host result must update hostStrategies");
    }

    private static void testBypassNoticeShowBlockedRemainsSilent() {
        ColgramBypassNotice.showBlocked(null, "SNI/DPI test");
        assertTrue(!ColgramBypassNotice.isShowingForTest(),
                "ColgramBypassNotice.showBlocked must stay silent (showing == false)");
        ColgramBypassNotice.clear(null);
        assertTrue(!ColgramBypassNotice.isShowingForTest(),
                "ColgramBypassNotice.clear must keep showing == false");
    }

    private static void assertTrue(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }
}
