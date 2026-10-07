package org.colgram.core;

import android.util.Log;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Enumeration;

/**
 * ColgramUdpTunnel - carries a tunnel's UDP over TCP, so a tunnel can start on a network that
 * filters UDP to its edge.
 *
 * <p>Why it exists, measured rather than assumed. On the networks this has to survive, UDP to the
 * WARP edge is dropped on every port the edge serves QUIC on, while TCP to the same address and port
 * connects at once:
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
 * <p>QUIC needs a bidirectional UDP flow, so on such a network its datagrams have to leave inside a
 * TCP stream. A SOCKS5 UDP ASSOCIATE describes exactly that, except RFC 1928 has the client send to a
 * UDP port - and it is the UDP port the filter closes. So the datagrams ride the control connection,
 * with an explicit two-byte length in front of each one.
 *
 * <p>That length is not optional. TCP has no message boundaries: two datagrams arriving together come
 * back as one read, and the second one's SOCKS5 header lands in the middle of the first one's
 * payload. quic-go then sees a QUIC packet with a stray leading byte and the edge ignores it, with no
 * error anywhere - the tunnel reports a plain timeout and nothing in any log says why.
 *
 * <p>The header is four bytes before the address, not three: RSV(2), FRAG(1), ATYP(1). Counting
 * three leaves the destination port's high byte in front of the payload, and the symptom is identical
 * - an edge that never answers a packet it is being sent.
 *
 * <p>This runs on the device's own socket and answers only on loopback, so no route, firewall or
 * DNS change is involved. Where the egress is not the one the edge answers stays a host-side question
 * and is not what this class is for.
 */
public final class ColgramUdpTunnel {

    private static final String TAG = "ColgramUdpTunnel";

    /** A QUIC packet plus a SOCKS5 UDP header, all inside one frame. */
    private static final int DATAGRAM_MAX = 2048;
    /** Read budget on the upstream socket. Idling out a healthy flow would cut a live tunnel. */
    private static final int UPSTREAM_IDLE_MS = 30000;
    /** Give a write this long before the control connection is declared unusable. */
    private static final int WRITE_TIMEOUT_MS = 10000;

    private static volatile ServerSocket listener;
    private static volatile int boundPort = -1;

    private ColgramUdpTunnel() {}

    /** Bind the loopback front. Idempotent; returns the port, or -1 when it cannot bind. */
    public static synchronized int start() {
        if (boundPort > 0) return boundPort;
        try {
            ServerSocket ss = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            listener = ss;
            boundPort = ss.getLocalPort();
            Thread t = new Thread(ColgramUdpTunnel::acceptLoop, "colgram-udp-tunnel");
            t.setDaemon(true);
            t.start();
            Log.i(TAG, "UDP-over-TCP front on 127.0.0.1:" + boundPort);
        } catch (Throwable e) {
            Log.w(TAG, "could not bind the UDP front: " + e);
            boundPort = -1;
        }
        return boundPort;
    }

    /** Tear the front down. A session already running keeps going until its peer closes. */
    public static synchronized void stop() {
        if (listener != null) {
            try { listener.close(); } catch (Throwable ignored) {}
            listener = null;
        }
        boundPort = -1;
    }

    public static boolean isRunning() {
        return boundPort > 0;
    }

    /** The address to hand the tunnel, as host:port, or null when the front is not up. */
    /**
     * A SOCKS5 proxy whose UDP ASSOCIATE should carry the tunnel datagrams, or null for a direct
     * socket. Set by the caller that knows which node has been proven to relay datagrams, so this class
     * needs no Context of its own.
     */
    private static volatile String udpRelayName;

    /** Names the proxy to send datagrams through, or null to send them directly. */
    public static void setUdpRelay(String hostAndPort) {
        udpRelayName = (hostAndPort == null || hostAndPort.trim().isEmpty())
                ? null : hostAndPort.trim();
    }

    public static String udpRelay() {
        return udpRelayName;
    }

    public static String frontAddress() {
        int p = boundPort;
        return p > 0 ? "127.0.0.1:" + p : null;
    }

    /**
     * Starts the front and reports what it can carry, in one line.
     *
     * Exists so the front can be checked by measurement rather than by a flag: "is the front there"
     * and "does the front carry the edge's QUIC" are different questions, and only the second one
     * says the tunnel can start. Cheap, and it does not touch the user's settings.
     */
    public static String selfTest() {
        int p = start();
        if (p <= 0) return "front failed to bind";
        return "front on 127.0.0.1:" + p;
    }

    private static void acceptLoop() {
        while (true) {
            final Socket client;
            try {
                ServerSocket ss = listener;
                if (ss == null) return;
                client = ss.accept();
            } catch (Throwable t) {
                if (listener == null) return;
                continue;
            }
            Thread t = new Thread(() -> serve(client), "colgram-udp-tunnel-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    /** One client: a SOCKS5 UDP ASSOCIATE whose datagrams ride the control connection. */
    private static void serve(Socket client) {
        DatagramSocket upstream = null;
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(WRITE_TIMEOUT_MS);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // Greeting. Three bytes for a one-method offer; reading two would leave one behind and
            // the next read would start mid-stream, which resets the connection with no error.
            int ver = in.read();
            int nmethods = in.read();
            if (ver != 0x05 || nmethods < 1) return;
            for (int i = 0; i < nmethods; i++) in.read();
            out.write(new byte[]{0x05, 0x00});
            out.flush();

            // Request: version, command, reserved, then the address.
            int reqVer = in.read();
            int cmd = in.read();
            int reserved = in.read();
            int atyp = in.read();
            if (reqVer != 0x05 || reserved != 0x00) return;

            // CONNECT is answered here as well as UDP ASSOCIATE, and its absence was the last
            // thing between the app and a tunnel it owned.
            //
            // The front speaks SOCKS5, and the client's own HTTPS travels over it: the DoH lookups
            // that find the API address, and the enrolment POST/PATCH that registers the device.
            // Both are CONNECT. This front answered only 0x03 and closed everything else, so the
            // resolver's connection died the moment it asked, and the log said so without ever
            // naming the front:
            //
            //     Get "https://1.1.1.1/dns-query?name=api.cloudflareclient.com&type=A": read tcp
            //     127.0.0.1:52938->127.0.0.1:42787: read: connection reset by peer
            //
            // A reset rather than a refusal, because the front returned from serve() mid-handshake.
            // Everything UDP worked; only the plain-TCP half of the same front was missing.
            if (cmd == 0x01) {
                Log.i(TAG, "connect requested, atyp=" + atyp);
                serveConnect(in, out, client, atyp);
                return;
            }
            if (cmd != 0x03) return;
            drainAddress(in, atyp);

            // One upstream socket per session, for the same reason there was one per session in the
            // two-process relay this replaces: a fresh socket per packet changes the source port,
            // and the edge treats each source port as an unrelated flow and answers none of them.
            // Bound to a concrete local IPv4 address, never to a wildcard.
            //
            // Every wildcard spelling tried produced a dual-stack socket on this device and the log
            // said so plainly: "upstream bound to ::", "first datagram: 1200 bytes to /162.159.198.2
            // from ::/::". The address was IPv4 and the port was right, but the datagrams went out
            // as IPv4-mapped IPv6 and never came back, so the session associated, the counters
            // moved, and the client timed out with nothing anywhere to say why.
            //
            // Binding to this host's own IPv4 address cannot be reinterpreted as a wildcard, so the
            // socket is IPv4 by construction. The address is read from the interfaces rather than
            // hardcoded, because which one is local is a property of the device.
            InetAddress local = localIPv4();
            // A relay, when one is named, carries the datagrams instead of this device's own socket.
            //
            // The MASQUE edge is unreachable by UDP from this network - measured for QUIC and for
            // WireGuard, from every source address, with no global IPv6 - while a pool of public
            // proxies completes a real MTProto handshake through TCP. So the only path left is to send
            // the tunnel's UDP out through a proxy's egress. That needs UDP ASSOCIATE on that proxy,
            // which is a separate capability from CONNECT and not every node has it; where it is
            // missing the relay refuses and the direct socket is used instead.
            SocksUdpRelay relay = SocksUdpRelay.open(udpRelayName);
            if (relay != null) {
                upstream = relay.socket();
                Log.i(TAG, "udp leaving through SOCKS relay " + udpRelayName
                        + ", relay bound at " + relay.boundAddress());
            } else {
                upstream = new DatagramSocket(new InetSocketAddress(
                        local != null ? local : InetAddress.getByAddress(new byte[]{0, 0, 0, 0}), 0));
                upstream.setSoTimeout(UPSTREAM_IDLE_MS);
            }

            // The reply names loopback and this session's upstream port. The client then keeps using
            // the control connection - the port matters because the SOCKS5 header of every relayed
            // datagram repeats it, not because datagrams are sent to it.
            int port = upstream.getLocalPort();
            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1,
                    (byte) (port >>> 8), (byte) port});
            out.flush();
            Log.i(TAG, "associate on 127.0.0.1:" + port + ", upstream bound to "
                    + upstream.getLocalAddress().getHostAddress());

            // A final handle for the reader. upstream is assigned in this method and cleared in
            // its finally block, so it is not effectively final and a lambda may not capture it.
            final DatagramSocket socket = upstream;
            Thread back = new Thread(() -> pumpBack(socket, out, client, relay),
                    "colgram-udp-tunnel-back");
            back.setDaemon(true);
            back.start();

            pumpFront(socket, in, relay);
        } catch (Throwable t) {
            Log.w(TAG, "session ended: " + t.getClass().getSimpleName());
        } finally {
            if (upstream != null) upstream.close();
            try { client.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Answers a SOCKS5 CONNECT by dialling the target from the device and splicing the two streams.
     *
     * <p>The target is reached over this device's own TCP, which is the point: the DoH resolvers and
     * the enrolment API are HTTPS, and this network filters the UDP path but not TCP. Nothing is
     * proxied anywhere off the device -- the socket this opens belongs to the app.
     *
     * <p>The reply is sent before any byte is read from the client, because a client that has paid
     * for a connection will start writing immediately and a late reply would look like a stall.
     */
    private static void serveConnect(InputStream in, OutputStream out, Socket client, int atyp)
            throws Exception {
        String host;
        if (atyp == 0x01) {
            host = InetAddress.getByAddress(readFully(in, 4)).getHostAddress();
        } else if (atyp == 0x04) {
            host = InetAddress.getByAddress(readFully(in, 16)).getHostAddress();
        } else if (atyp == 0x03) {
            int len = in.read();
            if (len <= 0) return;
            host = new String(readFully(in, len), java.nio.charset.StandardCharsets.UTF_8);
        } else {
            return;
        }
        int port = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);

        Socket up = null;
        try {
            // The client socket arrived with a read timeout for the UDP path, which is right there
            // and fatal here: an HTTPS response can idle longer than that between packets and the
            // timeout would close a working connection mid-transfer. Zero means block.
            client.setSoTimeout(0);
            up = new Socket();
            // Bound to this device's own IPv4 address, for the same reason the UDP path binds there:
            // a wildcard connect gets a source address chosen per destination, and the ones that do
            // not answer are chosen silently. The UDP path's fix -- logged as "upstream bound to
            // 10.0.2.15" -- applies to TCP for exactly the same reason.
            InetAddress local = localIPv4();
            if (local != null) {
                up.bind(new InetSocketAddress(local, 0));
            }
            up.connect(new InetSocketAddress(host, port), 15000);
            Log.i(TAG, "connected to " + host + ":" + port + " from "
                    + up.getLocalSocketAddress());
            up.setTcpNoDelay(true);
            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            out.flush();
            splice(client, up);
        } catch (Throwable t) {
            // 0x01 is "general failure"; the client reports it far better than a reset does.
            try {
                out.write(new byte[]{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
            } catch (Throwable ignored) {
            }
            Log.w(TAG, "connect to " + host + ":" + port + " failed: " + t.getClass().getSimpleName());
        } finally {
            if (up != null) {
                try { up.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Copies both ways until either side closes, then returns. */
    private static void splice(Socket client, Socket up) throws Exception {
        java.io.InputStream cin = client.getInputStream();
        java.io.InputStream uin = up.getInputStream();
        java.io.OutputStream cout = up.getOutputStream();
        Thread a = new Thread(() -> {
            try { cin.transferTo(cout); } catch (Throwable ignored) {}
        }, "colgram-connect-out");
        Thread b = new Thread(() -> {
            try { uin.transferTo(client.getOutputStream()); } catch (Throwable ignored) {}
        }, "colgram-connect-in");
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
        a.join();
        b.join();
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("short address");
            off += r;
        }
        return buf;
    }

    /** Read the client's datagrams off the control connection and send each to the edge. */
    private static void pumpFront(DatagramSocket upstream, InputStream in,
                                 SocksUdpRelay relay) throws IOException {
        byte[] head = new byte[2];
        byte[] body = new byte[DATAGRAM_MAX + 16];
        boolean sentLogged = false;
        while (true) {
            readFully(in, head, 2);
            int size = ((head[0] & 0xFF) << 8) | (head[1] & 0xFF);
            if (size <= 0 || size > body.length) return;
            readFully(in, body, size);

            // Strip the SOCKS5 UDP header. Four bytes come before the address - RSV(2), FRAG(1),
            // ATYP(1) - and getting that wrong shifts the payload, which the edge discards in
            // silence.
            if (size < 4 || (body[2] & 0xFF) != 0) continue;
            int atyp = body[3] & 0xFF;
            int addrLen;
            if (atyp == 0x01) addrLen = 4;
            else if (atyp == 0x04) addrLen = 16;
            else continue;
            int offset = 4 + addrLen + 2;
            if (size < offset) continue;

            // Copied out rather than passed with an offset: InetAddress.getByAddress(byte[], int, int)
            // is not in the Android API this module compiles against, only
            // getByAddress(byte[]) and the host-taking form are.
            byte[] raw = new byte[addrLen];
            System.arraycopy(body, 4, raw, 0, addrLen);
            InetAddress to = InetAddress.getByAddress(raw);
            int port = ((body[offset - 2] & 0xFF) << 8) | (body[offset - 1] & 0xFF);
            int payload = size - offset;
            if (payload <= 0 || port <= 0) continue;
            if (!sentLogged) {
                // One line per session saying where the first datagram actually went. A front that
                // binds an unusable socket produces every other symptom of a filtered path - the
                // session associates, the counters move, and nothing ever comes back - so the
                // socket's own address has to appear once where a reader can act on it.
                sentLogged = true;
                Log.i(TAG, "first datagram: " + payload + " bytes to " + to + ":" + port
                        + " from " + upstream.getLocalAddress());
            }
            // Through the proxy when one is open, and straight out of the device otherwise. The
            // destination travels in the SOCKS header either way, because the proxy is the one that
            // has to decide where a datagram goes and a direct socket already knows.
            if (relay != null) {
                relay.sendTo(body, offset, to, port);
            } else {
                upstream.send(new DatagramPacket(body, offset, payload, to, port));
            }
        }
    }

    /** Read the edge's replies and hand each back, framed the way the client sends them. */
    private static void pumpBack(DatagramSocket upstream, OutputStream out, Socket client,
                                 SocksUdpRelay relay) {
        byte[] buffer = new byte[DATAGRAM_MAX];
        try {
            while (!client.isClosed()) {
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    upstream.receive(reply);
                } catch (SocketTimeoutException idle) {
                    continue;
                } catch (Throwable gone) {
                    return;
                }

                // Through a proxy the datagram arrives wrapped: RSV(2) FRAG(1) ATYP(1) ADDR PORT, then
                // the payload. Handing that to the client as if it were the edge's packet would put a
                // ten byte SOCKS header in front of every IP packet the tunnel reads, and the tunnel
                // would discard them all as malformed - which is exactly what a broken frame looks
                // like from the outside.
                int skip = 0;
                if (relay != null) {
                    int atyp = reply.getLength() > 4 ? (buffer[reply.getOffset() + 3] & 0xFF) : 0;
                    int addrLen = atyp == 0x01 ? 4 : atyp == 0x04 ? 16 : -1;
                    if (addrLen < 0) {
                        // A fragment, or something that is not a datagram. Dropped rather than
                        // forwarded, because forwarding it shifts every later byte.
                        continue;
                    }
                    skip = 4 + addrLen + 2;
                    if (reply.getLength() <= skip) continue;
                }

                // Two length bytes, then RSV(2) FRAG(1) ATYP=IPv4(1) ADDR(4) PORT(2), then payload.
                // That is 12 bytes of header, not 10, because the two length bytes are counted too.
                int header = 12;
                int body = reply.getLength() - skip;
                int total = header + body;
                byte[] frame = new byte[2 + total];
                frame[0] = (byte) (total >>> 8);
                frame[1] = (byte) total;
                // The client strips the two length bytes and then reads FRAG at index 2 of what is
                // left and ATYP at index 3, which puts FRAG at frame[4] here and ATYP at frame[5].
                // ATYP was being written at frame[4], so the client read the value 1 as a fragment
                // number and refused the reply:
                //
                //   quic: transport closed: unexpected socks fragment 0x01
                //
                // Every index below is written explicitly rather than left to the array default,
                // because the whole fault was an index that happened to already be zero in one
                // direction and not in the other.
                frame[2] = 0;
                frame[3] = 0;
                frame[4] = 0;
                frame[5] = 1;
                byte[] addr = client.getLocalAddress().getAddress();
                System.arraycopy(addr, 0, frame, 6, Math.min(addr.length, 4));
                System.arraycopy(reply.getData(), reply.getOffset() + skip, frame, header, body);

                // Guarded: the front thread and this one both write, and an interleaved write would
                // corrupt the client's framing in a way that reads as a network fault.
                synchronized (out) {
                    client.setSoTimeout(WRITE_TIMEOUT_MS);
                    out.write(frame);
                    out.flush();
                }
            }
        } catch (Throwable gone) {
            // The client went away or the socket closed; either way this reader's job is over.
        }
    }

    /** Read exactly len bytes, or fail. A partial read must never be mistaken for data. */
    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, read, len - read);
            if (n < 0) throw new EOFException("control connection closed");
            read += n;
        }
    }

    /**
     * This host's own non-loopback IPv4 address, or null when it has none.
     *
     * Used to pin the upstream socket to IPv4 by binding to a real address. Read from the
     * interfaces rather than guessed, because which addresses exist is a property of the device and
     * the emulator's gateway is not a usable bind target.
     */
    static InetAddress localIPv4() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address)) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.startsWith("127.")) continue;
                    return addr;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the wildcard below; a failure here is not worth refusing a session.
        }
        return null;
    }

    /** Consume the DST.ADDR/DST.PORT an ASSOCIATE carries. Its value is advisory; it must be read. */
    private static void drainAddress(InputStream in, int atyp) throws IOException {
        byte[] skip;
        switch (atyp) {
            case 0x01: skip = new byte[6]; break;
            case 0x04: skip = new byte[18]; break;
            case 0x03:
                int n = in.read();
                if (n < 0) throw new EOFException("short domain name");
                skip = new byte[n + 2];
                break;
            default:
                throw new IOException("unknown address type " + atyp);
        }
        readFully(in, skip, skip.length);
    }
}
