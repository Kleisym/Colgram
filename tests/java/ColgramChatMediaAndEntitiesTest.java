import org.colgram.core.ColgramAiChatStore;
import org.colgram.core.ColgramDatabase;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ColgramChatMediaAndEntitiesTest {
    private static int checks;

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        File tmpDir = Files.createTempDirectory("colgram-chat-media-test").toFile();
        try {
            ColgramAiChatStore.resetForTest();
            ColgramAiChatStore.init(tmpDir);

            ColgramAiChatStore.ChatSession session = ColgramAiChatStore.createNewSession("Фото-тест", 12345L, "Test");
            List<String> paths = Arrays.asList("/data/koliti_media/a.jpg", "/data/koliti_media/b.mp4");
            ColgramAiChatStore.appendMessage(session.id, "user", "Что на фото?", "", paths);
            ColgramAiChatStore.appendMessage(session.id, "user", "", "", Collections.singletonList("/data/koliti_media/only_img.png"));
            ColgramAiChatStore.appendMessage(session.id, "assistant", "Готово", "Koliti");

            // Force reload from JSON on disk
            ColgramAiChatStore.resetForTest();
            ColgramAiChatStore.init(tmpDir);
            List<ColgramAiChatStore.ChatSession> reloaded = ColgramAiChatStore.listSessions();
            require(reloaded.size() == 1, "expected 1 persisted session, got " + reloaded.size());
            List<ColgramAiChatStore.ChatMessage> msgs = reloaded.get(0).messages;
            require(msgs.size() == 3, "expected 3 messages including media-only user bubble, got " + msgs.size());
            require("Что на фото?".equals(msgs.get(0).text), "first message text mismatch");
            require(msgs.get(0).mediaPaths.size() == 2, "expected 2 media paths on first message");
            require("/data/koliti_media/a.jpg".equals(msgs.get(0).mediaPaths.get(0)), "first media path mismatch");
            require("/data/koliti_media/b.mp4".equals(msgs.get(0).mediaPaths.get(1)), "second media path mismatch");
            require("".equals(msgs.get(1).text), "media-only message text should stay empty");
            require(msgs.get(1).mediaPaths.size() == 1 && "/data/koliti_media/only_img.png".equals(msgs.get(1).mediaPaths.get(0)),
                    "media-only path not persisted");
            require(msgs.get(2).mediaPaths.isEmpty(), "assistant message should have empty mediaPaths");

            // MessageEditEntry entity metadata preservation
            ColgramDatabase.MessageEditEntry legacy = new ColgramDatabase.MessageEditEntry(1700000000000L, "old");
            require(legacy.entitiesJson == null, "legacy 2-arg MessageEditEntry should have null entitiesJson");
            String sampleEntities = "[{\"type\":\"custom_emoji\",\"offset\":0,\"length\":2,\"custom_emoji_id\":\"5368324170671202286\"}]";
            ColgramDatabase.MessageEditEntry rich = new ColgramDatabase.MessageEditEntry(1700000001000L, "🔥 привет", sampleEntities);
            require(sampleEntities.equals(rich.entitiesJson), "rich 3-arg MessageEditEntry must preserve entitiesJson");

            System.out.println("ColgramChatMediaAndEntitiesTest: PASS (" + checks + " checks)");
        } finally {
            ColgramAiChatStore.resetForTest();
            File[] files = tmpDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            tmpDir.delete();
        }
    }
}
