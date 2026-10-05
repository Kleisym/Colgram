import re
p = r'C:\Colgram\tools\warpgo\native\main.go'
src = open(p, 'r', encoding='utf-8').read()

# Two earlier passes each inserted a newline where they saw '}' or ')' and also split line comments,
# so what is left is a mixture: some lines already broken, some runs of text still glued. The only
# reliable separators left are the ones that cannot legally appear inside a Go identifier or operator:
# a newline that already exists, and the '//' that begins a comment.
out = []
i = 0
n = len(src)
while i < n:
    ch = src[i]
    if ch == chr(10):
        out.append(chr(10)); i += 1; continue
    if ch == '`':
        j = src.find('`', i + 1)
        j = n if j == -1 else j + 1
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
        j = src.find('*/', i)
        j = n if j == -1 else j + 2
        out.append(src[i:j]); i = j; continue
    if ch == '/' and i + 1 < n and src[i+1] == '/':
        out.append(chr(10)); out.append('//'); i += 2; continue
    if ch in ('}', ')', ';'):
        out.append(ch); out.append(chr(10)); i += 1; continue
    out.append(ch); i += 1

res = ''.join(out)
res = re.sub(chr(10) + r'[ \t]*' + chr(10) + r'[ \t]*' + chr(10) + '+', chr(10)*2, res)
open(p, 'w', encoding='utf-8', newline='').write(res)
print('lines:', res.count(chr(10)), 'chars:', len(res))