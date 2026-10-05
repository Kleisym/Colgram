import re
p = r'C:\Colgram\tools\warpgo\native\main.go'
src = open(p, 'r', encoding='utf-8').read()
# Earlier passes left a stray '/' at the end of comment lines where the run split happened mid-'//'.
# A line that ends in '/' after real comment text, and where the next line starts with '/', is one
# broken '//' split in two.
lines = src.split(chr(10))
out = []
for ln in lines:
    if ln.rstrip().endswith('/') and not ln.rstrip().endswith('//'):
        nxt_is_comment = False
        if out:
            prev = out[-1].lstrip()
            nxt_is_comment = prev.startswith('/')
        if nxt_is_comment:
            ln = ln.rstrip()[:-1]
    out.append(ln)
src = chr(10).join(out)
# A line that starts with a single '/' then a space is a broken comment opener.
src = re.sub(chr(10) + r'/ (?=[A-Za-z])', chr(10) + '// ', src)
open(p, 'w', encoding='utf-8', newline='').write(src)
print('done')