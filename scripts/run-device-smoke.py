#!/usr/bin/env python3
"""Run account-free instrumentation on one explicitly selected Android device."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="adb serial (MuMu: see MuMuManager info)")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", default=shutil.which("adb") or str(
        Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", r"C:\android-sdk")))
        / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")))
    parser.add_argument("--package", default="org.colgram.messenger.web")
    parser.add_argument("--classes", default="org.colgram.core.ColgramDirectConnectionDeviceTest")
    parser.add_argument("--app-apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    prefix = [args.adb, "-s", args.serial]

    def run(*command, timeout=120):
        return subprocess.run(prefix + list(command), capture_output=True, timeout=timeout)

    def shell(*command):
        result = run("shell", *command)
        if result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", "replace"))
        return result.stdout.decode("utf-8", "replace").strip()

    if run("get-state").stdout.strip() != b"device":
        raise RuntimeError("Selected device is not online: " + args.serial)
    metadata = {"serial": args.serial, "abi": shell("getprop", "ro.product.cpu.abilist"),
                "android": shell("getprop", "ro.build.version.release"),
                "classes": args.classes, "apks": {}}
    for name, apk in (("app", args.app_apk), ("test", args.test_apk)):
        if apk is None:
            continue
        with apk.open("rb") as handle:
            digest = hashlib.file_digest(handle, "sha256").hexdigest()
        result = run("install", "-r", str(apk.resolve()), timeout=300)
        output = result.stdout + result.stderr
        (args.output / ("install-" + name + ".log")).write_bytes(output)
        if result.returncode or b"Success" not in output:
            raise RuntimeError("APK installation failed; see install-" + name + ".log")
        metadata["apks"][name] = {"path": str(apk.resolve()), "sha256": digest}
    runner = args.package + ".test/androidx.test.runner.AndroidJUnitRunner"
    if runner not in shell("pm", "list", "instrumentation"):
        raise RuntimeError("Instrumentation package is not installed: " + runner)
    (args.output / "connectivity-before.txt").write_text(shell("dumpsys", "connectivity"), encoding="utf-8")
    run("logcat", "-c")
    command = prefix + ["shell", "am", "instrument", "-w", "-r", "-e", "class", args.classes, runner]
    metadata["command"] = command
    # A blocked endpoint can flood Android's ring buffer before the suite finishes.
    # Keep the actual early native verdicts rather than only the final few seconds.
    live_log = (args.output / "logcat-live.txt").open("wb")
    live_process = subprocess.Popen(prefix + ["logcat", "-v", "threadtime",
        "ColgramDirectTest:I", "ColgramDirectApi:I", "ColgramOnboardingTest:I", "ColgramIntroSlides:I",
        "ColgramGuestProbe:I", "ColgramDpiApi:I", "ColgramSubRoute:I", "ColgramVpn:I",
        "ColgramProxyManager:I", "ColgramDpiBypass:I", "ColgramWarpMasque:I",
        "ColgramMasqueVpn:I", "TestRunner:I", "AndroidRuntime:E", "*:S"],
        stdout=live_log, stderr=subprocess.STDOUT)
    try:
        result = subprocess.run(command, capture_output=True, timeout=600)
        log = result.stdout + result.stderr
        metadata["adb_exit"] = result.returncode
    except subprocess.TimeoutExpired as error:
        log = (error.stdout or b"") + (error.stderr or b"") + b"\nHOST_TIMEOUT\n"
        metadata["adb_exit"] = 124
    finally:
        live_process.terminate()
        try:
            live_process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            live_process.kill()
            live_process.wait()
        live_log.close()
    output = args.output / "instrumentation.log"
    output.write_bytes(log)
    spec = importlib.util.spec_from_file_location("colgram_device_results", Path(__file__).with_name("device-tests.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    tests, clean = module.parse(output)
    metadata["tests"] = [{"name": name, "passed": status == 0} for name, status in tests]
    metadata["clean_termination"] = clean
    metadata["passed"] = metadata["adb_exit"] == 0 and clean and bool(tests) and all(status == 0 for _, status in tests)
    (args.output / "logcat.txt").write_bytes(run("logcat", "-d", "-v", "threadtime").stdout)
    (args.output / "connectivity-after.txt").write_text(shell("dumpsys", "connectivity"), encoding="utf-8")
    screenshot = run("exec-out", "screencap", "-p")
    if screenshot.returncode == 0 and screenshot.stdout.startswith(b"\x89PNG"):
        (args.output / "screen.png").write_bytes(screenshot.stdout)
    (args.output / "result.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(log.decode("utf-8", "replace"), end="")
    print("DEVICE_SMOKE=" + ("PASS" if metadata["passed"] else "FAIL"))
    return 0 if metadata["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
