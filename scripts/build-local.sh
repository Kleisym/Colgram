#!/usr/bin/env bash
#
# build-local.sh — Local Colgram build helper (Windows / Git Bash)
#
# Why this exists:
#   `./gradlew` fails under Git Bash with
#     "Could not find or load main class org.gradle.wrapper.GradleWrapperMain"
#   so we invoke the wrapper JAR through java directly. Also, Telegram's build
#   requires JDK 17 specifically — the system JDK (23) is rejected.
#
# Usage:
#   ./scripts/build-local.sh check       # 3s syntax gate on everything edited
#   ./scripts/build-local.sh check-core  # 3s syntax gate on colgram-core
#   ./scripts/build-local.sh dev         # patcher + Gradle Java compile only  <- inner loop
#   ./scripts/build-local.sh compile     # Gradle Java compile only
#   ./scripts/build-local.sh apk         # full: assemble the release APK
#   ./scripts/build-local.sh apkfast     # apk with more workers (only when RAM is free)
#   ./scripts/build-local.sh clean       # clean build outputs
#
# Timing measured on this machine (6 cores, 16 GB, 2026-09-22):
#   check            ~3 s      no Gradle, no daemon
#   dev / compile    13-35 s   one Java compile task, everything else up to date
#   apk              2-7 min   dexing 9 dex files + packaging + aligning a 181 MB APK
#
# `apk` is slow for a reason that a Gradle flag cannot fix: the box runs at 92% memory
# load with a game and the emulator open, so a 4 GB heap pages to disk. org.gradle.workers.max
# is 2 and parallel is false in gradle.properties precisely because of that. `apkfast` raises
# them for the case where nothing else is running; use it only then, or it gets slower.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$REPO_ROOT/Telegram-Src"

JDK17="C:\\colgram-tools\\jdk-17.0.20.1+1"
SDK_DIR="C:\\android-sdk"

export JAVA_HOME="$JDK17"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"

WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
JAVA_EXE="$JDK17\\bin\\java.exe"

# `check` needs no Gradle and no daemon, so handle it before the SRC_DIR guard.
#
# Path handling: under Git Bash `pwd` returns a POSIX path (/c/Colgram), but native
# Windows python cannot read that — passing it yields C:\c\Colgram. Convert the
# /c/... form to the C:/... form that Windows python understands.
CHECK_PY="$(pwd)/scripts/colgram-check.py"
case "$CHECK_PY" in
    /[a-zA-Z]/*)
        drive=$(echo "$CHECK_PY" | cut -c2 | tr '[:lower:]' '[:upper:]')
        rest=$(echo "$CHECK_PY" | cut -c3-)
        CHECK_PY="${drive}:${rest}"
        ;;
esac
[ -f "$CHECK_PY" ] || CHECK_PY="$REPO_ROOT/scripts/colgram-check.py"

case "${1:-check}" in
    check)
        exec python "$CHECK_PY" --syntax --core --all-templates
        ;;
    check-core)
        exec python "$CHECK_PY" --syntax --core
        ;;
    check-full)
        exec python "$CHECK_PY" --core --all-templates
        ;;
esac

if [ ! -d "$SRC_DIR" ]; then
    echo "[!] Telegram-Src not found at $SRC_DIR"
    echo "    Run: python scripts/apply-patches.py Telegram-Src"
    exit 1
fi

if [ ! -f "$SRC_DIR/$WRAPPER_JAR" ]; then
    echo "[!] Gradle wrapper JAR missing at $SRC_DIR/$WRAPPER_JAR"
    exit 1
fi

cd "$SRC_DIR"

run_gradle() {
    "$JAVA_EXE" -cp "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
}

apply_patches() {
    echo "[*] Re-applying patches (mirrors colgram-core and copies the UI templates)"
    (cd "$REPO_ROOT" && python scripts/apply-patches.py Telegram-Src | tail -3)
}

case "${1:-check}" in
    dev)
        apply_patches
        echo "[*] Compiling Java only — this is the fast loop, use it before any apk build"
        run_gradle :TMessagesProj:compileReleaseJavaWithJavac
        ;;
    compile)
        run_gradle :TMessagesProj:compileReleaseJavaWithJavac
        ;;
    apk)
        apply_patches
        echo "[*] Assembling release APK (this takes a long time on a cold build)"
        COLGRAM_BUILD_STAMP="${COLGRAM_BUILD_STAMP:-local$(date +%H%M)}"             run_gradle :TMessagesProj_AppStandalone:assembleAfatRelease
        ;;
    apkfast)
        apply_patches
        echo "[*] Assembling with 4 workers and parallel tasks — only when RAM is free"
        COLGRAM_BUILD_STAMP="${COLGRAM_BUILD_STAMP:-local$(date +%H%M)}"             run_gradle --max-workers=4 -Dorg.gradle.parallel=true             :TMessagesProj_AppStandalone:assembleAfatRelease
        ;;
    clean)
        run_gradle clean
        ;;
    *)
        echo "Unknown target: $1"
        echo "Usage: $0 [check|check-core|check-full|dev|compile|apk|apkfast|clean]"
        exit 1
        ;;
esac
