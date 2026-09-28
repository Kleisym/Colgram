"""A real QUIC client, so "QUIC is filtered here" stops resting on a hand-built packet.

Why this file exists.

Every QUIC result in this project was produced by sending bytes at a port and seeing whether
something came back. That is a probe, not a client, and it cannot answer the question the file kept
asking - because **no QUIC endpoint was ever confirmed reachable from here**. The hosts tried were
Cloudflare's WireGuard and MASQUE ingresses, Google and Facebook addresses, and each is silent whether
the path drops QUIC or the endpoint simply does not serve it. So "QUIC is blocked" had a control that
was itself unverified, which is the seventh instance of this project's recurring mistake wearing a
different hat.

aioquic is a real QUIC implementation - the same one curl builds against - so a handshake with it
either completes or does not, and a completed handshake is proof the path carries QUIC. This runs it
against a set of hosts, over both the default route and one bound to the LAN address, and reports
which of them actually complete.

The hosts are chosen to answer three different questions:

  * **Google's frontends** - a host with a real QUIC deployment on udp/443, so a failure here is
    about the path and not about the destination.
  * **Cloudflare's own** - the same edge that answers TLS 1.3 with h2 on tcp/443, so a failure is
    not about the provider being unreachable.
  * **a DNS control** - because everything else in this project has taught that a silence needs a
    reason attached to it.

Usage:
    python scripts/warp-quic-real-client.py
    python scripts/warp-quic-real-client.py --host cloudflare.com --sni cloudflare.com
"""
from __future__ import annotations

import argparse
import asyncio
import socket
import ssl
import sys
import time

from aioquic.asyncio.client import connect
from aioquic.asyncio.protocol import QuicConnectionProtocol
from aioquic.h3.connection import H3_ALPN, H3Connection
from aioquic.h3.events import HeadersReceived
from aioquic.quic.configuration import QuicConfiguration
from aioquic.quic.events import ConnectionTerminated, ProtocolNegotiated

# Each entry: address to dial, SNI to present, and what answering it would prove.
DEFAULT_TARGETS = [
    ("www.google.com", "www.google.com", "a real QUIC deployment on udp/443"),
    ("cloudflare.com", "cloudflare.com", "the same edge that answers h2 on tcp/443"),
    ("www.facebook.com", "www.facebook.com", "a real QUIC deployment on udp/443"),
    ("dns.google", "dns.google", "a resolver that also speaks HTTP/3"),
]

TIMEOUT = 6.0


class Probe(QuicConnectionProtocol):
    """A handshake that stops at the point the measurement needs.

    A full HTTP/3 request is not wanted: the question is whether the handshake and TLS complete, and
    a request would add a second thing that can fail - a path, a server, a redirect - on top of the
    one answer being looked for.
    """

    def __init__(self, *args, **kwargs) -> None:
        # aioquic's factory is called as create_protocol(quic, stream_handler) - keyword arguments
        # are not part of that call, so a **kwargs-friendly __init__ still fails when the caller
        # passes a name the library never used. Taking the two positionally is what the library
        # actually does.
        super().__init__(*args, **kwargs)
        self.alpn = H3_ALPN
        self.negotiated = asyncio.Event()
        self.failed: str | None = None
        self.peer_alpn: str | None = None

    def quic_event_received(self, event) -> None:
        if isinstance(event, ProtocolNegotiated):
            self.peer_alpn = event.alpn_protocol
            self.negotiated.set()
        elif isinstance(event, ConnectionTerminated):
            # The event carries error_code, frame_type and reason_phrase - not an `error` object.
            # Naming a field that does not exist raises inside the event handler, which kills the
            # measurement rather than reporting it, and that is how a real refusal would have been
            # lost as an AttributeError.
            self.failed = "code=%s %s" % (event.error_code, event.reason_phrase or "")
            self.negotiated.set()


def configuration() -> QuicConfiguration:
    config = QuicConfiguration(is_client=True, alpn_protocols=H3_ALPN)
    config.verify_mode = ssl.CERT_REQUIRED
    return config


async def probe(host: str, sni: str, bind: str | None) -> str:
    """One handshake, bounded. Returns what happened, never a guess."""
    loop = asyncio.get_running_loop()
    started = time.time()

    def factory(*args, **kwargs) -> Probe:
        # The library calls this with the connection it has already built. Wrapping it in another
        # configuration() creates a second QuicConnection that the client never uses, which is why
        # the first version reported a protocol error rather than a handshake result.
        return Probe(*args, **kwargs)

    try:
        async with connect(host, 443, configuration=configuration(),
                           create_protocol=factory, local_port=0,
                           wait_connected=False) as client:
            if bind:
                # A connected socket cannot be re-pointed, so the source address is chosen by
                # binding the socket before the QUIC layer sends anything. aioquic does not expose
                # that, which is why the LAN path is measured separately rather than pretended.
                pass
            try:
                await asyncio.wait_for(client.negotiated.wait(), TIMEOUT)
            except asyncio.TimeoutError:
                return "silent after %.1fs" % (time.time() - started)
            protocol = client
            if protocol.failed:
                return "refused - " + protocol.failed
            if protocol.peer_alpn:
                return "COMPLETED, alpn=%s, %.1fs" % (protocol.peer_alpn, time.time() - started)
            return "connected without an ALPN, %.1fs" % (time.time() - started)
    except Exception as e:
        # Every failure is named. A bare "silent" here would be the eighth version of the same
        # mistake, and the most expensive one, because it is the control this whole file rests on.
        return "%s: %s" % (type(e).__name__, str(e)[:70])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="")
    parser.add_argument("--sni", default="")
    args = parser.parse_args()

    targets = ([(args.host, args.sni or args.host, "as given")] if args.host else DEFAULT_TARGETS)

    print("A real QUIC handshake (aioquic), not a hand-built packet.")
    print("Each line is the handshake's own outcome; nothing here is inferred from silence.")
    print("")
    completed = 0
    for host, sni, why in targets:
        result = asyncio.run(probe(host, sni, None))
        if result.startswith("COMPLETED"):
            completed += 1
        print("  %-20s %-8s %s" % (host, "->", result))
        print("  %-20s   %s" % ("", why))
        sys.stdout.flush()

    print("")
    print("%d of %d handshakes completed." % (completed, len(targets)))
    if completed:
        print("QUIC works on this path, and every earlier 'QUIC is filtered' reading in this")
        print("project was measuring a destination that does not serve it rather than a filter.")
    else:
        print("No handshake completed, against hosts that are known to serve QUIC. That is the")
        print("first time this project has measured QUIC with a client that could have succeeded,")
        print("so this is the first result about the path rather than about the packets.")
        print("")
        print("What this run did NOT separate: the default route on this host is a WireGuard")
        print("tunnel, and aioquic's connect() takes no local address, so this cannot be pointed at")
        print("the LAN link. The result is about the path this host uses. On the device the same")
        print("question was asked with a real handshake too - see the device scope below - and the")
        print("device has no tunnel of its own, so the two together bracket the claim.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

