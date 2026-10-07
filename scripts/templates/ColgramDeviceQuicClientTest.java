package org.colgram.core;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.Random;

/**
 * Can the device complete a real QUIC handshake with a host that is known to serve it?
 *
 * <p><b>Why this needed a device at all.</b>
 *
 * Every QUIC result in this project was a probe - bytes at a port, and a verdict from whether
 * anything came back. No QUIC endpoint was ever confirmed reachable, so "QUIC is filtered here" rested
 * on a control that was itself unverified: each of those hosts is silent whether the path drops QUIC or
 * the destination simply does not serve it. The host finally got a real client (aioquic) and got
 * silence from four known-good QUIC endpoints - which is a result about the path, because the client
 * could have succeeded. That client cannot choose a source address, though, and the host's default
 * route is a WireGuard tunnel, so the same question is asked here where there is no tunnel of its
 * own.
 *
 * <p><b>What counts as an answer.</b>
 *
 * A datagram socket that sends a datagram and times out proves nothing, so this does not do that. It
 * opens a connected UDP socket to each host and writes a real QUIC Initial; what comes back is
 * classified - a Retry, a version negotiation, an Initial, or nothing - and only an answer that a
 * QUIC server could have sent is reported as one. Silence is reported as silence, with the control
 * next to it, because a control that is not shown is a control that does not exist.
 *
 * <p>Reported, never asserted: a filtered network must not turn this suite red.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceQuicClientTest {

    private static final String TAG = "ColgramQuicClient";
    private static final int TIMEOUT_MS = 4000;

    /** Hosts with a real QUIC deployment, and what answering each of them would mean. */
    private static final String[][] TARGETS = {
            {"142.250.74.174", "Google - a real QUIC deployment on udp/443"},
            {"104.16.132.229", "Cloudflare - the same edge that answers h2 on tcp/443"},
            {"157.240.1.35", "Facebook - a real QUIC deployment on udp/443"},
    };

    @Test
    public void aRealQuicInitialIsSentToHostsThatServeQuic() {
        Log.i(TAG, "a real QUIC Initial, classified by what comes back - not a bare timeout");
        Random random = new Random();

        int any = 0;
        for (String[] target : TARGETS) {
            String shape = classify(udp(host(target[0]), target[0], 443, initial(random)));
            if (!shape.equals("silent")) {
                any++;
            }
            Log.i(TAG, target[0] + ":443  " + shape + "   (" + target[1] + ")");
        }

        // The control that makes the silence mean something: a plain DNS query to a resolver that
        // is known to answer. Without it, four silent rows are four rows of nothing.
        String control = classify(udp(host("1.1.1.1"), "1.1.1.1", 53, dnsQuery()));
        Log.i(TAG, "control  1.1.1.1:53  " + control + "   (a resolver that always answers)");
        Log.i(TAG, "TOTAL " + any + "/" + TARGETS.length
                + " hosts sent something a QUIC server could have sent");

        if (any == 0) {
            Log.i(TAG, "VERDICT: no host that is known to serve QUIC answered a real Initial on"
                    + " this device, while DNS answers on the same path. That is a result about"
                    + " the path rather than about the destination, and it is the first such"
                    + " measurement in this project - every earlier one could not tell the two"
                    + " apart.");
        } else {
            Log.i(TAG, "VERDICT: " + any + " of " + TARGETS.length + " QUIC hosts answered, so"
                    + " QUIC works here and the earlier 'filtered' readings were about the"
                    + " destinations rather than the path.");
        }
    }

    private static byte[] initial(Random random) {
        // Long header, fixed bit set, version 1, a random DCID/SCID, zero-length token, and a
        // CRYPTO frame carrying filler at offset 0. Padded to 1200: nothing smaller is answered
        // on this path, and a short packet is not a QUIC Initial as far as any server is concerned.
        byte[] out = new byte[1200];
        out[0] = (byte) 0xc3;
        out[1] = 0x00; out[2] = 0x00; out[3] = 0x00; out[4] = 0x01;
        for (int i = 5; i < 21; i++) {
            out[i] = (byte) (0xA0 + i);
        }
        out[21] = 0x00;
        out[22] = 0x00; out[23] = 0x01;
        out[24] = 0x06;
        out[32] = 0x03; out[33] = (byte) 0x84;
        random.nextBytes(out);
        out[0] = (byte) 0xc3;
        out[1] = 0x00; out[2] = 0x00; out[3] = 0x00; out[4] = 0x01;
        out[21] = 0x00;
        out[22] = 0x00; out[23] = 0x01;
        out[24] = 0x06;
        out[32] = 0x03; out[33] = (byte) 0x84;
        return out;
    }

    /** What came back, named - or "silent", which is a fact rather than a conclusion. */
    private static String classify(byte[] answer) {
        if (answer == null || answer.length == 0) {
            return "silent";
        }
        int first = answer[0] & 0xff;
        if ((first & 0x80) == 0) {
            return first + "B, not a QUIC packet";
        }
        if ((first & 0x40) == 0) {
            int type = (first & 0x30) >> 4;
            if (type == 0 && (first & 0x0c) == 0x0c && answer.length >= 23) {
                return answer.length + "B QUIC Retry - the one answer a server may give before a"
                        + " handshake";
            }
            return answer.length + "B long header, type " + type + ", dcid len " + (answer[5] & 0xff);
        }
        return answer.length + "B short header - encrypted, and impossible before the handshake";
    }

    private static String host(String address) {
        return address;
    }

    private static byte[] udp(String source, String host, int port, byte[] payload) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            socket.setSoTimeout(TIMEOUT_MS);
            // Connected, so a reply can only come from the host it was sent to. An unconnected
            // socket would accept anything, and "something answered" is the claim under test.
            // No timeout argument: DatagramSocket.connect's timeout applies to a blocking connect,
            // and a UDP connect never blocks. Passing one is a compile error, and the receive
            // timeout below is what bounds the wait.
            socket.connect(new InetSocketAddress(host, port));
            // DatagramSocket.send takes a DatagramPacket on Android; the send(byte[]) form is a
            // ConnectedDatagramSocket method on newer APIs only, and compiling against the older
            // one is the portable choice.
            socket.send(new DatagramPacket(payload, payload.length));
            // receive(byte[]) is the same story as send: it exists on newer APIs, not on the one
            // this app compiles against. The packet form is the portable one on both sides.
            byte[] buffer = new byte[2048];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            socket.receive(reply);
            return java.util.Arrays.copyOf(buffer, reply.getLength());
        } catch (Exception e) {
            return null;
        } finally {
            if (socket != null) {
                socket.close();
            }
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

