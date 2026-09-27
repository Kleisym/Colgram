"""There is a transparent TCP proxy in front of everything on this network.

Port 443 answered with "HTTP/1.1 400 Bad Request" to a WireGuard initiation - a TLS server would
have sent an alert, and a WireGuard endpoint would have sent a cookie reply. So the path is: client
-> middlebox -> origin, and the middlebox completes the TCP handshake on the client's behalf.

That is not a dead end. It means the network terminates TCP, so anything the middlebox speaks
will get through, and anything it merely forwards may be filtered. Find out which.
"""
import socket

def probe(host, port, payload, label, timeout=5.0):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        s.connect((host, port))
        s.sendall(payload)
        data = s.recv(512)
        head = data[:60]
        printable = all(32 <= b < 127 or b in (10, 13) for b in head)
        print('%-22s %s' % (label, head.decode('latin-1').replace('\r\n', ' | ') if printable else head.hex()))
    except socket.timeout:
        print('%-22s timeout' % label)
    except OSError as e:
        print('%-22s %s' % (label, e))
    finally:
        s.close()

# Ask as an HTTP proxy would: the middlebox should answer like a proxy, not like an origin.
probe('162.159.192.1', 443, b'GET / HTTP/1.1\r\nHost: example.com\r\n\r\n', 'http via 162.159.192.1')
probe('1.1.1.1', 443, b'GET / HTTP/1.1\r\nHost: example.com\r\n\r\n', 'http via 1.1.1.1')
# A real TLS ClientHello to see whether TLS is terminated or passed through.
hello = bytes([0x16,0x03,0x01,0x00,0x05,0x01,0x00,0x00,0x01,0x00,0x01,0x00])
probe('162.159.192.1', 443, hello, 'tls via 162.159.192.1')
probe('1.1.1.1', 443, hello, 'tls via 1.1.1.1')
# And plain HTTP to a port that should not speak it at all.
probe('162.159.192.1', 2408, b'GET / HTTP/1.1\r\nHost: x\r\n\r\n', 'http via :2408')
