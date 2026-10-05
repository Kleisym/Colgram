import io
p = r'C:\Colgram\tools\warpgo\native\main.go'
data = open(p, 'rb').read()
src = data.decode('utf-8')
out = []
i = 0
n = len(src)
prev = ''
line_comment = False
while i < n:
    ch = src[i]
    if line_comment:
        out.append(ch)
        if ch == '/':
            # end of a // comment: the next token starts a new line unless it is punctuation
            pass
        i += 1
        continue
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
        if prev in ('}', ')'):
            out.append(chr(10))
        line_comment = True
        out.append(ch); i += 1
        # copy the comment body up to the next slash that ends it
        while i < n:
            c = src[i]
            out.append(c); i += 1
            if c == '/':
                # a comment body can contain slashes; treat '//' followed by space as the end
                if i < n and src[i] == '/':
                    line_comment = False
                    out.append(src[i]); i += 1
                    if i < n and src[i] == ' ':
                        pass
                    else:
                        break
                else:
                    break
            if c == chr(10):
                line_comment = False
                break
        out.append(chr(10))
        prev = ''
        continue
    if ch == '}' or ch == ')':
        out.append(ch)
        out.append(chr(10))
        prev = ch
        i += 1
        continue
    out.append(ch)
    if not ch.isspace():
        prev = ch
    i += 1
res = ''.join(out)
while chr(10)*3 in res:
    res = res.replace(chr(10)*3, chr(10)*2)
open(p, 'wb').write(res.encode('utf-8'))
print('lines:', res.count(chr(10)), 'chars:', len(res))
