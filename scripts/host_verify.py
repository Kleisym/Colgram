#!/usr/bin/env python3
"""Compile the real Android core and optionally execute isolated JVM policy tests.

No Android mocks and no filtered compiler errors. SDK/JDK paths can be supplied
explicitly or through ANDROID_HOME/JAVA_HOME. Dependencies use Gradle's cache.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


def choose_java(explicit=None):
    homes = [explicit, os.environ.get("JAVA_HOME")]
    if os.name == "nt":
        homes += [r"C:\colgram-tools\jdk-17.0.20.1+1"]
    for home in filter(None, homes):
        tool = Path(home) / "bin" / ("javac.exe" if os.name == "nt" else "javac")
        if tool.is_file():
            return tool
    tool = shutil.which("javac")
    if tool:
        return Path(tool)
    raise RuntimeError("JDK missing: set JAVA_HOME to JDK 17 or use --java-home")


def choose_sdk(explicit=None):
    homes = [explicit, os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
             str(Path.home() / "AppData/Local/Android/Sdk"), str(Path.home() / "Android/Sdk")]
    if os.name == "nt":
        homes += [r"C:\android-sdk"]
    for home in filter(None, homes):
        jar = Path(home) / "platforms/android-34/android.jar"
        if jar.is_file():
            return jar
    raise RuntimeError("Android SDK platform 34 missing: install platforms;android-34")


def cache_classpath(cache, dest):
    jars = []
    # Only direct core dependencies and their annotation dependencies are needed.
    groups = ("androidx.core", "androidx.appcompat", "com.google.android.material",
              "androidx.annotation", "androidx.lifecycle", "androidx.collection",
              "org.jetbrains", "org.jetbrains.kotlin", "androidx.savedstate")
    for group in groups:
        for archive in sorted((cache / group).rglob("*")):
            if archive.suffix == ".jar":
                jars.append(archive)
            elif archive.suffix == ".aar":
                with zipfile.ZipFile(archive) as z:
                    if "classes.jar" in z.namelist():
                        target = dest / (str(len(jars)) + ".jar")
                        target.write_bytes(z.read("classes.jar"))
                        jars.append(target)
    return jars


def run(cmd, cwd):
    result = subprocess.run([str(x) for x in cmd], cwd=cwd, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    if result.stdout.strip():
        print(result.stdout.rstrip())
    return result.returncode


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    ap.add_argument("--java-home")
    ap.add_argument("--android-sdk")
    ap.add_argument("--gradle-cache", type=Path,
                    default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1")
    ap.add_argument("--jvm-tests", action="store_true")
    args = ap.parse_args()
    root = args.repo.resolve()
    javac = choose_java(args.java_home)
    sdk = choose_sdk(args.android_sdk)
    sources = sorted((root / "colgram-core/src/main/java").rglob("*.java"))
    if not sources:
        raise RuntimeError("No core Java sources at " + str(root))
    with tempfile.TemporaryDirectory(prefix="colgram-verify-") as work:
        scratch = Path(work)
        classes = scratch / "classes"
        classes.mkdir()
        jars = cache_classpath(args.gradle_cache, scratch)
        argfile = scratch / "javac.args"
        def quote(value):
            return '"' + str(value).replace("\\", "/").replace('"', '\\"') + '"'
        argfile.write_text("\n".join(["-encoding", "UTF-8", "-proc:none", "--release", "17",
            "-d", quote(classes), "-classpath", quote(os.pathsep.join(map(str, [sdk, *jars]))),
            *map(quote, sources)]), encoding="utf-8")
        print(f"CORE: {len(sources)} sources; Android API 34; {len(jars)} dependency jars", flush=True)
        code = run([javac, "@" + str(argfile)], root)
        if code:
            print(f"CORE COMPILE: FAIL (exit {code})")
            return code
        print("CORE COMPILE: PASS")
        if args.jvm_tests:
            tests = sorted((root / "tests/java").rglob("*.java"))
            if not tests:
                raise RuntimeError("--jvm-tests requested, but tests/java is empty")
            test_cp = os.pathsep.join([str(classes), str(sdk)])
            code = run([javac, "-encoding", "UTF-8", "-cp", test_cp, "-d", classes, *tests], root)
            if code:
                return code
            java = javac.with_name("java.exe" if os.name == "nt" else "java")
            mains = 0
            for test in tests:
                text = test.read_text(encoding="utf-8")
                if not re.search(r"public\s+static\s+void\s+main\s*\(", text):
                    continue
                package = re.search(r"^package\s+([\w.]+);", text, re.M)
                classname = (package[1] + "." if package else "") + test.stem
                code = run([java, "-ea", "-cp", test_cp, classname], root)
                if code:
                    return code
                mains += 1
            if not mains:
                raise RuntimeError("No executable JVM test main found")
            print(f"JVM TEST CLASSES: {mains} PASS")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, OSError, zipfile.BadZipFile) as exc:
        print("ERROR: " + str(exc), file=sys.stderr)
        sys.exit(2)
