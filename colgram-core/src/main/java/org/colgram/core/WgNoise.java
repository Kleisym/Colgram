package org.colgram.core;

import java.util.Arrays;

/**
 * The Noise_IKpsk2 primitives WireGuard's handshake is built on: BLAKE2s, the HKDF-style KDFs, and
 * the two mixing steps that use them.
 *
 * <p>Every one of these is HMAC-<b>BLAKE2s</b>. That is the part that is easy to get wrong and hard to
 * notice: an implementation that uses HMAC-SHA256 instead produces perfectly well-formed 32-byte keys
 * that no endpoint has ever seen. Nothing raises, nothing is logged, and the handshake simply never
 * completes - which reads as a blocked network. That mistake was made here, was measured as a working
 * tunnel against a stand-in responder written with the same mistake, and only surfaced when the peer
 * was finally checked against upstream wireguard-go.
 *
 * <p>Both the digest and the KDFs are verified against wireguard-go's own vectors (device/kdf_test.go)
 * and against Python's hashlib, so this is a checked implementation rather than a plausible one.
 */
public final class WgNoise {

    /** The hash size and the chaining key size: BLAKE2s-256. */
    public static final int SIZE = 32;
    /** BLAKE2s block size, and therefore the HMAC key block size. */
    private static final int BLOCK = 64;

    public static final String CONSTRUCTION = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s";
    public static final String IDENTIFIER = "WireGuard v1 zx2c4 Jason@zx2c4.com";
    public static final String LABEL_MAC1 = "mac1----";
    public static final String LABEL_COOKIE = "cookie--";

    private static final int[] IV = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
            0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19};

    private static final byte[][] SIGMA = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
            {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
            {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
            {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
            {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
            {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
            {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
            {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
            {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0}};

    private WgNoise() {}

    /** The BLAKE2s digest of {@code input}, {@code outLength} bytes, unkeyed. */
    public static byte[] digest(byte[] input, int outLength) {
        int[] h = new int[8];
        System.arraycopy(IV, 0, h, 0, 8);
        // Parameter block word 0: digest length, key length 0, fanout 1, depth 1. The digest length is
        // the part that bites - a 128-bit and a 256-bit digest of the same input share no prefix at
        // all, and WireGuard needs the 256-bit form for chaining and the 128-bit for mac1.
        h[0] ^= 0x01010000 ^ outLength;

        if (input.length == 0) {
            compress(h, new byte[BLOCK], 0, true);
        } else {
            int full = input.length / BLOCK;
            int tail = input.length - full * BLOCK;
            for (int i = 0; i < full; i++) {
                // When the input is a whole number of blocks the LAST full block carries the
                // last-block flag; appending another all-zero block gives a 64-byte input two blocks
                // and a digest that differs from every other implementation while still returning
                // normally.
                boolean isLast = tail == 0 && i == full - 1;
                compress(h, Arrays.copyOfRange(input, i * BLOCK, i * BLOCK + BLOCK), (i + 1) * BLOCK, isLast);
            }
            if (tail != 0) {
                byte[] last = new byte[BLOCK];
                System.arraycopy(input, full * BLOCK, last, 0, tail);
                compress(h, last, input.length, true);
            }
        }
        byte[] out = new byte[outLength];
        for (int i = 0; i < outLength; i++) {
            out[i] = (byte) (h[i >>> 2] >>> (8 * (i & 3)));
        }
        return out;
    }

    /**
     * Keyed BLAKE2s, which is what mac1 and mac2 are.
     *
     * <p>This is not the same function as hashing the key alongside the data. Supplying a key changes
     * the parameter block, so a caller that concatenates the label and the public key as message bytes
     * gets an unrelated digest - which is what a peer here did, and the device's own log named it
     * exactly: Received packet with invalid mac1.
     */
    public static byte[] digestKeyed(byte[] key, byte[] input, int outLength) {
        int[] h = new int[8];
        System.arraycopy(IV, 0, h, 0, 8);
        if (key.length > SIZE) {
            throw new IllegalArgumentException("BLAKE2s key must be at most 32 bytes");
        }
        h[0] ^= 0x01010000 ^ (key.length << 8) ^ outLength;

        byte[] block = new byte[BLOCK];
        int counter = 0;
        if (key.length > 0) {
            System.arraycopy(key, 0, block, 0, key.length);
            // With a message present, the padded key block is absorbed first as a non-final block and the
            // counter then stands at 64, because the running count includes it. With an EMPTY message
            // there is nothing left to absorb, so the key block is the one and only block and is
            // finalised on its own - but with the offset still at 64, because the reference counts a
            // block it has absorbed whether or not a message follows. Zero here is the value that looks
            // obviously right and is not: it gives a digest differing in every byte, and the only symptom
            // would be a mac1 the far end rejects without saying why.
            if (input.length == 0) {
                compress(h, block, BLOCK, true);
                return truncate(h, outLength);
            }
            compress(h, block, BLOCK, false);
            counter = BLOCK;
        }
        int offset = 0;
        while (input.length - offset > BLOCK) {
            counter += BLOCK;
            compress(h, Arrays.copyOfRange(input, offset, offset + BLOCK), counter, false);
            offset += BLOCK;
        }
        byte[] last = new byte[BLOCK];
        int remaining = input.length - offset;
        if (remaining > 0) {
            System.arraycopy(input, offset, last, 0, remaining);
        }
        compress(h, last, counter + remaining, true);
        byte[] out = new byte[outLength];
        for (int i = 0; i < outLength; i++) {
            out[i] = (byte) (h[i >>> 2] >>> (8 * (i & 3)));
        }
        return out;
    }

    private static void compress(int[] h, byte[] block, int counter, boolean last) {
        int[] v = new int[16];
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(IV, 0, v, 8, 8);
        // The offset is a 64-bit byte counter split across v[12] and v[13], counting BYTES consumed so
        // far, not blocks processed. Each call site passes the running byte count including the block
        // being compressed. A block index instead leaves the empty input correct and corrupts every
        // other length, which is what the vector run showed.
        v[12] ^= counter;
        v[13] ^= 0;
        if (last) {
            v[14] ^= 0xFFFFFFFF;
        }

        int[] m = new int[16];
        for (int i = 0; i < 16; i++) {
            m[i] = (block[i * 4] & 0xff) | ((block[i * 4 + 1] & 0xff) << 8)
                    | ((block[i * 4 + 2] & 0xff) << 16) | ((block[i * 4 + 3] & 0xff) << 24);
        }
        for (int round = 0; round < 10; round++) {
            byte[] s = SIGMA[round];
            // Column step, then the diagonal step, exactly as RFC 7693 lays them out. The mixing
            // function is written once and called: an earlier version inlined all eight mixes per round
            // and got the diagonal triples wrong, which produced a digest differing in every byte
            // while still returning normally.
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
        }
        for (int i = 0; i < 8; i++) {
            h[i] ^= v[i] ^ v[i + 8];
        }
    }

    /** The BLAKE2s mixing function G, on four of the sixteen state words. */
    private static void g(int[] v, int a, int b, int c, int d, int x, int y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Integer.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Integer.rotateRight(v[b] ^ v[c], 12);
        v[a] = v[a] + v[b] + y;
        v[d] = Integer.rotateRight(v[d] ^ v[a], 8);
        v[c] = v[c] + v[d];
        v[b] = Integer.rotateRight(v[b] ^ v[c], 7);
    }

    /** The state words as a digest, little-endian, truncated to the requested length. */
    private static byte[] truncate(int[] h, int outLength) {
        byte[] out = new byte[outLength];
        for (int i = 0; i < outLength; i++) {
            out[i] = (byte) (h[i >>> 2] >>> (8 * (i & 3)));
        }
        return out;
    }

    /** One HMAC-BLAKE2s-256, the primitive every KDF below is built from. */
    public static byte[] hmac(byte[] key, byte[]... messages) {
        byte[] k = key;
        if (k.length > BLOCK) {
            k = digest(k, SIZE);
        }
        byte[] padded = new byte[BLOCK];
        System.arraycopy(k, 0, padded, 0, k.length);
        byte[] inner = new byte[BLOCK];
        byte[] outer = new byte[BLOCK];
        for (int i = 0; i < BLOCK; i++) {
            inner[i] = (byte) (padded[i] ^ 0x36);
            outer[i] = (byte) (padded[i] ^ 0x5c);
        }
        byte[] message = inner;
        for (byte[] part : messages) {
            message = concat(message, part);
        }
        return digest(concat(outer, digest(message, SIZE)), SIZE);
    }

    /** KDF1: one output. This is the whole of mixKey. */
    public static byte[] kdf1(byte[] key, byte[] input) {
        return hmac(hmac(key, input), new byte[]{1});
    }

    /** KDF2: two outputs, splitting a key into an encrypting and a decrypting key. */
    public static byte[][] kdf2(byte[] key, byte[] input) {
        byte[] prk = hmac(key, input);
        byte[] t0 = hmac(prk, new byte[]{1});
        byte[] t1 = hmac(prk, t0, new byte[]{2});
        return new byte[][]{t0, t1};
    }

    /** KDF3: three outputs: a chaining key, a tau for the transcript, and an AEAD key. */
    public static byte[][] kdf3(byte[] key, byte[] input) {
        byte[] prk = hmac(key, input);
        byte[] t0 = hmac(prk, new byte[]{1});
        byte[] t1 = hmac(prk, t0, new byte[]{2});
        byte[] t2 = hmac(prk, t1, new byte[]{3});
        return new byte[][]{t0, t1, t2};
    }

    /** mixHash: the transcript hash, absorbing into the running value. */
    public static byte[] mixHash(byte[] current, byte[] data) {
        return digest(concat(current, data), SIZE);
    }

    /** mixKey: the chaining key update, which is KDF1 and nothing else. */
    public static byte[] mixKey(byte[] current, byte[] data) {
        return kdf1(current, data);
    }

    /** The Noise construction string, as wireguard-go computes it at init time. */
    public static byte[] initialChainKey() {
        return digest(CONSTRUCTION.getBytes(), SIZE);
    }

    /** InitialHash, the transcript value every handshake starts from. */
    public static byte[] initialHash() {
        return mixHash(initialChainKey(), IDENTIFIER.getBytes());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
