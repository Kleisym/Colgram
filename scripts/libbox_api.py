import re
data = open(r'C:\Colgram\ci-artifact\libbox-x86_64.so', 'rb').read()
strings = set(re.findall(rb'[ -~]{4,}', data))

# What Colgram has to construct, and what go_seq_init needs set up first.
for needle in (b'CommandServer', b'PlatformInterface', b'go_Seq_', b'NewFileDescriptor', b'ParcelFileDescriptor'):
    hits = sorted(x.decode('ascii','replace') for x in strings
                  if x.startswith(needle) or needle in x[:40])[:14]
    print('--- %r ---' % needle)
    for h in hits:
        print('  ', h)

# The protocols we promised, confirmed present in the binary.
protocols = ['vless', 'vmess', 'trojan', 'shadowsocks', 'hysteria', 'hysteria2',
             'reality', 'socks', 'wireguard', 'tuic', 'anytls']
print('--- protocols compiled in ---')
for p in protocols:
    token = ('protocol/%s' % p).encode()
    print('  %-12s %s' % (p, 'yes' if any(token in x for x in strings) else 'no'))
