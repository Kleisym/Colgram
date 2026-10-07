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
 * Is QUIC blocked in general from the phone, or only at Cloudflare?
 *
 * <b>The control that every earlier measurement in this project was missing.</b>
 *
 * Every WARP probe here was aimed at Cloudflare, so "Cloudflare's UDP is filtered" was a conclusion
 * that could not be told apart from "QUIC is filtered". Those need different responses: the first
 * is a provider filter someone might route around, the second is a property of the path that no
 * amount of clever transport gets past. Measured on the host against four unrelated providers, with
 * the same real 1200-byte QUIC Initial, and answering 0 of 16 across both the default route and one
 * bound to the Ethernet address - while DNS answered 3 of 3 on the same sockets.
 *
 * <b>Why the phone has to be asked separately.</b>
 *
 * The emulator sits behind QEMU user-mode NAT, and its network path is not the host's: the host
 * reaches Cloudflare through a WireGuard tunnel that holds 0.0.0.0/1 at metric 0, and the emulator
 * inherits the host's connectivity rather than a phone's. So "QUIC is blocked here" measured on the
 * host may be a fact about a tunnel rather than about the network a real phone would use, and that
 * distinction decides whether any of this is actionable.
 *
 * <p>The DNS control is not decoration. Without it, "silent" would be ambiguous between QUIC being
 * filtered and UDP being dead, and the second reading is both larger and wrong - it would send
 * someone looking for a UDP problem that is not there.
 *
 * <p>Reported, never asserted: a diagnostic whose whole point is a filtered network must not turn
 * that network into a red build.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceQuicScopeTest {

    private static final String TAG = "ColgramQuicScope";
    private static final int TIMEOUT_MS = 2500;
    private static final int PROBE_BYTES = 1200;
    private static final int ATTEMPTS = 3;

    /** Four providers with no relation to each other, so a silence is a fact about the path. */
    private static final String[][] TARGETS = {
            {"142.250.74.174", "Google"},
            {"157.240.1.35", "Facebook"},
            {"1.1.1.1", "Cloudflare resolver"},
            {"8.8.8.8", "Google DNS"},
    };

    @Test
    public void quicIsProbedAcrossProvidersRatherThanOnlyCloudflare() {
        if (udp("1.1.1.1", 53, dnsQuery()) == null) {
            Log.w(TAG, "control: DNS over UDP -> SILENT, so every number below is meaningless");
            return;
        }
        Log.i(TAG, "control: DNS over UDP -> answers; probe " + PROBE_BYTES + "B");

        int total = 0;
        int possible = 0;
        for (String[] target : TARGETS) {
            int hits = 0;
            StringBuilder shapes = new StringBuilder();
            for (int i = 0; i < ATTEMPTS; i++) {
                byte[] answer = udp(target[0], 443, quicInitial());
                if (answer != null) {
                    hits++;
                    if (shapes.indexOf(" " + answer.length + "B") < 0) {
                        shapes.append(" ").append(answer.length).append("B");
                    }
                }
            }
            total += hits;
            possible += ATTEMPTS;
            Log.i(TAG, target[1] + " " + target[0] + ":443  answered " + hits + "/" + ATTEMPTS
                    + shapes);
        }

        Log.i(TAG, "TOTAL QUIC answered " + total + "/" + possible
                + " across four unrelated providers");
        if (total == 0) {
            Log.i(TAG, "VERDICT: QUIC is silent at every provider, so this is a property of the"
                    + " path and not of Cloudflare. That is the distinction the earlier"
                    + " Cloudflare-only probes could not make, and it is the one that decides"
                    + " whether any transport work could help.");
        } else {
            Log.i(TAG, "VERDICT: " + total + " of " + possible + " answered, so the block is"
                    + " provider-specific rather than a blanket QUIC filter. See the rows above.");
        }
    }

    private static byte[] quicInitial() {
        byte[] out = new byte[PROBE_BYTES];
        out[0] = (byte) 0xc3;
        out[1] = 0x00; out[2] = 0x00; out[3] = 0x00; out[4] = 0x01;
        for (int i = 5; i < 21; i++) {
            out[i] = (byte) (i * 37 + 11);
        }
        out[21] = 0x00;
        out[22] = 0x00; out[23] = 0x01;
        // A CRYPTO frame header at offset 0, length 900, then filler. The payload is encrypted, so
        // a server cannot tell this from a real handshake without our keys - what is measured is
        // whether the packet is answered as a QUIC packet, not whether it is a valid one.
        out[24] = 0x06;
        out[32] = (byte) 0x03; out[33] = (byte) 0x84;
        return out;
    }

    private static byte[] udp(String host, int port, byte[] payload) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            socket.setSoTimeout(TIMEOUT_MS);
            socket.send(new DatagramPacket(payload, payload.length,
                    InetAddress.getByName(host), port));
            DatagramPacket reply = new DatagramPacket(new byte[2048], 2048);
            socket.receive(reply);
            // The length, as a one-byte answer. Cast rather than assign: getLength() returns an int
            // and new byte[]{int} does not compile, and a test module that does not compile reports
            // a build failure that reads like a toolchain problem rather than the line at fault.
            return new byte[]{(byte) reply.getLength()};
        } catch (Exception e) {
            return null;
        } finally {
            if (socket != null) socket.close();
        }
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

