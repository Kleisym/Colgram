"""Which Cloudflare UDP ports answer from here, and what that tells us about the block.

Why this exists, and what it found.

The standing conclusion was that "Cloudflare's WireGuard UDP is filtered on this network". That
is true of the ports WARP advertises, but it is wider than the evidence supports, and the difference
matters: a block on every Cloudflare UDP destination is a different problem from a block on four
ports of one service, and only the second is something a different transport could work around.

So this sweeps the ports Cloudflare is actually reachable on - the WireGuard ones, the QUIC/HTTP3
one, and the DoH resolver port - and reports which answer. The first run of it found that 443/udp
ANSWERS while 2408/udp does not, on the same address. That is a port-specific block, not a host
block, and it is the most useful fact found about this network in a while: Cloudflare's QUIC path
is open here even though Cloudflare's WireGuard path is not.

Every answer is paired with the DNS control, because a silent result from a network with no UDP
egress at all is not a finding.

Usage:
    python scripts/warp-port-sweep.py
    python scripts/warp-port-sweep.py --host 162.159.192.1
"""
from __future__ import annotations

import argparse
import socket
import sys
import time

TIMEOUT = 2.5

INGRESSES = ["162.159.192.1", "162.159.193.1", "188.114.96.1", "188.114.97.1"]

# What each port is FOR, because "443 answers" only means something next to "2408 does not".
PORTS = [
    (2408, "WARP / WireGuard (the registered ingress)"),
    (500, "WARP / WireGuard alternate"),
    (1701, "WARP / WireGuard alternate"),
    (4500, "WARP / WireGuard alternate (NAT-T in some configs)"),
    (859, "WARP / WireGuard alternate"),
    (934, "WARP / WireGuard alternate"),
    (2409, "WARP / WireGuard alternate"),
    (443, "QUIC / HTTP3 - not WireGuard, but the same edge over UDP"),
    (80, "plain HTTP over UDP probe"),
    (8443, "alt HTTPS"),
    (2053, "DoH"),
    (2083, "DoH alt"),
    (2087, "DoT alt"),
    (2096, "DoT"),
    (51820, "WireGuard (the common default)"),
    (1280, "free-form probe"),
]


def control_dns(server: str = "1.1.1.1") -> bool:
    """A real DNS query. A silent control makes every other answer meaningless."""
    labels = b"".join(bytes([len(p)]) + p.encode() for p in "cloudflare.com".split("."))
    query = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + labels + b"\x00\x00\x01\x00\x01"
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(TIMEOUT)
    try:
        sock.sendto(query, (server, 53))
        reply, _ = sock.recvfrom(2048)
        return len(reply) > 12 and (reply[2] & 0x80) != 0
    except OSError:
        return False
    finally:
        sock.close()


def probe(host: str, port: int) -> bool:
    """True when a datagram of WireGuard-initialiation size comes back at all.

    The payload is not a valid handshake and does not need to be: the question is whether the
    destination is reachable by UDP, and anything at all coming back answers it. A QUIC endpoint
    replies to a short garbage datagram with a version-negotiation or a close, and a WireGuard one
    stays silent unless it is filtering - so "answered" and "silent" are both informative.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(TIMEOUT)
    try:
        sock.sendto(b"\x00" * 1200, (host, port))
        sock.recvfrom(2048)
        return True
    except OSError:
        return False
    finally:
        sock.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default=None, help="sweep one address instead of all four")
    args = parser.parse_args()
    hosts = [args.host] if args.host else INGRESSES

    control = control_dns()
    print("control: DNS to 1.1.1.1 -> " + ("answers" if control else "SILENT"))
    if not control:
        print("UDP egress does not work here, so nothing below would mean anything.")
        return 1

    answered, silent = [], []
    for host in hosts:
        print("\n" + host)
        for port, what in PORTS:
            if probe(host, port):
                answered.append((host, port))
                print(f"  {port:>6} ANSWERED   {what}")
            else:
                silent.append((host, port))
                print(f"  {port:>6} silent     {what}")

    print(f"\nanswered {len(answered)}, silent {len(silent)}")
    wireguard_open = [p for _, p in answered if p in (2408, 500, 1701, 4500, 859, 934, 2409, 51820)]
    quic_open = [p for _, p in answered if p == 443]
    if quic_open and not wireguard_open:
        print("\nThis is the useful shape: Cloudflare's QUIC port answers while every WireGuard port"
              "\nis silent, on the same address. So the block is PORT-SPECIFIC, not a blanket"
              "\nblock on Cloudflare, and Cloudflare's edge IS reachable over UDP from here.")
        print("What that does NOT do is make WARP work: WARP speaks WireGuard, not QUIC, so the"
              "\nopen port cannot carry its handshake. It does mean the standing claim that ALL"
              "\nCloudflare UDP is filtered was too wide, and a relay is still the only route to"
              "\nWARP itself.")
    elif wireguard_open:
        print("\nA WireGuard port ANSWERS from here. That contradicts the standing conclusion, and"
              "\nthe next step is a real handshake rather than a reachability probe.")
    else:
        print("\nNothing on Cloudflare answers by UDP here, ports included.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
