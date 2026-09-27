"""Can a WARP endpoint be reached over TCP at all?

Every WARP probe so far sent UDP. If the endpoint also listens on TCP, the whole problem changes:
TCP 443 to Cloudflare works on this network, so a WireGuard handshake carried over TCP would get
through. This is the first question to answer before building anything.
"""
import socket, struct, time

HOSTS = ['162.159.192.1', '162.159.193.1', '188.114.96.1', '188.114.97.1',
         'engage.cloudflareclient.com']
PORTS = [2408, 500, 854, 859, 934, 4500, 1701, 1640, 443, 2409, 51820]

print('=== TCP connect to WARP endpoints (a real handshake would follow) ===')
for host in HOSTS:
    for port in PORTS:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(3.0)
        t0 = time.time()
        try:
            s.connect((host, port))
            print('  %-30s %-6d OPEN   %.0fms' % (host, port, (time.time()-t0)*1000))
        except socket.timeout:
            pass
        except ConnectionRefusedError:
            print('  %-30s %-6d refused (port closed, path open)' % (host, port))
        except socket.gaierror:
            break
        except OSError:
            pass
        finally:
            s.close()
print('done')
