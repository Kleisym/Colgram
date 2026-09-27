"""The previous probe showed a TLS ServerHello with version 1.0 and a 2-byte record - decode it.

0x15 0x03 0x01 0x00 0x02 is a TLS Alert record: handshake version TLS 1.0, length 2, and the two
bytes that follow are the alert itself. That is not a ServerHello; it is the peer telling us the
record was rejected before any handshake happened.
"""
import socket

def alert(host, port, payload, label):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(5.0)
    try:
        s.connect((host, port))
        s.sendall(payload)
        data = s.recv(64)
        if not data:
            print('%-26s closed with no data' % label)
            return
        if data[0] == 0x15:
            print('%-26s TLS ALERT level=%d desc=%d  (rejected at record layer)' %
                  (label, data[5], data[6]))
        elif data[0] == 0x16:
            print('%-26s handshake record, type=%d len=%d' % (label, data[5], (data[3] << 8) | data[4]))
        else:
            print('%-26s %s' % (label, data[:16].hex()))
    except Exception as e:
        print('%-26s %s' % (label, e))
    finally:
        s.close()

hello = bytes([0x16,0x03,0x01,0x00,0x05,0x01,0x00,0x00,0x01,0x00,0x01,0x00])
alert('162.159.192.1', 443, hello, 'WG bytes as TLS to CF')
alert('1.1.1.1', 443, hello, 'WG bytes as TLS to 1.1.1.1')
# A well-formed minimal ClientHello, to see whether a real TLS client fares better.
real = bytes.fromhex('160301002a0100002603010200' + '00' * 32 + '00')
alert('162.159.192.1', 443, real, 'proper ClientHello to CF')
alert('1.1.1.1', 443, real, 'proper ClientHello to 1.1.1.1')
