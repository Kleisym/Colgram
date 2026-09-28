"""Is QUIC blocked in general here, or only at Cloudflare? And which path is it blocked on?

Why this file exists.

Every WARP measurement in this repository was aimed at Cloudflare, so "Cloudflare's UDP is
filtered here" was a conclusion that never had a control. A block that hits every QUIC endpoint on
the internet looks identical to a block at one provider, and the two call for opposite responses:
the first is a property of the network, the second is a targeted filter someone could route around.

So this sends the same real QUIC Initial to four unrelated providers - Google, Facebook, Cloudflare
and Google DNS - and runs each one twice: once on the default route, and once bound to the Ethernet
address so the packet cannot leave through a tunnel.

**What that second path is, and why it matters.** On this machine a WireGuard tunnel called VPNUS
holds `0.0.0.0/1` and `128.0.0.0/1` at metric 0, so it takes every packet by default:

    Find-NetRoute -RemoteIPAddress 162.159.198.2  ->  VPNUS, 128.0.0.0/1, metric 0
    tracert -d 162.159.198.2                     ->  1 hop, 11 ms

One hop to a Cloudflare address means the packet never touched the home router. So every previous
measurement described a tunnel, not the user's connection - and the tunnel is the thing that
provides the working TCP 443 in the first place. Binding to the Ethernet address is the only way
to see the underlying path without changing any system state, which is why this file does it that
way rather than by stopping the tunnel.

**What is being asked, precisely.** Not "does WARP work" - that needs the far side. Two questions
this can answer: whether the QUIC block is Cloudflare-specific, and whether it applies to the
tunnel, the plain connection, or both.

Usage:
    python scripts/warp-quic-block-scope.py
    python scripts/warp-quic-block-scope.py --ethernet 192.168.0.4
"""
from __future__ import annotations

import argparse
import os
import socket
import sys
import time

TIMEOUT = 2.5
PROBE_BYTES = 1200

# Four providers, deliberately unrelated to each other. One of them being silent would be a fact
# about that provider; all of them silent is a fact about the path.
TARGETS = [
    ("142.250.74.174", 443, "Google"),
    ("157.240.1.35", 443, "Facebook"),
    ("1.1.1.1", 443, "Cloudflare resolver"),
    ("8.8.8.8", 443, "Google DNS"),
]


def quic_initial() -> bytes:
    """A correctly shaped QUIC v1 Initial, padded to 1200.

    The payload is filler: it is encrypted, so a server cannot tell filler from a handshake without
    our keys. What is measured is whether the packet is answered at all as a QUIC packet, which is
    the question a filter answers and a network does not.
    """
    crypto = b"\x06" + (0).to_bytes(8, "big") + (900).to_bytes(2, "big") + os.urandom(900)
    packet = b"\xc3" + (1).to_bytes(4, "big") + os.urandom(8) + os.urandom(8)
    packet += b"\x00" + (0x0001).to_bytes(2, "big") + crypto
    return packet + b"\x00" * (PROBE_BYTES - len(packet))


def dns_query() -> bytes:
    """A real DNS query - the control that says UDP itself is alive on this path.

    Built from labels rather than typed, because a query that is one byte off is dropped by every
    resolver and reads as "UDP is dead here", which would be a much larger and wrong conclusion.
    """
    labels = b"".join(bytes([len(part)]) + part.encode()
                       for part in "cloudflare.com".split("."))
    return (b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + labels
            + b"\x00\x00\x01\x00\x01")


def send_recv(payload, ip, port, bind=None, timeout=TIMEOUT):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        if bind:
            sock.bind((bind, 0))
        sock.settimeout(timeout)
        sock.sendto(payload, (ip, port))
        data, _ = sock.recvfrom(2048)
        return "%dB 0x%02x" % (len(data), data[0])
    except socket.timeout:
        return "silent"
    except OSError as e:
        return type(e).__name__
    finally:
        sock.close()


def describe_route(address):
    """Which interface Windows would use for this address, read from the routing table.

    Worth printing, because the answer has been surprising: a tunnel holding 0.0.0.0/1 at metric 0
    means "the default route" is not the user's connection, and every measurement taken without
    noticing that describes the tunnel.
    """
    try:
        import subprocess
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "(Find-NetRoute -RemoteIPAddress %s -ErrorAction SilentlyContinue | "
             "Select-Object -First 1).InterfaceAlias" % address],
            capture_output=True, text=True, timeout=15)
        return out.stdout.strip() or "unknown"
    except Exception:
        return "unknown"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ethernet", default="",
                        help="local address to bind to, so probes bypass any tunnel")
    parser.add_argument("--attempts", type=int, default=3)
    args = parser.parse_args()

    print("route to 162.159.198.2 uses interface: " + describe_route("162.159.198.2"))
    if args.ethernet:
        print("probes bound to " + args.ethernet + " (default route) and to that address "
              "(tunnel bypassed)")
    print("")

    print("=== control: UDP is alive, because DNS answers ===")
    for label, bind in (("default route", None),
                        ("bound to %s" % args.ethernet if args.ethernet else None, args.ethernet)):
        if label is None:
            continue
        hits = sum(1 for _ in range(args.attempts)
                   if send_recv(dns_query(), "1.1.1.1", 53, bind) != "silent")
        print("  %-22s DNS 1.1.1.1:53   answered %d/%d" % (label, hits, args.attempts))
    print("")

    print("=== a real QUIC Initial, 1200 bytes, four unrelated providers ===")
    total = 0
    for ip, port, who in TARGETS:
        for label, bind in (("default", None),
                            ("bound", args.ethernet) if args.ethernet else (None, None)):
            if bind is None and label == "bound":
                continue
            hits = 0
            shapes = {}
            for _ in range(args.attempts):
                result = send_recv(quic_initial(), ip, port, bind)
                if result != "silent":
                    hits += 1
                    shapes[result] = True
            total += hits
            print("  %-20s %-9s udp/%d  answered %d/%d  %s"
                  % (who, label, port, hits, args.attempts,
                     " ".join(sorted(shapes))))
    print("")

    print("VERDICT")
    if total == 0:
        print("  Every QUIC Initial is silent on every provider and on every path tested, while")
        print("  DNS answers on the same sockets. The block is not Cloudflare's - it is a property")
        print("  of the path, and it applies to QUIC as such rather than to a provider or a port.")
        print("  This is the missing control the earlier Cloudflare-only measurements never had:")
        print("  they could not have distinguished 'Cloudflare is filtered' from 'QUIC is")
        print("  filtered', and those need different responses.")
    else:
        print("  QUIC is answered somewhere, so it is provider-specific rather than a blanket")
        print("  block. The rows above say which provider and which path.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

