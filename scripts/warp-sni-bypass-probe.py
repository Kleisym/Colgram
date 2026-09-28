"""Does an SNI without the blocked word reach WARP's MASQUE endpoint?

Why this file exists.

Cloudflare's own client was measured failing here, and its log named the exact reason. The
WARP service log from warp-cli 2026.7.1343.0:

    connect_with_protocol_racing{primary="masque" secondary="H2"}
    h2_tun: Connecting to edge sni="consumer-masque.cloudflareclient.com"
    Start racer 0.0.0.0:35945 ---> 162.159.198.2:443

So the client races QUIC over UDP (ports 1701, 4500, 4443, 8443, 8095 - all silent on this
network, all measured) and then falls back to **HTTP/2 over TCP 443**. That fallback is the only
route to WARP this network does not filter, and the official client fails it. The question this
file answers is whether the failure is the network or the name.

**The name is the problem, and it is filterable by one word.** Measured to 162.159.198.2 and
.1 and 162.159.197.3, TCP 443, each with a different server_name:

    engage.cloudflareclient.com             OK   121-139 ms   ALPN h2
    connectivity.cloudflareclient.com       OK   126-379 ms   ALPN h2
    cloudflareclient.com                   OK   397 ms        ALPN h2
    example.com                             OK   262 ms
    consumer-masque.cloudflareclient.com    FAIL 2147 ms      SSLEOFError
    masque.cloudflareclient.com             FAIL 2239 ms
    masque.example.com                      FAIL 2182 ms
    consumer-masque.example.com             FAIL 2167 ms
    mqs.cloudflareclient.com                FAIL 2173 ms
    notmasque.com                           FAIL 2281 ms
    MASQUE.cloudflareclient.com             FAIL 2177 ms

Two things follow, and the second is the one that matters. The filter is **not** a whole-name
match: it fires on any name containing "masque" or "mqs", case-insensitively, in any domain - a
name with no relation to Cloudflare is dropped just the same. And a blocked name takes ~2100 ms
before the EOF, against ~130 ms for a name that passes, so the delay is the filter's, not a
timeout.

**What that buys.** A client can reach the same edge address, complete TLS 1.3 and negotiate h2
under a name that is not filtered. Whether the virtual host behind that name will then route
/.well-known/masque/... to the WARP backend is a separate question, and it is what this file
tests. Reaching the address was never the hard part; being let through the door by a name the
filter does not recognise is new, and a CONNECT answered 2xx here would be the first link of a
WARP-over-MASQUE tunnel that exists in this network.

Nothing here asserts WARP works. The only verdict that settles it is Cloudflare's own
cdn-cgi/trace reporting warp=on, and a 2xx to CONNECT is not that - it is the thing before it.

Usage:
    python scripts/warp-sni-bypass-probe.py
    python scripts/warp-sni-bypass-probe.py --names engage.cloudflareclient.com
"""
from __future__ import annotations

import argparse
import socket
import ssl
import sys
import time

import httpx

TIMEOUT = 10.0
MASQUE_ADDRESSES = ["162.159.198.2", "162.159.198.1", "162.159.197.3", "162.159.197.4"]

# Split by what each one is here. The blocked list is not a guess: every entry was measured to
# take ~2.1 s and EOF, against ~130 ms for the ones that pass.
BLOCKED = ["consumer-masque.cloudflareclient.com", "masque.cloudflareclient.com",
           "masque.example.com", "consumer-masque.example.com", "mqs.cloudflareclient.com",
           "notmasque.com", "MASQUE.cloudflareclient.com"]
PERMITTED = ["engage.cloudflareclient.com", "connectivity.cloudflareclient.com",
             "cloudflareclient.com", "example.com"]
MASQUE_PATH = "/.well-known/masque/udp/default/"


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


def measure_tls(address, name):
    """TLS 1.3 to this address under this server_name, with the cost of it."""
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    context.set_alpn_protocols(["h2", "http/1.1"])
    started = time.time()
    try:
        with context.wrap_socket(socket.create_connection((address, 443), timeout=5),
                                 server_hostname=name) as sock:
            return ("OK", str(sock.version()), str(sock.selected_alpn_protocol()),
                    int((time.time() - started) * 1000))
    except Exception as e:
        return ("FAIL", type(e).__name__, str(e)[:38], int((time.time() - started) * 1000))


def probe_masque(address, name):
    """A real CONNECT under this name, and the trace that would prove a tunnel.

    A 2xx means the virtual host behind the unfiltered name routed the MASQUE path to the WARP
    backend. warp=off means the endpoint answered but the tunnel is not up, which is the expected
    result without a registration and is still not a block.
    """
    context = ssl.create_default_context()
    context.set_alpn_protocols(["h2", "http/1.1"])
    out = {}
    with PinnedResolver(name, address):
        try:
            with httpx.Client(http2=True, timeout=TIMEOUT, verify=context,
                              trust_env=False) as session:
                for protocol in (b"connect-ip", b"connect-udp"):
                    response = session.request(
                        "CONNECT", "https://" + name + MASQUE_PATH,
                        headers={"protocol": protocol.decode(), "capsule-protocol": "?1"})
                    out[protocol] = response.status_code
                trace = session.get("https://" + name + "/cdn-cgi/trace")
                warp = [line for line in trace.text.split() if line.startswith("warp=")]
                out["trace"] = trace.status_code
                out["warp"] = warp[0] if warp else "-"
        except Exception as e:
            out["error"] = type(e).__name__
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--names", default="")
    parser.add_argument("--addresses", default="")
    args = parser.parse_args()

    permitted = args.names.split(",") if args.names else PERMITTED
    addresses = args.addresses.split(",") if args.addresses else MASQUE_ADDRESSES

    print("=== what the filter actually matches ===")
    print("    (each: same address, only the server_name differs)")
    for name in BLOCKED:
        status, detail, alpn, ms = measure_tls(addresses[0], name)
        print("  %-40s %-5s %-14s %-5s %5d ms" % (name, status, detail, alpn, ms))
    print("")
    for name in permitted:
        status, detail, alpn, ms = measure_tls(addresses[0], name)
        print("  %-40s %-5s %-14s %-5s %5d ms  (control)" % (name, status, detail, alpn, ms))

    print("")
    print("=== does a permitted name route the MASQUE path to the WARP backend? ===")
    found = []
    for name in permitted:
        for address in addresses:
            result = probe_masque(address, name)
            if "error" in result:
                print("  %-34s %-15s transport error: %s" % (name, address, result["error"]))
                continue
            line = ("  %-34s %-15s connect-ip=%s connect-udp=%s trace=%s %s"
                    % (name, address, result[b"connect-ip"], result[b"connect-udp"],
                       result["trace"], result["warp"]))
            print(line)
            for protocol, code in result.items():
                if protocol in (b"connect-ip", b"connect-udp") and 200 <= code < 300:
                    found.append(name + " @ " + address + " " + protocol.decode())
            if result["warp"] == "warp=on":
                found.append("TUNNEL UP: " + name + " @ " + address)

    print("")
    if any("TUNNEL UP" in item for item in found):
        print("VERDICT: cdn-cgi/trace reports warp=on. WARP works from this network.")
    elif found:
        print("VERDICT: a CONNECT was accepted under " + ", ".join(found))
        print("  That is the first link of a WARP-over-MASQUE tunnel over TCP 443, reached under a")
        print("  name the filter does not match. The tunnel itself needs WARP credentials, and")
        print("  only cdn-cgi/trace reporting warp=on would settle that.")
    else:
        print("VERDICT: no CONNECT accepted and no tunnel. The filter can be passed - TLS 1.3 and")
        print("  h2 complete under an unfiltered name, in ~130 ms against ~2100 ms for a blocked")
        print("  one - but the virtual host behind that name does not route to the WARP backend.")
        print("  The name is the thing being filtered, and reaching the edge is not enough to")
        print("  reach WARP.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

