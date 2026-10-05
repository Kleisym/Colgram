import re
p = r'C:\Colgram\tools\warpgo\native\main.go'
src = open(p, 'r', encoding='utf-8').read()

# The file is one line again. Rather than guess, the separators that are unambiguous are the ones that
# cannot occur inside a Go token: a tab, and a newline that already exists. Go's own formatting makes a
# tab the indentation of every line inside a function, a composite literal and a comment block, so the
# tab is the signal. The // handling below only fires when the // is followed by a tab or by a space and
# the character before it is not a slash, which is what a real comment looks like after the newlines
# are gone and what '*//*' in the cgo preamble is not.
out = []
i = 0
n = len(src)
while i < n:
    ch = src[i]
    if ch == '`':
        j = src.find('`', i + 1); j = n if j == -1 else j + 1
        out.append(src[i:j]); i = j; continue
    if ch in ('"', "'"):
        q = ch; j = i + 1
        while j < n:
            if src[j] == chr(92):
                j += 2; continue
            if src[j] == q:
                j += 1; break
            j += 1
        out.append(src[i:j]); i = j; continue
    if ch == '/' and i + 1 < n and src[i+1] == '*':
        j = src.find('*/', i); j = n if j == -1 else j + 2
        block = src[i:j]
        out.append(chr(10) + block.replace('*//*', chr(10) + '*') + chr(10))
        i = j; continue
    if ch == '/' and i + 1 < n and src[i+1] == '/':
        prv = src[i-1] if i > 0 else ''
        nxt = src[i+2] if i + 2 < n else ''
        if prv != '/' and nxt in (chr(9), ' '):
            j = src.find('//', i + 2)
            k = i + 2
            while j == -1 or not (src[j-1] in ' .,;:)/}' or src[j+1:j+2] == ' ' or src[j+1:j+2] == chr(9)):
                j = src.find('//', k)
                if j == -1:
                    break
                k = j + 2
            j = n if j == -1 else j
            out.append(src[i:j].rstrip() + chr(10))
            i = j; continue
    if ch == chr(9):
        while out and out[-1] == chr(10):
            out.pop()
        if out:
            out.append(chr(10))
        out.append(chr(9)); i += 1; continue
    if ch in ('}', ')', ';'):
        out.append(ch); out.append(chr(10)); i += 1; continue
    out.append(ch); i += 1

res = ''.join(out)
res = re.sub(chr(10) + r'[ \t]*' + chr(10) + r'[ \t]*' + chr(10) + '+', chr(10)*2, res)
open(p, 'w', encoding='utf-8', newline='').write(res)
print('lines:', res.count(chr(10)))