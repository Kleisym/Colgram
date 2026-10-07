package org.colgram.core;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Whether the route to a WARP endpoint carries UDP at all, measured without the engine.
 *
 * <p>What this answers, precisely: can a datagram reach {@code host:port} and come back. That is the
 * only question the endpoint watchdog asks, and it is the question a moving filter can be judged on.
 * A QUIC Version Negotiation on 443 is a real answer from the edge, and it says the route is open.
 *
 * <p>What this does NOT answer, and a version of this class got that wrong badly enough to be worth
 * writing down. A first attempt was rewritten to send a real 148-byte WireGuard message-initiation,
 * on the reasoning that a peer answers only a handshake it can decrypt and that zeros prove nothing.
 * That reasoning is right about the zeros and wrong about the conclusion: the encrypted region of an
 * initiation is sealed under a key derived from DH(ephemeral, peer static), so an initiation whose
 * encrypted region is zeroed is not decryptable by anyone - the peer stays silent, and the probe
 * reports "filtered" about a route that is wide open. Making this answer a WireGuard question means
 * implementing Noise_IKpsk2 on the peer side, not fiddling with the sender's bytes. So it stays a
 * reachability probe, and says so.
 *
 * <p>What a caller may conclude from {@code true}: the route is open, and the endpoint on it is worth
 * dialling. What a caller may conclude from {@code false}: no answer came back within the timeout.
 * Whether that is a filtered route, a peer that does not answer strangers, or a one-off loss is not
 * decided here and must not be decided from this alone.
 */
public final class ColgramWarpEndpointProbe {

    private static final String TAG = "ColgramWarpProbe";
    private static final int TIMEOUT_MS = 2500;

    private ColgramWarpEndpointProbe() {}

    /** True when a datagram to the endpoint came back at all within the timeout. */
    public static boolean answers(String host, int port) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            socket.setSoTimeout(TIMEOUT_MS);
            // 1200 bytes, and that size is the point rather than an accident. A WireGuard
            // message-initiation is 148 bytes and is never answered as a stranger's datagram; a QUIC
            // Version Negotiation is 31 bytes and only comes back to a datagram big enough to be a
            // plausible first flight. Probing small reports silence on an open port, and the watchdog
            // answers silence by restarting the engine - so a probe that understates what it is
            // asking for takes working tunnels down.
            byte[] payload = new byte[1200];
            payload[0] = 1;
            socket.send(new DatagramPacket(payload, payload.length,
                    InetAddress.getByName(host), port));
            DatagramPacket reply = new DatagramPacket(new byte[512], 512);
            socket.receive(reply);
            Log.i(TAG, "the WARP route to " + host + ":" + port + " answered "
                    + reply.getLength() + " bytes");
            return true;
        } catch (Exception e) {
            Log.i(TAG, "the WARP route to " + host + ":" + port + " returned nothing: "
                    + e.getClass().getSimpleName());
            return false;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }
}
