"""The decisive measurement, and the one that decides the whole design.

Findings so far on this network:
  * UDP to any Cloudflare address is dropped, on every port, plain and AmneziaWG-obfuscated.
  * TCP to Cloudflare works: a well-formed ClientHello is accepted, a malformed one is answered
    with TLS alert 50 (decode_error), which is the origin server rejecting us, not a filter cutting
    the connection.

So TCP 443 to Cloudflare is intact. The question is whether a WARP endpoint will speak TCP.

If it will not - and a WireGuard endpoint that answers is expected, since WireGuard has no TCP
transport at all - then no client-side change can make WARP work here, and the only remaining
route is a relay on the far side. The measurement below is what tells us which world we are in.
"""
import socket, struct, os, time

def wg_init(seed=0):
    p = bytearray(148)
    p[0] = 1
    p[4:8] = struct.pack('>I', 0x11223300 + seed)
    for i in range(8, 36):
        p[i] = (i * 7 + seed) & 0xff
    p[68:72] = b'MAC1'
    for i in range(72, 104):
        p[i] = (i + seed) & 0xff
    p[104:108] = b'MAC2'
    for i in range(108, 140):
        p[i] = (i * 3 + seed) & 0xff
    return bytes(p)

print('=== TCP, sending a WireGuard initiation, on every WARP port ===')
for host in ('162.159.192.1', '188.114.96.1'):
    for port in (2408, 500, 854, 934, 4500, 1640):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(4.0)
        try:
            s.connect((host, port))
            s.sendall(wg_init())
            data = s.recv(256)
            if not data:
                verdict = 'closed with no data'
            elif data[0] in (2, 3, 4):
                verdict = '*** WIREGUARD REPLY type=%d ***' % data[0]
            else:
                verdict = 'not wireguard (first byte %d)' % data[0]
            print('  %-16s %-5d %s' % (host, port, verdict))
        except socket.timeout:
            print('  %-16s %-5d connected, no answer' % (host, port))
        except OSError as e:
            print('  %-16s %-5d %s' % (host, port, e))
        finally:
            s.close()
