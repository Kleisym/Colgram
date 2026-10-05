p = r'C:\Colgram\tools\warpgo\socksudp\main.go'
lines = open(p, encoding='utf-8').read().split(chr(10))
out = []
for ln in lines:
    if ln.strip() == 'ip := net.ParseIP(rhost).To4()':
        ln = ln.replace('ip :=', 'relayIP :=')
    if ln.strip() == 'if ip == nil {':
        ln = ln.replace('if ip == nil {', 'if relayIP == nil {')
    if 'ip = []byte{0, 0, 0, 0}' in ln:
        ln = ln.replace('ip = []byte{0, 0, 0, 0}', 'relayIP = []byte{0, 0, 0, 0}')
    if 'relayIP[0], relayIP[1], relayIP[2], relayIP[3]' in ln:
        out.append(ln)
        continue
    if ln.strip() == 'ip[0], ip[1], ip[2], ip[3],' and 'assoc' not in ln:
        ln = ln.replace('ip[0], ip[1], ip[2], ip[3],', 'relayIP[0], relayIP[1], relayIP[2], relayIP[3],')
    if ln.strip().startswith('eip := net.ParseIP(ehost)'):
        out.append(ln)
        continue
    out.append(ln)
open(p, 'w', encoding='utf-8').write(chr(10).join(out))
print('renamed')