package org.colgram.core;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Random;

/**
 * Would a real QUIC handshake get past 443 on the PHONE - and could WARP ride it?
 *
 * The host measured 0 valid Initials answered across all four Cloudflare ingresses, while random
 * bytes drew 31-byte stateless resets. If the phone behaves the same, then QUIC/HTTP-3 is not a
 * transport here, MASQUE is not a transport here, and the only remaining route to WARP is the relay
 * - which is built, and which needs a far side whose UDP egress is not filtered like this one.
 *
 * If instead the phone answers an Initial where the host does not, the emulator's QEMU NAT is
 * distorting host measurements and there is a tunnel worth building after all. That asymmetry is
 * the entire reason this runs on the device and not only on the host.
 *
 * Reported, never asserted, for the same reason as the shape test: a filtered network must not turn
 * a diagnostic into a red build.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceQuicInitialTest {

    private static final String TAG = "ColgramQuicInit";
    private static final int TIMEOUT_MS = 2500;
    private static final int PROBE_BYTES = 1200;
    private static final int ATTEMPTS = 10;
    private static final int GARBAGE_ATTEMPTS = 4;
    private static final int PORT = 443;
    private static final String[] HOSTS = {"162.159.192.1", "162.159.193.1", "188.114.96.1", "188.114.97.1"};

    @Test
    public void theDeviceIsAskedForARealQuicInitial() {
        if (!control()) {
            Log.w(TAG, "control: DNS over UDP -> SILENT, every number below is meaningless");
            return;
        }
        Log.i(TAG, "control: DNS over UDP -> answers; probe " + PROBE_BYTES + "B");
        Random random = new Random();
        int totalReal = 0;
        int totalGarbage = 0;
        for (String host : HOSTS) {
            int real = 0;
            int garbage = 0;
            StringBuilder kinds = new StringBuilder();
            for (int i = 0; i < ATTEMPTS; i++) {
                byte[] answer = udp(host, PORT, quicInitial(random));
                if (answer != null) {
                    real++;
                    remember(kinds, classify(answer));
                }
            }
            for (int i = 0; i < GARBAGE_ATTEMPTS; i++) {
                byte[] blob = new byte[PROBE_BYTES];
                random.nextBytes(blob);
                byte[] answer = udp(host, PORT, blob);
                if (answer != null) {
                    garbage++;
                    remember(kinds, classify(answer));
                }
            }
            totalReal += real;
            totalGarbage += garbage;
            Log.i(TAG, host + ":" + PORT + " Initial=" + real + "/" + ATTEMPTS
                    + " garbage=" + garbage + "/" + GARBAGE_ATTEMPTS + kinds);
        }
        Log.i(TAG, "TOTAL Initial=" + totalReal + "/" + (ATTEMPTS * HOSTS.length)
                + " garbage=" + totalGarbage + "/" + (GARBAGE_ATTEMPTS * HOSTS.length));
        Log.i(TAG, totalReal > 0
                ? "VERDICT: a valid QUIC Initial is answered - QUIC is alive on the phone, and"
                    + " HTTP/3/MASQUE is a real candidate tunnel"
                : "VERDICT: no valid Initial answered - QUIC is filtered on the phone too, so every"
                    + " QUIC-based transport is closed here and the relay is the only route");
    }

    private static void remember(StringBuilder kinds, String kind) {
        if (kinds.indexOf(kind) < 0) kinds.append("  [").append(kind).append("]");
    }

    private static String classify(byte[] answer) {
        int first = answer[0] & 0xff;
        if ((first & 0x80) == 0) return "not-quic-0x" + hex(first);
        if ((first & 0x40) == 0) return "long-0x" + hex(first) + "/" + answer.length + "B";
        if (first == 0xe6) return "stateless-reset-0x" + hex(first) + "/" + answer.length + "B";
        return "short-header-0x" + hex(first) + "/" + answer.length + "B";
    }

    private static String hex(int value) {
        String s = Integer.toHexString(value);
        return s.length() == 1 ? "0" + s : s;
    }

    private static byte[] quicInitial(Random random) {
        byte[] out = new byte[PROBE_BYTES];
        out[0] = (byte) 0xc3;
        out[1] = 0x00; out[2] = 0x00; out[3] = 0x00; out[4] = 0x01;
        int at = 5;
        random.nextBytes(out);
        // Fixed DCID/SCID rather than random: this packet is encrypted anyway, so a varying
        // connection id would only hide the fact that the probe is identical every time.
        for (int i = 0; i < 8; i++) out[at + i] = (byte) (0xA0 + i);
        at += 8;
        for (int i = 0; i < 8; i++) out[at + i] = (byte) (0xB0 + i);
        at += 8;
        out[at++] = 0x00;
        out[at++] = 0x00; out[at++] = 0x01;
        return out;
    }

    private static boolean control() {
        return udp("1.1.1.1", 53, dnsQuery()) != null;
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
            byte[] copy = new byte[reply.getLength()];
            System.arraycopy(reply.getData(), reply.getOffset(), copy, 0, reply.getLength());
            return copy;
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

