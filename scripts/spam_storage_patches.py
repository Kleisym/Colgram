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
    }"""
        ],
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
    }""",
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
            """        if (isVideo && preset.preloadVideo && size > maxSize && maxSize > 2 * 1024 * 1024) {
            return (mask & type) != 0 ? 2 : 0;"""
        ],
        """        if (isVideo && preset.preloadVideo && !org.colgram.core.ColgramShieldConfig.isMalwareDownloadShieldEnabled() && size > maxSize && maxSize > 2 * 1024 * 1024) {
            return (mask & type) != 0 ? 2 : 0;""",
        "DownloadController.java disable preloadVideo when shield enabled",
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
                path = FileLoader.getAttachFileName(document);
            } else if (downloadObject.object instanceof TLRPC.Photo) {
                TLRPC.Photo photo = (TLRPC.Photo) downloadObject.object;
                photoSize = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize());
                path = FileLoader.getAttachFileName(photoSize);
            }"""
        ],
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
        "DownloadController.java processDownloadObjects shield + metered medium photo cap",
    )

    # 5. FileLoader.java:
    #    - Defense-in-depth at PRIORITY_LOW inside loadFileInternal
    #    - Real-time cache accounting & Lazy Shadow Vault capture on didFinishLoadingFile
    file_loader = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "FileLoader.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        file_loader,
        [
            """        priority = getPriorityValue(priority);

        if (operation != null) {"""
        ],
        """        priority = getPriorityValue(priority);

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
        "FileLoader.java defense-in-depth background auto-download block",
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
                }"""
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
                for (File sDir : org.colgram.core.ColgramStorageSandbox.getAllSandboxDirectories(ApplicationLoader.applicationContext)) {
                    if (sDir != null && paths.indexOfValue(sDir) < 0) {
                        paths.put(paths.size() + 100, sDir);
                    }
                }""",
        "AutoDeleteMediaTask.java enforce CapEnforcer + MB cache_limit + sandbox scan",
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
                            org.colgram.core.ColgramConfig.isUnlimitedMemoryEnabled() ? Integer.MAX_VALUE : 0);"""
        ],
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
                    if (currentLimit == 1) currentLimit = -512;""",
        "CacheControlActivity.java add 1 GB / 512 MB cap options & sync ColgramUnlimitedMemory",
    )

    # 8. AndroidUtilities.java:
    #    - Intercept dangerous/huge file opening via Contract D ColgramUiKit.showMalwareWarningSheet (openForView + openDocument)
    android_utils = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "AndroidUtilities.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        android_utils,
        [
            """        if (f != null && f.exists()) {
            if (parentFragment != null && f.getName().toLowerCase().endsWith("attheme")) {"""
        ],
        """        if (f != null && f.exists()) {
            if (colgramWarnIfUnsafeOpen(f, fileName, document.mime_type, activity,
                    parentFragment != null ? parentFragment.getResourceProvider() : null,
                    () -> openDocument(message, activity, parentFragment))) {
                return;
            }
            if (parentFragment != null && f.getName().toLowerCase().endsWith("attheme")) {""",
        "AndroidUtilities.java openDocument malware/huge sheet confirmation",
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
            """        CharSequence text = messageText;
        try {
            textLayoutOriginalWidth = maxWidth;"""
        ],
        """        CharSequence text = messageText;
        if (org.colgram.core.ColgramHookHandler.isMessageCollapsedSpam(getDialogId(), getId())) {
            text = "\\uD83D\\uDEE1 Скрыто Антипингом · Показать";
        }
        try {
            textLayoutOriginalWidth = maxWidth;""",
        "MessageObject.java compact 1-line collapsed spam stub",
    )

    # 10. ChatMessageCell.java:
    #     - Track collapsed spam visibility for compact bubble rebuilds (disjoint from Saved Tags chips!)
    #     - Display Lazy Shadow Vault offline WebP preview when local photo is absent
    #     - Gate manual dangerous/huge download tap behind Contract D showMalwareWarningSheet
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
            """        if (!colgramMarkedDeleteStateKnown
                || colgramMarkedDeleteVisible != colgramNowDeletedCheck
                || (colgramNowDeletedCheck && colgramHasPendingDeleteAnim(messageObject.getDialogId(), messageObject.getId()))) {
            dataChanged = true;
        }"""
        ],
        """        boolean colgramNowCollapsedCheck = org.colgram.core.ColgramHookHandler.isMessageCollapsedSpam(
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
        "ChatMessageCell.java rebuild collapsed spam stub state cleanly",
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_cell,
        [
            """                                } else {
                                    photoNotSet = true;
                                    if (currentPhotoObjectThumb != null || currentPhotoObjectThumbStripped != null) {"""
        ],
        """                                } else {
                                    String colgramShadowPreview = org.colgram.core.ColgramHookHandler.getShadowPreviewPath(
                                            messageObject.getDialogId(), messageObject.getId());
                                    if (!TextUtils.isEmpty(colgramShadowPreview)) {
                                        photoNotSet = false;
                                        photoImage.setImage(ImageLocation.getForPath(colgramShadowPreview), currentPhotoFilter, null, null, null, 0, null, messageObject, 0);
                                    } else {
                                        photoNotSet = true;
                                        if (currentPhotoObjectThumb != null || currentPhotoObjectThumbStripped != null) {""",
        "ChatMessageCell.java load Lazy Shadow Vault WebP micro-preview fallback (part 1)",
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_cell,
        [
            """                                    } else {
                                        photoImage.setImageBitmap((Drawable) null);
                                    }
                                }
                            }
                            clearBlurredImage(blurredPhotoImage);"""
        ],
        """                                        } else {
                                            photoImage.setImageBitmap((Drawable) null);
                                        }
                                    }
                                }
                            }
                            clearBlurredImage(blurredPhotoImage);""",
        "ChatMessageCell.java load Lazy Shadow Vault WebP micro-preview fallback (part 2)",
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
            """        if (buttonState == 0 && (!drawVideoImageButton || video)) {"""
        ],
        """        if (buttonState == 0 && (!drawVideoImageButton || video) && !colgramBypassSafetyOnce
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
        "ChatMessageCell.java gate manual dangerous/huge download behind malware sheet",
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
