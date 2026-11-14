package org.colgram.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Account-free request over tgnet's real Generic route, not its proxy-check socket.
 * The pinned Telegram revision exposes help.getConfig without authentication.
 * No login, message RPC, synthetic response, local SOCKS front or address remap is used.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDirectApiDeviceTest {
    private static final String TAG = "ColgramDirectApi";
    private static final long REQUEST_TIMEOUT_MS = 90_000L;
    private static final String[] ROUTE_FLAGS = {
            "warp_enabled", "auto_proxy_enabled", "builtin_proxy_enabled",
            "dpi_bypass_enabled", "dc_remap_enabled", "ipv6_bypass_enabled"
    };

    private final List<SavedFlags> savedFlags = new ArrayList<>();
    private Context context;
    private ClassLoader loader;
    private Class<?> connections;
    private Object manager;
    private int requestToken;
    private boolean foregroundBorrowed;
    private int savedResumeCount;

    @Before
    public void useOnlyTheOrdinaryNativeRoute() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        loader = context.getClassLoader();
        savedFlags.add(new SavedFlags(context.getSharedPreferences("colgram_secure_config", Context.MODE_PRIVATE), ROUTE_FLAGS));
        savedFlags.add(new SavedFlags(context.getSharedPreferences("colgram_warp_masque", Context.MODE_PRIVATE), "device_wide"));
        savedFlags.add(new SavedFlags(context.getSharedPreferences("userconfing", Context.MODE_PRIVATE), "proxyRotationEnabled"));
        // Five slots are the pinned upstream/native capacity; only route booleans are read.
        for (int account = 0; account < 5; account++) {
            String name = account == 0 ? "mainconfig" : "mainconfig" + account;
            savedFlags.add(new SavedFlags(context.getSharedPreferences(name, Context.MODE_PRIVATE),
                    "proxy_enabled", "proxy_auto_applied", "proxyRotationEnabled", "colgram_proxy_manually_disabled"));
        }
        onMain(() -> {
            ColgramConfig.init(context);
            stopForeignRoutes();
            type("org.telegram.messenger.ApplicationLoader").getMethod("postInitApplication").invoke(null);
            connections = type("org.telegram.tgnet.ConnectionsManager");
            manager = connections.getMethod("getInstance", int.class).invoke(null, 0);
            stopForeignRoutes();
            // Unlike disableProxy's best-effort bridge, an unresolved native-clear API fails this test.
            connections.getMethod("setProxySettings", boolean.class, type("org.telegram.utils.proxy.ProxySettings"))
                    .invoke(null, false, null);
        });
        long deadline = SystemClock.elapsedRealtime() + 5_000L;
        while (isVpnActive() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50L);
        assertOrdinaryRoute();
    }

    @After
    public void restoreThePreviousFlagPreferences() throws Exception {
        try {
            if (context != null) onMain(() -> {
                try {
                    cancelAndReleaseForeground();
                } finally {
                    stopForeignRoutes();
                }
            });
        } finally {
            boolean restored = true;
            for (SavedFlags flags : savedFlags) restored &= flags.restore();
            assertTrue("The previous route-flag preferences were not restored exactly", restored);
        }
    }

    @Test
    public void ordinaryInternetReturnsARealTelegramConfigWithoutAnAccount() throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> responseClass = new AtomicReference<>();
        final AtomicReference<String> errorClass = new AtomicReference<>();
        final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        final AtomicInteger dcCount = new AtomicInteger(-1);
        final long started = SystemClock.elapsedRealtime();
        final long deadline = started + REQUEST_TIMEOUT_MS;
        try {
            onMain(() -> {
                Field paused = field(connections, "appPaused");
                if (paused.getBoolean(manager)) {
                    savedResumeCount = field(connections, "appResumeCount").getInt(manager);
                    connections.getMethod("setAppPaused", boolean.class, boolean.class).invoke(manager, false, false);
                    foregroundBorrowed = true;
                }
                Class<?> delegate = type("org.telegram.tgnet.RequestDelegate");
                Object callback = Proxy.newProxyInstance(delegate.getClassLoader(), new Class<?>[]{delegate},
                        (proxy, method, arguments) -> {
                            if ("run".equals(method.getName())) {
                                try {
                                    Object response = arguments[0];
                                    Object error = arguments[1];
                                    responseClass.set(response == null ? null : response.getClass().getName());
                                    errorClass.set(error == null ? null : error.getClass().getName());
                                    // tgnet frees the TLObject after this callback; retain no response or account data.
                                    if (response != null && type("org.telegram.tgnet.TLRPC$TL_config").isInstance(response)) {
                                        dcCount.set(((List<?>) response.getClass().getField("dc_options").get(response)).size());
                                    }
                                } catch (Throwable failure) {
                                    callbackFailure.set(failure);
                                } finally {
                                    done.countDown();
                                }
                                return null;
                            }
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return proxy == arguments[0];
                            if ("toString".equals(method.getName())) return "ColgramDirectApiRequestDelegate";
                            throw new UnsupportedOperationException(method.getName());
                        });
                int flags = connections.getField("RequestFlagEnableUnauthorized").getInt(null)
                        | connections.getField("RequestFlagWithoutLogin").getInt(null);
                int generic = connections.getField("ConnectionTypeGeneric").getInt(null);
                Object request = type("org.telegram.tgnet.TLRPC$TL_help_getConfig").getDeclaredConstructor().newInstance();
                requestToken = ((Number) connections.getMethod("sendRequest", type("org.telegram.tgnet.TLObject"),
                        delegate, int.class, int.class).invoke(manager, request, callback, flags, generic)).intValue();
                assertTrue("The normal Generic request did not receive a native request token", requestToken > 0);
                Log.i(TAG, "DIRECT_API_SENT rpc=help.getConfig flags=" + flags + " connection_type=" + generic
                        + " foreground_borrowed=" + foregroundBorrowed + " currentConnectionState=" + connectionState());
            });

            boolean completed = done.await(Math.min(30_000L, remaining(deadline)), TimeUnit.MILLISECONDS);
            if (!completed) {
                // Let normal tgnet bootstrap run first. Only a genuinely stalled Connecting state
                // requests its official DNS recovery; no custom DC endpoint or decoded data is injected.
                onMain(() -> {
                    int state = ((Number) connections.getMethod("native_getConnectionState", int.class).invoke(null, 0)).intValue();
                    if (done.getCount() != 0 && state == connections.getField("ConnectionStateConnecting").getInt(null)) {
                        Log.i(TAG, "DIRECT_API_DNS_BOOTSTRAP reason=generic_request_pending elapsed_ms="
                                + (SystemClock.elapsedRealtime() - started) + " currentConnectionState=" + connectionState());
                        connections.getMethod("onRequestNewServerIpAndPort", int.class, int.class).invoke(null, 0, 0);
                    }
                });
                completed = done.await(remaining(deadline), TimeUnit.MILLISECONDS);
            }
            Log.i(TAG, "DIRECT_API_RESULT completed=" + completed + " elapsed_ms=" + (SystemClock.elapsedRealtime() - started)
                    + " response_class=" + responseClass.get() + " dc_count=" + dcCount.get()
                    + " error_class=" + errorClass.get() + " currentConnectionState=" + connectionState());
            // A timeout must also prove that recovery did not silently select another route.
            assertOrdinaryRoute();
            Log.i(TAG, "DIRECT_API_ROUTE ordinary=true vpn=false proxy=false warp=false dpi=false remap=false");
            assertTrue("No real help.getConfig response within 90000ms; an open TCP socket is not success", completed);
            if (callbackFailure.get() != null) throw new AssertionError("The real RequestDelegate callback failed", callbackFailure.get());
            assertNull("The native Generic request returned a Telegram RPC error", errorClass.get());
            assertNotNull("RequestDelegate returned no Telegram response", responseClass.get());
            assertEquals("The response was not the pinned config type", "org.telegram.tgnet.TLRPC$TL_config", responseClass.get());
            assertTrue("The actual Telegram config contained no DC options", dcCount.get() > 0);
            assertOrdinaryRoute();
            int nativeState = ((Number) connections.getMethod("native_getConnectionState", int.class).invoke(null, 0)).intValue();
            assertFalse("The native stack unexpectedly connected through a proxy",
                    nativeState == connections.getField("ConnectionStateConnectingToProxy").getInt(null));
        } finally {
            onMain(this::cancelAndReleaseForeground);
        }
    }

    private void stopForeignRoutes() {
        ColgramRouteEpoch.next();
        ColgramConfig.setWarpEnabled(false);
        ColgramConfig.setAutoProxyEnabled(false);
        ColgramConfig.setBuiltinProxyEnabled(false);
        ColgramConfig.setDpiBypassEnabled(false);
        ColgramConfig.setDcRemapEnabled(false);
        ColgramConfig.setIpv6BypassEnabled(false);
        ColgramWarpTunnel.bringDown(context);
        ColgramDpiBypass.stop();
        ColgramProxyManager.applyDcRemap(false);
        ColgramDcRemap.stop();
        ColgramProxyManager.disableProxy(context);
    }

    private void assertOrdinaryRoute() throws Exception {
        SharedPreferences config = context.getSharedPreferences("colgram_secure_config", Context.MODE_PRIVATE);
        for (String flag : ROUTE_FLAGS) assertFalse("Foreign route flag changed: " + flag, config.getBoolean(flag, true));
        assertFalse("The target is using a device VPN", isVpnActive());
        assertFalse("A WARP tunnel remains active", ColgramWarpTunnel.isUp());
        assertFalse("A local DPI listener remains active", ColgramDpiBypass.isRunning());
        assertFalse("A DC-remap listener remains active", ColgramDcRemap.isRunning());
        assertNull("The manager unexpectedly selected a proxy", ColgramProxyManager.getCurrentActiveProxy());
        for (int account = 0; account < 5; account++) {
            String name = account == 0 ? "mainconfig" : "mainconfig" + account;
            assertFalse("A native user-proxy preference became enabled", context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .getBoolean("proxy_enabled", false));
        }
        assertEquals("Telegram's proxy bridge still reports an enabled user proxy", Boolean.FALSE,
                type("org.telegram.messenger.SharedConfig").getMethod("isProxyEnabled").invoke(null));
    }

    private void cancelAndReleaseForeground() throws Exception {
        if (manager == null) return;
        try {
            if (requestToken != 0) {
                connections.getMethod("cancelRequest", int.class, boolean.class).invoke(manager, requestToken, false);
                requestToken = 0;
            }
        } finally {
            if (foregroundBorrowed) {
                connections.getMethod("setAppPaused", boolean.class, boolean.class).invoke(manager, true, false);
                foregroundBorrowed = false;
                assertTrue("The borrowed foreground state was not released", field(connections, "appPaused").getBoolean(manager));
                assertEquals("The foreground resume lease was not balanced", savedResumeCount,
                        field(connections, "appResumeCount").getInt(manager));
                Log.i(TAG, "DIRECT_API_FOREGROUND restored=true resume_count=" + savedResumeCount);
            }
        }
    }

    private boolean isVpnActive() {
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        assertNotNull("ConnectivityManager is missing; the absence of a VPN was not verified", connectivity);
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    private int connectionState() throws Exception {
        return ((Number) connections.getMethod("getConnectionState").invoke(manager)).intValue();
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static long remaining(long deadline) {
        return Math.max(0L, deadline - SystemClock.elapsedRealtime());
    }

    private interface MainAction { void run() throws Exception; }

    private static void onMain(MainAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable error) { failure.set(error instanceof InvocationTargetException ? error.getCause() : error); }
        });
        Throwable error = failure.get();
        if (error instanceof Exception) throw (Exception) error;
        if (error instanceof Error) throw (Error) error;
        if (error != null) throw new AssertionError(error);
    }

    private static final class SavedFlags {
        final SharedPreferences preferences;
        final Map<String, Boolean> values = new HashMap<>();

        SavedFlags(SharedPreferences preferences, String... keys) {
            this.preferences = preferences;
            for (String key : keys) values.put(key, preferences.contains(key) ? preferences.getBoolean(key, false) : null);
        }

        boolean restore() {
            SharedPreferences.Editor editor = preferences.edit();
            for (Map.Entry<String, Boolean> entry : values.entrySet()) {
                if (entry.getValue() == null) editor.remove(entry.getKey());
                else editor.putBoolean(entry.getKey(), entry.getValue());
            }
            boolean restored = editor.commit();
            for (Map.Entry<String, Boolean> entry : values.entrySet()) {
                if (entry.getValue() == null) restored &= !preferences.contains(entry.getKey());
                else restored &= preferences.contains(entry.getKey())
                        && preferences.getBoolean(entry.getKey(), !entry.getValue()) == entry.getValue();
            }
            return restored;
        }
    }
}
