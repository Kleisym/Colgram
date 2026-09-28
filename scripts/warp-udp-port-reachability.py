"""Which UDP ports answer at all, and what answers - the control the 2408 silence never had.

Why this file exists.

"2408 is silent" has been this project's constant for a long time, and it was read as "the
WireGuard port is filtered". That reading needs a control: is the port silent because nothing is
listening on it, or because UDP does not reach the address at all? Those are different facts, and
only one of them is about filtering. Sweeping ports with a payload nothing answers and reporting
"silent" cannot tell them apart - and the one host in the sweep that *did* answer on a non-DNS port
turned out to answer in a way that changed the conclusion.

**That host.** `208.67.222.222` resolves to `dns.sse.cisco.com`, and it answers on 53, 5353 and
443 - three different ports, which is already a hint that a "443 answers" reading means nothing
about 443 being a service. Sending it a real QUIC Initial:

    12B  c30080810000000000000000

Six different Initials, byte-identical answer every time. Parsed: long header, fixed bit set,
version `0x00808100`, DCID length 0, SCID length 0. A QUIC server replies to an Initial with an
Initial or a Retry - either carries a real version (`0x00000001` or a version-negotiation list) and
non-zero connection ids. `0x00808100` is not a QUIC version, and zero-length ids with all-zero
payload is the shape of a stub. It is a resolver that holds other ports open and does not speak
QUIC on them.

The same sweep against Cloudflare's own ingress is unambiguous:

    162.159.192.1  udp/53 80 443 2408 500 4500 8080 8443 5000 51820 65535   all silent

Eleven ports, including 53, with a real DNS query. So UDP does not reach that address on any port
tested, and the silence is about the path rather than about 2408 being chosen.

**Why this matters for the conclusion.** The honest statement is narrower and stronger than "2408
is filtered": on this network, UDP to Cloudflare's WireGuard and MASQUE addresses does not arrive at
all, while UDP to resolvers does, and TCP 443 to the same Cloudflare addresses completes TLS 1.3
with h2. A relay on a host without that filter remains the only route, and the case for it does not
rest on a reading of which port is shut.

Usage:
    python scripts/warp-udp-port-reachability.py
    python scripts/warp-udp-port-reachability.py --host 162.159.198.2
"""
from __future__ import annotations

import argparse
import os
import socket
import sys
import time

TIMEOUT = 2.0
PORTS = [53, 80, 443, 123, 853, 2408, 500, 1701, 3478, 4500, 5353, 8080, 8443, 5000, 51820, 65535]

# The hosts that answered on a non-DNS port when the project assumed only Cloudflare was worth
# measuring. Kept in the tool rather than in a note, because the assumption is what was wrong.
INTERESTING = [
    ("162.159.192.1", "Cloudflare WireGuard ingress"),
    ("162.159.198.2", "Cloudflare MASQUE ingress"),
    ("1.1.1.1", "Cloudflare resolver"),
    ("208.67.222.222", "dns.sse.cisco.com - answers 443 with a stub"),
]


def dns_query() -> bytes:
    """A real DNS query. Built from labels rather than typed, because a query one byte out is
    dropped by every resolver and reads as "UDP is dead here"."""
    labels = b"".join(bytes([len(part)]) + part.encode()
                       for part in "cloudflare.com".split("."))
    return (b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + labels
            + b"\x00\x00\x01\x00\x01")


def quic_initial() -> bytes:
    """A real QUIC v1 Initial, 1200 bytes. Filler inside, since the payload is encrypted."""
    crypto = b"\x06" + (0).to_bytes(8, "big") + (900).to_bytes(2, "big") + os.urandom(900)
    packet = b"\xc3" + (1).to_bytes(4, "big") + os.urandom(8) + os.urandom(8)
    packet += b"\x00" + (0x0001).to_bytes(2, "big") + crypto
    return packet + b"\x00" * (1200 - len(packet))


def probe(ip, port, payload, timeout=TIMEOUT):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(timeout)
    try:
        sock.sendto(payload, (ip, port))
        data, _ = sock.recvfrom(2048)
        return data
    except socket.timeout:
        return None
    except OSError as e:
        return type(e).__name__
    finally:
        sock.close()


def is_quic(data) -> bool:
    """Whether a reply is a QUIC packet at all, rather than something that merely arrived.

    A server answers an Initial with an Initial or a Retry, and both carry a real version - 1 for
    the ones in use, or a version-negotiation list - and non-zero connection ids. A stub that
    repeats one fixed answer for every input fails this, which is the whole reason it exists.
    """
    if not isinstance(data, bytes) or len(data) < 7:
        return False
    if not (data[0] & 0x80):
        return False
    version = int.from_bytes(data[1:5], "big")
    if version not in (0, 1):
        return False
    return data[5] != 0


def describe(data) -> str:
    if data is None:
        return "silent"
    if isinstance(data, str):
        return data
    if is_quic(data):
        return "%dB REAL QUIC" % len(data)
    version = int.from_bytes(data[1:5], "big") if len(data) >= 5 else 0
    return "%dB stub (version 0x%08x, dcid len %d)" % (
        len(data), version, data[5] if len(data) > 5 else -1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="")
    parser.add_argument("--ports", default="")
    args = parser.parse_args()

    hosts = [(args.host, "target")] if args.host else INTERESTING
    ports = [int(p) for p in args.ports.split(",")] if args.ports else PORTS

    for ip, who in hosts:
        try:
            ptr = socket.gethostbyaddr(ip)[0]
        except Exception:
            ptr = "no PTR"
        print("=== %s  (%s) ===" % (ip, ptr if who == "target" else who))

        reachable = []
        for port in ports:
            data = probe(ip, port, dns_query())
            if data is not None and not isinstance(data, str):
                reachable.append(port)
            print("  udp/%-6d %s" % (port, describe(data)))
            sys.stdout.flush()
        print("  ports that answered at all: %s"
              % (", ".join(str(p) for p in reachable) if reachable else "none"))
        print("")

    print("=== and a real QUIC Initial at the port that answered ===")
    for ip, _ in hosts:
        for port in ports:
            data = probe(ip, port, quic_initial())
            if data is not None and not isinstance(data, str):
                print("  %s udp/%-5d %s" % (ip, port, describe(data)))
    print("")
    print("VERDICT")
    print("  A port that answers a DNS query but answers a QUIC Initial with a fixed stub is not")
    print("  running QUIC - it is holding the port open. Reading that as 'the QUIC port is")
    print("  reachable' is the mistake this file exists to make impossible, and it is the same")
    print("  mistake as counting a 31-byte stateless reset as a service.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

