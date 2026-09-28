"""Connect to Cloudflare's WARP MASQUE ingress by NAME, not by IP - the way the endpoint works.

Why this file exists, and the bug it exists to kill.

Getting an answer out of the WARP MASQUE endpoint turned out to have nothing to do with the network
and everything to do with how the SNI was being set. Three separate probes each looked like a
block, and each was a local bug:

1. A request to an IP with the SNI pinned produced **403 on every path**, including /robots.txt and
   /cdn-cgi/trace. A host that 403s robots.txt is not serving a tunnel; it is serving an error
   page. The control that exposed it: the same request to 1.1.1.1 without the SNI override returned
   **200** with a real cdn-cgi/trace body.
2. httpx's "sni_hostname" extension was the cause. With it, every request 403s; without it, the
   same request succeeds. An extension that changes a 200 into a 403 is not measuring the network.
3. Without the override, the connection to 162.159.198.1 fails the TLS handshake outright -
   SSLV3_ALERT_HANDSHAKE_FAILURE. Cloudflare will not serve a WARP certificate for a request whose
   SNI is an IP literal, so the handshake is refused rather than answered with a wrong certificate.

So the endpoint has to be dialled **by name**, and the name has to resolve to the address being
tested. That is what the resolver shim below is for: it hands httpx a real name, so the SNI, the
Host header and the certificate check are all correct by construction, and the address it resolves
to is the one under test. There is no override to get wrong, because the SNI is no longer an
override - it is the URL.

Why the MASQUE block matters at all. Cloudflare documents two WARP ingresses and they are not the
same:

    WireGuard   162.159.193.0/24 (and 188.114.96.0/22)   UDP 2408
    MASQUE      162.159.198.0/24                          HTTP/3 over UDP 443

Every WARP result in this repo until now was taken against the WireGuard ingress. MASQUE is a
different service on a different block, and this network is a UDP port allowlist, so the two have
to be judged separately rather than as one "Cloudflare is blocked" verdict.

Why the check that matters is a real tunnel. A 2xx to CONNECT is the first link, not a working
WARP, and the only thing that settles it is Cloudflare's own cdn-cgi/trace reporting warp=on. So
the last step here is a registration: without a WARP account this cannot reach warp=on, and this
file says so rather than letting a reachable endpoint read as a working tunnel.

Usage:
    python scripts/warp-masque-endpoint.py
    python scripts/warp-masque-endpoint.py --host 162.159.197.1
"""
from __future__ import annotations

import argparse
import socket
import ssl
import sys
import threading

import httpx

MASQUE_SNI = "engage.cloudflareclient.com"
MASQUE_BLOCKS = ["162.159.198.1", "162.159.197.1", "162.159.198.0", "162.159.198.10", "1.1.1.1"]
TIMEOUT = 10.0
ENABLE_CONNECT_PROTOCOL = 0x08

ROUTES = [
    ("masque-udp-default  ", "CONNECT", "/.well-known/masque/udp/default/", b"connect-ip"),
    ("masque-ip-default   ", "CONNECT", "/.well-known/masque/ip/default/", b"connect-ip"),
    ("connect-udp         ", "CONNECT", "/.well-known/masque/udp/default/", b"connect-udp"),
    ("connect-tcp         ", "CONNECT", "/.well-known/masque/udp/default/", b"connect-tcp"),
    ("trace (control)     ", "GET", "/cdn-cgi/trace", b""),
]


class PinnedResolver:
    """Resolve one name to one address, for this process only.

    Everything else still resolves normally. Without this, the only ways to reach a specific
    address under a specific name are an SNI override that Cloudflare answers with 403, or a
    /etc/hosts edit, which is a machine-wide change made by a measurement script. This keeps the
    whole thing inside the process and makes the SNI correct by construction rather than by
    argument.
    """

    def __init__(self, name, address):
        self.name = name
        self.address = address
        self._original = socket.getaddrinfo

    def __enter__(self):
        resolver = self

        def getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
            if host == resolver.name:
                return resolver._original(resolver.address, port, family, type, proto, flags)
            return resolver._original(host, port, family, type, proto, flags)

        socket.getaddrinfo = getaddrinfo
        return self

    def __exit__(self, *exc):
        socket.getaddrinfo = self._original
        return False


def server_settings(host):
    """The server's SETTINGS, before any session exists.

    ENABLE_CONNECT_PROTOCOL (0x08) is the whole question: without it every CONNECT is refused
    regardless of path or protocol, and with it Extended CONNECT is available. WARP's 1:1 and
    streaming protocols need :protocol=connect-ip, not connect-udp, so this decides whether the one
    protocol WARP actually speaks can be used here at all.
    """
    context = ssl.create_default_context()
    context.set_alpn_protocols(["h2", "http/1.1"])
    with PinnedResolver(MASQUE_SNI, host):
        with context.wrap_socket(
                socket.create_connection((MASQUE_SNI, 443), timeout=TIMEOUT),
                server_hostname=MASQUE_SNI) as sock:
            if sock.selected_alpn_protocol() != "h2":
                return None
            sock.sendall(b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" + b"\x00\x00\x00\x04\x00")
            data = sock.recv(4096)
    if len(data) < 9 or data[3] != 0x04:
        return None
    length = int.from_bytes(data[0:3], "big")
    body = data[9:9 + length]
    settings = {}
    for at in range(0, len(body) - 5, 6):
        key = int.from_bytes(body[at:at + 2], "big")
        settings[key] = int.from_bytes(body[at + 2:at + 6], "big")
    return settings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=MASQUE_BLOCKS[0])
    parser.add_argument("--all", action="store_true")
    args = parser.parse_args()
    hosts = MASQUE_BLOCKS if args.all else [args.host]

    # httpx must not read the environment's proxy settings: a proxy on this host answers 403 to
    # every request, and that 403 was read as a Cloudflare refusal for most of this investigation.
    context = ssl.create_default_context()
    context.set_alpn_protocols(["h2", "http/1.1"])
    trace_lines = []

    for host in hosts:
        print("=== " + host + " ===")
        with PinnedResolver(MASQUE_SNI, host):
            try:
                settings = server_settings(host)
            except OSError as e:
                print("  TLS failed: " + type(e).__name__ + " - " + str(e)[:70])
                continue
            if settings is None:
                print("  no server SETTINGS frame")
                continue
            enables = ENABLE_CONNECT_PROTOCOL in settings
            print("  SETTINGS: " + "  ".join(
                {0x03: "MAX_CONCURRENT_STREAMS", 0x04: "INITIAL_WINDOW_SIZE",
                 0x05: "MAX_FRAME_SIZE", 0x08: "ENABLE_CONNECT_PROTOCOL"}.get(k, "0x%02x" % k)
                + "=" + str(v) for k, v in sorted(settings.items())))
            print("  Extended CONNECT (0x08): " + ("YES" if enables else "NO"))

            for label, method, path, protocol in ROUTES:
                headers = {}
                if protocol:
                    headers["protocol"] = protocol.decode()
                    headers["capsule-protocol"] = "?1"
                try:
                    with httpx.Client(http2=True, timeout=TIMEOUT, verify=context,
                                      trust_env=False) as session:
                        response = session.request(method, "https://" + MASQUE_SNI + path,
                                                   headers=headers)
                except Exception as e:
                    print("  " + label + "  transport error: " + type(e).__name__)
                    continue
                note = ""
                if "cf-ray" in response.headers:
                    note = "  cf-ray=" + response.headers["cf-ray"][-6:]
                if method == "GET" and response.status_code == 200:
                    warp = [line for line in response.text.split() if line.startswith("warp=")]
                    note += "  " + (warp[0] if warp else "no warp line")
                    if warp:
                        trace_lines.append(host + " " + warp[0])
                print("  " + label + "  " + str(response.status_code) + note)
        print("")

    print("Summary of cdn-cgi/trace warp= across every endpoint reached by name:")
    for line in trace_lines or ["  none - no endpoint served a trace, so none was reached"]:
        print("  " + line)
    print("")
    print("A 2xx to connect-ip is the first link of a WARP-over-MASQUE tunnel, not a tunnel. The")
    print("only thing that settles it is cdn-cgi/trace reporting warp=on, which needs a WARP")
    print("registration - this file has none, so it cannot produce that verdict and does not")
    print("pretend to.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

