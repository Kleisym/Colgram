"""Those "OPEN" results are almost certainly not a WireGuard endpoint.

Every port on every address accepting a connection is the signature of a transparent TCP proxy or
a middlebox answering the handshake itself - a real server refuses the ports it does not serve.
So: does anything actually come back after connecting, and is it WireGuard?
"""
import socket, struct, time

def wg_init():
    p = bytearray(148)
    p[0] = 1
    p[4:8] = b'\x01\x02\x03\x04'
    for i in range(8, 36):
        p[i] = i
    p[68:72] = b'MAC1'
    p[72:104] = b'\x11' * 32
    p[104:108] = b'MAC2'
    p[108:140] = b'\x22' * 32
    return bytes(p)

for host, port in [('162.159.192.1', 2408), ('162.159.192.1', 443), ('162.159.192.1', 9999),
                   ('engage.cloudflareclient.com', 2408), ('1.1.1.1', 443)]:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(4.0)
    try:
        s.connect((host, port))
        # A WireGuard endpoint answers a handshake; a middlebox answers nothing or a TLS alert.
        s.sendall(wg_init())
        data = s.recv(256)
        if not data:
            verdict = 'connected, then closed with no data (proxy, not an endpoint)'
        else:
            first = data[0]
            if first in (2, 3, 4):
                verdict = 'WIREGUARD REPLY type=%d, %d bytes' % (first, len(data))
            elif first == 21:
                verdict = 'TLS alert (a web server, not WireGuard)'
            elif first == 0x16:
                verdict = 'TLS record (a web server, not WireGuard)'
            else:
                verdict = 'unknown first byte %d, %d bytes: %s' % (first, len(data), data[:16].hex())
        print('%-28s %-5d %s' % (host, port, verdict))
    except socket.timeout:
        print('%-28s %-5d connected, then timed out' % (host, port))
    except ConnectionResetError:
        print('%-28s %-5d RST after connect (filtered)' % (host, port))
    except OSError as e:
        print('%-28s %-5d %s' % (host, port, e))
    finally:
        s.close()
