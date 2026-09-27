import io
wire = bytes.fromhex("000181800001000100000000036170690874656c656772616d036f72670000010001c00c00010001000000760004959aa66e")
print('total bytes:', len(wire))
buf = io.BytesIO(wire)
ident = buf.read(2); flags = buf.read(2)
qd = int.from_bytes(buf.read(2),'big'); an = int.from_bytes(buf.read(2),'big')
ns = int.from_bytes(buf.read(2),'big'); ar = int.from_bytes(buf.read(2),'big')
print('id', ident.hex(), 'flags', flags.hex(), 'qd', qd, 'an', an, 'ns', ns, 'ar', ar)

def readname(b):
    parts=[]
    while True:
        ln=b.read(1)[0]
        if ln==0: break
        if ln & 0xc0 == 0xc0:
            ptr=((ln & 0x3f)<<8) | b.read(1)[0]
            parts.append('@%d'%ptr)
            break
        parts.append(b.read(ln).decode('ascii','replace'))
    return '.'.join(parts)

print('Q name:', readname(buf))
print('  qtype', int.from_bytes(buf.read(2),'big'), 'qclass', int.from_bytes(buf.read(2),'big'))
for i in range(an):
    nm=readname(buf); t=int.from_bytes(buf.read(2),'big'); c=int.from_bytes(buf.read(2),'big')
    ttl=int.from_bytes(buf.read(4),'big'); ln=int.from_bytes(buf.read(2),'big')
    data=buf.read(ln)
    print('A%d name=%s type=%d class=%d ttl=%d len=%d data=%s' % (i,nm,t,c,ttl,ln,data.hex()))
    if t==1 and ln==4: print('   -> IP', '.'.join(str(x) for x in data))
