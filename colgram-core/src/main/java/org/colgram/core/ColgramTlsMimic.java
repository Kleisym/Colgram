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
     * Parses a TLS 1.2/1.3 ClientHello (RFC 5246/8446) to find the exact byte span [start, end)
     * of the SNI hostname string (extension 0x0000). Works with BoringSSL/Chrome/Android GREASE
     * and post-quantum X25519MLKEM768 keyshares. Returns null if not a TLS ClientHello with SNI.
     */
    public static int[] findTlsSniSpan(byte[] buf, int len) {
        if (buf == null || len < 48) return null;
        if ((buf[0] & 0xFF) != 0x16 || (buf[1] & 0xFF) != 0x03 || (buf[5] & 0xFF) != 0x01) return null;
        int recLen = ((buf[3] & 0xFF) << 8) | (buf[4] & 0xFF);
        int limit = Math.min(len, 5 + recLen);
        if (limit < 48) return null;
        int pos = 5 + 4 + 2 + 32; // record(5) + handshake hdr(4) + legacy_version(2) + random(32)
        if (pos >= limit) return null;
        int sidLen = buf[pos] & 0xFF;
        pos += 1 + sidLen;
        if (pos + 2 > limit) return null;
        int cipherLen = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
        pos += 2 + cipherLen;
        if (pos + 1 > limit) return null;
        int compLen = buf[pos] & 0xFF;
        pos += 1 + compLen;
        if (pos + 2 > limit) return null;
        int extTotalLen = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
        pos += 2;
        int extEnd = Math.min(limit, pos + extTotalLen);
        while (pos + 4 <= extEnd) {
            int extType = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
            int extLen = ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
            pos += 4;
            if (pos + extLen > extEnd) break;
            if (extType == 0x0000 && extLen >= 5) {
                int listLen = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
                int p = pos + 2;
                int listEnd = Math.min(pos + extLen, p + listLen);
                while (p + 3 <= listEnd) {
                    int nameType = buf[p] & 0xFF;
                    int nameLen = ((buf[p + 1] & 0xFF) << 8) | (buf[p + 2] & 0xFF);
                    p += 3;
                    if (nameType == 0 && nameLen >= 2 && p + nameLen <= listEnd) {
                        return new int[]{p, p + nameLen};
                    }
                    p += nameLen;
                }
            }
            pos += extLen;
        }
        return null;
    }

    /** Extract lowercase SNI hostname from a TLS ClientHello, or null if absent/invalid. */
    public static String extractSniHostname(byte[] buf, int len) {
        int[] span = findTlsSniSpan(buf, len);
        if (span == null || span[1] <= span[0]) return null;
        for (int i = span[0]; i < span[1]; i++) {
            int c = buf[i] & 0xFF;
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            if (!valid) return null;
        }
        return new String(buf, span[0], span[1] - span[0], StandardCharsets.US_ASCII)
                .toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Returns the full announced TLS record length (5 + payloadLen) for a ClientHello, or -1 if
     * not a ClientHello or implausibly large.
     */
    public static int expectedClientHelloLength(byte[] data, int len) {
        if (data == null || len < 11 || (data[0] & 0xFF) != 0x16 || (data[1] & 0xFF) != 0x03
                || (data[5] & 0xFF) != 0x01) {
            return -1;
        }
        int recordLen = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
        if (recordLen < 40 || recordLen > 16384) return -1;
        return 5 + recordLen;
    }

    private static final java.util.Set<String> TWO_PART_CCTLD_SECOND_LABELS =
            new java.util.HashSet<>(Arrays.asList("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne"));

    /**
     * Computes the midpoint offset inside the Second-Level Domain (SLD) of an SNI span [sniStart, sniEnd).
     * On long CDN hostnames like "rr3---sn-jvhnu5g-c35z.googlevideo.com", the full-hostname midpoint falls
     * before "googlevideo.com", leaving the root domain unbroken; SLD midpoint cuts directly inside
     * "googlevideo" ("googl|evideo.com"), and also handles 2-part ccTLDs like ".co.uk".
     */
    public static int findSldMidpoint(byte[] data, int sniStart, int sniEnd) {
        int fallback = sniStart + Math.max(1, (sniEnd - sniStart) / 2);
        if (data == null || sniStart < 0 || sniEnd > data.length || sniEnd <= sniStart + 2) {
            return fallback;
        }
        int lastDot = -1;
        for (int i = sniEnd - 1; i >= sniStart; i--) {
            if (data[i] == (byte) '.') {
                lastDot = i;
                break;
            }
        }
        if (lastDot <= sniStart + 1) return fallback;

        int prevDot = sniStart - 1;
        for (int i = lastDot - 1; i >= sniStart; i--) {
            if (data[i] == (byte) '.') {
                prevDot = i;
                break;
            }
        }

        int sldEnd = lastDot;
        int sldStart = prevDot + 1;
        int tldLen = sniEnd - (lastDot + 1);
        int sldLen = sldEnd - sldStart;
        if (tldLen == 2 && prevDot > sniStart && sldLen >= 2 && sldLen <= 3) {
            String secondLabel = new String(data, sldStart, sldLen, StandardCharsets.US_ASCII)
                    .toLowerCase(java.util.Locale.ROOT);
            if (TWO_PART_CCTLD_SECOND_LABELS.contains(secondLabel)) {
                int prevPrevDot = sniStart - 1;
                for (int i = prevDot - 1; i >= sniStart; i--) {
                    if (data[i] == (byte) '.') {
                        prevPrevDot = i;
                        break;
                    }
                }
                if (prevDot - (prevPrevDot + 1) >= 2) {
                    sldEnd = prevDot;
                    sldStart = prevPrevDot + 1;
                    sldLen = sldEnd - sldStart;
                }
            }
        }
        if (sldLen < 2) return fallback;
        return sldStart + Math.max(1, sldLen / 2);
    }

    /**
     * Splits a complete TLS ClientHello record into 2 or 3 RFC-valid 0x16 TLS records sliced
     * directly inside the SNI hostname (at the SLD midpoint, and near SNI start for 3-record mode).
     * Preserves any trailing bytes pipelined after the ClientHello record. Returns null when data
     * does not hold a complete ClientHello with SNI.
     */
    public static byte[][] splitTlsRecordAtSni(byte[] data, int parts) {
        if (data == null) return null;
        int recEnd = expectedClientHelloLength(data, data.length);
        if (recEnd < 0 || data.length < recEnd) return null;
        int[] sni = findTlsSniSpan(data, recEnd);
        if (sni == null) return null;
        int sniStart = sni[0];
        int sniEnd = sni[1];
        int sldMid = findSldMidpoint(data, sniStart, sniEnd);
        int trailingLen = data.length - recEnd;

        if (parts >= 3 && sniEnd - sniStart >= 4) {
            int cut1 = Math.min(sniStart + 2, sldMid - 1);
            if (cut1 > 6 && sldMid > cut1 && sldMid < recEnd - 1) {
                byte[] r1 = buildTlsRecord(data[0], data[1], data[2], data, 5, cut1 - 5, null, 0, 0);
                byte[] r2 = buildTlsRecord(data[0], data[1], data[2], data, cut1, sldMid - cut1, null, 0, 0);
                byte[] r3 = buildTlsRecord(data[0], data[1], data[2], data, sldMid, recEnd - sldMid,
                        trailingLen > 0 ? data : null, recEnd, trailingLen);
                return new byte[][]{r1, r2, r3};
            }
        }
        if (sldMid <= 6 || sldMid >= recEnd - 1) return null;
        byte[] r1 = buildTlsRecord(data[0], data[1], data[2], data, 5, sldMid - 5, null, 0, 0);
        byte[] r2 = buildTlsRecord(data[0], data[1], data[2], data, sldMid, recEnd - sldMid,
                trailingLen > 0 ? data : null, recEnd, trailingLen);
        return new byte[][]{r1, r2};
    }

    /**
     * Splits a complete TLS ClientHello record into 3 RFC-valid 0x16 TLS records at
     * {@code sniStart + offsetAfterSniStart} AND the SLD midpoint when
     * {@code sldMid > sniStart + offsetAfterSniStart + 1}, falling back to a 2-record split via
     * {@link #splitTlsRecordAtSniOffset(byte[], int)} for short 2-label hostnames.
     */
    public static byte[][] splitTlsRecordAtSniAndSld(byte[] data, int offsetAfterSniStart) {
        if (data == null) return null;
        int recEnd = expectedClientHelloLength(data, data.length);
        if (recEnd < 0 || data.length < recEnd) return null;
        int[] sni = findTlsSniSpan(data, recEnd);
        if (sni == null) return null;
        int sniStart = sni[0];
        int sniEnd = sni[1];
        int sniLen = sniEnd - sniStart;
        if (sniLen < 2) return null;
        int off = Math.max(1, Math.min(offsetAfterSniStart, sniLen - 1));
        int cut1 = sniStart + off;
        int sldMid = findSldMidpoint(data, sniStart, sniEnd);
        if (sldMid > cut1 + 1 && cut1 > 6 && sldMid < recEnd - 1) {
            int trailingLen = data.length - recEnd;
            byte[] r1 = buildTlsRecord(data[0], data[1], data[2], data, 5, cut1 - 5, null, 0, 0);
            byte[] r2 = buildTlsRecord(data[0], data[1], data[2], data, cut1, sldMid - cut1, null, 0, 0);
            byte[] r3 = buildTlsRecord(data[0], data[1], data[2], data, sldMid, recEnd - sldMid,
                    trailingLen > 0 ? data : null, recEnd, trailingLen);
            return new byte[][]{r1, r2, r3};
        }
        return splitTlsRecordAtSniOffset(data, offsetAfterSniStart);
    }

    /**
     * Splits a complete TLS ClientHello record into 2 RFC-valid 0x16 TLS records at an exact
     * byte offset inside the SNI hostname (e.g. {@code offsetAfterSniStart == 1} implements exact
     * ByeDPI {@code -r1+s}, cutting immediately after the 1st character of the domain name).
     */
    public static byte[][] splitTlsRecordAtSniOffset(byte[] data, int offsetAfterSniStart) {
        if (data == null) return null;
        int recEnd = expectedClientHelloLength(data, data.length);
        if (recEnd < 0 || data.length < recEnd) return null;
        int[] sni = findTlsSniSpan(data, recEnd);
        if (sni == null) return null;
        int sniLen = sni[1] - sni[0];
        if (sniLen < 2) return null;
        int off = Math.max(1, Math.min(offsetAfterSniStart, sniLen - 1));
        int cut = sni[0] + off;
        if (cut <= 6 || cut >= recEnd - 1) return null;
        int trailingLen = data.length - recEnd;
        byte[] r1 = buildTlsRecord(data[0], data[1], data[2], data, 5, cut - 5, null, 0, 0);
        byte[] r2 = buildTlsRecord(data[0], data[1], data[2], data, cut, recEnd - cut,
                trailingLen > 0 ? data : null, recEnd, trailingLen);
        return new byte[][]{r1, r2};
    }

    /**
     * RFC 9110 §5.1 compliant HTTP/1.x Host header case-mangling (`Host:` -> `hoSt:`).
     * Defeats naive plain-HTTP DPI parsers matching exact `Host:` while remaining 100% valid
     * for all standards-compliant HTTP servers. Never mutates the caller's array.
     */
    public static byte[] mangleHttpHostHeader(byte[] buf, int len) {
        return mangleHttpHostHeaderInternal(buf, len, false);
    }

    /** Polymorphic HTTP/1.x Host header case-randomization across 14 non-canonical casings. */
    public static byte[] mangleHttpHostHeaderPolymorphic(byte[] buf, int len) {
        return mangleHttpHostHeaderInternal(buf, len, true);
    }

    private static byte[] mangleHttpHostHeaderInternal(byte[] buf, int len, boolean polymorphic) {
        if (buf == null || len < 16 || len > buf.length) return buf;
        byte b0 = buf[0];
        if (b0 != 'G' && b0 != 'P' && b0 != 'H' && b0 != 'C' && b0 != 'O' && b0 != 'D') return buf;
        int headerLimit = len - 6;
        for (int i = 0; i < len - 3; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                headerLimit = Math.min(headerLimit, i);
                break;
            }
        }
        for (int i = 0; i < headerLimit; i++) {
            if (buf[i] == '\n'
                    && (buf[i + 1] == 'H' || buf[i + 1] == 'h')
                    && (buf[i + 2] == 'o' || buf[i + 2] == 'O')
                    && (buf[i + 3] == 's' || buf[i + 3] == 'S')
                    && (buf[i + 4] == 't' || buf[i + 4] == 'T')
                    && buf[i + 5] == ':') {
                byte[] copy = Arrays.copyOf(buf, len);
                if (polymorphic) {
                    ColgramPolymorphicStealth.randomizeHttpHostHeaderCase(copy, i);
                } else {
                    copy[i + 1] = 'h';
                    copy[i + 2] = 'o';
                    copy[i + 3] = 'S';
                    copy[i + 4] = 't';
                }
                return copy;
            }
        }
        return buf;
    }

    private static byte[] buildTlsRecord(byte type, byte maj, byte min,
                                         byte[] payload, int off, int len,
                                         byte[] trailing, int trailingOff, int trailingLen) {
        byte[] out = new byte[5 + len + trailingLen];
        out[0] = type;
        out[1] = maj;
        out[2] = min;
        out[3] = (byte) ((len >>> 8) & 0xFF);
        out[4] = (byte) (len & 0xFF);
        System.arraycopy(payload, off, out, 5, len);
        if (trailing != null && trailingLen > 0) {
            System.arraycopy(trailing, trailingOff, out, 5 + len, trailingLen);
        }
        return out;
    }

    /**
     * Split the first packet into TCP segments without modifying a single byte.
     *
     * For TLS/FakeTLS ClientHellos containing an SNI hostname, ALWAYS slices at byte 1 AND right
     * across the SLD midpoint (`[0..1)`, `[1..sldMid)`, `[sldMid..end)`) so TSPU/RKN never sees
     * the complete registered domain in one TCP segment while preserving TLS transcripts & FakeTLS HMAC.
     */
    public static byte[][] fragment(byte[] data, Random rnd) {
        if (data == null || data.length < 16) return new byte[][]{data};
        int[] sni = findTlsSniSpan(data, data.length);
        if (sni != null) {
            int sldMid = findSldMidpoint(data, sni[0], sni[1]);
            int minCut = Math.max(2, sni[0] + 1);
            int maxCut = Math.min(data.length - 1, sni[1] - 1);
            if (minCut < maxCut) {
                sldMid = ColgramPolymorphicStealth.jitterSplitOffset(sldMid, minCut, maxCut);
            }
            if (sldMid > 1 && sldMid < data.length) {
                return new byte[][]{
                        Arrays.copyOfRange(data, 0, 1),
                        Arrays.copyOfRange(data, 1, sldMid),
                        Arrays.copyOfRange(data, sldMid, data.length)};
            }
        }
        int pieces = 2 + (rnd != null ? rnd.nextInt(2) : 0);
        if (pieces == 2) {
            int cut = 1 + (rnd != null ? rnd.nextInt(Math.max(1, Math.min(data.length - 1, 48))) : 1);
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

    /**
     * Whitelisted Russian SNI hostnames used exclusively inside low-TTL ByeDPI decoy packets
     * that expire before reaching the real destination server.
     */
    private static final String[] DECOY_WHITELIST_SNI = {
            "max.ru", "vk.com", "yandex.ru", "gosuslugi.ru",
            "ozon.ru", "sberbank.ru", "wildberries.ru", "dzen.ru"
    };

    /**
     * Builds a compact, RFC-valid TLS 1.3 ClientHello carrying a whitelisted Russian SNI right at
     * the start of its extensions (offset ~62..80), padded via RFC 7685 (0x0015) to match
     * {@code targetLen} when possible. Used exclusively as a low-TTL decoy packet in native
     * ByeDPI {@code sendfile()+mmap()} desync.
     */
    public static byte[] buildFakeWhitelistedClientHello(int targetLen, int variant) {
        String sni = ColgramPolymorphicStealth.pickBenignSni();
        if (targetLen >= 512) {
            return ColgramPolymorphicStealth.buildPolymorphicTlsClientHello(sni);
        }
        byte[] random = new byte[32];
        ColgramPolymorphicStealth.fillRandomBytes(random, 0, 32);

        ByteArray hs = new ByteArray(Math.max(256, targetLen));
        hs.u8(0x01);                                    // HandshakeType: client_hello
        int hsLenAt = hs.length();
        hs.u8(0x00); hs.u8(0x00); hs.u8(0x00);          // handshake length placeholder
        hs.u8(0x03); hs.u8(0x03);                       // legacy_version TLS 1.2
        hs.bytes(random);                               // 32B polymorphic client_random
        hs.u8(0x00);                                    // compact 0-byte session_id so SNI sits early inside split1
        // Randomized TLS 1.3 + ECDHE suite list
        int[] suites = (variant & 1) == 0
                ? new int[]{0x1301, 0x1302, 0x1303, 0xc02f}
                : new int[]{0x1301, 0x1303, 0x1302, 0xcca9};
        hs.u16(suites.length * 2);
        for (int cs : suites) hs.u16(cs);
        hs.u8(0x01); hs.u8(0x00);                       // compression: null

        int extLenAt = hs.length();
        hs.u16(0x0000);                                 // extensions length placeholder

        // Put SNI extension FIRST so carrier TSPU sees the complete whitelisted hostname even in small segments
        ext(hs, serverNameExtension(sni));
        // supported_versions: TLS 1.3, TLS 1.2
        ext(hs, simple(0x002b, new byte[]{0x04, 0x03, 0x04, 0x03, 0x03}));

        // If caller wants an exact targetLen >= current record size + 4, pad via RFC 7685 extension 0x0015
        int currentRecordLen = 5 + hs.length();
        if (targetLen >= currentRecordLen + 4) {
            int padBodyLen = targetLen - currentRecordLen - 4;
            ext(hs, simple(0x0015, new byte[padBodyLen]));
        }

        int extLen = hs.length() - extLenAt - 2;
        hs.setU16(extLenAt, extLen);
        int hsLen = hs.length() - 4;
        hs.setU24(hsLenAt, hsLen);

        byte[] hello = hs.toArray();
        byte[] record = new byte[5 + hello.length];
        record[0] = 0x16;
        record[1] = 0x03;
        record[2] = (byte) ((ColgramPolymorphicStealth.nextUInt16() & 3) == 0 ? 0x03 : 0x01);
        record[3] = (byte) ((hello.length >>> 8) & 0xFF);
        record[4] = (byte) (hello.length & 0xFF);
        System.arraycopy(hello, 0, record, 5, hello.length);
        return record;
    }

    /** True when upstream replied immediately with a fatal TLS Alert (e.g. decode_error / handshake_failure). */
    public static boolean looksLikeFatalTlsAlert(byte[] data, int len) {
        return data != null && len >= 7
                && (data[0] & 0xFF) == 0x15
                && (data[1] & 0xFF) == 0x03
                && (data[5] & 0xFF) == 0x02;
    }

    /** True when upstream replied with a well-known Russian ISP HTTP block-page redirect/stub. */
    public static boolean looksLikeIspBlockRedirect(byte[] data, int len) {
        if (data == null || len < 16) return false;
        if (data[0] != 'H' || data[1] != 'T' || data[2] != 'T' || data[3] != 'P') return false;
        int checkLen = Math.min(len, 512);
        String head = new String(data, 0, checkLen, StandardCharsets.ISO_8859_1)
                .toLowerCase(java.util.Locale.US);
        if (!head.startsWith("http/1.") || !(head.contains(" 301 ") || head.contains(" 302 ")
                || head.contains(" 307 ") || head.contains(" 403 ") || head.contains(" 451 "))) {
            return false;
        }
        return head.contains("warning.rt.ru")
                || head.contains("eais.rkn.gov.ru")
                || head.contains("blocklist.rkn.gov.ru")
                || head.contains("lawfilter")
                || head.contains("fz139")
                || head.contains("blocked.mts.ru")
                || head.contains("megafon.ru/block")
                || head.contains("beeline.ru/block")
                || head.contains("tele2.ru/block");
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
