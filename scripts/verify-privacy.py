#!/usr/bin/env python3
"""
Colgram Privacy & Security Auditor
Scans the codebase to detect telemetry, trackers, cloaking leaks, and invasive permissions.
"""

import os
import re
import sys

FORBIDDEN_KEYWORDS = [
    "com.google.firebase",
    "firebase-analytics",
    "firebase-crashlytics",
    "com.google.android.gms.analytics",
    "com.huawei.hms.push",
    "com.microsoft.appcenter",
    "io.sentry",
    "READ_PHONE_STATE",
    "MANAGE_EXTERNAL_STORAGE",
]

SKIP_DIR_NAMES = {".git", "build", ".gradle", "tests", "test", "androidTest"}


def _is_comment_or_manifest_removal(stripped):
    if not stripped:
        return True
    if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*") or stripped.startswith("<!--"):
        return True
    if 'tools:node="remove"' in stripped:
        return True
    return False


def _window_has(lines, idx_zero_based, patterns, before=12, after=2):
    lo = max(0, idx_zero_based - before)
    hi = min(len(lines), idx_zero_based + after + 1)
    blob = "\n".join(lines[lo:hi])
    return any(p in blob for p in patterns)


def scan_file(filepath):
    findings = []
    basename = os.path.basename(filepath)
    norm_path = filepath.replace("\\", "/")
    try:
        with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
            lines = f.read().splitlines()
    except Exception:
        return findings

    for idx, raw_line in enumerate(lines):
        line_no = idx + 1
        stripped = raw_line.strip()
        if _is_comment_or_manifest_removal(stripped):
            continue

        for kw in FORBIDDEN_KEYWORDS:
            if kw in raw_line:
                if (
                    kw == "com.google.firebase"
                    and basename == "ColgramSecurityDefender.java"
                    and 'Class.forName("com.google.firebase.' in raw_line
                ):
                    continue
                findings.append((line_no, kw, stripped))

        if "Colgram/1.0" in raw_line or "Colgram/3.0" in raw_line:
            findings.append((line_no, "custom-colgram-ua", stripped))

        if filepath.endswith(".xml") and 'android:allowBackup="true"' in raw_line:
            findings.append((line_no, "android:allowBackup=true", stripped))

        if filepath.endswith((".java", ".kt")):
            if "FirebaseUserActions.getInstance(" in raw_line:
                if not _window_has(lines, idx, ("allow_firebase", "ColgramConfig"), before=10, after=1):
                    findings.append((line_no, "unguarded-FirebaseUserActions", stripped))

            if "IntegrityManagerFactory.create(" in raw_line:
                if not _window_has(lines, idx, ("ColgramConfig.isCloakEnabled", "ColgramConfig.isStrict"), before=18, after=1):
                    findings.append((line_no, "unguarded-PlayIntegrity", stripped))

            if basename != "TLRPC.java" and re.search(r"\bnew\s+TLRPC\.TL_help_saveAppLog\b", raw_line):
                if not _window_has(lines, idx, ("ColgramConfig", "if (false", "instanceof TLRPC.TL_help_saveAppLog"), before=12, after=2):
                    findings.append((line_no, "unguarded-TL_help_saveAppLog", stripped))

            if basename == "ConnectionsManager.java" and "InetAddress.getByName(" in raw_line:
                if not _window_has(lines, idx, ("ColgramConfig.isStrictProxySecurityEnabled", "isStrictEncryptedDnsOnly"), before=10, after=1):
                    findings.append((line_no, "unguarded-system-dns-fallback", stripped))

            if basename == "ColgramDohResolver.java" and ("for (String dnsIp : EMERGENCY_UDP_DNS)" in raw_line or "53)" in raw_line and "DatagramPacket" in raw_line):
                if not _window_has(lines, idx, ("isStrictEncryptedDnsOnly()",), before=12, after=2):
                    findings.append((line_no, "unguarded-plaintext-udp-dns", stripped))

            if norm_path.endswith("org/telegram/messenger/NetworkMonitorAutoDetect.java") or basename == "NetworkMonitorAutoDetect.java":
                if "wifiInfo.getSSID()" in raw_line and not _window_has(lines, idx, ("ColgramConfig",), before=8, after=1):
                    findings.append((line_no, "unguarded-wifi-ssid", stripped))

    return findings


def audit_directory(root_dir, strict=False):
    print("=" * 60)
    print("[*] Starting Colgram Privacy & Security Audit")
    print(f"[*] Target Directory: {root_dir}")
    print("=" * 60)

    total_violations = 0
    scanned_files = 0

    for root, dirs, files in os.walk(root_dir):
        dirs[:] = [d for d in dirs if d not in SKIP_DIR_NAMES]
        for file in files:
            if file.endswith((".java", ".kt", ".gradle", ".xml")):
                scanned_files += 1
                filepath = os.path.join(root, file)
                violations = scan_file(filepath)
                if violations:
                    print(f"\n[!] Notice in {os.path.relpath(filepath, root_dir)}:")
                    for line_no, kw, line in violations:
                        print(f"    Line {line_no} [{kw}]: {line}")
                        total_violations += 1

    print("\n" + "=" * 60)
    print("[*] Audit Completed.")
    print(f"    Scanned files: {scanned_files}")
    print(f"    Findings detected: {total_violations}")
    if total_violations == 0:
        print("[+] PASSED: Clean! Zero telemetry trackers or privacy leaks detected.")
        print("=" * 60)
        return True
    else:
        if strict:
            print("[-] FAILED: Privacy findings detected in strict mode.")
            print("=" * 60)
            return False
        else:
            print("[+] ADVISORY: Findings logged. Build continuing in non-strict mode.")
            print("=" * 60)
            return True


if __name__ == "__main__":
    target = "."
    strict = False
    for arg in sys.argv[1:]:
        if arg == "--strict":
            strict = True
        elif arg == "--advisory":
            strict = False
        else:
            target = arg
    passed = audit_directory(target, strict=strict)
    sys.exit(0 if passed else 1)
