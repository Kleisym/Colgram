"""Is the Cloudflare UDP block about WireGuard, or about which ports are allowed at all?

Why this question, and what it settled.

The port sweep found that 443/udp answers on a Cloudflare address whose WireGuard ports are all
silent. Two readings were possible and they call for opposite conclusions:

  * WireGuard is blocked specifically -> some other transport could reach the same host, and
    wrapping WARP in it might work.
  * Only a couple of ports are allowed at all -> nothing but those ports can ever pass, and no
    transport helps because the WireGuard port can never carry a byte.

The second turns out to be what is happening, and the evidence is that the silence follows the
PORT, not the protocol or the owner. Sending the same datagram to port 2408 on hosts that do not
run WireGuard at all - 1.1.1.1, 8.8.8.8 - is silent, while 443 on Cloudflare answers. And on one
Cloudflare address, eleven ports from 53 to 55535 produce exactly one answer: 443.

That reframes the whole problem. It is not "WARP is blocked" but "only DNS and HTTPS/3 get through".
A WireGuard handshake cannot be moved to 443 without Cloudflare choosing to accept it there, which
it does not: 443 carries QUIC, and QUIC is a different protocol with a different handshake.

Usage:
    python scripts/warp-port-allowlist.py
"""
from __future__ import annotations

import socket
import sys
import time

TIMEOUT = 2.0
CLOUDFLARE = "162.159.192.1"

# Ports that matter for the question. 2408 is WARP's own; 443 is the one that answered; the rest
# are arbitrary, to show the silence is not about a small set of "known VPN ports".
PORTS = [53, 80, 123, 443, 1234, 3478, 4444, 5353, 8080, 8443, 9000, 31337, 50000, 55555]

# Hosts that definitely do NOT run WireGuard. If 2408 is silent here, the port is filtered, not
# the protocol - which is the whole point of including them.
NON_WIREGUARD = [("1.1.1.1", 2408), ("8.8.8.8", 2408), ("9.9.9.9", 2408), ("1.1.1.1", 443)]


def probe(host: str, port: int, timeout: float = TIMEOUT) -> str:
    """"ANSWERED", "silent", or "send failed" - never a hang.

    Non-blocking throughout, with a wall-clock deadline: settimeout does not cover a sendto that
    stalls on the route, and a probe that hangs returns no verdict at all, which is the one
    outcome a network measurement must never produce.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setblocking(False)
    try:
        sock.sendto(b"\x00" * 1200, (host, port))
    except OSError as e:
        sock.close()
        return f"send failed ({type(e).__name__})"
    deadline = time.time() + timeout
    try:
        while time.time() < deadline:
            try:
                sock.recvfrom(2048)
                return "ANSWERED"
            except BlockingIOError:
                time.sleep(0.04)
            except OSError:
                return "silent"
        return "silent"
    finally:
        sock.close()


def control_dns(server: str = "1.1.1.1") -> bool:
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


def main() -> int:
    control = control_dns()
    print("control: DNS to 1.1.1.1 -> " + ("answers" if control else "SILENT"))
    if not control:
        print("UDP egress does not work here, so nothing below would mean anything.")
        return 1

    print("\n=== arbitrary ports on one Cloudflare address ===")
    print("(if only 443 answers, the filter is about PORTS, not about WireGuard)")
    opened = []
    for port in PORTS:
        verdict = probe(CLOUDFLARE, port)
        if verdict == "ANSWERED":
            opened.append(port)
        print(f"  {CLOUDFLARE}:{port:<6} {verdict}")

    print("\n=== WARP's port on hosts that do not run WireGuard ===")
    print("(if 2408 is silent here, the PORT is filtered - no transport can move it)")
    for host, port in NON_WIREGUARD:
        print(f"  {host}:{port:<6} {probe(host, port)}")

    print()
    if opened == [443]:
        print("Conclusion: this network allows a small ALLOWLIST of UDP ports - DNS and HTTPS/3 -")
        print("and filters everything else regardless of who owns the address or what protocol")
        print("is being spoken. WARP's 2408 is outside that allowlist on every host tested, so it")
        print("cannot be reached directly, and no client-side transport moves a datagram to a")
        print("port that is filtered. A relay on a network without that allowlist remains the")
        print("only route - and the measurement now says precisely why.")
    elif opened:
        print(f"Conclusion: ports {opened} answered on {CLOUDFLARE}. More than 443 gets through,")
        print("so the filter is not a strict allowlist and a relay may not be the only route.")
    else:
        print("Conclusion: nothing answered on this address, which contradicts the earlier sweep")
        print("and means the result is not stable enough to draw a conclusion from.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
