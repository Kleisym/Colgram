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

    /**
     * How long one attempt may take end to end: registration, the carrier search, and one response.
     *
     * <p>This covers the whole search rather than one route, and that is a measured change. The
     * client rotates Cloudflare's five ingresses on every port, and on a network that filters UDP it
     * tries a TCP carrier first for each one - about five seconds per candidate. Seventy-five seconds
     * therefore expired partway through the FIRST address, and the device log shows exactly that:
     *
     * <pre>
     * 05:35:34  tcp/h2 to 162.159.198.2:443   failed: h2 read: i/o timeout
     * 05:35:39  edge 162.159.198.2:443        failed: quic dial: timeout
     * ...
     * 05:36:42  run finished: 1 tests, 1 failed
     * </pre>
     *
     * Four ports of one address, then the run ended - the four ingresses that answer TCP were never
     * dialled. The budget now fits the search it is timing.
     */
    private static final long ATTEMPT_TIMEOUT_MS = 180_000L;

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
    private static volatile String lastFailure;
    private static volatile Map<String, String> lastTrace = Collections.emptyMap();

    private ColgramWarpMasqueTunnel() {}

    public static boolean isUp() {
        return up;
    }

    public static String failure() {
        return lastFailure;
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
        if (!ColgramMasqueNative.isAvailable()) {
            lastFailure = "native MASQUE client missing: " + ColgramMasqueNative.unavailableReason();
            return false;
        }
        // The in-process front is started here rather than lazily at the first attempt, because
        // bringUp() has to apply it before the measurement starts and a front that starts inside the
        // worker thread would be one that could fail after the attempt has already been committed to.
        if (!ColgramUdpTunnel.isRunning()) {
            ColgramUdpTunnel.start();
        }
        return true;
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
        ColgramMasqueNative.setSocksFront(socksFront);

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
    public static synchronized void bringUp(Context ctx) throws Exception {
        if (!isAvailable(ctx)) {
            throw new IllegalStateException(lastFailure);
        }

        String edge = edgeOverride(ctx);
        String bind = localAddress();
        Log.i(TAG, "bringing up: bind=" + bind + " edge=" + (edge == null ? "default" : edge));

        final AtomicReference<Map<String, String>> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);

        // Applied per attempt rather than once at construction, because the front may be brought up
        // or torn down between two attempts and a stale value sends the tunnel nowhere.
        //
        // With no front named, the in-process one is started and named. It runs on the device's own
        // socket and answers on loopback, so this is the tunnel depending on nothing outside the app:
        // no host relay, no extra process, no route or DNS change.
        if (socksFront == null) {
            // Started here rather than only in isAvailable(), because isAvailable() runs on the UI
            // thread when the settings row is built and the process may have been restarted since,
            // leaving the front down. A front that failed to bind here means every later datagram
            // goes nowhere, and the failure has to be nameable rather than a plain timeout.
            if (!ColgramUdpTunnel.isRunning() && ColgramUdpTunnel.start() <= 0) {
                Log.w(TAG, "in-process UDP front could not bind; the tunnel will try direct routes only");
            }
            if (ColgramUdpTunnel.isRunning()) {
                socksFront = ColgramUdpTunnel.frontAddress();
            }
        }
        ColgramMasqueNative.setSocksFront(socksFront);

        Thread worker = new Thread(() -> {
            try {
                // The relay egress is the address the edge answers from. On a device where every
                // direct route is dead, the native client tries those first and then leaves through
                // this one; both are the same MASQUE session either way.
                result.set(ColgramMasqueNative.measure(bind, edge, null, bind));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        }, "colgram-warp-masque");
        worker.setDaemon(true);
        worker.start();

        boolean finished = done.await(ATTEMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!finished) {
            worker.interrupt();
            up = false;
            lastFailure = "timed out after " + (ATTEMPT_TIMEOUT_MS / 1000) + "s";
            throw new IllegalStateException(lastFailure);
        }

        Throwable t = failure.get();
        if (t != null) {
            up = false;
            lastFailure = t.getMessage();
            throw new IllegalStateException(lastFailure);
        }

        Map<String, String> trace = result.get();
        if (trace == null || trace.isEmpty()) {
            up = false;
            lastFailure = ColgramMasqueNative.lastError();
            if (lastFailure == null) lastFailure = "tunnel produced no trace";
            throw new IllegalStateException(lastFailure);
        }

        lastTrace = trace;
        if (!ColgramMasqueNative.warpOn(trace)) {
            up = false;
            lastFailure = "edge reported warp=" + trace.get("warp");
            persist(ctx, trace, false);
            throw new IllegalStateException(lastFailure);
        }

        up = true;
        lastFailure = null;
        persist(ctx, trace, true);
        Log.i(TAG, "warp=on via " + trace.get("colo") + " from " + trace.get("ip"));

        // The verdict is the proof the tunnel works; the interface is what makes it useful. Started
        // only after that proof, because a VpnService holds the device's single VPN slot - taking
        // it and then failing to reach the edge would leave the phone with no route at all and no
        // way back, which is a far worse outcome than not routing through WARP.
        //
        // Started after the measurement rather than before it, and deliberately: the measurement
        // leaves by the physical interface, the tunnel does not. Running them the other way round
        // is how the tunnel ends up carrying its own handshake.
        // The interface is not optional, and this was the defect behind "I turned WARP on and there
        // are still no connections".
        //
        // bringUp() proved the tunnel and wrote warp_on=true, and the row read that and said
        // "connected" - while the phone's own traffic kept going out the physical interface, because
        // the VpnService was only started when a second, separately-named switch happened to be on:
        //
        //     if (deviceWideRequested(ctx)) { ColgramMasqueVpnService.start(...); }
        //
        // deviceWideRequested defaults to false, and nothing in the WARP row sets it. So the default
        // state of "switch WARP on" was a tunnel nothing routes through, a settings row claiming
        // connected, and no tun interface on the device:
        //
        //     ip addr  ->  tunl0@NONE: <NOARP> mtu 1480 state DOWN
        //
        // The two switches were separate because a device-wide VPN changes what every other app does
        // and that should be a choice. It still is one - the choice is now made at the moment the
        // switch is pressed, where "WARP on" plainly means WARP carries my traffic, rather than
        // through a second switch the row does not mention.
        try {
            android.content.Intent consent =
                    ColgramMasqueVpnService.start(ctx.getApplicationContext(), bind, edge, socksFront);
            // A non-null result means the VPN consent dialog still has to be shown, and the caller
            // has to start the service again once the user answers it. Logging "started"
            // unconditionally claimed a device-wide tunnel that did not exist - and the switch stayed
            // on with the phone's traffic going nowhere.
            if (consent == null) {
                Log.i(TAG, "device-wide tunnel requested; VpnService started");
            } else {
                Log.i(TAG, "device-wide tunnel needs the VPN consent dialog before it can start");
                // A tunnel nothing routes through is worse than no tunnel: the row says connected and
                // the phone still cannot reach anything.
                lastFailure = "нужно разрешение на VPN для туннеля";
                persist(ctx, trace, false);
                up = false;
            }
        } catch (Throwable vpnFailure) {
            // The tunnel carries traffic but the interface did not install, so nothing on the phone
            // goes through it. That is a failure of the feature, not a warning about a secondary one.
            Log.w(TAG, "tunnel carries traffic but the device-wide interface did not start: "
                    + vpnFailure);
            lastFailure = "интерфейс VPN не поднялся: " + vpnFailure.getMessage();
            persist(ctx, trace, false);
            up = false;
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

    public static synchronized void bringDown() {
        up = false;
        // Switching WARP off takes the interface with it.
        //
        // device_wide was set on the press and never cleared, so a later start found it still true
        // and installed a VPN interface for a tunnel that was off. On the device after one on/off
        // cycle:
        //
        //     colgram_secure_config:  warp_enabled = false
        //     colgram_warp_masque:    device_wide = true
        //
        // which is the whole of the reported "I turned WARP off and something stayed on". The
        // preference is the switch's own record, so the switch has to write it in both directions.
        Context ctx = ColgramPythonEngine.appContext();
        if (ctx != null && deviceWideRequested(ctx)) {
            setDeviceWide(ctx, false);
        }
        // Tell the native side to let go of the carrier. Without this the tunnel's TCP connection to
        // the edge, its return-path reader goroutine and its datagram socket all survive the toggle,
        // and the next bring-up adds a second set beside them. That is the reported shape: a WARP row
        // that keeps stalling, gets slower each time it is tapped, and eventually takes the process
        // with it. A measurement opens its own carrier, so a user who turns WARP on, off and on again
        // pays for every run it ever made.
        //
        // It is guarded because close on a device without the library throws rather than being a
        // no-op, and a teardown that cannot run is still a teardown.
        try {
            if (ColgramMasqueNative.isAvailable()) {
                ColgramMasqueNative.closeSession();
            }
        } catch (Throwable t) {
            Log.w(TAG, "the native tunnel did not close cleanly: " + t.getClass().getSimpleName());
        }
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
                if (!nic.isUp() || nic.isLoopback()) continue;
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
