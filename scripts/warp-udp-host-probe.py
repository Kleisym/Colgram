"""Is Cloudflare's WireGuard ingress reachable by UDP from THIS network?

Why a host-side probe exists at all, given the device already answered this question.

The emulator sits behind 10.0.2.0/24, the QEMU user-mode NAT, and its TCP behaviour is an
artifact of that: `nc -z` reports OPEN for every port on 162.159.192.1 - including 65000, which
cannot be open - and a connect delivers zero bytes. So a TCP result measured inside the emulator
proves nothing about reachability, and an earlier note that "all TCP ports answer" was the
sandbox talking, not the network.

UDP has no such artifact. A datagram either comes back or it does not, and the control probe
next to it is what makes "silent" mean "that destination is filtered" rather than "UDP is
broken everywhere". So this probes from the host, on the same network the phone would use, and
reports both halves: whether a real WireGuard initiation gets an answer, and whether the control
answered.

Usage:
    python scripts/warp-udp-host-probe.py
    python scripts/warp-udp-host-probe.py --host 162.159.192.1 --port 2408
"""
from __future__ import annotations

import argparse
import socket
import struct
import sys
import time

TIMEOUT = 3.0

# Cloudflare's WireGuard ingresses, the addresses the WARP registration hands back.
INGRESSES = ["162.159.192.1", "162.159.193.1", "188.114.96.1", "188.114.97.1"]
PORTS = [2408, 500, 1701, 4500]


def control_dns(server: str = "1.1.1.1") -> bool:
    """A real DNS query. Random bytes to port 53 are dropped by every resolver."""
    # Built from the labels rather than hand-written as a byte string: a header that is one byte
    # long, or a missing root label, produces a query every resolver silently drops - which then
    # reads as "UDP is broken here" and invalidates the whole probe. An earlier version of this
    # file had exactly that bug, and it is the reason the control has to be built, not typed.
    labels = b"".join(bytes([len(part)]) + part.encode()
                       for part in "cloudflare.com".split("."))
    query = (b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + labels
             + b"\x00\x00\x01\x00\x01")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(TIMEOUT)
    try:
        sock.sendto(query, (server, 53))
        reply, _ = sock.recvfrom(512)
        # QR set means it is a response rather than an echo of our own query.
        return len(reply) > 12 and (reply[2] & 0x80) != 0
    except OSError:
        return False
    finally:
        sock.close()


def wireguard_initiation() -> bytes:
    """A correctly shaped message-initiation.

    The type, reserved and sender fields are right, so a responder that is listening and not
    filtering will answer. The rest is filler, because the point is to see whether the packet is
    answered at all - not to complete a handshake, which needs this device's own key pair.
    """
    header = struct.pack("<IB", 1, 0)          # type 1, reserved 0
    sender = bytes(range(32))                   # 32-byte sender, must be non-zero
    ephemeral = bytes(range(32, 64))            # 32-byte ephemeral
    mac1 = b"\x00" * 16
    mac2 = b"\x00" * 16
    return header + sender + ephemeral + mac1 + mac2


def probe(host: str, port: int) -> bool:
    """True when anything at all comes back for a real initiation."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(TIMEOUT)
    try:
        sock.sendto(wireguard_initiation(), (host, port))
        sock.recvfrom(512)
        return True
    except OSError:
        return False
    finally:
        sock.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default=None, help="probe one host instead of the set")
    parser.add_argument("--port", type=int, default=None)
    args = parser.parse_args()

    control = control_dns()
    print("control: DNS to 1.1.1.1 -> " + ("answers" if control else "SILENT"))
    if not control:
        print("UDP egress is broken here, so a silent result below would mean nothing.")
        return 1

    if args.host and args.port:
        targets = [(args.host, args.port)]
    else:
        targets = [(h, p) for h in INGRESSES for p in PORTS]

    answered, silent = [], []
    for host, port in targets:
        if probe(host, port):
            answered.append(f"{host}:{port}")
            print(f"  ANSWERED {host}:{port}")
        else:
            silent.append(f"{host}:{port}")
        time.sleep(0.2)

    print(f"\nanswered {len(answered)} of {len(targets)}")
    if silent:
        print("silent: " + ", ".join(silent))
    if answered:
        print("\nA WireGuard initiation was answered. The ingress is reachable by UDP from here,"
              " so a relay is not the only possible route and the next thing to measure is a"
              " real handshake end to end.")
    else:
        print("\nNo ingress answered a real WireGuard initiation. Cloudflare's WireGuard UDP is"
              " filtered on this network, and no client-side option changes that: WireGuard has"
              " no TCP transport, so there is no port or protocol version that reaches it.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
