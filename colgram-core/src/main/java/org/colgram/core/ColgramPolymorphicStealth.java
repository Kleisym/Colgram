package org.colgram.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * v6.0 Polymorphic Wire Stealth & RFC 9001 QUIC v1 Initial Cryptographic Codec:
 * - Zero static DPI/TSPU byte signatures across TLS fakes, HTTP fakes, continuation decoys, QUIC Initials,
 *   HTTP Host header casing, OOB urgency bytes, micro-flush timing, and WebSocket upgrades.
 * - Full RFC 9001 QUIC v1 Initial encryption (HKDF-SHA256 + AES-128-GCM + AES-ECB Header Protection)
 *   plus self-test decryption and SNI verification.
 * - Shared multi-tenant CDN classifier so one blocked subdomain never poisons an entire hosting domain.
 */
public final class ColgramPolymorphicStealth {

    private ColgramPolymorphicStealth() {}

    public static final String[] WHITELIST_BENIGN_SNIS = {
            "vk.com",
            "yandex.ru",
            "ya.ru",
            "ozon.ru",
            "wildberries.ru",
            "sberbank.ru",
            "gosuslugi.ru",
            "avito.ru",
            "dzen.ru",
            "mail.ru",
            "ok.ru",
            "rutube.ru",
            "kinopoisk.ru",
            "rbc.ru",
            "tbank.ru",
            "alfabank.ru",
            "vtb.ru",
            "mts.ru",
            "megafon.ru",
            "beeline.ru",
            "rt.ru",
            "kp.ru",
            "lenta.ru",
            "max.ru",
            "2gis.ru",
            "hh.ru",
            "habr.com",
            "dns-shop.ru"
    };

    private static final String[] BROWSER_USER_AGENTS = {
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; 23127PN0CG) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 15; SM-S931B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 YaBrowser/25.2.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 15; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 YaBrowser/25.4.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Edg/135.0.0.0"
    };

    private static final String[] ACCEPT_LANGUAGES = {
            "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            "ru,en;q=0.9,en-US;q=0.8",
            "ru-RU,ru;q=0.95,en;q=0.8",
            "en-US,en;q=0.9,ru;q=0.8"
    };

    private static final String[] FAKE_HTTP_PATHS = {
            "/",
            "/index.html",
            "/robots.txt",
            "/favicon.ico",
            "/assets/app.js",
            "/static/main.css",
            "/api/v1/status",
            "/news/"
    };

    // RFC 9001 §5.2 QUIC v1 Initial Salt
    private static final byte[] QUIC_V1_INITIAL_SALT = {
            (byte) 0x38, (byte) 0x76, (byte) 0x2C, (byte) 0xF7, (byte) 0xF5, (byte) 0x59, (byte) 0x34, (byte) 0xB3,
            (byte) 0x4D, (byte) 0x17, (byte) 0x9A, (byte) 0xE6, (byte) 0xA4, (byte) 0xC8, (byte) 0x0C, (byte) 0xAD,
            (byte) 0xCC, (byte) 0xBB, (byte) 0x7F, (byte) 0x0A
    };

    private static final byte[] LABEL_CLIENT_IN = "tls13 client in".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_QUIC_KEY = "tls13 quic key".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_QUIC_IV = "tls13 quic iv".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_QUIC_HP = "tls13 quic hp".getBytes(StandardCharsets.US_ASCII);

    private static final int[] TLS12_CIPHER_POOL = {
            0xC02B, 0xC02F, 0xC02C, 0xC030, 0xCCA9, 0xCCA8,
            0xC013, 0xC014, 0x009C, 0x009D, 0x002F, 0x0035
    };

    private static final int[] HOST_CASE_MASKS = {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
    };

    private static final String[] MULTI_TENANT_SHARED_SUFFIXES = {
            "cloudfront.net",
            "amazonaws.com",
            "azureedge.net",
            "azurefd.net",
            "fastly.net",
            "akamaiedge.net",
            "akamaihd.net",
            "workers.dev",
            "pages.dev",
            "googleapis.com",
            "googleusercontent.com",
            "herokuapp.com",
            "netlify.app",
            "vercel.app",
            "github.io",
            "cdn77.org"
    };

    private static final SecureRandom SEED_RNG = new SecureRandom();
    private static final Object SEED_LOCK = new Object();

    // [0]=state, [1]=seeded(0/1)
    private static final ThreadLocal<long[]> THREAD_RNG = ThreadLocal.withInitial(() -> new long[2]);

    private static final ConcurrentHashMap<Integer, Integer> BYPASS_OWNED_LOCAL_PORTS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Long> PORT_RELEASE_DEADLINES =
            new ConcurrentHashMap<>();

    private static final AtomicLong TLS_CLIENT_HELLOS_BUILT = new AtomicLong();
    private static final AtomicLong QUIC_INITIALS_BUILT = new AtomicLong();
    private static final AtomicLong QUIC_DECRYPT_VERIFIED = new AtomicLong();
    private static final AtomicLong CONTINUATION_DECOYS_BUILT = new AtomicLong();
    private static final AtomicLong FAKE_HTTP_BUILT = new AtomicLong();
    private static final AtomicLong VOICE_DECOYS_BUILT = new AtomicLong();
    private static final AtomicLong WS_UPGRADES_BUILT = new AtomicLong();
    private static final AtomicLong TRACKED_PORTS_TOTAL = new AtomicLong();

    private static long nextUInt64() {
        long[] cell = THREAD_RNG.get();
        if (cell[1] == 0L) {
            byte[] seedBuf = new byte[8];
            synchronized (SEED_LOCK) {
                SEED_RNG.nextBytes(seedBuf);
            }
            long s = 0L;
            for (int i = 0; i < 8; i++) {
                s = (s << 8) | (seedBuf[i] & 0xFFL);
            }
            s ^= System.nanoTime();
            s ^= (Thread.currentThread().getId() << 32) ^ 0x9E3779B97F4A7C15L;
            if (s == 0L) {
                s = 0x517CC1B727220A95L;
            }
            cell[0] = s;
            cell[1] = 1L;
        }
        long x = cell[0];
        x ^= (x >>> 12);
        x ^= (x << 25);
        x ^= (x >>> 27);
        cell[0] = x;
        return x * 0x2545F4914F6CDD1DL;
    }

    private static int nextInt(int minInclusive, int maxExclusive) {
        if (maxExclusive <= minInclusive) return minInclusive;
        long span = (long) (maxExclusive - minInclusive);
        long u = nextUInt64() & 0x7FFFFFFFFFFFFFFFL;
        return minInclusive + (int) (u % span);
    }

    public static int nextUInt16() {
        return (int) ((nextUInt64() >>> 48) & 0xFFFFL);
    }

    public static void fillRandomBytes(byte[] buf, int offset, int count) {
        if (buf == null || offset < 0 || count <= 0 || offset + count > buf.length) return;
        int end = offset + count;
        int pos = offset;
        while (pos + 8 <= end) {
            long r = nextUInt64();
            buf[pos] = (byte) r;
            buf[pos + 1] = (byte) (r >>> 8);
            buf[pos + 2] = (byte) (r >>> 16);
            buf[pos + 3] = (byte) (r >>> 24);
            buf[pos + 4] = (byte) (r >>> 32);
            buf[pos + 5] = (byte) (r >>> 40);
            buf[pos + 6] = (byte) (r >>> 48);
            buf[pos + 7] = (byte) (r >>> 56);
            pos += 8;
        }
        if (pos < end) {
            long r = nextUInt64();
            while (pos < end) {
                buf[pos++] = (byte) r;
                r >>>= 8;
            }
        }
    }

    private static int pickRandomGrease16() {
        int nibble = (int) ((nextUInt64() & 0x0FL) << 4);
        int b = nibble | 0x0A;
        return (b << 8) | b;
    }

    public static String pickBenignSni() {
        return pickBenignSni(null);
    }

    public static String pickBenignSni(String avoidSni) {
        int n = WHITELIST_BENIGN_SNIS.length;
        int idx = nextInt(0, n);
        String cand = WHITELIST_BENIGN_SNIS[idx];
        if (avoidSni != null && !avoidSni.trim().isEmpty()
                && cand.equalsIgnoreCase(avoidSni.trim()) && n > 1) {
            idx = (idx + 1 + nextInt(0, n - 1)) % n;
            cand = WHITELIST_BENIGN_SNIS[idx];
        }
        return cand;
    }

    public static String pickBrowserUserAgent() {
        return BROWSER_USER_AGENTS[nextInt(0, BROWSER_USER_AGENTS.length)];
    }

    public static int getBenignSniCount() {
        return WHITELIST_BENIGN_SNIS.length;
    }

    /** Track 3 telemetry contract: concise live stealth stats string for Custom UI / Settings. */
    public static String getStealthStatsSummary() {
        return getStealthStatsSnapshot();
    }

    public static String getStealthStatsSnapshot() {
        return String.format(
                Locale.US,
                "v6.0 snis=%d tls=%d quic=%d quicOk=%d cont=%d http=%d voice=%d ws=%d ports=%d/%d",
                WHITELIST_BENIGN_SNIS.length,
                TLS_CLIENT_HELLOS_BUILT.get(),
                QUIC_INITIALS_BUILT.get(),
                QUIC_DECRYPT_VERIFIED.get(),
                CONTINUATION_DECOYS_BUILT.get(),
                FAKE_HTTP_BUILT.get(),
                VOICE_DECOYS_BUILT.get(),
                WS_UPGRADES_BUILT.get(),
                BYPASS_OWNED_LOCAL_PORTS.size(),
                TRACKED_PORTS_TOTAL.get());
    }

    /** Multi-tenant CDN platforms where subdomains belong to completely unrelated services. */
    public static boolean isMultiTenantSharedDomain(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.US);
        if (h.isEmpty()) return false;
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        for (String suffix : MULTI_TENANT_SHARED_SUFFIXES) {
            if (h.equals(suffix) || h.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    public static String buildPolymorphicWsUpgradeRequest(String wssHost, String secWebSocketKey) {
        WS_UPGRADES_BUILT.incrementAndGet();
        String host = (wssHost == null || wssHost.trim().isEmpty()) ? "kws2.web.telegram.org" : wssHost.trim();
        String key = (secWebSocketKey == null || secWebSocketKey.trim().isEmpty())
                ? "dGhlIHNhbXBsZSBub25jZQ=="
                : secWebSocketKey.trim();

        List<String> headers = new ArrayList<>(12);
        headers.add("Connection: Upgrade");
        headers.add("Upgrade: websocket");
        headers.add("Sec-WebSocket-Key: " + key);
        headers.add("Sec-WebSocket-Version: 13");
        headers.add("Sec-WebSocket-Protocol: binary");
        headers.add("Origin: https://web.telegram.org");
        headers.add("User-Agent: " + pickBrowserUserAgent());

        if (nextInt(0, 2) == 0) headers.add("Pragma: no-cache");
        if (nextInt(0, 2) == 0) headers.add("Cache-Control: no-cache");
        if (nextInt(0, 4) != 0) headers.add("Accept-Language: " + ACCEPT_LANGUAGES[nextInt(0, ACCEPT_LANGUAGES.length)]);
        if (nextInt(0, 3) != 0) {
            headers.add("Accept-Encoding: gzip, deflate, br" + (nextInt(0, 2) == 0 ? ", zstd" : ""));
        }

        for (int i = headers.size() - 1; i > 0; i--) {
            int j = nextInt(0, i + 1);
            Collections.swap(headers, i, j);
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append("GET /apiws HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        for (int i = 0; i < headers.size(); i++) {
            sb.append(headers.get(i)).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString();
    }

    public static byte[] buildPolymorphicTlsClientHello() {
        return buildPolymorphicTlsClientHello(null);
    }

    public static byte[] buildPolymorphicTlsClientHello(String sni) {
        TLS_CLIENT_HELLOS_BUILT.incrementAndGet();
        byte[] hs = buildTlsHandshakeClientHello(sni, false, 940);
        byte[] rec = new byte[5 + hs.length];
        rec[0] = 0x16;
        rec[1] = 0x03;
        rec[2] = (byte) (nextInt(0, 4) == 0 ? 0x03 : 0x01);
        rec[3] = (byte) ((hs.length >>> 8) & 0xFF);
        rec[4] = (byte) (hs.length & 0xFF);
        System.arraycopy(hs, 0, rec, 5, hs.length);
        return rec;
    }

    private static byte[] buildTlsHandshakeClientHello(String sni, boolean isQuic, int maxTotalBytes) {
        String host = (sni == null || sni.trim().isEmpty()) ? pickBenignSni() : sni.trim();
        byte[] hostBytes = host.getBytes(StandardCharsets.US_ASCII);
        if (hostBytes.length > 250) {
            host = pickBenignSni();
            hostBytes = host.getBytes(StandardCharsets.US_ASCII);
        }

        byte[] random = new byte[32];
        fillRandomBytes(random, 0, 32);

        int sessionIdLen = isQuic ? 0 : 32;
        byte[] sessionId = sessionIdLen > 0 ? new byte[sessionIdLen] : null;
        if (sessionId != null) {
            fillRandomBytes(sessionId, 0, sessionIdLen);
        }

        int gSuite = pickRandomGrease16();
        int gExt1 = pickRandomGrease16();
        int gExt2 = pickRandomGrease16();
        if (gExt2 == gExt1) {
            gExt2 = ((gExt1 + 0x1010) & 0xF0F0) | 0x0A0A;
        }
        int gGroup = pickRandomGrease16();
        int gVer = pickRandomGrease16();

        List<Integer> suites = new ArrayList<>(18);
        if (isQuic) {
            if (nextInt(0, 2) == 0) suites.add(gSuite);
            suites.add(0x1301);
            suites.add(0x1302);
            suites.add(0x1303);
        } else {
            suites.add(gSuite);
            int[] tls13 = {0x1301, 0x1302, 0x1303};
            if (nextInt(0, 3) == 0) {
                int tmp = tls13[1];
                tls13[1] = tls13[2];
                tls13[2] = tmp;
            }
            for (int cs : tls13) suites.add(cs);
            for (int i = 0; i < 6; i++) suites.add(TLS12_CIPHER_POOL[i]);
            int extraCount = nextInt(2, TLS12_CIPHER_POOL.length - 5);
            for (int i = 6; i < 6 + extraCount && i < TLS12_CIPHER_POOL.length; i++) {
                if (nextInt(0, 4) != 0) suites.add(TLS12_CIPHER_POOL[i]);
            }
        }

        List<byte[]> extensions = new ArrayList<>(16);

        // 1. server_name (0x0000)
        {
            int listLen = 3 + hostBytes.length;
            int extLen = 2 + listLen;
            byte[] ext = new byte[4 + extLen];
            writeU16(ext, 0, 0x0000);
            writeU16(ext, 2, extLen);
            writeU16(ext, 4, listLen);
            ext[6] = 0x00;
            writeU16(ext, 7, hostBytes.length);
            System.arraycopy(hostBytes, 0, ext, 9, hostBytes.length);
            extensions.add(ext);
        }

        // 2. extended_master_secret & renegotiation_info & ec_point_formats (TCP TLS only)
        if (!isQuic) {
            extensions.add(new byte[]{0x00, 0x17, 0x00, 0x00});
            extensions.add(new byte[]{(byte) 0xFF, 0x01, 0x00, 0x01, 0x00});
            extensions.add(new byte[]{0x00, 0x0B, 0x00, 0x02, 0x01, 0x00});
        }

        // 3. status_request & SCT
        extensions.add(new byte[]{0x00, 0x05, 0x00, 0x05, 0x01, 0x00, 0x00, 0x00, 0x00});
        if (nextInt(0, 3) != 0) {
            extensions.add(new byte[]{0x00, 0x12, 0x00, 0x00});
        }

        // 4. ALPN (0x0010)
        if (isQuic) {
            extensions.add(new byte[]{0x00, 0x10, 0x00, 0x05, 0x00, 0x03, 0x02, 'h', '3'});
        } else if (nextInt(0, 3) == 0) {
            extensions.add(new byte[]{0x00, 0x10, 0x00, 0x05, 0x00, 0x03, 0x02, 'h', '2'});
        } else {
            extensions.add(new byte[]{
                    0x00, 0x10, 0x00, 0x0E, 0x00, 0x0C,
                    0x02, 'h', '2',
                    0x08, 'h', 't', 't', 'p', '/', '1', '.', '1'
            });
        }

        // 5. supported_groups (0x000A)
        {
            boolean includeP521 = nextInt(0, 2) == 0;
            int gCount = includeP521 ? 5 : 4;
            byte[] ext = new byte[6 + gCount * 2];
            writeU16(ext, 0, 0x000A);
            writeU16(ext, 2, 2 + gCount * 2);
            writeU16(ext, 4, gCount * 2);
            writeU16(ext, 6, gGroup);
            writeU16(ext, 8, 0x001D);  // x25519
            writeU16(ext, 10, 0x0017); // secp256r1
            writeU16(ext, 12, 0x0018); // secp384r1
            if (includeP521) writeU16(ext, 14, 0x0019);
            extensions.add(ext);
        }

        // 6. signature_algorithms (0x000D)
        extensions.add(new byte[]{
                0x00, 0x0D, 0x00, 0x12, 0x00, 0x10,
                0x04, 0x03, 0x08, 0x04, 0x04, 0x01, 0x05, 0x03,
                0x08, 0x05, 0x05, 0x01, 0x08, 0x06, 0x06, 0x01
        });

        // 7. psk_key_exchange_modes (0x002D)
        extensions.add(new byte[]{0x00, 0x2D, 0x00, 0x02, 0x01, 0x01});

        // 8. supported_versions (0x002B)
        if (isQuic) {
            extensions.add(new byte[]{
                    0x00, 0x2B, 0x00, 0x05, 0x04,
                    (byte) (gVer >>> 8), (byte) (gVer & 0xFF),
                    0x03, 0x04
            });
        } else {
            extensions.add(new byte[]{
                    0x00, 0x2B, 0x00, 0x07, 0x06,
                    (byte) (gVer >>> 8), (byte) (gVer & 0xFF),
                    0x03, 0x04, 0x03, 0x03
            });
        }

        // 9. key_share (0x0033)
        {
            byte[] ext = new byte[47];
            writeU16(ext, 0, 0x0033);
            writeU16(ext, 2, 43);
            writeU16(ext, 4, 41);
            writeU16(ext, 6, gGroup);
            writeU16(ext, 8, 1);
            ext[10] = 0x00;
            writeU16(ext, 11, 0x001D);
            writeU16(ext, 13, 32);
            fillRandomBytes(ext, 15, 32);
            extensions.add(ext);
        }

        // 10. Varied optional extensions
        if (nextInt(0, 2) == 0) {
            extensions.add(new byte[]{0x00, 0x1B, 0x00, 0x03, 0x02, 0x00, 0x02});
        }
        if (!isQuic && nextInt(0, 2) == 0) {
            int ticketLen = nextInt(0, 3) == 0 ? 0 : nextInt(32, 129);
            byte[] ext = new byte[4 + ticketLen];
            writeU16(ext, 0, 0x0023);
            writeU16(ext, 2, ticketLen);
            if (ticketLen > 0) fillRandomBytes(ext, 4, ticketLen);
            extensions.add(ext);
        }
        if (nextInt(0, 2) == 0) {
            extensions.add(new byte[]{0x00, 0x31, 0x00, 0x00});
        }
        if (isQuic) {
            extensions.add(buildQuicTransportParametersExtension());
        }

        // Extension order randomization (Chrome >= 106)
        for (int i = extensions.size() - 1; i > 0; i--) {
            int j = nextInt(0, i + 1);
            Collections.swap(extensions, i, j);
        }

        byte[] g1 = {(byte) (gExt1 >>> 8), (byte) (gExt1 & 0xFF), 0x00, 0x00};
        int g2Len = nextInt(0, 2) == 0 ? 0 : nextInt(1, 5);
        byte[] g2 = new byte[4 + g2Len];
        writeU16(g2, 0, gExt2);
        writeU16(g2, 2, g2Len);
        if (g2Len > 0) fillRandomBytes(g2, 4, g2Len);

        int fixedPrefixLen = 4 + 2 + 32 + 1 + sessionIdLen + 2 + (suites.size() * 2) + 2 + 2;
        int extsLen = g1.length + g2.length;
        for (byte[] e : extensions) extsLen += e.length;

        int padLen = 0;
        if (!isQuic) {
            int targetRecTotal = nextInt(512, Math.max(513, maxTotalBytes + 1));
            int currentRecTotal = 5 + fixedPrefixLen + extsLen;
            if (targetRecTotal > currentRecTotal + 4) {
                padLen = targetRecTotal - (currentRecTotal + 4);
            }
        }

        int totalExtsLen = extsLen + (padLen > 0 ? (4 + padLen) : 0);
        int totalHsLen = fixedPrefixLen + totalExtsLen;
        byte[] hs = new byte[totalHsLen];
        int pos = 0;

        hs[pos++] = 0x01; // ClientHello
        int bodyLen = totalHsLen - 4;
        hs[pos++] = (byte) ((bodyLen >>> 16) & 0xFF);
        hs[pos++] = (byte) ((bodyLen >>> 8) & 0xFF);
        hs[pos++] = (byte) (bodyLen & 0xFF);
        hs[pos++] = 0x03;
        hs[pos++] = 0x03;
        System.arraycopy(random, 0, hs, pos, 32);
        pos += 32;

        hs[pos++] = (byte) sessionIdLen;
        if (sessionIdLen > 0) {
            System.arraycopy(sessionId, 0, hs, pos, sessionIdLen);
            pos += sessionIdLen;
        }

        writeU16(hs, pos, suites.size() * 2);
        pos += 2;
        for (int cs : suites) {
            writeU16(hs, pos, cs);
            pos += 2;
        }

        hs[pos++] = 0x01;
        hs[pos++] = 0x00;

        writeU16(hs, pos, totalExtsLen);
        pos += 2;

        System.arraycopy(g1, 0, hs, pos, g1.length);
        pos += g1.length;
        for (byte[] e : extensions) {
            System.arraycopy(e, 0, hs, pos, e.length);
            pos += e.length;
        }
        System.arraycopy(g2, 0, hs, pos, g2.length);
        pos += g2.length;

        if (padLen > 0) {
            writeU16(hs, pos, 0x0015);
            writeU16(hs, pos + 2, padLen);
        }

        return hs;
    }

    private static byte[] buildQuicTransportParametersExtension() {
        ByteArrayOutputStream ms = new ByteArrayOutputStream(64);
        // max_idle_timeout (0x01)
        ms.write(0x01);
        ms.write(0x04);
        long idleMs = nextInt(20000, 60001);
        ms.write((int) (0x80 | ((idleMs >>> 24) & 0x3F)));
        ms.write((int) ((idleMs >>> 16) & 0xFF));
        ms.write((int) ((idleMs >>> 8) & 0xFF));
        ms.write((int) (idleMs & 0xFF));

        // initial_max_data (0x04)
        ms.write(0x04);
        ms.write(0x04);
        long maxData = nextInt(4000000, 15000001);
        ms.write((int) (0x80 | ((maxData >>> 24) & 0x3F)));
        ms.write((int) ((maxData >>> 16) & 0xFF));
        ms.write((int) ((maxData >>> 8) & 0xFF));
        ms.write((int) (maxData & 0xFF));

        // initial_max_stream_data_bidi_local (0x05)
        ms.write(0x05);
        ms.write(0x04);
        long maxStreamData = nextInt(1048576, 6291457);
        ms.write((int) (0x80 | ((maxStreamData >>> 24) & 0x3F)));
        ms.write((int) ((maxStreamData >>> 16) & 0xFF));
        ms.write((int) ((maxStreamData >>> 8) & 0xFF));
        ms.write((int) (maxStreamData & 0xFF));

        // initial_max_streams_bidi (0x08)
        ms.write(0x08);
        ms.write(0x02);
        int maxStreams = nextInt(64, 257);
        ms.write(0x40 | ((maxStreams >>> 8) & 0x3F));
        ms.write(maxStreams & 0xFF);

        byte[] body = ms.toByteArray();
        byte[] ext = new byte[4 + body.length];
        writeU16(ext, 0, 0x0039);
        writeU16(ext, 2, body.length);
        System.arraycopy(body, 0, ext, 4, body.length);
        return ext;
    }

    public static byte[] buildPolymorphicContinuationDecoy() {
        return buildPolymorphicContinuationDecoy(null);
    }

    public static byte[] buildPolymorphicContinuationDecoy(String sni) {
        CONTINUATION_DECOYS_BUILT.incrementAndGet();
        String host = (sni == null || sni.trim().isEmpty()) ? pickBenignSni() : sni.trim();
        byte[] hostBytes = host.getBytes(StandardCharsets.US_ASCII);
        if (hostBytes.length > 240) {
            host = pickBenignSni();
            hostBytes = host.getBytes(StandardCharsets.US_ASCII);
        }

        int total = nextInt(192, 385);
        int prefixLen = nextInt(20, 57);
        int sniListLen = 3 + hostBytes.length;
        int sniExtLen = 2 + sniListLen;
        int minRequired = prefixLen + 4 + sniExtLen + 16;
        if (total < minRequired) total = minRequired;

        byte[] b = new byte[total];
        fillRandomBytes(b, 0, prefixLen);
        for (int i = 0; i < prefixLen; i++) {
            if (b[i] == 0x00) {
                b[i] = (byte) (0x41 + (i & 0x3F));
            }
        }

        int pos = prefixLen;
        writeU16(b, pos, 0x0000); pos += 2;
        writeU16(b, pos, sniExtLen); pos += 2;
        writeU16(b, pos, sniListLen); pos += 2;
        b[pos++] = 0x00;
        writeU16(b, pos, hostBytes.length); pos += 2;
        System.arraycopy(hostBytes, 0, b, pos, hostBytes.length);
        pos += hostBytes.length;

        if (pos + 4 <= total) {
            int padLen = total - (pos + 4);
            writeU16(b, pos, 0x0015); pos += 2;
            writeU16(b, pos, padLen); pos += 2;
            if (padLen > 0) fillRandomBytes(b, pos, padLen);
        }
        return b;
    }

    public static byte[] buildPolymorphicFakeHttp() {
        return buildPolymorphicFakeHttp(null);
    }

    public static byte[] buildPolymorphicFakeHttp(String host) {
        FAKE_HTTP_BUILT.incrementAndGet();
        String targetHost = (host == null || host.trim().isEmpty()) ? pickBenignSni() : host.trim();
        String path = FAKE_HTTP_PATHS[nextInt(0, FAKE_HTTP_PATHS.length)];
        if (nextInt(0, 2) == 0) {
            path += String.format(Locale.US, "?r=%06x", nextUInt64() & 0xFFFFFFL);
        }

        List<String> headers = new ArrayList<>(5);
        headers.add("Host: " + targetHost);
        headers.add("User-Agent: " + pickBrowserUserAgent());
        headers.add("Accept: */*");
        headers.add("Accept-Language: " + ACCEPT_LANGUAGES[nextInt(0, ACCEPT_LANGUAGES.length)]);
        headers.add("Connection: keep-alive");

        for (int i = headers.size() - 1; i > 1; i--) {
            int j = nextInt(1, i + 1);
            Collections.swap(headers, i, j);
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
        for (int i = 0; i < headers.size(); i++) {
            sb.append(headers.get(i)).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Builds a genuine RFC 9001 QUIC v1 Initial packet:
     * HKDF-SHA256 initial keys + AES-128-GCM encrypted CRYPTO/PADDING frames + AES-128-ECB header protection.
     * Total length is randomized between 1200 and 1280 bytes with zero plaintext TLS headers on the wire.
     */
    public static byte[] buildPolymorphicQuicInitial() {
        return buildPolymorphicQuicInitial(null);
    }

    public static byte[] buildPolymorphicQuicInitial(String sni) {
        QUIC_INITIALS_BUILT.incrementAndGet();

        int dcidLen = nextInt(8, 17);
        int scidLen = (nextInt(0, 4) == 0) ? 0 : nextInt(4, 9);
        int pnLenChoice = nextInt(0, 3);
        int pnLen = pnLenChoice == 0 ? 1 : (pnLenChoice == 1 ? 2 : 4);
        long pn = pnLen == 1
                ? nextInt(0, 32)
                : (pnLen == 2 ? nextInt(0, 4096) : nextInt(0, 65536));

        byte[] dcid = new byte[dcidLen];
        fillRandomBytes(dcid, 0, dcidLen);
        byte[] scid = scidLen > 0 ? new byte[scidLen] : null;
        if (scid != null) fillRandomBytes(scid, 0, scidLen);

        byte[] chHs = buildTlsHandshakeClientHello(sni, true, 700);
        int cryptoLenVarintBytes = chHs.length >= 64 ? 2 : 1;
        int minTotal = 1200;
        int maxTotal = 1280;
        int totalLen = nextInt(minTotal, maxTotal + 1);

        int headerNoPnLen = 1 + 4 + 1 + dcidLen + 1 + scidLen + 1 + 2;
        int aadLen = headerNoPnLen + pnLen;
        int ciphertextLen = totalLen - aadLen - 16;
        int cryptoFrameLen = 1 + 1 + cryptoLenVarintBytes + chHs.length;
        if (ciphertextLen < cryptoFrameLen) {
            totalLen = Math.max(1200, aadLen + cryptoFrameLen + 16);
            ciphertextLen = totalLen - aadLen - 16;
        }

        byte[] pkt = new byte[totalLen];
        int p = 0;
        pkt[p++] = (byte) (0xC0 | ((pnLen - 1) & 0x03));
        pkt[p++] = 0x00; pkt[p++] = 0x00; pkt[p++] = 0x00; pkt[p++] = 0x01;
        pkt[p++] = (byte) dcidLen;
        System.arraycopy(dcid, 0, pkt, p, dcidLen);
        p += dcidLen;
        pkt[p++] = (byte) scidLen;
        if (scidLen > 0) {
            System.arraycopy(scid, 0, pkt, p, scidLen);
            p += scidLen;
        }
        pkt[p++] = 0x00; // Token length = 0

        int quicPayloadFieldLen = pnLen + ciphertextLen + 16;
        pkt[p++] = (byte) (0x40 | ((quicPayloadFieldLen >>> 8) & 0x3F));
        pkt[p++] = (byte) (quicPayloadFieldLen & 0xFF);

        int pnOffset = p;
        for (int i = 0; i < pnLen; i++) {
            int shift = (pnLen - 1 - i) * 8;
            pkt[p++] = (byte) ((pn >>> shift) & 0xFF);
        }

        byte[] plaintext = new byte[ciphertextLen];
        int ptPos = 0;
        plaintext[ptPos++] = 0x06; // CRYPTO
        plaintext[ptPos++] = 0x00; // Offset = 0
        if (cryptoLenVarintBytes == 2) {
            plaintext[ptPos++] = (byte) (0x40 | ((chHs.length >>> 8) & 0x3F));
            plaintext[ptPos++] = (byte) (chHs.length & 0xFF);
        } else {
            plaintext[ptPos++] = (byte) (chHs.length & 0x3F);
        }
        System.arraycopy(chHs, 0, plaintext, ptPos, chHs.length);

        try {
            byte[][] keys = deriveQuicV1ClientInitialKeys(dcid);
            byte[] quicKey = keys[0];
            byte[] quicIv = keys[1];
            byte[] quicHp = keys[2];

            byte[] nonce = buildQuicNonce(quicIv, pn);
            byte[] aad = Arrays.copyOfRange(pkt, 0, aadLen);

            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(quicKey, "AES"), new GCMParameterSpec(128, nonce));
            gcm.updateAAD(aad);
            byte[] ctWithTag = gcm.doFinal(plaintext);
            System.arraycopy(ctWithTag, 0, pkt, aadLen, ciphertextLen + 16);

            // RFC 9001 §5.4 Header Protection
            int sampleOffset = pnOffset + 4;
            byte[] mask = aes128EcbSingleBlock(quicHp, pkt, sampleOffset);
            pkt[0] ^= (byte) (mask[0] & 0x0F);
            for (int i = 0; i < pnLen; i++) {
                pkt[pnOffset + i] ^= mask[1 + i];
            }
        } catch (Exception e) {
            throw new IllegalStateException("QUIC Initial AEAD encrypt failure", e);
        }

        return pkt;
    }

    public static final class QuicVerificationResult {
        public final boolean verified;
        public final byte[] decryptedPayload;
        public final String extractedSni;

        public QuicVerificationResult(boolean verified, byte[] decryptedPayload, String extractedSni) {
            this.verified = verified;
            this.decryptedPayload = decryptedPayload;
            this.extractedSni = extractedSni;
        }
    }

    public static QuicVerificationResult tryDecryptQuicInitialForVerification(byte[] packet, int len) {
        if (packet == null || len < 1200 || len > packet.length) {
            return new QuicVerificationResult(false, null, null);
        }
        if ((packet[0] & 0xF0) != 0xC0) {
            return new QuicVerificationResult(false, null, null);
        }
        if (packet[1] != 0x00 || packet[2] != 0x00 || packet[3] != 0x00 || packet[4] != 0x01) {
            return new QuicVerificationResult(false, null, null);
        }

        int[] posRef = {5};
        int dcidLen = packet[posRef[0]++] & 0xFF;
        if (dcidLen > 20 || posRef[0] + dcidLen >= len) {
            return new QuicVerificationResult(false, null, null);
        }
        byte[] dcid = Arrays.copyOfRange(packet, posRef[0], posRef[0] + dcidLen);
        posRef[0] += dcidLen;

        if (posRef[0] >= len) return new QuicVerificationResult(false, null, null);
        int scidLen = packet[posRef[0]++] & 0xFF;
        if (scidLen > 20 || posRef[0] + scidLen >= len) {
            return new QuicVerificationResult(false, null, null);
        }
        posRef[0] += scidLen;

        long tokenLen = readQuicVarInt(packet, posRef, len);
        if (tokenLen < 0 || tokenLen > (len - posRef[0])) {
            return new QuicVerificationResult(false, null, null);
        }
        posRef[0] += (int) tokenLen;

        long payloadFieldLen = readQuicVarInt(packet, posRef, len);
        if (payloadFieldLen < 20 || payloadFieldLen > (len - posRef[0])) {
            return new QuicVerificationResult(false, null, null);
        }

        int pnOffset = posRef[0];
        int sampleOffset = pnOffset + 4;
        if (sampleOffset + 16 > len) {
            return new QuicVerificationResult(false, null, null);
        }

        try {
            byte[][] keys = deriveQuicV1ClientInitialKeys(dcid);
            byte[] quicKey = keys[0];
            byte[] quicIv = keys[1];
            byte[] quicHp = keys[2];

            byte[] mask = aes128EcbSingleBlock(quicHp, packet, sampleOffset);
            byte firstByte = (byte) (packet[0] ^ (mask[0] & 0x0F));
            if ((firstByte & 0xF0) != 0xC0) {
                return new QuicVerificationResult(false, null, null);
            }
            int pnLen = (firstByte & 0x03) + 1;
            if (payloadFieldLen < (pnLen + 16) || pnOffset + pnLen > len) {
                return new QuicVerificationResult(false, null, null);
            }

            byte[] aad = Arrays.copyOfRange(packet, 0, pnOffset + pnLen);
            aad[0] = firstByte;
            long pn = 0L;
            for (int i = 0; i < pnLen; i++) {
                byte pb = (byte) (packet[pnOffset + i] ^ mask[1 + i]);
                aad[pnOffset + i] = pb;
                pn = (pn << 8) | (pb & 0xFFL);
            }

            int ctOffset = pnOffset + pnLen;
            int ctLen = (int) payloadFieldLen - pnLen - 16;
            if (ctLen < 0 || ctOffset + ctLen + 16 > len) {
                return new QuicVerificationResult(false, null, null);
            }

            byte[] nonce = buildQuicNonce(quicIv, pn);
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(quicKey, "AES"), new GCMParameterSpec(128, nonce));
            gcm.updateAAD(aad);
            byte[] pt = gcm.doFinal(packet, ctOffset, ctLen + 16);

            String sni = extractSniFromDecryptedQuicFrames(pt);
            QUIC_DECRYPT_VERIFIED.incrementAndGet();
            return new QuicVerificationResult(true, pt, sni);
        } catch (Exception e) {
            return new QuicVerificationResult(false, null, null);
        }
    }

    private static String extractSniFromDecryptedQuicFrames(byte[] frames) {
        if (frames == null || frames.length == 0) return null;
        int[] posRef = {0};
        while (posRef[0] < frames.length) {
            int ftype = frames[posRef[0]++] & 0xFF;
            if (ftype == 0x00 || ftype == 0x01) continue;
            if (ftype == 0x06) {
                long cOff = readQuicVarInt(frames, posRef, frames.length);
                if (cOff < 0) break;
                long cLen = readQuicVarInt(frames, posRef, frames.length);
                if (cLen < 0 || cLen > (frames.length - posRef[0])) break;
                String sni = extractSniFromTlsHandshakeOrRecord(frames, posRef[0], (int) cLen);
                if (sni != null && !sni.isEmpty()) return sni;
                posRef[0] += (int) cLen;
                continue;
            }
            break;
        }
        return null;
    }

    private static String extractSniFromTlsHandshakeOrRecord(byte[] buf, int offset, int len) {
        if (buf == null || len < 44 || offset + len > buf.length) return null;
        if (buf[offset] == 0x16 && buf[offset + 1] == 0x03) {
            return ColgramTlsMimic.extractSniHostname(buf, offset + len);
        }
        if (buf[offset] != 0x01) return null;

        int limit = offset + len;
        int pos = offset + 4 + 2 + 32;
        if (pos >= limit) return null;
        int sidLen = buf[pos++] & 0xFF;
        pos += sidLen;
        if (pos + 2 > limit) return null;
        int csLen = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
        pos += 2 + csLen;
        if (pos >= limit) return null;
        int compLen = buf[pos++] & 0xFF;
        pos += compLen;
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
                        return new String(buf, p, nameLen, StandardCharsets.US_ASCII).toLowerCase(Locale.US);
                    }
                    p += nameLen;
                }
            }
            pos += extLen;
        }
        return null;
    }

    private static long readQuicVarInt(byte[] buf, int[] posRef, int limit) {
        int pos = posRef[0];
        if (pos >= limit) return -1L;
        int first = buf[pos] & 0xFF;
        int prefix = first >>> 6;
        int byteLen = 1 << prefix;
        if (pos + byteLen > limit) return -1L;
        long value = first & 0x3FL;
        for (int i = 1; i < byteLen; i++) {
            value = (value << 8) | (buf[pos + i] & 0xFFL);
        }
        posRef[0] = pos + byteLen;
        return value;
    }

    private static byte[][] deriveQuicV1ClientInitialKeys(byte[] dcid) throws Exception {
        byte[] initialSecret = hkdfExtractSha256(QUIC_V1_INITIAL_SALT, dcid);
        byte[] clientSecret = hkdfExpandLabelSha256(initialSecret, LABEL_CLIENT_IN, 32);
        byte[] quicKey = hkdfExpandLabelSha256(clientSecret, LABEL_QUIC_KEY, 16);
        byte[] quicIv = hkdfExpandLabelSha256(clientSecret, LABEL_QUIC_IV, 12);
        byte[] quicHp = hkdfExpandLabelSha256(clientSecret, LABEL_QUIC_HP, 16);
        return new byte[][]{quicKey, quicIv, quicHp};
    }

    private static byte[] hkdfExtractSha256(byte[] salt, byte[] ikm) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        return hmac.doFinal(ikm);
    }

    private static byte[] hkdfExpandLabelSha256(byte[] prk, byte[] fullTls13Label, int length) throws Exception {
        byte[] info = new byte[2 + 1 + fullTls13Label.length + 1];
        info[0] = (byte) ((length >>> 8) & 0xFF);
        info[1] = (byte) (length & 0xFF);
        info[2] = (byte) fullTls13Label.length;
        System.arraycopy(fullTls13Label, 0, info, 3, fullTls13Label.length);
        info[3 + fullTls13Label.length] = 0x00;

        byte[] okm = new byte[length];
        byte[] prev = new byte[0];
        int offset = 0;
        byte counter = 1;
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
        while (offset < length) {
            hmac.update(prev);
            hmac.update(info);
            hmac.update(counter++);
            prev = hmac.doFinal();
            int take = Math.min(prev.length, length - offset);
            System.arraycopy(prev, 0, okm, offset, take);
            offset += take;
        }
        return okm;
    }

    private static byte[] buildQuicNonce(byte[] quicIv, long pn) {
        byte[] nonce = Arrays.copyOf(quicIv, 12);
        for (int i = 0; i < 8; i++) {
            nonce[11 - i] ^= (byte) ((pn >>> (i * 8)) & 0xFFL);
        }
        return nonce;
    }

    private static byte[] aes128EcbSingleBlock(byte[] key, byte[] input, int inputOffset) throws Exception {
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return ecb.doFinal(input, inputOffset, 16);
    }

    public static byte[] buildPolymorphicVoiceUdpDecoy() {
        VOICE_DECOYS_BUILT.incrementAndGet();
        int len = nextInt(36, 73);
        byte[] buf = new byte[len];
        fillRandomBytes(buf, 0, len);
        buf[0] = (byte) (0x80 | (buf[0] & 0x3F));
        if (buf[4] == 0x21 && buf[5] == 0x12 && buf[6] == (byte) 0xA4 && buf[7] == 0x42) {
            buf[4] ^= 0x37;
        }
        return buf;
    }

    public static int pickRandomQuicGreaseVersion() {
        int n0 = (int) (nextUInt64() & 0x0FL);
        int n1 = (int) (nextUInt64() & 0x0FL);
        int n2 = (int) (nextUInt64() & 0x0FL);
        int n3 = (int) (nextUInt64() & 0x0FL);
        return (n0 << 28) | 0x0A000000
                | (n1 << 20) | 0x000A0000
                | (n2 << 12) | 0x00000A00
                | (n3 << 4) | 0x0000000A;
    }

    public static void randomizeHttpHostHeaderCase(byte[] buf, int hostOffset) {
        if (buf == null || hostOffset < 0) return;
        int start = hostOffset;
        if (start + 5 < buf.length && buf[start] == '\n'
                && (buf[start + 1] == 'H' || buf[start + 1] == 'h')) {
            start++;
        }
        if (start + 4 > buf.length) return;
        if ((buf[start] != 'H' && buf[start] != 'h')
                || (buf[start + 1] != 'O' && buf[start + 1] != 'o')
                || (buf[start + 2] != 'S' && buf[start + 2] != 's')
                || (buf[start + 3] != 'T' && buf[start + 3] != 't')) {
            return;
        }
        int mask = HOST_CASE_MASKS[nextInt(0, HOST_CASE_MASKS.length)];
        buf[start] = (byte) ((mask & 0x08) != 0 ? 'H' : 'h');
        buf[start + 1] = (byte) ((mask & 0x04) != 0 ? 'O' : 'o');
        buf[start + 2] = (byte) ((mask & 0x02) != 0 ? 'S' : 's');
        buf[start + 3] = (byte) ((mask & 0x01) != 0 ? 'T' : 't');
    }

    public static int jitterMicroFlushUs(int baseUs) {
        if (baseUs <= 0) return 0;
        int min = Math.max(50, (baseUs * 3) / 4);
        int max = Math.max(min + 1, (baseUs * 5) / 4);
        return nextInt(min, max + 1);
    }

    public static int jitterSplitOffset(int baseOffset, int minInclusive, int maxInclusive) {
        if (minInclusive >= maxInclusive) return minInclusive;
        int clampedBase = baseOffset;
        if (clampedBase < minInclusive) clampedBase = minInclusive;
        if (clampedBase > maxInclusive) clampedBase = maxInclusive;
        int lo = Math.max(minInclusive, clampedBase - 2);
        int hi = Math.min(maxInclusive, clampedBase + 2);
        return nextInt(lo, hi + 1);
    }

    public static byte nextNonZeroOobByte() {
        return (byte) nextInt(0x01, 0x7F);
    }

    public static int nextBadAckDelta() {
        return nextInt(48000, 92001);
    }

    public static int nextBadSeqDelta() {
        return nextInt(6000, 18001);
    }

    public static int nextBadTcpTsDelta(boolean large) {
        return large ? nextInt(350000, 950001) : nextInt(400, 3201);
    }

    public static int nextNonZeroChecksumMask() {
        return nextInt(1, 65536);
    }

    public static void registerBypassOwnedPort(int port) {
        if (port <= 0 || port > 65535) return;
        PORT_RELEASE_DEADLINES.remove(port);
        BYPASS_OWNED_LOCAL_PORTS.put(port, 1);
        TRACKED_PORTS_TOTAL.incrementAndGet();
    }

    public static void releaseBypassOwnedPort(int port, int graceSeconds) {
        if (port <= 0 || port > 65535) return;
        if (graceSeconds > 0) {
            PORT_RELEASE_DEADLINES.put(port, System.currentTimeMillis() + (graceSeconds * 1000L));
        } else {
            PORT_RELEASE_DEADLINES.remove(port);
        }
        BYPASS_OWNED_LOCAL_PORTS.remove(port);
        if (PORT_RELEASE_DEADLINES.size() > 512) {
            long now = System.currentTimeMillis();
            for (Iterator<Map.Entry<Integer, Long>> it = PORT_RELEASE_DEADLINES.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Long> e = it.next();
                if (e.getValue() <= now) {
                    it.remove();
                }
            }
        }
    }

    public static boolean isBypassOwnedLocalPort(int port) {
        if (port <= 0 || port > 65535) return false;
        if (BYPASS_OWNED_LOCAL_PORTS.containsKey(port)) return true;
        Long deadline = PORT_RELEASE_DEADLINES.get(port);
        if (deadline != null) {
            if (deadline > System.currentTimeMillis()) {
                return true;
            }
            PORT_RELEASE_DEADLINES.remove(port, deadline);
        }
        return false;
    }

    private static void writeU16(byte[] buf, int off, int val) {
        buf[off] = (byte) ((val >>> 8) & 0xFF);
        buf[off + 1] = (byte) (val & 0xFF);
    }
}
