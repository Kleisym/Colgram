import re
data = open(r'C:\Colgram\ci-artifact\libbox-x86_64.so', 'rb').read()
strings = set(re.findall(rb'[ -~]{4,}', data))

# The JNI entry points gomobile generates are Java_lib_<pkg>_<class>_<method>.
jni = sorted(s.decode('ascii', 'replace') for s in strings if s.startswith(b'Java_'))
print('--- JNI entry points:', len(jni))
for s in jni:
    print(' ', s)

# And the Go-side names the Java layer calls, which is what the interface we need looks like.
for needle in (b'CommandClient', b'newService', b'NewService', b'service', b'Start', b'Stop', b'formatURL'):
    hits = sorted(x.decode('ascii', 'replace') for x in strings
                  if needle in x and len(x) < 60)[:8]
    if hits:
        print('--- contains %r ---' % needle)
        for h in hits:
            print('   ', h)
