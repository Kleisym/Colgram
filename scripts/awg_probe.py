"""Does AmneziaWG-style obfuscation get past the DPI? Decisive experiment.

My earlier probes sent a REAL WireGuard initiation: type byte 0x01, and the literal
"MAC1 "/"MAC2 " magics in the header. TSPU fingerprints exactly that and drops it.

AmneziaWG changes the bytes ON THE WIRE, client-side, then reverses them before the
packet reaches the real WireGuard parser. So the server still speaks plain WireGuard,
but the DPI never sees a WireGuard handshake at all. That is why it can work against a
genuine Cloudflare endpoint - and why my probe could never have found it.

  S1, S2 : random replacement for the 4-byte message type
  H1, H2 : random replacement for the "MAC1 " / "MAC2 " header magics
  Jc     : count of junk packets sent before the real one
  Jmin..Jmax : junk packet size range
"""
import os, socket, threading, random, time

HOSTS = ['162.159.192.1', '162.159.193.1', '162.159.195.1', '188.114.96.1',
         '188.114.97.1', '188.114.98.1', '188.114.99.1', '162.158.0.1']
PORTS = [2408, 500, 854, 859, 934, 4500, 1701, 1640, 443]

S1 = os.urandom(4)
H1 = os.urandom(4)
H2 = os.urandom(4)
JC, JMIN, JMAX = 4, 10, 700


def junk():
    return os.urandom(random.randint(JMIN, JMAX))


def awg_init():
    """A Handshake Initiation as AmneziaWG puts it on the wire."""
    p = bytearray(148)
    p[0:4] = S1                       # obfuscated message type
    p[4:8] = os.urandom(4)           # sender index
    p[8:36] = os.urandom(28)         # unencrypted ephemeral
    p[36:64] = os.urandom(28)        # static (encrypted)
    p[64:68] = os.urandom(4)         # timestamp
    p[68:72] = H1                     # obfuscated "MAC1 "
    p[72:104] = os.urandom(32)        # mac1
    p[104:108] = H2                   # obfuscated "MAC2 "
    p[108:140] = os.urandom(32)       # mac2
    return bytes(p)


def burst():
    out = b''
    for _ in range(JC):
        out += junk()
    return out + awg_init()


def probe(ip, port, timeout=2.0):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    try:
        s.sendto(burst(), (ip, port))
        d, _ = s.recvfrom(2048)
        return 'ANSWERED %dB' % len(d)
    except socket.timeout:
        return None
    except ConnectionResetError:
        return 'ICMP'
    except OSError:
        return None
    finally:
        s.close()


hits = []
lock = threading.Lock()


def run(ip, p):
    r = probe(ip, p)
    if r:
        with lock:
            hits.append('%s:%d -> %s' % (ip, p, r))


pairs = [(ip, p) for ip in HOSTS for p in PORTS]
for i in range(0, len(pairs), 48):
    ts = [threading.Thread(target=run, args=x) for x in pairs[i:i + 48]]
    for t in ts:
        t.start()
    for t in ts:
        t.join()

print('AmneziaWG burst: %d (ip,port) pairs, Jc=%d junk %d-%dB' % (len(pairs), JC, JMIN, JMAX))
print('RESPONDED:' if hits else 'RESPONDED: nothing')
for h in sorted(hits):
    print('  ' + h)
