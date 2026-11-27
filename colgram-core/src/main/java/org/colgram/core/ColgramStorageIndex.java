package org.colgram.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Pure-Java persistent index for Colgram's Infinite Storage optimizer.
 *
 * Tracks every inspected, compressed, or content-deduplicated file so:
 * 1. FileLoadOperation never treats an in-place compressed or deduplicated file as a corrupt
 *    size-mismatch download (eliminating infinite re-download loops and offline media loss).
 * 2. Background compression sweeps skip already-compressed and already-checked optimal files in O(1),
 *    preventing multi-generation re-encoding loss and fixing the 48-file starvation bug.
 * 3. Content-addressed deduplication (.colgram_cas / hardlinks) tracks SHA-256 digests and references.
 *
 * Strictly pure-Java (GZIP TSV manifest) — never opens Telegram's SQLite databases.
 */
public final class ColgramStorageIndex {

    public static final int STATE_SKIPPED_OPTIMAL = 0;
    public static final int STATE_COMPRESSED = 1;
    public static final int STATE_DEDUP_LINK = 2;
    public static final int STATE_DEDUP_CAS = 3;
    public static final int STATE_DEHYDRATED = 4;

    private static final String INDEX_FILE_NAME = "colgram_storage_index_v1.tsv.gz";

    public static final class Entry {
        public final String path;
        public final long origSize;
        public final long storedSize;
        public final long mtime;
        public final String sha256;
        public final int state;
        public final long updatedAtMs;
        public final long dialogId;
        public final int msgId;
        public final String previewPath;

        public Entry(String path, long origSize, long storedSize, long mtime,
                     String sha256, int state, long updatedAtMs) {
            this(path, origSize, storedSize, mtime, sha256, state, updatedAtMs, 0L, 0, "");
        }

        public Entry(String path, long origSize, long storedSize, long mtime,
                     String sha256, int state, long updatedAtMs,
                     long dialogId, int msgId, String previewPath) {
            this.path = normalizePath(path);
            this.origSize = origSize;
            this.storedSize = storedSize;
            this.mtime = mtime;
            this.sha256 = sha256 == null ? "" : sha256;
            this.state = state;
            this.updatedAtMs = updatedAtMs;
            this.dialogId = dialogId;
            this.msgId = msgId;
            this.previewPath = previewPath == null ? "" : normalizePath(previewPath);
        }

        public long savedBytes() {
            return Math.max(0L, origSize - storedSize);
        }

        public boolean isDehydrated() {
            return state == STATE_DEHYDRATED;
        }

        public boolean matchesFileOnDisk(File f) {
            if (state == STATE_DEHYDRATED) return false;
            if (f == null || !f.isFile()) return false;
            long len = f.length();
            long mod = f.lastModified();
            if (len != storedSize) return false;
            // Allow 2s filesystem timestamp granularity
            return mtime <= 0L || mod <= 0L || Math.abs(mod - mtime) <= 2000L;
        }
    }

    private static final Object IO_LOCK = new Object();
    private static final ConcurrentHashMap<String, Entry> entriesByPath = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Entry> entriesByFileName = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> canonicalPathBySha256 = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Set<String>> refsBySha256 = new ConcurrentHashMap<>();

    private static volatile File indexFile;
    private static volatile boolean loaded = false;
    private static volatile boolean dirty = false;

    private ColgramStorageIndex() {
    }

    public static String normalizePath(File f) {
        if (f == null) return "";
        return normalizePath(f.getAbsolutePath());
    }

    public static String normalizePath(String p) {
        if (p == null || p.isEmpty()) return "";
        String norm = p.replace('\\', '/');
        while (norm.length() > 1 && norm.endsWith("/")) {
            norm = norm.substring(0, norm.length() - 1);
        }
        return norm;
    }

    private static String fileNameOf(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isEmpty()) return "";
        int slash = normalizedPath.lastIndexOf('/');
        return slash >= 0 ? normalizedPath.substring(slash + 1) : normalizedPath;
    }

    /** Initializes and loads the persistent index from baseDir (e.g. context.getFilesDir() or sandbox root). */
    public static void init(File baseDir) {
        if (baseDir == null) return;
        synchronized (IO_LOCK) {
            File target = new File(baseDir, INDEX_FILE_NAME);
            if (loaded && indexFile != null && indexFile.equals(target)) {
                return;
            }
            if (!baseDir.exists()) {
                baseDir.mkdirs();
            }
            indexFile = target;
            loadLocked(target);
            loaded = true;
        }
    }

    private static void ensureLazyLoadedFromCandidate(File candidateFile) {
        if (loaded || candidateFile == null) return;
        File dir = candidateFile.isDirectory() ? candidateFile : candidateFile.getParentFile();
        for (int depth = 0; depth < 4 && dir != null; depth++, dir = dir.getParentFile()) {
            File direct = new File(dir, INDEX_FILE_NAME);
            if (direct.isFile()) {
                init(dir);
                return;
            }
            File casDir = new File(dir, ".colgram_cas");
            File inCas = new File(casDir, INDEX_FILE_NAME);
            if (inCas.isFile()) {
                init(casDir);
                return;
            }
        }
    }

    /**
     * Returns true if {@code file} was compressed or deduplicated by Colgram and its current
     * byte length on disk matches the recorded stored size (or it is a valid Colgram-managed file).
     *
     * Used by FileLoadOperation to prevent deleting & re-downloading compressed cached media.
     */
    public static boolean isManagedModifiedFile(File file) {
        if (file == null || !file.isFile()) return false;
        long currentLen = file.length();
        if (currentLen <= 0L) return false;
        ensureLazyLoadedFromCandidate(file);

        String norm = normalizePath(file);
        Entry e = entriesByPath.get(norm);
        if (e == null) {
            String name = fileNameOf(norm);
            if (!name.isEmpty()) {
                e = entriesByFileName.get(name);
            }
        }
        if (e != null) {
            if ((e.state == STATE_COMPRESSED || e.state == STATE_DEDUP_LINK || e.state == STATE_DEDUP_CAS)
                    && e.storedSize == currentLen) {
                return true;
            }
        }
        // Fallback: if path is inside Colgram's protected stores (.colgram_cas, media_history,
        // colgram_wallpapers, colgram_vault, colgram_preserved), never let FileLoadOperation delete it.
        String lower = norm.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("/.colgram_cas/")
                || lower.contains("/media_history/")
                || lower.contains("/colgram_wallpapers/")
                || lower.contains("/colgram_vault/")
                || lower.contains("/colgram_preserved/");
    }

    /**
     * Returns true if the file has already been inspected/processed and its disk size & mtime
     * match the index entry (so background sweeps can skip it without re-decoding).
     */
    public static boolean isAlreadyProcessed(File file) {
        if (file == null || !file.isFile()) return false;
        ensureLazyLoadedFromCandidate(file);
        String norm = normalizePath(file);
        Entry e = entriesByPath.get(norm);
        return e != null && e.matchesFileOnDisk(file);
    }

    public static Entry getEntry(File file) {
        if (file == null) return null;
        ensureLazyLoadedFromCandidate(file);
        return entriesByPath.get(normalizePath(file));
    }

    public static Entry getEntry(String path) {
        if (path == null || path.isEmpty()) return null;
        return entriesByPath.get(normalizePath(path));
    }

    public static void recordEntry(File file, long origSize, long storedSize, String sha256, int state) {
        if (file == null) return;
        recordEntry(normalizePath(file), origSize, storedSize, file.lastModified(), sha256, state);
    }

    public static void recordEntry(String path, long origSize, long storedSize, long mtime, String sha256, int state) {
        recordEntry(path, origSize, storedSize, mtime, sha256, state, 0L, 0, "");
    }

    public static void recordEntry(String path, long origSize, long storedSize, long mtime,
                                   String sha256, int state, long dialogId, int msgId, String previewPath) {
        String norm = normalizePath(path);
        if (norm.isEmpty()) return;
        Entry entry = new Entry(norm, origSize, storedSize, mtime, sha256, state,
                System.currentTimeMillis(), dialogId, msgId, previewPath);
        entriesByPath.put(norm, entry);
        String name = fileNameOf(norm);
        if (!name.isEmpty()) {
            entriesByFileName.put(name, entry);
        }
        if (sha256 != null && !sha256.isEmpty() && state != STATE_DEHYDRATED) {
            canonicalPathBySha256.putIfAbsent(sha256, norm);
            refsBySha256.computeIfAbsent(sha256, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                    .add(norm);
        }
        dirty = true;
    }

    /** Records a Tier-0 dehydrated stub keeping virtual origSize/SHA/preview link while physical size is 0. */
    public static void markDehydrated(File file, long origSize, long dialogId, int msgId, String previewPath) {
        if (file == null) return;
        markDehydrated(normalizePath(file), origSize, "", dialogId, msgId, previewPath);
    }

    public static void markDehydrated(String path, long origSize, String sha256,
                                      long dialogId, int msgId, String previewPath) {
        String norm = normalizePath(path);
        if (norm.isEmpty()) return;
        Entry prev = entriesByPath.get(norm);
        long resolvedOrig = origSize > 0L ? origSize : (prev != null ? Math.max(prev.origSize, prev.storedSize) : 0L);
        String resolvedSha = (sha256 != null && !sha256.isEmpty()) ? sha256 : (prev != null ? prev.sha256 : "");
        long resolvedDialog = dialogId != 0L ? dialogId : (prev != null ? prev.dialogId : 0L);
        int resolvedMsg = msgId != 0 ? msgId : (prev != null ? prev.msgId : 0);
        String resolvedPreview = (previewPath != null && !previewPath.isEmpty())
                ? previewPath : (prev != null ? prev.previewPath : "");
        if (prev != null && !prev.sha256.isEmpty()) {
            Set<String> refs = refsBySha256.get(prev.sha256);
            if (refs != null) {
                refs.remove(norm);
            }
        }
        recordEntry(norm, resolvedOrig, 0L, 0L, resolvedSha, STATE_DEHYDRATED,
                resolvedDialog, resolvedMsg, resolvedPreview);
    }

    /** Marks a previously dehydrated file as re-hydrated on disk. */
    public static void markHydrated(File file) {
        if (file == null) return;
        String norm = normalizePath(file);
        if (norm.isEmpty()) return;
        Entry prev = entriesByPath.get(norm);
        long len = file.isFile() ? Math.max(0L, file.length()) : 0L;
        if (prev != null && prev.state == STATE_DEHYDRATED) {
            recordEntry(norm, Math.max(prev.origSize, len), len, file.lastModified(),
                    prev.sha256, STATE_SKIPPED_OPTIMAL, prev.dialogId, prev.msgId, prev.previewPath);
        } else if (prev == null && len > 0L) {
            recordEntry(file, len, len, "", STATE_SKIPPED_OPTIMAL);
        }
    }

    public static Entry getDehydratedEntry(String path) {
        if (path == null || path.isEmpty()) return null;
        Entry e = entriesByPath.get(normalizePath(path));
        if (e == null) {
            String fn = fileNameOf(normalizePath(path));
            if (!fn.isEmpty()) {
                e = entriesByFileName.get(fn);
            }
        }
        return (e != null && e.isDehydrated()) ? e : null;
    }

    public static long getTotalCompressionSavedBytes() {
        long saved = 0L;
        for (Entry e : entriesByPath.values()) {
            if (e != null && (e.state == STATE_COMPRESSED || e.state == STATE_DEDUP_LINK || e.state == STATE_DEDUP_CAS)) {
                saved += e.savedBytes();
            }
        }
        return saved;
    }

    public static long getTotalDehydratedBytes() {
        long total = 0L;
        for (Entry e : entriesByPath.values()) {
            if (e != null && e.state == STATE_DEHYDRATED) {
                total += Math.max(0L, e.origSize);
            }
        }
        return total;
    }

    public static int getDehydratedCount() {
        int count = 0;
        for (Entry e : entriesByPath.values()) {
            if (e != null && e.state == STATE_DEHYDRATED) {
                count++;
            }
        }
        return count;
    }

    public static long getTotalCatalogVirtualBytes() {
        long total = 0L;
        for (Entry e : entriesByPath.values()) {
            if (e != null) {
                total += Math.max(e.origSize, e.storedSize);
            }
        }
        return total;
    }

    public static void registerCanonicalHash(String sha256, String canonicalPath) {
        if (sha256 == null || sha256.isEmpty() || canonicalPath == null || canonicalPath.isEmpty()) return;
        String norm = normalizePath(canonicalPath);
        canonicalPathBySha256.put(sha256, norm);
        refsBySha256.computeIfAbsent(sha256, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(norm);
        dirty = true;
    }

    public static String getCanonicalPathForHash(String sha256) {
        if (sha256 == null || sha256.isEmpty()) return null;
        String path = canonicalPathBySha256.get(sha256);
        if (path == null) return null;
        File f = new File(path);
        if (f.isFile() && f.length() > 0L) {
            return path;
        }
        canonicalPathBySha256.remove(sha256, path);
        return null;
    }

    public static int getRefCountForHash(String sha256) {
        if (sha256 == null || sha256.isEmpty()) return 0;
        Set<String> refs = refsBySha256.get(sha256);
        return refs == null ? 0 : refs.size();
    }

    public static void removeEntry(String path) {
        String norm = normalizePath(path);
        if (norm.isEmpty()) return;
        Entry removed = entriesByPath.remove(norm);
        String name = fileNameOf(norm);
        if (!name.isEmpty()) {
            entriesByFileName.remove(name, removed);
        }
        if (removed != null && !removed.sha256.isEmpty()) {
            Set<String> refs = refsBySha256.get(removed.sha256);
            if (refs != null) {
                refs.remove(norm);
            }
        }
        dirty = true;
    }

    public static int size() {
        return entriesByPath.size();
    }

    public static List<Entry> allEntries() {
        return new ArrayList<>(entriesByPath.values());
    }

    /** Flushes any pending index updates to disk atomically using GZIP compression. */
    public static void flush() {
        if (!dirty || indexFile == null) return;
        synchronized (IO_LOCK) {
            if (!dirty || indexFile == null) return;
            saveLocked(indexFile);
            dirty = false;
        }
    }

    /** Also mirrors the index into an auxiliary directory (e.g. .colgram_cas) for context-free lookup. */
    public static void flushToDirectory(File dir) {
        if (dir == null) return;
        synchronized (IO_LOCK) {
            if (!dir.exists()) dir.mkdirs();
            File target = new File(dir, INDEX_FILE_NAME);
            if (indexFile == null) {
                indexFile = target;
                loaded = true;
            }
            saveLocked(target);
            if (indexFile != null && !indexFile.equals(target)) {
                saveLocked(indexFile);
            }
            dirty = false;
        }
    }

    private static void loadLocked(File file) {
        if (file == null || !file.isFile()) return;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(new FileInputStream(file)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int t1 = line.indexOf('\t');
                if (t1 < 0) continue;
                int t2 = line.indexOf('\t', t1 + 1);
                if (t2 < 0) continue;
                int t3 = line.indexOf('\t', t2 + 1);
                if (t3 < 0) continue;
                int t4 = line.indexOf('\t', t3 + 1);
                if (t4 < 0) continue;
                int t5 = line.indexOf('\t', t4 + 1);
                if (t5 < 0) continue;
                int t6 = line.indexOf('\t', t5 + 1);
                if (t6 < 0) continue;
                int t7 = line.indexOf('\t', t6 + 1);
                String path = line.substring(0, t1);
                long origSize = parseLongSafe(line.substring(t1 + 1, t2), 0L);
                long storedSize = parseLongSafe(line.substring(t2 + 1, t3), 0L);
                long mtime = parseLongSafe(line.substring(t3 + 1, t4), 0L);
                String sha = line.substring(t4 + 1, t5);
                int state = (int) parseLongSafe(line.substring(t5 + 1, t6), 0L);
                long updated = parseLongSafe(t7 >= 0 ? line.substring(t6 + 1, t7) : line.substring(t6 + 1), 0L);
                long dialogId = 0L;
                int msgId = 0;
                String previewPath = "";
                if (t7 >= 0) {
                    int t8 = line.indexOf('\t', t7 + 1);
                    if (t8 >= 0) {
                        dialogId = parseLongSafe(line.substring(t7 + 1, t8), 0L);
                        int t9 = line.indexOf('\t', t8 + 1);
                        if (t9 >= 0) {
                            msgId = (int) parseLongSafe(line.substring(t8 + 1, t9), 0L);
                            previewPath = line.substring(t9 + 1);
                        } else {
                            msgId = (int) parseLongSafe(line.substring(t8 + 1), 0L);
                        }
                    } else {
                        dialogId = parseLongSafe(line.substring(t7 + 1), 0L);
                    }
                }
                Entry e = new Entry(path, origSize, storedSize, mtime, sha, state, updated, dialogId, msgId, previewPath);
                entriesByPath.put(e.path, e);
                String fn = fileNameOf(e.path);
                if (!fn.isEmpty()) {
                    entriesByFileName.put(fn, e);
                }
                if (!e.sha256.isEmpty() && e.state != STATE_DEHYDRATED) {
                    canonicalPathBySha256.putIfAbsent(e.sha256, e.path);
                    refsBySha256.computeIfAbsent(e.sha256, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                            .add(e.path);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void saveLocked(File file) {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try {
            try (BufferedWriter bw = new BufferedWriter(
                    new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(tmp)), StandardCharsets.UTF_8))) {
                bw.write("#colgram_storage_index_v1\n");
                for (Map.Entry<String, Entry> me : entriesByPath.entrySet()) {
                    Entry e = me.getValue();
                    if (e == null || e.path.isEmpty()) continue;
                    String cleanPath = e.path.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
                    bw.write(cleanPath);
                    bw.write('\t');
                    bw.write(Long.toString(e.origSize));
                    bw.write('\t');
                    bw.write(Long.toString(e.storedSize));
                    bw.write('\t');
                    bw.write(Long.toString(e.mtime));
                    bw.write('\t');
                    bw.write(e.sha256);
                    bw.write('\t');
                    bw.write(Integer.toString(e.state));
                    bw.write('\t');
                    bw.write(Long.toString(e.updatedAtMs));
                    if (e.dialogId != 0L || e.msgId != 0 || !e.previewPath.isEmpty()) {
                        bw.write('\t');
                        bw.write(Long.toString(e.dialogId));
                        bw.write('\t');
                        bw.write(Integer.toString(e.msgId));
                        bw.write('\t');
                        bw.write(e.previewPath.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
                    }
                    bw.write('\n');
                }
                bw.flush();
            }
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            if (!tmp.renameTo(file)) {
                try (FileInputStream in = new FileInputStream(tmp);
                     FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable ignored) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static long parseLongSafe(String s, long fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /** Clears all state for deterministic JVM unit tests. */
    public static void resetForTest() {
        synchronized (IO_LOCK) {
            entriesByPath.clear();
            entriesByFileName.clear();
            canonicalPathBySha256.clear();
            refsBySha256.clear();
            indexFile = null;
            loaded = false;
            dirty = false;
        }
    }
}
