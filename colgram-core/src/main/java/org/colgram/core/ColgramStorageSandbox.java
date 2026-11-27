package org.colgram.core;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;

/**
 * ColgramStorageSandbox — Isolates Telegram's storage and file handling
 * into a dedicated, app-scoped sandboxed folder, preventing MediaStore scanning
 * of device media or leaks of user files into the phone Gallery / Downloads.
 */
public class ColgramStorageSandbox {

    private static final String TAG = "ColgramStorageSandbox";
    private static final String[] SANDBOX_SUBDIRS = new String[]{
            "Images",
            "Audio",
            "Video",
            "Documents",
            "Cache",
            "Files",
            "Stories",
            "Plugins",
            "Images" + File.separator + "Public",
            "Video" + File.separator + "Public",
            ".colgram_cas"
    };

    private static volatile File sandboxRoot;
    private static volatile File cacheRoot;
    private static final java.util.concurrent.ConcurrentHashMap<Integer, File> sandboxedDirCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile File sandboxedDirCacheRoot;
    private static volatile File ensuredCacheRootFile;
    private static volatile File cachedCasDir;
    private static volatile File cachedCasDirRoot;

    private static void invalidateResolvedDirCaches() {
        sandboxedDirCache.clear();
        sandboxedDirCacheRoot = null;
        ensuredCacheRootFile = null;
        cachedCasDir = null;
        cachedCasDirRoot = null;
    }

    public static void init(Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;

        // Deterministic, single-root binding on the calling thread: never flip mid-startup
        // to public Environment.DIRECTORY_DOCUMENTS (which caused split-brain paths and leaked
        // cached media into Android's MediaStore / Gallery).
        final String rawName = ColgramConfig.getCustomStorageDir();
        final String name = (rawName == null || rawName.trim().isEmpty()) ? "Colgram" : rawName.trim();
        final boolean sandboxOn = ColgramConfig.isSandboxStorageEnabled();
        File externalBase = null;
        try {
            externalBase = appCtx.getExternalFilesDir(null);
        } catch (Throwable ignored) {}

        File baseDir = externalBase != null ? externalBase : appCtx.getFilesDir();
        final File resolvedRoot = sandboxOn
                ? (baseDir != null ? new File(baseDir, name) : null)
                : baseDir;
        final File resolvedCache = resolvedRoot != null
                ? new File(resolvedRoot, "Cache")
                : new File(appCtx.getCacheDir(), "Colgram");

        invalidateResolvedDirCaches();
        sandboxRoot = resolvedRoot;
        cacheRoot = resolvedCache;
        sandboxedDirCacheRoot = resolvedRoot;

        // Defer directory/.nomedia writes & MediaScanner silencing onto background priority thread off UI startup.
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            try {
                ensureDirWithNoMedia(resolvedRoot);
                ensureDirWithNoMedia(resolvedCache);
                if (resolvedCache != null) {
                    ensuredCacheRootFile = resolvedCache;
                }
                File root = sandboxRoot;
                if (root != null && !isWritable(root)) {
                    File internalFallback = sandboxOn
                            ? new File(appCtx.getFilesDir(), name)
                            : appCtx.getFilesDir();
                    if (internalFallback != null) {
                        ensureDirWithNoMedia(internalFallback);
                        invalidateResolvedDirCaches();
                        File fallbackCache = new File(internalFallback, "Cache");
                        ensureDirWithNoMedia(fallbackCache);
                        sandboxRoot = internalFallback;
                        cacheRoot = fallbackCache;
                        sandboxedDirCacheRoot = internalFallback;
                        ensuredCacheRootFile = fallbackCache;
                        root = internalFallback;
                    }
                }
                if (root != null) {
                    ensureAllSubdirsWithNoMedia(root);
                }
                ColgramStorageIndex.init(appCtx.getFilesDir());
                silenceLegacyPublicDocumentsDir(appCtx, name);
                if (cacheRoot != null) {
                    Log.i(TAG, "media sandbox=" + (root != null ? root.getAbsolutePath() : "null")
                            + " cache=" + cacheRoot.getAbsolutePath());
                }
            } catch (Throwable ignored) {}
        }, "colgram-sandbox-init");
        t.setDaemon(true);
        t.start();
    }

    /** Ensures directory exists and contains a .nomedia marker so Android MediaStore never indexes it. */
    public static void ensureDirWithNoMedia(File dir) {
        if (dir == null) return;
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            if (dir.isDirectory()) {
                File noMedia = new File(dir, ".nomedia");
                if (!noMedia.exists()) {
                    try {
                        noMedia.createNewFile();
                    } catch (Throwable ignored) {
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(noMedia)) {
                            // empty marker
                        } catch (Throwable ignored2) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void ensureAllSubdirsWithNoMedia(File root) {
        if (root == null) return;
        ensureDirWithNoMedia(root);
        for (String sub : SANDBOX_SUBDIRS) {
            ensureDirWithNoMedia(new File(root, sub));
        }
    }

    /**
     * Non-destructively drops .nomedia into legacy /sdcard/Documents/<name> and any existing
     * subdirectories if a prior install wrote there, then triggers MediaScannerConnection.scanFile
     * so Android's MediaProvider immediately unindexes those directories from Gallery/Downloads.
     */
    private static void silenceLegacyPublicDocumentsDir(Context appCtx, String name) {
        try {
            java.util.LinkedHashSet<String> scanPaths = new java.util.LinkedHashSet<>();
            File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (documentsDir != null) {
                File legacy = new File(documentsDir, name);
                if (legacy.exists() && legacy.isDirectory()) {
                    collectNoMediaScanPaths(legacy, scanPaths);
                    for (String sub : SANDBOX_SUBDIRS) {
                        File subDir = new File(legacy, sub);
                        if (subDir.exists() && subDir.isDirectory()) {
                            collectNoMediaScanPaths(subDir, scanPaths);
                        }
                    }
                    File[] children = legacy.listFiles();
                    if (children != null) {
                        for (File child : children) {
                            if (child != null && child.isDirectory()) {
                                collectNoMediaScanPaths(child, scanPaths);
                            }
                        }
                    }
                }
            }
            if (appCtx != null && ColgramConfig.isSandboxStorageEnabled()
                    && android.os.Build.VERSION.SDK_INT >= 21) {
                try {
                    File[] mediaDirs = appCtx.getExternalMediaDirs();
                    if (mediaDirs != null) {
                        for (File md : mediaDirs) {
                            if (md == null || !md.exists()) continue;
                            collectNoMediaScanPaths(md, scanPaths);
                            File tgDir = new File(md, "Telegram");
                            if (tgDir.exists() && tgDir.isDirectory()) {
                                collectNoMediaScanPaths(tgDir, scanPaths);
                                File[] tgChildren = tgDir.listFiles();
                                if (tgChildren != null) {
                                    for (File tc : tgChildren) {
                                        if (tc != null && tc.isDirectory()) {
                                            collectNoMediaScanPaths(tc, scanPaths);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (appCtx != null && !scanPaths.isEmpty()) {
                android.media.MediaScannerConnection.scanFile(
                        appCtx,
                        scanPaths.toArray(new String[0]),
                        null,
                        null);
            }
        } catch (Throwable ignored) {}
    }

    private static void collectNoMediaScanPaths(File dir, java.util.Set<String> scanPaths) {
        if (dir == null) return;
        ensureDirWithNoMedia(dir);
        scanPaths.add(dir.getAbsolutePath());
        File noMedia = new File(dir, ".nomedia");
        if (noMedia.exists()) {
            scanPaths.add(noMedia.getAbsolutePath());
        }
    }

    /**
     * Does this directory exist (or can it be created) and accept a write?
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
        if (sandboxRoot == null && context != null) {
            init(context);
        }
        return sandboxRoot;
    }

    public static File getCacheRootDir(Context context) {
        if (cacheRoot == null && context != null) {
            init(context);
        }
        File c = cacheRoot;
        if (c != null && ensuredCacheRootFile != c) {
            ensureDirWithNoMedia(c);
            if (cacheRoot == c) {
                ensuredCacheRootFile = c;
            }
        }
        return c;
    }

    public static File getCasDirectory(Context context) {
        File root = getSandboxRootDir(context);
        if (root == null) return null;
        if (root == cachedCasDirRoot && cachedCasDir != null) {
            return cachedCasDir;
        }
        File cas = new File(root, ".colgram_cas");
        ensureDirWithNoMedia(cas);
        if (sandboxRoot == root) {
            cachedCasDirRoot = root;
            cachedCasDir = cas;
        }
        return cas;
    }

    /** Absolute path of the folder actually in effect, for display. */
    public static String getRootPath(Context context) {
        File root = getSandboxRootDir(context);
        return root != null ? root.getAbsolutePath() : "";
    }

    /**
     * Redirects Telegram's media directory lookup to the isolated sandbox folder.
     * Matches FileLoader.MEDIA_DIR_* constants accurately:
     * 0 = Images, 1 = Audio, 2 = Video, 3 = Documents, 4 = Cache, 5 = Files,
     * 6 = Stories, 100 = Images/Public, 101 = Video/Public.
     */
    public static File getSandboxedDirectory(Context context, int type) {
        File root = getSandboxRootDir(context);
        if (root == null) return null;
        if (type == 4) {
            return getCacheRootDir(context);
        }

        if (root != sandboxedDirCacheRoot) {
            sandboxedDirCache.clear();
            sandboxedDirCacheRoot = root;
        }
        File cached = sandboxedDirCache.get(type);
        if (cached != null) {
            return cached;
        }

        String subFolder;
        switch (type) {
            case 0:
                subFolder = "Images";
                break;
            case 1:
                subFolder = "Audio";
                break;
            case 2:
                subFolder = "Video";
                break;
            case 3:
                subFolder = "Documents";
                break;
            case 5:
                subFolder = "Files";
                break;
            case 6:
                subFolder = "Stories";
                break;
            case 100:
                subFolder = "Images" + File.separator + "Public";
                break;
            case 101:
                subFolder = "Video" + File.separator + "Public";
                break;
            default:
                subFolder = "Cache";
                break;
        }

        File dir = new File(root, subFolder);
        ensureDirWithNoMedia(dir);
        if (sandboxRoot == root && sandboxedDirCacheRoot == root) {
            File existing = sandboxedDirCache.putIfAbsent(type, dir);
            if (existing != null) {
                return existing;
            }
        }
        return dir;
    }

    /** Computes [fileCount, totalBytes] for a folder recursively (bounded depth) for UI display. */
    public static long[] computeDirStats(File dir) {
        long[] stats = new long[]{0L, 0L};
        accumulateStats(dir, stats, 0);
        return stats;
    }

    private static void accumulateStats(File dir, long[] stats, int depth) {
        if (dir == null || !dir.exists() || depth > 8) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child == null) continue;
            String name = child.getName();
            if (name.startsWith(".colgram-write-probe") || ".nomedia".equals(name)) continue;
            if (child.isDirectory()) {
                accumulateStats(child, stats, depth + 1);
            } else {
                stats[0]++;
                stats[1] += Math.max(0L, child.length());
            }
        }
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
