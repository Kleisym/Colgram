package org.colgram.core;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-JVM unit tests for YouTube/CDN SLD midpoint TLS/TCP splitting, HTTPS/SVCB DNS blocking,
 * Google ECS DoH prioritization, MSS=88 protection, and Russian domestic fast-path classification.
 */
public final class ColgramYoutubeBypassTest {

    private static int checks;

    private static void check(boolean cond, String label) {
        checks++;
        if (!cond) throw new AssertionError("ColgramYoutubeBypassTest failed [" + checks + "]: " + label);
    }

    private static byte[] buildClientHello(String hostname, int keySharePadBytes) {
        byte[] hostBytes = hostname.getBytes(StandardCharsets.US_ASCII);
        int sniPayloadLen = 2 + 1 + 2 + hostBytes.length;
        int extBlockLen = (keySharePadBytes > 0 ? (4 + keySharePadBytes) : 0) + (4 + sniPayloadLen);
        int chBodyLen = 2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + extBlockLen;
        int recLen = 4 + chBodyLen;
        byte[] ch = new byte[5 + recLen];
        int p = 0;
        ch[p++] = 0x16; ch[p++] = 0x03; ch[p++] = 0x01;
        ch[p++] = (byte) (recLen >> 8); ch[p++] = (byte) recLen;
        ch[p++] = 0x01; ch[p++] = 0x00;
        ch[p++] = (byte) (chBodyLen >> 8); ch[p++] = (byte) chBodyLen;
        ch[p++] = 0x03; ch[p++] = 0x03;
        p += 32; // client_random
        ch[p++] = 0; // session_id len
        ch[p++] = 0; ch[p++] = 2; ch[p++] = 0x13; ch[p++] = 0x01; // TLS_AES_128_GCM_SHA256
        ch[p++] = 1; ch[p++] = 0; // compression null
        ch[p++] = (byte) (extBlockLen >> 8); ch[p++] = (byte) extBlockLen;
        if (keySharePadBytes > 0) {
            ch[p++] = 0x00; ch[p++] = 0x33;
            ch[p++] = (byte) (keySharePadBytes >> 8); ch[p++] = (byte) keySharePadBytes;
            p += keySharePadBytes;
        }
        ch[p++] = 0x00; ch[p++] = 0x00;
        ch[p++] = (byte) (sniPayloadLen >> 8); ch[p++] = (byte) sniPayloadLen;
        int listLen = 1 + 2 + hostBytes.length;
        ch[p++] = (byte) (listLen >> 8); ch[p++] = (byte) listLen;
        ch[p++] = 0x00;
        ch[p++] = (byte) (hostBytes.length >> 8); ch[p++] = (byte) hostBytes.length;
        System.arraycopy(hostBytes, 0, ch, p, hostBytes.length);
        return ch;
    }

    private static byte[] buildDnsQuery(int txid, String qname, int qtype) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((txid >> 8) & 0xFF); out.write(txid & 0xFF);
        out.write(0x01); out.write(0x00); // RD=1
        out.write(0x00); out.write(0x01); // QDCOUNT=1
        out.write(0x00); out.write(0x00);
        out.write(0x00); out.write(0x00);
        out.write(0x00); out.write(0x00);
        for (String label : qname.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.US_ASCII);
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        out.write(0x00);
        out.write((qtype >> 8) & 0xFF); out.write(qtype & 0xFF);
        out.write(0x00); out.write(0x01); // QCLASS IN
        return out.toByteArray();
    }

    private static void verifyRecordsReassembleAndSeverSld(byte[] origHello, byte[][] recs, String expectedForbiddenRoot,
                                                         String label) {
        check(recs != null && recs.length >= 2, label + ": non-null >=2 records");
        ByteArrayOutputStream reassembled = new ByteArrayOutputStream();
        for (byte[] rec : recs) {
            check(rec.length > 5 && rec[0] == 0x16 && rec[1] == 0x03, label + ": valid TLS record header");
            int rLen = ((rec[3] & 0xFF) << 8) | (rec[4] & 0xFF);
            check(rLen + 5 == rec.length, label + ": exact TLS record length");
            String text = new String(rec, StandardCharsets.ISO_8859_1);
            check(!text.contains(expectedForbiddenRoot),
                    label + ": record must not contain unbroken '" + expectedForbiddenRoot + "'");
            reassembled.write(rec, 5, rLen);
        }
        check(Arrays.equals(Arrays.copyOfRange(origHello, 5, origHello.length), reassembled.toByteArray()),
                label + ": reassembled handshake bytes match original byte-for-byte");
    }

    public static void main(String[] args) throws Exception {
        // 1. SLD midpoint computation & TLS record splitting on long YouTube GGC hostname + 2-part ccTLD
        String ytCdn = "rr3---sn-jvhnu5g-c35z.googlevideo.com";
        byte[] ytHello = buildClientHello(ytCdn, 1216);
        int[] ytSni = ColgramTlsMimic.findTlsSniSpan(ytHello, ytHello.length);
        check(ytSni != null, "finds SNI span for " + ytCdn);
        int sldMid = ColgramTlsMimic.findSldMidpoint(ytHello, ytSni[0], ytSni[1]);
        String leftPart = new String(ytHello, ytSni[0], sldMid - ytSni[0], StandardCharsets.US_ASCII);
        String rightPart = new String(ytHello, sldMid, ytSni[1] - sldMid, StandardCharsets.US_ASCII);
        check("rr3---sn-jvhnu5g-c35z.googl".equals(leftPart) && "evideo.com".equals(rightPart),
                "SLD midpoint splits inside googlevideo -> googl|evideo.com, got=" + leftPart + "|" + rightPart);

        // 2-part ccTLD handling (.co.uk)
        byte[] ukBytes = "www.bbc.co.uk".getBytes(StandardCharsets.US_ASCII);
        int ukMid = ColgramTlsMimic.findSldMidpoint(ukBytes, 0, ukBytes.length);
        check("www.b".equals(new String(ukBytes, 0, ukMid, StandardCharsets.US_ASCII))
                        && "bc.co.uk".equals(new String(ukBytes, ukMid, ukBytes.length - ukMid, StandardCharsets.US_ASCII)),
                "findSldMidpoint handles 2-part ccTLD .co.uk (www.b|bc.co.uk)");

        byte[][] rec2 = ColgramTlsMimic.splitTlsRecordAtSni(ytHello, 2);
        check(rec2 != null && rec2.length == 2, "splitTlsRecordAtSni(2) produces 2 records");
        verifyRecordsReassembleAndSeverSld(ytHello, rec2, "googlevideo.com", "splitTlsRecordAtSni(2)");

        byte[][] rec3 = ColgramTlsMimic.splitTlsRecordAtSni(ytHello, 3);
        check(rec3 != null && rec3.length == 3, "splitTlsRecordAtSni(3) produces 3 records");
        verifyRecordsReassembleAndSeverSld(ytHello, rec3, "googlevideo.com", "splitTlsRecordAtSni(3)");

        byte[][] recSniAndSld = ColgramTlsMimic.splitTlsRecordAtSniAndSld(ytHello, 1);
        check(recSniAndSld != null && recSniAndSld.length == 3,
                "splitTlsRecordAtSniAndSld(1) produces 3 records for long YouTube CDN");
        verifyRecordsReassembleAndSeverSld(ytHello, recSniAndSld, "googlevideo.com", "splitTlsRecordAtSniAndSld(1)");

        // Short 2-label hostname falls back cleanly to 2-record SNI+1 split
        byte[] yaHello = buildClientHello("ya.ru", 0);
        byte[][] yaRecs = ColgramTlsMimic.splitTlsRecordAtSniAndSld(yaHello, 1);
        check(yaRecs != null && yaRecs.length == 2,
                "splitTlsRecordAtSniAndSld falls back to 2 records on short 2-label ya.ru");
        verifyRecordsReassembleAndSeverSld(yaHello, yaRecs, "ya.ru", "ya.ru fallback");

        // fragment() cuts across SLD midpoint for long CDN
        byte[][] tcpSegs = ColgramTlsMimic.fragment(ytHello, new Random(7));
        check(tcpSegs.length == 3, "fragment() produces 3 TCP segments");
        ByteArrayOutputStream tcpRe = new ByteArrayOutputStream();
        for (byte[] seg : tcpSegs) {
            check(!new String(seg, StandardCharsets.ISO_8859_1).contains("googlevideo.com"),
                    "TCP fragment must not contain unbroken googlevideo.com");
            tcpRe.write(seg, 0, seg.length);
        }
        check(Arrays.equals(ytHello, tcpRe.toByteArray()), "fragment() preserves bytes verbatim");

        // ColgramNativeDesync.prepareTlsOrRawPayload emits 3-record layout with valid cut2 boundary
        ColgramNativeDesync.PreparedDesync prep3 =
                ColgramNativeDesync.prepareTlsOrRawPayload(ytHello, 0, ytHello.length, true, true, false, 0);
        check(prep3.payload.length == ytHello.length + 10,
                "prepareTlsOrRawPayload(sniPlusOne=true) adds 2 TLS record headers (10B)");
        int cut1 = prep3.split1;
        check(cut1 + 5 < prep3.payload.length
                        && prep3.payload[cut1] == 0x16 && prep3.payload[cut1 + 1] == 0x03,
                "record 2 header starts at split1");
        int rec2Len = ((prep3.payload[cut1 + 3] & 0xFF) << 8) | (prep3.payload[cut1 + 4] & 0xFF);
        int cut2 = cut1 + 5 + rec2Len;
        check(cut2 + 5 < prep3.payload.length
                        && prep3.payload[cut2] == 0x16 && prep3.payload[cut2 + 1] == 0x03,
                "record 3 header starts right at cut2 (SLD midpoint)");

        ColgramNativeDesync.PreparedDesync prep2 =
                ColgramNativeDesync.prepareTlsOrRawPayload(ytHello, 0, ytHello.length, true, false, false, 0);
        check(prep2.payload.length == ytHello.length + 5,
                "prepareTlsOrRawPayload(sniPlusOne=false) uses 2-record SLD split (+5B)");
        check(!new String(prep2.payload, 0, prep2.split1, StandardCharsets.ISO_8859_1).contains("googlevideo.com")
                        && !new String(prep2.payload, prep2.split1, prep2.payload.length - prep2.split1,
                        StandardCharsets.ISO_8859_1).contains("googlevideo.com"),
                "2-record split1 severs googlevideo.com across both records");

        // 2. LocalDnsProxy blocks HTTPS (65) and SVCB (64) immediately with empty NOERROR
        AtomicInteger upstreamDnsCalls = new AtomicInteger(0);
        try (ColgramLocalDnsProxy dualStackDns = new ColgramLocalDnsProxy(false, wireQ -> {
            upstreamDnsCalls.incrementAndGet();
            byte[] resp = Arrays.copyOf(wireQ, wireQ.length + 16);
            resp[2] = (byte) 0x81; resp[3] = (byte) 0x80; resp[7] = 1;
            return resp;
        }); DatagramSocket cli = new DatagramSocket()) {
            cli.setSoTimeout(2000);
            InetAddress lo = InetAddress.getLoopbackAddress();
            for (int qtype : new int[]{65, 64}) {
                byte[] q = buildDnsQuery(0x7000 + qtype, "youtube.com", qtype);
                cli.send(new DatagramPacket(q, q.length, lo, dualStackDns.port()));
                byte[] rBuf = new byte[512];
                DatagramPacket rp = new DatagramPacket(rBuf, rBuf.length);
                cli.receive(rp);
                check((rBuf[0] & 0xFF) == ((0x7000 + qtype) >> 8)
                                && (rBuf[1] & 0xFF) == ((0x7000 + qtype) & 0xFF)
                                && (rBuf[2] & 0x80) != 0
                                && (rBuf[3] & 0x0F) == 0
                                && rBuf[6] == 0 && rBuf[7] == 0
                                && upstreamDnsCalls.get() == 0,
                        "LocalDnsProxy blocks qtype=" + qtype + " with empty NOERROR without upstream call");
            }
        }

        // 3. Google ECS DoH prioritization for YouTube/GoogleVideo/Discord Media
        check(ColgramDohResolver.prefersEcsDns(ytCdn), "prefersEcsDns true for googlevideo.com");
        check(ColgramDohResolver.prefersEcsDns("www.youtube.com"), "prefersEcsDns true for youtube.com");
        check(ColgramDohResolver.prefersEcsDns("cdn.discord.media"), "prefersEcsDns true for discord.media");
        check(!ColgramDohResolver.prefersEcsDns("example.com"), "prefersEcsDns false for generic domain");
        List<ColgramDohResolver.Endpoint> ytOrdered = ColgramDohResolver.orderedCandidatesForHost(ytCdn);
        check(!ytOrdered.isEmpty() && "google".equals(ytOrdered.get(0).providerGroup),
                "Google DoH ranked first for googlevideo.com, got=" + ytOrdered.get(0).ip);
        int googleCount = 0;
        int firstNonGoogle = -1;
        for (int i = 0; i < ytOrdered.size(); i++) {
            if ("google".equals(ytOrdered.get(i).providerGroup)) {
                googleCount++;
                check(firstNonGoogle == -1, "All Google DoH endpoints precede non-ECS resolvers");
            } else if (firstNonGoogle == -1) {
                firstNonGoogle = i;
            }
        }
        check(googleCount >= 2, "At least 8.8.8.8 and 8.8.4.4 prioritized first");

        // 4. MSS=88 protection on streaming/voice hosts + never globally promoted + domestic fast-path
        ColgramDpiBypass.resetStrategyStateForTest(true, ColgramDpiBypass.STRATEGY_NATIVE_MULTI_SPLIT_TLSREC);
        try {
            // Even if MSS=88 succeeds for a non-streaming host with promoteGlobal=true, it MUST NOT promote globally!
            ColgramDpiBypass.reportHostStrategyResult(
                    "blocked-site.example", ColgramDpiBypass.STRATEGY_NATIVE_MSS_CLAMP_TLSREC, true, true);
            ColgramDpiBypass.reportHostStrategyResult(
                    "blocked-site2.example", ColgramDpiBypass.STRATEGY_NATIVE_MSS64_OOB1_TLSREC, true, true);
            check(ColgramDpiBypass.cellularPreferredStrategyForTest()
                            == ColgramDpiBypass.STRATEGY_NATIVE_MULTI_SPLIT_TLSREC,
                    "MSS=88 must NEVER be promoted to cellularPreferredStrategy");

            for (String host : new String[]{"googlevideo.com", "discord.media", "youtube.com", "instagram.com"}) {
                check(ColgramDpiBypass.isStreamingOrVoiceDomain(host), "streaming domain matched: " + host);
                int[] plan = ColgramDpiBypass.buildReplayPlan(host, true, true, 14);
                check(plan.length > 0, "non-empty replay plan for " + host);
                for (int s : plan) {
                    check(ColgramDpiBypass.mssClampForStrategy(s) == 0,
                            "buildReplayPlan must exclude MSS=88 for " + host + ", found strat=" + s);
                }
            }

            check(ColgramDpiBypass.isDomesticDirectHost("vk.com"), "vk.com is domestic");
            check(ColgramDpiBypass.isDomesticDirectHost("api.vk.com"), "api.vk.com is domestic");
            check(ColgramDpiBypass.isDomesticDirectHost("gosuslugi.ru"), "gosuslugi.ru is domestic");
            check(ColgramDpiBypass.isDomesticDirectHost("site.xn--p1ai"), ".xn--p1ai is domestic");
            check(ColgramDpiBypass.isDomesticDirectHost("example.su"), ".su is domestic");
            check(ColgramDpiBypass.isDomesticDirectHost("mc.yandex.net"), "yandex.net is domestic");
            check(!ColgramDpiBypass.isDomesticDirectHost("googlevideo.com"), "googlevideo.com is NOT domestic");
            check(!ColgramDpiBypass.isDomesticDirectHost("discord.com"), "discord.com is NOT domestic");
            check(!ColgramDpiBypass.isDomesticDirectHost("notvk.com"), "notvk.com is NOT domestic");
        } finally {
            ColgramDpiBypass.resetStrategyStateForTest(false, ColgramDpiBypass.STRATEGY_SPLIT_1);
        }

        System.out.println("ColgramYoutubeBypassTest: " + checks + " checks PASS");
    }
}
