#!/usr/bin/env bash
#
# gradle-preflight.sh — clear the Windows file-lock debris that silently kills
# Colgram builds, WITHOUT the mistake that causes it.
#
# WHY THIS EXISTS
# ---------------
# On Windows, a killed or crashed Gradle build leaves file handles open on
# generated artifacts. The next build then fails, and it fails in ways that look
# nothing like a file-lock problem:
#
#   1. `java.io.IOException: Unable to delete directory '.../compileTransaction/stash-dir'`
#      on :TMessagesProj:compileReleaseJavaWithJavac — one orphaned .class file
#      blocks the whole compile.
#
#   2. `Failed to load cache entry ...: java.io.FileNotFoundException:
#      C:\Users\<u>\.gradle\caches\build-cache-1\build-cache-1.lock (Отказано в доступе)`
#      on :TMessagesProj_AppStandalone:mergeDexAfatRelease — a stale lock file.
#
#   3. `Could not download ... .tmp\gradle_downloadNNNbin (Отказано в доступе)`.
#
# All three surface 6-25 MINUTES in, so they read as "the build hangs".
#
# 🔴 THE CARDINAL RULE
# --------------------
# NEVER delete a Gradle cache directory while a daemon is alive. Doing so is what
# CREATES the poisoned `build-cache-1.lock`. Stop the daemons first, every time.
#
# USAGE
#   ./scripts/gradle-preflight.sh            # safe: stop daemons, clear debris
#   ./scripts/gradle-preflight.sh --deep     # also wipe build-cache-1 (slower next build)

set -u

REPO="${COLGRAM_REPO:-/c/Colgram/Telegram-Src}"
JAVA_EXE="${JAVA_EXE:-C:\\colgram-tools\\jdk-17.0.20.1+1\\bin\\java.exe}"
GRADLE_JAR="gradle\\wrapper\\gradle-wrapper.jar"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-/c/Users/$USER/.gradle}"

DEEP=0
[ "${1:-}" = "--deep" ] && DEEP=1

echo "== Colgram Gradle preflight =="
echo "   repo: $REPO"

# ---------------------------------------------------------------------------
# 1. Stop daemons FIRST. Everything else depends on this being done before any
#    cache directory is touched.
# ---------------------------------------------------------------------------
echo "[1/4] stopping Gradle daemons..."
if [ -f "$REPO/$GRADLE_JAR" ]; then
  ( cd "$REPO" && JAVA_HOME="${JAVA_HOME:-C:\\colgram-tools\\jdk-17.0.20.1+1}" \
      "$JAVA_EXE" -cp "$GRADLE_JAR" org.gradle.wrapper.GradleWrapperMain --stop ) 2>&1 | tail -3
else
  echo "      (wrapper jar not found at $REPO/$GRADLE_JAR — skipping)"
fi

# Give the OS a moment to release handles.
sleep 4

# ---------------------------------------------------------------------------
# 2. Verify nothing is alive. If a java process survives, bail out rather than
#    deleting caches that are still held.
# ---------------------------------------------------------------------------
echo "[2/4] checking for live JVMs..."
ALIVE=$(tasklist 2>/dev/null | iconv -f UTF-16LE -t UTF-8 2>/dev/null | grep -ci java)
echo "      java processes: $ALIVE"
if [ "$ALIVE" -ne 0 ]; then
  echo "      !! a JVM is still running. Refusing to touch caches — that is how"
  echo "      !! the poisoned build-cache-1.lock gets created in the first place."
  echo "      !! Close the build (or wait for it), then re-run this script."
  exit 1
fi

# ---------------------------------------------------------------------------
# 3. Clear javac transaction debris. This is the cheap, targeted, always-safe fix
#    — it is what unblocks "Unable to delete directory .../stash-dir".
# ---------------------------------------------------------------------------
echo "[3/4] clearing compileTransaction residue..."
COUNT=0
while IFS= read -r d; do
  [ -z "$d" ] && continue
  rm -rf "$d" 2>/dev/null && COUNT=$((COUNT + 1))
done < <(find "$REPO" -type d -name compileTransaction 2>/dev/null)
echo "      cleared $COUNT compileTransaction dir(s)"

# ---------------------------------------------------------------------------
# 4. Optional deep clean of the build cache + Gradle tmp.
# ---------------------------------------------------------------------------
if [ "$DEEP" -eq 1 ]; then
  echo "[4/4] deep-clean of build-cache-1 and .tmp (safe now: no JVMs alive)"
  rm -rf "$GRADLE_USER_HOME/caches/build-cache-1" 2>/dev/null
  mkdir -p "$GRADLE_USER_HOME/caches/build-cache-1"
  rm -rf "$GRADLE_USER_HOME/.tmp" 2>/dev/null
  mkdir -p "$GRADLE_USER_HOME/.tmp"
  if [ -e "$GRADLE_USER_HOME/caches/build-cache-1/build-cache-1.lock" ]; then
    echo "      !! lock STILL present — something holds it; investigate before building"
  else
    echo "      cache + tmp reset, no lock present"
  fi
else
  echo "[4/4] skipped deep clean (pass --deep to wipe build-cache-1)"
fi

echo "== preflight done =="
