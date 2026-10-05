p = r'C:\Colgram\tools\warpgo\socksudp\main.go'
s = open(p, encoding='utf-8').read()
# A literal address needs no resolution, and the DoH path is only for names.
old = '\tip := resolveProxy(proxy)\n\tif ip == "" {\n\t\treturn fmt.Errorf("no A record over DoH")\n\t}'
new = '''\tphost, _, _ := net.SplitHostPort(proxy)
\tip := phost
\tif net.ParseIP(phost) == nil {
\t\tip = resolveProxy(proxy)
\t}
\tif ip == "" {
\t\treturn fmt.Errorf("no A record over DoH")
\t}'''
if old not in s:
    raise SystemExit('anchor missing')
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
print('patched')