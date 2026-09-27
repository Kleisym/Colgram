import socket, threading, os

def quic_initial(seed=0):
    p = bytearray(1200)
    p[0] = 0xC0
    p[1:5] = b'\x00\x00\x00\x01'
    for i in range(5, 40):
        p[i] = (i * 7 + seed) & 0xff
    return bytes(p)

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
        return 'ICMP'
    except OSError:
        return None
    finally:
        s.close()

CF = ['104.16.0.1','104.17.0.1','104.18.0.1','104.19.0.1','104.20.0.1','104.21.0.1','104.22.0.1','104.23.0.1','104.24.0.1','104.25.0.1','104.26.0.1','104.27.0.1',
      '172.64.0.1','172.65.0.1','172.66.0.1','172.67.0.1','172.68.0.1','172.69.0.1','172.70.0.1','172.71.0.1',
      '162.158.0.1','162.159.0.1','188.114.96.1','188.114.97.1']

hits = []
lock = threading.Lock()

def run(ip, p):
    r = probe(ip, p, quic_initial())
    if r:
        with lock:
            hits.append('%s:%d -> %s' % (ip, p, r))

pairs = [(ip, p) for ip in CF for p in (443, 8443, 8853)]
for i in range(0, len(pairs), 48):
    ts = [threading.Thread(target=run, args=x) for x in pairs[i:i+48]]
    for t in ts: t.start()
    for t in ts: t.join()

print('QUIC tested: %d pairs' % len(pairs))
print('RESPONDED: ' + (', '.join(sorted(hits)) if hits else 'nothing'))

# Control: is UDP 443 open ANYWHERE, or is 443/UDP itself blocked wholesale?
print('\n=== UDP 443 control, non-Cloudflare ===')
for ip in ['8.8.8.8','9.9.9.9']:
    print('  %-12s %s' % (ip, probe(ip, 443, quic_initial(3)) or 'silent'))
