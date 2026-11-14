"""Pinned private call preflight, media checks, and activity result wiring."""


def patch_call_helper(source):
    marker = "COLGRAM_PRIVATE_CALL_ROUTE_PREFLIGHT"
    if marker in source:
        return source
    start = source.index("\t\tif (ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState()")
    end = source.index("\n\t\tif (Build.VERSION.SDK_INT >= 23)", start)
    offline = source[start:end]
    source = source[:start] + source[end:]
    # Move the offline check behind the route preflight: starting the selected
    # WARP/VPN can restore Telegram before the existing requestCall path runs.
    anchor = '''		intent.putExtra("account", UserConfig.selectedAccount);
		try {
			activity.startService(intent);
		} catch (Throwable e) {
			FileLog.e(e);
		}'''
    replacement = '''		final int account = accountInstance == null ? UserConfig.selectedAccount : accountInstance.getCurrentAccount();
		intent.putExtra("account", account);
		if (user != null) {
			// COLGRAM_PRIVATE_CALL_ROUTE_PREFLIGHT
			org.telegram.ui.ColgramCallRoutes.prepare(activity, (mode, ticket) -> {
				if (mode == org.colgram.core.ColgramCallRoutePolicy.Mode.CURRENT
						&& ConnectionsManager.getInstance(account).getConnectionState() != ConnectionsManager.ConnectionStateConnected) {
					showColgramOfflineCall(activity);
					return;
				}
				intent.putExtra(org.telegram.ui.ColgramCallRoutes.EXTRA_TICKET, ticket);
				intent.putExtra(org.telegram.ui.ColgramCallRoutes.EXTRA_MODE, mode.name());
				try {
					activity.startService(intent);
				} catch (RuntimeException failure) {
					org.telegram.ui.ColgramCallRoutes.release(ticket);
					FileLog.e(failure);
				}
			});
			return;
		}
		try {
			activity.startService(intent);
		} catch (Throwable e) {
			FileLog.e(e);
		}'''
    if source.count(anchor) != 1:
        raise ValueError("Private-call service-start anchor did not match")
    source = source.replace(anchor, replacement, 1)
    close = source.rfind("}")
    return source[:close] + "\n\tprivate static void showColgramOfflineCall(Activity activity) {\n" + offline.replace(
        "if (ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState() != ConnectionsManager.ConnectionStateConnected) {",
        "{", 1) + "\n\t}\n" + source[close:]


def patch_call_activity_result(source):
    marker = "// COLGRAM_PRIVATE_CALL_ROUTE_CONSENT"
    if marker in source:
        return source
    anchor = "    protected void onActivityResult(int requestCode, int resultCode, Intent data) {"
    if source.count(anchor) != 1:
        raise ValueError("LaunchActivity result hook did not match")
    source = source.replace(anchor, anchor + f'''
        {marker}
        if (org.telegram.ui.ColgramCallRoutes.onActivityResult(this, requestCode, resultCode)) {{
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }}''', 1)
    destroy = "    protected void onDestroy() {"
    if source.count(destroy) == 1:
        source = source.replace(destroy, destroy +
            "\n        org.telegram.ui.ColgramCallRoutes.cancelForActivity(this);", 1)
    return source


def patch_private_call_service(source):
    marker = "// COLGRAM_PRIVATE_CALL_ROUTE_SERVICE"
    if marker in source:
        return source
    # Remove the old Java-only SOCKS argument: some pinned modern native
    # implementations store that pointer but never route the media through it.
    if "private Instance.Proxy colgramCallProxy()" in source:
        begin = source.index("    private Instance.Proxy colgramCallProxy()")
        end = source.index("\n    }", begin) + len("\n    }")
        source = source[:begin] + source[end:]
        source = source.replace("endpoints, colgramCallProxy(), getNetworkType()", "endpoints, null, getNetworkType()", 1)

    def replace(anchor, update):
        nonlocal source
        if source.count(anchor) != 1:
            raise ValueError("Pinned call service anchor did not match: " + anchor)
        source = source.replace(anchor, update, 1)

    anchor = "\t\tlong userID = intent.getLongExtra(\"user_id\", 0);"
    replace(anchor, anchor + '''
		if (userID != 0) {
			colgramRouteTicket = intent.getLongExtra(org.telegram.ui.ColgramCallRoutes.EXTRA_TICKET, 0L);
			String chosen = intent.getStringExtra(org.telegram.ui.ColgramCallRoutes.EXTRA_MODE);
			colgramPrivateCallRoute = chosen == null ? org.telegram.ui.ColgramCallRoutes.mode()
					: org.colgram.core.ColgramCallRoutePolicy.fromPreference(chosen);
			if (intent.getBooleanExtra("is_outgoing", false) && !colgramRequirePrivateCallRoute()) {
				stopSelf();
				return START_NOT_STICKY;
			}
		}''')
    anchor = "\tprivate void initiateActualEncryptedCall() {"
    replace(anchor, anchor + '''
		if (!colgramRequirePrivateCallRoute()) {
			callFailed();
			return;
		}
		if (colgramCancelRouteWatch != null) colgramCancelRouteWatch.run();
		colgramCancelRouteWatch = org.telegram.ui.ColgramCallRoutes.watch(this, colgramPrivateCallRoute, () -> {
			if (sharedInstance == this && !colgramRequirePrivateCallRoute()) callFailed();
		});''')
    anchor = "\tpublic void acceptIncomingCall() {"
    replace(anchor, '''	public void acceptIncomingCall() {
		if (user == null) {
			colgramAcceptIncomingCall();
			return;
		}
		org.telegram.ui.LaunchActivity activity = org.telegram.ui.LaunchActivity.instance;
		if (activity == null || org.telegram.messenger.ApplicationLoader.mainInterfacePaused) {
			if (colgramRequirePrivateCallRoute()) colgramAcceptIncomingCall();
			return;
		}
		if (colgramIncomingRoutePending) return;
		colgramIncomingRoutePending = true;
		org.telegram.ui.ColgramCallRoutes.prepare(activity, (mode, ticket) -> {
			colgramIncomingRoutePending = false;
			if (sharedInstance != this) {
				org.telegram.ui.ColgramCallRoutes.release(ticket);
				return;
			}
			colgramPrivateCallRoute = mode;
			colgramRouteTicket = ticket;
			colgramAcceptIncomingCall();
		}, () -> colgramIncomingRoutePending = false);
	}

	private void colgramAcceptIncomingCall() {''')
    anchor = "\tpublic void onDestroy() {"
    replace(anchor, anchor + '''
		if (colgramCancelRouteWatch != null) colgramCancelRouteWatch.run();
		org.telegram.ui.ColgramCallRoutes.release(colgramRouteTicket);
		colgramRouteTicket = 0L;''')
    close = source.rfind("}")
    return source[:close] + f'''
    {marker}
    private long colgramRouteTicket;
    private Runnable colgramCancelRouteWatch;
    private boolean colgramIncomingRoutePending;
    private org.colgram.core.ColgramCallRoutePolicy.Mode colgramPrivateCallRoute =
            org.colgram.core.ColgramCallRoutePolicy.Mode.CURRENT;

    private boolean colgramRequirePrivateCallRoute() {{
        org.colgram.core.ColgramCallRoutePolicy.Decision verdict =
                org.telegram.ui.ColgramCallRoutes.verdict(this, colgramPrivateCallRoute);
        if (verdict.allowed) return true;
        org.telegram.ui.ColgramCallRoutes.reportFailure(this, verdict.failure);
        return false;
    }}
''' + source[close:]
