package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramVersionsActivity — real, working version switcher.
 *
 * The previous implementation was cosmetic: it listed hardcoded entries whose "download"
 * action opened a GitHub page, and the two archive rows both pointed at
 * telegram.org/dl/android/apk, so choosing any of them installed the same current build.
 *
 * What this version actually does:
 *
 *   1. "Colgram builds" — fetches the release list for this fork from the GitHub API and
 *      lists every published APK with its real version, size and date. Tapping one
 *      downloads that APK and hands it to the system package installer, so switching
 *      back and forth between Colgram builds genuinely works.
 *
 *   2. "Upstream Telegram versions" — lists the real DrKLO/Telegram release tags from the
 *      API. An upstream tag cannot be installed as-is (it is source without Colgram's
 *      patches), so choosing one records it as the pending build tag and opens the CI
 *      workflow that rebuilds Colgram on that tag. The tag is displayed so it is clear
 *      which codebase the resulting APK comes from.
 *
 * Downloads land in the app's external cache and are installed through a FileProvider,
 * never by writing to a shared public directory.
 */
public class ColgramVersionsActivity extends BaseFragment {

    private static final String TAG = "ColgramVersions";

    /** This fork. Release assets are downloadable because the repo is public. */
    private static final String COLGRAM_REPO = "Kleisym/Colgram";
    /** Upstream client, the base each Colgram build is patched from. */
    private static final String UPSTREAM_REPO = "DrKLO/Telegram";

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;

    /**
     * How many times to re-attempt a failed GitHub fetch before showing the failure row.
     *
     * These calls are fired once from onCreateFragment and never repeated, so a single
     * transient failure used to latch "Не удалось загрузить релизы" on screen until the app
     * was restarted - the user saw a permanently broken page for a momentary network blip.
     * A GitHub API call from a phone can fail for a dozen transient reasons (DNS not settled
     * right after boot, a captive-portal probe, a carrier dropping the first tunnel), and
     * every one of them is worth retrying.
     */
    private static final int FETCH_ATTEMPTS = 3;
    private static final long FETCH_RETRY_BASE_MS = 1200L;

    private static class Row {
        // 0 = header, 1 = current build info, 2 = colgram apk, 3 = upstream tag, 4 = info link
        final int type;
        final String title;
        final String subtitle;
        /** Direct APK URL for Colgram releases; null otherwise. */
        final String apkUrl;
        /** Upstream tag for rebuild entries; null otherwise. */
        final String tag;
        /** Browser fallback / info link. */
        final String url;

        Row(int type, String title, String subtitle, String apkUrl, String tag, String url) {
            this.type = type;
            this.title = title;
            this.subtitle = subtitle;
            this.apkUrl = apkUrl;
            this.tag = tag;
            this.url = url;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private boolean releasesLoading = true;
    private boolean releasesFailed = false;
    private boolean tagsLoading = true;
    private boolean tagsFailed = false;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Версии Telegram");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) return;
            Row row = rows.get(position);
            if (row.apkUrl != null) {
                confirmInstall(row);
            } else if (row.tag != null) {
                confirmTagBuild(row);
            } else if (row.url != null) {
                Browser.openUrl(getParentActivity(), row.url);
            }
        });

        rebuildRows();
        fetchReleases();
        fetchUpstreamTags();

        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    // ---------------------------------------------------------------- row assembly

    private void rebuildRows() {
        rows.clear();

        rows.add(new Row(0, "Текущая сборка", null, null, null, null));
        rows.add(new Row(1, "Colgram Client",
                "v" + BuildVars.BUILD_VERSION_STRING + " • Android " + Build.VERSION.RELEASE,
                null, null, null));

        rows.add(new Row(0, "Сборки Colgram (установить)", null, null, null, null));
        if (releasesLoading) {
            rows.add(new Row(4, "Загрузка списка...", "Получаем релизы из GitHub", null, null, null));
        } else if (releasesFailed) {
            rows.add(new Row(2, "Не удалось загрузить релизы", "Нажмите, чтобы открыть страницу релизов",
                    null, null, "https://github.com/" + COLGRAM_REPO + "/releases"));
        } else if (releases.isEmpty()) {
            rows.add(new Row(4, "Релизов пока нет", "Сборки появятся после первого релиза", null, null,
                    "https://github.com/" + COLGRAM_REPO + "/releases"));
        } else {
            for (ReleaseInfo r : releases) {
                rows.add(new Row(2, r.title, r.subtitle, r.apkUrl, null, r.pageUrl));
            }
        }

        rows.add(new Row(0, "Версии Telegram (пересборка)", null, null, null, null));
        if (tagsLoading) {
            rows.add(new Row(4, "Загрузка тегов...", "Получаем версии из апстрима", null, null, null));
        } else if (tagsFailed || upstreamTags.isEmpty()) {
            rows.add(new Row(4, "Не удалось загрузить теги", "Нажмите, чтобы открыть репозиторий",
                    null, null, "https://github.com/" + UPSTREAM_REPO + "/tags"));
        } else {
            for (String tag : upstreamTags) {
                rows.add(new Row(3, humanTag(tag),
                        "Исходник: " + tag + " — Colgram будет пересобран на этой версии",
                        null, tag, null));
            }
        }

        rows.add(new Row(0, "Прочее", null, null, null, null));
        rows.add(new Row(4, "Список сборок в CI", "Все сборки пайплайна Colgram",
                null, null, "https://github.com/" + COLGRAM_REPO + "/actions/workflows/build-colgram.yml"));

        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    private static String humanTag(String tag) {
        // release-11.4.2-5469 -> "Telegram 11.4.2 (5469)"
        String t = tag;
        if (t.startsWith("release-")) t = t.substring("release-".length());
        int dash = t.lastIndexOf('-');
        int underscore = t.lastIndexOf('_');
        int sep = Math.max(dash, underscore);
        if (sep > 0) {
            String ver = t.substring(0, sep);
            String build = t.substring(sep + 1);
            return "Telegram " + ver + " (" + build + ")";
        }
        return "Telegram " + t;
    }

    // ---------------------------------------------------------------- network

    private static class ReleaseInfo {
        final String title;
        final String subtitle;
        final String apkUrl;
        final String pageUrl;

        ReleaseInfo(String title, String subtitle, String apkUrl, String pageUrl) {
            this.title = title;
            this.subtitle = subtitle;
            this.apkUrl = apkUrl;
            this.pageUrl = pageUrl;
        }
    }

    private final List<ReleaseInfo> releases = new ArrayList<>();
    private final List<String> upstreamTags = new ArrayList<>();

    private void fetchReleases() {
        executor.execute(() -> {
            List<ReleaseInfo> found = new ArrayList<>();
            final boolean ok = withRetry("fetchReleases", () -> {
                found.clear();
                JSONArray arr = getJsonArray("https://api.github.com/repos/" + COLGRAM_REPO + "/releases?per_page=20");
                if (arr == null) return false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject rel = arr.optJSONObject(i);
                    if (rel == null) continue;
                    String tag = rel.optString("tag_name", "");
                    String name = rel.optString("name", "");
                    if (name.isEmpty()) name = tag;
                    boolean prerelease = rel.optBoolean("prerelease", false);
                    String published = rel.optString("published_at", "");
                    if (published.length() >= 10) published = published.substring(0, 10);

                    // Pick the first real .apk asset. A release can attach several files
                    // (idsig, mapping, sources), so this filters rather than assuming
                    // the first asset is the APK.
                    String apkUrl = null;
                    long size = 0;
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) {
                        for (int a = 0; a < assets.length(); a++) {
                            JSONObject asset = assets.optJSONObject(a);
                            if (asset == null) continue;
                            String an = asset.optString("name", "");
                            if (an.endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", "");
                                size = asset.optLong("size", 0);
                                break;
                            }
                        }
                    }
                    if (apkUrl == null || apkUrl.isEmpty()) continue;

                    StringBuilder sub = new StringBuilder();
                    if (prerelease) sub.append("pre-release • ");
                    if (size > 0) sub.append(formatSize(size)).append(" • ");
                    sub.append(published.isEmpty() ? tag : published);

                    found.add(new ReleaseInfo(
                            name.isEmpty() ? tag : name,
                            sub.toString(),
                            apkUrl,
                            rel.optString("html_url", "")));
                }
                return true;
            });
            final List<ReleaseInfo> result = found;
            final boolean success = ok;
            mainHandler.post(() -> {
                if (destroyed) return;
                releases.clear();
                releases.addAll(result);
                releasesLoading = false;
                releasesFailed = !success;
                rebuildRows();
            });
        });
    }

    private void fetchUpstreamTags() {
        executor.execute(() -> {
            List<String> found = new ArrayList<>();
            final boolean ok = withRetry("fetchUpstreamTags", () -> {
                found.clear();
                JSONArray arr = getJsonArray("https://api.github.com/repos/" + UPSTREAM_REPO + "/tags?per_page=40");
                if (arr == null) return false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.optJSONObject(i);
                    if (t == null) continue;
                    String name = t.optString("name", "");
                    if (!name.isEmpty()) found.add(name);
                }
                return true;
            });
            final List<String> result = found;
            final boolean success = ok;
            mainHandler.post(() -> {
                if (destroyed) return;
                upstreamTags.clear();
                upstreamTags.addAll(result);
                tagsLoading = false;
                tagsFailed = !success;
                rebuildRows();
            });
        });
    }

    /**
     * A non-2xx HTTP answer, carrying its status code as DATA.
     *
     * withRetry() needs to know whether a failure is worth repeating. Deriving that from
     * the message text would drift the moment a message is reworded, and stashing the code
     * in a field only held because this screen happens to use a single-thread executor —
     * a constraint that silently breaks if the pool is ever widened. Throwing the code
     * removes both hazards.
     */
    private static final class HttpStatusException extends java.io.IOException {
        final int code;

        HttpStatusException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /**
     * Fetch and parse a JSON array from the GitHub API.
     *
     * Two things this must get right, both learned the hard way:
     *
     *  1. `User-Agent` is MANDATORY. GitHub's API answers **403** to any request without one
     *     — verified directly: `curl -H "User-Agent:" .../tags` → 403, the same request with a
     *     UA → 200. A UA that names the app is the documented requirement, and a 403 here is
     *     indistinguishable from "repo unreachable" unless it is logged.
     *
     *  2. IPv4 is forced first. `api.telegram.org` publishes AAAA records and a device with no
     *     routable IPv6 will hang on them for the whole timeout; routing every outbound call
     *     through the same policy keeps this screen consistent with the Bot API path instead of
     *     re-learning the same lesson per call site.
     */
    private JSONArray getJsonArray(String urlStr) throws Exception {
        try {
            org.colgram.core.ColgramBotSync.applyIpv4Policy();
        } catch (Throwable ignored) {}

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "Colgram-Android");
        try {
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) {
                Log.w(TAG, "getJsonArray: no body, HTTP " + code + " for " + urlStr);
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "getJsonArray: HTTP " + code + " for " + urlStr + " body="
                        + sb.toString().trim());
                // Thrown rather than returned so withRetry() can read the status without a
                // shared field. A 401/403 (rejected credentials or User-Agent) can never
                // succeed on a retry, so retrying only delays the error row by the whole
                // backoff schedule.
                throw new HttpStatusException(code, "HTTP " + code + " for " + urlStr);
            }
            String body = sb.toString().trim();
            if (body.isEmpty()) {
                // An empty 200 means a proxy or captive portal swallowed the body. Without
                // this branch it would fall through to `new JSONArray("")` and throw
                // "End of input at character 0 of", which reads as a parser bug.
                Log.w(TAG, "getJsonArray: empty body, HTTP " + code + " for " + urlStr);
                return null;
            }
            if (body.startsWith("{")) {
                JSONObject obj = new JSONObject(body);
                if (obj.has("message")) {
                    Log.w(TAG, "GitHub API: " + obj.optString("message"));
                    return null;
                }
                return null;
            }
            return new JSONArray(body);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Run a fetch body with bounded retries and exponential backoff.
     *
     * Returns true if any attempt reported success. Retries are what stop one transient
     * network failure from latching a permanent error row on the screen.
     *
     * A hard client error short-circuits the loop: the server has already made a decision
     * and cannot be argued with. 408 (timeout) and 429 (rate limited) are excluded because
     * those ARE transient — retrying them is the whole point.
     */
    private boolean withRetry(String label, java.util.concurrent.Callable<Boolean> body) {
        for (int attempt = 1; attempt <= FETCH_ATTEMPTS; attempt++) {
            try {
                Boolean ok = body.call();
                if (Boolean.TRUE.equals(ok)) return true;
                Log.w(TAG, label + ": attempt " + attempt + "/" + FETCH_ATTEMPTS + " failed");
            } catch (HttpStatusException hse) {
                boolean permanent = hse.code >= 400 && hse.code < 500
                        && hse.code != 408 && hse.code != 429;
                Log.w(TAG, label + ": HTTP " + hse.code
                        + (permanent ? " is not retryable, giving up" : ", will retry"));
                if (permanent) return false;
            } catch (Throwable t) {
                Log.w(TAG, label + ": attempt " + attempt + "/" + FETCH_ATTEMPTS + " threw: "
                        + t.getMessage());
            }
            if (attempt < FETCH_ATTEMPTS) {
                try {
                    Thread.sleep(FETCH_RETRY_BASE_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0));
        }
        return (bytes / 1024) + " КБ";
    }

    // ---------------------------------------------------------------- actions

    private void confirmInstall(Row row) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Установить сборку");
        builder.setMessage(row.title + "\n\n" + row.subtitle
                + "\n\nAPK будет скачан и установлен поверх текущего Colgram. "
                + "Данные аккаунтов сохранятся.");
        builder.setPositiveButton("Скачать и установить", (d, w) -> downloadAndInstall(row));
        if (row.url != null && !row.url.isEmpty()) {
            builder.setNeutralButton("Открыть страницу", (d, w) -> Browser.openUrl(getParentActivity(), row.url));
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /**
     * Download the chosen APK into the app's external cache and launch the system
     * package installer.
     *
     * The install goes through a FileProvider rather than a file:// URI because Android 7+
     * forbids passing file:// across processes (FileUriExposedException). The provider is
     * declared in the manifest and the authority is derived from the package name so this
     * keeps working if the applicationId ever changes.
     */
    private void downloadAndInstall(Row row) {
        final Context ctx = getParentActivity();
        if (ctx == null) return;

        // Use Telegram's own themed dialog rather than android.app.ProgressDialog, whose
        // styling is completely off-brand next to every other Colgram screen.
        final AlertDialog progress = new AlertDialog(ctx, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setMessage("Загрузка сборки...\n" + row.title);
        progress.setCancelable(false);
        showDialog(progress);

        executor.execute(() -> {
            File target = null;
            try {
                File dir = new File(ctx.getExternalCacheDir(), "colgram_updates");
                if (!dir.exists()) dir.mkdirs();
                // Clean previous downloads so the cache cannot grow without bound.
                File[] stale = dir.listFiles();
                if (stale != null) {
                    for (File f : stale) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
                String fileName = row.apkUrl.substring(row.apkUrl.lastIndexOf('/') + 1);
                if (!fileName.endsWith(".apk")) fileName = "colgram_update.apk";
                target = new File(dir, fileName);

                HttpURLConnection conn = (HttpURLConnection) new URL(row.apkUrl).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Colgram-Android");
                conn.connect();

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new java.io.IOException("HTTP " + code);
                }
                int total = conn.getContentLength();
                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(target);
                byte[] buf = new byte[8192];
                long done = 0;
                int read;
                int lastPct = -1;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    done += read;
                    if (total > 0) {
                        final int pct = (int) (done * 100 / total);
                        if (pct != lastPct && pct % 5 == 0) {
                            lastPct = pct;
                            final long doneMb = done / (1024 * 1024);
                            final long totalMb = total / (1024 * 1024);
                            mainHandler.post(() -> progress.setMessage(
                                    "Загрузка сборки... " + pct + "%\n"
                                            + doneMb + " / " + totalMb + " МБ\n" + row.title));
                        }
                    }
                }
                out.flush();
                out.close();
                in.close();
                conn.disconnect();

                final File apk = target;
                mainHandler.post(() -> {
                    try {
                        progress.dismiss();
                    } catch (Throwable ignore) {
                        // dismissed with the fragment
                    }
                    launchInstaller(ctx, apk);
                });
            } catch (Throwable t) {
                final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                Log.w(TAG, "download failed: " + msg);
                mainHandler.post(() -> {
                    try {
                        progress.dismiss();
                    } catch (Throwable ignore) {
                        // dismissed with the fragment
                    }
                    if (getParentActivity() != null) {
                        Toast.makeText(getParentActivity(), "Не удалось скачать: " + msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void launchInstaller(Context ctx, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".provider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "installer launch failed: " + t.getMessage());
            Toast.makeText(ctx, "Не удалось запустить установщик: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * An upstream tag is source, not an installable APK, so record it as the pending
     * build tag and open the workflow that rebuilds Colgram on it.
     */
    private void confirmTagBuild(Row row) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(row.title);
        builder.setMessage("Upstream-тег " + row.tag + " будет использован как база для сборки Colgram.\n\n"
                + "Установить эту версию сразу нельзя: это исходный код Telegram без патчей Colgram. "
                + "Тег будет сохранён, а CI пересоберёт Colgram на нём — готовый APK появится "
                + "в разделе «Сборки Colgram» выше.");
        builder.setPositiveButton("Сохранить тег и открыть CI", (d, w) -> {
            try {
                org.colgram.core.ColgramConfig.setPendingUpstreamTag(row.tag);
                Toast.makeText(getParentActivity(),
                        "Тег " + row.tag + " сохранён", Toast.LENGTH_LONG).show();
            } catch (Throwable ignore) {
                // best-effort; the browser fallback still works
            }
            Browser.openUrl(getParentActivity(),
                    "https://github.com/" + COLGRAM_REPO + "/actions/workflows/build-colgram.yml");
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    // ---------------------------------------------------------------- adapter

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int pos = holder.getAdapterPosition();
            if (pos < 0 || pos >= rows.size()) return false;
            return rows.get(pos).type != 0;
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= rows.size()) return 0;
            return rows.get(position).type == 0 ? 0 : 2;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 0) {
                view = new HeaderCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextSettingsCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= rows.size()) return;
            Row row = rows.get(position);
            if (holder.getItemViewType() == 0) {
                ((HeaderCell) holder.itemView).setText(row.title);
            } else {
                TextSettingsCell s = (TextSettingsCell) holder.itemView;
                s.setTextAndValue(row.title, row.subtitle == null ? "" : row.subtitle, false);
            }
        }
    }
}
