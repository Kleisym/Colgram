package org.colgram.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

/**
 * ColgramTlsMimic — rewrites an MTProxy FakeTLS ClientHello so it fingerprints like a browser.
 *
 * Why this exists: TSPU does not merely sniff for MTProto, it fingerprints the FakeTLS
 * ClientHello that tgnet's native stack sends. That hello is one fixed byte pattern compiled
 * into libtmessages for every Telegram user on earth, so a signature for it blocks every
 * FakeTLS proxy at once — the TCP socket opens, the hello leaves, the connection dies before
 * the server ever answers. That is exactly the "TCP есть, связи нет" failure mode.
 *
 * The rewrite is legal because of how FakeTLS is specified: the proxy server validates the
 * handshake with session_id = HMAC(key, client_random) and then reads the tunnelled MTProto
 * stream. Everything else in the hello — cipher suites, extensions, curves, ALPN, GREASE —
 * is decoration the server never checks. So this class parses the hello, keeps client_random,
 * session_id and the SNI hostname byte-for-byte, and rebuilds the rest with a Chrome-shaped
 * fingerprint. The server still accepts the handshake; the DPI now sees a hello that looks
 * like Chrome talking to a CDN.
 *
 * All parsing is defensive: anything that does not parse as a TLS 1.x ClientHello is returned
 * untouched. A failed rewrite costs nothing; a successful one is the difference between a
 * blocked and a working proxy on a signature-based TSPU.
 */
public final class ColgramTlsMimic {

    private ColgramTlsMimic() {}

    private static final int[] GREASE_VALUES = {
            0x0a0a, 0x1a1a, 0x2a2a, 0x3a3a, 0x4a4a, 0x5a5a, 0x6a6a, 0x7a7a,
            0x8a8a, 0x9a9a, 0xaaaa, 0xbaba, 0xcaca, 0xdada, 0xeaea, 0xfafa,
    };

    /** Cipher suites in Chrome's desktop order (TLS 1.3 suites first, then ECDHE, then RSA). */
    private static final int[] CHROME_CIPHERS = {
            0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca9,
            0xcca8, 0xc013, 0xc014, 0x009c, 0x009d, 0x002f, 0x0035, 0x000a,
    };

    /** Firefox's desktop order, used for every other rewrite so the fleet is not uniform. */
    private static final int[] FIREFOX_CIPHERS = {
            0x1301, 0x1303, 0x1302, 0xc02b, 0xc02f, 0xcca9, 0xcca8, 0xc02c,
            0xc030, 0xc00a, 0xc009, 0xc013, 0xc014, 0x0033, 0x0039, 0x002f,
            0x0035, 0x000a,
    };

    /** Plausible SNIs for the rare FakeTLS secret that does not carry its own domain. */
    private static final String[] FALLBACK_SNI = {
            "www.google.com", "www.microsoft.com", "www.cloudflare.com",
            "ajax.googleapis.com", "www.bing.com", "cdn.jsdelivr.net",
    };

    /**
     * Rewrite a ClientHello into a browser-shaped one, keeping random + session_id + SNI.
     * Returns the original bytes when the input is not a ClientHello this class understands.
     */
    public static byte[] rewrite(byte[] data) {
        if (data == null || data.length < 64 || data[0] != 0x16) return data;
        int major = data[1] & 0xff;
        if (major != 0x03) return data;

        try {
            int recordLen = u16(data, 3);
            if (recordLen + 5 > data.length) return data;
            if (data[5] != 0x01) return data; // not a handshake, or not ClientHello
            int hsLen = (data[6] & 0xff) << 16 | u16(data, 7);
            if (hsLen + 4 > recordLen) return data;

            int p = 9; // record(5) + handshake header(4) -> client_version starts here
            int pEnd = 5 + 4 + hsLen;

            int randomAt = p + 2;
            if (randomAt + 32 > pEnd) return data;
            int sessionIdLenAt = randomAt + 32;
            int sessionIdLen = data[sessionIdLenAt] & 0xff;
            if (sessionIdLen > 32) return data;
            int sessionIdAt = sessionIdLenAt + 1;
            int cipherLenAt = sessionIdAt + sessionIdLen;
            if (cipherLenAt + 2 > pEnd) return data;
            int cipherLen = u16(data, cipherLenAt);
            int compLenAt = cipherLenAt + 2 + cipherLen;
            if (compLenAt >= pEnd) return data;
            int compLen = data[compLenAt] & 0xff;
            int extLenAt = compLenAt + 1 + compLen;
            if (extLenAt + 2 > pEnd) return data;

            String sni = extractSni(data, extLenAt + 2, extLenAt + 2 + u16(data, extLenAt));
            byte[] random = Arrays.copyOfRange(data, randomAt, randomAt + 32);
            byte[] sessionId = Arrays.copyOfRange(data, sessionIdAt, sessionIdAt + sessionIdLen);

            Random rnd = new Random();
            boolean firefox = rnd.nextBoolean();
            if (sni == null || sni.isEmpty()) {
                sni = FALLBACK_SNI[rnd.nextInt(FALLBACK_SNI.length)];
            }
            byte[] hello = buildClientHello(random, sessionId, sni, firefox, rnd);

            byte[] out = new byte[5 + hello.length];
            out[0] = 0x16;
            out[1] = 0x03;
            out[2] = 0x01;
            out[3] = (byte) ((hello.length >>> 8) & 0xff);
            out[4] = (byte) (hello.length & 0xff);
            System.arraycopy(hello, 0, out, 5, hello.length);
            // Anything the client pipelined after the hello is preserved verbatim after the
            // rewritten record. FakeTLS never pipelines this early, but a truncated read that
            // caught extra bytes must not lose them.
            if (5 + recordLen < data.length) {
                byte[] full = new byte[out.length + (data.length - 5 - recordLen)];
                System.arraycopy(out, 0, full, 0, out.length);
                System.arraycopy(data, 5 + recordLen, full, out.length, data.length - 5 - recordLen);
                return full;
            }
            return out;
        } catch (Throwable t) {
            return data;
        }
    }

    /** Walk TLS extensions looking for server_name (type 0x0000) and pull the hostname out. */
    private static String extractSni(byte[] data, int from, int to) {
        int p = from;
        while (p + 4 <= to) {
            int type = u16(data, p);
            int len = u16(data, p + 2);
            int body = p + 4;
            if (body + len > to) return null;
            if (type == 0x0000 && len >= 5) {
                int listLen = u16(data, body);
                int q = body + 2;
                int qEnd = body + 2 + Math.min(listLen, len - 2);
                while (q + 3 <= qEnd) {
                    int nameType = data[q] & 0xff;
                    int nameLen = u16(data, q + 1);
                    if (nameType == 0 && q + 3 + nameLen <= qEnd) {
                        return new String(data, q + 3, nameLen, StandardCharsets.US_ASCII);
                    }
                    q += 3 + nameLen;
                }
            }
            p = body + len;
        }
        return null;
    }

    private static byte[] buildClientHello(byte[] random, byte[] sessionId, String sni,
                                           boolean firefox, Random rnd) {
        int grease = GREASE_VALUES[rnd.nextInt(GREASE_VALUES.length)];
        int grease2 = GREASE_VALUES[rnd.nextInt(GREASE_VALUES.length)];

        byte[] sniExt = serverNameExtension(sni);
        byte[] keyShare = keyShareExtension();
        int[] ciphers = firefox ? FIREFOX_CIPHERS : CHROME_CIPHERS;

        ByteArray out = new ByteArray(512);
        out.u8(0x01);                                   // handshake: ClientHello
        int lenAt = out.length();
        out.u8(0x00); out.u8(0x00); out.u8(0x00);       // length placeholder
        out.u8(0x03); out.u8(0x03);                     // legacy_version TLS 1.2
        out.bytes(random);                              // client_random (preserved)
        out.u8(sessionId.length); out.bytes(sessionId); // legacy_session_id (preserved)
        out.u16(ciphers.length * 2);
        for (int cipher : ciphers) out.u16(cipher);
        out.u8(0x01); out.u8(0x00);                     // compression: null

        int extLenAt = out.length();
        out.u16(0x0000);                                // extensions length placeholder

        // GREASE extensions: type + 1-byte body, the shape real browsers send. A bare type
        // with no length field is a malformed extension and strict SNI parsers on the proxy
        // side would reject the whole hello.
        ext(out, simple(grease, new byte[]{0x00}));
        ext(out, sniExt);
        ext(out, simple(0x0017, new byte[0]));          // extended_master_secret
        ext(out, simple(0xff01, new byte[]{0x01, 0x00})); // renegotiation_info
        // supported_groups: x25519, secp256r1, secp384r1
        ext(out, simple(0x000a, new byte[]{0x00, 0x06, 0x00, 0x1d, 0x00, 0x17, 0x00, 0x18}));
        ext(out, simple(0x000b, new byte[]{0x01, 0x00})); // ec_point_formats: uncompressed
        if (!firefox) ext(out, simple(0x0023, new byte[0])); // session_ticket
        // ALPN: h2, http/1.1
        ext(out, simple(0x0010, new byte[]{0x00, 0x0e, 0x02, 'h', '2', 0x08,
                'h', 't', 't', 'p', '/', '1', '.', '1'}));
        ext(out, simple(0x0005, new byte[]{0x01, 0x00, 0x00, 0x00, 0x00})); // status_request
        // signature_algorithms (Chrome's list)
        ext(out, simple(0x000d, new byte[]{
                0x00, 0x10, 0x04, 0x03, 0x08, 0x04, 0x04, 0x01, 0x05, 0x03,
                0x08, 0x05, 0x05, 0x01, 0x08, 0x06, 0x06, 0x01}));
        ext(out, simple(0x0012, new byte[0]));          // signed_certificate_timestamp
        ext(out, keyShare);
        ext(out, simple(0x002d, new byte[]{0x01, 0x01})); // psk_key_exchange_modes
        // supported_versions: TLS 1.3, TLS 1.2 (+ GREASE for Chrome). The body is a 1-byte
        // list length followed by 2-byte version pairs, so the Chrome variant is
        // len=6: [grease][0304][0303].
        ext(out, firefox
                ? simple(0x002b, new byte[]{0x04, 0x03, 0x04, 0x03, 0x03})
                : simple(0x002b, new byte[]{0x06, (byte) (grease >>> 8), (byte) grease,
                        0x03, 0x04, 0x03, 0x03}));
        // compress_certificate: brotli + zlib
        ext(out, simple(0x001b, new byte[]{0x04, 0x02, 0x01, 0x00}));
        if (firefox) ext(out, simple(0x0023, new byte[0])); // session_ticket (FF order)
        if (!firefox) {
            // Chrome's application_settings (ALPS) for h2 — distinctive and browser-only.
            ext(out, simple(0x4469, new byte[]{0x02, 0x00, 0x02, 0x00, 0x02}));
        }
        ext(out, simple(grease2, new byte[]{0x00}));    // trailing GREASE

        // Fill in the two lengths now that the body is final.
        int extLen = out.length() - extLenAt - 2;
        out.setU16(extLenAt, extLen);
        int hsLen = out.length() - 4;
        out.setU24(lenAt, hsLen);
        return out.toArray();
    }

    private static byte[] serverNameExtension(String host) {
        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        // list: name_type(1) + len(2) + name ; ext body: list_len(2) + list
        int listLen = 3 + name.length;
        byte[] body = new byte[2 + listLen];
        body[0] = (byte) ((listLen >>> 8) & 0xff);
        body[1] = (byte) (listLen & 0xff);
        body[2] = 0x00;
        body[3] = (byte) ((name.length >>> 8) & 0xff);
        body[4] = (byte) (name.length & 0xff);
        System.arraycopy(name, 0, body, 5, name.length);
        return simple(0x0000, body);
    }

    private static byte[] keyShareExtension() {
        byte[] pubkey = new byte[32];
        new Random().nextBytes(pubkey);
        // body: client_shares_len(2) + [ group(2) + len(2) + key ]
        byte[] body = new byte[2 + 2 + 2 + 32];
        body[0] = 0x00; body[1] = 0x24;                 // 36 bytes of shares (2+2+32)
        body[2] = 0x00; body[3] = 0x1d;                 // x25519
        body[4] = 0x00; body[5] = 0x20;                 // 32 bytes
        System.arraycopy(pubkey, 0, body, 6, 32);
        return simple(0x0033, body);
    }

    private static byte[] simple(int type, byte[] body) {
        byte[] out = new byte[4 + body.length];
        out[0] = (byte) ((type >>> 8) & 0xff);
        out[1] = (byte) (type & 0xff);
        out[2] = (byte) ((body.length >>> 8) & 0xff);
        out[3] = (byte) (body.length & 0xff);
        System.arraycopy(body, 0, out, 4, body.length);
        return out;
    }

    /** Extension wrapper for an already-built body (type + len + body). */
    private static void ext(ByteArray out, byte[] extWithTypeAndLen) {
        out.bytes(extWithTypeAndLen);
    }

    /**
     * Split the first packet into 2-3 TCP segments at randomized offsets. Splitting defeats
     * DPI reassembly even when the fingerprint is fine; combined with the rewrite it is the
     * same one-two that ByeDPI-class tools use.
     */
    public static byte[][] fragment(byte[] data, Random rnd) {
        if (data == null || data.length < 16) return new byte[][]{data};
        int pieces = 2 + rnd.nextInt(2);
        if (pieces == 2) {
            int cut = 1 + rnd.nextInt(Math.max(1, Math.min(data.length - 1, 48)));
            return new byte[][]{
                    Arrays.copyOfRange(data, 0, cut),
                    Arrays.copyOfRange(data, cut, data.length)};
        }
        int a = 1 + rnd.nextInt(Math.max(1, Math.min(data.length - 2, 24)));
        int b = a + 1 + rnd.nextInt(Math.max(1, Math.min(data.length - a - 1, 64)));
        return new byte[][]{
                Arrays.copyOfRange(data, 0, a),
                Arrays.copyOfRange(data, a, b),
                Arrays.copyOfRange(data, b, data.length)};
    }

    /** True when the buffer starts with a TLS record header this class could engage with. */
    public static boolean looksLikeClientHello(byte[] data) {
        return data != null && data.length >= 11
                && data[0] == 0x16 && data[1] == 0x03 && data[5] == 0x01;
    }

    /**
     * True when it IS a ClientHello but the record it announces is longer than what we have —
     * the caller should coalesce more bytes before rewriting.
     */
    public static boolean needsMoreBytes(byte[] data) {
        if (!looksLikeClientHello(data)) return false;
        int recordLen = ((data[3] & 0xff) << 8) | (data[4] & 0xff);
        return data.length < 5 + recordLen;
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    private static final class ByteArray {
        private byte[] buf = new byte[256];
        private int len;

        ByteArray(int cap) { buf = new byte[Math.max(cap, 64)]; }

        int length() { return len; }

        void u8(int v) { ensure(1); buf[len++] = (byte) v; }

        void u16(int v) { ensure(2); buf[len++] = (byte) ((v >>> 8) & 0xff); buf[len++] = (byte) v; }

        void setU16(int at, int v) {
            buf[at] = (byte) ((v >>> 8) & 0xff);
            buf[at + 1] = (byte) v;
        }

        void setU24(int at, int v) {
            buf[at] = (byte) ((v >>> 16) & 0xff);
            buf[at + 1] = (byte) ((v >>> 8) & 0xff);
            buf[at + 2] = (byte) v;
        }

        void bytes(byte[] b) { ensure(b.length); System.arraycopy(b, 0, buf, len, b.length); len += b.length; }

        byte[] toArray() { return Arrays.copyOf(buf, len); }

        private void ensure(int n) {
            if (len + n <= buf.length) return;
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
        }
    }
}
