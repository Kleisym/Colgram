package org.colgram.core;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class NetworkCoreTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError("NetworkCoreTest failed [" + checks + "]: " + label);
    }

    private static byte[] buildDnsAReply(String qname, int reportedAnswers, byte[]... ips) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x12); out.write(0x34); // ID
        out.write(0x81); out.write(0x80); // standard response, no error
        out.write(0x00); out.write(0x01); // QDCOUNT = 1
        out.write((reportedAnswers >> 8) & 0xff); out.write(reportedAnswers & 0xff);
        out.write(0x00); out.write(0x00); // NSCOUNT = 0
        out.write(0x00); out.write(0x00); // ARCOUNT = 0
        for (String label : qname.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.US_ASCII);
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        out.write(0x00);
        out.write(0x00); out.write(0x01); // QTYPE A
        out.write(0x00); out.write(0x01); // QCLASS IN
        for (byte[] ip : ips) {
            out.write(0xc0); out.write(0x0c); // pointer to question name
            out.write(0x00); out.write(0x01); // TYPE A
            out.write(0x00); out.write(0x01); // CLASS IN
            out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x3c); // TTL 60
            out.write(0x00); out.write(0x04); // RDLENGTH 4
            out.write(ip, 0, 4);
        }
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        // 1. DC table & IPv4/IPv6 mapping
        check(ColgramTelegramDcAddresses.dcIdFor("149.154.175.53") == 1, "DC1 IPv4");
        check(ColgramTelegramDcAddresses.dcIdFor("2001:b28:f23d:f001::a") == 1, "DC1 IPv6");
        check(ColgramTelegramDcAddresses.dcIdFor("149.154.167.51") == 2, "DC2 primary IPv4");
        check(ColgramTelegramDcAddresses.dcIdFor("95.161.76.100") == 2, "DC2 alt IPv4");
        check(ColgramTelegramDcAddresses.dcIdFor("149.154.175.100") == 3, "DC3 exact beats DC1 /24");
        check(ColgramTelegramDcAddresses.dcIdFor("149.154.174.100") == 3, "DC3 subnet");
        check(ColgramTelegramDcAddresses.dcIdFor("149.154.167.91") == 4, "DC4 exact beats DC2 /24");
        check(ColgramTelegramDcAddresses.dcIdFor("194.221.250.50") == 4, "DC4 alt");
        check(ColgramTelegramDcAddresses.dcIdFor("91.108.56.130") == 5, "DC5 IPv4");
        check(ColgramTelegramDcAddresses.dcIdFor("2001:b28:f23f:f005::a") == 5, "DC5 IPv6");
        check(ColgramTelegramDcAddresses.dcIdFor("185.76.151.1") == 2, "DC2 185.76.151.* subnet");
        check(ColgramTelegramDcAddresses.dcIdFor("149.154.175.117") == 3, "DC3 alt .117");
        check(ColgramTelegramDcAddresses.dcIdFor("8.8.8.8") == 0, "Unknown host dcId=0");
        check(!ColgramTelegramDcAddresses.isKnownAddress("8.8.8.8"), "8.8.8.8 unknown");
        check(ColgramTelegramDcAddresses.isKnownAddress("149.154.160.1"), "149.154.* known");
        check(ColgramTelegramDcAddresses.isKnownAddress("91.108.4.1"), "91.108.* known");
        check(ColgramTelegramDcAddresses.isKnownAddress("185.76.151.1"), "185.76.151.* known");

        // 1b. WebSocket bridge (ip, sniHost) pairing invariants
        java.util.Map<String, java.util.List<String>> hostIps = new java.util.LinkedHashMap<>();
        hostIps.put("kws1.web.telegram.org", java.util.Arrays.asList("149.154.174.100", "149.154.167.220"));
        hostIps.put("kws4.web.telegram.org", java.util.Collections.singletonList("149.154.167.220"));
        hostIps.put("vesta.web.telegram.org", java.util.Collections.singletonList("149.154.167.220"));
        java.util.List<ColgramTelegramWsBridge.EdgeEndpoint> dc1Eps =
                ColgramTelegramWsBridge.buildCandidateEndpoints(1, null, hostIps, java.util.Collections.emptySet());
        check(!dc1Eps.isEmpty(), "DC1 WS candidates non-empty");
        for (ColgramTelegramWsBridge.EdgeEndpoint ep : dc1Eps) {
            check(!("149.154.167.220".equals(ep.ip) && "kws1.web.telegram.org".equals(ep.sniHost)),
                    "Never pair shared 149.154.167.220 with non-matching kws1 SNI");
            check(!"vesta.web.telegram.org".equals(ep.sniHost) && !"kws2.web.telegram.org".equals(ep.sniHost),
                    "Never cross-wire DC1 to DC2/DC4 SNI: " + ep);
        }
        check(dc1Eps.contains(new ColgramTelegramWsBridge.EdgeEndpoint("149.154.174.100", "kws1.web.telegram.org")),
                "DC1 keeps DoH-resolved DC1 IP");
        ColgramTelegramWsBridge.EdgeEndpoint pinnedDc4 =
                new ColgramTelegramWsBridge.EdgeEndpoint("149.154.167.220", "vesta.web.telegram.org");
        ColgramTelegramWsBridge.EdgeEndpoint pinnedDc2 =
                new ColgramTelegramWsBridge.EdgeEndpoint("149.154.167.220", "kws2.web.telegram.org");
        java.util.List<ColgramTelegramWsBridge.EdgeEndpoint> dc2WithDc4Pinned =
                ColgramTelegramWsBridge.buildCandidateEndpoints(2, pinnedDc4, hostIps, java.util.Collections.emptySet());
        for (ColgramTelegramWsBridge.EdgeEndpoint ep : dc2WithDc4Pinned) {
            check(!"vesta.web.telegram.org".equals(ep.sniHost) && !"kws4.web.telegram.org".equals(ep.sniHost),
                    "DC2 NEVER sends traffic over DC4 SNI (avoids MTProto -444): " + ep);
        }
        java.util.List<ColgramTelegramWsBridge.EdgeEndpoint> dc4Fast =
                ColgramTelegramWsBridge.buildCandidateEndpoints(4, pinnedDc4, hostIps, java.util.Collections.emptySet());
        check(pinnedDc4.equals(dc4Fast.get(0)), "Pinned working DC4 edge wins first slot for DC4");
        java.util.Set<String> blocked = new java.util.HashSet<>(
                java.util.Collections.singletonList(pinnedDc4.key()));
        java.util.List<ColgramTelegramWsBridge.EdgeEndpoint> dc4AfterFail =
                ColgramTelegramWsBridge.buildCandidateEndpoints(4, pinnedDc4, hostIps, blocked);
        check(!pinnedDc4.equals(dc4AfterFail.get(0)), "Deprioritized endpoint is skipped");

        // Regression test: when DoH resolves ALL edge domains to a carrier-blocked IP (.99),
        // DC2 puts proven 149.154.167.220|kws2.web.telegram.org at front (index <= 1) and never uses vesta.
        java.util.Map<String, java.util.List<String>> blockedDoh = new java.util.LinkedHashMap<>();
        for (String h : new String[]{"kws2.web.telegram.org", "zws2.web.telegram.org", "venus.web.telegram.org",
                "vesta.web.telegram.org", "kws4.web.telegram.org", "zws4.web.telegram.org"}) {
            blockedDoh.put(h, java.util.Collections.singletonList("149.154.167.99"));
        }
        java.util.List<ColgramTelegramWsBridge.EdgeEndpoint> dc2WithBlockedDoh =
                ColgramTelegramWsBridge.buildCandidateEndpoints(2, null, blockedDoh, java.util.Collections.emptySet());
        int kws2Idx = dc2WithBlockedDoh.indexOf(pinnedDc2);
        check(kws2Idx >= 0 && kws2Idx <= 1, "Fallback 149.154.167.220|kws2 stays at front for DC2 even when DoH returns .99 (idx=" + kws2Idx + ")");

        // 2. DoH parser robustness (valid + compressed + truncated)
        byte[] reply = buildDnsAReply("venus.web.telegram.org", 2,
                new byte[]{(byte) 149, (byte) 154, (byte) 167, 99},
                new byte[]{(byte) 149, (byte) 154, (byte) 167, 100});
        String[] parsed = ColgramDohResolver.parseARecords(reply);
        check(Arrays.equals(parsed, new String[]{"149.154.167.99", "149.154.167.100"}), "DoH 2 A records");

        byte[] baseOne = buildDnsAReply("venus.web.telegram.org", 2,
                new byte[]{(byte) 149, (byte) 154, (byte) 167, 99});
        byte[] truncated = Arrays.copyOf(baseOne, baseOne.length + 5);
        truncated[truncated.length - 5] = (byte) 0xc0;
        truncated[truncated.length - 4] = 0x0c;
        String[] partial = ColgramDohResolver.parseARecords(truncated);
        check(Arrays.equals(partial, new String[]{"149.154.167.99"}), "DoH truncated 2nd RR preserves 1st");
        check(ColgramDohResolver.parseARecords(null).length == 0, "DoH null input");
        check(ColgramDohResolver.parseARecords(new byte[8]).length == 0, "DoH short input");

        // 3. Obfuscated2 DC-ID patch verification
        byte[] init = new byte[64];
        new Random(42).nextBytes(init);
        byte[] key = Arrays.copyOfRange(init, 8, 40);
        byte[] iv = Arrays.copyOfRange(init, 40, 56);
        Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
        ctr.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] ks = ctr.doFinal(new byte[64]);
        byte[] plainTail = new byte[]{(byte) 0xef, (byte) 0xef, (byte) 0xef, (byte) 0xef, 0, 0, 0x11, 0x22};
        for (int i = 0; i < 8; i++) {
            init[56 + i] = (byte) (plainTail[i] ^ ks[56 + i]);
        }
        check(ColgramTelegramWsBridge.patchObfuscated2Header(init, 64, 4), "Obfuscated2 valid header patched");
        byte[] outPlain = new byte[8];
        for (int i = 0; i < 8; i++) {
            outPlain[i] = (byte) (init[56 + i] ^ ks[56 + i]);
        }
        check((outPlain[4] & 0xff) == 4 && outPlain[5] == 0, "Obfuscated2 dc_id=4 encoded");
        check(outPlain[6] == 0x11 && outPlain[7] == 0x22, "Obfuscated2 bytes 62..63 untouched");

        byte[] bad = init.clone();
        bad[56] ^= 0x55;
        check(!ColgramTelegramWsBridge.patchObfuscated2Header(bad, 64, 4), "Obfuscated2 rejects wrong magic");

        // 4. Strict RFC 6125 certificate matcher & DoH/isolation invariants
        check(ColgramDohResolver.matchesDnsPattern("dns.google", "dns.google"), "RFC6125 exact match");
        check(ColgramDohResolver.matchesDnsPattern("*.telegram.org", "web.telegram.org"), "RFC6125 wildcard single-label match");
        check(!ColgramDohResolver.matchesDnsPattern("*.telegram.org", "a.b.telegram.org"), "RFC6125 wildcard rejects multi-label");
        check(!ColgramDohResolver.matchesDnsPattern("telegram.org", "eviltelegram.org"), "RFC6125 rejects substring spoofing");
        check("dns.google".equals(ColgramDohResolver.extractCommonName("O=Google LLC,CN=dns.google,C=US")), "RFC2253 CN extraction");
        check(ColgramDohResolver.isIpLiteral("149.154.167.51") && ColgramDohResolver.isIpLiteral("2001:67c:4e8:f004::a"), "IP literal detection");
        check(!ColgramDohResolver.isIpLiteral("web.telegram.org"), "hostname is not IP literal");

        // 5. Isolation defaults & Browser Route Policy
        check(!ColgramConfig.isAutoProxyEnabled(), "Auto-proxy defaults OFF (own internet only)");
        check(ColgramConfig.isStrictProxySecurityEnabled(), "Strict proxy security defaults ON");
        check(ColgramBrowserRoutePolicy.fromPreference(null) == ColgramBrowserRoutePolicy.Mode.LOCAL_DPI, "Browser route defaults to LOCAL_DPI");
        ColgramBrowserRoutePolicy.Decision dpiDecision = ColgramBrowserRoutePolicy.evaluate(
                ColgramBrowserRoutePolicy.Mode.LOCAL_DPI, 9876, false, false, 0, false, false, true);
        check("socks5://127.0.0.1:9876".equals(dpiDecision.proxyRule) && dpiDecision.tunnelUp && !dpiDecision.strictHttpsOnly, "Browser LOCAL_DPI uses local SOCKS5 without 3rd-party restriction");
        ColgramBrowserRoutePolicy.Decision socksDecision = ColgramBrowserRoutePolicy.evaluate(
                ColgramBrowserRoutePolicy.Mode.SOCKS5, 9876, false, false, 41234, true, true, true);
        check("socks5://127.0.0.1:41234".equals(socksDecision.proxyRule) && socksDecision.strictHttpsOnly, "Browser 3rd-party SOCKS5 enforces strict HTTPS-only");
        ColgramBrowserRoutePolicy.Decision warpDecision = ColgramBrowserRoutePolicy.evaluate(
                ColgramBrowserRoutePolicy.Mode.WARP, 9876, true, false, 0, false, false, true);
        check(warpDecision.proxyRule == null && warpDecision.tunnelUp, "Browser WARP clears WebView ProxyController override");

        // 6. Obfuscated2FrameSplitter packet boundary splitting & ciphertext integrity
        byte[] sInit = new byte[64];
        new Random(77).nextBytes(sInit);
        byte[] sKey = Arrays.copyOfRange(sInit, 8, 40);
        byte[] sIv = Arrays.copyOfRange(sInit, 40, 56);
        Cipher enc = Cipher.getInstance("AES/CTR/NoPadding");
        enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sKey, "AES"), new IvParameterSpec(sIv));
        byte[] encHdr = enc.update(new byte[64]);
        // Re-derive true encryptor over sInit with 0xef magic
        byte[] rawStreamSeed = sInit.clone();
        rawStreamSeed[56] = (byte) 0xef; rawStreamSeed[57] = (byte) 0xef;
        rawStreamSeed[58] = (byte) 0xef; rawStreamSeed[59] = (byte) 0xef;
        rawStreamSeed[60] = 0; rawStreamSeed[61] = 0;
        Cipher realEnc = Cipher.getInstance("AES/CTR/NoPadding");
        realEnc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sKey, "AES"), new IvParameterSpec(sIv));
        byte[] hdrCipher = realEnc.update(rawStreamSeed);
        System.arraycopy(hdrCipher, 56, sInit, 56, 8);

        byte[] p1 = new byte[41]; p1[0] = 10; // 10*4 = 40B payload
        byte[] p2 = new byte[37]; p2[0] = (byte) (0x80 | 9); // quickAck + 9*4 = 36B
        byte[] p3 = new byte[345]; p3[0] = 86; // 86*4 = 344B
        byte[] p4 = new byte[564]; // 4-byte 0xff (quickAck + 0x7f) + 140*4 = 560B
        p4[0] = (byte) 0xff;
        p4[1] = (byte) 140;
        p4[2] = 0;
        p4[3] = 0;

        byte[] c1 = realEnc.update(p1);
        byte[] c2 = realEnc.update(p2);
        byte[] c3 = realEnc.update(p3);
        byte[] c4 = realEnc.update(p4);

        byte[] initWithP1 = new byte[64 + c1.length];
        System.arraycopy(sInit, 0, initWithP1, 0, 64);
        System.arraycopy(c1, 0, initWithP1, 64, c1.length);

        ColgramTelegramWsBridge.Obfuscated2FrameSplitter splitter =
                ColgramTelegramWsBridge.Obfuscated2FrameSplitter.create(initWithP1, initWithP1.length, 2);
        check(splitter != null, "Obfuscated2FrameSplitter.create succeeds on 0xef");
        List<byte[]> emitted = new ArrayList<>();
        ColgramTelegramWsBridge.FrameConsumer rec = (b, o, l) -> emitted.add(Arrays.copyOfRange(b, o, o + l));
        splitter.feedInitAndTail(initWithP1, initWithP1.length, rec);
        check(emitted.size() == 2 && emitted.get(0).length == 64 && Arrays.equals(emitted.get(1), c1),
                "Splitter emits 64B header + first packet separately");

        // Feed coalesced 37B + 345B = 382B in ONE call (exact Handshake.cpp scenario)
        byte[] coalesced382 = new byte[c2.length + c3.length];
        System.arraycopy(c2, 0, coalesced382, 0, c2.length);
        System.arraycopy(c3, 0, coalesced382, c2.length, c3.length);
        splitter.feed(coalesced382, 0, coalesced382.length, rec);
        check(emitted.size() == 4
                        && Arrays.equals(emitted.get(2), c2)
                        && Arrays.equals(emitted.get(3), c3),
                "Splitter un-coalesces 382B into exact 37B quickAck + 345B req_DH_params ciphertext");

        // Feed 4-byte 0xff extended header fragmented 2 bytes, then 3 bytes, then rest
        splitter.feed(c4, 0, 2, rec);
        splitter.feed(c4, 2, 3, rec);
        splitter.feed(c4, 5, c4.length - 5, rec);
        check(emitted.size() == 5 && Arrays.equals(emitted.get(4), c4),
                "Splitter reassembles fragmented 4-byte 0xff header packet verbatim");

        // Verify 0xdd (PaddedIntermediate) coalescing + bit-31 quickAck
        byte[] dSeed = sInit.clone();
        new Random(99).nextBytes(dSeed);
        byte[] dKey = Arrays.copyOfRange(dSeed, 8, 40);
        byte[] dIv = Arrays.copyOfRange(dSeed, 40, 56);
        dSeed[56] = (byte) 0xdd; dSeed[57] = (byte) 0xdd; dSeed[58] = (byte) 0xdd; dSeed[59] = (byte) 0xdd;
        Cipher dEnc = Cipher.getInstance("AES/CTR/NoPadding");
        dEnc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dKey, "AES"), new IvParameterSpec(dIv));
        byte[] dHdrCipher = dEnc.update(dSeed);
        System.arraycopy(dHdrCipher, 56, dSeed, 56, 8);
        byte[] dp1 = new byte[4 + 27]; dp1[0] = 27;
        byte[] dp2 = new byte[4 + 19]; dp2[0] = 19; dp2[3] = (byte) 0x80; // quickAck bit 31
        byte[] dc1 = dEnc.update(dp1);
        byte[] dc2 = dEnc.update(dp2);
        byte[] dCoal = new byte[dc1.length + dc2.length];
        System.arraycopy(dc1, 0, dCoal, 0, dc1.length);
        System.arraycopy(dc2, 0, dCoal, dc1.length, dc2.length);
        ColgramTelegramWsBridge.Obfuscated2FrameSplitter dSplit =
                ColgramTelegramWsBridge.Obfuscated2FrameSplitter.create(dSeed, 64, 4);
        List<byte[]> dOut = new ArrayList<>();
        dSplit.feedInitAndTail(dSeed, 64, (b, o, l) -> dOut.add(Arrays.copyOfRange(b, o, o + l)));
        dSplit.feed(dCoal, 0, dCoal.length, (b, o, l) -> dOut.add(Arrays.copyOfRange(b, o, o + l)));
        check(dOut.size() == 3 && Arrays.equals(dOut.get(1), dc1) && Arrays.equals(dOut.get(2), dc2),
                "Splitter splits 0xdd PaddedIntermediate coalesced packets with quickAck bit");
        check(encHdr.length == 64, "Helper sanity");

        // 7. Per-DC WS cooldown isolation: blocked DC5 must never poison healthy DC2/DC4
        ColgramTelegramWsBridge.clearCooldowns();
        for (int i = 0; i < 5; i++) {
            ColgramTelegramWsBridge.recordFailure(5);
        }
        check(ColgramTelegramWsBridge.isInCooldown(5), "DC5 enters cooldown after 5 consecutive failures");
        check(!ColgramTelegramWsBridge.isInCooldown(2), "DC5 failures must NOT poison DC2 cooldown");
        check(!ColgramTelegramWsBridge.isInCooldown(4), "DC5 failures must NOT poison DC4 cooldown");
        ColgramTelegramWsBridge.clearCooldowns();
        check(!ColgramTelegramWsBridge.isInCooldown(5), "clearCooldowns clears DC5 cooldown");

        System.out.println("NetworkCoreTest: " + checks + " checks PASS");
    }
}
