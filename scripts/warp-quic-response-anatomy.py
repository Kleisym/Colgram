"""Is the 31-byte answer to a QUIC Initial a real server reply, or noise from the path?

Why this file exists.

Measuring a valid QUIC Initial on 443 gave two results that disagree, and neither can be believed
as it stands:

  * from the host: 0 of 40 answered, while random bytes drew 31-byte stateless resets
  * from the phone: 4 of 40 answered, first bytes 0x9e, 0xca, 0xd5, all 31 bytes long

The phone result looks like progress, and it is exactly the kind of result that gets built on. So
it gets torn apart here before anything is built on it.

**A 31-byte short-header answer cannot be a real QUIC reply.** Short-header packets are encrypted
with keys derived from the handshake, and a server that has seen one Initial and no client
handshake has no such keys. A short header arriving back at us is therefore either a stateless
reset (whose first byte is arbitrary) or something that is not QUIC at all.

**0x9e is more interesting and still not proof.** 0x9e is 0b10011110 - long header, packet type
10, which is Retry, and Retry packets legitimately have the fixed bit clear. A Retry is the one
answer a server can give before the handshake completes, so it is worth confirming rather than
dismissing. But a Retry carries a token and a 16-byte integrity tag, and its length has to add
up; 31 bytes only fits particular connection-id sizes. So the bytes get dumped and checked rather
than classified by the first byte alone.

**The control that settles it is a dead port.** If the same 31-byte answers come back from a port
where nothing is listening - 2408 is filtered on this network, so nothing can possibly reply -
then the answers are the path or the emulator talking, and a "QUIC is alive" reading is an
artefact. If dead ports stay silent and 443 answers, the answers are real and QUIC is alive.

Nothing here asserts WARP works. It decides one thing only: whether 443/udp carries a QUIC server
that answers a well-formed Initial, which is the precondition for HTTP/3 or MASQUE tunnelling.

Usage:
    python scripts/warp-quic-response-anatomy.py
    python scripts/warp-quic-response-anatomy.py --attempts 12
"""
from __future__ import annotations

import argparse
import os
import socket
import struct
import sys
import time

PROBE_BYTES = 1200
TIMEOUT = 2.0
ALIVE_PORT = 443
# Nothing listens here and the network filters it, so an answer from it is the path talking.
DEAD_PORTS = [2408, 500]
INGRESSES = ["162.159.192.1", "162.159.193.1", "188.114.96.1", "188.114.97.1"]


def control_dns(server: str = "1.1.1.1") -> bool:
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
    crypto = b"\x06" + (0).to_bytes(8, "big") + (900).to_bytes(2, "big") + os.urandom(900)
    header = b"\xc3" + struct.pack(">I", 1) + dcid + scid
    header += b"\x00" + struct.pack(">H", 0x0001)
    packet = header + crypto
    return packet + b"\x00" * (PROBE_BYTES - len(packet))


def udp(host, port, payload, timeout):
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
                time.sleep(0.03)
            except OSError:
                return None
        return None
    finally:
        sock.close()


def anatomy(answer):
    """What the bytes can physically be, not what the first byte suggests."""
    first = answer[0]
    length = len(answer)
    if (first & 0x80) == 0:
        return "not a QUIC packet (form bit clear, 0x%02x)" % first
    if (first & 0x40) == 0:
        kind = (first & 0x30) >> 4
        if (first & 0x0c) == 0x0c and kind == 0x02 and length >= 23:
            return ("QUIC Retry (long header, type 2, fixed bit clear), %dB - the one answer that"
                    " could be real; integrity tag is the last 16 bytes" % length)
        return "long header, type %d, %dB - stateless reset or version negotiation" % (kind, length)
    return ("short header (encrypted), %dB - cannot be a real reply: the server has no keys yet,"
            " so this is a reset or path noise" % length)


def dump(answer):
    return " ".join("%02x" % b for b in answer[:32])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--attempts", type=int, default=10)
    parser.add_argument("--host", default="162.159.192.1")
    parser.add_argument("--timeout", type=float, default=TIMEOUT)
    args = parser.parse_args()

    if not control_dns():
        print("control: DNS over UDP -> SILENT, every number below is meaningless")
        return 1
    print("control: DNS over UDP -> answers")
    print("host: %s   probe: %dB QUIC Initial" % (args.host, PROBE_BYTES))
    print("")

    answers = {}
    hits = 0
    for _ in range(args.attempts):
        payload = quic_initial(os.urandom(8), os.urandom(8))
        reply = udp(args.host, ALIVE_PORT, payload, args.timeout)
        if reply is not None:
            hits += 1
            answers.setdefault(anatomy(reply), []).append(reply)
        time.sleep(0.12)
    print("=== 443/udp, where a QUIC server really runs ===")
    print("ANSWERED %d/%d" % (hits, args.attempts))
    for label, replies in answers.items():
        print("  x%d  %s" % (len(replies), label))
        print("       first reply: %s" % dump(replies[0]))
    if not answers:
        print("  (nothing came back)")
    print("")

    dead_hits = 0
    dead_total = 0
    print("=== control: ports where nothing can answer ===")
    for port in DEAD_PORTS:
        port_hits = 0
        for _ in range(args.attempts):
            payload = quic_initial(os.urandom(8), os.urandom(8))
            reply = udp(args.host, port, payload, args.timeout)
            dead_total += 1
            if reply is not None:
                port_hits += 1
                dead_hits += 1
        print("  %s:%d  ANSWERED %d/%d" % (args.host, port, port_hits, args.attempts))
    print("")

    print("VERDICT:")
    if dead_hits:
        print("  A filtered port answered, so these answers are the path - not a QUIC server.")
        print("  Any earlier 'QUIC is alive on the phone' reading is an artefact of that path.")
        return 1
    if not hits:
        print("  443 stayed silent to a valid Initial and dead ports stayed silent too, so the")
        print("  silence is about the packet, not the port. QUIC is not a tunnel here.")
        return 0
    retries = [r for label, reps in answers.items() if "Retry" in label for r in reps]
    if retries and len(retries) == hits:
        print("  Dead ports silent, and every answer on 443 is a QUIC Retry - the one reply a")
        print("  server can send before a handshake. QUIC is genuinely alive on 443, so HTTP/3")
        print("  and MASQUE are worth building on top of it.")
        return 0
    print("  Dead ports silent, so the answers do come from something on 443 - but they are not")
    print("  Retry packets, and a short-header answer is impossible before the handshake")
    print("  completes. Reset-or-noise is the only reading left, so QUIC still is not a tunnel.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

