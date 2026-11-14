package org.colgram.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Run on a disposable, account-free device on the network being measured. */
@RunWith(AndroidJUnit4.class)
public final class ColgramLocalDpiApiDeviceTest {
    private static final String TAG = "ColgramDpiApi";
    private static final long APPLY_MS = 8_000L;
    private static final long RPC_MS = 60_000L;
    private final List<SavedKeys> saved = new ArrayList<>();

    private Context context;
    private ConnectionsManager tgnet;
    private int requestToken;
    private boolean borrowedForeground;
    private int savedResumeCount;
    private long savedPauseTime;
    private long epoch;
    private Runnable restoreProcessState;

    @Before
    public void requireAnIdleAccountFreeDevice() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        onMain(() -> {
            ColgramConfig.init(context);
            assertFalse("Run without another selected route or system VPN", hasForeignRoute());
            assertFalse("Run without a previously enabled DPI route", ColgramConfig.isDpiBypassEnabled());
            assertFalse("Run without a local listener already started", ColgramDpiBypass.isRunning());
            assertNull("Run without a selected proxy", ColgramProxyManager.getCurrentActiveProxy());
            assertFalse("Run without a proxy selection in progress", ColgramProxyManager.isProxySelectionPending());
            for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                UserConfig account = UserConfig.getInstance(i);
                assertFalse("Use a disposable device without an account", account.getPreferences().contains("user"));
                assertFalse("Use a disposable device without an account", account.isClientActivated());
                assertFalse("Run with the saved stock proxy switched off", mainPrefs(i).getBoolean("proxy_enabled", false));
            }

            ApplicationLoader.postInitApplication();
            tgnet = ConnectionsManager.getInstance(0);
            assertFalse("Use Telegram's production network", tgnet.isTestBackend());
            saveRouteState();
            epoch = ColgramRouteEpoch.next();
            ColgramConfig.setBuiltinProxyEnabled(false);
            ColgramConfig.setAutoProxyEnabled(false);
        });
    }

    @Test
    public void localDpiCarriesAnOrdinaryUnauthenticatedTelegramConfig() throws Exception {
        final long started = SystemClock.elapsedRealtime();
        final long txBefore = ColgramDpiBypass.bytesToTarget();
        final long rxBefore = ColgramDpiBypass.bytesFromTarget();
        final long relaysBefore = ColgramDpiBypass.relayedConnections();
        final long closedBefore = ColgramDpiBypass.completedRelays();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<ConfigResult> result = new AtomicReference<>();
        long tx, rx, relays;
        try {
            onMain(() -> ColgramProxyManager.setDpiBypassEnabled(context, true));
            awaitPrivateRoute();
            onMain(() -> {
                if (field(ConnectionsManager.class, "appPaused").getBoolean(tgnet)) {
                    savedResumeCount = field(ConnectionsManager.class, "appResumeCount").getInt(tgnet);
                    savedPauseTime = field(ConnectionsManager.class, "lastPauseTime").getLong(tgnet);
                    tgnet.setAppPaused(false, false);
                    borrowedForeground = true;
                }
                int flags = ConnectionsManager.RequestFlagEnableUnauthorized | ConnectionsManager.RequestFlagWithoutLogin;
                requestToken = tgnet.sendRequest(new TLRPC.TL_help_getConfig(), (response, error) -> {
                    // The network layer releases the TLObject after the callback. Keep metadata only.
                    result.set(new ConfigResult(response == null ? null : response.getClass().getName(),
                            error == null ? null : error.getClass().getName(),
                            response instanceof TLRPC.TL_config ? ((TLRPC.TL_config) response).dc_options.size() : 0));
                    done.countDown();
                }, flags, ConnectionsManager.ConnectionTypeGeneric);
                assertTrue("The Generic request was not enqueued", requestToken > 0);
                Log.i(TAG, "DPI_API_SENT rpc=help.getConfig connection_type=generic authless=true"
                        + " vpn=false public_proxy=false warp=false remap=false port=" + ColgramDpiBypass.activePort());
            });

            long deadline = SystemClock.elapsedRealtime() + RPC_MS;
            while (done.getCount() != 0 && SystemClock.elapsedRealtime() < deadline) {
                assertPrivateRoute();
                done.await(Math.min(250L, Math.max(0L, deadline - SystemClock.elapsedRealtime())), TimeUnit.MILLISECONDS);
            }
            assertPrivateRoute();
        } finally {
            tx = ColgramDpiBypass.bytesToTarget() - txBefore;
            rx = ColgramDpiBypass.bytesFromTarget() - rxBefore;
            relays = ColgramDpiBypass.relayedConnections() - relaysBefore;
            ConfigResult reply = result.get();
            Log.i(TAG, "DPI_API_RESULT completed=" + (reply != null)
                    + " elapsed_ms=" + (SystemClock.elapsedRealtime() - started)
                    + " response_class=" + (reply == null ? null : reply.responseClass)
                    + " error_class=" + (reply == null ? null : reply.errorClass)
                    + " dc_count=" + (reply == null ? 0 : reply.dcCount)
                    + " native_state=" + ConnectionsManager.native_getConnectionState(0));
            Log.i(TAG, "DPI_API_CARRIAGE bound=" + ColgramDpiBypass.isBound() + " relays=" + relays
                    + " tx=" + tx + " rx=" + rx + " completed_relays="
                    + (ColgramDpiBypass.completedRelays() - closedBefore));
        }

        ConfigResult reply = result.get();
        assertNotNull("No real Telegram reply within 60 s; inspect DPI_API_CARRIAGE", reply);
        assertNull("Telegram returned an RPC error", reply.errorClass);
        assertEquals("The reply was not Telegram's config", TLRPC.TL_config.class.getName(), reply.responseClass);
        assertTrue("Telegram's config had no DC options", reply.dcCount > 0);
        assertTrue("The listener did not carry a bidirectional conversation", relays > 0 && tx > 0 && rx > 0);
    }

    @Test
    public void switchingOffAnEphemeralDpiListenerReleasesTheSelectedRoute() throws Exception {
        int defaultPort = ColgramDpiBypass.activePort();
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(true);
            occupied.bind(new InetSocketAddress("127.0.0.1", defaultPort));
            onMain(() -> ColgramProxyManager.setDpiBypassEnabled(context, true));
            awaitPrivateRoute();
            assertTrue("The test must exercise the ephemeral-port branch", ColgramDpiBypass.activePort() != defaultPort);
            onMain(() -> ColgramProxyManager.setDpiBypassEnabled(context, false));
            assertFalse("The off choice did not stop the listener", ColgramDpiBypass.isRunning());
            assertNull("The stopped listener's ephemeral address remains selected", ColgramProxyManager.getCurrentActiveProxy());
        }
    }

    @After
    public void restoreTheBorrowedRouteState() throws Exception {
        if (restoreProcessState == null) return;
        onMain(() -> {
            try {
                if (requestToken != 0) tgnet.cancelRequest(requestToken, false);
            } finally {
                try {
                    if (borrowedForeground) {
                        tgnet.setAppPaused(true, false);
                        field(ConnectionsManager.class, "lastPauseTime").setLong(tgnet, savedPauseTime);
                        assertTrue("The foreground lease did not restore pause", field(ConnectionsManager.class, "appPaused").getBoolean(tgnet));
                        assertEquals("The foreground lease did not restore the resume count", savedResumeCount,
                                field(ConnectionsManager.class, "appResumeCount").getInt(tgnet));
                    }
                } finally {
                    ColgramRouteEpoch.next(); // Invalidate any uncommitted application from this test.
                    try {
                        ColgramProxyManager.setDpiBypassEnabled(context, false);
                        if (ColgramProxyManager.getCurrentActiveProxy() != null) ColgramProxyManager.disableProxy(context);
                        ConnectionsManager.setProxySettings(false, null);
                    } finally {
                        restoreProcessState.run();
                        boolean restored = true;
                        for (SavedKeys keys : saved) restored &= keys.restore();
                        assertTrue("The previous route preferences were not restored exactly", restored);
                    }
                }
            }
            assertFalse("The test left a listener running", ColgramDpiBypass.isRunning());
            assertNull("The test left a proxy selected", ColgramProxyManager.getCurrentActiveProxy());
            Log.i(TAG, "DPI_API_RESTORE preferences=true listener=false proxy=false");
        });
    }

    private void saveRouteState() throws Exception {
        saved.add(new SavedKeys(prefs("colgram_secure_config"), new String[]{"builtin_proxy_enabled", "auto_proxy_enabled",
                "dpi_bypass_enabled"}, new String[0], new String[0]));
        saved.add(new SavedKeys(prefs("userconfing"), new String[]{"proxyRotationEnabled"}, new String[0], new String[0]));
        saved.add(new SavedKeys(prefs("colgram_proxies"), new String[0], new String[0], new String[]{"managed_proxy_identities"}));
        saved.add(new SavedKeys(mainPrefs(0), new String[]{"proxy_enabled", "colgram_proxy_manually_disabled"},
                new String[]{"proxy_list"}, new String[0]));
        for (int i = 1; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            saved.add(new SavedKeys(mainPrefs(i), new String[]{"proxy_enabled"}, new String[0], new String[0]));
        }
        Field listLoaded = field(SharedConfig.class, "proxyListLoaded");
        Field rotationHeld = field(ColgramProxyManager.class, "stockRotationPausedByColgram");
        Field lastApply = field(ColgramProxyManager.class, "lastApplyAt");
        Field dpiApply = field(ColgramProxyManager.class, "localDpiAppliedAt");
        boolean wasLoaded = listLoaded.getBoolean(null);
        boolean wasHeld = rotationHeld.getBoolean(null);
        long previousLastApply = lastApply.getLong(null);
        long previousDpiApply = dpiApply.getLong(null);
        boolean wasRotating = SharedConfig.proxyRotationEnabled;
        SharedConfig.ProxyInfo previousProxy = SharedConfig.currentProxy;
        List<SharedConfig.ProxyInfo> previousList = new ArrayList<>(SharedConfig.proxyList);
        restoreProcessState = () -> {
            try {
                listLoaded.setBoolean(null, wasLoaded);
                rotationHeld.setBoolean(null, wasHeld);
                lastApply.setLong(null, previousLastApply);
                dpiApply.setLong(null, previousDpiApply);
                SharedConfig.proxyRotationEnabled = wasRotating;
                SharedConfig.proxyList.clear();
                SharedConfig.proxyList.addAll(previousList);
                SharedConfig.currentProxy = previousProxy;
                assertSame("The saved stock selection was not restored", previousProxy, SharedConfig.currentProxy);
            } catch (IllegalAccessException failure) {
                throw new AssertionError("Cannot restore the previous in-memory route state", failure);
            }
        };
    }

    private void awaitPrivateRoute() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + APPLY_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ColgramDpiBypass.isBound() && ColgramProxyManager.getCurrentActiveProxy() != null) break;
            Thread.sleep(50L);
        }
        assertPrivateRoute();
    }

    private void assertPrivateRoute() {
        assertTrue("Another route selection superseded the test", ColgramRouteEpoch.isCurrent(epoch));
        assertFalse("A VPN, WARP or remap would invalidate this measurement", hasForeignRoute());
        assertFalse("The public-proxy catalog or fallback was switched on",
                ColgramConfig.isBuiltinProxyEnabled() || ColgramConfig.isAutoProxyEnabled());
        assertTrue("The local DPI preference or listener is off", ColgramConfig.isDpiBypassEnabled() && ColgramDpiBypass.isBound());
        ColgramProxyManager.ProxyItem active = ColgramProxyManager.getCurrentActiveProxy();
        assertNotNull("The local listener was not assigned to tgnet", active);
        assertTrue("The selected route is not the private listener", active.isLocalDpi());
        assertEquals("The selected endpoint does not match the listener", ColgramDpiBypass.activePort(), active.effectivePort());
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            assertFalse("A stock user proxy was enabled", mainPrefs(i).getBoolean("proxy_enabled", false));
        }
    }

    private boolean hasForeignRoute() {
        if (ColgramConfig.isWarpEnabled() || ColgramWarpTunnel.isUp() || ColgramConfig.isDcRemapEnabled()
                || ColgramConfig.isIpv6BypassEnabled() || ColgramDcRemap.isRunning()) return true;
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        assertNotNull("The VPN state could not be checked", connectivity);
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    private SharedPreferences mainPrefs(int account) { return prefs(account == 0 ? "mainconfig" : "mainconfig" + account); }

    private SharedPreferences prefs(String name) { return context.getSharedPreferences(name, Context.MODE_PRIVATE); }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private interface MainAction { void run() throws Exception; }

    private static void onMain(MainAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure.set(error); }
        });
        Throwable error = failure.get();
        if (error instanceof Exception) throw (Exception) error;
        if (error instanceof Error) throw (Error) error;
        if (error != null) throw new AssertionError(error);
    }

    private static final class ConfigResult {
        final String responseClass, errorClass;
        final int dcCount;

        ConfigResult(String responseClass, String errorClass, int dcCount) {
            this.responseClass = responseClass;
            this.errorClass = errorClass;
            this.dcCount = dcCount;
        }
    }

    private static final class SavedKeys {
        final SharedPreferences prefs;
        final java.util.Map<String, Object> values = new java.util.HashMap<>();

        SavedKeys(SharedPreferences prefs, String[] flags, String[] strings, String[] sets) {
            this.prefs = prefs;
            for (String key : flags) values.put(key, prefs.contains(key) ? prefs.getBoolean(key, false) : null);
            for (String key : strings) values.put(key, prefs.getString(key, null));
            for (String key : sets) {
                java.util.Set<String> value = prefs.getStringSet(key, null);
                values.put(key, value == null ? null : new java.util.HashSet<>(value));
            }
        }

        @SuppressWarnings("unchecked")
        boolean restore() {
            SharedPreferences.Editor editor = prefs.edit();
            for (java.util.Map.Entry<String, Object> entry : values.entrySet()) {
                Object value = entry.getValue();
                String key = entry.getKey();
                if (value == null) editor.remove(key);
                else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (value instanceof String) editor.putString(key, (String) value);
                else editor.putStringSet(key, (java.util.Set<String>) value);
            }
            boolean ok = editor.commit();
            for (java.util.Map.Entry<String, Object> entry : values.entrySet()) {
                Object value = entry.getValue();
                String key = entry.getKey();
                if (value == null) ok &= !prefs.contains(key);
                else if (value instanceof Boolean) ok &= prefs.contains(key) && prefs.getBoolean(key, false) == (Boolean) value;
                else if (value instanceof String) ok &= Objects.equals(value, prefs.getString(key, null));
                else ok &= Objects.equals(value, prefs.getStringSet(key, null));
            }
            return ok;
        }
    }
}
