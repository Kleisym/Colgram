package org.colgram.core;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Is the device's UDP block about the DESTINATION, or about one destination's ports?
 *
 * The device reaches 162.159.192.1 over ICMP in about 9 ms, so the route is up and packets are not
 * being discarded as "no route to host" - only UDP is affected. On the host, exactly one port on
 * that address answers over UDP (443) while 53, 80, 500, 1234, 2408 and 4500 are all silent. The
 * same question asked of the device decides what the relay has to carry: a block that spared DNS
 * would be a different problem from a port allowlist, and only one of them is a size artefact.
 *
 * Reported, never asserted - a test that failed on a filtered network could only pass on an
 * unfiltered one, which is exactly where it would be least needed.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceUdpShapeTest {

    private static final String TAG = "ColgramUdpShape";
    private static final int TIMEOUT_MS = 3000;
    private static final int PROBE_BYTES = 1200;

    @Test
    public void theDeviceIsProbedAcrossPortsAndDestinations() {
        String[] hosts = {"162.159.192.1", "1.1.1.1", "8.8.8.8"};
        int[] ports = {53, 443, 2408, 500, 4500, 1234};
        for (String host : hosts) {
            StringBuilder row = new StringBuilder();
            for (int port : ports) {
                row.append(" ").append(port).append(":")
                        .append(answers(host, port) ? "YES" : "no");
            }
            Log.i(TAG, host + row);
        }
    }

    private static boolean answers(String host, int port) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            socket.setSoTimeout(TIMEOUT_MS);
            // A DNS query on 53 and opaque bytes elsewhere: what is being asked is whether
            // anything comes back, not whether the payload is meaningful to the receiver.
            byte[] payload = port == 53 ? dnsQuery() : opaque();
            socket.send(new DatagramPacket(payload, payload.length,
                    InetAddress.getByName(host), port));
            DatagramPacket reply = new DatagramPacket(new byte[512], 512);
            socket.receive(reply);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (socket != null) socket.close();
        }
    }

    private static byte[] opaque() {
        byte[] out = new byte[PROBE_BYTES];
        for (int i = 0; i < out.length; i++) out[i] = (byte) (i * 31 + 7);
        return out;
    }

    private static byte[] dnsQuery() {
        byte[] out = new byte[32];
        out[0] = 0x12; out[1] = 0x34;
        out[2] = 0x01; out[5] = 0x01;
        out[12] = 4; out[13] = 'c'; out[14] = 'l'; out[15] = 'o'; out[16] = 'u';
        out[17] = 4; out[18] = 'd'; out[19] = 'f'; out[20] = 'l'; out[21] = 'a';
        out[22] = 3; out[23] = 'c'; out[24] = 'o'; out[25] = 'm';
        out[29] = 1; out[31] = 1;
        return out;
    }
}
