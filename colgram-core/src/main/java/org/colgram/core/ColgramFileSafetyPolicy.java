package org.colgram.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure-Java safety policy blocking dangerous executable/installer/script files and
 * multi-gigabyte auto-downloads/preloads (with double-extension & Unicode Bidi RLO spoof detection).
 */
public final class ColgramFileSafetyPolicy {

    public static final long HARD_CEILING_MOBILE_BYTES = 50L * 1024L * 1024L;
    public static final long HARD_CEILING_WIFI_BYTES = 150L * 1024L * 1024L;
    public static final long MANUAL_HUGE_FILE_BYTES = 500L * 1024L * 1024L;

    private static final Set<String> DANGEROUS_EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            ".apk", ".apks", ".xapk", ".aab",
            ".exe", ".msi", ".msp", ".com", ".pif", ".scr",
            ".bat", ".cmd", ".vbs", ".vbe", ".js", ".jse",
            ".ps1", ".psm1", ".sh", ".bash", ".csh", ".ksh", ".zsh",
            ".jar", ".dex", ".so", ".dll", ".elf", ".lnk", ".hta", ".reg",
            ".iso", ".img", ".cab", ".dmg", ".pkg", ".deb", ".rpm", ".appimage",
            ".wsf", ".wsh", ".vb", ".py", ".pyw"
    )));

    private static final Set<String> ARCHIVE_EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            ".zip", ".rar", ".7z", ".tar", ".gz", ".xz", ".bz2", ".tgz"
    )));

    private static final Set<String> DECOY_EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg",
            ".mp4", ".mov", ".mkv", ".avi", ".mp3", ".ogg", ".wav",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".rtf"
    )));

    private static final Set<String> DANGEROUS_MIME_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "application/vnd.android.package-archive",
            "application/x-msdownload",
            "application/x-dosexec",
            "application/x-executable",
            "application/x-elf",
            "application/x-sharedlib",
            "application/x-sh",
            "application/x-shellscript",
            "application/x-bat",
            "application/x-csh",
            "application/x-msi",
            "application/x-ms-shortcut",
            "application/x-iso9660-image",
            "application/x-apple-diskimage",
            "application/x-debian-package",
            "application/x-rpm",
            "application/java-archive",
            "application/javascript",
            "text/javascript",
            "application/x-javascript",
            "application/x-powershell",
            "text/x-python",
            "text/x-shellscript",
            "application/hta"
    )));

    public static final class SafetyAssessment {
        public final boolean autoDownloadBlocked;
        public final boolean manualWarningRequired;
        public final boolean isDangerousType;
        public final boolean isHugeFile;
        public final boolean hasBidiSpoof;
        public final boolean hasDoubleExtension;
        public final String detectedExtension;
        public final String threatReason;

        public SafetyAssessment(boolean autoDownloadBlocked,
                                boolean manualWarningRequired,
                                boolean isDangerousType,
                                boolean isHugeFile,
                                boolean hasBidiSpoof,
                                boolean hasDoubleExtension,
                                String detectedExtension,
                                String threatReason) {
            this.autoDownloadBlocked = autoDownloadBlocked;
            this.manualWarningRequired = manualWarningRequired;
            this.isDangerousType = isDangerousType;
            this.isHugeFile = isHugeFile;
            this.hasBidiSpoof = hasBidiSpoof;
            this.hasDoubleExtension = hasDoubleExtension;
            this.detectedExtension = detectedExtension == null ? "" : detectedExtension;
            this.threatReason = threatReason == null ? "" : threatReason;
        }
    }

    private ColgramFileSafetyPolicy() {
    }

    public static boolean hasBidiSpoofChars(String rawName) {
        if (rawName == null || rawName.isEmpty()) return false;
        for (int i = 0; i < rawName.length(); i++) {
            char c = rawName.charAt(i);
            if ((c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069')) {
                return true;
            }
        }
        return false;
    }

    /** Strips Unicode Bidi/invisible/control characters plus trailing spaces and dots. */
    public static String sanitizeFilename(String rawName) {
        if (rawName == null || rawName.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(rawName.length());
        for (int i = 0; i < rawName.length(); i++) {
            char c = rawName.charAt(i);
            if (c < 0x20 || c == '\u007F') continue;
            if ((c >= '\u202A' && c <= '\u202E')
                    || (c >= '\u2066' && c <= '\u2069')
                    || (c >= '\u200B' && c <= '\u200F')
                    || c == '\uFEFF'
                    || (c >= '\u2060' && c <= '\u2064')) {
                continue;
            }
            sb.append(c);
        }
        String out = sb.toString().trim();
        while (out.endsWith(".") || out.endsWith(" ")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    public static String extractFinalExtension(String rawName) {
        String clean = sanitizeFilename(rawName);
        if (clean.isEmpty()) return "";
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        String base = slash >= 0 ? clean.substring(slash + 1) : clean;
        int dot = base.lastIndexOf('.');
        if (dot <= 0 || dot >= base.length() - 1) return "";
        return base.substring(dot).toLowerCase(Locale.ROOT);
    }

    public static boolean isDoubleExtensionSpoof(String rawName) {
        String clean = sanitizeFilename(rawName);
        if (clean.isEmpty()) return false;
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        String base = slash >= 0 ? clean.substring(slash + 1) : clean;
        String[] parts = base.split("\\.");
        if (parts.length < 3) return false;
        String lastExt = "." + parts[parts.length - 1].toLowerCase(Locale.ROOT);
        String prevExt = "." + parts[parts.length - 2].toLowerCase(Locale.ROOT);
        if (DANGEROUS_EXTENSIONS.contains(lastExt)) {
            for (int i = 1; i < parts.length - 1; i++) {
                String earlierExt = "." + parts[i].toLowerCase(Locale.ROOT);
                if (DECOY_EXTENSIONS.contains(earlierExt) || ARCHIVE_EXTENSIONS.contains(earlierExt)) {
                    return true;
                }
            }
        }
        if (ARCHIVE_EXTENSIONS.contains(lastExt)
                && DANGEROUS_EXTENSIONS.contains(prevExt)
                && !".com".equals(prevExt)) {
            return true;
        }
        return false;
    }

    public static boolean isDangerousExtension(String extLower) {
        return extLower != null && DANGEROUS_EXTENSIONS.contains(extLower.toLowerCase(Locale.ROOT));
    }

    public static boolean isArchiveExtension(String extLower) {
        return extLower != null && ARCHIVE_EXTENSIONS.contains(extLower.toLowerCase(Locale.ROOT));
    }

    public static boolean isDangerousMimeType(String mimeType) {
        if (mimeType == null || mimeType.trim().isEmpty()) return false;
        String norm = mimeType.trim().toLowerCase(Locale.ROOT);
        int semi = norm.indexOf(';');
        if (semi >= 0) norm = norm.substring(0, semi).trim();
        return DANGEROUS_MIME_TYPES.contains(norm);
    }

    public static long effectiveAutoDownloadCapBytes(boolean isWifi, int maxMobileMb, int maxWifiMb, long storageCapBytes) {
        long requested = isWifi
                ? (long) Math.max(1, Math.min(150, maxWifiMb)) * 1024L * 1024L
                : (long) Math.max(1, Math.min(50, maxMobileMb)) * 1024L * 1024L;
        long hardNetworkCeiling = isWifi ? HARD_CEILING_WIFI_BYTES : HARD_CEILING_MOBILE_BYTES;
        long cap = Math.min(requested, hardNetworkCeiling);
        if (storageCapBytes > 0L) {
            long quarterCap = Math.max(1024L * 1024L, storageCapBytes / 4L);
            cap = Math.min(cap, quarterCap);
        }
        return cap;
    }

    public static SafetyAssessment assess(String fileName,
                                          String mimeType,
                                          long totalSizeBytes,
                                          boolean isWifi,
                                          int maxMobileMb,
                                          int maxWifiMb,
                                          long storageCapBytes,
                                          boolean shieldEnabled) {
        boolean bidiSpoof = hasBidiSpoofChars(fileName);
        String ext = extractFinalExtension(fileName);
        boolean doubleExt = isDoubleExtensionSpoof(fileName);
        boolean dangerousExt = isDangerousExtension(ext);
        boolean dangerousMime = isDangerousMimeType(mimeType);
        boolean isArchive = isArchiveExtension(ext);
        boolean dangerousType = dangerousExt || dangerousMime || bidiSpoof || doubleExt;

        long autoCap = effectiveAutoDownloadCapBytes(isWifi, maxMobileMb, maxWifiMb, storageCapBytes);
        boolean exceedsAutoCap = totalSizeBytes > 0L && totalSizeBytes > autoCap;
        boolean exceedsQuarterCap = storageCapBytes > 0L && totalSizeBytes > Math.max(1024L * 1024L, storageCapBytes / 4L);
        boolean hugeManual = totalSizeBytes >= MANUAL_HUGE_FILE_BYTES
                || (storageCapBytes > 0L && totalSizeBytes > Math.max(100L * 1024L * 1024L, storageCapBytes / 2L));

        boolean blockAuto;
        if (shieldEnabled) {
            blockAuto = dangerousType || exceedsAutoCap || exceedsQuarterCap || (isArchive && exceedsAutoCap);
        } else {
            // Even when malware shield is off, respect storage cap 25% boundary if a finite cap is set.
            blockAuto = exceedsQuarterCap;
        }

        boolean warnManual = shieldEnabled && (dangerousType || hugeManual);
        String reason = buildReason(dangerousExt, dangerousMime, bidiSpoof, doubleExt, isArchive,
                totalSizeBytes, autoCap, hugeManual, ext);

        return new SafetyAssessment(blockAuto, warnManual, dangerousType,
                exceedsAutoCap || hugeManual, bidiSpoof, doubleExt, ext, reason);
    }

    public static boolean shouldBlockAutoDownload(String fileName,
                                                  String mimeType,
                                                  long totalSizeBytes,
                                                  boolean isWifi,
                                                  long storageCapBytes) {
        SafetyAssessment res = assess(
                fileName,
                mimeType,
                totalSizeBytes,
                isWifi,
                ColgramShieldConfig.getAutoDownloadMaxMobileMb(),
                ColgramShieldConfig.getAutoDownloadMaxWifiMb(),
                storageCapBytes,
                ColgramShieldConfig.isMalwareDownloadShieldEnabled()
        );
        return res.autoDownloadBlocked;
    }

    public static SafetyAssessment assessForManualAction(String fileName,
                                                         String mimeType,
                                                         long totalSizeBytes,
                                                         long storageCapBytes) {
        return assess(
                fileName,
                mimeType,
                totalSizeBytes,
                true,
                ColgramShieldConfig.getAutoDownloadMaxMobileMb(),
                ColgramShieldConfig.getAutoDownloadMaxWifiMb(),
                storageCapBytes,
                ColgramShieldConfig.isMalwareDownloadShieldEnabled()
        );
    }

    private static String buildReason(boolean dangerousExt,
                                      boolean dangerousMime,
                                      boolean bidiSpoof,
                                      boolean doubleExt,
                                      boolean isArchive,
                                      long totalSizeBytes,
                                      long autoCap,
                                      boolean hugeManual,
                                      String ext) {
        StringBuilder sb = new StringBuilder();
        if (bidiSpoof) {
            sb.append("Обнаружена маскировка имени Unicode RLO/Bidi (скрытое расширение). ");
        }
        if (doubleExt) {
            sb.append("Подозрительное двойное расширение (").append(ext).append("). ");
        }
        if (dangerousExt || dangerousMime) {
            sb.append("Исполняемый или установочный файл")
                    .append(ext.isEmpty() ? "" : " (" + ext + ")")
                    .append(" может содержать вредоносный код. ");
        }
        if (hugeManual || (totalSizeBytes > 0L && totalSizeBytes > autoCap)) {
            long mb = Math.max(1L, totalSizeBytes / (1024L * 1024L));
            sb.append("Слишком большой файл (").append(mb).append(" МБ)");
            if (isArchive) {
                sb.append(" — архив превышает безопасный лимит автозагрузки.");
            } else {
                sb.append(" — превышает лимит автозагрузки или квоту хранилища.");
            }
        }
        return sb.toString().trim();
    }
}
