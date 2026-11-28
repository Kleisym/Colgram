"""Track 2 Telegram-Src patches: Anti-Ping / 317@ elimination, Colemailer sync/errors, Hard 1 GB Cap, Lazy Shadow Vault, and Malware/Huge-File Shield."""
from __future__ import annotations

import os


def _patch_eol_variants(eol_patch_fn, filepath: str, anchors: list[str], replacement: str, desc: str) -> None:
    """Apply an idempotent EOL-normalized patch matching whichever anchor state the target file holds."""
    if os.path.exists(filepath):
        with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
            norm = f.read().replace("\r\n", "\n")
        if replacement.replace("\r\n", "\n").strip() in norm:
            eol_patch_fn(filepath, replacement, replacement, desc)
            return
        for anchor in anchors:
            if anchor.replace("\r\n", "\n") in norm:
                eol_patch_fn(filepath, anchor, replacement, desc)
                return
    eol_patch_fn(filepath, anchors[-1], replacement, desc)


def apply(repo_path: str, patch_file_fn, eol_patch_fn) -> None:
    """Apply all Track 2 storage, anti-ping, broadcaster, shadow vault, and malware shield patches cleanly and idempotently."""
    # 1. MessagesStorage.java:
    #    - Lift colgramDispatched >= 10 cap for mentions / spam guard evaluation
    #    - Intercept spam pings BEFORE putMessagesInternal() so msg.mentioned=false before dialogMentionsIdsMap
    #    - Guard download queue insertion at MessagesStorage with Malware/Huge-File shield
    messages_storage = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "MessagesStorage.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        messages_storage,
        [
            """                if (colgramDispatched >= 10) {
                    continue;
                }"""
        ],
        """                if (colgramDispatched >= 10 && !colgramMessage.mentioned && !org.colgram.core.ColgramConfig.isSpamGuardEnabled()) {
                    continue;
                }""",
        "MessagesStorage.java lift colgramDispatched cap for mentions and spam guard",
    )
    _patch_eol_variants(
        eol_patch_fn,
        messages_storage,
        [
            """                    if (downloadMask != 0 && (message.peer_id.channel_id == 0 || message.post) && message.date >= getConnectionsManager().getCurrentTime() - 15 * 60 && getDownloadController().canDownloadMedia(message) == 1) {"""
        ],
        """                    if (downloadMask != 0 && (message.peer_id.channel_id == 0 || message.post) && message.date >= getConnectionsManager().getCurrentTime() - 15 * 60 && !ColgramNativeColemailerBridge.isStorageAutoDownloadBlocked(currentAccount, message) && getDownloadController().canDownloadMedia(message) == 1) {""",
        "MessagesStorage.java enforce Malware/Huge-File shield on storage download queue",
    )
    _patch_eol_variants(
        eol_patch_fn,
        messages_storage,
        [
            """    private void colgramPreFilterSpamMentions(ArrayList<TLRPC.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        org.colgram.core.ColgramHookHandler.beginShadowCaptureBatch();
        try {
            if (getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().bot) {
                return;
            }
            for (int a = 0, n = messages.size(); a < n; a++) {
                TLRPC.Message m = messages.get(a);
                if (m == null) {
                    continue;
                }
                MessageObject.getDialogId(m);
                ColgramNativeColemailerBridge.interceptIncomingSpamPing(currentAccount, m);
            }
        } catch (Throwable ignored) {
        } finally {
            org.colgram.core.ColgramHookHandler.flushShadowCaptureBatch();
        }
    }

    public void putMessages(ArrayList<TLRPC.Message> messages, boolean withTransaction, boolean useQueue, boolean doNotUpdateDialogDate, int downloadMask, boolean ifNoLastMessage, int mode, long threadMessageId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(() -> {
                if (mode == ChatActivity.MODE_DEFAULT) {
                    colgramPreFilterSpamMentions(messages);
                    colgramDispatchPlugins(messages);
                }
                putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask, ifNoLastMessage, mode, threadMessageId);
            });
        } else {
            if (mode == ChatActivity.MODE_DEFAULT) {
                colgramPreFilterSpamMentions(messages);
                colgramDispatchPlugins(messages);
            }
            putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask, ifNoLastMessage, mode, threadMessageId);
        }
    }

    public void putMessages(ArrayList<TLRPC.Message> messages, boolean withTransaction, boolean useQueue, boolean doNotUpdateDialogDate, int downloadMask, boolean ifNoLastMessage, int mode, long threadMessageId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(() -> {
                if (mode == ChatActivity.MODE_DEFAULT) {
                    colgramPreFilterSpamMentions(messages);
                    colgramDispatchPlugins(messages);
                }
                putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask, ifNoLastMessage, mode, threadMessageId);
            });
        } else {
            if (mode == ChatActivity.MODE_DEFAULT) {
                colgramPreFilterSpamMentions(messages);
                colgramDispatchPlugins(messages);
            }
            putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask, ifNoLastMessage, mode, threadMessageId);
        }
    }

    public void processEphemeralMessages(ArrayList<TL_ephemeral.EphemeralMessage> messages, Runnable callback) {""",
            """    private void colgramPreFilterSpamMentions(ArrayList<TLRPC.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try {
            if (getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().bot) {
                return;
            }
            for (int a = 0, n = messages.size(); a < n; a++) {
                TLRPC.Message m = messages.get(a);
                if (m == null) {
                    continue;
                }
                boolean wasMentioned = m.mentioned;
                if (ColgramNativeColemailerBridge.interceptIncomingSpamPing(currentAccount, m) && wasMentioned && m.id > 0) {
                    markMentionMessageAsRead(m.dialog_id, m.id, m.dialog_id);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public void putMessages(ArrayList<TLRPC.Message> messages, boolean withTransaction, boolean useQueue, boolean doNotUpdateDialogDate, int downloadMask, boolean ifNoLastMessage, int mode, long threadMessageId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(() -> {
                if (mode == ChatActivity.MODE_DEFAULT) {
                    colgramPreFilterSpamMentions(messages);
                    colgramDispatchPlugins(messages);
                }
                putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask, ifNoLastMessage, mode, threadMessageId);
            });
        } else {
            if (mode == ChatActivity.MODE_DEFAULT) {
                colgramPreFilterSpamMentions(messages);
                colgramDispatchPlugins(messages);
            }
            putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask, ifNoLastMessage, mode, threadMessageId);
        }
    }

    public void processEphemeralMessages(ArrayList<TL_ephemeral.EphemeralMessage> messages, Runnable callback) {""",
            """    public void putMessages(ArrayList<TLRPC.Message> messages, boolean withTransaction, boolean useQueue, boolean doNotUpdateDialogDate, int downloadMask, boolean ifNoLastMessage, int mode, long threadMessageId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(() -> {
                if (mode == ChatActivity.MODE_DEFAULT) {
                    colgramDispatchPlugins(messages);
                }
                putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask, ifNoLastMessage, mode, threadMessageId);
            });
        } else {
            if (mode == ChatActivity.MODE_DEFAULT) {
                colgramDispatchPlugins(messages);
            }
            putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask, ifNoLastMessage, mode, threadMessageId);
        }
    }

    public void processEphemeralMessages(ArrayList<TL_ephemeral.EphemeralMessage> messages, Runnable callback) {""",
        ],
        """    private void colgramPreFilterSpamMentions(ArrayList<TLRPC.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        org.colgram.core.ColgramHookHandler.beginShadowCaptureBatch();
        try {
            if (getUserConfig().getCurrentUser() != null && getUserConfig().getCurrentUser().bot) {
                return;
            }
            for (int a = 0, n = messages.size(); a < n; a++) {
                TLRPC.Message m = messages.get(a);
                if (m == null) {
                    continue;
                }
                MessageObject.getDialogId(m);
                ColgramNativeColemailerBridge.interceptIncomingSpamPing(currentAccount, m);
            }
        } catch (Throwable ignored) {
        } finally {
            org.colgram.core.ColgramHookHandler.flushShadowCaptureBatch();
        }
    }

    public void putMessages(ArrayList<TLRPC.Message> messages, boolean withTransaction, boolean useQueue, boolean doNotUpdateDialogDate, int downloadMask, boolean ifNoLastMessage, int mode, long threadMessageId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        if (useQueue) {
            storageQueue.postRunnable(() -> {
                if (mode == ChatActivity.MODE_DEFAULT) {
                    colgramPreFilterSpamMentions(messages);
                    colgramDispatchPlugins(messages);
                }
                putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask, ifNoLastMessage, mode, threadMessageId);
            });
        } else {
            if (mode == ChatActivity.MODE_DEFAULT) {
                colgramPreFilterSpamMentions(messages);
                colgramDispatchPlugins(messages);
            }
            putMessagesInternal(messages, withTransaction, doNotUpdateDialogDate, downloadMask, ifNoLastMessage, mode, threadMessageId);
        }
    }

    public void processEphemeralMessages(ArrayList<TL_ephemeral.EphemeralMessage> messages, Runnable callback) {""",
        "MessagesStorage.java pre-filter incoming spam pings before putMessagesInternal",
    )

    # 2. MessagesController.java:
    #    - Reconcile unread spam mentions after TL_updates_channelDifferenceTooLong
    #    - Auto-sync Colemailer folder targets (>=30s debounced) when dialogs/filters load
    messages_controller = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "MessagesController.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        messages_controller,
        [
            """                            getMessagesStorage().overwriteChannel(channelId, (TLRPC.TL_updates_channelDifferenceTooLong) res, newDialogType, () -> AndroidUtilities.runOnUIThread(() -> {
                                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.onReceivedChannelDifference, channelId);
                            }));"""
        ],
        """                            getMessagesStorage().overwriteChannel(channelId, (TLRPC.TL_updates_channelDifferenceTooLong) res, newDialogType, () -> AndroidUtilities.runOnUIThread(() -> {
                                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.onReceivedChannelDifference, channelId);
                                ColgramNativeColemailerBridge.reconcileChannelMentionsAfterTooLong(currentAccount, channelId, dialogs_dict.get(-channelId));
                            }));""",
        "MessagesController.java reconcile blocked spam mentions after channelDifferenceTooLong",
    )
    _patch_eol_variants(
        eol_patch_fn,
        messages_controller,
        [
            """        AndroidUtilities.runOnUIThread(() -> {
            getNotificationCenter().postNotificationName(NotificationCenter.suggestedFiltersLoaded);
            getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        });"""
        ],
        """        AndroidUtilities.runOnUIThread(() -> {
            getNotificationCenter().postNotificationName(NotificationCenter.suggestedFiltersLoaded);
            getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
            ColgramNativeColemailerBridge.scheduleDebouncedFolderSync(currentAccount);
        });""",
        "MessagesController.java debounced Colemailer folder sync on filter reload",
    )
    _patch_eol_variants(
        eol_patch_fn,
        messages_controller,
        [
            """                    getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
                });
                return;
            }

            LongSparseArray<TLRPC.Dialog> new_dialogs_dict = new LongSparseArray<>();"""
        ],
        """                    getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
                    ColgramNativeColemailerBridge.scheduleDebouncedFolderSync(currentAccount);
                });
                return;
            }

            LongSparseArray<TLRPC.Dialog> new_dialogs_dict = new LongSparseArray<>();""",
        "MessagesController.java debounced Colemailer folder sync on loaded dialogs",
    )

    # 3. SendMessagesHelper.java:
    #    - Record outgoing reply/DM/mention interactions for big-group anti-ping 24h LRU
    #    - Wire FLOOD_WAIT / SLOWMODE_WAIT / CHAT_WRITE_FORBIDDEN errors to ColgramNativeColemailerBridge
    send_helper = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "SendMessagesHelper.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        send_helper,
        [
            """        final SendMessageChatArguments sendMessageChatArguments = sendMessageParams.sendMessageChatArguments != null ?"""
        ],
        """        if (sendMessageParams.replyToMsg != null && sendMessageParams.replyToMsg.messageOwner != null && sendMessageParams.replyToMsg.messageOwner.from_id != null) {
            long colgramInteractedUid = MessageObject.getPeerId(sendMessageParams.replyToMsg.messageOwner.from_id);
            if (colgramInteractedUid > 0L) {
                org.colgram.core.ColgramHookHandler.recordOutgoingUserInteraction(colgramInteractedUid);
            }
        }
        final SendMessageChatArguments sendMessageChatArguments = sendMessageParams.sendMessageChatArguments != null ?""",
        "SendMessagesHelper.java record outgoing reply target into 24h anti-ping LRU",
    )
    _patch_eol_variants(
        eol_patch_fn,
        send_helper,
        [
            """                                    AndroidUtilities.runOnUIThread(() -> {
                                        getMediaDataController().increasePeerRaiting(newMsgObj.dialog_id);
                                        processSentMessage(oldId);
                                        removeFromSendingMessages(oldId, scheduled);
                                    });"""
        ],
        """                                    ColgramNativeColemailerBridge.onOutgoingMessageSent(currentAccount, newMsgObj);
                                    AndroidUtilities.runOnUIThread(() -> {
                                        getMediaDataController().increasePeerRaiting(newMsgObj.dialog_id);
                                        processSentMessage(oldId);
                                        removeFromSendingMessages(oldId, scheduled);
                                    });""",
        "SendMessagesHelper.java record outgoing sent interactions",
    )
    _patch_eol_variants(
        eol_patch_fn,
        send_helper,
        [
            """                    if (isSentError) {
                        getMessagesStorage().markMessageAsSendError(newMsgObj, scheduled ? 1 : 0);"""
        ],
        """                    if (isSentError) {
                        if (error != null && error.text != null) {
                            ColgramNativeColemailerBridge.onSendMessageError(currentAccount, MessageObject.getDialogId(newMsgObj), error.text);
                        }
                        getMessagesStorage().markMessageAsSendError(newMsgObj, scheduled ? 1 : 0);""",
        "SendMessagesHelper.java wire FLOOD_WAIT/SLOWMODE/FORBIDDEN into Colemailer engine",
    )

    # 4. DownloadController.java:
    #    - Harden ALL 6 canDownloadMedia* overloads + processDownloadObjects + preloadVideo + TYPE_STORY + sponsoredMedia
    #    - Cap mobile auto-downloaded photo size to 600px when Lazy Shadow Vault is ON
    download_ctrl = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "DownloadController.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        download_ctrl,
        [
            """    private boolean isColgramAutoDownloadSizeOrPolicyBlocked(String fileName, String mimeType, long size) {
        org.colgram.core.ColgramShieldConfig.init(ApplicationLoader.applicationContext);
        boolean isWifi = ApplicationLoader.getAutodownloadNetworkType() == StatsController.TYPE_WIFI;
        long capBytes = org.colgram.core.ColgramShieldConfig.isStorageCapEnabled()
                ? org.colgram.core.ColgramShieldConfig.getStorageHardCapBytes() : 0L;
        return org.colgram.core.ColgramFileSafetyPolicy.shouldBlockAutoDownload(
                fileName, mimeType, size, isWifi, capBytes);
    }

    private boolean isColgramAutoDownloadBlocked(MessageObject messageObject, long overrideSize) {
        if (messageObject == null) {
            return true;
        }
        org.colgram.core.ColgramShieldConfig.init(ApplicationLoader.applicationContext);
        if (org.colgram.core.ColgramShieldConfig.isMalwareDownloadShieldEnabled()
                && messageObject.type == MessageObject.TYPE_STORY) {
            return true;
        }
        if (messageObject.sponsoredMedia != null
                && (org.colgram.core.ColgramShieldConfig.isMalwareDownloadShieldEnabled()
                        || org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled())) {
            return true;
        }
        TLRPC.Document doc = messageObject.getDocument();
        String name = doc != null ? FileLoader.getDocumentFileName(doc) : null;
        String mime = doc != null ? doc.mime_type : null;
        long sz = overrideSize >= 0L ? overrideSize : (doc != null ? doc.size : MessageObject.getMessageSize(messageObject.messageOwner));
        return isColgramAutoDownloadSizeOrPolicyBlocked(name, mime, sz);
    }

    private boolean isColgramRawMessageBlocked(TLRPC.Message message, TLRPC.MessageMedia media) {
        if (message == null) {
            return true;
        }
        org.colgram.core.ColgramShieldConfig.init(ApplicationLoader.applicationContext);
        if (org.colgram.core.ColgramShieldConfig.isMalwareDownloadShieldEnabled()
                && ((media instanceof TLRPC.TL_messageMediaStory) || (message.media instanceof TLRPC.TL_messageMediaStory))) {
            return true;
        }
        TLRPC.Document doc = media != null ? media.document : MessageObject.getDocument(message);
        String name = doc != null ? FileLoader.getDocumentFileName(doc) : null;
        String mime = doc != null ? doc.mime_type : null;
        long sz = media != null ? MessageObject.getMediaSize(media) : MessageObject.getMessageSize(message);
        return isColgramAutoDownloadSizeOrPolicyBlocked(name, mime, sz);
    }

    public boolean canDownloadMedia(MessageObject messageObject) {
        if (isColgramAutoDownloadBlocked(messageObject, -1L)) {
            return false;
        }
        if (messageObject.type == MessageObject.TYPE_STORY) {
            if (!SharedConfig.isAutoplayVideo()) return false;
            TLRPC.TL_messageMediaStory mediaStory = (TLRPC.TL_messageMediaStory) MessageObject.getMedia(messageObject);
            TL_stories.StoryItem storyItem = mediaStory.storyItem;
            if (storyItem == null || storyItem.media == null || storyItem.media.document == null || !storyItem.isPublic) {
                return false;
            }
            return true;
        }
        if (messageObject.sponsoredMedia != null) {
            return !org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled();
        }
        if (messageObject.isHiddenSensitive())
            return false;
        return canDownloadMediaInternal(messageObject) == 1;
    }

    public boolean canDownloadMedia(int type, long size) {
        if (isColgramAutoDownloadSizeOrPolicyBlocked(null, null, size)) {
            return false;
        }""",
            """    public boolean canDownloadMedia(MessageObject messageObject) {
        if (messageObject.type == MessageObject.TYPE_STORY) {
            if (!SharedConfig.isAutoplayVideo()) return false;
            TLRPC.TL_messageMediaStory mediaStory = (TLRPC.TL_messageMediaStory) MessageObject.getMedia(messageObject);
            TL_stories.StoryItem storyItem = mediaStory.storyItem;
            if (storyItem == null || storyItem.media == null || storyItem.media.document == null || !storyItem.isPublic) {
                return false;
            }
            return true;
        }
        if (messageObject.sponsoredMedia != null) {
            return true;
        }
        if (messageObject.isHiddenSensitive())
            return false;
        return canDownloadMediaInternal(messageObject) == 1;
    }

    public boolean canDownloadMedia(int type, long size) {"""
        ],
        """    private boolean isColgramAutoDownloadSizeOrPolicyBlocked(String fileName, String mimeType, long size) {
        org.colgram.core.ColgramShieldConfig.init(ApplicationLoader.applicationContext);
        boolean isWifi = ApplicationLoader.getAutodownloadNetworkType() == StatsController.TYPE_WIFI;
        long capBytes = org.colgram.core.ColgramShieldConfig.isStorageCapEnabled()
                ? org.colgram.core.ColgramShieldConfig.getStorageHardCapBytes() : 0L;
        return org.colgram.core.ColgramFileSafetyPolicy.shouldBlockAutoDownload(
                fileName, mimeType, size, isWifi, capBytes);
    }

    private boolean isColgramAutoDownloadBlocked(MessageObject messageObject, long overrideSize) {
        if (messageObject == null) {
            return true;
        }
        org.colgram.core.ColgramShieldConfig.init(ApplicationLoader.applicationContext);
        if (messageObject.sponsoredMedia != null
                && (org.colgram.core.ColgramShieldConfig.isMalwareDownloadShieldEnabled()
                        || org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled())) {
            return true;
        }
        TLRPC.Document doc = messageObject.getDocument();
        String name = doc != null ? FileLoader.getDocumentFileName(doc) : null;
        String mime = doc != null ? doc.mime_type : null;
        long sz = overrideSize >= 0L ? overrideSize : (doc != null ? doc.size : MessageObject.getMessageSize(messageObject.messageOwner));
        return isColgramAutoDownloadSizeOrPolicyBlocked(name, mime, sz);
    }

    private boolean isColgramRawMessageBlocked(TLRPC.Message message, TLRPC.MessageMedia media) {
        if (message == null) {
            return true;
        }
        org.colgram.core.ColgramShieldConfig.init(ApplicationLoader.applicationContext);
        TLRPC.Document doc = media != null ? media.document : MessageObject.getDocument(message);
        String name = doc != null ? FileLoader.getDocumentFileName(doc) : null;
        String mime = doc != null ? doc.mime_type : null;
        long sz = media != null ? MessageObject.getMediaSize(media) : MessageObject.getMessageSize(message);
        return isColgramAutoDownloadSizeOrPolicyBlocked(name, mime, sz);
    }

    public boolean canDownloadMedia(MessageObject messageObject) {
        if (isColgramAutoDownloadBlocked(messageObject, -1L)) {
            return false;
        }
        if (messageObject.type == MessageObject.TYPE_STORY) {
            if (!SharedConfig.isAutoplayVideo()) return false;
            TLRPC.TL_messageMediaStory mediaStory = (TLRPC.TL_messageMediaStory) MessageObject.getMedia(messageObject);
            TL_stories.StoryItem storyItem = mediaStory.storyItem;
            if (storyItem == null || storyItem.media == null || storyItem.media.document == null || !storyItem.isPublic) {
                return false;
            }
            return true;
        }
        if (messageObject.sponsoredMedia != null) {
            return !org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled();
        }
        if (messageObject.isHiddenSensitive())
            return false;
        return canDownloadMediaInternal(messageObject) == 1;
    }

    public boolean canDownloadMedia(int type, long size) {
        if (isColgramAutoDownloadSizeOrPolicyBlocked(null, null, size)) {
            return false;
        }""",
        "DownloadController.java harden canDownloadMedia(MessageObject) and canDownloadMedia(int, long)",
    )
    _patch_eol_variants(
        eol_patch_fn,
        download_ctrl,
        [
            """    public int canDownloadMediaType(MessageObject messageObject) {
        if (messageObject.type == MessageObject.TYPE_STORY) {
            if (!SharedConfig.isAutoplayVideo()) return 0;
            TLRPC.TL_messageMediaStory mediaStory = (TLRPC.TL_messageMediaStory) MessageObject.getMedia(messageObject);
            TL_stories.StoryItem storyItem = mediaStory.storyItem;
            if (storyItem == null || storyItem.media == null || storyItem.media.document == null || !storyItem.isPublic) {
                return 0;
            }
            return 2;
        }
        if (messageObject.sponsoredMedia != null) {
            return 2;
        }
        if (messageObject.isHiddenSensitive())
            return 0;
        return canDownloadMediaInternal(messageObject);
    }

    public int canDownloadMediaType(MessageObject messageObject, long overrideSize) {
        if (messageObject.type == MessageObject.TYPE_STORY) {
            if (!SharedConfig.isAutoplayVideo()) return 0;
            TLRPC.TL_messageMediaStory mediaStory = (TLRPC.TL_messageMediaStory) MessageObject.getMedia(messageObject);
            TL_stories.StoryItem storyItem = mediaStory.storyItem;
            if (storyItem == null || storyItem.media == null || storyItem.media.document == null || !storyItem.isPublic) {
                return 0;
            }
            return 2;
        }
        if (messageObject.sponsoredMedia != null) {
            return 2;
        }
        if (messageObject.isHiddenSensitive())
            return 0;
        return canDownloadMediaInternal(messageObject, overrideSize);
    }"""
        ],
        """    public int canDownloadMediaType(MessageObject messageObject) {
        if (isColgramAutoDownloadBlocked(messageObject, -1L)) {
            return 0;
        }
        if (messageObject.type == MessageObject.TYPE_STORY) {
            if (!SharedConfig.isAutoplayVideo()) return 0;
            TLRPC.TL_messageMediaStory mediaStory = (TLRPC.TL_messageMediaStory) MessageObject.getMedia(messageObject);
            TL_stories.StoryItem storyItem = mediaStory.storyItem;
            if (storyItem == null || storyItem.media == null || storyItem.media.document == null || !storyItem.isPublic) {
                return 0;
            }
            return 2;
        }
        if (messageObject.sponsoredMedia != null) {
            return org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled() ? 0 : 2;
        }
        if (messageObject.isHiddenSensitive())
            return 0;
        return canDownloadMediaInternal(messageObject);
    }

    public int canDownloadMediaType(MessageObject messageObject, long overrideSize) {
        if (isColgramAutoDownloadBlocked(messageObject, overrideSize)) {
            return 0;
        }
        if (messageObject.type == MessageObject.TYPE_STORY) {
            if (!SharedConfig.isAutoplayVideo()) return 0;
            TLRPC.TL_messageMediaStory mediaStory = (TLRPC.TL_messageMediaStory) MessageObject.getMedia(messageObject);
            TL_stories.StoryItem storyItem = mediaStory.storyItem;
            if (storyItem == null || storyItem.media == null || storyItem.media.document == null || !storyItem.isPublic) {
                return 0;
            }
            return 2;
        }
        if (messageObject.sponsoredMedia != null) {
            return org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled() ? 0 : 2;
        }
        if (messageObject.isHiddenSensitive())
            return 0;
        return canDownloadMediaInternal(messageObject, overrideSize);
    }""",
        "DownloadController.java harden canDownloadMediaType overloads",
    )
    _patch_eol_variants(
        eol_patch_fn,
        download_ctrl,
        [
            """        long size;
        if (message.highestQuality != null) {
            size = message.highestQuality.document.size;
        } else if (message.thumbQuality != null) {
            size = message.thumbQuality.document.size;
        } else {
            size = MessageObject.getMessageSize(msg);
        }
        if (isVideo && preset.preloadVideo && !org.colgram.core.ColgramShieldConfig.isMalwareDownloadShieldEnabled() && size > maxSize && maxSize > 2 * 1024 * 1024) {
            return (mask & type) != 0 ? 2 : 0;"""
        ],
        """        long size;
        if (message.highestQuality != null) {
            size = message.highestQuality.document.size;
        } else if (message.thumbQuality != null) {
            size = message.thumbQuality.document.size;
        } else {
            size = MessageObject.getMessageSize(msg);
        }
        if (isVideo && preset.preloadVideo && size > maxSize && maxSize > 2 * 1024 * 1024) {
            return (mask & type) != 0 ? 2 : 0;""",
        "DownloadController.java preserve preset.preloadVideo when shield enabled",
    )
    _patch_eol_variants(
        eol_patch_fn,
        download_ctrl,
        [
            """    public int canDownloadMedia(TLRPC.Message message) {
        if (message == null || message.media instanceof TLRPC.TL_messageMediaStory) {"""
        ],
        """    public int canDownloadMedia(TLRPC.Message message) {
        if (isColgramRawMessageBlocked(message, null)) {
            return 0;
        }
        if (message == null || message.media instanceof TLRPC.TL_messageMediaStory) {""",
        "DownloadController.java harden canDownloadMedia(TLRPC.Message)",
    )
    _patch_eol_variants(
        eol_patch_fn,
        download_ctrl,
        [
            """    public int canDownloadMedia(TLRPC.Message message, TLRPC.MessageMedia media) {
        if (message == null || media instanceof TLRPC.TL_messageMediaStory) {"""
        ],
        """    public int canDownloadMedia(TLRPC.Message message, TLRPC.MessageMedia media) {
        if (isColgramRawMessageBlocked(message, media)) {
            return 0;
        }
        if (message == null || media instanceof TLRPC.TL_messageMediaStory) {""",
        "DownloadController.java harden canDownloadMedia(TLRPC.Message, TLRPC.MessageMedia)",
    )
    _patch_eol_variants(
        eol_patch_fn,
        download_ctrl,
        [
            """            if (downloadObject.object instanceof TLRPC.Document) {
                TLRPC.Document document = (TLRPC.Document) downloadObject.object;
                if (isColgramAutoDownloadSizeOrPolicyBlocked(FileLoader.getDocumentFileName(document), document.mime_type, document.size)) {
                    continue;
                }
                path = FileLoader.getAttachFileName(document);
            } else if (downloadObject.object instanceof TLRPC.Photo) {
                TLRPC.Photo photo = (TLRPC.Photo) downloadObject.object;
                int photoTargetSize = org.colgram.core.ColgramShieldConfig.isLazyShadowVaultEnabled() && ApplicationLoader.getAutodownloadNetworkType() != StatsController.TYPE_WIFI
                        ? Math.min(600, AndroidUtilities.getPhotoSize())
                        : AndroidUtilities.getPhotoSize();
                photoSize = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, photoTargetSize);
                path = FileLoader.getAttachFileName(photoSize);
            }""",
            """            if (downloadObject.object instanceof TLRPC.Document) {
                TLRPC.Document document = (TLRPC.Document) downloadObject.object;
                path = FileLoader.getAttachFileName(document);
            } else if (downloadObject.object instanceof TLRPC.Photo) {
                TLRPC.Photo photo = (TLRPC.Photo) downloadObject.object;
                photoSize = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize());
                path = FileLoader.getAttachFileName(photoSize);
            }""",
        ],
        """            if (downloadObject.object instanceof TLRPC.Document) {
                TLRPC.Document document = (TLRPC.Document) downloadObject.object;
                if (isColgramAutoDownloadSizeOrPolicyBlocked(FileLoader.getDocumentFileName(document), document.mime_type, document.size)) {
                    continue;
                }
                path = FileLoader.getAttachFileName(document);
            } else if (downloadObject.object instanceof TLRPC.Photo) {
                TLRPC.Photo photo = (TLRPC.Photo) downloadObject.object;
                photoSize = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize());
                path = FileLoader.getAttachFileName(photoSize);
            }""",
        "DownloadController.java processDownloadObjects shield + full photo size",
    )

    _patch_eol_variants(
        eol_patch_fn,
        download_ctrl,
        [
            """            } else if (downloadObject.object instanceof TLRPC.Document) {
                TLRPC.Document document = (TLRPC.Document) downloadObject.object;
                getFileLoader().loadFile(document, downloadObject.parent, FileLoader.PRIORITY_LOW, downloadObject.secret ? 2 : 0);"""
        ],
        """            } else if (downloadObject.object instanceof TLRPC.Document) {
                TLRPC.Document document = (TLRPC.Document) downloadObject.object;
                getFileLoader().loadFile(document, downloadObject.parent, FileLoader.PRIORITY_LOW, downloadObject.secret ? 2 : 0, true);""",
        "DownloadController.java pass explicit auto-download flag for documents",
    )

    # 5. FileLoader.java:
    #    - Explicit auto-download gate ONLY (never block manual PRIORITY_LOW clicks) evaluated BEFORE startDownloadFile / loadOperationPathsUI
    #    - Real-time cache accounting & Lazy Shadow Vault capture on didFinishLoadingFile
    file_loader = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "FileLoader.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        file_loader,
        [
            """    public void loadFile(TLRPC.Document document, Object parentObject, int priority, int cacheType) {
        if (document == null) {
            return;
        }
        if (cacheType == 0 && document.key != null) {
            cacheType = 1;
        }
        loadFile(document, null, null, null, null, parentObject, null, 0, priority, cacheType);
    }"""
        ],
        """    public void loadFile(TLRPC.Document document, Object parentObject, int priority, int cacheType) {
        loadFile(document, parentObject, priority, cacheType, false);
    }

    public void loadFile(TLRPC.Document document, Object parentObject, int priority, int cacheType, boolean isAutoDownload) {
        if (document == null) {
            return;
        }
        if (cacheType == 0 && document.key != null) {
            cacheType = 1;
        }
        loadFile(document, null, null, null, null, parentObject, null, 0, priority, cacheType, isAutoDownload);
    }""",
        "FileLoader.java explicit auto-download loadFile overload",
    )
    _patch_eol_variants(
        eol_patch_fn,
        file_loader,
        [
            """    private FileLoadOperation loadFileInternal(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, Object parentObject, final String locationExt, final long locationSize, int priority, FileLoadOperationStream stream, final long streamOffset, boolean streamPriority, final int cacheType) {
        String fileName;
        if (location != null) {
            fileName = getAttachFileName(location, locationExt);
        } else if (secureDocument != null) {
            fileName = getAttachFileName(secureDocument);
        } else if (document != null) {
            fileName = getAttachFileName(document);
        } else if (webDocument != null) {
            fileName = getAttachFileName(webDocument);
        } else {
            fileName = null;
        }
        if (fileName == null || fileName.contains("" + Integer.MIN_VALUE)) {
            return null;
        }
        if (fileName.startsWith("0_0")) {
            FileLog.e(new RuntimeException("cant get hash from " + document));
            return null;
        }
        if (cacheType != 10 && !TextUtils.isEmpty(fileName) && !fileName.contains("" + Integer.MIN_VALUE)) {
            loadOperationPathsUI.put(fileName, new LoadOperationUIObject());
        }

        if (document != null && parentObject instanceof MessageObject && ((MessageObject) parentObject).putInDownloadsStore && !((MessageObject) parentObject).isAnyKindOfSticker()) {
            getDownloadController().startDownloadFile(document, (MessageObject) parentObject);
        }

        final String finalFileName = fileName;
        FileLoadOperation operation = loadOperationPaths.get(finalFileName);

        priority = getPriorityValue(priority);

        if (operation != null) {""",
            """    private FileLoadOperation loadFileInternal(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, Object parentObject, final String locationExt, final long locationSize, int priority, FileLoadOperationStream stream, final long streamOffset, boolean streamPriority, final int cacheType) {
        String fileName;
        if (location != null) {
            fileName = getAttachFileName(location, locationExt);
        } else if (secureDocument != null) {
            fileName = getAttachFileName(secureDocument);
        } else if (document != null) {
            fileName = getAttachFileName(document);
        } else if (webDocument != null) {
            fileName = getAttachFileName(webDocument);
        } else {
            fileName = null;
        }
        if (fileName == null || fileName.contains("" + Integer.MIN_VALUE)) {
            return null;
        }
        if (fileName.startsWith("0_0")) {
            FileLog.e(new RuntimeException("cant get hash from " + document));
            return null;
        }
        if (cacheType != 10 && !TextUtils.isEmpty(fileName) && !fileName.contains("" + Integer.MIN_VALUE)) {
            loadOperationPathsUI.put(fileName, new LoadOperationUIObject());
        }

        if (document != null && parentObject instanceof MessageObject && ((MessageObject) parentObject).putInDownloadsStore && !((MessageObject) parentObject).isAnyKindOfSticker()) {
            getDownloadController().startDownloadFile(document, (MessageObject) parentObject);
        }

        final String finalFileName = fileName;
        FileLoadOperation operation = loadOperationPaths.get(finalFileName);

        priority = getPriorityValue(priority);

        if (document != null && priority <= PRIORITY_LOW) {
            org.colgram.core.ColgramShieldConfig.init(ApplicationLoader.applicationContext);
            boolean isWifi = ApplicationLoader.getAutodownloadNetworkType() == StatsController.TYPE_WIFI;
            long capBytes = org.colgram.core.ColgramShieldConfig.isStorageCapEnabled()
                    ? org.colgram.core.ColgramShieldConfig.getStorageHardCapBytes() : 0L;
            if (org.colgram.core.ColgramFileSafetyPolicy.shouldBlockAutoDownload(
                    getDocumentFileName(document), document.mime_type, document.size, isWifi, capBytes)) {
                loadOperationPathsUI.remove(finalFileName);
                return null;
            }
        }

        if (operation != null) {""",
            """    private boolean isColgramAutoDownloadBlocked(TLRPC.Document document) {
        if (document == null) {
            return false;
        }
        org.colgram.core.ColgramShieldConfig.init(ApplicationLoader.applicationContext);
        boolean isWifi = ApplicationLoader.getAutodownloadNetworkType() == StatsController.TYPE_WIFI;
        long capBytes = org.colgram.core.ColgramShieldConfig.isStorageCapEnabled()
                ? org.colgram.core.ColgramShieldConfig.getStorageHardCapBytes() : 0L;
        return org.colgram.core.ColgramFileSafetyPolicy.shouldBlockAutoDownload(
                getDocumentFileName(document), document.mime_type, document.size, isWifi, capBytes);
    }

    private FileLoadOperation loadFileInternal(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, Object parentObject, final String locationExt, final long locationSize, int priority, FileLoadOperationStream stream, final long streamOffset, boolean streamPriority, final int cacheType) {
        return loadFileInternal(document, secureDocument, webDocument, location, imageLocation, parentObject, locationExt, locationSize, priority, stream, streamOffset, streamPriority, cacheType, false);
    }

    private FileLoadOperation loadFileInternal(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, Object parentObject, final String locationExt, final long locationSize, int priority, FileLoadOperationStream stream, final long streamOffset, boolean streamPriority, final int cacheType, final boolean isAutoDownload) {
        String fileName;
        if (location != null) {
            fileName = getAttachFileName(location, locationExt);
        } else if (secureDocument != null) {
            fileName = getAttachFileName(secureDocument);
        } else if (document != null) {
            fileName = getAttachFileName(document);
        } else if (webDocument != null) {
            fileName = getAttachFileName(webDocument);
        } else {
            fileName = null;
        }
        if (fileName == null || fileName.contains("" + Integer.MIN_VALUE)) {
            return null;
        }
        if (fileName.startsWith("0_0")) {
            FileLog.e(new RuntimeException("cant get hash from " + document));
            return null;
        }
        if (isAutoDownload && isColgramAutoDownloadBlocked(document)) {
            loadOperationPathsUI.remove(fileName);
            return null;
        }
        if (cacheType != 10 && !TextUtils.isEmpty(fileName) && !fileName.contains("" + Integer.MIN_VALUE)) {
            loadOperationPathsUI.put(fileName, new LoadOperationUIObject());
        }

        if (document != null && parentObject instanceof MessageObject && ((MessageObject) parentObject).putInDownloadsStore && !((MessageObject) parentObject).isAnyKindOfSticker()) {
            getDownloadController().startDownloadFile(document, (MessageObject) parentObject);
        }

        final String finalFileName = fileName;
        FileLoadOperation operation = loadOperationPaths.get(finalFileName);

        priority = getPriorityValue(priority);

        if (operation != null) {""",
        ],
        """    private boolean isColgramAutoDownloadBlocked(TLRPC.Document document) {
        if (document == null) {
            return false;
        }
        org.colgram.core.ColgramShieldConfig.init(ApplicationLoader.applicationContext);
        boolean isWifi = ApplicationLoader.getAutodownloadNetworkType() == StatsController.TYPE_WIFI;
        long capBytes = org.colgram.core.ColgramShieldConfig.isStorageCapEnabled()
                ? org.colgram.core.ColgramShieldConfig.getStorageHardCapBytes() : 0L;
        return org.colgram.core.ColgramFileSafetyPolicy.shouldBlockAutoDownload(
                getDocumentFileName(document), document.mime_type, document.size, isWifi, capBytes);
    }

    private FileLoadOperation loadFileInternal(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, Object parentObject, final String locationExt, final long locationSize, int priority, FileLoadOperationStream stream, final long streamOffset, boolean streamPriority, final int cacheType, final boolean isAutoDownload) {
        String fileName;
        if (location != null) {
            fileName = getAttachFileName(location, locationExt);
        } else if (secureDocument != null) {
            fileName = getAttachFileName(secureDocument);
        } else if (document != null) {
            fileName = getAttachFileName(document);
        } else if (webDocument != null) {
            fileName = getAttachFileName(webDocument);
        } else {
            fileName = null;
        }
        if (fileName == null || fileName.contains("" + Integer.MIN_VALUE)) {
            return null;
        }
        if (fileName.startsWith("0_0")) {
            FileLog.e(new RuntimeException("cant get hash from " + document));
            return null;
        }
        if (isAutoDownload && isColgramAutoDownloadBlocked(document)) {
            loadOperationPathsUI.remove(fileName);
            return null;
        }
        if (cacheType != 10 && !TextUtils.isEmpty(fileName) && !fileName.contains("" + Integer.MIN_VALUE)) {
            loadOperationPathsUI.put(fileName, new LoadOperationUIObject());
        }

        if (document != null && parentObject instanceof MessageObject && ((MessageObject) parentObject).putInDownloadsStore && !((MessageObject) parentObject).isAnyKindOfSticker()) {
            getDownloadController().startDownloadFile(document, (MessageObject) parentObject);
        }

        final String finalFileName = fileName;
        FileLoadOperation operation = loadOperationPaths.get(finalFileName);

        priority = getPriorityValue(priority);

        if (operation != null) {""",
        "FileLoader.java explicit auto-download guard before startDownloadFile and UI map",
    )
    _patch_eol_variants(
        eol_patch_fn,
        file_loader,
        [
            """        return operation;
    }

    private FileLoadOperation loadFileInternal(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, Object parentObject, final String locationExt, final long locationSize, int priority, FileLoadOperationStream stream, final long streamOffset, boolean streamPriority, final int cacheType) {
        return loadFileInternal(document, secureDocument, webDocument, location, imageLocation, parentObject, locationExt, locationSize, priority, stream, streamOffset, streamPriority, cacheType, false);
    }

    public static boolean canSaveAsFile(Object parentObject) {"""
        ],
        """        return operation;
    }

    public static boolean canSaveAsFile(Object parentObject) {""",
        "FileLoader.java drop duplicate 13-arg loadFileInternal overload",
    )
    _patch_eol_variants(
        eol_patch_fn,
        file_loader,
        [
            """            result[0] = loadFileInternal(document, null, null, document == null && location != null ? location.location : null, location, parentObject, document == null && location != null ? "mp4" : null, document == null && location != null ? location.currentSize : 0, loadingPriority, stream, offset, priority, cacheType);"""
        ],
        """            result[0] = loadFileInternal(document, null, null, document == null && location != null ? location.location : null, location, parentObject, document == null && location != null ? "mp4" : null, document == null && location != null ? location.currentSize : 0, loadingPriority, stream, offset, priority, cacheType, false);""",
        "FileLoader.java pass isAutoDownload=false in loadStreamFile",
    )
    _patch_eol_variants(
        eol_patch_fn,
        file_loader,
        [
            """    private void loadFile(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, final Object parentObject, final String locationExt, final long locationSize, final int priority, final int cacheType) {
        String fileName;
        if (location != null) {
            fileName = getAttachFileName(location, locationExt);
        } else if (document != null) {
            fileName = getAttachFileName(document);
        } else if (webDocument != null) {
            fileName = getAttachFileName(webDocument);
        } else {
            fileName = null;
        }
        Runnable runnable = () -> loadFileInternal(document, secureDocument, webDocument, location, imageLocation, parentObject, locationExt, locationSize, priority, null, 0, false, cacheType);
        if (cacheType != 10 && !TextUtils.isEmpty(fileName) && !fileName.contains("" + Integer.MIN_VALUE)) {
            LoadOperationUIObject uiObject = new FileLoader.LoadOperationUIObject();
            uiObject.loadInternalRunnable = runnable;
            loadOperationPathsUI.put(fileName, uiObject);
        }
        fileLoaderQueue.postRunnable(runnable);
    }"""
        ],
        """    private void loadFile(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, final Object parentObject, final String locationExt, final long locationSize, final int priority, final int cacheType) {
        loadFile(document, secureDocument, webDocument, location, imageLocation, parentObject, locationExt, locationSize, priority, cacheType, false);
    }

    private void loadFile(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, final Object parentObject, final String locationExt, final long locationSize, final int priority, final int cacheType, final boolean isAutoDownload) {
        if (isAutoDownload && isColgramAutoDownloadBlocked(document)) {
            return;
        }
        String fileName;
        if (location != null) {
            fileName = getAttachFileName(location, locationExt);
        } else if (document != null) {
            fileName = getAttachFileName(document);
        } else if (webDocument != null) {
            fileName = getAttachFileName(webDocument);
        } else {
            fileName = null;
        }
        Runnable runnable = () -> loadFileInternal(document, secureDocument, webDocument, location, imageLocation, parentObject, locationExt, locationSize, priority, null, 0, false, cacheType, isAutoDownload);
        if (cacheType != 10 && !TextUtils.isEmpty(fileName) && !fileName.contains("" + Integer.MIN_VALUE)) {
            LoadOperationUIObject uiObject = new FileLoader.LoadOperationUIObject();
            uiObject.loadInternalRunnable = runnable;
            loadOperationPathsUI.put(fileName, uiObject);
        }
        fileLoaderQueue.postRunnable(runnable);
    }""",
        "FileLoader.java forward isAutoDownload in private loadFile",
    )
    _patch_eol_variants(
        eol_patch_fn,
        file_loader,
        [
            """                if (!operation.isPreloadVideoOperation()) {
                    loadOperationPathsUI.remove(fileName);
                    if (delegate != null) {
                        delegate.fileDidLoaded(fileName, finalFile, parentObject, finalType);
                    }
                }"""
        ],
        """                if (!operation.isPreloadVideoOperation()) {
                    ColgramNativeColemailerBridge.onMediaDownloadedForShadowAndCap(currentAccount, finalFile, parentObject);
                    loadOperationPathsUI.remove(fileName);
                    if (delegate != null) {
                        delegate.fileDidLoaded(fileName, finalFile, parentObject, finalType);
                    }
                }""",
        "FileLoader.java hook didFinishLoadingFile for real-time storage cap & Lazy Shadow Vault",
    )

    # 6. AutoDeleteMediaTask.java:
    #    - Scan both ColgramStorageSandbox & ImageLoader.createMediaPaths()
    #    - Trigger ColgramStorageCapEnforcer.enforceIfNeeded
    #    - Fix negative cache_limit (MB encoding) & lastUsageDate <= 0 fallback
    #    - Notify CapEnforcer on file deletion
    auto_del = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "AutoDeleteMediaTask.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        auto_del,
        [
            """                                    file.file.delete();"""
        ],
        """                                    long colgramDelLen = file.file.length();
                                    if (file.file.delete()) {
                                        org.colgram.core.ColgramHookHandler.notifyMediaFileDeleted(file.file, colgramDelLen);
                                    }""",
        "AutoDeleteMediaTask.java notify CapEnforcer on time-based delete",
    )
    _patch_eol_variants(
        eol_patch_fn,
        auto_del,
        [
            """            FileLog.cleanupLogs();
            int maxCacheGb = SharedConfig.getPreferences().getInt("cache_limit", Integer.MAX_VALUE);
            if (maxCacheGb != Integer.MAX_VALUE && maxCacheGb > 0) {
                long maxCacheSize;
                if (false) {
                    maxCacheSize = 1024L * 1024L * 92L;
                } else if (maxCacheGb == 1) {
                    maxCacheSize = 1024L * 1024L * 300L;
                } else {
                    maxCacheSize = maxCacheGb * 1024L * 1024L * 1000L;
                }""",
            """            FileLog.cleanupLogs();
            org.colgram.core.ColgramStorageCapEnforcer.enforceIfNeeded(ApplicationLoader.applicationContext, false);
            int maxCacheGb = SharedConfig.getPreferences().getInt("cache_limit", Integer.MAX_VALUE);
            if (maxCacheGb != Integer.MAX_VALUE && maxCacheGb != 0) {
                long maxCacheSize;
                if (maxCacheGb < 0) {
                    maxCacheSize = (long) (-maxCacheGb) * 1024L * 1024L;
                } else if (maxCacheGb == 1) {
                    maxCacheSize = 1024L * 1024L * 300L;
                } else {
                    maxCacheSize = maxCacheGb * 1024L * 1024L * 1000L;
                }
                for (File sDir : org.colgram.core.ColgramStorageSandbox.getAllSandboxDirectories(ApplicationLoader.applicationContext)) {
                    if (sDir != null && paths.indexOfValue(sDir) < 0) {
                        paths.put(paths.size() + 100, sDir);
                    }
                }""",
        ],
        """            FileLog.cleanupLogs();
            org.colgram.core.ColgramStorageCapEnforcer.enforceIfNeeded(ApplicationLoader.applicationContext, false);
            int maxCacheGb = SharedConfig.getPreferences().getInt("cache_limit", Integer.MAX_VALUE);
            if (maxCacheGb != Integer.MAX_VALUE && maxCacheGb != 0) {
                long maxCacheSize;
                if (maxCacheGb < 0) {
                    maxCacheSize = (long) (-maxCacheGb) * 1024L * 1024L;
                } else if (maxCacheGb == 1) {
                    maxCacheSize = 1024L * 1024L * 300L;
                } else {
                    maxCacheSize = maxCacheGb * 1024L * 1024L * 1000L;
                }
                colgramMergeSandboxDirsDedup(paths, org.colgram.core.ColgramStorageSandbox.getAllSandboxDirectories(ApplicationLoader.applicationContext));""",
        "AutoDeleteMediaTask.java enforce CapEnforcer + MB cache_limit + canonical sandbox dedup",
    )
    _patch_eol_variants(
        eol_patch_fn,
        auto_del,
        [
            """    private static void fillFilesRecursive(final File fromFolder, ArrayList<FileInfoInternal> fileInfoList) {
        if (fromFolder == null) {
            return;
        }
        File[] files = fromFolder.listFiles();
        if (files == null) {
            return;
        }
        for (final File fileEntry : files) {
            if (fileEntry.isDirectory()) {
                fillFilesRecursive(fileEntry, fileInfoList);"""
        ],
        """    private static String colgramCanonicalPath(File dir) {
        try {
            return dir.getCanonicalPath();
        } catch (Throwable ignored) {
            return dir.getAbsolutePath();
        }
    }

    private static boolean colgramIsDescendantOrEqual(String candidate, String root) {
        if (candidate == null || root == null) {
            return false;
        }
        return candidate.equals(root) || candidate.startsWith(root + File.separator);
    }

    private static void colgramMergeSandboxDirsDedup(SparseArray<File> paths, java.util.List<File> sandboxDirs) {
        if (paths == null) {
            return;
        }
        java.util.ArrayList<Integer> existingKeys = new java.util.ArrayList<>(paths.size());
        java.util.ArrayList<String> existingCanons = new java.util.ArrayList<>(paths.size());
        for (int a = 0; a < paths.size(); a++) {
            File f = paths.valueAt(a);
            if (f == null) {
                continue;
            }
            String canon = colgramCanonicalPath(f);
            boolean dupOrChild = false;
            for (int b = 0; b < existingCanons.size(); b++) {
                if (colgramIsDescendantOrEqual(canon, existingCanons.get(b))) {
                    dupOrChild = true;
                    break;
                }
            }
            if (dupOrChild) {
                continue;
            }
            for (int b = existingCanons.size() - 1; b >= 0; b--) {
                if (colgramIsDescendantOrEqual(existingCanons.get(b), canon)) {
                    existingCanons.remove(b);
                    existingKeys.remove(b);
                }
            }
            existingKeys.add(paths.keyAt(a));
            existingCanons.add(canon);
        }
        java.util.ArrayList<File> preservedDirs = new java.util.ArrayList<>(existingKeys.size());
        for (int a = 0; a < existingKeys.size(); a++) {
            preservedDirs.add(paths.get(existingKeys.get(a)));
        }
        paths.clear();
        for (int a = 0; a < existingKeys.size(); a++) {
            paths.put(existingKeys.get(a), preservedDirs.get(a));
        }
        if (sandboxDirs == null || sandboxDirs.isEmpty()) {
            return;
        }
        int nextKey = 1000;
        for (int i = 0; i < sandboxDirs.size(); i++) {
            File sDir = sandboxDirs.get(i);
            if (sDir == null) {
                continue;
            }
            String sCanon = colgramCanonicalPath(sDir);
            boolean skip = false;
            for (int b = 0; b < existingCanons.size(); b++) {
                if (colgramIsDescendantOrEqual(sCanon, existingCanons.get(b))) {
                    skip = true;
                    break;
                }
            }
            if (skip) {
                continue;
            }
            for (int b = existingCanons.size() - 1; b >= 0; b--) {
                if (colgramIsDescendantOrEqual(existingCanons.get(b), sCanon)) {
                    paths.remove(existingKeys.get(b));
                    existingKeys.remove(b);
                    existingCanons.remove(b);
                }
            }
            int key = 1000 + i;
            if (key < nextKey) {
                key = nextKey;
            }
            while (paths.indexOfKey(key) >= 0) {
                key++;
            }
            nextKey = key + 1;
            paths.put(key, sDir);
            existingKeys.add(key);
            existingCanons.add(sCanon);
        }
    }

    private static void fillFilesRecursive(final File fromFolder, ArrayList<FileInfoInternal> fileInfoList) {
        if (fromFolder == null || org.colgram.core.ColgramStorageCapEnforcer.isProtectedDirectoryName(fromFolder.getName())) {
            return;
        }
        File[] files = fromFolder.listFiles();
        if (files == null) {
            return;
        }
        for (final File fileEntry : files) {
            if (fileEntry.isDirectory()) {
                if (org.colgram.core.ColgramStorageCapEnforcer.isProtectedDirectoryName(fileEntry.getName())) {
                    continue;
                }
                fillFilesRecursive(fileEntry, fileInfoList);""",
        "AutoDeleteMediaTask.java canonical dir dedup helper and skip protected dirs",
    )
    _patch_eol_variants(
        eol_patch_fn,
        auto_del,
        [
            """                        if (allFiles.get(i).lastUsageDate <= 0) {
                            skippedFiles++;
                            continue;
                        }
                        long size = allFiles.get(i).file.length();
                        totalSize -= size;

                        try {
                            deletedFilesBySize++;
                            deletedFilesBySizeSize += size;
                            allFiles.get(i).file.delete();
                        } catch (Exception e) {

                        }""",
            """                        if (allFiles.get(i).lastUsageDate <= 0) {
                            allFiles.get(i).lastUsageDate = allFiles.get(i).file.lastModified() / 1000L;
                        }
                        if (allFiles.get(i).lastUsageDate <= 0 || org.colgram.core.ColgramStorageCapEnforcer.isProtectedFile(allFiles.get(i).file, usingFilePaths, System.currentTimeMillis(), true)) {
                            skippedFiles++;
                            continue;
                        }
                        long size = allFiles.get(i).file.length();

                        try {
                            if (allFiles.get(i).file.delete()) {
                                totalSize -= size;
                                deletedFilesBySize++;
                                deletedFilesBySizeSize += size;
                                org.colgram.core.ColgramHookHandler.notifyMediaFileDeleted(allFiles.get(i).file, size);
                            }
                        } catch (Exception e) {

                        }""",
        ],
        """                        long effectiveLastUsage = allFiles.get(i).lastUsageDate;
                        if (effectiveLastUsage <= 0L) {
                            effectiveLastUsage = allFiles.get(i).file.lastModified() / 1000L;
                        }
                        if (effectiveLastUsage <= 0L || org.colgram.core.ColgramStorageCapEnforcer.isProtectedFile(allFiles.get(i).file, usingFilePaths, System.currentTimeMillis(), true)) {
                            skippedFiles++;
                            continue;
                        }
                        long size = allFiles.get(i).file.length();

                        try {
                            if (allFiles.get(i).file.delete()) {
                                totalSize -= size;
                                deletedFilesBySize++;
                                deletedFilesBySizeSize += size;
                                org.colgram.core.ColgramHookHandler.notifyMediaFileDeleted(allFiles.get(i).file, size);
                            }
                        } catch (Exception e) {

                        }""",
        "AutoDeleteMediaTask.java fix lastUsageDate <= 0 fallback and protect Vault files",
    )

    # 7. CacheControlActivity.java:
    #    - Add 1 GB (-1024) option in VIEW_TYPE_MAX_CACHE_SIZE slider synced with ColgramUnlimitedMemory & ColgramShieldConfig
    cache_ctrl = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "CacheControlActivity.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        cache_ctrl,
        [
            """                    ArrayList<Integer> options = new ArrayList<>();
                    options.add(0);
                    options.add(1);
                    if (totalSizeInGb <= 17) {
                        options.add(2);
                    }
                    if (totalSizeInGb > 5) {
                        options.add(5);
                    }
                    if (totalSizeInGb > 16) {
                        options.add(16);
                    }
                    if (totalSizeInGb > 32) {
                        options.add(32);
                    }
                    options.add(Integer.MAX_VALUE);
                    String[] values = new String[options.size()];
                    for (int i = 0; i < options.size(); i++) {
                        if (options.get(i) == 0) {
                            values[i] = "100 MB";
                        } else if (options.get(i) == 1) {
                            values[i] = String.format("300 MB");
                        } else if (options.get(i) == Integer.MAX_VALUE) {
                            values[i] = LocaleController.getString(R.string.NoLimit);
                        } else {
                            values[i] = String.format("%d GB", options.get(i));
                        }
                    }
                    slideChooseView2.setCallback(i -> {
                        int chosen = options.get(i);
                        SharedConfig.getPreferences().edit().putInt("cache_limit", chosen).apply();
                        boolean unlimited = chosen == Integer.MAX_VALUE;
                        org.colgram.core.ColgramConfig.setUnlimitedMemoryEnabled(unlimited);
                        if (unlimited) {
                            org.colgram.core.ColgramUnlimitedMemory.apply(getContext(), true);
                        }
                    });
                    int currentLimit = SharedConfig.getPreferences().getInt("cache_limit",
                            org.colgram.core.ColgramConfig.isUnlimitedMemoryEnabled() ? Integer.MAX_VALUE : 0);
                    int i = options.indexOf(currentLimit);
                    if (i < 0) {
                        i = options.size() - 1;
                    }""",
            """                    ArrayList<Integer> options = new ArrayList<>();
                    options.add(0);
                    options.add(-512);
                    options.add(-1024);
                    options.add(2);
                    if (totalSizeInGb > 5) {
                        options.add(5);
                    }
                    if (totalSizeInGb > 16) {
                        options.add(16);
                    }
                    if (totalSizeInGb > 32) {
                        options.add(32);
                    }
                    options.add(Integer.MAX_VALUE);
                    String[] values = new String[options.size()];
                    for (int i = 0; i < options.size(); i++) {
                        int opt = options.get(i);
                        if (opt == 0) {
                            values[i] = "100 MB";
                        } else if (opt == -512) {
                            values[i] = "512 MB";
                        } else if (opt == -1024) {
                            values[i] = "1 GB";
                        } else if (opt == Integer.MAX_VALUE) {
                            values[i] = LocaleController.getString(R.string.NoLimit);
                        } else {
                            values[i] = String.format("%d GB", opt);
                        }
                    }
                    slideChooseView2.setCallback(i -> {
                        int chosen = options.get(i);
                        boolean unlimited = chosen == Integer.MAX_VALUE;
                        if (unlimited) {
                            org.colgram.core.ColgramConfig.setUnlimitedMemoryEnabled(true);
                            org.colgram.core.ColgramUnlimitedMemory.apply(getContext(), true);
                        } else {
                            int capMb = chosen < 0 ? -chosen : (chosen == 0 ? 100 : chosen * 1024);
                            org.colgram.core.ColgramUnlimitedMemory.applyStorageCap(getContext(), capMb);
                        }
                    });
                    org.colgram.core.ColgramShieldConfig.init(getContext());
                    int fallbackLimit = (org.colgram.core.ColgramConfig.isUnlimitedMemoryEnabled() && !org.colgram.core.ColgramShieldConfig.isStorageCapEnabled())
                            ? Integer.MAX_VALUE : org.colgram.core.ColgramUnlimitedMemory.toMainConfigCacheLimit(org.colgram.core.ColgramShieldConfig.getStorageHardCapMb());
                    int currentLimit = SharedConfig.getPreferences().getInt("cache_limit", fallbackLimit);
                    if (currentLimit == 1) currentLimit = -512;
                    int i = options.indexOf(currentLimit);
                    if (i < 0) {
                        i = options.size() - 1;
                    }""",
        ],
        """                    ArrayList<Integer> options = new ArrayList<>();
                    options.add(-100);
                    options.add(-512);
                    options.add(-1024);
                    options.add(2);
                    if (totalSizeInGb > 5) {
                        options.add(5);
                    }
                    if (totalSizeInGb > 16) {
                        options.add(16);
                    }
                    if (totalSizeInGb > 32) {
                        options.add(32);
                    }
                    options.add(Integer.MAX_VALUE);
                    String[] values = new String[options.size()];
                    for (int i = 0; i < options.size(); i++) {
                        int opt = options.get(i);
                        if (opt == -100 || opt == 0) {
                            values[i] = "100 MB";
                        } else if (opt == -512) {
                            values[i] = "512 MB";
                        } else if (opt == -1024) {
                            values[i] = "1 GB";
                        } else if (opt == Integer.MAX_VALUE) {
                            values[i] = LocaleController.getString(R.string.NoLimit);
                        } else {
                            values[i] = String.format("%d GB", opt);
                        }
                    }
                    slideChooseView2.setCallback(i -> {
                        int chosen = options.get(i);
                        boolean unlimited = chosen == Integer.MAX_VALUE;
                        if (unlimited) {
                            org.colgram.core.ColgramConfig.setUnlimitedMemoryEnabled(true);
                            org.colgram.core.ColgramUnlimitedMemory.apply(getContext(), true);
                        } else {
                            int capMb = chosen < 0 ? -chosen : (chosen == 0 ? 100 : chosen * 1024);
                            org.colgram.core.ColgramUnlimitedMemory.applyStorageCap(getContext(), capMb);
                        }
                    });
                    org.colgram.core.ColgramShieldConfig.init(getContext());
                    int fallbackLimit = (org.colgram.core.ColgramConfig.isUnlimitedMemoryEnabled() && !org.colgram.core.ColgramShieldConfig.isStorageCapEnabled())
                            ? Integer.MAX_VALUE : org.colgram.core.ColgramUnlimitedMemory.toMainConfigCacheLimit(org.colgram.core.ColgramShieldConfig.getStorageHardCapMb());
                    int currentLimit = SharedConfig.getPreferences().getInt("cache_limit", fallbackLimit);
                    if (currentLimit == 0) currentLimit = -100;
                    if (currentLimit == 1) currentLimit = -512;
                    int i = options.indexOf(currentLimit);
                    if (i < 0) {
                        if (currentLimit == Integer.MAX_VALUE) {
                            i = options.size() - 1;
                        } else {
                            int targetMb = currentLimit < 0 ? -currentLimit : currentLimit * 1024;
                            int bestIdx = 0;
                            int bestDiff = Integer.MAX_VALUE;
                            for (int idxOpt = 0; idxOpt < options.size(); idxOpt++) {
                                int opt = options.get(idxOpt);
                                if (opt == Integer.MAX_VALUE) continue;
                                int optMb = opt < 0 ? -opt : (opt == 0 ? 100 : opt * 1024);
                                int diff = Math.abs(optMb - targetMb);
                                if (diff < bestDiff) {
                                    bestDiff = diff;
                                    bestIdx = idxOpt;
                                }
                            }
                            i = bestIdx;
                        }
                    }""",
        "CacheControlActivity.java fix -100 MB slider mapping & nearest finite notch",
    )

    # 8. AndroidUtilities.java:
    #    - Whitelist canonical ColgramStorageSandbox cache & sandbox roots in isInternalUri
    #    - Intercept dangerous/huge file opening via Contract D ColgramUiKit.showMalwareWarningSheet (openForView + openDocument)
    android_utils = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "AndroidUtilities.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        android_utils,
        [
            """                if (!safeCustom.isEmpty() && !".".equals(safeCustom)) {
                    String canonicalSandbox = new File(filesDir, safeCustom).getCanonicalPath();
                    if (!canonicalSandbox.equals(filesDir.getCanonicalPath())
                            && pathString.startsWith(canonicalSandbox + File.separator)) {
                        return false;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return pathString.toLowerCase().contains("/data/data/" + ApplicationLoader.applicationContext.getPackageName());"""
        ],
        """                if (!safeCustom.isEmpty() && !".".equals(safeCustom)) {
                    String canonicalSandbox = new File(filesDir, safeCustom).getCanonicalPath();
                    if (!canonicalSandbox.equals(filesDir.getCanonicalPath())
                            && pathString.startsWith(canonicalSandbox + File.separator)) {
                        return false;
                    }
                }
            }
            File colgramSandboxRoot = org.colgram.core.ColgramStorageSandbox.getSandboxRootDir(ApplicationLoader.applicationContext);
            if (colgramSandboxRoot != null) {
                String canonicalSandboxRoot = colgramSandboxRoot.getCanonicalPath();
                if ((filesDir == null || !canonicalSandboxRoot.equals(filesDir.getCanonicalPath()))
                        && (pathString.equals(canonicalSandboxRoot) || pathString.startsWith(canonicalSandboxRoot + File.separator))) {
                    return false;
                }
            }
            File colgramCacheRoot = org.colgram.core.ColgramStorageSandbox.getCacheRootDir(ApplicationLoader.applicationContext);
            if (colgramCacheRoot != null) {
                String canonicalCacheRoot = colgramCacheRoot.getCanonicalPath();
                if ((filesDir == null || !canonicalCacheRoot.equals(filesDir.getCanonicalPath()))
                        && (pathString.equals(canonicalCacheRoot) || pathString.startsWith(canonicalCacheRoot + File.separator))) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }
        return pathString.toLowerCase().contains("/data/data/" + ApplicationLoader.applicationContext.getPackageName());""",
        "AndroidUtilities.java whitelist canonical sandbox and cache roots in isInternalUri",
    )
    _patch_eol_variants(
        eol_patch_fn,
        android_utils,
        [
            """        if (f != null && f.exists()) {
            if (parentFragment != null && f.getName().toLowerCase().endsWith("attheme")) {""",
            """        if (f != null && f.exists()) {
            if (colgramWarnIfUnsafeOpen(f, fileName, document.mime_type, activity,
                    parentFragment != null ? parentFragment.getResourceProvider() : null,
                    () -> openDocument(message, activity, parentFragment))) {
                return;
            }
            if (parentFragment != null && f.getName().toLowerCase().endsWith("attheme")) {""",
        ],
        """        if (f != null && f.exists()) {
            String colgramDocName = FileLoader.getDocumentFileName(document);
            if (TextUtils.isEmpty(colgramDocName)) {
                colgramDocName = fileName;
            }
            if (colgramWarnIfUnsafeOpen(f, colgramDocName, document.mime_type, activity,
                    parentFragment != null ? parentFragment.getResourceProvider() : null,
                    () -> openDocument(message, activity, parentFragment))) {
                return;
            }
            if (parentFragment != null && f.getName().toLowerCase().endsWith("attheme")) {""",
        "AndroidUtilities.java openDocument malware/huge sheet confirmation with real filename",
    )
    _patch_eol_variants(
        eol_patch_fn,
        android_utils,
        [
            """    public static boolean openForView(File f, String fileName, String mimeType, final Activity activity, Theme.ResourcesProvider resourcesProvider, boolean restrict) {
        if (f != null && f.exists()) {"""
        ],
        """    private static final ThreadLocal<String> colgramOpenWarningBypassPath = new ThreadLocal<>();

    private static boolean colgramWarnIfUnsafeOpen(File f, String fileName, String mimeType,
                                                   Activity activity, Theme.ResourcesProvider resourcesProvider,
                                                   Runnable onProceed) {
        if (f == null || !f.exists() || activity == null) {
            return false;
        }
        String absPath = f.getAbsolutePath();
        if (absPath.equals(colgramOpenWarningBypassPath.get())) {
            return false;
        }
        org.colgram.core.ColgramShieldConfig.init(activity);
        long capBytes = org.colgram.core.ColgramShieldConfig.isStorageCapEnabled()
                ? org.colgram.core.ColgramShieldConfig.getStorageHardCapBytes() : 0L;
        String displayName = TextUtils.isEmpty(fileName) ? f.getName() : fileName;
        org.colgram.core.ColgramFileSafetyPolicy.SafetyAssessment safety =
                org.colgram.core.ColgramFileSafetyPolicy.assessForManualAction(
                        displayName, mimeType, f.length(), capBytes);
        if (!safety.manualWarningRequired) {
            return false;
        }
        org.telegram.ui.ColgramUiKit.showMalwareWarningSheet(
                activity, resourcesProvider, displayName, safety.detectedExtension,
                f.length(), safety.threatReason, true, () -> {
                    colgramOpenWarningBypassPath.set(absPath);
                    try {
                        if (onProceed != null) {
                            onProceed.run();
                        }
                    } finally {
                        colgramOpenWarningBypassPath.remove();
                    }
                });
        return true;
    }

    public static boolean openForView(File f, String fileName, String mimeType, final Activity activity, Theme.ResourcesProvider resourcesProvider, boolean restrict) {
        if (f != null && f.exists()) {
            if (colgramWarnIfUnsafeOpen(f, fileName, mimeType, activity, resourcesProvider,
                    () -> openForView(f, fileName, mimeType, activity, resourcesProvider, restrict))) {
                return true;
            }""",
        "AndroidUtilities.java openForView malware/huge sheet confirmation",
    )

    # 9. MessageObject.java:
    #    - Collapsed spam stub text replacement when ColgramHookHandler.isMessageCollapsedSpam() is true
    msg_obj = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "MessageObject.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        msg_obj,
        [
            """    public static final int MESSAGE_SEND_STATE_SENT = 0;"""
        ],
        """    public boolean colgramLayoutCollapsed;

    public static final int MESSAGE_SEND_STATE_SENT = 0;""",
        "MessageObject.java track colgramLayoutCollapsed state",
    )
    _patch_eol_variants(
        eol_patch_fn,
        msg_obj,
        [
            """        CharSequence text = messageText;
        try {
            textLayoutOriginalWidth = maxWidth;""",
            """        CharSequence text = messageText;
        if (org.colgram.core.ColgramHookHandler.isMessageCollapsedSpam(getDialogId(), getId())) {
            text = "\\uD83D\\uDEE1 Скрыто Антипингом · Показать";
        }
        try {
            textLayoutOriginalWidth = maxWidth;""",
        ],
        """        CharSequence text = messageText;
        boolean colgramCollapsed = org.colgram.core.ColgramHookHandler.isMessageCollapsedSpam(getDialogId(), getId());
        colgramLayoutCollapsed = colgramCollapsed;
        if (colgramCollapsed) {
            text = "\\uD83D\\uDEE1 Скрыто Антипингом · Показать";
        }
        try {
            textLayoutOriginalWidth = maxWidth;""",
        "MessageObject.java compact 1-line collapsed spam stub",
    )

    # 10. ChatMessageCell.java:
    #     - Track collapsed spam visibility for compact bubble rebuilds + guard motion events from phantom link taps
    #     - Display Lazy Shadow Vault offline WebP preview as thumb (keeping photoNotSet=true so mediaExists stays false)
    #     - Gate manual dangerous/huge download tap behind Contract D showMalwareWarningSheet safely across ViewHolder recycling
    chat_cell = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "Cells", "ChatMessageCell.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_cell,
        [
            """    private boolean colgramMarkedDeleteVisible;"""
        ],
        """    private boolean colgramMarkedDeleteVisible;
    private boolean colgramCollapsedSpamVisible;
    private boolean colgramBypassSafetyOnce;""",
        "ChatMessageCell.java fields for collapsed spam & safety prompt",
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_cell,
        [
            """    private boolean checkTextBlockMotionEvent(MotionEvent event) {
        if (!(currentMessageObject.type == MessageObject.TYPE_TEXT || currentMessageObject.type == MessageObject.TYPE_EMOJIS || currentMessageObject.type == MessageObject.TYPE_STORY_MENTION) || currentMessageObject.textLayoutBlocks == null || currentMessageObject.textLayoutBlocks.isEmpty() || !(currentMessageObject.messageText instanceof Spannable)) {"""
        ],
        """    private boolean checkTextBlockMotionEvent(MotionEvent event) {
        if (colgramCollapsedSpamVisible) {
            return false;
        }
        if (!(currentMessageObject.type == MessageObject.TYPE_TEXT || currentMessageObject.type == MessageObject.TYPE_EMOJIS || currentMessageObject.type == MessageObject.TYPE_STORY_MENTION) || currentMessageObject.textLayoutBlocks == null || currentMessageObject.textLayoutBlocks.isEmpty() || !(currentMessageObject.messageText instanceof Spannable)) {""",
        "ChatMessageCell.java guard checkTextBlockMotionEvent on collapsed spam stub",
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_cell,
        [
            """    private boolean checkCaptionMotionEvent(MotionEvent event) {
        if (!(currentCaption instanceof Spannable) || captionLayout == null || touchedByExpanation) {"""
        ],
        """    private boolean checkCaptionMotionEvent(MotionEvent event) {
        if (colgramCollapsedSpamVisible) {
            return false;
        }
        if (!(currentCaption instanceof Spannable) || captionLayout == null || touchedByExpanation) {""",
        "ChatMessageCell.java guard checkCaptionMotionEvent on collapsed spam stub",
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_cell,
        [
            """    private boolean checkLinkPreviewMotionEvent(MotionEvent event) {
        if (currentMessageObject.type != MessageObject.TYPE_TEXT && currentMessageObject.type != MessageObject.TYPE_STORY_MENTION || !hasLinkPreview) {"""
        ],
        """    private boolean checkLinkPreviewMotionEvent(MotionEvent event) {
        if (colgramCollapsedSpamVisible) {
            return false;
        }
        if (currentMessageObject.type != MessageObject.TYPE_TEXT && currentMessageObject.type != MessageObject.TYPE_STORY_MENTION || !hasLinkPreview) {""",
        "ChatMessageCell.java guard checkLinkPreviewMotionEvent on collapsed spam stub",
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_cell,
        [
            """        boolean colgramNowDeletedCheck = org.colgram.core.ColgramConfig.isAntiDeleteHighlightEnabled()
                && org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(messageObject.getDialogId(), messageObject.getId());
        boolean colgramNowCollapsedCheck = org.colgram.core.ColgramHookHandler.isMessageCollapsedSpam(
                messageObject.getDialogId(), messageObject.getId());
        if (colgramCollapsedSpamVisible != colgramNowCollapsedCheck) {
            colgramCollapsedSpamVisible = colgramNowCollapsedCheck;
            dataChanged = true;
        }
        boolean colgramNowCollapsedCheck = org.colgram.core.ColgramHookHandler.isMessageCollapsedSpam(
                messageObject.getDialogId(), messageObject.getId());
        if (colgramCollapsedSpamVisible != colgramNowCollapsedCheck
                || messageObject.colgramLayoutCollapsed != colgramNowCollapsedCheck) {
            colgramCollapsedSpamVisible = colgramNowCollapsedCheck;
            dataChanged = true;
            messageObject.resetLayout();
            messageObject.checkLayout();
        }
        if (!colgramMarkedDeleteStateKnown
                || colgramMarkedDeleteVisible != colgramNowDeletedCheck
                || (colgramNowDeletedCheck && colgramHasPendingDeleteAnim(messageObject.getDialogId(), messageObject.getId()))) {
            dataChanged = true;
        }""",
            """        boolean colgramNowDeletedCheck = org.colgram.core.ColgramConfig.isAntiDeleteHighlightEnabled()
                && org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(messageObject.getDialogId(), messageObject.getId());
        boolean colgramNowCollapsedCheck = org.colgram.core.ColgramHookHandler.isMessageCollapsedSpam(
                messageObject.getDialogId(), messageObject.getId());
        if (colgramCollapsedSpamVisible != colgramNowCollapsedCheck) {
            colgramCollapsedSpamVisible = colgramNowCollapsedCheck;
            dataChanged = true;
        }
        if (!colgramMarkedDeleteStateKnown
                || colgramMarkedDeleteVisible != colgramNowDeletedCheck
                || (colgramNowDeletedCheck && colgramHasPendingDeleteAnim(messageObject.getDialogId(), messageObject.getId()))) {
            dataChanged = true;
        }""",
            """        boolean colgramNowDeletedCheck = org.colgram.core.ColgramConfig.isAntiDeleteHighlightEnabled()
                && org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(messageObject.getDialogId(), messageObject.getId());
        if (!colgramMarkedDeleteStateKnown
                || colgramMarkedDeleteVisible != colgramNowDeletedCheck
                || (colgramNowDeletedCheck && colgramHasPendingDeleteAnim(messageObject.getDialogId(), messageObject.getId()))) {
            dataChanged = true;
        }""",
        ],
        """        boolean colgramNowDeletedCheck = org.colgram.core.ColgramConfig.isAntiDeleteHighlightEnabled()
                && org.colgram.core.ColgramHookHandler.isMessageMarkedDeleted(messageObject.getDialogId(), messageObject.getId());
        boolean colgramNowCollapsedCheck = org.colgram.core.ColgramHookHandler.isMessageCollapsedSpam(
                messageObject.getDialogId(), messageObject.getId());
        if (colgramCollapsedSpamVisible != colgramNowCollapsedCheck
                || messageObject.colgramLayoutCollapsed != colgramNowCollapsedCheck) {
            colgramCollapsedSpamVisible = colgramNowCollapsedCheck;
            dataChanged = true;
            messageObject.resetLayout();
            messageObject.checkLayout();
        }
        if (!colgramMarkedDeleteStateKnown
                || colgramMarkedDeleteVisible != colgramNowDeletedCheck
                || (colgramNowDeletedCheck && colgramHasPendingDeleteAnim(messageObject.getDialogId(), messageObject.getId()))) {
            dataChanged = true;
        }""",
        "ChatMessageCell.java rebuild collapsed spam stub state and text layout cleanly",
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_cell,
        [
            """                                } else {
                                    photoNotSet = true;
                                    if (currentPhotoObjectThumb != null || currentPhotoObjectThumbStripped != null) {
                                        photoImage.setImage(null, null, ImageLocation.getForObject(currentPhotoObjectThumb, photoParentObject), String.format(Locale.US, "%d_%d_b", w, h), currentPhotoObjectThumbStripped, 0, null, messageObject, 0);
                                    } else {
                                        photoImage.setImageBitmap((Drawable) null);
                                    }
                                }
                            }
                            clearBlurredImage(blurredPhotoImage);""",
            """                                } else {
                                    String colgramShadowPreview = org.colgram.core.ColgramHookHandler.getShadowPreviewPath(
                                            messageObject.getDialogId(), messageObject.getId());
                                    if (!TextUtils.isEmpty(colgramShadowPreview)) {
                                        photoNotSet = false;
                                        photoImage.setImage(ImageLocation.getForPath(colgramShadowPreview), currentPhotoFilter, null, null, null, 0, null, messageObject, 0);
                                    } else {
                                        photoNotSet = true;
                                        if (currentPhotoObjectThumb != null || currentPhotoObjectThumbStripped != null) {
                                        photoImage.setImage(null, null, ImageLocation.getForObject(currentPhotoObjectThumb, photoParentObject), String.format(Locale.US, "%d_%d_b", w, h), currentPhotoObjectThumbStripped, 0, null, messageObject, 0);
                                        } else {
                                            photoImage.setImageBitmap((Drawable) null);
                                        }
                                    }
                                }
                            }
                            clearBlurredImage(blurredPhotoImage);""",
        ],
        """                                } else {
                                    photoNotSet = true;
                                    String colgramShadowPreview = org.colgram.core.ColgramHookHandler.getShadowPreviewPath(
                                            messageObject.getDialogId(), messageObject.getId());
                                    if (!TextUtils.isEmpty(colgramShadowPreview)) {
                                        photoImage.setImage(null, null, ImageLocation.getForPath(colgramShadowPreview), currentPhotoFilter, null, 0, null, messageObject, 0);
                                    } else if (currentPhotoObjectThumb != null || currentPhotoObjectThumbStripped != null) {
                                        photoImage.setImage(null, null, ImageLocation.getForObject(currentPhotoObjectThumb, photoParentObject), String.format(Locale.US, "%d_%d_b", w, h), currentPhotoObjectThumbStripped, 0, null, messageObject, 0);
                                    } else {
                                        photoImage.setImageBitmap((Drawable) null);
                                    }
                                }
                            }
                            clearBlurredImage(blurredPhotoImage);""",
        "ChatMessageCell.java load Lazy Shadow Vault WebP micro-preview as thumb",
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_cell,
        [
            """                    currentCaption = messageObject.isVoiceTranscriptionOpen() ? messageObject.getVoiceTranscription() : messageObject.caption;"""
        ],
        """                    currentCaption = colgramCollapsedSpamVisible ? "\\uD83D\\uDEE1 Скрыто Антипингом · Показать" : (messageObject.isVoiceTranscriptionOpen() ? messageObject.getVoiceTranscription() : messageObject.caption);""",
        "ChatMessageCell.java collapse spam caption to 1-line stub",
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_cell,
        [
            """        if (currentMessageObject != null && !currentMessageObject.isAnyKindOfSticker()) {
            currentMessageObject.putInDownloadsStore = true;
        }
        if (buttonState == 0 && (!drawVideoImageButton || video)) {""",
            """        if (currentMessageObject != null && !currentMessageObject.isAnyKindOfSticker()) {
            currentMessageObject.putInDownloadsStore = true;
        }
        if (buttonState == 0 && (!drawVideoImageButton || video) && !colgramBypassSafetyOnce
                && documentAttach != null && !currentMessageObject.isAnyKindOfSticker() && !currentMessageObject.mediaExists) {
            org.colgram.core.ColgramShieldConfig.init(getContext());
            long capBytes = org.colgram.core.ColgramShieldConfig.isStorageCapEnabled()
                    ? org.colgram.core.ColgramShieldConfig.getStorageHardCapBytes() : 0L;
            String docName = FileLoader.getDocumentFileName(documentAttach);
            org.colgram.core.ColgramFileSafetyPolicy.SafetyAssessment safety =
                    org.colgram.core.ColgramFileSafetyPolicy.assessForManualAction(
                            docName, documentAttach.mime_type, documentAttach.size, capBytes);
            if (safety.manualWarningRequired) {
                android.app.Activity hostAct = AndroidUtilities.findActivity(getContext());
                if (hostAct != null) {
                    org.telegram.ui.ColgramUiKit.showMalwareWarningSheet(
                            hostAct, resourcesProvider, docName, safety.detectedExtension,
                            documentAttach.size, safety.threatReason, false, () -> {
                                colgramBypassSafetyOnce = true;
                                try {
                                    didPressButton(animated, video);
                                } finally {
                                    colgramBypassSafetyOnce = false;
                                }
                            });
                    return;
                }
            }
        }
        if (buttonState == 0 && (!drawVideoImageButton || video)) {""",
        ],
        """        if (buttonState == 0 && (!drawVideoImageButton || video) && !colgramBypassSafetyOnce
                && documentAttach != null && currentMessageObject != null && !currentMessageObject.isAnyKindOfSticker() && !currentMessageObject.mediaExists) {
            org.colgram.core.ColgramShieldConfig.init(getContext());
            long capBytes = org.colgram.core.ColgramShieldConfig.isStorageCapEnabled()
                    ? org.colgram.core.ColgramShieldConfig.getStorageHardCapBytes() : 0L;
            String docName = FileLoader.getDocumentFileName(documentAttach);
            org.colgram.core.ColgramFileSafetyPolicy.SafetyAssessment safety =
                    org.colgram.core.ColgramFileSafetyPolicy.assessForManualAction(
                            docName, documentAttach.mime_type, documentAttach.size, capBytes);
            if (safety.manualWarningRequired) {
                android.app.Activity hostAct = AndroidUtilities.findActivity(getContext());
                if (hostAct != null) {
                    final MessageObject targetMsg = currentMessageObject;
                    final TLRPC.Document targetDoc = documentAttach;
                    final int targetAccount = currentAccount;
                    org.telegram.ui.ColgramUiKit.showMalwareWarningSheet(
                            hostAct, resourcesProvider, docName, safety.detectedExtension,
                            targetDoc.size, safety.threatReason, false, () -> {
                                if (currentMessageObject == targetMsg && documentAttach == targetDoc) {
                                    colgramBypassSafetyOnce = true;
                                    try {
                                        didPressButton(animated, video);
                                    } finally {
                                        colgramBypassSafetyOnce = false;
                                    }
                                } else if (targetDoc != null) {
                                    if (targetMsg != null && !targetMsg.isAnyKindOfSticker()) {
                                        targetMsg.putInDownloadsStore = true;
                                        targetMsg.loadingCancelled = false;
                                    }
                                    FileLoader.getInstance(targetAccount).loadFile(
                                            targetDoc, targetMsg, FileLoader.PRIORITY_NORMAL_UP, 0);
                                }
                            });
                    return;
                }
            }
        }
        if (currentMessageObject != null && !currentMessageObject.isAnyKindOfSticker()) {
            currentMessageObject.putInDownloadsStore = true;
        }
        if (buttonState == 0 && (!drawVideoImageButton || video)) {""",
        "ChatMessageCell.java gate manual dangerous/huge download safely behind malware sheet",
    )

    # 11. ChatActivity.java:
    #     - Tap on collapsed spam bubble expands full message + resets layout
    chat_act = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ChatActivity.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_act,
        [
            """            if (view instanceof ChatMessageCell) {
                MessageObject msg = ((ChatMessageCell) view).getMessageObject();
                if (msg != null && msg.type == MessageObject.TYPE_JOINED_CHANNEL) {"""
        ],
        """            if (view instanceof ChatMessageCell) {
                MessageObject msg = ((ChatMessageCell) view).getMessageObject();
                if (msg != null && msg.messageOwner != null && org.colgram.core.ColgramHookHandler.isMessageCollapsedSpam(msg.getDialogId(), msg.getId())) {
                    org.colgram.core.ColgramHookHandler.toggleExpandCollapsedSpam(msg.getDialogId(), msg.getId());
                    msg.resetLayout();
                    msg.checkLayout();
                    ((ChatMessageCell) view).forceResetMessageObject();
                    view.requestLayout();
                    if (position >= 0) {
                        chatAdapter.notifyItemChanged(position);
                    }
                    return;
                }
                if (msg != null && msg.type == MessageObject.TYPE_JOINED_CHANNEL) {""",
        "ChatActivity.java tap collapsed spam stub to expand/collapse message",
    )
