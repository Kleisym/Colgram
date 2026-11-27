package org.colgram.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ColgramAiAttachmentTest {

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("ASSERTION FAILED: " + msg);
        }
    }

    private static void checkEq(Object actual, Object expected, String msg) {
        if (actual == null ? expected != null : !actual.equals(expected)) {
            throw new AssertionError("ASSERTION FAILED: " + msg + " | expected=" + expected + ", actual=" + actual);
        }
    }

    public static void main(String[] args) throws Exception {
        File tmpDir = Files.createTempDirectory("colgram_attach_test_").toFile();
        try {
            testClassifyKindAndHelpers();
            testExifFlipAndRotate();
            testTextAndBinaryGuards(tmpDir);
            testDocxAndOdtExtraction(tmpDir);
            testContextIsolationAndSanitization();
            testVisionContextBudget();
            System.out.println("ColgramAiAttachmentTest: ALL PASS");
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    private static void testClassifyKindAndHelpers() {
        String[] imgs = {"a.jpg", "B.JPEG", "pic.png", "icon.webp", "anim.gif", "scan.bmp", "iphone.heic", "photo.HEIF", "hdr.avif"};
        for (String name : imgs) {
            checkEq(ColgramAiVisionProcessor.classifyKind(name), ColgramAiVisionProcessor.AttachmentKind.IMAGE, "image kind for " + name);
        }
        String[] vids = {"clip.mp4", "rec.MOV", "movie.mkv", "stream.webm", "old.3gp", "cam.avi", "clip.m4v"};
        for (String name : vids) {
            checkEq(ColgramAiVisionProcessor.classifyKind(name), ColgramAiVisionProcessor.AttachmentKind.VIDEO, "video kind for " + name);
        }
        checkEq(ColgramAiVisionProcessor.classifyKind("/sdcard/Download/Spec.PDF"), ColgramAiVisionProcessor.AttachmentKind.PDF, "pdf kind");

        String[] docs = {"readme.md", "notes.txt", "main.py", "App.java", "Main.kt", "config.json", "pom.xml",
                "compose.yaml", "deploy.yml", "query.sql", "report.docx", "sheet.odt", "Dockerfile", "README"};
        for (String name : docs) {
            checkEq(ColgramAiVisionProcessor.classifyKind(name), ColgramAiVisionProcessor.AttachmentKind.TEXT_DOC, "doc kind for " + name);
        }
        String[] bad = {"payload.exe", "app.apk", "blob.bin", "track.mp3", "archive.zip", "", null};
        for (String name : bad) {
            checkEq(ColgramAiVisionProcessor.classifyKind(name), ColgramAiVisionProcessor.AttachmentKind.UNSUPPORTED, "unsupported kind for " + name);
        }

        checkEq(ColgramAiVisionProcessor.getExtensionUpper("/tmp/script.py"), "PY", "ext PY");
        checkEq(ColgramAiVisionProcessor.getExtensionUpper("report.docx"), "DOCX", "ext DOCX");
        checkEq(ColgramAiVisionProcessor.getExtensionUpper("Makefile"), "FILE", "ext no dot");
        checkEq(ColgramAiVisionProcessor.getExtensionUpper(null), "FILE", "ext null");
        check(!ColgramAiVisionProcessor.formatFileSize(1536).isEmpty(), "formatFileSize non-empty");
    }

    private static void testExifFlipAndRotate() {
        check(Arrays.equals(ColgramAiVisionProcessor.exifFlipAndRotate(1), new int[]{0, 1, 1}), "EXIF 1");
        check(Arrays.equals(ColgramAiVisionProcessor.exifFlipAndRotate(2), new int[]{0, -1, 1}), "EXIF 2");
        check(Arrays.equals(ColgramAiVisionProcessor.exifFlipAndRotate(3), new int[]{180, 1, 1}), "EXIF 3");
        check(Arrays.equals(ColgramAiVisionProcessor.exifFlipAndRotate(4), new int[]{0, 1, -1}), "EXIF 4");
        check(Arrays.equals(ColgramAiVisionProcessor.exifFlipAndRotate(5), new int[]{90, -1, 1}), "EXIF 5");
        check(Arrays.equals(ColgramAiVisionProcessor.exifFlipAndRotate(6), new int[]{90, 1, 1}), "EXIF 6");
        check(Arrays.equals(ColgramAiVisionProcessor.exifFlipAndRotate(7), new int[]{270, -1, 1}), "EXIF 7");
        check(Arrays.equals(ColgramAiVisionProcessor.exifFlipAndRotate(8), new int[]{270, 1, 1}), "EXIF 8");
    }

    private static void testTextAndBinaryGuards(File tmpDir) throws Exception {
        // UTF-8 with BOM + CRLF normalization
        File pyFile = new File(tmpDir, "hello.py");
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "def greet(name):\r\n    return f'Привет, {name}'\r\n".getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(pyFile)) {
            fos.write(bom);
            fos.write(body);
        }
        ColgramAiVisionProcessor.PreparedAttachment pyAtt = ColgramAiVisionProcessor.prepareDocumentAttachment(pyFile);
        checkEq(pyAtt.kind, ColgramAiVisionProcessor.AttachmentKind.TEXT_DOC, "py kind");
        check(pyAtt.hasText(), "py hasText");
        check(pyAtt.extractedText.contains("def greet(name):") && pyAtt.extractedText.contains("Привет"), "py decoded text");
        check(!pyAtt.extractedText.contains("\r") && !pyAtt.extractedText.startsWith("\uFEFF"), "no CR or BOM");

        // Bounded truncation check
        File longFile = new File(tmpDir, "big.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6000; i++) {
            sb.append('x');
        }
        Files.write(longFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        String capped = ColgramAiVisionProcessor.extractTextFromFile(longFile, 4500);
        check(capped != null && capped.contains("обрезано до 4500 символов"), "truncated marker present");
        check(capped.length() < 4600, "truncated length bounded");

        // NUL byte binary guard (misnamed .txt with binary payload)
        File fakeTxt = new File(tmpDir, "corrupt.txt");
        Files.write(fakeTxt.toPath(), new byte[]{'E', 'L', 'F', 0x00, 0x01, 0x02, 0x03});
        String nullRes = ColgramAiVisionProcessor.extractTextFromFile(fakeTxt, 1000);
        checkEq(nullRes, null, "NUL byte file rejected as binary");
        ColgramAiVisionProcessor.PreparedAttachment badAtt = ColgramAiVisionProcessor.prepareAttachment(null, fakeTxt.getAbsolutePath());
        checkEq(badAtt.kind, ColgramAiVisionProcessor.AttachmentKind.UNSUPPORTED, "binary fake .txt returns UNSUPPORTED");
        check(!badAtt.errorMessage.isEmpty(), "binary error message populated");
    }

    private static void testDocxAndOdtExtraction(File tmpDir) throws Exception {
        File docx = new File(tmpDir, "contract.docx");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(docx))) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("word/document.xml"));
            String xml = "<w:document><w:body><w:p><w:r><w:t>Hello &amp; Привет</w:t></w:r></w:p>"
                    + "<w:p><w:r><w:t>Price &lt; 100&#x20BD;</w:t></w:r></w:p></w:body></w:document>";
            zos.write(xml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        ColgramAiVisionProcessor.PreparedAttachment docxAtt = ColgramAiVisionProcessor.prepareDocumentAttachment(docx);
        checkEq(docxAtt.kind, ColgramAiVisionProcessor.AttachmentKind.TEXT_DOC, "docx kind");
        check(docxAtt.extractedText.contains("Hello & Привет"), "docx paragraph 1 + &amp;");
        check(docxAtt.extractedText.contains("Price < 100₽"), "docx paragraph 2 + &lt; + hex entity");

        File odt = new File(tmpDir, "notes.odt");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(odt))) {
            zos.putNextEntry(new ZipEntry("content.xml"));
            String xml = "<office:document-content><office:body><office:text>"
                    + "<text:p>ODT line one</text:p><text:p>ODT line two</text:p>"
                    + "</office:text></office:body></office:document-content>";
            zos.write(xml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        String odtText = ColgramAiVisionProcessor.extractDocxOrOdtText(odt, 4500);
        check(odtText != null && odtText.contains("ODT line one") && odtText.contains("ODT line two"), "odt extracted");

        // Corrupted zip docx safely fails without throwing
        File badDocx = new File(tmpDir, "broken.docx");
        Files.write(badDocx.toPath(), "not-a-zip".getBytes(StandardCharsets.UTF_8));
        ColgramAiVisionProcessor.PreparedAttachment badDocxAtt = ColgramAiVisionProcessor.prepareDocumentAttachment(badDocx);
        checkEq(badDocxAtt.kind, ColgramAiVisionProcessor.AttachmentKind.UNSUPPORTED, "broken docx rejected safely");
    }

    private static void testContextIsolationAndSanitization() {
        // Implicit chat context MUST stay off when media is attached unless user explicitly asks about the chat!
        ColgramAiContextEngine.IntentPlan isolated = ColgramAiContextEngine.planAutonomousIntent(
                "Что здесь изображено?",
                ColgramAiContextEngine.AssistantMode.AUTO,
                true,
                true,
                true);
        checkEq(isolated.resolvedMode, ColgramAiContextEngine.AssistantMode.VISION, "attached media picks VISION");
        check(!isolated.shouldUseActiveChatContext, "attached media isolates unrelated active chat");
        check(!isolated.shouldAutoGrabMedia, "explicit attachment disables auto-grabbing chat media");

        ColgramAiContextEngine.IntentPlan explicitChat = ColgramAiContextEngine.planAutonomousIntent(
                "О чем этот чат?",
                ColgramAiContextEngine.AssistantMode.AUTO,
                false,
                true,
                true);
        check(explicitChat.shouldUseActiveChatContext, "explicit chat question enables active chat context");

        ColgramAiContextEngine.IntentPlan hybridChatAndMedia = ColgramAiContextEngine.planAutonomousIntent(
                "Что на фото и что писали в этом чате?",
                ColgramAiContextEngine.AssistantMode.AUTO,
                true,
                true,
                true);
        check(hybridChatAndMedia.shouldUseActiveChatContext, "explicit chat ask keeps active chat even with attached photo");

        // <__media__> token sanitization prevents llama mtmd count mismatch crash
        String cleaned = ColgramAiContextEngine.sanitizeMediaMarker("Смотри <__media__> и ещё <__media__>.");
        check(!cleaned.contains("<__media__>"), "raw <__media__> token sanitized");

        List<ColgramAiContextEngine.ConversationTurn> hist = Collections.singletonList(
                new ColgramAiContextEngine.ConversationTurn("проверь <__media__>", "ответ про <__media__>")
        );
        String prompt = ColgramAiContextEngine.buildUserPrompt(
                "Проверь <__media__> файл",
                "",
                "",
                "",
                "",
                "",
                "Документ test.py:\ndef run(): pass",
                1,
                false,
                hist);
        check(prompt.contains("Прикреплённые документы:"), "doc context injected into user prompt");
        check(prompt.contains("def run(): pass"), "doc body present in user prompt");
        check(!prompt.contains("<__media__>"), "prompt + history sanitized when attachedFramesCount > 0");
    }

    private static void testVisionContextBudget() {
        checkEq(ColgramAiEngine.computeVisionContextBudget(0, 0, 0), 2048, "default budget");
        checkEq(ColgramAiEngine.computeVisionContextBudget(0, 1, 0), 3072, "1 image budget");
        checkEq(ColgramAiEngine.computeVisionContextBudget(0, 2, 0), 3072, "2 images budget");
        checkEq(ColgramAiEngine.computeVisionContextBudget(0, 3, 0), 4096, "3 images budget");
        checkEq(ColgramAiEngine.computeVisionContextBudget(0, 4, 0), 4096, "4 images budget");
        checkEq(ColgramAiEngine.computeVisionContextBudget(0, 0, 2500), 4096, "large doc budget");
        checkEq(ColgramAiEngine.computeVisionContextBudget(2048, 3, 0), 4096, "multi-image beats small 2048 override");
        checkEq(ColgramAiEngine.computeVisionContextBudget(8192, 3, 0), 8192, "large user override preserved");
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
