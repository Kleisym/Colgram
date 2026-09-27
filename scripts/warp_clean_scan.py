import socket, threading, os

def wg_init():
    b = bytearray(148)
    b[0] = 1
    b[4:8] = os.urandom(4)
    b[8:36] = os.urandom(28)
    b[36:64] = os.urandom(28)
    b[68:100] = os.urandom(32)
    b[100:132] = os.urandom(32)
    return bytes(b)

def probe(ip, port, timeout=2.5):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    try:
        s.sendto(wg_init(), (ip, port))
        d, _ = s.recvfrom(2048)
        return 'ANSWERED %dB type=%d' % (len(d), d[0] if d else -1)
    except socket.timeout:
        return None
    except ConnectionResetError:
        return 'ICMP'
    except OSError:
        return None
    finally:
        s.close()

# Exactly the ranges the sources name as not-yet-blocked. My earlier sweep never touched these.
HOSTS = (['188.114.98.%d' % i for i in range(1, 12)] +
         ['188.114.99.%d' % i for i in range(1, 12)] +
         ['162.159.195.%d' % i for i in range(1, 12)])
PORTS = [2408, 500, 854, 859, 934, 4500, 1701, 1640]

hits = []
lock = threading.Lock()

def run(ip, p):
    r = probe(ip, p)
    if r:
        with lock:
            hits.append('%s:%d -> %s' % (ip, p, r))

pairs = [(ip, p) for ip in HOSTS for p in PORTS]
for i in range(0, len(pairs), 48):
    ts = [threading.Thread(target=run, args=x) for x in pairs[i:i+48]]
    for t in ts: t.start()
    for t in ts: t.join()

print('tested %d pairs in the ranges sources name as clean' % len(pairs))
print('RESPONDED:' if hits else 'RESPONDED: nothing')
for h in sorted(hits): print('  ' + h)
