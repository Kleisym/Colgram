"""Probe the two endpoints behind the reported "versions didn't load" and
"temp mail error" bugs, from ON the device.

Both bugs came from a network step that was treated as permanent. Before believing any
code fix, confirm (a) the hosts actually resolve and answer from this device, and (b)
what the real response bodies look like. A probe that reproduces the failure is worth
more than a probe that only shows green.

Endpoints exercised, matching the app call sites exactly:
  https://api.github.com/repos/Kleisym/Colgram/releases?per_page=20   (VersionsActivity)
  https://api.github.com/repos/DrKLO/Telegram/tags?per_page=40       (VersionsActivity)
  https://api.mail.tm/domains?page=1                                 (TempMail)
  https://api.mail.tm/accounts                                       (TempMail POST)

Usage:  python tempmail-probe.py
"""
import subprocess
import sys
import time

ADB = r"C:\android-sdk\platform-tools\adb.exe"
DEV = "127.0.0.1:7555"

# Written to /data/local/tmp and executed there. Uses only toybox tools that ship with
# Android, plus openssl/curl when present — the fallback branch still reports DNS, which
# is the cheapest signal for "the host is unreachable" vs "the host answered badly".
PROBE = r'''
echo "=== DNS ==="
for h in api.github.com api.mail.tm; do
  printf "%-18s " "$h"
  ping -c 1 -W 3 "$h" >/dev/null 2>&1 && echo "ping OK" || echo "ping FAILED"
done

echo
echo "=== IPv4 literal routes (avoid DNS entirely) ==="
for pair in "api.github.com:140.82.121.6" "api.mail.tm:0"; do
  h=${pair%%:*}; ip=${pair##*:}
  [ "$ip" = "0" ] && continue
  (echo > /dev/tcp/$ip/443) >/dev/null 2>&1 && echo "  $h ($ip):443 OPEN" || echo "  $h ($ip):443 unreachable"
done

echo
echo "=== HTTPS via curl (if present) ==="
CURL=$(command -v curl)
if [ -z "$CURL" ]; then
  echo "  (no curl on device — skipping body checks)"
else
  echo "--- GitHub releases ---"
  $CURL -s -o /data/local/tmp/gh.json -w "  http=%{http_code} bytes=%{size_download} time=%{time_total}s\n" \
     -H "User-Agent: Colgram" -H "Accept: application/vnd.github+json" \
     "https://api.github.com/repos/Kleisym/Colgram/releases?per_page=20"
  echo "  body head: $(head -c 120 /data/local/tmp/gh.json 2>/dev/null)"

  echo "--- GitHub tags ---"
  $CURL -s -o /data/local/tmp/tags.json -w "  http=%{http_code} bytes=%{size_download} time=%{time_total}s\n" \
     -H "User-Agent: Colgram" -H "Accept: application/vnd.github+json" \
     "https://api.github.com/repos/DrKLO/Telegram/tags?per_page=40"
  echo "  body head: $(head -c 120 /data/local/tmp/tags.json 2>/dev/null)"

  echo "--- GitHub WITHOUT User-Agent (expect 403) ---"
  $CURL -s -o /dev/null -w "  http=%{http_code}\n" \
     "https://api.github.com/repos/DrKLO/Telegram/tags?per_page=5"

  echo "--- mail.tm domains ---"
  $CURL -s -o /data/local/tmp/dom.json -w "  http=%{http_code} bytes=%{size_download} time=%{time_total}s\n" \
     -H "Accept: application/json" "https://api.mail.tm/domains?page=1"
  echo "  body head: $(head -c 160 /data/local/tmp/dom.json 2>/dev/null)"

  echo "--- mail.tm POST /accounts (empty body, expect 4xx with a real reason) ---"
  $CURL -s -X POST -o /data/local/tmp/acc.json -w "  http=%{http_code} bytes=%{size_download}\n" \
     -H "Content-Type: application/json" -H "Accept: application/json" \
     -d '{}' "https://api.mail.tm/accounts"
  echo "  body: $(head -c 200 /data/local/tmp/acc.json 2>/dev/null)"
fi

echo
echo "=== app-side logs ==="
logcat -d -s ColgramTempMail:* ColgramVersions:* 2>/dev/null | tail -20
'''


def _ensure_online():
    """MuMu drops its adb transport constantly ("device offline"/"not found").

    Every call site in this script goes through here because a probe that fails on a
    flaky transport tells you nothing about the app, and it is easy to mistake that for
    a real network failure — the exact class of confusion this script exists to avoid.
    """
    for _ in range(6):
        r = subprocess.run([ADB, "devices"], capture_output=True, text=True, timeout=30)
        for line in (r.stdout or "").splitlines():
            if line.startswith("emulator-5554") and line.split()[-1] == "device":
                return True
        subprocess.run([ADB, "kill-server"], capture_output=True, timeout=30)
        subprocess.run([ADB, "start-server"], capture_output=True, timeout=30)
        subprocess.run([ADB, "connect", "127.0.0.1:7555"], capture_output=True, timeout=30)
        time.sleep(2)
    return False


def sh(cmd, timeout=120):
    _ensure_online()
    r = subprocess.run([ADB, "-s", "emulator-5554", "shell", cmd],
                       capture_output=True, text=True, errors="replace", timeout=timeout)
    return (r.stdout or "") + (r.stderr or "")


def main():
    if not _ensure_online():
        print("could not bring the emulator online")
        return 1
    with open("_probe.sh", "w", encoding="utf-8", newline="\n") as fh:
        fh.write(PROBE)
    subprocess.run([ADB, "-s", "emulator-5554", "push", "_probe.sh", "/data/local/tmp/probe.sh"],
                   capture_output=True, text=True, timeout=60)
    out = sh("sh /data/local/tmp/probe.sh")
    print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
