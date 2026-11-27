import org.colgram.core.ColgramLocalTranslator;
import org.colgram.core.ColgramSavedTagsStore;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-JVM tests for standalone Saved Messages tag cache, JSON round-trip export/import,
 * and ColgramLocalTranslator response parsing & chunk splitting without Android SQLite stubs.
 */
public final class ColgramSavedTagsStoreTest {

    public static void main(String[] args) {
        ColgramSavedTagsStore.resetForTests();

        AtomicInteger notifyCount = new AtomicInteger(0);
        ColgramSavedTagsStore.ChangeListener listener = acc -> notifyCount.incrementAndGet();
        ColgramSavedTagsStore.addListener(listener);

        // 1. Add, deduplicate, and count tags per account
        assertEquals(24, ColgramSavedTagsStore.DEFAULT_PRESETS.size(), "24 curated emoji presets");
        ColgramSavedTagsStore.addTag(null, 0, 101, "\uD83D\uDD25", "Срочно");
        ColgramSavedTagsStore.addTag(null, 0, 101, "\u2B50", "");
        ColgramSavedTagsStore.addTag(null, 0, 205, "\uD83D\uDD25", "");
        ColgramSavedTagsStore.addTag(null, 1, 101, "\u2B50", "Другой аккаунт");

        List<String> tags101 = ColgramSavedTagsStore.getTags(0, 101);
        assertEquals(2, tags101.size(), "account 0 msg 101 has 2 tags");
        assertTrue(ColgramSavedTagsStore.hasTag(0, 101, "\uD83D\uDD25"), "has fire tag");
        assertTrue(ColgramSavedTagsStore.hasTag(0, 101, "\u2B50"), "has star tag");
        assertEquals(2, ColgramSavedTagsStore.getTotalTaggedMessagesCount(0), "account 0 has 2 tagged messages");
        assertEquals(1, ColgramSavedTagsStore.getTotalTaggedMessagesCount(1), "account 1 isolated");

        List<Integer> fireIds = ColgramSavedTagsStore.getTaggedMessageIds(0, "\uD83D\uDD25");
        assertEquals(2, fireIds.size(), "2 messages match fire");
        assertEquals(205, fireIds.get(0), "newest msgId first (205 > 101)");
        assertEquals(101, fireIds.get(1), "older msgId second");

        // 2. Toggle removes existing tag cleanly
        boolean toggledOn = ColgramSavedTagsStore.toggleTag(null, 0, 101, "\u2B50", "");
        assertTrue(!toggledOn, "toggle existing returns false");
        assertTrue(!ColgramSavedTagsStore.hasTag(0, 101, "\u2B50"), "star removed");
        assertTrue(notifyCount.get() >= 4, "listener notified on mutations");

        // 3. Pure-Java JSON export/import round-trip (preserves quotes/newlines & clears target before import or merges cleanly)
        String exported = ColgramSavedTagsStore.exportJson(0);
        assertTrue(exported.contains("\"emoji\""), "exported json has emoji field");
        ColgramSavedTagsStore.resetForTests();
        assertEquals(0, ColgramSavedTagsStore.getTotalTaggedMessagesCount(0), "cleared after reset");

        int imported = ColgramSavedTagsStore.importJson(null, 0, exported);
        assertEquals(2, imported, "imported 2 tag rows into account 0");
        assertTrue(ColgramSavedTagsStore.hasTag(0, 101, "\uD83D\uDD25"), "fire restored on 101");
        assertTrue(ColgramSavedTagsStore.hasTag(0, 205, "\uD83D\uDD25"), "fire restored on 205");

        // 4. Local translator response parsing & chunk splitting
        String sampleGtx = "[[[\"Привет \",\"Hello \",null,null,10],[\"мир\\nТест \\\"OK\\\"\",\"world\\nTest \\\"OK\\\"\",null,null,10]],null,\"en\"]";
        String parsed = ColgramLocalTranslator.parseTranslateGtxResponse(sampleGtx);
        assertEquals("Привет мир\nТест \"OK\"", parsed, "gtx json response unescaped properly");

        StringBuilder longMsg = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            longMsg.append("Line ").append(i).append(" sample translation paragraph with enough characters.\n");
        }
        List<String> chunks = ColgramLocalTranslator.splitChunks(longMsg.toString(), 600);
        assertTrue(chunks.size() >= 4, "long message split into multiple <=600 chunks");
        StringBuilder rejoined = new StringBuilder();
        for (String c : chunks) {
            assertTrue(c.length() <= 600, "each chunk respects max limit");
            rejoined.append(c);
        }
        assertEquals(longMsg.toString(), rejoined.toString(), "split chunks rejoin losslessly");

        ColgramSavedTagsStore.removeListener(listener);
        ColgramSavedTagsStore.resetForTests();
        System.out.println("ColgramSavedTagsStoreTest: ALL PASSED");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + ": expected=" + expected + " actual=" + actual);
    }

    private static void assertEquals(String expected, String actual, String msg) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(msg + ": expected=[" + expected + "] actual=[" + actual + "]");
        }
    }
}
