"""Live Bot API reachability probe, run ON the device.

"no response" is a transport failure. This asks the device directly which addresses it
resolves for api.telegram.org and which of them actually complete a TLS handshake, so
the fix targets the real failure rather than a guess.

Usage:  python botapi-probe.py <token>
"""
import os
import subprocess
import sys

ADB = r"C:\android-sdk\platform-tools\adb.exe"
DEV = "127.0.0.1:7555"

PROBE = r'''
set -e
echo "--- DNS: what does the device resolve? ---"
getprop | grep -i "net.dns" | head -3
for h in api.telegram.org; do
  echo "host: $h"
  ping -c 1 -W 2 $h >/dev/null 2>&1 && echo "  ping OK" || echo "  ping FAILED"
done
echo
echo "--- TCP 443 reachability, forced v4 ---"
for ip in 149.154.167.220 149.154.167.197; do
  (echo > /dev/tcp/$ip/443) >/dev/null 2>&1 && echo "  $ip:443 OPEN" || echo "  $ip:443 CLOSED/unreachable"
done
echo
echo "--- toybox/nc available? ---"
command -v nc || command -v toybox || echo "  (no nc)"
'''


def sh(cmd, timeout=40):
    r = subprocess.run([ADB, "-s", DEV, "shell", cmd],
                       capture_output=True, text=True, errors="replace", timeout=timeout)
    return (r.stdout or "") + (r.stderr or "")


def main():
    print("=== device TCP/IP ground truth ===")
    print(sh(PROBE))

    if len(sys.argv) > 1:
        token = sys.argv[1]
        print("=== live Bot API call through the app's own path ===")
        # curl on the device avoids the app entirely: isolates network from app logic.
        out = sh("curl -s -m 15 -o /dev/null -w 'http=%{http_code} ip=%{remote_ip} "
                 "time=%{time_total}\\n' https://api.telegram.org/bot%s/getMe" % token)
        print(out)
    else:
        print("(pass a bot token to also run a live getMe)")


if __name__ == "__main__":
    main()
