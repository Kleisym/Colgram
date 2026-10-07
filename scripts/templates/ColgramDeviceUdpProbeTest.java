package org.colgram.core;

import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Is Cloudflare's WireGuard ingress reachable BY UDP from the phone?
 *
 * The host and the emulator do not share a path - the phone sits behind the QEMU user-mode NAT, and
 * its ICMP to the same address answers in single-digit milliseconds while the host's UDP to that
 * address times out. A filter applied to one uplink need not be applied to the other, so "the host
 * cannot reach 2408" is not by itself a statement about the device this actually runs on.
 *
 * The probe is 1200 bytes, for the reason established on the host: anything smaller is dropped on
 * this path regardless of destination, so a small probe would report silent for a perfectly open
 * port. A control sits beside it, because a run where nothing answers anywhere means nothing.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceUdpProbeTest {

    private static final String TAG = "ColgramDeviceUdp";
    private static final int TIMEOUT_MS = 3000;
    private static final int PROBE_BYTES = 1200;

    @Test
    public void wireGuardIngressesAreProbedFromThePhoneItself() throws Exception {
        assertTrue("UDP egress is broken on this device, so silence below would mean nothing",
                controlAnswers());

        String[] hosts = {"162.159.192.1", "162.159.193.1", "188.114.96.1", "188.114.97.1"};
        int[] ports = {2408, 500, 1701, 4500};
        int answered = 0;
        int total = 0;
        for (String host : hosts) {
            StringBuilder row = new StringBuilder();
            for (int port : ports) {
                total++;
                if (answered(host, port)) {
                    answered++;
                    row.append(" ").append(port).append(":YES");
                } else {
                    row.append(" ").append(port).append(":no");
                }
            }
            Log.i(TAG, "from the device " + host + row);
        }
        Log.i(TAG, "MEASURED-WARP-UDP " + answered + " of " + total + " answered from the device");
        // Reported, not asserted. The honest answer on a filtered network is zero, and a test that
        // failed here could only ever pass on an unfiltered one.
        assertTrue("every probe was attempted", total > 0);
    }

    private static boolean controlAnswers() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            socket.setSoTimeout(TIMEOUT_MS);
            socket.send(new DatagramPacket(query(), 32, InetAddress.getByName("8.8.8.8"), 53));
            DatagramPacket reply = new DatagramPacket(new byte[512], 512);
            socket.receive(reply);
            return reply.getLength() > 12 && (reply.getData()[2] & 0x80) != 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (socket != null) socket.close();
        }
    }

    private static boolean answered(String host, int port) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            socket.setSoTimeout(TIMEOUT_MS);
            byte[] payload = new byte[PROBE_BYTES];
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31 + 7);
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

    private static byte[] query() {
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
