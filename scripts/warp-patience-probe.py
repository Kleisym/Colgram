"""Does a patient client ever get a WireGuard packet through, given the filter fluctuates?

Why this is the most useful question left.

Measured on this network: Cloudflare's WireGuard ports are silent, but the filtering is not
constant - the same host and port have answered in one session and not in another, and
`8.8.8.8:443` and `1.1.1.1:443` have swapped places between runs. So "is 2408 blocked" is not
the question. The question is what fraction of attempts get through, because that decides whether
a client that simply keeps retrying would eventually connect - which needs no relay, no tunnel
and no trick, just patience.

The distinction matters for what to build. If the rate is zero, a relay is the only answer. If it
is even a few percent, the right answer is a patient handshake, and it is worth knowing which one
before telling anyone to go buy a VPS.

Every probe is a correctly shaped 1200-byte datagram, because a real WireGuard initiation is 148
bytes and sits BELOW the size floor measured on this network - a 148-byte probe cannot tell a
filtered port from a live one, and every earlier verdict drawn from one was a probe artefact.

Usage:
    python scripts/warp-patience-probe.py --minutes 5
"""
from __future__ import annotations

import argparse
import socket
import sys
import time

PAYLOAD_BYTES = 1200
TIMEOUT = 2.5

TARGETS = [
    ("188.114.96.1", 2408),
    ("162.159.192.1", 2408),
    ("162.159.193.1", 2408),
    ("188.114.97.1", 2408),
]


def control() -> bool:
    """A DNS control, so a run where nothing answers anywhere is not read as a result."""
    labels = b"".join(bytes([len(p)]) + p.encode() for p in "cloudflare.com".split("."))
    query = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + labels + b"\x00\x00\x01\x00\x01"
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(TIMEOUT)
    try:
        sock.sendto(query, ("1.1.1.1", 53))
        reply, _ = sock.recvfrom(2048)
        return len(reply) > 12 and (reply[2] & 0x80) != 0
    except OSError:
        return False
    finally:
        sock.close()


def probe(host: str, port: int, timeout: float = TIMEOUT) -> bool:
    """True when a 1200-byte datagram comes back. Non-blocking, wall-clock bounded."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setblocking(False)
    try:
        sock.sendto(b"\x00" * PAYLOAD_BYTES, (host, port))
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minutes", type=float, default=3.0)
    parser.add_argument("--interval", type=float, default=4.0)
    args = parser.parse_args()

    print("control: DNS to 1.1.1.1 -> " + ("answers" if control() else "SILENT"))
    if not control():
        print("UDP egress does not work right now, so nothing below would mean anything.")
        return 1

    print(f"\nprobing {len(TARGETS)} WireGuard ingresses every {args.interval:.0f}s"
          f" for {args.minutes:.1f} minutes, {PAYLOAD_BYTES}-byte datagrams")
    hits = {target: 0 for target in TARGETS}
    rounds = 0
    deadline = time.time() + args.minutes * 60
    while time.time() < deadline:
        for target in TARGETS:
            if probe(*target):
                hits[target] += 1
        rounds += 1
        print(f"  round {rounds}: "
              + "  ".join(f"{t[0]}:{t[1]}={hits[t]}" for t in TARGETS), flush=True)
        time.sleep(args.interval)

    total = sum(hits.values())
    attempts = rounds * len(TARGETS)
    print(f"\nanswered {total} of {attempts} attempts over {args.minutes:.1f} minutes")
    for target, count in hits.items():
        print(f"  {target[0]}:{target[1]}  {count}/{rounds}")
    if total == 0:
        print("\nNo WireGuard packet got through in this window. The filter may be moving - this is")
        print("a sample, not a proof - but on the evidence so far a relay remains the only route,")
        print("and patience alone does not get there.")
    else:
        print(f"\n{total} of {attempts} got through, so the port is REACHABLE SOMETIMES. A patient")
        print("client that keeps retrying would eventually connect, and no relay would be needed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
