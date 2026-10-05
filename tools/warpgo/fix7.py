import re
p = r'C:\Colgram\tools\warpgo\native\main.go'
src = open(p, 'r', encoding='utf-8').read()

out = []
i = 0
n = len(src)
prev_sig = ''

def nl():
    # collapse a run of blank lines to exactly one
    while len(out) >= 2 and out[-1] == chr(10) and out[-2] == chr(10):
        out.pop()

while i < n:
    ch = src[i]

    # raw string literal: copy verbatim
    if ch == '`':
        j = src.find('`', i + 1)
        j = n if j == -1 else j + 1
        out.append(src[i:j]); i = j; prev_sig = '`'; continue

    # interpreted string / rune literal: a // or a tab inside is data
    if ch in ('"', "'"):
        q = ch; j = i + 1
        while j < n:
            if src[j] == chr(92):
                j += 2; continue
            if src[j] == q:
                j += 1; break
            j += 1
        out.append(src[i:j]); i = j; prev_sig = q; continue

    # block comment
    if ch == '/' and i + 1 < n and src[i+1] == '*':
        j = src.find('*/', i)
        j = n if j == -1 else j + 2
        out.append(src[i:j]); i = j; prev_sig = ')'; continue

    # line comment: the original had one comment per line, so the body runs to the next // that is
    # followed by a tab (indentation) or a space (an inline trailing comment)
    if ch == '/' and i + 1 < n and src[i+1] == '/':
        j = i + 2
        while j < n:
            if src[j] == '/':
                nxt = src[j+1] if j + 1 < n else ''
                prv = src[j-1] if j > 0 else ''
                if nxt in (chr(9), ' ') and prv in (' ', '.', ',', ';', ')', '}', '/', ':'):
                    break
            j += 1
        body = src[i:j].rstrip()
        if body:
            out.append(body)
        out.append(chr(10))
        i = j
        prev_sig = ''
        continue

    # a tab in the middle of a run is the indentation of the next line
    if ch == chr(9) and out and out[-1] not in (chr(10), chr(9)):
        nl()
        out.append(chr(10))
        out.append(chr(9))
        i += 1
        continue

    if ch in ('}', ')', ';'):
        out.append(ch)
        out.append(chr(10))
        prev_sig = ch
        i += 1
        continue

    out.append(ch)
    if not ch.isspace():
        prev_sig = ch
    i += 1

res = ''.join(out)
res = re.sub(chr(10) + r'[ \t]*' + chr(10) + r'[ \t]*' + chr(10) + '+', chr(10)*2, res)
open(p, 'w', encoding='utf-8', newline='').write(res)
print('lines:', res.count(chr(10)))