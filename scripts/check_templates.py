import os
import re

def check_java_file(path):
    print(f"Checking {path}...")
    with open(path, "r", encoding="utf-8") as f:
        lines = f.readlines()

    issues = []
    in_block_comment = False

    for line_no, line in enumerate(lines, 1):
        stripped = line.strip()
        if stripped.startswith("/*"):
            in_block_comment = True
        if in_block_comment:
            if "*/" in stripped:
                in_block_comment = False
            continue
        if stripped.startswith("//"):
            continue

        # Check for unclosed string on single line
        # Count unescaped double quotes
        in_quote = False
        quote_start = -1
        idx = 0
        while idx < len(line):
            ch = line[idx]
            if ch == '\\':
                idx += 2
                continue
            if ch == '"':
                in_quote = not in_quote
            idx += 1

        if in_quote:
            issues.append((line_no, "Unclosed string literal on line", line.strip()))

        # Check for illegal escapes in strings: e.g. \d, \w, \s (outside of \\d, \\w)
        # In Java string, \d is illegal unless written as \\d
        for m in re.finditer(r'"([^"\\]*(\\.[^"\\]*)*)"', line):
            s = m.group(0)
            for bad in [r'\d', r'\w', r'\s']:
                # if bad is preceded by another \, it's \\d which is valid
                # find instances of bad not preceded by \
                pos = 0
                while True:
                    p = s.find(bad, pos)
                    if p == -1:
                        break
                    # count preceding backslashes
                    slash_count = 0
                    k = p - 1
                    while k >= 0 and s[k] == '\\':
                        slash_count += 1
                        k -= 1
                    if slash_count % 2 == 0:
                        issues.append((line_no, f"Illegal escape {bad}", line.strip()))
                    pos = p + len(bad)

    if issues:
        print(f"[-] Found {len(issues)} issues in {path}:")
        for line_no, desc, code in issues:
            print(f"    Line {line_no}: {desc} -> {code}")
        return False
    else:
        print(f"[+] Clean! No issues in {path}")
        return True

def main():
    tmpl_dir = "scripts/templates"
    all_ok = True
    for f in os.listdir(tmpl_dir):
        if f.endswith(".java"):
            ok = check_java_file(os.path.join(tmpl_dir, f))
            if not ok:
                all_ok = False
    print("All OK:", all_ok)

if __name__ == "__main__":
    main()
