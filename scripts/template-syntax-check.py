"""Syntax-check a template file that gets copied into Telegram-Src.

Templates under scripts/templates/ are NOT part of any Gradle source set, so nothing
compiles them until the patcher copies them in. This runs javac in -proc:none parse-and-
attribute mode against the real tree's classpath so a template edit is verified before a
build.

Usage:  python template-syntax-check.py <path/to/Template.java> [...]
"""
import os
import subprocess
import sys
import zipfile

JDK = r"C:\colgram-tools\jdk-17.0.20.1+1"
SRC = r"C:\Colgram\Telegram-Src"
WORK = r"C:\Colgram\ci-artifact\tmpl-check"
OUT = os.path.join(WORK, "classes")
LIBS = os.path.join(WORK, "libs")
os.makedirs(OUT, exist_ok=True)
os.makedirs(LIBS, exist_ok=True)

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
        continue
    dest = os.path.join(LIBS, os.path.basename(aar).replace(".aar", ".jar"))
    if not os.path.exists(dest):
        with zipfile.ZipFile(aar) as z:
            try:
                data = z.read("classes.jar")
            except KeyError:
                continue
        open(dest, "wb").write(data)
    cp.append(dest)

# The patched UI tree itself, so org.telegram.* references resolve.
cp.append(os.path.join(SRC, "TMessagesProj", "src", "main", "java"))
cp.append(os.path.join(SRC, "colgram-core", "src", "main", "java"))

# Every jar the app module uses.
libs = os.path.join(SRC, "TMessagesProj", "libs")
if os.path.isdir(libs):
    for f in os.listdir(libs):
        if f.endswith(".jar"):
            cp.append(os.path.join(libs, f))

# Compile the templates TOGETHER with the whole UI tree present as source on the path.
targets = sys.argv[1:]
if not targets:
    targets = [os.path.join(r"C:\Colgram\scripts\templates", f)
               for f in os.listdir(r"C:\Colgram\scripts\templates") if f.endswith(".java")]

# Find the real UI sources so cross-references (BaseFragment, Theme, ...) resolve.
#
# The tree under Telegram-Src already holds PATCHED COPIES of every template (that is
# what the patcher does), so compiling both would report "duplicate class" instead of
# a real error. The template under scripts/templates/ is the source of truth here, so
# skip the tree's copy of anything we are compiling explicitly.
tpl_names = {os.path.basename(t) for t in targets}
ui_root = os.path.join(SRC, "TMessagesProj", "src", "main", "java")
srcs = list(targets)
skipped_dupes = 0
for root, _, files in os.walk(ui_root):
    for f in files:
        if f.endswith(".java"):
            if f in tpl_names:
                skipped_dupes += 1
                continue
            srcs.append(os.path.join(root, f))
if skipped_dupes:
    print("skipped     : %d tree copies shadowed by templates" % skipped_dupes)
for root, _, files in os.walk(os.path.join(SRC, "colgram-core", "src", "main", "java")):
    for f in files:
        if f.endswith(".java"):
            srcs.append(os.path.join(root, f))

print("android.jar :", os.path.basename(android_jar))
print("classpath   : %d entries" % len(cp))
print("sources     : %d files" % len(srcs))

# A javac @argfile is mandatory here: the full UI tree plus the templates overflows the
# Windows command line and CreateProcess fails with WinError 206 ("filename or extension
# too long") before javac is ever reached. Paths with spaces also have to be quoted
# inside the argfile, and backslashes must be doubled or javac eats them as escapes.
argfile = os.path.join(WORK, "javac.args")


def q(p):
    return '"' + p.replace("\\", "\\\\") + '"'


with open(argfile, "w", encoding="utf-8") as fh:
    fh.write("-nowarn\n-proc:none\n-encoding UTF-8\n")
    fh.write("-d " + q(OUT) + "\n")
    fh.write("-cp " + os.pathsep.join(q(p) for p in cp) + "\n")
    for s in srcs:
        fh.write(q(s) + "\n")

r = subprocess.run([os.path.join(JDK, "bin", "javac.exe"), "@" + argfile],
                   capture_output=True, text=True, errors="replace")
out = (r.stdout or "") + (r.stderr or "")
errors = [l for l in out.split("\n") if ": error:" in l]
print("exit:", r.returncode)
print("real errors: %d" % len(errors))
for l in errors[:40]:
    print("   ", l)
if not errors and r.returncode == 0:
    print("CLEAN")
