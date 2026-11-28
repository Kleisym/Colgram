package org.telegram.ui;

import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single canonical engine for read-receipt eligibility, 15s-TTL coherent caching,
 * 3-tier group viewer resolution (memory -> RPC -> SQLite + placeholder), and
 * graceful DM read-date handling shared across ALL 6 Colgram/Telegram surfaces.
 */
public final class ColgramReadReceiptsHelper {

    public static final long CACHE_TTL_MS = 15_000L;
    public static final String TEXT_NOT_READ_YET = "Ещё не прочитано";

    public static final class ViewerEntry {
        public final long peerId;
        public final TLObject obj;
        public final int date;

        public ViewerEntry(long peerId, TLObject obj, int date) {
            this.peerId = peerId;
            this.obj = obj;
            this.date = date;
        }
    }

    public static final class GroupSeenResult {
        public final ArrayList<ViewerEntry> viewers;
        public final String errorText;
        public final long fetchedAtMs;

        public GroupSeenResult(ArrayList<ViewerEntry> viewers, String errorText, long fetchedAtMs) {
            this.viewers = viewers != null ? viewers : new ArrayList<>();
            this.errorText = errorText;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    public static final class PrivateSeenResult {
        public static final int STATUS_OK = 0;
        public static final int STATUS_MESSAGE_NOT_READ = 1;
        public static final int STATUS_USER_PRIVACY_RESTRICTED = 2;
        public static final int STATUS_YOUR_PRIVACY_RESTRICTED = 3;
        public static final int STATUS_ERROR = 4;

        public final int status;
        public final int date;
        public final TLRPC.TL_error rawError;
        public final long fetchedAtMs;

        public PrivateSeenResult(int status, int date, TLRPC.TL_error rawError, long fetchedAtMs) {
            this.status = status;
            this.date = date;
            this.rawError = rawError;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    private static final ConcurrentHashMap<String, GroupSeenResult> GROUP_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, PrivateSeenResult> PRIVATE_CACHE = new ConcurrentHashMap<>();
    private static final Set<Integer> OBSERVED_ACCOUNTS = ConcurrentHashMap.newKeySet();

    private ColgramReadReceiptsHelper() {}

    private static String cacheKey(int account, long dialogId, int msgId) {
        return account + ":" + dialogId + ":" + msgId;
    }

    private static void ensureObservers(int currentAccount) {
        if (!OBSERVED_ACCOUNTS.add(currentAccount)) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
            NotificationCenter.NotificationCenterDelegate del = (id, account, args) -> {
                if (id == NotificationCenter.messagesRead) {
                    invalidateAccount(currentAccount);
                } else if (id == NotificationCenter.didUpdateReactions && args != null && args.length >= 2 && args[0] instanceof Long) {
                    invalidateDialog(currentAccount, (Long) args[0]);
                }
            };
            nc.addObserver(del, NotificationCenter.messagesRead);
            nc.addObserver(del, NotificationCenter.didUpdateReactions);
        });
    }

    public static void invalidateAccount(int account) {
        String prefix = account + ":";
        for (String k : GROUP_CACHE.keySet()) {
            if (k.startsWith(prefix)) {
                GROUP_CACHE.remove(k);
            }
        }
        for (String k : PRIVATE_CACHE.keySet()) {
            if (k.startsWith(prefix)) {
                PRIVATE_CACHE.remove(k);
            }
        }
    }

    public static void invalidateDialog(int account, long dialogId) {
        String prefix = account + ":" + dialogId + ":";
        for (String k : GROUP_CACHE.keySet()) {
            if (k.startsWith(prefix)) {
                GROUP_CACHE.remove(k);
            }
        }
        for (String k : PRIVATE_CACHE.keySet()) {
            if (k.startsWith(prefix)) {
                PRIVATE_CACHE.remove(k);
            }
        }
    }

    public static void invalidateMessage(int account, long dialogId, int msgId) {
        String k = cacheKey(account, dialogId, msgId);
        GROUP_CACHE.remove(k);
        PRIVATE_CACHE.remove(k);
    }

    public static boolean canShowReadReceipts(
            int currentAccount,
            MessageObject message,
            TLRPC.Chat chat,
            TLRPC.User user) {
        if (message == null) {
            return false;
        }
        MessagesController mc = MessagesController.getInstance(currentAccount);
        TLRPC.ChatFull chatInfo = chat != null ? mc.getChatFull(chat.id) : null;
        TLRPC.UserFull userInfo = user != null ? mc.getUserFull(user.id) : null;
        return canShowReadReceipts(currentAccount, message, chat, chatInfo, user, userInfo, message.scheduled, 0);
    }

    public static boolean canShowReadReceiptsForMessage(int currentAccount, MessageObject message) {
        if (message == null) {
            return false;
        }
        long dialogId = message.getDialogId();
        MessagesController mc = MessagesController.getInstance(currentAccount);
        TLRPC.Chat chat = dialogId < 0 ? mc.getChat(-dialogId) : null;
        TLRPC.User user = dialogId > 0 ? mc.getUser(dialogId) : null;
        return canShowReadReceipts(currentAccount, message, chat, user);
    }

    public static boolean canShowReadReceipts(
            int currentAccount,
            MessageObject message,
            TLRPC.Chat chat,
            TLRPC.ChatFull chatInfo,
            TLRPC.User user,
            TLRPC.UserFull userInfo,
            boolean isInScheduleMode,
            int chatMode) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        if (isInScheduleMode || chatMode == ChatActivity.MODE_SAVED || chatMode == ChatActivity.MODE_QUICK_REPLIES) {
            return false;
        }
        long dialogId = message.getDialogId();
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return false;
        }
        if (!message.isOutOwner() || !message.isSent() || message.isEditing()
                || message.isSending() || message.isSendError()
                || message.getId() <= 0 || message.scheduled) {
            return false;
        }
        if (message.isEphemeral() || message.isSecretMedia()) {
            return false;
        }
        if (message.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByRequest) {
            return false;
        }
        boolean circleOrVoice = message.isRoundVideo() || message.isVoice();
        if (message.isContentUnread() && !circleOrVoice) {
            return false;
        }
        MessagesController mc = MessagesController.getInstance(currentAccount);
        if (!mc.isOutboxMessageRead(dialogId, message.getId(), message.isUnread())) {
            return false;
        }
        int age = ConnectionsManager.getInstance(currentAccount).getCurrentTime() - message.messageOwner.date;
        if (chat != null) {
            if (ChatObject.isChannel(chat) && !ChatObject.isMegagroup(chat)) {
                return false;
            }
            if (ChatObject.isMonoForum(chat)) {
                return false;
            }
            if (!message.canSetReaction()) {
                return false;
            }
            if (age >= mc.chatReadMarkExpirePeriod) {
                return false;
            }
            TLRPC.ChatFull resolvedChatInfo = chatInfo != null ? chatInfo : mc.getChatFull(chat.id);
            if (resolvedChatInfo != null && resolvedChatInfo.participants_count > mc.chatReadMarkSizeThreshold) {
                return false;
            }
            return true;
        }
        if (user != null) {
            if (UserObject.isUserSelf(user)
                    || UserObject.isReplyUser(user)
                    || UserObject.isAnonymous(user)
                    || user.bot
                    || UserObject.isService(user.id)) {
                return false;
            }
            if (age >= mc.pmReadDateExpirePeriod) {
                return false;
            }
            TLRPC.UserFull resolvedUserInfo = userInfo != null ? userInfo : mc.getUserFull(user.id);
            if (resolvedUserInfo != null && resolvedUserInfo.read_dates_private) {
                return false;
            }
            return true;
        }
        return false;
    }

    public static int loadGroupReadParticipants(
            int currentAccount,
            MessageObject message,
            TLRPC.Chat chat,
            boolean forceRefresh,
            Utilities.Callback<GroupSeenResult> callback) {
        if (message == null || message.messageOwner == null) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(new GroupSeenResult(null, null, 0L)));
            }
            return 0;
        }
        ensureObservers(currentAccount);
        final long dialogId = message.getDialogId();
        final int msgId = message.getId();
        final String key = cacheKey(currentAccount, dialogId, msgId);
        if (!forceRefresh) {
            GroupSeenResult cached = GROUP_CACHE.get(key);
            if (cached != null && cached.errorText == null
                    && (SystemClock.elapsedRealtime() - cached.fetchedAtMs) < CACHE_TTL_MS) {
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(() -> callback.run(cached));
                }
                return 0;
            }
        }

        final MessagesController mc = MessagesController.getInstance(currentAccount);
        final long fromId = message.messageOwner.from_id != null ? message.messageOwner.from_id.user_id : 0L;
        TLRPC.TL_messages_getMessageReadParticipants req = new TLRPC.TL_messages_getMessageReadParticipants();
        req.msg_id = msgId;
        req.peer = mc.getInputPeer(dialogId);

        return ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            if (error != null || !(response instanceof Vector)) {
                String errTxt = error != null && error.text != null ? error.text : "ERROR";
                GroupSeenResult errRes = new GroupSeenResult(null, errTxt, SystemClock.elapsedRealtime());
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(() -> callback.run(errRes));
                }
                return;
            }

            Vector vector = (Vector) response;
            final ArrayList<Long> orderedPeerIds = new ArrayList<>();
            final ArrayList<Integer> orderedDates = new ArrayList<>();
            final Set<Long> seenPeerIds = new HashSet<>();
            final Map<Long, TLObject> resolved = new HashMap<>();
            final ArrayList<Long> missingUsers = new ArrayList<>();

            for (int i = 0, n = vector.objects.size(); i < n; i++) {
                Object obj = vector.objects.get(i);
                long peerId = 0L;
                int date = 0;
                if (obj instanceof TLRPC.TL_readParticipantDate) {
                    TLRPC.TL_readParticipantDate pd = (TLRPC.TL_readParticipantDate) obj;
                    peerId = pd.user_id;
                    date = pd.date;
                } else if (obj instanceof Long) {
                    peerId = (Long) obj;
                }
                if (peerId == 0L || peerId == fromId || !seenPeerIds.add(peerId)) {
                    continue;
                }
                orderedPeerIds.add(peerId);
                orderedDates.add(date);
                // Tier 1: resolve immediately from in-memory MessagesController cache
                TLObject cachedObj = peerId > 0 ? mc.getUser(peerId) : mc.getChat(-peerId);
                if (cachedObj != null) {
                    resolved.put(peerId, cachedObj);
                } else if (peerId > 0) {
                    missingUsers.add(peerId);
                }
            }

            Runnable finishTier3AndEmit = () -> resolveFromStorageAndFinish(
                    currentAccount, mc, key, orderedPeerIds, orderedDates, resolved, callback);

            if (missingUsers.isEmpty() || chat == null) {
                finishTier3AndEmit.run();
                return;
            }

            // Tier 2: only when memory cache is missing IDs, request participants / full chat
            if (ChatObject.isChannel(chat)) {
                TLRPC.TL_channels_getParticipants usersReq = new TLRPC.TL_channels_getParticipants();
                usersReq.limit = Math.max(100, mc.chatReadMarkSizeThreshold);
                usersReq.offset = 0;
                usersReq.filter = new TLRPC.TL_channelParticipantsRecent();
                usersReq.channel = mc.getInputChannel(chat.id);
                ConnectionsManager.getInstance(currentAccount).sendRequest(usersReq, (r1, e1) -> {
                    if (r1 instanceof TLRPC.TL_channels_channelParticipants) {
                        TLRPC.TL_channels_channelParticipants cp = (TLRPC.TL_channels_channelParticipants) r1;
                        if (cp.users != null) {
                            mc.putUsers(cp.users, false);
                            for (int i = 0; i < cp.users.size(); i++) {
                                TLRPC.User u = cp.users.get(i);
                                if (u != null) {
                                    resolved.put(u.id, u);
                                }
                            }
                        }
                    }
                    finishTier3AndEmit.run();
                }, ConnectionsManager.RequestFlagInvokeAfter);
            } else {
                TLRPC.TL_messages_getFullChat usersReq = new TLRPC.TL_messages_getFullChat();
                usersReq.chat_id = chat.id;
                ConnectionsManager.getInstance(currentAccount).sendRequest(usersReq, (r1, e1) -> {
                    if (r1 instanceof TLRPC.TL_messages_chatFull) {
                        TLRPC.TL_messages_chatFull cf = (TLRPC.TL_messages_chatFull) r1;
                        if (cf.users != null) {
                            mc.putUsers(cf.users, false);
                            for (int i = 0; i < cf.users.size(); i++) {
                                TLRPC.User u = cf.users.get(i);
                                if (u != null) {
                                    resolved.put(u.id, u);
                                }
                            }
                        }
                    }
                    finishTier3AndEmit.run();
                }, ConnectionsManager.RequestFlagInvokeAfter);
            }
        }, ConnectionsManager.RequestFlagInvokeAfter);
    }

    private static void resolveFromStorageAndFinish(
            int currentAccount,
            MessagesController mc,
            String cacheKey,
            ArrayList<Long> orderedPeerIds,
            ArrayList<Integer> orderedDates,
            Map<Long, TLObject> resolved,
            Utilities.Callback<GroupSeenResult> callback) {
        final ArrayList<Long> stillMissingUsers = new ArrayList<>();
        final ArrayList<Long> stillMissingChats = new ArrayList<>();
        for (int i = 0; i < orderedPeerIds.size(); i++) {
            long peerId = orderedPeerIds.get(i);
            if (resolved.containsKey(peerId)) {
                continue;
            }
            TLObject mem = peerId > 0 ? mc.getUser(peerId) : mc.getChat(-peerId);
            if (mem != null) {
                resolved.put(peerId, mem);
            } else if (peerId > 0) {
                stillMissingUsers.add(peerId);
            } else {
                stillMissingChats.add(-peerId);
            }
        }

        Runnable buildAndCallback = () -> {
            ArrayList<ViewerEntry> entries = new ArrayList<>(orderedPeerIds.size());
            for (int i = 0; i < orderedPeerIds.size(); i++) {
                long peerId = orderedPeerIds.get(i);
                TLObject obj = resolved.get(peerId);
                if (obj == null) {
                    obj = createPlaceholderObject(peerId);
                }
                entries.add(new ViewerEntry(peerId, obj, orderedDates.get(i)));
            }
            GroupSeenResult result = new GroupSeenResult(entries, null, SystemClock.elapsedRealtime());
            GROUP_CACHE.put(cacheKey, result);
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(result));
            }
        };

        if (stillMissingUsers.isEmpty() && stillMissingChats.isEmpty()) {
            buildAndCallback.run();
            return;
        }

        // Tier 3: resolve remaining viewers from SQLite MessagesStorage on storageQueue
        final MessagesStorage ms = MessagesStorage.getInstance(currentAccount);
        ms.getStorageQueue().postRunnable(() -> {
            if (!stillMissingUsers.isEmpty()) {
                ArrayList<TLRPC.User> dbUsers = ms.getUsers(stillMissingUsers);
                if (dbUsers != null && !dbUsers.isEmpty()) {
                    mc.putUsers(dbUsers, true);
                    for (int i = 0; i < dbUsers.size(); i++) {
                        TLRPC.User u = dbUsers.get(i);
                        if (u != null) {
                            resolved.put(u.id, u);
                        }
                    }
                }
            }
            if (!stillMissingChats.isEmpty()) {
                ArrayList<TLRPC.Chat> dbChats = ms.getChats(stillMissingChats);
                if (dbChats != null && !dbChats.isEmpty()) {
                    mc.putChats(dbChats, true);
                    for (int i = 0; i < dbChats.size(); i++) {
                        TLRPC.Chat c = dbChats.get(i);
                        if (c != null) {
                            resolved.put(-c.id, c);
                        }
                    }
                }
            }
            buildAndCallback.run();
        });
    }

    private static TLObject createPlaceholderObject(long peerId) {
        if (peerId > 0) {
            TLRPC.TL_user placeholder = new TLRPC.TL_user();
            placeholder.id = peerId;
            placeholder.first_name = "Пользователь #" + peerId;
            placeholder.last_name = "";
            return placeholder;
        }
        TLRPC.TL_chat placeholder = new TLRPC.TL_chat();
        placeholder.id = -peerId;
        placeholder.title = "Чат #" + (-peerId);
        return placeholder;
    }

    public static int loadPrivateReadDate(
            int currentAccount,
            long dialogId,
            int msgId,
            boolean forceRefresh,
            Utilities.Callback<PrivateSeenResult> callback) {
        if (dialogId == 0L || msgId <= 0) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() ->
                        callback.run(new PrivateSeenResult(PrivateSeenResult.STATUS_MESSAGE_NOT_READ, 0, null, 0L)));
            }
            return 0;
        }
        ensureObservers(currentAccount);
        final String key = cacheKey(currentAccount, dialogId, msgId);
        if (!forceRefresh) {
            PrivateSeenResult cached = PRIVATE_CACHE.get(key);
            if (cached != null && (SystemClock.elapsedRealtime() - cached.fetchedAtMs) < CACHE_TTL_MS) {
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(() -> callback.run(cached));
                }
                return 0;
            }
        }

        TLRPC.TL_messages_getOutboxReadDate req = new TLRPC.TL_messages_getOutboxReadDate();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.msg_id = msgId;
        return ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            long now = SystemClock.elapsedRealtime();
            final PrivateSeenResult out;
            if (err != null) {
                String txt = err.text != null ? err.text : "";
                if (txt.contains("MESSAGE_NOT_READ")) {
                    out = new PrivateSeenResult(PrivateSeenResult.STATUS_MESSAGE_NOT_READ, 0, err, now);
                } else if ("USER_PRIVACY_RESTRICTED".equals(txt)) {
                    out = new PrivateSeenResult(PrivateSeenResult.STATUS_USER_PRIVACY_RESTRICTED, 0, err, now);
                    PRIVATE_CACHE.put(key, out);
                } else if ("YOUR_PRIVACY_RESTRICTED".equals(txt)) {
                    out = new PrivateSeenResult(PrivateSeenResult.STATUS_YOUR_PRIVACY_RESTRICTED, 0, err, now);
                } else {
                    out = new PrivateSeenResult(PrivateSeenResult.STATUS_ERROR, 0, err, now);
                }
            } else if (res instanceof TLRPC.TL_outboxReadDate) {
                TLRPC.TL_outboxReadDate r = (TLRPC.TL_outboxReadDate) res;
                out = new PrivateSeenResult(PrivateSeenResult.STATUS_OK, r.date, null, now);
                PRIVATE_CACHE.put(key, out);
            } else {
                out = new PrivateSeenResult(PrivateSeenResult.STATUS_ERROR, 0, null, now);
            }
            if (callback != null) {
                callback.run(out);
            }
        }));
    }

    public static String formatPrivateStatusText(PrivateSeenResult res) {
        if (res == null) {
            return TEXT_NOT_READ_YET;
        }
        switch (res.status) {
            case PrivateSeenResult.STATUS_OK:
                return LocaleController.formatPmSeenDate(res.date);
            case PrivateSeenResult.STATUS_MESSAGE_NOT_READ:
                return TEXT_NOT_READ_YET;
            case PrivateSeenResult.STATUS_USER_PRIVACY_RESTRICTED:
                return LocaleController.getString(R.string.PmReadUnknown);
            case PrivateSeenResult.STATUS_YOUR_PRIVACY_RESTRICTED:
                return LocaleController.getString(R.string.PmRead);
            default:
                return LocaleController.getString("UnknownError");
        }
    }
}
