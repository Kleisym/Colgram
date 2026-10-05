import re
p = r'C:\Colgram\tools\warpgo\native\main.go'
src = open(p, 'r', encoding='utf-8').read()

# The header lost its leading slashes and later comment runs are still glued together as "////" and
# "sentence.// sentence". Both are recoverable without touching code: a '//' that immediately follows
# a period, a slash, or another '//' begins a comment that lost its line break.
res = src
res = re.sub(r'(?<![/\w.]) warpverdict measures', '// warpverdict measures', res, count=1)
res = res.replace('////', chr(10) + '//')
res = re.sub(r'\.//', '.' + chr(10) + '//', res)
res = re.sub(r'(?<=[\w\)\]\}`])//', chr(10) + '//', res)
res = re.sub(r'\*/(?=[^' + chr(10) + '])', '*/' + chr(10), res)
res = re.sub(chr(10) + r'[ \t]*' + chr(10) + r'[ \t]*' + chr(10) + '+', chr(10)*2, res)
open(p, 'w', encoding='utf-8', newline='').write(res)
print('lines:', res.count(chr(10)))