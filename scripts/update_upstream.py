#!/usr/bin/env python3
"""
update_upstream.py — Check and prepare upstream DrKLO/Telegram updates for Colgram.

Inspects the local Telegram-Src version from gradle.properties / BuildVars.java, queries
DrKLO/Telegram tags from GitHub, compares semantic versions, and optionally checks
local Colgram syntax/patch compatibility via scripts/colgram-check.py.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional, Tuple


DEFAULT_UPSTREAM_REPO = "DrKLO/Telegram"
DOTTED_VERSION_RE = re.compile(r"(\d+(?:\.\d+)+)")
TRAILING_BUILD_RE = re.compile(r"[_-](\d+)$")


def parse_semver_tuple(raw: Optional[str]) -> Tuple[int, ...]:
    """Extracts the primary dotted numeric version tuple (e.g. 'release-12.10.6_5900' -> (12, 10, 6))."""
    if not raw:
        return ()
    m = DOTTED_VERSION_RE.search(raw.strip())
    if m:
        return tuple(int(part) for part in m.group(1).split("."))
    digits = re.search(r"(\d+)", raw)
    return (int(digits.group(1)),) if digits else ()


def is_upstream_newer(local_version: Optional[str], upstream_tag: Optional[str]) -> bool:
    """Returns True strictly when upstream_tag points to a newer Telegram release than local_version."""
    local_ver = parse_semver_tuple(local_version)
    remote_ver = parse_semver_tuple(upstream_tag)
    if not remote_ver:
        return False
    if not local_ver:
        return True
    max_len = max(len(local_ver), len(remote_ver))
    padded_local = local_ver + (0,) * (max_len - len(local_ver))
    padded_remote = remote_ver + (0,) * (max_len - len(remote_ver))
    return padded_remote > padded_local


def read_local_version(telegram_dir: str) -> str:
    """Reads APP_VERSION_NAME from <telegram_dir>/gradle.properties or BuildVars.java fallback."""
    gradle_props = os.path.join(telegram_dir, "gradle.properties")
    if os.path.isfile(gradle_props):
        with open(gradle_props, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                stripped = line.strip()
                if stripped.startswith("APP_VERSION_NAME="):
                    return stripped.split("=", 1)[1].strip()
    build_vars = os.path.join(
        telegram_dir,
        "TMessagesProj",
        "src",
        "main",
        "java",
        "org",
        "telegram",
        "messenger",
        "BuildVars.java",
    )
    if os.path.isfile(build_vars):
        with open(build_vars, "r", encoding="utf-8", errors="ignore") as f:
            text = f.read()
        m = re.search(r'BUILD_VERSION_STRING\s*=\s*"([^"]+)"', text)
        if m:
            return m.group(1).strip()
    return "unknown"


def _http_get_json(url: str, timeout: int = 12) -> Any:
    req = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "Colgram-Upstream-Sync",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8", errors="replace")
    return json.loads(raw)


def fetch_latest_upstream_tag(repo: str = DEFAULT_UPSTREAM_REPO, timeout: int = 12) -> Optional[str]:
    """Fetches the highest release tag name from DrKLO/Telegram via GitHub REST API."""
    tags_url = f"https://api.github.com/repos/{repo}/tags?per_page=20"
    try:
        payload = _http_get_json(tags_url, timeout=timeout)
        if isinstance(payload, list):
            candidates: List[str] = []
            for item in payload:
                if isinstance(item, dict) and isinstance(item.get("name"), str):
                    name = item["name"].strip()
                    if DOTTED_VERSION_RE.search(name):
                        candidates.append(name)
            if candidates:
                candidates.sort(key=parse_semver_tuple, reverse=True)
                return candidates[0]
    except (urllib.error.URLError, ValueError, OSError):
        pass

    latest_url = f"https://api.github.com/repos/{repo}/releases/latest"
    try:
        rel = _http_get_json(latest_url, timeout=timeout)
        if isinstance(rel, dict) and isinstance(rel.get("tag_name"), str):
            return rel["tag_name"].strip()
    except (urllib.error.URLError, ValueError, OSError):
        pass
    return None


def run_local_patch_syntax_check(repo_root: str) -> Tuple[bool, str]:
    """Runs scripts/colgram-check.py --syntax --core to confirm Colgram core/templates compile cleanly."""
    checker = os.path.join(repo_root, "scripts", "colgram-check.py")
    if not os.path.isfile(checker):
        return False, f"Checker not found: {checker}"
    proc = subprocess.run(
        [sys.executable, checker, "--syntax", "--core"],
        cwd=repo_root,
        capture_output=True,
        text=True,
        check=False,
    )
    out = (proc.stdout or "") + (proc.stderr or "")
    return proc.returncode == 0, out.strip()


def inspect_upstream_status(
    repo_root: str,
    telegram_dir: Optional[str] = None,
    upstream_repo: str = DEFAULT_UPSTREAM_REPO,
    override_remote_tag: Optional[str] = None,
    check_syntax: bool = False,
) -> Dict[str, Any]:
    """Computes complete upstream sync report without mutating the repository."""
    tg_dir = telegram_dir or os.path.join(repo_root, "Telegram-Src")
    local_ver = read_local_version(tg_dir)
    remote_tag = override_remote_tag or fetch_latest_upstream_tag(repo=upstream_repo)
    update_available = bool(remote_tag and is_upstream_newer(local_ver, remote_tag))

    report: Dict[str, Any] = {
        "upstream_repo": upstream_repo,
        "local_version": local_ver,
        "local_semver": list(parse_semver_tuple(local_ver)),
        "remote_tag": remote_tag,
        "remote_semver": list(parse_semver_tuple(remote_tag)) if remote_tag else [],
        "update_available": update_available,
        "recommended_ci_command": (
            f"gh workflow run build-colgram.yml -f telegram_version={remote_tag}"
            if remote_tag
            else None
        ),
    }

    if check_syntax:
        ok, output = run_local_patch_syntax_check(repo_root)
        report["syntax_check_ok"] = ok
        report["syntax_check_output"] = output
    return report


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Check DrKLO/Telegram upstream releases for Colgram")
    parser.add_argument("--repo-root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument("--telegram-dir", default=None)
    parser.add_argument("--upstream-repo", default=DEFAULT_UPSTREAM_REPO)
    parser.add_argument("--remote-tag", default=None, help="Override remote tag (offline check)")
    parser.add_argument("--check", action="store_true", help="Also run colgram-check.py --syntax --core")
    parser.add_argument("--json", action="store_true", help="Print structured JSON output")
    args = parser.parse_args(argv)

    report = inspect_upstream_status(
        repo_root=args.repo_root,
        telegram_dir=args.telegram_dir,
        upstream_repo=args.upstream_repo,
        override_remote_tag=args.remote_tag,
        check_syntax=args.check,
    )

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"Local Telegram base : {report['local_version']} ({'.'.join(map(str, report['local_semver']))})")
        print(f"Remote upstream tag : {report['remote_tag'] or 'unreachable'}")
        print(f"Update available    : {'YES' if report['update_available'] else 'NO'}")
        if report["recommended_ci_command"]:
            print(f"CI rebuild command  : {report['recommended_ci_command']}")
        if "syntax_check_ok" in report:
            print(f"Local syntax check  : {'PASS' if report['syntax_check_ok'] else 'FAIL'}")

    if args.check and not report.get("syntax_check_ok", False):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
