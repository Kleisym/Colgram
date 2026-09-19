package org.colgram.core;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramUpdater — In-app OTA updater.
 * Checks GitHub Releases for new APK builds, downloads them in the background,
 * and launches the system package installer.
 */
public class ColgramUpdater {

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface UpdateCheckCallback {
        void onUpdateAvailable(String newVersion, String releaseNotes, String downloadUrl);
        void onUpToDate();
        void onError(String error);
    }

    /**
     * Checks if a new release exists on GitHub.
     */
    public static void checkForUpdates(final String currentVersion, final UpdateCheckCallback callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String repo = ColgramConfig.getUpdateRepo();
                URL url = new URL("https://api.github.com/repos/" + repo + "/releases/latest");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject release = new JSONObject(sb.toString());
                    String tagName = release.optString("tag_name", "");
                    String body = release.optString("body", "");

                    // Find .apk asset
                    String apkDownloadUrl = null;
                    if (release.has("assets")) {
                        JSONArray assets = release.getJSONArray("assets");
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String name = asset.optString("name", "");
                            if (name.endsWith(".apk")) {
                                apkDownloadUrl = asset.optString("browser_download_url", null);
                                break;
                            }
                        }
                    }

                    final String finalApkUrl = apkDownloadUrl;
                    if (apkDownloadUrl != null && !tagName.equals(currentVersion)) {
                        mainHandler.post(() -> callback.onUpdateAvailable(tagName, body, finalApkUrl));
                    } else {
                        mainHandler.post(callback::onUpToDate);
                    }
                } else {
                    mainHandler.post(() -> callback.onError("Server returned code " + 0));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /**
     * Downloads the APK file and prompts Android PackageInstaller to install it.
     */
    public static void downloadAndInstall(final Context context, final String apkUrl, final String versionName) {
        if (context == null || apkUrl == null) return;

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
        request.setTitle("Colgram Update v" + versionName);
        request.setDescription("Downloading latest build...");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "Colgram-Update.apk");

        final DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return;

        final long downloadId = manager.enqueue(request);

        // Register receiver for install when download finishes
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    File file = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Colgram-Update.apk");
                    if (file.exists()) {
                        installApk(ctx, file);
                    }
                    try {
                        ctx.unregisterReceiver(this);
                    } catch (Exception ignored) {}
                }
            }
        }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
    }

    private static void installApk(Context context, File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Uri contentUri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".provider",
                apkFile
            );
            intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
        } else {
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
        }
        context.startActivity(intent);
    }
}
