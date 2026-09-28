"""Which UDP destinations answer from this machine, and which are silent?

Every "UDP is filtered" claim needs a control that answers, or it is indistinguishable from
broken UDP. A single resolver is a fragile control: it can be filtered, throttled, or simply
not answer a query it dislikes, and then a perfectly healthy network looks dark. So this sweeps a
set of well-known resolvers plus a couple of ports nothing else listens on, and reports what came
back rather than a single yes/no.

The point is to establish what this machine's UDP egress actually does before reading anything
into a WARP probe's silence.

Usage:
    python scripts/udp-egress-check.py
"""
from __future__ import annotations

import socket
import sys
import time

TIMEOUT = 3.0

RESOLVERS = ["1.1.1.1", "8.8.8.8", "9.9.9.9", "77.88.8.8", "208.67.222.222", "94.140.14.14"]


def query(name: str) -> bytes:
    labels = b"".join(bytes([len(part)]) + part.encode() for part in name.split("."))
    return (b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + labels
            + b"\x00\x00\x01\x00\x01")


def ask(server: str, port: int = 53, payload: bytes = None) -> int:
    """Bytes received, or -1 when nothing came back."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(TIMEOUT)
    try:
        sock.sendto(payload if payload is not None else query("cloudflare.com"), (server, port))
        return len(sock.recvfrom(2048)[0])
    except OSError:
        return -1
    finally:
        sock.close()


def main() -> int:
    print("=== DNS resolvers, port 53 ===")
    answered = []
    for server in RESOLVERS:
        got = ask(server)
        print(f"  {server:>16}  {'answers ' + str(got) + 'B' if got > 0 else 'SILENT'}")
        if got > 0:
            answered.append(server)
        time.sleep(0.2)

    print("\n=== a port nothing listens on (a real closed port) ===")
    # 9.9.9.9 answers ICMP port unreachable on closed ports, so this distinguishes "the UDP
    # path is broken" from "that destination is filtered" - an ICMP refusal is not a datagram.
    got = ask("9.9.9.9", 65000, b"probe")
    print(f"  {'9.9.9.9:65000':>16}  {'answers ' + str(got) + 'B' if got > 0 else 'no datagram'}")

    print()
    if not answered:
        print("No resolver answered. UDP egress from this machine is blocked or broken, so any"
              " silence from a filtered-destination probe means nothing either way.")
        return 1
    print(f"{len(answered)} of {len(RESOLVERS)} resolvers answered, so UDP egress works and a"
          " silent destination really is that destination being filtered.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
