"""How large must a datagram be before this network lets an answer back at all?

Why this exists: it caught an overclaim.

The port sweep sent 1200-byte datagrams and concluded that 443/udp answers while every WireGuard
port is silent, then generalised that to "this network allows only a small allowlist of UDP ports".
That conclusion was drawn from a probe that never varied its payload, and it does not survive
being varied: 1.1.1.1:443 is silent to 64, 256 and 600 byte datagrams and answers a 1200 byte one
with a 31 byte QUIC version negotiation. Nothing about the port changed - only the size did.

So there is a size threshold under everything, and it has to be characterised before any port
comparison means anything. A WireGuard message-initiation is 148 bytes, which is BELOW the threshold
that 443 needs - so "2408 is silent" may be saying as much about the probe as about the filter,
and a 1200-byte probe is the only fair way to compare ports.

This measures the threshold itself, on a port that definitely answers, and then re-tests
Cloudflare's WireGuard ports at a size that is known to get through.

Usage:
    python scripts/warp-mtu-threshold.py
"""
from __future__ import annotations

import socket
import sys
import time

TIMEOUT = 3.0

# 1.1.1.1:443 is QUIC and answers a large enough datagram, so it is the control for the threshold
# question: same host, same port, only the size varies.
CONTROL = ("1.1.1.1", 443)

SIZES = [64, 256, 512, 600, 700, 800, 1000, 1200]

# A WireGuard message-initiation is 148 bytes, and a handshake response is 148 too. Both are well
# under what 443 needs, which is the whole reason a naive probe cannot see a live WireGuard port.
WIREGUARD_MESSAGE = 148
PROBE_SIZE = 1200

INGRESSES = ["162.159.192.1", "162.159.193.1", "188.114.96.1", "188.114.97.1"]
PORTS = [2408, 500, 1701, 4500, 859, 934, 2409, 51820]


def probe(host: str, port: int, size: int, timeout: float = TIMEOUT) -> bool:
    """True when anything comes back. Non-blocking with a wall-clock deadline, never a hang."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setblocking(False)
    try:
        sock.sendto(b"\x00" * size, (host, port))
    except OSError:
        sock.close()
        return False
    deadline = time.time() + timeout
    try:
        while time.time() < deadline:
            try:
                sock.recvfrom(2048)
                return True
            except BlockingIOError:
                time.sleep(0.04)
            except OSError:
                return False
        return False
    finally:
        sock.close()


def main() -> int:
    print("=== the size threshold, on a port that answers ===")
    print(f"  {CONTROL[0]}:{CONTROL[1]}  (QUIC - answers a large enough datagram)")
    smallest = None
    for size in SIZES:
        answered = probe(*CONTROL, size)
        print(f"    {size:>5} bytes  {'ANSWERED' if answered else 'silent'}")
        if answered and smallest is None:
            smallest = size

    print()
    if smallest is None:
        print("  Nothing answered at any size, so UDP egress does not work here and the rest of")
        print("  this measurement would mean nothing.")
        return 1
    print(f"  Smallest size that gets an answer: {smallest} bytes.")
    print(f"  A WireGuard message-initiation is {WIREGUARD_MESSAGE} bytes - "
          + ("BELOW" if WIREGUARD_MESSAGE < smallest else "at or above")
          + " that threshold, so a small-packet probe cannot see a live WireGuard port.")

    print(f"\n=== Cloudflare WireGuard ports at {PROBE_SIZE} bytes ===")
    print("  (a size known to get through on 443, so the comparison is fair)")
    any_open = False
    for host in INGRESSES:
        row = []
        for port in PORTS:
            opened = probe(host, port, PROBE_SIZE, 2.0)
            any_open = any_open or opened
            row.append(f"{port}:{'YES' if opened else 'no'}")
        print(f"  {host}  " + "  ".join(row))
        sys.stdout.flush()

    print()
    if any_open:
        print("A WireGuard port ANSWERS at a fair packet size. That contradicts every earlier")
        print("verdict, and the next step is a real handshake rather than a reachability probe.")
    else:
        print("No WireGuard port answers even at a size that gets 443 to reply, so the block is not")
        print("a probe artifact after all - the port really is filtered, and the earlier conclusion")
        print("survives having been tested properly rather than by accident.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
