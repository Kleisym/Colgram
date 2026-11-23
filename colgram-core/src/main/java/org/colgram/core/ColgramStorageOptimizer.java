package org.colgram.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Pure-Java + Android media/storage optimizer for Colgram Infinite Storage:
 * - SHA-256 content deduplication (size-bucketed >= 64 KB, streaming 64 KB hash, atomic hardlink / CAS dedup)
 * - EXIF-orientation-preserving, ARGB_8888 color-safe, single-pass indexed image compression
 * - Starvation-free pagination across the entire archive (records both compressed and optimal skipped files)
 * - Orphan .cgtmp cleanup, regenerable acache/.pcache2 & stale .temp/.preload pruning
 * - Emergency Low-Disk Virtual Cloud Guard (< 1.5 GB free space safety valve)
 *
 * Strictly never opens Telegram's SQLite databases.
 */
public final class ColgramStorageOptimizer {

    public static final int PRESET_LOSSLESS = 0;
    public static final int PRESET_BALANCED = 1;
    public static final int PRESET_MAX = 2;

    public static final long MIN_DEDUP_BYTES = 64L * 1024L;          // 64 KB
    public static final long MIN_JPG_BALANCED_BYTES = 200_000L;      // 200 KB
    public static final long MIN_JPG_MAX_BYTES = 150_000L;           // 150 KB
    public static final long MIN_PNG_BALANCED_BYTES = 500_000L;      // 500 KB
    public static final long MIN_PNG_MAX_BYTES = 350_000L;           // 350 KB
    public static final long MIN_FILE_AGE_MS = 60_000L;              // 60 seconds
    public static final long ORPHAN_TMP_AGE_MS = 5L * 60_000L;       // 5 minutes
    public static final long STALE_TEMP_AGE_MS = 7L * 86_400_000L;   // 7 days
    public static final long ACACHE_MAX_AGE_MS = 2L * 86_400_000L;   // 48 hours
    public static final long ACACHE_MAX_BYTES = 128L * 1024L * 1024L; // 128 MB

    public static final long EMERGENCY_LOW_DISK_BYTES = 1536L * 1024L * 1024L;   // 1.5 GB
    public static final long CRITICAL_CLOUD_OFFLOAD_BYTES = 1024L * 1024L * 1024L; // 1.0 GB
    public static final long MIN_CLOUD_OFFLOAD_FILE_BYTES = 5L * 1024L * 1024L;  // 5 MB
    public static final long MIN_CLOUD_OFFLOAD_AGE_MS = 24L * 3600_000L;         // 24 hours

    public interface ImageTranscoder {
        /**
         * Transcodes {@code source} into {@code tempDest} preserving visual orientation and color depth.
         * @return true if {@code tempDest} was written successfully.
         */
        boolean transcode(File source, File tempDest, int preset) throws Exception;
    }

    public interface FileProtectionChecker {
        boolean isProtected(File file);
    }

    public static final class SweepResult {
        public int compressedFiles;
        public long compressedSavedBytes;
        public int dedupedFiles;
        public long dedupedSavedBytes;
        public int orphanTmpCleaned;
        public long wasteReclaimedBytes;
        public int cloudOffloadedFiles;
        public long cloudOffloadedBytes;

        public long totalSavedBytes() {
            return compressedSavedBytes + dedupedSavedBytes + wasteReclaimedBytes;
        }

        public void add(SweepResult other) {
            if (other == null) return;
            this.compressedFiles += other.compressedFiles;
            this.compressedSavedBytes += other.compressedSavedBytes;
            this.dedupedFiles += other.dedupedFiles;
            this.dedupedSavedBytes += other.dedupedSavedBytes;
            this.orphanTmpCleaned += other.orphanTmpCleaned;
            this.wasteReclaimedBytes += other.wasteReclaimedBytes;
            this.cloudOffloadedFiles += other.cloudOffloadedFiles;
            this.cloudOffloadedBytes += other.cloudOffloadedBytes;
        }
    }

    private static final String[] EXCLUDED_EXTENSIONS = new String[]{
            ".enc", ".key", ".cgtmp", ".tmp", ".temp", ".preload", ".part",
            ".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v", ".3gp",
            ".gif", ".tgs", ".webp",
            ".ogg", ".opus", ".mp3", ".m4a", ".wav", ".flac", ".aac",
            ".pdf", ".zip", ".rar", ".7z", ".tar", ".gz", ".apk", ".dex", ".so",
            ".db", ".db-wal", ".db-shm", ".sqlite", ".gguf", ".onnx", ".bin", ".py"
    };

    private ColgramStorageOptimizer() {
    }

    /** Computes streaming SHA-256 hex digest using a 64 KB buffer (never loads full file into RAM). */
    public static String computeSha256(File file) {
        if (file == null || !file.isFile()) return "";
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String toHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2] = digits[v >>> 4];
            hexChars[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static boolean isExcludedDirectoryName(String dirName) {
        if (dirName == null || dirName.isEmpty()) return false;
        String dn = dirName.toLowerCase(Locale.ROOT);
        return dn.equals(".colgram_cas")
                || dn.equals("acache")
                || dn.contains("koliti")
                || dn.contains("llama")
                || dn.contains("vault")
                || dn.contains("voice");
    }

    /** Returns true if a file must never be touched by compression or deduplication. */
    public static boolean isExcludedFromOptimization(File file, Set<String> activePaths, long nowMs) {
        if (file == null || !file.isFile()) return true;
        String name = file.getName();
        if (name.startsWith(".") || ".nomedia".equalsIgnoreCase(name)) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".enc") || lower.endsWith(".key")
                || lower.endsWith(".cgtmp") || lower.endsWith(".tmp")
                || lower.endsWith(".temp") || lower.endsWith(".preload")
                || lower.endsWith(".db") || lower.endsWith(".db-wal") || lower.endsWith(".db-shm")
                || lower.endsWith(".gguf") || lower.endsWith(".so")) {
            return true;
        }
        File parent = file.getParentFile();
        if (parent != null && isExcludedDirectoryName(parent.getName())) {
            return true;
        }
        if (isPathActive(file, activePaths)) {
            return true;
        }
        long mtime = file.lastModified();
        return nowMs > 0L && mtime > 0L && (nowMs - mtime) < MIN_FILE_AGE_MS;
    }

    public static boolean isPathActive(File file, Set<String> activePaths) {
        if (file == null || activePaths == null || activePaths.isEmpty()) return false;
        String abs = file.getAbsolutePath();
        if (activePaths.contains(abs) || activePaths.contains(file.getPath())) return true;
        String norm = ColgramStorageIndex.normalizePath(abs);
        return activePaths.contains(norm);
    }

    /** Returns true if {@code file} is an eligible image candidate for compression that has not yet been processed. */
    public static boolean isImageCompressCandidate(File file, int preset, Set<String> activePaths, long nowMs) {
        if (preset == PRESET_LOSSLESS) return false;
        if (isExcludedFromOptimization(file, activePaths, nowMs)) return false;
        String lower = file.getName().toLowerCase(Locale.ROOT);
        for (String ext : EXCLUDED_EXTENSIONS) {
            if (lower.endsWith(ext)) return false;
        }
        long len = file.length();
        boolean isJpg = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        boolean isPng = lower.endsWith(".png");
        if (!isJpg && !isPng) return false;
        long minBytes = isJpg
                ? (preset == PRESET_MAX ? MIN_JPG_MAX_BYTES : MIN_JPG_BALANCED_BYTES)
                : (preset == PRESET_MAX ? MIN_PNG_MAX_BYTES : MIN_PNG_BALANCED_BYTES);
        if (len < minBytes) return false;
        return !ColgramStorageIndex.isAlreadyProcessed(file);
    }

    /** Sweeps orphaned *.cgtmp files older than 5 minutes across all given roots. */
    public static int sweepOrphanTmpFiles(Collection<File> roots, long nowMs) {
        if (roots == null || roots.isEmpty()) return 0;
        int cleaned = 0;
        for (File root : roots) {
            cleaned += sweepOrphanTmpRecursive(root, 0, nowMs);
        }
        return cleaned;
    }

    private static int sweepOrphanTmpRecursive(File dir, int depth, long nowMs) {
        if (dir == null || !dir.isDirectory() || depth > 6) return 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int cleaned = 0;
        for (File child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                cleaned += sweepOrphanTmpRecursive(child, depth + 1, nowMs);
            } else if (child.getName().endsWith(".cgtmp")) {
                long age = nowMs > 0L && child.lastModified() > 0L ? (nowMs - child.lastModified()) : Long.MAX_VALUE;
                if (age >= ORPHAN_TMP_AGE_MS) {
                    if (child.delete()) {
                        cleaned++;
                    }
                }
            }
        }
        return cleaned;
    }

    /**
     * Collects eligible image compression candidates across roots in deterministic order,
     * skipping any file already recorded in {@link ColgramStorageIndex} so pagination never starves!
     */
    public static List<File> collectCompressCandidates(Collection<File> roots, int preset,
                                                       Set<String> activePaths, long nowMs, int maxFiles) {
        List<File> out = new ArrayList<>();
        if (roots == null || preset == PRESET_LOSSLESS || maxFiles <= 0) return out;
        for (File root : roots) {
            collectCompressRecursive(root, 0, preset, activePaths, nowMs, maxFiles, out);
            if (out.size() >= maxFiles) break;
        }
        return out;
    }

    private static void collectCompressRecursive(File dir, int depth, int preset,
                                                 Set<String> activePaths, long nowMs,
                                                 int maxFiles, List<File> out) {
        if (dir == null || !dir.isDirectory() || depth > 5 || out.size() >= maxFiles) return;
        if (depth > 0 && isExcludedDirectoryName(dir.getName())) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (out.size() >= maxFiles) return;
            if (child == null) continue;
            if (child.isDirectory()) {
                collectCompressRecursive(child, depth + 1, preset, activePaths, nowMs, maxFiles, out);
            } else if (isImageCompressCandidate(child, preset, activePaths, nowMs)) {
                out.add(child);
            }
        }
    }

    /**
     * Runs a single-pass, starvation-free image compression sweep using the provided {@link ImageTranscoder}.
     * Files that shrink by >= 12% are atomically replaced and indexed as STATE_COMPRESSED.
     * Files that cannot shrink by >= 12% are left untouched and indexed as STATE_SKIPPED_OPTIMAL
     * so future sweeps skip them in O(1) and advance through the rest of the archive.
     */
    public static SweepResult runImageCompressionSweep(Collection<File> roots, int preset,
                                                       Set<String> activePaths, long nowMs,
                                                       int maxFilesToProcess,
                                                       ImageTranscoder transcoder) {
        SweepResult result = new SweepResult();
        if (roots == null || roots.isEmpty()) return result;
        result.orphanTmpCleaned = sweepOrphanTmpFiles(roots, nowMs);
        if (preset == PRESET_LOSSLESS || transcoder == null || maxFilesToProcess <= 0) {
            return result;
        }

        List<File> candidates = collectCompressCandidates(roots, preset, activePaths, nowMs, maxFilesToProcess);
        for (File f : candidates) {
            if (isPathActive(f, activePaths)) continue;
            long origLen = f.length();
            if (origLen <= 0L) continue;
            long origMtime = f.lastModified();
            File tmp = new File(f.getAbsolutePath() + ".cgtmp");
            try {
                boolean ok = transcoder.transcode(f, tmp, preset);
                long newLen = (ok && tmp.isFile()) ? tmp.length() : 0L;
                // Require valid output > 4 KB and at least 12% size reduction
                if (ok && newLen > 4096L && newLen <= (origLen * 88L) / 100L) {
                    if (atomicReplaceFile(tmp, f)) {
                        if (origMtime > 0L) {
                            //noinspection ResultOfMethodCallIgnored
                            f.setLastModified(origMtime);
                        }
                        long finalLen = f.length();
                        ColgramStorageIndex.recordEntry(f, origLen, finalLen, "", ColgramStorageIndex.STATE_COMPRESSED);
                        result.compressedFiles++;
                        result.compressedSavedBytes += Math.max(0L, origLen - finalLen);
                    } else {
                        ColgramStorageIndex.recordEntry(f, origLen, origLen, "", ColgramStorageIndex.STATE_SKIPPED_OPTIMAL);
                    }
                } else {
                    // Record as optimal so we NEVER re-decode this file again on future sweeps!
                    ColgramStorageIndex.recordEntry(f, origLen, origLen, "", ColgramStorageIndex.STATE_SKIPPED_OPTIMAL);
                }
            } catch (Throwable ignored) {
                // On decode/transcode failure, mark optimal for current mtime/size to avoid infinite retry loop
                ColgramStorageIndex.recordEntry(f, origLen, origLen, "", ColgramStorageIndex.STATE_SKIPPED_OPTIMAL);
            } finally {
                if (tmp.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        }
        ColgramStorageIndex.flush();
        return result;
    }

    /**
     * Content-addressed SHA-256 deduplication across all given directories.
     * Pre-filters by exact file size (>= 64 KB) so unique-size files incur zero hashing I/O.
     */
    public static SweepResult runDeduplicationSweep(Collection<File> roots, File casDir,
                                                    Set<String> activePaths, long nowMs,
                                                    int maxHashesPerSweep) {
        SweepResult result = new SweepResult();
        if (roots == null || roots.isEmpty()) return result;

        Map<Long, List<File>> bySize = new LinkedHashMap<>();
        for (File root : roots) {
            collectDedupCandidatesBySize(root, 0, activePaths, nowMs, bySize);
        }

        int hashesComputed = 0;
        for (Map.Entry<Long, List<File>> entry : bySize.entrySet()) {
            List<File> bucket = entry.getValue();
            if (bucket == null || bucket.size() < 2) continue;

            // Sort oldest first so the oldest file is chosen as canonical
            Collections.sort(bucket, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });

            Map<String, File> canonicalByHash = new HashMap<>();
            for (File f : bucket) {
                if (!f.isFile() || isPathActive(f, activePaths)) continue;
                long len = f.length();
                if (len < MIN_DEDUP_BYTES) continue;

                ColgramStorageIndex.Entry existingEntry = ColgramStorageIndex.getEntry(f);
                String sha = "";
                if (existingEntry != null && existingEntry.matchesFileOnDisk(f) && !existingEntry.sha256.isEmpty()) {
                    sha = existingEntry.sha256;
                } else {
                    if (maxHashesPerSweep > 0 && hashesComputed >= maxHashesPerSweep) {
                        break;
                    }
                    sha = computeSha256(f);
                    hashesComputed++;
                }
                if (sha.isEmpty()) continue;

                File canonical = canonicalByHash.get(sha);
                if (canonical == null) {
                    String recordedCanonical = ColgramStorageIndex.getCanonicalPathForHash(sha);
                    if (recordedCanonical != null && !recordedCanonical.equals(ColgramStorageIndex.normalizePath(f))) {
                        File recFile = new File(recordedCanonical);
                        if (recFile.isFile() && recFile.length() == len) {
                            canonical = recFile;
                        }
                    }
                }

                if (canonical == null || canonical.getAbsolutePath().equals(f.getAbsolutePath())) {
                    canonicalByHash.put(sha, f);
                    ColgramStorageIndex.registerCanonicalHash(sha, f.getAbsolutePath());
                    if (existingEntry == null) {
                        ColgramStorageIndex.recordEntry(f, len, len, sha, ColgramStorageIndex.STATE_SKIPPED_OPTIMAL);
                    }
                    continue;
                }

                // If already hardlinked to the same underlying inode/fileKey, ensure indexed without double-counting
                if (existingEntry != null && (existingEntry.state == ColgramStorageIndex.STATE_DEDUP_LINK
                        || existingEntry.state == ColgramStorageIndex.STATE_DEDUP_CAS)
                        && existingEntry.matchesFileOnDisk(f)) {
                    continue;
                }
                if (areSameUnderlyingFile(canonical, f)) {
                    ColgramStorageIndex.recordEntry(f, len, len, sha, ColgramStorageIndex.STATE_DEDUP_LINK);
                    continue;
                }

                // Attempt atomic POSIX/NIO hardlink replacement
                if (tryHardlinkReplace(canonical, f)) {
                    ColgramStorageIndex.recordEntry(f, len, f.length(), sha, ColgramStorageIndex.STATE_DEDUP_LINK);
                    result.dedupedFiles++;
                    result.dedupedSavedBytes += len;
                } else if (casDir != null) {
                    // On filesystems where hardlinks are unsupported (e.g. Android external FUSE),
                    // stage canonical copy in .colgram_cas/<ab>/<sha256> and record CAS dedup reference
                    File casBlob = ensureCasBlob(casDir, sha, canonical);
                    if (casBlob != null && casBlob.isFile()) {
                        ColgramStorageIndex.registerCanonicalHash(sha, casBlob.getAbsolutePath());
                        ColgramStorageIndex.recordEntry(f, len, f.length(), sha, ColgramStorageIndex.STATE_DEDUP_CAS);
                        result.dedupedFiles++;
                        result.dedupedSavedBytes += len;
                    }
                }
            }
        }
        if (casDir != null) {
            ColgramStorageIndex.flushToDirectory(casDir);
        } else {
            ColgramStorageIndex.flush();
        }
        return result;
    }

    private static void collectDedupCandidatesBySize(File dir, int depth, Set<String> activePaths,
                                                     long nowMs, Map<Long, List<File>> bySize) {
        if (dir == null || !dir.isDirectory() || depth > 5) return;
        if (depth > 0 && isExcludedDirectoryName(dir.getName())) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                collectDedupCandidatesBySize(child, depth + 1, activePaths, nowMs, bySize);
            } else if (!isExcludedFromOptimization(child, activePaths, nowMs)) {
                long len = child.length();
                if (len >= MIN_DEDUP_BYTES) {
                    bySize.computeIfAbsent(len, k -> new ArrayList<>()).add(child);
                }
            }
        }
    }

    public static boolean areSameUnderlyingFile(File a, File b) {
        if (a == null || b == null) return false;
        if (a.getAbsolutePath().equals(b.getAbsolutePath())) return true;
        try {
            BasicFileAttributes attrA = Files.readAttributes(a.toPath(), BasicFileAttributes.class);
            BasicFileAttributes attrB = Files.readAttributes(b.toPath(), BasicFileAttributes.class);
            Object keyA = attrA.fileKey();
            Object keyB = attrB.fileKey();
            if (keyA != null && keyA.equals(keyB)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Replaces {@code duplicateTarget} with a hardlink to {@code canonicalSource} atomically.
     */
    public static boolean tryHardlinkReplace(File canonicalSource, File duplicateTarget) {
        if (canonicalSource == null || duplicateTarget == null) return false;
        if (!canonicalSource.isFile() || !duplicateTarget.isFile()) return false;
        if (canonicalSource.getAbsolutePath().equals(duplicateTarget.getAbsolutePath())) return false;
        File tmpLink = new File(duplicateTarget.getAbsolutePath() + ".cglink");
        if (tmpLink.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tmpLink.delete();
        }
        try {
            boolean linked = false;
            try {
                Files.createLink(tmpLink.toPath(), canonicalSource.toPath());
                linked = tmpLink.isFile();
            } catch (Throwable nioErr) {
                try {
                    Class<?> osCls = Class.forName("android.system.Os");
                    osCls.getMethod("link", String.class, String.class)
                            .invoke(null, canonicalSource.getAbsolutePath(), tmpLink.getAbsolutePath());
                    linked = tmpLink.isFile();
                } catch (Throwable ignored) {
                }
            }
            if (!linked) return false;
            long origMtime = duplicateTarget.lastModified();
            if (atomicReplaceFile(tmpLink, duplicateTarget)) {
                if (origMtime > 0L) {
                    //noinspection ResultOfMethodCallIgnored
                    duplicateTarget.setLastModified(origMtime);
                }
                return true;
            }
            return false;
        } finally {
            if (tmpLink.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmpLink.delete();
            }
        }
    }

    private static File ensureCasBlob(File casDir, String sha256, File sourceFile) {
        if (casDir == null || sha256 == null || sha256.length() < 4 || sourceFile == null || !sourceFile.isFile()) {
            return null;
        }
        try {
            File shard = new File(casDir, sha256.substring(0, 2));
            if (!shard.exists()) shard.mkdirs();
            String ext = "";
            String name = sourceFile.getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot >= name.length() - 6) {
                ext = name.substring(dot).toLowerCase(Locale.ROOT);
            }
            File blob = new File(shard, sha256 + ext);
            if (blob.isFile() && blob.length() == sourceFile.length()) {
                return blob;
            }
            if (tryHardlinkOrCopy(sourceFile, blob)) {
                return blob;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean tryHardlinkOrCopy(File src, File dst) {
        if (src == null || dst == null || !src.isFile()) return false;
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try {
                if (dst.exists()) dst.delete();
                Files.createLink(dst.toPath(), src.toPath());
                if (dst.isFile() && dst.length() == src.length()) return true;
            } catch (Throwable ignored) {
            }
            File tmp = new File(dst.getAbsolutePath() + ".cgtmp");
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
                try { out.getFD().sync(); } catch (Throwable ignored) {}
            }
            return atomicReplaceFile(tmp, dst);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Deduplicating copy into a Colgram-managed directory (e.g. media_history or colgram_inbox).
     * If an identical file (by SHA-256 and byte length) already exists in {@code targetDir},
     * returns the existing file immediately without duplicating bytes on disk.
     */
    public static File copyOrDeduplicateToDir(File src, File targetDir, String destFileName) {
        if (src == null || !src.isFile() || src.length() <= 0L || targetDir == null) return null;
        if (!targetDir.exists() && !targetDir.mkdirs()) return null;
        long srcLen = src.length();
        String sha = computeSha256(src);
        if (!sha.isEmpty()) {
            File[] existing = targetDir.listFiles();
            if (existing != null) {
                for (File cand : existing) {
                    if (cand != null && cand.isFile() && cand.length() == srcLen) {
                        ColgramStorageIndex.Entry e = ColgramStorageIndex.getEntry(cand);
                        String candSha = (e != null && e.matchesFileOnDisk(cand) && !e.sha256.isEmpty())
                                ? e.sha256 : computeSha256(cand);
                        if (!candSha.isEmpty()) {
                            ColgramStorageIndex.recordEntry(cand, srcLen, srcLen, candSha, ColgramStorageIndex.STATE_SKIPPED_OPTIMAL);
                        }
                        if (sha.equals(candSha)) {
                            ColgramStorageIndex.recordEntry(cand, srcLen, srcLen, sha, ColgramStorageIndex.STATE_DEDUP_CAS);
                            ColgramStorageIndex.flush();
                            return cand;
                        }
                    }
                }
            }
        }
        File dst = new File(targetDir, destFileName);
        if (dst.exists()) {
            dst = new File(targetDir, System.currentTimeMillis() + "_" + Math.abs(destFileName.hashCode()) + "_" + destFileName);
        }
        if (tryHardlinkOrCopy(src, dst)) {
            if (!sha.isEmpty()) {
                ColgramStorageIndex.recordEntry(dst, srcLen, dst.length(), sha, ColgramStorageIndex.STATE_SKIPPED_OPTIMAL);
                ColgramStorageIndex.flush();
            }
            return dst;
        }
        return null;
    }

    /**
     * Prunes purely regenerable transient waste even when Unlimited Memory is ON:
     * 1. Pre-rasterized animated sticker bitmaps in {@code acache/*.pcache2} (>48h old or >128 MB LRU cap).
     * 2. Abandoned {@code *.temp}, {@code *.preload}, {@code *.cgtmp} older than 7 days (excluding active paths).
     * 3. Unreferenced temporary SAF import copies in {@code colgram_inbox} older than 7 days.
     */
    public static long pruneRegenerableWaste(Collection<File> roots, File inboxDir,
                                             Set<String> activePaths, long nowMs) {
        long reclaimed = 0L;
        if (roots != null) {
            for (File root : roots) {
                if (root == null || !root.isDirectory()) continue;
                File acache = "acache".equalsIgnoreCase(root.getName()) ? root : new File(root, "acache");
                if (acache.isDirectory()) {
                    reclaimed += pruneAcacheDirectory(acache, activePaths, nowMs);
                }
                reclaimed += pruneStaleTempFilesRecursive(root, 0, activePaths, nowMs);
            }
        }
        if (inboxDir != null && inboxDir.isDirectory()) {
            File[] inboxFiles = inboxDir.listFiles();
            if (inboxFiles != null) {
                for (File f : inboxFiles) {
                    if (f == null || !f.isFile() || ".nomedia".equals(f.getName())) continue;
                    if (isPathActive(f, activePaths)) continue;
                    long age = nowMs > 0L && f.lastModified() > 0L ? (nowMs - f.lastModified()) : 0L;
                    if (age >= STALE_TEMP_AGE_MS) {
                        long len = f.length();
                        if (f.delete()) {
                            reclaimed += len;
                            ColgramStorageIndex.removeEntry(f.getAbsolutePath());
                        }
                    }
                }
            }
        }
        return reclaimed;
    }

    public static long pruneAcacheDirectory(File acacheDir, Set<String> activePaths, long nowMs) {
        if (acacheDir == null || !acacheDir.isDirectory()) return 0L;
        long reclaimed = 0L;
        List<File> surviving = new ArrayList<>();
        long survivingBytes = 0L;
        File[] files = acacheDir.listFiles();
        if (files == null) return 0L;
        for (File f : files) {
            if (f == null || ".nomedia".equals(f.getName())) continue;
            if (f.isDirectory()) {
                reclaimed += pruneAcacheDirectory(f, activePaths, nowMs);
                continue;
            }
            if (isPathActive(f, activePaths)) continue;
            long len = f.length();
            long age = (nowMs > 0L && f.lastModified() > 0L) ? (nowMs - f.lastModified()) : 0L;
            if (age >= ACACHE_MAX_AGE_MS) {
                if (f.delete()) {
                    reclaimed += len;
                }
            } else {
                surviving.add(f);
                survivingBytes += len;
            }
        }
        if (survivingBytes > ACACHE_MAX_BYTES) {
            Collections.sort(surviving, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (File f : surviving) {
                if (survivingBytes <= ACACHE_MAX_BYTES) break;
                long len = f.length();
                if (f.delete()) {
                    survivingBytes -= len;
                    reclaimed += len;
                }
            }
        }
        return reclaimed;
    }

    private static long pruneStaleTempFilesRecursive(File dir, int depth, Set<String> activePaths, long nowMs) {
        if (dir == null || !dir.isDirectory() || depth > 5) return 0L;
        File[] children = dir.listFiles();
        if (children == null) return 0L;
        long reclaimed = 0L;
        for (File child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                if (!"acache".equalsIgnoreCase(child.getName())) {
                    reclaimed += pruneStaleTempFilesRecursive(child, depth + 1, activePaths, nowMs);
                }
            } else {
                String lower = child.getName().toLowerCase(Locale.ROOT);
                boolean isCgtmp = lower.endsWith(".cgtmp") || lower.endsWith(".cglink");
                boolean isTemp = lower.endsWith(".temp") || lower.endsWith(".preload");
                if (!isCgtmp && !isTemp) continue;
                if (isPathActive(child, activePaths)) continue;
                long age = (nowMs > 0L && child.lastModified() > 0L) ? (nowMs - child.lastModified()) : 0L;
                long maxAge = isCgtmp ? ORPHAN_TMP_AGE_MS : STALE_TEMP_AGE_MS;
                if (age >= maxAge) {
                    long len = child.length();
                    if (child.delete()) {
                        reclaimed += len;
                    }
                }
            }
        }
        return reclaimed;
    }

    /**
     * Emergency Low-Disk Virtual Cloud Guard:
     * Only activates when physical free disk space drops below {@code max(1.5 GB, 5% of totalBytes)}.
     * Step 1: Reclaims regenerable waste (acache, stale .temp/.cgtmp, old inbox).
     * Step 2: Runs content deduplication and image compression.
     * Step 3: ONLY if free space is still < 1.0 GB and {@code smartCloudOffloadEnabled} is true,
     *         offloads oldest cold (>24h), large (>5 MB) cloud-backed cache blobs while strictly
     *         preserving Anti-Delete, media_history, colgram_vault, .enc secret media, and wallpapers.
     */
    public static SweepResult runEmergencyLowDiskGuard(Collection<File> roots,
                                                       File casDir,
                                                       File inboxDir,
                                                       Set<String> activePaths,
                                                       long nowMs,
                                                       long availableBytes,
                                                       long totalBytes,
                                                       boolean smartCloudOffloadEnabled,
                                                       int preset,
                                                       ImageTranscoder transcoder,
                                                       FileProtectionChecker protectionChecker) {
        SweepResult total = new SweepResult();
        long triggerThreshold = Math.max(EMERGENCY_LOW_DISK_BYTES, totalBytes > 0L ? (totalBytes / 20L) : EMERGENCY_LOW_DISK_BYTES);
        if (availableBytes >= triggerThreshold) {
            return total;
        }

        // Tier 1: Reclaim regenerable transient waste
        long wasteFreed = pruneRegenerableWaste(roots, inboxDir, activePaths, nowMs);
        total.wasteReclaimedBytes += wasteFreed;
        long currentFree = availableBytes + wasteFreed;

        // Tier 2: Deduplicate and compress
        SweepResult dedup = runDeduplicationSweep(roots, casDir, activePaths, nowMs, 120);
        total.add(dedup);
        currentFree += dedup.dedupedSavedBytes;

        SweepResult comp = runImageCompressionSweep(roots, preset, activePaths, nowMs, 64, transcoder);
        total.add(comp);
        currentFree += comp.compressedSavedBytes;

        // Tier 3: Virtual Cloud Offload ONLY if still critically below 1.0 GB free
        if (currentFree < CRITICAL_CLOUD_OFFLOAD_BYTES && smartCloudOffloadEnabled && roots != null) {
            List<File> coldLargeCandidates = new ArrayList<>();
            for (File root : roots) {
                collectColdCloudOffloadCandidates(root, 0, activePaths, nowMs, protectionChecker, coldLargeCandidates);
            }
            Collections.sort(coldLargeCandidates, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (File candidate : coldLargeCandidates) {
                if (currentFree >= CRITICAL_CLOUD_OFFLOAD_BYTES) break;
                long len = candidate.length();
                if (candidate.delete()) {
                    ColgramStorageIndex.removeEntry(candidate.getAbsolutePath());
                    currentFree += len;
                    total.cloudOffloadedFiles++;
                    total.cloudOffloadedBytes += len;
                }
            }
            ColgramStorageIndex.flush();
        }
        return total;
    }

    private static void collectColdCloudOffloadCandidates(File dir, int depth,
                                                          Set<String> activePaths, long nowMs,
                                                          FileProtectionChecker protectionChecker,
                                                          List<File> out) {
        if (dir == null || !dir.isDirectory() || depth > 5) return;
        String dn = dir.getName().toLowerCase(Locale.ROOT);
        if (isExcludedDirectoryName(dn)
                || dn.contains("media_history")
                || dn.contains("colgram_wallpapers")
                || dn.contains("colgram_vault")
                || dn.contains("colgram_preserved")
                || dn.contains("colgram_inbox")
                || dn.contains("exports")
                || dn.contains("plugins")) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                collectColdCloudOffloadCandidates(child, depth + 1, activePaths, nowMs, protectionChecker, out);
            } else if (child.isFile()) {
                if (isEligibleForCloudOffload(child, activePaths, nowMs, protectionChecker)) {
                    out.add(child);
                }
            }
        }
    }

    public static boolean isEligibleForCloudOffload(File file, Set<String> activePaths,
                                                    long nowMs, FileProtectionChecker protectionChecker) {
        if (file == null || !file.isFile()) return false;
        if (file.length() < MIN_CLOUD_OFFLOAD_FILE_BYTES) return false;
        String name = file.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.startsWith(".") || lower.startsWith("q_") || lower.contains("thumb")
                || lower.endsWith(".enc") || lower.endsWith(".key")
                || lower.endsWith(".db") || lower.endsWith(".db-wal") || lower.endsWith(".db-shm")
                || lower.endsWith(".gguf") || lower.endsWith(".py") || lower.endsWith(".json")
                || lower.endsWith(".gz")) {
            return false;
        }
        String normPath = ColgramStorageIndex.normalizePath(file).toLowerCase(Locale.ROOT);
        if (normPath.contains("/media_history/")
                || normPath.contains("/colgram_wallpapers/")
                || normPath.contains("/colgram_vault/")
                || normPath.contains("/colgram_preserved/")
                || normPath.contains("/colgram_inbox/")
                || normPath.contains("/.colgram_cas/")
                || normPath.contains("/koliti")
                || normPath.contains("/llama")) {
            return false;
        }
        if (isPathActive(file, activePaths)) return false;
        if (protectionChecker != null && protectionChecker.isProtected(file)) return false;
        long mtime = file.lastModified();
        return nowMs <= 0L || mtime <= 0L || (nowMs - mtime) >= MIN_CLOUD_OFFLOAD_AGE_MS;
    }

    /** Writes UTF-8 bytes compressed with GZIP atomically to {@code targetGzFile}. */
    public static boolean writeGzipBytesAtomic(File targetGzFile, byte[] rawBytes) {
        if (targetGzFile == null || rawBytes == null) return false;
        File parent = targetGzFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(targetGzFile.getAbsolutePath() + ".cgtmp");
        try {
            try (GZIPOutputStream gzos = new GZIPOutputStream(new FileOutputStream(tmp))) {
                gzos.write(rawBytes);
                gzos.finish();
                gzos.flush();
            }
            return atomicReplaceFile(tmp, targetGzFile);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /** Reads either a GZIP-compressed file or plaintext file transparently into a byte array. */
    public static byte[] readGzipOrPlainBytes(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            boolean isGzip = false;
            try (FileInputStream probe = new FileInputStream(file)) {
                int b0 = probe.read();
                int b1 = probe.read();
                isGzip = (b0 == 0x1f && b1 == 0x8b);
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = isGzip
                    ? new GZIPInputStream(new FileInputStream(file))
                    : new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
            }
            return bos.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean atomicReplaceFile(File tmpSource, File target) {
        if (tmpSource == null || target == null || !tmpSource.isFile()) return false;
        if (tmpSource.renameTo(target)) {
            return true;
        }
        File backup = new File(target.getAbsolutePath() + ".cgorig");
        if (backup.exists()) {
            //noinspection ResultOfMethodCallIgnored
            backup.delete();
        }
        boolean movedOrig = !target.exists() || target.renameTo(backup);
        if (movedOrig && tmpSource.renameTo(target)) {
            if (backup.exists()) {
                //noinspection ResultOfMethodCallIgnored
                backup.delete();
            }
            return true;
        }
        if (backup.exists() && !target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            backup.renameTo(target);
        }
        // Final stream copy fallback (e.g. Windows file-lock quirk)
        try (FileInputStream in = new FileInputStream(tmpSource);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            if (backup.exists()) {
                //noinspection ResultOfMethodCallIgnored
                backup.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            tmpSource.delete();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Queries active file locks from AutoDeleteMediaTask.usingFilePaths via reflection when available. */
    @SuppressWarnings("unchecked")
    public static Set<String> getActiveTelegramFilePaths() {
        try {
            Class<?> adt = Class.forName("org.telegram.messenger.AutoDeleteMediaTask");
            Object set = adt.getField("usingFilePaths").get(null);
            if (set instanceof Set) {
                return new HashSet<>((Set<String>) set);
            }
        } catch (Throwable ignored) {
        }
        return Collections.emptySet();
    }
}
