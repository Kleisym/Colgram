package org.colgram.core;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-JVM unit tests for Colgram Infinite Storage, Deduplication, Starvation-Free Indexed
 * Image Compression, GZIP AI/Export Stores, Regenerable Waste Pruning, and Low-Disk Cloud Guard.
 */
public final class ColgramStorageOptimizerTest {

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("ColgramStorageOptimizerTest failed: " + msg);
        }
    }

    private static File writeBytes(File f, byte[] data, long mtimeMs) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
            fos.flush();
        }
        if (mtimeMs > 0L) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(mtimeMs);
        }
        return f;
    }

    private static byte[] patternBytes(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ((i * 31 + seed) & 0xFF);
        }
        return b;
    }

    private static void testStorageIndexAndManagedFileGuard() throws Exception {
        File tmpDir = Files.createTempDirectory("colgram_idx_test").toFile();
        ColgramStorageIndex.resetForTest();
        ColgramStorageIndex.init(tmpDir);

        long oldMtime = System.currentTimeMillis() - 300_000L;
        File photo = writeBytes(new File(tmpDir, "photo_1.jpg"), patternBytes(180_000, 7), oldMtime);

        check(!ColgramStorageIndex.isManagedModifiedFile(photo),
                "Unindexed file must not report as managed modified file");
        check(!ColgramStorageIndex.isAlreadyProcessed(photo),
                "Unindexed file must not report as already processed");

        // Record as compressed from 320 KB -> 180 KB
        ColgramStorageIndex.recordEntry(photo, 320_000L, 180_000L, "deadbeef", ColgramStorageIndex.STATE_COMPRESSED);
        ColgramStorageIndex.flush();

        check(ColgramStorageIndex.isManagedModifiedFile(photo),
                "Compressed file matching storedSize must be recognized by FileLoadOperation guard");
        check(ColgramStorageIndex.isAlreadyProcessed(photo),
                "Compressed file with matching size & mtime must be skipped by future sweeps");

        // Reload index from disk (.tsv.gz) and verify persistence across process restarts
        ColgramStorageIndex.resetForTest();
        ColgramStorageIndex.init(tmpDir);
        check(ColgramStorageIndex.isManagedModifiedFile(photo),
                "Reloaded GZIP storage index must preserve compressed file state");
        ColgramStorageIndex.Entry reloaded = ColgramStorageIndex.getEntry(photo);
        check(reloaded != null && reloaded.origSize == 320_000L && reloaded.storedSize == 180_000L
                        && reloaded.savedBytes() == 140_000L,
                "Reloaded entry metadata mismatch");
        ColgramStorageIndex.resetForTest();
    }

    private static void testStarvationFreePaginationAndSinglePassCompression() throws Exception {
        File tmpDir = Files.createTempDirectory("colgram_starvation_test").toFile();
        ColgramStorageIndex.resetForTest();
        ColgramStorageIndex.init(tmpDir);

        long now = System.currentTimeMillis();
        long oldMtime = now - 600_000L; // 10 min old

        // Create 50 already-optimal JPEGs (which will only shrink by 5%, failing the 12% gate)
        // followed by 10 highly-compressible JPEGs (which shrink by 50%).
        for (int i = 0; i < 60; i++) {
            String name = String.format("img_%03d.jpg", i);
            writeBytes(new File(tmpDir, name), patternBytes(240_000, i), oldMtime);
        }

        // Also create excluded files that must NEVER be touched:
        File encFile = writeBytes(new File(tmpDir, "secret_photo.jpg.enc"), patternBytes(300_000, 99), oldMtime);
        File videoFile = writeBytes(new File(tmpDir, "clip.mp4"), patternBytes(400_000, 98), oldMtime);
        File stickerFile = writeBytes(new File(tmpDir, "anim.tgs"), patternBytes(250_000, 97), oldMtime);
        File freshFile = writeBytes(new File(tmpDir, "fresh_download.jpg"), patternBytes(300_000, 96), now - 5_000L);
        File activeFile = writeBytes(new File(tmpDir, "active_view.jpg"), patternBytes(300_000, 95), oldMtime);
        File orphanTmp = writeBytes(new File(tmpDir, "abandoned.jpg.cgtmp"), patternBytes(50_000, 1), now - 600_000L);

        Set<String> activeSet = new HashSet<>(Collections.singletonList(activeFile.getAbsolutePath()));
        final int[] decodeCalls = new int[]{0};

        ColgramStorageOptimizer.ImageTranscoder mockTranscoder = (src, dst, preset) -> {
            decodeCalls[0]++;
            String n = src.getName();
            if (n.compareTo("img_050.jpg") >= 0 && n.compareTo("img_059.jpg") <= 0) {
                // Compressible by 50% (240 KB -> 120 KB)
                writeBytes(dst, patternBytes(120_000, 55), 0L);
                return true;
            }
            // Incompressible (only 2% smaller -> rejected by 12% gate, marked STATE_SKIPPED_OPTIMAL)
            writeBytes(dst, patternBytes(235_000, 11), 0L);
            return true;
        };

        // Sweep 1 (batch size 30): inspects img_000..img_029, marks all 30 STATE_SKIPPED_OPTIMAL, cleans orphan .cgtmp
        ColgramStorageOptimizer.SweepResult s1 = ColgramStorageOptimizer.runImageCompressionSweep(
                Collections.singletonList(tmpDir), ColgramStorageOptimizer.PRESET_BALANCED,
                activeSet, now, 30, mockTranscoder);
        check(s1.orphanTmpCleaned == 1 && !orphanTmp.exists(), "Orphan .cgtmp older than 5m must be swept");
        check(s1.compressedFiles == 0 && decodeCalls[0] == 30,
                "Sweep 1 should inspect first 30 files once, got decodes=" + decodeCalls[0]);

        // Sweep 2 (batch size 30): MUST skip img_000..img_029 and advance to img_030..img_059 (compressing 050..059!)
        ColgramStorageOptimizer.SweepResult s2 = ColgramStorageOptimizer.runImageCompressionSweep(
                Collections.singletonList(tmpDir), ColgramStorageOptimizer.PRESET_BALANCED,
                activeSet, now, 30, mockTranscoder);
        check(s2.compressedFiles == 10 && s2.compressedSavedBytes == 10L * 120_000L,
                "Sweep 2 must paginate past first 30 optimal files and compress img_050..059, got=" + s2.compressedFiles);
        check(decodeCalls[0] == 60, "Each of the 60 files must be decoded at most once, got=" + decodeCalls[0]);

        // Sweep 3: all 60 eligible files are now indexed -> zero additional decodes (no double compression!)
        ColgramStorageOptimizer.SweepResult s3 = ColgramStorageOptimizer.runImageCompressionSweep(
                Collections.singletonList(tmpDir), ColgramStorageOptimizer.PRESET_BALANCED,
                activeSet, now, 30, mockTranscoder);
        check(s3.compressedFiles == 0 && decodeCalls[0] == 60,
                "Sweep 3 must not re-decode already-compressed or optimal-skipped files!");

        // Excluded files must remain 100% untouched
        check(encFile.length() == 300_000L && videoFile.length() == 400_000L
                        && stickerFile.length() == 250_000L && freshFile.length() == 300_000L
                        && activeFile.length() == 300_000L,
                "Excluded (.enc, .mp4, .tgs, <60s fresh, active) files must never be modified");
        ColgramStorageIndex.resetForTest();
    }

    private static void testSha256ContentDeduplication() throws Exception {
        File tmpDir = Files.createTempDirectory("colgram_dedup_test").toFile();
        File casDir = new File(tmpDir, ".colgram_cas");
        ColgramStorageIndex.resetForTest();
        ColgramStorageIndex.init(tmpDir);

        long now = System.currentTimeMillis();
        long oldMtime = now - 300_000L;
        byte[] identicalPayload = patternBytes(128 * 1024, 42); // 128 KB (>= 64 KB threshold)
        byte[] uniquePayload = patternBytes(128 * 1024, 99);

        File chat1Copy = writeBytes(new File(tmpDir, "Images/dup_a.bin"), identicalPayload, oldMtime - 10_000L);
        File chat2Copy = writeBytes(new File(tmpDir, "Documents/dup_b.bin"), identicalPayload, oldMtime);
        File historyCopy = writeBytes(new File(tmpDir, "media_history/dup_c.bin"), identicalPayload, oldMtime + 5_000L);
        File sameSizeDiffContent = writeBytes(new File(tmpDir, "Images/unique_same_size.bin"), uniquePayload, oldMtime);

        ColgramStorageOptimizer.SweepResult dedupRes = ColgramStorageOptimizer.runDeduplicationSweep(
                Arrays.asList(
                        new File(tmpDir, "Images"),
                        new File(tmpDir, "Documents"),
                        new File(tmpDir, "media_history")),
                casDir, Collections.emptySet(), now, 50);

        check(dedupRes.dedupedFiles == 2 && dedupRes.dedupedSavedBytes == 2L * 128L * 1024L,
                "Expected 2 duplicates deduplicated saving 256 KB, got files=" + dedupRes.dedupedFiles);
        check(chat1Copy.isFile() && chat2Copy.isFile() && historyCopy.isFile(),
                "All deduplicated file paths must remain readable in-place");
        check(Arrays.equals(identicalPayload, Files.readAllBytes(chat2Copy.toPath())),
                "Deduplicated file content must match original byte-for-byte");
        check(Arrays.equals(uniquePayload, Files.readAllBytes(sameSizeDiffContent.toPath())),
                "Same-size file with different SHA-256 must not be conflated");

        // Verify copyOrDeduplicateToDir skips creating duplicate files in media_history
        File historyDir = new File(tmpDir, "media_history");
        int countBefore = historyDir.listFiles().length;
        File reused = ColgramStorageOptimizer.copyOrDeduplicateToDir(chat1Copy, historyDir, "new_copy.bin");
        int countAfter = historyDir.listFiles().length;
        check(reused != null && countAfter == countBefore,
                "copyOrDeduplicateToDir must reuse existing identical SHA-256 file in target directory");
        ColgramStorageIndex.resetForTest();
    }

    private static void testAiChatStoreGzipMigrationAndHighCaps() throws Exception {
        File tmpDir = Files.createTempDirectory("colgram_ai_gzip_test").toFile();
        ColgramAiChatStore.resetForTest();

        check(ColgramAiChatStore.MAX_SESSIONS >= 500 && ColgramAiChatStore.MAX_MESSAGES_PER_SESSION >= 2000,
                "ColgramAiChatStore limits must be raised to >= 500 sessions / 2000 messages");

        // Write a legacy v1 plaintext JSON file and verify transparent migration to v2.json.gz
        File legacyV1 = new File(tmpDir, "koliti_chat_history_v1.json");
        String legacyJson = "{\"activeId\":\"chat_1\",\"sessions\":[{\"id\":\"chat_1\",\"title\":\"Старый чат\","
                + "\"boundDialogId\":777,\"boundChatTitle\":\"Канал\",\"createdAt\":1000,\"updatedAt\":2000,"
                + "\"messages\":[{\"role\":\"user\",\"text\":\"Привет из v1\",\"footer\":\"\",\"ts\":1500}]}]}";
        writeBytes(legacyV1, legacyJson.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());

        ColgramAiChatStore.init(tmpDir);
        List<ColgramAiChatStore.ChatSession> sessions = ColgramAiChatStore.listSessions();
        check(sessions.size() == 1 && "Старый чат".equals(sessions.get(0).title)
                        && sessions.get(0).messages.size() == 1,
                "Legacy v1 plaintext JSON must migrate transparently on init");

        File gzV2 = new File(tmpDir, "koliti_chat_history_v2.json.gz");
        check(gzV2.isFile() && !legacyV1.exists(),
                "Migrated history must be saved as compressed koliti_chat_history_v2.json.gz and remove legacy v1");

        // Append 120 messages (> old 80 cap) and verify all 120 persist in GZIP format
        for (int i = 0; i < 120; i++) {
            ColgramAiChatStore.appendMessage("chat_1", "user", "Сообщение номер " + i, "");
        }
        ColgramAiChatStore.resetForTest();
        ColgramAiChatStore.init(tmpDir);
        ColgramAiChatStore.ChatSession reloaded = ColgramAiChatStore.getOrCreateSessionForDialog(777L, "Канал");
        check(reloaded.messages.size() == 121,
                "All 121 messages must persist above old 80-message cap, got=" + reloaded.messages.size());
        ColgramAiChatStore.resetForTest();
    }

    private static void testRegenerableWasteAndEmergencyLowDiskCloudGuard() throws Exception {
        File tmpDir = Files.createTempDirectory("colgram_lowdisk_test").toFile();
        ColgramStorageIndex.resetForTest();
        ColgramStorageIndex.init(tmpDir);

        long now = System.currentTimeMillis();
        File cacheDir = new File(tmpDir, "Cache");
        File acacheDir = new File(cacheDir, "acache");
        File videoDir = new File(tmpDir, "Video");
        File mediaHistoryDir = new File(tmpDir, "media_history");
        File inboxDir = new File(tmpDir, "colgram_inbox");

        // 1. Old acache sticker bitmap (>48h) -> must be pruned
        File oldStickerBmp = writeBytes(new File(acacheDir, "sticker.pcache2"),
                patternBytes(200_000, 1), now - 3L * 86_400_000L);
        // 2. Stale .temp download (>7d) -> must be pruned
        File staleTemp = writeBytes(new File(cacheDir, "part.temp"),
                patternBytes(150_000, 2), now - 8L * 86_400_000L);
        // 3. Cold cloud-backed >5 MB video (3 days old) -> eligible for offload ONLY when < 1.0 GB free
        File coldLargeVideo = writeBytes(new File(videoDir, "2_999888777.mp4"),
                patternBytes(6 * 1024 * 1024, 3), now - 3L * 86_400_000L);
        // 4. Protected files that must NEVER be offloaded even at < 1.0 GB free:
        File preservedRevision = writeBytes(new File(mediaHistoryDir, "rev_video.mp4"),
                patternBytes(6 * 1024 * 1024, 4), now - 10L * 86_400_000L);
        File secretEnc = writeBytes(new File(videoDir, "secret_video.mp4.enc"),
                patternBytes(6 * 1024 * 1024, 5), now - 10L * 86_400_000L);
        File antiDeletedVideo = writeBytes(new File(videoDir, "anti_deleted.mp4"),
                patternBytes(6 * 1024 * 1024, 6), now - 10L * 86_400_000L);

        List<File> roots = Arrays.asList(cacheDir, videoDir, mediaHistoryDir);
        ColgramStorageOptimizer.FileProtectionChecker protector =
                f -> f.getName().equals("anti_deleted.mp4");

        // When available disk space is healthy (5 GB free out of 64 GB), guard must be a no-op
        ColgramStorageOptimizer.SweepResult healthy = ColgramStorageOptimizer.runEmergencyLowDiskGuard(
                roots, null, inboxDir, Collections.emptySet(), now,
                5L * 1024L * 1024L * 1024L, 64L * 1024L * 1024L * 1024L,
                true, ColgramStorageOptimizer.PRESET_LOSSLESS, null, protector);
        check(healthy.cloudOffloadedFiles == 0 && oldStickerBmp.exists() && coldLargeVideo.exists(),
                "Healthy free disk space (5 GB) must not trigger emergency guard");

        // When free space is critically low (800 MB < 1.0 GB), guard prunes waste AND offloads cold cloud blob
        // while keeping preservedRevision, secretEnc, and antiDeletedVideo 100% intact!
        ColgramStorageOptimizer.SweepResult critical = ColgramStorageOptimizer.runEmergencyLowDiskGuard(
                roots, null, inboxDir, Collections.emptySet(), now,
                800L * 1024L * 1024L, 64L * 1024L * 1024L * 1024L,
                true, ColgramStorageOptimizer.PRESET_LOSSLESS, null, protector);
        check(!oldStickerBmp.exists() && !staleTemp.exists() && critical.wasteReclaimedBytes >= 350_000L,
                "Low-disk guard must reclaim acache and stale .temp waste first");
        check(critical.cloudOffloadedFiles == 1 && !coldLargeVideo.exists(),
                "Critical <1GB guard must offload cold cloud-backed >5MB video");
        check(preservedRevision.exists() && secretEnc.exists() && antiDeletedVideo.exists(),
                "Critical guard must NEVER touch media_history, .enc secret media, or Anti-Deleted files");
        ColgramStorageIndex.resetForTest();
    }

    public static void main(String[] args) throws Exception {
        testStorageIndexAndManagedFileGuard();
        testStarvationFreePaginationAndSinglePassCompression();
        testSha256ContentDeduplication();
        testAiChatStoreGzipMigrationAndHighCaps();
        testRegenerableWasteAndEmergencyLowDiskCloudGuard();
        System.out.println("ColgramStorageOptimizerTest: PASS");
    }
}
