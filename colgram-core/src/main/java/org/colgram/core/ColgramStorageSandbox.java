package org.colgram.core;

import android.content.Context;
import android.os.Environment;

import java.io.File;

/**
 * ColgramStorageSandbox — Isolates Telegram's storage and file handling
 * into a dedicated, sandboxed folder, preventing scanning of device media
 * or leaks of user files outside the sandbox.
 */
public class ColgramStorageSandbox {

    private static File sandboxRoot;

    public static void init(Context context) {
        if (context == null) return;

        if (ColgramConfig.isSandboxStorageEnabled()) {
            // Target: scoped Documents/Colgram folder
            File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (documentsDir != null) {
                sandboxRoot = new File(documentsDir, ColgramConfig.getCustomStorageDir());
            } else {
                // Fallback to app-internal sandboxed files directory
                sandboxRoot = new File(context.getFilesDir(), ColgramConfig.getCustomStorageDir());
            }
        } else {
            sandboxRoot = context.getExternalFilesDir(null);
        }

        if (sandboxRoot != null && !sandboxRoot.exists()) {
            sandboxRoot.mkdirs();
        }
    }

    public static File getSandboxRootDir(Context context) {
        if (sandboxRoot == null) {
            init(context);
        }
        return sandboxRoot;
    }

    /**
     * Redirects Telegram's media directory lookup to the isolated sandbox folder.
     *
     * @param type Directory type (0 = documents, 1 = images, 2 = audio, 3 = video)
     */
    public static File getSandboxedDirectory(Context context, int type) {
        File root = getSandboxRootDir(context);
        if (root == null) return null;

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
            return canonicalPath.startsWith(canonicalRoot);
        } catch (Exception e) {
            return false;
        }
    }
}
