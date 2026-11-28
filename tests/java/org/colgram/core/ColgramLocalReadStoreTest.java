package org.colgram.core;

/**
 * Pure-JVM tests for persistent monotonic local Ghost-Read watermark store.
 */
public final class ColgramLocalReadStoreTest {

    public static void main(String[] args) {
        ColgramLocalReadStore.resetForTests();

        final long dmDialog = 123456789L;
        final long channelDialog = -1001987654321L;

        // 1. Invalid inputs are safely ignored without recording
        assertTrue(!ColgramLocalReadStore.recordLocalRead(null, 0, 0L, 100), "dialogId=0 rejected");
        assertTrue(!ColgramLocalReadStore.recordLocalRead(null, 0, dmDialog, 0), "maxId=0 rejected");
        assertTrue(!ColgramLocalReadStore.recordLocalRead(null, 0, dmDialog, -5), "negative maxId rejected");
        assertTrue(!ColgramLocalReadStore.recordLocalRead(null, 0, dmDialog, Integer.MAX_VALUE),
                "Integer.MAX_VALUE sentinel rejected");
        assertEquals(0, ColgramLocalReadStore.getLocalReadMax(0, dmDialog), "empty dialog watermark is 0");

        // 2. Monotonic MAX enforcement + account isolation
        assertTrue(ColgramLocalReadStore.recordLocalRead(null, 0, dmDialog, 500), "initial watermark recorded");
        assertEquals(500, ColgramLocalReadStore.getLocalReadMax(0, dmDialog), "watermark == 500");
        assertTrue(!ColgramLocalReadStore.recordLocalRead(null, 0, dmDialog, 450), "lower id ignored");
        assertTrue(!ColgramLocalReadStore.recordLocalRead(null, 0, dmDialog, 500), "equal id ignored");
        assertEquals(500, ColgramLocalReadStore.getLocalReadMax(0, dmDialog), "watermark stays 500");
        assertTrue(ColgramLocalReadStore.recordLocalRead(null, 0, dmDialog, 620), "higher id advances watermark");
        assertEquals(620, ColgramLocalReadStore.getLocalReadMax(0, dmDialog), "watermark == 620");

        assertTrue(ColgramLocalReadStore.recordLocalRead(null, 0, channelDialog, 9100), "negative dialogId supported");
        assertEquals(9100, ColgramLocalReadStore.getLocalReadMax(0, channelDialog), "channel watermark == 9100");
        assertEquals(0, ColgramLocalReadStore.getLocalReadMax(1, dmDialog), "account 1 isolated from account 0");

        // 3. Server sync catch-up pruning
        assertTrue(!ColgramLocalReadStore.clearIfCaughtUp(null, 0, dmDialog, 619),
                "stale server read_inbox_max (< 620) must NOT clear watermark");
        assertEquals(620, ColgramLocalReadStore.getLocalReadMax(0, dmDialog), "watermark still 620 after stale sync");
        assertTrue(ColgramLocalReadStore.clearIfCaughtUp(null, 0, dmDialog, 620),
                "server catch-up (>= 620) clears watermark");
        assertEquals(0, ColgramLocalReadStore.getLocalReadMax(0, dmDialog), "cleared after catch-up");

        // 4. Explicit mark-as-unread clearing
        ColgramLocalReadStore.recordLocalRead(null, 0, dmDialog, 700);
        ColgramLocalReadStore.clearDialog(null, 0, dmDialog);
        assertEquals(0, ColgramLocalReadStore.getLocalReadMax(0, dmDialog), "clearDialog removes watermark");
        assertEquals(9100, ColgramLocalReadStore.getLocalReadMax(0, channelDialog), "other dialog untouched");

        ColgramLocalReadStore.resetForTests();
        assertEquals(0, ColgramLocalReadStore.getLocalReadMax(0, channelDialog), "resetForTests clears all");
        System.out.println("ColgramLocalReadStoreTest: ALL PASSED");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) {
            throw new AssertionError(msg + ": expected=" + expected + " actual=" + actual);
        }
    }
}
