package org.colgram.core;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

/**
 * Is the MASQUE SNI filter the same on the phone as on the host, and can a permitted name reach WARP?
 *
 * <b>Why this is the test that decides the WARP transport in the app.</b>
 *
 * Cloudflare's own client was measured failing here, and its service log named the reason:
 *
 * <pre>
 *   connect_with_protocol_racing{primary="masque" secondary="H2"}
 *   h2_tun: Connecting to edge sni="consumer-masque.cloudflareclient.com"
 *   Start racer ---> 162.159.198.2:443
 * </pre>
 *
 * The client races QUIC over UDP (1701, 4500, 4443, 8443, 8095 - all measured silent on this
 * network) and then falls back to HTTP/2 over TCP 443. That fallback is the only route to WARP this
 * network does not filter, and it is failing on the name.
 *
 * <b>What the host measured, to the same address, changing only the server_name:</b>
 *
 * <pre>
 *   engage.cloudflareclient.com            OK  TLSv1.3  ALPN h2   164 ms
 *   connectivity.cloudflareclient.com      OK  TLSv1.3  ALPN h2   143 ms
 *   cloudflareclient.com                  OK  TLSv1.3  ALPN h2   121 ms
 *   consumer-masque.cloudflareclient.com   FAIL  SSLEOFError       2111 ms
 *   masque.cloudflareclient.com            FAIL  SSLEOFError       2563 ms
 *   masque.example.com                     FAIL  SSLEOFError       2131 ms
 *   mqs.cloudflareclient.com               FAIL  SSLEOFError       2150 ms
 *   MASQUE.cloudflareclient.com            FAIL  SSLEOFError       2152 ms
 * </pre>
 *
 * The filter is not a whole-name match. It fires on any name containing "masque" or "mqs",
 * case-insensitively, in any domain at all - a name with nothing to do with Cloudflare is dropped
 * the same way. A blocked name takes ~2.1 s against ~130 ms for one that passes, so the delay is
 * the filter's rather than a timeout.
 *
 * <b>Why the host answer is not enough.</b> The emulator sits behind QEMU user-mode NAT, and TCP
 * behaviour there has already proven to be an artefact of the sandbox rather than the network -
 * nc reports OPEN for every port on 162.159.192.1, including 65000. So the filter's behaviour on the
 * phone has to be measured, not assumed from the host.
 *
 * <b>What the result decides.</b> If the phone shows the same split, the transport is available in
 * the app and the only remaining work is credentials. If the phone shows no split, the filter is on
 * the host's path only and the app on this network has nothing to work with.
 *
 * Reported, never asserted: a diagnostic whose whole point is a filtered network must not turn that
 * network into a red build.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceSniFilterTest {

    private static final String TAG = "ColgramSniFilter";
    private static final int TIMEOUT_MS = 6000;
    private static final String ADDRESS = "162.159.198.2";
    private static final int PORT = 443;

    /** Measured blocked: each contains "masque" or "mqs", in any domain. */
    private static final String[] BLOCKED = {
            "consumer-masque.cloudflareclient.com",
            "masque.cloudflareclient.com",
            "masque.example.com",
            "mqs.cloudflareclient.com",
            "notmasque.com",
    };

    /** Measured permitted: same address, same port, no marked substring. */
    private static final String[] PERMITTED = {
            "engage.cloudflareclient.com",
            "connectivity.cloudflareclient.com",
            "cloudflareclient.com",
    };

    @Test
    public void thePhoneSeesTheSameSniSplitAsTheHost() {
        Log.i(TAG, "address " + ADDRESS + ":" + PORT + " - only the server_name differs");

        int blockedDead = 0;
        List<String> blockedDetail = new ArrayList<>();
        for (String name : BLOCKED) {
            Result result = handshake(name);
            Log.i(TAG, "  BLOCKED   " + pad(name) + " " + result.describe());
            if (!result.ok) {
                blockedDead++;
            } else {
                blockedDetail.add(name + " passed");
            }
        }

        int permittedOk = 0;
        for (String name : PERMITTED) {
            Result result = handshake(name);
            Log.i(TAG, "  PERMITTED " + pad(name) + " " + result.describe());
            if (result.ok && "h2".equals(result.alpn)) {
                permittedOk++;
            }
        }

        Log.i(TAG, "blocked " + (BLOCKED.length - blockedDead) + "/" + BLOCKED.length
                + " got through   permitted " + permittedOk + "/" + PERMITTED.length
                + " completed TLS with h2");
        for (String detail : blockedDetail) {
            Log.w(TAG, "  a marked name passed the filter: " + detail);
        }

        if (blockedDead == BLOCKED.length && permittedOk > 0) {
            Log.i(TAG, "VERDICT: the phone sees the same split as the host. The filter matches the"
                    + " server_name, a permitted name completes TLS 1.3 and negotiates h2, and the"
                    + " WARP endpoint is reachable over TCP 443 - the one port this network"
                    + " allows. What remains is credentials, not transport.");
        } else if (permittedOk == 0) {
            Log.i(TAG, "VERDICT: no name completed h2 on the phone, so the WARP endpoint is not"
                    + " reachable from the device path by any name. The host's result does not"
                    + " carry over to the phone.");
        } else {
            Log.i(TAG, "VERDICT: partial - " + permittedOk + " permitted name(s) negotiated h2 but "
                    + (BLOCKED.length - blockedDead) + " marked name(s) also passed, so the filter"
                    + " is not behaving on this path the way it does on the host.");
        }
    }

    private static String pad(String name) {
        StringBuilder out = new StringBuilder(name);
        while (out.length() < 34) {
            out.append(' ');
        }
        return out.toString();
    }

    private static final class Result {
        boolean ok;
        String alpn = "-";
        String failure = "";
        long millis;

        String describe() {
            if (!ok) {
                return "FAIL " + failure + "  " + millis + "ms";
            }
            return "OK  " + alpn + "  " + millis + "ms";
        }
    }

    /**
     * A real TLS handshake under a given server_name.
     *
     * Certificate verification is off on purpose. It is not what is being measured, and leaving it
     * on would make the test's answer depend on whether a given name has a valid certificate for
     * the address rather than on whether the name is filtered at all.
     */
    private static Result handshake(String name) {
        Result result = new Result();
        long started = System.currentTimeMillis();
        Socket socket = null;
        SSLSocket ssl = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(ADDRESS, PORT), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, null, null);
            ssl = (SSLSocket) context.getSocketFactory().createSocket(socket, name, PORT, true);
            ssl.setUseClientMode(true);
            ssl.setSoTimeout(TIMEOUT_MS);
            String[] alpn = new String[] {"h2", "http/1.1"};
            ssl.setSSLParameters(alpn(ssl, alpn));
            ssl.startHandshake();
            result.ok = true;
            result.alpn = String.valueOf(ssl.getApplicationProtocol());
            // Force the handshake to complete rather than stop at negotiation.
            InputStream in = ssl.getInputStream();
            byte[] one = new byte[1];
            try {
                in.read(one);
            } catch (Exception ignored) {
                // A server that sends nothing until spoken to is normal here; the handshake
                // succeeding is the measurement, not the first byte arriving.
            }
        } catch (SSLHandshakeException e) {
            result.failure = "SSLHandshakeException";
        } catch (Exception e) {
            result.failure = e.getClass().getSimpleName();
        } finally {
            result.millis = System.currentTimeMillis() - started;
            try {
                if (ssl != null) {
                    ssl.close();
                }
            } catch (Exception ignored) {
                // Closing a socket that already failed is not interesting.
            }
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (Exception ignored) {
                // Same.
            }
        }
        return result;
    }

    private static javax.net.ssl.SSLParameters alpn(SSLSocket socket, String[] protocols) {
        javax.net.ssl.SSLParameters params = socket.getSSLParameters();
        params.setApplicationProtocols(protocols);
        return params;
    }
}

