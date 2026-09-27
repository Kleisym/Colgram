import re
data = open(r'C:\Colgram\ci-artifact\libbox-x86_64.so', 'rb').read()
strings = set(re.findall(rb'[ -~]{4,}', data))

# The Java class AndroidVPNType is a THIN wrapper: the Java_..._AndroidVPNType_* JNI entries show
# which Go functions the generated class exposes. If the class is missing at runtime but these
# entry points exist, the binding is compiled in and the class simply was not on the classpath.
print('--- AndroidVPNType JNI entry points present:',
      len([s for s in strings if b'Java_io_nekohasekai_libbox_AndroidVPNType' in s]))

# The real question: what starts a service from Go, callable through a plain JNI signature?
for needle in (b'libbox.NewService', b'libbox.Bridge', b'libbox.NewCommandServer',
               b'service.Run', b'CommandServerHandler'):
    hits = sorted(x.decode('ascii','replace') for x in strings
                  if needle in x and len(x) < 90)[:5]
    if hits:
        print('--- %r ---' % needle.decode())
        for h in hits:
            print('   ', h)
