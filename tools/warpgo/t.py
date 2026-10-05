import re

def restore(src):
    out = []
    i = 0
    n = len(src)
    while i < n:
        ch = src[i]
        # raw string: copy to the closing backtick, never touched again
        if ch == '`':
            j = src.find('`', i + 1)
            j = n if j == -1 else j + 1
            out.append(src[i:j]); i = j; continue
        # quoted literal: a // inside it is data, not a comment
        if ch in ('"', "'"):
            q = ch; j = i + 1
            while j < n:
                if src[j] == chr(92):
                    j += 2; continue
                if src[j] == q:
                    j += 1; break
                j += 1
            out.append(src[i:j]); i = j; continue
        # block comment
        if ch == '/' and i + 1 < n and src[i+1] == '*':
            j = src.find('*/', i)
            j = n if j == -1 else j + 2
            out.append(src[i:j]); out.append(chr(10)); i = j; continue
        # line comment: a single // to the next // OR to the end of the comment block. The original
        # file had one comment per line, so the body runs until the next // which starts the next line.
        if ch == '/' and i + 1 < n and src[i+1] == '/':
            j = src.find('//', i + 2)
            j = n if j == -1 else j
            out.append(src[i:j]); out.append(chr(10)); i = j; continue
        if ch in ('}', ')'):
            out.append(ch); out.append(chr(10)); i += 1; continue
        out.append(ch); i += 1
    res = ''.join(out)
    return re.sub(chr(10) + r'[ \t]*' + chr(10) + r'[ \t]*' + chr(10) + '+', chr(10)*2, res)

sample = 'package main// a comment// anotherfunc x() {if a {b()}}// tailvar y = "http://x//y"'
print(repr(restore(sample)))