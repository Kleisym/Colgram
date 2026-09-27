import socket, time, threading, random

IPS = ['162.159.192.1','162.159.193.1','188.114.96.1','188.114.97.1']
# Cloudflare WARP listens on a wide, published port range - not just the 4 in a registration.
PORTS = [500,854,859,864,878,880,890,891,894,903,908,928,934,939,942,943,945,946,955,
         968,987,988,1002,1010,1014,1018,1070,1074,1180,1387,1640,1701,1843,2071,2079,
         2080,2408,2506,3138,3476,3581,3854,4177,4198,4233,4500,5270,5956,7103,7152,
         7156,7281,7559,8319,8742,8854,8886,53,123,161,389,636,3478,5222,8080,8443]

results = {}
lock = threading.Lock()

def probe(ip, port):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(2.0)
    try:
        s.sendto(bytes(random.getrandbits(8) for _ in range(140)), (ip, port))
        d, a = s.recvfrom(2048)
        out = 'ANSWERED %dB' % len(d)
    except socket.timeout:
        out = None
    except ConnectionResetError:
        out = 'ICMP-unreachable(port open)'
    except OSError:
        out = None
    finally:
        s.close()
    if out:
        with lock:
            results[(ip, port)] = out

pairs = [(ip, p) for ip in IPS for p in PORTS]
for chunk in range(0, len(pairs), 64):
    batch = pairs[chunk:chunk+64]
    ts = [threading.Thread(target=probe, args=p) for p in batch]
    for t in ts: t.start()
    for t in ts: t.join()

print('tested %d (ip,port) pairs' % len(pairs))
if results:
    print('RESPONDED:')
    for k, v in sorted(results.items()): print('  %s:%d -> %s' % (k[0], k[1], v))
else:
    print('RESPONDED: nothing. All %d Cloudflare WARP UDP endpoints are silent.' % len(pairs))
