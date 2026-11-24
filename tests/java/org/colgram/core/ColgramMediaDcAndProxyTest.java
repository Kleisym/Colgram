package org.colgram.core;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Isolated pure-JVM unit tests for media_only DC routing, signed obfuscated2 patching,
 * 12-bucket WSS isolation, and ColgramProxyPolicy ping cap + fast-first ordering.
 */
public final class ColgramMediaDcAndProxyTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("ColgramMediaDcAndProxyTest failed [" + checks + "]: " + message);
    }

    private static boolean containsAddr(String[] addrs, String target) {
        if (addrs == null) return false;
        for (String a : addrs) {
            if (target.equalsIgnoreCase(a)) return true;
        }
        return false;
    }

    private static byte[] buildUnpatchedObfuscated2Header(long seed, int protoByte, byte b60, byte b61, byte b62, byte b63) throws Exception {
        byte[] hdr = new byte[64];
        new Random(seed).nextBytes(hdr);
        byte[] key = Arrays.copyOfRange(hdr, 8, 40);
        byte[] iv = Arrays.copyOfRange(hdr, 40, 56);
        Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
        ctr.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] ks = ctr.doFinal(new byte[64]);
        byte[] plainTail = new byte[]{
                (byte) protoByte, (byte) protoByte, (byte) protoByte, (byte) protoByte,
                b60, b61, b62, b63
        };
        for (int i = 0; i < 8; i++) {
            hdr[56 + i] = (byte) (plainTail[i] ^ ks[56 + i]);
        }
        return hdr;
    }

    private static byte[] decryptTail(byte[] hdr) throws Exception {
        byte[] key = Arrays.copyOfRange(hdr, 8, 40);
        byte[] iv = Arrays.copyOfRange(hdr, 40, 56);
        Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
        ctr.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] ks = ctr.doFinal(new byte[64]);
        byte[] plain = new byte[8];
        for (int i = 0; i < 8; i++) {
            plain[i] = (byte) (hdr[56 + i] ^ ks[56 + i]);
        }
        return plain;
    }

    public static void main(String[] args) throws Exception {
        ColgramTelegramDcAddresses.clearDynamicMapForTest();
        try {
            // 1. Cold-start signedDcIdFor mapping (exact media_only vs generic)
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.167.43") == -4, "149.154.167.43 -> -4");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.167.222") == -2, "149.154.167.222 -> -2");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.167.41") == 2, "149.154.167.41 -> +2");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.167.151") == -2, "149.154.167.151 -> -2");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.167.92") == -4, "149.154.167.92 -> -4");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.165.120") == -4, "149.154.165.120 -> -4");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.175.54") == -1, "149.154.175.54 -> -1");
            check(ColgramTelegramDcAddresses.signedDcIdFor("91.108.56.131") == -5, "91.108.56.131 -> -5");
            check(ColgramTelegramDcAddresses.signedDcIdFor("91.108.56.140") == -5, "91.108.56.140 -> -5");
            check(ColgramTelegramDcAddresses.signedDcIdFor("91.108.56.170") == -5, "91.108.56.170 -> -5");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.175.50") == 1, "149.154.175.50 -> +1");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.175.53") == 1, "149.154.175.53 -> +1");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.175.56") == 1, "149.154.175.56 -> +1");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.167.50") == 2, "149.154.167.50 -> +2");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.167.51") == 2, "149.154.167.51 -> +2");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.175.100") == 3, "149.154.175.100 -> +3");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.167.91") == 4, "149.154.167.91 -> +4");
            check(ColgramTelegramDcAddresses.signedDcIdFor("91.108.56.112") == 5, "91.108.56.112 -> +5");
            check(ColgramTelegramDcAddresses.signedDcIdFor("91.108.56.130") == 5, "91.108.56.130 -> +5");

            check(ColgramTelegramDcAddresses.isMediaOnlyHost("149.154.167.43"), "149.154.167.43 is media_only");
            check(ColgramTelegramDcAddresses.isMediaOnlyHost("149.154.167.222"), "149.154.167.222 is media_only");
            check(!ColgramTelegramDcAddresses.isMediaOnlyHost("149.154.167.41"), "149.154.167.41 is generic");
            check(ColgramTelegramDcAddresses.dcIdFor("149.154.167.43") == 4, "dcIdFor abs(-4) == 4");
            check(ColgramTelegramDcAddresses.dcIdFor("149.154.167.222") == 2, "dcIdFor abs(-2) == 2");
            check(ColgramTelegramDcAddresses.signedDcIdFor("2001:67c:4e8:f004::b") == -4, "IPv6 DC4 media_only -> -4");
            check(ColgramTelegramDcAddresses.signedDcIdFor("2001:67c:4e8:f004:0:0:0:b") == -4, "Expanded IPv6 DC4 media_only -> -4");

            // 2. candidatesFor non-mixing (generic vs media_only)
            String[] genDc4 = ColgramTelegramDcAddresses.candidatesFor("149.154.167.91");
            check(containsAddr(genDc4, "149.154.167.91"), "generic DC4 includes self");
            check(!containsAddr(genDc4, "149.154.167.92"), "generic DC4 NEVER mixes media 149.154.167.92");
            check(!containsAddr(genDc4, "149.154.167.43"), "generic DC4 NEVER mixes media 149.154.167.43");
            check(!containsAddr(genDc4, "149.154.165.120"), "generic DC4 NEVER mixes media 149.154.165.120");

            String[] mediaDc4 = ColgramTelegramDcAddresses.candidatesFor("149.154.167.43");
            check(mediaDc4.length >= 3 && "149.154.167.43".equals(mediaDc4[0]), "media DC4 returns requested host first");
            check(containsAddr(mediaDc4, "149.154.167.92"), "media DC4 includes sibling media .92");
            check(containsAddr(mediaDc4, "149.154.165.120"), "media DC4 includes sibling media .120");
            check(!containsAddr(mediaDc4, "149.154.167.91"), "media DC4 NEVER mixes generic .91");
            check(!containsAddr(mediaDc4, "194.221.250.50"), "media DC4 NEVER mixes generic 194.221.250.50");

            String[] mediaDc2 = ColgramTelegramDcAddresses.candidatesFor("149.154.167.222");
            check(mediaDc2.length >= 2 && "149.154.167.222".equals(mediaDc2[0]), "media DC2 returns requested host first");
            check(containsAddr(mediaDc2, "149.154.167.151"), "media DC2 includes sibling media .151");
            check(!containsAddr(mediaDc2, "149.154.167.41"), "media DC2 NEVER mixes generic .41");
            check(!containsAddr(mediaDc2, "149.154.167.50"), "media DC2 NEVER mixes generic .50");
            check(!containsAddr(mediaDc2, "149.154.167.51"), "media DC2 NEVER mixes generic .51");

            String[] genDc2 = ColgramTelegramDcAddresses.candidatesFor("149.154.167.41");
            check(!containsAddr(genDc2, "149.154.167.222") && !containsAddr(genDc2, "149.154.167.151"),
                    "generic DC2 NEVER mixes media .222 or .151");

            // 3. updateFromDcOptions / applyDcOptions (generic, media_only, CDN -> 0)
            ColgramTelegramDcAddresses.applyDcOptions(
                    new int[]{4, 2, 203, 4},
                    new String[]{"149.154.167.205", "149.154.167.206", "149.154.167.207", "2001:67c:4e8:f004::c"},
                    new boolean[]{true, false, false, true},
                    new boolean[]{false, false, true, false},
                    new boolean[]{false, false, false, true}
            );
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.167.205") == -4,
                    "dynamic dc_options media_only DC4 overrides /24 heuristic");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.167.206") == 2,
                    "dynamic dc_options generic DC2 stored as +2");
            check(ColgramTelegramDcAddresses.signedDcIdFor("149.154.167.207") == 0,
                    "dynamic dc_options CDN IP stored as 0 (overrides 149.154.167.* prefix)");
            check(ColgramTelegramDcAddresses.dcIdFor("149.154.167.207") == 0,
                    "CDN IP has dcIdFor == 0");
            check(!ColgramTelegramWsBridge.supports("149.154.167.207", 443),
                    "WSS bridge refuses CDN IP");
            check(ColgramTelegramWsBridge.dial("149.154.167.207", 443) == null,
                    "WSS bridge dial returns null immediately for CDN IP");
            check(ColgramTelegramDcAddresses.signedDcIdFor("2001:67c:4e8:f004:0:0:0:c") == -4,
                    "dynamic IPv6 media_only normalized cleanly");
            String[] cdnCands = ColgramTelegramDcAddresses.candidatesFor("149.154.167.207");
            check(cdnCands.length == 1 && "149.154.167.207".equals(cdnCands[0]),
                    "CDN IP candidatesFor stays isolated to itself");
            String[] dynMediaDc4 = ColgramTelegramDcAddresses.candidatesFor("149.154.167.205");
            check("149.154.167.205".equals(dynMediaDc4[0]) && containsAddr(dynMediaDc4, "149.154.167.43")
                            && !containsAddr(dynMediaDc4, "149.154.167.91")
                            && !containsAddr(dynMediaDc4, "149.154.167.207"),
                    "dynamic media DC4 pairs only with media DC4, never generic or CDN");
        } finally {
            ColgramTelegramDcAddresses.clearDynamicMapForTest();
        }

        // 4. primaryWssHostForSignedDc, hostCandidatesForDc, and 12-bucket isolation
        for (int dc = 1; dc <= 5; dc++) {
            check(("kws" + dc + ".web.telegram.org").equals(ColgramTelegramWsBridge.primaryWssHostForSignedDc(dc)),
                    "primaryWssHostForSignedDc(+" + dc + ") == kws" + dc);
            check(("kws" + dc + "-1.web.telegram.org").equals(ColgramTelegramWsBridge.primaryWssHostForSignedDc(-dc)),
                    "primaryWssHostForSignedDc(-" + dc + ") == kws" + dc + "-1");
            String[] genHosts = ColgramTelegramWsBridge.hostCandidatesForDc(dc, false);
            String[] medHosts = ColgramTelegramWsBridge.hostCandidatesForDc(dc, true);
            check(("kws" + dc + ".web.telegram.org").equals(genHosts[0]),
                    "hostCandidatesForDc(" + dc + ", false) starts with kws" + dc);
            check(("kws" + dc + "-1.web.telegram.org").equals(medHosts[0]),
                    "hostCandidatesForDc(" + dc + ", true) starts with kws" + dc + "-1");
            check(ColgramTelegramWsBridge.bucketFor(dc, false) == dc,
                    "generic bucket " + dc);
            check(ColgramTelegramWsBridge.bucketFor(dc, true) == dc + 5,
                    "media bucket " + (dc + 5));
            check(ColgramDpiBypass.dcBucketForSignedDc(dc) == dc,
                    "DPI bypass generic bucket " + dc);
            check(ColgramDpiBypass.dcBucketForSignedDc(-dc) == dc + 5,
                    "DPI bypass media bucket " + (dc + 5));
        }

        // Verify media endpoints rank kws{dc}-1 first on 149.154.167.220
        List<ColgramTelegramWsBridge.EdgeEndpoint> dc2MediaEps =
                ColgramTelegramWsBridge.buildCandidateEndpoints(2, null, Collections.emptyMap(), Collections.emptySet(), true);
        check(!dc2MediaEps.isEmpty()
                        && "149.154.167.220".equals(dc2MediaEps.get(0).ip)
                        && "kws2-1.web.telegram.org".equals(dc2MediaEps.get(0).sniHost),
                "DC2 media WSS endpoints start with kws2-1@149.154.167.220, got=" + dc2MediaEps.get(0));
        List<ColgramTelegramWsBridge.EdgeEndpoint> dc4MediaEps =
                ColgramTelegramWsBridge.buildCandidateEndpoints(4, null, Collections.emptyMap(), Collections.emptySet(), true);
        check(!dc4MediaEps.isEmpty()
                        && "149.154.167.220".equals(dc4MediaEps.get(0).ip)
                        && "kws4-1.web.telegram.org".equals(dc4MediaEps.get(0).sniHost),
                "DC4 media WSS endpoints start with kws4-1@149.154.167.220, got=" + dc4MediaEps.get(0));

        // Verify generic vs media cooldown bucket isolation
        ColgramTelegramWsBridge.clearCooldowns();
        for (int i = 0; i < 5; i++) {
            ColgramTelegramWsBridge.recordFailure(-2);
        }
        check(ColgramTelegramWsBridge.isInCooldown(-2), "DC -2 media enters cooldown after 5 failures");
        check(!ColgramTelegramWsBridge.isInCooldown(2), "DC -2 media cooldown must NOT poison DC +2 generic");
        check(!ColgramTelegramWsBridge.isInCooldown(-4), "DC -2 media cooldown must NOT poison DC -4 media");
        ColgramTelegramWsBridge.clearCooldowns();

        // 5. Obfuscated2FrameSplitter negative DC patching (-2, -4) when bytes [60..61] are random/uninitialized
        for (int wireDc : new int[]{-2, -4, 2, 4}) {
            byte[] hdr = buildUnpatchedObfuscated2Header(1000L + wireDc, 0xef, (byte) 0x77, (byte) 0x33, (byte) 0x42, (byte) 0x99);
            ColgramTelegramWsBridge.Obfuscated2FrameSplitter splitter =
                    ColgramTelegramWsBridge.Obfuscated2FrameSplitter.create(hdr, 64, wireDc);
            check(splitter != null, "Obfuscated2FrameSplitter.create succeeded for wireDc=" + wireDc);
            check(splitter.rawDcId() == wireDc, "splitter.rawDcId() == " + wireDc + ", got=" + splitter.rawDcId());
            check(splitter.isMediaSession() == (wireDc < 0), "isMediaSession matches sign for " + wireDc);
            check(ColgramTelegramWsBridge.extractRawDcFromObfuscated2(hdr, 64) == wireDc,
                    "extractRawDcFromObfuscated2 decodes patched wireDc=" + wireDc);
            byte[] plainTail = decryptTail(hdr);
            int decodedLe16 = (short) ((plainTail[4] & 0xff) | ((plainTail[5] & 0xff) << 8));
            check(decodedLe16 == wireDc, "decrypted bytes [60..61] match little-endian int16 " + wireDc + ", got=" + decodedLe16);
            check(plainTail[6] == (byte) 0x42 && plainTail[7] == (byte) 0x99,
                    "bytes [62..63] preserved verbatim for wireDc=" + wireDc);
        }

        // 6. ColgramProxyPolicy ping cap (MAX_SELECTABLE_PING_MS=3000, FAST_PING_THRESHOLD_MS=1200) & fast-first ordering
        check(ColgramProxyPolicy.MAX_SELECTABLE_PING_MS == 3_000, "MAX_SELECTABLE_PING_MS == 3000");
        check(ColgramProxyPolicy.FAST_PING_THRESHOLD_MS == 1_200, "FAST_PING_THRESHOLD_MS == 1200");

        ColgramProxyPolicy.State fastProxy = new ColgramProxyPolicy.State(true, true, 180, 120, 0, "fast.example", 443, 1);
        ColgramProxyPolicy.State borderFast = new ColgramProxyPolicy.State(true, true, 1_200, 300, 0, "border-fast.example", 443, 1);
        ColgramProxyPolicy.State slowProxy = new ColgramProxyPolicy.State(true, true, 1_201, 200, 0, "slow.example", 443, 1);
        ColgramProxyPolicy.State capProxy = new ColgramProxyPolicy.State(true, true, 3_000, 500, 0, "cap.example", 443, 1);
        ColgramProxyPolicy.State tooSlowProxy = new ColgramProxyPolicy.State(true, true, 3_001, 100, 0, "tooslow.example", 443, 1);
        ColgramProxyPolicy.State tcpOnly = new ColgramProxyPolicy.State(false, false, -1, 20, 0, "tcp.example", 443, 0);
        ColgramProxyPolicy.State unchecked = new ColgramProxyPolicy.State(false, false, -1, -1, 0, "unchecked.example", 443, 0);
        ColgramProxyPolicy.State failed = new ColgramProxyPolicy.State(false, false, -2, -2, 0, "failed.example", 443, 0);
        ColgramProxyPolicy.State quarantined = new ColgramProxyPolicy.State(true, true, 50, 50, 5_000, "quar.example", 443, 1);

        check(ColgramProxyPolicy.isSelectable(fastProxy, 900, 1_000, 60_000), "180ms verified proxy selectable");
        check(ColgramProxyPolicy.isSelectable(borderFast, 900, 1_000, 60_000), "1200ms verified proxy selectable");
        check(ColgramProxyPolicy.isSelectable(slowProxy, 900, 1_000, 60_000), "1201ms verified proxy selectable");
        check(ColgramProxyPolicy.isSelectable(capProxy, 900, 1_000, 60_000), "3000ms boundary selectable");
        check(!ColgramProxyPolicy.isSelectable(tooSlowProxy, 900, 1_000, 60_000), "3001ms exceeds MAX_SELECTABLE_PING_MS");
        check(!ColgramProxyPolicy.isSelectable(new ColgramProxyPolicy.State(true, true, 3_500, 100, 0, "huge.example", 443, 1),
                900, 1_000, 60_000), "3500ms rejected");

        check(fastProxy.tier(1_000) == 0 && borderFast.tier(1_000) == 0, "fast verified proxies are tier 0");
        check(slowProxy.tier(1_000) == 1 && capProxy.tier(1_000) == 1, "slow verified proxies are tier 1");
        check(tcpOnly.tier(1_000) == 2, "TCP-only is tier 2");
        check(unchecked.tier(1_000) == 3, "unchecked is tier 3");
        check(failed.tier(1_000) == 4, "failed is tier 4");
        check(quarantined.tier(1_000) == 5, "quarantined is tier 5");

        check(ColgramProxyPolicy.compare(fastProxy, borderFast, 1_000) < 0, "180ms < 1200ms within tier 0");
        check(ColgramProxyPolicy.compare(borderFast, slowProxy, 1_000) < 0, "tier 0 (1200ms) < tier 1 (1201ms)");
        check(ColgramProxyPolicy.compare(slowProxy, capProxy, 1_000) < 0, "1201ms < 3000ms within tier 1");
        check(ColgramProxyPolicy.compare(capProxy, tcpOnly, 1_000) < 0, "verified slow (tier 1) < TCP-only (tier 2)");
        check(ColgramProxyPolicy.compare(tcpOnly, unchecked, 1_000) < 0, "TCP-only (tier 2) < unchecked (tier 3)");
        check(ColgramProxyPolicy.compare(unchecked, failed, 1_000) < 0, "unchecked (tier 3) < failed (tier 4)");
        check(ColgramProxyPolicy.compare(failed, quarantined, 1_000) < 0, "failed (tier 4) < quarantined (tier 5)");

        System.out.println("ColgramMediaDcAndProxyTest: " + checks + " checks PASS");
    }
}
