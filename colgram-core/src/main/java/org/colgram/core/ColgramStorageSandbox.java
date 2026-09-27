package org.colgram.core;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;

/**
 * ColgramStorageSandbox — Isolates Telegram's storage and file handling
 * into a dedicated, sandboxed folder, preventing scanning of device media
 * or leaks of user files outside the sandbox.
 */
public class ColgramStorageSandbox {

    private static File sandboxRoot;
    private static File cacheRoot;

    public static void init(Context context) {
        if (context == null) return;

        if (ColgramConfig.isSandboxStorageEnabled()) {
            // The folder is only a sandbox if the app can actually write to it. Public
            // Documents/Colgram is the nicest place to live - a file manager shows it - but under
            // scoped storage (targetSdk 36) a new directory there is usually denied, and a
            // download path that silently cannot be written is worse than one in an out-of-the-way
            // directory that can. So probe instead of guessing: public Documents, then the
            // app-specific external directory (writable with no permission on every API level),
            // then internal storage.
            String name = ColgramConfig.getCustomStorageDir();
            File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            File candidate = documentsDir != null ? new File(documentsDir, name) : null;
            if (!isWritable(candidate)) {
                candidate = context.getExternalFilesDir(name);
            }
            if (!isWritable(candidate)) {
                candidate = new File(context.getFilesDir(), name);
            }
            sandboxRoot = candidate;
        } else {
            sandboxRoot = context.getExternalFilesDir(null);
        }

        if (sandboxRoot != null && !sandboxRoot.exists()) {
            sandboxRoot.mkdirs();
        }

        // Telegram's media cache is private working storage, not a user export. Public
        // Documents/Colgram/Cache made Android 16's MediaProvider reject image and video
        // reads even though the app itself could create the files there. Keep exported media
        // in the selected sandbox, but place cache files in this app's own external area.
        File external = context.getExternalFilesDir(null);
        File candidate = external == null ? null : new File(external, "Colgram/Cache");
        if (!isWritable(candidate)) candidate = new File(context.getCacheDir(), "Colgram");
        cacheRoot = candidate;
        Log.i("ColgramStorageSandbox", "media cache=" + cacheRoot.getAbsolutePath());
    }

    /**
     * Does this directory exist (or can it be created) and accept a write? Existence alone is
     * not evidence: scoped storage happily reports a path that every open() on it rejects.
     */
    private static boolean isWritable(File dir) {
        if (dir == null) return false;
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            if (!dir.isDirectory()) return false;
            File probe = new File(dir, ".colgram-write-probe");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(probe)) {
                out.write(1);
            } catch (Throwable t) {
                return false;
            } finally {
                //noinspection ResultOfMethodCallIgnored
                probe.delete();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static File getSandboxRootDir(Context context) {
        if (sandboxRoot == null) {
            init(context);
        }
        return sandboxRoot;
    }

    /** Absolute path of the folder actually in effect, for display. */
    public static String getRootPath(Context context) {
        File root = getSandboxRootDir(context);
        return root != null ? root.getAbsolutePath() : "";
    }

    /**
     * Redirects Telegram's media directory lookup to the isolated sandbox folder.
     *
     * @param type Directory type (0 = documents, 1 = images, 2 = audio, 3 = video)
     */
    public static File getSandboxedDirectory(Context context, int type) {
        File root = getSandboxRootDir(context);
        if (root == null) return null;
        if (type == 4) return cacheRoot;

        String subFolder;
        switch (type) {
            case 0:
                subFolder = "Documents";
                break;
            case 1:
                subFolder = "Images";
                break;
            case 2:
                subFolder = "Audio";
                break;
            case 3:
                subFolder = "Video";
                break;
            default:
                subFolder = "Cache";
                break;
        }

        File dir = new File(root, subFolder);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Checks if a requested path falls within the permitted sandbox boundary.
     * Prevents directory traversal attacks or unauthorized file indexing.
     */
    public static boolean isPathPermitted(Context context, String path) {
        if (!ColgramConfig.isSandboxStorageEnabled()) return true;
        if (path == null) return false;

        File root = getSandboxRootDir(context);
        if (root == null) return false;

        try {
            String canonicalRoot = root.getCanonicalPath();
            String canonicalPath = new File(path).getCanonicalPath();
            boolean inRoot = canonicalPath.equals(canonicalRoot)
                    || canonicalPath.startsWith(canonicalRoot + File.separator);
            if (cacheRoot == null) return inRoot;
            String canonicalCache = cacheRoot.getCanonicalPath();
            return inRoot || canonicalPath.equals(canonicalCache)
                    || canonicalPath.startsWith(canonicalCache + File.separator);
        } catch (Exception e) {
            return false;
        }
    }
}
