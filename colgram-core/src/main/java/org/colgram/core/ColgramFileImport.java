package org.colgram.core;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reliable file picker + sandboxed URI importer for Telegram attachments.
 * Handles large files (up to 2000 MiB / 4000 MiB Premium) without holding UI/globalQueue threads,
 * opens document descriptors synchronously in handleActivityResult before temporary grants expire,
 * validates OpenableColumns.SIZE and StatFs available bytes before copying, and skips SHA-256
 * deduplication above 32 MiB to prevent minutes-long hashing stalls.
 */
public final class ColgramFileImport {

    public static final int COLGRAM_PICK_REQUEST = 0x0C01;
    public static final long DEFAULT_MAX_FILE_BYTES = 2000L * 1024L * 1024L;
    public static final long PREMIUM_MAX_FILE_BYTES = 4000L * 1024L * 1024L;
    public static final long LARGE_FILE_DEDUP_SKIP_BYTES = 32L * 1024L * 1024L;
    public static final long STATFS_HEADROOM_BYTES = 64L * 1024L * 1024L;
    public static final long PROGRESS_THRESHOLD_BYTES = 25L * 1024L * 1024L;
    private static final int COPY_BUFFER_BYTES = 512 * 1024;
    private static final long STALE_TEMP_AGE_MS = 6L * 60L * 60L * 1000L;

    public interface Callback {
        void onResult(List<String> paths);
    }

    public interface ProgressListener {
        void onProgress(int completedCount, int totalCount, String currentName, long bytesCopied, long totalBytes);

        void onRejected(String fileName, String reason);
    }

    private static final class PickSession {
        final Callback callback;
        final long maxFileSizeBytes;
        final ProgressListener progressListener;
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        PickSession(Callback callback, long maxFileSizeBytes, ProgressListener progressListener) {
            this.callback = callback;
            this.maxFileSizeBytes = maxFileSizeBytes > 0L ? maxFileSizeBytes : DEFAULT_MAX_FILE_BYTES;
            this.progressListener = progressListener;
        }
    }

    private static final class StagedUri {
        final Uri uri;
        final AssetFileDescriptor afd;
        final String displayName;
        final long knownSize;

        StagedUri(Uri uri, AssetFileDescriptor afd, String displayName, long knownSize) {
            this.uri = uri;
            this.afd = afd;
            this.displayName = displayName;
            this.knownSize = knownSize;
        }

        void closeQuietly() {
            if (afd != null) {
                try {
                    afd.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final ExecutorService IMPORT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "colgram-file-import");
        t.setDaemon(true);
        return t;
    });

    private static volatile Handler mainHandler;
    private static final AtomicReference<PickSession> pendingSession = new AtomicReference<>();
    private static final AtomicReference<PickSession> activeImportSession = new AtomicReference<>();
    private static final Set<String> activeTempNames = ConcurrentHashMap.newKeySet();

    private ColgramFileImport() {
    }

    private static void postToMain(Runnable r) {
        if (r == null) return;
        try {
            Handler h = mainHandler;
            if (h == null) {
                h = new Handler(Looper.getMainLooper());
                mainHandler = h;
            }
            h.post(r);
        } catch (Throwable ignored) {
            r.run();
        }
    }

    public static boolean shouldSkipDedupHash(long fileSizeBytes) {
        return fileSizeBytes > LARGE_FILE_DEDUP_SKIP_BYTES;
    }

    public static boolean isSizeWithinLimit(long knownSizeBytes, long maxFileSizeBytes) {
        if (maxFileSizeBytes <= 0L || knownSizeBytes <= 0L) {
            return true;
        }
        return knownSizeBytes < maxFileSizeBytes;
    }

    public static boolean hasSufficientSpace(long availableBytes, long requiredFileBytes) {
        if (availableBytes < 0L || requiredFileBytes <= 0L) {
            return true;
        }
        if (requiredFileBytes > Long.MAX_VALUE - STATFS_HEADROOM_BYTES) {
            return false;
        }
        return availableBytes >= requiredFileBytes + STATFS_HEADROOM_BYTES;
    }

    public static boolean isPathInsideInbox(File inboxDir, File candidate) {
        if (inboxDir == null || candidate == null) {
            return false;
        }
        try {
            String inboxCanon = inboxDir.getCanonicalPath();
            String candCanon = candidate.getCanonicalPath();
            String prefix = inboxCanon.endsWith(File.separator) ? inboxCanon : inboxCanon + File.separator;
            return candCanon.startsWith(prefix);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void cancelActiveImport() {
        PickSession running = activeImportSession.get();
        if (running != null) {
            running.cancelled.set(true);
        }
    }

    public static void pickFiles(Activity activity, String mimeType, boolean allowMultiple, Callback callback) {
        pickFiles(activity, mimeType, allowMultiple, DEFAULT_MAX_FILE_BYTES, callback, null);
    }

    public static void pickFiles(Activity activity, String mimeType, boolean allowMultiple,
                                 long maxFileSizeBytes, Callback callback) {
        pickFiles(activity, mimeType, allowMultiple, maxFileSizeBytes, callback, null);
    }

    public static void pickFiles(Activity activity, String mimeType, boolean allowMultiple,
                                 long maxFileSizeBytes, Callback callback, ProgressListener progressListener) {
        if (activity == null || callback == null) {
            return;
        }
        pendingSession.set(new PickSession(callback, maxFileSizeBytes, progressListener));
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType((mimeType != null && !mimeType.isEmpty()) ? mimeType : "*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, false);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, COLGRAM_PICK_REQUEST);
        } catch (Throwable t) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType(intent.getType());
            fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                activity.startActivityForResult(fallback, COLGRAM_PICK_REQUEST);
            } catch (Throwable t2) {
                PickSession failed = pendingSession.getAndSet(null);
                if (failed != null) {
                    deliverResult(failed.callback, new ArrayList<String>());
                }
            }
        }
    }

    public static boolean handleActivityResult(final Context context, int requestCode, int resultCode, Intent data) {
        if (requestCode != COLGRAM_PICK_REQUEST) {
            return false;
        }
        final PickSession session = pendingSession.getAndSet(null);
        if (session == null) {
            return true;
        }
        if (resultCode != Activity.RESULT_OK || data == null || context == null) {
            deliverResult(session.callback, new ArrayList<String>());
            return true;
        }

        final List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            int count = clip.getItemCount();
            for (int i = 0; i < count; i++) {
                Uri u = clip.getItemAt(i) != null ? clip.getItemAt(i).getUri() : null;
                if (u != null) {
                    uris.add(u);
                }
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        if (uris.isEmpty()) {
            deliverResult(session.callback, new ArrayList<String>());
            return true;
        }

        final Context appCtx = context.getApplicationContext();
        final ContentResolver cr = appCtx.getContentResolver();
        final int dataFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        final List<StagedUri> stagedUris = new ArrayList<>(uris.size());
        for (int i = 0; i < uris.size(); i++) {
            Uri uri = uris.get(i);
            if (dataFlags != 0) {
                try {
                    cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {
                }
            }
            stagedUris.add(openStagedUriSync(appCtx, cr, uri));
        }

        IMPORT_EXECUTOR.execute(() -> {
            activeImportSession.set(session);
            final List<String> results = new ArrayList<>();
            try {
                File inboxDir = ensureInboxDir(appCtx);
                pruneStaleTemps(inboxDir);
                int total = stagedUris.size();
                for (int i = 0; i < total; i++) {
                    StagedUri staged = stagedUris.get(i);
                    try {
                        if (session.cancelled.get()) {
                            break;
                        }
                        String path = copyStagedToSandbox(appCtx, cr, inboxDir, staged, i, total, session);
                        if (path != null && !path.isEmpty()) {
                            results.add(path);
                        }
                    } finally {
                        staged.closeQuietly();
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                for (int i = 0; i < stagedUris.size(); i++) {
                    stagedUris.get(i).closeQuietly();
                }
                activeImportSession.compareAndSet(session, null);
                deliverResult(session.callback, results);
            }
        });
        return true;
    }

    public static String copyToSandbox(Context context, Uri uri) {
        if (context == null || uri == null) {
            return null;
        }
        Context appCtx = context.getApplicationContext();
        ContentResolver cr = appCtx.getContentResolver();
        StagedUri staged = openStagedUriSync(appCtx, cr, uri);
        try {
            File inboxDir = ensureInboxDir(appCtx);
            return copyStagedToSandbox(appCtx, cr, inboxDir, staged, 0, 1, null);
        } finally {
            staged.closeQuietly();
        }
    }

    public static boolean cleanupRejectedStagedFile(Context context, String path) {
        if (context == null || path == null || path.isEmpty()) {
            return false;
        }
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        File candidate = new File(path);
        if (!candidate.isFile()) {
            return false;
        }
        File extInbox = app.getExternalFilesDir("colgram_inbox");
        File intInbox = app.getFilesDir() != null ? new File(app.getFilesDir(), "colgram_inbox") : null;
        if (!isPathInsideInbox(extInbox, candidate) && !isPathInsideInbox(intInbox, candidate)) {
            return false;
        }
        return candidate.delete();
    }

    private static StagedUri openStagedUriSync(Context context, ContentResolver cr, Uri uri) {
        String name = resolveDisplayName(cr, uri);
        long knownSize = queryUriSize(cr, uri);
        AssetFileDescriptor afd = null;
        try {
            String mime = cr.getType(uri);
            afd = cr.openTypedAssetFileDescriptor(uri, (mime != null && !mime.isEmpty()) ? mime : "*/*", null);
        } catch (Throwable ignored) {
            try {
                afd = cr.openAssetFileDescriptor(uri, "r");
            } catch (Throwable ignored2) {
                afd = null;
            }
        }
        if (knownSize <= 0L && afd != null) {
            long afdLen = afd.getLength();
            if (afdLen > 0L) {
                knownSize = afdLen;
            }
        }
        if ((name == null || name.isEmpty() || "attachment.bin".equals(name)) && afd != null) {
            name = ensureExtension(cr, uri, name);
        }
        return new StagedUri(uri, afd, name, knownSize);
    }

    private static File ensureInboxDir(Context context) {
        File dir = context.getExternalFilesDir("colgram_inbox");
        if (dir == null) {
            dir = new File(context.getFilesDir(), "colgram_inbox");
        }
        ColgramStorageSandbox.ensureDirWithNoMedia(dir);
        return dir;
    }

    private static void pruneStaleTemps(File inboxDir) {
        if (inboxDir == null || !inboxDir.isDirectory()) {
            return;
        }
        File[] files = inboxDir.listFiles();
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (f != null && f.isFile()) {
                String n = f.getName();
                if ((n.endsWith(".cgtmp") || n.startsWith(".cgtmp_"))
                        && !activeTempNames.contains(n)
                        && (now - f.lastModified()) > STALE_TEMP_AGE_MS) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    private static String copyStagedToSandbox(Context context, ContentResolver cr, File inboxDir,
                                              StagedUri staged, int itemIndex, int totalItems,
                                              PickSession session) {
        if (inboxDir == null || (!inboxDir.exists() && !inboxDir.mkdirs())) {
            return null;
        }
        String displayName = (staged.displayName != null && !staged.displayName.isEmpty())
                ? staged.displayName
                : "attachment.bin";
        long maxAllowed = session != null ? session.maxFileSizeBytes : PREMIUM_MAX_FILE_BYTES;
        if (!isSizeWithinLimit(staged.knownSize, maxAllowed)) {
            notifyRejected(session, displayName, "file_too_large");
            return null;
        }
        long availBytes = queryAvailableBytes(inboxDir);
        if (!hasSufficientSpace(availBytes, staged.knownSize)) {
            notifyRejected(session, displayName, "insufficient_storage");
            return null;
        }

        File tmp = new File(inboxDir, ".cgtmp_" + System.nanoTime() + "_" + sanitizeFilename(displayName));
        activeTempNames.add(tmp.getName());
        boolean finished = false;
        long totalCopied = 0L;
        try {
            InputStream in = null;
            try {
                if (staged.afd != null) {
                    in = staged.afd.createInputStream();
                } else {
                    in = cr.openInputStream(staged.uri);
                }
                if (in == null) {
                    notifyRejected(session, displayName, "open_failed");
                    return null;
                }
                try (OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[COPY_BUFFER_BYTES];
                    int read;
                    long lastProgressNs = 0L;
                    while ((read = in.read(buf)) != -1) {
                        if (session != null && session.cancelled.get()) {
                            return null;
                        }
                        totalCopied += read;
                        if (!isSizeWithinLimit(totalCopied, maxAllowed)) {
                            notifyRejected(session, displayName, "file_too_large");
                            return null;
                        }
                        out.write(buf, 0, read);
                        if (session != null && session.progressListener != null
                                && (staged.knownSize >= PROGRESS_THRESHOLD_BYTES || totalCopied >= PROGRESS_THRESHOLD_BYTES)) {
                            long nowNs = System.nanoTime();
                            if (nowNs - lastProgressNs >= 200_000_000L) {
                                lastProgressNs = nowNs;
                                notifyProgress(session, itemIndex, totalItems, displayName, totalCopied, staged.knownSize);
                            }
                        }
                    }
                    out.flush();
                }
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }

            if (totalCopied == 0L) {
                return null;
            }
            if (!shouldSkipDedupHash(totalCopied)) {
                String sha = computeSha256(tmp);
                File existing = findMatchingBySizeAndHash(inboxDir, totalCopied, sha);
                if (existing != null) {
                    finished = true;
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    notifyProgress(session, itemIndex + 1, totalItems, existing.getName(), totalCopied, totalCopied);
                    return existing.getAbsolutePath();
                }
            }

            File dst = uniqueFile(inboxDir, displayName);
            if (!tmp.renameTo(dst)) {
                try (InputStream fis = new FileInputStream(tmp);
                     OutputStream fos = new FileOutputStream(dst)) {
                    copyStream(fis, fos);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            finished = true;
            notifyProgress(session, itemIndex + 1, totalItems, dst.getName(), totalCopied, totalCopied);
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            notifyRejected(session, displayName, "copy_failed");
            return null;
        } finally {
            activeTempNames.remove(tmp.getName());
            if (!finished && tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static long queryAvailableBytes(File dir) {
        try {
            StatFs fs = new StatFs(dir.getAbsolutePath());
            return fs.getAvailableBytes();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static long queryUriSize(ContentResolver cr, Uri uri) {
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    long s = c.getLong(idx);
                    if (s > 0L) {
                        return s;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return -1L;
    }

    private static String resolveDisplayName(ContentResolver cr, Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = c.getString(idx);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (name == null || name.trim().isEmpty()) {
            String last = uri.getLastPathSegment();
            if (last != null) {
                int slash = last.lastIndexOf('/');
                name = slash >= 0 ? last.substring(slash + 1) : last;
                int colon = name.lastIndexOf(':');
                if (colon >= 0 && colon + 1 < name.length()) {
                    name = name.substring(colon + 1);
                }
            }
        }
        name = sanitizeFilename(name);
        if (name.isEmpty()) {
            name = "attachment_" + System.currentTimeMillis();
        }
        return ensureExtension(cr, uri, name);
    }

    private static String ensureExtension(ContentResolver cr, Uri uri, String name) {
        if (name == null || name.isEmpty()) {
            name = "attachment_" + System.currentTimeMillis();
        }
        if (!name.contains(".")) {
            String mime = cr.getType(uri);
            if (mime != null) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (ext != null && !ext.isEmpty()) {
                    name = name + "." + ext;
                }
            }
        }
        return name;
    }

    private static String sanitizeFilename(String raw) {
        if (raw == null) {
            return "";
        }
        String base = new File(raw).getName();
        base = base.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").trim();
        if (".".equals(base) || "..".equals(base)) {
            return "";
        }
        if (base.length() > 140) {
            int dot = base.lastIndexOf('.');
            if (dot > 0 && base.length() - dot <= 16) {
                String ext = base.substring(dot);
                base = base.substring(0, 140 - ext.length()) + ext;
            } else {
                base = base.substring(0, 140);
            }
        }
        return base;
    }

    private static File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) {
            return candidate;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File f = new File(dir, stem + "_" + i + ext);
            if (!f.exists()) {
                return f;
            }
        }
        return new File(dir, stem + "_" + System.nanoTime() + ext);
    }

    private static File findMatchingBySizeAndHash(File dir, long size, String sha) {
        if (sha == null) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        for (File f : files) {
            if (f != null && f.isFile() && !f.getName().endsWith(".cgtmp") && !f.getName().startsWith(".cgtmp_") && f.length() == size) {
                String fSha = computeSha256(f);
                if (sha.equals(fSha)) {
                    return f;
                }
            }
        }
        return null;
    }

    private static String computeSha256(File f) {
        try (InputStream is = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[COPY_BUFFER_BYTES];
            int n;
            while ((n = is.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[COPY_BUFFER_BYTES];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private static void notifyProgress(final PickSession session, final int done, final int total,
                                       final String name, final long bytesCopied, final long totalBytes) {
        if (session == null || session.progressListener == null) {
            return;
        }
        postToMain(() -> {
            try {
                session.progressListener.onProgress(done, total, name, bytesCopied, totalBytes);
            } catch (Throwable ignored) {
            }
        });
    }

    private static void notifyRejected(final PickSession session, final String name, final String reason) {
        if (session == null || session.progressListener == null) {
            return;
        }
        postToMain(() -> {
            try {
                session.progressListener.onRejected(name, reason);
            } catch (Throwable ignored) {
            }
        });
    }

    private static void deliverResult(final Callback cb, final List<String> paths) {
        if (cb == null) {
            return;
        }
        postToMain(() -> {
            try {
                cb.onResult(paths);
            } catch (Throwable ignored) {
            }
        });
    }
}
