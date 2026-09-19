package org.colgram.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramBotSync — Full Dialog History Synchronizer & Chat Initiator for Bot Accounts.
 *
 * 1. Synchronizes all active chats and messages from Telegram Bot API (getUpdates).
 * 2. Injects users, messages, and dialogs into Telegram's native MessagesStorage & MessagesController.
 * 3. Provides "Start chat as Bot" dialog by @username or user ID.
 */
public class ColgramBotSync {

    private static final String TAG = "ColgramBotSync";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

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
     * Initiates asynchronous sync of all chats for the bot account.
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

        if (userInitiated) {
            Toast.makeText(context, "🔄 Синхронизация чатов бота...", Toast.LENGTH_SHORT).show();
        }

        executor.execute(() -> {
            try {
                String urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?limit=100&offset=0";
                HttpURLConnection conn;
                try {
                    conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    if (conn.getResponseCode() != 200 && ColgramDpiBypass.isRunning()) {
                        throw new Exception("HTTP not 200");
                    }
                } catch (Throwable t) {
                    java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                            new java.net.InetSocketAddress("127.0.0.1", ColgramDpiBypass.LOCAL_PORT));
                    conn = (HttpURLConnection) new URL(urlStr).openConnection(proxy);
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(12000);
                }

                int responseCode = conn.getResponseCode();
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
                        mainHandler.post(() -> Toast.makeText(context, "Bot API вернул ошибку: " + root.optString("description"), Toast.LENGTH_SHORT).show());
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

                // Reflection handles into Telegram client core
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
                    String text = msgObj.optString("text", "[Медиа]");
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
                        mcClass.getMethod("putUser", Class.forName("org.telegram.tgnet.TLRPC$User"), boolean.class).invoke(mc, user, false);
                    }

                    // Create TLRPC.TL_message
                    Object message = messageClass.getConstructor().newInstance();
                    messageClass.getField("id").setInt(message, msgId);
                    messageClass.getField("dialog_id").setLong(message, chatId);
                    messageClass.getField("date").setInt(message, date);
                    messageClass.getField("message").set(message, text);

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

                // Reload dialogs in UI
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

                        if (userInitiated) {
                            Toast.makeText(context, "✅ Синхронизировано " + dialogsList.size() + " чатов бота!", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Error notifying UI after bot sync", t);
                    }
                });

            } catch (Throwable t) {
                Log.e(TAG, "Error syncing bot updates", t);
                if (userInitiated) {
                    mainHandler.post(() -> Toast.makeText(context, "Сбой синхронизации: " + t.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    /**
     * Opens a dialog allowing the bot to initiate a conversation with any user by username or ID.
     */
    public static void showStartChatDialog(final Activity activity, final int account) {
        if (activity == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("✉️ Написать от имени бота");
        builder.setMessage("Введите @username или числовой User ID пользователя:");

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        final EditText input = new EditText(activity);
        input.setHint("@username или 123456789");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        layout.addView(input);

        builder.setView(layout);
        builder.setPositiveButton("Открыть чат", (dialog, which) -> {
            String query = input.getText().toString().trim();
            if (query.isEmpty()) return;

            try {
                if (query.startsWith("@")) query = query.substring(1);

                if (query.matches("^\\d+$")) {
                    long userId = Long.parseLong(query);
                    openChatWithUserId(activity, userId);
                } else {
                    // Try open by username via MessagesController
                    Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
                    Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, account);

                    Class<?> launchActivityClass = Class.forName("org.telegram.ui.LaunchActivity");
                    if (launchActivityClass.isInstance(activity)) {
                        Method runWhenDone = mcClass.getMethod("openByUserName", String.class, Class.forName("org.telegram.ui.ActionBar.BaseFragment"), int.class);
                        runWhenDone.invoke(mc, query, null, 1);
                    } else {
                        Toast.makeText(activity, "Поиск пользователя: @" + query, Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error starting chat", t);
                Toast.makeText(activity, "Не удалось открыть чат: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    private static void openChatWithUserId(Activity activity, long userId) {
        try {
            Class<?> chatActivityClass = Class.forName("org.telegram.ui.ChatActivity");
            Class<?> baseFragmentClass = Class.forName("org.telegram.ui.ActionBar.BaseFragment");
            Class<?> launchActivityClass = Class.forName("org.telegram.ui.LaunchActivity");

            Bundle args = new Bundle();
            args.putLong("user_id", userId);

            Constructor<?> ctor = chatActivityClass.getConstructor(Bundle.class);
            Object fragment = ctor.newInstance(args);

            if (launchActivityClass.isInstance(activity)) {
                Method presentFragment = launchActivityClass.getMethod("presentFragment", baseFragmentClass);
                presentFragment.invoke(activity, fragment);
            }
        } catch (Throwable t) {
            Log.e(TAG, "openChatWithUserId error", t);
        }
    }
}
