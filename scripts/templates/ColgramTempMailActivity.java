package org.telegram.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColgramTempMailActivity extends BaseFragment {

    private static final String TAG = "ColgramTempMail";

    /** How many times a transient network step is retried before reporting failure. */
    private static final int NET_ATTEMPTS = 3;
    /** Base backoff between retries, multiplied by the attempt number. */
    private static final long NET_RETRY_BASE_MS = 1000L;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private String currentEmail = "";
    private String currentLogin = "";
    private String currentDomain = "mail.tm";
    private String mailTmToken = "";

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

    private final List<TempMessage> messages = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isDestroyed = false;

    // --- Action bar ids -------------------------------------------------------------
    private static final int MENU_REFRESH = 1;
    private static final int MENU_DOMAIN = 2;
    private static final int MENU_NEW = 3;
    private static final int MENU_TOGGLE = 4;

    /**
     * Domains the service currently offers, for the picker.
     *
     * Fetched ON DEMAND only. It is never loaded when the screen opens - that was part of
     * what made every visit cost a round trip.
     */
    private final ArrayList<String> availableDomains = new ArrayList<>();

    /**
     * Domain the user chose for the NEXT mailbox. null means "let the service decide".
     *
     * Note on what is actually possible here: a disposable-mail API can only hand out
     * addresses on domains IT operates (mail.tm serves things like uberip.com). No API can
     * mint a @gmail.com address - Google does not permit it. So the picker offers the real
     * list from the server, which is the honest version of "выбрать окончание".
     */
    private String pendingDomain = null;

    /**
     * Polling is OPT-IN and defaults to off.
     *
     * It used to run unconditionally every 5s from the moment the fragment was created,
     * which is exactly the "обновляются постоянно" behaviour. A user who wants live updates
     * can turn it on from the action bar; the setting persists.
     */
    private boolean autoRefreshEnabled = false;
    private boolean autoRefreshRunning = false;

    /**
     * True only while generateNewMailbox() is actually on the wire.
     *
     * The address label used to read "Генерация адреса..." whenever the field was empty, so a
     * user who had simply never created a mailbox was told the app was working on it — and it
     * never finished, because nothing had started. Distinguishing "busy" from "not created yet"
     * is the whole point.
     */
    private volatile boolean mailboxGenerating = false;

    private static final String[][] WEB_TEMP_SERVICES = {
        {"smailpro.com", "https://smailpro.com/", "Временная почта Gmail / Outlook"},
        {"22.do", "https://22.do/en/", "Быстрый генератор disposable почты"},
        {"tempamail.com", "https://tempamail.com/", "Бесплатный прием писем"},
        {"temp-mail.io", "https://temp-mail.io/ru/", "Временные ящики с вложениями"},
        {"tmailor.com", "https://tmailor.com/ru/", "Одноразовая почта без спама"},
        {"tempmail.net", "https://tempmail.net/", "Анонимная почта на 10 минут"}
    };

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        // 🔴 LOCAL ONLY. No network, no automatic mailbox creation, no polling.
        //
        // This method used to call restoreSavedMailbox() - which refreshed the token AND
        // fetched the inbox - and, when that returned false, fell through to
        // generateNewMailbox(). Two consequences, both reported:
        //   * every single visit to this screen cost a round trip ("обновляются постоянно
        //     при переходе в меню"), and
        //   * a transient network failure silently REPLACED the user's address with a new
        //     one, so the inbox they were watching disappeared.
        //
        // Now the saved mailbox is read from preferences, whatever was cached is shown, and
        // the user drives: Refresh, or pick a domain and create a new mailbox. Polling is a
        // separate opt-in toggle.
        restoreSavedMailboxLocal();

        try {
            Context ctx = getParentActivity();
            if (ctx != null) {
                autoRefreshEnabled = ctx
                        .getSharedPreferences("colgram_tempmail", Context.MODE_PRIVATE)
                        .getBoolean("auto_refresh", false);
            }
        } catch (Throwable ignored) {
        }
        if (autoRefreshEnabled) {
            startAutoRefresh();
        }
        return true;
    }

    /**
     * Read the saved mailbox from preferences. Touches no network.
     *
     * Split out of restoreSavedMailbox() so that opening the screen is free: the token
     * refresh and the fetch only happen when the user asks for them.
     */
    private boolean restoreSavedMailboxLocal() {
        try {
            Context ctx = getParentActivity();
            if (ctx == null) return false;
            android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences("colgram_tempmail", Context.MODE_PRIVATE);
            String address = prefs.getString("address", "");
            String password = prefs.getString("password", "");
            if (address.isEmpty() || password.isEmpty()) return false;

            currentEmail = address;
            currentLogin = prefs.getString("login", "");
            currentDomain = prefs.getString("domain", "");
            mailTmToken = prefs.getString("token", "");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "restoreSavedMailboxLocal failed: " + t.getMessage());
            return false;
        }
    }

    /** Explicit refresh, driven by the user. */
    private void manualRefresh() {
        final Context ctx = getParentActivity();
        if (ctx == null) return;
        if (currentEmail == null || currentEmail.isEmpty()) {
            Toast.makeText(ctx, "Сначала создайте ящик", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(ctx, "Обновление...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            final String fresh = refreshMailToken();
            if (fresh != null) {
                fetchMessages(true);
            } else {
                mainHandler.post(() -> {
                    Context c = getParentActivity();
                    if (c != null) {
                        Toast.makeText(c, "Не удалось обновить — токен недействителен",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /** Load the service's domain list, then let the user pick one. */
    private void showDomainPicker() {
        final Context ctx = getParentActivity();
        if (ctx == null) return;
        if (!availableDomains.isEmpty()) {
            presentDomainDialog();
            return;
        }
        Toast.makeText(ctx, "Загрузка списка доменов...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            final ArrayList<String> got = fetchDomainList();
            mainHandler.post(() -> {
                Context c = getParentActivity();
                if (c == null) return;
                availableDomains.clear();
                availableDomains.addAll(got);
                if (availableDomains.isEmpty()) {
                    Toast.makeText(c, "Сервис не вернул список доменов", Toast.LENGTH_LONG).show();
                } else {
                    presentDomainDialog();
                }
            });
        });
    }

    private void presentDomainDialog() {
        final Context ctx = getParentActivity();
        if (ctx == null) return;
        final ArrayList<String> opts = new ArrayList<>();
        opts.add("По умолчанию (выберет сервис)");
        opts.addAll(availableDomains);
        opts.add("Обновить список доменов");
        final int reloadIndex = opts.size() - 1;
        AlertDialog.Builder domainDialog = new AlertDialog.Builder(ctx)
                .setTitle("Домен для нового ящика");
        if (availableDomains.isEmpty()) {
            // An empty list with no explanation is what made this read as a broken feature.
            // Say which host failed and how, and let him retry from inside the dialog.
            domainDialog.setMessage("Список доменов не пришёл:\n"
                    + (lastDomainError == null || lastDomainError.isEmpty()
                            ? "сервис не отвечал" : lastDomainError));
        }
        domainDialog
                .setItems(opts.toArray(new String[0]), (d, which) -> {
                    if (which == reloadIndex) {
                        // The list used to be cached for the lifetime of the process with no
                        // way to refresh it, so a domain the provider added or re-activated
                        // stayed invisible until an app restart.
                        availableDomains.clear();
                        showDomainPicker();
                        return;
                    }
                    pendingDomain = (which == 0) ? null : opts.get(which);
                    // Creating the mailbox here is the point of the picker. Previously the
                    // choice only landed in pendingDomain and the user still had to press
                    // "Новый ящик" afterwards, which read as the picker doing nothing.
                    generateNewMailbox();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /**
     * The service's active domains. Never called from onFragmentCreate.
     *
     * Several hosts are tried because the mail.tm family is reachable under more than one
     * domain and any of them can be down or blocked from a given network. What matters is that a
     * failure is REPORTED: an empty list with no reason is what made this look like a broken
     * feature rather than an unreachable service. Measured on this network right now - every one
     * of these hosts fails to connect, while guerrillamail.com answers, so "no domains" here is
     * the network, not the code.
     */
    private static final String[] DOMAIN_ENDPOINTS = {
            // Tested from this network on 2026-09-22: api.mail.tm times out mid-TLS, and the
            // community mirrors I listed here first do not even resolve - they were guesses, so
            // they are gone. Kept because mail.tm is the real API on a normal network, and every
            // failure is now reported per host instead of showing an empty list.
            "https://api.mail.tm/domains?page=1",
    };

    /** The one disposable-mail API that did answer here, with its API shape verified by hand. */
    private static final String GUERRILLA_API = "https://api.guerrillamail.com/ajax.php";

    /**
     * The host that actually answered the domain probe. Every later call has to go to the same
     * one: a domain taken from mail-sac and an account created on mail.tm is a mailbox that
     * cannot receive anything.
     */
    private String apiBase = "https://api.mail.tm";

    /** "mailtm" or "guerrilla"; decides which backend the inbox calls go to. */
    private String provider = "mailtm";
    private String guerrillaToken = "";

    /** Why the last domain fetch failed, for the UI. Null when it succeeded. */
    private String lastDomainError;

    private ArrayList<String> fetchDomainList() {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder reasons = new StringBuilder();
        for (String endpoint : DOMAIN_ENDPOINTS) {
            try {
                JSONObject domRes = httpGetJson(endpoint);
                JSONArray members = domRes.optJSONArray("hydra:member");
                if (members == null || members.length() == 0) {
                    reasons.append(hostOf(endpoint)).append(": пустой ответ\n");
                    continue;
                }
                for (int i = 0; i < members.length(); i++) {
                    JSONObject d = members.optJSONObject(i);
                    if (d == null) continue;
                    // An inactive domain accepts the account and then rejects the token.
                    if (!d.optBoolean("isActive", true)) continue;
                    String dn = d.optString("domain", "");
                    if (!dn.isEmpty()) out.add(dn);
                }
                if (!out.isEmpty()) {
                    lastDomainError = null;
                    return out;
                }
                reasons.append(hostOf(endpoint)).append(": нет активных доменов\n");
            } catch (Throwable t) {
                String msg = t.getMessage();
                reasons.append(hostOf(endpoint)).append(": ")
                        .append(msg == null || msg.isEmpty() ? t.getClass().getSimpleName() : msg)
                        .append('\n');
            }
        }
        lastDomainError = reasons.length() == 0 ? "неизвестная ошибка" : reasons.toString().trim();
        Log.w(TAG, "fetchDomainList failed: " + lastDomainError);
        return out;
    }

    private static String schemeAndHost(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            if (u.getScheme() != null && u.getHost() != null) {
                return u.getScheme() + "://" + u.getHost();
            }
        } catch (Throwable ignored) {}
        return "https://api.mail.tm";
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Throwable t) {
            return url;
        }
    }

    private void toggleAutoRefresh() {
        autoRefreshEnabled = !autoRefreshEnabled;
        try {
            Context ctx = getParentActivity();
            if (ctx != null) {
                ctx.getSharedPreferences("colgram_tempmail", Context.MODE_PRIVATE)
                        .edit().putBoolean("auto_refresh", autoRefreshEnabled).apply();
            }
        } catch (Throwable ignored) {
        }
        if (autoRefreshEnabled) {
            startAutoRefresh();
        } else {
            autoRefreshRunning = false;
        }
        Context ctx = getParentActivity();
        if (ctx != null) {
            Toast.makeText(ctx, autoRefreshEnabled
                            ? "Автообновление включено (каждые 5 секунд)"
                            : "Автообновление выключено",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Re-load the last mailbox from preferences and refresh its token.
     *
     * @return true when a usable mailbox was restored
     */
    private boolean restoreSavedMailbox() {
        try {
            Context ctx = getParentActivity();
            if (ctx == null) return false;
            android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences("colgram_tempmail", Context.MODE_PRIVATE);
            String address = prefs.getString("address", "");
            String password = prefs.getString("password", "");
            if (address.isEmpty() || password.isEmpty()) return false;

            currentEmail = address;
            currentLogin = prefs.getString("login", "");
            currentDomain = prefs.getString("domain", "");
            mailTmToken = prefs.getString("token", "");

            // The stored token is probably stale; refresh it up front so the first poll
            // does not have to fail before recovering.
            String fresh = refreshMailToken();
            if (fresh == null) return false;

            fetchMessages(false);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "restoreSavedMailbox failed: " + t.getMessage());
            return false;
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        isDestroyed = true;
    }

    private void startAutoRefresh() {
        if (autoRefreshRunning) return;
        autoRefreshRunning = true;
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // `autoRefreshRunning` is the stop flag: without it, turning the toggle off
                // left the previous runnable chain alive and it kept polling anyway.
                if (isDestroyed || !autoRefreshRunning) return;
                fetchMessages(false);
                if (autoRefreshRunning) {
                    mainHandler.postDelayed(this, 5000);
                }
            }
        }, 5000);
    }

    /**
     * Run a network step, retrying transient failures.
     *
     * mail.tm intermittently answers with an empty body, a 5xx, or closes the socket
     * mid-request — especially right after app start while the radio is still settling.
     * Without a retry a single blip produced a permanent, user-visible failure ("Не
     * удалось создать ящик: ...") for a call that would have succeeded on the next try.
     *
     * A rejected request (4xx) is NOT retried: the server has made a decision and
     * repeating it just burns time and can trip rate limiting.
     */
    private <T> T withRetry(String label, java.util.concurrent.Callable<T> body) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= NET_ATTEMPTS; attempt++) {
            try {
                return body.call();
            } catch (Exception e) {
                last = e;
                if (isPermanent(e)) throw e;
                if (attempt < NET_ATTEMPTS) {
                    Log.w(TAG, label + " attempt " + attempt + " failed (" + e.getMessage()
                            + "), retrying in " + (NET_RETRY_BASE_MS * attempt) + "ms");
                    Thread.sleep(NET_RETRY_BASE_MS * attempt);
                }
            }
        }
        throw last != null ? last : new IOException(label + " failed");
    }

    /**
     * True when retrying cannot help — the server answered with a client error.
     *
     * Reads the status off the exception rather than the message text. Matching on prose
     * meant a reworded message would silently turn a non-retryable 4xx into three retries,
     * which is how you earn a 429.
     */
    private boolean isPermanent(Exception e) {
        if (e instanceof HttpException) {
            int c = ((HttpException) e).code;
            // 4xx = the server made a decision; repeating it is pointless and can trip
            // rate limiting. 408/429 ARE worth retrying (transient by definition).
            return c >= 400 && c < 500 && c != 408 && c != 429;
        }
        return false;
    }

    private void generateNewMailbox() {
        mailboxGenerating = true;
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
        executor.execute(() -> {
            try {
                // mail.tm — create a disposable mailbox.
                // Step 1: fetch an available domain.
                //
                // The literal "mail.tm" is NOT a valid mail.tm domain — the service hands
                // out addresses on domains it actually operates (uberip.com and friends).
                // Falling back to the literal meant account creation was rejected with 422
                // and the whole feature looked dead. If the domain list cannot be read we
                // must fail loudly instead of inventing an address.
                final String domain = withRetry("resolve domain", () -> {
                    // The user's pick wins. Only fall back to "first active domain" when
                    // they have not chosen one.
                    String resolved = pendingDomain;
                    if (resolved != null && !resolved.isEmpty()) {
                        return resolved;
                    }
                    JSONObject domRes = null;
                    if (resolved == null || resolved.isEmpty()) {
                        // Same multi-host path the picker uses. This used to hardcode one host,
                        // so a network that cannot reach it produced a dead "Новый ящик" button
                        // even though the service answers under other domains.
                        ArrayList<String> domains = fetchDomainList();
                        if (!domains.isEmpty()) {
                            resolved = domains.get(0);
                        }
                    }
                    if (resolved == null || resolved.isEmpty()) {
                        throw new IOException(lastDomainError != null && !lastDomainError.isEmpty()
                                ? lastDomainError : "сервис не вернул список доменов");
                    }
                    return resolved;
                });

                final String login = "colgram" + System.currentTimeMillis() % 1000000 + (int) (Math.random() * 9000 + 1000);
                final String address = login + "@" + domain;
                final String password = "Colgram_" + (int) (Math.random() * 900000 + 100000);

                final JSONObject create = new JSONObject();
                create.put("address", address);
                create.put("password", password);

                // Account creation is retried only for transient transport faults: a 422
                // means the address was taken, and repeating that is pointless.
                withRetry("create account", () -> {
                    JSONObject created = httpPostJson(apiBase + "/accounts", create.toString());
                    String accountId = created.optString("id", "");
                    if (accountId.isEmpty()) {
                        // A 2xx with no id means the body was empty or unexpected.
                        throw new IOException("сервис не подтвердил создание ящика");
                    }
                    return accountId;
                });

                // Step 2: authenticate to get the bearer token.
                final JSONObject authReq = new JSONObject();
                authReq.put("address", address);
                authReq.put("password", password);
                final String token = withRetry("authenticate", () -> {
                    JSONObject authRes = httpPostJson(apiBase + "/token", authReq.toString());
                    String t = authRes.optString("token", "");
                    if (t.isEmpty()) {
                        throw new IOException("сервис не выдал токен для ящика");
                    }
                    return t;
                });

                mainHandler.post(() -> {
                    // Persist the mailbox credentials: the JWT is short-lived and the
                    // account is the only way to get a new one, so it must survive a
                    // restart or the user loses the inbox they were watching.
                    if (getParentActivity() != null) {
                        getParentActivity().getSharedPreferences("colgram_tempmail", android.content.Context.MODE_PRIVATE)
                                .edit()
                                .putString("address", address)
                                .putString("password", password)
                                .putString("login", login)
                                .putString("domain", domain)
                                .putString("token", token)
                                .apply();
                    }
                    currentEmail = address;
                    currentLogin = login;
                    currentDomain = domain;
                    mailTmToken = token;
                    mailboxGenerating = false;
                    messages.clear();
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    fetchMessages(true);
                });
            } catch (Throwable t) {
                mailboxGenerating = false;
                final String msg = humanizeError(t);
                Log.w(TAG, "generateNewMailbox failed: " + t);
                // The primary service being unreachable is not a reason for the button to do
                // nothing. GuerrillaMail answers on networks where the whole mail.tm family
                // does not, so fall through to it and say which one produced the mailbox.
                if (generateWithGuerrilla()) {
                    return;
                }
                mainHandler.post(() -> {
                    if (getParentActivity() != null) {
                        Toast.makeText(getParentActivity(), "Не удалось создать ящик: " + msg, Toast.LENGTH_LONG).show();
                    }
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                });
            }
        });
    }

    /**
     * Create a mailbox on GuerrillaMail.
     *
     * Deliberately offers no domain picker: measured on 2026-09-22, both set_email_user and
     * set_email_domain leave the address on the gateway domain, so any list of "endings" this
     * provider showed would be a lie the user discovers when the confirmation mail bounces.
     */
    private boolean generateWithGuerrilla() {
        final Context ctx = getParentActivity();
        try {
            JSONObject r = httpGetJson(GUERRILLA_API + "?f=get_email_address&lang=en");
            final String addr = r.optString("email_addr", "");
            final String tok = r.optString("sid_token", "");
            if (addr.isEmpty() || tok.isEmpty()) return false;
            int at = addr.indexOf('@');
            final String login = at > 0 ? addr.substring(0, at) : addr;
            final String domain = at > 0 ? addr.substring(at + 1) : "";
            guerrillaToken = tok;
            provider = "guerrilla";
            if (ctx != null) {
                ctx.getSharedPreferences("colgram_tempmail", Context.MODE_PRIVATE).edit()
                        .putString("provider", provider)
                        .putString("address", addr)
                        .putString("login", login)
                        .putString("domain", domain)
                        .putString("guerrilla_token", tok)
                        .remove("token")
                        .apply();
            }
            mainHandler.post(() -> {
                currentEmail = addr;
                currentLogin = login;
                currentDomain = domain;
                mailTmToken = "";
                mailboxGenerating = false;
                messages.clear();
                if (listAdapter != null) listAdapter.notifyDataSetChanged();
                if (ctx != null) {
                    Toast.makeText(ctx, "Ящик создан на GuerrillaMail: основной сервис почты не отвечает",
                            Toast.LENGTH_LONG).show();
                }
                fetchMessages(true);
            });
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "guerrilla fallback failed: " + e.getMessage());
            return false;
        }
    }

    /** GuerrillaMail inbox: check_email returns {list:[{mail_id, mail_secret, ...}], ...}. */
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
                    // Reading a message needs mail_id AND mail_secret, and TempMessage has one
                    // string slot for identity, so the pair travels together.
                    gid + ":" + obj.optString("mail_secret", ""),
                    obj.optString("mail_from", ""),
                    obj.optString("mail_subject", ""),
                    obj.optString("mail_date", "")
            ));
        }
        return list;
    }

    private JSONObject guerrillaGet(String params) throws Exception {
        return httpGetJson(GUERRILLA_API + "?" + params + "&sid_token="
                + java.net.URLEncoder.encode(guerrillaToken, "UTF-8") + "&lang=en");
    }

    /**
     * Translate a raw network/JSON exception into something a person can act on.
     *
     * The user used to be shown the raw org.json text
     *     End of input at character 0 of
     * which describes a parser internal, not a problem they can fix. Anything we do not
     * have a specific phrase for falls through to the original message so no information
     * is lost — it is only the well-known cases that get human wording.
     */
    private String humanizeError(Throwable t) {
        String raw = t.getMessage() == null ? t.toString() : t.getMessage();
        if (raw.contains("End of input at character 0")) {
            return "сервис вернул пустой ответ (проверьте соединение)";
        }
        if (raw.contains("Unable to resolve host") || raw.contains("UnknownHostException")) {
            return "нет соединения с mail.tm";
        }
        if (raw.contains("timed out") || raw.contains("timeout")) {
            return "истекло время ожидания ответа mail.tm";
        }
        if (raw.contains("HTTP 429")) {
            return "слишком много запросов, попробуйте позже";
        }
        if (raw.contains("HTTP 5")) {
            return "сервис mail.tm временно недоступен";
        }
        return raw;
    }

    /**
     * Obtain a fresh JWT for the current mailbox.
     *
     * mail.tm bearer tokens expire (roughly an hour). The polling loop runs every 5s
     * forever, so without a refresh the inbox silently stops updating partway through a
     * session with no error shown — which reads as "temp mail does not work".
     *
     * @return the new token, or null when it could not be refreshed
     */
    private String refreshMailToken() {
        try {
            if (currentEmail == null || currentEmail.isEmpty()) return null;
            android.content.SharedPreferences prefs =
                    getParentActivity() != null ? getParentActivity().getSharedPreferences("colgram_tempmail", android.content.Context.MODE_PRIVATE) : null;
            String password = prefs != null ? prefs.getString("password", "") : "";
            if (password.isEmpty()) return null;

            JSONObject authReq = new JSONObject();
            authReq.put("address", currentEmail);
            authReq.put("password", password);
            JSONObject authRes = httpPostJson(apiBase + "/token", authReq.toString());
            String newToken = authRes.optString("token", "");
            if (newToken.isEmpty()) return null;

            mailTmToken = newToken;
            if (prefs != null) prefs.edit().putString("token", newToken).apply();
            Log.i(TAG, "temp mail token refreshed");
            return newToken;
        } catch (Throwable t) {
            Log.w(TAG, "token refresh failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Parse a mail.tm response that may be either a Hydra-wrapped object
     * ({"hydra:member":[...]}) or a BARE ARRAY ([{...}]).
     *
     * mail.tm now returns a bare array for /domains and /messages. The old code called
     * `new JSONObject(body)` unconditionally, so an array threw
     *     Value [{"id":"...","domain":"berip.com",...}] of type java.lang.String
     *     cannot be converted to JSONObject
     * which surfaced to the user as "Не удалось создать ящик: Value [{...}]" and made the
     * whole feature look dead.
     *
     * Normalising a bare array to {"hydra:member":[...]} keeps every existing call site
     * (optJSONArray("hydra:member")) working unchanged, and covers any future endpoint
     * that switches shape the same way.
     */
    private JSONObject parseAsObject(String body) throws org.json.JSONException {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("[")) {
            JSONObject wrapper = new JSONObject();
            wrapper.put("hydra:member", new JSONArray(trimmed));
            return wrapper;
        }
        // An EMPTY body is not valid JSON, and org.json reports that as
        //     End of input at character 0 of
        // which is meaningless to a user. It reached them as
        //     "Не удалось создать ящик: End of input at character 0 of"
        // See readBody(): an empty response is now only ever returned for 2xx with a
        // genuinely empty payload, so the right interpretation here is "no data".
        if (trimmed.isEmpty()) {
            return new JSONObject();
        }
        return new JSONObject(trimmed);
    }

    /**
     * Read a response body, tolerating an empty one.
     *
     * Two real cases produce a zero-length body and both used to be fatal:
     *
     *  1. A legitimately empty 2xx (204 No Content, or a terse 200). There is simply
     *     nothing to parse — treating that as an error made a SUCCESSFUL call look
     *     broken.
     *  2. A transport failure where the socket is closed before any bytes are written.
     *     `getInputStream()` then returns an empty stream instead of throwing, so the
     *     failure was never recognised as a failure.
     *
     * The returned map keeps the status code alongside the parsed JSON so callers can
     * distinguish "empty but fine" from "empty and an error".
     */
    /**
     * An HTTP failure that carries its status code as DATA, not as prose.
     *
     * The status used to be recoverable only by string-matching the exception message
     * ("HTTP 4xx"). That works until a message is reworded, and then a 4xx silently starts
     * being retried — the exact class of bug that rate-limited the Bot API. Carrying the
     * code on the exception makes the retry decision structural instead of textual.
     */
    /** Turn a relay-aware response into JSON, or into an error a person can read. */
    private JSONObject toJson(org.colgram.core.ColgramHttp.Response r, String urlStr) throws Exception {
        String body = r.body == null ? "" : r.body.trim();
        if (body.isEmpty()) {
            throw new HttpException(r.code, "HTTP " + r.code + " без тела ответа (" + urlStr + ")");
        }
        if (r.code < 200 || r.code >= 300) {
            // The API Platform convention puts the actionable text in the body ("address:
            // This value is already used"), so show that rather than a status code.
            String detail = "";
            try {
                detail = describeApiError(r.code, parseAsObject(body));
            } catch (Throwable ignored) {}
            throw new HttpException(r.code, detail.isEmpty()
                    ? ("HTTP " + r.code + ": " + (body.length() > 120 ? body.substring(0, 120) : body))
                    : detail);
        }
        try {
            // parseAsObject, not new JSONObject: mail.tm answers /domains with a bare JSON
            // ARRAY, which the old reader wrapped into hydra:member for everyone. Routing the
            // response through a new helper and parsing it directly regressed that silently.
            return parseAsObject(body);
        } catch (org.json.JSONException e) {
            throw new HttpException(r.code, "сервис вернул не JSON (" + r.code + "): "
                    + (body.length() > 80 ? body.substring(0, 80) : body));
        }
    }

    private static final class HttpException extends IOException {
        final int code;

        HttpException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private JSONObject httpGetJson(String urlStr) throws Exception {
        return httpGetJsonWithAuth(urlStr, null);
    }

    private JSONObject httpGetJsonWithAuth(String urlStr, String bearer) throws Exception {
        // Same address-family policy as the Bot API and GitHub paths. Without it this
        // screen re-learns the IPv6 lesson per call site: a device with no routable IPv6
        // hangs the whole timeout on an AAAA answer instead of falling back.
        org.colgram.core.ColgramBotSync.applyIpv4Policy();
        // ColgramHttp adds what a direct connection does not have here: when the provider's
        // IP is dropped by the network, the request goes out through one of the harvested
        // SOCKS5 relays instead of failing, and every attempt is named in the error.
        return toJson(org.colgram.core.ColgramHttp.get(urlStr, bearer), urlStr);
    }

    private JSONObject httpPostJson(String urlStr, String jsonBody) throws Exception {
        org.colgram.core.ColgramBotSync.applyIpv4Policy();
        return toJson(org.colgram.core.ColgramHttp.post(urlStr, jsonBody, null), urlStr);
    }

    /**
     * Turn a mail.tm error payload into something a human can act on.
     *
     * The API follows the API Platform convention: a machine code plus a prose
     * description, sometimes a list of per-field violations. Surface all of it —
     * "422 Unprocessable Entity" alone tells the user nothing, whereas
     * "address: This value is already used" is immediately actionable.
     */
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

    private void fetchMessages(boolean notifyUser) {
        if (mailTmToken == null || mailTmToken.isEmpty()) return;
        executor.execute(() -> {
            try {
                List<TempMessage> list;
                try {
                    list = loadInbox();
                } catch (Throwable first) {
                    // A 401 here almost always means the JWT aged out. Refresh once and
                    // retry before giving up, otherwise the poller dies silently and the
                    // inbox just stops updating with no message — the exact symptom the
                    // user reports as "temp mail is dead".
                    String refreshed = refreshMailToken();
                    if (refreshed == null) throw first;
                    list = loadInbox();
                }

                final List<TempMessage> finalList = list;
                mainHandler.post(() -> {
                    messages.clear();
                    messages.addAll(finalList);
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    if (notifyUser && getParentActivity() != null) {
                        Toast.makeText(getParentActivity(), "Входящие обновлены (" + finalList.size() + " писем)", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable t) {
                // Report the failure instead of swallowing it. The previous version used
                // `catch (Throwable ignored) {}`, which is why a broken mailbox looked
                // identical to an empty one.
                final String msg = humanizeError(t);
                Log.w(TAG, "fetchMessages failed: " + msg);
                if (notifyUser) {
                    mainHandler.post(() -> {
                        if (getParentActivity() != null) {
                            Toast.makeText(getParentActivity(), "Ошибка почты: " + msg, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private List<TempMessage> loadInbox() throws Exception {
        if ("guerrilla".equals(provider)) {
            return loadGuerrillaInbox();
        }
        JSONObject res = httpGetJsonWithAuth(apiBase + "/messages?page=1", mailTmToken);
        JSONArray arr = res.optJSONArray("hydra:member");
        final List<TempMessage> list = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String from = "";
                JSONObject fromObj = obj.optJSONObject("from");
                if (fromObj != null) from = fromObj.optString("address", "");
                String tmId = obj.optString("id", "");
                // Row identity is the real mail.tm id, not String.hashCode(). The old hash
                // was only 32 bits (collisions are plausible inside a mailbox) and can be
                // negative, which broke the id lookup in readMessageContent().
                list.add(new TempMessage(
                    tmId.hashCode() & 0x7fffffff,
                    tmId,
                    from,
                    obj.optString("subject", ""),
                    obj.optString("createdAt", "")
                ));
            }
        }
        return list;
    }

    private void readMessageContent(int messageId) {
        if (getParentActivity() == null) return;
        if ("guerrilla".equals(provider) && guerrillaToken.isEmpty()) return;
        if (!"guerrilla".equals(provider) && mailTmToken == null) return;
        Toast.makeText(getParentActivity(), "Загрузка письма...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                // Find the mail.tm message id from the cached list by hash code match
                String targetId = null;
                for (TempMessage m : messages) {
                    if (m.id == messageId) { targetId = m.tmId; break; }
                }
                if (targetId == null) return;

                JSONObject obj = httpGetJsonWithAuth(apiBase + "/messages/" + targetId, mailTmToken);
                String from = "";
                JSONObject fromObj = obj.optJSONObject("from");
                if (fromObj != null) from = fromObj.optString("address", "");
                String subject = obj.optString("subject", "");
                String textBody = obj.optString("text", "");
                if (textBody.isEmpty()) textBody = obj.optString("intro", "");

                String detectedOtp = "";
                Pattern pattern = Pattern.compile("\\b(\\d{4,8})\\b");
                Matcher matcher = pattern.matcher(subject + " " + textBody);
                if (matcher.find()) {
                    detectedOtp = matcher.group(1);
                }

                final String fFrom = from;
                final String fSubject = subject;
                final String fBody = textBody;
                final String fOtp = detectedOtp;

                mainHandler.post(() -> showMessageDialog(fFrom, fSubject, fBody, fOtp));
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (getParentActivity() != null) Toast.makeText(getParentActivity(), "Ошибка чтения: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showMessageDialog(String from, String subject, String body, String otp) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(subject.isEmpty() ? "Письмо" : subject);

        String message = "От: " + from + "\n\n" + body;
        if (!otp.isEmpty()) {
            message = "🔑 Найден проверочный код: " + otp + "\n\n" + message;
            final String finalOtp = otp;
            builder.setNeutralButton("Скопировать код (" + otp + ")", (d, w) -> {
                copyToClipboard(finalOtp);
                Toast.makeText(getParentActivity(), "Код " + finalOtp + " скопирован!", Toast.LENGTH_SHORT).show();
            });
        }
        builder.setMessage(message);
        builder.setPositiveButton("Скопировать текст", (d, w) -> {
            copyToClipboard(body);
            Toast.makeText(getParentActivity(), "Текст письма скопирован", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Закрыть", null);
        showDialog(builder.create());
    }

    private void copyToClipboard(String text) {
        try {
            if (getParentActivity() != null) {
                ClipboardManager cm = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Colgram TempMail", text);
                cm.setPrimaryClip(clip);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Временная почта (Temp Mail)");
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
                    generateNewMailbox();
                } else if (id == MENU_TOGGLE) {
                    toggleAutoRefresh();
                }
            }
        });
        // Actions, in the order a user reaches for them: refresh what you have, choose the
        // address, or start over. All three are real drawables verified to exist in res/
        // (msg_refresh / msg_reload / ic_refresh do NOT exist in this tree - only msg_retry,
        // msg_settings and msg_add do).
        actionBar.createMenu().addItem(MENU_REFRESH, R.drawable.msg_retry);
        actionBar.createMenu().addItem(MENU_DOMAIN, R.drawable.msg_settings);
        actionBar.createMenu().addItem(MENU_NEW, R.drawable.msg_add);
        // Polling is opt-in, so it needs a visible way in. A text item rather than an icon
        // because there is no "sync" drawable in this tree, and because the current state
        // matters here - the toast on toggle reports it.
        actionBar.createMenu().addItem(MENU_TOGGLE, "Авто");

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
            int msgCount = messages.size();
            if (position >= 2 && position < 2 + msgCount) {
                int msgIndex = position - 2;
                readMessageContent(messages.get(msgIndex).id);
            } else {
                int webIndex = position - (2 + (msgCount == 0 ? 1 : msgCount) + 2);
                if (webIndex >= 0 && webIndex < WEB_TEMP_SERVICES.length) {
                    Browser.openUrl(getParentActivity(), WEB_TEMP_SERVICES[webIndex][1]);
                }
            }
        });

        return fragmentView;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            int msgCount = messages.size();
            return 1 + 1 + (msgCount == 0 ? 1 : msgCount) + 1 + 1 + WEB_TEMP_SERVICES.length;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int pos = holder.getAdapterPosition();
            int msgCount = messages.size();
            if (pos == 0) return false;
            if (pos == 1) return false;
            if (msgCount == 0 && pos == 2) return false;
            int shadowPos = 2 + (msgCount == 0 ? 1 : msgCount);
            if (pos == shadowPos || pos == shadowPos + 1) return false;
            return true;
        }

        @Override
        public int getItemViewType(int position) {
            int msgCount = messages.size();
            int shadowPos = 2 + (msgCount == 0 ? 1 : msgCount);
            if (position == 0) return 10;
            if (position == 1 || position == shadowPos + 1) return 0;
            if (position == shadowPos) return 3;
            return 2;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 10: {
                    LinearLayout card = new LinearLayout(mContext);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
                    card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

                    TextView label = new TextView(mContext);
                    label.setText("Анонимный временный адрес:");
                    label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                    label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                    card.addView(label);

                    TextView emailTv = new TextView(mContext);
                    emailTv.setTag("email_tv");
                    emailTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                    emailTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                    emailTv.setTextColor(0xFFFF3344);
                    emailTv.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(12));
                    card.addView(emailTv);

                    LinearLayout btnRow = new LinearLayout(mContext);
                    btnRow.setOrientation(LinearLayout.HORIZONTAL);

                    TextView copyBtn = new TextView(mContext);
                    copyBtn.setText("Скопировать");
                    copyBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                    copyBtn.setTypeface(AndroidUtilities.bold());
                    copyBtn.setTextColor(Color.WHITE);
                    copyBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8), 0xFFFF3344, 0xFFCC1122));
                    copyBtn.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
                    copyBtn.setOnClickListener(v -> {
                        if (!currentEmail.isEmpty()) {
                            copyToClipboard(currentEmail);
                            Toast.makeText(mContext, "Почта скопирована: " + currentEmail, Toast.LENGTH_SHORT).show();
                        }
                    });
                    btnRow.addView(copyBtn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f, 0, 0, 8, 0));

                    TextView newBtn = new TextView(mContext);
                    newBtn.setText("Новый ящик");
                    newBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                    newBtn.setTypeface(AndroidUtilities.bold());
                    newBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    newBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8), 0x1A808080, 0x33808080));
                    newBtn.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
                    newBtn.setOnClickListener(v -> {
                        Toast.makeText(mContext, "Генерация нового ящика...", Toast.LENGTH_SHORT).show();
                        generateNewMailbox();
                    });
                    btnRow.addView(newBtn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f));

                    card.addView(btnRow);
                    view = card;
                    break;
                }
                case 0:
                    view = new HeaderCell(mContext);
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
            int msgCount = messages.size();
            int shadowPos = 2 + (msgCount == 0 ? 1 : msgCount);

            switch (holder.getItemViewType()) {
                case 10: {
                    LinearLayout card = (LinearLayout) holder.itemView;
                    TextView tv = card.findViewWithTag("email_tv");
                    if (tv != null) {
                        tv.setText(currentEmail.isEmpty()
                            ? (mailboxGenerating ? "Генерация адреса..." : "Ящик ещё не создан — нажми «Новый ящик»")
                            : currentEmail);
                    }
                    break;
                }
                case 0: {
                    HeaderCell h = (HeaderCell) holder.itemView;
                    if (position == 1) {
                        h.setText("Входящие письма (" + msgCount + ")");
                    } else {
                        h.setText("Веб-сервисы временных почт");
                    }
                    break;
                }
                case 2: {
                    TextSettingsCell s = (TextSettingsCell) holder.itemView;
                    if (position >= 2 && position < shadowPos) {
                        if (msgCount == 0) {
                            // Polling is opt-in, so promising a 5 second refresh while "Авто" is
                            // off was simply false.
                            s.setText(autoRefreshEnabled
                                    ? "Ожидание писем... обновляется каждые 5 сек"
                                    : "Писем пока нет. Включи «Авто» — будут приходить сами", false);
                        } else {
                            int mIdx = position - 2;
                            TempMessage m = messages.get(mIdx);
                            s.setTextAndValue(m.subject.isEmpty() ? "(Без темы)" : m.subject, m.from, mIdx < msgCount - 1);
                        }
                    } else {
                        int webIndex = position - (shadowPos + 2);
                        if (webIndex >= 0 && webIndex < WEB_TEMP_SERVICES.length) {
                            s.setTextAndValue(WEB_TEMP_SERVICES[webIndex][0], WEB_TEMP_SERVICES[webIndex][2], webIndex < WEB_TEMP_SERVICES.length - 1);
                        }
                    }
                    break;
                }
            }
        }
    }
}
