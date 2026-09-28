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
 * Are the phone's 31-byte answers to a QUIC Initial a real server, or the path talking?
 *
 * A previous run on this device reported 4 of 40 valid Initials answered on 443, with first bytes
 * 0x9e, 0xca and 0xd5, every answer exactly 31 bytes. The host, on the same network, answered 0 of
 * 40. Two things make the phone result untrustworthy as it stands:
 *
 * <b>A short-header answer is impossible here.</b> Short-header packets are encrypted with keys
 * derived from the completed handshake. A server that has seen one Initial and no client hello has
 * no such keys, so 0xca and 0xd5 cannot be real replies - at best a stateless reset, whose first
 * byte is arbitrary, at worst an echo from something that is not a QUIC server at all.
 *
 * <b>The 0x9e answer deserves a real check.</b> 0x9e is a long header with the fixed bit clear and
 * packet type 10, which is Retry - the one answer a server can legitimately send before the
 * handshake completes. If those bytes are a genuine Retry, QUIC really is alive on the phone and
 * HTTP/3 becomes worth building. If not, the "QUIC is alive" reading is an artefact.
 *
 * <b>The control that decides it is a dead port.</b> The same probe is sent to 2408 and 500, where
 * nothing listens and the network filters traffic outright. An answer from those ports cannot come
 * from a server, so an answer there means the path is producing these bytes and every answer on
 * 443 is equally meaningless.
 *
 * Reported, never asserted: a diagnostic must not turn a filtered network into a red build, and
 * the interesting result here is a set of numbers rather than a pass or fail.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceQuicAnatomyTest {

    private static final String TAG = "ColgramQuicAnatomy";
    private static final int TIMEOUT_MS = 2000;
    private static final int PROBE_BYTES = 1200;
    private static final int ATTEMPTS = 10;
    private static final String HOST = "162.159.192.1";
    private static final int ALIVE_PORT = 443;
    private static final int[] DEAD_PORTS = {2408, 500};

    @Test
    public void theThirtyOneByteAnswersAreExaminedAgainstADeadPort() {
        if (udp("1.1.1.1", 53, dnsQuery()) == null) {
            Log.w(TAG, "control: DNS over UDP -> SILENT, every number below is meaningless");
            return;
        }
        Log.i(TAG, "control: DNS over UDP -> answers; probe " + PROBE_BYTES + "B");
        Random random = new Random();

        int aliveHits = 0;
        StringBuilder kinds = new StringBuilder();
        for (int i = 0; i < ATTEMPTS; i++) {
            byte[] answer = udp(HOST, ALIVE_PORT, quicInitial(random));
            if (answer != null) {
                aliveHits++;
                remember(kinds, anatomy(answer));
            }
        }
        Log.i(TAG, HOST + ":" + ALIVE_PORT + "  ANSWERED " + aliveHits + "/" + ATTEMPTS + kinds);

        int deadHits = 0;
        for (int port : DEAD_PORTS) {
            int portHits = 0;
            for (int i = 0; i < ATTEMPTS; i++) {
                if (udp(HOST, port, quicInitial(random)) != null) {
                    portHits++;
                }
            }
            deadHits += portHits;
            Log.i(TAG, HOST + ":" + port + "  ANSWERED " + portHits + "/" + ATTEMPTS
                    + "   (nothing listens here; an answer here is the path, not a server)");
        }

        Log.i(TAG, "ALIVE " + aliveHits + "/" + ATTEMPTS + "  DEAD " + deadHits
                + "/" + (ATTEMPTS * DEAD_PORTS.length));
        if (deadHits > 0) {
            Log.i(TAG, "VERDICT: a filtered port answered, so the 443 answers are the path."
                    + " 'QUIC is alive on the phone' was an artefact and is withdrawn.");
        } else if (aliveHits == 0) {
            Log.i(TAG, "VERDICT: 443 silent, dead ports silent - silence follows the packet,"
                    + " not the port. QUIC is not a tunnel on this network.");
        } else {
            Log.i(TAG, "VERDICT: dead ports silent, so something on 443 really answered."
                    + " Read the anatomy above: only a Retry packet makes that meaningful.");
        }
    }

    private static void remember(StringBuilder kinds, String kind) {
        if (kinds.indexOf(kind) < 0) kinds.append("  [").append(kind).append("]");
    }

    /**
     * What the bytes can physically be, rather than what the first byte suggests.
     */
    private static String anatomy(byte[] answer) {
        int first = answer[0] & 0xff;
        int length = answer.length;
        if ((first & 0x80) == 0) {
            return "not-quic-0x" + hex(first);
        }
        if ((first & 0x40) == 0) {
            int type = (first & 0x30) >> 4;
            if ((first & 0x0c) == 0x0c && type == 0x02 && length >= 23) {
                return "RETRY-" + length + "B";
            }
            return "long-type" + type + "-" + length + "B";
        }
        return "short-header-" + length + "B(encrypted; impossible pre-handshake)";
    }

    private static String hex(int value) {
        String s = Integer.toHexString(value);
        return s.length() == 1 ? "0" + s : s;
    }

    private static byte[] quicInitial(Random random) {
        byte[] out = new byte[PROBE_BYTES];
        out[0] = (byte) 0xc3;
        out[1] = 0x00; out[2] = 0x00; out[3] = 0x00; out[4] = 0x01;
        random.nextBytes(out);
        // The payload is encrypted, so the connection ids are fixed rather than random: a varying
        // id would only obscure that this is the same packet every attempt, sent ten times.
        for (int i = 0; i < 8; i++) out[5 + i] = (byte) (0xA0 + i);
        for (int i = 0; i < 8; i++) out[13 + i] = (byte) (0xB0 + i);
        out[21] = 0x00;
        out[22] = 0x00; out[23] = 0x01;
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

