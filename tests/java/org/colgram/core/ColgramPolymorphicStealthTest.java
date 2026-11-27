package org.colgram.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for v6.0 ColgramPolymorphicStealth wire stealth codec and RFC 9001 QUIC v1 Initial crypto.
 */
public final class ColgramPolymorphicStealthTest {

    public static void main(String[] args) {
        testWhitelistSnisAndTelemetry();
        testPolymorphicWsUpgradeRequests();
        testPolymorphicTlsClientHelloAndContinuation();
        testPolymorphicQuicV1InitialCryptoAndTamperRejection();
        testVoiceUdpDecoysHttpCaseAndJitterHelpers();
        testPortTrackerAndSharedCdns();
        System.out.println("ColgramPolymorphicStealthTest PASSED");
    }

    private static void testWhitelistSnisAndTelemetry() {
        check(ColgramPolymorphicStealth.getBenignSniCount() == 28,
                "Expected 28 Russian benign SNIs, got=" + ColgramPolymorphicStealth.getBenignSniCount());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            String avoid = "vk.com";
            String sni = ColgramPolymorphicStealth.pickBenignSni(avoid);
            check(!avoid.equalsIgnoreCase(sni), "pickBenignSni(avoid) returned avoided SNI");
            seen.add(sni);
        }
        check(seen.size() >= 15, "pickBenignSni showed low diversity: " + seen.size());

        String ua = ColgramPolymorphicStealth.pickBrowserUserAgent();
        check(ua != null && ua.contains("Mozilla/5.0"), "Browser User-Agent missing Mozilla/5.0");
    }

    private static void testPolymorphicWsUpgradeRequests() {
        Set<String> reqs = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            String req = ColgramPolymorphicStealth.buildPolymorphicWsUpgradeRequest(
                    "kws2.web.telegram.org", "dGhlIHNhbXBsZSBub25jZQ==");
            check(req.startsWith("GET /apiws HTTP/1.1\r\nHost: kws2.web.telegram.org\r\n"),
                    "WS request must begin with GET /apiws + Host");
            check(req.contains("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"), "Sec-WebSocket-Key missing");
            check(req.contains("Upgrade: websocket\r\n"), "Upgrade header missing");
            check(req.endsWith("\r\n\r\n"), "HTTP headers must end with CRLFCRLF");
            reqs.add(req);
        }
        check(reqs.size() >= 5, "WS upgrade headers lacked polymorphism: unique=" + reqs.size());
    }

    private static void testPolymorphicTlsClientHelloAndContinuation() {
        Set<Integer> lengths = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            byte[] rec = ColgramPolymorphicStealth.buildPolymorphicTlsClientHello();
            check(rec.length >= 512 && rec.length <= 945, "TLS ClientHello length out of range: " + rec.length);
            check(rec[0] == 0x16 && rec[1] == 0x03 && rec[5] == 0x01, "Invalid TLS ClientHello header");
            int recLen = ((rec[3] & 0xFF) << 8) | (rec[4] & 0xFF);
            check(recLen + 5 == rec.length, "TLS record length mismatch");
            String extracted = ColgramTlsMimic.extractSniHostname(rec, rec.length);
            check(extracted != null && !extracted.isEmpty(), "Failed to extract SNI from polymorphic TLS ClientHello");
            lengths.add(rec.length);
        }
        check(lengths.size() >= 4, "TLS ClientHello length was static");

        for (int i = 0; i < 15; i++) {
            byte[] cont = ColgramPolymorphicStealth.buildPolymorphicContinuationDecoy();
            check(cont.length >= 192 && cont.length <= 385, "Continuation decoy size out of range: " + cont.length);
            byte[] http = ColgramPolymorphicStealth.buildPolymorphicFakeHttp();
            String httpStr = new String(http, StandardCharsets.US_ASCII);
            check(httpStr.startsWith("GET /") && httpStr.contains("HTTP/1.1\r\n") && httpStr.contains("Host: "),
                    "Invalid fake HTTP request");
        }
    }

    private static void testPolymorphicQuicV1InitialCryptoAndTamperRejection() {
        for (int i = 0; i < 10; i++) {
            String targetSni = ColgramPolymorphicStealth.pickBenignSni();
            byte[] pkt = ColgramPolymorphicStealth.buildPolymorphicQuicInitial(targetSni);
            check(pkt.length >= 1200 && pkt.length <= 1280, "QUIC Initial length out of range: " + pkt.length);

            ColgramPolymorphicStealth.QuicVerificationResult ok =
                    ColgramPolymorphicStealth.tryDecryptQuicInitialForVerification(pkt, pkt.length);
            check(ok.verified, "QUIC Initial self-verification failed at iter=" + i);
            check(ok.decryptedPayload != null && ok.decryptedPayload.length > 0, "Empty decrypted QUIC payload");
            check(targetSni.equals(ok.extractedSni),
                    "Decrypted QUIC SNI mismatch: expected=" + targetSni + " got=" + ok.extractedSni);

            byte[] tampered = Arrays.copyOf(pkt, pkt.length);
            tampered[tampered.length - 1] ^= 0x5A;
            ColgramPolymorphicStealth.QuicVerificationResult bad =
                    ColgramPolymorphicStealth.tryDecryptQuicInitialForVerification(tampered, tampered.length);
            check(!bad.verified, "Tampered QUIC Initial GCM tag must fail verification");
        }
    }

    private static void testVoiceUdpDecoysHttpCaseAndJitterHelpers() {
        Set<Integer> udpSizes = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            byte[] d = ColgramPolymorphicStealth.buildPolymorphicVoiceUdpDecoy();
            check(d.length >= 36 && d.length <= 72, "Voice decoy size out of range: " + d.length);
            check((d[0] & 0xC0) == 0x80, "Voice decoy must carry RTP-like 0x80 top bits");
            boolean stunCookie = d[4] == 0x21 && d[5] == 0x12 && d[6] == (byte) 0xA4 && d[7] == 0x42;
            check(!stunCookie, "Voice decoy must avoid STUN magic cookie");
            udpSizes.add(d.length);
        }
        check(udpSizes.size() >= 8, "Voice decoys need randomized lengths");

        byte[] sampleHttp = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        Set<String> hostHeaders = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            byte[] buf = Arrays.copyOf(sampleHttp, sampleHttp.length);
            ColgramPolymorphicStealth.randomizeHttpHostHeaderCase(buf, 16);
            String hdr = new String(buf, 16, 4, StandardCharsets.US_ASCII);
            check("host".equalsIgnoreCase(hdr) && !"Host".equals(hdr) && !"host".equals(hdr),
                    "Unexpected Host case variant: " + hdr);
            hostHeaders.add(hdr);
        }
        check(hostHeaders.size() >= 6, "Host casing should rotate across multiple masks");

        for (int i = 0; i < 50; i++) {
            byte oob = ColgramPolymorphicStealth.nextNonZeroOobByte();
            check(oob >= 1 && oob < 0x7F, "OOB byte out of range: " + oob);
            int split = ColgramPolymorphicStealth.jitterSplitOffset(10, 5, 15);
            check(split >= 8 && split <= 12, "Split jitter out of bounds: " + split);
            int us = ColgramPolymorphicStealth.jitterMicroFlushUs(200);
            check(us >= 150 && us <= 250, "Micro-flush jitter out of bounds: " + us);
        }
    }

    private static void testPortTrackerAndSharedCdns() {
        ColgramPolymorphicStealth.registerBypassOwnedPort(45678);
        check(ColgramPolymorphicStealth.isBypassOwnedLocalPort(45678), "Port should be tracked");
        ColgramPolymorphicStealth.releaseBypassOwnedPort(45678, 0);
        check(!ColgramPolymorphicStealth.isBypassOwnedLocalPort(45678), "Port with 0s grace must clear immediately");

        check(ColgramPolymorphicStealth.isMultiTenantSharedDomain("abc.cloudfront.net"), "cloudfront CDN match");
        check(ColgramPolymorphicStealth.isMultiTenantSharedDomain("myworker.workers.dev"), "workers.dev CDN match");
        check(!ColgramPolymorphicStealth.isMultiTenantSharedDomain("youtube.com"), "youtube not multi-tenant");

        String summary = ColgramPolymorphicStealth.getStealthStatsSummary();
        check(summary != null && summary.contains("v6.0 snis=28"), "Unexpected stealth summary: " + summary);
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
