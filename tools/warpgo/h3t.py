import os
p = r'C:\Colgram\tools\warpgo\h3probe\main.go'
s = open(p, encoding='utf-8').read()
old = s[s.index('\ttargets := []target{'):s.index('\tif v := env("ONLY")')]
new = '''\ttargets := []target{
\t\t{"edge 188.114.97.1:443", "188.114.97.1:443", "https://188.114.97.1/"},
\t\t{"edge 162.159.192.6:443", "162.159.192.6:443", "https://162.159.192.6/"},
\t\t{"edge 162.159.192.6:2408", "162.159.192.6:2408", "https://162.159.192.6/"},
\t\t{"edge 188.114.97.1:2408", "188.114.97.1:2408", "https://188.114.97.1/"},
\t\t{"1.1.1.1 control", "1.1.1.1:443", "https://1.1.1.1/cdn-cgi/trace"},
\t}
'''
s = s.replace(old, new)
open(p, 'w', encoding='utf-8').write(s)
print('ok')