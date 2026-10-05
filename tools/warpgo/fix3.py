p = r'C:\Colgram\tools\warpgo\native\main.go'
src = open(p, 'r', encoding='utf-8').read()
out = []
i = 0
n = len(src)
prev = ''
while i < n:
    ch = src[i]
    if ch == '`':
        out.append(ch); i += 1
        while i < n:
            out.append(src[i])
            if src[i] == '`':
                i += 1
                break
            i += 1
        prev = '`'
        continue
    if ch == '"' or ch == "'":
        q = ch
        out.append(ch); i += 1
        while i < n:
            c = src[i]
            out.append(c); i += 1
            if c == chr(92) and i < n:
                out.append(src[i]); i += 1
                continue
            if c == q:
                break
        prev = q
        continue
    if ch == '/' and i + 1 < n and src[i+1] == '/':
        j = src.find('\n', i)
        out.append(chr(10))
        prev = ''
        i += 2
        continue
    if ch == '/':
        j = src.find('\n', i)
        if j == -1:
            j = n
        out.append(src[i:j])
        out.append(chr(10))
        prev = ''
        i = j
        continue
    if ch == '}' or ch == ')':
        out.append(ch)
        out.append(chr(10))
        prev = ch
        i += 1
        continue
    if ch == '{':
        out.append(ch)
        prev = ch
        i += 1
        continue
    out.append(ch)
    if not ch.isspace():
        prev = ch
    i += 1
res = ''.join(out)
while chr(10) * 3 in res:
    res = res.replace(chr(10) * 3, chr(10) * 2)
open(p, 'w', encoding='utf-8', newline='').write(res)
print('lines:', res.count(chr(10)))