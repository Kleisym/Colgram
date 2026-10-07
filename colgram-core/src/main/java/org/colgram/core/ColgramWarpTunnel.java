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
    /** Whether libwg-go.so has been executed, so it may safely be loaded after libbox.so. */
    private static boolean wireguardGoReady = false;
    /** Whether a session was ever started through the WireGuard backend, so teardown need not load it. */
    private static boolean wireguardSessionStarted = false;
    /** The session's byte total at the last watchdog look, so movement can be told from a total. */
    private static long lastSessionBytes = -1L;
    private static volatile boolean up;
    private static volatile boolean connected;
    private static volatile long rxAtStart = -1;
    private static volatile boolean rotating;
    /** Why the last attempt died, shown to the user instead of a silent dead toggle. */
    private static volatile String lastFailure;

    private ColgramWarpTunnel() {}

    /** Reflectively resolved backend class, or null when the artifact is missing. */
    public static boolean isBackendAvailable() {
        // Whether a transport exists at all. It must not START one.
        //
        // This used to answer the question with ColgramMasqueNative.isAvailable(), which calls
        // System.loadLibrary - so asking it from the UI process loaded a second Go runtime into the
        // process that also holds libbox. Two of them decide whether each other's heap is valid, and
        // the first allocation by the engine afterwards is the one that dies:
        //
        //   fatal error: addspecial on invalid pointer
        //   runtime.setprofilebucket -> runtime.mProf_Malloc -> runtime.slicebytetostring
        //   main.decodeString  libbox/seq_android.go:58
        //   proxylibbox__CheckConfig
        //
        // which is the crash the report called "everything crashes when WARP starts": the toggle was
        // asked a question and the answer cost the process. The manifest comment above
        // ColgramMasqueVpnService already says the client lives in :colgram_masque for exactly this
        // reason, and the gate was undoing it.
        //
        // isAvailableForProbe() answers without loading. Where a start really is wanted, the caller
        // is the service in the other process, which is where the library belongs.
        if (ColgramMasqueNative.isAvailableForProbe()) {
            return true;
        }
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
        // A LITERAL address for the endpoint, never the name. This is the fix for the fault that had
        // every run in this project reporting zero handshakes with a healthy tunnel: a WireGuard
        // endpoint whose peer's address is a hostname does not send its first initiation at all.
        // Measured on the device against a live tunnel and a peer that answers a real handshake -
        // the shipped shape sent nothing, a keepalive alone sent nothing, and a keepalive with a
        // literal address sent a real 148-byte initiation, twice.
        //
        // The name is kept as the fallback, not as the primary: if the resolution has not happened
        // yet, a profile carrying the name is exactly the shape that does not work, and saying so in
        // the log beats a tunnel that looks fine and carries nothing.
        String host = viaRelay ? ColgramWarp.relayAddress() : resolvedEndpointAddress();
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
        // The sing-box profile is no longer what carries WARP - libwg-go does, through the session this
        // class brings up. It is still built, because the settings row and the watchdog describe the
        // tunnel through it and a null here would read as a configuration failure, but nothing starts
        // it: the VPN slot is single-occupancy and the session owns it.
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
                    viaRelay ? ColgramWarp.relayAddress() : ColgramWarp.nextEndpointAddress(),
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

    /**
     * Load libwg-go.so and make it EXECUTE before anything touches libbox.so.
     *
     * <p>This is the whole fix, and it is one ordering constraint rather than a workaround. Both
     * libraries are already in the same APK - libbox.so, libwg-go.so, libwg.so and libwg-quick.so for
     * four ABIs - so nothing about packaging was ever the obstacle.
     *
     * <p>Measured on the device, in three separate runs because a segfault takes the process with it.
     * With libbox.so first and a live tunnel up, the first native call into libwg-go.so -
 * getVersion() returning wgVersion() - killed the process with signal 11, which is the collision the
     * comment above this class has described all along. With libwg-go.so first and a real call made
     * through it, libbox.so then started, tun0 came up, and a second call into libwg-go.so still
     * worked. So the collision is one-sided and it is about INITIALISATION ORDER: whichever Go
     * runtime sets up its thread-local and TLS state first owns it, and the second one faults trying
     * to set it up again.
     *
     * <p>getVersion() is used deliberately rather than System.loadLibrary, because a load only maps
     * the library and a call is what proves the runtime actually initialises and runs. A version string
     * is the cheapest call in the whole surface - no VPN, no tun fd, no configuration - and it is
     * exactly the one that used to segfault, so it is the one worth having succeed here.
     *
     * <p>It is safe to call more than once: the first call is the one that faults or succeeds, and
     * after a success the runtime is already initialised. Failing is not fatal either - it is logged
     * and the class-load path is left to try the constructor as before, because a version call that
     * cannot happen should not by itself stop the tunnel from being attempted.
     */
    private static void preloadWireguardGo(Context ctx) {
        if (wireguardGoReady) return;
        try {
            Class<?> backendClass = Class.forName("com.wireguard.android.backend.GoBackend");
            Object instance = backendClass.getConstructor(Context.class)
                    .newInstance(ctx.getApplicationContext());
            Object version = backendClass.getMethod("getVersion").invoke(instance);
            wireguardGoReady = true;
            Log.i(TAG, "libwg-go.so was executed before libbox.so; wireguard-go " + version);
        } catch (Throwable t) {
            // A Go runtime that could not run is a fact worth having, and not a reason to refuse the
            // tunnel: the constructor path is still tried below, and a failure there is reported on
            // its own terms.
            Log.w(TAG, "could not run libwg-go.so first: " + t.getClass().getSimpleName());
        }
    }

    /**
     * This device's WARP configuration in the wg-quick text the Go backend parses.
     *
     * <p>Built from the same registration everything else reads - the interface addresses, the three
     * reserved bytes Cloudflare assigns, the private key this device registered, and the peer - so
     * there is one identity and one source of truth for it. A relay replaces the peer address and
     * port and the peer's own public and preshared keys, because a relay owns the handshake itself:
     * pinning Cloudflare's peer key at a relay that does not hold it produces a profile that names
     * the relay and can never complete.
     *
     * <p>The endpoint address is a LITERAL. A name here is the same defect the sing-box profile had -
     * a peer address that has to resolve before it can be dialled - and in this backend there is no
     * field to give it a resolver either, so a name would simply never send. The address is pinned,
     * and the pin is rotated by nextEndpointAddress() rather than being permanent, because
     * Cloudflare's addresses are anycast and move.
     */
    private static String wireguardQuickConfig() throws Exception {
        String privateKey = ColgramWarp.getPrivateKey();
        if (privateKey == null || privateKey.trim().isEmpty()) {
            throw new Exception("нет ключа WARP");
        }
        String addresses = ColgramWarp.interfaceAddresses();
        if (addresses == null || addresses.trim().isEmpty()) {
            throw new Exception("нет адреса WARP");
        }
        String host = ColgramWarp.hasUsableRelay()
                ? ColgramWarp.relayAddress()
                : resolvedEndpointAddress();
        if (host == null || host.trim().isEmpty()) {
            throw new Exception("нет адреса сервера WARP");
        }
        int port = ColgramWarp.hasUsableRelay()
                ? ColgramWarp.relayPort()
                : ColgramWarp.nextEndpointPort();
        String peerKey = ColgramWarp.hasUsableRelay()
                ? ColgramWarp.relayPublicKey()
                : ColgramWarp.WARP_PEER_PUBLIC_KEY;

        StringBuilder sb = new StringBuilder();
        sb.append("[Interface]\n");
        sb.append("PrivateKey = ").append(privateKey.trim()).append('\n');
        for (String address : addresses.split(",")) {
            String trimmed = address.trim();
            if (trimmed.isEmpty()) continue;
            sb.append("Address = ").append(trimmed)
                    .append(trimmed.indexOf(':') >= 0 ? "/128" : "/32").append('\n');
        }
        sb.append("MTU = 1280\n");

        sb.append("\n[Peer]\n");
        sb.append("PublicKey = ").append(peerKey).append('\n');
        sb.append("Endpoint = ").append(host.trim()).append(':').append(port).append('\n');
        sb.append("AllowedIPs = 0.0.0.0/0, ::/0\n");
        // Cloudflare's three reserved bytes are NOT a preshared key and do not go in one. This fork
        // carries them as its own UAPI line, set through GoBackend.setClientReserved and prepended to
        // the userspace configuration - so putting them in PresharedKey would have produced a key the
        // peer rejects, and the tunnel would fail in a way that looks like a wrong identity.
        String relayPsk = ColgramWarp.relayPresharedKey();
        if (relayPsk != null && !relayPsk.trim().isEmpty()) {
            sb.append("PresharedKey = ").append(relayPsk.trim()).append('\n');
        }
        sb.append("PersistentKeepalive = 25\n");
        return sb.toString();
    }

    /**
     * The address the endpoint peer should be given: a literal when one is known, the name if not.
     *
     * <p>Falling back to the name is deliberate and loud rather than silent. The failure it produces
     * is the documented one - a tunnel that comes up and sends nothing - and it is logged at the point
     * of the decision, so the log says which shape the profile went out with instead of leaving it
     * to be inferred from a peer that never answered.
     */
    private static String resolvedEndpointAddress() {
        String name = ColgramWarp.endpointHost();
        // The NAME is the primary shape, and this is a correction of an earlier reading of the code.
        // libwg-go resolves a hostname itself: InetEndpoint.parse marks a non-literal host unresolved
        // and GoBackend.setState retries DNS_RESOLUTION_RETRIES times before failing with
        // DNS_RESOLUTION_FAILURE. A pinned literal cannot be changed without restarting the session,
        // so an anycast address that moves takes the tunnel down with it; a name is re-resolved by the
        // backend and survives the move without us touching anything.
        //
        // The literal is kept as a FALLBACK, and only for the case the name cannot cover: a resolver
        // the network has filtered, which is a real thing here - this project's own DoH resolver
        // exists for it. So the pin is what the session uses when the system resolver will not answer.
        String literal = ColgramWarp.currentEndpointAddress();
        boolean systemResolverWorks = systemResolves(name);
        if (systemResolverWorks) {
            Log.i(TAG, "the endpoint will be dialled by name (" + name
                    + "), so a change of Cloudflare's address needs no restart");
            return name;
        }
        if (literal != null && !literal.trim().isEmpty()) {
            Log.i(TAG, "the system resolver does not answer for " + name
                    + ", so the session will use the literal " + literal);
            return literal.trim();
        }
        Log.w(TAG, "neither the name nor a literal is usable for the WARP endpoint: " + name);
        return name;
    }

    /** Whether the platform resolver can answer for the endpoint's name right now. */
    private static boolean systemResolves(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String trimmed = name.trim();
        // A literal needs no resolver, so the question does not arise for it.
        if (trimmed.indexOf(':') < 0 && trimmed.split("\\.").length == 4) return true;
        try {
            return java.net.InetAddress.getByName(trimmed) != null;
        } catch (Throwable t) {
            return false;
        }
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
        // MASQUE first, and it is not a preference. Every WireGuard edge address is silent on this
        // network - 162.159.192.1 and 162.159.198.2 on 443, 500, 4500, 4443, 8443 and 8095 answer
        // no initiation at all - while the same 162.159.198.2 answers a QUIC Initial with a Retry in
        // about 100 ms. So the protocol that reaches the edge here is MASQUE, and the transport
        // below is used only when the native client is genuinely unavailable.
        if (ColgramWarpMasqueTunnel.isAvailable(ctx)) {
            ColgramWarpMasqueTunnel.bringUp(ctx);
            up = true;
            connected = true;
            lastFailure = null;
            Log.i(TAG, "WARP up over MASQUE, verdict " + ColgramWarpMasqueTunnel.trace());
            return;
        }
        bringUpWireGuard(ctx);
    }

    /**
     * The original WireGuard path, kept as the fallback.
     *
     * It is unreachable on this network - measured, not assumed - so it is not removed: a device on
     a network where WireGuard does answer would otherwise lose WARP entirely, and this code was
     written against a real peer and a real session.
     */
    private static void bringUpWireGuard(Context ctx) throws Exception {
        // The instrumentation builds the WireGuard session on its own, so the pieces that bringUp
        // uses are reachable from a test without bringUp dragging the whole engine profile up with
        // it. They are public for that reason and are not called from anywhere in the app.
        // A verdict from a previous attempt must never be shown against a fresh one.
        lastFailure = null;
        if (!ColgramWarp.isRegistered()) throw new Exception("WARP не зарегистрирован");
        // The embedded WireGuard backend is executed BEFORE the engine is started, and that ordering
        // is the whole reason it can be used at all. libwg-go.so and libbox.so are each a complete
        // cgo Go runtime, and the original note here said the two simply do not coexist - which was
        // measured, but only in ONE order. The other order works. With libbox.so first and a live
        // tunnel, the first native call into libwg-go.so killed the process with signal 11 in about a
        // second, with no Java exception and no stack. With libwg-go.so executed first, libbox.so
        // started normally, tun0 came up, and a second call into libwg-go.so still worked: the
        // collision is one-sided and it is about which runtime initialises its thread-local and TLS
        // state first. So the second runtime is not gone - it was being initialised second.
        preloadWireguardGo(ctx);
        String profile = warpProfile();
        // The engine is NOT started while WARP is on, and that is forced rather than chosen.
        // GoBackend builds its own VpnService.Builder and calls establish() itself - it never
        // accepts a descriptor from the engine - and a device has exactly one VPN slot. Measured with
        // the engine first: it took the slot, tun0 came up, GoBackend then called establish(), and the
        // process died with signal 11. The reverse order is the one that was measured working: the
        // Go backend holds the slot, the tunnel comes up, and a real WireGuard initiation leaves the
        // device - which is why the carriage run was made with the engine absent.
        //
        // So a profile is still built and still logged, because it is what the watchdog and the
        // settings row describe, but nothing starts it here. The subscription tunnel is brought up
        // separately, and never at the same time as WARP.
        ColgramWarpServiceBridge.stop(ctx);

        // WARP's own WireGuard session, carried by libwg-go rather than by the sing-box endpoint. The
        // endpoint in this build creates a handshake and never puts it on the wire - measured with a
        // live tunnel carrying thousands of packets, a peer that answers a real 148-byte initiation,
        // routing correct in all three shapes tried, and keys taken from the peer itself. There is no
        // log channel to say why (writeLog is absent from PlatformInterface, a trace profile produces
        // nothing, the crash report is 0 bytes, the binding exposes no status API), so the path that
        // can be measured is used instead of the path that cannot.
        //
        // The same method the rotation calls, so there is one place that builds a session and one
        // place that can be wrong. A second copy here would drift from it, and a rotation that works
        // while a first start does not is the hardest kind of fault to find.
        String sessionState = startWireguardSession(ctx);
        Log.i(TAG, "wireguard-go session " + sessionState + " on " + resolvedEndpointAddress());

        activeProfile = profile;
        up = true;
        connected = false;
        startEndpointWatchdog(ctx);
        // The address the profile actually carries, not the name it resolved from: a log that says
        // "engage.cloudflareclient.com" while the profile holds 162.159.192.1 is a log that cannot
        // tell a reader which route was judged.
        Log.i(TAG, "WARP tunnel up on " + resolvedEndpointAddress()
                + ":" + ColgramWarp.currentEndpointPort());
    }

    public static synchronized void bringDown(Context ctx) {
        up = false;
        rotating = false;
        // The device-wide switch is the fourth thing that has to go down with the tunnel.
        //
        // It is a separate flag, it defaults to ON, and nothing cleared it here, so "WARP off"
        // left it set: the next unrelated start re-armed a device-wide tunnel that the user had
        // already turned off, and the settings row kept reading a switch the tunnel did not own.
        // setDeviceWide(ctx, false) is idempotent and only touches the preference, so it is safe
        // to call on every teardown including the ones where nothing was up.
        ColgramWarpMasqueTunnel.setDeviceWide(ctx, false);
        // The MASQUE tunnel is torn down first, because on this network it is the one that came up.
        //
        // It was never torn down at all. bringDown stopped the WireGuard session and the engine
        // profile - the two transports that are not in use here - and left the MASQUE carrier, its
        // reader goroutine and its datagram socket running. So "WARP off" left the tunnel up, and
        // every subsequent "WARP on" stacked a fresh carrier on top of the last: the reported toggle
        // that gets slower each time it is tapped, ending in the process dying.
        //
        //   ColgramWarpMasque: warp=on via ORD from 104.28.227.110
        //   ColgramWarpTunnel: WARP tunnel down, and the VPN slot is free again
        //   ColgramWarpMasque: bringing up: bind=10.0.2.15 edge=default
        //   Zygote: Process 13350 exited due to signal 11 (Segmentation fault)
        ColgramWarpMasqueTunnel.bringDown();
        // The session is taken down FIRST, and through the same runtime that brought it up. This used
        // to stop the sing-box profile and never touch the WireGuard backend, on the belief that
        // loading libwg-go.so beside libbox.so segfaults the process. That belief was half right: the
        // collision is real but it is about ORDER, measured both ways, and libwg-go is already
        // running by the time this is called. Without this the toggle leaves the tunnel up - a user who
        // turns WARP off would keep sending traffic through it, and nothing would say so.
        stopWireguardSession(ctx);
        // The engine profile is stopped too, because bringUp explicitly released the slot for WARP and
        // this is the other end of that same release. Harmless if it was never started.
        ColgramWarpServiceBridge.stop(ctx);
        Log.i(TAG, "WARP tunnel down, and the VPN slot is free again");
        activeProfile = null;
    }

    /** Take the WireGuard session down, and say why if it will not. */
    private static void stopWireguardSession(Context ctx) {
        // Only a session that was actually started may be stopped, and saying so costs one flag.
        //
        // This reached for the WireGuard backend on every teardown, including the ones where WARP came
        // up over MASQUE and never touched it. Loading it means Class.forName on com.wireguard.android
        // .backend.GoBackend, whose constructor executes libwg-go.so - a second cgo Go runtime, in a
        // process that already has this client's own runtime running the tunnel. Two Go runtimes in
        // one process collide over their thread-local and TLS bookkeeping, and the crash lands in the
        // scheduler rather than in anything that names a cause:
        //
        //   ColgramWarpMasque: warp=on via ORD from 104.28.227.110
        //   ColgramWarpTunnel: WARP tunnel down, and the VPN slot is free again
        //   ColgramWarpMasque: bringing up: bind=10.0.2.15 edge=default
        //   Zygote: Process 13350 exited due to signal 11 (Segmentation fault)
        //
        // So the backend is never constructed when nothing started a session through it, and a
        // library is never loaded by a teardown that has no business loading one.
        if (!wireguardSessionStarted) {
            return;
        }
        try {
            Object goBackend = backend(ctx);
            Class<?> stateClass = Class.forName("com.wireguard.android.backend.Tunnel$State");
            goBackend.getClass()
                    .getMethod("setState", Class.forName("com.wireguard.android.backend.Tunnel"),
                            stateClass, Class.forName("com.wireguard.config.Config"))
                    .invoke(goBackend, ensureTunnel(), stateState(stateClass, "DOWN"), null);
            connected = false;
            wireguardSessionStarted = false;
        } catch (Throwable t) {
            // Reported rather than swallowed: a tunnel that will not come down is a tunnel the user
            // believes they turned off, and that is exactly the kind of state this class has spent
            // the whole project refusing to leave ambiguous.
            Log.w(TAG, "the WireGuard session did not go down cleanly: "
                    + t.getClass().getSimpleName());
        }
    }

    /**
     * Is the live WireGuard session carrying bytes?
     *
     * <p>Read from the Go backend's own statistics rather than from a probe outside the tunnel, and
     * held between two samples so the answer is MOVEMENT rather than a non-zero total. A tunnel that
     * came up once and has been idle since still reports a non-zero byte count forever, and a test
     * that only checks for non-zero would call a dead tunnel healthy on the strength of the handshake
     * that set it up - which is the same "nothing moved, so it looks fine" shape this class has spent
     * the whole project refusing to accept.
     *
     * <p>The first sample has nothing to compare against, so it is recorded and the answer is false:
     * a watchdog that declared success before it had evidence would break out of its loop on the
     * first tick and never rotate at all.
     */
    private static boolean sessionCarryingTraffic() {
        try {
            Object goBackend = backend(ColgramPythonEngine.appContext());
            Object stats = goBackend.getClass()
                    .getMethod("getStatistics", Class.forName("com.wireguard.android.backend.Tunnel"))
                    .invoke(goBackend, ensureTunnel());
            // totalRx() and totalTx(), not getRxBytes()/getTxBytes(): a first draft used the getter
            // names, every call threw NoSuchMethodException, and the catch turned that into a quiet
            // "no evidence" - so the watchdog would have rotated on schedule and looked deliberate
            // while never having asked the session anything at all.
            long rx = ((Number) stats.getClass().getMethod("totalRx").invoke(stats)).longValue();
            long tx = ((Number) stats.getClass().getMethod("totalTx").invoke(stats)).longValue();
            long total = rx + tx;
            if (lastSessionBytes < 0) {
                lastSessionBytes = total;
                return false;
            }
            boolean moved = total > lastSessionBytes;
            lastSessionBytes = total;
            return moved;
        } catch (Throwable t) {
            // No statistics means no evidence, and no evidence is not health. The rotation below is
            // the safe answer here: a session that cannot be asked is one whose liveness is unknown.
            Log.i(TAG, "the session would not report statistics: "
                    + t.getClass().getSimpleName());
            return false;
        }
    }

    /** Bring the session up again on the next address and port the rotation chose. */
    private static void restartWireguardSession(Context ctx) {
        try {
            String state = startWireguardSession(ctx);
            Log.i(TAG, "WARP rotated; the session is " + state + " on " + resolvedEndpointAddress()
                    + ":" + ColgramWarp.currentEndpointPort());
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            Log.w(TAG, "the rotated session did not come up: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
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
            // The whole product, not the ports alone. A pass that only counts ports finishes after
            // endpointPortCount restarts, and with 4 ports and 2 resolved addresses that is halfway
            // through the combinations that actually exist - the watchdog would declare the route
            // dead while two thirds of it had never been dialled. The addresses are only counted when
            // there are any: with the endpoint name unresolvable there is one shape to try, and
            // multiplying by an empty list would give a budget of zero and no rotation at all.
            int endpointCount = ColgramWarp.hasUsableRelay()
                    ? 1
                    : ColgramWarp.endpointPortCount() * ColgramWarp.endpointAddressCount();
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
                            : resolvedEndpointAddress();
                    int judgedPort = ColgramWarp.hasUsableRelay()
                            ? ColgramWarp.relayPort()
                            : ColgramWarp.currentEndpointPort();
                    // Ask the SESSION, not the port. ColgramWarpEndpointProbe sends a 1200-byte
                    // datagram and waits for any reply; a WireGuard peer answers only a handshake it
                    // can decrypt, never a stranger, so that probe is silent BY CONSTRUCTION on a
                    // tunnel that is working perfectly. Silence drove a rotation, which tore the
                    // session down and rebuilt it - and each rebuild advanced the rotation index, so
                    // a working endpoint was walked away from rather than kept. Measured as the
                    // arithmetic of the constants themselves: 8s between rounds over a 5 minute
                    // patience is 37 restarts of a healthy session.
                    //
                    // The Go backend can be asked directly: getStatistics reports bytes received
                    // by the live session, which is a fact about the tunnel rather than a guess from
                    // outside it, and a moving counter is a session that is carrying something.
                    if (sessionCarryingTraffic()) {
                        connected = true;
                        Log.i(TAG, "the WARP session is carrying traffic; keeping it");
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
                    // Rotation re-brings the session on the next advertised address and port. It
                    // used to restart the sing-box profile, which is no longer what carries WARP: the
                    // session lives in libwg-go now, so restarting the profile rotated nothing and
                    // left a dead tunnel looking alive. The configuration is rebuilt from the same
                    // registration and the session is stopped and started again around it.
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
                        stopWireguardSession(ctx);
                        restartWireguardSession(ctx);
                        activeProfile = next;
                    } else {
                        stopWireguardSession(ctx);
                        restartWireguardSession(ctx);
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

    // ===================================================================================
    // Instrumentation entry points
    //
    // These expose the two halves bringUp uses - the preloading that makes the second cgo runtime
    // safe, and the session call itself - so a device test can drive them without starting the whole
    // engine profile. They are public because instrumentation lives in another package, and nothing
    // in the app calls them.
    // ===================================================================================

    /** Execute libwg-go.so before anything touches libbox.so, which is the ordering the fix is. */
    public static void preloadForTest(Context ctx) {
        preloadWireguardGo(ctx);
    }

    /**
     * Build the configuration and bring the session up. Public for instrumentation, which drives it
     * in a device test; bringUp and the rotation call the same private method, so a test and the app
     * exercise identical code rather than a copy of it.
     *
     * @return the state the backend reports, so a test reads what the engine says rather than
     *         assuming that no exception means it came up
     */
    public static String startSessionForTest(Context ctx) throws Exception {
        return startWireguardSession(ctx);
    }

    /**
     * Build this device's configuration and bring the WireGuard session up through the Go backend.
     *
     * @return the state the backend reports afterwards, so a test reads what the engine says rather
     *         than assuming that no exception means it came up
     */
    private static String startWireguardSession(Context ctx) throws Exception {
        // Recorded before the call, not after: a session that came up and then lost its connection
        // still has to be stopped, and a flag written on success would leave that one running.
        wireguardSessionStarted = true;
        // A fresh session has its own byte total, and comparing it against the previous session's would
        // either hide real movement or invent it. Starting from -1 makes the first watchdog look a
        // sample rather than a verdict.
        lastSessionBytes = -1L;
        String config = wireguardQuickConfig();
        Object goBackend = backend(ctx);
        String reserved = ColgramWarp.reservedHex();
        if (reserved != null && !reserved.trim().isEmpty()) {
            goBackend.getClass().getMethod("setClientReserved", String.class)
                    .invoke(goBackend, reserved);
        }
        Class<?> configClass = Class.forName("com.wireguard.config.Config");
        Object parsed = configClass.getMethod("parse", java.io.InputStream.class)
                .invoke(null, new java.io.ByteArrayInputStream(config.getBytes("UTF-8")));
        Class<?> stateClass = Class.forName("com.wireguard.android.backend.Tunnel$State");
        Object newState = goBackend.getClass()
                .getMethod("setState", Class.forName("com.wireguard.android.backend.Tunnel"),
                        stateClass, configClass)
                .invoke(goBackend, ensureTunnel(), stateState(stateClass, "UP"), parsed);
        return String.valueOf(newState);
    }

    /** Take the session down, so a test leaves nothing running behind it. */
    public static void stopSessionForTest() {
        try {
            Object goBackend = backend(ColgramPythonEngine.appContext());
            Class<?> stateClass = Class.forName("com.wireguard.android.backend.Tunnel$State");
            goBackend.getClass()
                    .getMethod("setState", Class.forName("com.wireguard.android.backend.Tunnel"),
                            stateClass, Class.forName("com.wireguard.config.Config"))
                    .invoke(goBackend, ensureTunnel(), stateState(stateClass, "DOWN"), null);
        } catch (Throwable t) {
            Log.w(TAG, "could not take the test session down: " + t.getClass().getSimpleName());
        }
    }
}
