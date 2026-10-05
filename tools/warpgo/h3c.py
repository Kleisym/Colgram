import re
p = r'C:\Colgram\tools\warpgo\h3probe\main.go'
lines = open(p, encoding='utf-8').read().split(chr(10))
out = []
seen_tr = False
for ln in lines:
    if 'chr(92)' in ln:
        ln = ln.replace('" + chr(92) + "n", ', chr(92) + 'n", ')
    if ln.strip().startswith('tr := &http3.Transport'):
        if seen_tr:
            continue
        seen_tr = True
    out.append(ln)
open(p, 'w', encoding='utf-8').write(chr(10).join(out))
print('cleaned')