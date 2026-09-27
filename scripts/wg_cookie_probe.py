import socket, struct, os

def wg_init():
    b = bytearray(148)
    b[0] = 1
    b[4:8] = os.urandom(4)
    b[8:36] = os.urandom(28)
    b[36:64] = os.urandom(28)
    b[68:100] = os.urandom(32)
    b[100:132] = os.urandom(32)
    return bytes(b)

def wg_cookie_reply():
    b = bytearray(64)
    b[0] = 3          # type 3: cookie reply - the endpoint's answer to a mac1-only probe
    b[4:8] = os.urandom(4)
    b[8:12] = os.urandom(4)  # receiver index
    b[12:44] = os.urandom(32)
    return bytes(b)

def probe(ip, port, payload, timeout=3.0):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    try:
        s.sendto(payload, (ip, port))
        d, _ = s.recvfrom(2048)
        return d, True
    except socket.timeout:
        return None, False
    except ConnectionResetError:
        return b'ICMP', True
    except OSError:
        return None, False
    finally:
        s.close()

# 172.65.0.1 answered on 443. Try the WARP handshake on the ports it might listen on.
HOSTS = ['172.65.0.1', '172.64.0.1', '172.66.0.1', '172.67.0.1', '172.68.0.1', '172.69.0.1', '172.70.0.1', '172.71.0.1']
PORTS = [2408, 500, 1701, 4500, 1640, 854, 443, 2053, 2083, 2087, 2096, 8443]

print('=== cookie-reply probe (does the endpoint answer a mac1-only handshake?) ===')
for ip in HOSTS:
    for p in PORTS:
        d, ok = probe(ip, p, wg_cookie_reply(), timeout=2.0)
        if ok:
            print('  %s:%d -> %d bytes, type=%d' % (ip, p, len(d), d[0]))

print('done')
