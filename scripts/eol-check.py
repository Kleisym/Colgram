"""Line-ending guard for Colgram edits.

The patcher and the Java templates must stay CRLF-pure: a single bare LF written into
scripts/apply-patches.py makes every later anchor stop matching, and the failure is
silent because patch_file() just returns False. colgram-core Java files are mixed by
history, so each file is normalised back to whichever convention it already used
rather than to one global rule.

    python scripts/eol-check.py              # report drift
    python scripts/eol-check.py --fix        # restore each file's own convention
"""

import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

TARGETS = [
    os.path.join(ROOT, "scripts", "apply-patches.py"),
    os.path.join(ROOT, "scripts", "templates"),
    os.path.join(ROOT, "colgram-core", "src", "main", "java"),
]


def java_files():
    out = []
    for target in TARGETS:
        if target.endswith(".py"):
            out.append(target)
        elif os.path.isdir(target):
            for base, _dirs, names in os.walk(target):
                for name in names:
                    if name.endswith((".java", ".py")):
                        out.append(os.path.join(base, name))
    return sorted(out)


def git_tracked(paths):
    """Only files git knows about, so build output and scratch copies are skipped."""
    rel = [os.path.relpath(p, ROOT).replace("\\", "/") for p in paths]
    res = subprocess.run(
        ["git", "ls-files", "--", *rel],
        cwd=ROOT, capture_output=True, text=True,
    )
    keep = {os.path.normpath(os.path.join(ROOT, l)) for l in res.stdout.splitlines() if l.strip()}
    return [p for p in paths if os.path.normpath(p) in keep]


def analyse(data):
    crlf = data.count(b"\r\n")
    lf = data.count(b"\n") - crlf
    return crlf, lf


def main():
    fix = "--fix" in sys.argv
    bad = []
    for path in git_tracked(java_files()):
        data = open(path, "rb").read()
        crlf, bare_lf = analyse(data)
        if crlf and bare_lf:
            bad.append((path, crlf, bare_lf))
            print("MIXED  %6d CRLF %6d bareLF  %s" % (crlf, bare_lf, os.path.relpath(path, ROOT)))
        elif crlf and fix is False:
            pass  # CRLF-pure: correct for the patcher and templates
        elif bare_lf and fix is False:
            pass  # LF-pure: some colgram-core files are historical LF
    if not bad:
        print("OK  no file mixes CRLF and bare LF")
        return 0
    if not fix:
        return 1
    for path, crlf, bare_lf in bad:
        data = open(path, "rb").read()
        # Majority convention wins: a file that was CRLF before an LF-writing edit
        # goes back to CRLF, and a historical-LF file stays LF.
        uniform = b"\r\n" if crlf >= bare_lf else b"\n"
        normalised = data.replace(b"\r\n", b"\n").replace(b"\n", uniform)
        with open(path, "wb") as f:
            f.write(normalised)
        crlf2, lf2 = analyse(open(path, "rb").read())
        print("FIXED  -> %s  (%d CRLF, %d bareLF)" % (path, crlf2, lf2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
