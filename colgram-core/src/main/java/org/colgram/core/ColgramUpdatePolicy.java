package org.colgram.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java version comparison, ABI asset selection, upstream Telegram tag comparison,
 * and SHA-256 verification helpers for Colgram OTA updates.
 * Kept free of Android framework imports so it runs directly in host JVM tests as well as on device.
 */
public final class ColgramUpdatePolicy {

    private static final Pattern DOTTED_VERSION_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");
    private static final Pattern SINGLE_NUMBER_PATTERN = Pattern.compile("(?:^|\\s)v?(\\d+)(?:$|\\s)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCAL_STAMP_PATTERN = Pattern.compile("-colgram\\.\\d{8}-\\d{4}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_BUILD_PATTERN = Pattern.compile("(?:^|[.-])(?:ci)?(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHA256_PATTERN = Pattern.compile("(?:^|[^0-9a-fA-F])([0-9a-fA-F]{64})(?:$|[^0-9a-fA-F])");

    public static final class ApkAsset {
        public final String name;
        public final String downloadUrl;

        public ApkAsset(String name, String downloadUrl) {
            this.name = name == null ? "" : name;
            this.downloadUrl = downloadUrl == null ? "" : downloadUrl;
        }
    }

    private ColgramUpdatePolicy() {}

    /** Parses numeric dotted version segments safely from a tag or version string. */
    public static int[] parseSemver(String version) {
        if (version == null || version.trim().isEmpty()) {
            return new int[0];
        }
        String trimmed = version.trim();
        Matcher dotted = DOTTED_VERSION_PATTERN.matcher(trimmed);
        if (dotted.find()) {
            String[] parts = dotted.group(1).split("\\.");
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = parsePositiveIntBounded(parts[i]);
            }
            return out;
        }
        Matcher single = SINGLE_NUMBER_PATTERN.matcher(trimmed);
        if (single.find()) {
            return new int[] { parsePositiveIntBounded(single.group(1)) };
        }
        return new int[0];
    }

    /** Compares two version/tag strings numerically (>0 if a > b, 0 if equal, <0 if a < b). */
    public static int compareSemver(String a, String b) {
        int[] va = parseSemver(a);
        int[] vb = parseSemver(b);
        int maxLen = Math.max(va.length, vb.length);
        for (int i = 0; i < maxLen; i++) {
            int na = i < va.length ? va[i] : 0;
            int nb = i < vb.length ? vb[i] : 0;
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }
        return 0;
    }

    /** Returns true strictly when remoteTag represents a newer build than currentVersion. */
    public static boolean isNewerTag(String remoteTag, String currentVersion) {
        if (remoteTag == null || remoteTag.trim().isEmpty()) {
            return false;
        }
        if (currentVersion == null || currentVersion.trim().isEmpty()) {
            return true;
        }
        boolean currentHasDotted = DOTTED_VERSION_PATTERN.matcher(currentVersion.trim()).find();
        boolean remoteHasDotted = DOTTED_VERSION_PATTERN.matcher(remoteTag.trim()).find();
        // Legacy/SHA-only release tags (e.g. colgram-f2908b1-98) must never override a semver build.
        if (currentHasDotted && !remoteHasDotted) {
            return false;
        }
        int cmp = compareSemver(remoteTag, currentVersion);
        if (cmp != 0) {
            return cmp > 0;
        }
        int remoteBuild = extractTrailingBuildNumber(remoteTag);
        int currentBuild = extractTrailingBuildNumber(currentVersion);
        return remoteBuild >= 0 && currentBuild >= 0 && remoteBuild > currentBuild;
    }

    /** Extracts the first 64-character lowercase SHA-256 hex digest from release notes or .sha256 text. */
    public static String extractSha256FromText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = SHA256_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1).toLowerCase(Locale.US);
        }
        return null;
    }

    /**
     * Extracts the SHA-256 hex digest matching a specific APK asset name in multi-ABI release notes,
     * falling back to the first SHA-256 in the text only when no asset-specific line matches.
     */
    public static String extractSha256FromText(String text, String assetName) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (assetName != null && !assetName.trim().isEmpty()) {
            String needle = assetName.trim().toLowerCase(Locale.US);
            String[] lines = text.split("\\r?\\n");
            for (String line : lines) {
                if (line != null && line.toLowerCase(Locale.US).contains(needle)) {
                    Matcher m = SHA256_PATTERN.matcher(line);
                    if (m.find()) {
                        return m.group(1).toLowerCase(Locale.US);
                    }
                }
            }
        }
        return extractSha256FromText(text);
    }

    /** Computes lowercase SHA-256 hex digest of an in-memory byte array. */
    public static String sha256Hex(byte[] data) {
        if (data == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return bytesToHex(digest.digest(data));
        } catch (Exception e) {
            return null;
        }
    }

    /** Computes lowercase SHA-256 hex digest of a file on disk. */
    public static String sha256Hex(File file) {
        if (file == null || !file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
            return bytesToHex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns true when remote upstream Telegram tag carries a strictly higher base semver than localVersion. */
    public static boolean isUpstreamTelegramNewer(String localVersion, String upstreamTag) {
        if (upstreamTag == null || upstreamTag.trim().isEmpty()) return false;
        if (localVersion == null || localVersion.trim().isEmpty()) return true;
        return compareSemver(upstreamTag, localVersion) > 0;
    }

    /** Picks the best APK asset matching device ABIs, preferring ABI-specific then universal/afat. */
    public static ApkAsset pickBestApkAsset(List<ApkAsset> assets, String[] supportedAbis) {
        if (assets == null || assets.isEmpty()) return null;
        if (supportedAbis != null) {
            for (String abi : supportedAbis) {
                if (abi == null || abi.trim().isEmpty()) continue;
                String abiLow = abi.trim().toLowerCase(Locale.US);
                for (ApkAsset asset : assets) {
                    if (asset == null || asset.downloadUrl.isEmpty()) continue;
                    String low = asset.name.toLowerCase(Locale.US);
                    if (low.endsWith(".apk") && low.contains(abiLow)) {
                        return asset;
                    }
                }
            }
        }
        ApkAsset universal = null;
        ApkAsset firstValid = null;
        for (ApkAsset asset : assets) {
            if (asset == null || asset.downloadUrl.isEmpty()) continue;
            String low = asset.name.toLowerCase(Locale.US);
            if (!low.endsWith(".apk")) continue;
            if (firstValid == null) firstValid = asset;
            if (universal == null && (low.contains("universal") || low.contains("afat") || low.contains("colgram-signed-release"))) {
                universal = asset;
            }
        }
        return universal != null ? universal : firstValid;
    }

    private static int parsePositiveIntBounded(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static int extractTrailingBuildNumber(String tag) {
        if (tag == null) return -1;
        String trimmed = tag.trim();
        if (LOCAL_STAMP_PATTERN.matcher(trimmed).find()) {
            return -1;
        }
        Matcher m = TRAILING_BUILD_PATTERN.matcher(trimmed);
        if (m.find()) {
            return parsePositiveIntBounded(m.group(1));
        }
        return -1;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
