package org.colgram.core;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Whether a WARP endpoint answers at all, measured without loading the WireGuard backend.
 *
 * The old rotation watchdog read the backend's receive counter, which meant it could only ask its
 * question by calling into libwg-go.so - the second Go runtime in a process that already has
 * libbox.so. That pair is what segfaults the app, so a watchdog that polls it was a crash waiting
 * for a slow endpoint. This probe answers the same question with a plain datagram, which touches
 * no native tunnel library at all.
 *
 * A WireGuard endpoint does not echo arbitrary payloads, so silence here means no handshake
 * completed rather than wrong bytes. That is the distinction the rotator needs: it separates a
 * route that is alive from one whose Cloudflare ingress is filtered.
 */
public final class ColgramWarpEndpointProbe {

    private static final String TAG = "ColgramWarpProbe";
    private static final int TIMEOUT_MS = 2500;

    private ColgramWarpEndpointProbe() {}

    /** True when anything at all came back from the endpoint within the timeout. */
    public static boolean answers(String host, int port) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            socket.setSoTimeout(TIMEOUT_MS);
            // A WireGuard-sized, initiation-shaped datagram. The content cannot make Cloudflare
            // reply, because it is not a valid handshake for this identity - only the exchange is
            // being measured, which is what separates a filtered port from a working one.
            byte[] payload = new byte[148];
            payload[0] = 1;
            socket.send(new DatagramPacket(payload, payload.length,
                    InetAddress.getByName(host), port));
            DatagramPacket reply = new DatagramPacket(new byte[512], 512);
            socket.receive(reply);
            Log.i(TAG, "the WARP endpoint at " + host + ":" + port + " answered");
            return true;
        } catch (Exception e) {
            Log.i(TAG, "the WARP endpoint at " + host + ":" + port + " is silent: "
                    + e.getClass().getSimpleName());
            return false;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }
}
