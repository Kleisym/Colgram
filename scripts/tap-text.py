"""Tap a visible row by its text, reading live uiautomator bounds.

Coordinates are read from the hierarchy every time, so a rotation or a scroll cannot make the
tap land on the wrong row the way fixed pixel offsets do.
"""
import codecs
import io
import re
import subprocess
import sys
import time
import html

ADB = r"C:/android-sdk/platform-tools/adb.exe"
SERIAL = "emulator-5554"
DUMP = "/sdcard/w.xml"


def sh(*args):
    # text=True would decode adb's UTF-8 output in the Windows locale codepage and turn every
    # Cyrillic label into mojibake, so no needle ever matches.
    return subprocess.run([ADB, "-s", SERIAL, *args],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")


def dump_rows():
    sh("shell", "rm", "-f", DUMP)
    sh("exec-out", "uiautomator", "dump", "//sdcard/w.xml")
    out = sh("shell", "cat", DUMP).stdout
    rows = []
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', out):
        x1, y1, x2, y2 = (int(m.group(i)) for i in (2, 3, 4, 5))
        rows.append((html.unescape(m.group(1)), (x1 + x2) // 2, (y1 + y2) // 2))
    return rows


def find(needle, rows=None):
    for text, x, y in (rows if rows is not None else dump_rows()):
        if needle in text:
            return text, x, y
    return None


def tap(x, y):
    sh("shell", "input", "tap", str(x), str(y))


def scroll_right_pane(times=3):
    rows = dump_rows()
    xs = [x for _, x, _ in rows if x > 400]
    x = max(xs) if xs else 450
    for _ in range(times):
        sh("shell", "input", "swipe", str(x), "1200", str(x), "450", "400")
        time.sleep(1)


def main():
    # Git Bash on Windows hands over argv in the console codepage, which mangles Cyrillic.
    # Pass escapes instead: "\u0417\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u044c".
    import codecs
    needle = codecs.decode(sys.argv[1], "unicode_escape")
    path = [codecs.decode(s, "unicode_escape") for s in sys.argv[2:]]
    for step in path:
        hit = find(step)
        if not hit:
            scroll_right_pane(1)
            hit = find(step)
        if not hit:
            print(f"MISSING: {step}")
            return 1
        print(f"TAP {hit[0][:60]!r} at {hit[1]},{hit[2]}")
        tap(hit[1], hit[2])
        time.sleep(2.5)
    for attempt in range(6):
        hit = find(needle)
        if hit:
            print(f"TAP {hit[0][:60]!r} at {hit[1]},{hit[2]}")
            tap(hit[1], hit[2])
            time.sleep(28)
            lines = [text for text, _, _ in dump_rows()
                     if text and ("Bot API" in text or "getMe" in text or text == "Ок")]
            io.open(r"C:/Colgram/ci-artifact/verify/bot-result.txt", "w",
                    encoding="utf-8").write("\n".join(lines))
            print("RESULT written:", len(lines), "lines")
            return 0
        scroll_right_pane(2)
        print(f"pass {attempt}: {needle} not visible")
    print(f"MISSING: {needle}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
