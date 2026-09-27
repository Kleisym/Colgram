"""The decisive experiment: does putting a real WireGuard handshake inside a TLS record
survive this network?

Reading the sources produced two claims that can both be true and point opposite ways:

  * keremerkan.dev (2026-07): "the most censor will block the protocol on all UDP ports" - so
    no amount of WireGuard-with-hiding works, and you need the protocol inside a TCP tunnel;
  * evilork/awesome-vpn-russia-2026: AmneziaWG works in Russia - WireGuard with HTTPS-like
    obfuscation, still UDP.

Those can only both hold if the block keys on something narrower than "UDP to Cloudflare". The
test isolates which: send a genuine WireGuard initiation alone, then the same initiation wrapped
in a TLS record, to the same address and port. If the wrapped one gets an answer and the bare one
does not, the filter is reading packet structure, and a TLS-shaped wrapper is the bypass.
"""
import os, socket, struct, threading, random

HOST = '162.159.192.1'
PORT = 2408
TIMEOUT = 3.0

def wg_init():
    p = bytearray(148)
    p[0] = 1
    p[4:8] = os.urandom(4)
    p[8:36] = os.urandom(28)
    p[36:64] = os.urandom(28)
    p[68:100] = os.urandom(32)
    p[100:132] = os.urandom(32)
    return bytes(p)

def tls_wrap(payload):
    """A TLS 1.3 application_data record carrying the handshake as its body."""
    header = struct.pack('>BHH', 0x17, 0x0303, len(payload))   # type 23, TLS1.2, length
    return header + payload

def send_and_wait(data, label, trials=3):
    for _ in range(trials):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(TIMEOUT)
        try:
            s.sendto(data, (HOST, PORT))
            d, a = s.recvfrom(2048)
            return '%s -> ANSWERED %dB from %s type=%d' % (label, len(d), a[0], d[0])
        except socket.timeout:
            continue
        except ConnectionResetError:
            return '%s -> ICMP unreachable' % label
        except OSError as e:
            return '%s -> %s' % (label, e)
        finally:
            s.close()
    return '%s -> silent after %d trials' % (label, trials)

print('control: a genuine DNS query to 1.1.1.1 (is UDP egress alive at all?)')
q = bytearray(29); q[0:2] = b'\x12\x34'; q[2] = 1; q[5] = 1
for lbl, part in (('api', 3), ('telegram', 8), ('org', 3)):
    q.append(part); q += lbl.encode()
q += bytes([0, 0, 1, 0, 1])
print('  ', send_and_wait(bytes(q), 'udp53'))

print()
print('the experiment, same address and port:')
print('  ', send_and_wait(wg_init(), 'bare WireGuard initiation'))
print('  ', send_and_wait(tls_wrap(wg_init()), 'same initiation inside a TLS record'))

# A TLS record with a plausible payload length, which is what a real client would send.
print('  ', send_and_wait(tls_wrap(os.urandom(180)), 'TLS record, random body'))
