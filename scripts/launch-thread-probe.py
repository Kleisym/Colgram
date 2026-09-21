"""Sample thread growth during app launch, at high frequency.

The poller storm (2366 starts observed) happens during boot, when loadDialogs fires
repeatedly. `startBotUpdatesPoller` is `synchronized`, but the guard is
`existing != null && existing.isAlive()` and the put happens at the END of the method -
after `new Thread(...)` is constructed. If the thread does not reach RUNNABLE before the
next caller arrives, `isAlive()` is still false and a second thread is created.

This samples /proc/<pid>/task every 150ms from launch and prints the growth curve.
"""
import subprocess
import sys
import time

ADB = r"C:\android-sdk\platform-tools\adb.exe"
DEV = "127.0.0.1:7555"
PKG = "org.colgram.messenger"


def sh(cmd, timeout=30):
    r = subprocess.run([ADB, "-s", DEV, "shell", cmd],
                       capture_output=True, text=True, errors="replace", timeout=timeout)
    return (r.stdout or "").replace("\r", "").strip()


def main():
    print("--- force-stop ---")
    sh("am force-stop %s" % PKG)
    time.sleep(2)

    print("--- clear logcat ---")
    subprocess.run([ADB, "-s", DEV, "logcat", "-c"], capture_output=True)

    print("--- launch ---")
    sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % PKG)

    pid = ""
    peak = 0
    curve = []
    for i in range(120):                      # up to ~18s of sampling
        time.sleep(0.15)
        if not pid:
            pid = sh("pidof %s" % PKG)
        if not pid:
            continue
        n = sh("ls /proc/%s/task 2>/dev/null | wc -l" % pid)
        try:
            n = int(n)
        except ValueError:
            continue
        peak = max(peak, n)
        curve.append((round(i * 0.15, 2), n))
        if i % 20 == 19:
            print("   t+%.1fs threads=%d" % ((i + 1) * 0.15, n))

    if not pid:
        print("app never came up")
        return

    print()
    print("pid=%s  peak threads=%d  final=%d" % (pid, peak, curve[-1][1] if curve else -1))

    out = subprocess.run([ADB, "-s", DEV, "logcat", "-d", "-s", "ColgramBotSync:*"],
                         capture_output=True, text=True, errors="replace", timeout=45)
    txt = (out.stdout or "").replace("\r", "")
    print("poller starts during launch: %d" % txt.count("Starting bot updates poller"))
    print("ipv4 force line: %s" %
          ("PRESENT" if "forcing IPv4" in txt else "absent"))
    ipv6_fail = txt.count("2001:67c:4e8:f004::9")
    print("IPv6-address connect attempts in our own logs: %d" % ipv6_fail)

    # print the growth curve compressed
    print()
    print("growth curve (t, threads):")
    step = max(1, len(curve) // 24)
    print("   " + "  ".join("%.1f:%d" % c for c in curve[::step]))


if __name__ == "__main__":
    main()
