package org.colgram.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ColgramWarpMasqueTunnel — the WARP transport that works on this network.
 *
 * It replaces the WireGuard path, and it had to. Every WireGuard edge address is silent here,
 * measured port by port and address by address:
 *
 *   162.159.192.1:443                                    no initiation ever sent
 *   162.159.198.2 on 443, 500, 4500, 4443, 8443, 8095     no answer to a real initiation
 *
 * while the same 162.159.198.2 answers a QUIC Initial with a Retry in about 100 ms. The edge is
 * reachable and only the WireGuard handshake is not, so WARP is spoken over MASQUE instead, which
 * is the same edge over HTTP/3.
 *
 * The client is Go, built as libcolgrammasque.so, and it was verified on the device reading
 * Cloudflare's own trace: warp=on, ip=104.28.244.74, colo=FRA. That verdict is what this class
 * gates on. A tunnel that opens but cannot prove it is not up is reported as not up.
 *
 * Why the native client sits in this process at all, given that libbox.so is also a Go c-shared
 * runtime and two Go runtimes were believed to be fatal here: that belief was tested. Loading
 * libbox first, then libcolgrammasque, then running a full MASQUE session on the device returned
 * warp=on with no crash and no signal. The earlier crash was the wireguard-go backend, not Go
 * itself, so this path avoids it by not loading that library at all.
 *
 * One thing this class deliberately does not do is install a TUN device. The measurement proves
 * the tunnel carries traffic; turning that into a device-wide VPN needs the VpnService consent
 * flow, and until that exists this is a working transport with a measured verdict rather than a
 * claimed device-wide tunnel.
 */
public final class ColgramWarpMasqueTunnel {

    private static final String TAG = "ColgramWarpMasque";
    private static final String PREFS = "colgram_warp_masque";

    /** An explicit edge, host:port. Set when the edge has to be reached through a relay. */
    private static final String KEY_EDGE_OVERRIDE = "edge_override";
    /** The last verdict Cloudflare returned, kept so the settings row can show it. */
    private static final String KEY_LAST_TRACE = "last_trace";
    private static final String KEY_WARP_ON = "warp_on";
    private static final String KEY_LAST_RUN_AT = "last_run_at";

    // Native limits enrolment to 14 s, carrier setup to 16 s, and trace
    // verification to 12 s. Leave room for service dispatch, then stop the attempt.
    private static final long ATTEMPT_TIMEOUT_MS = 45_000L;

    /**
     * The SOCKS5 front the tunnel's UDP leaves through, host:port, or null for the device's own
     * socket.
     *
     * Set when a front is running, and cleared when it is not, because leaving it set after the
     * front goes away produces exactly the failure it was meant to fix: QUIC packets handed to a
     * TCP control connection nobody is reading, no Retry from the edge, and a tunnel that reports a
     * timeout with nothing in any log to explain it.
     */
    private static volatile String socksFront;
/** A SOCKS5 proxy whose UDP ASSOCIATE carries the tunnel datagrams, or null for a direct socket. */
private static volatile String udpRelay;

    private static volatile boolean up;
    private static final ColgramWarpState serviceState = new ColgramWarpState();
    private static volatile Context serviceContext;
    private static volatile String lastFailure;
    private static volatile String startupStage;
    private static volatile Map<String, String> lastTrace = Collections.emptyMap();

    private ColgramWarpMasqueTunnel() {}

    public static boolean isUp() {
        return serviceState.isUp(android.os.SystemClock.elapsedRealtime());
    }

    public static String failure() {
        return lastFailure;
    }

    /** Latest actual startup phase, or null when no attempt is in progress. */
    public static String startupStage() {
        return startupStage;
    }

    /** The last trace Cloudflare returned, so the UI can show what the edge actually saw. */
    public static Map<String, String> trace() {
        return lastTrace;
    }

    /**
     * Whether the native client can run here at all.
     *
     * Checked before the toggle is enabled, so a device without the library is told the reason
     * instead of being offered a switch that does nothing.
     */
    public static boolean isAvailable(Context ctx) {
        ColgramMasqueNative.setProbeContext(ctx == null ? null : ctx.getApplicationContext());
        if (!ColgramMasqueNative.isAvailableForProbe()) {
            lastFailure = "В сборке отсутствует MASQUE для этого устройства";
            return false;
        }
        return true; // Never load native code in the process that owns Telegram/libbox.
    }

    /** An edge override set by the caller, as host:port, or null for the edge's own address. */
    public static void setEdgeOverride(Context ctx, String hostAndPort) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_EDGE_OVERRIDE, hostAndPort).apply();
    }

    public static String edgeOverride(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_EDGE_OVERRIDE, null);
    }

    /**
     * Names the SOCKS5 front to carry the tunnel's QUIC over TCP, or clears it with null.
     *
     * The reason it exists is measured, not assumed. On the networks this has to survive, UDP to the
     * WARP edge is filtered on every port the edge serves QUIC on - four to five seconds, no Retry,
     * no alert - while TCP to the same address and port connects at once:
     *
     * <pre>
     * device, real QUIC stack, 162.159.198.2:
     *   :443   handshake failed after 5003ms  timeout: no recent network activity
     *   :500   handshake failed after 5002ms  timeout
     *   :8443  handshake failed after 5004ms  timeout
     *   :4500  handshake failed after 5001ms  timeout
     *
     * device, TCP to the same address:
     *   nc 162.159.198.2 443   RC=0
     * </pre>
     *
     * QUIC needs a bidirectional UDP flow to the edge, so on such a network the datagrams have to
     * leave inside a TCP stream. A SOCKS5 front that already carries TCP to an egress the edge
     * answers from can carry them, and this is how it is named to the native client.
     */
    public static void setSocksFront(String hostAndPort) {
        socksFront = (hostAndPort == null || hostAndPort.trim().isEmpty())
                ? null : hostAndPort.trim();

        // The UDP relay, when one is named, is a separate thing from the front: the front carries
        // this app's own HTTPS through a SOCKS CONNECT, while the relay carries the MASQUE tunnel's
        // datagrams through a SOCKS UDP ASSOCIATE. They answer to different capabilities on the same
        // kind of node, so one being available says nothing about the other, and the tunnel needs the
        // second one precisely when the first cannot reach the edge by UDP.
        Log.i(TAG, socksFront == null
                ? "tunnel UDP leaves by the device's own socket"
                : "tunnel UDP leaves through SOCKS5 front " + socksFront);
    }

    public static String socksFront() {
        return socksFront;
    }

    /**
     * Names the proxy whose UDP ASSOCIATE carries the tunnel datagrams.
     *
     * <p>Separate from {@link #setSocksFront} on purpose: the front is asked for a CONNECT and
     * carries this app's own HTTPS, the relay is asked for an ASSOCIATE and carries the tunnel's
     * datagrams, and a node can do either without doing the other. Measured - every node in the pool
     * that completes an MTProto handshake answers a SOCKS greeting with EOF, because it speaks
     * MTProto on that port and closes anything else.
     */
    public static void setUdpRelay(String hostAndPort) {
        udpRelay = (hostAndPort == null || hostAndPort.trim().isEmpty())
                ? null : hostAndPort.trim();
        ColgramUdpTunnel.setUdpRelay(udpRelay);
        Log.i(TAG, udpRelay == null
                ? "tunnel UDP leaves from this device"
                : "tunnel UDP leaves through SOCKS5 relay " + udpRelay);
    }

    public static String udpRelay() {
        return udpRelay;
    }

    /**
     * Brings the tunnel up and records the verdict.
     *
     * Blocking, and it must be: the client opens a QUIC session, runs a TLS handshake and reads one
     * response, none of which belongs on the main thread. Callers are expected to own a worker.
     */
    public static void bringUp(Context ctx) throws Exception {
        bringUp(ctx, ColgramRouteEpoch.snapshot());
    }

    public static void bringUp(Context ctx, long routeEpoch) throws Exception {
        if (!ColgramRouteEpoch.isCurrent(routeEpoch)) throw new InterruptedException("Запуск WARP отменён");
        if (ctx == null || !isAvailable(ctx)) throw new IllegalStateException(lastFailure);
        serviceContext = ctx.getApplicationContext();
        final long attempt = serviceState.begin();
        up = false;
        lastFailure = null;
        startupStage = "Запуск сервиса";
        final CountDownLatch done = new CountDownLatch(1);
        android.os.ResultReceiver callback = new android.os.ResultReceiver(null) {
            @Override protected void onReceiveResult(int code, android.os.Bundle data) {
                if (!serviceState.accept(attempt, code, android.os.SystemClock.elapsedRealtime())) return;
                if (code == ColgramWarpState.STARTING) {
                    String stage = data == null ? null : data.getString("stage");
                    if ("enrolling".equals(stage)) startupStage = "Регистрация в Cloudflare";
                    else if ("dialing".equals(stage)) startupStage = "Подключение к шлюзу Cloudflare";
                    else if ("trace".equals(stage)) startupStage = "Проверка трафика WARP";
                } else if (code == ColgramWarpState.UP) {
                    boolean firstUp = !up;
                    startupStage = null;
                    String text = data == null ? null : data.getString("trace");
                    lastTrace = Collections.unmodifiableMap(ColgramMasqueNative.parseTrace(text == null ? "" : text));
                    up = true;
                    persist(serviceContext, lastTrace, true);
                    if (firstUp) {
                        ColgramDpiBypass.clearConnectionFailureBackoff();
                    }
                    done.countDown();
                } else if (code == ColgramWarpState.FAILED || code == ColgramWarpState.STOPPED) {
                    boolean wasUp = up;
                    startupStage = null;
                    up = false;
                    if (code == ColgramWarpState.FAILED) lastFailure = data == null ? "WARP остановлен" : data.getString("error");
                    persist(serviceContext, lastTrace, false);
                    if (wasUp) {
                        ColgramDpiBypass.clearConnectionFailureBackoff();
                        final Context ctxForNotify = serviceContext;
                        if (ctxForNotify != null) {
                            ColgramProxyManager.onOverlayTransportChanged(ctxForNotify, ColgramRouteEpoch.snapshot());
                        }
                    }
                    done.countDown();
                }
                ColgramProxyManager.notifyProxySettingsChanged();
            }
        };
        try {
            AtomicReference<android.content.Intent> consent = new AtomicReference<>();
            boolean requested = ColgramRouteEpoch.runIfCurrent(routeEpoch, () -> consent.set(
                    ColgramMasqueVpnService.start(serviceContext, localAddress(), edgeOverride(serviceContext), socksFront, callback)));
            if (!requested) throw new InterruptedException("Запуск WARP отменён");
            if (consent.get() != null) throw new IllegalStateException("Нужно разрешение Android на VPN");
            long deadline = android.os.SystemClock.elapsedRealtime() + ATTEMPT_TIMEOUT_MS;
            while (!done.await(250L, TimeUnit.MILLISECONDS)) {
                if (!serviceState.isCurrent(attempt) || !ColgramRouteEpoch.isCurrent(routeEpoch)) throw new InterruptedException("Запуск WARP отменён");
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    String phase = startupStage;
                    throw new IllegalStateException((phase == null ? "Запуск WARP" : phase) + " — время ожидания истекло");
                }
            }
            if (!serviceState.isCurrent(attempt) || !isUp()) throw new IllegalStateException(lastFailure == null ? "WARP не подключён" : lastFailure);
        } catch (Exception error) {
            if (serviceState.isCurrent(attempt)) {
                lastFailure = error.getMessage();
                cancelPendingStart();
            }
            throw error;
        }
    }

    /** Invalidates late callbacks immediately; native teardown stays in the service process. */
    public static void cancelPendingStart() {
        serviceState.stop();
        startupStage = null;
        up = false;
        Context ctx = serviceContext;
        if (ctx != null) {
            ColgramMasqueVpnService.stop(ctx);
            persist(ctx, lastTrace, false);
        }
    }

    /**
     * Whether the user asked for the whole device or only for a working tunnel.
     *
     * Default off, because installing a device-wide VPN changes what every other app on the phone
     * does, and that should be a choice rather than a side effect of switching WARP on.
     */
    private static boolean deviceWideRequested(Context ctx) {
        if (ctx == null) return false;
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("device_wide", false);
    }

    /** Turns the device-wide interface on or off. */
    public static void setDeviceWide(Context ctx, boolean enabled) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("device_wide", enabled).apply();
        if (!enabled) {
            ColgramMasqueVpnService.stop(ctx.getApplicationContext());
        }
    }

    public static boolean isDeviceWide(Context ctx) {
        return deviceWideRequested(ctx);
    }

    public static void bringDown() {
        cancelPendingStart();
        Context ctx = serviceContext;
        if (ctx == null) ctx = ColgramPythonEngine.appContext();
        if (ctx != null) setDeviceWide(ctx, false);
    }

    private static void persist(Context ctx, Map<String, String> trace, boolean on) {
        if (ctx == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(KEY_WARP_ON, on)
                .putLong(KEY_LAST_RUN_AT, System.currentTimeMillis())
                .putString(KEY_LAST_TRACE, trace.toString())
                .apply();
    }

    /**
     * The address to leave from.
     *
     * Not cosmetic. This edge answers QUIC from some egress addresses and silently drops the same
     handshake from others, and which one a device uses is decided by which local address the socket
     binds to. Measured on the same machine: bound to the LAN address the tunnel reports warp=on,
     bound to the wildcard the same code times out. So the bind is explicit rather than left to the
     kernel, and the first non-loopback IPv4 is the one that is not the emulator's NAT gateway.
     */
    static String localAddress() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.getName().startsWith("tun") || nic.getName().startsWith("wg")) continue;
                for (Enumeration<InetAddress> addrs = nic.getInetAddresses(); addrs.hasMoreElements(); ) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address)) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.startsWith("127.")) continue;
                    // The emulator's gateway is where a relayed edge lives, so it is not a bind
                    // candidate - a socket bound there cannot reach it as a peer.
                    if (ip.endsWith(".2") && ip.startsWith("10.0.2.")) continue;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot enumerate interfaces: " + t);
        }
        return fallback;
    }
}
