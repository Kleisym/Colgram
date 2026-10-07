package org.colgram.core;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

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
        new Thread(() -> {
            ArrayList<String> paths = new ArrayList<>();
            for (Uri u : uris) {
                File f = copyToSandbox(appContext, u);
                if (f != null) paths.add(f.getAbsolutePath());
            }
            Log.i(TAG, "imported " + paths.size() + "/" + uris.size() + " file(s) into the sandbox");
            final ArrayList<String> result = paths;
            if (cb != null) {
                // Deliver on the main thread: every caller here is UI code.
                new android.os.Handler(android.os.Looper.getMainLooper()).post(
                        () -> cb.onImported(result));
            }
        }, "colgram-file-import").start();
        return true;
    }

    /** The private inbox. Inside the app sandbox, so it needs no permission whatsoever. */
    public static File getInboxDir(Context context) {
        File dir = new File(context.getFilesDir(), "colgram_inbox");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Copy one picked document into the inbox.
     *
     * Returns null on any failure so the caller can simply skip it - a partial import is better
     * than an aborted one.
     */
    public static File copyToSandbox(Context context, Uri uri) {
        if (context == null || uri == null) return null;
        InputStream in = null;
        OutputStream out = null;
        try {
            String name = displayName(context, uri);
            if (name == null || name.isEmpty()) {
                name = "import_" + System.currentTimeMillis();
            }
            // Sanitise: a display name can contain path separators, and it is attacker-controlled
            // as far as we are concerned (any app can publish a document provider).
            name = name.replace('/', '_').replace('\\', '_');
            File dest = uniqueFile(getInboxDir(context), name);

            in = context.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            out = new FileOutputStream(dest);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            return dest;
        } catch (Throwable t) {
            Log.w(TAG, "copy failed for " + uri + ": " + t);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
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
