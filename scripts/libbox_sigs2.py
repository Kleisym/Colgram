import re
data = open(r'C:\Colgram\ci-artifact\libbox-x86_64.so', 'rb').read()
strings = set(re.findall(rb'[ -~]{3,}', data))

# The Go type strings for the method signatures live alongside; print anything mentioning the
# types that cross the boundary, so the Java signatures can be written exactly.
for t in (b'OpenTun', b'AutoDetectInterfaceControl', b'CreateBridge', b'RegisterMyInterface', b'GetInterfaces'):
    hits = sorted(x.decode('ascii','replace') for x in strings if t in x and len(x) < 160)
    print('--- %s ---' % t.decode())
    for h in hits[:6]:
        print('  ', h)
