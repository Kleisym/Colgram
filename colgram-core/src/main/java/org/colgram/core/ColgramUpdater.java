package org.colgram.core;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramUpdater — In-app OTA & upstream Telegram update checker.
 * Checks GitHub Releases via ColgramHttp (with proxy/relay support), verifies semantic versions,
 * ABI-matching APKs, and optional SHA-256 digests, downloads strictly via active Colgram
 * transport/proxy, and safely launches PackageInstaller.
 */
public class ColgramUpdater {

    private static final String UPSTREAM_REPO = "DrKLO/Telegram";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Map<String, String> EXPECTED_SHA256_BY_URL = new ConcurrentHashMap<>();
    private static volatile PendingUpdate pendingUpdate;

    public static final class PendingUpdate {
        public final String version;
        public final String releaseNotes;
        public final String downloadUrl;
        public final String expectedSha256;

        public PendingUpdate(String version, String releaseNotes, String downloadUrl, String expectedSha256) {
            this.version = version;
            this.releaseNotes = releaseNotes == null ? "" : releaseNotes;
            this.downloadUrl = downloadUrl;
            this.expectedSha256 = expectedSha256;
        }
    }

    public interface UpdateCheckCallback {
        void onUpdateAvailable(String newVersion, String releaseNotes, String downloadUrl);
        void onUpToDate();
        void onError(String error);
    }

    public interface UpstreamCheckCallback {
        void onResult(String latestUpstreamTag, boolean newerThanBase, String error);
    }

    public static PendingUpdate getPendingUpdate() {
        return pendingUpdate;
    }

    public static PendingUpdate consumePendingUpdate() {
        PendingUpdate current = pendingUpdate;
        pendingUpdate = null;
        return current;
    }

    /** Checks updates only when auto-update is enabled in ColgramConfig. */
    public static void checkForUpdatesIfEnabled(final String currentVersion, final UpdateCheckCallback callback) {
        if (!ColgramConfig.isAutoUpdateEnabled()) {
            if (callback != null) {
                mainHandler.post(callback::onUpToDate);
            }
            return;
        }
        checkForUpdates(currentVersion, callback);
    }

    /** Checks if a new Colgram release exists on GitHub. */
    public static void checkForUpdates(final String currentVersion, final UpdateCheckCallback callback) {
        if (callback == null) return;
        executor.execute(() -> {
            try {
                String repo = ColgramConfig.getUpdateRepo();
                ColgramHttp.Response resp = ColgramHttp.get(
                        "https://api.github.com/repos/" + repo + "/releases/latest");

                JSONObject release = null;
                if (resp.code == 200) {
                    JSONObject cand = new JSONObject(resp.body);
                    if (hasMatchingEditionApkAsset(cand.optJSONArray("assets"))) {
                        release = cand;
                    }
                } else if (resp.code != 404 && resp.code != 403) {
                    final int code = resp.code;
                    mainHandler.post(() -> callback.onError("Server returned code " + code));
                    return;
                }
                if (release == null) {
                    // Repository may publish pre-releases (CI tags) or /latest without matching-edition APK.
                    ColgramHttp.Response listResp = ColgramHttp.get(
                            "https://api.github.com/repos/" + repo + "/releases?per_page=10");
                    if (listResp.code == 200) {
                        JSONArray arr = new JSONArray(listResp.body);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject cand = arr.optJSONObject(i);
                            if (cand == null) continue;
                            JSONArray assets = cand.optJSONArray("assets");
                            if (assets != null && hasMatchingEditionApkAsset(assets)) {
                                release = cand;
                                break;
                            }
                        }
                    } else {
                        final int listCode = listResp.code;
                        mainHandler.post(() -> callback.onError("Server returned code " + listCode));
                        return;
                    }
                }

                if (release == null) {
                    mainHandler.post(callback::onUpToDate);
                    return;
                }

                String tagName = release.optString("tag_name", "");
                String body = release.optString("body", "");

                List<ColgramUpdatePolicy.ApkAsset> apkAssets = new ArrayList<>();
                String shaAssetUrl = null;
                if (release.has("assets")) {
                    JSONArray assets = release.getJSONArray("assets");
                    java.util.HashMap<String, String> shaByName = new java.util.HashMap<>();
                    String shaSumsUrl = null;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) continue;
                        String name = asset.optString("name", "");
                        String dl = asset.optString("browser_download_url", "");
                        if (dl.isEmpty()) continue;
                        String low = name.toLowerCase(java.util.Locale.US);
                        if (low.endsWith(".apk")) {
                            apkAssets.add(new ColgramUpdatePolicy.ApkAsset(name, dl));
                        } else if (low.endsWith(".sha256")) {
                            shaByName.put(low, dl);
                        } else if ("sha256sums.txt".equals(low)) {
                            shaSumsUrl = dl;
                        }
                    }
                    ColgramUpdatePolicy.ApkAsset preBest = ColgramUpdatePolicy.pickBestApkAsset(
                            apkAssets, Build.SUPPORTED_ABIS, ColgramBuildFlags.KOLITI_ENABLED);
                    if (preBest != null) {
                        String exactKey = (preBest.name + ".sha256").toLowerCase(java.util.Locale.US);
                        shaAssetUrl = shaByName.containsKey(exactKey) ? shaByName.get(exactKey) : shaSumsUrl;
                    }
                }

                ColgramUpdatePolicy.ApkAsset bestApk = ColgramUpdatePolicy.pickBestApkAsset(
                        apkAssets, Build.SUPPORTED_ABIS, ColgramBuildFlags.KOLITI_ENABLED);
                String apkDownloadUrl = bestApk != null ? bestApk.downloadUrl : null;

                if (apkDownloadUrl != null && isNewerTag(tagName, currentVersion)) {
                    String expectedSha = null;
                    if (shaAssetUrl != null) {
                        try {
                            ColgramHttp.Response shaResp = ColgramHttp.get(shaAssetUrl);
                            if (shaResp.code >= 200 && shaResp.code < 300) {
                                expectedSha = ColgramUpdatePolicy.extractSha256FromText(
                                        shaResp.body, bestApk != null ? bestApk.name : null);
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (expectedSha == null) {
                        expectedSha = ColgramUpdatePolicy.extractSha256FromText(
                                body, bestApk != null ? bestApk.name : null);
                    }
                    if (expectedSha != null) {
                        EXPECTED_SHA256_BY_URL.put(apkDownloadUrl, expectedSha);
                    }
                    pendingUpdate = new PendingUpdate(tagName, body, apkDownloadUrl, expectedSha);
                    final String finalApkUrl = apkDownloadUrl;
                    mainHandler.post(() -> callback.onUpdateAvailable(tagName, body, finalApkUrl));
                } else {
                    mainHandler.post(callback::onUpToDate);
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        });
    }

    /** Queries upstream DrKLO/Telegram tags to see if a newer Telegram base release has landed. */
    public static void checkUpstreamTelegramUpdate(final String currentVersion, final UpstreamCheckCallback callback) {
        if (callback == null) return;
        executor.execute(() -> {
            try {
                ColgramHttp.Response resp = ColgramHttp.get(
                        "https://api.github.com/repos/" + UPSTREAM_REPO + "/tags?per_page=15");
                if (resp.code >= 200 && resp.code < 300 && resp.body != null) {
                    JSONArray arr = new JSONArray(resp.body);
                    String bestTag = null;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.optJSONObject(i);
                        if (item == null) continue;
                        String tag = item.optString("name", "").trim();
                        if (ColgramUpdatePolicy.parseSemver(tag).length >= 2) {
                            if (bestTag == null || ColgramUpdatePolicy.compareSemver(tag, bestTag) > 0) {
                                bestTag = tag;
                            }
                        }
                    }
                    boolean newer = bestTag != null && ColgramUpdatePolicy.isUpstreamTelegramNewer(currentVersion, bestTag);
                    final String found = bestTag;
                    mainHandler.post(() -> callback.onResult(found, newer, null));
                } else {
                    final int code = resp.code;
                    mainHandler.post(() -> callback.onResult(null, false, "HTTP " + code));
                }
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                mainHandler.post(() -> callback.onResult(null, false, msg));
            }
        });
    }

    private static boolean hasMatchingEditionApkAsset(JSONArray assets) {
        if (assets == null) return false;
        List<ColgramUpdatePolicy.ApkAsset> apks = new ArrayList<>();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String dl = asset.optString("browser_download_url", "");
            if (name.toLowerCase(java.util.Locale.US).endsWith(".apk") && !dl.isEmpty()) {
                apks.add(new ColgramUpdatePolicy.ApkAsset(name, dl));
            }
        }
        return ColgramUpdatePolicy.pickBestApkAsset(apks, Build.SUPPORTED_ABIS, ColgramBuildFlags.KOLITI_ENABLED) != null;
    }

    public static int[] parseSemver(String version) {
        return ColgramUpdatePolicy.parseSemver(version);
    }

    public static int compareSemver(String a, String b) {
        return ColgramUpdatePolicy.compareSemver(a, b);
    }

    public static boolean isNewerTag(String remoteTag, String currentVersion) {
        return ColgramUpdatePolicy.isNewerTag(remoteTag, currentVersion);
    }

    public static String extractSha256FromText(String text) {
        return ColgramUpdatePolicy.extractSha256FromText(text);
    }

    public static String sha256Hex(byte[] data) {
        return ColgramUpdatePolicy.sha256Hex(data);
    }

    public static String sha256Hex(File file) {
        return ColgramUpdatePolicy.sha256Hex(file);
    }

    /** Downloads the APK file, optionally verifies SHA-256 checksum if known, and launches installer. */
    public static void downloadAndInstall(final Context context, final String apkUrl, final String versionName) {
        String cachedSha = apkUrl != null ? EXPECTED_SHA256_BY_URL.get(apkUrl) : null;
        downloadAndInstall(context, apkUrl, versionName, cachedSha);
    }

    /**
     * Downloads the APK file through {@link ColgramHttp} (using active SOCKS5 proxy, local DPI
     * tunnel, or DoH pinning) so OS download services never bypass Colgram's proxy route,
     * verifies expectedSha256 (when provided), and launches installer.
     */
    public static void downloadAndInstall(final Context context, final String apkUrl,
                                          final String versionName, final String expectedSha256) {
        if (context == null || apkUrl == null) return;
        final Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;

        File dlDir = appCtx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dlDir != null) {
            File oldFile = new File(dlDir, "Colgram-Update.apk");
            if (oldFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                oldFile.delete();
            }
        }

        mainHandler.post(() -> Toast.makeText(
                appCtx, "Загрузка Colgram " + versionName + " через защищённый канал...", Toast.LENGTH_SHORT).show());
        downloadViaColgramHttpFallback(appCtx, apkUrl, expectedSha256);
    }

    private static void downloadViaColgramHttpFallback(final Context ctx, final String apkUrl, final String expectedSha256) {
        executor.execute(() -> {
            File dlDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dlDir == null) dlDir = new File(ctx.getFilesDir(), "cache");
            if (!dlDir.exists()) dlDir.mkdirs();
            final File file = new File(dlDir, "Colgram-Update.apk");
            java.net.HttpURLConnection conn = null;
            try {
                String currentUrl = apkUrl;
                int finalCode = -1;
                for (int r = 0; r < 6; r++) {
                    conn = ColgramHttp.openBinaryStream(currentUrl, 15000, 60000);
                    int code = conn.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = conn.getHeaderField("Location");
                        conn.disconnect();
                        conn = null;
                        if (loc == null || loc.isEmpty()) throw new java.io.IOException("Redirect empty");
                        currentUrl = new java.net.URL(new java.net.URL(currentUrl), loc).toString();
                        continue;
                    }
                    finalCode = code;
                    break;
                }
                if (conn == null) throw new java.io.IOException("Too many redirects");
                if (finalCode < 200 || finalCode >= 300) throw new java.io.IOException("HTTP " + finalCode);
                try (java.io.InputStream in = conn.getInputStream();
                     java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    out.flush();
                }
                if (expectedSha256 != null && !expectedSha256.isEmpty()) {
                    String actual = sha256Hex(file);
                    if (actual == null || !actual.equalsIgnoreCase(expectedSha256)) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                        mainHandler.post(() -> Toast.makeText(ctx, "Ошибка SHA-256: файл обновления повреждён", Toast.LENGTH_LONG).show());
                        return;
                    }
                }
                mainHandler.post(() -> installApk(ctx, file));
            } catch (Throwable t) {
                mainHandler.post(() -> Toast.makeText(ctx, "Загрузка обновления не завершилась: " + t.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static void installApk(Context context, File apkFile) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(context, "Разрешите установку обновлений Colgram из неизвестных источников", Toast.LENGTH_LONG).show();
                Intent perm = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()));
                perm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(perm);
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Uri contentUri = resolveFileProviderUri(context, apkFile);
                intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
            } else {
                intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
            }
            context.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(context, "Не удалось запустить установщик: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static Uri resolveFileProviderUri(Context context, File apkFile) throws Exception {
        try {
            Class<?> fpClass = Class.forName("androidx.core.content.FileProvider");
            java.lang.reflect.Method method = fpClass.getMethod(
                    "getUriForFile", Context.class, String.class, File.class);
            Object uri = method.invoke(null, context, context.getPackageName() + ".provider", apkFile);
            if (!(uri instanceof Uri)) {
                throw new IllegalStateException("FileProvider returned null URI");
            }
            return (Uri) uri;
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw ite;
        }
    }
}
