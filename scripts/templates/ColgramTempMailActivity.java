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
        // Restore a previously created mailbox before generating a new one. The JWT is
        // short-lived but the account is permanent, so reusing the saved address keeps the
        // inbox the user was watching instead of silently replacing it on every open.
        if (!restoreSavedMailbox()) {
            generateNewMailbox();
        }
        startAutoRefresh();
        return true;
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
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isDestroyed) return;
                fetchMessages(false);
                mainHandler.postDelayed(this, 5000);
            }
        }, 5000);
    }

    private void generateNewMailbox() {
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
                String resolvedDomain = null;
                JSONObject domRes = httpGetJson("https://api.mail.tm/domains?page=1");
                JSONArray members = domRes.optJSONArray("hydra:member");
                if (members != null) {
                    for (int i = 0; i < members.length(); i++) {
                        JSONObject d = members.optJSONObject(i);
                        if (d == null) continue;
                        // Only use a domain the server reports as active; an inactive
                        // domain accepts the account and then rejects the token.
                        if (!d.optBoolean("isActive", true)) continue;
                        String dn = d.optString("domain", "");
                        if (!dn.isEmpty()) {
                            resolvedDomain = dn;
                            break;
                        }
                    }
                }
                if (resolvedDomain == null) {
                    throw new IOException("Не удалось получить доступный домен mail.tm. Проверьте интернет.");
                }
                final String domain = resolvedDomain;

                final String login = "colgram" + System.currentTimeMillis() % 1000000 + (int) (Math.random() * 9000 + 1000);
                final String address = login + "@" + domain;
                final String password = "Colgram_" + (int) (Math.random() * 900000 + 100000);

                JSONObject create = new JSONObject();
                create.put("address", address);
                create.put("password", password);

                JSONObject created = httpPostJson("https://api.mail.tm/accounts", create.toString());
                String accountId = created.optString("id", "");
                if (accountId.isEmpty()) {
                    throw new IOException("mail.tm rejected mailbox creation");
                }

                // Step 2: authenticate to get the bearer token.
                JSONObject authReq = new JSONObject();
                authReq.put("address", address);
                authReq.put("password", password);
                JSONObject authRes = httpPostJson("https://api.mail.tm/token", authReq.toString());
                final String token = authRes.optString("token", "");
                if (token.isEmpty()) {
                    throw new IOException("mail.tm не выдал токен для нового ящика");
                }

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
                    messages.clear();
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    fetchMessages(true);
                });
            } catch (Throwable t) {
                // Include the address we tried so a rejected domain is diagnosable.
                final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                mainHandler.post(() -> {
                    if (getParentActivity() != null) {
                        Toast.makeText(getParentActivity(), "Не удалось создать ящик: " + msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
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
            JSONObject authRes = httpPostJson("https://api.mail.tm/token", authReq.toString());
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

    private JSONObject httpGetJson(String urlStr) throws Exception {
        return httpGetJsonWithAuth(urlStr, null);
    }

    private JSONObject httpGetJsonWithAuth(String urlStr, String bearer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/json");
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
        try {
            int code = conn.getResponseCode();
            // Read the error stream for non-2xx. Calling getInputStream() on a 4xx throws
            // FileNotFoundException and the server's explanation is lost, which is why a
            // rejected/expired request used to look like an unexplained failure.
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) throw new IOException("HTTP " + code);
            BufferedReader r = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject json = new JSONObject(sb.toString());
            if (code < 200 || code >= 300) {
                throw new IOException(describeApiError(code, json));
            }
            return json;
        } finally {
            conn.disconnect();
        }
    }

    private JSONObject httpPostJson(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        try {
            OutputStream os = conn.getOutputStream();
            os.write(jsonBody.getBytes("UTF-8"));
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) throw new IOException("HTTP " + code);
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject json = new JSONObject(sb.toString());
            if (code < 200 || code >= 300) {
                throw new IOException(describeApiError(code, json));
            }
            return json;
        } finally {
            conn.disconnect();
        }
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
                final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
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
        JSONObject res = httpGetJsonWithAuth("https://api.mail.tm/messages?page=1", mailTmToken);
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
        if (getParentActivity() == null || mailTmToken == null) return;
        Toast.makeText(getParentActivity(), "Загрузка письма...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                // Find the mail.tm message id from the cached list by hash code match
                String targetId = null;
                for (TempMessage m : messages) {
                    if (m.id == messageId) { targetId = m.tmId; break; }
                }
                if (targetId == null) return;

                JSONObject obj = httpGetJsonWithAuth("https://api.mail.tm/messages/" + targetId, mailTmToken);
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
                    copyBtn.setText("📋 Скопировать");
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
                    newBtn.setText("🔄 Новый ящик");
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
                        tv.setText(currentEmail.isEmpty() ? "Генерация адреса..." : currentEmail);
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
                            s.setText("Ожидание писем... (обновляется каждые 5 сек)", false);
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
