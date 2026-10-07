package org.colgram.core;

import android.content.Context;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * ColgramMasqueNative — the JNI face of the MASQUE client that carries WARP.
 *
 * The client itself is Go, built with cgo as libcolgrammasque.so, and it is the same code that was
 * measured reading warp=on. It lives in native code for one reason that is not convenience:
 * QUIC needs a TLS 1.3 stack with X25519 and AES-GCM, and the only one available inside an APK
 * without shipping another runtime is Go's. Writing that in Java on Android means either a
 * hand-rolled QUIC with platform crypto - thousands of lines whose failure modes all look like
 * "the network blocked it" - or a library that does not exist in any released form.
 *
 * What is native and what is not:
 *
 *   native   the MASQUE client: registration, enrolment, QUIC, HTTP/3, Connect-IP capsules, and
 *            the TLS client that runs inside the tunnel
 *   java     policy only - which egress to use, whether to relay, what to show the user
 *
 * So the app owns the decisions and the native side owns the protocol, and the verdict comes back
 * as text from Cloudflare's own trace rather than as a boolean this class decides.
 *
 * Loading is lazy and failure is explicit. The library is large, it is only needed when WARP is
 * actually switched on, and a device that cannot load it must say so instead of showing a toggle
 * that quietly does nothing.
 */
public final class ColgramMasqueNative {

    private static final String TAG = "ColgramMasqueNative";
    private static final String LIB = "colgrammasque";

    private static volatile boolean loadAttempted;
    private static volatile boolean loaded;
    private static volatile String loadError;

    private ColgramMasqueNative() {}

    /** Whether the native client is present and loadable on this device. */
    public static synchronized boolean isAvailable() {
        if (!loadAttempted) {
            loadAttempted = true;
            try {
                System.loadLibrary(LIB);
                loaded = true;
            } catch (Throwable t) {
                loaded = false;
                loadError = t.getClass().getSimpleName() + ": " + t.getMessage();
                Log.w(TAG, "native MASQUE client unavailable: " + loadError);
            }
        }
        return loaded;
    }

    /**
     * Whether this library has been loaded into this process already.
     *
     * <p>It exists for the check that the two Go runtimes never share an address space. libbox and this
     * library are both gomobile builds, and each starts a complete Go runtime when it is loaded, so a
     * process that has both has two schedulers and two collectors over one heap. Measured, with the
     * tunnel opened first: a profile that logged intact was parsed by Go as
     *
     *     netip.ParsePrefix("C\x00\x00\x00\x00\x00\x00\x00D")
     *
     * and the process died of "out of memory" from inside its own allocator. Asking whether this process
     * has already loaded it does not start anything - the difference from isAvailable() is that this one
     * never triggers a load.
     */
    public static synchronized boolean isLoadedInThisProcess() {
        return loaded;
    }

    /**
     * Whether the native client could be loaded here, WITHOUT loading it.
     *
     * <p>This is the only safe question to ask from a process that also holds libbox. Both are
     * gomobile builds and each starts a complete Go runtime when it is loaded - its own scheduler,
     * collector and heap metadata - so calling {@link #isAvailable()} from the UI process brings a
     * second one into an address space libbox already owns, and the first thing the engine allocates
     * afterwards faults:
     *
     * <pre>
     *   fatal error: addspecial on invalid pointer
     *   runtime.setprofilebucket -> runtime.mProf_Malloc -> runtime.slicebytetostring
     *   main.decodeString  libbox/seq_android.go:58
     *   proxylibbox__CheckConfig
     * </pre>
     *
     * <p>It answers from the packaged native libraries rather than from a load attempt, so a gate that
     * only needs to know whether the transport exists gets its answer and leaves the heap alone.
     */
    public static synchronized boolean isAvailableForProbe() {
        if (loaded) {
            return true;
        }
        // Ask the package manager, not the dynamic loader. The libraries are in the APK under
        // lib/<abi>/libcolgrammasque.so, so this answers for every ABI the build ships and starts
        // nothing - which is the entire point of the method.
        Context ctx = probeContext;
        if (ctx == null) {
            return false;
        }
        try {
            android.content.pm.ApplicationInfo info = ctx.getApplicationContext()
                    .getApplicationInfo();
            String sourceDir = info.sourceDir;
            if (sourceDir == null) {
                return false;
            }
            java.io.File apk = new java.io.File(sourceDir);
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk);
            try {
                return zip.getEntry("lib/x86_64/libcolgrammasque.so") != null
                        || zip.getEntry("lib/arm64-v8a/libcolgrammasque.so") != null
                        || zip.getEntry("lib/x86/libcolgrammasque.so") != null;
            } finally {
                zip.close();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Set once by the app so a probe can answer without being handed a Context on every tap. */
    private static volatile Context probeContext;

    public static void setProbeContext(Context ctx) {
        probeContext = ctx;
    }

    /** Why the library could not be loaded, or null when it loaded. */
    public static String unavailableReason() {
        return loadError;
    }

    /** The library's own version string, so a stale .so in a build is visible rather than guessed at. */
    public static native String colgram_masque_version();

    /**
     * Opens a tunnel and reads Cloudflare's trace through it.
     *
     * @param bind    the local IPv4 address to leave from, or null to let the kernel choose
     * @param edge    an explicit host:port for the edge, or null for the default edge address
     * @param port    an explicit port, or null for 443
     * @return the raw trace body, or null when the tunnel did not come up
     */
    private static native String colgram_masque_measure(
            String bind, String edge, String port, String relayBind);

    private static native String colgram_masque_last_error();

    /**
     * Points the tunnel's UDP at a SOCKS5 front, as host:port.
     *
     * Needed because of a measured property of the networks this app runs on: UDP 443 is filtered
     * to the WARP edge while TCP 443 to the same address and port connects at once. QUIC needs a
     * bidirectional UDP flow to the edge, so on such a network the tunnel's datagrams have to leave
     * inside the TCP control connection instead. A SOCKS5 relay that already carries TCP to a
     * working egress can carry them, and this is how the front is named to the native client.
     *
     * Set through the library rather than an environment variable, and that is not a style choice:
     * a c-shared library snapshots the environment when it is loaded, so a variable exported after
     * the load is invisible to it. Measured, not assumed - the same process printed WARP_SOCKS=""
     * while a shell in the same invocation printed the value.
     *
     * Pass null or empty to go back to the device's own socket.
     */
    private static native void colgram_masque_set_socks(String hostAndPort);

    /**
     * Names the SOCKS5 front the tunnel should use, or clears it.
     *
     * Safe to call when the library is missing: it only records the address, and the measurement
     * reports the real failure later with a real reason.
     */
    public static void setSocksFront(String hostAndPort) {
        if (!isAvailable()) {
            return;
        }
        try {
            colgram_masque_set_socks(hostAndPort == null || hostAndPort.isEmpty()
                    ? null : hostAndPort);
        } catch (Throwable t) {
            Log.w(TAG, "setSocksFront failed for " + hostAndPort + ": " + t);
        }
    }

    /**
     * Carries one whole IP packet through a MASQUE session and returns whatever comes back.
     *
     * Exists for the device-wide tunnel: the VpnService hands out whole packets and expects whole
     * packets back, and Connect-IP carries exactly that - a capsule whose payload is an IP packet. So
     * no IP stack is needed in Java, and no framing is invented on either side.
     *
     * <p>Blocking, and it must be: it holds a QUIC session open for the life of the tunnel rather
     * than for the life of one measurement, which is why it is a separate entry point and not
     * measure() called in a loop. A fresh registration per packet would be unusable.
     *
     * @return the reply packet, or an empty array when nothing came back before the deadline
     */
    private static native byte[] colgram_masque_exchange(byte[] packet, String bind, String edge);

    /** Starts and keeps a session for the lifetime of the process. Safe to call more than once. */
    private static native void colgram_masque_open_session(String bind, String edge);

    /** Tears the long-lived session down. */
    private static native void colgram_masque_close_session();

    /**
     * Sends one packet on the long-lived session and returns the reply.
     *
     * @return the reply packet, or an empty array when nothing came back
     */
    public static byte[] exchangeIpPacket(byte[] packet, String bind, String edge) {
        if (packet == null || packet.length == 0 || !isAvailable()) {
            return new byte[0];
        }
        try {
            byte[] reply = colgram_masque_exchange(packet, bind, edge);
            return reply == null ? new byte[0] : reply;
        } catch (Throwable t) {
            Log.w(TAG, "exchange failed: " + t);
            return new byte[0];
        }
    }

    /**
     * Opens the long-lived session. Safe to call more than once; the native side keeps one session and
     * ignores the rest.
     *
     * <p>Blocking: the call registers, enrols and dials before it returns, which is several seconds on
     * a network where the first path has to time out before the second is tried. Not for the main
     * thread.
     */
    public static void openSession(String bind, String edge) {
        if (!isAvailable()) return;
        try {
            colgram_masque_open_session(bind == null ? "" : bind, edge == null ? "" : edge);
        } catch (Throwable t) {
            Log.w(TAG, "open session failed: " + t);
        }
    }

    /** Tears the long-lived session down. */
    public static void closeSession() {
        if (!isAvailable()) return;
        try {
            colgram_masque_close_session();
        } catch (Throwable t) {
            Log.w(TAG, "close session failed: " + t);
        }
    }

    /** The last failure in full, so the settings row can say what actually went wrong. */
    public static String lastError() {
        if (!isAvailable()) return unavailableReason();
        try {
            String s = colgram_masque_last_error();
            return s == null || s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return t.getMessage();
        }
    }

    /**
     * How far the long-lived tunnel session got, as text.
     *
     * <p>Exists because every failure mode above looks identical from the device: the interface is
     * up, the pump accepts packets, and nothing comes back. Without this the only signal is the
     * absence of an error message, which is not the same as success -- a session stalled mid-handshake
     * and a session carrying traffic both report "no error".
     *
     * <p>Stages: {@code none}, {@code enrolling}, {@code dialing}, {@code connecting},
     * {@code opening-peer}, {@code open}, or {@code failed} with the reason in parentheses.
     */
    public static String sessionProgress() {
        if (!isAvailable()) return unavailableReason();
        try {
            return colgram_masque_session_progress();
        } catch (Throwable t) {
            return t.getMessage();
        }
    }

    private static native String colgram_masque_session_progress();

    /**
     * What the tunnel has received back, as {@code in=} / {@code dropped=} / {@code bytes=}.
     *
     * <p>{@code in} counts every datagram off the MASQUE stream, {@code dropped} the ones rejected
     * before reaching a flow, and {@code bytes} the payload that did. The distinction is the point:
     * an interface whose tx climbs and whose rx stays at zero looks the same whether nothing came
     * back or everything came back and was discarded, and only one of those is a dead network.
     */
    public static String capsuleStats() {
        if (!isAvailable()) return unavailableReason();
        try {
            return colgram_masque_capsule_stats();
        } catch (Throwable t) {
            return t.getMessage();
        }
    }

    private static native String colgram_masque_capsule_stats();

    /**
     * Reads Cloudflare's trace through the tunnel and returns it whole.
     *
     * <p>The verdict, taken on the phone. The request travels as IP packets inside Connect-IP
     * capsules over the same session the app uses for its own traffic, so a {@code warp=on} here is
     * read over the path the device is actually taking rather than over a host that happens to have
     * an unfiltered connection.
     *
     * @return the trace text, or an empty string with the reason available from
     *         {@link #lastError()}
     */
    public static String trace() {
        if (!isAvailable()) return null;
        try {
            return colgram_masque_trace();
        } catch (Throwable t) {
            Log.w(TAG, "trace failed: " + t);
            return null;
        }
    }

    private static native String colgram_masque_trace();

    /** Whether a trace body reports {@code warp=on}. */
    public static boolean traceIsWarpOn(String trace) {
        if (trace == null) return false;
        for (String line : trace.split("\n")) {
            // The CR matters: an HTTP response read off a socket keeps its line ends, so a plain
            // trim leaves "warp=on\r" and every verdict comes back off.
            if (line.replace("\r", "").trim().equals("warp=on")) return true;
        }
        return false;
    }

    public static String version() {
        if (!isAvailable()) return null;
        try {
            return colgram_masque_version();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Runs the full measurement and parses the trace.
     *
     * This is a blocking call that opens a QUIC session, runs a TLS handshake and reads one HTTPS
     * response. It must not be called on the main thread; {@link ColgramWarpTunnel} owns the
     * thread it runs on.
     */
    public static Map<String, String> measure(
            String bind, String edge, String port, String relayBind) {
        if (!isAvailable()) {
            return Collections.emptyMap();
        }
        String body;
        try {
            body = colgram_masque_measure(bind, edge, port, relayBind);
        } catch (Throwable t) {
            Log.w(TAG, "measure failed", t);
            return Collections.emptyMap();
        }
        if (body == null || body.isEmpty()) {
            return Collections.emptyMap();
        }
        return parseTrace(body);
    }

    /**
     * Turns Cloudflare's trace into fields.
     *
     * The trace is plain text, one key=value per line. It is parsed here rather than in Go so the
     * verdict rule stays visible in one place on the Java side: only warp=on counts, and it is the
     * string Cloudflare returned, not a value this app infers.
     */
    static Map<String, String> parseTrace(String body) {
        Map<String, String> out = new HashMap<>();
        int start = body.indexOf("\r\n\r\n");
        if (start >= 0 && start + 4 <= body.length()) {
            body = body.substring(start + 4);
        }
        for (String line : body.split("\n")) {
            line = line.trim();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            out.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return out;
    }

    /** True only when Cloudflare itself said the request went through WARP. */
    public static boolean warpOn(Map<String, String> trace) {
        return "on".equals(trace.get("warp"));
    }

 /** Encodes a string for the native call, kept here so both call sites agree. */
    static String utf8(String s) {
        return s == null ? null : new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
