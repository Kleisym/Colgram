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
 * Is the WireGuard port shut, or does UDP not arrive at all? Asked on the device.
 *
 * <b>Why the distinction was never settled by asking.</b>
 *
 * "2408 is silent" has been this project's constant, and it was read as "the WireGuard port is
 * filtered". Those are different facts and they call for different responses. A shut port is a fact
 * about one service; UDP not arriving is a fact about the path, and no amount of transport work
 * changes it. Measured on the host: sixteen ports on 162.159.192.1, including 53, all silent with a
 * real DNS query, while six resolvers answered in the same second. So on the host the silence is
 * about the path.
 *
 * <b>Why the host is not the answer.</b>
 *
 * The host reaches Cloudflare through a WireGuard tunnel holding 0.0.0.0/1 at metric 0, and the
 * emulator inherits the host's connectivity rather than a phone's own. "Every port silent" measured
 * there may be a fact about that tunnel. The device path has to be asked, or the file has no reading
 * that belongs to a phone.
 *
 * <p>The reply check is the second half and the reason this is not just a port sweep. A port that
 * answers a DNS query can still not be running QUIC: 208.67.222.222 - dns.sse.cisco.com - answers on
 * 443 and returns 12 bytes to a QUIC Initial, byte-identical across six different Initials, with
 * version 0x00808100 and zero-length connection ids. That is a stub, not a server. A reply only
 * counts as evidence of a service when it is a packet that service could only have produced, so this
 * reports the shape rather than a bare "answered".
 *
 * <p>Reported, never asserted: a diagnostic whose subject is a filtered network must not turn that
 * network into a red build.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceUdpPortTest {

    private static final String TAG = "ColgramUdpPort";
    private static final int TIMEOUT_MS = 2000;
    private static final int[] PORTS = {53, 443, 2408, 500, 4500, 8443, 51820};

    /**
     * A host that answers on ports it does not serve, so "answered" can be shown to be weaker than
     * "a service is running here".
     */
    private static final String STUB_HOST = "208.67.222.222";
    private static final int STUB_PORT = 443;

    @Test
    public void portsAreSweptAndRepliesAreCheckedForBeingFromAService() {
        if (udp("1.1.1.1", 53, dnsQuery()) == null) {
            Log.w(TAG, "control: DNS over UDP -> SILENT, so every number below is meaningless");
            return;
        }
        Log.i(TAG, "control: DNS over UDP -> answers");

        String host = "162.159.192.1";
        StringBuilder row = new StringBuilder();
        int answered = 0;
        for (int port : PORTS) {
            byte[] reply = udp(host, port, dnsQuery());
            if (reply != null) {
                answered++;
            }
            row.append(" ").append(port).append(":")
                    .append(reply == null ? "silent" : reply.length + "B");
        }
        Log.i(TAG, host + row);
        Log.i(TAG, host + " answered " + answered + "/" + PORTS.length
                + " ports to a real DNS query, 53 included");

        // The same port on a host that does serve DNS, as the contrast that makes "silent" mean
        // something. Without it, a device with no UDP at all would produce the same row.
        byte[] control = udp("1.1.1.1", 443, dnsQuery());
        Log.i(TAG, "1.1.1.1:443 -> " + (control == null ? "silent" : control.length + "B")
                + "   (a host that certainly serves DNS, same port)");

        // And the shape check: a reply that cannot have come from a QUIC server.
        byte[] stub = udp(STUB_HOST, STUB_PORT, quicInitial());
        String verdict;
        if (stub == null) {
            verdict = "the stub host is silent here, so it is not a usable control on this path";
        } else if (isRealQuic(stub)) {
            verdict = "the stub host answered with a real QUIC packet, so QUIC is reachable here"
                    + " and the port sweep above is not about QUIC being filtered";
        } else {
            verdict = "the stub host answers " + STUB_HOST + ":" + STUB_PORT + " with " + stub.length
                    + "B that no QUIC server could send - version 0x"
                    + String.format("%08x", versionOf(stub)) + ", dcid len " + dcidLen(stub)
                    + ". So a bare ANSWERED here would have meant nothing, and any earlier reading"
                    + " that counted one as a live QUIC port was wrong.";
        }
        Log.i(TAG, "stub check: " + verdict);

        if (answered == 0) {
            Log.i(TAG, "VERDICT: no port on " + host + " answered, 53 included, while DNS answers"
                    + " elsewhere. UDP does not reach that address on this path, so the silence is"
                    + " about the path and not about 2408 being shut. A relay on a host without that"
                    + " filter remains the only route to the WireGuard ingress.");
        } else {
            Log.i(TAG, "VERDICT: " + answered + " of " + PORTS.length + " ports answered, so UDP"
                    + " does reach " + host + " and the silence on 2408 is about that port rather"
                    + " than the path. See the per-port row above.");
        }
    }

    /**
     * Whether a reply could only have come from a QUIC server.
     *
     * A server answers an Initial with an Initial or a Retry, and both carry a real version - 1 for
     * the versions in use, or a version-negotiation list - with a non-zero destination connection
     * id. A fixed 12-byte answer with version 0x00808100 and a zero-length id is a stub.
     */
    private static boolean isRealQuic(byte[] reply) {
        if (reply.length < 7) {
            return false;
        }
        if ((reply[0] & 0x80) == 0) {
            return false;
        }
        int version = versionOf(reply);
        if (version != 0 && version != 1) {
            return false;
        }
        return dcidLen(reply) != 0;
    }

    private static int versionOf(byte[] reply) {
        if (reply.length < 5) {
            return -1;
        }
        return ((reply[1] & 0xff) << 24) | ((reply[2] & 0xff) << 16)
                | ((reply[3] & 0xff) << 8) | (reply[4] & 0xff);
    }

    private static int dcidLen(byte[] reply) {
        return reply.length > 5 ? (reply[5] & 0xff) : -1;
    }

    private static byte[] quicInitial() {
        byte[] out = new byte[1200];
        out[0] = (byte) 0xc3;
        out[4] = 0x01;
        for (int i = 5; i < 21; i++) {
            out[i] = (byte) (i * 29 + 5);
        }
        out[21] = 0x00;
        out[22] = 0x00; out[23] = 0x01;
        out[24] = 0x06;
        out[32] = 0x03; out[33] = (byte) 0x84;
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

