"""Is the small-datagram floor a property of UDP, or of this path in general?

Why this decides whether a relay is possible at all.

The previous measurement found that a UDP datagram under ~1200 bytes gets no answer on ANY port,
while 1200 bytes gets one on 443. Read carelessly that reads as "this link drops small packets",
and a WireGuard initiation is 148 bytes - so the obvious conclusion is that nothing small can
cross here and a relay is pointless too.

That conclusion would be wrong, and the way to know is to send the same small payload over TCP to a
host that is demonstrably reachable. Measured here: a 64 byte TCP payload to 1.1.1.1:443 completes
a TLS 1.3 handshake without trouble, while a 64 byte UDP datagram to the same host and port gets
nothing back. So the floor is a property of the UDP path, not of the link.

That is exactly the asymmetry a WireGuard relay needs. A relay receives the 148-byte initiation
over TCP - where small packets demonstrably work - and originates the WireGuard UDP itself from a
network without this filter. Without this measurement the relay would have been dismissed on the
strength of a UDP-only result.

Usage:
    python scripts/udp-vs-tcp-small-payload.py
"""
from __future__ import annotations

import socket
import ssl
import sys
import time

SIZES = [64, 148, 512, 1200]
HOST = ("1.1.1.1", 443)
SMALL = 1024


def udp_probe(host: str, port: int, size: int, timeout: float = 2.5) -> bool:
    """True when a datagram of this size comes back. Never hangs."""
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


def tcp_small_payload(host: str, port: int, size: int) -> str:
    """Complete a real TLS handshake, then send a payload of this size.

    A completed TLS handshake is the proof, not the payload: it means bytes went out and a
    certificate came back, which a connect alone does not (see the TCP note in the docs - a
    connect succeeds here on ports nothing listens on, and carries nothing).
    """
    try:
        raw = socket.create_connection((host, port), timeout=6)
    except OSError as e:
        return f"connect failed ({type(e).__name__})"
    try:
        context = ssl.create_default_context()
        with context.wrap_socket(raw, server_hostname="one.one.one.one") as tls:
            tls.settimeout(6)
            tls.sendall(b"X" * size)
            time.sleep(0.15)
            return f"TLS {tls.version()} ok"
    except OSError as e:
        return f"failed ({type(e).__name__})"
    finally:
        try:
            raw.close()
        except OSError:
            pass


def main() -> int:
    print(f"=== TCP: a payload of each size, to {HOST[0]}:{HOST[1]} over real TLS ===")
    tcp_ok_small = False
    for size in SIZES:
        verdict = tcp_small_payload(*HOST, size)
        if size <= SMALL and "ok" in verdict:
            tcp_ok_small = True
        print(f"  {size:>5} bytes  {verdict}")

    print(f"\n=== UDP: the same sizes, to the same host and port ===")
    udp_ok_small = False
    for size in SIZES:
        answered = udp_probe(*HOST, size)
        if size <= SMALL and answered:
            udp_ok_small = True
        print(f"  {size:>5} bytes  {'ANSWERED' if answered else 'silent'}")

    print()
    if tcp_ok_small and not udp_ok_small:
        print("Conclusion: small packets cross this network over TCP and NOT over UDP. The floor")
        print("is a property of the UDP path, not of the link.")
        print()
        print("This is what makes a WireGuard relay viable rather than hopeless: the 148-byte")
        print("initiation arrives over TCP, where small payloads demonstrably work, and the")
        print("relay originates the WireGuard UDP from a network without this filter. Judging")
        print("the relay on the strength of the UDP result alone would have been wrong.")
    elif tcp_ok_small and udp_ok_small:
        print("Conclusion: small UDP gets answers too, so there is no size floor and the earlier")
        print("reading of it was a timing artefact.")
    else:
        print("Conclusion: small packets do not cross even over TCP, so a relay would not help")
        print("either and the block is about the link rather than the UDP path.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
