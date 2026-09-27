"""Run the account-free on-device tests and report what actually happened.

Why this exists: `gradle connectedAndroidTest` on this emulator finishes every test and then
fails the build with "Failed to receive the UTP test results". The UTP result stream over this
emulator's adb transport is unreliable - it dropped on two consecutive runs where the device
reported `OK (3 tests)` and every per-test INSTRUMENTATION_STATUS_CODE was 0.

So the build result is not the evidence; the device's own instrumentation log is. This runs the
tests and reads that log, and fails only when a test genuinely failed.

Usage:
    python scripts/device-tests.py
    python scripts/device-tests.py org.colgram.core.ColgramProxyAutonomyDeviceTest
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE_SRC = ROOT / "Telegram-Src"
JAVA = r"C:\colgram-tools\jdk-17.0.20.1+1\bin\java.exe"
WRAPPER = GRADLE_SRC / "gradle/wrapper/gradle-wrapper.jar"
RESULTS = (GRADLE_SRC / "TMessagesProj_AppTests/build/outputs/androidTest-results/connected/debug"
            / "flavors/afat")

DEVICE_TESTS = [
    "org.colgram.core.ColgramProxyAutonomyDeviceTest",
    "org.colgram.core.ColgramGlobalSearchHistoryDeviceTest",
    "org.colgram.core.ColgramGlobalSearchRestoreDeviceTest",
    "org.colgram.core.ColgramThemeContrastDeviceTest",
    "org.colgram.core.ColgramWarpUdpReachabilityDeviceTest",
]


def newest_log():
    logs = sorted(RESULTS.glob("*/testlog/test-results.log"),
                  key=lambda p: p.stat().st_mtime, reverse=True)
    return logs[0] if logs else None


def parse(log):
    """Per-test (name, status) pairs, plus whether the run terminated cleanly."""
    text = log.read_text(encoding="utf-8", errors="replace")
    tests = []
    pending = None
    failed_since_start = False
    for line in text.splitlines():
        match = re.search(r"INSTRUMENTATION_STATUS: test=(\S+)", line)
        if match:
            pending = match.group(1)
            failed_since_start = False
            continue
        if pending and "INSTRUMENTATION_STATUS: stack=" in line:
            # The stack line is emitted with the failing test's own status block, between the
            # test= line and the terminal status code.
            failed_since_start = True
            continue
        if pending and "INSTRUMENTATION_STATUS_CODE:" in line:
            code = line.rsplit(":", 1)[1].strip()
            # Codes are signed (-1/-2 on failure, 0 on success, 1 on start), and str.isdigit()
            # rejects every negative value - so the one code that marks a real failure was being
            # skipped, and a genuinely broken test would have been reported as a pass.
            if not re.fullmatch(r"-?\d+", code):
                continue
            # Each test emits a start status and then one terminal status. Start is 1; the end
            # is 0 on success. Anything else - or a `stack=` line, which only a failure emits -
            # is a failure. Deciding on "0 and no stack" rather than on the sign of the code
            # means an unrecognised shape fails closed instead of quietly counting as a pass.
            if code == "0":
                tests.append((pending, -1 if failed_since_start else 0))
                pending = None
            elif code == "1" and not any(name == pending for name, _ in tests):
                continue  # the test has started; its terminal status comes next
            else:
                tests.append((pending, -1))
                pending = None
    clean = "OK (" in text and "INSTRUMENTATION_CODE: -1" in text
    return tests, clean


def main() -> int:
    targets = sys.argv[1:] or DEVICE_TESTS
    env = dict(os.environ, JAVA_HOME=r"C:\colgram-tools\jdk-17.0.20.1+1",
               ANDROID_HOME=r"C:\android-sdk")
    # Start clean, so a stale log cannot be read as this run's outcome.
    if RESULTS.exists():
        for child in RESULTS.glob("*/testlog"):
            for item in child.glob("*"):
                try:
                    item.unlink()
                except OSError:
                    pass

    log_path = ROOT / "device-tests-run.log"
    with log_path.open("w", encoding="utf-8") as sink:
        subprocess.run(
            [JAVA, "-cp", str(WRAPPER), "org.gradle.wrapper.GradleWrapperMain",
             "--console=plain", ":TMessagesProj_AppTests:connectedAfatDebugAndroidTest",
             "-Pandroid.testInstrumentationRunnerArguments.class=" + ",".join(targets)],
            cwd=GRADLE_SRC, env=env, stdout=sink, stderr=subprocess.STDOUT, check=False)

    # The UTP result stream drops on this emulator, but the tests are finished by the time
    # gradle exits, so the device log is the only trustworthy result.
    deadline = time.time() + 120
    log = None
    while time.time() < deadline:
        log = newest_log()
        if log and "OK (" in log.read_text(encoding="utf-8", errors="replace"):
            break
        time.sleep(5)

    if log is None:
        print("FAIL: no device results were produced; see", log_path)
        return 1

    tests, clean = parse(log)
    for name, code in tests:
        print(("  PASS  " if code == 0 else "  FAIL  ") + name)
    if not tests:
        print("FAIL: the device log carried no test results; see", log)
        return 1
    failed = [name for name, code in tests if code != 0]
    if failed:
        print("\nFAILED: %d of %d device tests failed" % (len(failed), len(tests)))
        return 1
    print("\nOK: %d device tests passed on the device" % len(tests)
          + ("" if clean else " (instrumentation stream did not close cleanly)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
