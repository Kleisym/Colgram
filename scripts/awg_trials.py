import os, socket, random, threading

def awg_init(S1, H1, H2):
    p = bytearray(148)
    p[0:4] = S1; p[4:8] = os.urandom(4)
    p[8:36] = os.urandom(28); p[36:64] = os.urandom(28)
    p[64:68] = os.urandom(4); p[68:72] = H1
    p[72:104] = os.urandom(32); p[104:108] = H2
    p[108:140] = os.urandom(32)
    return bytes(p)

def plain_init():
    p = bytearray(148); p[0] = 1
    p[4:8] = os.urandom(4); p[8:36] = os.urandom(28); p[36:64] = os.urandom(28)
    p[68:104] = os.urandom(36); p[104:140] = os.urandom(36)
    return bytes(p)

S1 = os.urandom(4); H1 = os.urandom(4); H2 = os.urandom(4)
HOSTS = ['188.114.99.1','188.114.98.1','162.159.195.1','162.159.192.1','188.114.96.1','188.114.97.1']
PORTS = [934, 2408, 859, 854, 500, 4500, 1701]

def burst(payload, jc=4):
    out = b''
    for _ in range(jc):
        out += os.urandom(random.randint(10, 700))
    return out + payload

def probe(ip, port, payload, timeout=3.0):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(timeout)
    try:
        s.sendto(burst(payload), (ip, port))
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

# 5 independent trials per pair: a single 16-byte hit was not reproducible earlier.
print('plain vs AmneziaWG, 5 trials each')
awg_hits, plain_hits = [], []
lock = threading.Lock()

def run(ip, p, kind):
    payload = awg_init(S1, H1, H2) if kind == 'awg' else plain_init()
    for _ in range(5):
        r = probe(ip, p, payload)
        if r:
            with lock:
                (awg_hits if kind == 'awg' else plain_hits).append('%s:%d %s' % (ip, p, r))
            return

for kind in ('awg', 'plain'):
    pairs = [(ip, p) for ip in HOSTS for p in PORTS]
    for i in range(0, len(pairs), 32):
        ts = [threading.Thread(target=run, args=x + (kind,)) for x in pairs[i:i+32]]
        for t in ts: t.start()
        for t in ts: t.join()

print('AmneziaGW hits: %d %s' % (len(awg_hits), sorted(set(awg_hits)) if awg_hits else ''))
print('plain WG hits  : %d %s' % (len(plain_hits), sorted(set(plain_hits)) if plain_hits else ''))
