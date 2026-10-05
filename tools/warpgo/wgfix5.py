p = r'C:\Colgram\tools\warpgo\wgprobe\main.go'
lines = open(p, encoding='utf-8').read().split(chr(10))
out = []
for ln in lines:
    # The cut left a stray literal backtick-n plus a duplicate tail line.
    if ln.strip() == '", len(peerPub))':
        continue
    ln = ln.replace('`n"', chr(92) + 'n"')
    out.append(ln)
open(p, 'w', encoding='utf-8').write(chr(10).join(out))
print('cleaned, lines:', len(out))