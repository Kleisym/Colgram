import re
data = open(r'C:\Colgram\ci-artifact\libbox-x86_64.so', 'rb').read()
strings = set(re.findall(rb'[ -~]{4,}', data))
for needle in (b'reality', b'sing-box/common/urltest', b'fragment', b'ech'):
    hits = sorted(x.decode('ascii','replace') for x in strings if needle in x.lower())[:10]
    print('--- %r : %d hits ---' % (needle, len(hits)))
    for h in hits:
        print('  ', h[:100])
