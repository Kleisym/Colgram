package org.colgram.core;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * JVM unit tests for ColgramStorageCapEnforcer 3-Tier Virtual Infinite Storage:
 *   - Phase A (<15ms): acache/temp/CAS-orphan cleanup + 8 MB messages_log.jsonl rotation
 *   - Phase B (Tier 1): image compression, GZIP text/log compression, progressive Shadow Micro-Vault compaction
 *   - Phase C (Tier 0+2): >10 MB cache hog eviction + STATE_DEHYDRATED catalog stub preservation down to <=75% cap
 *   - Strict protection of usingFilePaths, shadow_previews, colgram_vault, media_history, wallpapers, .db*, .enc, .gguf, <5m files.
 */
public class ColgramStorageCapEnforcerTest {

    private static final long MB = 1024L * 1024L;

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("ColgramStorageCapEnforcerTest FAILED: " + msg);
        }
    }

    private static File writeFile(File f, int sizeBytes, byte seed, long mtimeMs) throws Exception {
        if (f.getParentFile() != null && !f.getParentFile().exists()) {
            f.getParentFile().mkdirs();
        }
        byte[] buf = new byte[sizeBytes];
        for (int i = 0; i < sizeBytes; i++) {
            buf[i] = (byte) ((i * 31 + seed) & 0xFF);
        }
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(buf);
        }
        if (mtimeMs > 0L) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(mtimeMs);
        }
        return f;
    }

    private static File writeCompressibleText(File f, int approxBytes, long mtimeMs) throws Exception {
        if (f.getParentFile() != null && !f.getParentFile().exists()) {
            f.getParentFile().mkdirs();
        }
        String chunk = "{\"ts\":1700000000,\"dialog\":-100123456,\"text\":\"Repeating log entry for compression test\"}\n";
        byte[] unit = chunk.getBytes(StandardCharsets.UTF_8);
        int repeats = Math.max(1, approxBytes / unit.length);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            for (int i = 0; i < repeats; i++) {
                fos.write(unit);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.setLastModified(mtimeMs);
        return f;
    }

    private static void test3TierCapEnforcementAndProtections() throws Exception {
        File tmpRoot = Files.createTempDirectory("colgram_cap_enforcer_test").toFile();
        ColgramStorageIndex.resetForTest();
        ColgramStorageIndex.init(tmpRoot);

        long now = System.currentTimeMillis();
        long old1h = now - 3600_000L;
        long old2h = now - 7200_000L;
        long fresh2m = now - 120_000L;

        File sandboxImages = new File(tmpRoot, "Colgram/Images");
        File sandboxVideo = new File(tmpRoot, "Colgram/Video");
        File sandboxCache = new File(tmpRoot, "Colgram/Cache");
        File acache = new File(sandboxCache, "acache");
        File casDir = new File(tmpRoot, "Colgram/.colgram_cas");
        File vaultDir = new File(tmpRoot, "colgram_vault");
        File shadowPreviews = new File(vaultDir, "shadow_previews");
        File wallpapersDir = new File(tmpRoot, "colgram_wallpapers");
        File mediaHistory = new File(tmpRoot, "media_history");
        File modelsDir = new File(tmpRoot, "koliti_models");

        // Cap = 20 MB -> trigger 85% (17 MB), target <= 75% (15 MB)
        long capBytes = 20L * MB;

        // 1. Phase A candidates: orphan CAS blob + stale .temp + >8 MB messages_log.jsonl
        File orphanCas = writeFile(new File(casDir, "ab/abcdef1234567890.mp4"), 512 * 1024, (byte) 1, old2h);
        File staleTemp = writeFile(new File(sandboxCache, "broken.temp"), 512 * 1024, (byte) 2, old2h);
        File msgLog = writeCompressibleText(new File(tmpRoot, "Colgram/messages_log.jsonl"), (int) (8L * MB + 16_384), old1h);

        // 2. Phase B candidates: >120 KB image + >32 KB JSON/log
        File bigPhoto = writeFile(new File(sandboxImages, "photo_big.jpg"), 400_000, (byte) 3, old1h);
        File bigLog = writeCompressibleText(new File(sandboxCache, "trace.log"), 256 * 1024, old1h);

        // 3. Phase C candidates: >10 MB video hog (18 MB so afterAB > 15 MB target -> enters Phase C!) + old LRU cache file
        File videoHog = writeFile(new File(sandboxVideo, "movie_hog.mp4"), (int) (18L * MB), (byte) 4, old1h);
        File oldTiny = writeFile(new File(sandboxImages, "tiny_old.jpg"), 80_000, (byte) 5, old2h);

        // 4. Strictly protected files (must NEVER be modified/evicted!)
        File activeDownload = writeFile(new File(sandboxVideo, "active_playing.mp4"), (int) (2L * MB), (byte) 6, old2h);
        File youngFile = writeFile(new File(sandboxImages, "brand_new.jpg"), 600_000, (byte) 7, fresh2m);
        File previewFile = writeFile(new File(shadowPreviews, "-1001_42.webp"), 32 * 1024, (byte) 8, old2h);
        File vaultDb = writeFile(new File(vaultDir, "colgram_vault.db"), 256 * 1024, (byte) 9, old2h);
        File encFile = writeFile(new File(sandboxVideo, "secret.mp4.enc"), 128 * 1024, (byte) 10, old2h);
        File modelFile = writeFile(new File(modelsDir, "tiny.gguf"), 512 * 1024, (byte) 11, old2h);
        File wallFile = writeFile(new File(wallpapersDir, "bg.mp4"), 256 * 1024, (byte) 12, old2h);
        File revFile = writeFile(new File(mediaHistory, "rev_1.jpg"), 128 * 1024, (byte) 13, old2h);
        File acacheOld = writeFile(new File(acache, "old.pcache2"), 128 * 1024, (byte) 14, now - 4L * 86_400_000L);

        Set<String> activePaths = new HashSet<>(Collections.singletonList(activeDownload.getAbsolutePath()));
        ColgramStorageOptimizer.ImageTranscoder mockWebpTranscoder = (src, tmpDest, preset) -> {
            long lim = src.length() / 4L;
            byte[] out = new byte[(int) Math.max(8192L, lim)];
            try (FileOutputStream fos = new FileOutputStream(tmpDest)) {
                fos.write(out);
            }
            return true;
        };

        ColgramStorageCapEnforcer.EnforcementReport rep = ColgramStorageCapEnforcer.enforceCapSync(
                Arrays.asList(new File(tmpRoot, "Colgram"), modelsDir, wallsFallback(wallpapersDir)),
                tmpRoot,
                shadowPreviews,
                capBytes,
                activePaths,
                mockWebpTranscoder,
                true,
                false,
                true,
                now);

        check(rep.triggered, "Cap enforcement must trigger when total >= 85% of 20 MB");
        check(!orphanCas.exists(), "Phase A must prune orphan .colgram_cas blob");
        check(!staleTemp.exists(), "Phase A must prune stale .temp file");
        check(!acacheOld.exists(), "Phase A must prune old acache bitmap");
        check(msgLog.length() == 0L && new File(tmpRoot, "Colgram/messages_log.1.jsonl.gz").isFile(),
                "Phase A must rotate >=8 MB messages_log.jsonl into messages_log.1.jsonl.gz");
        check(rep.compressedFilesCount >= 2 && rep.phaseBCompressedSavedBytes > 0L,
                "Phase B must compress >120 KB image + >32 KB log, saved=" + rep.phaseBCompressedSavedBytes);
        check(!bigLog.exists() && new File(bigLog.getAbsolutePath() + ".gz").isFile(),
                "Phase B must replace compressible .log with .log.gz");
        check(bigPhoto.length() < 200_000L, "Phase B must replace photo_big.jpg with compressed WebP bytes");
        check(!videoHog.exists() && oldTiny.exists(),
                "Phase C must evict >10 MB cache hog BEFORE smaller older files");
        check(ColgramStorageIndex.getDehydratedEntry(videoHog.getAbsolutePath()) != null
                        && ColgramStorageIndex.getTotalDehydratedBytes() >= 18L * MB,
                "Tier-0 catalog must preserve STATE_DEHYDRATED stub & virtual bytes for evicted hog");
        check(rep.bytesAfter <= (long) (capBytes * 0.75d),
                "Enforcer must bring physical usage <= 75% cap (" + (capBytes * 0.75d) + "), got=" + rep.bytesAfter);

        // Verify strict protections
        check(activeDownload.exists(), "Active usingFilePaths MUST be protected");
        check(youngFile.exists(), "Files <5 min old MUST be protected from Phase C eviction");
        check(previewFile.exists(), "shadow_previews/ MUST be protected");
        check(vaultDb.exists(), "colgram_vault.db MUST be protected");
        check(encFile.exists(), ".enc secret media MUST be protected");
        check(modelFile.exists(), ".gguf AI models MUST be protected");
        check(wallFile.exists(), "colgram_wallpapers MUST be protected");
        check(revFile.exists(), "media_history revisions MUST be protected");

        ColgramStorageIndex.resetForTest();
    }

    private static File wallsFallback(File d) {
        return d;
    }

    public static void main(String[] args) throws Exception {
        test3TierCapEnforcementAndProtections();
        System.out.println("ColgramStorageCapEnforcerTest: PASS");
    }
}
