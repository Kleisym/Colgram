"""Pre-delete the deletion-heavy Gradle intermediates.

Gradle removes old outputs at ~5 files/sec under Defender, and `compileReleaseJavaWithJavac`
stashes ~13.7k .class files - roughly 45 minutes, and it has actually FAILED with
`Unable to delete directory ... stash-dir`. Python's shutil.rmtree does the same work in
~17 seconds. Run this before every build after a source change.
"""
import os
import shutil
import sys
import time

SRC = r"C:\Colgram\Telegram-Src"
TARGETS = [
    r"colgram-core\build",
    r"TMessagesProj\build\intermediates\javac\release\compileReleaseJavaWithJavac",
    r"TMessagesProj\build\tmp\compileReleaseJavaWithJavac",
    r"TMessagesProj\build\.transforms",
    r"TMessagesProj_AppStandalone\build\intermediates\merged_jni_libs",
    r"TMessagesProj_AppStandalone\build\intermediates\merged_native_libs",
]

total = 0.0
for rel in TARGETS:
    path = os.path.join(SRC, rel)
    if not os.path.exists(path):
        print("  -- absent   %s" % rel)
        continue
    t0 = time.time()
    try:
        shutil.rmtree(path)
    except Exception as e:
        print("  !! FAILED   %s -> %s" % (rel, e))
        continue
    dt = time.time() - t0
    total += dt
    print("  ok deleted  %s  (%.1fs)" % (rel, dt))

print("total %.1fs" % total)
