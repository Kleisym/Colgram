package org.telegram.ui;

import android.app.Activity;
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

import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramHttp;
import org.colgram.core.ColgramUpdater;
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
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /** Upstream client, the base each Colgram build is patched from. */
    private static final String UPSTREAM_REPO = "DrKLO/Telegram";

    private static final int MENU_REFRESH = 1;

    private static final int ACTION_NONE = 0;
    private static final int ACTION_TOGGLE_AUTO = 1;
    private static final int ACTION_CHECK_NOW = 2;
    private static final int ACTION_RETRY_RELEASES = 3;
    private static final int ACTION_RETRY_TAGS = 4;
    private static final int ACTION_EDIT_REPO = 5;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed = false;

    private static final int FETCH_ATTEMPTS = 3;
    private static final long FETCH_RETRY_BASE_MS = 1200L;

    private static String getColgramRepo() {
        String repo = ColgramConfig.getUpdateRepo();
        return (repo == null || repo.trim().isEmpty()) ? ColgramConfig.DEFAULT_UPDATE_REPO : repo.trim();
    }

    private static class Row {
        // 0 = header, 1 = toggle, 2 = colgram apk, 3 = upstream tag, 4 = info/action
        final int type;
        final String title;
        final String subtitle;
        boolean checked;
        final int actionId;
        /** Direct APK URL for Colgram releases; null otherwise. */
        final String apkUrl;
        final String sha256;
        final String sha256Url;
        /** Upstream tag for rebuild entries; null otherwise. */
        final String tag;
        /** Browser fallback / info link. */
        final String url;

        Row(int type, String title, String subtitle, String apkUrl, String tag, String url) {
            this(type, title, subtitle, false, ACTION_NONE, apkUrl, null, null, tag, url);
        }

        Row(int type, String title, String subtitle, boolean checked, int actionId,
            String apkUrl, String sha256, String sha256Url, String tag, String url) {
            this.type = type;
            this.title = title;
            this.subtitle = subtitle;
            this.checked = checked;
            this.actionId = actionId;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.sha256Url = sha256Url;
            this.tag = tag;
            this.url = url;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private boolean releasesLoading = true;
    private boolean releasesFailed = false;
    private boolean tagsLoading = true;
    private boolean tagsFailed = false;
    private String quickCheckStatus = null;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Версии и обновления");
        ColgramMenuChrome.styleActionBar(actionBar);
        actionBar.createMenu().addItem(MENU_REFRESH, R.drawable.msg_retry).setContentDescription("Проверить обновления");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_REFRESH) {
                    triggerManualCheck();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        ColgramMenuChrome.attachDynamicListAnimations(listView);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) return;
            Row row = rows.get(position);
            if (row.actionId == ACTION_TOGGLE_AUTO) {
                boolean next = !ColgramConfig.isAutoUpdateEnabled();
                ColgramConfig.setAutoUpdateEnabled(next);
                row.checked = next;
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(next);
                }
            } else if (row.actionId == ACTION_CHECK_NOW) {
                triggerManualCheck();
            } else if (row.actionId == ACTION_RETRY_RELEASES) {
                releasesLoading = true;
                releasesFailed = false;
                rebuildRows();
                fetchReleases();
            } else if (row.actionId == ACTION_RETRY_TAGS) {
                tagsLoading = true;
                tagsFailed = false;
                rebuildRows();
                fetchUpstreamTags();
            } else if (row.actionId == ACTION_EDIT_REPO) {
                showEditRepoDialog();
            } else if (row.apkUrl != null) {
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
        executor.shutdownNow();
        super.onFragmentDestroy();
    }

    // ---------------------------------------------------------------- row assembly

    private void rebuildRows() {
        String colgramRepo = getColgramRepo();
        rows.clear();

        rows.add(new Row(10, "hero", null, null, null, null));
        rows.add(new Row(0, "Текущая сборка и автообновление", null, null, null, null));
        rows.add(new Row(4, "Colgram Client",
                "v" + BuildVars.BUILD_VERSION_STRING + " • Android " + Build.VERSION.RELEASE,
                false, ACTION_NONE, null, null, null, null, null));
        String pendingTag = ColgramConfig.getPendingUpstreamTag();
        String latestUpstreamTag = findNewestUpstreamTag();
        if (latestUpstreamTag != null && org.colgram.core.ColgramUpdatePolicy.isUpstreamTelegramNewer(
                BuildVars.BUILD_VERSION_STRING, latestUpstreamTag)) {
            rows.add(new Row(3, "База Telegram: есть обновление",
                    humanTag(latestUpstreamTag) + " новее текущей — нажмите для пересборки CI",
                    null, latestUpstreamTag, null));
        } else {
            rows.add(new Row(4, "База Telegram (DrKLO/Telegram)",
                    latestUpstreamTag != null
                            ? "актуальна (" + humanTag(latestUpstreamTag) + ")"
                            : (pendingTag != null ? "в очереди CI: " + pendingTag : "проверяется…"),
                    false, ACTION_NONE, null, null, null, null, null));
        }
        rows.add(new Row(1, "Автопроверка обновлений", null,
                ColgramConfig.isAutoUpdateEnabled(), ACTION_TOGGLE_AUTO, null, null, null, null, null));
        rows.add(new Row(4, "Проверить обновления сейчас",
                quickCheckStatus != null ? quickCheckStatus : "Сверить с последним релизом " + colgramRepo,
                false, ACTION_CHECK_NOW, null, null, null, null, null));
        rows.add(new Row(4, "Репозиторий обновлений",
                colgramRepo,
                false, ACTION_EDIT_REPO, null, null, null, null, null));
        rows.add(new Row(5, null, null, null, null, null));

        rows.add(new Row(0, "Сборки Colgram (установить)", null, null, null, null));
        if (releasesLoading) {
            rows.add(new Row(4, "Загрузка списка...", "Получаем релизы из GitHub", null, null, null));
        } else if (releasesFailed) {
            rows.add(new Row(4, "Не удалось загрузить релизы", "Нажмите для повторной попытки",
                    false, ACTION_RETRY_RELEASES, null, null, null, null, null));
            rows.add(new Row(4, "Открыть релизы в браузере", "https://github.com/" + colgramRepo + "/releases",
                    null, null, "https://github.com/" + colgramRepo + "/releases"));
        } else if (releases.isEmpty()) {
            rows.add(new Row(4, "Релизов пока нет", "Нажмите, чтобы проверить снова",
                    false, ACTION_RETRY_RELEASES, null, null, null, null, null));
        } else {
            for (ReleaseInfo r : releases) {
                rows.add(new Row(2, r.title, r.subtitle, false, ACTION_NONE,
                        r.apkUrl, r.sha256, r.sha256Url, null, r.pageUrl));
            }
        }
        rows.add(new Row(5, null, null, null, null, null));

        rows.add(new Row(0, "Версии Telegram (пересборка)", null, null, null, null));
        if (tagsLoading) {
            rows.add(new Row(4, "Загрузка тегов...", "Получаем версии из апстрима", null, null, null));
        } else if (tagsFailed || upstreamTags.isEmpty()) {
            rows.add(new Row(4, "Не удалось загрузить теги", "Нажмите для повторной попытки",
                    false, ACTION_RETRY_TAGS, null, null, null, null, null));
            rows.add(new Row(4, "Открыть теги апстрима", UPSTREAM_REPO,
                    null, null, "https://github.com/" + UPSTREAM_REPO + "/tags"));
        } else {
            for (String tag : upstreamTags) {
                rows.add(new Row(3, humanTag(tag),
                        "Исходник: " + tag + " — Colgram будет пересобран на этой версии",
                        null, tag, null));
            }
        }
        rows.add(new Row(5, null, null, null, null, null));

        rows.add(new Row(0, "Прочее", null, null, null, null));
        rows.add(new Row(4, "Список сборок в CI", "Все сборки пайплайна Colgram",
                null, null, "https://github.com/" + colgramRepo + "/actions/workflows/build-colgram.yml"));
        rows.add(new Row(5, null, null, null, null, null));

        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    private String findNewestUpstreamTag() {
        String best = null;
        for (String tag : upstreamTags) {
            if (tag == null || tag.trim().isEmpty()) continue;
            if (org.colgram.core.ColgramUpdatePolicy.parseSemver(tag).length < 2) continue;
            if (best == null || org.colgram.core.ColgramUpdatePolicy.compareSemver(tag, best) > 0) {
                best = tag;
            }
        }
        return best;
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
        final String sha256;
        final String sha256Url;
        final String pageUrl;

        ReleaseInfo(String title, String subtitle, String apkUrl, String sha256, String sha256Url, String pageUrl) {
            this.title = title;
            this.subtitle = subtitle;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.sha256Url = sha256Url;
            this.pageUrl = pageUrl;
        }
    }

    private final List<ReleaseInfo> releases = new ArrayList<>();
    private final List<String> upstreamTags = new ArrayList<>();

    private void triggerManualCheck() {
        quickCheckStatus = "Проверяем обновления...";
        releasesLoading = true;
        releasesFailed = false;
        tagsLoading = true;
        tagsFailed = false;
        rebuildRows();
        fetchReleases();
        fetchUpstreamTags();
        ColgramUpdater.checkForUpdates(BuildVars.BUILD_VERSION_STRING, new ColgramUpdater.UpdateCheckCallback() {
            @Override
            public void onUpdateAvailable(String newVersion, String releaseNotes, String downloadUrl) {
                if (destroyed) return;
                quickCheckStatus = "Доступна версия " + newVersion;
                rebuildRows();
                ColgramUpdater.PendingUpdate pending = ColgramUpdater.getPendingUpdate();
                String expectedSha = (pending != null && pending.expectedSha256 != null)
                        ? pending.expectedSha256
                        : ColgramUpdater.extractSha256FromText(releaseNotes);
                confirmInstall(new Row(2, "Обновление " + newVersion,
                        "Новая сборка готова к установке", false, ACTION_NONE,
                        downloadUrl, expectedSha, null, null, null));
            }

            @Override
            public void onUpToDate() {
                if (destroyed) return;
                quickCheckStatus = "Установлена актуальная версия";
                rebuildRows();
            }

            @Override
            public void onError(String error) {
                if (destroyed) return;
                quickCheckStatus = "Ошибка проверки: " + (error == null ? "сеть" : error);
                rebuildRows();
            }
        });
    }

    private void showEditRepoDialog() {
        Context ctx = getParentActivity();
        if (ctx == null) return;
        EditTextBoldCursor input = new EditTextBoldCursor(ctx);
        input.setText(getColgramRepo());
        input.setHint("owner/repo (по умолчанию Kleisym/Colgram)");
        input.setSingleLine(true);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourceProvider));
        input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourceProvider));
        FrameLayout wrap = new FrameLayout(ctx);
        int pad = AndroidUtilities.dp(20);
        wrap.setPadding(pad, AndroidUtilities.dp(8), pad, AndroidUtilities.dp(4));
        wrap.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx, resourceProvider);
        builder.setTitle("GitHub-репозиторий обновлений");
        builder.setView(wrap);
        builder.setPositiveButton("Сохранить", (d, w) -> {
            ColgramConfig.setUpdateRepo(input.getText() != null ? input.getText().toString() : "");
            quickCheckStatus = null;
            releasesLoading = true;
            releasesFailed = false;
            rebuildRows();
            fetchReleases();
        });
        builder.setNeutralButton("Сбросить", (d, w) -> {
            ColgramConfig.setUpdateRepo(ColgramConfig.DEFAULT_UPDATE_REPO);
            quickCheckStatus = null;
            releasesLoading = true;
            releasesFailed = false;
            rebuildRows();
            fetchReleases();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void fetchReleases() {
        final String repo = getColgramRepo();
        executor.execute(() -> {
            List<ReleaseInfo> found = new ArrayList<>();
            final boolean ok = withRetry("fetchReleases", () -> {
                found.clear();
                JSONArray arr = getJsonArray("https://api.github.com/repos/" + repo + "/releases?per_page=20");
                if (arr == null) return false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject rel = arr.optJSONObject(i);
                    if (rel == null) continue;
                    String tag = rel.optString("tag_name", "");
                    String name = rel.optString("name", "");
                    if (name.isEmpty()) name = tag;
                    String body = rel.optString("body", "");
                    boolean prerelease = rel.optBoolean("prerelease", false);
                    String published = rel.optString("published_at", "");
                    if (published.length() >= 10) published = published.substring(0, 10);

                    String apkUrl = null;
                    String shaUrl = null;
                    long size = 0;
                    JSONArray assets = rel.optJSONArray("assets");
                    if (assets != null) {
                        for (int a = 0; a < assets.length(); a++) {
                            JSONObject asset = assets.optJSONObject(a);
                            if (asset == null) continue;
                            String an = asset.optString("name", "");
                            String dl = asset.optString("browser_download_url", "");
                            if (apkUrl == null && an.endsWith(".apk") && !dl.isEmpty()) {
                                apkUrl = dl;
                                size = asset.optLong("size", 0);
                            } else if (shaUrl == null && an.endsWith(".sha256") && !dl.isEmpty()) {
                                shaUrl = dl;
                            }
                        }
                    }
                    if (apkUrl == null || apkUrl.isEmpty()) continue;

                    String shaFromBody = ColgramUpdater.extractSha256FromText(body);
                    StringBuilder sub = new StringBuilder();
                    if (ColgramUpdater.isNewerTag(tag, BuildVars.BUILD_VERSION_STRING)) {
                        sub.append("новее текущей • ");
                    }
                    if (prerelease) sub.append("pre-release • ");
                    if (size > 0) sub.append(formatSize(size)).append(" • ");
                    sub.append(published.isEmpty() ? tag : published);
                    if (shaFromBody != null || shaUrl != null) sub.append(" • SHA-256");

                    found.add(new ReleaseInfo(
                            name.isEmpty() ? tag : name,
                            sub.toString(),
                            apkUrl,
                            shaFromBody,
                            shaUrl,
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

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) ColgramBrowserRoutes.openConnection(new URL(urlStr));
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "Colgram-Android");
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) {
                throw new HttpStatusException(code, "No body HTTP " + code);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "getJsonArray: HTTP " + code + " for " + urlStr);
                throw new HttpStatusException(code, "HTTP " + code + " for " + urlStr);
            }
            String body = sb.toString().trim();
            if (body.isEmpty()) {
                return null;
            }
            if (body.startsWith("{")) {
                JSONObject obj = new JSONObject(body);
                if (obj.has("message")) {
                    Log.w(TAG, "GitHub API: " + obj.optString("message"));
                }
                return null;
            }
            return new JSONArray(body);
        } catch (HttpStatusException hse) {
            if (hse.code >= 400 && hse.code < 500 && hse.code != 408 && hse.code != 429) {
                throw hse;
            }
            return fetchJsonArrayViaHttpRelay(urlStr, hse);
        } catch (Throwable primaryErr) {
            return fetchJsonArrayViaHttpRelay(urlStr, primaryErr);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONArray fetchJsonArrayViaHttpRelay(String urlStr, Throwable cause) throws Exception {
        try {
            ColgramHttp.Response resp = ColgramHttp.get(urlStr);
            if (resp.code >= 200 && resp.code < 300 && resp.body != null) {
                String trimmed = resp.body.trim();
                if (trimmed.startsWith("[")) {
                    return new JSONArray(trimmed);
                }
            }
        } catch (Throwable ignored) {}
        if (cause instanceof Exception) throw (Exception) cause;
        throw new java.io.IOException(cause);
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
        Activity act = getParentActivity();
        if (destroyed || isFinishing() || act == null || act.isFinishing() || act.isDestroyed() || getVisibleDialog() != null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(act, resourceProvider);
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

        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        final AlertDialog progress = new AlertDialog(ctx, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setMessage("Загрузка сборки...\n" + row.title);
        progress.setCancelable(true);
        progress.setOnCancelListener(d -> cancelled.set(true));
        showDialog(progress);

        executor.execute(() -> {
            File target = null;
            try {
                if (cancelled.get()) return;
                String expectedSha = row.sha256;
                if ((expectedSha == null || expectedSha.isEmpty()) && row.sha256Url != null) {
                    try {
                        ColgramHttp.Response shaResp = ColgramHttp.get(row.sha256Url);
                        if (shaResp.code >= 200 && shaResp.code < 300) {
                            expectedSha = ColgramUpdater.extractSha256FromText(shaResp.body);
                        }
                    } catch (Throwable ignored) {}
                }

                File dir = new File(ctx.getExternalCacheDir(), "colgram_updates");
                if (!dir.exists()) dir.mkdirs();
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

                HttpURLConnection conn;
                try {
                    conn = openWithRedirects(row.apkUrl, 5);
                } catch (Throwable primaryDownloadErr) {
                    String currentUrl = row.apkUrl;
                    HttpURLConnection relayConn = null;
                    for (int hop = 0; hop < 6; hop++) {
                        relayConn = ColgramHttp.openBinaryStream(currentUrl, 15000, 60000);
                        int rc = relayConn.getResponseCode();
                        if (rc == 301 || rc == 302 || rc == 303 || rc == 307 || rc == 308) {
                            String loc = relayConn.getHeaderField("Location");
                            relayConn.disconnect();
                            if (loc == null || loc.isEmpty()) throw new java.io.IOException("Empty redirect location");
                            currentUrl = new URL(new URL(currentUrl), loc).toString();
                            continue;
                        }
                        break;
                    }
                    if (relayConn == null) throw primaryDownloadErr;
                    conn = relayConn;
                }
                try {
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) {
                        throw new java.io.IOException("HTTP " + code);
                    }
                    long total = conn.getContentLengthLong();
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(target)) {
                        byte[] buf = new byte[8192];
                        long done = 0;
                        int read;
                        int lastPct = -1;
                        while ((read = in.read(buf)) != -1) {
                            if (cancelled.get() || destroyed) {
                                //noinspection ResultOfMethodCallIgnored
                                target.delete();
                                return;
                            }
                            out.write(buf, 0, read);
                            digest.update(buf, 0, read);
                            done += read;
                            if (total > 0) {
                                final int pct = (int) (done * 100L / total);
                                if (pct != lastPct && pct % 5 == 0) {
                                    lastPct = pct;
                                    final long doneMb = done / (1024L * 1024L);
                                    final long totalMb = total / (1024L * 1024L);
                                    mainHandler.post(() -> {
                                        if (!destroyed && !cancelled.get() && progress.isShowing()) {
                                            progress.setMessage(
                                                    "Загрузка сборки... " + pct + "%\n"
                                                            + doneMb + " / " + totalMb + " МБ\n" + row.title);
                                        }
                                    });
                                }
                            }
                        }
                        out.flush();
                    }
                    if (expectedSha != null && !expectedSha.isEmpty()) {
                        byte[] rawHash = digest.digest();
                        StringBuilder hex = new StringBuilder(rawHash.length * 2);
                        for (byte b : rawHash) {
                            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                            hex.append(Character.forDigit(b & 0xF, 16));
                        }
                        String actualSha = hex.toString().toLowerCase(Locale.US);
                        if (!actualSha.equalsIgnoreCase(expectedSha)) {
                            //noinspection ResultOfMethodCallIgnored
                            target.delete();
                            throw new java.io.IOException("Контрольная сумма SHA-256 не совпала");
                        }
                    }
                } finally {
                    conn.disconnect();
                }

                final File apk = target;
                final Context appCtx = ctx.getApplicationContext();
                mainHandler.post(() -> {
                    try {
                        progress.dismiss();
                    } catch (Throwable ignore) {}
                    if (destroyed || cancelled.get()) return;
                    Activity curAct = getParentActivity();
                    launchInstaller(curAct != null ? curAct : appCtx, apk);
                });
            } catch (Throwable t) {
                final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                Log.w(TAG, "download failed: " + msg);
                final Context appCtx = ctx.getApplicationContext();
                mainHandler.post(() -> {
                    try {
                        progress.dismiss();
                    } catch (Throwable ignore) {}
                    if (destroyed) return;
                    Toast.makeText(appCtx, "Не удалось скачать: " + msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private HttpURLConnection openWithRedirects(String initialUrl, int maxHops) throws Exception {
        String current = initialUrl;
        for (int hop = 0; hop <= maxHops; hop++) {
            URL url = new URL(current);
            HttpURLConnection conn = (HttpURLConnection) ColgramBrowserRoutes.openConnection(url);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Colgram-Android");
            conn.connect();
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new java.io.IOException("Empty redirect location");
                }
                current = new URL(url, loc).toString();
                continue;
            }
            return conn;
        }
        throw new java.io.IOException("Too many redirects");
    }

    private void launchInstaller(Context ctx, File apk) {
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        try {
            if (Build.VERSION.SDK_INT >= 26 && !appCtx.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(appCtx, "Разрешите установку обновлений Colgram из неизвестных источников", Toast.LENGTH_LONG).show();
                Intent perm = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + appCtx.getPackageName()));
                perm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appCtx.startActivity(perm);
                return;
            }
            Uri uri = FileProvider.getUriForFile(appCtx, appCtx.getPackageName() + ".provider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appCtx.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "installer launch failed: " + t.getMessage());
            Toast.makeText(appCtx, "Не удалось запустить установщик: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * An upstream tag is source, not an installable APK, so record it as the pending
     * build tag and open the workflow that rebuilds Colgram on it.
     */
    private void confirmTagBuild(Row row) {
        Activity act = getParentActivity();
        if (destroyed || isFinishing() || act == null || act.isFinishing() || act.isDestroyed() || getVisibleDialog() != null) return;
        final Context appCtx = act.getApplicationContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(act, resourceProvider);
        builder.setTitle(row.title);
        builder.setMessage("Upstream-тег " + row.tag + " будет использован как база для сборки Colgram.\n\n"
                + "Установить эту версию сразу нельзя: это исходный код Telegram без патчей Colgram. "
                + "Тег будет сохранён, а CI пересоберёт Colgram на нём — готовый APK появится "
                + "в разделе «Сборки Colgram» выше.");
        builder.setPositiveButton("Сохранить тег и открыть CI", (d, w) -> {
            try {
                org.colgram.core.ColgramConfig.setPendingUpstreamTag(row.tag);
                Toast.makeText(appCtx,
                        "Тег " + row.tag + " сохранён", Toast.LENGTH_LONG).show();
            } catch (Throwable ignore) {
                // best-effort; the browser fallback still works
            }
            Activity curAct = getParentActivity();
            if (curAct != null) {
                Browser.openUrl(curAct,
                        "https://github.com/" + getColgramRepo() + "/actions/workflows/build-colgram.yml");
            }
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
            Row r = rows.get(pos);
            if (r.type == 0 || r.type == 5 || r.type == 10) return false;
            if (r.type == 4 && r.actionId == ACTION_NONE && r.url == null) return false;
            return true;
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= rows.size()) return 0;
            Row row = rows.get(position);
            int type = row.type;
            if (type == 10) return 10;
            if (type == 0) return 0;
            if (type == 1) return 1;
            if (type == 5) return 5;
            return 3;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == 10) {
                view = new ColgramMenuChrome.HeroBannerCell(mContext);
            } else if (viewType == 0) {
                view = new HeaderCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == 1) {
                view = new TextCheckCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == 5) {
                view = new ShadowSectionCell(mContext);
            } else if (viewType == 3) {
                view = new TextDetailSettingsCell(mContext);
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
            boolean divider = position + 1 < rows.size()
                    && rows.get(position + 1).type != 0 && rows.get(position + 1).type != 5;
            int vt = holder.getItemViewType();
            if (vt == 5) return;
            if (vt == 10) {
                ColgramMenuChrome.HeroBannerCell hero = (ColgramMenuChrome.HeroBannerCell) holder.itemView;
                String newestTag = findNewestUpstreamTag();
                boolean upstreamNewer = newestTag != null && org.colgram.core.ColgramUpdatePolicy.isUpstreamTelegramNewer(
                        BuildVars.BUILD_VERSION_STRING, newestTag);
                int accent = upstreamNewer ? ColgramMenuChrome.COLOR_AMBER : ColgramMenuChrome.COLOR_EMERALD;
                String pill = upstreamNewer ? "ОБНОВЛЕНИЕ" : "v" + BuildVars.BUILD_VERSION_STRING;
                String sub = releasesLoading || tagsLoading
                        ? "Синхронизация релизов Colgram и тегов DrKLO/Telegram…"
                        : (upstreamNewer
                                ? "Доступна новая база Telegram " + humanTag(newestTag) + " для пересборки Colgram"
                                : "Сборки с автопроверкой SHA-256 и прямой установкой APK");
                hero.bind(R.drawable.msg_download, accent,
                        "Версии Colgram",
                        sub,
                        pill,
                        "Релизы: " + releases.size(),
                        "Теги: " + upstreamTags.size(),
                        ColgramConfig.isAutoUpdateEnabled() ? "Авто: ВКЛ" : "Авто: ВЫКЛ");
            } else if (vt == 0) {
                HeaderCell hc = (HeaderCell) holder.itemView;
                hc.setText(row.title);
                ColgramMenuChrome.styleHeader(hc, 0xFF047857, 0xFF34D399);
            } else if (vt == 1) {
                TextCheckCell tc = (TextCheckCell) holder.itemView;
                ColgramMenuChrome.applyCheckIcon(tc, ColgramMenuChrome.COLOR_EMERALD, R.drawable.msg_retry);
                tc.setTextAndCheck(row.title, row.checked, divider);
            } else if (vt == 3) {
                TextDetailSettingsCell d = (TextDetailSettingsCell) holder.itemView;
                int iconRes = iconForRow(row);
                int iconColor = colorForRow(row);
                ColgramMenuChrome.applyDetailIcon(d, row.title, row.subtitle == null ? "" : row.subtitle, iconRes, iconColor, divider);
            } else {
                TextSettingsCell s = (TextSettingsCell) holder.itemView;
                ColgramMenuChrome.applySettingIcon(s, iconForRow(row), colorForRow(row));
                s.setTextAndValue(row.title, row.subtitle == null ? "" : row.subtitle, divider);
            }
        }

        private int iconForRow(Row row) {
            if (row.type == 2) return R.drawable.msg_download;
            if (row.type == 3) return R.drawable.msg_bots;
            if (row.actionId == ACTION_CHECK_NOW) return R.drawable.msg_retry;
            if (row.actionId == ACTION_EDIT_REPO) return R.drawable.msg_link;
            if (row.actionId == ACTION_RETRY_RELEASES || row.actionId == ACTION_RETRY_TAGS) return R.drawable.msg_retry;
            if (row.url != null) return R.drawable.msg_link;
            return R.drawable.msg_info;
        }

        private int colorForRow(Row row) {
            if (row.type == 2) return ColgramMenuChrome.COLOR_EMERALD;
            if (row.type == 3) return ColgramMenuChrome.COLOR_VIOLET;
            if (row.actionId == ACTION_CHECK_NOW) return ColgramMenuChrome.COLOR_CYAN;
            if (row.actionId == ACTION_EDIT_REPO) return ColgramMenuChrome.COLOR_INDIGO;
            if (row.actionId == ACTION_RETRY_RELEASES || row.actionId == ACTION_RETRY_TAGS) return ColgramMenuChrome.COLOR_AMBER;
            if (row.url != null) return ColgramMenuChrome.COLOR_TEAL;
            return ColgramMenuChrome.COLOR_BLUE;
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR,
                new Class[]{HeaderCell.class, TextCheckCell.class, TextSettingsCell.class, TextDetailSettingsCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        list.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND,
                null, null, null, null, Theme.key_windowBackgroundGray));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND,
                null, null, null, null, Theme.key_actionBarDefault));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR,
                null, null, null, null, Theme.key_actionBarDefaultIcon));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR,
                null, null, null, null, Theme.key_actionBarDefaultTitle));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR,
                null, null, null, null, Theme.key_listSelector));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextDetailSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{TextDetailSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER,
                new Class[]{ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR,
                new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));
        list.add(new ThemeDescription(listView, 0,
                new Class[]{ColgramMenuChrome.HeroBannerCell.class}, null, null, () -> {
            if (listView != null) {
                for (int i = 0; i < listView.getChildCount(); i++) {
                    View ch = listView.getChildAt(i);
                    if (ch instanceof ColgramMenuChrome.HeroBannerCell) {
                        ((ColgramMenuChrome.HeroBannerCell) ch).updateTheme();
                    }
                }
            }
        }, Theme.key_windowBackgroundWhite));
        return list;
    }
}
