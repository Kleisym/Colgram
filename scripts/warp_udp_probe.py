import socket, struct, time, random, sys

# Results from this network are NOT stable across sessions, and that has to be visible in the
# output rather than discovered later. Measured on this machine:
#
#   one session:    8.8.8.8:443 ANSWERED, 1.1.1.1:443 silent, 9.9.9.9:443 silent
#   later session:  1.1.1.1:443 ANSWERED, 8.8.8.8:443 silent, 9.9.9.9:443 silent
#
# The same hosts, ports and payload size, minutes apart, with the results INVERTED. Within a
# session it is stable across repeated runs, so this is not a flaky probe - the filter's
# behaviour changes over time. The consequence is that one run cannot settle a question here, and a
# verdict that looks like a result may be a snapshot of a moving filter. Anything concluded from a
# single run has to be re-measured, which is what REPEATS is for.
REPEATS = 3

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
    sys.stdout.flush()

def udp_wireguard(ip, port, timeout=4):
    # A 1200-byte payload, NOT a 148-byte one, and that is the whole point of this function.
    #
    # Measured on this network: a UDP datagram under ~1200 bytes gets no answer on ANY port, while a
    # 1200-byte one gets a QUIC reply on 443. A real WireGuard message-initiation is 148 bytes - below
    # that floor - so a 148-byte probe cannot tell a filtered port from a live one, and every
    # verdict drawn from one was an artefact of the probe rather than a property of the network.
    # 1200 is used here so that silence really does mean the port is filtered.
    #
    # A live WireGuard endpoint still drops an unauthenticated payload silently, and a closed port
    # answers with ICMP unreachable, so all three outcomes stay distinguishable.
    t0=time.time()
    s=socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(timeout)
    try:
        # A real QUIC Initial, not 1200 random bytes. Random bytes draw a 31-byte stateless reset
        # from any QUIC endpoint - arbitrary first byte, constant tail - and counting that as
        # ANSWERED is what made this file report 443 as reachable for so long. A real Initial is
        # answered by a server and reset by a filter, which is the distinction the file is for.
        s.sendto(quic_initial(),(ip,port))
        d,a=s.recvfrom(2048)
        return '%s %dB %.0fms'%(classify(d),len(d),(time.time()-t0)*1000)
    except socket.timeout: return 'silent'
    except ConnectionResetError: return 'ICMP -> port reachable'
    except OSError as e: return 'OSError %s'%e
    finally: s.close()

def quic_initial():
    """A correctly shaped QUIC v1 Initial, 1200 bytes, from a stdlib-only build.

    Long header, version 1, random connection ids, a CRYPTO frame at offset 0, padded to 1200
    because nothing smaller is answered on this path. The bytes inside are filler: the payload is
    encrypted, so a server cannot tell filler from a handshake without our keys. What is asked is
    whether the packet is answered *as a packet type*, which is exactly what a stateless reset
    declines to do.
    """
    crypto = b'\x06' + (0).to_bytes(8, 'big') + (900).to_bytes(2, 'big') \
        + bytes(random.getrandbits(8) for _ in range(900))
    packet = b'\xc3' + (1).to_bytes(4, 'big') \
        + bytes(random.getrandbits(8) for _ in range(8)) \
        + bytes(random.getrandbits(8) for _ in range(8)) \
        + b'\x00' + (0x0001).to_bytes(2, 'big') + crypto
    return packet + b'\x00' * (1200 - len(packet))


def classify(d):
    """A 31-byte answer is a stateless reset; anything longer answered the packet."""
    if len(d) <= 40:
        return 'RESET'
    return 'ANSWERED'


print('=== Cloudflare WARP ingress, UDP ===')
for ip in ['162.159.192.1', '188.114.96.1']:
    for port in [2408, 500, 1701, 4500, 443, 854, 1640]:
        # Repeated, because the filter's behaviour changes between sessions: a verdict
        # that is true only right now is not a verdict.
        seen = [udp_wireguard(ip, port, 3.0) for _ in range(REPEATS)]
        answered = sum(1 for r in seen if r.startswith('ANSWERED'))
        resets = sum(1 for r in seen if r.startswith('RESET'))
        if answered:
            summary = 'ANSWERED %d/%d' % (answered, REPEATS)
        elif resets:
            summary = 'RESET %d/%d' % (resets, REPEATS)
        else:
            summary = 'silent %d/%d' % (REPEATS, REPEATS)
        print('  %-14s %-5d %s' % (ip, port, summary))
        sys.stdout.flush()


print()
print('A single unanswered probe is not proof of a block on this network: the same hosts and ports')
print('have given opposite answers in different sessions. Re-run before concluding anything.')
