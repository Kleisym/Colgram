"""Track A Telegram-Src patches for bypass stability, background sync, reactions, and media loading."""
from __future__ import annotations

import os


def apply(repo_path: str, patch_file_fn, eol_patch_fn) -> None:
    """Apply all Track A patches cleanly and idempotently against Telegram-Src."""
    # 1. AndroidManifest.xml: restore POST_NOTIFICATIONS so background sync & notifications work on Android 13+.
    manifest = os.path.join(repo_path, "TMessagesProj", "src", "main", "AndroidManifest.xml")
    eol_patch_fn(
        manifest,
        "    <!-- stripped POST_NOTIFICATIONS for Colgram privacy -->",
        '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
        "AndroidManifest.xml restore POST_NOTIFICATIONS",
    )

    # 2. ApplicationLoader.java: start background services even when pushService pref is off,
    #    and diff NetworkCallback.onCapabilitiesChanged to ignore LTE RSSI / bandwidth jitter.
    app_loader = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "ApplicationLoader.java"
    )
    eol_patch_fn(
        app_loader,
        """    private static volatile  ConnectivityManager.NetworkCallback networkCallback;
    private static long lastNetworkCheckTypeTime;""",
        """    private static volatile  ConnectivityManager.NetworkCallback networkCallback;
    private static volatile long colgramLastNetHandle = -1L;
    private static volatile int colgramLastNetMask = -1;
    private static volatile boolean colgramLastNetValidated = false;
    private static volatile long colgramLastCapRefreshMs = 0L;
    private static long lastNetworkCheckTypeTime;""",
        "ApplicationLoader.java network callback state fields",
    )
    eol_patch_fn(
        app_loader,
        """        } else {
            if (org.colgram.core.ColgramConfig.isAlwaysBackgroundEnabled()) {
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    try {
                        if (UserConfig.getInstance(a).isClientActivated()) {
                            ConnectionsManager.getInstance(a).setPushConnectionEnabled(true);
                        }
                    } catch (Throwable ignored) {}
                }""",
        """        } else {
            if (org.colgram.core.ColgramConfig.isAlwaysBackgroundEnabled()) {
                if (org.colgram.core.ColgramUiBridge.isAppInForeground()
                        || org.colgram.core.ColgramPowerGuard.isBatteryExemptionGranted(applicationContext)) {
                    try {
                        org.colgram.core.ColgramForegroundService.start(applicationContext);
                    } catch (Throwable ignored) {}
                }
                try {
                    org.colgram.core.ColgramForegroundService.scheduleDozeKeepalive(applicationContext);
                } catch (Throwable ignored) {}
                try {
                    org.colgram.core.ColgramBootJobService.scheduleProxyRefresh(applicationContext);
                } catch (Throwable ignored) {}
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    try {
                        if (UserConfig.getInstance(a).isClientActivated()) {
                            ConnectionsManager.getInstance(a).setPushConnectionEnabled(true);
                        }
                    } catch (Throwable ignored) {}
                }""",
        "ApplicationLoader.java decouple always-background start from pushService pref",
    )
    eol_patch_fn(
        app_loader,
        """                        networkCallback = new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(@NonNull Network network) {
                                refreshNetworkAndNotify();
                            }

                            @Override
                            public void onLost(@NonNull Network network) {
                                refreshNetworkAndNotify();
                            }

                            @Override
                            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
                                refreshNetworkAndNotify();
                            }
                        };""",
        """                        networkCallback = new ConnectivityManager.NetworkCallback() {
                            private int colgramCapsMask(NetworkCapabilities caps) {
                                if (caps == null) return 0;
                                int mask = 0;
                                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) mask |= 1;
                                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) mask |= 2;
                                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) mask |= 4;
                                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) mask |= 8;
                                return mask;
                            }

                            @Override
                            public void onAvailable(@NonNull Network network) {
                                colgramLastNetHandle = Build.VERSION.SDK_INT >= 23 ? network.getNetworkHandle() : 0L;
                                NetworkCapabilities caps = null;
                                try {
                                    if (connectivityManager != null) {
                                        caps = connectivityManager.getNetworkCapabilities(network);
                                    }
                                } catch (Throwable ignored) {}
                                colgramLastNetMask = colgramCapsMask(caps);
                                colgramLastNetValidated = caps != null
                                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                                colgramLastCapRefreshMs = android.os.SystemClock.elapsedRealtime();
                                refreshNetworkAndNotify();
                            }

                            @Override
                            public void onLost(@NonNull Network network) {
                                colgramLastNetHandle = -1L;
                                colgramLastNetMask = -1;
                                colgramLastNetValidated = false;
                                colgramLastCapRefreshMs = android.os.SystemClock.elapsedRealtime();
                                refreshNetworkAndNotify();
                            }

                            @Override
                            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
                                long handle = Build.VERSION.SDK_INT >= 23 ? network.getNetworkHandle() : 0L;
                                int mask = colgramCapsMask(networkCapabilities);
                                boolean validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                                if (handle == colgramLastNetHandle
                                        && mask == colgramLastNetMask
                                        && validated == colgramLastNetValidated) {
                                    return;
                                }
                                long nowMs = android.os.SystemClock.elapsedRealtime();
                                if (handle == colgramLastNetHandle
                                        && mask == colgramLastNetMask
                                        && colgramLastCapRefreshMs > 0L
                                        && (nowMs - colgramLastCapRefreshMs) < 1500L) {
                                    return;
                                }
                                colgramLastNetHandle = handle;
                                colgramLastNetMask = mask;
                                colgramLastNetValidated = validated;
                                colgramLastCapRefreshMs = nowMs;
                                refreshNetworkAndNotify();
                            }
                        };""",
        "ApplicationLoader.java diff network capabilities to filter LTE RSSI jitter",
    )

    # 3. ConnectionsManager.java: expose colgramResetProxySignature() so bypass restart/fallback clears cached signature.
    conn_mgr = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "tgnet", "ConnectionsManager.java"
    )
    eol_patch_fn(
        conn_mgr,
        "    private static volatile String colgramLastProxySignature = null;",
        """    private static volatile String colgramLastProxySignature = null;

    public static void colgramResetProxySignature() {
        colgramLastProxySignature = null;
    }""",
        "ConnectionsManager.java add colgramResetProxySignature()",
    )

    # 4. MessagesController.java: never permanently mark dialogsEndReached on offline/network error; retry on reconnect.
    msgs_ctrl = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "MessagesController.java"
    )
    eol_patch_fn(
        msgs_ctrl,
        "    private SparseBooleanArray dialogsEndReached = new SparseBooleanArray();",
        """    private SparseBooleanArray dialogsEndReached = new SparseBooleanArray();
    private final SparseBooleanArray colgramDialogsRetryPending = new SparseBooleanArray();""",
        "MessagesController.java add colgramDialogsRetryPending field",
    )
    eol_patch_fn(
        msgs_ctrl,
        "            getNotificationCenter().addObserver(messagesController, NotificationCenter.updateMessageMedia);",
        """            getNotificationCenter().addObserver(messagesController, NotificationCenter.updateMessageMedia);
            getNotificationCenter().addObserver(messagesController, NotificationCenter.didUpdateConnectionState);""",
        "MessagesController.java observe didUpdateConnectionState",
    )
    eol_patch_fn(
        msgs_ctrl,
        "        } else if (id == NotificationCenter.currentUserPremiumStatusChanged) {",
        """        } else if (id == NotificationCenter.didUpdateConnectionState) {
            if (getConnectionsManager().getConnectionState() == ConnectionsManager.ConnectionStateConnected
                    && colgramDialogsRetryPending.size() > 0) {
                for (int i = 0; i < colgramDialogsRetryPending.size(); i++) {
                    if (colgramDialogsRetryPending.valueAt(i)) {
                        int retryFolderId = colgramDialogsRetryPending.keyAt(i);
                        colgramDialogsRetryPending.put(retryFolderId, false);
                        loadDialogs(retryFolderId, 0, 100, false);
                    }
                }
            }
        } else if (id == NotificationCenter.currentUserPremiumStatusChanged) {""",
        "MessagesController.java retry pending dialogs on connection restore",
    )
    if os.path.exists(msgs_ctrl):
        with open(msgs_ctrl, "r", encoding="utf-8", errors="ignore") as _mc_f:
            _mc_src = _mc_f.read()
        if "                if (error == null) {\n                    colgramDialogsRetryPending.put(folderId, false);" in _mc_src.replace("\r\n", "\n"):
            eol_patch_fn(
                msgs_ctrl,
                """                if (error == null) {
                    colgramDialogsRetryPending.put(folderId, false);
                    TLRPC.messages_Dialogs dialogsRes = (TLRPC.messages_Dialogs) response;""",
                """                if (error == null) {
                    AndroidUtilities.runOnUIThread(() -> colgramDialogsRetryPending.put(folderId, false));
                    TLRPC.messages_Dialogs dialogsRes = (TLRPC.messages_Dialogs) response;""",
                "MessagesController.java upgrade colgramDialogsRetryPending clear onto UI thread",
            )
    eol_patch_fn(
        msgs_ctrl,
        """                if (error == null) {
                    TLRPC.messages_Dialogs dialogsRes = (TLRPC.messages_Dialogs) response;
                    processLoadedDialogs(dialogsRes, null, null, folderId, 0, count, 0, false, false, false);
                    if (onEmptyCallback != null && dialogsRes.dialogs.isEmpty()) {
                        AndroidUtilities.runOnUIThread(onEmptyCallback);
                    }
                } else {
                    AndroidUtilities.runOnUIThread(() -> {
                        loadingDialogs.put(folderId, false);
                        dialogsEndReached.put(folderId, true);
                        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
                    });
                }""",
        """                if (error == null) {
                    AndroidUtilities.runOnUIThread(() -> colgramDialogsRetryPending.put(folderId, false));
                    TLRPC.messages_Dialogs dialogsRes = (TLRPC.messages_Dialogs) response;
                    processLoadedDialogs(dialogsRes, null, null, folderId, 0, count, 0, false, false, false);
                    if (onEmptyCallback != null && dialogsRes.dialogs.isEmpty()) {
                        AndroidUtilities.runOnUIThread(onEmptyCallback);
                    }
                } else {
                    AndroidUtilities.runOnUIThread(() -> {
                        loadingDialogs.put(folderId, false);
                        colgramDialogsRetryPending.put(folderId, true);
                        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
                    });
                }""",
        "MessagesController.java replace dialogsEndReached on error with colgramDialogsRetryPending",
    )

    # 5. MediaDataController.java: keep fallback reactions in-memory only, retry on connect, guard SQLite & preloads.
    media_dc = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "MediaDataController.java"
    )
    eol_patch_fn(
        media_dc,
        """    private int reactionsUpdateDate;
    private boolean reactionsCacheGenerated;""",
        """    private int reactionsUpdateDate;
    private boolean reactionsCacheGenerated;
    private boolean reactionsFallbackActive;
    private NotificationCenter.NotificationCenterDelegate colgramReactionsConnObserver;""",
        "MediaDataController.java add reactionsFallbackActive fields",
    )
    if os.path.exists(media_dc):
        with open(media_dc, "r", encoding="utf-8", errors="ignore") as _mdc_f:
            _mdc_src = _mdc_f.read().replace("\r\n", "\n")
        if """                } else if (enabledReactionsList.isEmpty()) {
                    reactionsFallbackActive = true;
                    isLoadingReactions = false;
                    colgramApplyFallbackReactionsInMemory(buildDefaultFallbackReactions());
                    colgramEnsureReactionsConnectionObserver();""" in _mdc_src:
            eol_patch_fn(
                media_dc,
                """                } else if (enabledReactionsList.isEmpty()) {
                    reactionsFallbackActive = true;
                    isLoadingReactions = false;
                    colgramApplyFallbackReactionsInMemory(buildDefaultFallbackReactions());
                    colgramEnsureReactionsConnectionObserver();
                } else {""",
                """                } else if (enabledReactionsList.isEmpty() || reactionsFallbackActive) {
                    reactionsFallbackActive = true;
                    isLoadingReactions = false;
                    if (enabledReactionsList.isEmpty()) {
                        colgramApplyFallbackReactionsInMemory(buildDefaultFallbackReactions());
                    }
                    colgramEnsureReactionsConnectionObserver();
                } else {""",
                "MediaDataController.java re-arm reactions observer if reconnect retry fails",
            )
    eol_patch_fn(
        media_dc,
        """            TLRPC.TL_messages_getAvailableReactions req = new TLRPC.TL_messages_getAvailableReactions();
            req.hash = lastHash != null ? lastHash : reactionsUpdateHash;
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                int date = (int) (System.currentTimeMillis() / 1000);
                if (response instanceof TLRPC.TL_messages_availableReactionsNotModified) {
                    processLoadedReactions(null, 0, date, false);
                } else if (response instanceof TLRPC.TL_messages_availableReactions) {
                    TLRPC.TL_messages_availableReactions r = (TLRPC.TL_messages_availableReactions) response;
                    processLoadedReactions(r.reactions, r.hash, date, false);
                } else if (enabledReactionsList.isEmpty()) {
                    processLoadedReactions(buildDefaultFallbackReactions(), 0, date, false);
                }
            }));""",
        """            TLRPC.TL_messages_getAvailableReactions req = new TLRPC.TL_messages_getAvailableReactions();
            req.hash = reactionsFallbackActive ? 0 : (lastHash != null ? lastHash : reactionsUpdateHash);
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                int date = (int) (System.currentTimeMillis() / 1000);
                if (response instanceof TLRPC.TL_messages_availableReactionsNotModified) {
                    if (reactionsFallbackActive) {
                        reactionsFallbackActive = false;
                        loadReactions(false, 0);
                    } else {
                        processLoadedReactions(null, 0, date, false);
                    }
                } else if (response instanceof TLRPC.TL_messages_availableReactions) {
                    reactionsFallbackActive = false;
                    colgramDetachReactionsConnectionObserver();
                    TLRPC.TL_messages_availableReactions r = (TLRPC.TL_messages_availableReactions) response;
                    processLoadedReactions(r.reactions, r.hash, date, false);
                } else if (enabledReactionsList.isEmpty() || reactionsFallbackActive) {
                    reactionsFallbackActive = true;
                    isLoadingReactions = false;
                    if (enabledReactionsList.isEmpty()) {
                        colgramApplyFallbackReactionsInMemory(buildDefaultFallbackReactions());
                    }
                    colgramEnsureReactionsConnectionObserver();
                } else {
                    isLoadingReactions = false;
                }
            }));""",
        "MediaDataController.java avoid caching fallback reactions and retry on connect",
    )
    eol_patch_fn(
        media_dc,
        """    private List<TLRPC.TL_availableReaction> buildDefaultFallbackReactions() {""",
        """    private void colgramApplyFallbackReactionsInMemory(List<TLRPC.TL_availableReaction> fallback) {
        if (fallback == null) return;
        reactionsList.clear();
        reactionsMap.clear();
        enabledReactionsList.clear();
        reactionsList.addAll(fallback);
        for (int i = 0; i < reactionsList.size(); i++) {
            TLRPC.TL_availableReaction item = reactionsList.get(i);
            item.positionInList = i;
            reactionsMap.put(item.reaction, item);
            if (!item.inactive) {
                enabledReactionsList.add(item);
            }
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reactionsDidLoad);
    }

    private void colgramEnsureReactionsConnectionObserver() {
        if (colgramReactionsConnObserver == null) {
            colgramReactionsConnObserver = (id, account, args) -> {
                if (id == NotificationCenter.didUpdateConnectionState
                        && getConnectionsManager().getConnectionState() == ConnectionsManager.ConnectionStateConnected) {
                    colgramDetachReactionsConnectionObserver();
                    if (reactionsFallbackActive && !isLoadingReactions) {
                        loadReactions(false, 0);
                    }
                }
            };
            getNotificationCenter().addObserver(colgramReactionsConnObserver, NotificationCenter.didUpdateConnectionState);
        }
    }

    private void colgramDetachReactionsConnectionObserver() {
        if (colgramReactionsConnObserver != null) {
            getNotificationCenter().removeObserver(colgramReactionsConnObserver, NotificationCenter.didUpdateConnectionState);
            colgramReactionsConnObserver = null;
        }
    }

    private List<TLRPC.TL_availableReaction> buildDefaultFallbackReactions() {""",
        "MediaDataController.java add fallback & reconnect helpers",
    )
    eol_patch_fn(
        media_dc,
        """    public void preloadDefaultReactions() {
        if (reactionsList == null || reactionsCacheGenerated || !LiteMode.isEnabled(LiteMode.FLAG_ANIMATED_EMOJI_REACTIONS)) {
            return;
        }
        if (currentAccount != UserConfig.selectedAccount) {
            return;
        }
        reactionsCacheGenerated = true;
        ArrayList<TLRPC.TL_availableReaction> arrayList = new ArrayList<>(reactionsList);
        int N = Math.min(arrayList.size(), 10);
        for (int i = 0; i < N; i++) {
            TLRPC.TL_availableReaction reaction = arrayList.get(i);
            preloadImage(ImageLocation.getForDocument(reaction.activate_animation), FileLoader.PRIORITY_LOW);
            preloadImage(ImageLocation.getForDocument(reaction.appear_animation), FileLoader.PRIORITY_LOW);
        }

        for (int i = 0; i < N; i++) {
            TLRPC.TL_availableReaction reaction = arrayList.get(i);
            preloadImage(ImageLocation.getForDocument(reaction.effect_animation), FileLoader.PRIORITY_LOW);
        }
    }""",
        """    public void preloadDefaultReactions() {
        if (reactionsFallbackActive || reactionsList == null || reactionsCacheGenerated
                || !LiteMode.isEnabled(LiteMode.FLAG_ANIMATED_EMOJI_REACTIONS)) {
            return;
        }
        if (currentAccount != UserConfig.selectedAccount) {
            return;
        }
        reactionsCacheGenerated = true;
        ArrayList<TLRPC.TL_availableReaction> arrayList = new ArrayList<>(reactionsList);
        int N = Math.min(arrayList.size(), 10);
        for (int i = 0; i < N; i++) {
            TLRPC.TL_availableReaction reaction = arrayList.get(i);
            if (reaction == null) continue;
            if (reaction.activate_animation != null) {
                preloadImage(ImageLocation.getForDocument(reaction.activate_animation), FileLoader.PRIORITY_LOW);
            }
            if (reaction.appear_animation != null) {
                preloadImage(ImageLocation.getForDocument(reaction.appear_animation), FileLoader.PRIORITY_LOW);
            }
        }

        for (int i = 0; i < N; i++) {
            TLRPC.TL_availableReaction reaction = arrayList.get(i);
            if (reaction != null && reaction.effect_animation != null) {
                preloadImage(ImageLocation.getForDocument(reaction.effect_animation), FileLoader.PRIORITY_LOW);
            }
        }
    }""",
        "MediaDataController.java null-guard preloadDefaultReactions",
    )
    eol_patch_fn(
        media_dc,
        """                if (reactionsFinal != null) {
                    getMessagesStorage().getDatabase().executeFast("DELETE FROM reactions").stepThis().dispose();""",
        """                if (reactionsFinal != null) {
                    for (int a = 0; a < reactionsFinal.size(); a++) {
                        TLRPC.TL_availableReaction item = reactionsFinal.get(a);
                        if (item == null || item.static_icon == null) {
                            return;
                        }
                    }
                    getMessagesStorage().getDatabase().executeFast("DELETE FROM reactions").stepThis().dispose();""",
        "MediaDataController.java guard putReactionsToCache against null-doc wipe",
    )

    # 6. ReactionsLayoutInBubble.java: render emoji fallback drawable when reaction icons are null.
    reactions_bubble = os.path.join(
        repo_path,
        "TMessagesProj",
        "src",
        "main",
        "java",
        "org",
        "telegram",
        "ui",
        "Components",
        "Reactions",
        "ReactionsLayoutInBubble.java",
    )
    eol_patch_fn(
        reactions_bubble,
        """                } else if (visibleReaction.emojicon != null) {
                    TLRPC.TL_availableReaction r = MediaDataController.getInstance(currentAccount).getReactionsMap().get(visibleReaction.emojicon);
                    if (r != null) {
                        //imageReceiver.setImage(ImageLocation.getForDocument(r.static_icon), "40_40", svgThumb, "webp", r, 1);
                        SvgHelper.SvgDrawable svgThumb = DocumentObject.getSvgThumb(r.static_icon, Theme.key_windowBackgroundGray, 1.0f);
                        imageReceiver.setImage(ImageLocation.getForDocument(r.center_icon), "40_40_lastreactframe", svgThumb, "webp", r, 1);
                    }""",
        """                } else if (visibleReaction.emojicon != null) {
                    TLRPC.TL_availableReaction r = MediaDataController.getInstance(currentAccount).getReactionsMap().get(visibleReaction.emojicon);
                    if (r != null && (r.center_icon != null || r.static_icon != null)) {
                        TLRPC.Document primaryDoc = r.center_icon != null ? r.center_icon : r.static_icon;
                        SvgHelper.SvgDrawable svgThumb = r.static_icon != null
                                ? DocumentObject.getSvgThumb(r.static_icon, Theme.key_windowBackgroundGray, 1.0f) : null;
                        imageReceiver.setImage(ImageLocation.getForDocument(primaryDoc), "40_40_lastreactframe", svgThumb, "webp", r, 1);
                    } else {
                        Drawable emojiDrawable = Emoji.getEmojiDrawable(visibleReaction.emojicon);
                        if (emojiDrawable != null) {
                            imageReceiver.setImageBitmap(emojiDrawable);
                        }
                    }""",
        "ReactionsLayoutInBubble.java fallback to Emoji.getEmojiDrawable",
    )
    eol_patch_fn(
        reactions_bubble,
        """            TLRPC.TL_availableReaction r = MediaDataController.getInstance(currentAccount).getReactionsMap().get(reaction.emojicon);
            if (r != null) {
                imageReceiver.setImage(ImageLocation.getForDocument(r.center_icon), "40_40_nolimit", null, "tgs", r, 1);
            }""",
        """            TLRPC.TL_availableReaction r = MediaDataController.getInstance(currentAccount).getReactionsMap().get(reaction.emojicon);
            if (r != null && (r.center_icon != null || r.static_icon != null)) {
                TLRPC.Document animDoc = r.center_icon != null ? r.center_icon : r.static_icon;
                imageReceiver.setImage(ImageLocation.getForDocument(animDoc), "40_40_nolimit", null, "tgs", r, 1);
            }""",
        "ReactionsLayoutInBubble.java null-guard animateReaction",
    )

    # 7. FileLoadOperation.java: 32KB chunks for small/avatar/thumb, safe request caps (4/6/6),
    #    OFFSET_INVALID / LIMIT_INVALID accounting fix, and CDN DC 203 bypass fallback.
    file_load_op = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "FileLoadOperation.java"
    )
    eol_patch_fn(
        file_load_op,
        """        boolean isAvatarOrSmall = location instanceof TLRPC.TL_inputPeerPhotoFileLocation
                || (totalBytesCount > 0 && totalBytesCount <= 256 * 1024);
        if (boost && !forceSmallChunk) {
            downloadChunkSize = (isAvatarOrSmall && (totalBytesCount <= 0 || totalBytesCount > 64 * 1024))
                    || totalBytesCount >= 256 * 1024 ? 1024 * 128 : 1024 * 64;
            downloadChunkSizeBig = 1024 * 512;
            maxDownloadRequests = 12;
            maxDownloadRequestsBig = 12;
            maxDownloadRequestsAnimation = Math.max(maxDownloadRequestsAnimation, 12);""",
        """        boolean isSmallOrThumbOrAvatar = !allowDisordererFileSave
                || location instanceof TLRPC.TL_inputPeerPhotoFileLocation
                || location instanceof TLRPC.TL_inputStickerSetThumb
                || location instanceof TLRPC.TL_inputPhotoFileLocation
                || totalBytesCount <= 0
                || totalBytesCount < 256 * 1024;
        if (boost && !forceSmallChunk) {
            downloadChunkSize = isSmallOrThumbOrAvatar ? 1024 * 32 : 1024 * 128;
            downloadChunkSizeBig = totalBytesCount >= 1024 * 1024 ? 1024 * 512 : 1024 * 128;
            maxDownloadRequests = 4;
            maxDownloadRequestsBig = 6;
            maxDownloadRequestsAnimation = Math.max(maxDownloadRequestsAnimation, 6);""",
        "FileLoadOperation.java safe chunk sizes and request caps",
    )
    eol_patch_fn(
        file_load_op,
        """                removePart(notRequestedBytesRanges, requestInfo.offset, requestInfo.offset + requestInfo.chunkSize);
                if (!forceSmallChunk) {""",
        """                removePart(notRequestedBytesRanges, requestInfo.offset, requestInfo.offset + requestInfo.chunkSize);
                if (notRequestedBytesRanges == null) {
                    requestedBytesCount = Math.max(downloadedBytes, requestedBytesCount - requestInfo.chunkSize);
                }
                if (!forceSmallChunk) {""",
        "FileLoadOperation.java LIMIT_INVALID requestedBytesCount fix",
    )
    eol_patch_fn(
        file_load_op,
        """            } else if (error.text.contains("OFFSET_INVALID")) {
                if (downloadedBytes % currentDownloadChunkSize == 0) {""",
        """            } else if (error.text.contains("OFFSET_INVALID")) {
                if (downloadedBytes > 0 && downloadedBytes % currentDownloadChunkSize == 0) {""",
        "FileLoadOperation.java OFFSET_INVALID 0-byte download guard",
    )
    eol_patch_fn(
        file_load_op,
        """                    req.cdn_supported = true;""",
        """                    req.cdn_supported = !(org.colgram.core.ColgramDpiBypass.isBound()
                            && org.colgram.core.ColgramTelegramWsBridge.hasWorkedOnCurrentNetwork());""",
        "FileLoadOperation.java bypass CDN DC 203 when WSS bridge active",
    )
    eol_patch_fn(
        file_load_op,
        """                    } else if (request instanceof TLRPC.TL_upload_getCdnFile) {
                        if (error.text.equals("FILE_TOKEN_INVALID")) {""",
        """                    } else if (request instanceof TLRPC.TL_upload_getCdnFile) {
                        if (error.code < 0 || error.text.equals("FILE_TOKEN_INVALID")
                                || error.text.equals("REQUEST_TOKEN_INVALID")
                                || error.text.startsWith("CDN_")) {""",
        "FileLoadOperation.java CDN getCdnFile fallback to origin DC",
    )
    eol_patch_fn(
        file_load_op,
        """                            } else if (error1 != null) {
                                if (error1.text.equals("FILE_TOKEN_INVALID") || error1.text.equals("REQUEST_TOKEN_INVALID")) {""",
        """                            } else if (error1 != null) {
                                if (error1.code < 0 || error1.text.equals("FILE_TOKEN_INVALID")
                                        || error1.text.equals("REQUEST_TOKEN_INVALID")
                                        || error1.text.startsWith("CDN_")) {""",
        "FileLoadOperation.java CDN reupload fallback to origin DC",
    )

    # 8. FileLoader.java: stop blanket priority = PRIORITY_HIGH and setForceRequest(true) on every small load.
    file_loader = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "FileLoader.java"
    )
    eol_patch_fn(
        file_loader,
        """        if (priority != PRIORITY_STREAM && priority != PRIORITY_LOW) {
            boolean isAvatarOrSmallImage = parentObject instanceof TLRPC.User
                    || parentObject instanceof TLRPC.UserFull
                    || parentObject instanceof TLRPC.Chat
                    || parentObject instanceof TLRPC.ChatFull
                    || parentObject instanceof TLRPC.Dialog
                    || (imageLocation != null && (imageLocation.photoPeer != null || imageLocation.thumbSize != null || imageLocation.stickerSet != null))
                    || (location != null && cacheType != 10 && locationSize <= 256 * 1024);
            if (isAvatarOrSmallImage && priority < PRIORITY_HIGH) {
                priority = PRIORITY_HIGH;
            }
        }
        priority = getPriorityValue(priority);

        if (operation != null) {
            if (cacheType != 10 && operation.isPreloadVideoOperation()) {
                operation.setIsPreloadVideoOperation(false);
            }
            operation.setForceRequest(priority > 0);""",
        """        priority = getPriorityValue(priority);

        if (operation != null) {
            if (cacheType != 10 && operation.isPreloadVideoOperation()) {
                operation.setIsPreloadVideoOperation(false);
            }
            if (priority >= FileLoaderPriorityQueue.PRIORITY_VALUE_MAX) {
                operation.setForceRequest(true);
            }""",
        "FileLoader.java stop blanket PRIORITY_HIGH & setForceRequest storm",
    )

    # 9. FileLoaderPriorityQueue.java: safe concurrent operation caps (large=3, small=6).
    prio_queue = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "FileLoaderPriorityQueue.java"
    )
    eol_patch_fn(
        prio_queue,
        "            max = Math.max(max, type == TYPE_LARGE ? 8 : 24);",
        "            max = Math.max(max, type == TYPE_LARGE ? 3 : 6);",
        "FileLoaderPriorityQueue.java cap concurrent operations (3/6)",
    )

    # 10. ImageLoader.java: tame cacheOutQueue/cacheThumbOutQueue threads (2/1) and serialize Lottie .pcache2 precache.
    image_loader = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "ImageLoader.java"
    )
    if os.path.exists(image_loader):
        with open(image_loader, "r", encoding="utf-8", errors="ignore") as _il_f:
            _il_src = _il_f.read()
        if "int[] fitzModifier, boolean isSingleChannel" in _il_src:
            eol_patch_fn(
                image_loader,
                "            int[] fitzModifier, boolean isSingleChannel) {",
                "            int fitzModifier, boolean isSingleChannel) {",
                "ImageLoader.java upgrade fitzModifier parameter type",
            )
    eol_patch_fn(
        image_loader,
        """    private DispatchQueuePriority cacheOutQueue = new DispatchQueuePriority("cacheOutQueue", 4);
    private DispatchQueuePriority cacheThumbOutQueue = new DispatchQueuePriority("cacheThumbOutQueue", 2);""",
        """    private DispatchQueuePriority cacheOutQueue = new DispatchQueuePriority("cacheOutQueue", 2);
    private DispatchQueuePriority cacheThumbOutQueue = new DispatchQueuePriority("cacheThumbOutQueue", 1);
    private static final Object colgramLottiePrecacheLock = new Object();

    private static RLottieDrawable colgramCreateRLottieDrawable(
            File file, boolean compressed, int w, int h,
            BitmapsCache.CacheOptions cacheOptions, boolean limitFps,
            int fitzModifier, boolean isSingleChannel) {
        String json = compressed ? decompressGzip(file) : null;
        if (cacheOptions != null) {
            synchronized (colgramLottiePrecacheLock) {
                return new RLottieDrawable(file, json, w, h, cacheOptions, limitFps, null, fitzModifier, isSingleChannel);
            }
        }
        return new RLottieDrawable(file, json, w, h, null, limitFps, null, fitzModifier, isSingleChannel);
    }""",
        "ImageLoader.java queue thread count (2/1) and Lottie precache lock",
    )
    eol_patch_fn(
        image_loader,
        """                    if (compressed) {
                        lottieDrawable = new RLottieDrawable(cacheImage.finalFilePath, decompressGzip(cacheImage.finalFilePath), w, h, cacheOptions, limitFps, null, fitzModifier, isSingleChannel);
                    } else {
                        lottieDrawable = new RLottieDrawable(cacheImage.finalFilePath, null, w, h, cacheOptions, limitFps, null, fitzModifier, isSingleChannel);
                    }""",
        """                    lottieDrawable = colgramCreateRLottieDrawable(cacheImage.finalFilePath, compressed, w, h, cacheOptions, limitFps, fitzModifier, isSingleChannel);""",
        "ImageLoader.java synchronize Lottie .pcache2 construction",
    )
