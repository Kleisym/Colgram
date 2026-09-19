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
#   ./scripts/build-local.sh compile     # Gradle: compile Java only (slow, full symbols)
#   ./scripts/build-local.sh apk         # full: assemble the release APK
#   ./scripts/build-local.sh clean       # clean build outputs
#
# Prefer `check` while editing; `compile`/`apk` are for shipping.
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

case "${1:-check}" in
    compile)
        echo "[*] Compiling Java sources (flavour: standalone) — slower, full symbol check"
        run_gradle :TMessagesProj:compileStandaloneJavaWithJavac
        ;;
    apk)
        echo "[*] Assembling release APK (this takes a long time on a cold build)"
        run_gradle :TMessagesProj_AppStandalone:assembleAfatRelease
        ;;
    clean)
        run_gradle clean
        ;;
    *)
        echo "Unknown target: $1"
        echo "Usage: $0 [check|check-core|check-full|compile|apk|clean]"
        exit 1
        ;;
esac
