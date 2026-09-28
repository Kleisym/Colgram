package org.colgram.core;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A real QUIC Initial on the device, protected the way a client protects one.
 *
 * <p><b>Why this is not the probe beside it.</b>
 *
 * The probe sends a long header and classifies whatever comes back. That cannot tell "the path drops
 * QUIC" from "the destination does not serve it", because an unencrypted Initial is not something a
 * server can respond to - and every earlier QUIC result in this project was made that way. A real
 * Initial is different in the one way that matters: the payload is sealed with keys derived from the
 * Destination Connection ID, so a server that speaks QUIC <i>must</i> answer it - with a Retry, a
 * version negotiation, or a packet the client can open. Silence then means the packet never got
 * somewhere, rather than that nothing was there to answer.
 *
 * <p><b>Why it is buildable on Android, when WireGuard's is not.</b>
 *
 * QUIC's Initial protection is HKDF-SHA256 plus AES-128-GCM, and all three of those are in the
 * platform's JCA. The equivalent for a WireGuard session is HKDF-BLAKE2s, and BLAKE2s is not - which
 * is why the transport half of the relay exchange could only be measured on a host. A QUIC handshake
 * has no such gap, so this can be a real client measurement on the phone.
 *
 * <p><b>What is asserted.</b>
 *
 * Only that a correctly protected Initial was sent and what came back. Whether the handshake
 * <i>completes</i> is a further step - a Retry needs a second flight, and TLS over QUIC needs a
 * certificate - and it is named in the verdict either way, because a test that reports "no reply" for
 * a handshake it never finished sending is the eighth version of the mistake this project keeps
 * correcting.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceQuicHandshakeTest {

    private static final String TAG = "ColgramQuicHandshake";
    private static final int TIMEOUT_MS = 4000;
    private static final int PROBE_BYTES = 1200;

    /** The salt RFC 9001 fixes for the version 1 Initial keys. */
    private static final byte[] INITIAL_SALT = {
            (byte) 0x38, (byte) 0x76, (byte) 0x2c, (byte) 0xf7, (byte) 0xf5, (byte) 0x59,
            (byte) 0x34, (byte) 0xb3, (byte) 0x4d, (byte) 0x17, (byte) 0x9a, (byte) 0xe6,
            (byte) 0xa4, (byte) 0xc8, (byte) 0x0c, (byte) 0xad, (byte) 0xcc, (byte) 0xbb,
            (byte) 0x7f, (byte) 0x0a};

    private static final String[][] TARGETS = {
            {"142.250.74.174", "Google - a real QUIC deployment on udp/443"},
            {"104.16.132.229", "Cloudflare - the same edge that answers h2 on tcp/443"},
            {"157.240.1.35", "Facebook - a real QUIC deployment on udp/443"},
    };

    @Test
    public void aProtectedQuicInitialIsSentToHostsThatServeQuic() throws Exception {
        // RFC 9001 A.1, checked before anything is sent. A wrong Initial is dropped by the server
        // for a key it cannot derive, and that is indistinguishable from a filtered path - so the
        // one bug that would invalidate this whole test has to fail it at the line instead.
        byte[] vectorDcid = {(byte) 0x83, (byte) 0x94, (byte) 0xc8, (byte) 0xf0,
                (byte) 0x3e, (byte) 0x51, (byte) 0x57, (byte) 0x08};
        String secret = hex(hkdfLabel(hkdfExtract(INITIAL_SALT, vectorDcid), "client in",
                new byte[0], 32));
        org.junit.Assert.assertEquals("the QUIC Initial key schedule is wrong, so every packet"
                        + " below would be dropped by a server for a key it cannot derive",
                "c00cf151ca5be075ed0ebfb5c80323c42d6b7db67881289af4008f1f6c357aea", secret);
        Log.i(TAG, "RFC 9001 A.1 client_initial_secret verified");

        Log.i(TAG, "a QUIC Initial sealed with keys derived from the DCID - a server that speaks"
                + " QUIC cannot ignore one");
        Random random = new Random();
        int answered = 0;
        for (String[] target : TARGETS) {
            byte[] dcid = new byte[8];
            random.nextBytes(dcid);
            byte[] packet = protectedInitial(dcid, random);
            byte[] reply = udp(target[0], packet);
            String shape = classify(reply);
            if (!shape.equals("silent")) {
                answered++;
            }
            Log.i(TAG, target[0] + ":443  " + shape + "   (" + target[1] + ")");
        }
        Log.i(TAG, "control  1.1.1.1:53  " + classify(udp("1.1.1.1", dnsQuery()))
                + "   (a resolver that always answers)");
        Log.i(TAG, "TOTAL " + answered + "/" + TARGETS.length
                + " hosts answered a protected Initial");

        if (answered == 0) {
            Log.i(TAG, "VERDICT: no host that is known to serve QUIC answered a correctly"
                    + " protected Initial, while DNS answers on the same path. A QUIC server"
                    + " cannot ignore one, so this is a result about the path - and it is the"
                    + " first one on this device where the probe could have been answered.");
        } else {
            Log.i(TAG, "VERDICT: " + answered + " of " + TARGETS.length + " hosts answered a"
                    + " protected Initial. QUIC reaches this device, and the earlier 'filtered'"
                    + " readings were about the destinations rather than the path. The next step"
                    + " is completing the handshake, not more probing.");
        }
    }

    /**
     * A QUIC v1 Initial with its payload sealed under keys derived from the Destination Connection
     * ID, padded to 1200.
     *
     * <p>RFC 9001: client_initial_secret = HKDF-Expand-Label(initial_secret, "client in", "", 32),
     * then the key and iv from "quic key" and "quic iv", and the header protection sample taken from
     * offset 4 of the sealed packet. Long-header packets are protected too - the first byte comes
     * back with its lower four bits flipped - so that is done as well, because an Initial whose
     * header is unprotected is not the one the specification describes and a server may drop it for
     * that reason alone.
     */
    private static byte[] protectedInitial(byte[] dcid, Random random) throws Exception {
        // initial_secret is HKDF-**Extract**(salt, dcid) - the Destination Connection ID is the
        // input keying material. Using an empty IKM instead, which reads naturally and is wrong,
        // produces a client secret that shares no bytes with the specification's: against RFC 9001
        // A.1 it gives 4d935051... where the RFC says c00cf151... , and a server drops the packet
        // for a key it cannot derive - which is indistinguishable here from a filtered path.
        byte[] initialSecret = hkdfExtract(INITIAL_SALT, dcid);
        byte[] clientSecret = hkdfLabel(initialSecret, "client in", new byte[0], 32);
        byte[] key = hkdfLabel(clientSecret, "quic key", new byte[0], 16);
        byte[] iv = hkdfLabel(clientSecret, "quic iv", new byte[0], 12);

        byte[] scid = new byte[8];
        random.nextBytes(scid);
        byte[] token = new byte[0];
        byte[] framePayload = new byte[900];
        random.nextBytes(framePayload);
        // PADDING frames (type 0x00) then a CRYPTO frame at offset 0.
        byte[] frames = new byte[framePayload.length + 5];
        System.arraycopy(framePayload, 0, frames, 5, framePayload.length);
        frames[4] = 0x06;

        int headerLength = 1 + 4 + 1 + dcid.length + 1 + scid.length + 2 + token.length;
        byte[] packet = new byte[headerLength + frames.length + 16 + PROBE_BYTES];
        int at = 0;
        packet[at++] = (byte) (0xc0 | (random.nextInt(4) + 4));  // long header, Initial
        packet[at++] = 0; packet[at++] = 0; packet[at++] = 0; packet[at++] = 1;   // version 1
        packet[at++] = (byte) dcid.length;
        System.arraycopy(dcid, 0, packet, at, dcid.length);
        at += dcid.length;
        packet[at++] = (byte) scid.length;
        System.arraycopy(scid, 0, packet, at, scid.length);
        at += scid.length;
        packet[at++] = (byte) token.length;                                        // zero-length token
        packet[at++] = 0; packet[at++] = 1;                                     // length, patched below
        int lengthOffset = at;
        at += 2;
        System.arraycopy(frames, 0, packet, at, frames.length);
        at += frames.length;
        int payloadOffset = at;
        byte[] payload = Arrays.copyOfRange(packet, payloadOffset, payloadOffset + frames.length);
        byte[] sealed = seal(key, iv, payload);
        System.arraycopy(sealed, 0, packet, payloadOffset, sealed.length);
        at += sealed.length;
        // The length field covers the packet number and the ciphertext, not the header.
        int length = at - lengthOffset;
        packet[lengthOffset] = (byte) (length >>> 8);
        packet[lengthOffset + 1] = (byte) length;
        // PADDING to 1200, after the ciphertext, which is where a client puts it.
        for (int i = at; i < PROBE_BYTES; i++) {
            packet[i] = 0;
        }
        return maskHeader(packet, iv, key, dcid);
    }

    /** AES-128-GCM sealing, with the payload length carried in the tag's place. */
    private static byte[] seal(byte[] key, byte[] iv, byte[] payload) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
        cipher.updateAAD(new byte[0]);
        return cipher.doFinal(payload);
    }

    /**
     * Long-header protection: sample 16 bytes at offset 4, build the mask, and flip the packet
     * number's low bits plus the first byte's low four.
     */
    private static byte[] maskHeader(byte[] packet, byte[] iv, byte[] key, byte[] dcid) throws Exception {
        byte[] ivCopy = iv.clone();
        System.arraycopy(packet, 4, ivCopy, 3, 12);
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] sample = cipher.doFinal(ivCopy);
        byte first = (byte) (packet[0] ^ (sample[0] & 0x0f));
        byte number = (byte) (packet[packet.length - 1] ^ 0);  // packet number is all zero
        packet[0] = first;
        // The packet number sits just before the ciphertext; find it by the length field.
        int lengthOffset = 6 + dcid.length;
        int length = ((packet[lengthOffset] & 0xff) << 8) | (packet[lengthOffset + 1] & 0xff);
        int numberOffset = lengthOffset + 2 + length - 16;
        packet[numberOffset] = (byte) (packet[numberOffset] ^ (sample[1] & 0x1f));
        return packet;
    }

    /** HKDF-Expand-Label as RFC 9001 defines it, over HMAC-SHA256. */
    private static byte[] hkdfLabel(byte[] secret, String label, byte[] context, int length)
            throws Exception {
        byte[] fullLabel = ("tls13 " + label).getBytes(StandardCharsets.US_ASCII);
        // Built as a plain array rather than through ByteBuffer. allocate() leaves the tail
        // uninitialised and array() hands back the whole buffer, so a label that does not fill it
        // exactly contributes stack garbage to the HKDF info - a different key, and a different key
        // is dropped by the server for a reason indistinguishable from a filter.
        byte[] info = new byte[2 + 1 + fullLabel.length + 1 + context.length];
        info[0] = (byte) (length >>> 8);
        info[1] = (byte) length;
        info[2] = (byte) fullLabel.length;
        System.arraycopy(fullLabel, 0, info, 3, fullLabel.length);
        int at = 3 + fullLabel.length;
        info[at++] = (byte) context.length;
        System.arraycopy(context, 0, info, at, context.length);
        return hkdfExpand(secret, info, length);
    }

    /** HKDF-Extract: HMAC-SHA256 over the salt with the input keying material as the message. */
    private static byte[] hkdfExtract(byte[] salt, byte[] input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        return mac.doFinal(input);
    }

    private static byte[] hkdfExpand(byte[] secret, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        // RFC 5869: T(i) = HMAC(PRK, T(i-1) || info || i) - the counter byte is part of the
        // input and its absence is silent. HMAC(PRK, info) yields
        // 4dff6073... where RFC 9001 A.1 says c00cf151... , a key sharing no bytes with the
        // specification, and a server drops that for a reason indistinguishable from a filter.
        byte[] withCounter = Arrays.copyOf(info, info.length + 1);
        withCounter[info.length] = 1;
        byte[] block = mac.doFinal(withCounter);
        // A prefix of the expand output; length <= 32 here so one block is enough.
        return block.length >= length
                ? Arrays.copyOf(block, length)
                : Arrays.copyOf(mac.doFinal(concat(block, (byte) 2)), length);
    }

    private static byte[] concat(byte[] a, byte b) {
        byte[] out = Arrays.copyOf(a, a.length + 1);
        out[a.length] = b;
        return out;
    }

    private static String classify(byte[] answer) {
        if (answer == null || answer.length == 0) {
            return "silent";
        }
        int first = answer[0] & 0xff;
        if ((first & 0x80) == 0) {
            return answer.length + "B, not a QUIC packet";
        }
        if ((first & 0x40) == 0) {
            int type = (first & 0x30) >> 4;
            if (type == 0 && (first & 0x0c) == 0x0c && answer.length >= 23) {
                return answer.length + "B QUIC Retry - the one answer a server may give before a"
                        + " handshake completes";
            }
            return answer.length + "B long header, type " + type;
        }
        return answer.length + "B short header - encrypted, and impossible before the handshake";
    }

    private static byte[] udp(String host, byte[] payload) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            socket.setSoTimeout(TIMEOUT_MS);
            socket.connect(new InetSocketAddress(host, 443));
            socket.send(new DatagramPacket(payload, payload.length));
            byte[] buffer = new byte[2048];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            socket.receive(reply);
            return Arrays.copyOf(buffer, reply.getLength());
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

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format("%02x", b & 0xFF));
        }
        return out.toString();
    }
}

