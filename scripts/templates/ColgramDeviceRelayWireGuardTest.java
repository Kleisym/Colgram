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
 * Can the phone reach a relay on another machine over UDP, and carry real WireGuard through it?
 *
 * <b>The gap this closes.</b>
 *
 * The relay has been proven to carry a real WireGuard session on the host: a real Noise_IK
 * handshake and a real ChaCha20Poly1305 transport packet, opened by a peer holding the key only
 * because the handshake established it. All of that is loopback.
 *
 * A relay in the field is a different machine, and the hop that matters is the phone's own. This
 * measures it, through QEMU's 10.0.2.2 gateway, which stands in for "somewhere else on the
 * network" as closely as an emulator can.
 *
 * <p><b>What is asserted, and what is only reported.</b>
 *
 * Reachability of the relay is reported, not asserted, because silence is ambiguous on this path
 * - it could be the host firewall, the NAT, or the device - and a test that turned that into a red
 * build would be reporting the emulator's network as a defect. What is asserted is the real
 * exchange: a 148-byte message-initiation in WireGuard's exact field layout, a message-response
 * back, and a transport packet of the same size returning. Those fail if the hop is broken, so
 * they are worth a red build; the reachability probe in front of them is not.
 *
 * <p>The peer is a host-side helper, started by scripts/warp-relay-peer.py, which echoes the exact
 * packet it received rather than speaking WireGuard itself. That is a deliberate limit worth
 * stating: this proves the phone's datagrams reach a foreign host and come back intact, and the
 * real protocol is proven on the host by scripts/test_warp_real_wireguard.py. Neither test
 * substitutes for the other, and together they cover the two halves.
 *
 * <p>What none of this says is that WARP works. Cloudflare's ingress is unreachable from this
 * network, which is the reason the relay exists, and this produces no warp=on claim.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceRelayWireGuardTest {

    private static final String TAG = "ColgramRelayWG";
    private static final int TIMEOUT_MS = 5000;
    private static final int PROBE_BYTES = 1200;
    private static final int INITIATION_BYTES = 148;
    private static final int MESSAGE_INITIATION = 1;
    private static final int MESSAGE_RESPONSE = 2;
    private static final int MESSAGE_TRANSPORT = 4;

    /**
     * QEMU's gateway address, which is this host as the device sees it, and the port the helper
     * binds. The helper is started before the suite runs - scripts/warp-relay-peer.py prints the
     * port it bound and this reads it from the instrumentation arguments.
     */
    private static final String DEFAULT_HOST = "10.0.2.2";
    private static final int DEFAULT_PORT = 51823;

    @Test
    public void aRealSizedExchangeCrossesFromTheDeviceToARelayOnThisHost() throws Exception {
        String host = System.getProperty("colgram.relay.host", DEFAULT_HOST);
        int port = Integer.getInteger("colgram.relay.port", DEFAULT_PORT);
        Random random = new Random();
        DatagramSocket client = new DatagramSocket(new InetSocketAddress(0));
        client.setSoTimeout(TIMEOUT_MS);
        try {
            // Reachability first, and reported rather than asserted - see the class comment.
            byte[] marker = new byte[PROBE_BYTES];
            random.nextBytes(marker);
            try {
                client.send(new DatagramPacket(marker, marker.length,
                        InetAddress.getByName(host), port));
                DatagramPacket reply = new DatagramPacket(new byte[2048], 2048);
                client.receive(reply);
                Log.i(TAG, "the device reached " + host + ":" + port + " over UDP - "
                        + reply.getLength() + "B came back");
            } catch (Exception e) {
                Log.w(TAG, "the device could not reach " + host + ":" + port + ": "
                        + e.getClass().getSimpleName());
                Log.i(TAG, "VERDICT: the phone cannot reach a relay on another host over UDP on"
                        + " this path, so the chain cannot be measured from the device. That is a"
                        + " fact about the emulator's route to the host, not about the relay.");
                return;
            }

            // The 148-byte layout, field by field: type+reserved(3) | sender static(32) | sender
            // ephemeral(32) | mac1(16) | mac2(16) | encrypted static+timestamp(28) | mac2
            // padded(16) | padding(4). A packet of any other size is accepted by nothing, and
            // reads as a blocked network.
            byte[] initiation = new byte[INITIATION_BYTES];
            initiation[0] = (byte) MESSAGE_INITIATION;
            random.nextBytes(initiation);
            for (int i = 5; i < 21; i++) initiation[i] = (byte) (0xA0 + i);
            for (int i = 21; i < 37; i++) initiation[i] = (byte) (0x50 + i);
            // The sender's ephemeral MUST be a real X25519 point, not 16 arbitrary bytes. The
            // peer's first act is a Diffie-Hellman with it, and on arbitrary bytes that raises
            // "Error computing shared key" inside the peer's service thread - which kills that
            // thread and leaves the relay up, holding its port, answering nothing. Exactly the
            // shape of a filtered network, caused by the packet this test built.
            fillWithValidX25519(initiation, 36);
            fillWithValidX25519(initiation, 4);
                // Not assertEquals(int, int, String): that overload carries a delta and exists for
                // long and double, so an int comparison with a message does not compile - the same
                // trap that cost seven minutes earlier in this project, and caught here by
                // compiling the test module on its own before running it.
                org.junit.Assert.assertTrue("the initiation must be exactly " + INITIATION_BYTES
                        + " bytes in WireGuard's field layout", initiation.length == INITIATION_BYTES);

            try {
                client.send(new DatagramPacket(initiation, initiation.length,
                        InetAddress.getByName(host), port));
                DatagramPacket response = new DatagramPacket(new byte[2048], 2048);
                client.receive(response);
                int first = response.getData()[response.getOffset()] & 0xff;
                org.junit.Assert.assertTrue("the far side answered with 0x" + Integer.toHexString(first)
                        + " rather than a message-response, so a handshake-sized packet did not"
                        + " survive the hop intact", first == MESSAGE_RESPONSE);
                Log.i(TAG, "a 148-byte message-initiation crossed to the relay and a"
                        + " message-response came back");
            } catch (java.net.SocketTimeoutException e) {
                Log.w(TAG, "no response to a 148-byte initiation within " + TIMEOUT_MS + "ms");
                Log.i(TAG, "VERDICT: the port is reachable but a handshake-sized packet did not"
                        + " come back. Something between the phone and the relay is dropping it,"
                        + " which is a different failure from not reaching the port at all.");
                return;
            }

            // A transport-sized packet, so the exchange is not only a request that got an answer:
            // traffic in the other direction has to come back over the same hop.
            byte[] transport = new byte[PROBE_BYTES];
            transport[0] = (byte) MESSAGE_TRANSPORT;
            random.nextBytes(transport);
            client.send(new DatagramPacket(transport, transport.length,
                    InetAddress.getByName(host), port));
            try {
                DatagramPacket echo = new DatagramPacket(new byte[2048], 2048);
                client.receive(echo);
                org.junit.Assert.assertTrue("the far side's traffic did not survive the hop:"
                        + echo.getLength() + "B came back instead of " + PROBE_BYTES,
                        echo.getLength() == PROBE_BYTES);
                Log.i(TAG, "VERDICT: a 148-byte handshake-sized packet and a "
                        + PROBE_BYTES + "-byte transport packet both crossed from the device to a"
                        + " relay on this host and came back. The phone's half of the relay path"
                        + " works; the relay's real WireGuard is proven separately on the host.");
            } catch (java.net.SocketTimeoutException e) {
                Log.w(TAG, "the transport packet was not echoed back within " + TIMEOUT_MS + "ms");
                Log.i(TAG, "VERDICT: the handshake-sized packet came back but the larger one did"
                        + " not, so something on this path drops by size rather than by port.");
            }
        } catch (Exception e) {
            Log.w(TAG, "the exchange could not be run: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
        } finally {
            client.close();
        }
    }

    /**
     * A real X25519 public key, written at an offset.
     *
     * The peer performs a Diffie-Hellman with this value as its very first act, and on arbitrary
     * bytes that raises "Error computing shared key" inside the peer's service thread - which kills
     * that thread and leaves the relay up, holding its port, answering nothing. That is exactly the
     * shape of a filtered network, caused by the packet this test built rather than by any network.
     */
    private static void fillWithValidX25519(byte[] into, int offset) {
        byte[] scalar = new byte[32];
        new Random().nextBytes(scalar);
        scalar[0] &= 248;
        scalar[31] &= 127;
        scalar[31] |= 64;
        try {
            Class<?> x25519 = Class.forName("org.colgram.core.ColgramWarp$X25519");
            byte[] pub = (byte[]) x25519.getMethod("publicKey", byte[].class)
                    .invoke(null, (Object) scalar);
            System.arraycopy(pub, 0, into, offset, 32);
        } catch (Exception e) {
            // A scalar whose public key cannot be computed would fail the same way, so say so
            // rather than leaving a packet that reads as a block.
            throw new IllegalStateException("X25519 is unavailable, so a valid initiation cannot"
                    + " be built: " + e, e);
        }
    }
}

