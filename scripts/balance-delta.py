"""Brace/paren DELTA of a Java file vs its git HEAD baseline.

Absolute counts are meaningless in this codebase - upstream files carry char literals
such as `new byte[]{'W','P','S','\n'}` (Theme.java:6817) which break naive strippers, and
several files sit at a permanent imbalance in HEAD itself. Only the DELTA is a signal.

IMPORTANT: a delta of 0/0 means "no structural change". A NON-ZERO delta is not
automatically a bug: if you deliberately added or removed a whole block (try/catch,
if, method), the delta legitimately moves. Read the delta, do not just test it.

Two git repos are in play:
  C:\\Colgram\\Telegram-Src   -> tracks   TMessagesProj/**
  C:\\Colgram                 -> tracks   colgram-core/**, scripts/**, patches/**

Usage:  python balance-delta.py [relative/path/To.java ...]
Defaults to the files Colgram patches most often.
"""
import re
import subprocess
import sys

SRC_REPO = r"C:\Colgram\Telegram-Src"   # tracks TMessagesProj/**
ROOT_REPO = r"C:\Colgram"               # tracks colgram-core/**

DEFAULTS = [
    "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java",
    "TMessagesProj/src/main/java/org/telegram/ui/IntroActivity.java",
    "colgram-core/src/main/java/org/colgram/core/ColgramBotSync.java",
    "colgram-core/src/main/java/org/colgram/core/ColgramConfig.java",
    "colgram-core/src/main/java/org/colgram/core/ColgramDpiBypass.java",
]

STR = re.compile(r'"(?:\\.|[^"\\])*"')
CH = re.compile(r"'(?:\\.|[^'\\])*'")


def counts(src):
    s = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    s = re.sub(r"//[^\n]*", "", s)
    s = STR.sub('""', s)
    s = CH.sub("''", s)
    return s.count("{"), s.count("}"), s.count("("), s.count(")")


def ownership(rel):
    """Return (repo_root, path_inside_repo) for a repo-relative path."""
    if rel.startswith("TMessagesProj"):
        return SRC_REPO, rel
    return ROOT_REPO, rel


def baseline(rel):
    repo, inner = ownership(rel)
    r = subprocess.run(["git", "-C", repo, "show", "HEAD:" + inner],
                       capture_output=True)
    if r.returncode != 0:
        return ""
    return r.stdout.decode("utf-8", "ignore")


def main():
    rels = sys.argv[1:] or DEFAULTS
    bad = 0
    for rel in rels:
        repo, inner = ownership(rel)
        cur_path = repo + "\\" + inner.replace("/", "\\")
        try:
            cur = open(cur_path, encoding="utf-8", errors="ignore").read()
        except OSError:
            print(f"{rel}: NOT FOUND at {cur_path}")
            bad += 1
            continue
        base = baseline(rel)
        name = rel.split("/")[-1]
        if not base:
            print(f"{name:24s} no git baseline (new file?)  absolute {counts(cur)}")
            continue
        b, h = counts(base), counts(cur)
        db = (h[0] - b[0], h[1] - b[1])
        dp = (h[2] - b[2], h[3] - b[3])
        balanced = (h[0] - h[1]) == (b[0] - b[1]) and (h[2] - h[3]) == (b[2] - b[3])
        flag = "OK" if balanced else "UNBALANCED"
        if not balanced:
            bad += 1
        print(f"{name:24s} delta braces {db}  delta parens {dp}  {flag}")
        # Show the net shape so a non-zero-but-matched delta reads as intentional.
        print(f"{'':24s} net brace {h[0]-h[1]:+d} (HEAD {b[0]-b[1]:+d})   "
              f"net paren {h[2]-h[3]:+d} (HEAD {b[2]-b[3]:+d})")
    print()
    print("ALL BALANCED" if bad == 0 else f"{bad} FILE(S) UNBALANCED")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
