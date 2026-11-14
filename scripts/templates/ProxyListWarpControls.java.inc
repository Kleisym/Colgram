    // ---- Colgram WARP helpers ----------------------------------------------------------
    private static final org.colgram.core.ColgramOperationGate warpOperationsGate =
            org.colgram.core.ColgramRouteControl.WARP_GATE;
    private static final java.util.concurrent.ExecutorService warpOperations =
            org.colgram.core.ColgramRouteControl.OPERATIONS;
    private static final int REQ_COLGRAM_SUBSCRIPTION_CONSENT = 9184;
    private boolean warpStartPending;
    private long waitingWarpConsent = -1L;
    private long warpRouteEpoch = -1L;
    private String warpUiFailure;
    private int callsRow = -1;
    private int callsDetailRow = -1;
    private int networkModesRow = -1;
    private int colgramBackgroundProxyRow = -1;
    private int colgramDpiRow = -1;
    private int colgramSubscriptionImportRow = -1;
    private int colgramSubscriptionNodeRow = -1;
    private int colgramSubscriptionToggleRow = -1;
    private int colgramSubscriptionRemoveRow = -1;
    private final ColgramSubscriptionControls colgramSubscription =
            new ColgramSubscriptionControls(this, REQ_COLGRAM_SUBSCRIPTION_CONSENT, this::refreshWarpState);
    private String colgramLastProxyStatus;
    private String colgramLastWarpStatus;
    private String colgramLastBackgroundProxyStatus;
    private String colgramLastDpiStatus;
    private String colgramLastSubscriptionStatus;
    private String colgramLastSubscriptionNode;
    private boolean colgramLastProxyPending;
    private boolean colgramLastWarpDesired;
    private boolean colgramLastSubscriptionActive;
    private final android.os.Handler colgramRouteHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable colgramRouteRefresh = new Runnable() {
        @Override public void run() {
            if (getParentActivity() == null || listAdapter == null) return;
            warpStartPending = warpOperationsGate.isPending()
                    && org.colgram.core.ColgramConfig.isWarpEnabled();
            boolean enabled = colgramRemoteProxyEnabled();
            boolean proxyPending = org.colgram.core.ColgramProxyManager.isProxySelectionPending();
            boolean warpDesired = org.colgram.core.ColgramConfig.isWarpEnabled();
            String proxyStatus = org.colgram.core.ColgramProxyManager.getProxySelectionStatus();
            String warpStatus = warpStatusForProxyScreen();
            String background = org.colgram.core.ColgramProxyManager.getBackgroundProxyStatus();
            String dpi = colgramDpiStatus();
            String subscription = colgramSubscription.statusLine();
            boolean subscriptionActive = colgramSubscription.canDisconnect();
            String node = colgramSubscription.nodeLabel();
            if (useProxySettings != enabled) {
                useProxySettings = enabled;
                updateRows(true);
            } else {
                if (proxyPending != colgramLastProxyPending || !android.text.TextUtils.equals(proxyStatus, colgramLastProxyStatus))
                    listAdapter.notifyItemChanged(useProxyRow);
                if (warpDesired != colgramLastWarpDesired || !android.text.TextUtils.equals(warpStatus, colgramLastWarpStatus))
                    listAdapter.notifyItemChanged(warpRow);
                if (!android.text.TextUtils.equals(background, colgramLastBackgroundProxyStatus))
                    listAdapter.notifyItemChanged(colgramBackgroundProxyRow);
                if (!android.text.TextUtils.equals(dpi, colgramLastDpiStatus))
                    listAdapter.notifyItemChanged(colgramDpiRow);
                if (subscriptionActive != colgramLastSubscriptionActive
                        || !android.text.TextUtils.equals(subscription, colgramLastSubscriptionStatus))
                    listAdapter.notifyItemChanged(colgramSubscriptionToggleRow);
                if (!android.text.TextUtils.equals(node, colgramLastSubscriptionNode))
                    listAdapter.notifyItemChanged(colgramSubscriptionNodeRow);
            }
            colgramLastProxyStatus = proxyStatus;
            colgramLastWarpStatus = warpStatus;
            colgramLastBackgroundProxyStatus = background;
            colgramLastDpiStatus = dpi;
            colgramLastSubscriptionStatus = subscription;
            colgramLastSubscriptionActive = subscriptionActive;
            colgramLastSubscriptionNode = node;
            colgramLastProxyPending = proxyPending;
            colgramLastWarpDesired = warpDesired;
            colgramRouteHandler.postDelayed(this, 1000L);
        }
    };

    private boolean colgramRemoteProxyEnabled() {
        if (org.colgram.core.ColgramProxyManager.isDcRemapActive()) return false;
        org.colgram.core.ColgramProxyManager.ProxyItem active = org.colgram.core.ColgramProxyManager.getCurrentActiveProxy();
        return active == null ? org.colgram.core.ColgramProxyManager.isProxyEnabled(getContext()) : !active.isLocalDpi();
    }

    private String colgramDpiStatus() {
        if (!org.colgram.core.ColgramDpiBypass.isBound()) return org.colgram.core.ColgramDpiBypass.upstreamStatus();
        org.colgram.core.ColgramProxyManager.ProxyItem active = org.colgram.core.ColgramProxyManager.getCurrentActiveProxy();
        if (active == null || !active.isLocalDpi()) return ColgramSubscriptionControls.label("всегда включён · поверх выбранный транспорт", "Always on · selected transport above it");
        if (org.colgram.core.ColgramDpiBypass.bytesFromTarget() > 0 && ConnectionsManager.getInstance(currentAccount).getConnectionState()
                == ConnectionsManager.ConnectionStateConnected)
            return ColgramSubscriptionControls.label("подключён к Telegram", "Connected to Telegram");
        return org.colgram.core.ColgramDpiBypass.upstreamStatus();
    }

    private void colgramShowSourceDiagnostics() {
        if (getParentActivity() == null) return;
        AlertDialog.Builder details = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        details.setTitle(ColgramSubscriptionControls.label("Источники прокси", "Proxy Sources"));
        details.setMessage(org.colgram.core.ColgramProxyManager.getSourceDiagnostics());
        details.setPositiveButton(ColgramSubscriptionControls.label("Закрыть", "Close"), null);
        showDialog(details.create());
    }

    private boolean warpRequested(long token) {
        return token >= 0 && warpOperationsGate.isCurrent(token)
                && org.colgram.core.ColgramRouteEpoch.isCurrent(warpRouteEpoch)
                && org.colgram.core.ColgramConfig.isWarpEnabled();
    }

    private void startWarpFromProxyScreen() {
        final android.content.Context source = getContext();
        if (source == null || getParentActivity() == null) return;
        final android.content.Context context = source.getApplicationContext();
        if (!org.colgram.core.ColgramWarpTunnel.isBackendAvailable()) {
            android.widget.Toast.makeText(source, "WARP-бэкенд отсутствует в этой сборке", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        colgramSubscription.stopForAlternativeRoute(context);
        org.colgram.core.ColgramProxyManager.cancelProxySelection();
        final long token = warpOperationsGate.begin();
        warpRouteEpoch = org.colgram.core.ColgramRouteEpoch.next();
        waitingWarpConsent = token;
        warpStartPending = true;
        warpUiFailure = null;
        org.colgram.core.ColgramConfig.setWarpEnabled(true);
        org.colgram.core.ColgramWarpTunnel.clearFailure();
        refreshWarpState();
        warpOperations.execute(() -> {
            if (!warpRequested(token)) return;
            try {
                // MASQUE obtains its own identity in the isolated service. Only WireGuard needs this registration.
                if (!org.colgram.core.ColgramMasqueNative.isAvailableForProbe()
                        && !org.colgram.core.ColgramWarp.isRegistered()) {
                    org.colgram.core.ColgramWarp.register(context);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (warpRequested(token)) prepareAndStartWarp();
                });
            } catch (Throwable failure) {
                completeWarpAttempt(token, context, failure.getMessage());
            }
        });
    }

    private void cancelWarpFromProxyScreen() {
        org.colgram.core.ColgramRouteEpoch.next();
        warpOperationsGate.cancel();
        waitingWarpConsent = -1L;
        warpStartPending = false;
        org.colgram.core.ColgramConfig.setWarpEnabled(false);
        org.colgram.core.ColgramWarpTunnel.cancelPendingStart();
        final android.content.Context source = getContext();
        if (source != null) {
            final android.content.Context context = source.getApplicationContext();
            warpOperations.execute(() -> {
                if (!org.colgram.core.ColgramConfig.isWarpEnabled())
                    org.colgram.core.ColgramWarpTunnel.bringDown(context);
                refreshWarpState();
            });
        }
        refreshWarpState();
    }

    private void prepareAndStartWarp() {
        final long token = waitingWarpConsent;
        if (!warpRequested(token)) return;
        if (getParentActivity() == null) {
            cancelWarpFromProxyScreen();
            return;
        }
        try {
            android.content.Intent prepare = android.net.VpnService.prepare(getParentActivity());
            if (prepare != null) startActivityForResult(prepare, REQ_WARP_CONSENT);
            else bringWarpUp();
        } catch (Throwable failure) {
            completeWarpAttempt(token, getContext(), failure.getMessage());
        }
    }

    private void bringWarpUp() {
        final long token = waitingWarpConsent;
        final long routeEpoch = warpRouteEpoch;
        waitingWarpConsent = -1L;
        final android.content.Context source = getContext();
        if (source == null || !warpRequested(token)) return;
        final android.content.Context context = source.getApplicationContext();
        warpOperations.execute(() -> {
            if (!warpRequested(token)) return;
            String error = null;
            try {
                ColgramSubscriptionControls.stopStoredProfile(context);
                org.colgram.core.ColgramConfig.setAutoProxyEnabled(false);
                org.colgram.core.ColgramConfig.setDcRemapEnabled(false);
                org.colgram.core.ColgramConfig.setIpv6BypassEnabled(false);
                org.colgram.core.ColgramProxyManager.applyDcRemap(false);
                org.colgram.core.ColgramProxyManager.disableProxy(context);
                if (!warpRequested(token)) return;
                org.colgram.core.ColgramWarpTunnel.bringUp(context, routeEpoch);
            } catch (Throwable failure) {
                error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            }
            completeWarpAttempt(token, context, error);
        });
    }

    private void completeWarpAttempt(long token, android.content.Context context, String error) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!warpOperationsGate.isCurrent(token)
                    || !org.colgram.core.ColgramRouteEpoch.isCurrent(warpRouteEpoch)) return;
            warpOperationsGate.finish(token);
            warpStartPending = false;
            if (error != null || !org.colgram.core.ColgramWarpTunnel.isUp()) {
                org.colgram.core.ColgramConfig.setWarpEnabled(false);
                warpUiFailure = error == null ? "туннель не запущен" : error;
                if (getParentActivity() != null && context != null)
                    android.widget.Toast.makeText(context, "WARP: " + warpUiFailure, android.widget.Toast.LENGTH_LONG).show();
            }
            refreshWarpState();
        });
    }

    private String warpStatusForProxyScreen() {
        if (org.colgram.core.ColgramConfig.isWarpEnabled() && warpOperationsGate.isPending()) {
            String stage = org.colgram.core.ColgramWarpMasqueTunnel.startupStage();
            if (stage == null) stage = waitingWarpConsent >= 0 ? "разрешение VPN" : "запуск";
            return stage + " · нажмите для отмены";
        }
        if (org.colgram.core.ColgramWarpTunnel.isConnected()) return org.colgram.core.ColgramProxyManager.isTelegramConnected()
                ? "WARP подключён · Telegram доступен" : "WARP подключён · ожидаем связь с Telegram";
        if (org.colgram.core.ColgramWarpTunnel.isUp()) return "подключается";
        String failure = org.colgram.core.ColgramWarpTunnel.lastFailureReason();
        if (failure == null || failure.isEmpty()) failure = warpUiFailure;
        return failure == null || failure.isEmpty() ? "выключен" : "ошибка: " + failure;
    }

    private boolean callsPreference() {
        return MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled_calls", true);
    }

    private void refreshWarpState() {
        AndroidUtilities.runOnUIThread(() -> {
            if (listAdapter != null && getParentActivity() != null) updateRows(true);
            org.colgram.core.ColgramProxyManager.notifyProxySettingsChanged();
        });
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        if (colgramSubscription.onActivityResult(requestCode, resultCode)) return;
        if (requestCode != REQ_WARP_CONSENT) {
            super.onActivityResultFragment(requestCode, resultCode, data);
            return;
        }
        final long token = waitingWarpConsent;
        if (!warpRequested(token)) return;
        if (resultCode == android.app.Activity.RESULT_OK) bringWarpUp();
        else cancelWarpFromProxyScreen();
    }
