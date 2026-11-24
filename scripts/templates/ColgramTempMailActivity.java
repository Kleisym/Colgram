package org.telegram.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.colgram.core.ColgramBotSync;
import org.colgram.core.ColgramHttp;
import org.colgram.core.ColgramTempMailDiscovery;
import org.colgram.core.ColgramTempMailDiscovery.DiscoveryResult;
import org.colgram.core.ColgramTempMailDiscovery.ProviderSpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ColgramTempMailActivity extends BaseFragment {

    private static final String TAG = "ColgramTempMail";
    private static final String PREFS_NAME = "colgram_tempmail";
    private static final int NET_ATTEMPTS = 3;
    private static final long NET_RETRY_BASE_MS = 1000L;

    private static final String EMAILNATOR_API = "https://www.emailnator.com";
    private static final String[] DOMAIN_API_BASES = {
            "https://api.mail.tm",
            "https://api.mail.gw",
    };
    private static final String GUERRILLA_API = "https://api.guerrillamail.com/ajax.php";

    public static class TempMessage {
        public final int id;
        public final String tmId;
        public final String from;
        public final String subject;
        public final String date;

        public TempMessage(int id, String from, String subject, String date) {
            this(id, "", from, subject, date);
        }

        public TempMessage(int id, String tmId, String from, String subject, String date) {
            this.id = id;
            this.tmId = tmId;
            this.from = from;
            this.subject = subject;
            this.date = date;
        }
    }

    private static final class CachedEmailDetail {
        final String from;
        final String subject;
        final String plainBody;

        CachedEmailDetail(String from, String subject, String plainBody) {
            this.from = from == null ? "" : from;
            this.subject = subject == null ? "" : subject;
            this.plainBody = plainBody == null ? "" : plainBody;
        }
    }

    private static final int MENU_REFRESH = 1;
    private static final int MENU_DOMAIN = 2;
    private static final int MENU_NEW = 3;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private String currentEmail = "";
    private String currentLogin = "";
    private String currentDomain = "";
    private String mailTmToken = "";
    private String apiBase = EMAILNATOR_API;
    private String provider = ColgramTempMailDiscovery.PROTOCOL_EMAILNATOR;
    private String guerrillaToken = "";

    private final List<TempMessage> messages = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, CachedEmailDetail> cachedBodies = new ConcurrentHashMap<>();
    private final List<String> availableDomains = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> domainApiBases = new ConcurrentHashMap<>();
    private final Map<String, ProviderSpec> domainProviderSpecs = new ConcurrentHashMap<>();
    private final List<ProviderSpec> discoveredProviders = Collections.synchronizedList(new ArrayList<>());
    private final LinkedHashSet<String> customSeeds = new LinkedHashSet<>();

    private String pendingDomain = null;
    private ProviderSpec pendingProvider = null;

    private volatile boolean autoRefreshEnabled = true;
    private volatile boolean autoRefreshRunning = false;
    private Runnable autoRefreshRunnable = null;
    private volatile boolean mailboxGenerating = false;
    private volatile boolean discoveryRunning = false;
    private volatile boolean bootstrapDone = false;
    private volatile boolean isDestroyed = false;

    private String lastDomainError;
    private String providerStatusLine = "";

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        restoreSavedMailboxLocal();
        loadStoredDiscoveryState();
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                autoRefreshEnabled = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getBoolean("auto_refresh", true);
            }
        } catch (Throwable ignored) {}
        if (autoRefreshEnabled) {
            startAutoRefresh();
        }
        startAutomaticBootstrap();
        return true;
    }

    private void startAutomaticBootstrap() {
        discoveryRunning = true;
        executor.execute(() -> {
            try {
                final ArrayList<String> fastDomains = fetchFastDomains();
                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    discoveryRunning = false;
                    bootstrapDone = true;
                    if (!fastDomains.isEmpty()) {
                        mergeAvailableDomains(fastDomains);
                    }
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    if (!hasActiveSession() && !mailboxGenerating) {
                        generateNewMailbox();
                    } else if (hasActiveSession()) {
                        fetchMessages(false);
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "fast bootstrap failed: " + t.getMessage());
                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    discoveryRunning = false;
                    bootstrapDone = true;
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                });
            }
            try {
                LinkedHashSet<String> seedSnapshot;
                synchronized (customSeeds) {
                    seedSnapshot = new LinkedHashSet<>(customSeeds);
                }
                DiscoveryResult res = ColgramTempMailDiscovery.discoverProviders(seedSnapshot);
                if (res != null && !res.workingProviders.isEmpty()) {
                    synchronized (discoveredProviders) {
                        discoveredProviders.clear();
                        discoveredProviders.addAll(res.workingProviders);
                    }
                }
                final ArrayList<String> extraDomains = fetchSlowFallbackDomains();
                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    if (!extraDomains.isEmpty()) {
                        mergeAvailableDomains(extraDomains);
                    }
                    saveDiscoveryState();
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                });
            } catch (Throwable t) {
                Log.w(TAG, "background discovery failed: " + t.getMessage());
            }
        });
    }

    private void mergeAvailableDomains(List<String> incoming) {
        if (incoming == null || incoming.isEmpty()) return;
        synchronized (availableDomains) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(availableDomains);
            for (String d : incoming) {
                if (d != null && !d.trim().isEmpty()) merged.add(d.trim());
            }
            availableDomains.clear();
            availableDomains.addAll(merged);
        }
        saveDiscoveryState();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (autoRefreshEnabled && !isDestroyed) {
            startAutoRefresh();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopAutoRefresh();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        isDestroyed = true;
        stopAutoRefresh();
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    private SharedPreferences getPrefs() {
        Context ctx = getParentActivity();
        if (ctx == null) ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return null;
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void loadStoredDiscoveryState() {
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs == null) return;
            discoveredProviders.clear();
            discoveredProviders.addAll(
                    ColgramTempMailDiscovery.deserializeProviders(prefs.getString("discovered_providers", "")));
            customSeeds.clear();
            String rawSeeds = prefs.getString("custom_provider_seeds", "");
            if (!rawSeeds.isEmpty()) {
                for (String s : rawSeeds.split("\\r?\\n")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) customSeeds.add(trimmed);
                }
            }
            availableDomains.clear();
            String cachedDoms = prefs.getString("cached_domains_v1", "");
            if (!cachedDoms.isEmpty()) {
                List<ProviderSpec> knownSpecs = collectAllProviders();
                for (String line : cachedDoms.split("\\r?\\n")) {
                    String[] parts = line.split("\\|", 2);
                    String dom = parts[0].trim();
                    if (dom.isEmpty()) continue;
                    availableDomains.add(dom);
                    if (parts.length == 2 && !parts[1].trim().isEmpty()) {
                        String pid = parts[1].trim();
                        for (ProviderSpec ps : knownSpecs) {
                            if (pid.equals(ps.id)) {
                                domainProviderSpecs.put(dom, ps);
                                if (ColgramTempMailDiscovery.PROTOCOL_HYDRA.equals(ps.protocol)) {
                                    domainApiBases.put(dom, ps.apiBase);
                                }
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "loadStoredDiscoveryState failed: " + t.getMessage());
        }
    }

    private void saveDiscoveryState() {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return;
        StringBuilder seedsSb = new StringBuilder();
        synchronized (customSeeds) {
            for (String s : customSeeds) {
                seedsSb.append(s).append('\n');
            }
        }
        List<String> domSnap;
        synchronized (availableDomains) {
            domSnap = new ArrayList<>(availableDomains);
        }
        StringBuilder domsSb = new StringBuilder();
        for (String d : domSnap) {
            ProviderSpec owner = domainProviderSpecs.get(d);
            domsSb.append(d).append('|').append(owner != null ? owner.id : "").append('\n');
        }
        List<ProviderSpec> provSnap;
        synchronized (discoveredProviders) {
            provSnap = new ArrayList<>(discoveredProviders);
        }
        prefs.edit()
                .putString("discovered_providers", ColgramTempMailDiscovery.serializeProviders(provSnap))
                .putString("custom_provider_seeds", seedsSb.toString().trim())
                .putString("cached_domains_v1", domsSb.toString().trim())
                .apply();
    }

    /**
     * Reads saved mailbox state from preferences without hitting the network.
     * Supports all built-in and discovered temp-mail protocols, including stateless (1secmail)
     * and token-session (GuerrillaMail, TempMail.lol, Temp-Mail.io) providers.
     */
    private boolean restoreSavedMailboxLocal() {
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs == null) return false;

            String address = prefs.getString("address", "");
            if (address.isEmpty()) return false;

            String savedProvider = normalizeProtocol(prefs.getString("provider", ColgramTempMailDiscovery.PROTOCOL_HYDRA));
            // Migrate away from legacy personal gmail_alias mode automatically -> generate a real live mailbox
            if ("gmail_alias".equals(savedProvider)) {
                prefs.edit()
                        .remove("provider")
                        .remove("address")
                        .remove("login")
                        .remove("domain")
                        .remove("gmail_alias_base")
                        .apply();
                return false;
            }
            String password = prefs.getString("password", "");
            String token = prefs.getString("token", "");
            String gToken = prefs.getString("guerrilla_token", "");
            String login = prefs.getString("login", "");
            String domain = prefs.getString("domain", "");

            if (ColgramTempMailDiscovery.PROTOCOL_GUERRILLA.equals(savedProvider)) {
                if (gToken.isEmpty() && token.isEmpty()) return false;
            } else if (ColgramTempMailDiscovery.PROTOCOL_1SECMAIL.equals(savedProvider)) {
                if (login.isEmpty() || domain.isEmpty()) return false;
            } else if (ColgramTempMailDiscovery.PROTOCOL_HYDRA.equals(savedProvider)) {
                if (password.isEmpty() && token.isEmpty()) return false;
            } else if (ColgramTempMailDiscovery.PROTOCOL_TEMPMAIL_LOL.equals(savedProvider)) {
                if (token.isEmpty()) return false;
            }

            provider = savedProvider;
            currentEmail = address;
            currentLogin = login;
            currentDomain = domain;
            mailTmToken = token;
            guerrillaToken = !gToken.isEmpty() ? gToken : token;
            apiBase = prefs.getString("api_base", defaultBaseForProtocol(savedProvider));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "restoreSavedMailboxLocal failed: " + t.getMessage());
            return false;
        }
    }

    private static String normalizeProtocol(String raw) {
        if (raw == null || raw.isEmpty() || "mailtm".equals(raw)) {
            return ColgramTempMailDiscovery.PROTOCOL_HYDRA;
        }
        return raw;
    }

    private static String defaultBaseForProtocol(String proto) {
        if (ColgramTempMailDiscovery.PROTOCOL_EMAILNATOR.equals(proto)) return EMAILNATOR_API;
        if (ColgramTempMailDiscovery.PROTOCOL_INBOXES.equals(proto)) return "https://inboxes.com/api/v2";
        if (ColgramTempMailDiscovery.PROTOCOL_GUERRILLA.equals(proto)) return GUERRILLA_API;
        if (ColgramTempMailDiscovery.PROTOCOL_TEMPMAIL_LOL.equals(proto)) return "https://api.tempmail.lol";
        if (ColgramTempMailDiscovery.PROTOCOL_MAIL_IO.equals(proto)) return "https://api.internal.temp-mail.io/api/v3";
        if (ColgramTempMailDiscovery.PROTOCOL_MAILDROP.equals(proto)) return "https://api.maildrop.cc/graphql";
        if (ColgramTempMailDiscovery.PROTOCOL_1SECMAIL.equals(proto)) return "https://www.1secmail.com/api/v1";
        return "https://api.mail.tm";
    }

    private boolean hasActiveSession() {
        if (currentEmail == null || currentEmail.isEmpty()) return false;
        if (ColgramTempMailDiscovery.PROTOCOL_EMAILNATOR.equals(provider)) {
            return !currentEmail.isEmpty();
        }
        if (ColgramTempMailDiscovery.PROTOCOL_GUERRILLA.equals(provider)) {
            return guerrillaToken != null && !guerrillaToken.isEmpty();
        }
        if (ColgramTempMailDiscovery.PROTOCOL_1SECMAIL.equals(provider)
                || ColgramTempMailDiscovery.PROTOCOL_INBOXES.equals(provider)) {
            return !currentEmail.isEmpty();
        }
        if (ColgramTempMailDiscovery.PROTOCOL_MAIL_IO.equals(provider)
                || ColgramTempMailDiscovery.PROTOCOL_MAILDROP.equals(provider)) {
            return !currentEmail.isEmpty();
        }
        return (mailTmToken != null && !mailTmToken.isEmpty())
                || (ColgramTempMailDiscovery.PROTOCOL_HYDRA.equals(provider) && hasSavedPassword());
    }

    private boolean hasSavedPassword() {
        SharedPreferences prefs = getPrefs();
        return prefs != null && !prefs.getString("password", "").isEmpty();
    }

    private void manualRefresh() {
        final Context ctx = getParentActivity();
        if (ctx == null) return;
        if (currentEmail == null || currentEmail.isEmpty()) {
            showToast("Сначала создайте ящик");
            return;
        }
        showToast("Обновление входящих...");
        executor.execute(() -> {
            if (ColgramTempMailDiscovery.PROTOCOL_HYDRA.equals(provider)) {
                String fresh = refreshMailToken();
                if (fresh != null || (mailTmToken != null && !mailTmToken.isEmpty())) {
                    fetchMessages(true);
                } else {
                    mainHandler.post(() -> showToast("Не удалось обновить токен ящика"));
                }
                return;
            }
            if (!hasActiveSession()) {
                mainHandler.post(() -> showToast("Сессия ящика истекла, создайте новый ящик"));
                return;
            }
            fetchMessages(true);
        });
    }

    private void showDomainPicker() {
        final Context ctx = getParentActivity();
        if (ctx == null) return;
        if (!availableDomains.isEmpty()) {
            presentDomainDialog();
            return;
        }
        showToast("Загрузка доменов...");
        executor.execute(() -> {
            ArrayList<String> got = fetchFastDomains();
            if (got.isEmpty()) got = fetchSlowFallbackDomains();
            final ArrayList<String> resolved = got;
            mainHandler.post(() -> {
                if (isDestroyed || getParentActivity() == null) return;
                mergeAvailableDomains(resolved);
                if (listAdapter != null) listAdapter.notifyDataSetChanged();
                presentDomainDialog();
            });
        });
    }

    private void presentDomainDialog() {
        final Activity ctx = getParentActivity();
        if (isDestroyed || isFinishing() || ctx == null || ctx.isFinishing() || ctx.isDestroyed() || getVisibleDialog() != null) return;

        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();

        labels.add("Авто-выбор (лучший свободный)");
        actions.add(() -> {
            pendingProvider = null;
            pendingDomain = null;
            generateNewMailbox();
        });

        labels.add("@gmail.com  ·  Нативный ящик Gmail");
        actions.add(() -> createNativeGmailMailbox("gmail.com"));

        labels.add("@googlemail.com  ·  Нативный ящик GoogleMail");
        actions.add(() -> createNativeGmailMailbox("googlemail.com"));

        List<String> domainSnapshot;
        synchronized (availableDomains) {
            domainSnapshot = new ArrayList<>(availableDomains);
        }
        for (String domain : domainSnapshot) {
            if ("gmail.com".equalsIgnoreCase(domain) || "googlemail.com".equalsIgnoreCase(domain)) continue;
            ProviderSpec owner = domainProviderSpecs.get(domain);
            String tag = owner != null ? ("  ·  " + owner.name) : "";
            labels.add("@" + domain + tag);
            actions.add(() -> {
                pendingProvider = owner;
                pendingDomain = domain;
                generateNewMailbox();
            });
        }

        for (ProviderSpec spec : collectAllProviders()) {
            if (!spec.reachable && domainSnapshot.isEmpty()) {
                continue;
            }
            String suffix = spec.reachable && spec.latencyMs >= 0
                    ? " · " + spec.latencyMs + " мс" : "";
            labels.add("Сервис: " + spec.name + suffix);
            actions.add(() -> {
                pendingProvider = spec;
                pendingDomain = null;
                generateNewMailbox();
            });
        }

        String title = availableDomains.isEmpty()
                ? "Выбор сервиса и домена"
                : "Домены (" + Math.max(2, availableDomains.size()) + " доступно)";
        showDialog(new AlertDialog.Builder(ctx, resourceProvider)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which >= 0 && which < actions.size()) {
                        actions.get(which).run();
                    }
                })
                .setNegativeButton("Отмена", null)
                .create());
    }

    /** Creates a native @gmail.com or @googlemail.com mailbox directly inside Colgram (zero browser/WebView). */
    private void createNativeGmailMailbox(final String targetDomain) {
        ProviderSpec spec = new ProviderSpec("emailnator", "Gmail / GoogleMail", EMAILNATOR_API,
                ColgramTempMailDiscovery.PROTOCOL_EMAILNATOR, -1, "builtin", true);
        pendingProvider = spec;
        pendingDomain = "googlemail.com".equalsIgnoreCase(targetDomain) ? "googlemail.com" : "gmail.com";
        showToast("Создаём @" + pendingDomain + "…");
        generateNewMailbox();
    }

    private List<ProviderSpec> collectAllProviders() {
        List<ProviderSpec> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ProviderSpec> snap;
        synchronized (discoveredProviders) {
            snap = new ArrayList<>(discoveredProviders);
        }
        for (ProviderSpec spec : snap) {
            String key = spec.apiBase.toLowerCase(Locale.ROOT) + "|" + spec.protocol;
            if (seen.add(key)) out.add(spec);
        }
        for (ProviderSpec spec : ColgramTempMailDiscovery.builtInProviders()) {
            String key = spec.apiBase.toLowerCase(Locale.ROOT) + "|" + spec.protocol;
            if (seen.add(key)) out.add(spec);
        }
        return out;
    }

    private void runInternetDiscovery() {
        final Context ctx = getParentActivity();
        if (ctx == null) return;
        if (discoveryRunning) {
            showToast("Поиск уже выполняется...");
            return;
        }
        discoveryRunning = true;
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
        showToast("Поиск и проверка бесплатных почтовых API в сети...");

        executor.execute(() -> {
            DiscoveryResult res = null;
            Throwable error = null;
            try {
                LinkedHashSet<String> seedSnapshot;
                synchronized (customSeeds) {
                    seedSnapshot = new LinkedHashSet<>(customSeeds);
                }
                res = ColgramTempMailDiscovery.discoverProviders(seedSnapshot);
            } catch (Throwable t) {
                error = t;
            }
            final DiscoveryResult finalRes = res;
            final Throwable finalErr = error;
            mainHandler.post(() -> {
                if (isDestroyed) return;
                discoveryRunning = false;
                if (finalRes != null) {
                    synchronized (discoveredProviders) {
                        discoveredProviders.clear();
                        discoveredProviders.addAll(finalRes.workingProviders);
                    }
                    saveDiscoveryState();
                }
                if (listAdapter != null) listAdapter.notifyDataSetChanged();
                Activity activity = getParentActivity();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                if (finalErr != null) {
                    showToast("Ошибка автопоиска: " + humanizeError(finalErr));
                    return;
                }
                presentDiscoveryReport(finalRes);
            });
        });
    }

    private void presentDiscoveryReport(DiscoveryResult res) {
        final Activity ctx = getParentActivity();
        if (isDestroyed || isFinishing() || ctx == null || ctx.isFinishing() || ctx.isDestroyed() || res == null) return;
        if (res.workingProviders.isEmpty()) {
            showDialog(new AlertDialog.Builder(ctx, resourceProvider)
                    .setTitle("Автопоиск источников")
                    .setMessage("Проверено кандидатов: " + res.probedCandidates
                            + "\nРабочие бесплатные API не ответили с текущей сети.\n\n"
                            + res.reportSummary)
                    .setPositiveButton("Добавить свой URL", (d, w) -> showAddCustomProviderDialog())
                    .setNegativeButton("Закрыть", null)
                    .create());
            return;
        }

        final String[] items = new String[res.workingProviders.size()];
        for (int i = 0; i < res.workingProviders.size(); i++) {
            ProviderSpec p = res.workingProviders.get(i);
            items[i] = p.name + " [" + p.protocol + "] — " + p.latencyMs + " мс";
        }
        showDialog(new AlertDialog.Builder(ctx, resourceProvider)
                .setTitle("Найдено рабочих API: " + res.workingProviders.size())
                .setItems(items, (d, which) -> {
                    pendingProvider = res.workingProviders.get(which);
                    pendingDomain = null;
                    generateNewMailbox();
                })
                .setNeutralButton("Подробный отчёт", (d, w) -> {
                    Activity curAct = getParentActivity();
                    if (isDestroyed || curAct == null || curAct.isFinishing() || curAct.isDestroyed()) return;
                    showDialog(new AlertDialog.Builder(curAct, resourceProvider)
                            .setTitle("Отчёт проверки (" + res.probedCandidates + " проверено)")
                            .setMessage(res.reportSummary)
                            .setPositiveButton("OK", null)
                            .create());
                })
                .setNegativeButton("Закрыть", null)
                .create());
    }

    private void showAddCustomProviderDialog() {
        final Context ctx = getParentActivity();
        if (ctx == null) return;

        FrameLayout container = new FrameLayout(ctx);
        int pad = AndroidUtilities.dp(20);
        container.setPadding(pad, AndroidUtilities.dp(8), pad, 0);

        EditText input = ColgramMenuChrome.makeDialogEditText(ctx, resourceProvider,
                "https://api.mail.gw или URL списка источников", false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        showDialog(new AlertDialog.Builder(ctx, resourceProvider)
                .setTitle("Добавить свой API / URL")
                .setMessage("Укажите базовый URL Hydra/TempMail API или страницу со списком сервисов. Colgram проверит протокол автоматически.")
                .setView(container)
                .setPositiveButton("Проверить и добавить", (d, w) -> {
                    String raw = input.getText().toString().trim();
                    if (raw.isEmpty()) return;
                    if (!raw.startsWith("https://") && !raw.startsWith("http://")) {
                        raw = "https://" + raw;
                    }
                    probeAndSaveCustomUrl(raw);
                })
                .setNegativeButton("Отмена", null)
                .create());
    }

    private void probeAndSaveCustomUrl(String rawUrl) {
        showToast("Проверка источника " + rawUrl + "...");
        executor.execute(() -> {
            synchronized (customSeeds) {
                customSeeds.add(rawUrl);
            }
            List<String> extracted = ColgramTempMailDiscovery.extractCandidateUrls(rawUrl);
            if (extracted.isEmpty()) {
                extracted = Collections.singletonList(rawUrl);
            }
            ProviderSpec verified = null;
            for (String candidateUrl : extracted) {
                ProviderSpec probe = ColgramTempMailDiscovery.probeProvider(
                        new ProviderSpec("custom_" + Integer.toHexString(candidateUrl.hashCode()),
                                hostOf(candidateUrl), candidateUrl, ColgramTempMailDiscovery.PROTOCOL_HYDRA,
                                -1, "custom", false));
                if (probe != null && probe.reachable) {
                    verified = probe;
                    break;
                }
            }
            final ProviderSpec finalSpec = verified;
            mainHandler.post(() -> {
                saveDiscoveryState();
                if (finalSpec != null) {
                    upsertDiscoveredProvider(finalSpec);
                    saveDiscoveryState();
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    pendingProvider = finalSpec;
                    pendingDomain = null;
                    showToast("Источник работает (" + finalSpec.protocol + ", " + finalSpec.latencyMs + " мс)");
                    generateNewMailbox();
                } else {
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    showToast("URL сохранён как источник для автопоиска, но прямой API не ответил");
                }
            });
        });
    }

    private void upsertDiscoveredProvider(ProviderSpec spec) {
        synchronized (discoveredProviders) {
            for (int i = 0; i < discoveredProviders.size(); i++) {
                ProviderSpec existing = discoveredProviders.get(i);
                if (existing.apiBase.equalsIgnoreCase(spec.apiBase) && existing.protocol.equals(spec.protocol)) {
                    discoveredProviders.set(i, spec);
                    return;
                }
            }
            discoveredProviders.add(0, spec);
        }
    }

    private ArrayList<String> fetchDomainList() {
        ArrayList<String> out = fetchFastDomains();
        ArrayList<String> extra = fetchSlowFallbackDomains();
        for (String d : extra) {
            if (!out.contains(d)) out.add(d);
        }
        saveDiscoveryState();
        return out;
    }

    /** Keyless Tier-1 providers (Inboxes.com 18 domains + Temp-Mail.io + GuerrillaMail + Maildrop.cc). */
    private ArrayList<String> fetchFastDomains() {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seenDomains = new LinkedHashSet<>();
        StringBuilder reasons = new StringBuilder();

        // Native @gmail.com and @googlemail.com via built-in keyless Emailnator API (zero browser/WebView)
        ProviderSpec emailnatorSpec = new ProviderSpec("emailnator", "Gmail / GoogleMail", EMAILNATOR_API,
                ColgramTempMailDiscovery.PROTOCOL_EMAILNATOR, 120, "builtin", true);
        if (seenDomains.add("gmail.com")) {
            domainProviderSpecs.put("gmail.com", emailnatorSpec);
            out.add("gmail.com");
        }
        if (seenDomains.add("googlemail.com")) {
            domainProviderSpecs.put("googlemail.com", emailnatorSpec);
            out.add("googlemail.com");
        }

        // 0. Keyless Inboxes.com public domains (18 live fast domains!)
        ProviderSpec inboxesSpec = null;
        for (ProviderSpec spec : collectAllProviders()) {
            if (ColgramTempMailDiscovery.PROTOCOL_INBOXES.equals(normalizeProtocol(spec.protocol))
                    && !spec.apiBase.isEmpty()) {
                inboxesSpec = spec;
                break;
            }
        }
        if (inboxesSpec != null) {
            try {
                JSONObject res = httpGetJsonOnce(inboxesSpec.apiBase + "/domain");
                JSONArray doms = res.optJSONArray("domains");
                if (doms != null) {
                    ProviderSpec liveSpec = new ProviderSpec(inboxesSpec.id, inboxesSpec.name, inboxesSpec.apiBase,
                            ColgramTempMailDiscovery.PROTOCOL_INBOXES, 90, inboxesSpec.source, true);
                    for (int i = 0; i < doms.length(); i++) {
                        JSONObject d = doms.optJSONObject(i);
                        String dn = d != null ? d.optString("qdn", d.optString("name", "")).trim() : "";
                        if (!dn.isEmpty() && seenDomains.add(dn)) {
                            domainProviderSpecs.put(dn, liveSpec);
                            out.add(dn);
                        }
                    }
                    upsertDiscoveredProvider(liveSpec);
                }
            } catch (Throwable t) {
                reasons.append("inboxes.com: ").append(humanizeError(t)).append('\n');
            }
        }

        // 1. Keyless Temp-Mail.io public domains (verified fastest/direct)
        List<ProviderSpec> mailIoSpecs = new ArrayList<>();
        for (ProviderSpec spec : collectAllProviders()) {
            if (ColgramTempMailDiscovery.PROTOCOL_MAIL_IO.equals(normalizeProtocol(spec.protocol))
                    && !spec.apiBase.isEmpty()) {
                mailIoSpecs.add(spec);
            }
        }
        for (ProviderSpec spec : mailIoSpecs) {
            try {
                JSONObject res = httpGetJsonOnce(spec.apiBase + "/domains");
                JSONArray doms = res.optJSONArray("domains");
                if (doms == null) doms = res.optJSONArray("hydra:member");
                if (doms != null) {
                    for (int i = 0; i < doms.length(); i++) {
                        JSONObject d = doms.optJSONObject(i);
                        String dn = d != null ? d.optString("name", d.optString("domain", "")).trim()
                                : doms.optString(i, "").trim();
                        String dtype = d != null ? d.optString("type", "public") : "public";
                        if (!dn.isEmpty() && !"private".equalsIgnoreCase(dtype) && seenDomains.add(dn)) {
                            domainProviderSpecs.put(dn, spec);
                            out.add(dn);
                        }
                    }
                    upsertDiscoveredProvider(new ProviderSpec(spec.id, spec.name, spec.apiBase,
                            ColgramTempMailDiscovery.PROTOCOL_MAIL_IO, 100, spec.source, true));
                }
            } catch (Throwable t) {
                reasons.append(hostOf(spec.apiBase)).append(": ").append(humanizeError(t)).append('\n');
            }
        }

        // 2. Maildrop.cc GraphQL keyless domain
        ProviderSpec maildropSpec = null;
        for (ProviderSpec spec : collectAllProviders()) {
            if (ColgramTempMailDiscovery.PROTOCOL_MAILDROP.equals(normalizeProtocol(spec.protocol))) {
                maildropSpec = spec;
                break;
            }
        }
        if (maildropSpec != null) {
            try {
                JSONObject ping = httpPostJsonOnce(maildropSpec.apiBase,
                        "{\"query\":\"query{ping(message:\\\"ok\\\")}\"}");
                JSONObject data = ping.optJSONObject("data");
                if (data != null && !data.optString("ping", "").isEmpty()) {
                    if (seenDomains.add("maildrop.cc")) {
                        domainProviderSpecs.put("maildrop.cc", maildropSpec);
                        out.add("maildrop.cc");
                    }
                    upsertDiscoveredProvider(new ProviderSpec(maildropSpec.id, maildropSpec.name,
                            maildropSpec.apiBase, ColgramTempMailDiscovery.PROTOCOL_MAILDROP,
                            120, maildropSpec.source, true));
                }
            } catch (Throwable t) {
                reasons.append("maildrop.cc: ").append(humanizeError(t)).append('\n');
            }
        }

        // 3. GuerrillaMail instant keyless domains (8 verified domains)
        try {
            ProviderSpec gSpec = new ProviderSpec("guerrilla", "GuerrillaMail", GUERRILLA_API,
                    ColgramTempMailDiscovery.PROTOCOL_GUERRILLA, 140, "builtin", true);
            for (String gd : ColgramTempMailDiscovery.GUERRILLA_DOMAINS) {
                if (seenDomains.add(gd)) {
                    domainProviderSpecs.put(gd, gSpec);
                    out.add(gd);
                }
            }
        } catch (Throwable ignored) {}

        if (!out.isEmpty()) {
            lastDomainError = null;
        } else if (reasons.length() > 0) {
            lastDomainError = reasons.toString().trim();
        }
        return out;
    }

    /** Optional slower mirrors (Hydra / GuerrillaMail) queried with single fast tries so blocked domains never stall UI. */
    private ArrayList<String> fetchSlowFallbackDomains() {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seenDomains;
        synchronized (availableDomains) {
            seenDomains = new LinkedHashSet<>(availableDomains);
        }

        LinkedHashSet<String> hydraBases = new LinkedHashSet<>(Arrays.asList(DOMAIN_API_BASES));
        synchronized (discoveredProviders) {
            for (ProviderSpec spec : discoveredProviders) {
                if (ColgramTempMailDiscovery.PROTOCOL_HYDRA.equals(spec.protocol) && !spec.apiBase.isEmpty()) {
                    hydraBases.add(spec.apiBase);
                }
            }
        }
        for (String base : hydraBases) {
            try {
                int page = 1;
                int seen = 0;
                while (page <= 2) {
                    JSONObject domRes = httpGetJsonOnce(base + "/domains?page=" + page);
                    JSONArray members = domRes.optJSONArray("hydra:member");
                    if (members == null || members.length() == 0) break;
                    seen += members.length();
                    ProviderSpec hydraSpec = new ProviderSpec("hydra_" + hostOf(base), hostOf(base), base,
                            ColgramTempMailDiscovery.PROTOCOL_HYDRA, 200, "builtin", true);
                    for (int i = 0; i < members.length(); i++) {
                        JSONObject d = members.optJSONObject(i);
                        if (d == null || !d.optBoolean("isActive", true)
                                || d.optBoolean("isPrivate", false)) continue;
                        String dn = d.optString("domain", "").trim();
                        if (!dn.isEmpty() && !domainApiBases.containsKey(dn)) {
                            domainApiBases.put(dn, base);
                            domainProviderSpecs.put(dn, hydraSpec);
                            if (seenDomains.add(dn)) out.add(dn);
                        }
                    }
                    JSONObject view = domRes.optJSONObject("hydra:view");
                    String next = view == null ? "" : view.optString("hydra:next", "");
                    int total = domRes.optInt("hydra:totalItems", -1);
                    if (next.isEmpty() && (total >= 0 ? seen >= total : members.length() < 30)) break;
                    page++;
                    Thread.sleep(100);
                }
            } catch (Throwable ignored) {}
        }

        try {
            JSONObject gr = httpGetJsonOnce(GUERRILLA_API + "?f=get_email_address&lang=en");
            if (!gr.optString("email_addr", "").isEmpty()) {
                ProviderSpec gSpec = new ProviderSpec("guerrilla", "GuerrillaMail", GUERRILLA_API,
                        ColgramTempMailDiscovery.PROTOCOL_GUERRILLA, 250, "builtin", true);
                for (String gd : ColgramTempMailDiscovery.GUERRILLA_DOMAINS) {
                    if (seenDomains.add(gd)) {
                        domainProviderSpecs.put(gd, gSpec);
                        out.add(gd);
                    }
                }
                upsertDiscoveredProvider(gSpec);
            }
        } catch (Throwable ignored) {}

        return out;
    }

    private void probeProviderStatus() {
        StringBuilder sb = new StringBuilder("Быстрый пинг сервисов:");
        for (ProviderSpec spec : collectAllProviders()) {
            ProviderSpec probed = ColgramTempMailDiscovery.probeProvider(spec);
            if (probed != null && probed.reachable) {
                upsertDiscoveredProvider(probed);
                sb.append("\n• ").append(probed.name).append(": OK (").append(probed.latencyMs).append(" мс)");
            } else {
                sb.append("\n• ").append(spec.name).append(": недоступен");
            }
        }
        saveDiscoveryState();
        providerStatusLine = sb.toString();
    }

    private void toggleAutoRefresh() {
        autoRefreshEnabled = !autoRefreshEnabled;
        SharedPreferences prefs = getPrefs();
        if (prefs != null) {
            prefs.edit().putBoolean("auto_refresh", autoRefreshEnabled).apply();
        }
        if (autoRefreshEnabled) {
            startAutoRefresh();
        } else {
            stopAutoRefresh();
        }
        if (listAdapter != null) {
            listAdapter.notifyItemChanged(listAdapter.heroPos());
            listAdapter.notifyItemChanged(listAdapter.autoRowPos());
        }
        showToast(autoRefreshEnabled
                ? "Автообновление включено (каждые 5 секунд)"
                : "Автообновление выключено");
    }

    private void startAutoRefresh() {
        if (autoRefreshRunning || isDestroyed) return;
        autoRefreshRunning = true;
        autoRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDestroyed || !autoRefreshRunning) return;
                if (hasActiveSession()) {
                    fetchMessages(false);
                }
                if (autoRefreshRunning && !isDestroyed) {
                    mainHandler.postDelayed(this, 5000);
                }
            }
        };
        mainHandler.postDelayed(autoRefreshRunnable, 5000);
    }

    private void stopAutoRefresh() {
        autoRefreshRunning = false;
        if (autoRefreshRunnable != null) {
            mainHandler.removeCallbacks(autoRefreshRunnable);
            autoRefreshRunnable = null;
        }
    }

    private void generateNewMailbox() {
        if (mailboxGenerating) return;
        mailboxGenerating = true;
        if (listAdapter != null) listAdapter.notifyDataSetChanged();

        final String chosenDomain = pendingDomain;
        final ProviderSpec chosenProvider = pendingProvider;

        executor.execute(() -> {
            Throwable lastError = null;
            List<ProviderSpec> chain = buildCreationChain(chosenProvider, chosenDomain);
            for (ProviderSpec candidate : chain) {
                try {
                    if (createMailboxOnProvider(candidate, chosenDomain)) {
                        return;
                    }
                } catch (Throwable t) {
                    lastError = t;
                    Log.w(TAG, "Provider " + candidate.name + " failed: " + t.getMessage());
                }
            }
            mailboxGenerating = false;
            final String errText = lastError != null ? humanizeError(lastError) : "ни один сервис не ответил";
            mainHandler.post(() -> {
                if (isDestroyed) return;
                showToast("Не удалось создать ящик: " + errText);
                if (listAdapter != null) listAdapter.notifyDataSetChanged();
            });
        });
    }

    private List<ProviderSpec> buildCreationChain(ProviderSpec explicitProvider, String explicitDomain) {
        List<ProviderSpec> chain = new ArrayList<>();
        if (explicitProvider != null) {
            chain.add(explicitProvider);
        }
        if (explicitDomain != null && domainProviderSpecs.containsKey(explicitDomain)) {
            ProviderSpec mapped = domainProviderSpecs.get(explicitDomain);
            if (mapped != null) chain.add(mapped);
        }
        if (explicitDomain != null && isGuerrillaDomain(explicitDomain)) {
            chain.add(new ProviderSpec("guerrilla", "GuerrillaMail", GUERRILLA_API,
                    ColgramTempMailDiscovery.PROTOCOL_GUERRILLA, -1, "builtin", true));
        }
        if (explicitDomain != null && domainApiBases.containsKey(explicitDomain)) {
            String base = domainApiBases.get(explicitDomain);
            chain.add(new ProviderSpec("hydra_domain", hostOf(base), base,
                    ColgramTempMailDiscovery.PROTOCOL_HYDRA, -1, "builtin", true));
        }
        for (ProviderSpec spec : collectAllProviders()) {
            boolean duplicate = false;
            for (ProviderSpec existing : chain) {
                if (existing.apiBase.equalsIgnoreCase(spec.apiBase) && existing.protocol.equals(spec.protocol)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) chain.add(spec);
        }
        return chain;
    }

    private boolean createMailboxOnProvider(ProviderSpec spec, String explicitDomain) throws Exception {
        String proto = normalizeProtocol(spec.protocol);
        if (ColgramTempMailDiscovery.PROTOCOL_EMAILNATOR.equals(proto)) {
            return createEmailnatorMailbox(spec.apiBase, explicitDomain, spec.name);
        }
        if (ColgramTempMailDiscovery.PROTOCOL_INBOXES.equals(proto)) {
            return createInboxesMailbox(spec.apiBase, explicitDomain, spec.name);
        }
        if (ColgramTempMailDiscovery.PROTOCOL_GUERRILLA.equals(proto)) {
            return createGuerrillaMailbox(spec.apiBase, explicitDomain);
        }
        if (ColgramTempMailDiscovery.PROTOCOL_TEMPMAIL_LOL.equals(proto)) {
            return createTempMailLolMailbox(spec.apiBase, spec.name);
        }
        if (ColgramTempMailDiscovery.PROTOCOL_MAIL_IO.equals(proto)) {
            return createTempMailIoMailbox(spec.apiBase, explicitDomain, spec.name);
        }
        if (ColgramTempMailDiscovery.PROTOCOL_MAILDROP.equals(proto)) {
            return createMaildropMailbox(spec.apiBase, spec.name);
        }
        if (ColgramTempMailDiscovery.PROTOCOL_1SECMAIL.equals(proto)) {
            return createOneSecMailMailbox(spec.apiBase, explicitDomain, spec.name);
        }
        return createHydraMailbox(spec.apiBase, explicitDomain, spec.name);
    }

    private boolean createHydraMailbox(String baseUrl, String explicitDomain, String label) throws Exception {
        String domain = explicitDomain;
        if (domain == null || domain.isEmpty() || !baseUrl.equals(domainApiBases.get(domain))) {
            domain = resolveHydraDomain(baseUrl);
        }
        if (domain == null || domain.isEmpty()) {
            throw new IOException(hostOf(baseUrl) + ": домены не найдены");
        }
        final String targetDomain = domain;
        final String login = "colgram" + (System.currentTimeMillis() % 1000000L) + (int) (Math.random() * 9000 + 1000);
        final String address = login + "@" + targetDomain;
        final String password = "Colgram_" + (int) (Math.random() * 900000 + 100000);

        JSONObject create = new JSONObject();
        create.put("address", address);
        create.put("password", password);
        withRetry("create hydra account", () -> {
            JSONObject created = httpPostJson(baseUrl + "/accounts", create.toString());
            String id = created.optString("id", "");
            if (id.isEmpty()) throw new IOException("сервис не подтвердил создание ящика");
            return id;
        });

        JSONObject auth = new JSONObject();
        auth.put("address", address);
        auth.put("password", password);
        String token = withRetry("authenticate hydra", () -> {
            JSONObject res = httpPostJson(baseUrl + "/token", auth.toString());
            String t = res.optString("token", "");
            if (t.isEmpty()) throw new IOException("сервис не выдал токен");
            return t;
        });

        applyCreatedMailbox(ColgramTempMailDiscovery.PROTOCOL_HYDRA, baseUrl, address, login,
                targetDomain, password, token, "", label);
        return true;
    }

    private String resolveHydraDomain(String baseUrl) throws Exception {
        JSONObject domRes = httpGetJson(baseUrl + "/domains?page=1");
        JSONArray members = domRes.optJSONArray("hydra:member");
        if (members == null) return "";
        for (int i = 0; i < members.length(); i++) {
            JSONObject d = members.optJSONObject(i);
            if (d == null || !d.optBoolean("isActive", true) || d.optBoolean("isPrivate", false)) continue;
            String dn = d.optString("domain", "").trim();
            if (!dn.isEmpty()) {
                domainApiBases.put(dn, baseUrl);
                return dn;
            }
        }
        return "";
    }

    private boolean createGuerrillaMailbox(String baseUrl, String preferredDomain) throws Exception {
        String api = (baseUrl == null || baseUrl.isEmpty()) ? GUERRILLA_API : baseUrl;
        JSONObject r = httpGetJson(api + "?f=get_email_address&lang=en");
        String serverAddr = r.optString("email_addr", "");
        String tok = r.optString("sid_token", "");
        if (serverAddr.isEmpty() || tok.isEmpty()) return false;
        int at = serverAddr.indexOf('@');
        String login = at > 0 ? serverAddr.substring(0, at) : serverAddr;
        String serverDomain = at > 0 ? serverAddr.substring(at + 1) : "guerrillamailblock.com";
        String finalDomain = isGuerrillaDomain(preferredDomain) ? preferredDomain : serverDomain;
        String addr = login + "@" + finalDomain;

        applyCreatedMailbox(ColgramTempMailDiscovery.PROTOCOL_GUERRILLA, api, addr, login,
                finalDomain, "", "", tok, "GuerrillaMail");
        return true;
    }

    private boolean createTempMailLolMailbox(String baseUrl, String label) throws Exception {
        String root = tempMailLolRoot(baseUrl);
        JSONObject r;
        try {
            r = httpPostJson(root + "/v2/inbox/create", "{}");
        } catch (Throwable v2Err) {
            r = httpGetJson(root + "/generate");
        }
        String addr = r.optString("address", "");
        String tok = r.optString("token", "");
        if (addr.isEmpty() || tok.isEmpty()) return false;
        int at = addr.indexOf('@');
        String login = at > 0 ? addr.substring(0, at) : addr;
        String domain = at > 0 ? addr.substring(at + 1) : "";
        applyCreatedMailbox(ColgramTempMailDiscovery.PROTOCOL_TEMPMAIL_LOL, root, addr, login,
                domain, "", tok, "", label);
        return true;
    }

    private boolean createTempMailIoMailbox(String baseUrl, String explicitDomain, String label) throws Exception {
        JSONObject r = null;
        if (explicitDomain != null && !explicitDomain.isEmpty()) {
            try {
                String desiredName = "cg" + (System.currentTimeMillis() % 100000L) + (int) (Math.random() * 900 + 100);
                JSONObject req = new JSONObject();
                req.put("name", desiredName);
                req.put("domain", explicitDomain);
                r = httpPostJson(baseUrl + "/email/new", req.toString());
            } catch (Throwable ignored) {
                r = null;
            }
        }
        if (r == null || r.optString("email", "").isEmpty()) {
            r = httpPostJson(baseUrl + "/email/new", "{\"min_name_length\":8,\"max_name_length\":12}");
        }
        String addr = r.optString("email", "");
        String tok = r.optString("token", "");
        if (addr.isEmpty()) return false;
        int at = addr.indexOf('@');
        String login = at > 0 ? addr.substring(0, at) : addr;
        String domain = at > 0 ? addr.substring(at + 1) : "";
        applyCreatedMailbox(ColgramTempMailDiscovery.PROTOCOL_MAIL_IO, baseUrl, addr, login,
                domain, "", tok, "", label);
        return true;
    }

    private boolean createMaildropMailbox(String baseUrl, String label) throws Exception {
        String api = (baseUrl == null || baseUrl.isEmpty()) ? "https://api.maildrop.cc/graphql" : baseUrl;
        JSONObject ping = httpPostJson(api, "{\"query\":\"query{ping(message:\\\"ok\\\")}\"}");
        JSONObject data = ping.optJSONObject("data");
        if (data == null || data.optString("ping", "").isEmpty()) {
            return false;
        }
        String login = "cg" + (System.currentTimeMillis() % 1000000L) + (int) (Math.random() * 900 + 100);
        String addr = login + "@maildrop.cc";
        applyCreatedMailbox(ColgramTempMailDiscovery.PROTOCOL_MAILDROP, api, addr, login,
                "maildrop.cc", "", "", "", label);
        return true;
    }

    private boolean createOneSecMailMailbox(String baseUrl, String preferredDomain, String label) throws Exception {
        ColgramHttp.Response resp = ColgramHttp.get(baseUrl + "/?action=getDomainList");
        String raw = (resp != null && resp.code >= 200 && resp.code < 300) ? resp.body : null;
        JSONArray domainArr = null;
        if (raw != null) {
            String trimmed = raw.trim();
            if (trimmed.startsWith("[")) {
                domainArr = new JSONArray(trimmed);
            } else if (trimmed.startsWith("{")) {
                JSONObject domainsRes = new JSONObject(trimmed);
                domainArr = domainsRes.optJSONArray("domains");
                if (domainArr == null) domainArr = domainsRes.optJSONArray("hydra:member");
            }
        }
        String domain = "";
        if (domainArr != null && domainArr.length() > 0) {
            if (preferredDomain != null) {
                for (int i = 0; i < domainArr.length(); i++) {
                    if (preferredDomain.equals(domainArr.optString(i))) {
                        domain = preferredDomain;
                        break;
                    }
                }
            }
            if (domain.isEmpty()) {
                domain = domainArr.optString(0, "");
            }
        }
        if (domain.isEmpty()) return false;
        String login = "cg" + (System.currentTimeMillis() % 1000000L) + (int) (Math.random() * 9000 + 1000);
        String addr = login + "@" + domain;
        applyCreatedMailbox(ColgramTempMailDiscovery.PROTOCOL_1SECMAIL, baseUrl, addr, login,
                domain, "", "", "", label);
        return true;
    }

    /**
     * Generates a real native @gmail.com / @googlemail.com mailbox via Emailnator's keyless JSON API
     * (POST /api/generate-email with ids: [3,2] for dot/plus Gmail or [8] for GoogleMail).
     */
    private boolean createEmailnatorMailbox(String baseUrl, String preferredDomain, String label) throws Exception {
        String base = (baseUrl == null || baseUrl.isEmpty()) ? EMAILNATOR_API : baseUrl;
        String payload;
        if ("googlemail.com".equalsIgnoreCase(preferredDomain)) {
            payload = "{\"ids\":[8]}";
        } else if ("gmail.com".equalsIgnoreCase(preferredDomain)) {
            payload = "{\"ids\":[3,2]}";
        } else {
            payload = "{\"ids\":[3,2,8]}";
        }
        JSONObject res = httpPostJson(base + "/api/generate-email", payload);
        String addr = res.optString("email", "").trim();
        if (addr.isEmpty()) {
            JSONArray arr = res.optJSONArray("email");
            if (arr != null && arr.length() > 0) addr = arr.optString(0, "").trim();
        }
        if (addr.isEmpty() || !addr.contains("@")) return false;
        int at = addr.indexOf('@');
        String login = addr.substring(0, at);
        String domain = addr.substring(at + 1);
        applyCreatedMailbox(ColgramTempMailDiscovery.PROTOCOL_EMAILNATOR, base, addr, login,
                domain, "", "", "", domain);
        return true;
    }

    private boolean createInboxesMailbox(String baseUrl, String preferredDomain, String label) throws Exception {
        String domain = "";
        if (preferredDomain != null && !preferredDomain.isEmpty()) {
            ProviderSpec owner = domainProviderSpecs.get(preferredDomain);
            if (owner != null && ColgramTempMailDiscovery.PROTOCOL_INBOXES.equals(normalizeProtocol(owner.protocol))) {
                domain = preferredDomain;
            }
        }
        if (domain.isEmpty()) {
            JSONObject res = httpGetJsonOnce(baseUrl + "/domain");
            JSONArray doms = res.optJSONArray("domains");
            if (doms != null && doms.length() > 0) {
                int pick = (int) (Math.random() * doms.length());
                JSONObject obj = doms.optJSONObject(pick);
                if (obj != null) {
                    domain = obj.optString("qdn", obj.optString("name", "")).trim();
                }
            }
        }
        if (domain.isEmpty()) return false;
        String login = "colgram" + (System.currentTimeMillis() % 100000L) + (int) (Math.random() * 900 + 100);
        String addr = login + "@" + domain;
        applyCreatedMailbox(ColgramTempMailDiscovery.PROTOCOL_INBOXES, baseUrl, addr, login,
                domain, "", "", "", label);
        return true;
    }

    private void applyCreatedMailbox(String newProvider, String newBase, String address,
                                     String login, String domain, String password,
                                     String token, String gToken, String providerLabel) {
        mainHandler.post(() -> {
            if (isDestroyed) return;
            SharedPreferences prefs = getPrefs();
            if (prefs != null) {
                SharedPreferences.Editor ed = prefs.edit()
                        .putString("provider", newProvider)
                        .putString("api_base", newBase)
                        .putString("address", address)
                        .putString("login", login)
                        .putString("domain", domain)
                        .putString("password", password)
                        .putString("token", token);
                if (!gToken.isEmpty()) {
                    ed.putString("guerrilla_token", gToken);
                } else {
                    ed.remove("guerrilla_token");
                }
                ed.apply();
            }
            provider = newProvider;
            apiBase = newBase;
            currentEmail = address;
            currentLogin = login;
            currentDomain = domain;
            mailTmToken = token;
            guerrillaToken = gToken;
            mailboxGenerating = false;
            messages.clear();
            cachedBodies.clear();
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
            showToast("Ящик создан (" + providerLabel + "): " + address);
            fetchMessages(false);
        });
    }

    private static boolean isGuerrillaDomain(String domain) {
        if (domain == null || domain.isEmpty()) return false;
        for (String d : ColgramTempMailDiscovery.GUERRILLA_DOMAINS) {
            if (d.equalsIgnoreCase(domain)) return true;
        }
        return false;
    }

    private static String tempMailLolRoot(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) return "https://api.tempmail.lol";
        return baseUrl.endsWith("/v2") ? baseUrl.substring(0, baseUrl.length() - 3) : baseUrl;
    }

    private void fetchMessages(boolean notifyUser) {
        if (!hasActiveSession()) return;
        executor.execute(() -> {
            try {
                List<TempMessage> list;
                try {
                    list = loadInbox();
                } catch (Throwable first) {
                    if (isEmptyInboxHttpStatus(first)) {
                        list = new ArrayList<>();
                    } else if (ColgramTempMailDiscovery.PROTOCOL_HYDRA.equals(provider)
                            || ColgramTempMailDiscovery.PROTOCOL_MAIL_IO.equals(provider)) {
                        String refreshed = refreshMailToken();
                        if (refreshed == null) throw first;
                        list = loadInbox();
                    } else {
                        throw first;
                    }
                }
                final List<TempMessage> finalList = list;
                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    boolean changed = !sameInbox(messages, finalList);
                    if (changed) {
                        synchronized (messages) {
                            messages.clear();
                            messages.addAll(finalList);
                        }
                        if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    }
                    if (notifyUser) {
                        showToast("Входящие обновлены (" + finalList.size() + " писем)");
                    }
                });
            } catch (Throwable t) {
                final String msg = humanizeError(t);
                Log.w(TAG, "fetchMessages failed: " + msg);
                if (notifyUser) {
                    mainHandler.post(() -> {
                        if (!isDestroyed) showToast("Ошибка почты: " + msg);
                    });
                }
            }
        });
    }

    private static boolean sameInbox(List<TempMessage> a, List<TempMessage> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            TempMessage ma = a.get(i);
            TempMessage mb = b.get(i);
            if (ma.id != mb.id || !String.valueOf(ma.tmId).equals(String.valueOf(mb.tmId))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEmptyInboxHttpStatus(Throwable t) {
        if (t instanceof HttpException) {
            int code = ((HttpException) t).code;
            return code == 404 || code == 204;
        }
        return false;
    }

    private List<TempMessage> loadInbox() throws Exception {
        if ("gmail_alias".equals(provider)) {
            return new ArrayList<>();
        }
        if (ColgramTempMailDiscovery.PROTOCOL_EMAILNATOR.equals(provider)) {
            return loadEmailnatorInbox();
        }
        if (ColgramTempMailDiscovery.PROTOCOL_INBOXES.equals(provider)) {
            return loadInboxesInbox();
        }
        if (ColgramTempMailDiscovery.PROTOCOL_GUERRILLA.equals(provider)) {
            return loadGuerrillaInbox();
        }
        if (ColgramTempMailDiscovery.PROTOCOL_TEMPMAIL_LOL.equals(provider)) {
            return loadTempMailLolInbox();
        }
        if (ColgramTempMailDiscovery.PROTOCOL_MAIL_IO.equals(provider)) {
            return loadTempMailIoInbox();
        }
        if (ColgramTempMailDiscovery.PROTOCOL_MAILDROP.equals(provider)) {
            return loadMaildropInbox();
        }
        if (ColgramTempMailDiscovery.PROTOCOL_1SECMAIL.equals(provider)) {
            return loadOneSecMailInbox();
        }
        if (apiBase == null || apiBase.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return loadHydraInbox();
    }

    private List<TempMessage> loadHydraInbox() throws Exception {
        JSONObject res = httpGetJsonWithAuth(apiBase + "/messages?page=1", mailTmToken);
        JSONArray arr = res.optJSONArray("hydra:member");
        List<TempMessage> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            String from = "";
            JSONObject fromObj = obj.optJSONObject("from");
            if (fromObj != null) from = fromObj.optString("address", "");
            String tmId = obj.optString("id", "");
            list.add(new TempMessage(
                    tmId.hashCode() & 0x7fffffff,
                    tmId,
                    from,
                    obj.optString("subject", ""),
                    obj.optString("createdAt", "")
            ));
        }
        return list;
    }

    private List<TempMessage> loadGuerrillaInbox() throws Exception {
        JSONObject res = guerrillaGet("f=check_email&seq=0");
        JSONArray arr = res.optJSONArray("list");
        List<TempMessage> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            String gid = obj.optString("mail_id", "");
            list.add(new TempMessage(
                    gid.hashCode() & 0x7fffffff,
                    gid + ":" + obj.optString("mail_secret", ""),
                    obj.optString("mail_from", ""),
                    obj.optString("mail_subject", ""),
                    obj.optString("mail_date", "")
            ));
        }
        return list;
    }

    private List<TempMessage> loadTempMailLolInbox() throws Exception {
        String root = tempMailLolRoot(apiBase);
        String encoded = URLEncoder.encode(mailTmToken, "UTF-8");
        JSONObject res;
        try {
            res = httpGetJson(root + "/v2/inbox?token=" + encoded);
        } catch (Throwable v2Err) {
            res = httpGetJson(root + "/auth/" + encoded);
        }
        JSONArray arr = res.optJSONArray("emails");
        if (arr == null) arr = res.optJSONArray("email");
        if (arr == null) arr = res.optJSONArray("hydra:member");

        List<TempMessage> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            String id = obj.optString("_id", obj.optString("id", String.valueOf(i)));
            String from = obj.optString("from", "");
            String subject = obj.optString("subject", "");
            String body = obj.optString("body", "");
            String html = obj.optString("html", "");
            String plain = !body.isEmpty() ? body : ColgramTempMailDiscovery.htmlToPlainText(html);
            cachedBodies.put(id, new CachedEmailDetail(from, subject, plain));
            list.add(new TempMessage(
                    id.hashCode() & 0x7fffffff,
                    id,
                    from,
                    subject,
                    obj.optString("createdAt", obj.optString("date", ""))
            ));
        }
        return list;
    }

    private static String encodeEmailForPath(String email) {
        if (email == null) return "";
        String clean = email.trim();
        int at = clean.indexOf('@');
        if (at <= 0 || at >= clean.length() - 1) return clean;
        try {
            String local = URLEncoder.encode(clean.substring(0, at), "UTF-8").replace("+", "%20");
            String dom = URLEncoder.encode(clean.substring(at + 1), "UTF-8").replace("+", "%20");
            return local + "@" + dom;
        } catch (Throwable ignored) {
            return clean;
        }
    }

    private List<TempMessage> loadTempMailIoInbox() throws Exception {
        String pathEmail = encodeEmailForPath(currentEmail);
        JSONObject res;
        try {
            res = httpGetJson(apiBase + "/email/" + pathEmail + "/messages");
        } catch (HttpException he) {
            if (he.code == 400 || he.code == 404) {
                // Re-register expired temp-mail.io address silently on the free website backend
                try {
                    int at = currentEmail.indexOf('@');
                    if (at > 0 && at < currentEmail.length() - 1) {
                        JSONObject recreate = new JSONObject();
                        recreate.put("name", currentEmail.substring(0, at));
                        recreate.put("domain", currentEmail.substring(at + 1));
                        httpPostJson(apiBase + "/email/new", recreate.toString());
                    }
                    res = httpGetJson(apiBase + "/email/" + pathEmail + "/messages");
                } catch (Throwable ignored) {
                    return new ArrayList<>();
                }
            } else {
                throw he;
            }
        }
        JSONArray arr = res.optJSONArray("hydra:member");
        if (arr == null) arr = res.optJSONArray("messages");
        List<TempMessage> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            String id = obj.optString("id", String.valueOf(i));
            String from = obj.optString("from", "");
            String subject = obj.optString("subject", "");
            String body = obj.optString("body_text", "");
            if (body.isEmpty()) {
                body = ColgramTempMailDiscovery.htmlToPlainText(obj.optString("body_html", ""));
            }
            cachedBodies.put(id, new CachedEmailDetail(from, subject, body));
            list.add(new TempMessage(
                    id.hashCode() & 0x7fffffff,
                    id,
                    from,
                    subject,
                    obj.optString("created_at", "")
            ));
        }
        return list;
    }

    private List<TempMessage> loadMaildropInbox() throws Exception {
        String mb = escapeGraphQl(currentLogin.isEmpty() ? currentEmail.split("@")[0] : currentLogin);
        String payload = "{\"query\":\"query{inbox(mailbox:\\\"" + mb + "\\\"){id headerfrom subject date}}\"}";
        JSONObject res = httpPostJson(apiBase, payload);
        JSONObject data = res.optJSONObject("data");
        JSONArray arr = data != null ? data.optJSONArray("inbox") : null;
        List<TempMessage> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            String id = obj.optString("id", String.valueOf(i));
            list.add(new TempMessage(
                    id.hashCode() & 0x7fffffff,
                    id,
                    obj.optString("headerfrom", ""),
                    obj.optString("subject", ""),
                    obj.optString("date", "")
            ));
        }
        return list;
    }

    private static String escapeGraphQl(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<TempMessage> loadEmailnatorInbox() throws Exception {
        String base = (apiBase == null || apiBase.isEmpty()) ? EMAILNATOR_API : apiBase;
        JSONObject req = new JSONObject();
        req.put("email", currentEmail);
        req.put("limit", 20);
        JSONObject res = httpPostJson(base + "/api/message-list", req.toString());
        JSONArray arr = res.optJSONArray("messages");
        if (arr == null) arr = res.optJSONArray("messageData");
        List<TempMessage> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            if (obj.optBoolean("locked", false)) continue;
            String mid = obj.optString("id", obj.optString("messageID", ""));
            if (mid.isEmpty() || "ADSVPN".equalsIgnoreCase(mid)) continue;
            String from = obj.optString("from", "");
            String subject = obj.optString("subject", "");
            String time = obj.optString("time_ago", obj.optString("time", ""));
            list.add(new TempMessage(mid.hashCode() & 0x7fffffff, mid, from, subject, time));
        }
        return list;
    }

    private List<TempMessage> loadInboxesInbox() throws Exception {
        String url = apiBase + "/inbox/" + encodeEmailForPath(currentEmail);
        JSONObject res = httpGetJson(url);
        JSONArray arr = res.optJSONArray("msgs");
        List<TempMessage> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            String uid = obj.optString("uid", String.valueOf(i));
            String from = obj.optString("f", obj.optString("fe", ""));
            String subject = obj.optString("s", "");
            String date = obj.optString("r", "");
            list.add(new TempMessage(uid.hashCode() & 0x7fffffff, uid, from, subject, date));
        }
        return list;
    }

    private List<TempMessage> loadOneSecMailInbox() throws Exception {
        String url = apiBase + "/?action=getMessages&login="
                + URLEncoder.encode(currentLogin, "UTF-8")
                + "&domain=" + URLEncoder.encode(currentDomain, "UTF-8");
        JSONObject res = httpGetJson(url);
        JSONArray arr = res.optJSONArray("hydra:member");
        List<TempMessage> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            String id = obj.optString("id", String.valueOf(i));
            list.add(new TempMessage(
                    id.hashCode() & 0x7fffffff,
                    id,
                    obj.optString("from", ""),
                    obj.optString("subject", ""),
                    obj.optString("date", "")
            ));
        }
        return list;
    }

    private JSONObject guerrillaGet(String params) throws Exception {
        String base = (apiBase == null || apiBase.isEmpty()) ? GUERRILLA_API : apiBase;
        return httpGetJson(base + "?" + params + "&sid_token="
                + URLEncoder.encode(guerrillaToken, "UTF-8") + "&lang=en");
    }

    private void readMessageContent(int messageId) {
        if (getParentActivity() == null || !hasActiveSession()) return;
        showToast("Загрузка письма...");
        executor.execute(() -> {
            try {
                TempMessage target = null;
                synchronized (messages) {
                    for (TempMessage m : messages) {
                        if (m.id == messageId) {
                            target = m;
                            break;
                        }
                    }
                }
                if (target == null) return;

                String from = target.from;
                String subject = target.subject;
                String textBody = "";

                CachedEmailDetail cached = cachedBodies.get(target.tmId);
                if (cached != null) {
                    from = cached.from;
                    subject = cached.subject;
                    textBody = cached.plainBody;
                } else if (ColgramTempMailDiscovery.PROTOCOL_EMAILNATOR.equals(provider)) {
                    String base = (apiBase == null || apiBase.isEmpty()) ? EMAILNATOR_API : apiBase;
                    JSONObject obj = httpGetJson(base + "/api/message/" + URLEncoder.encode(target.tmId, "UTF-8"));
                    from = obj.optString("from", target.from);
                    subject = obj.optString("subject", target.subject);
                    String content = obj.optString("content", obj.optString("body", ""));
                    textBody = ColgramTempMailDiscovery.htmlToPlainText(content);
                    cachedBodies.put(target.tmId, new CachedEmailDetail(from, subject, textBody));
                } else if (ColgramTempMailDiscovery.PROTOCOL_INBOXES.equals(provider)) {
                    JSONObject obj = httpGetJson(apiBase + "/message/" + URLEncoder.encode(target.tmId, "UTF-8"));
                    from = obj.optString("f", obj.optString("fe", target.from));
                    subject = obj.optString("s", target.subject);
                    textBody = obj.optString("text", "");
                    if (textBody.isEmpty()) {
                        textBody = ColgramTempMailDiscovery.htmlToPlainText(obj.optString("html", ""));
                    }
                } else if (ColgramTempMailDiscovery.PROTOCOL_GUERRILLA.equals(provider)) {
                    int sep = target.tmId.indexOf(':');
                    String gid = sep > 0 ? target.tmId.substring(0, sep) : target.tmId;
                    JSONObject obj = guerrillaGet("f=fetch_email&email_id=" + URLEncoder.encode(gid, "UTF-8"));
                    from = obj.optString("mail_from", target.from);
                    subject = obj.optString("mail_subject", target.subject);
                    textBody = ColgramTempMailDiscovery.htmlToPlainText(obj.optString("mail_body", ""));
                } else if (ColgramTempMailDiscovery.PROTOCOL_1SECMAIL.equals(provider)) {
                    JSONObject obj = httpGetJson(apiBase + "/?action=readMessage&login="
                            + URLEncoder.encode(currentLogin, "UTF-8")
                            + "&domain=" + URLEncoder.encode(currentDomain, "UTF-8")
                            + "&id=" + URLEncoder.encode(target.tmId, "UTF-8"));
                    from = obj.optString("from", target.from);
                    subject = obj.optString("subject", target.subject);
                    textBody = obj.optString("textBody", "");
                    if (textBody.isEmpty()) {
                        textBody = ColgramTempMailDiscovery.htmlToPlainText(obj.optString("htmlBody", obj.optString("body", "")));
                    }
                } else if (ColgramTempMailDiscovery.PROTOCOL_MAIL_IO.equals(provider)) {
                    JSONObject obj = httpGetJson(apiBase + "/message/" + URLEncoder.encode(target.tmId, "UTF-8"));
                    from = obj.optString("from", target.from);
                    subject = obj.optString("subject", target.subject);
                    textBody = obj.optString("body_text", "");
                    if (textBody.isEmpty()) {
                        textBody = ColgramTempMailDiscovery.htmlToPlainText(obj.optString("body_html", ""));
                    }
                } else if (ColgramTempMailDiscovery.PROTOCOL_MAILDROP.equals(provider)) {
                    String mb = escapeGraphQl(currentLogin.isEmpty() ? currentEmail.split("@")[0] : currentLogin);
                    String mid = escapeGraphQl(target.tmId);
                    JSONObject res = httpPostJson(apiBase,
                            "{\"query\":\"query{message(mailbox:\\\"" + mb + "\\\",id:\\\"" + mid + "\\\"){id headerfrom subject date data html}}\"}");
                    JSONObject dataObj = res.optJSONObject("data");
                    JSONObject msgObj = dataObj != null ? dataObj.optJSONObject("message") : null;
                    if (msgObj != null) {
                        from = msgObj.optString("headerfrom", target.from);
                        subject = msgObj.optString("subject", target.subject);
                        textBody = msgObj.optString("data", "");
                        if (textBody.isEmpty()) {
                            textBody = ColgramTempMailDiscovery.htmlToPlainText(msgObj.optString("html", ""));
                        }
                    }
                } else if (ColgramTempMailDiscovery.PROTOCOL_HYDRA.equals(provider)) {
                    JSONObject obj = httpGetJsonWithAuth(apiBase + "/messages/" + target.tmId, mailTmToken);
                    JSONObject fromObj = obj.optJSONObject("from");
                    if (fromObj != null) from = fromObj.optString("address", target.from);
                    subject = obj.optString("subject", target.subject);
                    textBody = obj.optString("text", "");
                    if (textBody.isEmpty()) {
                        JSONArray htmlArr = obj.optJSONArray("html");
                        if (htmlArr != null && htmlArr.length() > 0) {
                            StringBuilder rawHtml = new StringBuilder();
                            for (int i = 0; i < htmlArr.length(); i++) {
                                rawHtml.append(htmlArr.optString(i, "")).append('\n');
                            }
                            textBody = ColgramTempMailDiscovery.htmlToPlainText(rawHtml.toString());
                        } else {
                            String htmlStr = obj.optString("html", "");
                            if (!htmlStr.isEmpty()) {
                                textBody = ColgramTempMailDiscovery.htmlToPlainText(htmlStr);
                            }
                        }
                    }
                    if (textBody.isEmpty()) textBody = obj.optString("intro", "");
                }

                String otp = ColgramTempMailDiscovery.extractOtp(subject, textBody);
                final String fFrom = from;
                final String fSubject = subject;
                final String fBody = textBody.isEmpty() ? "(Пустое тело письма)" : textBody;
                final String fOtp = otp;
                mainHandler.post(() -> {
                    if (!isDestroyed) showMessageDialog(fFrom, fSubject, fBody, fOtp);
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (!isDestroyed) showToast("Ошибка чтения: " + humanizeError(t));
                });
            }
        });
    }

    private void showMessageDialog(String from, String subject, String body, String otp) {
        Activity ctx = getParentActivity();
        if (isDestroyed || isFinishing() || ctx == null || ctx.isFinishing() || ctx.isDestroyed()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx, resourceProvider);
        builder.setTitle(subject.isEmpty() ? "Письмо" : subject);

        String message = "От: " + from + "\n\n" + body;
        if (!otp.isEmpty()) {
            message = "Найден код подтверждения: " + otp + "\n\n" + message;
            final String finalOtp = otp;
            builder.setNeutralButton("Скопировать код (" + otp + ")", (d, w) -> {
                copyToClipboard(finalOtp);
                showToast("Код " + finalOtp + " скопирован!");
            });
        }
        builder.setMessage(message);
        builder.setPositiveButton("Скопировать текст", (d, w) -> {
            copyToClipboard(body);
            showToast("Текст письма скопирован");
        });
        builder.setNegativeButton("Закрыть", null);
        showDialog(builder.create());
    }

    private void copyToClipboard(String text) {
        try {
            Context ctx = getParentActivity();
            if (ctx == null) ctx = ApplicationLoader.applicationContext;
            if (ctx == null) return;
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Colgram TempMail", text));
            }
        } catch (Throwable ignored) {}
    }

    private boolean restoreSavedMailbox() {
        try {
            if (!restoreSavedMailboxLocal()) return false;
            if (ColgramTempMailDiscovery.PROTOCOL_HYDRA.equals(provider)) {
                String fresh = refreshMailToken();
                if (fresh == null && (mailTmToken == null || mailTmToken.isEmpty())) return false;
            }
            fetchMessages(false);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "restoreSavedMailbox failed: " + t.getMessage());
            return false;
        }
    }

    private String refreshMailToken() {
        try {
            if (!ColgramTempMailDiscovery.PROTOCOL_HYDRA.equals(provider)
                    && !ColgramTempMailDiscovery.PROTOCOL_MAIL_IO.equals(provider)) {
                return mailTmToken;
            }
            if (currentEmail == null || currentEmail.isEmpty()) return null;
            SharedPreferences prefs = getPrefs();
            String password = prefs != null ? prefs.getString("password", "") : "";
            if (password.isEmpty()) return null;

            JSONObject authReq = new JSONObject();
            if (ColgramTempMailDiscovery.PROTOCOL_MAIL_IO.equals(provider)) {
                authReq.put("email", currentEmail);
            } else {
                authReq.put("address", currentEmail);
            }
            authReq.put("password", password);
            String tokenEndpoint = ColgramTempMailDiscovery.PROTOCOL_MAIL_IO.equals(provider)
                    ? (apiBase + "/accounts/token")
                    : (apiBase + "/token");
            JSONObject authRes = httpPostJson(tokenEndpoint, authReq.toString());
            String newToken = authRes.optString("token", "");
            if (newToken.isEmpty()) return null;

            mailTmToken = newToken;
            if (prefs != null) prefs.edit().putString("token", newToken).apply();
            return newToken;
        } catch (Throwable t) {
            Log.w(TAG, "token refresh failed: " + t.getMessage());
            return null;
        }
    }

    private <T> T withRetry(String label, java.util.concurrent.Callable<T> body) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= NET_ATTEMPTS; attempt++) {
            try {
                return body.call();
            } catch (Exception e) {
                last = e;
                if (isPermanent(e)) throw e;
                if (attempt < NET_ATTEMPTS) {
                    Thread.sleep(NET_RETRY_BASE_MS * attempt);
                }
            }
        }
        throw last != null ? last : new IOException(label + " failed");
    }

    private boolean isPermanent(Exception e) {
        if (e instanceof HttpException) {
            int c = ((HttpException) e).code;
            return c >= 400 && c < 500 && c != 408 && c != 429;
        }
        return false;
    }

    private JSONObject httpGetJson(String urlStr) throws Exception {
        return httpGetJsonWithAuth(urlStr, null);
    }

    private JSONObject httpGetJsonOnce(String urlStr) throws Exception {
        ColgramBotSync.applyIpv4Policy();
        return toJson(ColgramHttp.getFast(urlStr), urlStr);
    }

    private JSONObject httpPostJsonOnce(String urlStr, String jsonBody) throws Exception {
        ColgramBotSync.applyIpv4Policy();
        return toJson(ColgramHttp.post(urlStr, jsonBody, null), urlStr);
    }

    private JSONObject httpGetJsonWithAuth(String urlStr, String bearer) throws Exception {
        ColgramBotSync.applyIpv4Policy();
        return toJson(ColgramHttp.get(urlStr, bearer), urlStr);
    }

    private JSONObject httpPostJson(String urlStr, String jsonBody) throws Exception {
        ColgramBotSync.applyIpv4Policy();
        return toJson(ColgramHttp.post(urlStr, jsonBody, null), urlStr);
    }

    private JSONObject toJson(ColgramHttp.Response r, String urlStr) throws Exception {
        String body = r.body == null ? "" : r.body.trim();
        if (body.isEmpty()) {
            throw new HttpException(r.code, "HTTP " + r.code + " без тела ответа (" + hostOf(urlStr) + ")");
        }
        if (r.code < 200 || r.code >= 300) {
            String detail = "";
            try {
                detail = describeApiError(r.code, parseAsObject(body));
            } catch (Throwable ignored) {}
            throw new HttpException(r.code, detail.isEmpty()
                    ? ("HTTP " + r.code + ": " + (body.length() > 120 ? body.substring(0, 120) : body))
                    : detail);
        }
        try {
            return parseAsObject(body);
        } catch (org.json.JSONException e) {
            throw new HttpException(r.code, "сервис вернул не JSON (" + r.code + ")");
        }
    }

    private JSONObject parseAsObject(String body) throws org.json.JSONException {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("[")) {
            JSONObject wrapper = new JSONObject();
            wrapper.put("hydra:member", new JSONArray(trimmed));
            return wrapper;
        }
        if (trimmed.isEmpty()) {
            return new JSONObject();
        }
        return new JSONObject(trimmed);
    }

    private String describeApiError(int code, JSONObject json) {
        StringBuilder sb = new StringBuilder("HTTP ").append(code);
        String desc = json.optString("detail", "");
        if (desc.isEmpty()) desc = json.optString("hydra:description", "");
        if (desc.isEmpty()) desc = json.optString("message", "");
        if (!desc.isEmpty()) sb.append(": ").append(desc);

        JSONArray violations = json.optJSONArray("violations");
        if (violations != null) {
            for (int i = 0; i < violations.length(); i++) {
                JSONObject v = violations.optJSONObject(i);
                if (v == null) continue;
                String prop = v.optString("propertyPath", "");
                String msg = v.optString("message", "");
                if (!msg.isEmpty()) sb.append(" [").append(prop).append(": ").append(msg).append("]");
            }
        }
        return sb.toString();
    }

    private String humanizeError(Throwable t) {
        if (t == null) return "неизвестная ошибка";
        String raw = t.getMessage() == null ? t.toString() : t.getMessage();
        if (raw.contains("End of input at character 0")) return "сервис вернул пустой ответ";
        if (raw.contains("Unable to resolve host") || raw.contains("UnknownHostException")) return "сервер недоступен";
        if (raw.contains("timed out") || raw.contains("timeout")) return "таймаут ожидания ответа";
        if (raw.contains("HTTP 429")) return "слишком много запросов, подождите";
        if (raw.contains("HTTP 5")) return "сервис временно недоступен";
        return raw;
    }

    private static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h != null ? h : url;
        } catch (Throwable t) {
            return url;
        }
    }

    private void showToast(String text) {
        if (isDestroyed) return;
        Context ctx = getParentActivity();
        if (ctx != null) {
            Toast.makeText(ctx.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
        }
    }

    private static final class HttpException extends IOException {
        final int code;

        HttpException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Временная почта");
        ColgramMenuChrome.styleActionBar(actionBar);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_REFRESH) {
                    manualRefresh();
                } else if (id == MENU_DOMAIN) {
                    showDomainPicker();
                } else if (id == MENU_NEW) {
                    pendingProvider = null;
                    pendingDomain = null;
                    generateNewMailbox();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_REFRESH, R.drawable.msg_retry).setContentDescription("Обновить письма");
        menu.addItem(MENU_DOMAIN, R.drawable.msg_link).setContentDescription("Выбрать домен");
        menu.addItem(MENU_NEW, R.drawable.msg_add).setContentDescription("Новый ящик");

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
            if (listAdapter == null) return;
            listAdapter.handleClick(position, view);
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (listAdapter != null && position == listAdapter.domainRowPos()) {
                showAddCustomProviderDialog();
                return true;
            }
            return false;
        });

        return fragmentView;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        private int heroPos() { return 0; }
        private int actionsHeaderPos() { return 1; }
        private int copyRowPos() { return 2; }
        private int gmailRowPos() { return 3; }
        private int googleMailRowPos() { return 4; }
        private int newRowPos() { return 5; }
        private int domainRowPos() { return 6; }
        private int refreshRowPos() { return 7; }
        private int autoRowPos() { return 8; }
        private int shadowAfterActionsPos() { return 9; }
        private int inboxHeaderPos() { return 10; }
        private int inboxStartPos() { return 11; }
        private int inboxRowsCount() { return Math.max(1, messages.size()); }
        private int bottomShadowPos() { return inboxStartPos() + inboxRowsCount(); }

        void handleClick(int position, View view) {
            if (position == heroPos() || position == copyRowPos()) {
                if (!currentEmail.isEmpty()) {
                    copyToClipboard(currentEmail);
                    showToast("Скопировано: " + currentEmail);
                } else {
                    generateNewMailbox();
                }
            } else if (position == gmailRowPos()) {
                createNativeGmailMailbox("gmail.com");
            } else if (position == googleMailRowPos()) {
                createNativeGmailMailbox("googlemail.com");
            } else if (position == newRowPos()) {
                pendingProvider = null;
                pendingDomain = null;
                showToast("Создаём новый адрес…");
                generateNewMailbox();
            } else if (position == domainRowPos()) {
                showDomainPicker();
            } else if (position == refreshRowPos()) {
                manualRefresh();
            } else if (position == autoRowPos()) {
                toggleAutoRefresh();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(autoRefreshEnabled);
                }
            } else if (!messages.isEmpty() && position >= inboxStartPos() && position < inboxStartPos() + messages.size()) {
                readMessageContent(messages.get(position - inboxStartPos()).id);
            }
        }

        @Override
        public int getItemCount() {
            return bottomShadowPos() + 1;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int pos = holder.getAdapterPosition();
            if (pos == heroPos() || pos == copyRowPos() || pos == gmailRowPos() || pos == googleMailRowPos()
                    || pos == newRowPos() || pos == domainRowPos() || pos == refreshRowPos() || pos == autoRowPos()) {
                return true;
            }
            return !messages.isEmpty() && pos >= inboxStartPos() && pos < inboxStartPos() + messages.size();
        }

        @Override
        public int getItemViewType(int position) {
            if (position == heroPos()) {
                return 10;
            }
            if (position == actionsHeaderPos() || position == inboxHeaderPos()) {
                return 0;
            }
            if (position == autoRowPos()) {
                return 1;
            }
            if (position == shadowAfterActionsPos() || position == bottomShadowPos()) {
                return 3;
            }
            return 2;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 10:
                    view = new ColgramMenuChrome.HeroBannerCell(mContext);
                    break;
                case 0:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 1:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 2:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 3:
                default:
                    view = new ShadowSectionCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 10: {
                    ColgramMenuChrome.HeroBannerCell hero = (ColgramMenuChrome.HeroBannerCell) holder.itemView;
                    String title = currentEmail.isEmpty()
                            ? (mailboxGenerating || discoveryRunning ? "Создание ящика…" : "Временная почта")
                            : currentEmail;
                    String sub = currentEmail.isEmpty()
                            ? "Нажмите, чтобы создать адрес"
                            : "Нажмите, чтобы скопировать адрес";
                    String pill = mailboxGenerating ? "Создание…" : (!currentEmail.isEmpty() ? "Активен" : "");
                    int accent = !currentEmail.isEmpty() ? ColgramMenuChrome.COLOR_CYAN : ColgramMenuChrome.COLOR_VIOLET;
                    String chip1 = availableDomains.isEmpty() ? "Gmail и домены" : (availableDomains.size() + " доменов");
                    String chip2 = messages.size() + " писем";
                    String chip3 = autoRefreshEnabled ? "Автопроверка 5с" : "Ручная проверка";
                    hero.bind(R.drawable.msg_send, accent, title, sub, pill, chip1, chip2, chip3);
                    break;
                }
                case 0: {
                    HeaderCell h = (HeaderCell) holder.itemView;
                    if (position == actionsHeaderPos()) {
                        h.setText("Почтовый ящик");
                        ColgramMenuChrome.styleHeader(h, 0xFF0E7490, 0xFF22D3EE);
                    } else {
                        h.setText("Входящие (" + messages.size() + ")");
                        ColgramMenuChrome.styleHeader(h, 0xFF4338CA, 0xFF818CF8);
                    }
                    break;
                }
                case 1: {
                    TextCheckCell tc = (TextCheckCell) holder.itemView;
                    tc.setTextAndCheck("Автообновление (5 сек)", autoRefreshEnabled, false);
                    ColgramMenuChrome.applyCheckIcon(tc, ColgramMenuChrome.COLOR_TEAL, R.drawable.msg_online);
                    break;
                }
                case 2: {
                    TextSettingsCell s = (TextSettingsCell) holder.itemView;
                    if (position == copyRowPos()) {
                        s.setTextAndValue("Скопировать адрес",
                                currentEmail.isEmpty() ? "Создаётся…" : currentEmail, true);
                        ColgramMenuChrome.applySettingIcon(s, R.drawable.msg_copy, ColgramMenuChrome.COLOR_BLUE);
                    } else if (position == gmailRowPos()) {
                        s.setTextAndValue("Новый @gmail.com", "Нативно", true);
                        ColgramMenuChrome.applySettingIcon(s, R.drawable.msg_send, ColgramMenuChrome.COLOR_CRIMSON);
                    } else if (position == googleMailRowPos()) {
                        s.setTextAndValue("Новый @googlemail.com", "Нативно", true);
                        ColgramMenuChrome.applySettingIcon(s, R.drawable.msg_send, ColgramMenuChrome.COLOR_ROSE);
                    } else if (position == newRowPos()) {
                        s.setTextAndValue("Случайный домен", "Новый ящик", true);
                        ColgramMenuChrome.applySettingIcon(s, R.drawable.msg_retry, ColgramMenuChrome.COLOR_EMERALD);
                    } else if (position == domainRowPos()) {
                        String val = pendingDomain != null
                                ? "@" + pendingDomain
                                : (availableDomains.isEmpty()
                                        ? (discoveryRunning ? "Загрузка…" : "Авто")
                                        : availableDomains.size() + " доменов");
                        s.setTextAndValue("Выбрать домен", val, true);
                        ColgramMenuChrome.applySettingIcon(s, R.drawable.msg_link, ColgramMenuChrome.COLOR_VIOLET);
                    } else if (position == refreshRowPos()) {
                        s.setTextAndValue("Проверить входящие", messages.size() + " писем", true);
                        ColgramMenuChrome.applySettingIcon(s, R.drawable.msg_search, ColgramMenuChrome.COLOR_CYAN);
                    } else if (position >= inboxStartPos() && position < bottomShadowPos()) {
                        if (messages.isEmpty()) {
                            s.setText(autoRefreshEnabled
                                    ? "Ожидание писем (проверка каждые 5 сек)…"
                                    : "Писем пока нет", false);
                            ColgramMenuChrome.applySettingIcon(s, R.drawable.msg_info, ColgramMenuChrome.COLOR_SLATE);
                        } else {
                            int mIdx = position - inboxStartPos();
                            TempMessage m = messages.get(mIdx);
                            CachedEmailDetail cached = cachedBodies.get(m.tmId);
                            String bodyPreview = cached != null ? cached.plainBody : "";
                            String otp = ColgramTempMailDiscovery.extractOtp(m.subject, bodyPreview);
                            String rightVal = !otp.isEmpty() ? ("Код: " + otp) : m.from;
                            if (rightVal != null && rightVal.length() > 26) {
                                rightVal = rightVal.substring(0, 25) + "…";
                            }
                            s.setTextAndValue(m.subject.isEmpty() ? "(Без темы)" : m.subject,
                                    rightVal == null ? "" : rightVal, mIdx < messages.size() - 1);
                            ColgramMenuChrome.applySettingIcon(s,
                                    !otp.isEmpty() ? R.drawable.msg_secret : R.drawable.msg_send,
                                    !otp.isEmpty() ? ColgramMenuChrome.COLOR_AMBER : ColgramMenuChrome.COLOR_INDIGO);
                        }
                    }
                    break;
                }
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR,
                new Class[]{HeaderCell.class, TextCheckCell.class, TextSettingsCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        list.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND,
                null, null, null, null, Theme.key_windowBackgroundGray));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND,
                null, null, null, null, Theme.key_actionBarDefault));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR,
                null, null, null, null, Theme.key_actionBarDefaultIcon));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR,
                null, null, null, null, Theme.key_actionBarDefaultTitle));
        list.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR,
                null, null, null, null, Theme.key_actionBarDefaultSelector));
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
