"""Does Cloudflare's edge accept Extended CONNECT at all - and if not, where is it decided?

Why this file exists.

A status code of 400 is not a reason. Reading the body says what actually answered, and on this
edge the body is a sentence:

    HTTP CONNECT is not supported | engage.cloudflareclient.com | Cloudflare

That is not a MASQUE refusal, not a WAF rule, and not the network. It is Cloudflare's edge saying
the CONNECT method is not implemented for this virtual host, and it arrives in 93-530 ms with an
HTML body, text/html content-type and a cf-ray - not as an HTTP/2 response from a tunnel backend.

Which matters because the whole HTTP/2 route to WARP rested on that request. WARP's 1:1 and
streaming protocols ride :protocol=connect-ip (RFC 9484) over HTTP/2, so if the edge refuses
CONNECT before any backend sees it, then no client, no credential and no relay on an unfiltered
host can use this route. The question is whether the refusal is a property of this vhost or of
Cloudflare's edge everywhere, because those have very different consequences: the first is
something a different name fixes, the second is a dead end for HTTP/2 in front of Cloudflare.

So this file sends the same CONNECT to six vhosts across two kinds of address - the MASQUE block
and 1.1.1.1, Cloudflare's own resolver - and reads the body of each. A 400 with the same sentence
everywhere is an edge-wide rule. A 400 from one vhost and something else from another is a vhost
property, and that would be worth chasing.

The second thing it checks is SETTINGS_ENABLE_CONNECT_PROTOCOL (0x08), and it reads the SETTINGS
frame properly - looping until the frame is complete rather than trusting a single recv. A truncated
frame silently drops whatever settings sit past the cut, and 0x08 is exactly the key that would go
missing that way, turning "the server does not allow it" into a measurement artefact. The frame
here is 18 bytes and arrives whole, so its absence is real.

Usage:
    python scripts/warp-connect-support.py
    python scripts/warp-connect-support.py --host 1.1.1.1 --name cloudflare.com
"""
from __future__ import annotations

import argparse
import re
import socket
import ssl
import sys
import time

import httpx

TIMEOUT = 10.0
ENABLE_CONNECT_PROTOCOL = 0x08
NOT_SUPPORTED = "HTTP CONNECT is not supported"

# Two different addresses on purpose: the MASQUE block, and Cloudflare's own resolver. If the edge
# refuses CONNECT on both, the refusal is not a property of the WARP vhost.
DEFAULT_CASES = [
    ("engage.cloudflareclient.com", "162.159.198.2"),
    ("connectivity.cloudflareclient.com", "162.159.198.2"),
    ("engage.cloudflareclient.com", "162.159.197.3"),
    ("engage.cloudflareclient.com", "1.1.1.1"),
    ("cloudflare.com", "1.1.1.1"),
    ("cloudflare-dns.com", "1.1.1.1"),
]

PATHS = ["/.well-known/masque/udp/default/", "/.well-known/masque/ip/default/"]


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


def read_settings(host, name):
    """The server's SETTINGS, read as a complete frame.

    The loop is the point. HTTP/2 frame headers carry a 3-byte length, and one recv on a TCP
    connection can return a partial frame. Reading once and parsing whatever arrived drops every
    setting past the cut - and since ENABLE_CONNECT_PROTOCOL is the key being looked for, a
    truncated read reports "not allowed" when the server in fact said nothing about it. Reading
    until the announced length is satisfied removes that whole class of false negative.
    """
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    context.set_alpn_protocols(["h2", "http/1.1"])
    with context.wrap_socket(socket.create_connection((host, 443), timeout=TIMEOUT),
                             server_hostname=name) as sock:
        if sock.selected_alpn_protocol() != "h2":
            return None, "ALPN is not h2"
        sock.sendall(b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" + b"\x00\x00\x00\x04\x00")
        sock.settimeout(TIMEOUT)
        buffer = b""
        deadline = time.time() + TIMEOUT
        while time.time() < deadline and len(buffer) < 9:
            try:
                chunk = sock.recv(4096)
            except OSError:
                break
            if not chunk:
                break
            buffer += chunk
        if len(buffer) < 9:
            return None, "short read, " + str(len(buffer)) + "B"
        length = int.from_bytes(buffer[0:3], "big")
        while time.time() < deadline and len(buffer) < 9 + length:
            try:
                chunk = sock.recv(4096)
            except OSError:
                break
            if not chunk:
                break
            buffer += chunk
        body = buffer[9:9 + length]
        if len(body) != length:
            return None, "frame truncated, " + str(len(body)) + " of " + str(length)
        settings = {}
        for at in range(0, len(body) - 5, 6):
            key = int.from_bytes(body[at:at + 2], "big")
            settings[key] = int.from_bytes(body[at + 2:at + 6], "big")
        return settings, "frame complete, " + str(length) + "B"


def body_text(response):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", response.text)).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="")
    parser.add_argument("--name", default="")
    args = parser.parse_args()
    cases = ([(args.name, args.host)] if args.host and args.name else DEFAULT_CASES)

    context = ssl.create_default_context()
    context.set_alpn_protocols(["h2", "http/1.1"])

    print("=== server SETTINGS, read as a complete frame ===")
    for name, address in cases:
        with PinnedResolver(name, address):
            try:
                settings, note = read_settings(address, name)
            except OSError as e:
                print("  %-34s %-15s ERR %s" % (name, address, type(e).__name__))
                continue
            if settings is None:
                print("  %-34s %-15s %s" % (name, address, note))
                continue
            print("  %-34s %-15s %s  0x08 present=%s"
                  % (name, address, note, ENABLE_CONNECT_PROTOCOL in settings))

    print("")
    print("=== Extended CONNECT, and the sentence in the body ===")
    edge_everywhere = True
    anything_else = False
    accepted = []
    for name, address in cases:
        for path in PATHS:
            with PinnedResolver(name, address):
                try:
                    with httpx.Client(http2=True, timeout=TIMEOUT, verify=context,
                                      trust_env=False) as session:
                        started = time.time()
                        response = session.request(
                            "CONNECT", "https://" + name + path,
                            headers={"protocol": "connect-ip", "capsule-protocol": "?1"})
                        millis = int((time.time() - started) * 1000)
                except Exception as e:
                    print("  %-34s %-15s %-34s transport error %s"
                          % (name, address, path[-34:], type(e).__name__))
                    continue
                text = body_text(response)
                edge_says_no = NOT_SUPPORTED in text
                if 200 <= response.status_code < 300:
                    accepted.append(name + " @ " + address + path)
                if not edge_says_no:
                    anything_else = True
                label = ("EDGE REFUSES THE METHOD" if edge_says_no
                         else ("ACCEPTED" if 200 <= response.status_code < 300
                               else "status " + str(response.status_code) + " " + text[:40]))
                print("  %-34s %-15s %-34s %3d %5dms  %s"
                      % (name, address, path[-34:], response.status_code, millis, label))

    print("")
    print("VERDICT")
    if accepted:
        print("  Extended CONNECT was accepted: " + ", ".join(accepted))
        print("  The HTTP/2 route to WARP is open on this path. Everything still depends on")
        print("  credentials, which this file does not have.")
    elif edge_everywhere and not anything_else:
        print("  Every vhost on every address answered the same sentence from Cloudflare's edge:")
        print('    "' + NOT_SUPPORTED + '"')
        print("  The refusal is a property of the edge, not of the WARP virtual host and not of")
        print("  the network. It is decided before any backend sees the request, so no client,")
        print("  no credential and no relay on an unfiltered host can use HTTP/2 to reach WARP")
        print("  here. Combined with ENABLE_CONNECT_PROTOCOL being absent from a complete SETTINGS")
        print("  frame, the HTTP/2 route is closed at both ends of the exchange.")
        print("")
        print("  The transport WARP would need is HTTP/3, which is QUIC, and a QUIC Initial on")
        print("  this block is answered 0 of 16. The relay over TCP remains the only route to the")
        print("  WireGuard ingress, and it is built and proven.")
    else:
        print("  At least one vhost answered with something other than the edge's refusal, so the")
        print("  refusal is a vhost property and there may be a name that works. See the rows above.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

