package org.colgram.core;

/**
 * JVM unit tests for ColgramFileSafetyPolicy (malware extensions, double-ext & Bidi RLO spoofing,
 * dangerous MIME types, mobile/Wi-Fi size caps, storage cap 25% ceiling, and manual warning gates).
 */
public class ColgramFileSafetyPolicyTest {

    private static final long MB = 1024L * 1024L;

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("ColgramFileSafetyPolicyTest FAILED: " + msg);
        }
    }

    private static void testDangerousExtensionsAndSpoofing() {
        String[] blockedFiles = {
                "update.apk", "installer.APK", "split.apks", "bundle.xapk", "app.aab",
                "payload.exe", "setup.msi", "run.bat", "script.cmd", "evil.vbs",
                "loader.ps1", "run.sh", "dropper.jar", "classes.dex", "libhook.so",
                "native.dll", "binary.elf", "shortcut.lnk", "phish.hta", "disk.iso",
                "mac.dmg", "pkg.deb", "pkg.rpm", "bot.py",
                // Double extensions & trailing obfuscation
                "photo.jpg.apk", "invoice.pdf.exe", "archive.zip.bat", "evil.exe.zip",
                "installer.apk   ", "loader.exe...",
                // Unicode Bidi RLO spoof
                "photo\u202Egpj.apk", "contract\u202Efdp.exe"
        };
        for (String name : blockedFiles) {
            ColgramFileSafetyPolicy.SafetyAssessment a = ColgramFileSafetyPolicy.assess(
                    name, "application/octet-stream", 500_000L, true, 15, 50, 1024L * MB, true);
            check(a.autoDownloadBlocked, "Expected auto-download block for dangerous file: " + name);
            check(a.manualWarningRequired, "Expected manual warning sheet for dangerous file: " + name);
            check(a.isDangerousType, "Expected dangerous type flag for: " + name);
        }

        // Dangerous MIME even with harmless filename
        ColgramFileSafetyPolicy.SafetyAssessment mimeBlocked = ColgramFileSafetyPolicy.assess(
                "attachment", "application/vnd.android.package-archive; charset=utf-8",
                120_000L, true, 15, 50, 1024L * MB, true);
        check(mimeBlocked.autoDownloadBlocked && mimeBlocked.manualWarningRequired,
                "Dangerous MIME must trigger block + manual warning even without extension");
    }

    private static void testSizeCeilingsAndCleanMedia() {
        long oneGb = 1024L * MB;
        // Mobile limit: <= 15 MB allowed, 16 MB blocked
        check(!ColgramFileSafetyPolicy.assess("vacation.mp4", "video/mp4", 12L * MB, false, 15, 50, oneGb, true).autoDownloadBlocked,
                "12 MB clean video on mobile must pass 15 MB cap");
        check(ColgramFileSafetyPolicy.assess("vacation.mp4", "video/mp4", 20L * MB, false, 15, 50, oneGb, true).autoDownloadBlocked,
                "20 MB video on mobile must block at 15 MB cap");

        // Wi-Fi limit: <= 50 MB allowed, > 50 MB blocked
        check(!ColgramFileSafetyPolicy.assess("presentation.pdf", "application/pdf", 35L * MB, true, 15, 50, oneGb, true).autoDownloadBlocked,
                "35 MB clean PDF on Wi-Fi must pass 50 MB cap");
        check(ColgramFileSafetyPolicy.assess("backup.zip", "application/zip", 60L * MB, true, 15, 50, oneGb, true).autoDownloadBlocked,
                "60 MB archive on Wi-Fi must be blocked");

        // Multi-GB files ALWAYS blocked for auto-download and warn manually (>500 MB)
        ColgramFileSafetyPolicy.SafetyAssessment huge = ColgramFileSafetyPolicy.assess(
                "movie.mkv", "video/x-matroska", 2L * 1024L * MB, true, 15, 50, oneGb, true);
        check(huge.autoDownloadBlocked && huge.manualWarningRequired && huge.isHugeFile,
                "2 GB file must block auto-download and require manual warning sheet");

        // Safe small photo/document never prompts manually
        ColgramFileSafetyPolicy.SafetyAssessment safePdf = ColgramFileSafetyPolicy.assessForManualAction(
                "notes_2026.pdf", "application/pdf", 4L * MB, oneGb);
        check(!safePdf.autoDownloadBlocked && !safePdf.manualWarningRequired,
                "4 MB clean PDF must open cleanly without warning sheet");

        // Small storage cap (100 MB) enforces 25% ceiling (25 MB) even if Wi-Fi cap were higher
        check(ColgramFileSafetyPolicy.assess("video.mp4", "video/mp4", 30L * MB, true, 15, 50, 100L * MB, true).autoDownloadBlocked,
                "30 MB file must be blocked when 25% of 100 MB cap = 25 MB");
    }

    public static void main(String[] args) {
        testDangerousExtensionsAndSpoofing();
        testSizeCeilingsAndCleanMedia();
        System.out.println("ColgramFileSafetyPolicyTest: PASS");
    }
}
