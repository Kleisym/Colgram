p = r'C:\Colgram\tools\warpgo\wgprobe\main.go'
lines = open(p, encoding='utf-8').read().split(chr(10))
out = []
i = 0
Q = chr(34)
while i < len(lines):
    cur = lines[i]
    if cur.count(Q) % 2 == 1 and i + 1 < len(lines):
        out.append(cur + lines[i + 1])
        i += 2
        continue
    out.append(cur)
    i += 1
open(p, 'w', encoding='utf-8').write(chr(10).join(out))
print('joined, lines:', len(out))