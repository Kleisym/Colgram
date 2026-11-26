package org.colgram.core;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * ColgramFileImport — bring a file into Colgram WITHOUT granting access to the user's media.
 *
 * THE PROBLEM THIS SOLVES
 *
 * Telegram requests READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO and
 * READ_EXTERNAL_STORAGE, which together hand it every photo, video and audio file on the device.
 * That is the "ноль анонимности" complaint, and it is accurate: an app with those grants can read
 * the entire media library whether or not the user ever intended to send anything.
 *
 * THE FIX
 *
 * Use the Storage Access Framework (`ACTION_OPEN_DOCUMENT`). SAF needs NO permission at all - the
 * user picks specific files and the app receives a per-file URI for exactly those. Colgram copies
 * the chosen files into its own private inbox (`files/colgram_inbox/`, inside the app sandbox, no
 * permission required) and then hands the LOCAL PATHS to Telegram's normal attachment path.
 *
 * Why copy instead of just passing the content:// URI: Telegram's send pipeline is built around
 * real file paths (`didSelectFiles(ArrayList<String> files, ...)`), and reworking it to carry URIs
 * would touch the whole media stack. Copying is a few megabytes of I/O and keeps the change
 * contained. It also means the sent file is a stable local copy rather than a URI that can be
 * revoked when the picker closes.
 *
 * The net effect: the app never asks for your gallery, and the only files it can see are the ones
 * you explicitly handed it.
 */
public class ColgramFileImport {

    private static final String TAG = "ColgramFileImport";

    /** Request code for the SAF picker. Arbitrary, but must be unique within the host activity. */
    public static final int COLGRAM_PICK_REQUEST = 0x0C01;

    /** Called with the local sandbox paths of the imported files, or an empty list on cancel. */
    public interface Callback {
        void onImported(ArrayList<String> localPaths);
    }

    /** Only one pick can be outstanding at a time; a second launch replaces the first. */
    private static Callback pendingCallback;

    /**
     * Launch the system file picker.
     *
     * @param mimeType  e.g. "*&#47;*", "image/*", "audio/*". SAF filters by MIME, so this is how a
     *                  caller narrows the choice without any permission.
     * @param multiple  allow selecting several files at once
     */
    public static void pickFiles(final Activity activity, final String mimeType,
                                 final boolean multiple, final Callback callback) {
        if (activity == null) {
            if (callback != null) callback.onImported(new ArrayList<>());
            return;
        }
        pendingCallback = callback;
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(mimeType == null || mimeType.isEmpty() ? "*/*" : mimeType);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
            // Read-only: we copy, we never write back to the user's file.
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(i, COLGRAM_PICK_REQUEST);
            Log.i(TAG, "SAF picker launched, mime=" + mimeType);
        } catch (Throwable t) {
            // No document provider on this device (rare, but some stripped ROMs). Report rather
            // than leaving the caller waiting on a callback that never fires.
            Log.w(TAG, "could not launch picker: " + t);
            pendingCallback = null;
            if (callback != null) callback.onImported(new ArrayList<>());
        }
    }

    /**
     * Forward an activity result here. Returns true when the result belonged to the picker, so a
     * host can chain this in front of its own handling without swallowing other results.
     *
     * Copying runs on a worker: a large video can take seconds and this is called from the UI
     * thread.
     */
    public static boolean handleActivityResult(final Context context, int requestCode,
                                               int resultCode, Intent data) {
        if (requestCode != COLGRAM_PICK_REQUEST) {
            return false;
        }
        final Callback cb = pendingCallback;
        pendingCallback = null;

        if (resultCode != Activity.RESULT_OK || data == null) {
            if (cb != null) cb.onImported(new ArrayList<>());
            return true;
        }

        final ArrayList<Uri> uris = new ArrayList<>();
        try {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not read picker result: " + t);
        }

        if (uris.isEmpty()) {
            if (cb != null) cb.onImported(new ArrayList<>());
            return true;
        }

        final Context appContext = context == null ? null : context.getApplicationContext();
        final Context deliveryContext = appContext != null ? appContext : context;
        new Thread(() -> {
            ArrayList<String> paths = new ArrayList<>();
            int failed = 0;
            for (Uri u : uris) {
                File f = copyToSandbox(deliveryContext, u);
                if (f != null && f.exists() && f.length() > 0L) {
                    paths.add(f.getAbsolutePath());
                } else {
                    failed++;
                }
            }
            Log.i(TAG, "imported " + paths.size() + "/" + uris.size() + " file(s) into the sandbox");
            final ArrayList<String> result = paths;
            final int failedCount = failed;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (failedCount > 0 && result.isEmpty() && deliveryContext != null) {
                    try {
                        Toast.makeText(deliveryContext, "Не удалось прикрепить выбранный файл", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                }
                if (cb != null) {
                    try {
                        cb.onImported(result);
                    } catch (Throwable t) {
                        Log.w(TAG, "onImported callback error: " + t);
                    }
                }
            });
        }, "colgram-file-import").start();
        return true;
    }

    /**
     * Private import staging directory. Prefers app-private external files dir (zero storage
     * permission needed, never triggers AndroidUtilities.isInternalUri /data/data/ block, and still
     * shielded from MediaStore with .nomedia), falling back to internal files/colgram_inbox.
     */
    public static File getInboxDir(Context context) {
        if (context != null) {
            try {
                File ext = context.getExternalFilesDir("colgram_inbox");
                if (ext != null && (ext.exists() || ext.mkdirs()) && ext.canWrite()) {
                    ColgramStorageSandbox.ensureDirWithNoMedia(ext);
                    return ext;
                }
            } catch (Throwable ignored) {}
        }
        File dir = new File(context.getFilesDir(), "colgram_inbox");
        ColgramStorageSandbox.ensureDirWithNoMedia(dir);
        return dir;
    }

    /**
     * Copy one picked document into the inbox, deduplicating against existing inbox files by SHA-256
     * and opportunistically pruning unreferenced inbox copies older than 7 days.
     *
     * Returns null on any failure so the caller can simply skip it - a partial import is better
     * than an aborted one.
     */
    public static File copyToSandbox(Context context, Uri uri) {
        if (context == null || uri == null) return null;
        InputStream in = null;
        OutputStream out = null;
        AssetFileDescriptor afd = null;
        File tmp = null;
        try {
            File inboxDir = getInboxDir(context);
            try {
                ColgramStorageOptimizer.pruneRegenerableWaste(
                        null, inboxDir, ColgramStorageOptimizer.getActiveTelegramFilePaths(), System.currentTimeMillis());
            } catch (Throwable ignored) {}

            String name = displayName(context, uri);
            if (name == null || name.isEmpty()) {
                name = "import_" + System.currentTimeMillis();
            }
            ContentResolver resolver = context.getContentResolver();
            // Sanitise: a display name can contain path separators, and it is attacker-controlled
            // as far as we are concerned (any app can publish a document provider).
            name = name.replace('/', '_').replace('\\', '_').replace(":", "_").trim();
            if (name.isEmpty()) {
                name = "import_" + System.currentTimeMillis();
            }
            if (!name.contains(".") && resolver != null) {
                try {
                    String mime = resolver.getType(uri);
                    String ext = mime != null ? MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) : null;
                    if (ext != null && !ext.isEmpty()) {
                        name = name + "." + ext;
                    }
                } catch (Throwable ignored) {}
            }
            tmp = new File(inboxDir, ".import_" + System.currentTimeMillis() + "_" + Math.abs(name.hashCode()) + ".cgtmp");

            if (resolver == null) return null;
            try {
                in = resolver.openInputStream(uri);
            } catch (Throwable openErr) {
                in = null;
            }
            if (in == null) {
                try {
                    afd = resolver.openTypedAssetFileDescriptor(uri, "*/*", null);
                    if (afd != null) {
                        in = afd.createInputStream();
                    }
                } catch (Throwable afdErr) {
                    in = null;
                }
            }
            if (in == null) return null;
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            out = null;

            long len = tmp.length();
            if (len <= 0L) {
                return null;
            }
            String sha = ColgramStorageOptimizer.computeSha256(tmp);
            if (!sha.isEmpty() && len > 0L) {
                File[] existing = inboxDir.listFiles();
                if (existing != null) {
                    for (File cand : existing) {
                        if (cand != null && cand.isFile() && !cand.equals(tmp)
                                && !cand.getName().endsWith(".cgtmp") && cand.length() == len) {
                            ColgramStorageIndex.Entry e = ColgramStorageIndex.getEntry(cand);
                            String candSha = (e != null && e.matchesFileOnDisk(cand) && !e.sha256.isEmpty())
                                    ? e.sha256 : ColgramStorageOptimizer.computeSha256(cand);
                            if (sha.equals(candSha)) {
                                //noinspection ResultOfMethodCallIgnored
                                tmp.delete();
                                ColgramUnlimitedMemory.recordDedupSaved(context, 1, len);
                                return cand;
                            }
                        }
                    }
                }
            }

            File dest = uniqueFile(inboxDir, name);
            if (ColgramStorageOptimizer.atomicReplaceFile(tmp, dest)) {
                if (!sha.isEmpty()) {
                    ColgramStorageIndex.recordEntry(dest, len, dest.length(), sha, ColgramStorageIndex.STATE_SKIPPED_OPTIMAL);
                    ColgramStorageIndex.flush();
                }
                return dest;
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "copy failed for " + uri + ": " + t);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (afd != null) afd.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
            try { if (tmp != null && tmp.exists()) tmp.delete(); } catch (Throwable ignored) {}
        }
    }

    /** Avoid clobbering an earlier import of the same name. */
    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            f = new File(dir, base + "_" + i + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    /** The provider's own display name for the document, when it offers one. */
    private static String displayName(Context context, Uri uri) {
        android.database.Cursor c = null;
        try {
            c = context.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getString(idx);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }
        // Fall back to the last path segment of the URI.
        String last = uri.getLastPathSegment();
        return last == null ? null : last;
    }
}
