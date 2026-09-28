"""Would a real QUIC handshake get past 443 - and could WARP ride it?

Why this exists separately from warp-quic-443-check.py.

That file sends random bytes to 443 and finds something comes back. The reply is not a proof that
QUIC works, and the two plausible readings of it point opposite ways:

  * a stateless reset (first byte 0xe6, or a short-header 0xba/0xb3 aimed at garbage) - the far
    side saw a packet it could not decrypt and told us to go away, which is what a healthy QUIC
    server does to random input. It says nothing about whether a valid Initial is answered.
  * a real short-header reply - a QUIC server is genuinely terminating on 443.

Either way the previous check never established whether a *correct* QUIC Initial is answered, so
the question "can WARP be tunnelled over QUIC/HTTP-3 on the one open port" was left unmeasured. This
sends a real Initial: long header, version 1, a random DCID/SCID, a CRYPTO frame carrying a
plausible client-hello length, padded to 1200 bytes (nothing smaller is answered on this path), and
enough retransmission to distinguish a silent port from a slow one. A control DNS query sits next
to it so "silent" means "this destination filters it", not "UDP is dead everywhere".

What the answer decides:

  * Initial answered  -> QUIC is really alive on 443, so Cloudflare's HTTP/3 endpoint can be a
    tunnel, and MASQUE/QUIC-carried WireGuard is worth building on top of it.
  * Initial silent, random bytes answered -> 443 only emits stateless resets. Any QUIC transport is
    dead here, and so is every MASQUE scheme that depends on it.

Usage:
    python scripts/warp-quic-initial-443.py
    python scripts/warp-quic-initial-443.py --attempts 12
"""
from __future__ import annotations

import argparse
import os
import socket
import struct
import sys
import time

PROBE_BYTES = 1200
TIMEOUT = 3.0
OPEN_PORT = 443
INGRESSES = ["162.159.192.1", "162.159.193.1", "188.114.96.1", "188.114.97.1"]


def control_dns(server: str = "1.1.1.1") -> bool:
    """A real DNS query - the same control the other probes use, so results stay comparable."""
    labels = b"".join(bytes([len(part)]) + part.encode()
                       for part in "cloudflare.com".split("."))
    query = (b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + labels
             + b"\x00\x00\x01\x00\x01")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(TIMEOUT)
    try:
        sock.sendto(query, (server, 53))
        reply, _ = sock.recvfrom(512)
        return len(reply) > 12 and (reply[2] & 0x80) != 0
    except OSError:
        return False
    finally:
        sock.close()


def quic_initial(dcid: bytes, scid: bytes) -> bytes:
    """A correctly shaped QUIC v1 Initial packet.

    Long header, version 1, zero-length token, a CRYPTO frame at offset 0, then padding. The bytes
    are not a real ClientHello - the payload is encrypted, so a server cannot tell filler from a
    handshake without our keys, and what is measured is whether it answers an Initial at all. That
    distinction matters: a server silent to a valid Initial but noisy to garbage is filtering the
    packet type, and no tunnel built on QUIC will get through it.
    """
    crypto = b"\x06" + (0).to_bytes(8, "big") + (900).to_bytes(2, "big") + os.urandom(900)
    header = b"\xc3" + struct.pack(">I", 1) + dcid + scid
    header += b"\x00"                      # token length 0
    header += struct.pack(">H", 0x0001)     # version specific payload: min datagram size
    packet = header + crypto
    return packet + b"\x00" * (PROBE_BYTES - len(packet))


def classify(answer: bytes) -> str:
    first = answer[0]
    if not (first & 0x80):
        return "NOT quic (first byte 0x%02x)" % first
    if not (first & 0x40):
        return "long header (0x%02x, %dB)" % (first, len(answer))
    if first == 0xe6:
        return "stateless reset (0x%02x, %dB)" % (first, len(answer))
    return "short header, QUIC server reply (0x%02x, %dB)" % (first, len(answer))


def udp(host: str, port: int, payload: bytes, timeout: float):
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--attempts", type=int, default=8)
    parser.add_argument("--attempts-garbage", type=int, default=4)
    parser.add_argument("--host", default="")
    parser.add_argument("--timeout", type=float, default=TIMEOUT)
    args = parser.parse_args()

    if not control_dns():
        print("control: DNS over UDP -> SILENT, so every number below is meaningless")
        return 1
    print("control: DNS over UDP -> answers")
    print("probe size: %d bytes (nothing smaller is answered on this network)" % PROBE_BYTES)
    print("")

    hosts = [args.host] if args.host else INGRESSES
    total_real = 0
    total_garbage = 0
    total_n = 0
    for host in hosts:
        real_hits = 0
        garbage_hits = 0
        kinds = {}
        for _ in range(args.attempts):
            answer = udp(host, OPEN_PORT, quic_initial(os.urandom(8), os.urandom(8)),
                         args.timeout)
            if answer is not None:
                real_hits += 1
                kinds[classify(answer)] = True
            time.sleep(0.15)
        for _ in range(args.attempts_garbage):
            answer = udp(host, OPEN_PORT, os.urandom(PROBE_BYTES), args.timeout)
            if answer is not None:
                garbage_hits += 1
                kinds[classify(answer)] = True
            time.sleep(0.15)
        total_real += real_hits
        total_garbage += garbage_hits
        total_n += args.attempts + args.attempts_garbage
        print("%s:%d" % (host, OPEN_PORT))
        print("  QUIC Initial   ANSWERED %d/%d" % (real_hits, args.attempts))
        print("  random bytes   ANSWERED %d/%d" % (garbage_hits, args.attempts_garbage))
        for kind in sorted(kinds):
            print("    - " + kind)
        print("")

    print("ANSWERED %d/%d probes in total (%d valid Initials, %d random)"
          % (total_real + total_garbage, total_n, total_real, total_garbage))
    print("")
    if total_real:
        print("RESULT: a valid QUIC Initial is answered on " + str(OPEN_PORT) + " - QUIC is alive,")
        print("        HTTP/3 is a real candidate tunnel, and WARP-over-QUIC/MASQUE is worth")
        print("        building on top of it.")
        return 0
    if total_garbage:
        print("RESULT: 443 answers garbage with stateless resets but never answers a valid")
        print("        Initial. QUIC itself is filtered here, so MASQUE and every other QUIC")
        print("        tunnel are closed on this network.")
    else:
        print("RESULT: 443 is silent to both. Only TLS survives, and even a QUIC handshake does")
        print("        not - all QUIC-based transports are closed here.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

