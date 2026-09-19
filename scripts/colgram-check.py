#!/usr/bin/env python3
"""
colgram-check.py — Fast verification for Colgram edits.

The problem this solves
-----------------------
A full `:TMessagesProj:compileStandaloneJavaWithJavac` takes 15-30 minutes: it runs
AAPT, resource merging, Kotlin, ~4000 Java files and dexing. That is unusable as an
edit/verify loop when you changed 30 lines.

This script gives a syntax gate in ~2-3 seconds.

Modes
-----
    python scripts/colgram-check.py --syntax                 # changed files (fast)
    python scripts/colgram-check.py --syntax --core          # colgram-core module
    python scripts/colgram-check.py --syntax --all-templates # scripts/templates/*.java
    python scripts/colgram-check.py --syntax --core --all-templates
    python scripts/colgram-check.py --syntax File.java ...   # specific files

    python scripts/colgram-check.py --all-templates          # full symbol check
    python scripts/colgram-check.py File.java                # full symbol check

What --syntax catches
---------------------
Exactly the failure classes the patch engine produces, which is where nearly all
breakage actually comes from:
    - unbalanced braces / parens from regex surgery
    - "reached end of file while parsing"
    - broken string literals and malformed declarations
These are reported as hard errors; the classpath-dependent noise is filtered.

What the full (default) mode adds
---------------------------------
Symbol resolution (undefined type, wrong signature, missing method). It needs a
complete compiled class set. NOTE: the committed build output is currently PARTIAL
(an interrupted Gradle run left ~103 packages with sources but no .class files), so
full mode may report errors originating in Telegram files you never touched. Prefer
--syntax for routine edits, and treat full-mode errors outside your target file as
"the build cache is stale", not "my edit is broken". A clean full build regenerates
the cache.

Usage note: a full Gradle compile is only needed before shipping, not per edit.
"""

import os
import re
import shutil
import subprocess
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO, "Telegram-Src")
JAVA_HOME = r"C:\colgram-tools\jdk-17.0.20.1+1"
JAVAC = os.path.join(JAVA_HOME, "bin", "javac.exe")

CLASSES_DIRS = [
    os.path.join(SRC, "TMessagesProj", "build", "intermediates", "javac",
                 "standalone", "compileStandaloneJavaWithJavac", "classes"),
    os.path.join(SRC, "colgram-core", "build", "intermediates", "javac",
                 "release", "compileReleaseJavaWithJavac", "classes"),
]
CP_FILE = os.path.join(SRC, "TMessagesProj", "build", "colgram-classpath.txt")
R_JAR = os.path.join(SRC, "TMessagesProj", "build", "intermediates",
                     "compile_r_class_jar", "standalone", "generateStandaloneRFile", "R.jar")
ANDROID_JAR = r"C:\android-sdk\platforms\android-34\android.jar"

# Source roots javac may pull dependencies from on demand (via -sourcepath).
# This is what lets us check one file without compiling all of Telegram.
SOURCE_ROOTS = [
    os.path.join(SRC, "TMessagesProj", "src", "main", "java"),
    os.path.join(SRC, "TMessagesProj", "src", "main", "kotlin"),
    # BuildConfig is generated, not checked in. Without this root javac reports
    # "cannot find symbol: class BuildConfig" on every file that touches it.
    os.path.join(SRC, "TMessagesProj", "build", "generated", "source", "buildConfig",
                 "standalone"),
    os.path.join(SRC, "colgram-core", "src", "main", "java"),
]

def decode_output(b):
    """javac on a Russian Windows locale emits cp1251/cp866 diagnostics."""
    if not b:
        return ""
    for enc in ("utf-8", "cp1251", "cp866", "latin-1"):
        try:
            return b.decode(enc)
        except UnicodeDecodeError:
            continue
    return b.decode("utf-8", errors="replace")


def syntax_only_check(targets, out_dir):
    """
    Fastest possible gate (1-3s for a few files): parse each target without resolving
    symbols.

    This catches exactly the error classes the patch engine produces most often:
      - unbalanced braces / parens from regex surgery
      - broken string literals from a bad anchor replacement
      - malformed declarations

    All targets are passed in ONE javac invocation. Calling javac per file costs
    ~3s of JVM startup each, which dwarfs the actual work once there are more than a
    couple of files.
    """
    cmd = [
        JAVAC,
        "-proc:none",
        "-encoding", "UTF-8",
        "-d", out_dir,
        "-sourcepath", "",   # resolve nothing from source
    ] + targets
    try:
        res = subprocess.run(cmd, capture_output=True, timeout=120)
    except subprocess.TimeoutExpired:
        return 2, "[!] syntax check timed out"
    out = decode_output(res.stdout) + decode_output(res.stderr)
    # javac without a classpath still reports parse errors; the following are
    # expected noise in this mode and are filtered out:
    #   - "cannot find symbol" / "package ... does not exist"  (classpath absent)
    #   - "cannot access ..."                                  (classpath absent)
    #   - "method does not override ..."                       (@Override needs supertype)
    # Anything left is a genuine parse/structure error.
    noise = ("cannot find symbol", "does not exist", "package ",
             "cannot access", "does not override", "cannot inherit")
    real = [ln for ln in out.splitlines()
            if "error:" in ln and not any(n in ln for n in noise)]
    if real:
        return 1, "\n".join(real)
    return 0, ""


def missing_source_roots(targets):
    """
    Return source roots for packages whose .class files are absent from the compiled
    output, so javac can still resolve them.

    Background: the class output can be incomplete — an interrupted or incremental
    Gradle build leaves packages with sources but no compiled classes (observed:
    org/telegram/messenger/browser/ had Browser.java but zero .class files). Those
    packages must come from source, while everything else must keep coming from
    .class files to avoid javac re-reading Telegram's tree and surfacing API-level
    and flavour mismatches in files we never touched.

    We also add the root that contains each target, so a target's own siblings in
    the same package resolve against the version being edited, not a stale .class.
    """
    roots = []
    tgt_java_root = os.path.join(SRC, "TMessagesProj", "src", "main", "java")
    compiled = os.path.join(SRC, "TMessagesProj", "build", "intermediates", "javac",
                            "standalone", "compileStandaloneJavaWithJavac", "classes")
    if not os.path.isdir(compiled):
        return [tgt_java_root]

    # Collect declared packages that have no compiled output at all.
    missing_pkgs = set()
    for root, _dirs, files in os.walk(tgt_java_root):
        pkg = os.path.relpath(root, tgt_java_root).replace(os.sep, ".")
        for n in files:
            if not n.endswith(".java"):
                continue
            cls = n[:-5]
            # Inner classes land as Name$X.class, so probe by prefix.
            hit = os.path.exists(os.path.join(compiled, pkg.replace(".", os.sep), cls + ".class"))
            if not hit:
                missing_pkgs.add(pkg)
                break

    if missing_pkgs:
        sample = sorted(missing_pkgs)[:5]
        print(f"[i] {len(missing_pkgs)} package(s) missing compiled output "
              f"(e.g. {', '.join(sample)}) - resolving those from source")

    return [tgt_java_root] if missing_pkgs else []


def media3_source_roots():
    """
    media3 is a git submodule at TMessagesProj_Modules/media.

    Its libraries ARE already compiled on disk (build/intermediates/javac/...), so we
    do NOT add them to the sourcepath. Adding them makes javac eagerly recompile huge
    swathes of media3, including AIDL-generated types that have no .java source at
    all (IMediaSession, IMediaController), producing a cascade of phantom errors.
    """
    return []


def find_media3_classes():
    """Compiled media3 library class dirs — used instead of its sourcepath."""
    base = os.path.join(SRC, "TMessagesProj_Modules", "media", "libraries")
    out = []
    if not os.path.isdir(base):
        return out
    for lib in sorted(os.listdir(base)):
        d = os.path.join(base, lib, "build", "intermediates", "javac",
                         "release", "compileReleaseJavaWithJavac", "classes")
        if os.path.isdir(d):
            out.append(d)
    return out

# Build-output dir for our scratch compilation (never pollutes real artifacts)
OUT_DIR = os.path.join(SRC, "build", "colgram-fastcheck")
# Staging tree where targets are mirrored into their real package layout.
STAGE_DIR = os.path.join(OUT_DIR, "staged")
# Android library archives (.aar) are zip files holding classes.jar. javac cannot
# read .aar directly, so we explode each one once into this cache and put the
# extracted jar on the classpath instead. Built once, reused forever.
AAR_CACHE = os.path.join(OUT_DIR, "aar")
AAR_INDEX = os.path.join(AAR_CACHE, ".done")


def explode_aars(entries):
    """
    Replace every .aar on the classpath with the classes.jar inside it.

    javac silently skips .aar (it is a zip, not a classpath archive), which makes
    every androidx/Google type look undefined — producing a flood of bogus
    "package X does not exist" errors. Extraction is cached so it happens once.
    """
    aars = [e for e in entries if e.lower().endswith(".aar")]
    if not aars:
        return entries
    os.makedirs(AAR_CACHE, exist_ok=True)
    done = set()
    if os.path.exists(AAR_INDEX):
        try:
            with open(AAR_INDEX, "r", encoding="utf-8") as f:
                done = {ln.strip() for ln in f if ln.strip()}
        except Exception:
            done = set()

    out = []
    new_index = []
    for e in entries:
        if not e.lower().endswith(".aar"):
            out.append(e)
            continue
        name = os.path.basename(e)[:-4]
        target = os.path.join(AAR_CACHE, name + "-classes.jar")
        if e in done and os.path.exists(target):
            out.append(target)
            new_index.append(e)
            continue
        try:
            with zipfile.ZipFile(e) as z:
                inner = [n for n in z.namelist() if n.endswith("classes.jar")]
                if not inner:
                    continue
                # Extract the first classes.jar; nested libs inside are rare and
                # only matter for a handful of packages, all of which also ship a
                # top-level classes.jar.
                with z.open(inner[0]) as src, open(target, "wb") as dst:
                    shutil.copyfileobj(src, dst)
            out.append(target)
            new_index.append(e)
        except Exception:
            # A single broken aar must not kill the whole check.
            continue

    try:
        with open(AAR_INDEX, "w", encoding="utf-8") as f:
            f.write("\n".join(new_index))
    except Exception:
        pass
    return out


def build_classpath():
    parts = []
    if os.path.exists(CP_FILE):
        with open(CP_FILE, "r", encoding="utf-8") as f:
            parts.append(f.read().strip())
    for d in CLASSES_DIRS:
        if os.path.isdir(d):
            parts.append(d)
    for d in find_media3_classes():
        parts.append(d)
    if os.path.exists(R_JAR):
        parts.append(R_JAR)
    # android.jar provides the framework classes (View, LinearLayout, TypedValue ...).
    # Without it every Android type looks like an unresolved symbol.
    if os.path.exists(ANDROID_JAR):
        parts.append(ANDROID_JAR)
    # Flatten the joined string back into individual entries so .aar archives can
    # be replaced by their inner classes.jar before javac sees the classpath.
    entries = []
    for p in parts:
        entries.extend(p.split(os.pathsep))
    entries = [e for e in entries if e]
    entries = explode_aars(entries)
    return os.pathsep.join(entries)


def git_changed_files():
    """Java/Kotlin files changed vs HEAD, plus untracked ones."""
    try:
        out = subprocess.run(
            ["git", "-C", SRC, "status", "--porcelain"],
            capture_output=True, text=True, timeout=30).stdout
    except Exception:
        return []
    files = []
    for line in out.splitlines():
        path = line[3:].strip().strip('"')
        if path.endswith(".java"):
            files.append(os.path.join(SRC, path))
    return [f for f in files if os.path.exists(f)]


def template_files():
    tdir = os.path.join(REPO, "scripts", "templates")
    if not os.path.isdir(tdir):
        return []
    return [os.path.join(tdir, n) for n in os.listdir(tdir) if n.endswith(".java")]


def core_files():
    cdir = os.path.join(REPO, "colgram-core", "src", "main", "java")
    result = []
    for root, _d, files in os.walk(cdir):
        for n in files:
            if n.endswith(".java"):
                result.append(os.path.join(root, n))
    return result


def resolve_check_target(src_file):
    """
    javac resolves same-package and imported types relative to the *sourcepath*.

    Template files live in scripts/templates/ but declare `package org.telegram.ui;`.
    Checking them in place makes javac fail to find sibling Telegram classes, producing
    bogus "package X does not exist" errors.

    Fix: mirror every target into a staging tree laid out by its declared package, and
    check the mirrored copy instead. Original file is never modified.

    The staged filename follows the declared TOP-LEVEL PUBLIC class name, not the
    source filename, because javac rejects a public class that does not match its file.
    This keeps ad-hoc copies (foo_bak.java) checkable.
    """
    try:
        with open(src_file, "r", encoding="utf-8", errors="ignore") as f:
            head = f.read(65536)
    except Exception:
        return None
    m = re.search(r"^\s*package\s+([\w.]+)\s*;", head, re.MULTILINE)
    if not m:
        return None
    pkg_path = m.group(1).replace(".", os.sep)

    # Find the public top-level type so the staged file name is legal.
    staged_name = os.path.basename(src_file)
    pm = re.search(r"^\s*public\s+(?:final\s+|abstract\s+)?"
                   r"(?:class|interface|enum|@interface)\s+(\w+)",
                   head, re.MULTILINE)
    if pm:
        staged_name = pm.group(1) + ".java"

    staged = os.path.join(STAGE_DIR, pkg_path, staged_name)
    os.makedirs(os.path.dirname(staged), exist_ok=True)
    with open(src_file, "r", encoding="utf-8", errors="ignore") as fin, \
         open(staged, "w", encoding="utf-8") as fout:
        fout.write(fin.read())
    return staged


def main():
    args = [a for a in sys.argv[1:]]
    do_core = "--core" in args
    do_all_templates = "--all-templates" in args
    do_syntax = "--syntax" in args
    args = [a for a in args if not a.startswith("--")]

    if args:
        targets = [os.path.abspath(a) for a in args]
    elif do_all_templates:
        targets = template_files()
    elif do_core:
        # --core means "the colgram-core module", nothing else. Mixing in git-changed
        # Telegram files would silently widen the check and drag in classpath work.
        targets = core_files()
    else:
        targets = git_changed_files()

    if do_core and do_all_templates:
        targets = template_files() + core_files()

    if not targets:
        print("[=] Nothing to check (no changed .java files).")
        return 0

    # Fresh staging tree each run so a renamed/removed target cannot leave a stale
    # copy behind that javac then compiles as if it were still part of the project.
    if os.path.isdir(STAGE_DIR):
        shutil.rmtree(STAGE_DIR, ignore_errors=True)
    os.makedirs(STAGE_DIR, exist_ok=True)

    # Mirror each target into a tree laid out by its declared package so javac can
    # resolve same-package sibling types (scripts/templates/*.java live outside any
    # source root, so checking them in place yields phantom "package does not exist").
    staged_targets = []
    for t in targets:
        st = resolve_check_target(t)
        staged_targets.append(st if st else t)
    targets = staged_targets

    if do_syntax:
        print(f"[*] Syntax-checking {len(targets)} file(s) (fast mode)")
        rc, msg = syntax_only_check(targets, OUT_DIR)
        if rc == 0:
            print("[+] SYNTAX OK.")
            return 0
        print("[-] SYNTAX ERRORS:")
        print("   ", msg)
        return 1

    cp = build_classpath()
    if not cp:
        print("[!] Classpath empty. Run the one-time dump:")
        print("    java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain \\")
        print("      -I ../scripts/dump-classpath.gradle :TMessagesProj:dumpStandaloneClasspath")
        return 2

    os.makedirs(OUT_DIR, exist_ok=True)

    print(f"[*] Fast-checking {len(targets)} file(s) against {len(cp.split(os.pathsep))} classpath entries")
    for t in targets:
        print(f"      - {os.path.relpath(t, REPO)}")

    cmd = [
        JAVAC,
        "-nowarn",
        "-proc:none",
        "-encoding", "UTF-8",
        "-source", "17",
        "-target", "17",
        "-d", OUT_DIR,
        "-cp", cp,
    ] + targets

    # Long command lines can overflow on Windows; write an @argfile instead.
    # javac argfiles treat backslash as an escape character, so Windows paths must
    # be normalised to forward slashes inside the file.
    def fwd(p):
        return p.replace("\\", "/")

    argfile = os.path.join(OUT_DIR, "args.txt")
    with open(argfile, "w", encoding="utf-8") as f:
        f.write("-nowarn\n-proc:none\n-encoding\nUTF-8\n-source\n17\n-target\n17\n")
        f.write(f"-d\n{fwd(OUT_DIR)}\n")
        f.write(f"-cp\n{fwd(cp)}\n")
        # Sourcepath lets javac resolve types on demand. It must contain ONLY the
        # generated BuildConfig root — NOT Telegram's own source tree.
        #
        # Reason: javac prefers sourcepath over classpath. If Telegram's sources are
        # on the sourcepath, javac re-reads them from .java and surfaces flavour/
        # API-level mismatches in files we never touched (HwFrameLayout is Huawei-only,
        # VANILLA_ICE_CREAM needs android-35, jlatexmath is a separate module). All of
        # those are already compiled and correct on the classpath, so the source tree
        # must stay off the sourcepath entirely.
        existing_roots = []
        for sp in SOURCE_ROOTS:
            if not os.path.isdir(sp):
                continue
            # colgram-core is compiled separately and is on the classpath already.
            # The Telegram java root is deliberately excluded (see above).
            if os.path.normpath(sp).endswith(os.path.join("src", "main", "java")):
                if "colgram-core" in sp:
                    continue
                if os.path.normpath(sp) == os.path.normpath(
                        os.path.join(SRC, "TMessagesProj", "src", "main", "java")):
                    continue
            existing_roots.append(fwd(sp))
        # Only expose Telegram's source tree if the compiled output is incomplete.
        existing_roots += [fwd(r) for r in missing_source_roots(targets)]
        if existing_roots:
            f.write(f"-sourcepath\n{';'.join(existing_roots)}\n")
        for t in targets:
            f.write(f'"{fwd(t)}"\n')

    cmd = [JAVAC, f"@{argfile}"]

    try:
        res = subprocess.run(cmd, capture_output=True, timeout=300)
    except subprocess.TimeoutExpired:
        print("[!] javac timed out after 300s")
        return 2

    # javac on a Russian Windows locale emits cp1251/cp866 diagnostics, so decode
    # defensively instead of assuming UTF-8.
    def dec(b):
        if not b:
            return ""
        for enc in ("utf-8", "cp1251", "cp866", "latin-1"):
            try:
                return b.decode(enc)
            except UnicodeDecodeError:
                continue
        return b.decode("utf-8", errors="replace")

    output = dec(res.stdout) + dec(res.stderr)
    if res.returncode == 0:
        errs = output.count("error:")
        if errs == 0:
            print("[+] FAST CHECK PASSED — no compile errors.")
            return 0
    print("[-] COMPILE ERRORS:")
    # Print only the meaningful lines
    for line in output.splitlines():
        if re.search(r"error:|warning:|\^|symbol:|location:", line):
            print("   ", line)
    if not output.strip():
        print("    (javac produced no diagnostic output; returncode=", res.returncode, ")")
    return 1


if __name__ == "__main__":
    sys.exit(main())
