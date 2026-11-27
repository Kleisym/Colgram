"""Track A Telegram-Src patches for bypass stability, background sync, reactions, and media loading."""
from __future__ import annotations

import os
import re


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
    """Apply all Track A patches cleanly and idempotently against Telegram-Src."""
    # 1. AndroidManifest.xml: restore POST_NOTIFICATIONS, harden backup & permissions,
    #    and strip unused Play/Wear/Referral/Clipboard tracking surfaces.
    manifest = os.path.join(repo_path, "TMessagesProj", "src", "main", "AndroidManifest.xml")
    eol_patch_fn(
        manifest,
        "    <!-- stripped POST_NOTIFICATIONS for Colgram privacy -->",
        '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
        "AndroidManifest.xml restore POST_NOTIFICATIONS",
    )
    _patch_eol_variants(
        eol_patch_fn,
        manifest,
        ['    <uses-permission android:name="android.permission.READ_CLIPBOARD"/>'],
        "    <!-- stripped clipboard permission for Colgram privacy -->",
        "AndroidManifest.xml strip READ_CLIPBOARD",
    )
    _patch_eol_variants(
        eol_patch_fn,
        manifest,
        [
            """        android:allowBackup="true"
        android:hasFragileUserData="true"
        android:restoreAnyVersion="true"
        android:backupAgent=".BackupAgent\""""
        ],
        """        android:allowBackup="false"
        android:hasFragileUserData="true"
        android:restoreAnyVersion="false\"""",
        "AndroidManifest.xml disable allowBackup and strip BackupAgent",
    )
    _patch_eol_variants(
        eol_patch_fn,
        manifest,
        [
            """        <receiver
            android:name=".SmsReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="com.google.android.gms.auth.api.phone.SMS_RETRIEVED"/>
            </intent-filter>
        </receiver>"""
        ],
        "        <!-- stripped sms retriever for Colgram privacy -->",
        "AndroidManifest.xml strip SmsReceiver",
    )
    _patch_eol_variants(
        eol_patch_fn,
        manifest,
        [
            """        <receiver android:name=".RefererReceiver" android:exported="true" android:permission="android.permission.INSTALL_PACKAGES">
            <intent-filter>
                <action android:name="com.android.vending.INSTALL_REFERRER" />
            </intent-filter>
        </receiver>"""
        ],
        "        <!-- stripped install referrer for Colgram privacy -->",
        "AndroidManifest.xml strip RefererReceiver",
    )
    _patch_eol_variants(
        eol_patch_fn,
        manifest,
        [
            """        <service
            android:name=".WearAuthListenerService"
            android:exported="true">
            <intent-filter>
                <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
                <data android:scheme="wear" android:host="*" android:pathPrefix="/tg-wear-auth/" />
            </intent-filter>
        </service>"""
        ],
        "        <!-- stripped wear auth listener for Colgram privacy -->",
        "AndroidManifest.xml strip WearAuthListenerService",
    )
    _patch_eol_variants(
        eol_patch_fn,
        manifest,
        ['        <meta-data android:name="com.google.android.gms.wallet.api.enabled" android:value="true" />'],
        "        <!-- stripped GMS wallet metadata for Colgram privacy -->",
        "AndroidManifest.xml strip GMS wallet metadata",
    )
    _patch_eol_variants(
        eol_patch_fn,
        manifest,
        ['        <meta-data android:name="com.google.android.gms.vision.DEPENDENCIES" android:value="face,barcode" />'],
        "        <!-- stripped GMS vision metadata for Colgram privacy -->",
        "AndroidManifest.xml strip GMS vision metadata",
    )
    _patch_eol_variants(
        eol_patch_fn,
        manifest,
        [
            """        <meta-data
            android:name="com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"
            android:value="org.telegram.messenger.chromecast.ChromecastOptionsProvider" />"""
        ],
        "        <!-- stripped GMS cast metadata for Colgram privacy -->",
        "AndroidManifest.xml strip GMS cast metadata",
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
    eol_patch_fn(
        app_loader,
        """            NotificationCenter.getGlobalInstance().addObserver((id, account, args) -> {
                if (id == NotificationCenter.screenStateChanged && isScreenOn) {
                    ensureCurrentNetworkGet(true);
                    refreshNetworkAndNotify();
                }
            }, NotificationCenter.screenStateChanged);""",
        """            NotificationCenter.getGlobalInstance().addObserver((id, account, args) -> {
                if (id == NotificationCenter.screenStateChanged && isScreenOn
                        && (!mainInterfacePaused || org.colgram.core.ColgramUiBridge.isAppInForeground())) {
                    ensureCurrentNetworkGet(true);
                    refreshNetworkAndNotify();
                }
            }, NotificationCenter.screenStateChanged);""",
        "ApplicationLoader.java gate screenStateChanged network refresh on foreground",
    )

    # 3. ConnectionsManager.java: expose colgramResetProxySignature(), volatile state fields,
    #    direct instant-send dispatch, and non-blocking checkConnection offload.
    conn_mgr = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "tgnet", "ConnectionsManager.java"
    )
    if os.path.isfile(conn_mgr):
        with open(conn_mgr, "r", encoding="utf-8", errors="ignore") as _cm_f:
            _cm_raw = _cm_f.read()
        _dup_sig_re = re.compile(
            r'(public static String colgramGetLastProxySignature\(\)\s*\{\s*return colgramLastProxySignature;\s*\})'
            r'(\r?\n){2,}[ \t]*public static void colgramResetProxySignature\(\)\s*\{\s*colgramLastProxySignature = null;\s*\}'
        )
        _cm_dedup, _cm_dedup_n = _dup_sig_re.subn(r'\1', _cm_raw, count=1)
        if _cm_dedup_n > 0 and _cm_dedup != _cm_raw:
            with open(conn_mgr, "w", encoding="utf-8", newline="") as _cm_f:
                _cm_f.write(_cm_dedup)
    _patch_eol_variants(
        eol_patch_fn,
        conn_mgr,
        [
            """    private static volatile String colgramLastProxySignature = null;

    public static void colgramResetProxySignature() {
        colgramLastProxySignature = null;
    }""",
            "    private static volatile String colgramLastProxySignature = null;",
        ],
        """    private static volatile String colgramLastProxySignature = null;

    public static void colgramResetProxySignature() {
        colgramLastProxySignature = null;
    }

    public static String colgramGetLastProxySignature() {
        return colgramLastProxySignature;
    }""",
        "ConnectionsManager.java add colgramResetProxySignature()",
    )
    eol_patch_fn(
        conn_mgr,
        """    private long lastPauseTime = System.currentTimeMillis();
    private boolean appPaused = true;
    private boolean isUpdating;
    private int connectionState;""",
        """    private volatile long lastPauseTime = System.currentTimeMillis();
    private volatile boolean appPaused = true;
    private volatile boolean isUpdating;
    private volatile int connectionState;""",
        "ConnectionsManager.java volatile state fields for direct instant send",
    )
    _patch_eol_variants(
        eol_patch_fn,
        conn_mgr,
        [
            """    public int sendRequest(final TLObject object, final RequestDelegate onComplete, final RequestDelegateTimestamp onCompleteTimestamp, final QuickAckDelegate onQuickAck, final WriteToSocketDelegate onWriteToSocket, final int flags, final int datacenterId, final int connectionType, final boolean immediate) {
        final int requestToken = lastRequestToken.getAndIncrement();
        Utilities.stageQueue.postRunnable(() -> {
            sendRequestInternal(object, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate, requestToken);
        });
        return requestToken;
    }""",
            """    private static boolean colgramIsDirectSendResponseSafe(TLObject object) {
        return object instanceof TLRPC.TL_messages_sendMessage
                || object instanceof TLRPC.TL_messages_sendMedia
                || object instanceof TLRPC.TL_messages_sendMultiMedia;
    }

    public int sendRequest(final TLObject object, final RequestDelegate onComplete, final RequestDelegateTimestamp onCompleteTimestamp, final QuickAckDelegate onQuickAck, final WriteToSocketDelegate onWriteToSocket, final int flags, final int datacenterId, final int connectionType, final boolean immediate) {
        final int requestToken = lastRequestToken.getAndIncrement();
        if (!ColgramGuestProxyService.inProbeProcess() && colgramIsInstantSendRequest(object)) {
            sendRequestInternal(object, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate, requestToken);
            return requestToken;
        }
        Utilities.stageQueue.postRunnable(() -> {
            sendRequestInternal(object, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate, requestToken);
        });
        return requestToken;
    }""",
            """    private static boolean colgramIsDirectSendResponseSafe(TLObject object) {
        if (object instanceof TLRPC.TL_messages_sendMedia) {
            return !(((TLRPC.TL_messages_sendMedia) object).media instanceof TLRPC.TL_inputMediaGame);
        }
        return object instanceof TLRPC.TL_messages_sendMessage
                || object instanceof TLRPC.TL_messages_sendMultiMedia;
    }

    public int sendRequest(final TLObject object, final RequestDelegate onComplete, final RequestDelegateTimestamp onCompleteTimestamp, final QuickAckDelegate onQuickAck, final WriteToSocketDelegate onWriteToSocket, final int flags, final int datacenterId, final int connectionType, final boolean immediate) {
        final int requestToken = lastRequestToken.getAndIncrement();
        if (!ColgramGuestProxyService.inProbeProcess() && colgramIsInstantSendRequest(object)) {
            sendRequestInternal(object, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate, requestToken);
            return requestToken;
        }
        Utilities.stageQueue.postRunnable(() -> {
            sendRequestInternal(object, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate, requestToken);
        });
        return requestToken;
    }""",
        ],
        """    private static boolean colgramIsDirectSendResponseSafe(TLObject object) {
        if (object instanceof TLRPC.TL_messages_sendMedia) {
            return !(((TLRPC.TL_messages_sendMedia) object).media instanceof TLRPC.TL_inputMediaGame);
        }
        return object instanceof TLRPC.TL_messages_sendMessage
                || object instanceof TLRPC.TL_messages_sendMultiMedia;
    }

    public int sendRequest(final TLObject object, final RequestDelegate onComplete, final RequestDelegateTimestamp onCompleteTimestamp, final QuickAckDelegate onQuickAck, final WriteToSocketDelegate onWriteToSocket, final int flags, final int datacenterId, final int connectionType, final boolean immediate) {
        final int requestToken = lastRequestToken.getAndIncrement();
        if (!ColgramGuestProxyService.inProbeProcess() && colgramIsInstantSendRequest(object)) {
            sendRequestInternal(object, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate, requestToken);
            return requestToken;
        }
        final boolean colgramAllowSpamMentionClear = org.colgram.core.ColgramGhostMode.isAllowingSpamMentionClear();
        Utilities.stageQueue.postRunnable(() -> {
            if (colgramAllowSpamMentionClear) {
                org.colgram.core.ColgramGhostMode.runAllowingSpamMentionClear(() ->
                        sendRequestInternal(object, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate, requestToken));
            } else {
                sendRequestInternal(object, onComplete, onCompleteTimestamp, onQuickAck, onWriteToSocket, flags, datacenterId, connectionType, immediate, requestToken);
            }
        });
        return requestToken;
    }""",
        "ConnectionsManager.java bypass stageQueue on outbound instant send requests and propagate Contract A read exemption",
    )
    if os.path.isfile(conn_mgr):
        with open(conn_mgr, "r", encoding="utf-8", errors="ignore") as _cm_f:
            _cm_txt = _cm_f.read()
        if "colgramShouldDropFirewallRequest" in _cm_txt:
            eol_patch_fn(
                conn_mgr,
                """    private static TLObject colgramSyntheticFirewallResponse(TLObject object) {
        if (object instanceof TLRPC.TL_messages_getSponsoredMessages) {
            return new TLRPC.TL_messages_sponsoredMessages();
        }
        if (object instanceof TLRPC.TL_messages_readHistory
                || object instanceof TLRPC.TL_messages_readMessageContents
                || object instanceof TLRPC.TL_channels_readMessageContents) {
            return new TLRPC.TL_messages_affectedMessages();
        }
        if (object instanceof TLRPC.TL_channels_readHistory
                || object instanceof TLRPC.TL_messages_readSavedHistory
                || object instanceof TLRPC.TL_messages_readMentions
                || object instanceof TLRPC.TL_messages_readReactions) {
            return new TLRPC.TL_messages_affectedHistory();
        }
        return new TLRPC.TL_boolTrue();
    }""",
                """    private static TLObject colgramSyntheticFirewallResponse(TLObject object) {
        if (object instanceof TLRPC.TL_messages_getSponsoredMessages) {
            return new TLRPC.TL_messages_sponsoredMessages();
        }
        if (object instanceof TLRPC.TL_contacts_getSponsoredPeers) {
            return new TLRPC.TL_contacts_sponsoredPeersEmpty();
        }
        if (object instanceof org.telegram.tgnet.tl.TL_stories.TL_stories_readStories) {
            return new org.telegram.tgnet.Vector<>(org.telegram.tgnet.Vector.Int::TLDeserialize);
        }
        if (object instanceof TLRPC.TL_messages_readPollVotes
                || object instanceof TLRPC.TL_messages_readMentions
                || object instanceof TLRPC.TL_messages_readReactions) {
            return new TLRPC.TL_messages_affectedHistory();
        }
        if (object instanceof TLRPC.TL_messages_readHistory
                || object instanceof TLRPC.TL_messages_readMessageContents) {
            return new TLRPC.TL_messages_affectedMessages();
        }
        if (object instanceof TLRPC.TL_channels_readHistory
                || object instanceof TLRPC.TL_messages_readSavedHistory
                || object instanceof TLRPC.TL_channels_readMessageContents) {
            return new TLRPC.TL_boolTrue();
        }
        return new TLRPC.TL_boolTrue();
    }""",
                "ConnectionsManager.java fix synthetic firewall TL response types",
            )
    _patch_eol_variants(
        eol_patch_fn,
        conn_mgr,
        [
            """    private void sendRequestInternal(TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp, QuickAckDelegate onQuickAck, WriteToSocketDelegate onWriteToSocket, int flags, int datacenterId, int connectionType, boolean immediate, int requestToken) {
        if (object instanceof org.telegram.tgnet.tl.TL_account.updateStatus"""
        ],
        """    private static boolean colgramShouldDropFirewallRequest(TLObject object) {
        if (object == null) return false;
        if (object instanceof TLRPC.TL_help_saveAppLog
                || object instanceof TLRPC.TL_messages_viewSponsoredMessage) {
            return true;
        }
        if (object instanceof org.telegram.tgnet.tl.TL_account.updateDeviceLocked
                && (org.colgram.core.ColgramConfig.isCloakEnabled()
                        || org.colgram.core.ColgramConfig.isMaxAnonymityEnabled())) {
            return true;
        }
        if ((object instanceof TLRPC.TL_messages_getSponsoredMessages
                || object instanceof TLRPC.TL_contacts_getSponsoredPeers)
                && org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled()) {
            return true;
        }
        if (org.colgram.core.ColgramConfig.isGhostTypingEnabled()
                && object instanceof TLRPC.TL_messages_setTyping) {
            return true;
        }
        if (org.colgram.core.ColgramConfig.isStealthStoriesEnabled()
                && object instanceof org.telegram.tgnet.tl.TL_stories.TL_stories_readStories) {
            return true;
        }
        if (org.colgram.core.ColgramConfig.isHideScreenshotNotifyEnabled()
                && object instanceof TLRPC.TL_messages_sendScreenshotNotification) {
            return true;
        }
        if (org.colgram.core.ColgramConfig.isGhostReadEnabled()
                && !org.colgram.core.ColgramGhostMode.isAllowingSpamMentionClear()) {
            if (object instanceof TLRPC.TL_messages_readHistory
                    || object instanceof TLRPC.TL_channels_readHistory
                    || object instanceof TLRPC.TL_messages_readSavedHistory
                    || object instanceof TLRPC.TL_messages_readMessageContents
                    || object instanceof TLRPC.TL_channels_readMessageContents
                    || object instanceof TLRPC.TL_messages_readMentions
                    || object instanceof TLRPC.TL_messages_readReactions
                    || object instanceof TLRPC.TL_messages_readPollVotes) {
                return true;
            }
        }
        return false;
    }

    private static TLObject colgramSyntheticFirewallResponse(TLObject object) {
        if (object instanceof TLRPC.TL_messages_getSponsoredMessages) {
            return new TLRPC.TL_messages_sponsoredMessages();
        }
        if (object instanceof TLRPC.TL_contacts_getSponsoredPeers) {
            return new TLRPC.TL_contacts_sponsoredPeersEmpty();
        }
        if (object instanceof org.telegram.tgnet.tl.TL_stories.TL_stories_readStories) {
            return new org.telegram.tgnet.Vector<>(org.telegram.tgnet.Vector.Int::TLDeserialize);
        }
        if (object instanceof TLRPC.TL_messages_readPollVotes
                || object instanceof TLRPC.TL_messages_readMentions
                || object instanceof TLRPC.TL_messages_readReactions) {
            return new TLRPC.TL_messages_affectedHistory();
        }
        if (object instanceof TLRPC.TL_messages_readHistory
                || object instanceof TLRPC.TL_messages_readMessageContents) {
            return new TLRPC.TL_messages_affectedMessages();
        }
        if (object instanceof TLRPC.TL_channels_readHistory
                || object instanceof TLRPC.TL_messages_readSavedHistory
                || object instanceof TLRPC.TL_channels_readMessageContents) {
            return new TLRPC.TL_boolTrue();
        }
        return new TLRPC.TL_boolTrue();
    }

    private void sendRequestInternal(TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp, QuickAckDelegate onQuickAck, WriteToSocketDelegate onWriteToSocket, int flags, int datacenterId, int connectionType, boolean immediate, int requestToken) {
        if (object instanceof TLRPC.TL_messages_getMessagesViews
                && org.colgram.core.ColgramConfig.isGhostReadEnabled()) {
            ((TLRPC.TL_messages_getMessagesViews) object).increment = false;
        }
        if (colgramShouldDropFirewallRequest(object)) {
            final TLObject syntheticResp = colgramSyntheticFirewallResponse(object);
            object.freeResources();
            if (onComplete != null || onCompleteTimestamp != null) {
                Utilities.stageQueue.postRunnable(() -> {
                    if (onComplete != null) {
                        onComplete.run(syntheticResp, null);
                    } else if (onCompleteTimestamp != null) {
                        onCompleteTimestamp.run(syntheticResp, null, 0L);
                    }
                });
            }
            return;
        }
        if (object instanceof org.telegram.tgnet.tl.TL_account.updateStatus""",
        "ConnectionsManager.java centralized MTProto privacy and telemetry firewall",
    )
    _patch_eol_variants(
        eol_patch_fn,
        conn_mgr,
        [
            """        if (org.colgram.core.ColgramConfig.isCloakEnabled()) {
            installer = "com.android.vending";
        }""",
            """        if (org.colgram.core.ColgramConfig.isCloakEnabled()) {
            installer = "com.android.vending";
            packageId = "org.telegram.messenger";
            regId = "";
            cFingerprint = "";
            timezoneOffset = 0;
        }""",
        ],
        """        if (org.colgram.core.ColgramConfig.isCloakEnabled()) {
            installer = "com.android.vending";
            packageId = "org.telegram.messenger";
            if (!enablePushConnection) {
                regId = "";
            }
            cFingerprint = "";
            timezoneOffset = 0;
        }""",
        "ConnectionsManager.java full native_init parameter cloaking",
    )
    _patch_eol_variants(
        eol_patch_fn,
        conn_mgr,
        [
            """    public static int getInitFlags() {
        if (ColgramGuestProxyService.inProbeProcess()) return 0;
        int flags = 0;
        EmuDetector detector = EmuDetector.with(ApplicationLoader.applicationContext);"""
        ],
        """    public static int getInitFlags() {
        if (ColgramGuestProxyService.inProbeProcess()) return 0;
        if (org.colgram.core.ColgramConfig.isCloakEnabled()) return 0;
        int flags = 0;
        EmuDetector detector = EmuDetector.with(ApplicationLoader.applicationContext);""",
        "ConnectionsManager.java skip EmuDetector when cloaked",
    )
    _patch_eol_variants(
        eol_patch_fn,
        conn_mgr,
        [
            """            if (org.colgram.core.ColgramConfig.isDohEnabled()) {
                try {
                    java.net.InetAddress[] doh = org.colgram.core.ColgramDohResolver.resolve(currentHostName);
                    if (doh != null && doh.length > 0) {
                        ArrayList<String> addresses = new ArrayList<>(doh.length);
                        for (java.net.InetAddress addr : doh) {
                            if (addr != null) addresses.add(addr.getHostAddress());
                        }
                        if (!addresses.isEmpty()) {
                            return new ResolvedDomain(addresses, SystemClock.elapsedRealtime());
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (org.colgram.core.ColgramConfig.isDohEnabled()
                    && (org.colgram.core.ColgramConfig.isStrictProxySecurityEnabled()
                            || org.colgram.core.ColgramConfig.isMaxAnonymityEnabled())) {
                try {
                    InetAddress[] doh = org.colgram.core.ColgramDohResolver.resolve(currentHostName);
                    if (doh != null && doh.length > 0) {
                        ArrayList<String> addresses = new ArrayList<>(doh.length);
                        for (InetAddress addr : doh) {
                            if (addr != null) addresses.add(addr.getHostAddress());
                        }
                        if (!addresses.isEmpty()) {
                            return new ResolvedDomain(addresses, SystemClock.elapsedRealtime());
                        }
                    }
                } catch (Throwable ignored) {}
                return null;
            }
            ByteArrayOutputStream outbuf = null;
            InputStream httpConnectionStream = null;
            boolean done = false;
            try {
                URL downloadUrl = new URL("https://www.google.com/resolve?name=" + currentHostName + "&type=A");""",
            """            if (org.colgram.core.ColgramConfig.isDohEnabled()
                    && (org.colgram.core.ColgramConfig.isStrictProxySecurityEnabled()
                            || org.colgram.core.ColgramConfig.isMaxAnonymityEnabled())) {
                try {
                    InetAddress[] doh = org.colgram.core.ColgramDohResolver.resolve(currentHostName);
                    if (doh != null && doh.length > 0) {
                        ArrayList<String> addresses = new ArrayList<>(doh.length);
                        for (InetAddress addr : doh) {
                            if (addr != null) addresses.add(addr.getHostAddress());
                        }
                        if (!addresses.isEmpty()) {
                            return new ResolvedDomain(addresses, SystemClock.elapsedRealtime());
                        }
                    }
                } catch (Throwable ignored) {}
                return null;
            }
            ByteArrayOutputStream outbuf = null;
            InputStream httpConnectionStream = null;
            boolean done = false;
            try {
                URL downloadUrl = new URL("https://www.google.com/resolve?name=" + currentHostName + "&type=A");""",
            """            ByteArrayOutputStream outbuf = null;
            InputStream httpConnectionStream = null;
            boolean done = false;
            try {
                URL downloadUrl = new URL("https://www.google.com/resolve?name=" + currentHostName + "&type=A");""",
        ],
        """            if (org.colgram.core.ColgramConfig.isDohEnabled()) {
                try {
                    InetAddress[] doh = org.colgram.core.ColgramConfig.isMaxAnonymityEnabled()
                            ? org.colgram.core.ColgramDohResolver.resolve(currentHostName)
                            : org.colgram.core.ColgramDohResolver.resolveOrSystem(currentHostName);
                    if (doh != null && doh.length > 0) {
                        ArrayList<String> addresses = new ArrayList<>(doh.length);
                        for (InetAddress addr : doh) {
                            if (addr != null) addresses.add(addr.getHostAddress());
                        }
                        if (!addresses.isEmpty()) {
                            return new ResolvedDomain(addresses, SystemClock.elapsedRealtime());
                        }
                    }
                } catch (Throwable ignored) {}
                if (org.colgram.core.ColgramConfig.isMaxAnonymityEnabled()
                        || org.colgram.core.ColgramConfig.isStrictProxySecurityEnabled()) {
                    return null;
                }
            }
            ByteArrayOutputStream outbuf = null;
            InputStream httpConnectionStream = null;
            boolean done = false;
            try {
                URL downloadUrl = new URL("https://www.google.com/resolve?name=" + currentHostName + "&type=A");""",
        "ConnectionsManager.java block direct google.com/resolve and system DNS in ResolveHostByNameTask",
    )
    _patch_eol_variants(
        eol_patch_fn,
        conn_mgr,
        [
            """            URLConnection conn = url.openConnection();
            conn.addRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 10_0 like Mac OS X) AppleWebKit/602.1.38 (KHTML, like Gecko) Version/10.0 Mobile/14A5297c Safari/602.1");"""
        ],
        """            if (org.colgram.core.ColgramConfig.isStrictProxySecurityEnabled()
                    || org.colgram.core.ColgramConfig.isMaxAnonymityEnabled()) {
                throw new java.io.IOException("Direct DNS endpoint blocked under strict privacy policy");
            }
            URLConnection conn = url.openConnection();
            conn.addRequestProperty("User-Agent", org.colgram.core.ColgramPolymorphicStealth.pickBrowserUserAgent());""",
        "ConnectionsManager.java block direct openColgramBypassDnsConnection fallback under strict privacy",
    )
    _patch_eol_variants(
        eol_patch_fn,
        conn_mgr,
        [
            """    public static void onIntegrityCheckClassic(final int currentAccount, final int requestToken, final String project, final String nonce) {
        if (ColgramGuestProxyService.inProbeProcess()) return;
        AndroidUtilities.runOnUIThread(() -> {"""
        ],
        """    public static void onIntegrityCheckClassic(final int currentAccount, final int requestToken, final String project, final String nonce) {
        if (ColgramGuestProxyService.inProbeProcess()) return;
        if (org.colgram.core.ColgramConfig.isCloakEnabled() || org.colgram.core.ColgramConfig.isMaxAnonymityEnabled()) {
            native_receivedIntegrityCheckClassic(currentAccount, requestToken, nonce, "PLAYINTEGRITY_FAILED_EXCEPTION_UNAVAILABLE");
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {""",
        "ConnectionsManager.java stub Play Integrity when cloaked",
    )
    _patch_eol_variants(
        eol_patch_fn,
        conn_mgr,
        [
            """        if (colgramInstantWake) {
            if (lastPauseTime != 0 || appPaused) {
                resumeNetworkMaybe();
            }
            if (connectionState != ConnectionStateConnected) {
                checkConnection();
            }
            if (!org.colgram.core.ColgramDpiBypass.isBound()
                    && org.colgram.core.ColgramConfig.isBuiltinProxyEnabled()) {
                try {
                    org.colgram.core.ColgramDpiBypass.restartIfDead();
                } catch (Throwable ignored) {}
            }
        }""",
        ],
        """        if (colgramInstantWake && (lastPauseTime != 0 || appPaused)) {
            resumeNetworkMaybe();
        }""",
        "ConnectionsManager.java keep pre-send instant wake non-blocking",
    )
    eol_patch_fn(
        conn_mgr,
        """            native_sendRequest(currentAccount, buffer.address, flags, datacenterId, connectionType, immediate, requestToken);
        } catch (Exception e) {""",
        """            native_sendRequest(currentAccount, buffer.address, flags, datacenterId, connectionType, immediate, requestToken);
            if (colgramInstantWake) {
                final boolean colgramNeedConnCheck = colgramIsRawDisconnected();
                final boolean colgramNeedBypassWake = !org.colgram.core.ColgramDpiBypass.isBound()
                        && org.colgram.core.ColgramConfig.isBuiltinProxyEnabled();
                if (colgramNeedConnCheck || colgramNeedBypassWake) {
                    Utilities.globalQueue.postRunnable(() -> {
                        if (colgramNeedConnCheck) {
                            checkConnection();
                        }
                        if (colgramNeedBypassWake) {
                            try {
                                org.colgram.core.ColgramDpiBypass.restartIfDead();
                            } catch (Throwable ignored) {}
                        }
                    });
                }
            }
        } catch (Exception e) {""",
        "ConnectionsManager.java offload checkConnection and bypass restart after native_sendRequest",
    )
    eol_patch_fn(
        conn_mgr,
        """                    final TLObject finalResponse = resp;
                    final TLRPC.TL_error finalError = error;
                    Utilities.stageQueue.postRunnable(() -> {
                        if (onComplete != null) {
                            onComplete.run(finalResponse, finalError);
                        } else if (onCompleteTimestamp != null) {
                            onCompleteTimestamp.run(finalResponse, finalError, timestamp);
                        } else if (finalResponse instanceof TLRPC.Updates) {
                            KeepAliveJob.finishJob();
                            AccountInstance.getInstance(currentAccount).getMessagesController().processUpdates((TLRPC.Updates) finalResponse, false);
                        }
                        if (finalResponse != null) {
                            finalResponse.freeResources();
                        }
                        if (colgramTriggersOnline && finalError == null && org.colgram.core.ColgramHookHandler.shouldStayOffline()) {
                            AccountInstance.getInstance(currentAccount).getMessagesController().scheduleGhostOfflineAfterActivity();
                        }
                    });""",
        """                    final TLObject finalResponse = resp;
                    final TLRPC.TL_error finalError = error;
                    if (finalError == null && onComplete != null && colgramIsDirectSendResponseSafe(object)) {
                        onComplete.run(finalResponse, null);
                        if (colgramTriggersOnline && org.colgram.core.ColgramHookHandler.shouldStayOffline()) {
                            Utilities.stageQueue.postRunnable(() -> AccountInstance.getInstance(currentAccount).getMessagesController().scheduleGhostOfflineAfterActivity());
                        }
                    } else {
                        Utilities.stageQueue.postRunnable(() -> {
                            if (onComplete != null) {
                                onComplete.run(finalResponse, finalError);
                            } else if (onCompleteTimestamp != null) {
                                onCompleteTimestamp.run(finalResponse, finalError, timestamp);
                            } else if (finalResponse instanceof TLRPC.Updates) {
                                KeepAliveJob.finishJob();
                                AccountInstance.getInstance(currentAccount).getMessagesController().processUpdates((TLRPC.Updates) finalResponse, false);
                            }
                            if (finalResponse != null) {
                                finalResponse.freeResources();
                            }
                            if (colgramTriggersOnline && finalError == null && org.colgram.core.ColgramHookHandler.shouldStayOffline()) {
                                AccountInstance.getInstance(currentAccount).getMessagesController().scheduleGhostOfflineAfterActivity();
                            }
                        });
                    }""",
        "ConnectionsManager.java bypass stageQueue on inbound send response completion",
    )
    eol_patch_fn(
        conn_mgr,
        """    public int getConnectionState() {
        if (connectionState == ConnectionStateConnected && isUpdating) {
            return ConnectionStateUpdating;
        }
        return connectionState;
    }""",
        """    public int getConnectionState() {
        if (connectionState == ConnectionStateConnected && isUpdating) {
            return ConnectionStateUpdating;
        }
        return connectionState;
    }

    public boolean colgramIsRawDisconnected() {
        return connectionState != ConnectionStateConnected;
    }""",
        "ConnectionsManager.java add colgramIsRawDisconnected()",
    )

    # 3b. SendMessagesHelper.java: keep UI-thread instant-send preparation non-blocking and
    #     remove redundant second messageReceivedByServer broadcasts after storageQueue.
    send_helper = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "SendMessagesHelper.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        send_helper,
        [
            """        ConnectionsManager connectionsManager = getConnectionsManager();
        if (connectionsManager.getPauseTime() != 0 || connectionsManager.getConnectionState() != ConnectionsManager.ConnectionStateConnected) {
            connectionsManager.resumeNetworkMaybe();
            connectionsManager.checkConnection();
        }""",
        ],
        """        ConnectionsManager connectionsManager = getConnectionsManager();
        if (connectionsManager.getPauseTime() != 0) {
            connectionsManager.resumeNetworkMaybe();
        }
        if (connectionsManager.colgramIsRawDisconnected()) {
            Utilities.globalQueue.postRunnable(connectionsManager::checkConnection);
        }""",
        "SendMessagesHelper.java make colgramPrepareInstantSend() non-blocking on UI thread",
    )
    eol_patch_fn(
        send_helper,
        """                                    getMediaDataController().increasePeerRaiting(newMsgObj.dialog_id);
                                    getNotificationCenter().postNotificationName(NotificationCenter.messageReceivedByServer, oldId, newMsgObj.id, newMsgObj, newMsgObj.dialog_id, grouped_id, existFlags, finalCurrentSchedule);
                                    getNotificationCenter().postNotificationName(NotificationCenter.messageReceivedByServer2, oldId, newMsgObj.id, newMsgObj, newMsgObj.dialog_id, grouped_id, existFlags, finalCurrentSchedule);
                                    processSentMessage(oldId);""",
        """                                    getMediaDataController().increasePeerRaiting(newMsgObj.dialog_id);
                                    processSentMessage(oldId);""",
        "SendMessagesHelper.java remove redundant second messageReceivedByServer in performSendMessageRequestMulti",
    )
    eol_patch_fn(
        send_helper,
        """                                    AndroidUtilities.runOnUIThread(() -> {
                                        getMediaDataController().increasePeerRaiting(newMsgObj.dialog_id);
                                        getNotificationCenter().postNotificationName(NotificationCenter.messageReceivedByServer, oldId, newMsgObj.id, newMsgObj, newMsgObj.dialog_id, 0L, existFlags, scheduled);
                                        getNotificationCenter().postNotificationName(NotificationCenter.messageReceivedByServer2, oldId, newMsgObj.id, newMsgObj, newMsgObj.dialog_id, 0L, existFlags, scheduled);
                                        processSentMessage(oldId);
                                        removeFromSendingMessages(oldId, scheduled);
                                    });""",
        """                                    AndroidUtilities.runOnUIThread(() -> {
                                        getMediaDataController().increasePeerRaiting(newMsgObj.dialog_id);
                                        processSentMessage(oldId);
                                        removeFromSendingMessages(oldId, scheduled);
                                    });""",
        "SendMessagesHelper.java remove redundant second messageReceivedByServer in performSendMessageRequest",
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
    _patch_eol_variants(
        eol_patch_fn,
        file_load_op,
        [
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
            downloadChunkSize = isSmallOrThumbOrAvatar ? (totalBytesCount >= 1024 * 32 ? 1024 * 64 : 1024 * 32) : 1024 * 128;
            downloadChunkSizeBig = totalBytesCount >= 1024 * 1024 ? 1024 * 512 : 1024 * 128;
            maxDownloadRequests = 4;
            maxDownloadRequestsBig = 4;
            maxDownloadRequestsAnimation = Math.max(maxDownloadRequestsAnimation, 6);""",
        ],
        """        boolean isSmallOrThumbOrAvatar = !allowDisordererFileSave
                || location instanceof TLRPC.TL_inputPeerPhotoFileLocation
                || location instanceof TLRPC.TL_inputStickerSetThumb
                || location instanceof TLRPC.TL_inputPhotoFileLocation
                || totalBytesCount <= 0
                || totalBytesCount < 256 * 1024;
        if (boost && !forceSmallChunk) {
            downloadChunkSize = isSmallOrThumbOrAvatar ? (totalBytesCount >= 1024 * 32 ? 1024 * 64 : 1024 * 32) : 1024 * 128;
            downloadChunkSizeBig = totalBytesCount >= 1024 * 1024 ? 1024 * 512 : 1024 * 128;
            maxDownloadRequests = 6;
            maxDownloadRequestsBig = 6;
            maxDownloadRequestsAnimation = Math.max(maxDownloadRequestsAnimation, 6);""",
        "FileLoadOperation.java safe chunk sizes (64KB >=32KB) and upgraded request caps (6/6/6)",
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
    _patch_eol_variants(
        eol_patch_fn,
        file_load_op,
        [
            """                    req.cdn_supported = !(org.colgram.core.ColgramDpiBypass.isBound()
                            && org.colgram.core.ColgramTelegramWsBridge.hasWorkedOnCurrentNetwork());""",
            """                    req.cdn_supported = true;""",
        ],
        """                    req.cdn_supported = !org.colgram.core.ColgramDpiBypass.isBound();""",
        "FileLoadOperation.java bypass CDN DC 203 when local DPI bypass bound",
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
    with open(file_loader, "r", encoding="utf-8", errors="replace") as _fl_f:
        _fl_prio_src = _fl_f.read()
    if "isAvatarOrSmallImage" in _fl_prio_src:
        eol_patch_fn(
            file_loader,
            """        FileLoadOperation operation = loadOperationPaths.get(finalFileName);

        if (priority != PRIORITY_STREAM && priority != PRIORITY_LOW) {
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
        priority = getPriorityValue(priority);""",
            """        FileLoadOperation operation = loadOperationPaths.get(finalFileName);

        priority = getPriorityValue(priority);""",
            "FileLoader.java stop blanket PRIORITY_HIGH",
        )
    eol_patch_fn(
        file_loader,
        """        if (operation != null) {
            if (cacheType != 10 && operation.isPreloadVideoOperation()) {
                operation.setIsPreloadVideoOperation(false);
            }
            operation.setForceRequest(priority > 0);""",
        """        if (operation != null) {
            if (cacheType != 10 && operation.isPreloadVideoOperation()) {
                operation.setIsPreloadVideoOperation(false);
            }
            if (priority >= FileLoaderPriorityQueue.PRIORITY_VALUE_MAX) {
                operation.setForceRequest(true);
            }""",
        "FileLoader.java gate setForceRequest on PRIORITY_VALUE_MAX",
    )
    eol_patch_fn(
        file_loader,
        "        if (operation.totalBytesCount > 20 * 1024 * 1024 || isStory) {",
        "        if (operation.totalBytesCount > 2 * 1024 * 1024 || isStory) {",
        "FileLoader.java split smallFilesQueue vs largeFilesQueue at 2MB for instant avatars",
    )

    # 9. FileLoaderPriorityQueue.java: instant avatar/sticker concurrent ops (large=3, small=12).
    prio_queue = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "FileLoaderPriorityQueue.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        prio_queue,
        [
            "            max = Math.max(max, type == TYPE_LARGE ? 3 : 6);",
            "            max = Math.max(max, type == TYPE_LARGE ? 8 : 24);",
        ],
        "            max = Math.max(max, type == TYPE_LARGE ? 3 : 12);",
        "FileLoaderPriorityQueue.java boost small queue concurrent operations (3/12)",
    )

    # 10. ImageLoader.java: cacheOutQueue/cacheThumbOutQueue threads (3/2) + dedicated Lottie queue.
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
    _patch_eol_variants(
        eol_patch_fn,
        image_loader,
        [
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
            """    private DispatchQueuePriority cacheOutQueue = new DispatchQueuePriority("cacheOutQueue", 4);
    private DispatchQueuePriority cacheThumbOutQueue = new DispatchQueuePriority("cacheThumbOutQueue", 2);
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
            """    private DispatchQueuePriority cacheOutQueue = new DispatchQueuePriority("cacheOutQueue", 4);
    private DispatchQueuePriority cacheThumbOutQueue = new DispatchQueuePriority("cacheThumbOutQueue", 2);""",
            """    private DispatchQueuePriority cacheOutQueue = new DispatchQueuePriority("cacheOutQueue", 2);
    private DispatchQueuePriority cacheThumbOutQueue = new DispatchQueuePriority("cacheThumbOutQueue", 1);""",
            """    private DispatchQueuePriority cacheOutQueue = new DispatchQueuePriority("cacheOutQueue", 3);
    private DispatchQueuePriority cacheThumbOutQueue = new DispatchQueuePriority("cacheThumbOutQueue", 2);
    private DispatchQueuePriority colgramLottieOutQueue = new DispatchQueuePriority("colgramLottieOutQueue", 1);
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
        ],
        """    private DispatchQueuePriority cacheOutQueue = new DispatchQueuePriority("cacheOutQueue", 3);
    private DispatchQueuePriority cacheThumbOutQueue = new DispatchQueuePriority("cacheThumbOutQueue", 2);
    private DispatchQueuePriority colgramLottieOutQueue = new DispatchQueuePriority("colgramLottieOutQueue", 2);
    private static final Object[] colgramLottiePrecacheLocks = colgramCreatePrecacheLocks(8);

    private static Object[] colgramCreatePrecacheLocks(int count) {
        Object[] locks = new Object[count];
        for (int i = 0; i < count; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private static Object colgramLottiePrecacheLockFor(File file) {
        if (file == null) return colgramLottiePrecacheLocks[0];
        int h = file.getAbsolutePath().hashCode();
        return colgramLottiePrecacheLocks[(h & 0x7fffffff) % colgramLottiePrecacheLocks.length];
    }

    private static RLottieDrawable colgramCreateRLottieDrawable(
            File file, boolean compressed, int w, int h,
            BitmapsCache.CacheOptions cacheOptions, boolean limitFps,
            int fitzModifier, boolean isSingleChannel) {
        String json = compressed ? decompressGzip(file) : null;
        if (cacheOptions != null) {
            final Object colgramLottiePrecacheLock = colgramLottiePrecacheLockFor(file);
            synchronized (colgramLottiePrecacheLock) {
                return new RLottieDrawable(file, json, w, h, cacheOptions, limitFps, null, fitzModifier, isSingleChannel);
            }
        }
        return new RLottieDrawable(file, json, w, h, null, limitFps, null, fitzModifier, isSingleChannel);
    }""",
        "ImageLoader.java queue thread count (3/2), 2-thread Lottie queue, and striped per-path precache locks",
    )
    eol_patch_fn(
        image_loader,
        """                                if (task.cacheImage.type == ImageReceiver.TYPE_THUMB) {
                                    cacheThumbOutQueue.postRunnable(task, task.cacheImage.priority);
                                } else {
                                    cacheOutQueue.postRunnable(task, task.cacheImage.priority);
                                }
                            }
                        });
                    }
                    long currentTime = SystemClock.elapsedRealtime();""",
        """                                if (task.cacheImage.type == ImageReceiver.TYPE_THUMB) {
                                    cacheThumbOutQueue.postRunnable(task, task.cacheImage.priority);
                                } else if (task.cacheImage.imageType == FileLoader.IMAGE_TYPE_LOTTIE) {
                                    colgramLottieOutQueue.postRunnable(task, task.cacheImage.priority);
                                } else {
                                    cacheOutQueue.postRunnable(task, task.cacheImage.priority);
                                }
                            }
                        });
                    }
                    long currentTime = SystemClock.elapsedRealtime();""",
        "ImageLoader.java route Lottie load tasks onto dedicated colgramLottieOutQueue",
    )
    eol_patch_fn(
        image_loader,
        """                        if (thumb != 0) {
                            cacheThumbOutQueue.postRunnable(img.cacheTask, img.priority);
                        } else {
                            img.runningTask = cacheOutQueue.postRunnable(img.cacheTask, img.priority);
                        }""",
        """                        if (thumb != 0) {
                            cacheThumbOutQueue.postRunnable(img.cacheTask, img.priority);
                        } else if (img.imageType == FileLoader.IMAGE_TYPE_LOTTIE) {
                            img.runningTask = colgramLottieOutQueue.postRunnable(img.cacheTask, img.priority);
                        } else {
                            img.runningTask = cacheOutQueue.postRunnable(img.cacheTask, img.priority);
                        }""",
        "ImageLoader.java route cached Lottie decodes onto dedicated colgramLottieOutQueue",
    )
    eol_patch_fn(
        image_loader,
        """                if (task.cacheImage.type == ImageReceiver.TYPE_THUMB) {
                    cacheThumbOutQueue.postRunnable(task, task.cacheImage.priority);
                } else {
                    cacheOutQueue.postRunnable(task, task.cacheImage.priority);
                }
            }
        });
    }""",
        """                if (task.cacheImage.type == ImageReceiver.TYPE_THUMB) {
                    cacheThumbOutQueue.postRunnable(task, task.cacheImage.priority);
                } else if (task.cacheImage.imageType == FileLoader.IMAGE_TYPE_LOTTIE) {
                    colgramLottieOutQueue.postRunnable(task, task.cacheImage.priority);
                } else {
                    cacheOutQueue.postRunnable(task, task.cacheImage.priority);
                }
            }
        });
    }""",
        "ImageLoader.java route post-download Lottie decodes onto dedicated colgramLottieOutQueue",
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
    if os.path.exists(image_loader):
        with open(image_loader, "r", encoding="utf-8", errors="ignore") as _il_p_f:
            _il_p_src = _il_p_f.read()
        if "isAvatarRequest" in _il_p_src:
            _patch_eol_variants(
                eol_patch_fn,
                image_loader,
                [
                    """                    int effectivePriority = imageReceiver.getFileLoadingPriority();
                    if (effectivePriority == FileLoader.PRIORITY_LOW) {
                        img.priority = 0;
                    } else if (thumb != 0 || isAvatarRequest || effectivePriority >= FileLoader.PRIORITY_HIGH) {
                        img.priority = 2;
                    } else {
                        img.priority = 1;
                    }"""
                ],
                """                    boolean isCustomEmojiRequest = imageLocation.document != null
                            && MessageObject.isAnimatedEmoji(imageLocation.document);
                    int effectivePriority = imageReceiver.getFileLoadingPriority();
                    if (effectivePriority == FileLoader.PRIORITY_LOW) {
                        img.priority = 0;
                    } else if (isAvatarRequest || isCustomEmojiRequest) {
                        img.priority = 3;
                    } else if (thumb != 0 || effectivePriority >= FileLoader.PRIORITY_HIGH) {
                        img.priority = 2;
                    } else {
                        img.priority = 1;
                    }""",
                "ImageLoader.java elevate avatar/custom-emoji decode priority above generic thumbs/photos",
            )
            _patch_eol_variants(
                eol_patch_fn,
                image_loader,
                [
                    """                            int loadingPriority = (thumb != 0 || isAvatarRequest || (cacheType != 10 && size > 0 && size <= 256 * 1024) || (imageLocation.location != null && cacheType != 10 && size <= 256 * 1024))
                                    ? Math.max(FileLoader.PRIORITY_HIGH, imageReceiver.getFileLoadingPriority())
                                    : imageReceiver.getFileLoadingPriority();"""
                ],
                """                            boolean isCustomEmojiLoad = imageLocation.document != null
                                    && MessageObject.isAnimatedEmoji(imageLocation.document);
                            int loadingPriority = (thumb != 0 || isAvatarRequest || isCustomEmojiLoad)
                                    ? Math.max(FileLoader.PRIORITY_HIGH, imageReceiver.getFileLoadingPriority())
                                    : ((cacheType != 10 && size <= 256 * 1024)
                                            ? Math.max(FileLoader.PRIORITY_NORMAL_UP, imageReceiver.getFileLoadingPriority())
                                            : imageReceiver.getFileLoadingPriority());""",
                "ImageLoader.java keep avatars/custom emojis strictly above generic <=256KB message photos",
            )

    # 11. AnimatedEmojiDrawable.java: remove doOnIdle on DB->server lookup and chunk <= 100 IDs.
    anim_emoji = os.path.join(
        repo_path,
        "TMessagesProj",
        "src",
        "main",
        "java",
        "org",
        "telegram",
        "ui",
        "Components",
        "AnimatedEmojiDrawable.java",
    )
    _patch_eol_variants(
        eol_patch_fn,
        anim_emoji,
        [
            """        private void processDatabaseResult(ArrayList<Object> documents, HashSet<Long> loadFromServerIds) {
            if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
                processDocumentsAndLoadMore(documents, loadFromServerIds);
            } else {
                NotificationCenter.getInstance(currentAccount).doOnIdle(() -> AndroidUtilities.runOnUIThread(() -> processDocumentsAndLoadMore(documents, loadFromServerIds)));
            }
        }

        private void loadFromServer(ArrayList<Long> loadFromServerIds) {
            if (loadFromServerIds.isEmpty()) {
                return;
            }"""
        ],
        """        private void processDatabaseResult(ArrayList<Object> documents, HashSet<Long> loadFromServerIds) {
            if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
                processDocumentsAndLoadMore(documents, loadFromServerIds);
            } else {
                AndroidUtilities.runOnUIThread(() -> processDocumentsAndLoadMore(documents, loadFromServerIds));
            }
        }

        private void loadFromServer(ArrayList<Long> loadFromServerIds) {
            if (loadFromServerIds.isEmpty()) {
                return;
            }
            if (loadFromServerIds.size() > 100) {
                for (int start = 0; start < loadFromServerIds.size(); start += 100) {
                    int end = Math.min(loadFromServerIds.size(), start + 100);
                    loadFromServer(new ArrayList<>(loadFromServerIds.subList(start, end)));
                }
                return;
            }""",
        "AnimatedEmojiDrawable.java remove doOnIdle delay and chunk server requests <= 100 IDs",
    )

    # 12. ChatActivity.java: defer participants & scheduled checks by 350ms, block sponsored reads.
    chat_act = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ChatActivity.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_act,
        [
            """            if ((chatMode == 0 || chatMode == MODE_SAVED && getSavedDialogId() == getUserConfig().getClientUserId()) && (!isThreadChat() || isTopic)) {
                waitingForLoad.add(lastLoadIndex);
                getMessagesController().loadMessages(dialog_id, mergeDialogId, false, 1, 0, 0, true, 0, classGuid, 2, 0, MODE_SCHEDULED, chatMode == MODE_SAVED ? 0 : threadMessageId, replyMaxReadId, lastLoadIndex++, isTopic);
            }"""
        ],
        """            if ((chatMode == 0 || chatMode == MODE_SAVED && getSavedDialogId() == getUserConfig().getClientUserId()) && (!isThreadChat() || isTopic)) {
                final int scheduledLoadIndex = lastLoadIndex++;
                waitingForLoad.add(scheduledLoadIndex);
                AndroidUtilities.runOnUIThread(() -> {
                    if (finishing) {
                        waitingForLoad.remove(scheduledLoadIndex);
                        return;
                    }
                    getMessagesController().loadMessages(dialog_id, mergeDialogId, false, 1, 0, 0, true, 0, classGuid, 2, 0, MODE_SCHEDULED, chatMode == MODE_SAVED ? 0 : threadMessageId, replyMaxReadId, scheduledLoadIndex, isTopic);
                }, 350);
            }""",
        "ChatActivity.java defer MODE_SCHEDULED probe by 350ms",
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_act,
        [
            """                        if (lastDate == 0 || Math.abs(System.currentTimeMillis() / 1000 - lastDate) > 60 * 60) {
                            getMessagesController().loadChannelParticipants(currentChat.id);
                        }"""
        ],
        """                        if (lastDate == 0 || Math.abs(System.currentTimeMillis() / 1000 - lastDate) > 60 * 60) {
                            final long colgramParticipantsChatId = currentChat.id;
                            AndroidUtilities.runOnUIThread(() -> {
                                if (!finishing && currentChat != null && currentChat.id == colgramParticipantsChatId) {
                                    getMessagesController().loadChannelParticipants(colgramParticipantsChatId);
                                }
                            }, 350);
                        }""",
        "ChatActivity.java defer loadChannelParticipants by 350ms",
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_act,
        [
            """    private void addSponsoredMessages(boolean animated) {
        if (sponsoredMessagesAdded || chatMode != 0 || !ChatObject.isChannel(currentChat) && !UserObject.isBot(currentUser) || !forwardEndReached[0] || getUserConfig().isPremium() && getMessagesController().isSponsoredDisabled() || isReport()) {
            return;
        }"""
        ],
        """    private void addSponsoredMessages(boolean animated) {
        if (sponsoredMessagesAdded || chatMode != 0 || !ChatObject.isChannel(currentChat) && !UserObject.isBot(currentUser) || !forwardEndReached[0] || org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled() || getUserConfig().isPremium() && getMessagesController().isSponsoredDisabled() || isReport()) {
            return;
        }""",
        "ChatActivity.java short-circuit addSponsoredMessages via ColgramPremiumConfig",
    )
    _patch_eol_variants(
        eol_patch_fn,
        chat_act,
        [
            """        object.viewsReloaded = true;
        TLRPC.TL_messages_viewSponsoredMessage req = new TLRPC.TL_messages_viewSponsoredMessage();
        req.random_id = object.sponsoredId;
        getConnectionsManager().sendRequest(req, null);
        getMessagesController().markSponsoredAsRead(dialog_id, object);"""
        ],
        """        object.viewsReloaded = true;
        if (!org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled()) {
            getMessagesController().markSponsoredAsRead(dialog_id, object);
        }""",
        "ChatActivity.java drop TL_messages_viewSponsoredMessage",
    )

    # 13. VideoAds.java: short-circuit sponsored video ads & viewSponsoredMessage.
    video_ads = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "video", "VideoAds.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        video_ads,
        [
            """    private void load() {
        if (loading || loaded) return;

        if (UserConfig.getInstance(currentAccount).isPremium() && MessagesController.getInstance(currentAccount).isSponsoredDisabled()) {
            return;
        }"""
        ],
        """    private void load() {
        if (loading || loaded) return;

        if (org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled()
                || (UserConfig.getInstance(currentAccount).isPremium() && MessagesController.getInstance(currentAccount).isSponsoredDisabled())) {
            loaded = true;
            return;
        }""",
        "VideoAds.java short-circuit sponsored ads load()",
    )
    _patch_eol_variants(
        eol_patch_fn,
        video_ads,
        [
            """    public void logSponsoredShown(TLRPC.TL_sponsoredMessage ad) {
        if (ad == null) return;
        final TLRPC.TL_messages_viewSponsoredMessage req = new TLRPC.TL_messages_viewSponsoredMessage();
        req.random_id = ad.random_id;
        if (!BuildVars.DEBUG_PRIVATE_VERSION) {
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, null);
        }
    }"""
        ],
        """    public void logSponsoredShown(TLRPC.TL_sponsoredMessage ad) {
        if (ad == null || org.colgram.core.ColgramPremiumConfig.isBlockSponsoredAdsEnabled()) return;
        if (true) return;
    }""",
        "VideoAds.java drop logSponsoredShown TL_messages_viewSponsoredMessage",
    )

    # 14. Telemetry / Firebase / Play Integrity / SSID hardening across UI & system bridges.
    dual_cam = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "Stories", "recorder", "DualCameraView.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        dual_cam,
        [
            """    private void log(boolean success) {
        final boolean vendor = DualCameraView.dualAvailableDefault(ApplicationLoader.applicationContext, false);
        if (MessagesController.getInstance(UserConfig.selectedAccount).collectDeviceStats) {"""
        ],
        """    private void log(boolean success) {
        final boolean vendor = DualCameraView.dualAvailableDefault(ApplicationLoader.applicationContext, false);
        if (false && MessagesController.getInstance(UserConfig.selectedAccount).collectDeviceStats) {""",
        "DualCameraView.java disable TL_help_saveAppLog telemetry",
    )
    push_ctrl = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "PushListenerController.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        push_ctrl,
        [
            """                    if (sendStat) {
                        String tag = pushType == PUSH_TYPE_FIREBASE ? "fcm" : "hcm";"""
        ],
        """                    if (false && sendStat) {
                        String tag = pushType == PUSH_TYPE_FIREBASE ? "fcm" : "hcm";""",
        "PushListenerController.java disable TL_help_saveAppLog telemetry",
    )
    prem_prev = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "PremiumPreviewFragment.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        prem_prev,
        [
            """    public static void sentShowScreenStat(String source) {
        ConnectionsManager connectionsManager = ConnectionsManager.getInstance(UserConfig.selectedAccount);"""
        ],
        """    public static void sentShowScreenStat(String source) {
        if (true) return;
        ConnectionsManager connectionsManager = ConnectionsManager.getInstance(UserConfig.selectedAccount);""",
        "PremiumPreviewFragment.java disable sentShowScreenStat telemetry",
    )
    _patch_eol_variants(
        eol_patch_fn,
        prem_prev,
        [
            """    public static void sentPremiumButtonClick() {
        TLRPC.TL_help_saveAppLog req = new TLRPC.TL_help_saveAppLog();"""
        ],
        """    public static void sentPremiumButtonClick() {
        if (true) return;
        TLRPC.TL_help_saveAppLog req = new TLRPC.TL_help_saveAppLog();""",
        "PremiumPreviewFragment.java disable sentPremiumButtonClick telemetry",
    )
    _patch_eol_variants(
        eol_patch_fn,
        prem_prev,
        [
            """    public static void sentPremiumBuyCanceled() {
        TLRPC.TL_help_saveAppLog req = new TLRPC.TL_help_saveAppLog();"""
        ],
        """    public static void sentPremiumBuyCanceled() {
        if (true) return;
        TLRPC.TL_help_saveAppLog req = new TLRPC.TL_help_saveAppLog();""",
        "PremiumPreviewFragment.java disable sentPremiumBuyCanceled telemetry",
    )
    _patch_eol_variants(
        eol_patch_fn,
        prem_prev,
        [
            """    public static void sentShowFeaturePreview(int currentAccount, int type) {
        TLRPC.TL_help_saveAppLog req = new TLRPC.TL_help_saveAppLog();"""
        ],
        """    public static void sentShowFeaturePreview(int currentAccount, int type) {
        if (true) return;
        TLRPC.TL_help_saveAppLog req = new TLRPC.TL_help_saveAppLog();""",
        "PremiumPreviewFragment.java disable sentShowFeaturePreview telemetry",
    )
    msg_ctrl_privacy = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "MessagesController.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        msg_ctrl_privacy,
        [
            """    public void logDeviceStats() {
        if (collectDeviceStats && !loggedDeviceStats) {"""
        ],
        """    public void logDeviceStats() {
        if (false && collectDeviceStats && !loggedDeviceStats) {""",
        "MessagesController.java disable TL_help_saveAppLog device stats telemetry",
    )

    launch_act = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "LaunchActivity.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        launch_act,
        [
            """                        if (intent.hasExtra(EXTRA_ACTION_TOKEN)) {
                            final boolean success = true;
                            final Action assistAction = new AssistActionBuilder()
                                .setActionToken(intent.getStringExtra(EXTRA_ACTION_TOKEN))
                                .setActionStatus(success ? Action.Builder.STATUS_TYPE_COMPLETED : Action.Builder.STATUS_TYPE_FAILED)
                                .build();
                            FirebaseUserActions.getInstance(this).end(assistAction);
                            intent.removeExtra(EXTRA_ACTION_TOKEN);
                        }"""
        ],
        """                        if (intent.hasExtra(EXTRA_ACTION_TOKEN)) {
                            intent.removeExtra(EXTRA_ACTION_TOKEN);
                        }""",
        "LaunchActivity.java strip FirebaseUserActions action end (view)",
    )
    _patch_eol_variants(
        eol_patch_fn,
        launch_act,
        [
            """                        if (intent.hasExtra(EXTRA_ACTION_TOKEN)) {
                            final boolean success = UserConfig.getInstance(currentAccount).isClientActivated() && "tg".equals(scheme) && unsupportedUrl == null;
                            final Action assistAction = new AssistActionBuilder()
                                    .setActionToken(intent.getStringExtra(EXTRA_ACTION_TOKEN))
                                    .setActionStatus(success ? Action.Builder.STATUS_TYPE_COMPLETED : Action.Builder.STATUS_TYPE_FAILED)
                                    .build();
                            FirebaseUserActions.getInstance(this).end(assistAction);
                            intent.removeExtra(EXTRA_ACTION_TOKEN);
                        }"""
        ],
        """                        if (intent.hasExtra(EXTRA_ACTION_TOKEN)) {
                            intent.removeExtra(EXTRA_ACTION_TOKEN);
                        }""",
        "LaunchActivity.java strip FirebaseUserActions action end (scheme)",
    )
    _patch_eol_variants(
        eol_patch_fn,
        launch_act,
        [
            """                                req.settings.allow_app_hash = req.settings.allow_firebase = PushListenerController.GooglePushListenerServiceProvider.INSTANCE.hasServices();"""
        ],
        """                                req.settings.allow_app_hash = req.settings.allow_firebase = !(org.colgram.core.ColgramConfig.isCloakEnabled() || org.colgram.core.ColgramConfig.isMaxAnonymityEnabled()) && PushListenerController.GooglePushListenerServiceProvider.INSTANCE.hasServices();""",
        "LaunchActivity.java set allow_firebase = false when cloaked",
    )

    login_act = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "LoginActivity.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        login_act,
        [
            """                if (r.play_integrity_nonce != null) {
                    IntegrityManager integrityManager = IntegrityManagerFactory.create(getContext());"""
        ],
        """                if (r.play_integrity_nonce != null) {
                    if (org.colgram.core.ColgramConfig.isCloakEnabled() || org.colgram.core.ColgramConfig.isMaxAnonymityEnabled()) {
                        resendCodeFromSafetyNet(params, res, "PLAYINTEGRITY_UNAVAILABLE");
                        return;
                    }
                    IntegrityManager integrityManager = IntegrityManagerFactory.create(getContext());""",
        "LoginActivity.java stub Play Integrity when cloaked",
    )
    _patch_eol_variants(
        eol_patch_fn,
        login_act,
        [
            """            settings.allow_app_hash = settings.allow_firebase = PushListenerController.GooglePushListenerServiceProvider.INSTANCE.hasServices();"""
        ],
        """            settings.allow_app_hash = settings.allow_firebase = !(org.colgram.core.ColgramConfig.isCloakEnabled() || org.colgram.core.ColgramConfig.isMaxAnonymityEnabled()) && PushListenerController.GooglePushListenerServiceProvider.INSTANCE.hasServices();""",
        "LoginActivity.java disable allow_firebase when cloaked",
    )

    net_mon = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "webrtc", "NetworkMonitorAutoDetect.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        net_mon,
        [
            """        if (wifiInfo != null) {
          final String ssid = wifiInfo.getSSID();"""
        ],
        """        if (wifiInfo != null) {
          if (org.colgram.core.ColgramConfig.isCloakEnabled() || org.colgram.core.ColgramConfig.isMaxAnonymityEnabled()) {
            return "";
          }
          final String ssid = wifiInfo.getSSID();""",
        "NetworkMonitorAutoDetect.java scrub Wi-Fi SSID when cloaked",
    )

    # 15. ExoPlayer ExtendedDefaultDataSource* + BotWebViewContainer: proxy routing, UA cloak, and proxy queue.
    ext_factory = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "secretmedia", "ExtendedDefaultDataSourceFactory.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        ext_factory,
        [
            """        this(context, listener, new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setTransferListener(listener)
                .setAllowCrossProtocolRedirects(true));"""
        ],
        """        this(context, listener, new DefaultHttpDataSource.Factory()
                .setUserAgent(org.colgram.core.ColgramConfig.isCloakEnabled() ? org.colgram.core.ColgramPolymorphicStealth.pickBrowserUserAgent() : userAgent)
                .setTransferListener(listener)
                .setAllowCrossProtocolRedirects(true));""",
        "ExtendedDefaultDataSourceFactory.java cloak ExoPlayer User-Agent",
    )
    ext_ds = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "secretmedia", "ExtendedDefaultDataSource.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        ext_ds,
        [
            """            new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)"""
        ],
        """            new DefaultHttpDataSource.Factory()
                .setUserAgent(org.colgram.core.ColgramConfig.isCloakEnabled() ? org.colgram.core.ColgramPolymorphicStealth.pickBrowserUserAgent() : userAgent)""",
        "ExtendedDefaultDataSource.java cloak ExoPlayer User-Agent",
    )
    _patch_eol_variants(
        eol_patch_fn,
        ext_ds,
        [
            """        } else if (SCHEME_RAW.equals(scheme)) {
            dataSource = getRawResourceDataSource();
        } else if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && (org.colgram.core.ColgramConfig.isMaxAnonymityEnabled()
                        || org.colgram.core.ColgramConfig.isStrictProxySecurityEnabled()
                        || org.telegram.ui.ColgramBrowserRoutes.mode() != org.telegram.ui.ColgramBrowserRoutes.Mode.DIRECT)) {
            dataSource = getColgramHttpDataSource();
        } else if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && (org.colgram.core.ColgramConfig.isMaxAnonymityEnabled()
                        || org.colgram.core.ColgramConfig.isStrictProxySecurityEnabled()
                        || org.telegram.ui.ColgramBrowserRoutes.mode() != org.colgram.core.ColgramBrowserRoutePolicy.Mode.CURRENT)) {
            dataSource = getColgramHttpDataSource();
        } else {
            dataSource = baseDataSource;
        }
        // Open the source and return.
        return dataSource.open(dataSpec);""",
            """        } else if (SCHEME_RAW.equals(scheme)) {
            dataSource = getRawResourceDataSource();
        } else if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && (org.colgram.core.ColgramConfig.isMaxAnonymityEnabled()
                        || org.colgram.core.ColgramConfig.isStrictProxySecurityEnabled()
                        || org.telegram.ui.ColgramBrowserRoutes.mode() != org.telegram.ui.ColgramBrowserRoutes.Mode.DIRECT)) {
            dataSource = getColgramHttpDataSource();
        } else {
            dataSource = baseDataSource;
        }
        // Open the source and return.
        return dataSource.open(dataSpec);""",
            """        } else if (SCHEME_RAW.equals(scheme)) {
            dataSource = getRawResourceDataSource();
        } else {
            dataSource = baseDataSource;
        }
        // Open the source and return.
        return dataSource.open(dataSpec);""",
        ],
        """        } else if (SCHEME_RAW.equals(scheme)) {
            dataSource = getRawResourceDataSource();
        } else if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && (org.colgram.core.ColgramConfig.isMaxAnonymityEnabled()
                        || org.colgram.core.ColgramConfig.isStrictProxySecurityEnabled()
                        || org.telegram.ui.ColgramBrowserRoutes.mode() != org.colgram.core.ColgramBrowserRoutePolicy.Mode.CURRENT)) {
            dataSource = getColgramHttpDataSource();
        } else {
            dataSource = baseDataSource;
        }
        // Open the source and return.
        return dataSource.open(dataSpec);""",
        "ExtendedDefaultDataSource.java route ExoPlayer HTTP(S) through ColgramBrowserRoutes proxy",
    )
    if os.path.isfile(ext_ds):
        with open(ext_ds, "r", encoding="utf-8", errors="ignore") as _ds_f:
            _ds_raw = _ds_f.read()
        if _ds_raw.count("private DataSource getColgramHttpDataSource()") > 1:
            _ds_block_re = re.compile(
                r'\r?\n    private @Nullable DataSource colgramHttpDataSource;\r?\n\r?\n    private DataSource getColgramHttpDataSource\(\) \{[\s\S]*?return colgramHttpDataSource;\r?\n    \}'
            )
            _first_m = _ds_block_re.search(_ds_raw)
            if _first_m is not None:
                _tail_dedup, _tail_n = _ds_block_re.subn("", _ds_raw[_first_m.end():], count=1)
                if _tail_n > 0:
                    with open(ext_ds, "w", encoding="utf-8", newline="") as _ds_f:
                        _ds_f.write(_ds_raw[:_first_m.end()] + _tail_dedup)
    _patch_eol_variants(
        eol_patch_fn,
        ext_ds,
        [
            """    private @Nullable DataSource rawResourceDataSource;
    private @Nullable DataSource colgramHttpDataSource;

    private DataSource getColgramHttpDataSource() {
        if (colgramHttpDataSource == null) {
            colgramHttpDataSource = new DataSource() {
                private java.net.HttpURLConnection conn;
                private java.io.InputStream in;
                private Uri activeUri;
                private DataSpec activeSpec;

                @Override
                public void addTransferListener(TransferListener transferListener) {
                    // Tracked via outer ExtendedDefaultDataSource listeners
                }

                @Override
                public long open(DataSpec spec) throws IOException {
                    activeSpec = spec;
                    String cur = spec.uri.toString();
                    for (int redirects = 0; redirects < 6; redirects++) {
                        java.net.URLConnection rawConn = org.telegram.ui.ColgramBrowserRoutes.openConnection(new java.net.URL(cur));
                        if (!(rawConn instanceof java.net.HttpURLConnection)) {
                            throw new IOException("Expected HttpURLConnection");
                        }
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection) rawConn;
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(15000);
                        c.setInstanceFollowRedirects(false);
                        c.setRequestProperty("User-Agent", org.colgram.core.ColgramPolymorphicStealth.pickBrowserUserAgent());
                        for (Map.Entry<String, String> e : spec.httpRequestHeaders.entrySet()) {
                            c.setRequestProperty(e.getKey(), e.getValue());
                        }
                        if (spec.position > 0 || spec.length != -1) {
                            String range = "bytes=" + spec.position + "-";
                            if (spec.length != -1) {
                                range += (spec.position + spec.length - 1);
                            }
                            c.setRequestProperty("Range", range);
                        }
                        int code = c.getResponseCode();
                        if (code >= 300 && code < 400) {
                            String loc = c.getHeaderField("Location");
                            c.disconnect();
                            if (loc == null || loc.isEmpty()) throw new IOException("Redirect without Location");
                            cur = new java.net.URL(new java.net.URL(cur), loc).toString();
                            continue;
                        }
                        if (code < 200 || code >= 300) {
                            c.disconnect();
                            throw new IOException("HTTP response code " + code);
                        }
                        conn = c;
                        activeUri = Uri.parse(cur);
                        in = c.getInputStream();
                        for (int i = 0; i < transferListeners.size(); i++) {
                            transferListeners.get(i).onTransferInitializing(this, spec, true);
                            transferListeners.get(i).onTransferStart(this, spec, true);
                        }
                        long len = c.getContentLengthLong();
                        return spec.length != -1 ? spec.length : (len >= 0 ? len : -1L);
                    }
                    throw new IOException("Too many redirects");
                }

                @Override
                public int read(byte[] buffer, int offset, int readLength) throws IOException {
                    if (readLength == 0) return 0;
                    if (in == null) return -1;
                    int n = in.read(buffer, offset, readLength);
                    if (n > 0 && activeSpec != null) {
                        for (int i = 0; i < transferListeners.size(); i++) {
                            transferListeners.get(i).onBytesTransferred(this, activeSpec, true, n);
                        }
                    }
                    return n;
                }

                @Override
                public @Nullable Uri getUri() {
                    return activeUri;
                }

                @Override
                public Map<String, List<String>> getResponseHeaders() {
                    return conn != null ? conn.getHeaderFields() : Collections.emptyMap();
                }

                @Override
                public void close() throws IOException {
                    try {
                        if (in != null) in.close();
                    } finally {
                        in = null;
                        if (conn != null) {
                            conn.disconnect();
                            conn = null;
                        }
                        if (activeSpec != null) {
                            for (int i = 0; i < transferListeners.size(); i++) {
                                transferListeners.get(i).onTransferEnd(this, activeSpec, true);
                            }
                            activeSpec = null;
                        }
                        activeUri = null;
                    }
                }
            };
        }
        return colgramHttpDataSource;
    }""",
            """    private @Nullable DataSource rawResourceDataSource;"""
        ],
        """    private @Nullable DataSource rawResourceDataSource;
    private @Nullable DataSource colgramHttpDataSource;

    private DataSource getColgramHttpDataSource() {
        if (colgramHttpDataSource == null) {
            colgramHttpDataSource = new DataSource() {
                private java.net.HttpURLConnection conn;
                private java.io.InputStream in;
                private Uri activeUri;
                private DataSpec activeSpec;

                @Override
                public void addTransferListener(TransferListener transferListener) {
                    // Tracked via outer ExtendedDefaultDataSource listeners
                }

                @Override
                public long open(DataSpec spec) throws IOException {
                    activeSpec = spec;
                    String cur = spec.uri.toString();
                    for (int redirects = 0; redirects < 6; redirects++) {
                        java.net.URLConnection rawConn = org.telegram.ui.ColgramBrowserRoutes.openConnection(new java.net.URL(cur));
                        if (!(rawConn instanceof java.net.HttpURLConnection)) {
                            throw new IOException("Expected HttpURLConnection");
                        }
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection) rawConn;
                        boolean colgramHandedOff = false;
                        try {
                            c.setConnectTimeout(10000);
                            c.setReadTimeout(15000);
                            c.setInstanceFollowRedirects(false);
                            boolean hasCallerUa = false;
                            for (Map.Entry<String, String> e : spec.httpRequestHeaders.entrySet()) {
                                if ("user-agent".equalsIgnoreCase(e.getKey())) {
                                    hasCallerUa = true;
                                }
                                c.setRequestProperty(e.getKey(), e.getValue());
                            }
                            if (!hasCallerUa) {
                                c.setRequestProperty("User-Agent", org.colgram.core.ColgramPolymorphicStealth.pickBrowserUserAgent());
                            }
                            if (spec.position > 0 || spec.length != -1) {
                                String range = "bytes=" + spec.position + "-";
                                if (spec.length != -1) {
                                    range += (spec.position + spec.length - 1);
                                }
                                c.setRequestProperty("Range", range);
                            }
                            int code = c.getResponseCode();
                            if (code >= 300 && code < 400) {
                                String loc = c.getHeaderField("Location");
                                if (loc == null || loc.isEmpty()) throw new IOException("Redirect without Location");
                                cur = new java.net.URL(new java.net.URL(cur), loc).toString();
                                continue;
                            }
                            if (code < 200 || code >= 300) {
                                throw new IOException("HTTP response code " + code);
                            }
                            conn = c;
                            activeUri = Uri.parse(cur);
                            in = c.getInputStream();
                            colgramHandedOff = true;
                            for (int i = 0; i < transferListeners.size(); i++) {
                                transferListeners.get(i).onTransferInitializing(this, spec, true);
                                transferListeners.get(i).onTransferStart(this, spec, true);
                            }
                            long len = c.getContentLengthLong();
                            return spec.length != -1 ? spec.length : (len >= 0 ? len : -1L);
                        } finally {
                            if (!colgramHandedOff) {
                                c.disconnect();
                            }
                        }
                    }
                    throw new IOException("Too many redirects");
                }

                @Override
                public int read(byte[] buffer, int offset, int readLength) throws IOException {
                    if (readLength == 0) return 0;
                    if (in == null) return -1;
                    int n = in.read(buffer, offset, readLength);
                    if (n > 0 && activeSpec != null) {
                        for (int i = 0; i < transferListeners.size(); i++) {
                            transferListeners.get(i).onBytesTransferred(this, activeSpec, true, n);
                        }
                    }
                    return n;
                }

                @Override
                public @Nullable Uri getUri() {
                    return activeUri;
                }

                @Override
                public Map<String, List<String>> getResponseHeaders() {
                    return conn != null ? conn.getHeaderFields() : Collections.emptyMap();
                }

                @Override
                public void close() throws IOException {
                    try {
                        if (in != null) in.close();
                    } finally {
                        in = null;
                        if (conn != null) {
                            conn.disconnect();
                            conn = null;
                        }
                        if (activeSpec != null) {
                            for (int i = 0; i < transferListeners.size(); i++) {
                                transferListeners.get(i).onTransferEnd(this, activeSpec, true);
                            }
                            activeSpec = null;
                        }
                        activeUri = null;
                    }
                }
            };
        }
        return colgramHttpDataSource;
    }""",
        "ExtendedDefaultDataSource.java add ColgramBrowserRoutes proxy HTTP DataSource",
    )

    bot_web = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "web", "BotWebViewContainer.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        bot_web,
        [
            """            url = tonsite2magic(url);
            currentUrl = url;
            d("loadUrl " + url);
            super.loadUrl(url);
            if (botWebViewContainer != null) {
                botWebViewContainer.onURLChanged(dangerousUrl ? urlFallback : url, !canGoBack(), !canGoForward());
            }
        }

        @Override
        public void loadUrl(@NonNull String url, @NonNull Map<String, String> additionalHttpHeaders) {
            if (currentSheet != null) {
                currentSheet.dismiss();
                currentSheet = null;
            }
            final String ourl = url;
            checkCachedMetaProperties(url);
            openedByUrl = url;
            url = tonsite2magic(url);
            currentUrl = url;
            d("loadUrl " + url + " " + additionalHttpHeaders);
            super.loadUrl(url, additionalHttpHeaders);
            if (botWebViewContainer != null) {
                botWebViewContainer.onURLChanged(dangerousUrl ? urlFallback : url, !canGoBack(), !canGoForward());
            }
        }

        public void loadUrl(String url, WebMetadataCache.WebMetadata meta) {
            if (currentSheet != null) {
                currentSheet.dismiss();
                currentSheet = null;
            }
            final String ourl = url;
            applyCachedMeta(meta);
            openedByUrl = url;
            url = tonsite2magic(url);
            currentUrl = url;
            d("loadUrl " + url + " with cached meta");
            super.loadUrl(url);"""
        ],
        """            url = tonsite2magic(url);
            currentUrl = url;
            d("loadUrl " + url);
            final String colgramUrl1 = url;
            org.telegram.ui.ColgramBrowserRoutes.whenProxyReady(this, () -> colgramSuperLoadUrl(colgramUrl1, null));
            if (botWebViewContainer != null) {
                botWebViewContainer.onURLChanged(dangerousUrl ? urlFallback : url, !canGoBack(), !canGoForward());
            }
        }

        private void colgramSuperLoadUrl(String url, Map<String, String> headers) {
            if (headers != null) {
                super.loadUrl(url, headers);
            } else {
                super.loadUrl(url);
            }
        }

        @Override
        public void loadUrl(@NonNull String url, @NonNull Map<String, String> additionalHttpHeaders) {
            if (currentSheet != null) {
                currentSheet.dismiss();
                currentSheet = null;
            }
            final String ourl = url;
            checkCachedMetaProperties(url);
            openedByUrl = url;
            url = tonsite2magic(url);
            currentUrl = url;
            d("loadUrl " + url + " " + additionalHttpHeaders);
            final String colgramUrl2 = url;
            final Map<String, String> colgramHdrs2 = new java.util.HashMap<>(additionalHttpHeaders);
            org.telegram.ui.ColgramBrowserRoutes.whenProxyReady(this, () -> colgramSuperLoadUrl(colgramUrl2, colgramHdrs2));
            if (botWebViewContainer != null) {
                botWebViewContainer.onURLChanged(dangerousUrl ? urlFallback : url, !canGoBack(), !canGoForward());
            }
        }

        public void loadUrl(String url, WebMetadataCache.WebMetadata meta) {
            if (currentSheet != null) {
                currentSheet.dismiss();
                currentSheet = null;
            }
            final String ourl = url;
            applyCachedMeta(meta);
            openedByUrl = url;
            url = tonsite2magic(url);
            currentUrl = url;
            d("loadUrl " + url + " with cached meta");
            final String colgramUrl3 = url;
            org.telegram.ui.ColgramBrowserRoutes.whenProxyReady(this, () -> colgramSuperLoadUrl(colgramUrl3, null));""",
        "BotWebViewContainer.java gate loadUrl on ColgramBrowserRoutes proxy readiness",
    )

    # 18. SharedConfig & LaunchActivity: sync ColgramProxyManager when Telegram clears proxy & harden LaunchActivity onResume FGS start.
    shared_cfg = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "messenger", "SharedConfig.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        shared_cfg,
        [
            """            if (enabled) {
                ConnectionsManager.setProxySettings(false, null);
            }"""
        ],
        """            if (enabled) {
                ConnectionsManager.setProxySettings(false, null);
                org.colgram.core.ColgramProxyManager.onTelegramProxyCleared();
            }""",
        "SharedConfig.java notify ColgramProxyManager on deleteProxy",
    )

    _patch_eol_variants(
        eol_patch_fn,
        launch_act,
        [
            """                        ConnectionsManager.setProxySettings(false, null);
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);"""
        ],
        """                        ConnectionsManager.setProxySettings(false, null);
                        org.colgram.core.ColgramProxyManager.onTelegramProxyCleared();
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);""",
        "LaunchActivity.java notify ColgramProxyManager on proxyErrorDialog dismiss",
    )
    _patch_eol_variants(
        eol_patch_fn,
        launch_act,
        [
            """        if (org.colgram.core.ColgramConfig.isAlwaysBackgroundEnabled()) {
            org.colgram.core.ColgramForegroundService.start(this);
            org.colgram.core.ColgramForegroundService.scheduleDozeKeepalive(this);""",
            """        if (org.colgram.core.ColgramConfig.isAlwaysBackgroundEnabled()) {
            try {
                org.colgram.core.ColgramForegroundService.start(this);
                org.colgram.core.ColgramForegroundService.scheduleDozeKeepalive(this);
            } catch (Throwable ignored) {}""",
        ],
        """        if (org.colgram.core.ColgramConfig.isAlwaysBackgroundEnabled()) {
            try {
                org.colgram.core.ColgramForegroundService.start(this);
                org.colgram.core.ColgramForegroundService.scheduleDozeKeepalive(this);
            } catch (Throwable ignored) {}""",
        "LaunchActivity.java guard onResume ColgramForegroundService start",
    )

    # 19. ProxyListActivity: await prior VPN teardown before System DPI start, surface redacted failures in status/HUD, and wire colgramDpiRow tap.
    proxy_act = os.path.join(
        repo_path, "TMessagesProj", "src", "main", "java", "org", "telegram", "ui", "ProxyListActivity.java"
    )
    _patch_eol_variants(
        eol_patch_fn,
        proxy_act,
        [
            """    private String colgramSystemDpiStatus() {
        Context ctx = getContext();
        String st = org.colgram.core.ColgramCallSocksTunnel.systemDpiStatus(ctx);
        if ("running".equals(st)) {
            return "Активен для всего телефона (SNI-разрез + DoH + QUIC-блок)";
        }
        if ("starting".equals(st) || colgramSystemDpiPending) {
            return "Запуск системного локального обхода…";
        }
        return "YouTube, Discord, браузеры и звонки • без внешнего VPN";
    }"""
        ],
        """    private String colgramSystemDpiStatus() {
        Context ctx = getContext();
        String st = org.colgram.core.ColgramCallSocksTunnel.systemDpiStatus(ctx);
        if ("running".equals(st)) {
            return "Активен для всего телефона (SNI-разрез + DoH + QUIC-блок)";
        }
        if ("starting".equals(st) || colgramSystemDpiPending) {
            return "Запуск системного локального обхода…";
        }
        if ("failed".equals(st)) {
            String err = org.colgram.core.ColgramCallSocksTunnel.systemDpiLastError(ctx);
            return (err != null && !err.isEmpty())
                    ? ("Ошибка запуска (" + err + ") · нажмите для повтора")
                    : "Не удалось запустить · нажмите для повтора";
        }
        return "YouTube, Discord, браузеры и звонки • без внешнего VPN";
    }""",
        "ProxyListActivity.java surface failed status in colgramSystemDpiStatus",
    )
    _patch_eol_variants(
        eol_patch_fn,
        proxy_act,
        [
            """    private void startSystemDpiNow(Context app) {
        colgramSystemDpiPending = true;
        org.colgram.core.ColgramRouteEpoch.next();
        refreshWarpState();
        warpOperations.execute(() -> {
            String err = null;
            try {
                org.colgram.core.ColgramCallSocksTunnel.startSystemDpi(app);
            } catch (Throwable t) {
                err = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            final String finalErr = err;
            AndroidUtilities.runOnUIThread(() -> {
                colgramSystemDpiPending = false;
                if (finalErr != null && getContext() != null) {
                    android.widget.Toast.makeText(getContext(), "Локальный обход: " + finalErr, android.widget.Toast.LENGTH_LONG).show();
                }
                refreshWarpState();
            });
        });
    }"""
        ],
        """    private void startSystemDpiNow(Context app) {
        colgramSystemDpiPending = true;
        org.colgram.core.ColgramRouteEpoch.next();
        refreshWarpState();
        warpOperations.execute(() -> {
            String err = null;
            try {
                if (!org.colgram.core.ColgramCallSocksTunnel.awaitStopped(app, 3000L)) {
                    err = "VPN_TEARDOWN_TIMEOUT";
                } else {
                    org.colgram.core.ColgramCallSocksTunnel.startSystemDpi(app);
                    for (int i = 0; i < 40; i++) {
                        String curSt = org.colgram.core.ColgramCallSocksTunnel.systemDpiStatus(app);
                        if (!"starting".equals(curSt)) {
                            if ("failed".equals(curSt) || !"running".equals(curSt)) {
                                String last = org.colgram.core.ColgramCallSocksTunnel.systemDpiLastError(app);
                                err = (last != null && !last.isEmpty()) ? last : ("VPN_" + curSt);
                            }
                            break;
                        }
                        try { Thread.sleep(75L); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    if (err == null && "starting".equals(org.colgram.core.ColgramCallSocksTunnel.systemDpiStatus(app))) {
                        String last = org.colgram.core.ColgramCallSocksTunnel.systemDpiLastError(app);
                        if (last != null && !last.isEmpty()) {
                            err = last;
                        }
                    }
                }
            } catch (Throwable t) {
                String last = org.colgram.core.ColgramCallSocksTunnel.systemDpiLastError(app);
                err = (last != null && !last.isEmpty()) ? last : t.getClass().getSimpleName();
            }
            final String finalErr = err;
            AndroidUtilities.runOnUIThread(() -> {
                colgramSystemDpiPending = false;
                if (finalErr != null && getContext() != null) {
                    ColgramUiKit.showNotice(ProxyListActivity.this, org.telegram.messenger.R.drawable.msg_info, "Локальный обход: " + finalErr, true);
                }
                refreshWarpState();
            });
        });
    }""",
        "ProxyListActivity.java await VPN teardown and surface System-DPI errors",
    )
    _patch_eol_variants(
        eol_patch_fn,
        proxy_act,
        [
            """                    if (position == colgramDpiRow) {
                        cell.setText(ColgramSubscriptionControls.label("Локальный слой всегда включён", "Local base is always on")
                                + "\\n" + colgramDpiStatus());
                    } else if (position == colgramBackgroundProxyRow) {"""
        ],
        """                    if (position == colgramDpiRow) {
                        cell.setText(ColgramSubscriptionControls.label("Локальный слой всегда включён", "Local base is always on")
                                + "\\n" + colgramDpiStatus());
                        cell.setOnClickListener(v -> {
                            org.colgram.core.ColgramProxyManager.recheckBypassListener();
                            ColgramUiKit.showNotice(ProxyListActivity.this, org.telegram.messenger.R.drawable.msg_info,
                                    ColgramSubscriptionControls.label("Перепроверка локального транспорта…", "Re-checking local bypass listener…"),
                                    false);
                        });
                        cell.setClickable(true);
                        cell.getTextView().setMovementMethod(null);
                        cell.getTextView().setClickable(false);
                    } else if (position == colgramBackgroundProxyRow) {""",
        "ProxyListActivity.java wire colgramDpiRow tap to recheckBypassListener()",
    )
