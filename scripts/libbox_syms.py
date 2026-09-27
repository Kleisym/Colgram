import re
# The library is stripped of a symbol table in release builds, so read the dynamic symbols the
# JNI registration needs. What matters is the class and method names gomobile registers.
data = open(r'C:\Colgram\ci-artifact\libbox-x86_64.so', 'rb').read()
strings = set(re.findall(rb'[ -~]{6,}', data))
interesting = sorted(s.decode('ascii', 'replace') for s in strings
                    if b'libbox' in s or b'NewPlatformInterface' in s or b'CommandServer' in s)
print('--- JNI symbols found ---')
for s in interesting[:30]:
    print(' ', s)
print('total candidates:', len(interesting))
