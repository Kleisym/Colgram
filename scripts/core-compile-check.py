"""Standalone javac probe for the colgram-core module.

A full Gradle build costs ~20 min. This compiles just the module against android.jar
plus the androidx AARs from the Gradle cache, so a type error surfaces in seconds.

Caveat: AARs contain classes.jar - we extract them to a scratch dir and put those on
the classpath, because javac cannot read an .aar directly.
"""
import os
import subprocess
import zipfile

JDK = r"C:\colgram-tools\jdk-17.0.20.1+1"
CORE = r"C:\Colgram\colgram-core\src\main\java"
WORK = r"C:\Colgram\ci-artifact\javac-probe"
OUT = os.path.join(WORK, "classes")
LIBS = os.path.join(WORK, "libs")
os.makedirs(OUT, exist_ok=True)
os.makedirs(LIBS, exist_ok=True)

# Highest available platform.
sdk = r"C:\android-sdk"
platforms = sorted(os.listdir(os.path.join(sdk, "platforms")))
android_jar = os.path.join(sdk, "platforms", platforms[-1], "android.jar")

AARS = [
    r"C:\Users\virsu\.gradle\caches\modules-2\files-2.1\androidx.core\core\1.12.0\5aa3088ed93ba8ebaea2b8a83befdfa829339e7a\core-1.12.0.aar",
    r"C:\Users\virsu\.gradle\caches\modules-2\files-2.1\androidx.appcompat\appcompat\1.6.1\6c7577004b7ebbee5ed87d512b578dd20e3c8c31\appcompat-1.6.1.aar",
    r"C:\Users\virsu\.gradle\caches\modules-2\files-2.1\com.google.android.material\material\1.11.0\e46abb2e27abed3ad274ba96b169a4c61fbb11fd\material-1.11.0.aar",
]

cp = [android_jar]
for aar in AARS:
    if not os.path.exists(aar):
        print("!! missing aar:", aar)
        continue
    dest = os.path.join(LIBS, os.path.basename(aar).replace(".aar", ".jar"))
    if not os.path.exists(dest):
        with zipfile.ZipFile(aar) as z:
            try:
                data = z.read("classes.jar")
            except KeyError:
                continue
        with open(dest, "wb") as fh:
            fh.write(data)
    cp.append(dest)

srcs = []
for root, _, files in os.walk(CORE):
    for f in files:
        if f.endswith(".java"):
            srcs.append(os.path.join(root, f))

print("android.jar:", os.path.basename(android_jar))
print("classpath jars:", len(cp))
print("sources:", len(srcs))

r = subprocess.run([os.path.join(JDK, "bin", "javac.exe"),
                    "-nowarn", "-proc:none", "-encoding", "UTF-8",
                    "-d", OUT, "-cp", os.pathsep.join(cp)] + srcs,
                   capture_output=True, text=True, errors="replace")
out = (r.stdout or "") + (r.stderr or "")
print("exit:", r.returncode)
print(out[:8000] if out.strip() else "CLEAN - no diagnostics")
