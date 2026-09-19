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
     * Convert a Bot API chat id to the id Telegram's client uses for a channel peer.
     *
     * Bot API:  -1001234567890
     * Client:    1234567890   (strip the -100 prefix)
     */
    private static long channelIdFromBotApi(long botApiChatId) {
        return Math.abs(botApiChatId) - 1000000000000L;
    }

    /**
     * A Bot API chat id is a supergroup/channel when it carries the -100 prefix.
     * Legacy groups are negative without it; private chats are positive.
     */
    private static boolean isSupergroupOrChannel(long botApiChatId) {
        return botApiChatId < 0 && String.valueOf(Math.abs(botApiChatId)).startsWith("100");
    }

    /**
     * Mirror of MessageObject.getPeerId() for a TL_message, without referencing Telegram
     * classes at compile time.
     *
     * Telegram's rule (MessageObject.getPeerId):
     *     TL_peerChat    -> -chat_id
     *     TL_peerChannel -> -channel_id
     *     TL_peerUser    ->  user_id
     * Returning 0 when the message has no peer keeps the caller's match check safe.
     */
    private static long peerIdOf(Class<?> messageClass, Class<?> peerChannelClass, Class<?> peerChatClass,
                                 Class<?> peerUserClass, Object message) {
        try {
            Object peer = messageClass.getField("peer_id").get(message);
            if (peer == null) return 0;
            if (peerChannelClass.isInstance(peer)) {
                return -peerChannelClass.getField("channel_id").getLong(peer);
            }
            if (peerChatClass.isInstance(peer)) {
                return -peerChatClass.getField("chat_id").getLong(peer);
            }
            if (peerUserClass.isInstance(peer)) {
                return peerUserClass.getField("user_id").getLong(peer);
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Build a TLRPC chat object for a group or channel seen through the Bot API.
     *
     * DialogsActivity cannot render a dialog row for a chat it has no object for: it needs
     * the title and the group/channel flags to pick the row type, the participant count for
     * the subtitle, and the id to look the peer up. The Bot API supplies only a subset, so
     * this constructs the closest honest equivalent:
     *
     *   - supergroup/channel -> TLRPC.TL_channel, megagroup=true (broadcast=false)
     *   - legacy group       -> TLRPC.TL_chat
     *
     * Field names differ between the two classes (title/participants_count exist on both,
     * but only TL_channel has megagroup/broadcast), so the optional ones are set defensively.
     *
     * @param source    the raw Bot API chat object, used for title/username when available
     * @param isChannel true for a supergroup/channel, false for a legacy group
     * @return a TLRPC chat instance, or null if reflection could not build one
     */
    private static Object buildBotApiChat(Class<?> chatClass, Class<?> channelClass, JSONObject source,
                                          long id, String title, int date, boolean isChannel) {
        try {
            Object chat = isChannel ? channelClass.getConstructor().newInstance() : chatClass.getConstructor().newInstance();

            chatClass.getField("id").setLong(chat, id);
            chatClass.getField("title").set(chat, title == null || title.isEmpty() ? "Chat " + id : title);
            chatClass.getField("date").setInt(chat, date);
            try {
                chatClass.getField("participants_count").setInt(chat, 0);
            } catch (Throwable ignored) {}

            if (source != null) {
                String username = source.optString("username", "");
                if (!username.isEmpty()) {
                    try { chatClass.getField("username").set(chat, username); } catch (Throwable ignored) {}
                }
            }

            if (isChannel) {
                // megagroup=true keeps the chat openable in ChatActivity; a channel with
                // broadcast=true and megagroup=false opens read-only and would look wrong
                // for a bot's own group.
                try { channelClass.getField("megagroup").setBoolean(chat, true); } catch (Throwable ignored) {}
                try { channelClass.getField("broadcast").setBoolean(chat, false); } catch (Throwable ignored) {}
                try { channelClass.getField("left").setBoolean(chat, false); } catch (Throwable ignored) {}
                try { channelClass.getField("creator").setBoolean(chat, true); } catch (Throwable ignored) {}
            }
            return chat;
        } catch (Throwable t) {
            Log.w(TAG, "could not build chat object for bot api id " + id + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * Fetch the bot's own profile via getMe and register it as the account's current user.
     *
     * Why this matters: on a bot account the client otherwise has no idea who "it" is.
     * MessagesController needs a current user to resolve the account's own id, to decide
     * which side of a dialog is "outgoing", and to render the avatar in the chat list.
     * Without it, dialogs can be inserted into storage and still never render.
     *
     * Returns the bot's user id, or 0 on failure.
     */
    public static long fetchAndRegisterBotSelf(Context context, int account, String token) {
        if (token == null || token.isEmpty()) return 0;
        try {
            HttpURLConnection conn = openConnection(
                    "https://api.telegram.org/bot" + token + "/getMe", 12000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return 0;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject root = new JSONObject(sb.toString());
            if (!root.optBoolean("ok", false)) return 0;
            JSONObject me = root.optJSONObject("result");
            if (me == null) return 0;

            long botId = me.optLong("id", 0);
            if (botId == 0) return 0;

            Class<?> userClass = Class.forName("org.telegram.tgnet.TLRPC$TL_user");
            Class<?> userStatusClass = Class.forName("org.telegram.tgnet.TLRPC$TL_userStatusRecently");

            Object user = userClass.getConstructor().newInstance();
            userClass.getField("id").setLong(user, botId);
            userClass.getField("first_name").set(user, me.optString("first_name", "Bot"));
            userClass.getField("last_name").set(user, me.optString("last_name", ""));
            userClass.getField("username").set(user, me.optString("username", ""));
            userClass.getField("phone").set(user, "");
            userClass.getField("bot").setBoolean(user, true);
            userClass.getField("status").set(user, userStatusClass.getConstructor().newInstance());

            Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
            Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, account);
            mcClass.getMethod("putUser", Class.forName("org.telegram.tgnet.TLRPC$User"), boolean.class)
                    .invoke(mc, user, true);

            SharedPreferences prefs = context.getSharedPreferences(
                    "colgram_bot_account_" + account, Context.MODE_PRIVATE);
            prefs.edit()
                    .putLong("bot_self_id", botId)
                    .putString("bot_self_username", me.optString("username", ""))
                    .apply();

            Log.i(TAG, "getMe ok: id=" + botId + " @" + me.optString("username", ""));
            return botId;
        } catch (Throwable t) {
            Log.w(TAG, "getMe failed: " + t.getMessage());
            return 0;
        }
    }

    /** Cached bot user id for this account, or 0 if getMe has not succeeded yet. */
    public static long getBotSelfId(Context context, int account) {
        if (context == null) return 0;
        return context.getSharedPreferences("colgram_bot_account_" + account, Context.MODE_PRIVATE)
                .getLong("bot_self_id", 0);
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
                // Make sure we know who the bot is before polling updates.
                if (getBotSelfId(appContext, account) == 0) {
                    fetchAndRegisterBotSelf(appContext, account, token);
                }
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
                        // First run.
                        //
                        // DO NOT use offset=-50 here. In the Bot API, `offset` means
                        // "return updates starting from this id" and Telegram treats every
                        // update BELOW that id as confirmed/delivered. A negative offset is
                        // read as "give me the last N", so this very first call marked the
                        // bot's entire pending backlog as read — and the next getUpdates
                        // came back empty. That is why the chat list was empty and never
                        // recovered until the local offset was cleared.
                        //
                        // Omitting offset entirely returns the pending queue without
                        // acknowledging anything we have not actually processed.
                        urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?limit=100&timeout=0&allowed_updates=%5B%22message%22%2C%22edited_message%22%2C%22channel_post%22%2C%22callback_query%22%5D";
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
        builder.setMessage("Введите токен бота из @BotFather для загрузки диалогов и синхронизации сообщений:\n\n"
                + "Важно: бот видит только те чаты, которые ему писали (или где он добавлен). "
                + "Если список пуст — напишите боту любое сообщение и нажмите «Синхронизировать» снова.");

        final EditText input = new EditText(activity);
        input.setHint("123456789:ABCdef...");
        String existing = getBotToken(activity, account);
        if (!existing.isEmpty()) input.setText(existing);
        builder.setView(input);

        builder.setPositiveButton("Синхронизировать", (dialog, which) -> {
            String token = input.getText().toString().trim();
            if (token.isEmpty()) {
                Toast.makeText(activity, "Токен пустой", Toast.LENGTH_SHORT).show();
                return;
            }
            saveBotToken(activity, account, token);
            // Verify the token before the heavier dialog sync, so a typo is reported as a
            // token problem instead of surfacing as the misleading "no chats found".
            syncBotDialogs(activity, account, true);
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
                // Resolve the bot's own identity FIRST. Without a current user the client
                // cannot resolve its own id, so dialogs get inserted but never render —
                // they look like "no chats" even when storage has them.
                if (getBotSelfId(context, account) == 0) {
                    fetchAndRegisterBotSelf(context, account, token);
                }

                String urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?limit=100&timeout=0";
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
            Class<?> peerChannelClass = Class.forName("org.telegram.tgnet.TLRPC$TL_peerChannel");
            Class<?> dialogClass = Class.forName("org.telegram.tgnet.TLRPC$TL_dialog");
            Class<?> chatClass = Class.forName("org.telegram.tgnet.TLRPC$TL_chat");
            Class<?> channelClass = Class.forName("org.telegram.tgnet.TLRPC$TL_channel");
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
            // Which updates belong to a group/channel, and what to call it. Needed later when
            // building the chat object the dialog row is rendered from — the chat fields are
            // not all present on every update, so the last non-empty value wins.
            java.util.LinkedHashMap<Long, JSONObject> chatObjCache = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<Long, String> titleCache = new java.util.LinkedHashMap<>();

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
                if (chatObj != null && chatId < 0) {
                    chatObjCache.put(chatId, chatObj);
                    String t = chatObj.optString("title", "");
                    if (t.isEmpty()) {
                        // Fall back to @username so the row is not labelled "Chat".
                        t = chatObj.optString("username", "");
                    }
                    if (!t.isEmpty()) titleCache.put(chatId, t);
                }

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

                // Resolve the correct peer type and id.
                //
                // Telegram has THREE peer shapes and the Bot API returns them all as
                // negative chat ids, so you cannot pick the class by sign alone:
                //
                //   -100XXXXXXXXXX  supergroup or channel  -> TLRPC.TL_peerChannel,
                //                                             id = -chatId - 1000000000000
                //   -XXXXXXXXXX     legacy group           -> TLRPC.TL_peerChat,
                //                                             id = -chatId
                //   > 0             private chat           -> TLRPC.TL_peerUser
                //
                // The previous code sent EVERY negative id to TL_peerChat as -chatId, so a
                // supergroup became channel_id 1001234567890 — an id that does not exist.
                // The dialog was stored under a bogus peer and never rendered, which is why
                // group chats were missing entirely.
                if (isSupergroupOrChannel(chatId)) {
                    Object peer = peerChannelClass.getConstructor().newInstance();
                    peerChannelClass.getField("channel_id").setLong(peer, channelIdFromBotApi(chatId));
                    messageClass.getField("peer_id").set(message, peer);
                } else if (chatId < 0) {
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

            // Create and persist TLRPC.TL_dialog for each unique chat.
            //
            // The dialog id MUST equal MessageObject.getPeerId(peer), which Telegram defines
            // as:
            //     TL_peerChat    -> -chat_id
            //     TL_peerChannel -> -channel_id
            //     TL_peerUser    ->  user_id
            //
            // The Bot API hands back a supergroup as -1001234567890. That is NOT a Telegram
            // dialog id: the real channel_id is 1234567890 and the dialog id is
            // -1234567890. Writing the raw -100-prefixed value produced a dialog whose id
            // matched no peer, so the chat never appeared in the list even though the
            // message and user rows were stored correctly. This mirrors the peer-type fix
            // applied to messages above.
            ArrayList dialogsList = new ArrayList();
            ArrayList chatsList = new ArrayList();
            for (java.util.Map.Entry<Long, Integer> entry : topMessageMap.entrySet()) {
                long botApiChatId = entry.getKey();
                int topMid = entry.getValue();
                int lastDate = lastDateMap.containsKey(botApiChatId) ? lastDateMap.get(botApiChatId)
                        : (int) (System.currentTimeMillis() / 1000);

                long dialogId;
                Object peer;
                if (isSupergroupOrChannel(botApiChatId)) {
                    long channelId = channelIdFromBotApi(botApiChatId);
                    dialogId = -channelId;
                    peer = peerChannelClass.getConstructor().newInstance();
                    peerChannelClass.getField("channel_id").setLong(peer, channelId);
                    // DialogsActivity resolves the title and row type from a registered chat
                    // object; without it the dialog row cannot be built at all.
                    Object chat = buildBotApiChat(chatClass, channelClass, chatObjCache.get(botApiChatId),
                            channelId, titleCache.get(botApiChatId), lastDate, true);
                    if (chat != null) chatsList.add(chat);
                } else if (botApiChatId < 0) {
                    long chatId = -botApiChatId;
                    dialogId = -chatId;
                    peer = peerChatClass.getConstructor().newInstance();
                    peerChatClass.getField("chat_id").setLong(peer, chatId);
                    Object chat = buildBotApiChat(chatClass, channelClass, chatObjCache.get(botApiChatId),
                            chatId, titleCache.get(botApiChatId), lastDate, false);
                    if (chat != null) chatsList.add(chat);
                } else {
                    dialogId = botApiChatId;
                    peer = peerUserClass.getConstructor().newInstance();
                    peerUserClass.getField("user_id").setLong(peer, dialogId);
                }

                Object dialog = dialogClass.getConstructor().newInstance();
                dialogClass.getField("id").setLong(dialog, dialogId);
                dialogClass.getField("peer").set(dialog, peer);
                dialogClass.getField("top_message").setInt(dialog, topMid);
                dialogClass.getField("last_message_date").setInt(dialog, lastDate);
                dialogClass.getField("unread_count").setInt(dialog, 0);
                dialogsList.add(dialog);
            }

            if (!chatsList.isEmpty()) {
                try {
                    msClass.getMethod("putUsersAndChats", ArrayList.class, ArrayList.class, boolean.class, boolean.class)
                            .invoke(ms, null, chatsList, true, true);
                    // Also publish to the live chat cache so the dialog list can build rows
                    // immediately, without waiting for a full getDialogs round-trip.
                    for (Object c : chatsList) {
                        try {
                            mcClass.getMethod("putChat", Class.forName("org.telegram.tgnet.TLRPC$Chat"), boolean.class)
                                    .invoke(mc, c, false);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "putUsersAndChats(chats) reflection warning", t);
                }
            }

            if (!dialogsList.isEmpty()) {
                try {
                    Object dialogsRes = messagesDialogsClass.getConstructor().newInstance();
                    messagesDialogsClass.getField("dialogs").set(dialogsRes, dialogsList);
                    messagesDialogsClass.getField("messages").set(dialogsRes, messagesList);
                    messagesDialogsClass.getField("users").set(dialogsRes, usersList);
                    messagesDialogsClass.getField("chats").set(dialogsRes, chatsList);

                    msClass.getMethod("putDialogs", messagesDialogsBaseClass, int.class).invoke(ms, dialogsRes, 1);
                } catch (Throwable t) {
                    Log.w(TAG, "putDialogs reflection warning", t);
                }
            }

            // Seed the IN-MEMORY dialog cache, not just SQLite.
            //
            // putDialogs() above only writes rows to the database. The chat list is rendered
            // from MessagesController.dialogs_dict / dialogMessage, and those are populated by
            // loadDialogs() from a server getDialogs response. A bot account's getDialogs
            // returns almost nothing, so the cache stayed empty and the list rendered blank
            // even though every dialog was correctly persisted. Nothing downstream reloads
            // storage on its own, so the cache has to be filled here.
            //
            // The same seed is also posted to the UI thread, because DialogsActivity reads
            // these structures directly while building rows.
            final ArrayList finalDialogsList = dialogsList;
            final ArrayList finalMessagesList = messagesList;
            final ArrayList finalUsersList = usersList;
            final ArrayList finalChatsList = chatsList;
            Runnable seedCache = () -> {
                try {
                    Class<?> dialogBaseClass = Class.forName("org.telegram.tgnet.TLRPC$Dialog");
                    Class<?> msgObjCls = Class.forName("org.telegram.messenger.MessageObject");
                    Class<?> sparseArrayClass = Class.forName("android.util.LongSparseArray");
                    Class<?> arrayListClass = java.util.ArrayList.class;
                    Class<?> chatBaseClass = Class.forName("org.telegram.tgnet.TLRPC$Chat");
                    Class<?> userBaseClass = Class.forName("org.telegram.tgnet.TLRPC$User");

                    java.lang.reflect.Field dictField = mcClass.getField("dialogs_dict");
                    java.lang.reflect.Field msgField = mcClass.getField("dialogMessage");

                    Object dict = dictField.get(mc);
                    Object msgs = msgField.get(mc);
                    if (dict == null || msgs == null) return;

                    // LongSparseArray.put(long, Object)
                    Method putSparse = sparseArrayClass.getMethod("put", long.class, Object.class);
                    Method getSparse = sparseArrayClass.getMethod("get", long.class);

                    // Register users/chats in the live caches so titles and avatars resolve.
                    if (finalUsersList != null && !finalUsersList.isEmpty()) {
                        Method putUser = mcClass.getMethod("putUser", userBaseClass, boolean.class);
                        for (Object u : finalUsersList) putUser.invoke(mc, u, false);
                    }
                    if (finalChatsList != null && !finalChatsList.isEmpty()) {
                        Method putChat = mcClass.getMethod("putChat", chatBaseClass, boolean.class);
                        for (Object c : finalChatsList) putChat.invoke(mc, c, false);
                    }

                    for (Object d : finalDialogsList) {
                        long did = dialogClass.getField("id").getLong(d);
                        putSparse.invoke(dict, did, d);

                        // Attach the newest message as a MessageObject so the row shows a
                        // preview line instead of an empty subtitle.
                        Object best = null;
                        int bestId = -1;
                        for (Object m : finalMessagesList) {
                            try {
                                long peerId = peerIdOf(messageClass, peerChannelClass, peerChatClass,
                                        peerUserClass, m);
                                int mid = messageClass.getField("id").getInt(m);
                                if (peerId == did && mid > bestId) {
                                    bestId = mid;
                                    best = m;
                                }
                            } catch (Throwable ignored) {}
                        }
                        if (best != null) {
                            Object mo = msgObjCls.getConstructor(int.class, messageClass, messageClass, boolean.class)
                                    .newInstance(account, best, null, false);
                            java.util.ArrayList<Object> list = new java.util.ArrayList<>();
                            list.add(mo);
                            putSparse.invoke(msgs, did, list);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "could not seed in-memory dialog cache: " + t.getMessage());
                }
            };
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                seedCache.run();
            } else {
                mainHandler.post(seedCache);
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
