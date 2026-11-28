import org.colgram.core.ColgramLocalTranslator;
import org.colgram.core.ColgramSavedTagsStore;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-JVM tests for Saved Messages tag store v2 (Unicode + full doc: keys,
 * topic_id filtering, non-auto-seeded labels, atomic setMessageTags, pruneDeletedMessages,
 * JSON round-trip) and ColgramLocalTranslator response parsing & chunk splitting.
 */
public final class ColgramSavedTagsStoreTest {

    public static void main(String[] args) {
        ColgramSavedTagsStore.resetForTests();

        AtomicInteger notifyCount = new AtomicInteger(0);
        ColgramSavedTagsStore.ChangeListener listener = acc -> notifyCount.incrementAndGet();
        ColgramSavedTagsStore.addListener(listener);

        // 1. Add, deduplicate, and verify no Russian default labels are auto-seeded onto unlabeled tags
        assertEquals(24, ColgramSavedTagsStore.DEFAULT_PRESETS.size(), "24 curated emoji presets");
        ColgramSavedTagsStore.addTag(null, 0, 101, "\uD83D\uDD25", "Срочно");
        ColgramSavedTagsStore.addTag(null, 0, 101, "\u2B50", "");
        ColgramSavedTagsStore.addTag(null, 0, 205, "\uD83D\uDD25", "");
        ColgramSavedTagsStore.addTag(null, 1, 101, "\u2B50", "Другой аккаунт");

        assertEquals("", ColgramSavedTagsStore.getTagLabel(0, "\u2B50"),
                "unlabeled preset emoji must NOT auto-seed Russian default label");
        assertEquals("", ColgramSavedTagsStore.getLabelForEmoji(0, "\u2B50"),
                "getLabelForEmoji must match getTagLabel");
        assertEquals("Срочно", ColgramSavedTagsStore.getTagLabel(0, "\uD83D\uDD25"),
                "explicit non-empty label preserved");

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

        // 3. Long custom emoji doc:<id> keys (must not truncate at 16 chars) & topicId scoping & setTagLabel
        String docKey = "doc:5384123456789012345";
        assertEquals(docKey, ColgramSavedTagsStore.normalizeEmojiKey(" " + docKey + " "),
                "doc key preserved untruncated");
        ColgramSavedTagsStore.setMessageTags(null, 0, 309, -1001999888777L,
                Arrays.asList(docKey, "\u2B50", docKey));
        List<String> tags309 = ColgramSavedTagsStore.getTags(0, 309);
        assertEquals(2, tags309.size(), "setMessageTags deduplicates keys atomically");
        assertEquals(docKey, tags309.get(0), "full custom emoji key retained");
        assertEquals(1, ColgramSavedTagsStore.getTagCounts(0, -1001999888777L).stream()
                .filter(c -> c.emoji.equals(docKey)).findFirst().get().count, "topic-filtered count works");
        assertEquals(Collections.singletonList(309),
                ColgramSavedTagsStore.getTaggedMessageIds(0, -1001999888777L, docKey),
                "topic-filtered tagged IDs include negative Saved peer topicId");

        ColgramSavedTagsStore.setTagLabel(null, 0, docKey, "Важный стикер");
        assertEquals("Важный стикер", ColgramSavedTagsStore.getTagLabel(0, docKey), "setTagLabel sets custom title");
        ColgramSavedTagsStore.setTagLabel(null, 0, docKey, "");
        assertEquals("", ColgramSavedTagsStore.getTagLabel(0, docKey), "empty setTagLabel clears custom title");

        // 4. Prune deleted messages cleans stale tags
        ColgramSavedTagsStore.pruneDeletedMessages(null, 0, Collections.singletonList(309));
        assertTrue(!ColgramSavedTagsStore.hasTag(0, 309, docKey), "pruned deleted msg removes its tags");

        // 5. Pure-Java JSON export/import round-trip (preserving topicId, doc keys, and explicit labels)
        ColgramSavedTagsStore.setMessageTags(null, 0, 412, -1001555444333L, Collections.singletonList(docKey));
        ColgramSavedTagsStore.setTagLabel(null, 0, docKey, "Документ");
        String exported = ColgramSavedTagsStore.exportJson(0);
        assertTrue(exported.contains("\"emoji\""), "exported json has emoji field");
        assertTrue(exported.contains(docKey), "exported json retains full doc: key");
        ColgramSavedTagsStore.resetForTests();
        assertEquals(0, ColgramSavedTagsStore.getTotalTaggedMessagesCount(0), "cleared after reset");

        int imported = ColgramSavedTagsStore.importJson(null, 0, exported);
        assertEquals(3, imported, "imported 3 tag rows into account 0");
        assertTrue(ColgramSavedTagsStore.hasTag(0, 101, "\uD83D\uDD25"), "fire restored on 101");
        assertTrue(ColgramSavedTagsStore.hasTag(0, 205, "\uD83D\uDD25"), "fire restored on 205");
        assertTrue(ColgramSavedTagsStore.hasTag(0, 412, docKey), "doc key restored on 412");
        assertEquals("Документ", ColgramSavedTagsStore.getTagLabel(0, docKey), "custom label restored on import");
        assertEquals(Collections.singletonList(412),
                ColgramSavedTagsStore.getTaggedMessageIds(0, -1001555444333L, docKey),
                "topicId restored on import");

        // 6. Local translator response parsing & chunk splitting
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

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(msg + ": expected=[" + expected + "] actual=[" + actual + "]");
        }
    }
}
