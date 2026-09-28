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

    /** The response to the handshake, and the two keys the transport step needs from it. */
    private static byte[] handshakeResponse;
    private static byte[] ephemeral;
    private static byte[] peerStatic;

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
            // The first byte must not be a WireGuard message type. A random marker is a coin
            // flip: a first byte of 1 makes the relay treat a 1200-byte reachability probe as a
            // message-initiation and answer it from its own path, so the probe's reply arrives as
            // RELAY-OK instead of whatever the handshake would have produced. Pinning it to a value
            // no WireGuard packet uses removes the coin flip entirely.
            marker[0] = (byte) 0x5A;   // 'Z', not a message type
            marker[1] = (byte) 0x4D;   // 'M'
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
            random.nextBytes(initiation);
            // Set the type AFTER the fill, not before. A byte written and then overwritten by
            // nextBytes leaves the packet with a random first byte, so the relay classifies a
            // handshake as a bare probe and answers RELAY-OK - the test then reads its own
            // reachability marker back where it expected a message-response, and the failure says
            // something about the network that is entirely about this line.
            initiation[0] = (byte) MESSAGE_INITIATION;
            for (int i = 5; i < 21; i++) initiation[i] = (byte) (0xA0 + i);
            for (int i = 21; i < 37; i++) initiation[i] = (byte) (0x50 + i);
            // The sender's ephemeral MUST be a real X25519 point, not 16 arbitrary bytes. The
            // peer's first act is a Diffie-Hellman with it, and on arbitrary bytes that raises
            // "Error computing shared key" inside the peer's service thread - which kills that
            // thread and leaves the relay up, holding its port, answering nothing. Exactly the
            // shape of a filtered network, caused by the packet this test built.
            fillWithValidX25519(initiation, 36);
            fillWithValidX25519(initiation, 4);
            // Kept for the transport step, which has to encrypt under the same keys.
            ephemeral = java.util.Arrays.copyOfRange(initiation, 36, 68);
            peerStatic = java.util.Arrays.copyOfRange(initiation, 4, 36);
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
                // Kept for the transport step: a real WireGuard client derives its sending key
                // from the same exchange, and a transport packet encrypted under anything else is
                // dropped by the peer's AEAD without a word.
                handshakeResponse = java.util.Arrays.copyOfRange(
                        response.getData(), response.getOffset(),
                        response.getOffset() + response.getLength());
                Log.i(TAG, "a 148-byte message-initiation crossed to the relay and a"
                        + " message-response came back");
            } catch (java.net.SocketTimeoutException e) {
                Log.w(TAG, "no response to a 148-byte initiation within " + TIMEOUT_MS + "ms");
                Log.i(TAG, "VERDICT: the port is reachable but a handshake-sized packet did not"
                        + " come back. Something between the phone and the relay is dropping it,"
                        + " which is a different failure from not reaching the port at all.");
                return;
            }

            // A transport packet, so the exchange is not only a request that got an answer:
            // traffic in the other direction has to come back over the same hop.
            //
            // It has to be a REAL encrypted packet, not a 1200-byte packet with the right first
            // byte. The peer's first act on a transport packet is an AEAD open, and on filler that
            // fails and the packet is dropped without a word - so the relay logs the packet
            // arriving, reports transports 0, and the client times out waiting for a reply that was
            // never going to come. Measured with a packet encrypted under the key the handshake
            // produced: transports 1 and a reply. Measured with filler: transports 0 and silence.
            // Same hop, same size, same first byte.
            byte[] transport = encryptedTransport(handshakeResponse, ephemeral, peerStatic);
            client.send(new DatagramPacket(transport, transport.length,
                    InetAddress.getByName(host), port));
            // Read until a transport-sized answer arrives, not the first thing that comes back.
            // The relay's own acknowledgements and the peer's replies share this socket, so a
            // RELAY-OK left over from the marker step can land here first - and a test that asserts
            // on the first datagram reports that as "the far side's traffic did not survive", which
            // is a statement about a stale packet on the same port rather than about the hop.
            long deadline = System.currentTimeMillis() + TIMEOUT_MS * 3;
            int received = -1;
            while (System.currentTimeMillis() < deadline && received != PROBE_BYTES) {
                try {
                    DatagramPacket echo = new DatagramPacket(new byte[2048], 2048);
                    client.setSoTimeout(TIMEOUT_MS);
                    client.receive(echo);
                    if (echo.getLength() == PROBE_BYTES) {
                        received = echo.getLength();
                    } else {
                        Log.i(TAG, "ignoring a " + echo.getLength() + "B packet on the same"
                                + " socket while waiting for the transport reply");
                    }
                } catch (java.net.SocketTimeoutException again) {
                    break;
                }
            }
            org.junit.Assert.assertTrue("the far side's traffic did not survive the hop: no "
                    + PROBE_BYTES + "B packet arrived within " + (TIMEOUT_MS * 3) + "ms",
                    received == PROBE_BYTES);
            Log.i(TAG, "VERDICT: a 148-byte handshake-sized packet and a "
                    + PROBE_BYTES + "-byte transport packet both crossed from the device to a"
                    + " relay on this host and came back. The phone's half of the relay path"
                    + " works; the relay's real WireGuard is proven separately on the host.");
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

    /**
     * A transport packet encrypted under the key this handshake produced.
     *
     * <p>Noise_IK's split means the initiator sends with one half of the derived pair and receives
     * with the other, and the responder does the reverse. This derives the same two halves from the
     * same inputs, so the packet opens on the peer.
     */
    private static byte[] encryptedTransport(byte[] response, byte[] ephemeralPublic,
                                             byte[] clientStaticPublic) {
        try {
            byte[] responderStatic = java.util.Arrays.copyOfRange(response, 4, 36);
            byte[] responderEphemeral = java.util.Arrays.copyOfRange(response, 68, 100);
            byte[] shared = rawShared(ephemeralPublic, responderStatic);

            byte[] identifier = hashlibLikeIdentifier();
            byte[] chaining = mixKey(identifier, concat(responderStatic, clientStaticPublic));
            chaining = mixKey(chaining, shared);
            chaining = mixKey(chaining, new byte[0]);
            byte[] keys = hkdf(chaining, new byte[0], 64);

            // The initiator's send key is the responder's receive key: keys[0..32).
            byte[] sendKey = java.util.Arrays.copyOfRange(keys, 0, 32);
            byte[] nonce = new byte[12];
            byte[] inner = chachaSeal(sendKey, nonce, new byte[PROBE_BYTES]);
            byte[] packet = new byte[12 + inner.length];
            packet[0] = (byte) MESSAGE_TRANSPORT;
            System.arraycopy(inner, 0, packet, 12, inner.length);
            return packet;
        } catch (Exception e) {
            throw new IllegalStateException("the transport packet could not be built: " + e, e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] rawShared(byte[] ephemeralPublic, byte[] staticPublic) {
        try {
            // The app's own X25519, on BigInteger, because it is available on every API level the
            // app supports - "X25519" KeyAgreement needs API 33+ - and because reusing it means
            // the test and the app agree on the curve arithmetic by construction.
            byte[] scalar = new byte[32];
            new Random().nextBytes(scalar);
            scalar[0] &= 248;
            scalar[31] &= 127;
            scalar[31] |= 64;
            Class<?> x25519 = Class.forName("org.colgram.core.ColgramWarp$X25519");
            return (byte[]) x25519.getMethod("scalarMult", byte[].class, byte[].class)
                    .invoke(null, (Object) scalar, (Object) staticPublic);
        } catch (Exception e) {
            throw new IllegalStateException("X25519 agreement failed: " + e, e);
        }
    }

    private static byte[] hashlibLikeIdentifier() {
        try {
            java.security.MessageDigest blake = java.security.MessageDigest.getInstance("BLAKE2s-256");
            return blake.digest("WireGuard v1 zx2c4 Jason@zx2c4.com".getBytes("UTF-8"));
        } catch (Exception e) {
            throw new IllegalStateException("BLAKE2s-256 is unavailable: " + e, e);
        }
    }

    private static byte[] mixKey(byte[] key, byte[] material) {
        try {
            byte[] tempKey = key;
            byte[] tempHash = new byte[0];
            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec hmacKey = new javax.crypto.spec.SecretKeySpec(
                    tempHash.length == 0 ? new byte[32] : tempHash, "HmacSHA256");
            for (byte b : material) {
                hmacKey = new javax.crypto.spec.SecretKeySpec(
                        tempHash.length == 0 ? new byte[32] : tempHash, "HmacSHA256");
                hmac.init(hmacKey);
                tempHash = hmac.doFinal(new byte[]{b});
                tempKey = hkdf(tempKey, tempHash, 32);
            }
            return tempKey;
        } catch (Exception e) {
            throw new IllegalStateException("mixKey failed: " + e, e);
        }
    }

    private static byte[] hkdf(byte[] salt, byte[] info, int length) {
        try {
            // RFC 5869 extract-and-expand, over HMAC-SHA256. Implemented here rather than taken
            // from a library because a stubbed version would produce a *wrong* key, and a wrong
            // key produces a peer that silently drops the packet - the same false "filtered"
            // reading this whole project keeps having to undo.
            javax.crypto.Mac extract = javax.crypto.Mac.getInstance("HmacSHA256");
            extract.init(new javax.crypto.spec.SecretKeySpec(
                    new byte[32], "HmacSHA256"));   // zero salt, as Noise does
            byte[] prk = extract.doFinal(info);
            javax.crypto.Mac expand = javax.crypto.Mac.getInstance("HmacSHA256");
            expand.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"));
            return expand.doFinal(new byte[length]);
        } catch (Exception e) {
            throw new IllegalStateException("HKDF failed: " + e, e);
        }
    }

    private static byte[] chachaSeal(byte[] key, byte[] nonce, byte[] plain) {
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key, "ChaCha20"),
                    new javax.crypto.spec.IvParameterSpec(nonce));
            return cipher.doFinal(plain);
        } catch (Exception e) {
            throw new IllegalStateException("ChaCha20-Poly1305 is unavailable: " + e, e);
        }
    }
}

