import re
p = r'C:\Colgram\tools\warpgo\native\main.go'
src = open(p, 'r', encoding='utf-8').read()

# The file is one line: every newline was lost. Newlines are required after '}' and after a //
# comment, and before a // comment that follows code. Strings and rune literals are copied verbatim so
# that a URL containing // is never broken.
tokens = []
i = 0
n = len(src)
while i < n:
    ch = src[i]
    if ch == '`':
        j = src.find('`', i + 1)
        j = n - 1 if j == -1 else j
        tokens.append(('str', src[i:j+1]))
        i = j + 1
        continue
    if ch in ('"', "'"):
        q = ch
        j = i + 1
        while j < n:
            if src[j] == chr(92):
                j += 2
                continue
            if src[j] == q:
                j += 1
                break
            j += 1
        tokens.append(('str', src[i:j]))
        i = j
        continue
    if ch == '/' and i + 1 < n and src[i+1] == '/':
        j = src.find(chr(10), i)
        k = src.find('\n', i)
        if k == -1:
            k = src.find('//', i + 2)
            if k == -1:
                k = n
        tokens.append(('comment', src[i:k]))
        i = k
        continue
    tokens.append(('chr', ch))
    i += 1

out = []
prev_kind = None
prev_chr = ''
for kind, text in tokens:
    if kind == 'comment':
        if prev_chr in ('}', ')'):
            out.append(chr(10))
        out.append(text)
        out.append(chr(10))
        prev_kind = 'comment'
        prev_chr = ''
        continue
    if kind == 'str':
        out.append(text)
        prev_kind = 'str'
        prev_chr = text[-1]
        continue
    if text in ('}', ')'):
        out.append(text)
        out.append(chr(10))
        prev_kind = 'chr'
        prev_chr = text
        continue
    out.append(text)
    if not text.isspace():
        prev_kind = 'chr'
        prev_chr = text

res = ''.join(out)
res = re.sub(chr(10) + r'\s*' + chr(10) + r'\s*' + chr(10) + '+', chr(10) + chr(10), res)
open(p, 'w', encoding='utf-8', newline='').write(res)
print('lines:', res.count(chr(10)))