src = r'''package org.colgram.core;

import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A datagram socket whose peers are reached through a SOCKS5 proxy's UDP ASSOCIATE.
 *
 * <p>This exists for one measured situation. The WARP edge does not answer UDP from this network at
 * all - a QUIC Initial and a WireGuard handshake to it both get nothing, from every source address, on
 * every port the registration names - while TCP to the same addresses completes TLS and HTTP/3. A
 * proxy's TCP egress is on the far side of that block, so a datagram sent through the proxy's
 * ASSOCIATE leaves from somewhere the edge does answer.
 *
 * <p>Two things make this narrower than it looks, and both were measured rather than assumed:
 *
 * <ul>
 *   <li>UDP ASSOCIATE is a separate capability from CONNECT. The nodes in the pool that complete an
 *       MTProto handshake answer the SOCKS greeting with EOF when it is not MTProto, and the ones on
 *       the odd ports accept the association and then never reply to it. {@link #open} treats both as
 *       "this node cannot carry datagrams" and returns null, and the caller falls back to a direct
 *       socket rather than reporting a session that will never move a packet.
 *   <li>The proxy's BND.ADDR is frequently 0.0.0.0 or 127.0.0.1, which are addresses on the proxy's
 *       side and not on ours. Sending to them reaches nothing, so they are resolved against our own
 *       address, which is the only one of the two the proxy can actually deliver to.
 * </ul>
 */
final class SocksUdpRelay {
    private static final String TAG = "ColgramUdpRelay";
    private static final int GREETING_TIMEOUT_MS = 8000;
    private static final int ASSOCIATE_TIMEOUT_MS = 8000;

    private final Socket control;
    private final DatagramSocket datagrams;
    private final DataInputStream in;
    private final OutputStream out;
    private final String bound;

    private SocksUdpRelay(Socket control, DatagramSocket datagrams,
                          DataInputStream in, OutputStream out, String bound) {
        this.control = control;
        this.datagrams = datagrams;
        this.in = in;
        this.out = out;
        this.bound = bound;
    }

    DatagramSocket socket() {
        return datagrams;
    }

    String boundAddress() {
        return bound;
    }

    /**
     * The relay to use, or null for a direct socket. Reads the same preference key the app writes, so
     * a node that has been proven to carry datagrams is used by the tunnel without anything else having
     * to name it.
     */
    static String udpRelaySetting() {
        try {
            Context app = ColgramConfig.appContext();
            if (app == null) return null;
            return app.getSharedPreferences("colgram_udp", android.content.Context.MODE_PRIVATE)
                    .getString("relay", null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Opens the association, or returns null when this proxy cannot carry datagrams. */
    static SocksUdpRelay open(String hostAndPort) {
        if (hostAndPort == null || hostAndPort.trim().isEmpty()) return null;
        String hp = hostAndPort.trim();
        int colon = hp.lastIndexOf(':');
        if (colon <= 0) return null;
        String host = hp.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(hp.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }

        Socket ctrl = null;
        try {
            ctrl = new Socket();
            ctrl.connect(new InetSocketAddress(
                    ColgramDohResolver.resolveOrSystem(host), port), GREETING_TIMEOUT_MS);
            ctrl.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(ctrl.getInputStream());
            OutputStream out = ctrl.getOutputStream();

            // Greeting: version 5, one method, none.
            out.write(new byte[]{0x05, 0x01, 0x00});
            out.flush();
            int v = in.read();
            int m = in.read();
            if (v != 0x05 || m != 0x00) {
                // A node that answers with anything else on this port speaks a different protocol.
                Log.i(TAG, "relay " + hp + " answered method 0x" + Integer.toHexString(m)
                        + " to a SOCKS greeting; not a SOCKS proxy");
                ctrl.close();
                return null;
            }

            DatagramSocket dgram = new DatagramSocket(new InetSocketAddress(0));
            dgram.setSoTimeout(1);

            // ASSOCIATE, addressed to our own datagram socket so the reply can name something we can
            // actually be reached on.
            byte[] mine = dgram.getLocalAddress().getAddress();
            out.write(new byte[]{0x05, 0x03, 0x00, 0x01,
                    mine[0], mine[1], mine[2], mine[3],
                    (byte) (dgram.getLocalPort() >> 8), (byte) dgram.getLocalPort()});
            out.flush();

            ctrl.setSoTimeout(ASSOCIATE_TIMEOUT_MS);
            int rv = in.read();
            int rc = in.read();
            if (rv != 0x05 || rc != 0x00) {
                Log.i(TAG, "relay " + hp + " refused UDP ASSOCIATE with code " + rc);
                dgram.close();
                ctrl.close();
                return null;
            }
            // VER REP RSV ATYP BND.ADDR BND.PORT, read to its exact length.
            in.read(); // RSV
            int atyp = in.read();
            String bHost = "0.0.0.0";
            if (atyp == 0x01) {
                byte[] a = new byte[4];
                in.readFully(a);
                bHost = (a[0] & 0xFF) + "." + (a[1] & 0xFF) + "." + (a[2] & 0xFF) + "." + (a[3] & 0xFF);
            } else if (atyp == 0x03) {
                int len = in.read();
                byte[] a = new byte[Math.max(0, len)];
                if (len > 0) in.readFully(a);
                bHost = new String(a);
            } else if (atyp == 0x04) {
                byte[] a = new byte[16];
                in.readFully(a);
            }
            int bPort = (in.read() << 8) | in.read();
            if (bPort == 0) {
                Log.i(TAG, "relay " + hp + " bound port 0; it has no address to send to");
                dgram.close();
                ctrl.close();
                return null;
            }
            // 0.0.0.0 and 127.0.0.1 name the proxy's own side. Ours is the only address the proxy can
            // deliver to, so that is what the datagrams go to.
            String target = bHost;
            if ("0.0.0.0".equals(bHost) || "127.0.0.1".equals(bHost)
                    || bHost.startsWith("::")) {
                InetAddress self = ColgramUdpTunnel.localIPv4();
                if (self != null) target = self.getHostAddress();
            }
            return new SocksUdpRelay(ctrl, dgram, in, out, target + ":" + bPort);
        } catch (Throwable t) {
            Log.i(TAG, "relay " + hp + " unusable: " + t.getClass().getSimpleName());
            try {
                if (ctrl != null) ctrl.close();
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /**
     * Wrap one datagram in the SOCKS5 UDP header and send it to the proxy.
     *
     * <p>RSV(2) FRAG(1) ATYP(1) ADDR PORT, then the payload. The header is what the proxy strips,
     * and a peer that is not the one named in the header receives nothing - so every datagram repeats
     * the destination, which is why the front carries the address rather than the tunnel deciding where
     * each packet goes.
     */
    void sendTo(byte[] payload, int len, InetAddress to, int toPort) throws IOException {
        byte[] addr = to.getAddress();
        byte[] pkt = new byte[10 + len];
        pkt[3] = 0x00; // FRAG
        if (addr.length == 4) {
            pkt[4] = 0x01;
            System.arraycopy(addr, 0, pkt, 5, 4);
        } else {
            pkt[4] = 0x04;
            System.arraycopy(addr, 0, pkt, 5, 16);
        }
        int p = 5 + addr.length;
        pkt[p++] = (byte) (toPort >> 8);
        pkt[p++] = (byte) toPort;
        System.arraycopy(payload, 0, pkt, p, len);

        String[] bs = bound.split(":");
        InetAddress bAddr = InetAddress.getByName(bs[0]);
        int bPort = Integer.parseInt(bs[1]);
        datagrams.send(new DatagramPacket(pkt, pkt.length, bAddr, bPort));
    }
}
'''
open(src, 'w', encoding='utf-8').write(src)
print('written', len(src))