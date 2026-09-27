import socket, struct, threading, random, time

# A WireGuard handshake initiation, sized and shaped like the real thing.
def wg_init():
    b = bytearray(148)
    b[0] = 1                      # type 1: handshake initiation
    b[4:8] = os.urandom(4)         # sender index
    b[8:36] = os.urandom(28)       # unencrypted ephemeral
    b[36:64] = os.urandom(28)      # static (encrypted)
    b[64:68] = os.urandom(4)       # timestamp
    b[68:100] = os.urandom(32)     # mac1
    b[100:132] = os.urandom(32)    # mac2
    return bytes(b)
import os

def probe(ip, port, timeout=2.5):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    try:
        s.sendto(wg_init(), (ip, port))
        d, a = s.recvfrom(2048)
        return 'ANSWERED %dB type=%d' % (len(d), d[0] if d else -1)
    except socket.timeout:
        return None
    except ConnectionResetError:
        return 'ICMP (port reachable)'
    except OSError:
        return None
    finally:
        s.close()

# Cloudflare's own anycast space, far broader than the WARP ingress ranges.
RANGES = [
    ('104.16.0.0/12',  ['104.16.0.1','104.17.0.1','104.18.0.1','104.19.0.1','104.20.0.1','104.21.0.1','104.22.0.1','104.23.0.1','104.24.0.1','104.25.0.1','104.26.0.1','104.27.0.1']),
    ('172.64.0.0/13',  ['172.64.0.1','172.65.0.1','172.66.0.1','172.67.0.1','172.68.0.1','172.69.0.1','172.70.0.1','172.71.0.1']),
    ('162.158.0.0/15', ['162.158.0.1','162.159.0.1']),
    ('198.18.0.0/15',  ['198.18.0.1','198.19.0.1']),
]
PORTS = [2408, 500, 1701, 4500, 1640, 854]

hits = []
lock = threading.Lock()

def run(ip, p):
    r = probe(ip, p)
    if r:
        with lock:
            hits.append('%s:%d -> %s' % (ip, p, r))

pairs = [(ip, p) for _, ips in RANGES for ip in ips for p in PORTS]
for i in range(0, len(pairs), 48):
    ts = [threading.Thread(target=run, args=x) for x in pairs[i:i+48]]
    for t in ts: t.start()
    for t in ts: t.join()

print('tested %d (ip,port) pairs across 4 Cloudflare ranges' % len(pairs))
if hits:
    print('RESPONDED:')
    for h in sorted(hits): print('  ' + h)
else:
    print('RESPONDED: nothing. Cloudflare UDP is dark across every range tested.')
