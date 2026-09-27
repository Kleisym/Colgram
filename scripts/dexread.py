"""Reads class and method names straight out of a dex, so the binding contract is not guessed."""
import re, struct, sys

data = open(sys.argv[1], 'rb').read()

magic = data[:8]
assert magic[:4] == b'dex\n', 'not a dex file'
# string_ids, type_ids, method_ids, class_defs are all uint32-spooled at fixed offsets.
def u32(off):
    return struct.unpack_from('<I', data, off)[0]

string_ids_size, string_ids_off = u32(56), u32(60)
type_ids_size, type_ids_off = u32(64), u32(68)
proto_ids_size, proto_ids_off = u32(72), u32(76)
field_ids_size, field_ids_off = u32(80), u32(84)
method_ids_size, method_ids_off = u32(88), u32(92)
class_defs_size, class_defs_off = u32(96), u32(100)

def uleb(off):
    result = shift = 0
    while True:
        b = data[off]; off += 1
        result |= (b & 0x7f) << shift
        if not b & 0x80:
            return result, off
        shift += 7

strings = []
for i in range(string_ids_size):
    off = u32(string_ids_off + i * 4)
    _, off = uleb(off)
    end = data.index(b'\x00', off)
    strings.append(data[off:end].decode('utf-8', 'replace'))

def type_of(idx):
    return strings[u32(type_ids_off + idx * 4)]

# method_id: class_idx(u16) proto_idx(u16) name_idx(u32)
methods = []
for i in range(method_ids_size):
    off = method_ids_off + i * 8
    class_idx, proto_idx, name_idx = struct.unpack_from('<HHI', data, off)
    methods.append((type_of(class_idx), strings[name_idx]))

want = sys.argv[2] if len(sys.argv) > 2 else 'PlatformInterface'
hits = sorted({(c, m) for c, m in methods if c.endswith('/' + want)})
print('--- %s: %d methods ---' % (want, len(hits)))
for c, m in hits:
    print('  %s' % m)
