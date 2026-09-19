package org.telegram.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
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
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColgramTempMailActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private String currentEmail = "";
    private String currentLogin = "";
    private String currentDomain = "1secmail.com";

    public static class TempMessage {
        public final int id;
        public final String from;
        public final String subject;
        public final String date;

        public TempMessage(int id, String from, String subject, String date) {
            this.id = id;
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
        generateNewMailbox();
        startAutoRefresh();
        return true;
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
                URL url = new URL("https://www.1secmail.com/api/v1/?action=genRandomMailbox&count=1");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String res = r.readLine();
                    r.close();
                    JSONArray arr = new JSONArray(res);
                    if (arr.length() > 0) {
                        String email = arr.getString(0);
                        String[] parts = email.split("@");
                        mainHandler.post(() -> {
                            currentEmail = email;
                            currentLogin = parts[0];
                            currentDomain = parts.length > 1 ? parts[1] : "1secmail.com";
                            messages.clear();
                            if (listAdapter != null) listAdapter.notifyDataSetChanged();
                            fetchMessages(true);
                        });
                    }
                }
                conn.disconnect();
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    String rnd = "colgram_" + (int)(Math.random() * 90000 + 10000);
                    currentLogin = rnd;
                    currentDomain = "1secmail.com";
                    currentEmail = rnd + "@" + currentDomain;
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                });
            }
        });
    }

    private void fetchMessages(boolean notifyUser) {
        if (currentLogin.isEmpty()) return;
        executor.execute(() -> {
            try {
                URL url = new URL("https://www.1secmail.com/api/v1/?action=getMessages&login=" + currentLogin + "&domain=" + currentDomain);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();

                    JSONArray arr = new JSONArray(sb.toString());
                    final List<TempMessage> list = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        list.add(new TempMessage(
                            obj.optInt("id", 0),
                            obj.optString("from", ""),
                            obj.optString("subject", ""),
                            obj.optString("date", "")
                        ));
                    }
                    mainHandler.post(() -> {
                        messages.clear();
                        messages.addAll(list);
                        if (listAdapter != null) listAdapter.notifyDataSetChanged();
                        if (notifyUser && getParentActivity() != null) {
                            Toast.makeText(getParentActivity(), "Входящие обновлены (" + list.size() + " писем)", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                conn.disconnect();
            } catch (Throwable ignored) {}
        });
    }

    private void readMessageContent(int messageId) {
        if (getParentActivity() == null) return;
        Toast.makeText(getParentActivity(), "Загрузка письма...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                URL url = new URL("https://www.1secmail.com/api/v1/?action=readMessage&login=" + currentLogin + "&domain=" + currentDomain + "&id=" + messageId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();

                    JSONObject obj = new JSONObject(sb.toString());
                    String from = obj.optString("from", "");
                    String subject = obj.optString("subject", "");
                    String textBody = obj.optString("textBody", "");
                    if (textBody.isEmpty()) textBody = obj.optString("body", "");

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
                }
                conn.disconnect();
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
