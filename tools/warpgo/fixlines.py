import sys, re

src = open(r'C:\Colgram\tools\warpgo\native\main.go', 'r', encoding='utf-8').read()
print('input chars:', len(src))

# A newline was lost at every line break, so the whole file is one line. Go needs newlines after
# '}' and ')' and before a comment that follows code. Everything else gofmt will re-indent.
# The scan has to know about string and rune literals, or a URL like "https://..." gets a newline
# inserted inside the string and the file stops parsing.
out = []
i = 0
n = len(src)
prev_sig = ''   # last significant char emitted
in_line_comment = False
while i < n:
    ch = src[i]
    if in_line_comment:
        out.append(ch)
        if ch == '\n':
            in_line_comment = False
        i += 1
        continue
    if ch == '`':
        # raw string literal: copy verbatim to the closing backtick
        out.append(ch); i += 1
        while i < n:
            out.append(src[i])
            if src[i] == '`':
                i += 1
                break
            i += 1
        prev_sig = '`'
        continue
    if ch == '"' or ch == "'":
        q = ch
        out.append(ch); i += 1
        while i < n:
            c = src[i]
            out.append(c); i += 1
            if c == '\\' and i < n:
                out.append(src[i]); i += 1
                continue
            if c == q:
                break
        prev_sig = q
        continue
    if ch == '/' and i + 1 < n and src[i+1] == '/':
        if prev_sig in ('}', ')', ';'):
            out.append('\n')
        in_line_comment = True
        out.append(ch); i += 1
        continue
    if ch == '/' and i + 1 < n and src[i+1] == '*':
        if prev_sig in ('}', ')', ';'):
            out.append('\n')
        out.append('/*'); i += 2
        while i < n and not (src[i] == '*' and i + 1 < n and src[i+1] == '/'):
            out.append(src[i]); i += 1
        out.append('*/'); i += 2
        prev_sig = ')'
        continue
    if ch == '}' or ch == ')':
        out.append(ch)
        out.append('\n')
        prev_sig = ch
        i += 1
        continue
    out.append(ch)
    if not ch.isspace():
        prev_sig = ch
    i += 1

res = ''.join(out)
res = re.sub(r'\n{3,}', '\n\n', res)
open(r'C:\Colgram\tools\warpgo\native\main.go', 'w', encoding='utf-8', newline='\n').write(res)
print('recovered chars:', len(res), 'lines:', res.count('\n'))
