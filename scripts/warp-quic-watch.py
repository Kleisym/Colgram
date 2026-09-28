"""Does the filter ever open? Watch a real QUIC handshake over time rather than sampling once.

Why this file exists.

Every measurement in this project is a snapshot, and the project's own record says the filter
moves: the same DNS control on the same device returned 18 bytes at 05:21 and silence at 05:16,
with no code change. A snapshot cannot distinguish "this network blocks QUIC" from "this network
blocked QUIC when I looked", and those have completely different consequences - one is a wall to
build around, the other is a window to catch.

So this watches rather than samples. A real QUIC handshake is attempted on a fixed interval for as
long as it is left running, and a control runs on every iteration, because an iteration whose control
did not answer says nothing at all. The output is a timeline, not a verdict: the only claim it can
support is what it actually saw, at the times it saw it.

Usage:
    python scripts/warp-quic-watch.py --minutes 20
    python scripts/warp-quic-watch.py --minutes 20 --interval 20
"""
from __future__ import annotations

import argparse
import asyncio
import ssl
import sys
import time
from datetime import datetime

from aioquic.asyncio.client import connect
from aioquic.asyncio.protocol import QuicConnectionProtocol
from aioquic.h3.connection import H3_ALPN
from aioquic.quic.configuration import QuicConfiguration
from aioquic.quic.events import ConnectionTerminated, ProtocolNegotiated

# One known-good QUIC deployment, and the resolver that must answer on every iteration.
PROBE_HOST = "www.google.com"
PROBE_PORT = 443
CONTROL_HOST = "1.1.1.1"
CONTROL_PORT = 53
PROBE_TIMEOUT = 4.0
CONTROL_TIMEOUT = 3.0


class Probe(QuicConnectionProtocol):
    """A handshake recorded, not inferred: the event that ended it is the result."""

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.alpn = H3_ALPN
        self.done = asyncio.Event()
        self.outcome = "silent"
        self.detail = ""

    def quic_event_received(self, event) -> None:
        if isinstance(event, ProtocolNegotiated):
            self.outcome = "HANDSHAKE COMPLETED, alpn=" + str(event.alpn_protocol)
            self.done.set()
        elif isinstance(event, ConnectionTerminated):
            self.outcome = "refused"
            self.detail = "code=%s %s" % (event.error_code, event.reason_phrase or "")
            self.done.set()


def configuration() -> QuicConfiguration:
    config = QuicConfiguration(is_client=True, alpn_protocols=H3_ALPN)
    config.verify_mode = ssl.CERT_REQUIRED
    return config


async def attempt_quic() -> str:
    started = time.time()
    def factory(*args, **kwargs) -> Probe:
        return Probe(*args, **kwargs)
    try:
        async with connect(PROBE_HOST, PROBE_PORT, configuration=configuration(),
                           create_protocol=factory, wait_connected=False) as client:
            try:
                await asyncio.wait_for(client.done.wait(), PROBE_TIMEOUT)
            except asyncio.TimeoutError:
                return "silent"
            return "%s %.1fs %s" % (client.outcome, time.time() - started, client.detail)
    except Exception as e:
        return "%s: %s" % (type(e).__name__, str(e)[:50])


def dns_query() -> bytes:
    labels = b"".join(bytes([len(p)]) + p.encode() for p in "cloudflare.com".split("."))
    # Written as escapes rather than literal bytes: a DNS header pasted as raw
    # octal becomes null bytes the moment it passes through a tool that treats
    header = bytes([0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])
    trailer = bytes([0x00, 0x00, 0x01, 0x00, 0x01])
    return header + labels + trailer


def control() -> str:
    import socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(CONTROL_TIMEOUT)
    try:
        sock.sendto(dns_query(), (CONTROL_HOST, CONTROL_PORT))
        data, _ = sock.recvfrom(2048)
        return "answered %dB" % len(data)
    except socket.timeout:
        return "SILENT"
    except OSError as e:
        return type(e).__name__
    finally:
        sock.close()


async def main_async(minutes: float, interval: float) -> int:
    deadline = time.time() + minutes * 60
    iteration = 0
    completed = 0
    valid = 0
    print("watching %s:%d for %.0f min, every %.0fs"
          % (PROBE_HOST, PROBE_PORT, minutes, interval), flush=True)
    print("a row counts only when its control answered", flush=True)
    print("", flush=True)
    while time.time() < deadline:
        iteration += 1
        mark = datetime.now().strftime("%H:%M:%S")
        ctl = control()
        if ctl.startswith("answered"):
            valid += 1
            result = await attempt_quic()
            if result.startswith("HANDSHAKE COMPLETED"):
                completed += 1
            print("  %s  control %-14s  quic %s" % (mark, ctl, result), flush=True)
        else:
            # Skipped, and said so. An iteration whose control was silent cannot tell a filtered
            # handshake from a moment when this host's own UDP was not getting out, and counting
            # it either way is how a filter that opens get written down as one that does not.
            print("  %s  control %-14s  (not counted - the control did not answer)"
                  % (mark, ctl), flush=True)

    print("", flush=True)
    print("%d iterations, %d with a working control, %d handshakes completed"
          % (iteration, valid, completed), flush=True)
    if completed:
        print("The window exists. A filter that opens is a different problem from one that does")
        print("not, and this is the measurement that separates them.")
    else:
        print("No window was seen in %.0f minutes with a working control on %d of %d iterations."
              % (minutes, valid, iteration))
        print("That is a statement about the time observed, not a proof about the network.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--minutes", type=float, default=20.0)
    parser.add_argument("--interval", type=float, default=15.0)
    args = parser.parse_args()
    return asyncio.run(main_async(args.minutes, args.interval))


if __name__ == "__main__":
    sys.exit(main())

