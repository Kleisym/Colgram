package org.colgram.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramBotSync — Full Dialog History Synchronizer, Real-time Poller & Profile Manager for Bot Accounts.
 *
 * 1. Synchronizes chats and messages from Telegram Bot API.
 * 2. Background daemon long-polling for real-time incoming updates.
 * 3. Injects users, messages, and dialogs into Telegram's native MessagesStorage & MessagesController.
 * 4. Bot Profile Management (setMyName, setMyDescription) bypassing MTProto BOT_METHOD_INVALID.
 * 5. "Start chat as Bot" dialog by @username or user ID.
 */
public class ColgramBotSync {

    private static final String TAG = "ColgramBotSync";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final ConcurrentHashMap<Integer, Thread> pollerThreads = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Integer> lastUpdateIds = new ConcurrentHashMap<>();

    public static void saveBotToken(Context context, int account, String token) {
        if (context == null || token == null) return;
        token = token.trim();
        SharedPreferences prefs = context.getSharedPreferences("colgram_bot_account_" + account, Context.MODE_PRIVATE);
        prefs.edit().putString("bot_token", token).apply();

        SharedPreferences globalPrefs = context.getSharedPreferences("colgram_bot_tokens_global", Context.MODE_PRIVATE);
        globalPrefs.edit()
                .putString("token_account_" + account, token)
                .putString("last_bot_token", token)
                .apply();
    }

    public static String getBotToken(Context context, int account) {
        if (context == null) {
            try {
                Class<?> alClass = Class.forName("org.telegram.messenger.ApplicationLoader");
                context = (Context) alClass.getField("applicationContext").get(null);
            } catch (Throwable ignored) {}
        }
        if (context == null) return "";

        SharedPreferences prefs = context.getSharedPreferences("colgram_bot_account_" + account, Context.MODE_PRIVATE);
        String token = prefs.getString("bot_token", "");
        if (token.isEmpty()) {
            SharedPreferences globalPrefs = context.getSharedPreferences("colgram_bot_tokens_global", Context.MODE_PRIVATE);
            token = globalPrefs.getString("token_account_" + account, "");
            if (token.isEmpty()) {
                token = globalPrefs.getString("last_bot_token", "");
            }
        }
        return token;
    }

    private static HttpURLConnection openConnection(String urlStr, int readTimeoutMs) throws Exception {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(readTimeoutMs);
            return conn;
        } catch (Throwable t) {
            if (ColgramDpiBypass.isRunning()) {
                java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                        new java.net.InetSocketAddress("127.0.0.1", ColgramDpiBypass.LOCAL_PORT));
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection(proxy);
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(readTimeoutMs);
                return conn;
            }
            throw t;
        }
    }

    public static void deleteWebhook(String token) {
        if (token == null || token.isEmpty()) return;
        executor.execute(() -> {
            try {
                String urlStr = "https://api.telegram.org/bot" + token + "/deleteWebhook?drop_pending_updates=false";
                HttpURLConnection conn = openConnection(urlStr, 10000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.getOutputStream().close();
                int code = conn.getResponseCode();
                Log.d(TAG, "deleteWebhook result: " + code);
            } catch (Throwable t) {
                Log.w(TAG, "deleteWebhook error: " + t.getMessage());
            }
        });
    }

    /**
     * Starts continuous background polling for incoming messages on bot accounts.
     */
    public static synchronized void startBotUpdatesPoller(final Context context, final int account) {
        if (context == null) return;
        Thread existing = pollerThreads.get(account);
        if (existing != null && existing.isAlive()) {
            return;
        }

        final Context appContext = context.getApplicationContext();
        Thread poller = new Thread(() -> {
            Log.i(TAG, "Starting bot updates poller for account " + account);

            String token = getBotToken(appContext, account);
            if (!token.isEmpty()) {
                deleteWebhook(token);
            }

            SharedPreferences prefs = appContext.getSharedPreferences("colgram_bot_account_" + account, Context.MODE_PRIVATE);
            int savedOffset = prefs.getInt("last_update_id", 0);
            if (savedOffset > 0) {
                lastUpdateIds.put(account, savedOffset);
            }

            int consecutiveErrors = 0;

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Check if current user is still a bot on this account
                    Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
                    Object uc = ucClass.getMethod("getInstance", int.class).invoke(null, account);
                    Object currentUser = ucClass.getMethod("getCurrentUser").invoke(uc);
                    if (currentUser == null) {
                        break;
                    }
                    boolean isBot = currentUser.getClass().getField("bot").getBoolean(currentUser);
                    if (!isBot) {
                        break;
                    }

                    token = getBotToken(appContext, account);
                    if (token == null || token.isEmpty()) {
                        Thread.sleep(4000);
                        continue;
                    }

                    int currentOffset = lastUpdateIds.getOrDefault(account, 0);
                    String urlStr;
                    if (currentOffset == 0) {
                        // First run: fetch last 50 updates immediately
                        urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?limit=50&offset=-50&timeout=0";
                    } else {
                        // Long-poll: wait up to 20 seconds on server
                        urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?limit=100&offset=" + currentOffset + "&timeout=20";
                    }

                    HttpURLConnection conn = openConnection(urlStr, currentOffset == 0 ? 10000 : 28000);
                    conn.setRequestMethod("GET");

                    int responseCode = conn.getResponseCode();
                    if (responseCode == 409) {
                        // Webhook was active, delete it and retry
                        deleteWebhook(token);
                        Thread.sleep(2000);
                        continue;
                    }

                    if (responseCode != 200) {
                        consecutiveErrors++;
                        Thread.sleep(Math.min(consecutiveErrors * 2000, 15000));
                        continue;
                    }
                    consecutiveErrors = 0;

                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject root = new JSONObject(sb.toString());
                    if (root.optBoolean("ok", false)) {
                        JSONArray updates = root.optJSONArray("result");
                        if (updates != null && updates.length() > 0) {
                            int maxId = currentOffset;
                            for (int i = 0; i < updates.length(); i++) {
                                int uid = updates.getJSONObject(i).optInt("update_id", 0);
                                if (uid >= maxId) {
                                    maxId = uid + 1;
                                }
                            }
                            lastUpdateIds.put(account, maxId);
                            prefs.edit().putInt("last_update_id", maxId).apply();

                            processUpdatesJson(appContext, account, updates);
                        }
                    }

                    Thread.sleep(300);

                } catch (InterruptedException e) {
                    break;
                } catch (Throwable t) {
                    Log.w(TAG, "Poller cycle error: " + t.getMessage());
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            Log.i(TAG, "Bot updates poller stopped for account " + account);
        }, "ColgramBotPoller-" + account);

        poller.setDaemon(true);
        pollerThreads.put(account, poller);
        poller.start();
    }

    public static void promptBotTokenAndSync(final Activity activity, final int account) {
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("🔑 Токен бота (@BotFather)");
        builder.setMessage("Введите токен бота из @BotFather для загрузки диалогов и синхронизации сообщений:");

        final EditText input = new EditText(activity);
        input.setHint("123456789:ABCdef...");
        String existing = getBotToken(activity, account);
        if (!existing.isEmpty()) input.setText(existing);
        builder.setView(input);

        builder.setPositiveButton("Синхронизировать", (dialog, which) -> {
            String token = input.getText().toString().trim();
            if (!token.isEmpty()) {
                saveBotToken(activity, account, token);
                syncBotDialogs(activity, account, true);
            }
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    public static void syncBotDialogs(final Context context, final int account) {
        syncBotDialogs(context, account, false);
    }

    /**
     * One-shot dialog sync trigger, also kicks off the real-time poller.
     */
    public static void syncBotDialogs(final Context context, final int account, final boolean userInitiated) {
        if (context == null) return;
        final String token = getBotToken(context, account);
        if (token.isEmpty()) {
            if (userInitiated && context instanceof Activity) {
                promptBotTokenAndSync((Activity) context, account);
            }
            return;
        }

        // Always ensure background poller is running
        startBotUpdatesPoller(context, account);

        if (userInitiated) {
            Toast.makeText(context, "🔄 Синхронизация чатов бота...", Toast.LENGTH_SHORT).show();
        }

        executor.execute(() -> {
            try {
                String urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?limit=100&offset=-50";
                HttpURLConnection conn = openConnection(urlStr, 12000);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                if (responseCode == 409) {
                    deleteWebhook(token);
                }
                if (responseCode != 200) {
                    if (userInitiated) {
                        mainHandler.post(() -> Toast.makeText(context, "Ошибка Telegram Bot API: HTTP " + responseCode, Toast.LENGTH_SHORT).show());
                    }
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject root = new JSONObject(sb.toString());
                if (!root.optBoolean("ok", false)) {
                    if (userInitiated) {
                        mainHandler.post(() -> Toast.makeText(context, "Bot API: " + root.optString("description"), Toast.LENGTH_SHORT).show());
                    }
                    return;
                }

                JSONArray updates = root.optJSONArray("result");
                if (updates == null || updates.length() == 0) {
                    if (userInitiated) {
                        mainHandler.post(() -> Toast.makeText(context, "У бота пока нет входящих сообщений", Toast.LENGTH_SHORT).show());
                    }
                    return;
                }

                int maxId = 0;
                for (int i = 0; i < updates.length(); i++) {
                    int uid = updates.getJSONObject(i).optInt("update_id", 0);
                    if (uid >= maxId) {
                        maxId = uid + 1;
                    }
                }
                if (maxId > 0) {
                    lastUpdateIds.put(account, maxId);
                    context.getSharedPreferences("colgram_bot_account_" + account, Context.MODE_PRIVATE)
                            .edit().putInt("last_update_id", maxId).apply();
                }

                int count = processUpdatesJson(context, account, updates);
                if (userInitiated) {
                    final int finalCount = count;
                    mainHandler.post(() -> Toast.makeText(context, "✅ Синхронизировано " + finalCount + " чатов бота!", Toast.LENGTH_SHORT).show());
                }

            } catch (Throwable t) {
                Log.e(TAG, "Error syncing bot updates", t);
                if (userInitiated) {
                    mainHandler.post(() -> Toast.makeText(context, "Сбой: " + t.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    /**
     * Parses Bot API updates array and injects users, messages, and dialogs into MessagesStorage & MessagesController.
     */
    private static int processUpdatesJson(Context context, int account, JSONArray updates) {
        if (updates == null || updates.length() == 0) return 0;
        try {
            Class<?> userClass = Class.forName("org.telegram.tgnet.TLRPC$TL_user");
            Class<?> userStatusClass = Class.forName("org.telegram.tgnet.TLRPC$TL_userStatusRecently");
            Class<?> messageClass = Class.forName("org.telegram.tgnet.TLRPC$TL_message");
            Class<?> peerUserClass = Class.forName("org.telegram.tgnet.TLRPC$TL_peerUser");
            Class<?> peerChatClass = Class.forName("org.telegram.tgnet.TLRPC$TL_peerChat");
            Class<?> dialogClass = Class.forName("org.telegram.tgnet.TLRPC$TL_dialog");
            Class<?> messagesDialogsClass = Class.forName("org.telegram.tgnet.TLRPC$TL_messages_dialogs");
            Class<?> messagesDialogsBaseClass = Class.forName("org.telegram.tgnet.TLRPC$messages_Dialogs");

            Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
            Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, account);

            Class<?> msClass = Class.forName("org.telegram.messenger.MessagesStorage");
            Object ms = msClass.getMethod("getInstance", int.class).invoke(null, account);

            ArrayList usersList = new ArrayList();
            ArrayList messagesList = new ArrayList();
            Set<Long> processedUserIds = new HashSet<>();
            java.util.LinkedHashMap<Long, Integer> topMessageMap = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<Long, Integer> lastDateMap = new java.util.LinkedHashMap<>();

            for (int i = 0; i < updates.length(); i++) {
                JSONObject upd = updates.getJSONObject(i);
                JSONObject msgObj = upd.optJSONObject("message");
                if (msgObj == null) msgObj = upd.optJSONObject("edited_message");
                if (msgObj == null) msgObj = upd.optJSONObject("channel_post");
                if (msgObj == null) continue;

                JSONObject fromObj = msgObj.optJSONObject("from");
                JSONObject chatObj = msgObj.optJSONObject("chat");

                long fromId = fromObj != null ? fromObj.optLong("id", 0) : 0;
                long chatId = chatObj != null ? chatObj.optLong("id", 0) : fromId;
                if (chatId == 0) continue;

                String firstName = fromObj != null ? fromObj.optString("first_name", "User") : "Chat";
                String lastName = fromObj != null ? fromObj.optString("last_name", "") : "";
                String username = fromObj != null ? fromObj.optString("username", "") : "";

                String text = msgObj.optString("text", "");
                if (text.isEmpty()) {
                    if (msgObj.has("caption")) text = msgObj.optString("caption");
                    else if (msgObj.has("photo")) text = "🖼 Фотография";
                    else if (msgObj.has("video")) text = "📹 Видео";
                    else if (msgObj.has("document")) text = "📎 Документ";
                    else if (msgObj.has("voice")) text = "🎤 Голосовое сообщение";
                    else if (msgObj.has("sticker")) text = "🎨 Стикер";
                    else text = "[Сообщение]";
                }

                int date = msgObj.optInt("date", (int) (System.currentTimeMillis() / 1000));
                int msgId = msgObj.optInt("message_id", 1);

                topMessageMap.put(chatId, msgId);
                lastDateMap.put(chatId, date);

                // Create TLRPC.TL_user
                if (fromId != 0 && !processedUserIds.contains(fromId)) {
                    processedUserIds.add(fromId);
                    Object user = userClass.getConstructor().newInstance();
                    userClass.getField("id").setLong(user, fromId);
                    userClass.getField("first_name").set(user, firstName);
                    userClass.getField("last_name").set(user, lastName);
                    userClass.getField("username").set(user, username);
                    userClass.getField("phone").set(user, "");
                    userClass.getField("status").set(user, userStatusClass.getConstructor().newInstance());

                    usersList.add(user);
                    try {
                        mcClass.getMethod("putUser", Class.forName("org.telegram.tgnet.TLRPC$User"), boolean.class)
                                .invoke(mc, user, false);
                    } catch (Throwable ignored) {}
                }

                // Create TLRPC.TL_message
                Object message = messageClass.getConstructor().newInstance();
                messageClass.getField("id").setInt(message, msgId);
                messageClass.getField("date").setInt(message, date);
                messageClass.getField("message").set(message, text);
                try {
                    messageClass.getField("out").setBoolean(message, false);
                } catch (Throwable ignored) {}

                if (chatId < 0) {
                    Object peer = peerChatClass.getConstructor().newInstance();
                    peerChatClass.getField("chat_id").setLong(peer, -chatId);
                    messageClass.getField("peer_id").set(message, peer);
                } else {
                    Object peer = peerUserClass.getConstructor().newInstance();
                    peerUserClass.getField("user_id").setLong(peer, chatId);
                    messageClass.getField("peer_id").set(message, peer);
                }

                if (fromId != 0) {
                    Object peerFrom = peerUserClass.getConstructor().newInstance();
                    peerUserClass.getField("user_id").setLong(peerFrom, fromId);
                    messageClass.getField("from_id").set(message, peerFrom);
                }

                messagesList.add(message);
            }

            // Persist users and messages into database
            if (!usersList.isEmpty()) {
                msClass.getMethod("putUsersAndChats", ArrayList.class, ArrayList.class, boolean.class, boolean.class)
                        .invoke(ms, usersList, null, true, true);
            }
            if (!messagesList.isEmpty()) {
                msClass.getMethod("putMessages", ArrayList.class, boolean.class, boolean.class, boolean.class, int.class, int.class, long.class)
                        .invoke(ms, messagesList, true, true, false, 0, 0, 0L);
            }

            // Create and persist TLRPC.TL_dialog for each unique chat
            ArrayList dialogsList = new ArrayList();
            for (java.util.Map.Entry<Long, Integer> entry : topMessageMap.entrySet()) {
                long did = entry.getKey();
                int topMid = entry.getValue();
                int lastDate = lastDateMap.containsKey(did) ? lastDateMap.get(did) : (int) (System.currentTimeMillis() / 1000);

                Object dialog = dialogClass.getConstructor().newInstance();
                dialogClass.getField("id").setLong(dialog, did);
                dialogClass.getField("top_message").setInt(dialog, topMid);
                dialogClass.getField("last_message_date").setInt(dialog, lastDate);

                if (did < 0) {
                    Object peer = peerChatClass.getConstructor().newInstance();
                    peerChatClass.getField("chat_id").setLong(peer, -did);
                    dialogClass.getField("peer").set(dialog, peer);
                } else {
                    Object peer = peerUserClass.getConstructor().newInstance();
                    peerUserClass.getField("user_id").setLong(peer, did);
                    dialogClass.getField("peer").set(dialog, peer);
                }
                dialogsList.add(dialog);
            }

            if (!dialogsList.isEmpty()) {
                try {
                    Object dialogsRes = messagesDialogsClass.getConstructor().newInstance();
                    messagesDialogsClass.getField("dialogs").set(dialogsRes, dialogsList);
                    messagesDialogsClass.getField("messages").set(dialogsRes, messagesList);
                    messagesDialogsClass.getField("users").set(dialogsRes, usersList);
                    messagesDialogsClass.getField("chats").set(dialogsRes, new ArrayList());

                    msClass.getMethod("putDialogs", messagesDialogsBaseClass, int.class).invoke(ms, dialogsRes, 1);
                } catch (Throwable t) {
                    Log.w(TAG, "putDialogs reflection warning", t);
                }
            }

            // Reload UI dialogs & messages
            mainHandler.post(() -> {
                try {
                    Method loadDialogs = mcClass.getMethod("loadDialogs", int.class, int.class, int.class, boolean.class, Runnable.class);
                    loadDialogs.invoke(mc, 0, 0, 100, true, null);

                    Class<?> ncClass = Class.forName("org.telegram.messenger.NotificationCenter");
                    Method getInst = ncClass.getMethod("getInstance", int.class);
                    Object nc = getInst.invoke(null, account);

                    int dialogsNeedReload = ncClass.getField("dialogsNeedReload").getInt(null);
                    Method postNotification = ncClass.getMethod("postNotificationName", int.class, Object[].class);
                    postNotification.invoke(nc, dialogsNeedReload, new Object[0]);

                    try {
                        int updateInterfaces = ncClass.getField("updateInterfaces").getInt(null);
                        int maskAll = mcClass.getField("UPDATE_MASK_ALL").getInt(null);
                        postNotification.invoke(nc, updateInterfaces, new Object[]{maskAll});
                    } catch (Throwable ignored) {}

                } catch (Throwable t) {
                    Log.e(TAG, "Error notifying UI after bot sync", t);
                }
            });

            return dialogsList.size();
        } catch (Throwable t) {
            Log.e(TAG, "processUpdatesJson error", t);
            return 0;
        }
    }

    /**
     * Updates bot name via Telegram Bot API setMyName (bypassing MTProto BOT_METHOD_INVALID).
     */
    public static void updateBotName(final Context context, final int account, final String newName) {
        final String token = getBotToken(context, account);
        if (token.isEmpty()) return;

        executor.execute(() -> {
            try {
                String urlStr = "https://api.telegram.org/bot" + token + "/setMyName";
                JSONObject json = new JSONObject();
                json.put("name", newName);

                HttpURLConnection conn = openConnection(urlStr, 12000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.close();

                int code = conn.getResponseCode();
                if (code == 200) {
                    mainHandler.post(() -> {
                        try {
                            Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
                            Object uc = ucClass.getMethod("getInstance", int.class).invoke(null, account);
                            Object currentUser = ucClass.getMethod("getCurrentUser").invoke(uc);
                            if (currentUser != null) {
                                currentUser.getClass().getField("first_name").set(currentUser, newName);
                                ucClass.getMethod("saveConfig", boolean.class).invoke(uc, true);
                            }

                            Class<?> ncClass = Class.forName("org.telegram.messenger.NotificationCenter");
                            Object nc = ncClass.getMethod("getInstance", int.class).invoke(null, account);
                            int mainUserInfoChanged = ncClass.getField("mainUserInfoChanged").getInt(null);
                            ncClass.getMethod("postNotificationName", int.class, Object[].class).invoke(nc, mainUserInfoChanged, new Object[0]);

                            Toast.makeText(context, "Имя бота успешно обновлено!", Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            Log.e(TAG, "Error updating local user name", t);
                        }
                    });
                } else {
                    mainHandler.post(() -> Toast.makeText(context, "Ошибка изменения имени бота: HTTP " + code, Toast.LENGTH_SHORT).show());
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error setMyName", t);
            }
        });
    }

    /**
     * Updates bot description (bio) via Telegram Bot API setMyDescription & setMyShortDescription.
     */
    public static void updateBotDescription(final Context context, final int account, final String newBio, final Runnable onDone) {
        final String token = getBotToken(context, account);
        if (token.isEmpty()) return;

        executor.execute(() -> {
            try {
                // 1. setMyDescription (displayed on bot profile)
                String urlStr = "https://api.telegram.org/bot" + token + "/setMyDescription";
                JSONObject json = new JSONObject();
                json.put("description", newBio);

                HttpURLConnection conn = openConnection(urlStr, 12000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.close();
                int code = conn.getResponseCode();

                // 2. setMyShortDescription
                try {
                    String urlShort = "https://api.telegram.org/bot" + token + "/setMyShortDescription";
                    JSONObject jsonShort = new JSONObject();
                    jsonShort.put("short_description", newBio);
                    HttpURLConnection connShort = openConnection(urlShort, 10000);
                    connShort.setRequestMethod("POST");
                    connShort.setDoOutput(true);
                    connShort.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    OutputStream osShort = connShort.getOutputStream();
                    osShort.write(jsonShort.toString().getBytes("UTF-8"));
                    osShort.close();
                    connShort.getResponseCode();
                } catch (Throwable ignored) {}

                if (code == 200) {
                    mainHandler.post(() -> {
                        Toast.makeText(context, "Описание бота успешно обновлено!", Toast.LENGTH_SHORT).show();
                        if (onDone != null) onDone.run();
                    });
                } else {
                    mainHandler.post(() -> Toast.makeText(context, "Ошибка изменения описания: HTTP " + code, Toast.LENGTH_SHORT).show());
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error setMyDescription", t);
            }
        });
    }

    private static int getThemeColor(Class<?> themeClass, String keyName, int defaultColor) {
        if (themeClass == null) return defaultColor;
        try {
            Field keyField = themeClass.getField(keyName);
            int key = keyField.getInt(null);
            Method getColorMethod = themeClass.getMethod("getColor", int.class);
            return (int) getColorMethod.invoke(null, key);
        } catch (Throwable t) {
            return defaultColor;
        }
    }

    /**
     * Opens a dialog allowing the bot to initiate a conversation with any user by username or ID.
     */
    public static void showStartChatDialog(final Activity activity, final int account) {
        showStartChatDialog(activity, account, null);
    }

    public static void showStartChatDialog(final Activity activity, final int account, final Object fragmentObj) {
        if (activity == null || activity.isFinishing()) return;

        boolean isRu = false;
        try {
            Class<?> lcClass = Class.forName("org.telegram.messenger.LocaleController");
            Object lc = lcClass.getMethod("getInstance").invoke(null);
            Field currField = lcClass.getDeclaredField("currentLocaleInfo");
            currField.setAccessible(true);
            Object li = currField.get(lc);
            if (li != null) {
                String sn = (String) li.getClass().getField("shortName").get(li);
                isRu = "ru".equalsIgnoreCase(sn);
            }
        } catch (Throwable ignored) {}

        final boolean isRussian = isRu;

        try {
            Class<?> auClass = Class.forName("org.telegram.messenger.AndroidUtilities");
            Class<?> themeClass = Class.forName("org.telegram.ui.ActionBar.Theme");
            Class<?> alertBuilderClass = Class.forName("org.telegram.ui.ActionBar.AlertDialog$Builder");

            Method dpMethod = auClass.getMethod("dp", float.class);
            int dp8 = (int) dpMethod.invoke(null, 8f);
            int dp12 = (int) dpMethod.invoke(null, 12f);
            int dp16 = (int) dpMethod.invoke(null, 16f);
            int dp20 = (int) dpMethod.invoke(null, 20f);

            int textColor = getThemeColor(themeClass, "key_dialogTextBlack", Color.parseColor("#222222"));
            int grayColor = getThemeColor(themeClass, "key_dialogTextGray", Color.parseColor("#888888"));
            int hintColor = getThemeColor(themeClass, "key_dialogTextHint", Color.parseColor("#AAAAAA"));
            int accentColor = getThemeColor(themeClass, "key_featuredStickers_addButton", Color.parseColor("#2AABEE"));
            int fieldBgColor = getThemeColor(themeClass, "key_dialogInputField", Color.parseColor("#0F000000"));

            LinearLayout container = new LinearLayout(activity);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(dp20, dp8, dp20, dp8);

            TextView descView = new TextView(activity);
            descView.setText(isRussian
                    ? "Введите @username или числовой ID пользователя, чтобы открыть чат от имени бота:"
                    : "Enter @username or user ID to start a chat as bot:");
            descView.setTextColor(grayColor);
            descView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            descView.setPadding(0, 0, 0, dp16);
            container.addView(descView);

            LinearLayout inputCard = new LinearLayout(activity);
            inputCard.setOrientation(LinearLayout.HORIZONTAL);
            inputCard.setGravity(Gravity.CENTER_VERTICAL);
            inputCard.setPadding(dp12, dp8, dp12, dp8);

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(dp8);
            cardBg.setColor(fieldBgColor != 0 ? fieldBgColor : Color.parseColor("#15000000"));
            cardBg.setStroke((int) dpMethod.invoke(null, 1.0f), accentColor & 0x4DFFFFFF);
            inputCard.setBackground(cardBg);

            final EditText input = new EditText(activity);
            input.setHint("@username или 123456789");
            input.setHintTextColor(hintColor);
            input.setTextColor(textColor);
            input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            input.setBackground(null);
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            input.setLayoutParams(inputParams);
            inputCard.addView(input);

            container.addView(inputCard);

            Object builder = alertBuilderClass.getConstructor(Context.class).newInstance(activity);
            alertBuilderClass.getMethod("setTitle", CharSequence.class).invoke(builder, isRussian ? "✉️ Написать пользователю" : "✉️ Message User");
            alertBuilderClass.getMethod("setView", View.class).invoke(builder, container);

            alertBuilderClass.getMethod("setPositiveButton", CharSequence.class, DialogInterface.OnClickListener.class)
                    .invoke(builder, isRussian ? "Открыть чат" : "Open Chat", (DialogInterface.OnClickListener) (dialog, which) -> {
                        String query = input.getText().toString().trim();
                        if (query.isEmpty()) return;
                        openChatAsBot(activity, account, fragmentObj, query);
                    });

            alertBuilderClass.getMethod("setNegativeButton", CharSequence.class, DialogInterface.OnClickListener.class)
                    .invoke(builder, isRussian ? "Отмена" : "Cancel", null);

            alertBuilderClass.getMethod("show").invoke(builder);

        } catch (Throwable t) {
            Log.e(TAG, "showStartChatDialog fallback error", t);
            fallbackShowStartChatDialog(activity, account, fragmentObj, isRussian);
        }
    }

    private static void fallbackShowStartChatDialog(final Activity activity, final int account, final Object fragmentObj, final boolean isRussian) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(isRussian ? "✉️ Написать пользователю" : "✉️ Message User");
        builder.setMessage(isRussian ? "Введите @username или числовой User ID пользователя:" : "Enter @username or numerical User ID:");

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        final EditText input = new EditText(activity);
        input.setHint("@username или 123456789");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        layout.addView(input);

        builder.setView(layout);
        builder.setPositiveButton(isRussian ? "Открыть чат" : "Open Chat", (dialog, which) -> {
            String query = input.getText().toString().trim();
            if (query.isEmpty()) return;
            openChatAsBot(activity, account, fragmentObj, query);
        });
        builder.setNegativeButton(isRussian ? "Отмена" : "Cancel", null);
        builder.show();
    }

    private static void openChatAsBot(Activity activity, int account, Object fragmentObj, String query) {
        try {
            if (query.startsWith("@")) query = query.substring(1);

            Class<?> baseFragmentClass = Class.forName("org.telegram.ui.ActionBar.BaseFragment");
            Class<?> launchActivityClass = Class.forName("org.telegram.ui.LaunchActivity");
            Class<?> chatActivityClass = Class.forName("org.telegram.ui.ChatActivity");

            Object targetFragment = fragmentObj;
            if (targetFragment == null) {
                try {
                    Method getSafeLast = launchActivityClass.getMethod("getSafeLastFragment");
                    targetFragment = getSafeLast.invoke(null);
                } catch (Throwable ignored) {}
            }

            if (query.matches("^\\d+$")) {
                long userId = Long.parseLong(query);
                Bundle args = new Bundle();
                args.putLong("user_id", userId);
                Constructor<?> ctor = chatActivityClass.getConstructor(Bundle.class);
                Object chatFrag = ctor.newInstance(args);

                if (targetFragment != null) {
                    Method presentFragment = baseFragmentClass.getMethod("presentFragment", baseFragmentClass);
                    presentFragment.invoke(targetFragment, chatFrag);
                } else if (launchActivityClass.isInstance(activity)) {
                    Method presentFragment = launchActivityClass.getMethod("presentFragment", baseFragmentClass);
                    presentFragment.invoke(activity, chatFrag);
                }
            } else {
                Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
                Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, account);

                Method openByUserName = mcClass.getMethod("openByUserName", String.class, baseFragmentClass, int.class);
                openByUserName.invoke(mc, query, targetFragment, 1);
            }
        } catch (Throwable t) {
            Log.e(TAG, "openChatAsBot error", t);
            Toast.makeText(activity, "Ошибка открытия чата: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
