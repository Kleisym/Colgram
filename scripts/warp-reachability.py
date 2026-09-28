"""Is Cloudflare WARP usable from this network?

Why this question, in one line: WARP is the only remaining route that is neither a public proxy
nor a server to rent, and the user raised it himself. Whether it is worth building anything around
it depends on two facts that can be measured in seconds:

  1. the registration API (api.cloudflareclient.com) - what RKN blocks first;
  2. the WireGuard data plane (UDP 1640/2408/4500 on Cloudflare's ingress ranges) - without a
     reachable endpoint, a registered account still cannot tunnel.

A third check is included because it is free and decisive about egress: Cloudflare's own
cdn-cgi/trace reports whether the request left through WARP. Reaching it proves the control plane
is alive; it does not prove the tunnel works, and the two are reported separately so the
difference is not lost.
"""
import json
import socket
import ssl
import struct
import sys
import time
import urllib.request

API = "api.cloudflareclient.com"
INGRESSES = ["162.159.192.1", "162.159.193.1", "188.114.96.1", "188.114.97.1",
             "141.101.69.1", "162.159.195.1"]
UDP_PORTS = [1640, 2408, 4500]


def https(url, timeout=6):
    t0 = time.time()
    try:
        req = urllib.request.Request(url, headers={
            "User-Agent": "WARP/0.11.0",
            "Accept": "application/json",
        })
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read(400)
        return "ok %d %.0fms %s" % (r.status, (time.time() - t0) * 1000,
                                    body[:120].decode("utf-8", "replace"))
    except Exception as e:
        return "fail %.0fms %s: %s" % ((time.time() - t0) * 1000, type(e).__name__, e)


def tcp(ip, port, timeout=3):
    t0 = time.time()
    try:
        s = socket.create_connection((ip, port), timeout)
        ms = int((time.time() - t0) * 1000)
        s.close()
        return "open %dms" % ms
    except Exception as e:
        return "closed/unreachable %s" % type(e).__name__


def describe(data):
    """What a reply can be, judged by its shape rather than by the fact that it arrived.

    Three times in this project a bare "answered" was wrong: a 31-byte stateless reset read as a
    QUIC service, a 12-byte stub on a Cisco resolver read as an open QUIC port, and an httpx SNI
    override turning 200 into 403 read as Cloudflare refusing. A reply is evidence that a path is
    open. It is evidence that a *service* is running only when it is a packet the service could
    only have produced - which for QUIC means a real version and non-zero connection ids.
    """
    if len(data) < 7 or not (data[0] & 0x80):
        return "reply (not a QUIC packet)"
    version = int.from_bytes(data[1:5], "big")
    dcid = data[5]
    if version in (0, 1) and dcid != 0:
        return "answered (real QUIC)"
    return "stub (version 0x%08x, dcid len %d)" % (version, dcid)


def udp_probe(ip, port, timeout=3):
    """Send a WireGuard-style init packet and see whether anything comes back.

    A live endpoint that is merely not answering would be indistinguishable from a filtered one,
    so an ICMP error (which surfaces as an OS error on the next read/write) is the useful signal:
    it means the network delivered the packet and something refused it, as opposed to silence.

    Two things were wrong with that and are fixed here. The payload was 148 bytes - below the
    ~1200-byte floor this network has, so a filtered port and an unfilled buffer were the same
    reading - and any reply was reported as "answered", which counts a 31-byte stateless reset or a
    12-byte stub as a service. Both have produced wrong verdicts in this project before. The packet
    is now 1200 bytes and the reply is described, not assumed.
    """
    pkt = struct.pack("<BBI", 1, 0, 0) + b"\x00" * 32 + struct.pack("<Q", int(time.time())) + b"\x00" * 64
    pkt += b"\x00" * (1200 - len(pkt))
    t0 = time.time()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    try:
        s.sendto(pkt, (ip, port))
        data, _ = s.recvfrom(2048)
        return "%s %d bytes in %dms" % (describe(data), len(data),
                                        int((time.time() - t0) * 1000))
    except socket.timeout:
        return "silent (no reply, no ICMP)"
    except OSError as e:
        return "oserror %s (delivered and refused)" % e
    finally:
        s.close()


print("== DNS")
try:
    print("   %s -> %s" % (API, sorted({i[4][0] for i in socket.getaddrinfo(API, 443, socket.AF_INET)})))
except Exception as e:
    print("   resolve failed:", e)

print("== WARP control plane (HTTPS)")
print("   registration:", https("https://%s/v0a2376/reg" % API))
print("   trace       :", https("https://www.cloudflare.com/cdn-cgi/trace"))

print("== WireGuard data plane (UDP)")
for ip in INGRESSES:
    for port in UDP_PORTS:
        print("   %-16s %-5d %s" % (ip, port, udp_probe(ip, port)))
    break  # one address is enough to answer "reachable or not"; the rest add noise

print("== TCP fallbacks Cloudflare uses for restricted networks")
for ip, port in [(INGRESSES[0], 443), ("162.159.192.1", 443), ("api.cloudflareclient.com", 443)]:
    print("   %-24s %-5d %s" % (ip, port, tcp(ip, port)))
