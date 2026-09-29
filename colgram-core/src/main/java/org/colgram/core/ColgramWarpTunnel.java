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
     * How long a WARP attempt keeps trying before it is called a dead route.
     *
     * The old budget was one pass over the advertised ports, about 32 seconds. That was written when
     * the block was assumed constant, and it is not: measured on this network the same host and port
     * have answered in one session and not in another, and two resolvers swapped places between
     * runs. A filter that moves on a scale of minutes cannot be outlasted by a 32-second budget, so a
     * route that was merely slow looked identical to a dead one and the tunnel was torn down
     * seconds before it would have worked.
     *
     * Five minutes is long enough to ride out the fluctuation measured so far and short enough that
     * a genuinely dead route still fails - and says why - rather than spinning forever.
     */
    private static final long WARP_PATIENCE_MS = 5L * 60L * 1000L;

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

    /**
     * The WARP profile, in the shape the one remaining engine accepts.
     *
     * An endpoint rather than an outbound, because the WireGuard outbound was removed in
     * sing-box 1.13.0 - the engine states that itself, by name, when given one - and an address
     * list the endpoint carries itself, because the direct outbound's destination override was
     * removed at the same time. Both were measured, not read: each one was accepted only after
     * the engine named the field it refused.
     */
    private static String warpProfile() throws Exception {
        String priv = ColgramWarp.getPrivateKey();
        if (priv == null) throw new Exception("нет ключа WARP");
        String addresses = ColgramWarp.interfaceAddresses();
        if (addresses == null || addresses.trim().isEmpty()) {
            throw new Exception("нет адреса WARP");
        }
        // A relay, when one is configured, REPLACES Cloudflare's ingress rather than sitting in
        // front of it. The settings row already says "через релей" for a configured relay, so a
        // profile that quietly dialled Cloudflare anyway would be the app disagreeing with its own
        // UI - and the user would have no way to tell which one was true.
        //
        // The address and the key are decided together, from hasUsableRelay(), and never
        // separately. Asking hasRelay() for the address while asking hasUsableRelay() for the key
        // produces exactly the broken combination: the relay's address carrying Cloudflare's peer
        // key, which the relay rejects, and which reads from the outside as a relay that simply
        // does not work. Measured on the device as a profile that named the relay and did not
        // carry its key.
        boolean viaRelay = ColgramWarp.hasUsableRelay();
        String host = viaRelay ? ColgramWarp.relayAddress() : ColgramWarp.endpointHost();
        if (host == null || host.trim().isEmpty()) {
            throw new Exception("нет адреса сервера WARP");
        }
        int port = viaRelay ? ColgramWarp.relayPort() : ColgramWarp.currentEndpointPort();
        String[] parts = addresses.split(",");
        return ColgramWarpProfileBuilder.build(priv, parts[0].trim(),
                parts.length > 1 ? parts[1].trim() : null,
                ColgramWarp.reservedHex(), host, port, null,
                viaRelay ? ColgramWarp.relayPublicKey() : null,
                viaRelay ? ColgramWarp.relayPresharedKey() : null);
    }

    /** The same profile on the next advertised port, for rotation. */
    private static String nextProfile() {
        try {
            String priv = ColgramWarp.getPrivateKey();
            String addresses = ColgramWarp.interfaceAddresses();
            if (priv == null || addresses == null || addresses.trim().isEmpty()) return null;
            String[] parts = addresses.split(",");
            // A relay has one fixed port, so rotating Cloudflare's ports through it would only
            // produce profiles that cannot connect. The relay path is left alone.
            boolean viaRelay = ColgramWarp.hasUsableRelay();
            return ColgramWarpProfileBuilder.build(priv, parts[0].trim(),
                    parts.length > 1 ? parts[1].trim() : null,
                    ColgramWarp.reservedHex(),
                    viaRelay ? ColgramWarp.relayAddress() : ColgramWarp.endpointHost(),
                    viaRelay ? ColgramWarp.relayPort() : ColgramWarp.nextEndpointPort(), null,
                    viaRelay ? ColgramWarp.relayPublicKey() : null,
                    viaRelay ? ColgramWarp.relayPresharedKey() : null);
        } catch (Throwable t) {
            Log.w(TAG, "cannot build the next WARP profile: " + t.getMessage());
            return null;
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
        // WARP is a sing-box profile now, started by the same engine that carries the
        // subscription. It used to be handed to the embedded WireGuard Android backend, and that
        // is what segfaulted the app: libwg-go.so and libbox.so are each a complete cgo Go
        // runtime, and two of them in one Android process do not coexist. Measured on the device:
        // loading the WireGuard backend and then calling into libbox killed the process with
        // signal 11 in about a second, with no Java exception, no tombstone and no stack. Either
        // runtime alone was fine, which is why it only ever appeared in the full device suite -
        // the one run that loads both. So the second runtime is not merely unloaded; it is gone.
        String profile = warpProfile();
        ColgramWarpServiceBridge.start(ctx, profile);

        activeProfile = profile;
        up = true;
        connected = false;
        startEndpointWatchdog(ctx);
        Log.i(TAG, "WARP tunnel up on " + ColgramWarp.endpointHost()
                + ":" + ColgramWarp.currentEndpointPort());
    }

    public static synchronized void bringDown(Context ctx) {
        up = false;
        rotating = false;
        // Stops the same engine that started it. Deliberately never touches the WireGuard
        // backend: calling into it here would load libwg-go.so into a process that also has
        // libbox.so, which is the pair that segfaults it. bringDown has to be as free of that
        // library as bringUp now is, or a user who turns WARP off crashes the app.
        ColgramWarpServiceBridge.stop(ctx);
        Log.i(TAG, "WARP tunnel down");
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
            // A relay has a single fixed endpoint, so there is nothing to rotate through: the
            // budget is one, and a silent relay is reported as itself rather than as four failed
            // Cloudflare ports that were never dialled.
            int endpointCount = ColgramWarp.hasUsableRelay() ? 1 : ColgramWarp.endpointPortCount();
            // How long to keep trying before calling it a dead route. The old budget was one pass
            // over the advertised ports - about 32 seconds - which was written when the block was
            // assumed constant. It is not: measured on this network, the SAME host and port have
            // answered in one session and not in another, and 8.8.8.8:443 and 1.1.1.1:443 swapped
            // places between runs. A filter that moves on a scale of minutes cannot be outlasted by
            // a 32-second budget, so a route that is merely slow looks identical to a dead one and
            // the tunnel is torn down seconds before it would have worked.
            //
            // So the tunnel keeps trying, quietly, in the background, and only reports failure once
            // the patience is exhausted. The user sees "connecting" rather than a toggle that
            // gives up before the network has finished deciding.
            long patienceUntil = System.currentTimeMillis() + WARP_PATIENCE_MS;
            int portRounds = 0;
            try {
                while (up && System.currentTimeMillis() < patienceUntil) {
                    Thread.sleep(STALE_AFTER_SECS * 1000L);
                    if (!up) break;
                    // The endpoint to judge is the one the profile actually names. With a relay
                    // configured that is the relay, and Cloudflare's host is not in the path at all:
                    // the probe dialed engage.cloudflareclient.com:2408, got UnknownHostException
                    // because the tunnel it had just built no longer resolved that name, and then
                    // reported the tunnel silent. It was measuring a host the tunnel does not use,
                    // through a tunnel that had just been pointed somewhere else.
                    //
                    // Worse, the answer also drove a restart, so a probe that cannot succeed was
                    // tearing down the session it was supposed to be evaluating. Measured on the
                    // device: three restarts inside one measurement window, handshakes 0.
                    String judgedHost = ColgramWarp.hasUsableRelay()
                            ? ColgramWarp.relayAddress()
                            : ColgramWarp.endpointHost();
                    int judgedPort = ColgramWarp.hasUsableRelay()
                            ? ColgramWarp.relayPort()
                            : ColgramWarp.currentEndpointPort();
                    if (ColgramWarpEndpointProbe.answers(judgedHost, judgedPort)) {
                        connected = true;
                        Log.i(TAG, "WARP endpoint answered; keeping it");
                        ColgramProxyManager.notifyProxySettingsChanged();
                        break;
                    }
                    failedEndpoints++;
                    // A relay has one fixed endpoint, so there is nothing to rotate through: the
                    // engine's own WireGuard retry is the only retry there is, and restarting it
                    // resets a handshake that is in flight.
                    if (ColgramWarp.hasUsableRelay()) {
                        Log.w(TAG, "the relay stayed silent for " + STALE_AFTER_SECS
                                + "s; attempt " + failedEndpoints
                                + ", leaving the engine to retry its own handshake");
                        continue;
                    }
                    Log.w(TAG, "WARP endpoint silent for " + STALE_AFTER_SECS
                            + "s; attempt " + failedEndpoints + ", still within patience");
                    // Rotation restarts the engine on the next advertised port. The profile is
                    // rebuilt rather than edited, because the port travels inside it now.
                    portRounds++;
                    String next = nextProfile();
                    if (next == null) {
                        Log.e(TAG, "WARP rotation produced no profile; retrying the same one");
                        if (portRounds > endpointCount * 3) {
                            // Nothing can be built at all, so retrying cannot help. Say so rather
                            // than spinning quietly on something that will never work.
                            Log.e(TAG, "WARP cannot build any profile; giving up");
                            break;
                        }
                    } else if (portRounds % endpointCount == 0) {
                        // A full pass over every advertised port has failed. Restarting on the same
                        // profile is what re-attempts the handshake - the engine does its own
                        // WireGuard retry, and rotating only helps if another port is genuinely
                        // open, which on a moving filter is sometimes true.
                        ColgramWarpServiceBridge.restart(ctx, next);
                        activeProfile = next;
                    } else {
                        ColgramWarpServiceBridge.restart(ctx, next);
                        activeProfile = next;
                    }
                    connected = false;
                }
                if (up && System.currentTimeMillis() >= patienceUntil) {
                    // Never leave a zero-receive WireGuard tunnel installed indefinitely. Once
                    // every distinct Cloudflare-advertised UDP port has timed out, take WARP
                    // down, clear its persisted selection and refresh the settings row.
                    disableStalledTunnel(ctx, "no traffic after " + failedEndpoints
                            + " attempts over " + (WARP_PATIENCE_MS / 60000L) + " minutes");
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

    private static Object stateState(Class<?> stateCls, String name) throws Exception {
        return stateCls.getMethod("valueOf", String.class).invoke(null, name);
    }
}
