package org.colgram.core;

import android.content.Context;
import android.util.Log;

import java.io.StringReader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * ColgramWarpTunnel — drives the embedded WireGuard userspace backend for WARP.
 *
 * The backend comes from the wireguard-android "tunnel" artifact (com.wireguard.android.backend
 * .GoBackend + its bundled libwg-go.so). Colgram talks to it ENTIRELY through reflection: this
 * module compiles before TMessagesProj against a bare android.jar and has never been allowed a
 * compile-time dependency on anything else, and reflection also means a future artifact bump
 * degrades into a logged "unavailable" instead of a broken build.
 *
 * The tunnel wraps the whole device (Android VpnService). ColgramWarp.provision() produced the
 * identity; this class applies the wg-quick profile, watches the receive counter, and rotates
 * the UDP endpoint port when the edge stays silent — on Russian networks :2408 is the port the
 * DPI drops first, while :500/:1701/:4500 on the same Cloudflare IPs frequently pass.
 *
 * Everything here fails LOUD: a state or config error is surfaced as an exception with a
 * human-readable message so the settings row can show why WARP did not come up instead of
 * leaving a toggle that does nothing.
 */
public final class ColgramWarpTunnel {

    private static final String TAG = "ColgramWarpTunnel";
    /**
     * Give each advertised UDP port time for several WireGuard handshake retries.
     *
     * wireguard-go retries an initiation every RekeyTimeout (5s), so 8s still covers two
     * attempts while cutting the worst case for a dead route from 4x15=60s to 4x8=32s.
     */
    private static final int STALE_AFTER_SECS = 8;

    /**
     * Transmit bytes a working WireGuard session emits before its first reply is due.
     *
     * Measured on the emulator 2026-09-27: a healthy handshake puts ~444B on the wire within
     * 15s and the peer answers. When the port is filtered, tx stays at that same ~444B and rx
     * never moves off zero. tx is therefore only trusted as proof of real transmission once it
     * grows - a route whose tx never moves at all means the datagrams never left this device,
     * which is a different fault (no egress, or a captive portal) and worth saying out loud.
     */
    private static final long MIN_HANDSHAKE_TX_BYTES = 64L;

    private static Object backend;
    private static Object tunnel;
    private static Object activeProfile;
    private static volatile boolean up;
    private static volatile boolean connected;
    private static volatile long rxAtStart = -1;
    private static volatile boolean rotating;
    /** Why the last attempt died, shown to the user instead of a silent dead toggle. */
    private static volatile String lastFailure;

    private ColgramWarpTunnel() {}

    /** Reflectively resolved backend class, or null when the artifact is missing. */
    public static boolean isBackendAvailable() {
        try {
            Class.forName("com.wireguard.android.backend.GoBackend");
            Class.forName("com.wireguard.config.Config");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized Object backend(Context ctx) throws Exception {
        if (backend != null) return backend;
        Class<?> goCls = Class.forName("com.wireguard.android.backend.GoBackend");
        backend = goCls.getConstructor(Context.class).newInstance(ctx.getApplicationContext());
        return backend;
    }

    private static Object ensureTunnel() throws Exception {
        if (tunnel != null) return tunnel;
        Class<?> tunnelCls = Class.forName("com.wireguard.android.backend.Tunnel");
        final Class<?> stateCls = Class.forName("com.wireguard.android.backend.Tunnel$State");
        tunnel = Proxy.newProxyInstance(tunnelCls.getClassLoader(), new Class<?>[]{tunnelCls},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getName".equals(name)) return "ColgramWarp";
                    if ("onStateChange".equals(name)) {
                        // State callbacks arrive on backend threads; just mirror them.
                        if (args != null && args.length == 1 && args[0] != null) {
                            Log.i(TAG, "backend state -> " + args[0]);
                        }
                        return null;
                    }
                    return defaultFor(method);
                });
        return tunnel;
    }

    private static Object defaultFor(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        return null;
    }

    /**
     * Bring the WARP tunnel up.
     *
     * The caller MUST have completed {@code VpnService.prepare(activity)} first — Android
     * shows the consent dialog there, and the backend refuses to start otherwise.
     *
     * @throws IllegalStateException when the user declined the VPN dialog
     * @throws Exception for missing registration, missing backend or backend errors
     */
    public static synchronized void bringUp(Context ctx) throws Exception {
        // A verdict from a previous attempt must never be shown against a fresh one.
        lastFailure = null;
        if (!ColgramWarp.isRegistered()) throw new Exception("WARP не зарегистрирован");
        String conf = ColgramWarp.buildWgQuickConf(
                ColgramWarp.endpointHost(), ColgramWarp.currentEndpointPort());
        if (conf == null) throw new Exception("профиль WARP не собрался");

        Class<?> configCls = Class.forName("com.wireguard.config.Config");
        // parse(BufferedReader) - the 1.0.20230706 artifact has no parse(Reader); probing
        // against the real artifact signature, not the docs.
        Object profile = configCls.getMethod("parse", java.io.BufferedReader.class)
                .invoke(null, new java.io.BufferedReader(new StringReader(conf)));

        Object be = backend(ctx);
        setReservedClientId(be);
        Object t = ensureTunnel();
        Class<?> stateCls = Class.forName("com.wireguard.android.backend.Tunnel$State");
        // Resolve by NAME, not ordinal: the enum is {DOWN, TOGGLE, UP}, and an ordinal guess
        // of [1] would hand setState the TOGGLE value - bringing the tunnel up by accident.
        Object upState = stateState(stateCls, "UP");
        Method setState = be.getClass().getMethod("setState",
                Class.forName("com.wireguard.android.backend.Tunnel"),
                stateCls, configCls);
        setState.invoke(be, t, upState, profile);

        activeProfile = profile;
        up = true;
        connected = false;
        rxAtStart = receivedBytes(be, t);
        startEndpointWatchdog(ctx);
        Log.i(TAG, "WARP tunnel up on " + ColgramWarp.endpointHost()
                + ":" + ColgramWarp.currentEndpointPort());
    }

    public static synchronized void bringDown(Context ctx) {
        up = false;
        rotating = false;
        try {
            Object be = backend(ctx);
            Object t = ensureTunnel();
            Class<?> stateCls = Class.forName("com.wireguard.android.backend.Tunnel$State");
            Method setState = be.getClass().getMethod("setState",
                    Class.forName("com.wireguard.android.backend.Tunnel"),
                    stateCls, Class.forName("com.wireguard.config.Config"));
            Object down = stateState(stateCls, "DOWN");
            setState.invoke(be, t, down, activeProfile);
            Log.i(TAG, "WARP tunnel down");
        } catch (Throwable t) {
            Log.w(TAG, "tunnel shutdown: " + t.getMessage());
        }
        activeProfile = null;
    }

    public static boolean isUp() {
        return up;
    }

    /** True only after the embedded backend reports actual bytes from the WARP peer. */
    public static boolean isConnected() {
        return up && connected;
    }

    /**
     * Why the last attempt to bring WARP up failed, or null when there is nothing to report.
     *
     * The toggle used to sit on "подключается" for a full minute while a filtered route could
     * never answer, and then quietly switch itself off. This is what the settings row and the
     * proxy screen show instead, so the failure is named at the moment it happens.
     */
    public static String lastFailureReason() {
        return lastFailure;
    }

    public static void clearFailure() {
        lastFailure = null;
    }

    /**
     * Watch the receive counter: an endpoint whose handshake never lands gets rotated off.
     *
     * A DPI that silently drops UDP leaves the tunnel "UP" forever with zero traffic — the
     * rotator is what separates a working route from a lit toggle that carries nothing.
     */
    private static void startEndpointWatchdog(Context ctx) {
        if (rotating) return;
        rotating = true;
        Thread w = new Thread(() -> {
            int failedEndpoints = 0;
            int endpointCount = ColgramWarp.endpointPortCount();
            try {
                while (up && failedEndpoints < endpointCount) {
                    Thread.sleep(STALE_AFTER_SECS * 1000L);
                    if (!up) break;
                    Object be = backend(ctx);
                    Object t = ensureTunnel();
                    long rx = receivedBytes(be, t);
                    if (rx > (rxAtStart < 0 ? 0 : rxAtStart)) {
                        connected = true;
                        Log.i(TAG, "WARP endpoint carrying traffic (rx=" + rx + "B); keeping it");
                        ColgramProxyManager.notifyProxySettingsChanged();
                        break;
                    }
                    failedEndpoints++;
                    long tx = transmittedBytes(be, t);
                    Log.w(TAG, "WARP endpoint silent for " + STALE_AFTER_SECS
                            + "s (rx=" + rx + "B, tx=" + tx + "B); endpoint attempt "
                            + failedEndpoints + "/" + endpointCount);
                    if (failedEndpoints >= endpointCount) break;
                    // A route that never even puts a handshake on the wire cannot be fixed by
                    // trying the next port: nothing about the port is what failed. Rotating would
                    // burn the whole budget to arrive at the same answer, so stop and say so.
                    if (tx < MIN_HANDSHAKE_TX_BYTES) {
                        lastFailure = "no UDP egress: handshake never left the device";
                        Log.e(TAG, lastFailure + " (tx=" + tx + "B); not rotating ports");
                        failedEndpoints = endpointCount;
                        break;
                    }
                    String conf = ColgramWarp.buildWgQuickConf(
                            ColgramWarp.endpointHost(), ColgramWarp.nextEndpointPort());
                    if (conf == null) {
                        Log.e(TAG, "WARP endpoint rotation produced no profile; stopping the stalled tunnel");
                        failedEndpoints = endpointCount;
                        break;
                    }
                    setReservedClientId(be);
                    Class<?> configCls = Class.forName("com.wireguard.config.Config");
                    Object profile = configCls.getMethod("parse", java.io.BufferedReader.class)
                            .invoke(null, new java.io.BufferedReader(new StringReader(conf)));
                    Class<?> stateCls = Class.forName("com.wireguard.android.backend.Tunnel$State");
                    Object upState = stateState(stateCls, "UP");
                    be.getClass().getMethod("setState",
                            Class.forName("com.wireguard.android.backend.Tunnel"),
                            stateCls, configCls).invoke(be, t, upState, profile);
                    activeProfile = profile;
                    rxAtStart = receivedBytes(be, t);
                    connected = false;
                }
                if (up && failedEndpoints >= endpointCount) {
                    // Never leave a zero-receive WireGuard tunnel installed indefinitely. Once
                    // every distinct Cloudflare-advertised UDP port has timed out, take WARP
                    // down, clear its persisted selection and refresh the settings row.
                    disableStalledTunnel(ctx, "no traffic after " + failedEndpoints
                            + " distinct endpoint attempts");
                }
            } catch (Throwable t) {
                Log.e(TAG, "endpoint watchdog failed; disabling the stalled tunnel", t);
                if (up) disableStalledTunnel(ctx, "endpoint watchdog failed");
            } finally {
                rotating = false;
            }
        }, "colgram-warp-watchdog");
        w.setDaemon(true);
        w.start();
    }

    private static void disableStalledTunnel(Context ctx, String reason) {
        Log.e(TAG, "Disabling WARP: " + reason);
        lastFailure = reason;
        bringDown(ctx);
        ColgramConfig.setWarpEnabled(false);
        ColgramProxyManager.notifyProxySettingsChanged();
    }

    private static void setReservedClientId(Object backend) throws Exception {
        String reserved = ColgramWarp.reservedHex();
        if (reserved == null) throw new IllegalStateException("Cloudflare registration has no 3-byte client ID");
        backend.getClass().getMethod("setClientReserved", String.class).invoke(backend, reserved);
    }

    private static Object stateState(Class<?> stateCls, String name) throws Exception {
        return stateCls.getMethod("valueOf", String.class).invoke(null, name);
    }

    private static long receivedBytes(Object be, Object t) {
        // GoBackend.getStatistics requires the Tunnel argument. Omitting it silently failed
        // into the previous zero-counter fallback on every WireGuard 1.0.20230706 build.
        return ColgramWarpStatistics.totalRx(ColgramWarpStatistics.statistics(be, t));
    }

    private static long transmittedBytes(Object be, Object t) {
        return ColgramWarpStatistics.totalTx(ColgramWarpStatistics.statistics(be, t));
    }
}
