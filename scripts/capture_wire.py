import urllib.request

def q(name):
    b = bytearray()
    b += bytes([0x00, 0x01, 0x01, 0x00])  # id, flags
    b += bytes([0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])
    for label in name.split('.'):
        b.append(len(label))
        b += label.encode()
    b.append(0)
    b += bytes([0x00, 0x01, 0x00, 0x01])
    return bytes(b)

req = urllib.request.Request(
    'https://1.1.1.1/dns-query?name=api.telegram.org&type=A',
    headers={'Accept': 'application/dns-message', 'Host': 'cloudflare-dns.com'},
    data=q('api.telegram.org'), method='POST')
try:
    wire = urllib.request.urlopen(req, timeout=15).read()
    print('bytes:', len(wire))
    print(wire.hex())
    # decode the A record the same way the Java parser does
    import io
    buf = io.BytesIO(wire)
    def name():
        while True:
            ln = buf.read(1)[0]
            if ln == 0: return
            if ln & 0xc0 == 0xc0: buf.read(1); return
            buf.read(ln)
    buf.seek(12)
    name(); name()
    t = int.from_bytes(buf.read(2), 'big'); c = int.from_bytes(buf.read(2), 'big')
    buf.read(4); ln = int.from_bytes(buf.read(2), 'big')
    if t == 1 and ln == 4:
        print('A =', '.'.join(str(b) for b in buf.read(4)))
    else:
        print('type', t, 'class', c, 'len', ln)
except Exception as e:
    print('ERR', type(e).__name__, e)
