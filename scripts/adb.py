"""Self-healing adb wrapper for the MuMu emulator.

MuMu drops its transport constantly — usually between two calls a few hundred ms apart.
Every raw `adb -s emulator-5554 ...` is therefore a coin flip, and the failures look like
app problems (a missing screenshot, an empty logcat) rather than a transport hiccup.

Usage:
    python adb.py shell "pidof org.colgram.messenger"
    python adb.py logcat -d -s ColgramTempMail:*
    python adb.py shot ci-artifact/now.png
    python adb.py install <apk>
"""
import os
import subprocess
import sys
import time

ADB = r"C:\android-sdk\platform-tools\adb.exe"
DEV = "emulator-5554"


def online(retries=8):
    for _ in range(retries):
        r = subprocess.run([ADB, "devices"], capture_output=True, text=True, timeout=30)
        for line in (r.stdout or "").splitlines():
            parts = line.split()
            if len(parts) == 2 and parts[0] == DEV and parts[1] == "device":
                return True
        subprocess.run([ADB, "kill-server"], capture_output=True, timeout=30)
        subprocess.run([ADB, "start-server"], capture_output=True, timeout=30)
        subprocess.run([ADB, "connect", "127.0.0.1:7555"], capture_output=True, timeout=30)
        time.sleep(2)
    return False


def run(args, retries=4, timeout=300):
    """Run an adb command, retrying when the transport (not the command) failed."""
    for attempt in range(retries):
        if not online():
            continue
        r = subprocess.run([ADB, "-s", DEV] + args,
                           capture_output=True, text=True, errors="replace", timeout=timeout)
        out = (r.stdout or "") + (r.stderr or "")
        if "device offline" not in out and "not found" not in out:
            return r.returncode, out
        time.sleep(1.5)
    return 1, "adb: transport could not be stabilised after %d tries" % retries


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        return 1

    if a[0] == "shot":
        dest = a[1] if len(a) > 1 else "ci-artifact/shot.png"
        rc, out = run(["shell", "screencap -p /sdcard/_shot.png"])
        if rc != 0:
            print(out)
            return rc
        rc, out = run(["pull", "/sdcard/_shot.png", dest])
        print(out.strip() or ("saved " + dest))
        return rc

    if a[0] == "tap":
        # Coordinates are in LANDSCAPE space (rotation 1): 1600x900 for a 1088x612 grab.
        return run(["shell", "input tap %s %s" % (a[1], a[2])])[0]

    rc, out = run(a)
    print(out)
    return rc


if __name__ == "__main__":
    sys.exit(main())
