import socket, struct, threading, os, random

def dns_query(name='cloudflare.com'):
    out = bytearray(32)
    out[0:2] = os.urandom(2)
    out[2] = 0x01; out[5] = 0x01
    at = 12
    for label in name.split('.'):
        out[at] = len(label); at += 1
        for ch in label:
            out[at] = ord(ch); at += 1
    out[at] = 0; at += 1
    out[at:at+4] = b'\x00\x01\x00\x01'
    return bytes(out[:at+4])

def probe(ip, port, payload, timeout=2.5):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    try:
        s.sendto(payload, (ip, port))
        d, _ = s.recvfrom(2048)
        return 'ANSWERED %dB' % len(d)
    except socket.timeout:
        return None
    except ConnectionResetError:
        return 'ICMP(port reachable)'
    except OSError:
        return None
    finally:
        s.close()

# Does the block follow the DESTINATION or the PORT? Random high-port UDP is the control.
print('=== random public resolvers, port 53 ===')
for ip in ['8.8.8.8','1.1.1.1','9.9.9.9','208.67.222.222','77.88.8.8']:
    print('  %-16s %s' % (ip, probe(ip, 53, dns_query()) or 'silent'))

print('=== same resolvers, random high port (should be silent everywhere) ===')
for ip in ['8.8.8.8','1.1.1.1']:
    print('  %-16s %s' % (ip, probe(ip, 31337, os.urandom(64)) or 'silent'))

print('=== QUIC (UDP 443) to Cloudflare - the transport real WARP clients prefer ===')
# A minimal QUIC Initial: long header, version 1, then a plausible packet.
quic = bytearray(1200)
quic[0] = 0xC0
quic[1:5] = b'\x00\x00\x00\x01'
for ip in ['104.16.0.1','162.159.192.1','188.114.96.1']:
    print('  %-16s %s' % (ip, probe(ip, 443, bytes(quic)) or 'silent'))
