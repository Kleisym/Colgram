package org.colgram.core;

import java.util.Arrays;

/**
 * Pure-JVM regression tests covering Bot API chat ID resolution, GIF query expansion,
 * and core safety invariants.
 */
public final class ColgramCoreRegressionTest {

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void checkArrays(long[] expected, long[] actual, String msg) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(msg + " expected=" + Arrays.toString(expected) + " actual=" + Arrays.toString(actual));
        }
    }

    public static void main(String[] args) {
        // 1. Bot API chat_id resolution across private, modern basic groups (>1e9), small channels (<=1e9), and ambiguous hints
        checkArrays(new long[]{123456789L},
                ColgramBotChatIdPolicy.resolveChatIds(123456789L, null),
                "private user dialog keeps positive id");
        checkArrays(new long[]{-4500000000L},
                ColgramBotChatIdPolicy.resolveChatIds(-4500000000L, Boolean.FALSE),
                "modern basic group (>1e9) with isChannel=false must NOT get -100 prefix");
        checkArrays(new long[]{-1000000123456L},
                ColgramBotChatIdPolicy.resolveChatIds(-123456L, Boolean.TRUE),
                "small channel/supergroup (<=1e9) with isChannel=true gets -100 prefix");
        checkArrays(new long[]{-1004500000000L, -4500000000L},
                ColgramBotChatIdPolicy.resolveChatIds(-4500000000L, null),
                "unknown chat type returns supergroup candidate then basic group fallback");

        // 2. Russian GIF query expansion must not false-match short 2-3 char keys
        String far = ColgramGifSearch.expandSearchQuery("далеко");
        check("далеко".equals(far), "далеко must not match 'да' -> 'yes', got=" + far);
        String wood = ColgramGifSearch.expandSearchQuery("древесина");
        check("древесина".equals(wood), "древесина must not match 'др' -> 'happy birthday', got=" + wood);
        String dragons = ColgramGifSearch.expandSearchQuery("драконы");
        check("dragon".equals(dragons), "драконы should match 'дракон' -> 'dragon', not 'др', got=" + dragons);
        String cat = ColgramGifSearch.expandSearchQuery("кот");
        check(cat.toLowerCase().contains("cat"), "exact short keyword 'кот' still expands to cat, got=" + cat);
        String fire = ColgramGifSearch.expandSearchQuery("огонь");
        check(fire.toLowerCase().contains("fire"), "keyword 'огонь' expands to fire, got=" + fire);

        // 3. ColgramDesignConfig clamping, HEX parsing, JSON round-trip, and reset
        ColgramDesignConfig.resetAll();
        check(ColgramDesignConfig.parseHexColor("#00E5FF") == 0xFF00E5FF, "hex parse #00E5FF");
        check(ColgramDesignConfig.parseHexColor("zzzzzz") == 0, "invalid hex returns 0");
        ColgramDesignConfig.setAccentColor(0xFF06B6D4);
        ColgramDesignConfig.setCornerRadiusDp(99);
        check(ColgramDesignConfig.getCornerRadiusDp() == ColgramDesignConfig.MAX_CORNER_DP, "corner radius clamps max");
        ColgramDesignConfig.setKolitiOrbSizeDp(10);
        check(ColgramDesignConfig.getKolitiOrbSizeDp() == ColgramDesignConfig.MIN_ORB_SIZE_DP, "orb size clamps min");
        ColgramDesignConfig.setKolitiOrbSizeDp(70);
        ColgramDesignConfig.setCustomHeaderTitle("MyColgram");
        ColgramDesignConfig.setKolitiCustomName("Nova");
        String exported = ColgramDesignConfig.exportJson();
        ColgramDesignConfig.resetAll();
        check("Colgram".equals(ColgramDesignConfig.getEffectiveHeaderTitle()), "reset restores header title");
        check(ColgramDesignConfig.importJson(exported), "importJson succeeds");
        check(ColgramDesignConfig.getAccentColor() == 0xFF06B6D4, "imported accent matches");
        check(ColgramDesignConfig.getKolitiOrbSizeDp() == 70, "imported orb size matches");
        check("MyColgram".equals(ColgramDesignConfig.getEffectiveHeaderTitle()), "imported header title matches");
        check("Nova".equals(ColgramDesignConfig.getKolitiDisplayName()), "imported Koliti name matches");
        ColgramDesignConfig.resetAll();

        // 4. ColgramDpiBypass TLS SNI hostname parser finds exact SNI byte span
        byte[] hostBytes = "www.youtube.com".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int sniPayloadLen = 2 + 1 + 2 + hostBytes.length;
        int extBlockLen = 4 + sniPayloadLen;
        int chBodyLen = 2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + extBlockLen;
        int recLen = 4 + chBodyLen;
        byte[] ch = new byte[5 + recLen];
        int p = 0;
        ch[p++] = 0x16; ch[p++] = 0x03; ch[p++] = 0x01;
        ch[p++] = (byte) (recLen >> 8); ch[p++] = (byte) recLen;
        ch[p++] = 0x01; ch[p++] = 0x00;
        ch[p++] = (byte) (chBodyLen >> 8); ch[p++] = (byte) chBodyLen;
        ch[p++] = 0x03; ch[p++] = 0x03;
        p += 32; // random
        ch[p++] = 0; // session id len
        ch[p++] = 0; ch[p++] = 2; ch[p++] = 0x13; ch[p++] = 0x01; // 1 cipher suite
        ch[p++] = 1; ch[p++] = 0; // compression
        ch[p++] = (byte) (extBlockLen >> 8); ch[p++] = (byte) extBlockLen;
        ch[p++] = 0x00; ch[p++] = 0x00; // SNI ext type
        ch[p++] = (byte) (sniPayloadLen >> 8); ch[p++] = (byte) sniPayloadLen;
        int listLen = 1 + 2 + hostBytes.length;
        ch[p++] = (byte) (listLen >> 8); ch[p++] = (byte) listLen;
        ch[p++] = 0x00; // host_name
        ch[p++] = (byte) (hostBytes.length >> 8); ch[p++] = (byte) hostBytes.length;
        int expectedStart = p;
        System.arraycopy(hostBytes, 0, ch, p, hostBytes.length);
        int[] span = ColgramDpiBypass.findTlsSniSpan(ch, ch.length);
        check(span != null && span[0] == expectedStart && span[1] == expectedStart + hostBytes.length,
                "findTlsSniSpan locates www.youtube.com exactly");
        check("www.youtube.com".equals(ColgramTlsMimic.extractSniHostname(ch, ch.length)),
                "extractSniHostname extracts www.youtube.com");

        // 4b. Modern Post-Quantum X25519MLKEM768 TLS 1.3 ClientHello (~1400+ bytes, keyshare BEFORE SNI!)
        byte[] pqHostBytes = "rr3---sn-test.googlevideo.com".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int pqKeyShareBodyLen = 1216;
        int pqSniPayloadLen = 2 + 1 + 2 + pqHostBytes.length;
        int pqExtBlockLen = (4 + pqKeyShareBodyLen) + (4 + pqSniPayloadLen);
        int pqChBodyLen = 2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + pqExtBlockLen;
        int pqRecLen = 4 + pqChBodyLen;
        byte[] pqCh = new byte[5 + pqRecLen];
        int q = 0;
        pqCh[q++] = 0x16; pqCh[q++] = 0x03; pqCh[q++] = 0x01;
        pqCh[q++] = (byte) (pqRecLen >> 8); pqCh[q++] = (byte) pqRecLen;
        pqCh[q++] = 0x01; pqCh[q++] = 0x00;
        pqCh[q++] = (byte) (pqChBodyLen >> 8); pqCh[q++] = (byte) pqChBodyLen;
        pqCh[q++] = 0x03; pqCh[q++] = 0x03;
        q += 32; // random
        pqCh[q++] = 0; // session id
        pqCh[q++] = 0; pqCh[q++] = 2; pqCh[q++] = 0x13; pqCh[q++] = 0x01;
        pqCh[q++] = 1; pqCh[q++] = 0;
        pqCh[q++] = (byte) (pqExtBlockLen >> 8); pqCh[q++] = (byte) pqExtBlockLen;
        // KeyShare extension (0x0033) of 1216 bytes BEFORE SNI
        pqCh[q++] = 0x00; pqCh[q++] = 0x33;
        pqCh[q++] = (byte) (pqKeyShareBodyLen >> 8); pqCh[q++] = (byte) pqKeyShareBodyLen;
        q += pqKeyShareBodyLen;
        // SNI extension (0x0000) AFTER the 1216-byte ML-KEM768 keyshare
        pqCh[q++] = 0x00; pqCh[q++] = 0x00;
        pqCh[q++] = (byte) (pqSniPayloadLen >> 8); pqCh[q++] = (byte) pqSniPayloadLen;
        int pqListLen = 1 + 2 + pqHostBytes.length;
        pqCh[q++] = (byte) (pqListLen >> 8); pqCh[q++] = (byte) pqListLen;
        pqCh[q++] = 0x00;
        pqCh[q++] = (byte) (pqHostBytes.length >> 8); pqCh[q++] = (byte) pqHostBytes.length;
        int pqExpectedStart = q;
        System.arraycopy(pqHostBytes, 0, pqCh, q, pqHostBytes.length);

        check(pqCh.length > 1280, "PQ ClientHello exceeds old 1034B cap: " + pqCh.length);
        check(ColgramTlsMimic.expectedClientHelloLength(pqCh, pqCh.length) == pqCh.length,
                "expectedClientHelloLength matches full PQ ClientHello");
        int[] pqSpan = ColgramTlsMimic.findTlsSniSpan(pqCh, pqCh.length);
        check(pqSpan != null && pqSpan[0] == pqExpectedStart && pqSpan[1] == pqExpectedStart + pqHostBytes.length,
                "findTlsSniSpan finds SNI after 1216B ML-KEM768 keyshare");
        check("rr3---sn-test.googlevideo.com".equals(ColgramTlsMimic.extractSniHostname(pqCh, pqCh.length)),
                "extractSniHostname extracts PQ SNI");
        check("googlevideo.com".equals(ColgramDpiBypass.baseDomainKey("rr3---sn-test.googlevideo.com")),
                "baseDomainKey collapses YouTube CDN subdomain");

        // Verify 2-record & 3-record RFC-valid TLS SNI splits preserve exact handshake payload and hide full SNI
        for (int parts : new int[]{2, 3}) {
            byte[][] recs = ColgramTlsMimic.splitTlsRecordAtSni(pqCh, parts);
            check(recs != null && recs.length == parts, "splitTlsRecordAtSni parts=" + parts);
            java.io.ByteArrayOutputStream reassembled = new java.io.ByteArrayOutputStream();
            for (byte[] rec : recs) {
                check(rec.length > 5 && rec[0] == 0x16 && rec[1] == 0x03, "valid TLS record header");
                int rLen = ((rec[3] & 0xFF) << 8) | (rec[4] & 0xFF);
                check(rLen + 5 == rec.length, "TLS record length matches fragment");
                String pieceStr = new String(rec, java.nio.charset.StandardCharsets.ISO_8859_1);
                check(!pieceStr.contains("rr3---sn-test.googlevideo.com"),
                        "no single TLS record contains complete SNI hostname");
                reassembled.write(rec, 5, rLen);
            }
            byte[] origPayload = Arrays.copyOfRange(pqCh, 5, pqCh.length);
            check(Arrays.equals(origPayload, reassembled.toByteArray()),
                    "concatenated TLS record payloads match original PQ ClientHello byte-for-byte");
        }

        // Verify byte-preserving TCP fragment() slices through PQ SNI
        byte[][] tcpSegs = ColgramTlsMimic.fragment(pqCh, new java.util.Random(42));
        check(tcpSegs.length == 3, "fragment() produces 3 TCP segments for TLS with SNI");
        java.io.ByteArrayOutputStream tcpReassembled = new java.io.ByteArrayOutputStream();
        for (byte[] seg : tcpSegs) {
            String s = new String(seg, java.nio.charset.StandardCharsets.ISO_8859_1);
            check(!s.contains("rr3---sn-test.googlevideo.com"), "no single TCP segment contains full SNI");
            tcpReassembled.write(seg, 0, seg.length);
        }
        check(Arrays.equals(pqCh, tcpReassembled.toByteArray()), "fragment() preserves exact bytes for FakeTLS/TLS");

        // Verify all 14 ColgramDpiBypass.sendDesynced strategies succeed on PQ ClientHello
        for (int strat = 0; strat < 14; strat++) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try {
                ColgramDpiBypass.sendDesynced(null, out, pqCh, pqCh.length, strat);
            } catch (Exception e) {
                throw new AssertionError("sendDesynced failed for strategy " + strat, e);
            }
            check(out.size() >= pqCh.length, "sendDesynced strategy " + strat + " wrote full ClientHello");
        }

        // Verify whitelisted Russian fake ClientHello decoy builder + fatal alert & ISP redirect detectors
        for (int variant = 0; variant < 4; variant++) {
            byte[] fake = ColgramTlsMimic.buildFakeWhitelistedClientHello(200, variant);
            check(fake.length == 200, "fake ClientHello matches requested 200B length: " + fake.length);
            check(ColgramTlsMimic.expectedClientHelloLength(fake, fake.length) == fake.length,
                    "fake ClientHello has valid TLS record length");
            int[] fSpan = ColgramTlsMimic.findTlsSniSpan(fake, fake.length);
            String fSni = ColgramTlsMimic.extractSniHostname(fake, fake.length);
            check(fSpan != null && fSpan[1] <= 110 && fSni != null && !fSni.isEmpty(),
                    "fake ClientHello places whitelisted SNI early inside segment: " + fSni);
        }
        check(ColgramTlsMimic.looksLikeFatalTlsAlert(new byte[]{0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28}, 7),
                "detects fatal TLS alert");
        check(!ColgramTlsMimic.looksLikeFatalTlsAlert(new byte[]{0x16, 0x03, 0x03, 0x00, 0x40, 0x02, 0x00}, 7),
                "does not flag ServerHello as fatal alert");
        byte[] ispBlock = "HTTP/1.1 302 Found\r\nLocation: http://warning.rt.ru/?id=1\r\n\r\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] normalRedir = "HTTP/1.1 302 Found\r\nLocation: https://www.youtube.com/\r\n\r\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        check(ColgramTlsMimic.looksLikeIspBlockRedirect(ispBlock, ispBlock.length), "detects ISP block redirect");
        check(!ColgramTlsMimic.looksLikeIspBlockRedirect(normalRedir, normalRedir.length), "allows normal HTTP 302");

        // Verify -r1+s (exact SNI+1 2-record split) and HTTP Host header mangling
        byte[][] r1s = ColgramTlsMimic.splitTlsRecordAtSniOffset(pqCh, 1);
        check(r1s != null && r1s.length == 2, "splitTlsRecordAtSniOffset(pqCh, 1) produces 2 RFC-valid TLS records");
        byte[] reassembledR1s = new byte[(r1s[0].length - 5) + (r1s[1].length - 5)];
        System.arraycopy(r1s[0], 5, reassembledR1s, 0, r1s[0].length - 5);
        System.arraycopy(r1s[1], 5, reassembledR1s, r1s[0].length - 5, r1s[1].length - 5);
        check(Arrays.equals(reassembledR1s, Arrays.copyOfRange(pqCh, 5, pqCh.length)),
                "-r1+s 2-record TLS split preserves every handshake byte verbatim");

        byte[] httpReq = "GET / HTTP/1.1\r\nHost: youtube.com\r\nUser-Agent: test\r\n\r\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] mangledHttp = ColgramTlsMimic.mangleHttpHostHeader(httpReq, httpReq.length);
        String mangledStr = new String(mangledHttp, java.nio.charset.StandardCharsets.US_ASCII);
        check(mangledHttp.length == httpReq.length && mangledStr.contains("\r\nhoSt: youtube.com\r\n"),
                "mangleHttpHostHeader rewrites Host: -> hoSt: preserving exact byte length");
        check(ColgramTlsMimic.mangleHttpHostHeader(pqCh, pqCh.length) == pqCh,
                "mangleHttpHostHeader leaves non-HTTP TLS traffic untouched");

        // Verify native payload preparation & cellular vs Wi-Fi Wave 1/2/3 replay plans
        ColgramNativeDesync.PreparedDesync prep =
                ColgramNativeDesync.prepareTlsOrRawPayload(pqCh, 0, pqCh.length, true, true, true, 0);
        check(prep.payload.length == pqCh.length + 10,
                "3-record SNI+1 & SLD TLS split adds 2 extra 5B record headers: " + (prep.payload.length - pqCh.length));
        check(prep.split1 > 100 && prep.split1 < prep.payload.length, "valid native split1 offset");
        check(prep.fakePayload != null && prep.fakePayload.length <= prep.split1, "valid decoy fake payload");

        int[] cellPlan = ColgramDpiBypass.buildReplayPlan("youtube.com", true, true, 9);
        check(cellPlan.length == 9
                        && cellPlan[0] == ColgramDpiBypass.STRATEGY_NATIVE_MULTI_SPLIT_TLSREC
                        && cellPlan[1] == ColgramDpiBypass.STRATEGY_TLS_2REC_SNI
                        && cellPlan[2] == ColgramDpiBypass.STRATEGY_NATIVE_OOB1_ONLY_TLSREC,
                "cellular Wave 1 prioritizes carrier-safe multi-split + 2-rec TLS SNI, demoting OOB1 to lane 3");
        int[] wifiPlan = ColgramDpiBypass.buildReplayPlan("youtube.com", false, true, 6);
        check(wifiPlan.length == 6 && wifiPlan[0] == ColgramDpiBypass.STRATEGY_SPLIT_1
                        && wifiPlan[1] == ColgramDpiBypass.STRATEGY_NATIVE_MULTI_SPLIT_TLSREC
                        && wifiPlan[2] == ColgramDpiBypass.STRATEGY_TLS_2REC_SNI,
                "Wi-Fi Wave 1 starts with carrier-safe split, multi-split, and 2-record TLS split");
        check(ColgramDpiBypass.mssClampForStrategy(ColgramDpiBypass.STRATEGY_NATIVE_MSS64_OOB1_TLSREC) == 88
                        && ColgramDpiBypass.mssClampForStrategy(ColgramDpiBypass.STRATEGY_NATIVE_MSS_CLAMP_TLSREC) == 88
                        && ColgramDpiBypass.mssClampForStrategy(ColgramDpiBypass.STRATEGY_NATIVE_MULTI_SPLIT_TLSREC) == 0
                        && ColgramDpiBypass.windowClampForStrategy(ColgramDpiBypass.STRATEGY_NATIVE_MULTI_SPLIT_TLSREC) == 0
                        && ColgramDpiBypass.windowClampForStrategy(ColgramDpiBypass.STRATEGY_NATIVE_OOB1_ONLY_TLSREC) == 0
                        && ColgramDpiBypass.windowClampForStrategy(ColgramDpiBypass.STRATEGY_NATIVE_DISORDER_TLSREC) == 0
                        && ColgramDpiBypass.windowClampForStrategy(ColgramDpiBypass.STRATEGY_NATIVE_OOB_DISORDER_TLSREC) == 0
                        && ColgramDpiBypass.windowClampForStrategy(ColgramDpiBypass.STRATEGY_NATIVE_MSS_CLAMP_TLSREC) == 2048
                        && ColgramDpiBypass.windowClampForStrategy(ColgramDpiBypass.STRATEGY_NATIVE_MSS64_OOB1_TLSREC) == 2048
                        && ColgramDpiBypass.windowClampForStrategy(ColgramDpiBypass.STRATEGY_SPLIT_1) == 0,
                "MSS clamp (>=88, never 64) and TCP_WINDOW_CLAMP (dedicated fallback lanes only) match strategy table");

        // Verify findTlsSniSpan clamps to Record 1 boundary and never bleeds into a 2nd TLS record header
        byte[][] preSplit = ColgramTlsMimic.splitTlsRecordAtSni(pqCh, 2);
        byte[] twoRecCombined = new byte[preSplit[0].length + preSplit[1].length];
        System.arraycopy(preSplit[0], 0, twoRecCombined, 0, preSplit[0].length);
        System.arraycopy(preSplit[1], 0, twoRecCombined, preSplit[0].length, preSplit[1].length);
        check(ColgramTlsMimic.extractSniHostname(twoRecCombined, twoRecCombined.length) == null,
                "findTlsSniSpan does not bleed across an already-split 2-record TLS ClientHello");

        // Verify in-session transparent strategy replay: mock TSPU sends fatal TLS alert on attempt 1,
        // replies valid ServerHello on attempt 2 -> caller gets ServerHello transparently!
        try (java.net.ServerSocket mockDpi = new java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress())) {
            final int dpiPort = mockDpi.getLocalPort();
            final java.util.concurrent.atomic.AtomicInteger connCount = new java.util.concurrent.atomic.AtomicInteger(0);
            final byte[] mockServerHello = new byte[]{0x16, 0x03, 0x03, 0x00, 0x05, 0x02, 0x00, 0x00, 0x01, 0x00};
            final byte[] mockFatalAlert = new byte[]{0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28};
            Thread dpiServer = new Thread(() -> {
                for (int i = 0; i < 2; i++) {
                    try (java.net.Socket s = mockDpi.accept()) {
                        int idx = connCount.incrementAndGet();
                        byte[] sink = new byte[4096];
                        s.setSoTimeout(1000);
                        int r = s.getInputStream().read(sink);
                        if (r > 0) {
                            if (idx == 1) {
                                s.getOutputStream().write(mockFatalAlert);
                            } else {
                                s.getOutputStream().write(mockServerHello);
                            }
                            s.getOutputStream().flush();
                            Thread.sleep(60);
                        }
                    } catch (Exception ignored) {}
                }
            });
            dpiServer.setDaemon(true);
            dpiServer.start();

            java.net.Socket firstConn = new java.net.Socket("127.0.0.1", dpiPort);
            int[] testPlan = new int[]{ColgramDpiBypass.STRATEGY_SPLIT_1, ColgramDpiBypass.STRATEGY_TLS_2REC_SNI};
            ColgramDpiBypass.ReplayOutcome outcome = ColgramDpiBypass.executeReplayPlan(
                    firstConn, false, pqCh, true, testPlan,
                    mss -> {
                        try { return new java.net.Socket("127.0.0.1", dpiPort); }
                        catch (Exception e) { return null; }
                    },
                    null, 1500, 1500, 5000L);
            check(outcome.winningUpstream != null && outcome.attempts == 2
                            && outcome.winningStrategy == ColgramDpiBypass.STRATEGY_TLS_2REC_SNI
                            && outcome.firstReplyLen == mockServerHello.length,
                    "in-session replay transparently recovers from fatal alert on attempt 1");
            try { outcome.winningUpstream.close(); } catch (Exception ignored) {}
        } catch (Exception e) {
            throw new AssertionError("in-session replay loopback test failed", e);
        }

        // Verify 3-way Staggered Parallel Wave Replay:
        // Lane 0 silently blackholes (would stall serial replay for 2500ms!),
        // Lane 1 sends fatal TLS alert, Lane 2 replies ServerHello -> must win in <1200ms!
        try (java.net.ServerSocket mockWaveDpi = new java.net.ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())) {
            final int wavePort = mockWaveDpi.getLocalPort();
            final java.util.concurrent.atomic.AtomicInteger waveConn = new java.util.concurrent.atomic.AtomicInteger(0);
            final byte[] mockServerHello = new byte[]{0x16, 0x03, 0x03, 0x00, 0x05, 0x02, 0x00, 0x00, 0x01, 0x00};
            final byte[] mockFatalAlert = new byte[]{0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x32};
            Thread waveServer = new Thread(() -> {
                for (int i = 0; i < 3; i++) {
                    try {
                        final java.net.Socket s = mockWaveDpi.accept();
                        final int idx = waveConn.incrementAndGet();
                        Thread handler = new Thread(() -> {
                            try (java.net.Socket sock = s) {
                                byte[] sink = new byte[4096];
                                sock.setSoTimeout(3000);
                                int r = sock.getInputStream().read(sink);
                                if (r > 0) {
                                    if (idx == 1) {
                                        // Lane 0: silent TSPU blackhole!
                                        Thread.sleep(2800);
                                    } else if (idx == 2) {
                                        // Lane 1: fatal TLS alert
                                        sock.getOutputStream().write(mockFatalAlert);
                                        sock.getOutputStream().flush();
                                        Thread.sleep(50);
                                    } else {
                                        // Lane 2: ServerHello success!
                                        sock.getOutputStream().write(mockServerHello);
                                        sock.getOutputStream().flush();
                                        Thread.sleep(150);
                                    }
                                }
                            } catch (Exception ignored) {}
                        });
                        handler.setDaemon(true);
                        handler.start();
                    } catch (Exception ignored) {}
                }
            });
            waveServer.setDaemon(true);
            waveServer.start();

            long t0 = System.currentTimeMillis();
            int[] wavePlan = new int[]{
                    ColgramDpiBypass.STRATEGY_SPLIT_1,
                    ColgramDpiBypass.STRATEGY_TLS_2REC_SNI,
                    ColgramDpiBypass.STRATEGY_TLS_3REC_SNI
            };
            ColgramDpiBypass.ReplayOutcome waveOut = ColgramDpiBypass.executeParallelReplayPlan(
                    null, 0, 0, pqCh, true, wavePlan,
                    (mss, wc) -> {
                        try { return new java.net.Socket("127.0.0.1", wavePort); }
                        catch (Exception e) { return null; }
                    },
                    null, 2500, 5000L, false);
            long elapsed = System.currentTimeMillis() - t0;
            check(waveOut.winningUpstream != null
                            && waveOut.winningStrategy == ColgramDpiBypass.STRATEGY_TLS_3REC_SNI
                            && waveOut.firstReplyLen == mockServerHello.length
                            && elapsed < 1500L,
                    "3-way parallel wave beats Lane 0 blackhole & Lane 1 alert in " + elapsed + "ms");
            try { waveOut.winningUpstream.close(); } catch (Exception ignored) {}
        } catch (Exception e) {
            throw new AssertionError("3-way parallel wave replay loopback test failed", e);
        }

        // Verify ColgramLocalDnsProxy AAAA instant-empty response, forwarding, caching & TXID patching
        final java.util.concurrent.atomic.AtomicInteger dnsCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        try (ColgramLocalDnsProxy dnsProxy = new ColgramLocalDnsProxy(true, wireQ -> {
            dnsCalls.incrementAndGet();
            byte[] resp = Arrays.copyOf(wireQ, wireQ.length + 16);
            resp[2] = (byte) 0x81; resp[3] = (byte) 0x80; // NOERROR
            resp[7] = 1; // ANCOUNT=1
            return resp;
        }); java.net.DatagramSocket cli = new java.net.DatagramSocket()) {
            cli.setSoTimeout(2000);
            java.net.InetAddress lo = java.net.InetAddress.getLoopbackAddress();
            // Build minimal AAAA query (qtype=28) for youtube.com
            byte[] qAaaa = new byte[]{
                    0x11, 0x22, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0,
                    7, 'y', 'o', 'u', 't', 'u', 'b', 'e', 3, 'c', 'o', 'm', 0,
                    0x00, 28, 0x00, 0x01
            };
            cli.send(new java.net.DatagramPacket(qAaaa, qAaaa.length, lo, dnsProxy.port()));
            byte[] rBuf = new byte[512];
            java.net.DatagramPacket rp = new java.net.DatagramPacket(rBuf, rBuf.length);
            cli.receive(rp);
            check(rBuf[0] == 0x11 && rBuf[1] == 0x22 && (rBuf[3] & 0x0F) == 0 && rBuf[7] == 0
                            && dnsCalls.get() == 0,
                    "LocalDnsProxy answers AAAA with instant empty NOERROR on IPv4-only TUN");

            // Build A query (qtype=1) TXID 0x3344 -> miss -> upstream called once
            byte[] qA1 = qAaaa.clone();
            qA1[0] = 0x33; qA1[1] = 0x44; qA1[qA1.length - 3] = 1;
            cli.send(new java.net.DatagramPacket(qA1, qA1.length, lo, dnsProxy.port()));
            rp.setLength(rBuf.length);
            cli.receive(rp);
            check(rBuf[0] == 0x33 && rBuf[1] == 0x44 && rBuf[7] == 1 && dnsCalls.get() == 1,
                    "LocalDnsProxy forwards A query and returns answer");

            // Same A query with new TXID 0x5566 -> served from cache with patched TXID
            byte[] qA2 = qA1.clone();
            qA2[0] = 0x55; qA2[1] = 0x66;
            cli.send(new java.net.DatagramPacket(qA2, qA2.length, lo, dnsProxy.port()));
            rp.setLength(rBuf.length);
            cli.receive(rp);
            check(rBuf[0] == 0x55 && rBuf[1] == 0x66 && rBuf[7] == 1 && dnsCalls.get() == 1,
                    "LocalDnsProxy serves cached answer with patched TXID");
        } catch (Exception e) {
            throw new AssertionError("ColgramLocalDnsProxy loopback test failed", e);
        }

        // Verify ColgramInitialPacketReader.readInitialPacket reads split ~1300B PQ ClientHello completely
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            final int port = ss.getLocalPort();
            Thread writer = new Thread(() -> {
                try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
                    s.setTcpNoDelay(true);
                    s.getOutputStream().write(pqCh, 0, 600);
                    s.getOutputStream().flush();
                    Thread.sleep(15);
                    s.getOutputStream().write(pqCh, 600, pqCh.length - 600);
                    s.getOutputStream().flush();
                    Thread.sleep(100);
                } catch (Exception ignored) {}
            });
            writer.setDaemon(true);
            writer.start();
            try (java.net.Socket accepted = ss.accept()) {
                byte[] captured = ColgramInitialPacketReader.readInitialPacket(accepted, 2048, 8192, 120, 300);
                check(Arrays.equals(pqCh, captured), "readInitialPacket captures full fragmented PQ ClientHello");
            }
        } catch (Exception e) {
            throw new AssertionError("readInitialPacket loopback test failed", e);
        }

        // 5. Whole-device Local DPI sing-box profile contains TUN, hijack-dns, udp:443 reject, and resilient DoH
        try {
            String sysDpiJson = ColgramProfileBuilder.forSystemDpiSocks("127.0.0.1", 19876, "u", "p");
            check(sysDpiJson.contains("\"type\":\"tun\""), "system DPI profile has tun inbound");
            check(sysDpiJson.contains("\"action\":\"hijack-dns\""), "system DPI profile hijacks port 53 DNS");
            check(sysDpiJson.contains("\"network\":\"udp\"") && sysDpiJson.contains("\"port\":443") && sysDpiJson.contains("\"action\":\"reject\""),
                    "system DPI profile rejects QUIC udp:443 for instant TCP TLS SNI-split fallback");
            check(sysDpiJson.contains("\"server\":\"94.140.14.14\"") && sysDpiJson.contains("\"server\":\"8.8.8.8\"")
                    && sysDpiJson.contains("\"server\":\"1.1.1.1\"") && sysDpiJson.contains("\"server\":\"9.9.9.9\""),
                    "system DPI profile configures redundant AdGuard/Google/Cloudflare/Quad9 DoH over bypass");
            check(sysDpiJson.contains("\"final\":\"sys-dns-adguard\""),
                    "system DPI profile defaults to uncensored AdGuard DoH over bypass");
            check(sysDpiJson.contains("\"strategy\":\"ipv4_only\"") && !sysDpiJson.contains("fdfe:dcba"),
                    "system DPI profile stays IPv4-only so apps never hit dead IPv6 routes on IPv4 networks");
            String dualStackJson = ColgramProfileBuilder.forSystemDpiSocks("127.0.0.1", 19876, "u", "p", 15353, true);
            check(dualStackJson.contains("fdfe:dcba:9876::1/126")
                            && dualStackJson.contains("\"::/0\"")
                            && dualStackJson.contains("\"strategy\":\"prefer_ipv4\"")
                            && dualStackJson.contains("\"sys-dns-local\""),
                    "dual-stack system DPI profile enables IPv6 TUN + prefer_ipv4 + local DoH proxy");
        } catch (LinkageError | RuntimeException androidStub) {
            // Host JVM runtime omits android.jar org.json; validated natively on Android via libbox.
        }

        // 6. Cellular raw MTProto ladder progression & full-cycle-only dcBackoffUntil
        int[] cellLadder = ColgramDpiBypass.activeLadderFor(true, true);
        check(cellLadder.length >= 10 && cellLadder[0] == ColgramDpiBypass.STRATEGY_NATIVE_MULTI_SPLIT_TLSREC,
                "CELLULAR_LADDER starts with carrier-safe STRATEGY_NATIVE_MULTI_SPLIT_TLSREC");
        ColgramDpiBypass.resetStrategyStateForTest(true, cellLadder[0]);
        // Failing strategy 0 twice must advance cellularPreferredStrategy to cellLadder[1] WITHOUT 8s backoff!
        ColgramDpiBypass.reportStrategyResultWithLadderForTest(cellLadder[0], false, 2, cellLadder);
        ColgramDpiBypass.reportStrategyResultWithLadderForTest(cellLadder[0], false, 2, cellLadder);
        check(ColgramDpiBypass.cellularPreferredStrategyForTest() == cellLadder[1],
                "2 failures advance cellularPreferredStrategy through CELLULAR_LADDER by index");
        check(ColgramDpiBypass.dcBackoffUntilForTest(2) == 0L,
                "single strategy failure must NOT trigger premature 8s dcBackoffUntil");
        // Exhaust remaining strategies in the ladder -> only after full cycle does backoff trigger
        for (int idx = 1; idx < cellLadder.length - 1; idx++) {
            int cur = ColgramDpiBypass.cellularPreferredStrategyForTest();
            check(cur == cellLadder[idx], "ladder index " + idx + " matches");
            ColgramDpiBypass.reportStrategyResultWithLadderForTest(cur, false, 2, cellLadder);
            ColgramDpiBypass.reportStrategyResultWithLadderForTest(cur, false, 2, cellLadder);
            check(ColgramDpiBypass.dcBackoffUntilForTest(2) == 0L,
                    "backoff must stay 0 before full ladder cycle completes at idx=" + idx);
        }
        int lastStrat = ColgramDpiBypass.cellularPreferredStrategyForTest();
        check(lastStrat == cellLadder[cellLadder.length - 1], "reached final strategy in cellular ladder");
        ColgramDpiBypass.reportStrategyResultWithLadderForTest(lastStrat, false, 2, cellLadder);
        ColgramDpiBypass.reportStrategyResultWithLadderForTest(lastStrat, false, 2, cellLadder);
        check(ColgramDpiBypass.dcBackoffUntilForTest(2) > 0L,
                "dcBackoffUntil activates ONLY after exhausting full cellular ladder cycle");
        check(ColgramDpiBypass.dcBackoffUntilForTest(4) == 0L,
                "DC2 backoff never leaks into DC4 bucket");
        ColgramDpiBypass.resetStrategyStateForTest(false, ColgramDpiBypass.STRATEGY_SPLIT_1);

        // 7. DoH / RFC 7858 DoT:853 endpoints, spoofed SNI support, diverse wave selection & framing
        ColgramDohResolver.Endpoint[] dohEps = ColgramDohResolver.configuredEndpoints();
        java.util.Set<String> dohKeys = new java.util.HashSet<>();
        boolean hasComssSpoof = false;
        boolean hasYandexDot853 = false;
        boolean hasAliDnsSpoof = false;
        boolean hasAdguardNoSpoof = false;
        for (ColgramDohResolver.Endpoint ep : dohEps) {
            dohKeys.add(ep.ip + ":" + ep.port);
            check(!"83.220.169.155".equals(ep.ip), "removed stale self-signed Comss IP 83.220.169.155");
            check(!"9.9.9.9".equals(ep.ip) && !"149.112.112.112".equals(ep.ip),
                    "removed HTTP/2-only Quad9 from HTTP/1.1 DoH");
            check(!"194.242.2.2".equals(ep.ip), "removed dead Mullvad IP 194.242.2.2");
            check(!("77.88.8.8".equals(ep.ip) && ep.port == 443),
                    "removed HTTP/2-only Yandex 77.88.8.8:443 from HTTP/1.1 DoH");
            if ("141.133.184.24".equals(ep.ip) && "dns.comss.one".equals(ep.verifyName) && ep.supportsSpoofedSni && !ep.dot) {
                hasComssSpoof = true;
            }
            if ("77.88.8.8".equals(ep.ip) && ep.port == 853 && ep.dot && ep.supportsSpoofedSni
                    && "common.dot.dns.yandex.net".equals(ep.verifyName)) {
                hasYandexDot853 = true;
            }
            if ("223.5.5.5".equals(ep.ip) && "dns.alidns.com".equals(ep.verifyName) && ep.supportsSpoofedSni) {
                hasAliDnsSpoof = true;
            }
            if ("94.140.14.14".equals(ep.ip) && "dns.adguard-dns.com".equals(ep.verifyName) && !ep.supportsSpoofedSni) {
                hasAdguardNoSpoof = true;
            }
        }
        check(hasComssSpoof, "live Comss 141.133.184.24 with spoofed SNI enabled");
        check(hasYandexDot853, "Yandex RFC 7858 DoT 77.88.8.8:853 with spoofed SNI enabled");
        check(hasAliDnsSpoof, "AliDNS 223.5.5.5 with spoofed SNI enabled");
        check(hasAdguardNoSpoof, "AdGuard 94.140.14.14 keeps real SNI only");
        for (String required : new String[]{
                "141.133.184.24:443", "141.133.183.77:443", "155.212.172.101:443",
                "212.109.195.93:443", "212.109.221.20:443", "77.88.8.8:853", "77.88.8.1:853",
                "94.140.14.14:443", "94.140.15.15:443", "8.8.8.8:443", "8.8.4.4:443",
                "1.1.1.1:443", "1.0.0.1:443", "76.76.2.0:443", "45.11.45.11:443", "223.5.5.5:443"}) {
            check(dohKeys.contains(required), "missing live-verified DoH/DoT endpoint " + required);
        }
        ColgramDohResolver.Endpoint emergencyDot = ColgramDohResolver.emergencyRuDotEndpoint();
        check(emergencyDot != null && emergencyDot.dot && emergencyDot.port == 853 && "77.88.8.8".equals(emergencyDot.ip),
                "emergency RU fallback uses RFC 7858 DoT 77.88.8.8:853");

        java.util.List<ColgramDohResolver.Endpoint> poolCopy = new java.util.ArrayList<>(Arrays.asList(dohEps));
        java.util.List<ColgramDohResolver.Endpoint> wave1 = ColgramDohResolver.pickDiverseWave(poolCopy, 3);
        check(wave1.size() == 3, "Wave 1 selects 3 concurrent endpoints");
        java.util.Set<String> wave1Groups = new java.util.HashSet<>();
        for (ColgramDohResolver.Endpoint ep : wave1) wave1Groups.add(ep.providerGroup);
        check(wave1Groups.size() == 3, "Wave 1 spans 3 distinct provider groups: " + wave1Groups);

        // RFC 7858 DoT 2-byte length-prefixed frame reader
        try {
            byte[] dnsSample = new byte[16];
            dnsSample[0] = 0x12; dnsSample[1] = 0x34; dnsSample[2] = (byte) 0x81; dnsSample[3] = (byte) 0x80;
            byte[] dotFramed = new byte[2 + dnsSample.length];
            dotFramed[0] = 0x00; dotFramed[1] = (byte) dnsSample.length;
            System.arraycopy(dnsSample, 0, dotFramed, 2, dnsSample.length);
            byte[] parsedDot = ColgramDohResolver.readDotResponse(new java.io.ByteArrayInputStream(dotFramed));
            check(Arrays.equals(dnsSample, parsedDot), "readDotResponse parses RFC 7858 framed reply verbatim");
            check(ColgramDohResolver.readDotResponse(new java.io.ByteArrayInputStream(new byte[]{0x00, 0x04, 1, 2, 3, 4})) == null,
                    "readDotResponse rejects sub-header DNS length (<12B)");
        } catch (Exception e) {
            throw new AssertionError("RFC 7858 DoT framing test failed", e);
        }

        // 8. Conscrypt engine SSLSocketFactory & MODE_MULTI_SPLIT_SNI & createSniFragmentingFactory contract
        check(ColgramNativeDesync.MODE_MULTI_SPLIT_SNI == 11, "MODE_MULTI_SPLIT_SNI == 11");
        javax.net.ssl.SSLSocketFactory engineSf = ColgramNativeDesync.createEngineSslSocketFactory();
        check(engineSf != null, "createEngineSslSocketFactory returns non-null SSLSocketFactory");
        try {
            javax.net.ssl.SSLSocketFactory fragSf =
                    ColgramDohResolver.createSniFragmentingFactory("141.133.184.24", "dns.comss.one", null);
            java.net.Socket s = fragSf.createSocket();
            check(s instanceof javax.net.ssl.SSLSocket,
                    "createSniFragmentingFactory().createSocket() returns real SSLSocket (no broken DeferredFragmentingSslSocket)");
            s.close();
        } catch (Exception e) {
            throw new AssertionError("createSniFragmentingFactory contract failed", e);
        }

        // 9. isOverlayVpnActive policy & strict L3-whitelist classification
        check(ColgramDpiBypass.isOverlayVpnActive(true, false, false), "WARP counts as overlay VPN");
        check(!ColgramDpiBypass.isOverlayVpnActive(false, true, true),
                "own colgram-system-dpi TUN must NOT trigger overlayActive and kill desync/WSS");
        check(ColgramDpiBypass.isOverlayVpnActive(false, false, true),
                "external Android TRANSPORT_VPN counts as overlay VPN");
        check(!ColgramDpiBypass.isOverlayVpnActive(false, false, false),
                "normal cellular/WiFi (even with VoLTE/IMS tun*/ppp*) is NOT an overlay VPN");
        check(ColgramDpiBypass.evaluateStrictL3Whitelist(true, false),
                "RU whitelist SYN ok + all Telegram edges SYN failed => strict L3 whitelist");
        check(!ColgramDpiBypass.evaluateStrictL3Whitelist(true, true),
                "Telegram WSS edge SYN ok => NOT L3 whitelist");
        check(!ColgramDpiBypass.evaluateStrictL3Whitelist(false, false),
                "no internet at all => NOT L3 whitelist");

        // 10. Verified Telegram WSS edge IPs & spoofed-SNI official Telegram SAN verifier
        ColgramTelegramWsBridge.setCellularNetwork(true);
        check(ColgramTelegramWsBridge.maxDialWallMs() <= 5500L,
                "cellular WSS maxDialWallMs stays <= 5500ms: " + ColgramTelegramWsBridge.maxDialWallMs());
        ColgramTelegramWsBridge.setCellularNetwork(false);
        String[] dc2Edges = ColgramTelegramWsBridge.getVerifiedWssEdgeIpsForDc(2);
        String[] dc1Edges = ColgramTelegramWsBridge.getVerifiedWssEdgeIpsForDc(1);
        String[] dc5Edges = ColgramTelegramWsBridge.getVerifiedWssEdgeIpsForDc(5);
        check(dc2Edges != null && dc2Edges.length > 0 && "149.154.167.220".equals(dc2Edges[0]),
                "DC2 verified WSS edges rank 149.154.167.220 first");
        check(dc1Edges != null && Arrays.asList(dc1Edges).contains("149.154.174.100")
                        && !Arrays.asList(dc1Edges).contains("149.154.175.50"),
                "DC1 verified WSS edges include 149.154.174.100 and exclude raw MTProto IP");
        check(dc5Edges != null && Arrays.asList(dc5Edges).contains("149.154.170.100")
                        && !Arrays.asList(dc5Edges).contains("91.108.56.130"),
                "DC5 verified WSS edges include 149.154.170.100 and exclude raw MTProto IP");

        check(ColgramTelegramWsBridge.verifyOfficialTelegramSession(
                        mockSslSessionWithSan("*.telegram.org"), "kws2.web.telegram.org"),
                "spoofed-SNI verifier accepts official *.telegram.org SAN (DC2/DC4 TLS 1.3)");
        check(ColgramTelegramWsBridge.verifyOfficialTelegramSession(
                        mockSslSessionWithSan("*.telesco.pe"), "kws1.web.telegram.org"),
                "spoofed-SNI verifier accepts official *.telesco.pe SAN (DC1/DC3 TLS 1.3)");
        check(ColgramTelegramWsBridge.verifyOfficialTelegramSession(
                        mockSslSessionWithSan("*.web.telegram.org"), "kws5.web.telegram.org"),
                "spoofed-SNI verifier accepts official *.web.telegram.org SAN (DC5)");
        check(!ColgramTelegramWsBridge.verifyOfficialTelegramSession(
                        mockSslSessionWithSan("*.vk.com"), "kws2.web.telegram.org"),
                "spoofed-SNI verifier rejects non-Telegram *.vk.com cert");
        check(!ColgramTelegramWsBridge.verifyOfficialTelegramSession(
                        mockSslSessionWithSan("*.evil-telegram.org"), "kws2.web.telegram.org"),
                "spoofed-SNI verifier rejects lookalike *.evil-telegram.org cert");

        // 11. Universal Layer-1 IPv6 WSS + Layer-2 L3-Relay Cascade & SOCKS5 pool readiness
        // 11a. IPv6 WSS probing & candidate race selection
        java.util.List<String> probeV4Only = ColgramDpiBypass.buildL3ProbeEdgeIps(false);
        java.util.List<String> probeDual = ColgramDpiBypass.buildL3ProbeEdgeIps(true);
        boolean v4OnlyHasColon = false;
        for (String ip : probeV4Only) if (ip.indexOf(':') >= 0) v4OnlyHasColon = true;
        check(!v4OnlyHasColon && probeV4Only.contains("149.154.167.220"),
                "L3 IPv4-only probe list keeps verified IPv4 edges without IPv6");
        check(probeDual.contains("149.154.167.220")
                        && probeDual.contains("2001:67c:4e8:f004::9")
                        && probeDual.contains("2001:b28:f23d:8005:7:0:109:337")
                        && probeDual.contains("2001:b28:f23f:9::852:437"),
                "L3 dual-stack probe list includes verified IPv6 WSS edges alongside IPv4: " + probeDual);
        java.util.List<ColgramTelegramWsBridge.EdgeEndpoint> dc1Cands =
                ColgramTelegramWsBridge.buildCandidateEndpoints(1, null, null, null);
        java.util.List<ColgramTelegramWsBridge.EdgeEndpoint> dc1RaceV6 =
                ColgramTelegramWsBridge.selectRaceEndpoints(dc1Cands, true);
        boolean raceHasV6 = false;
        for (ColgramTelegramWsBridge.EdgeEndpoint ep : dc1RaceV6) {
            if (ep.ip.indexOf(':') >= 0) raceHasV6 = true;
        }
        check(dc1RaceV6.size() == 4 && raceHasV6 && dc1RaceV6.get(1).ip.indexOf(':') >= 0,
                "WSS candidate race interleaves verified IPv6 edge in slot 1 even after 3 IPv4 edges: " + dc1RaceV6);

        // 11b. Strict encryption guard for Layer-2 L3-relay cascade (NEVER plaintext port 80)
        check(!ColgramDpiBypass.isEligibleForL3RelayCascade("example.com", 80),
                "plaintext port 80 strictly rejected for non-Telegram host");
        check(!ColgramDpiBypass.isEligibleForL3RelayCascade("149.154.167.51", 80),
                "port 80 strictly rejected even for Telegram DC IP");
        check(!ColgramDpiBypass.isEligibleForL3RelayCascade("example.com", 8080),
                "plaintext port 8080 strictly rejected");
        check(!ColgramDpiBypass.isEligibleForL3RelayCascade("127.0.0.1", 443),
                "loopback target strictly rejected");
        check(!ColgramDpiBypass.isEligibleForL3RelayCascade("192.168.1.10", 443),
                "private LAN target strictly rejected");
        check(ColgramDpiBypass.isEligibleForL3RelayCascade("149.154.167.51", 443),
                "Telegram DC IP:443 eligible for L3 relay cascade");
        check(ColgramDpiBypass.isEligibleForL3RelayCascade("149.154.167.51", 5222),
                "Telegram DC IP:5222 eligible for L3 relay cascade");
        check(ColgramDpiBypass.isEligibleForL3RelayCascade("2001:67c:4e8:f002:0:0:0:a", 443),
                "Telegram IPv6 DC:443 eligible for L3 relay cascade");
        check(ColgramDpiBypass.isEligibleForL3RelayCascade("www.youtube.com", 443),
                "encrypted HTTPS port 443 eligible for L3 relay cascade");
        check(ColgramDpiBypass.isEligibleForL3RelayCascade("discord.com", 8443),
                "encrypted port 8443 eligible for L3 relay cascade");

        // 11c. Real loopback mock SOCKS5 + HTTP CONNECT servers: tunnel bytes & state lifecycle
        ColgramDpiBypass.onDirectLayer1Success();
        ColgramDpiBypass.setStrictL3WhitelistForTest(true);
        check(!ColgramDpiBypass.isL3RelayCascadeActive(),
                "L3 cascade inactive before any relay connects");
        check("TCP SYN блокируется вышкой · ищу L3-мост…".equals(ColgramDpiBypass.upstreamStatusForBound(true)),
                "upstreamStatus honestly reports searching when L3 blocked: "
                        + ColgramDpiBypass.upstreamStatusForBound(true));

        try (java.net.ServerSocket mockSocks = new java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress())) {
            final int socksPort = mockSocks.getLocalPort();
            final java.util.concurrent.atomic.AtomicInteger socksServed = new java.util.concurrent.atomic.AtomicInteger(0);
            Thread socksServer = new Thread(() -> {
                for (int i = 0; i < 2; i++) {
                    try (java.net.Socket s = mockSocks.accept()) {
                        s.setSoTimeout(3000);
                        java.io.InputStream in = s.getInputStream();
                        java.io.OutputStream out = s.getOutputStream();
                        int ver = in.read();
                        int nMethods = in.read();
                        byte[] methods = new byte[Math.max(0, nMethods)];
                        for (int k = 0; k < methods.length; k++) methods[k] = (byte) in.read();
                        if (ver != 5 || nMethods < 1) continue;
                        out.write(new byte[]{5, 0});
                        out.flush();
                        int rVer = in.read();
                        int cmd = in.read();
                        int rsv = in.read();
                        int atyp = in.read();
                        if (rVer != 5 || cmd != 1 || rsv != 0) continue;
                        int addrLen = (atyp == 1) ? 4 : (atyp == 4 ? 16 : (atyp == 3 ? in.read() : -1));
                        if (addrLen <= 0) continue;
                        byte[] addr = new byte[addrLen];
                        for (int k = 0; k < addrLen; k++) addr[k] = (byte) in.read();
                        int pHi = in.read();
                        int pLo = in.read();
                        int reqPort = ((pHi & 0xFF) << 8) | (pLo & 0xFF);
                        if (reqPort != 443) continue;
                        out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                        out.flush();
                        socksServed.incrementAndGet();
                        byte[] buf = new byte[256];
                        int n = in.read(buf);
                        if (n > 0) {
                            out.write(buf, 0, n);
                            out.flush();
                        }
                    } catch (Exception ignored) {}
                }
            });
            socksServer.setDaemon(true);
            socksServer.start();

            ColgramProxyChain.Relay loopbackSocks = new ColgramProxyChain.Relay("127.0.0.1", socksPort, true);
            loopbackSocks.rttMs = 20;
            java.util.List<ColgramProxyChain.Relay> oneRelay = java.util.Collections.singletonList(loopbackSocks);

            // Plaintext port 80 must be rejected before dialing relay
            check(ColgramDpiBypass.connectViaL3RelayCascade("example.com", 80, 2000, null, oneRelay) == null,
                    "connectViaL3RelayCascade strictly rejects plaintext port 80");

            // Telegram DC IP:443 (ATYP=1) must tunnel bytes verbatim
            try (java.net.Socket tunneled = ColgramDpiBypass.connectViaL3RelayCascade(
                    "149.154.167.51", 443, 2500, null, oneRelay)) {
                check(tunneled != null, "connectViaL3RelayCascade opened SOCKS5 tunnel to Telegram DC IP:443");
                byte[] probeBytes = "MTPROTO_OBF2_PAYLOAD".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                tunneled.getOutputStream().write(probeBytes);
                tunneled.getOutputStream().flush();
                byte[] echo = new byte[probeBytes.length];
                int got = tunneled.getInputStream().read(echo);
                check(got == probeBytes.length && Arrays.equals(probeBytes, echo),
                        "SOCKS5 L3 tunnel echoed Telegram payload verbatim");
            }
            // Domain:443 (ATYP=3) must tunnel bytes verbatim
            try (java.net.Socket tunneled2 = ColgramDpiBypass.connectViaL3RelayCascade(
                    "youtube.com", 443, 2500, null, oneRelay)) {
                check(tunneled2 != null, "connectViaL3RelayCascade opened SOCKS5 tunnel to youtube.com:443");
                byte[] probeBytes = "TLS_CLIENT_HELLO".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                tunneled2.getOutputStream().write(probeBytes);
                tunneled2.getOutputStream().flush();
                byte[] echo = new byte[probeBytes.length];
                int got = tunneled2.getInputStream().read(echo);
                check(got == probeBytes.length && Arrays.equals(probeBytes, echo),
                        "SOCKS5 L3 tunnel echoed domain:443 payload verbatim");
            }
            check(socksServed.get() == 2, "mock SOCKS5 server served both encrypted tunnels");
            check(ColgramDpiBypass.isL3RelayCascadeActive(),
                    "isL3RelayCascadeActive() is true after successful L3 cascade under strict L3 whitelist");
            String activeStatus = ColgramDpiBypass.upstreamStatusForBound(true);
            check(activeStatus.equals("Авто-обход (L3-мост 127.0.0.1:" + socksPort + ") · подключён"),
                    "upstreamStatus reports active L3 relay label: " + activeStatus);
            // Clearing via direct Layer-1 success snaps right back to local bypass
            ColgramDpiBypass.onDirectLayer1Success();
            check(!ColgramDpiBypass.isL3RelayCascadeActive()
                            && !ColgramDpiBypass.isStrictL3WhitelistDetected()
                            && ColgramDpiBypass.getActiveL3RelayLabel().isEmpty(),
                    "onDirectLayer1Success clears L3 cascade and strict L3 whitelist state cleanly");
        } catch (Exception e) {
            throw new AssertionError("L3 relay SOCKS5 loopback test failed", e);
        }

        // 11d. HTTP CONNECT loopback relay test
        try (java.net.ServerSocket mockHttp = new java.net.ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress())) {
            final int httpPort = mockHttp.getLocalPort();
            Thread httpServer = new Thread(() -> {
                try (java.net.Socket s = mockHttp.accept()) {
                    s.setSoTimeout(3000);
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(s.getInputStream(), java.nio.charset.StandardCharsets.ISO_8859_1));
                    String reqLine = br.readLine();
                    if (reqLine == null || !reqLine.startsWith("CONNECT 149.154.167.51:443 ")) return;
                    String hdr;
                    while ((hdr = br.readLine()) != null && !hdr.isEmpty()) {}
                    s.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n"
                            .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    s.getOutputStream().flush();
                    byte[] buf = new byte[64];
                    int n = s.getInputStream().read(buf);
                    if (n > 0) {
                        s.getOutputStream().write(buf, 0, n);
                        s.getOutputStream().flush();
                    }
                } catch (Exception ignored) {}
            });
            httpServer.setDaemon(true);
            httpServer.start();

            ColgramProxyChain.Relay httpRelay = new ColgramProxyChain.Relay("127.0.0.1", httpPort, false);
            httpRelay.rttMs = 35;
            try (java.net.Socket tunneled = ColgramDpiBypass.connectViaL3RelayCascade(
                    "149.154.167.51", 443, 2500, null, java.util.Collections.singletonList(httpRelay))) {
                check(tunneled != null, "connectViaL3RelayCascade opened HTTP CONNECT tunnel");
                byte[] payload = "HTTP_CONNECT_OK".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                tunneled.getOutputStream().write(payload);
                tunneled.getOutputStream().flush();
                byte[] echo = new byte[payload.length];
                int got = tunneled.getInputStream().read(echo);
                check(got == payload.length && Arrays.equals(payload, echo),
                        "HTTP CONNECT L3 tunnel echoed bytes verbatim");
            }
            ColgramDpiBypass.onDirectLayer1Success();
        } catch (Exception e) {
            throw new AssertionError("L3 relay HTTP CONNECT loopback test failed", e);
        }

        // 11e. ColgramProxyManager.getReachableRelays() surfaces catalog-verified SOCKS5 entries sorted by RTT
        ColgramProxyManager.clearPoolsForTest();
        try {
            ColgramProxyManager.ProxyItem socksConnectItem =
                    new ColgramProxyManager.ProxyItem("85.234.66.79", 443, "", 0);
            socksConnectItem.socksConnectOk = true;
            socksConnectItem.tcpMs = 180;
            ColgramProxyManager.addPoolItemForTest(socksConnectItem);

            ColgramProxyManager.ProxyItem availSocksItem =
                    new ColgramProxyManager.ProxyItem("141.133.195.42", 443, "", 0);
            availSocksItem.isAvailable = true;
            availSocksItem.pingMs = 95;
            ColgramProxyManager.addPoolItemForTest(availSocksItem);

            // Duplicate with worse RTT in relayPool must deduplicate keeping 95ms
            ColgramProxyChain.Relay dupSlower = new ColgramProxyChain.Relay("141.133.195.42", 443, true);
            dupSlower.rttMs = 260;
            ColgramProxyManager.addRelayForTest(dupSlower);

            ColgramProxyChain.Relay httpConnectRelay = new ColgramProxyChain.Relay("45.10.20.30", 8080, false);
            httpConnectRelay.rttMs = 140;
            ColgramProxyManager.addRelayForTest(httpConnectRelay);

            // MTProto, loopback local DPI, and dead SOCKS5 must be excluded
            ColgramProxyManager.ProxyItem mtprotoItem =
                    new ColgramProxyManager.ProxyItem("185.100.87.10", 443, "ee000102030405060708090a0b0c0d0e0f676f6f676c652e636f6d", 1);
            mtprotoItem.isAvailable = true;
            mtprotoItem.pingMs = 40;
            ColgramProxyManager.addPoolItemForTest(mtprotoItem);

            ColgramProxyManager.ProxyItem loopbackItem =
                    new ColgramProxyManager.ProxyItem("127.0.0.1", 9876, "", 0);
            loopbackItem.isAvailable = true;
            loopbackItem.pingMs = 1;
            ColgramProxyManager.addPoolItemForTest(loopbackItem);

            ColgramProxyManager.ProxyItem deadSocksItem =
                    new ColgramProxyManager.ProxyItem("91.100.20.30", 1080, "", 0);
            deadSocksItem.socksConnectOk = true;
            deadSocksItem.pingMs = -2;
            ColgramProxyManager.addPoolItemForTest(deadSocksItem);

            java.util.List<ColgramProxyChain.Relay> relays = ColgramProxyManager.getReachableRelays();
            check(ColgramProxyManager.hasReachableRelays(), "hasReachableRelays() true with catalog SOCKS5");
            check(relays.size() == 3, "getReachableRelays returns 3 deduplicated live relays, got=" + relays);
            check("141.133.195.42".equals(relays.get(0).host) && relays.get(0).rttMs == 95 && relays.get(0).socks,
                    "fastest verified catalog SOCKS5 ranked #1 (deduped 95ms): " + relays.get(0));
            check("45.10.20.30".equals(relays.get(1).host) && relays.get(1).rttMs == 140 && !relays.get(1).socks,
                    "HTTP CONNECT relay ranked #2 (140ms): " + relays.get(1));
            check("85.234.66.79".equals(relays.get(2).host) && relays.get(2).rttMs == 180 && relays.get(2).socks,
                    "socksConnectOk catalog SOCKS5 ranked #3 (180ms): " + relays.get(2));
        } finally {
            ColgramProxyManager.clearPoolsForTest();
        }

        // 11f. MegaFon/Yota TSPU-strict SOCKS5 test: rejects single-segment 10B CONNECT, accepts 2B segmented!
        try (java.net.ServerSocket tspuSocks = new java.net.ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress())) {
            final int tspuPort = tspuSocks.getLocalPort();
            final java.util.concurrent.atomic.AtomicInteger firstChunkBytes = new java.util.concurrent.atomic.AtomicInteger(999);
            Thread tspuServer = new Thread(() -> {
                try (java.net.Socket s = tspuSocks.accept()) {
                    s.setSoTimeout(3000);
                    java.io.InputStream in = s.getInputStream();
                    java.io.OutputStream out = s.getOutputStream();
                    byte[] greet = new byte[3];
                    int g = in.read(greet);
                    if (g < 3 || greet[0] != 5) return;
                    out.write(new byte[]{5, 0});
                    out.flush();
                    byte[] req = new byte[10];
                    int got = 0;
                    int r0 = in.read(req, 0, req.length);
                    if (r0 <= 0) return;
                    firstChunkBytes.set(r0);
                    // Simulate MegaFon/Yota TSPU: DROP if all 10 bytes arrived in 1 unsplit TCP segment!
                    if (r0 >= 10) return;
                    got = r0;
                    while (got < 10) {
                        int n = in.read(req, got, 10 - got);
                        if (n <= 0) return;
                        got += n;
                    }
                    // Verify exact IPv4 bytes for 149.154.167.51:443
                    if (req[0] != 5 || req[1] != 1 || req[2] != 0 || req[3] != 1
                            || (req[4] & 0xFF) != 149 || (req[5] & 0xFF) != 154
                            || (req[6] & 0xFF) != 167 || (req[7] & 0xFF) != 51) return;
                    out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                    out.flush();
                    byte[] buf = new byte[64];
                    int n = in.read(buf);
                    if (n > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Exception ignored) {}
            });
            tspuServer.setDaemon(true);
            tspuServer.start();

            ColgramProxyChain.Relay tspuRelay = new ColgramProxyChain.Relay("127.0.0.1", tspuPort, true);
            tspuRelay.rttMs = 30;
            try (java.net.Socket tunneled = ColgramDpiBypass.connectViaL3RelayCascade(
                    "149.154.167.51", 443, 2500, null, java.util.Collections.singletonList(tspuRelay))) {
                check(tunneled != null && firstChunkBytes.get() < 10,
                        "connectViaL3RelayCascade segments SOCKS5 CONNECT across TCP packets (firstRead="
                                + firstChunkBytes.get() + ")");
                byte[] msg = "TSPU_EVADED".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                tunneled.getOutputStream().write(msg);
                tunneled.getOutputStream().flush();
                byte[] echo = new byte[msg.length];
                int got = tunneled.getInputStream().read(echo);
                check(got == msg.length && Arrays.equals(msg, echo),
                        "segmented SOCKS5 tunnel carried bytes cleanly past TSPU mock");
            }
            ColgramDpiBypass.onDirectLayer1Success();
        } catch (Exception e) {
            throw new AssertionError("TSPU-strict segmented SOCKS5 loopback test failed", e);
        }

        // 11g. In-tunnel TLS ClientHello fragmentation vs MTProto verbatim passthrough + WSS host mapping
        try {
            java.io.ByteArrayOutputStream tlsOut = new java.io.ByteArrayOutputStream();
            ColgramDpiBypass.writeFirstPayloadForL3Relay(tlsOut, pqCh, pqCh.length);
            byte[] splitTls = tlsOut.toByteArray();
            check(splitTls.length == pqCh.length + 5,
                    "writeFirstPayloadForL3Relay splits TLS ClientHello into 2 RFC-valid TLS records");
            int r1Len = ((splitTls[3] & 0xFF) << 8) | (splitTls[4] & 0xFF);
            int r2Off = 5 + r1Len;
            int r2Len = ((splitTls[r2Off + 3] & 0xFF) << 8) | (splitTls[r2Off + 4] & 0xFF);
            check(splitTls[0] == 0x16 && splitTls[r2Off] == 0x16 && r2Off + 5 + r2Len == splitTls.length,
                    "in-tunnel split TLS records have valid headers & exact lengths");
            byte[] rebuilt = new byte[r1Len + r2Len];
            System.arraycopy(splitTls, 5, rebuilt, 0, r1Len);
            System.arraycopy(splitTls, r2Off + 5, rebuilt, r1Len, r2Len);
            check(Arrays.equals(rebuilt, Arrays.copyOfRange(pqCh, 5, pqCh.length)),
                    "in-tunnel split TLS records preserve PQ ClientHello bytes verbatim");

            byte[] mtprotoInit = new byte[64];
            for (int i = 0; i < mtprotoInit.length; i++) mtprotoInit[i] = (byte) (0x41 + (i % 26));
            java.io.ByteArrayOutputStream mtOut = new java.io.ByteArrayOutputStream();
            ColgramDpiBypass.writeFirstPayloadForL3Relay(mtOut, mtprotoInit, mtprotoInit.length);
            check(Arrays.equals(mtprotoInit, mtOut.toByteArray()),
                    "writeFirstPayloadForL3Relay forwards non-TLS MTProto bytes verbatim");
            check("kws2.web.telegram.org".equals(ColgramTelegramWsBridge.primaryWssHostForDc(2))
                            && "kws1.web.telegram.org".equals(ColgramTelegramWsBridge.primaryWssHostForDc(1))
                            && "vesta.web.telegram.org".equals(ColgramTelegramWsBridge.primaryWssHostForDc(4))
                            && "kws5.web.telegram.org".equals(ColgramTelegramWsBridge.primaryWssHostForDc(5)),
                    "primaryWssHostForDc maps DCs to official WSS hosts");
        } catch (Exception e) {
            throw new AssertionError("in-tunnel TLS fragmentation test failed", e);
        }

        // 11g. Relay Tier-2 fallback ordering, dead-relay eviction, and ColgramDesignConfig performance defaults
        check(ColgramDesignConfig.isSmartColorContrastEnabled(), "smart color contrast enabled by default");
        check(ColgramDesignConfig.isDownloadSpeedBoostEnabled(), "download speed boost enabled by default");
        ColgramProxyManager.clearPoolsForTest();
        ColgramDpiBypass.setStrictL3WhitelistForTest(false);
        try (java.net.ServerSocket mockRelay = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))) {
            final int relayPort = mockRelay.getLocalPort();
            Thread relayAccept = new Thread(() -> {
                try (java.net.Socket s = mockRelay.accept()) {
                    java.io.InputStream in = s.getInputStream();
                    java.io.OutputStream out = s.getOutputStream();
                    byte[] hdr = ColgramSocks5Codec.readFully(in, 3);
                    if (hdr[0] == 5) {
                        out.write(new byte[]{5, 0});
                        out.flush();
                        byte[] reqHead = ColgramSocks5Codec.readFully(in, 4);
                        if ((reqHead[3] & 0xFF) == 3) {
                            int domLen = ColgramSocks5Codec.readByte(in) & 0xFF;
                            ColgramSocks5Codec.readFully(in, domLen + 2);
                        }
                        out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 80});
                        out.flush();
                        // Immediately close on TLS start to trigger L3 WSS failover penalty
                    }
                } catch (Throwable ignored) {}
            }, "test-l3-wss-evict");
            relayAccept.setDaemon(true);
            relayAccept.start();

            ColgramProxyManager.ProxyItem t1 = new ColgramProxyManager.ProxyItem("203.0.113.10", 1080, "", 0);
            t1.socksConnectOk = true;
            t1.tcpMs = 120;
            ColgramProxyManager.addPoolItemForTest(t1);

            ColgramProxyManager.ProxyItem t2 = new ColgramProxyManager.ProxyItem("203.0.113.20", 1080, "", 0);
            t2.socksGreetedOk = true;
            t2.pingMs = -2; // Failed raw MTProto probe must NOT disqualify a greeted Tier-2 relay
            t2.tcpMs = 40; // Faster TCP RTT, but Tier-2 gets +600ms offset so Tier-1 stays ahead
            ColgramProxyManager.addPoolItemForTest(t2);

            java.util.List<ColgramProxyChain.Relay> tiered = ColgramProxyManager.getReachableRelays();
            check(tiered.size() == 2 && "203.0.113.10".equals(tiered.get(0).host) && "203.0.113.20".equals(tiered.get(1).host),
                    "Tier-1 socksConnectOk relays outrank Tier-2 socksGreetedOk relays, got=" + tiered);

            ColgramProxyManager.markRelayDead("203.0.113.10", 1080);
            java.util.List<ColgramProxyChain.Relay> afterEvict = ColgramProxyManager.getReachableRelays();
            check(afterEvict.size() == 1 && "203.0.113.20".equals(afterEvict.get(0).host),
                    "markRelayDead evicts dead relay from getReachableRelays, got=" + afterEvict);

            ColgramProxyChain.Relay brokenWssRelay = new ColgramProxyChain.Relay("127.0.0.1", relayPort, true, "", "");
            brokenWssRelay.rttMs = 15;
            java.util.List<ColgramProxyChain.Relay> brokenList = java.util.Collections.singletonList(brokenWssRelay);
            ColgramTelegramWsBridge.WsSession wsFail = ColgramDpiBypass.dialWssViaL3RelayCascade(
                    "149.154.167.51", 443, null, brokenList);
            check(wsFail == null, "dialWssViaL3RelayCascade returns null when TLS upgrade stalls/closes");
            check(ColgramDpiBypass.isL3RelayPenalized("127.0.0.1", relayPort),
                    "failed WSS-over-relay evicts relay into 90s penalty window");
            relayAccept.join(1500);
        } catch (Exception e) {
            throw new AssertionError("relay eviction / tier2 test failed", e);
        } finally {
            ColgramDpiBypass.setStrictL3WhitelistForTest(false);
            ColgramProxyManager.clearPoolsForTest();
        }

        // 11h. 2-byte SOCKS5 CONNECT segmentation + RFC 6455 Ping/Pong, 64-bit (len==127),
        //      and fragmented (FIN=0 / opcode=0x0) frame verification
        try {
            java.io.ByteArrayOutputStream sockCapture = new java.io.ByteArrayOutputStream();
            final java.util.List<Integer> writeChunks = new java.util.ArrayList<>();
            java.io.OutputStream chunkMeasuringOut = new java.io.OutputStream() {
                @Override public void write(int b) throws java.io.IOException { writeChunks.add(1); sockCapture.write(b); }
                @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
                    writeChunks.add(len);
                    sockCapture.write(b, off, len);
                }
            };
            byte[] okReply = new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 80};
            final int[] replyIdx = {0};
            boolean segOk = ColgramSocks5Codec.requestConnect(chunkMeasuringOut,
                    () -> replyIdx[0] < okReply.length ? (okReply[replyIdx[0]++] & 0xff) : -1,
                    new byte[]{(byte) 149, (byte) 154, (byte) 167, (byte) 220}, 443);
            check(segOk && !writeChunks.isEmpty() && writeChunks.get(0) <= 2 && sockCapture.size() == 10,
                    "requestConnect writes 2-byte segments, first=" + (writeChunks.isEmpty() ? -1 : writeChunks.get(0)));

            // Build inbound WebSocket stream:
            //   1) Server Ping (0x89, "ping")
            //   2) Fragmented Binary 1/2 (FIN=0, 0x02, "hel")
            //   3) Interleaved Server Ping (0x89, "hi")
            //   4) Fragmented Binary 2/2 (FIN=1, 0x00, "lo")
            //   5) 64-bit length frame (FIN=1, 0x82, len==127 -> 70000 bytes)
            java.io.ByteArrayOutputStream wireIn = new java.io.ByteArrayOutputStream();
            wireIn.write(new byte[]{(byte) 0x89, 0x04, 'p', 'i', 'n', 'g'});
            wireIn.write(new byte[]{0x02, 0x03, 'h', 'e', 'l'});
            wireIn.write(new byte[]{(byte) 0x89, 0x02, 'h', 'i'});
            wireIn.write(new byte[]{(byte) 0x80, 0x02, 'l', 'o'});
            int bigLen = 70000;
            wireIn.write(0x82);
            wireIn.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) wireIn.write((int) (((long) bigLen >>> shift) & 0xff));
            byte[] bigPayload = new byte[bigLen];
            for (int i = 0; i < bigLen; i++) bigPayload[i] = (byte) (i & 0xff);
            wireIn.write(bigPayload);

            java.io.ByteArrayInputStream wsIn = new java.io.ByteArrayInputStream(wireIn.toByteArray());
            java.io.ByteArrayOutputStream wsOut = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream mtprotoDownstream = new java.io.ByteArrayOutputStream();
            ColgramTelegramWsBridge.WsSession mockSess =
                    new ColgramTelegramWsBridge.WsSession(2, "149.154.167.220", "kws2.web.telegram.org", null, wsIn, wsOut);
            long downBytes = ColgramTelegramWsBridge.pumpUpstreamFrames(mockSess, mtprotoDownstream, null);
            byte[] assembled = mtprotoDownstream.toByteArray();
            check(downBytes == 5L + bigLen && assembled.length == 5 + bigLen
                            && assembled[0] == 'h' && assembled[4] == 'o'
                            && java.util.Arrays.equals(java.util.Arrays.copyOfRange(assembled, 5, assembled.length), bigPayload),
                    "pumpUpstreamFrames handles fragmented FIN=0/0x0 + 64-bit len=127 frames verbatim");
            byte[] pongs = wsOut.toByteArray();
            // Verify both replies are masked RFC 6455 Pong (0x8A, MASK=1) with non-zero masked payload decoding to "ping" and "hi"
            check(pongs.length == (6 + 4) + (6 + 2)
                            && (pongs[0] & 0xff) == 0x8A && (pongs[1] & 0xff) == (0x80 | 4)
                            && (pongs[10] & 0xff) == 0x8A && (pongs[11] & 0xff) == (0x80 | 2),
                    "Ping opcode 0x9 sends RFC 6455 randomly masked Pong opcode 0xA");
            byte[] unmaskedPong1 = new byte[4];
            for (int i = 0; i < 4; i++) unmaskedPong1[i] = (byte) (pongs[6 + i] ^ pongs[2 + (i & 3)]);
            check("ping".equals(new String(unmaskedPong1, java.nio.charset.StandardCharsets.US_ASCII)),
                    "Masked Pong 1 payload decodes back to 'ping'");
        } catch (Exception e) {
            throw new AssertionError("SOCKS5/WS frame test failed", e);
        }

        System.out.println("ColgramCoreRegressionTest: PASS");
    }

    private static javax.net.ssl.SSLSession mockSslSessionWithSan(final String dnsSan) {
        final java.security.cert.X509Certificate cert = new java.security.cert.X509Certificate() {
            @Override public java.util.Collection<java.util.List<?>> getSubjectAlternativeNames() {
                java.util.List<java.util.List<?>> sans = new java.util.ArrayList<>();
                sans.add(Arrays.<Object>asList(2, dnsSan));
                return sans;
            }
            @Override public javax.security.auth.x500.X500Principal getSubjectX500Principal() {
                return new javax.security.auth.x500.X500Principal("CN=" + dnsSan);
            }
            @Override public void checkValidity() {}
            @Override public void checkValidity(java.util.Date date) {}
            @Override public int getVersion() { return 3; }
            @Override public java.math.BigInteger getSerialNumber() { return java.math.BigInteger.ONE; }
            @Override public java.security.Principal getIssuerDN() { return getSubjectX500Principal(); }
            @Override public java.security.Principal getSubjectDN() { return getSubjectX500Principal(); }
            @Override public java.util.Date getNotBefore() { return new java.util.Date(0); }
            @Override public java.util.Date getNotAfter() { return new java.util.Date(Long.MAX_VALUE); }
            @Override public byte[] getTBSCertificate() { return new byte[0]; }
            @Override public byte[] getSignature() { return new byte[0]; }
            @Override public String getSigAlgName() { return "SHA256withRSA"; }
            @Override public String getSigAlgOID() { return "1.2.840.113549.1.1.11"; }
            @Override public byte[] getSigAlgParams() { return null; }
            @Override public boolean[] getIssuerUniqueID() { return null; }
            @Override public boolean[] getSubjectUniqueID() { return null; }
            @Override public boolean[] getKeyUsage() { return null; }
            @Override public int getBasicConstraints() { return -1; }
            @Override public byte[] getEncoded() { return new byte[0]; }
            @Override public void verify(java.security.PublicKey key) {}
            @Override public void verify(java.security.PublicKey key, String sigProvider) {}
            @Override public String toString() { return "MockCert(" + dnsSan + ")"; }
            @Override public java.security.PublicKey getPublicKey() { return null; }
            @Override public boolean hasUnsupportedCriticalExtension() { return false; }
            @Override public java.util.Set<String> getCriticalExtensionOIDs() { return java.util.Collections.emptySet(); }
            @Override public java.util.Set<String> getNonCriticalExtensionOIDs() { return java.util.Collections.emptySet(); }
            @Override public byte[] getExtensionValue(String oid) { return null; }
        };
        return (javax.net.ssl.SSLSession) java.lang.reflect.Proxy.newProxyInstance(
                ColgramCoreRegressionTest.class.getClassLoader(),
                new Class<?>[]{javax.net.ssl.SSLSession.class},
                (proxy, method, methodArgs) -> {
                    String name = method.getName();
                    if ("getPeerCertificates".equals(name)) return new java.security.cert.Certificate[]{cert};
                    if ("isValid".equals(name)) return Boolean.TRUE;
                    if ("getProtocol".equals(name)) return "TLSv1.3";
                    return null;
                });
    }
}
