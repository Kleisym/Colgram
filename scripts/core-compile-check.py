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

GRADLE_CACHE = r"C:\Users\virsu\.gradle\caches\modules-2\files-2.1"

def find_aar(group, artifact, version_hint):
    """Locate an AAR by group/artifact under the Gradle cache, any pinned hash.

    The hash directory in the middle of the path changes whenever Gradle re-resolves
    the dependency, and a probe that hardcodes it silently loses its classpath and
    starts reporting fake 'cannot find symbol' errors (androidx.core.content went
    missing exactly this way).
    """
    base = os.path.join(GRADLE_CACHE, group, artifact)
    if not os.path.isdir(base):
        return None
    hits = []
    for version_dir in sorted(os.listdir(base)):
        if version_hint and not version_dir.startswith(version_hint):
            continue
        for root, _, files in os.walk(os.path.join(base, version_dir)):
            for f in files:
                if f.endswith(".aar"):
                    hits.append(os.path.join(root, f))
    return max(hits, key=os.path.getmtime) if hits else None

AARS = [
    find_aar("androidx.core", "core", "1."),
    find_aar("androidx.appcompat", "appcompat", "1."),
    find_aar("com.google.android.material", "material", "1."),
]

cp = [android_jar]
FALLBACK_JARS = {
    "core": os.path.join(r"C:\Colgram\ci-artifact\tmpl-check\libs", "core-1.12.0.jar"),
    "appcompat": os.path.join(r"C:\Colgram\ci-artifact\tmpl-check\libs", "appcompat-1.6.1.jar"),
    "material": os.path.join(r"C:\Colgram\ci-artifact\tmpl-check\libs", "material-1.11.0.jar"),
}
FALLBACK_KEYS = ["core", "appcompat", "material"]
for i, aar in enumerate(AARS):
    if aar is None or not os.path.exists(aar):
        fallback = FALLBACK_JARS.get(FALLBACK_KEYS[i]) if i < len(FALLBACK_KEYS) else None
        if fallback and os.path.exists(fallback):
            print("!! aar missing, using extracted fallback:", fallback)
            cp.append(fallback)
        else:
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
