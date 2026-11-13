package org.colgram.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.Closeable;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Account-free tests of the actual Telegram native handshake on ordinary Internet.
 *
 * A loopback SOCKS endpoint does not mean a remote public proxy: DirectFront below
 * opens only the exact Telegram address requested by tgnet, on this device's own
 * ordinary Socket, then forwards bytes unchanged. The native checker performs the
 * same obfuscated MTProto handshake in both tests; a SYN/SOCKS success cannot pass.
 * No login, message sending, WARP enrollment or external proxy is involved.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDirectConnectionDeviceTest {
    private static final String TAG = "ColgramDirectTest";
    // tgnet's proxy socket timeout is 5 s; allow first-run DC-key bootstrap/queueing.
    private static final long NATIVE_TIMEOUT_MS = 60_000L;
    private static final String[] FLAGS = {
            "warp_enabled", "auto_proxy_enabled", "dpi_bypass_enabled",
            "dc_remap_enabled", "ipv6_bypass_enabled", "builtin_proxy_enabled"
    };
    private final List<String> missing = new ArrayList<>();
    private final List<Boolean> saved = new ArrayList<>();
    private Context context;
    private SharedPreferences config;

    @Before
    public void disableAllForeignRoutes() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        config = context.getSharedPreferences("colgram_secure_config", Context.MODE_PRIVATE);
        for (String key : FLAGS) {
            if (!config.contains(key)) missing.add(key);
            saved.add(config.getBoolean(key, false));
        }
        ColgramConfig.init(context);
        ColgramRouteEpoch.next();
        ColgramConfig.setWarpEnabled(false);
        ColgramConfig.setAutoProxyEnabled(false);
        ColgramConfig.setBuiltinProxyEnabled(false);
        ColgramConfig.setDpiBypassEnabled(false);
        ColgramConfig.setDcRemapEnabled(false);
        ColgramConfig.setIpv6BypassEnabled(false);
        ColgramWarpTunnel.cancelPendingStart();
        ColgramDpiBypass.stop();
        ColgramProxyManager.applyDcRemap(false);
        ColgramProxyManager.disableProxy(context);
        assertFalse("WARP must be off before direct transport testing", ColgramConfig.isWarpEnabled());
        assertFalse("public-proxy fallback must be off", ColgramConfig.isAutoProxyEnabled());
        assertFalse("direct test must not use a device VPN", isVpnActive());
    }

    @After
    public void restoreNetworkFlags() {
        ColgramRouteEpoch.next();
        ColgramDpiBypass.stop();
        if (context != null) ColgramProxyManager.disableProxy(context);
        if (config == null) return;
        SharedPreferences.Editor editor = config.edit();
        for (int index = 0; index < FLAGS.length; index++) {
            if (missing.contains(FLAGS[index])) editor.remove(FLAGS[index]);
            else editor.putBoolean(FLAGS[index], saved.get(index));
        }
        editor.commit();
    }

    @Test
    public void ordinaryInternetCompletesTheNativeTelegramHandshake() throws Exception {
        try (DirectFront front = new DirectFront(false)) {
            long rtt = nativeHandshake(front.port(), NATIVE_TIMEOUT_MS);
            Log.i(TAG, "DIRECT_NATIVE rtt_ms=" + rtt + " attempts=" + front.accepted.get()
                    + " tx=" + front.sent.get() + " rx=" + front.received.get()
                    + " destination=" + front.destination.get() + " failure=" + front.failure.get());
            assertTrue("The native Telegram handshake failed on ordinary Internet; TCP/SOCKS accept is not proof. "
                    + front.failure.get(), rtt >= 0);
            assertTrue("The direct front did not forward a real Telegram request", front.sent.get() > 0);
            assertTrue("The Telegram DC did not send a protocol response", front.received.get() > 0);
            assertFalse("The test unexpectedly enabled a public proxy", proxyEnabled());
            assertFalse("The test unexpectedly enabled WARP", ColgramConfig.isWarpEnabled());
        }
    }

    @Test
    public void localDpiBypassCompletesTheSameNativeHandshake() throws Exception {
        ColgramConfig.setDpiBypassEnabled(true);
        ColgramDpiBypass.clearConnectionFailureBackoff();
        ColgramDcRemap.invalidateFailedProbes();
        ColgramDpiBypass.resetCarriageCounters();
        ColgramDpiBypass.startImmediately();
        assertTrue("Local DPI listener did not become ready", ColgramDpiBypass.awaitReady(8000L));
        long rtt = nativeHandshake(ColgramDpiBypass.activePort(), NATIVE_TIMEOUT_MS);
        Log.i(TAG, "DPI_NATIVE rtt_ms=" + rtt + " relays=" + ColgramDpiBypass.relayedConnections()
                + " tx=" + ColgramDpiBypass.bytesToTarget() + " rx=" + ColgramDpiBypass.bytesFromTarget()
                + " strategy=" + ColgramDpiBypass.hasWorkingStrategy());
        assertTrue("The actual local DPI path did not complete Telegram's native handshake", rtt >= 0);
        assertTrue("A bound listener without carriage is not a working bypass", ColgramDpiBypass.relayedConnections() > 0);
        assertTrue("The local bypass carried no response from Telegram", ColgramDpiBypass.bytesFromTarget() > 0);
        assertTrue("The local bypass did not account for the initial Telegram request", ColgramDpiBypass.bytesToTarget() > 0);
        assertFalse("DPI testing must not silently enable public-proxy fallback", ColgramConfig.isAutoProxyEnabled());
        assertFalse("DPI testing must not turn on WARP", ColgramConfig.isWarpEnabled());
        assertFalse("DPI route must not install a device-wide VPN", isVpnActive());
    }

    @Test
    public void tcpAndSocksSuccessWithoutTelegramResponseIsRejected() throws Exception {
        try (DirectFront front = new DirectFront(true)) {
            final long started = SystemClock.elapsedRealtime();
            final AtomicReference<Boolean> verdict = new AtomicReference<>();
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            final ColgramProxyManager.ProxyItem item = new ColgramProxyManager.ProxyItem("127.0.0.1", front.port(), "", 0);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try {
                    Class<?> delegate = Class.forName("org.colgram.core.ColgramProxyManager$AvailabilityHandler", true, context.getClassLoader());
                    Object callback = java.lang.reflect.Proxy.newProxyInstance(delegate.getClassLoader(), new Class<?>[]{delegate},
                            (proxy, method, arguments) -> {
                                if ("onResult".equals(method.getName())) {
                                    verdict.set((Boolean) arguments[0]); done.countDown();
                                }
                                return null;
                            });
                    Method check = ColgramProxyManager.class.getDeclaredMethod("checkOne", ColgramProxyManager.ProxyItem.class, delegate);
                    check.setAccessible(true);
                    assertEquals("Production native check did not start", Boolean.TRUE, check.invoke(null, item, callback));
                } catch (Throwable error) { failure.set(error); done.countDown(); }
            });
            assertTrue("Production checker left a silent TCP-open peer pending beyond its deadline", done.await(16_000L, TimeUnit.MILLISECONDS));
            if (failure.get() != null) throw new AssertionError("Production check did not run", failure.get());
            assertEquals("TCP/SOCKS success without any MTProto response was called healthy", Boolean.FALSE, verdict.get());
            assertTrue("Negative control did not accept the SOCKS request", front.accepted.get() > 0);
            assertTrue("Native checker never sent its protocol bytes", front.sent.get() > 0);
            assertEquals("Silent negative control unexpectedly returned protocol bytes", 0L, front.received.get());
            assertFalse("Production deadline left the request in flight", item.nativeProbeInFlight.get());
            Log.i(TAG, "NEGATIVE_CONTROL healthy=false accepted=" + front.accepted.get()
                    + " client_bytes=" + front.sent.get() + " rx=0 elapsed_ms=" + (SystemClock.elapsedRealtime() - started));
        }
    }

    @Test
    public void explicitOffSurvivesAnInFlightBypassStart() throws Exception {
        ColgramProxyManager.setDpiBypassEnabled(context, true);
        ColgramRouteEpoch.next();
        ColgramProxyManager.setDpiBypassEnabled(context, false);
        ColgramProxyManager.disableProxy(context);
        long deadline = SystemClock.elapsedRealtime() + 5500L;
        do {
            assertFalse("An old bypass start restored the route after explicit off", proxyEnabled());
            assertFalse("An old bypass start restored the requested flag", ColgramConfig.isDpiBypassEnabled());
            assertFalse("A background result enabled public fallback", ColgramConfig.isAutoProxyEnabled());
            Thread.sleep(100L);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertFalse("Stopped local listener remains alive", ColgramDpiBypass.isRunning());
        Log.i(TAG, "EXPLICIT_OFF stable_ms=5500 proxy=false dpi=false auto_proxy=false");
    }

    @Test
    public void configurationHonorsDpiAndDcRemapOffAcrossInitialization() {
        ColgramConfig.setDpiBypassEnabled(true);
        ColgramConfig.setDcRemapEnabled(true);
        assertTrue("DPI enabled state did not persist", ColgramConfig.isDpiBypassEnabled());
        assertTrue("DC-remap enabled state did not persist", ColgramConfig.isDcRemapEnabled());

        ColgramConfig.setDpiBypassEnabled(false);
        ColgramConfig.setDcRemapEnabled(false);
        assertFalse("DPI setter ignored explicit off", config.getBoolean("dpi_bypass_enabled", true));
        assertFalse("DC-remap setter ignored explicit off", config.getBoolean("dc_remap_enabled", true));
        for (int initialization = 0; initialization < 2; initialization++) {
            ColgramConfig.init(context);
            assertFalse("DPI getter rewrote explicit off after initialization " + initialization,
                    ColgramConfig.isDpiBypassEnabled());
            assertFalse("DC-remap getter rewrote explicit off after initialization " + initialization,
                    ColgramConfig.isDcRemapEnabled());
            assertFalse("DPI read changed the stored flag", config.getBoolean("dpi_bypass_enabled", true));
            assertFalse("DC-remap read changed the stored flag", config.getBoolean("dc_remap_enabled", true));
        }
        Log.i(TAG, "PERSISTED_OFF reinitializations=2 dpi=false dc_remap=false");
    }

    @Test
    public void productionProxySettingsBridgeUsesThePinnedTelegramClass() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = listener.getLocalPort();
            ColgramProxyManager.ProxyItem item = new ColgramProxyManager.ProxyItem("127.0.0.1", port, "", 0);
            Method build = ColgramProxyManager.class.getDeclaredMethod("buildProxySettings", ColgramProxyManager.ProxyItem.class);
            build.setAccessible(true);
            Object settings = build.invoke(null, item);
            assertNotNull("Production proxy-settings bridge returned null for a valid SOCKS endpoint", settings);
            Class<?> pinnedClass = Class.forName("org.telegram.utils.proxy.ProxySettings", true, context.getClassLoader());
            assertTrue("Production bridge returned the wrong upstream proxy-settings class", pinnedClass.isInstance(settings));
            assertEquals("Production bridge changed the local address", "127.0.0.1", pinnedClass.getMethod("getAddress").invoke(settings));
            assertEquals("Production bridge changed the port", port, ((Number) pinnedClass.getMethod("getPort").invoke(settings)).intValue());
            assertEquals("Production bridge created invalid settings", Boolean.TRUE, pinnedClass.getMethod("isValid").invoke(settings));
            Log.i(TAG, "PRODUCTION_PROXY_BRIDGE class=" + settings.getClass().getName() + " port=" + port + " valid=true");
        }
    }

    @Test
    public void explicitDpiRequestSelectsThePrivateRouteEvenWhenDcTcpOpens() throws Exception {
        awaitManagerPrivateRoute();
        ColgramDpiBypass.clearConnectionFailureBackoff();
        ColgramDcRemap.invalidateFailedProbes();
        int tcpProbe = ColgramDcRemap.telegramDcProbe();
        Log.i(TAG, "EXPLICIT_DPI_PREREQUISITE dc_tcp_rtt_ms=" + tcpProbe);
        assertTrue("Prerequisite: this regression requires a TCP-open DC to exercise the erroneous direct short-circuit", tcpProbe >= 0);
        long started = SystemClock.elapsedRealtime();
        ColgramProxyManager.setDpiBypassEnabled(context, true);
        long deadline = started + 12_000L;
        ColgramProxyManager.ProxyItem active;
        do {
            active = ColgramProxyManager.getCurrentActiveProxy();
            assertFalse("Explicit DPI request enabled WARP", ColgramConfig.isWarpEnabled());
            assertFalse("Explicit DPI request enabled public-proxy fallback", ColgramConfig.isAutoProxyEnabled());
            assertFalse("The private runtime route was persisted as a user proxy", proxyEnabled());
            if (active != null && active.isLocalDpi() && active.port == ColgramDpiBypass.activePort()) break;
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) break;
            Thread.sleep(Math.min(100L, remaining));
        } while (SystemClock.elapsedRealtime() < deadline);
        Log.i(TAG, "EXPLICIT_DPI_ROUTE elapsed_ms=" + (SystemClock.elapsedRealtime() - started)
                + " active=" + (active == null ? "none" : active.address + ":" + active.port)
                + " listener_port=" + ColgramDpiBypass.activePort() + " bound=" + ColgramDpiBypass.isBound());
        assertNotNull("Explicit DPI request never selected a route within 12 s; TCP-open DCs must not short-circuit an explicit choice", active);
        assertTrue("Explicit DPI request selected a route other than local DPI", active.isLocalDpi());
        assertEquals("Explicit DPI route points at an obsolete listener port", ColgramDpiBypass.activePort(), active.port);
        assertTrue("Explicit DPI route selected an unbound listener", ColgramDpiBypass.isBound());
        assertFalse("Explicit DPI route must not use a device-wide VPN", isVpnActive());
    }

    @Test
    public void twentyImmediateStartStopCyclesDoNotResurrectAListener() throws Exception {
        for (int cycle = 0; cycle < 20; cycle++) {
            ColgramDpiBypass.startImmediately();
            ColgramDpiBypass.stop();
        }
        long deadline = SystemClock.elapsedRealtime() + 1500L;
        do {
            assertFalse("A stale listener worker restored isRunning after stop", ColgramDpiBypass.isRunning());
            assertFalse("A stale listener worker restored bound after stop", ColgramDpiBypass.isBound());
            Thread.sleep(50L);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertFalse("A stopped listener still accepts TCP at the active port", loopbackAccepts(ColgramDpiBypass.activePort()));
        assertFalse("A stopped listener still accepts TCP at the preferred port", loopbackAccepts(ColgramDpiBypass.LOCAL_PORT));
        Log.i(TAG, "LISTENER_CHURN cycles=20 settled_ms=1500 running=false bound=false accepts=false");
    }

    private static boolean loopbackAccepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (Exception closed) { return false; }
    }

    private void awaitManagerPrivateRoute() throws Exception {
        final AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Class.forName("org.telegram.messenger.ApplicationLoader", true, context.getClassLoader())
                        .getMethod("postInitApplication").invoke(null);
            } catch (Throwable error) { startupFailure.set(error); }
        });
        if (startupFailure.get() != null) throw new AssertionError("Prerequisite: Telegram application startup failed", startupFailure.get());
        Field appContext = ColgramProxyManager.class.getDeclaredField("appContext");
        appContext.setAccessible(true);
        assertNotNull("Prerequisite: application startup did not initialize ColgramProxyManager.appContext", appContext.get(null));
        Method findLocal = ColgramProxyManager.class.getDeclaredMethod("findLocalDpiProxy");
        findLocal.setAccessible(true);
        long deadline = SystemClock.elapsedRealtime() + 12_000L;
        Object local;
        do {
            local = findLocal.invoke(null);
            if (local != null) break;
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) break;
            Thread.sleep(Math.min(100L, remaining));
        } while (SystemClock.elapsedRealtime() < deadline);
        assertNotNull("Prerequisite: deferred manager startup did not seed the private DPI route within 12 s", local);
    }

    private boolean proxyEnabled() {
        return context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).getBoolean("proxy_enabled", false);
    }

    private boolean isVpnActive() {
        android.net.ConnectivityManager manager = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        for (android.net.Network network : manager.getAllNetworks()) {
            android.net.NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    private long nativeHandshake(int localPort, long timeoutMs) throws Exception {
        final long started = SystemClock.elapsedRealtime();
        final AtomicLong result = new AtomicLong(Long.MIN_VALUE);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<Object> foregroundLease = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                ClassLoader loader = context.getClassLoader();
                Class<?> application = Class.forName("org.telegram.messenger.ApplicationLoader", true, loader);
                application.getMethod("postInitApplication").invoke(null);
                Class<?> settingsClass = Class.forName("org.telegram.utils.proxy.ProxySettings", true, loader);
                Class<?> typeClass = Class.forName(settingsClass.getName() + "$Type", true, loader);
                Object builder = settingsClass.getMethod("builder").invoke(null);
                Object socks = Enum.valueOf((Class) typeClass, "SOCKS5");
                Class<?> builderClass = builder.getClass();
                builderClass.getMethod("setType", typeClass).invoke(builder, socks);
                builderClass.getMethod("setAddress", String.class).invoke(builder, "127.0.0.1");
                builderClass.getMethod("setPort", int.class).invoke(builder, localPort);
                Object settings = builderClass.getMethod("build").invoke(builder);
                Class<?> delegate = Class.forName("org.telegram.tgnet.RequestTimeDelegate", true, loader);
                Object callback = java.lang.reflect.Proxy.newProxyInstance(delegate.getClassLoader(), new Class<?>[]{delegate},
                        (proxy, method, arguments) -> {
                            if ("run".equals(method.getName())) {
                                result.compareAndSet(Long.MIN_VALUE, ((Number) arguments[0]).longValue());
                                done.countDown();
                            }
                            return null;
                        });
                Class<?> connections = Class.forName("org.telegram.tgnet.ConnectionsManager", true, loader);
                Object manager = connections.getMethod("getInstance", int.class).invoke(null, 0);
                Field paused = connections.getDeclaredField("appPaused");
                paused.setAccessible(true);
                if (paused.getBoolean(manager)) {
                    // Instrumentation has no resumed Activity. Borrow the same full network
                    // resume as Activity.onResume, so native socket timers keep ticking.
                    connections.getMethod("setAppPaused", boolean.class, boolean.class).invoke(manager, false, false);
                    foregroundLease.set(manager);
                    Log.i(TAG, "NATIVE_FOREGROUND borrowed=true");
                }
                connections.getMethod("resumeNetworkMaybe").invoke(manager);
                connections.getMethod("checkProxy", settingsClass, delegate).invoke(manager, settings, callback);
            } catch (Throwable error) {
                failure.set(error);
                done.countDown();
            }
        });
        try {
            assertTrue("Telegram's native checker did not finish within " + timeoutMs + "ms", done.await(timeoutMs, TimeUnit.MILLISECONDS));
            if (failure.get() != null) throw new AssertionError("Native Telegram checker failed to run", failure.get());
            assertTrue("Native callback did not provide a verdict", result.get() != Long.MIN_VALUE);
            Log.i(TAG, "NATIVE_VERDICT port=" + localPort + " rtt_ms=" + result.get()
                    + " elapsed_ms=" + (SystemClock.elapsedRealtime() - started));
            return result.get();
        } finally {
            Object manager = foregroundLease.get();
            if (manager != null) {
                final AtomicReference<Throwable> restoreFailure = new AtomicReference<>();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    try {
                        manager.getClass().getMethod("setAppPaused", boolean.class, boolean.class).invoke(manager, true, false);
                        Log.i(TAG, "NATIVE_FOREGROUND restored=true");
                    } catch (Throwable error) { restoreFailure.set(error); }
                });
                if (restoreFailure.get() != null) throw new AssertionError("Could not restore the borrowed native network lifecycle", restoreFailure.get());
            }
        }
    }

    /** Test-owned SOCKS proxy with no remap, strategy, upstream proxy or endpoint discovery. */
    private static final class DirectFront implements Closeable {
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicLong sent = new AtomicLong(), received = new AtomicLong();
        final AtomicReference<String> destination = new AtomicReference<>("none");
        final AtomicReference<String> failure = new AtomicReference<>("none");
        private final CopyOnWriteArrayList<Socket> sockets = new CopyOnWriteArrayList<>();
        private final ServerSocket server;
        private final boolean silent;
        private volatile boolean closed;

        DirectFront(boolean silent) throws Exception {
            this.silent = silent;
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            start("direct-test-accept", () -> {
                while (!closed) {
                    try {
                        Socket client = server.accept();
                        sockets.add(client);
                        start("direct-test-client", () -> serve(client));
                    } catch (Exception error) { if (!closed) failure.set(error.toString()); }
                }
            });
        }

        int port() { return server.getLocalPort(); }

        private void serve(Socket client) {
            Socket upstream = null;
            try {
                client.setSoTimeout(silent ? 65_000 : 15_000);
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                if (byteValue(in) != 5) throw new IllegalArgumentException("not SOCKS5");
                int methods = byteValue(in);
                byte[] choices = read(in, methods);
                boolean none = false;
                for (byte choice : choices) none |= choice == 0;
                if (!none) throw new IllegalArgumentException("native checker requested unsupported authentication");
                out.write(new byte[]{5, 0}); out.flush();
                if (byteValue(in) != 5 || byteValue(in) != 1 || byteValue(in) != 0) throw new IllegalArgumentException("not SOCKS CONNECT");
                int type = byteValue(in);
                String host;
                if (type == 1) host = InetAddress.getByAddress(read(in, 4)).getHostAddress();
                else if (type == 4) host = InetAddress.getByAddress(read(in, 16)).getHostAddress();
                else throw new IllegalArgumentException("native checker did not name a literal Telegram address");
                int port = (byteValue(in) << 8) | byteValue(in);
                destination.set(host + ":" + port);
                if (!isTelegram(host)) throw new IllegalArgumentException("unexpected non-Telegram target " + host);
                accepted.incrementAndGet();
                if (silent) {
                    out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); out.flush();
                    byte[] buffer = new byte[8192];
                    int size;
                    while ((size = in.read(buffer)) != -1) sent.addAndGet(size);
                    return;
                }
                upstream = new Socket();
                sockets.add(upstream);
                upstream.setTcpNoDelay(true);
                upstream.connect(new InetSocketAddress(InetAddress.getByName(host), port), 4000);
                upstream.setSoTimeout(15000);
                out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); out.flush();
                final Socket remote = upstream;
                start("direct-test-upload", () -> pipe(client, remote, sent));
                pipe(remote, client, received);
            } catch (Exception error) { failure.set(error.toString()); }
            finally { quietly(client); quietly(upstream); }
        }

        private static boolean isTelegram(String host) {
            return host.startsWith("149.154.") || host.startsWith("91.108.") || host.startsWith("2001:b28:")
                    || host.startsWith("2001:67c:4e8:") || host.startsWith("2001:0b28:");
        }

        private void pipe(Socket from, Socket to, AtomicLong counter) {
            try {
                InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream();
                byte[] buffer = new byte[16384]; int size;
                while (!closed && (size = in.read(buffer)) != -1) {
                    counter.addAndGet(size); out.write(buffer, 0, size); out.flush();
                }
            } catch (Exception error) { if (!closed) failure.set(error.toString()); }
            finally { quietly(from); quietly(to); }
        }

        @Override public void close() {
            closed = true; quietly(server);
            for (Socket socket : sockets) quietly(socket);
            sockets.clear();
        }
    }

    private static void start(String name, Runnable action) {
        Thread thread = new Thread(action, name); thread.setDaemon(true); thread.start();
    }

    private static int byteValue(InputStream input) throws Exception {
        int value = input.read(); if (value < 0) throw new EOFException("unexpected EOF"); return value;
    }

    private static byte[] read(InputStream input, int length) throws Exception {
        byte[] bytes = new byte[length]; int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) throw new EOFException("unexpected EOF"); offset += count;
        }
        return bytes;
    }

    private static void quietly(Closeable value) {
        if (value != null) try { value.close(); } catch (Exception ignored) { }
    }
}
