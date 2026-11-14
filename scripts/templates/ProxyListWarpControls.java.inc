    // ---- Colgram WARP helpers ----------------------------------------------------------
    private static final org.colgram.core.ColgramOperationGate warpOperationsGate =
            org.colgram.core.ColgramRouteControl.WARP_GATE;
    private static final java.util.concurrent.ExecutorService warpOperations =
            org.colgram.core.ColgramRouteControl.OPERATIONS;
    private boolean warpStartPending;
    private long waitingWarpConsent = -1L;
    private long warpRouteEpoch = -1L;
    private String warpUiFailure;
    private int callsRow = -1;
    private int callsDetailRow = -1;
    private int networkModesRow = -1;
    private String colgramLastProxyStatus;
    private String colgramLastWarpStatus;
    private boolean colgramLastProxyPending;
    private boolean colgramLastWarpDesired;
    private final android.os.Handler colgramRouteHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable colgramRouteRefresh = new Runnable() {
        @Override public void run() {
            if (getParentActivity() == null || listAdapter == null) return;
            warpStartPending = warpOperationsGate.isPending()
                    && org.colgram.core.ColgramConfig.isWarpEnabled();
            boolean enabled = org.colgram.core.ColgramProxyManager.isProxyEnabled(getContext())
                    || org.colgram.core.ColgramProxyManager.hasActiveRouteForUi();
            boolean proxyPending = org.colgram.core.ColgramProxyManager.isProxySelectionPending();
            boolean warpDesired = org.colgram.core.ColgramConfig.isWarpEnabled();
            String proxyStatus = org.colgram.core.ColgramProxyManager.getProxySelectionStatus();
            String warpStatus = warpStatusForProxyScreen();
            if (useProxySettings != enabled) {
                useProxySettings = enabled;
                updateRows(true);
            } else {
                if (proxyPending != colgramLastProxyPending || !android.text.TextUtils.equals(proxyStatus, colgramLastProxyStatus))
                    listAdapter.notifyItemChanged(useProxyRow);
                if (warpDesired != colgramLastWarpDesired || !android.text.TextUtils.equals(warpStatus, colgramLastWarpStatus))
                    listAdapter.notifyItemChanged(warpRow);
            }
            colgramLastProxyStatus = proxyStatus;
            colgramLastWarpStatus = warpStatus;
            colgramLastProxyPending = proxyPending;
            colgramLastWarpDesired = warpDesired;
            colgramRouteHandler.postDelayed(this, 1000L);
        }
    };

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
                context.stopService(new android.content.Intent(context,
                        Class.forName("org.colgram.singbox.ColgramVpnService")));
                org.colgram.core.ColgramConfig.setAutoProxyEnabled(false);
                org.colgram.core.ColgramConfig.setDpiBypassEnabled(false);
                org.colgram.core.ColgramConfig.setDcRemapEnabled(false);
                org.colgram.core.ColgramConfig.setIpv6BypassEnabled(false);
                org.colgram.core.ColgramProxyManager.setDpiBypassEnabled(context, false);
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
        if (org.colgram.core.ColgramWarpTunnel.isConnected()) return "подключён";
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
        if (requestCode != REQ_WARP_CONSENT) {
            super.onActivityResultFragment(requestCode, resultCode, data);
            return;
        }
        final long token = waitingWarpConsent;
        if (!warpRequested(token)) return;
        if (resultCode == android.app.Activity.RESULT_OK) bringWarpUp();
        else cancelWarpFromProxyScreen();
    }
