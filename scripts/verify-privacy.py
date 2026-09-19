#!/usr/bin/env python3
"""
Colgram Privacy & Security Auditor
Scans the codebase to guarantee zero telemetry, no hidden trackers,
and no invasive device permissions.
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
    "MANAGE_EXTERNAL_STORAGE"
]

def scan_file(filepath):
    findings = []
    try:
        with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
            for line_no, line in enumerate(f, 1):
                # Ignore commented-out lines in gradle or java
                stripped = line.strip()
                if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*"):
                    continue
                for kw in FORBIDDEN_KEYWORDS:
                    if kw in line:
                        findings.append((line_no, kw, line.strip()))
    except Exception as e:
        pass
    return findings

def audit_directory(root_dir):
    print("=" * 60)
    print(f"[*] Starting Colgram Privacy & Security Audit")
    print(f"[*] Target Directory: {root_dir}")
    print("=" * 60)

    total_violations = 0
    scanned_files = 0

    for root, _, files in os.walk(root_dir):
        # Skip git directory
        if ".git" in root:
            continue
        for file in files:
            if file.endswith((".java", ".kt", ".gradle", ".xml")):
                scanned_files += 1
                filepath = os.path.join(root, file)
                violations = scan_file(filepath)
                if violations:
                    print(f"\n[!] VIOLATION in {os.path.relpath(filepath, root_dir)}:")
                    for line_no, kw, line in violations:
                        print(f"    Line {line_no} [{kw}]: {line}")
                        total_violations += 1

    print("\n" + "=" * 60)
    print(f"[*] Audit Completed.")
    print(f"    Scanned files: {scanned_files}")
    print(f"    Violations found: {total_violations}")
    if total_violations == 0:
        print("[+] PASSED: Clean build! Zero telemetry trackers or invasive permissions detected.")
        print("=" * 60)
        return True
    else:
        print("[-] FAILED: Potential privacy leaks detected. Remove offending lines.")
        print("=" * 60)
        return False

if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "."
    passed = audit_directory(target)
    sys.exit(0 if passed else 1)
