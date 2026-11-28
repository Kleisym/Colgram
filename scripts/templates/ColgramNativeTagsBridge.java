package org.telegram.ui;

import android.text.TextUtils;

import org.colgram.core.ColgramPremiumConfig;
import org.colgram.core.ColgramSavedTagsStore;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 100% native bridge between Telegram's Saved Messages tag pipeline and ColgramSavedTagsStore.
 * Eliminates duplicate tag UI while persisting tags/labels locally for non-Premium accounts.
 */
public final class ColgramNativeTagsBridge {

    private ColgramNativeTagsBridge() {}

    public static String reactionToKey(TLRPC.Reaction reaction) {
        if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            long docId = ((TLRPC.TL_reactionCustomEmoji) reaction).document_id;
            return docId != 0L ? ColgramSavedTagsStore.normalizeEmojiKey("doc:" + docId) : "";
        }
        if (reaction instanceof TLRPC.TL_reactionEmoji) {
            return ColgramSavedTagsStore.normalizeEmojiKey(((TLRPC.TL_reactionEmoji) reaction).emoticon);
        }
        return "";
    }

    public static String visibleReactionToKey(ReactionsLayoutInBubble.VisibleReaction vr) {
        if (vr == null) {
            return "";
        }
        if (vr.documentId != 0L) {
            return ColgramSavedTagsStore.normalizeEmojiKey("doc:" + vr.documentId);
        }
        if (!TextUtils.isEmpty(vr.emojicon)) {
            return ColgramSavedTagsStore.normalizeEmojiKey(vr.emojicon);
        }
        return "";
    }

    public static TLRPC.Reaction keyToTLReaction(String key) {
        String norm = ColgramSavedTagsStore.normalizeEmojiKey(key);
        if (norm.isEmpty()) {
            return null;
        }
        if (norm.startsWith("doc:")) {
            try {
                long docId = Long.parseLong(norm.substring(4));
                if (docId == 0L) {
                    return null;
                }
                TLRPC.TL_reactionCustomEmoji r = new TLRPC.TL_reactionCustomEmoji();
                r.document_id = docId;
                return r;
            } catch (Throwable ignored) {
                return null;
            }
        }
        TLRPC.TL_reactionEmoji r = new TLRPC.TL_reactionEmoji();
        r.emoticon = norm;
        return r;
    }

    public static void mergeIntoMessage(int account, TLRPC.Message msg) {
        if (msg == null) {
            return;
        }
        mergeIntoMessage(account, MessageObject.getDialogId(msg), msg);
    }

    public static void mergeIntoMessage(int account, long dialogId, TLRPC.Message msg) {
        if (msg == null || msg.id <= 0 || !ColgramPremiumConfig.isSavedTagsEnabled()) {
            return;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        if (selfId == 0L || dialogId != selfId) {
            return;
        }
        List<String> keys = ColgramSavedTagsStore.getTags(account, msg.id);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        if (msg.reactions == null) {
            msg.reactions = new TLRPC.TL_messageReactions();
        }
        if (msg.reactions.results == null) {
            msg.reactions.results = new ArrayList<>();
        }
        msg.reactions.reactions_as_tags = true;
        msg.flags |= 1048576;

        Set<String> present = new HashSet<>();
        int maxOrder = 0;
        for (int i = 0; i < msg.reactions.results.size(); i++) {
            TLRPC.ReactionCount rc = msg.reactions.results.get(i);
            if (rc == null) continue;
            if (rc.chosen_order > maxOrder) {
                maxOrder = rc.chosen_order;
            }
            String existingKey = reactionToKey(rc.reaction);
            if (!existingKey.isEmpty()) {
                present.add(existingKey);
                if (keys.contains(existingKey)) {
                    rc.chosen = true;
                    if (rc.count <= 0) {
                        rc.count = 1;
                    }
                }
            }
        }
        for (String key : keys) {
            if (present.contains(key)) {
                continue;
            }
            TLRPC.Reaction tl = keyToTLReaction(key);
            if (tl == null) {
                continue;
            }
            TLRPC.TL_reactionCount rc = new TLRPC.TL_reactionCount();
            rc.reaction = tl;
            rc.count = 1;
            rc.chosen = true;
            rc.chosen_order = ++maxOrder;
            msg.reactions.results.add(rc);
            present.add(key);
        }
    }

    public static TLRPC.TL_messages_savedReactionsTags mergeIntoSavedReactionTags(
            int account,
            long topicId,
            TLRPC.TL_messages_savedReactionsTags base) {
        if (!ColgramPremiumConfig.isSavedTagsEnabled()) {
            return base;
        }
        List<ColgramSavedTagsStore.TagCount> localCounts = ColgramSavedTagsStore.getTagCounts(account, topicId);
        if ((base == null || base.tags == null || base.tags.isEmpty())
                && (localCounts == null || localCounts.isEmpty())) {
            return base;
        }

        Map<String, TLRPC.TL_savedReactionTag> merged = new LinkedHashMap<>();
        if (base != null && base.tags != null) {
            for (int i = 0; i < base.tags.size(); i++) {
                TLRPC.TL_savedReactionTag src = base.tags.get(i);
                if (src == null || src.reaction == null) continue;
                String key = reactionToKey(src.reaction);
                if (key.isEmpty()) continue;
                TLRPC.TL_savedReactionTag copy = new TLRPC.TL_savedReactionTag();
                copy.reaction = src.reaction;
                copy.count = src.count;
                copy.flags = src.flags;
                copy.title = src.title;
                String localLabel = ColgramSavedTagsStore.getTagLabel(account, key);
                if (!TextUtils.isEmpty(localLabel)) {
                    copy.flags |= 1;
                    copy.title = localLabel;
                }
                merged.put(key, copy);
            }
        }

        if (localCounts != null) {
            for (ColgramSavedTagsStore.TagCount tc : localCounts) {
                if (tc == null || tc.count <= 0 || TextUtils.isEmpty(tc.emoji)) {
                    continue;
                }
                TLRPC.TL_savedReactionTag existing = merged.get(tc.emoji);
                String localLabel = ColgramSavedTagsStore.getTagLabel(account, tc.emoji);
                if (TextUtils.isEmpty(localLabel)) {
                    localLabel = tc.label;
                }
                if (existing != null) {
                    if (tc.count > existing.count) {
                        existing.count = tc.count;
                    }
                    if (!TextUtils.isEmpty(localLabel) && TextUtils.isEmpty(existing.title)) {
                        existing.flags |= 1;
                        existing.title = localLabel;
                    }
                } else {
                    TLRPC.Reaction tl = keyToTLReaction(tc.emoji);
                    if (tl == null) {
                        continue;
                    }
                    TLRPC.TL_savedReactionTag tag = new TLRPC.TL_savedReactionTag();
                    tag.reaction = tl;
                    tag.count = tc.count;
                    if (!TextUtils.isEmpty(localLabel)) {
                        tag.flags |= 1;
                        tag.title = localLabel;
                    }
                    merged.put(tc.emoji, tag);
                }
            }
        }

        TLRPC.TL_messages_savedReactionsTags out = new TLRPC.TL_messages_savedReactionsTags();
        if (base != null) {
            out.hash = base.hash;
        }
        out.tags = new ArrayList<>(merged.values());
        Collections.sort(out.tags, (a, b) -> Integer.compare(b.count, a.count));
        return out;
    }

    public static void handleLocalSavedReaction(
            int account,
            MessageObject msgObj,
            ArrayList<ReactionsLayoutInBubble.VisibleReaction> visibleReactions,
            Runnable callback) {
        if (msgObj == null || msgObj.getId() <= 0) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(callback);
            }
            return;
        }
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        if (selfId == 0L) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(callback);
            }
            return;
        }
        final int msgId = msgObj.getId();
        final long topicId = msgObj.messageOwner != null
                ? MessageObject.getSavedDialogId(selfId, msgObj.messageOwner)
                : 0L;

        List<String> keys = new ArrayList<>();
        TLRPC.TL_messageReactions localReactions = new TLRPC.TL_messageReactions();
        localReactions.reactions_as_tags = true;
        localReactions.results = new ArrayList<>();

        if (visibleReactions != null) {
            Set<String> seenKeys = new LinkedHashSet<>();
            int order = 0;
            for (int i = 0; i < visibleReactions.size(); i++) {
                ReactionsLayoutInBubble.VisibleReaction vr = visibleReactions.get(i);
                String key = visibleReactionToKey(vr);
                if (key.isEmpty() || !seenKeys.add(key)) {
                    continue;
                }
                keys.add(key);
                TLRPC.TL_reactionCount rc = new TLRPC.TL_reactionCount();
                rc.reaction = vr.toTLReaction();
                rc.count = 1;
                rc.chosen = true;
                rc.chosen_order = ++order;
                localReactions.results.add(rc);
            }
        }

        ColgramSavedTagsStore.setMessageTags(ApplicationLoader.applicationContext, account, msgId, topicId, keys);
        if (msgObj.messageOwner != null) {
            MessageObject.updateReactions(msgObj.messageOwner, localReactions);
        }
        MessagesStorage.getInstance(account).updateMessageReactions(selfId, msgId, localReactions);

        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(account).postNotificationName(
                    NotificationCenter.didUpdateReactions, selfId, msgId, localReactions);
            MessagesController mc = MessagesController.getInstance(account);
            mc.getSavedReactionTags(0L, true);
            if (topicId != 0L) {
                mc.getSavedReactionTags(topicId, true);
            }
            NotificationCenter.getInstance(account).postNotificationName(
                    NotificationCenter.savedReactionTagsUpdate, 0L);
            if (topicId != 0L) {
                NotificationCenter.getInstance(account).postNotificationName(
                        NotificationCenter.savedReactionTagsUpdate, topicId);
            }
            if (callback != null) {
                callback.run();
            }
        });
    }

    public static void syncServerReactionTags(
            int account,
            MessageObject msgObj,
            ArrayList<ReactionsLayoutInBubble.VisibleReaction> visibleReactions) {
        if (msgObj == null || msgObj.getId() <= 0 || !ColgramPremiumConfig.isSavedTagsEnabled()) {
            return;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        if (selfId == 0L || msgObj.getDialogId() != selfId) {
            return;
        }
        long topicId = msgObj.messageOwner != null
                ? MessageObject.getSavedDialogId(selfId, msgObj.messageOwner)
                : 0L;
        List<String> keys = new ArrayList<>();
        if (visibleReactions != null) {
            for (int i = 0; i < visibleReactions.size(); i++) {
                String k = visibleReactionToKey(visibleReactions.get(i));
                if (!k.isEmpty() && !keys.contains(k)) {
                    keys.add(k);
                }
            }
        }
        ColgramSavedTagsStore.setMessageTags(ApplicationLoader.applicationContext, account, msgObj.getId(), topicId, keys);
    }

    public static ArrayList<Integer> mergeLocalTagIdsIntoSearch(
            int account,
            long topicId,
            TLRPC.Reaction reaction,
            ArrayList<Integer> existingIds) {
        ArrayList<Integer> added = new ArrayList<>();
        if (reaction == null || !ColgramPremiumConfig.isSavedTagsEnabled()) {
            return added;
        }
        String key = reactionToKey(reaction);
        if (key.isEmpty()) {
            return added;
        }
        List<Integer> localIds = ColgramSavedTagsStore.getTaggedMessageIds(account, topicId, key);
        if (localIds == null || localIds.isEmpty()) {
            return added;
        }
        Set<Integer> seen = existingIds != null ? new HashSet<>(existingIds) : Collections.emptySet();
        for (int i = 0; i < localIds.size(); i++) {
            Integer id = localIds.get(i);
            if (id != null && id > 0 && !seen.contains(id)) {
                added.add(id);
                if (existingIds != null) {
                    existingIds.add(id);
                }
            }
        }
        return added;
    }
}
