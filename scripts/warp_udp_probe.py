import socket, struct, time, random

def udp_dns(ip, port=53, timeout=3):
    q = b'\xab\xcd' + b'\x01\x00' + b'\x00\x01' + b'\x00\x00' + b'\x00\x00' + b'\x00\x00' + b'\x07cloud\x04flare\x03com\x00' + b'\x00\x01' + b'\x00\x01'
    t0=time.time()
    s=socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(timeout)
    try:
        s.sendto(q,(ip,port)); d,_=s.recvfrom(2048); return 'ANSWERED %dB %.0fms'%(len(d),(time.time()-t0)*1000)
    except socket.timeout: return 'timeout (filtered?)'
    except ConnectionResetError: return 'ICMP unreachable -> port open'
    except OSError as e: return 'OSError %s'%e
    finally: s.close()

print('=== UDP egress sanity (DNS) ===')
for ip in ['1.1.1.1','8.8.8.8','9.9.9.9']:
    print('  %-10s %s'%(ip, udp_dns(ip)))

def udp_wireguard(ip, port, timeout=4):
    # A real (garbage) payload: a live endpoint drops it silently; a closed port ICMPs back.
    t0=time.time()
    s=socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(timeout)
    try:
        s.sendto(bytes(random.getrandbits(8) for _ in range(140)),(ip,port))
        d,a=s.recvfrom(2048); return 'ANSWERED %dB %.0fms'%(len(d),(time.time()-t0)*1000)
    except socket.timeout: return 'silent'
    except ConnectionResetError: return 'ICMP -> port reachable'
    except OSError as e: return 'OSError %s'%e
    finally: s.close()

print('=== Cloudflare WARP ingress, UDP ===')
for ip in ['162.159.192.1','188.114.96.1']:
    for p in [2408, 500, 1701, 4500, 443, 854, 1640]:
        print('  %-14s %-5d %s'%(ip,p,udp_wireguard(ip,p)))
