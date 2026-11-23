#!/usr/bin/env python3
"""Build libcolgramdesync.so for all 4 Android ABIs using NDK clang."""
import os
import shutil
import subprocess
import sys

NDK_BIN = r"C:\android-sdk\ndk\26.1.10909125\toolchains\llvm\prebuilt\windows-x86_64\bin"
SRC = r"F:\brom\Colgram\colgram-core\src\main\cpp\colgram_desync.c"
OUT_ROOTS = [
    r"F:\brom\Colgram\colgram-core\src\main\jniLibs",
    r"F:\brom\Colgram\Telegram-Src\colgram-core\src\main\jniLibs",
]

TARGETS = [
    ("arm64-v8a", "aarch64-linux-android24-clang.cmd"),
    ("armeabi-v7a", "armv7a-linux-androideabi24-clang.cmd"),
    ("x86", "i686-linux-android24-clang.cmd"),
    ("x86_64", "x86_64-linux-android24-clang.cmd"),
]

def main() -> int:
    if not os.path.isfile(SRC):
        print(f"ERROR: source not found: {SRC}", file=sys.stderr)
        return 1
    for abi, compiler in TARGETS:
        cc = os.path.join(NDK_BIN, compiler)
        if not os.path.isfile(cc):
            print(f"ERROR: compiler not found: {cc}", file=sys.stderr)
            return 1
        primary_dir = os.path.join(OUT_ROOTS[0], abi)
        os.makedirs(primary_dir, exist_ok=True)
        out_so = os.path.join(primary_dir, "libcolgramdesync.so")
        cmd = [
            cc,
            "-O2",
            "-fPIC",
            "-shared",
            "-std=c11",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-Wl,-z,max-page-size=16384",
            "-Wl,--build-id=sha1",
            SRC,
            "-llog",
            "-o",
            out_so,
        ]
        print(f"[build] {abi} -> {out_so}")
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode != 0:
            print(res.stdout)
            print(res.stderr, file=sys.stderr)
            return res.returncode
        size = os.path.getsize(out_so)
        print(f"  OK ({size} bytes)")
        for mirror_root in OUT_ROOTS[1:]:
            mirror_dir = os.path.join(mirror_root, abi)
            os.makedirs(mirror_dir, exist_ok=True)
            shutil.copy2(out_so, os.path.join(mirror_dir, "libcolgramdesync.so"))
    print("All 4 ABIs built and synced cleanly.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
