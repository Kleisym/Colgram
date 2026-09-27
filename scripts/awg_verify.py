"""Is 188.114.99.1:934 really a WireGuard peer, or just something that answers?

A 16-byte reply is the size of a Handshake Response header. To confirm it is WireGuard and
not a coincidence, check the two things that make it unambiguous:
  * the reserved field is zero (bytes 4-7 of a type-2 response)
  * a COOKIE REPLY (type 3) also gets an answer, which no random service would produce
"""
import os, socket, struct, random

S1 = os.urandom(4); H1 = os.urandom(4); H2 = os.urandom(4)
HOST, PORT = '188.114.99.1', 934


def awg_init():
    p = bytearray(148)
    p[0:4] = S1; p[4:8] = os.urandom(4)
    p[8:36] = os.urandom(28); p[36:64] = os.urandom(28)
    p[64:68] = os.urandom(4); p[68:72] = H1
    p[72:104] = os.urandom(32); p[104:108] = H2
    p[108:140] = os.urandom(32)
    return bytes(p)


def awg_cookie_reply():
    # type 3, obfuscated the same way
    p = bytearray(64)
    p[0:4] = b'\x03' + S1[1:4]
    p[4:8] = os.urandom(4)
    p[8:12] = os.urandom(4)
    p[12:44] = os.urandom(32)
    return bytes(p)


def send(payload, label):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(4.0)
    try:
        junk = b''
        for _ in range(4):
            junk += os.urandom(random.randint(10, 700))
        s.sendto(junk + payload, (HOST, PORT))
        d, a = s.recvfrom(2048)
        print('%-14s -> %d bytes from %s' % (label, len(d), a[0]))
        print('   first 16 bytes: %s' % d[:16].hex())
        print('   bytes[4:8] (reserved, must be 00000000): %s' % d[4:8].hex())
        return d
    except socket.timeout:
        print('%-14s -> timeout' % label)
    except OSError as e:
        print('%-14s -> %s' % (label, e))
    finally:
        s.close()
    return None

print('=== plain handshake, no junk, no obfuscation (the control) ===')
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(3.0)
plain = bytearray(148); plain[0] = 1
plain[4:8] = os.urandom(4); plain[8:36] = os.urandom(28); plain[36:64] = os.urandom(28)
plain[68:104] = os.urandom(36); plain[104:140] = os.urandom(36)
try:
    s.sendto(bytes(plain), (HOST, PORT)); d, _ = s.recvfrom(2048); print('plain init -> %d bytes %s' % (len(d), d[:8].hex()))
except socket.timeout:
    print('plain init -> timeout  (DPI drops it)')
finally:
    s.close()

print()
print('=== AmneziaWG handshake ===')
r1 = send(awg_init(), 'awg init')
print()
print('=== AmneziaWG cookie reply (only a real WG peer sends this) ===')
r2 = send(awg_cookie_reply(), 'awg cookie')
print()
if r1 and r2:
    print('VERDICT: both handshake AND cookie reply answered -> a live WireGuard peer is reachable')
elif r1:
    print('VERDICT: handshake answered, cookie reply silent -> check the reserved bytes above')
else:
    print('VERDICT: inconclusive')
