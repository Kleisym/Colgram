package org.telegram.ui;

import android.app.Activity;
import android.database.Cursor;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import org.colgram.core.ColgramAiAgentTools;
import org.colgram.core.ColgramAiContextEngine;
import org.colgram.core.ColgramAiEngine;
import org.colgram.core.ColgramBotSync;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unified Telegram <-> Colgram Aero AI bridge:
 * - Resolves live ChatActivity across phone stacks and tablet RightSlidingDialogContainer
 * - Reads active chat messages + scans across ALL dialogs (memory + SQLite fallback)
 * - Grabs latest photo/video files from chat automatically when Vision intent is detected
 * - Inserts AI drafts into ChatActivity input field & executes safe in-app Colgram actions
 */
public final class ColgramAiChatBridge {

    private ColgramAiChatBridge() {}

    public static BaseFragment resolveTopFragment(LaunchActivity activity) {
        if (activity == null) return null;
        try {
            BaseFragment f = LaunchActivity.getSafeLastFragment();
            BaseFragment leftBase = activity.actionBarLayout != null ? activity.actionBarLayout.getLastFragment() : null;
            boolean hasLayerModal = f != null && leftBase != null && f != leftBase;
            if (!hasLayerModal && activity.rightActionBarLayout != null
                    && activity.rightActionBarLayout.getView() != null
                    && activity.rightActionBarLayout.getView().getVisibility() == android.view.View.VISIBLE
                    && !activity.rightActionBarLayout.getFragmentStack().isEmpty()) {
                BaseFragment rf = activity.rightActionBarLayout.getLastFragment();
                if (rf != null) {
                    return rf;
                }
            }
            if (f instanceof MainTabsActivity) {
                BaseFragment visible = ((MainTabsActivity) f).getCurrentVisibleFragment();
                if (visible != null) return visible;
            }
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    public static ChatActivity resolveActiveChat(BaseFragment fragment) {
        if (fragment instanceof ChatActivity) {
            return (ChatActivity) fragment;
        }
        if (fragment instanceof DialogsActivity) {
            DialogsActivity d = (DialogsActivity) fragment;
            if (d.rightSlidingDialogContainer != null && d.rightSlidingDialogContainer.hasFragment()) {
                BaseFragment rf = d.rightSlidingDialogContainer.getFragment();
                if (rf instanceof ChatActivity) {
                    return (ChatActivity) rf;
                }
            }
        }
        if (fragment instanceof MainTabsActivity) {
            DialogsActivity da = ((MainTabsActivity) fragment).getDialogsActivity();
            if (da != null && da.rightSlidingDialogContainer != null && da.rightSlidingDialogContainer.hasFragment()) {
                BaseFragment rf = da.rightSlidingDialogContainer.getFragment();
                if (rf instanceof ChatActivity) {
                    return (ChatActivity) rf;
                }
            }
        }
        if (LaunchActivity.instance != null && LaunchActivity.instance.rightActionBarLayout != null
                && LaunchActivity.instance.rightActionBarLayout.getView() != null
                && LaunchActivity.instance.rightActionBarLayout.getView().getVisibility() == android.view.View.VISIBLE
                && !LaunchActivity.instance.rightActionBarLayout.getFragmentStack().isEmpty()) {
            BaseFragment rf = LaunchActivity.instance.rightActionBarLayout.getLastFragment();
            if (rf instanceof ChatActivity) {
                return (ChatActivity) rf;
            }
        }
        LaunchActivity la = LaunchActivity.instance;
        if (la != null) {
            BaseFragment safe = LaunchActivity.getSafeLastFragment();
            if (safe instanceof ChatActivity) return (ChatActivity) safe;
            if (safe instanceof MainTabsActivity) {
                DialogsActivity da = ((MainTabsActivity) safe).getDialogsActivity();
                if (da != null && da.rightSlidingDialogContainer != null && da.rightSlidingDialogContainer.hasFragment()) {
                    BaseFragment rf = da.rightSlidingDialogContainer.getFragment();
                    if (rf instanceof ChatActivity) return (ChatActivity) rf;
                }
            }
        }
        return null;
    }

    public static String resolveContextTitle(BaseFragment fragment, ChatActivity chat) {
        if (chat != null) return resolveChatTitle(chat);
        if (fragment instanceof DialogsActivity) return "Список чатов";
        if (fragment instanceof SettingsActivity) return "Настройки";
        if (fragment instanceof ColgramSettingsActivity) return "Настройки Colgram";
        if (fragment instanceof ProfileActivity) return "Профиль";
        if (fragment instanceof ContactsActivity) return "Контакты";
        return "Koliti";
    }

    public static String resolveChatTitle(ChatActivity chat) {
        if (chat == null) return "Чат";
        TLRPC.Chat c = chat.getCurrentChat();
        if (c != null && !TextUtils.isEmpty(c.title)) {
            return c.title;
        }
        TLRPC.User u = chat.getCurrentUser();
        if (u != null) {
            if (u.self) return "Избранное / Заметки";
            String name = UserObject.getUserName(u);
            if (!TextUtils.isEmpty(name)) return name;
        }
        return "Диалог " + chat.getDialogId();
    }

    /**
     * Collects authoritative metadata about the currently open chat/channel/bot (type, title, @username,
     * description/about, bot welcome card & commands, subscriber count, pinned message) so Koliti NEVER
     * guesses what a channel, bot, or chat is about!
     */
    public static String collectActiveChatMetadata(ChatActivity chat) {
        if (chat == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            int account = chat.getCurrentAccount();
            MessagesController mc = MessagesController.getInstance(account);
            TLRPC.Chat c = chat.getCurrentChat();
            if (c != null) {
                boolean isChannel = c.broadcast && !c.megagroup;
                sb.append(isChannel ? "Тип: Telegram-канал" : "Тип: Группа").append('\n');
                if (!TextUtils.isEmpty(c.title)) {
                    sb.append("Название: ").append(c.title.trim()).append('\n');
                }
                if (!TextUtils.isEmpty(c.username)) {
                    sb.append("Username: @").append(c.username.trim()).append('\n');
                }
                TLRPC.ChatFull full = chat.getCurrentChatInfo();
                if (full == null) {
                    full = mc.getChatFull(c.id);
                }
                int count = c.participants_count > 0 ? c.participants_count
                        : (full != null ? full.participants_count : 0);
                if (count > 0) {
                    sb.append("Подписчиков/участников: ").append(count).append('\n');
                }
                if (full != null && !TextUtils.isEmpty(full.about)) {
                    sb.append("Описание: ").append(full.about.trim()).append('\n');
                }
                if (full != null && full.pinned_msg_id > 0) {
                    String pinnedText = loadSingleMessageText(chat, account, chat.getDialogId(), full.pinned_msg_id);
                    if (!TextUtils.isEmpty(pinnedText)) {
                        if (pinnedText.length() > 360) pinnedText = pinnedText.substring(0, 357) + "…";
                        sb.append("Закреплённое сообщение: ").append(pinnedText).append('\n');
                    }
                }
            } else {
                TLRPC.User u = chat.getCurrentUser();
                if (u != null) {
                    sb.append(u.self ? "Тип: Избранное (личные заметки)\n" : (u.bot ? "Тип: Telegram-бот\n" : "Тип: Личный чат\n"));
                    sb.append("Название: ").append(UserObject.getUserName(u)).append('\n');
                    if (!TextUtils.isEmpty(u.username)) {
                        sb.append("Username: @").append(u.username.trim()).append('\n');
                    }
                    if (u.bot && u.bot_active_users > 0) {
                        sb.append("Активных пользователей: ").append(u.bot_active_users).append('\n');
                    }
                    TLRPC.UserFull uFull = chat.getCurrentUserInfo();
                    if (uFull == null) {
                        uFull = mc.getUserFull(u.id);
                    }
                    if (uFull != null && !TextUtils.isEmpty(uFull.about)) {
                        sb.append("О себе: ").append(uFull.about.trim()).append('\n');
                    }
                    // Bot welcome card ("Что умеет этот бот") lives in chat.botInfo and/or uFull.bot_info!
                    TL_bots.BotInfo bInfo = null;
                    if (chat.botInfo != null && chat.botInfo.size() > 0) {
                        bInfo = chat.botInfo.get(u.id);
                        if (bInfo == null) {
                            bInfo = chat.botInfo.valueAt(0);
                        }
                    }
                    if ((bInfo == null || TextUtils.isEmpty(bInfo.description)) && uFull != null && uFull.bot_info != null) {
                        bInfo = uFull.bot_info;
                    }
                    if (bInfo != null) {
                        if (!TextUtils.isEmpty(bInfo.description)) {
                            String desc = bInfo.description.trim();
                            if (desc.length() > 800) {
                                desc = desc.substring(0, 800) + "…";
                            }
                            sb.append("Описание бота (карточка): ").append(desc).append('\n');
                        }
                        if (bInfo.commands != null && !bInfo.commands.isEmpty()) {
                            StringBuilder cmdSb = new StringBuilder();
                            int maxCmds = Math.min(bInfo.commands.size(), 8);
                            for (int i = 0; i < maxCmds; i++) {
                                TLRPC.BotCommand cmd = bInfo.commands.get(i);
                                if (cmd == null || TextUtils.isEmpty(cmd.command)) continue;
                                if (cmdSb.length() > 0) cmdSb.append("; ");
                                cmdSb.append('/').append(cmd.command.trim());
                                if (!TextUtils.isEmpty(cmd.description)) {
                                    cmdSb.append(" — ").append(cmd.description.trim());
                                }
                            }
                            if (cmdSb.length() > 0) {
                                sb.append("Команды бота: ").append(cmdSb).append('\n');
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return sb.toString().trim();
    }

    public static List<ColgramAiContextEngine.ChatSnippet> collectActiveChatSnippets(ChatActivity chat, int maxMessages) {
        return collectActiveChatSnippets(chat, maxMessages, true);
    }

    public static List<ColgramAiContextEngine.ChatSnippet> collectActiveChatSnippets(ChatActivity chat, int maxMessages, boolean allowDbFallback) {
        if (chat == null) {
            return Collections.emptyList();
        }
        int account = chat.getCurrentAccount();
        long dialogId = chat.getDialogId();
        String chatTitle = resolveChatTitle(chat);
        MessagesController mc = MessagesController.getInstance(account);
        int limit = Math.max(30, maxMessages);

        // Merge live in-memory messages WITH SQLite messages_v2 rows (queried on storageQueue) so channels
        // & scrolled-up chats expose cached posts to Koliti safely without racing SQLite across threads.
        final java.util.LinkedHashMap<Integer, ColgramAiContextEngine.ChatSnippet> byMid = new java.util.LinkedHashMap<>();

        if (chat.messages != null && !chat.messages.isEmpty()) {
            ArrayList<MessageObject> copy = new ArrayList<>(chat.messages);
            int take = Math.min(copy.size(), limit);
            for (int i = take - 1; i >= 0; i--) {
                MessageObject m = copy.get(i);
                if (m == null || m.messageOwner == null) continue;
                if (m.isDateObject || m.type == MessageObject.TYPE_DATE || m.messageOwner.action != null) continue;
                String text = extractMessageText(m);
                String mediaKind = describeMediaKind(m);
                if (text.isEmpty() && mediaKind.isEmpty()) continue;
                byMid.put(m.getId(), new ColgramAiContextEngine.ChatSnippet(
                        dialogId,
                        chatTitle,
                        resolveSenderName(mc, m),
                        m.messageOwner.date,
                        text,
                        m.isOutOwner(),
                        mediaKind
                ));
            }
        }

        // Supplement with SQLite messages_v2 history on storageQueue when needed
        if (allowDbFallback && byMid.size() < Math.min(12, limit)) {
            querySnippetsOnStorageQueue(account, dialogId, chatTitle, Math.min(60, limit), byMid, true);
        }

        List<ColgramAiContextEngine.ChatSnippet> list = new ArrayList<>(byMid.values());
        Collections.sort(list, (a, b) -> Integer.compare(a.dateSec, b.dateSec));
        if (list.size() > limit) {
            list = new ArrayList<>(list.subList(list.size() - limit, list.size()));
        }
        return list;
    }

    private static void querySnippetsOnStorageQueue(
            final int account,
            final long dialogId,
            final String chatTitle,
            final int rowLimit,
            final java.util.LinkedHashMap<Integer, ColgramAiContextEngine.ChatSnippet> byMid,
            final boolean includeWebPage
    ) {
        try {
            final MessagesStorage storage = MessagesStorage.getInstance(account);
            if (storage == null || storage.getStorageQueue() == null) return;
            final MessagesController mc = MessagesController.getInstance(account);
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            Runnable dbTask = () -> {
                SQLiteCursor cursor = null;
                try {
                    SQLiteDatabase db = storage.getDatabase();
                    if (db == null) return;
                    cursor = db.queryFinalized("SELECT mid, data, date, out FROM messages_v2 WHERE uid = " + dialogId
                            + " ORDER BY date DESC, mid DESC LIMIT " + rowLimit);
                    while (cursor.next()) {
                        int mid = cursor.intValue(0);
                        synchronized (byMid) {
                            if (byMid.containsKey(mid)) continue;
                        }
                        NativeByteBuffer data = cursor.byteBufferValue(1);
                        int dateSec = cursor.intValue(2);
                        boolean out = cursor.intValue(3) != 0;
                        if (data == null) continue;
                        try {
                            TLRPC.Message msg = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                            if (msg == null || msg.action != null) continue;
                            String text = msg.message != null ? msg.message.trim() : "";
                            if (includeWebPage && msg.media instanceof TLRPC.TL_messageMediaWebPage && msg.media.webpage != null) {
                                TLRPC.WebPage wp = msg.media.webpage;
                                if (!TextUtils.isEmpty(wp.title) || !TextUtils.isEmpty(wp.description)) {
                                    String wpInfo = (wp.title != null ? wp.title : "")
                                            + (wp.description != null ? " — " + wp.description : "");
                                    text = text.isEmpty() ? wpInfo.trim() : (text + " [" + wpInfo.trim() + "]");
                                }
                            }
                            String kind = describeTlMediaKind(msg);
                            if (text.isEmpty() && kind.isEmpty()) continue;
                            String sender = out ? "Вы" : resolveTlSenderName(mc, msg, chatTitle);
                            synchronized (byMid) {
                                byMid.put(mid, new ColgramAiContextEngine.ChatSnippet(
                                        dialogId, chatTitle, sender, dateSec > 0 ? dateSec : msg.date, text, out, kind));
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            data.reuse();
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (cursor != null) cursor.dispose();
                    latch.countDown();
                }
            };
            if (Thread.currentThread() == storage.getStorageQueue()) {
                dbTask.run();
            } else {
                storage.getStorageQueue().postRunnable(dbTask);
                long timeoutMs = (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) ? 350L : 1500L;
                latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Throwable ignored) {}
    }

    private static String extractMessageText(MessageObject m) {
        CharSequence txtSeq = !TextUtils.isEmpty(m.messageText) ? m.messageText : m.caption;
        String text = txtSeq != null ? txtSeq.toString().trim() : "";
        if (text.isEmpty() && m.messageOwner.message != null) {
            text = m.messageOwner.message.trim();
        }
        if (m.messageOwner.media instanceof TLRPC.TL_messageMediaWebPage) {
            TLRPC.WebPage wp = m.messageOwner.media.webpage;
            if (wp != null && (!TextUtils.isEmpty(wp.title) || !TextUtils.isEmpty(wp.description))) {
                String wpInfo = (wp.title != null ? wp.title : "") + (wp.description != null ? " — " + wp.description : "");
                text = text.isEmpty() ? wpInfo.trim() : (text + " [" + wpInfo.trim() + "]");
            }
        }
        return text;
    }

    private static String describeTlMediaKind(TLRPC.Message msg) {
        if (msg == null || msg.media == null) return "";
        if (msg.media instanceof TLRPC.TL_messageMediaPhoto) return "Фото";
        if (msg.media instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.Document d = msg.media.document;
            if (d != null && d.mime_type != null) {
                if (d.mime_type.startsWith("video/")) return "Видео";
                if (d.mime_type.startsWith("audio/")) return "Аудио";
            }
            return "Файл";
        }
        return "";
    }

    private static String resolveTlSenderName(MessagesController mc, TLRPC.Message msg, String fallbackTitle) {
        if (msg != null && msg.from_id != null) {
            if (msg.from_id.user_id != 0) {
                TLRPC.User u = mc.getUser(msg.from_id.user_id);
                if (u != null) return UserObject.getUserName(u);
            } else if (msg.from_id.channel_id != 0) {
                TLRPC.Chat c = mc.getChat(msg.from_id.channel_id);
                if (c != null && !TextUtils.isEmpty(c.title)) return c.title;
            }
        }
        return TextUtils.isEmpty(fallbackTitle) ? "Канал" : fallbackTitle;
    }

    private static String loadSingleMessageText(ChatActivity chat, int account, long dialogId, int mid) {
        if (dialogId == 0 || mid <= 0) return "";
        if (chat != null && chat.messages != null) {
            for (int i = 0; i < chat.messages.size(); i++) {
                MessageObject m = chat.messages.get(i);
                if (m != null && m.getId() == mid) {
                    String txt = extractMessageText(m);
                    if (!TextUtils.isEmpty(txt)) return txt;
                }
            }
        }
        final String[] holder = new String[]{""};
        try {
            final MessagesStorage storage = MessagesStorage.getInstance(account);
            if (storage == null || storage.getStorageQueue() == null) return "";
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            Runnable dbTask = () -> {
                SQLiteCursor cursor = null;
                try {
                    SQLiteDatabase db = storage.getDatabase();
                    if (db == null) return;
                    cursor = db.queryFinalized("SELECT data FROM messages_v2 WHERE uid = " + dialogId + " AND mid = " + mid + " LIMIT 1");
                    if (cursor.next()) {
                        NativeByteBuffer data = cursor.byteBufferValue(0);
                        if (data != null) {
                            try {
                                TLRPC.Message msg = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                if (msg != null && !TextUtils.isEmpty(msg.message)) {
                                    holder[0] = msg.message.trim();
                                }
                            } finally {
                                data.reuse();
                            }
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (cursor != null) cursor.dispose();
                    latch.countDown();
                }
            };
            if (Thread.currentThread() == storage.getStorageQueue()) {
                dbTask.run();
            } else {
                storage.getStorageQueue().postRunnable(dbTask);
                latch.await((android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) ? 250L : 1000L, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Throwable ignored) {}
        return holder[0];
    }

    public static List<String> collectRecentChatMediaPaths(ChatActivity chat, int maxFiles) {
        if (chat == null || chat.messages == null || chat.messages.isEmpty()) {
            return Collections.emptyList();
        }
        FileLoader loader = FileLoader.getInstance(chat.getCurrentAccount());
        List<String> paths = new ArrayList<>();
        ArrayList<MessageObject> copy = new ArrayList<>(chat.messages);
        for (int i = 0; i < copy.size() && paths.size() < maxFiles; i++) {
            MessageObject m = copy.get(i);
            if (m == null || m.messageOwner == null) continue;
            if (!m.isPhoto() && !m.isVideo()) continue;
            try {
                File f = null;
                if (!TextUtils.isEmpty(m.messageOwner.attachPath)) {
                    File af = new File(m.messageOwner.attachPath);
                    if (af.isFile() && af.length() > 0) f = af;
                }
                if (f == null) {
                    File mf = loader.getPathToMessage(m.messageOwner);
                    if (mf != null && mf.isFile() && mf.length() > 0) f = mf;
                }
                if (f == null && m.messageOwner.media != null && m.messageOwner.media.photo != null
                        && m.messageOwner.media.photo.sizes != null) {
                    for (int sIdx = m.messageOwner.media.photo.sizes.size() - 1; sIdx >= 0; sIdx--) {
                        TLRPC.PhotoSize ps = m.messageOwner.media.photo.sizes.get(sIdx);
                        if (ps == null || ps instanceof TLRPC.TL_photoStrippedSize || ps instanceof TLRPC.TL_photoPathSize) continue;
                        File pf = loader.getPathToAttach(ps, true);
                        if (pf != null && pf.isFile() && pf.length() > 0) {
                            f = pf;
                            break;
                        }
                    }
                }
                if (f != null && f.isFile() && f.length() > 0) {
                    paths.add(f.getAbsolutePath());
                }
            } catch (Throwable ignored) {}
        }
        return paths;
    }

    /**
     * Collects recent messages across ALL dialogs (both in-memory dialogMessage and top dialog summary).
     */
    public static List<ColgramAiContextEngine.ChatSnippet> collectCrossChatSnippets(int account, long excludeDialogId, int maxSnippets) {
        List<ColgramAiContextEngine.ChatSnippet> result = new ArrayList<>();
        MessagesController mc = MessagesController.getInstance(account);
        if (mc == null) return result;

        ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
        if (dialogs != null) {
            int limitDialogs = Math.min(dialogs.size(), 45);
            for (int i = 0; i < limitDialogs && result.size() < maxSnippets; i++) {
                TLRPC.Dialog d = dialogs.get(i);
                if (d == null || d.id == excludeDialogId) continue;
                String title = resolveDialogTitle(mc, d.id);
                ArrayList<MessageObject> msgs = mc.dialogMessage != null ? mc.dialogMessage.get(d.id) : null;
                if (msgs != null) {
                    for (int j = 0; j < Math.min(msgs.size(), 4) && result.size() < maxSnippets; j++) {
                        MessageObject m = msgs.get(j);
                        if (m == null || m.messageOwner == null) continue;
                        if (m.isDateObject || m.type == MessageObject.TYPE_DATE || m.messageOwner.action != null) continue;
                        CharSequence cs = !TextUtils.isEmpty(m.messageText) ? m.messageText : m.caption;
                        String text = cs != null ? cs.toString().trim() : "";
                        String kind = describeMediaKind(m);
                        if (text.isEmpty() && kind.isEmpty()) continue;
                        if (d.unread_count > 0 && j == 0) {
                            text = "[непрочит.: " + d.unread_count + "] " + text;
                        }
                        result.add(new ColgramAiContextEngine.ChatSnippet(
                                d.id, title, resolveSenderName(mc, m), m.messageOwner.date, text, m.isOutOwner(), kind));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Collects a compact, grounded directory of real Telegram dialogs (exact title, @username, type,
     * unread count, tiny last-message preview — deliberately omitting online/offline status so the LLM
     * never refuses to message offline contacts).
     */
    public static String collectDialogDirectorySummary(int account, long activeDialogId, int maxDialogs) {
        MessagesController mc = MessagesController.getInstance(account);
        if (mc == null) return "";
        StringBuilder sb = new StringBuilder();
        java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
        int count = 0;
        int cap = Math.max(8, Math.min(30, maxDialogs));

        ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
        if (dialogs != null) {
            for (int i = 0; i < dialogs.size() && count < cap; i++) {
                TLRPC.Dialog d = dialogs.get(i);
                if (d == null || !seen.add(d.id)) continue;
                String title = resolveDialogTitle(mc, d.id);
                if (TextUtils.isEmpty(title)) continue;
                String uname = "";
                String kind = "Чат";
                if (d.id > 0) {
                    TLRPC.User u = mc.getUser(d.id);
                    if (u != null) {
                        if (u.self) kind = "Заметки";
                        else if (u.bot) kind = "Бот";
                        else kind = "ЛС";
                        if (!TextUtils.isEmpty(u.username)) uname = u.username.trim();
                    }
                } else {
                    TLRPC.Chat c = mc.getChat(-d.id);
                    if (c != null) {
                        kind = (c.broadcast && !c.megagroup) ? "Канал" : "Группа";
                        if (!TextUtils.isEmpty(c.username)) uname = c.username.trim();
                    }
                }
                sb.append("• ").append(title);
                if (!uname.isEmpty()) {
                    sb.append(" (@").append(uname).append(')');
                }
                sb.append(" [").append(kind);
                if (d.id == activeDialogId && activeDialogId != 0L) {
                    sb.append(", открыт");
                }
                if (d.unread_count > 0) {
                    sb.append(", непрочит.: ").append(d.unread_count);
                }
                sb.append(']');

                ArrayList<MessageObject> msgs = mc.dialogMessage != null ? mc.dialogMessage.get(d.id) : null;
                if (msgs != null && !msgs.isEmpty()) {
                    MessageObject last = msgs.get(0);
                    if (last != null && last.messageOwner != null && last.messageOwner.action == null) {
                        String preview = extractMessageText(last).replace('\n', ' ').trim();
                        if (!preview.isEmpty()) {
                            if (preview.length() > 42) preview = preview.substring(0, 41) + "…";
                            sb.append(" — «").append(preview).append('»');
                        }
                    }
                }
                sb.append('\n');
                count++;
            }
        }

        // Always ensure Избранное (Saved Messages) is discoverable
        long selfId = UserConfig.getInstance(account).getClientUserId();
        if (selfId != 0L && seen.add(selfId)) {
            sb.append("• Избранное [Заметки]\n");
        }
        return sb.toString().trim();
    }

    /**
     * Reads recent messages from ANY Telegram chat by name/@username/fuzzy match (in-memory + SQLite messages_v2)
     * for Koliti's ReAct [[TOOL:READ_CHAT|имя|кол-во]] observation step.
     */
    public static ColgramAiEngine.ToolResult readChatMessagesByTarget(int account, String targetQuery, int limit) {
        long targetDid = resolveTargetDialogId(account, targetQuery);
        if (targetDid == 0L) {
            return new ColgramAiEngine.ToolResult(
                    false, true, false, "READ_CHAT", "",
                    "Чат «" + targetQuery + "» не найден. Доступные чаты: " + listShortDialogNames(account, 12));
        }
        MessagesController mc = MessagesController.getInstance(account);
        String resolvedTitle = resolveDialogTitle(mc, targetDid);
        int cap = Math.max(3, Math.min(25, limit));
        java.util.LinkedHashMap<Integer, ColgramAiContextEngine.ChatSnippet> byMid = new java.util.LinkedHashMap<>();

        if (mc != null && mc.dialogMessage != null) {
            ArrayList<MessageObject> memMsgs = mc.dialogMessage.get(targetDid);
            if (memMsgs != null) {
                for (int i = 0; i < Math.min(memMsgs.size(), cap); i++) {
                    MessageObject m = memMsgs.get(i);
                    if (m == null || m.messageOwner == null || m.messageOwner.action != null) continue;
                    String txt = extractMessageText(m);
                    String mk = describeMediaKind(m);
                    if (txt.isEmpty() && mk.isEmpty()) continue;
                    byMid.put(m.getId(), new ColgramAiContextEngine.ChatSnippet(
                            targetDid, resolvedTitle, resolveSenderName(mc, m), m.messageOwner.date, txt, m.isOutOwner(), mk));
                }
            }
        }
        querySnippetsOnStorageQueue(account, targetDid, resolvedTitle, cap, byMid, false);

        List<ColgramAiContextEngine.ChatSnippet> list = new ArrayList<>(byMid.values());
        Collections.sort(list, (a, b) -> Integer.compare(a.dateSec, b.dateSec));
        if (list.size() > cap) {
            list = new ArrayList<>(list.subList(list.size() - cap, list.size()));
        }
        if (list.isEmpty()) {
            return new ColgramAiEngine.ToolResult(
                    true, true, false, "READ_CHAT", resolvedTitle,
                    "В чате «" + resolvedTitle + "» пока нет кэшированных сообщений.");
        }
        String formatted = ColgramAiContextEngine.formatChatContext(list, false);
        if (formatted.length() > 1400) {
            formatted = formatted.substring(formatted.length() - 1400);
        }
        return new ColgramAiEngine.ToolResult(
                true, true, false, "READ_CHAT", resolvedTitle,
                "Чат «" + resolvedTitle + "» (последние " + list.size() + " сообщений):\n" + formatted);
    }

    private static String listShortDialogNames(int account, int maxNames) {
        MessagesController mc = MessagesController.getInstance(account);
        if (mc == null) return "Избранное";
        ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        if (dialogs != null) {
            for (int i = 0; i < dialogs.size() && count < maxNames; i++) {
                TLRPC.Dialog d = dialogs.get(i);
                if (d == null) continue;
                String t = resolveDialogTitle(mc, d.id);
                if (TextUtils.isEmpty(t)) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(t);
                count++;
            }
        }
        return sb.length() == 0 ? "Избранное" : sb.toString();
    }

    /**
     * Structured synchronous tool executor for Koliti's ReAct Agent Loop.
     * Always returns an honest ToolResult with verified delivery status & canonical resolved target.
     */
    public static ColgramAiEngine.ToolResult executeToolWithResult(
            BaseFragment navFragment,
            Activity activity,
            int account,
            String actionId
    ) {
        if (actionId == null || actionId.isEmpty()) {
            return new ColgramAiEngine.ToolResult(false, false, false, "", "", "Пустая команда.");
        }
        BaseFragment presenter = navFragment != null ? navFragment : resolveTopFragment(LaunchActivity.instance);
        try {
            if ("LIST_CHATS".equals(actionId)) {
                ChatActivity active = resolveActiveChat(presenter);
                long activeId = active != null ? active.getDialogId() : 0L;
                String dir = collectDialogDirectorySummary(account, activeId, 26);
                return new ColgramAiEngine.ToolResult(true, true, false, "LIST_CHATS", "",
                        dir.isEmpty() ? "Список чатов пока пуст." : dir);
            }
            if (actionId.startsWith("READ_CHAT:")) {
                String payload = actionId.substring("READ_CHAT:".length());
                int sep = payload.indexOf('|');
                String target = sep > 0 ? payload.substring(0, sep).trim() : payload.trim();
                int limit = 12;
                if (sep > 0 && sep + 1 < payload.length()) {
                    try { limit = Integer.parseInt(payload.substring(sep + 1).trim()); } catch (NumberFormatException ignored) {}
                }
                return readChatMessagesByTarget(account, target, limit);
            }
            if (actionId.startsWith("SEND_TO_CHAT:")) {
                String payload = actionId.substring("SEND_TO_CHAT:".length());
                int sep = payload.indexOf('|');
                if (sep <= 0 || sep + 1 >= payload.length()) {
                    return new ColgramAiEngine.ToolResult(false, false, false, "SEND_TO_CHAT", "", "Не указан адресат или текст.");
                }
                String target = payload.substring(0, sep).trim();
                String rawText = payload.substring(sep + 1).trim();
                long targetDid = resolveTargetDialogId(account, target);
                if (targetDid == 0L) {
                    return new ColgramAiEngine.ToolResult(false, false, false, "SEND_TO_CHAT", "",
                            "Чат «" + target + "» не найден. Доступные чаты: " + listShortDialogNames(account, 12));
                }
                String cleanText = ColgramAiAgentTools.cleanMessagePayload(
                        ColgramAiContextEngine.extractCleanDraftText(rawText));
                if (cleanText.isEmpty()) {
                    return new ColgramAiEngine.ToolResult(false, false, false, "SEND_TO_CHAT", "", "Пустой текст сообщения.");
                }
                String resolvedTitle = resolveDialogTitle(MessagesController.getInstance(account), targetDid);
                SendMessagesHelper.getInstance(account).sendMessage(
                        SendMessagesHelper.SendMessageParams.of(cleanText, targetDid));
                if (activity != null) {
                    Toast.makeText(activity, "✓ Отправлено в «" + resolvedTitle + "»", Toast.LENGTH_SHORT).show();
                }
                return new ColgramAiEngine.ToolResult(
                        true, false, false, "SEND_TO_CHAT", resolvedTitle,
                        "Отправлено в чат «" + resolvedTitle + "»: «" + cleanText + "»");
            }
            if (actionId.startsWith("DRAFT_TO_CHAT:")) {
                String payload = actionId.substring("DRAFT_TO_CHAT:".length());
                int sep = payload.indexOf('|');
                if (sep <= 0 || sep + 1 >= payload.length()) {
                    return new ColgramAiEngine.ToolResult(false, false, false, "DRAFT_TO_CHAT", "", "Не указан чат или текст.");
                }
                String target = payload.substring(0, sep).trim();
                String draftText = payload.substring(sep + 1).trim();
                long targetDid = resolveTargetDialogId(account, target);
                if (targetDid == 0L) {
                    return new ColgramAiEngine.ToolResult(false, false, false, "DRAFT_TO_CHAT", "",
                            "Чат «" + target + "» не найден. Доступные чаты: " + listShortDialogNames(account, 12));
                }
                String resolvedTitle = resolveDialogTitle(MessagesController.getInstance(account), targetDid);
                boolean ok = openChatAndDraft(presenter, activity, account, target, draftText);
                String cleanDraft = ColgramAiAgentTools.cleanMessagePayload(draftText);
                return new ColgramAiEngine.ToolResult(
                        ok, false, ok, "DRAFT_TO_CHAT", resolvedTitle,
                        ok ? ("Открыл «" + resolvedTitle + "» и вставил текст: «" + cleanDraft + "»")
                                : ("Не удалось открыть чат «" + resolvedTitle + "»."));
            }
            if (actionId.startsWith("OPEN_CHAT:")) {
                String target = actionId.substring("OPEN_CHAT:".length()).trim();
                long targetDid = resolveTargetDialogId(account, target);
                if (targetDid == 0L) {
                    return new ColgramAiEngine.ToolResult(false, false, false, "OPEN_CHAT", "",
                            "Чат «" + target + "» не найден. Доступные чаты: " + listShortDialogNames(account, 12));
                }
                String resolvedTitle = resolveDialogTitle(MessagesController.getInstance(account), targetDid);
                boolean ok = openChatByNameOrUsername(presenter, account, target);
                return new ColgramAiEngine.ToolResult(
                        ok, false, ok, "OPEN_CHAT", resolvedTitle,
                        ok ? ("Открыт чат «" + resolvedTitle + "».") : ("Не удалось открыть чат «" + resolvedTitle + "»."));
            }
            boolean ok = executeInAppAction(presenter, activity, account, actionId);
            boolean dismiss = ok && actionId.startsWith("OPEN_");
            return new ColgramAiEngine.ToolResult(ok, false, dismiss, actionId, "",
                    ok ? "" : ("Не удалось выполнить команду " + actionId));
        } catch (Throwable t) {
            return new ColgramAiEngine.ToolResult(false, false, false, actionId, "",
                    "Ошибка инструмента: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
        }
    }

    public static void insertDraftIntoChat(ChatActivity chat, String text, boolean append) {
        if (chat == null || chat.chatActivityEnterView == null || text == null) return;
        String clean = ColgramAiContextEngine.extractCleanDraftText(text);
        if (clean.isEmpty()) return;
        CharSequence existing = chat.chatActivityEnterView.getFieldText();
        String finalText;
        if (append && existing != null && existing.length() > 0) {
            finalText = existing.toString().trim() + "\n" + clean;
        } else {
            finalText = clean;
        }
        chat.chatActivityEnterView.setFieldText(finalText);
        if (chat.chatActivityEnterView.getEditField() != null) {
            try {
                chat.chatActivityEnterView.getEditField().requestFocus();
                chat.chatActivityEnterView.getEditField().setSelection(finalText.length());
            } catch (Throwable ignored) {}
        }
    }

    public static boolean executeInAppAction(BaseFragment navFragment, Activity activity, int account, String actionId) {
        if (actionId == null || actionId.isEmpty()) return false;
        BaseFragment presenter = navFragment != null ? navFragment : resolveTopFragment(LaunchActivity.instance);
        try {
            if (actionId.startsWith("SEND_TO_CHAT:")) {
                String payload = actionId.substring("SEND_TO_CHAT:".length());
                int sep = payload.indexOf('|');
                if (sep > 0 && sep + 1 < payload.length()) {
                    String target = payload.substring(0, sep).trim();
                    String sendText = payload.substring(sep + 1).trim();
                    return openChatAndSend(presenter, activity, account, target, sendText);
                }
                return false;
            }
            if (actionId.startsWith("DRAFT_TO_CHAT:")) {
                String payload = actionId.substring("DRAFT_TO_CHAT:".length());
                int sep = payload.indexOf('|');
                if (sep > 0 && sep + 1 < payload.length()) {
                    String target = payload.substring(0, sep).trim();
                    String draftText = payload.substring(sep + 1).trim();
                    return openChatAndDraft(presenter, activity, account, target, draftText);
                }
                return false;
            }
            if (actionId.startsWith("OPEN_CHAT:")) {
                String query = actionId.substring("OPEN_CHAT:".length()).trim();
                return openChatByNameOrUsername(presenter, account, query);
            }
            if (actionId.startsWith("SET_BOT_NAME:")) {
                String newName = actionId.substring("SET_BOT_NAME:".length()).trim();
                if (activity != null && !newName.isEmpty()) {
                    ColgramBotSync.updateBotName(activity, account, newName);
                    return true;
                }
                return false;
            }
            if (actionId.startsWith("SET_BOT_BIO:")) {
                String newBio = actionId.substring("SET_BOT_BIO:".length()).trim();
                if (activity != null && !newBio.isEmpty()) {
                    ColgramBotSync.updateBotDescription(activity, account, newBio, null);
                    return true;
                }
                return false;
            }
            switch (actionId) {
                case "OPEN_STUDIO":
                    if (presenter != null) presenter.presentFragment(new ColgramAiActivity());
                    return true;
                case "OPEN_SETTINGS":
                    if (presenter != null) presenter.presentFragment(new ColgramSettingsActivity());
                    return true;
                case "OPEN_WALLPAPERS":
                    if (presenter != null) presenter.presentFragment(new WallpapersListActivity(WallpapersListActivity.TYPE_ALL));
                    return true;
                case "OPEN_PROXY":
                    if (presenter != null) presenter.presentFragment(new ProxyListActivity());
                    return true;
                case "OPEN_TEMPMAIL":
                    if (presenter != null) presenter.presentFragment(new ColgramTempMailActivity());
                    return true;
                case "OPEN_DEFENDER":
                    if (presenter != null) presenter.presentFragment(new ColgramDefenderActivity());
                    return true;
                case "OPEN_ANTISPAM":
                    if (presenter != null) presenter.presentFragment(new ColgramAntiSpamActivity());
                    return true;
                case "OPEN_VERSIONS":
                    if (presenter != null) presenter.presentFragment(new ColgramVersionsActivity());
                    return true;
                case "OPEN_PLUGINS":
                    if (presenter != null) presenter.presentFragment(new ColgramPluginsActivity());
                    return true;
                case "OPEN_SAVED":
                    if (presenter != null) {
                        Bundle args = new Bundle();
                        args.putLong("user_id", UserConfig.getInstance(account).getClientUserId());
                        presenter.presentFragment(new ChatActivity(args));
                    }
                    return true;
                case "EXPORT_CHAT": {
                    ChatActivity active = resolveActiveChat(presenter);
                    if (active != null && activity != null) {
                        final long did = active.getDialogId();
                        new Thread(() -> {
                            File out = org.colgram.core.ColgramChatExport.exportDialog(did);
                            AndroidUtilities.runOnUIThread(() -> Toast.makeText(activity,
                                    out != null ? ("Экспорт готов: " + out.getName()) : "В кэше нет сообщений для экспорта",
                                    Toast.LENGTH_LONG).show());
                        }).start();
                        return true;
                    }
                    return false;
                }
                case "SYNC_BOT":
                    if (activity != null) {
                        ColgramBotSync.syncBotDialogs(activity, account, true);
                    }
                    return true;
                case "SET_THEME_DARK":
                case "SET_THEME_LIGHT":
                case "TOGGLE_THEME": {
                    boolean toDark = "SET_THEME_DARK".equals(actionId)
                            || ("TOGGLE_THEME".equals(actionId) && !Theme.isCurrentThemeDark());
                    Theme.ThemeInfo ti = Theme.getTheme(toDark ? "Night" : "Day");
                    if (ti != null) {
                        Theme.applyTheme(ti, toDark);
                        if (LaunchActivity.instance != null) {
                            LaunchActivity.colgramSyncAllPanesAfterTheme(ti, toDark);
                        } else {
                            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didSetNewTheme, toDark, true, false);
                        }
                    }
                    return true;
                }
                case "REFRESH_THEME": {
                    Theme.ThemeInfo cur = Theme.getCurrentTheme();
                    if (cur != null) {
                        boolean night = Theme.isCurrentThemeNight();
                        Theme.applyTheme(cur, night);
                        if (LaunchActivity.instance != null) {
                            LaunchActivity.colgramSyncAllPanesAfterTheme(cur, night);
                        } else {
                            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didSetNewTheme, night, true, false);
                        }
                    }
                    return true;
                }
                case "EASTER_EGG":
                    if (activity != null) {
                        ColgramEasterEggs.showMatrixOverdriveSheet(presenter);
                    }
                    return true;
                default:
                    return false;
            }
        } catch (Throwable t) {
            if (activity != null) {
                Toast.makeText(activity, "Не удалось выполнить действие", Toast.LENGTH_SHORT).show();
            }
            return false;
        }
    }

    private static boolean openChatAndSend(BaseFragment presenter, Activity activity, int account, String targetQuery, String rawText) {
        if (TextUtils.isEmpty(targetQuery) || TextUtils.isEmpty(rawText)) return false;
        long targetDid = resolveTargetDialogId(account, targetQuery);
        if (targetDid == 0) {
            if (activity != null) {
                Toast.makeText(activity, "Чат «" + targetQuery + "» не найден", Toast.LENGTH_SHORT).show();
            }
            return false;
        }
        final String cleanText = org.colgram.core.ColgramAiAgentTools.cleanMessagePayload(
                ColgramAiContextEngine.extractCleanDraftText(rawText));
        if (cleanText.isEmpty()) return false;
        ChatActivity active = resolveActiveChat(presenter);
        boolean switched = false;
        if (presenter != null && (active == null || active.getDialogId() != targetDid)) {
            Bundle args = new Bundle();
            if (targetDid > 0) args.putLong("user_id", targetDid);
            else args.putLong("chat_id", -targetDid);
            presenter.presentFragment(new ChatActivity(args));
            switched = true;
        }
        final String resolvedTitle = resolveDialogTitle(MessagesController.getInstance(account), targetDid);
        Runnable doSend = () -> {
            try {
                SendMessagesHelper.getInstance(account).sendMessage(
                        SendMessagesHelper.SendMessageParams.of(cleanText, targetDid));
                if (activity != null) {
                    Toast.makeText(activity, "Отправлено в «" + resolvedTitle + "»", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable err) {
                if (activity != null) {
                    Toast.makeText(activity, "Не удалось отправить в «" + resolvedTitle + "»", Toast.LENGTH_SHORT).show();
                }
            }
        };
        if (switched) {
            AndroidUtilities.runOnUIThread(doSend, 200L);
        } else {
            doSend.run();
        }
        return true;
    }

    private static boolean openChatAndDraft(BaseFragment presenter, Activity activity, int account, String targetQuery, String draftText) {
        if (presenter == null || TextUtils.isEmpty(targetQuery) || TextUtils.isEmpty(draftText)) return false;
        final String cleanDraft = org.colgram.core.ColgramAiAgentTools.cleanMessagePayload(draftText);
        if (cleanDraft.isEmpty()) return false;
        long targetDid = resolveTargetDialogId(account, targetQuery);
        if (targetDid == 0) {
            if (activity != null) {
                Toast.makeText(activity, "Чат «" + targetQuery + "» не найден", Toast.LENGTH_SHORT).show();
            }
            return false;
        }
        ChatActivity active = resolveActiveChat(presenter);
        if (active != null && active.getDialogId() == targetDid) {
            insertDraftIntoChat(active, cleanDraft, false);
            return true;
        }
        Bundle args = new Bundle();
        if (targetDid > 0) args.putLong("user_id", targetDid);
        else args.putLong("chat_id", -targetDid);
        final ChatActivity nextChat = new ChatActivity(args);
        presenter.presentFragment(nextChat);
        AndroidUtilities.runOnUIThread(() -> {
            if (nextChat.chatActivityEnterView != null) {
                insertDraftIntoChat(nextChat, cleanDraft, false);
            } else {
                AndroidUtilities.runOnUIThread(() -> insertDraftIntoChat(nextChat, cleanDraft, false), 420L);
            }
        }, 240L);
        return true;
    }

    private static long resolveTargetDialogId(int account, String query) {
        if (TextUtils.isEmpty(query)) return 0L;
        String clean = query.startsWith("@") ? query.substring(1).trim() : query.trim();
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("избран") || lower.contains("избра")
                || lower.contains("сохранен") || lower.contains("сохранён")
                || lower.contains("заметк") || lower.equals("saved")
                || lower.contains("saved messages") || lower.equals("себе") || lower.equals("мне")) {
            return UserConfig.getInstance(account).getClientUserId();
        }
        MessagesController mc = MessagesController.getInstance(account);
        if (mc == null) return 0L;

        long bestDid = 0L;
        double bestScore = 0.0;

        // 1. Score all active/recent dialogs (private, bots, groups, channels) with exact + translit + fuzzy matcher
        ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
        if (dialogs != null) {
            for (int i = 0; i < dialogs.size(); i++) {
                TLRPC.Dialog d = dialogs.get(i);
                if (d == null) continue;
                String title = resolveDialogTitle(mc, d.id);
                String uname = "";
                if (d.id > 0) {
                    TLRPC.User u = mc.getUser(d.id);
                    if (u != null && u.username != null) uname = u.username;
                } else {
                    TLRPC.Chat c = mc.getChat(-d.id);
                    if (c != null && c.username != null) uname = c.username;
                }
                double s = ColgramAiAgentTools.scoreTargetMatch(clean, title, uname);
                if (s > bestScore) {
                    bestScore = s;
                    bestDid = d.id;
                    if (bestScore >= 0.99) return bestDid;
                }
            }
        }

        // 2. Also check Telegram ContactsController in case contact has no recent dialog yet
        try {
            ArrayList<TLRPC.TL_contact> contacts = ContactsController.getInstance(account).contacts;
            if (contacts != null) {
                for (int i = 0; i < contacts.size(); i++) {
                    TLRPC.TL_contact c = contacts.get(i);
                    if (c == null) continue;
                    TLRPC.User u = mc.getUser(c.user_id);
                    if (u == null) continue;
                    String title = UserObject.getUserName(u);
                    String uname = u.username != null ? u.username : "";
                    double s = ColgramAiAgentTools.scoreTargetMatch(clean, title, uname);
                    if (s > bestScore) {
                        bestScore = s;
                        bestDid = u.id;
                        if (bestScore >= 0.99) return bestDid;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 3. Fallback scan of cached users in memory (useful on bot accounts with synced contacts)
        if (bestScore < 0.85) {
            try {
                java.util.concurrent.ConcurrentHashMap<Long, TLRPC.User> users = mc.getUsers();
                if (users != null) {
                    for (TLRPC.User u : users.values()) {
                        if (u == null || u.self) continue;
                        String title = UserObject.getUserName(u);
                        String uname = u.username != null ? u.username : "";
                        double s = ColgramAiAgentTools.scoreTargetMatch(clean, title, uname);
                        if (s > bestScore) {
                            bestScore = s;
                            bestDid = u.id;
                            if (bestScore >= 0.99) return bestDid;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        return bestScore >= 0.72 ? bestDid : 0L;
    }

    private static String normalizeFuzzy(String s) {
        if (s == null) return "";
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]", "");
    }

    private static boolean openChatByNameOrUsername(BaseFragment presenter, int account, String query) {
        if (presenter == null || TextUtils.isEmpty(query)) return false;
        long targetDid = resolveTargetDialogId(account, query);
        if (targetDid == 0) return false;
        Bundle args = new Bundle();
        if (targetDid > 0) args.putLong("user_id", targetDid);
        else args.putLong("chat_id", -targetDid);
        presenter.presentFragment(new ChatActivity(args));
        return true;
    }

    private static String resolveDialogTitle(MessagesController mc, long did) {
        if (did > 0) {
            TLRPC.User u = mc.getUser(did);
            if (u != null) {
                if (u.self) return "Избранное";
                return UserObject.getUserName(u);
            }
        } else {
            TLRPC.Chat c = mc.getChat(-did);
            if (c != null && !TextUtils.isEmpty(c.title)) return c.title;
        }
        return "Чат " + did;
    }

    private static String resolveSenderName(MessagesController mc, MessageObject m) {
        if (m.isOutOwner()) return "Вы";
        long from = m.getFromChatId();
        if (from > 0) {
            TLRPC.User u = mc.getUser(from);
            if (u != null) return UserObject.getUserName(u);
        } else if (from < 0) {
            TLRPC.Chat c = mc.getChat(-from);
            if (c != null && !TextUtils.isEmpty(c.title)) return c.title;
        }
        return "Собеседник";
    }

    private static String describeMediaKind(MessageObject m) {
        if (m.isVideo()) return "Видео";
        if (m.isPhoto()) return "Фото";
        if (m.isVoice()) return "Голосовое";
        if (m.isDocument()) return "Файл";
        return "";
    }

    /**
     * Detects the user's primary chat/profile language ("ru" or "en") for Koliti's voice greeting & speech:
     * examines recent user messages across chats, active chat text, user's Telegram profile name/bio,
     * and falls back to the active app/device locale.
     */
    public static String detectUserLanguage(int account, ChatActivity activeChat) {
        List<String> ownMsgs = new ArrayList<>();
        List<String> otherMsgs = new ArrayList<>();
        StringBuilder profileBuf = new StringBuilder();
        try {
            long activeDid = activeChat != null ? activeChat.getDialogId() : 0L;
            if (activeChat != null) {
                List<ColgramAiContextEngine.ChatSnippet> active = collectActiveChatSnippets(activeChat, 30, false);
                for (ColgramAiContextEngine.ChatSnippet s : active) {
                    if (s == null || TextUtils.isEmpty(s.text)) continue;
                    if (s.outgoing) ownMsgs.add(s.text);
                    else otherMsgs.add(s.text);
                }
            }
            List<ColgramAiContextEngine.ChatSnippet> cross = collectCrossChatSnippets(account, activeDid, 40);
            for (ColgramAiContextEngine.ChatSnippet s : cross) {
                if (s == null || TextUtils.isEmpty(s.text)) continue;
                if (s.outgoing) ownMsgs.add(s.text);
                else otherMsgs.add(s.text);
            }
            TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
            if (me != null) {
                if (!TextUtils.isEmpty(me.first_name)) profileBuf.append(me.first_name).append(' ');
                if (!TextUtils.isEmpty(me.last_name)) profileBuf.append(me.last_name).append(' ');
                TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(me.id);
                if (full != null && !TextUtils.isEmpty(full.about)) {
                    profileBuf.append(full.about).append(' ');
                }
            }
        } catch (Throwable ignored) {}

        String appLocale = "";
        try {
            java.util.Locale loc = org.telegram.messenger.LocaleController.getInstance().getCurrentLocale();
            if (loc != null) appLocale = loc.getLanguage();
        } catch (Throwable ignored) {}
        if (TextUtils.isEmpty(appLocale)) {
            appLocale = java.util.Locale.getDefault().getLanguage();
        }
        return org.colgram.core.ColgramKolitiVoice.detectPreferredLanguageCode(
                ownMsgs, otherMsgs, profileBuf.toString(), appLocale
        );
    }
}
