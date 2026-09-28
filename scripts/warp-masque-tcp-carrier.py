"""Can WARP's MASQUE ingress carry a real tunnel on the one transport this network allows?

Why this file exists.

The MASQUE block was measured and the answer has a shape that is easy to get backwards:

    162.159.198.1, .0, .10, 162.159.197.1, 1.1.1.1
        TLSv1.3  ALPN h2  CN=engage.cloudflareclient.com      the endpoint is alive
        SETTINGS  MAX_CONCURRENT_STREAMS=100, MAX_FRAME_SIZE=16777215
        ENABLE_CONNECT_PROTOCOL (0x08)                        ABSENT
        every CONNECT                                      400
        GET /cdn-cgi/trace                                 200, warp=off

And separately, on the same address: a QUIC Initial is answered **0 of 16**, including 4 of 4
random probes. That last part is a different filter profile from the WireGuard block, where 443/udp
answered random bytes with stateless resets. Silence to random data is what a port allowlist does;
a stateless reset is what a QUIC server does. So this block is filtered outright rather than served
and refused, and HTTP/3 - which is QUIC - cannot be the transport.

That leaves HTTP/2 over TCP 443, which this network demonstrably carries, and the 400s become the
question worth answering. A 400 on every CONNECT with 0x08 absent from SETTINGS is the spec'd
answer: Extended CONNECT is not available, so the server cannot accept one. The check that decides
whether that is a hard limit or a precondition is whether **0x08 appears once a client is
identified** - and the only thing that identifies a WARP client is a registration, since the
endpoint derives the tunnel from WARP credentials in a header.

So this file makes the one request it can make without credentials, reads the server's SETTINGS
back, and then states plainly what is and is not settled. It deliberately does not register an
account, create keys, or claim anything about a tunnel: the verdict that matters is Cloudflare's
own cdn-cgi/trace reporting warp=on, and this cannot produce that.

What is measured, on TCP 443, by name (the SNI has to be right or the edge answers 403 to
everything, including robots.txt - see warp-masque-endpoint.py for that trap):

    server SETTINGS                        as above
    :protocol=connect-ip, capsule ?1       400
    :protocol=connect-udp, capsule ?1      400
    :protocol=connect-tcp, capsule ?1      400
    GET /.well-known/masque/udp/default/   400
    GET /cdn-cgi/trace                     200, warp=off

Usage:
    python scripts/warp-masque-tcp-carrier.py
    python scripts/warp-masque-tcp-carrier.py --host 162.159.198.1
"""
from __future__ import annotations

import argparse
import socket
import ssl
import sys

import httpx

MASQUE_SNI = "engage.cloudflareclient.com"
DEFAULT_HOST = "162.159.198.1"
TIMEOUT = 10.0

SHAPES = [
    ("connect-ip   capsule ?1", b"connect-ip", "?1"),
    ("connect-ip   capsule RFC1", b"connect-ip", "1"),
    ("connect-udp  capsule ?1", b"connect-udp", "?1"),
    ("connect-tcp  capsule ?1", b"connect-tcp", "?1"),
    ("connect-ip   no capsule", b"connect-ip", None),
]


class PinnedResolver:
    """Resolve one name to one address, inside this process only."""

    def __init__(self, name, address):
        self.name = name
        self.address = address
        self._original = socket.getaddrinfo

    def __enter__(self):
        original = self._original

        def getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
            if host == self.name:
                return original(self.address, port, family, type, proto, flags)
            return original(host, port, family, type, proto, flags)

        socket.getaddrinfo = getaddrinfo
        return self

    def __exit__(self, *exc):
        socket.getaddrinfo = self._original
        return False


def read_settings(host):
    """The server's SETTINGS frame, read from a raw handshake before any session exists."""
    context = ssl.create_default_context()
    context.set_alpn_protocols(["h2", "http/1.1"])
    with PinnedResolver(MASQUE_SNI, host):
        with context.wrap_socket(socket.create_connection((MASQUE_SNI, 443), timeout=TIMEOUT),
                                 server_hostname=MASQUE_SNI) as sock:
            if sock.selected_alpn_protocol() != "h2":
                return None
            sock.sendall(b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" + b"\x00\x00\x00\x04\x00")
            data = sock.recv(4096)
    if len(data) < 9 or data[3] != 0x04:
        return None
    length = int.from_bytes(data[0:3], "big")
    settings = {}
    body = data[9:9 + length]
    for at in range(0, len(body) - 5, 6):
        key = int.from_bytes(body[at:at + 2], "big")
        settings[key] = int.from_bytes(body[at + 2:at + 6], "big")
    return settings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=DEFAULT_HOST)
    args = parser.parse_args()

    names = {0x02: "ENABLE_PUSH", 0x03: "MAX_CONCURRENT_STREAMS", 0x04: "INITIAL_WINDOW_SIZE",
             0x05: "MAX_FRAME_SIZE", 0x06: "MAX_HEADER_LIST_SIZE",
             0x08: "ENABLE_CONNECT_PROTOCOL"}
    print("endpoint: " + args.host + ":443/TCP   SNI=" + MASQUE_SNI)
    print("")

    with PinnedResolver(MASQUE_SNI, args.host):
        try:
            settings = read_settings(args.host)
        except OSError as e:
            print("TLS failed: " + type(e).__name__ + " - " + str(e)[:70])
            return 1
        if settings is None:
            print("no server SETTINGS frame; ALPN is not h2, so there is nothing to speak.")
            return 1
        for key, value in sorted(settings.items()):
            print("  SETTINGS " + names.get(key, "0x%02x" % key) + " = " + str(value))
        enables_connect = 0x08 in settings
        print("  Extended CONNECT available: " + ("YES" if enables_connect else "NO"))
        print("")

        print("CONNECT shapes:")
        accepted = []
        context = ssl.create_default_context()
        context.set_alpn_protocols(["h2", "http/1.1"])
        for label, protocol, capsule in SHAPES:
            headers = {"protocol": protocol.decode()}
            if capsule:
                headers["capsule-protocol"] = capsule
            try:
                with httpx.Client(http2=True, timeout=TIMEOUT, verify=context,
                                  trust_env=False) as session:
                    response = session.request(
                        "CONNECT", "https://" + MASQUE_SNI + "/.well-known/masque/udp/default/",
                        headers=headers)
            except Exception as e:
                print("  " + label + "  transport error: " + type(e).__name__)
                continue
            print("  " + label + "  " + str(response.status_code))
            if 200 <= response.status_code < 300:
                accepted.append(label)

        print("")
        print("Control, and the one line that would prove it:")
        with httpx.Client(http2=True, timeout=TIMEOUT, verify=context, trust_env=False) as session:
            trace = session.get("https://" + MASQUE_SNI + "/cdn-cgi/trace")
        warp = [line for line in trace.text.split() if line.startswith("warp=")]
        print("  cdn-cgi/trace  " + str(trace.status_code) + "  " + (warp[0] if warp else "-"))
        print("")

    print("VERDICT")
    if accepted:
        print("  " + ", ".join(accepted) + " accepted. The first link of a WARP-over-MASQUE")
        print("  tunnel is up over TCP 443. Carrying a tunnel through it needs a registration and")
        print("  an end-to-end cdn-cgi/trace showing warp=on, neither of which this file has.")
    elif not enables_connect:
        print("  Every CONNECT is refused and the server does not advertise")
        print("  ENABLE_CONNECT_PROTOCOL, so Extended CONNECT is unavailable to any client here,")
        print("  authenticated or not. WARP's own protocols ride connect-ip, so HTTP/2 over TCP")
        print("  cannot carry WARP from this network - and QUIC, which would, is answered 0 of 16.")
        print("  The relay over TCP remains the only route to the WireGuard ingress.")
    else:
        print("  The server allows Extended CONNECT but refused every shape tried, so the")
        print("  protocol or path is wrong rather than the mechanism being unavailable.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

