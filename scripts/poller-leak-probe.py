"""Measure the bot-poller spawn burst on device.

`loadDialogs()` is called constantly, and the patcher injects an unconditional
`syncBotDialogs()` into it, which calls `startBotUpdatesPoller()`. That method is
`synchronized` with a `pollerThreads` guard, so it should be idempotent - if it is not,
every dialog refresh spawns another poller thread.

This counts poller-start log lines and live threads before/after a UI refresh, so the
leak is measured rather than guessed.

Usage:  python poller-leak-probe.py [seconds]
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


def threads():
    pid = sh("pidof %s" % PKG)
    if not pid:
        return -1, ""
    n = sh("ls /proc/%s/task 2>/dev/null | wc -l" % pid)
    return int(n or -1), pid


def starts():
    out = subprocess.run([ADB, "-s", DEV, "logcat", "-d", "-s", "ColgramBotSync:*"],
                         capture_output=True, text=True, errors="replace", timeout=40)
    txt = (out.stdout or "").replace("\r", "")
    return txt.count("Starting bot updates poller")


def main():
    secs = int(sys.argv[1]) if len(sys.argv) > 1 else 25

    t0, pid = threads()
    s0 = starts()
    print("pid=%s  threads=%d  poller-starts=%d" % (pid, t0, s0))

    print("--- forcing a dialog reload on every account ---")
    for acct in range(0, 4):
        sh("am broadcast -a org.colgram.TEST_SYNC --ei account %d" % acct)

    print("--- waiting %ds, sampling thread count ---" % secs)
    samples = []
    for i in range(secs):
        time.sleep(1)
        n, _ = threads()
        samples.append(n)
        if i % 5 == 4:
            print("   t+%02ds threads=%d" % (i + 1, n))

    t1, _ = threads()
    s1 = starts()
    print()
    print("threads: %d -> %d  (delta %+d, peak %d)"
          % (t0, t1, t1 - t0, max(samples) if samples else t1))
    print("poller-starts: %d -> %d  (delta %+d)" % (s0, s1, s1 - s0))
    print()
    if s1 - s0 > 40:
        print("VERDICT: burst - syncBotDialogs is being called far more than once per refresh")
    else:
        print("VERDICT: idle - no burst while the UI is still")


if __name__ == "__main__":
    main()
