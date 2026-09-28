"""Is Cloudflare's WARP MASQUE ingress reachable on this network - the one route left?

Why this file exists, and why it is the most important measurement in the project.

Every earlier WARP result here measured Cloudflare's *WireGuard* ingress: 162.159.192.x, 162.159.193.x
and 188.114.9x.x on UDP 2408. That is a different service from the one the WARP clients actually
have, and the difference is the whole point.

WARP's MASQUE endpoint lives in a different block, 162.159.197.0/24, and it is reached over *HTTP/2
on TCP 443* - not UDP 2408. Every measurement in this repo up to now was about a port the network
filters, and this one is about the single port the network is known to allow. So the earlier
conclusion, "no transport survives, because no client-side framing can move a datagram onto a
filtered port", was correct about WireGuard and silently assumed WireGuard was the only way to
reach WARP. It is not, and this is the measurement that shows it.

Measured on this host, over TCP 443, before any claim was made:

    TLSv1.3, ALPN h2, certificate CN=engage.cloudflareclient.com
    GET  /.well-known/masque/udp/default/   403
    CONNECT :protocol connect-udp           400
    CONNECT :protocol connect-tcp           400
    CONNECT :protocol connect-ip            400
    server: cloudflare, cf-ray present

A real h2 ALPN plus a real Cloudflare certificate naming the WARP host is a much stronger signal
than "a port answered": the endpoint is identifying itself over the one transport that works
here, and Cloudflare's edge is answering. What is not established is whether it will carry a
tunnel, and the 400s are the interesting part rather than a disappointment.

**What a 400 on CONNECT means, precisely.** Extended CONNECT is only legal when the server
advertises SETTINGS_ENABLE_CONNECT_PROTOCOL (setting 0x08). Without that the server answers 400 to
every CONNECT regardless of path or protocol, because the mechanism itself is unavailable. So the
first job here is to read the server's SETTINGS and check for 0x08. This matters because WARP
clients do not use connect-udp at all - the 1:1 and streaming WARP protocols tunnel at layer 3 via
:protocol=connect-ip (RFC 9484) over HTTP/2 - so a server that does not advertise 0x08 is closed to
the one protocol WARP actually speaks, while a server that does advertise it may be reachable even
though connect-udp was refused.

**Why the SNI is set explicitly.** The MASQUE endpoint is selected by SNI and certificate, not by
dialing a name, and 162.159.197.x does not resolve. Letting httpx verify the certificate against
the IP raises SSLV3_ALERT_HANDSHAKE_FAILURE - Cloudflare rejects the handshake outright rather than
serving a wrong certificate - so this failure means "SNI was wrong or absent", never "blocked".

**Why httpx rather than hand-built frames.** A first version assembled the HTTP/2 preface, SETTINGS
and HEADERS by hand. Every response was silence or an unparseable frame, which reads like a server
refusing and is far more likely to be a malformed frame: HTTP/2 length fields are 3 bytes, and
writing one as a 4-byte big-endian int shifts every byte after it. A client that gets that wrong
learns nothing about the server and looks like a dead endpoint. A library frames correctly, so a
status code here is the server's answer.

Nothing here asserts that WARP works. It establishes whether the one unfiltered route to
Cloudflare's WARP endpoint is usable, and the gap between "the port answered" and "a tunnel came
up" is the gap this repo keeps refusing to paper over.

Usage:
    python scripts/warp-masque-443-probe.py
    python scripts/warp-masque-443-probe.py --host 162.159.197.5
"""
from __future__ import annotations

import argparse
import socket
import ssl
import sys

import httpx

MASQUE_SNI = "engage.cloudflareclient.com"
MASQUE_HOSTS = ["162.159.197.1", "162.159.197.5", "162.159.197.0", "162.159.197.10"]
TIMEOUT = 8.0
ENABLE_CONNECT_PROTOCOL = 0x08

# Each shape the endpoint might accept, with the :protocol that selects it. WARP's own 1:1 and
# streaming protocols tunnel at layer 3, so connect-ip is the one that matters and it is tried
# alongside the others rather than after them.
ROUTES = [
    ("masque-udp-default  ", "GET", "/.well-known/masque/udp/default/", b""),
    ("connect-ip          ", "CONNECT", "/", b"connect-ip"),
    ("connect-udp         ", "CONNECT", "/", b"connect-udp"),
    ("connect-tcp         ", "CONNECT", "/", b"connect-tcp"),
    ("masque-ip-default   ", "GET", "/.well-known/masque/ip/default/", b""),
    ("root                ", "GET", "/", b""),
]


def client():
    """httpx with h2 and the SNI pinned to the MASQUE host.

    The SNI has to be set separately from the URL because the URL carries an IP and the endpoint is
    selected by name. Certificate verification stays on: a Cloudflare edge that serves the wrong
    certificate for this name is a fact worth failing on, and turning verification off would hide
    having reached something other than the WARP endpoint.
    """
    context = ssl.create_default_context()
    context.set_alpn_protocols(["h2", "http/1.1"])
    # The SNI extension goes on the per-request transport, not on the client: httpx accepts
    # extensions per request, and passing them to the constructor is a TypeError rather than a
    # silently ignored argument. Keeping it per request also means one client can probe several
    # hosts with different names.
    return httpx.Client(http2=True, timeout=TIMEOUT, verify=context), {"sni_hostname": MASQUE_SNI}


def server_settings(host):
    """The server's SETTINGS values, read before any session exists.

    Parsed from a raw frame because the first bytes on the wire are the answer to "does this
    endpoint allow Extended CONNECT at all", and that has to be known before a CONNECT is worth
    trying. The client preface is sent first - the server never speaks first - and the reply is a
    SETTINGS frame whose type sits at index 3, with a 3-byte length in the first three bytes.
    """
    context = ssl.create_default_context()
    context.set_alpn_protocols(["h2", "http/1.1"])
    try:
        with context.wrap_socket(socket.create_connection((host, 443), timeout=TIMEOUT),
                                 server_hostname=MASQUE_SNI) as sock:
            if sock.selected_alpn_protocol() != "h2":
                return None
            sock.sendall(b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" + b"\x00\x00\x00\x04\x00")
            data = sock.recv(4096)
    except OSError:
        return None
    if len(data) < 9 or data[3] != 0x04:
        return None
    length = int.from_bytes(data[0:3], "big")
    body = data[9:9 + length]
    settings = {}
    for at in range(0, len(body) - 5, 6):
        key = int.from_bytes(body[at:at + 2], "big")
        settings[key] = int.from_bytes(body[at + 2:at + 6], "big")
    return settings


def describe(settings):
    if settings is None:
        return "no server SETTINGS frame (ALPN is not h2, or the connection did not complete)"
    names = {0x02: "ENABLE_PUSH", 0x03: "MAX_CONCURRENT_STREAMS", 0x04: "INITIAL_WINDOW_SIZE",
             0x05: "MAX_FRAME_SIZE", 0x06: "MAX_HEADER_LIST_SIZE", 0x08: "ENABLE_CONNECT_PROTOCOL"}
    return "  ".join(names.get(k, "setting 0x%02x" % k) + "=" + str(v)
                     for k, v in sorted(settings.items()))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=MASQUE_HOSTS[0])
    args = parser.parse_args()

    url = "https://" + args.host
    print("target: %s:443/TCP  SNI=%s" % (args.host, MASQUE_SNI))
    print("")
    print("=== server SETTINGS, over a raw h2 handshake ===")
    settings = server_settings(args.host)
    print("  " + describe(settings))
    enables_connect = bool(settings) and ENABLE_CONNECT_PROTOCOL in settings
    print("  Extended CONNECT allowed (0x08): " + ("YES" if enables_connect else "NO"))
    print("")

    print("=== route shapes ===")
    results = {}
    session, sni = client()
    with session:
        for label, method, path, protocol in ROUTES:
            headers = {}
            if protocol:
                headers["protocol"] = protocol.decode()
                headers["capsule-protocol"] = "?1"
            try:
                response = session.request(method, url + path, headers=headers,
                                           extensions=sni)
            except Exception as e:
                print(label + "  transport error: " + type(e).__name__ + " - " + str(e)[:90])
                results[label] = None
                continue
            note = ""
            if "cf-ray" in response.headers:
                note = "  cf-ray=" + response.headers["cf-ray"][-6:]
            print(label + "  HTTP/" + response.http_version.split("/")[-1] + " "
                  + str(response.status_code) + note)
            results[label] = response.status_code

    print("")
    accepted = [label for label, code in results.items() if code and 200 <= code < 300]
    if accepted:
        print("ACCEPTED: " + ", ".join(accepted))
        print("A 2xx here is the first link of a real WARP-over-MASQUE tunnel on the one port this")
        print("network allows. The next step is a real tunnel and Cloudflare's warp=on, not this.")
    elif any(code == 400 for code in results.values()) and not enables_connect:
        print("VERDICT: 400 on every CONNECT, and the server does not advertise")
        print("  ENABLE_CONNECT_PROTOCOL (0x08). That is a refusal of the mechanism itself, which")
        print("  rules out connect-ip - the protocol WARP actually speaks - as well as the rest.")
        print("  The endpoint is reachable and identified; it will not carry a WARP tunnel on h2.")
    else:
        print("VERDICT: no 2xx. The endpoint answers HTTP/2 over TCP 443 - which is genuinely the")
        print("  only unfiltered route to WARP from this network - but carries none of the tunnel")
        print("  shapes tried. See the per-shape statuses above for which one refused.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

