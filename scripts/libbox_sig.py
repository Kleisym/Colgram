import re
data = open(r'C:\Colgram\ci-artifact\libbox-x86_64.so', 'rb').read()
strings = set(re.findall(rb'[ -~]{4,}', data))

# The generated Go interface struct lists the method set with their signatures; gomobile emits a
# descriptor per method. Find the interface's own type descriptor to recover the real signatures.
for needle in (b'PlatformInterface', b'libbox.PlatformInterface'):
    hits = sorted(x.decode('ascii','replace') for x in strings
                  if x.startswith(needle) and len(x) < 300)
    print('--- %r : %d ---' % (needle.decode(), len(hits)))
    for h in hits[:8]:
        print('  ', h[:280])
    print()
