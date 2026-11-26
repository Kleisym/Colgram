package org.colgram.core;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Unit tests for ColgramUpdater semver comparison and SHA-256 verification helpers. */
public final class ColgramUpdaterTest {
    private static void check(boolean condition, String msg) {
        if (!condition) {
            throw new AssertionError(msg);
        }
    }

    private static void testParseAndCompareSemver() {
        check(Arrays.equals(new int[]{12, 10, 6}, ColgramUpdatePolicy.parseSemver("12.10.6")),
                "plain semver");
        check(Arrays.equals(new int[]{12, 10, 6}, ColgramUpdatePolicy.parseSemver("v12.10.6")),
                "v-prefixed semver");
        check(Arrays.equals(new int[]{12, 11, 0},
                        ColgramUpdatePolicy.parseSemver("colgram-release-12.11.0_5900-42")),
                "ci tag semver");
        check(ColgramUpdatePolicy.parseSemver(null).length == 0, "null version -> empty");
        check(ColgramUpdatePolicy.parseSemver("").length == 0, "empty version -> empty");

        check(ColgramUpdatePolicy.compareSemver("12.10.6", "12.10.6") == 0, "equal semvers");
        check(ColgramUpdatePolicy.compareSemver("12.10.0", "12.10") == 0, "trailing zero padding");
        check(ColgramUpdatePolicy.compareSemver("12.10", "12.10.1") < 0, "12.10 < 12.10.1");
        check(ColgramUpdatePolicy.compareSemver("12.10.1", "12.10") > 0, "12.10.1 > 12.10");
        check(ColgramUpdatePolicy.compareSemver("v12.10.6-ci42", "12.10.6") == 0, "suffix ignored in semver");
        check(ColgramUpdatePolicy.compareSemver(
                "12.10.6-colgram.mumu-call-routes-20261114", "release-12.10.6_5900") == 0,
                "gradle APP_VERSION_NAME vs upstream release tag");
        check(ColgramUpdatePolicy.compareSemver("colgram-12.11.0-1", "12.10.9") > 0, "12.11.0 > 12.10.9");
    }

    private static void testIsNewerTag() {
        check(ColgramUpdatePolicy.isNewerTag("12.10.7", "12.10.6"), "newer patch");
        check(ColgramUpdatePolicy.isNewerTag("colgram-release-12.11.0-5", "12.10.6"), "newer ci tag");
        check(!ColgramUpdatePolicy.isNewerTag("v12.10.6", "12.10.6"), "same version with v prefix");
        check(!ColgramUpdatePolicy.isNewerTag("12.10.5", "12.10.6"), "older version rejected");
        check(!ColgramUpdatePolicy.isNewerTag("12.10", "12.10.1"), "shorter older version rejected");
        check(ColgramUpdatePolicy.isNewerTag("12.10.6-ci43", "12.10.6-ci42"), "newer ci build");
        check(ColgramUpdatePolicy.isNewerTag("12.10.6-colgram.ci43", "12.10.6-colgram.ci42"), "newer colgram dotted ci build");
        check(!ColgramUpdatePolicy.isNewerTag("12.10.6-ci42", "12.10.6-ci42"), "same ci build");
        check(!ColgramUpdatePolicy.isNewerTag("", "12.10.6"), "empty remote tag");
    }

    private static void testSha256Helpers() throws Exception {
        String helloDigest = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
        check(helloDigest.equals(ColgramUpdatePolicy.sha256Hex("hello".getBytes(StandardCharsets.UTF_8))),
                "sha256Hex bytes mismatch");

        String extractedUpper = ColgramUpdatePolicy.extractSha256FromText(
                "SHA256: " + helloDigest.toUpperCase() + "  colgram-signed-release.apk");
        check(helloDigest.equals(extractedUpper), "extractSha256FromText upper normalization");

        check(ColgramUpdatePolicy.extractSha256FromText("short hex deadbeef1234") == null,
                "short hex must not match");

        File tmp = File.createTempFile("colgram-sha-test-", ".bin");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write("hello".getBytes(StandardCharsets.UTF_8));
            }
            check(helloDigest.equals(ColgramUpdatePolicy.sha256Hex(tmp)), "sha256Hex file mismatch");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static void testAbiSelectionAndUpstreamCompare() {
        java.util.List<ColgramUpdatePolicy.ApkAsset> assets = java.util.Arrays.asList(
                new ColgramUpdatePolicy.ApkAsset("colgram-armeabi-v7a-release.apk", "https://example.com/v7a.apk"),
                new ColgramUpdatePolicy.ApkAsset("colgram-arm64-v8a-release.apk", "https://example.com/arm64.apk"),
                new ColgramUpdatePolicy.ApkAsset("colgram-x86_64-release.apk", "https://example.com/x86_64.apk"),
                new ColgramUpdatePolicy.ApkAsset("colgram-universal-release.apk", "https://example.com/uni.apk")
        );
        ColgramUpdatePolicy.ApkAsset pickedArm64 = ColgramUpdatePolicy.pickBestApkAsset(
                assets, new String[]{"arm64-v8a", "armeabi-v7a"}
        );
        if (pickedArm64 == null || !pickedArm64.name.contains("arm64-v8a")) {
            throw new AssertionError("Expected arm64-v8a asset, got: " + (pickedArm64 == null ? "null" : pickedArm64.name));
        }

        ColgramUpdatePolicy.ApkAsset pickedX86_64 = ColgramUpdatePolicy.pickBestApkAsset(
                assets, new String[]{"x86_64", "x86"}
        );
        if (pickedX86_64 == null || !pickedX86_64.name.contains("x86_64")) {
            throw new AssertionError("Expected x86_64 asset, got: " + (pickedX86_64 == null ? "null" : pickedX86_64.name));
        }

        ColgramUpdatePolicy.ApkAsset fallbackUniversal = ColgramUpdatePolicy.pickBestApkAsset(
                assets, new String[]{"riscv64"}
        );
        if (fallbackUniversal == null || !fallbackUniversal.name.contains("universal")) {
            throw new AssertionError("Expected universal fallback asset, got: " + (fallbackUniversal == null ? "null" : fallbackUniversal.name));
        }

        if (!ColgramUpdatePolicy.isUpstreamTelegramNewer("12.10.6", "release-12.11.0_6001")) {
            throw new AssertionError("Expected release-12.11.0_6001 to be newer than 12.10.6");
        }
        if (ColgramUpdatePolicy.isUpstreamTelegramNewer("12.10.6-colgram.ci42", "release-12.10.6_5900")) {
            throw new AssertionError("Same dotted version upstream release should not trigger update");
        }
        if (!ColgramUpdatePolicy.isNewerTag("colgram-12.10.6-43", "12.10.6-colgram.ci42")) {
            throw new AssertionError("Expected CI build 43 to beat ci42");
        }
        if (ColgramUpdatePolicy.isNewerTag("colgram-12.10.6-10", "12.10.6-colgram.20261118-1300")) {
            throw new AssertionError("Local dev stamp 20261118-1300 must not let lower build 10 win at equal semver");
        }
        if (!ColgramUpdatePolicy.isNewerTag("colgram-12.11.0-1", "12.10.6-colgram.20261118-1300")) {
            throw new AssertionError("Higher semver 12.11.0 must beat local 12.10.6 dev stamp");
        }
        if (ColgramUpdatePolicy.isNewerTag("colgram-f2908b1-98", "12.10.6-colgram.mumu-call-routes-20261114")) {
            throw new AssertionError("Legacy hex-SHA release tag colgram-f2908b1-98 must never beat semver 12.10.6");
        }
        if (ColgramUpdatePolicy.parseSemver("colgram-f2908b1-98").length != 0) {
            throw new AssertionError("Hex commit SHA must not be parsed as semver major number");
        }
    }

    private static void testDualEditionAssetAndShaSelection() {
        java.util.List<ColgramUpdatePolicy.ApkAsset> dualAssets = java.util.Arrays.asList(
                new ColgramUpdatePolicy.ApkAsset("Colgram-12.10.6-Koliti.apk", "https://example.com/Colgram-12.10.6-Koliti.apk"),
                new ColgramUpdatePolicy.ApkAsset("Colgram-12.10.6.apk", "https://example.com/Colgram-12.10.6.apk")
        );
        String[] abis = new String[]{"arm64-v8a", "armeabi-v7a"};

        ColgramUpdatePolicy.ApkAsset mainPick = ColgramUpdatePolicy.pickBestApkAsset(dualAssets, abis, false);
        check(mainPick != null && "Colgram-12.10.6.apk".equals(mainPick.name),
                "Main edition must select Main APK, got: " + (mainPick == null ? "null" : mainPick.name));

        ColgramUpdatePolicy.ApkAsset kolitiPick = ColgramUpdatePolicy.pickBestApkAsset(dualAssets, abis, true);
        check(kolitiPick != null && "Colgram-12.10.6-Koliti.apk".equals(kolitiPick.name),
                "Koliti edition must select Koliti APK, got: " + (kolitiPick == null ? "null" : kolitiPick.name));

        java.util.List<ColgramUpdatePolicy.ApkAsset> onlyKoliti = java.util.Collections.singletonList(
                new ColgramUpdatePolicy.ApkAsset("Colgram-12.10.6-Koliti.apk", "https://example.com/Colgram-12.10.6-Koliti.apk")
        );
        check(ColgramUpdatePolicy.pickBestApkAsset(onlyKoliti, abis, false) == null,
                "Main edition must not auto-switch to Koliti-only release");

        java.util.List<ColgramUpdatePolicy.ApkAsset> onlyMain = java.util.Collections.singletonList(
                new ColgramUpdatePolicy.ApkAsset("Colgram-12.10.6.apk", "https://example.com/Colgram-12.10.6.apk")
        );
        check(ColgramUpdatePolicy.pickBestApkAsset(onlyMain, abis, true) == null,
                "Koliti edition must not silently downgrade to Main-only release");

        String shaMain = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String shaKoliti = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String sums = shaMain + "  Colgram-12.10.6.apk\n"
                + shaKoliti + "  Colgram-12.10.6-Koliti.apk\n";
        check(shaMain.equals(ColgramUpdatePolicy.extractSha256FromText(sums, "Colgram-12.10.6.apk")),
                "SHA256SUMS main lookup mismatch");
        check(shaKoliti.equals(ColgramUpdatePolicy.extractSha256FromText(sums, "Colgram-12.10.6-Koliti.apk")),
                "SHA256SUMS koliti lookup mismatch");
    }

    public static void main(String[] args) throws Exception {
        testParseAndCompareSemver();
        testIsNewerTag();
        testSha256Helpers();
        testAbiSelectionAndUpstreamCompare();
        testDualEditionAssetAndShaSelection();
        System.out.println("ColgramUpdaterTest: PASS");
    }
}
