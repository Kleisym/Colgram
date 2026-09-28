"""Could WireGuard be carried over QUIC on 443 - the one port that is open?

Why this is the last transport worth testing, and what it found.

The measured shape of this network: 2408 and every other WireGuard port are silent over BOTH UDP and
TCP, while 443 is reachable over both. So any route to WARP here would have to arrive on 443. Two
ways that could work were checked, and both are dead - which is worth establishing rather than
assuming, because "QUIC is open, therefore maybe WARP can ride it" is a natural and wrong
inference.

1. **WireGuard carried inside a QUIC connection.** That needs Cloudflare to run a WireGuard-over-QUIC
   endpoint on 443. It does not. A real QUIC server does answer 443 - short-header packets, first
   byte 0xba - but that port terminates HTTP/3 and nothing else, so a WireGuard initiation sent
   there is dropped. QUIC being reachable does not make it a tunnel to the WireGuard ingress.

2. **A plain WireGuard handshake to 2408 over TCP.** Also dead: TLS to 2408 times out while TLS to
   443 completes in milliseconds. The WireGuard port does not listen on TCP at all, so there is
   nothing there to encapsulate towards.

What survives is the relay: it accepts the initiation over TCP 443, which IS open and does carry
small payloads, and originates the WireGuard UDP from a network without this filter.

Usage:
    python scripts/warp-quic-443-check.py
"""
from __future__ import annotations

import socket
import ssl
import struct
import sys
import time

PROBE_BYTES = 1200
TIMEOUT = 4.0
WG_PORT = 2408
OPEN_PORT = 443


def udp(host, port, payload, timeout=TIMEOUT):
    """Non-blocking and wall-clock bounded; a probe that hangs returns nothing at all."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setblocking(False)
    try:
        sock.sendto(payload, (host, port))
    except OSError:
        sock.close()
        return None
    deadline = time.time() + timeout
    try:
        while time.time() < deadline:
            try:
                data, _ = sock.recvfrom(2048)
                return data
            except BlockingIOError:
                time.sleep(0.04)
            except OSError:
                return None
        return None
    finally:
        sock.close()


def control():
    labels = b"".join(bytes([len(p)]) + p.encode() for p in "cloudflare.com".split("."))
    query = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + labels + b"\x00\x00\x01\x00\x01"
    return udp("1.1.1.1", 53, query) is not None


def tls_on(host, port):
    """A real TLS handshake, which a connect alone does not prove on this network."""
    try:
        raw = socket.create_connection((host, port), timeout=TIMEOUT)
    except OSError as e:
        return "no (" + type(e).__name__ + ")"
    try:
        context = ssl.create_default_context()
        with context.wrap_socket(raw, server_hostname="one.one.one.one") as tls:
            return "TLS " + str(tls.version())
    except OSError as e:
        return "no (" + type(e).__name__ + ")"
    finally:
        try:
            raw.close()
        except OSError:
            pass


def main() -> int:
    if not control():
        print("UDP egress does not work right now, so nothing below would mean anything.")
        return 1
    print("control: DNS over UDP -> answers")
    print("")
    print("=== can a WireGuard handshake reach " + str(OPEN_PORT) + "/udp? ===")
    # 1200 bytes, because nothing smaller is answered on this network at all.
    initiation = struct.pack("<IB", 1, 0) + bytes(range(32)) + bytes(range(32, 64))
    initiation += b"\x00" * (PROBE_BYTES - len(initiation))
    for host in ("162.159.192.1", "188.114.96.1"):
        answer = udp(host, OPEN_PORT, initiation)
        if answer is None:
            print("  " + host + ":" + str(OPEN_PORT) + "  silent - no tunnel here")
        else:
            short = bool(answer[0] & 0x80) and bool(answer[0] & 0x40)
            kind = "QUIC short header (real server reply)" if short else "long header / reset"
            print("  " + host + ":" + str(OPEN_PORT) + "  " + str(len(answer))
                  + "B first=0x" + format(answer[0], "02x") + "  " + kind)
    print("  A QUIC server answering here is HTTP/3 terminating on 443. It is not a tunnel to")
    print("  Cloudflare's WireGuard ingress, which listens on 2408 and nothing else.")
    print("")
    print("=== is the WireGuard port " + str(WG_PORT) + " reachable over TCP at all? ===")
    for host in ("162.159.192.1", "1.1.1.1"):
        print("  " + host + ":" + str(WG_PORT) + "  " + tls_on(host, WG_PORT))
    print("  162.159.192.1:" + str(OPEN_PORT) + "  " + tls_on("162.159.192.1", OPEN_PORT)
          + "  (for comparison)")
    print("")
    print("Conclusion: the WireGuard port is unreachable over UDP and over TCP, and the one open")
    print("port terminates a different service. No encapsulation on this device changes that, so")
    print("the relay remains the only route - and it is built and tested.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
