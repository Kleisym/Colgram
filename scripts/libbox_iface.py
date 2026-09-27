import re
data = open(r'C:\Colgram\ci-artifact\libbox-x86_64.so', 'rb').read()
strings = set(re.findall(rb'[ -~]{4,}', data))

# PlatformInterface is an interface the Go side calls THROUGH into Java. gomobile generates a
# cgo thunk per method, and each thunk name is the method. That is the exact contract we must
# implement, so enumerate the thunks rather than guessing from documentation.
thunks = sorted(s.decode('ascii','replace') for s in strings
                if s.startswith(b'cproxylibbox_PlatformInterface_'))
print('--- PlatformInterface methods the library requires:', len(thunks))
for t in thunks:
    print('  ', t.replace('cproxylibbox_PlatformInterface_', ''))
