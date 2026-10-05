p = r'C:\Colgram\tools\warpgo\wgprobe\main.go'
lines = open(p, encoding='utf-8').read().split(chr(10))
out = []
i = 0
while i < len(lines):
    cur = lines[i]
    # A literal string was cut open by a backtick-n: the line ends without a closing quote and the
    # next line starts with the remainder. Joining them restores exactly what was written.
    if cur.count('"') % 2 == 1 and i + 1 < len(lines):
        nxt = lines[i + 1]
        if nxt.lstrip().startswith('"') or nxt.lstrip().startswith(',') or nxt.lstrip().startswith(')',') :
            out.append(cur + nxt)
            i += 2
            continue
    out.append(cur)
    i += 1
res = chr(10).join(out)
open(p, 'w', encoding='utf-8').write(res)
print('joined, lines:', len(out))