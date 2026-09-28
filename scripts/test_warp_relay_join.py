"""Would Colgram's WARP relay path actually connect? Traced against the relay, not assumed.

Why this exists.

Everything the relay has been credited with is on its own side of the wire. warp-relay-server.py
accepts TCP, reads 2-byte length-prefixed frames and forwards them as UDP datagrams, and two tests
prove that works. Nothing has ever checked the other end: whether Colgram speaks the relay's
protocol at all. It does not.

**The gap, exactly.** When a relay is configured, ColgramWarpTunnel puts the relay's address into
ColgramWarpProfileBuilder, which emits a sing-box profile containing:

    endpoints: [{ type: "wireguard", peers: [{ address: <relay>, port: <relay port> }] }]
    outbounds: [{ type: "direct", tag: "direct" }]

A WireGuard endpoint dials its peer over **UDP**. The relay listens on **TCP** - SOCK_STREAM, see
line 171 of the server - and speaks a length-prefixed framing that no standard UDP sender produces.
So the app sends UDP datagrams at a port that is reading a TCP handshake, and waits. The tunnel
never comes up, and from the settings screen that is identical to a blocked network: the switch
turns blue, the row says connected, and nothing is routed.

The same swap of keys that makes the relay correct for identity - peer key *and* endpoint together,
since the relay terminates the handshake - is what makes it look plausible. A profile with the
relay's key and Cloudflare's address fails loudly; this one has the relay's key and the relay's
address, which is exactly what should work and does not, because the transport is wrong. The
symptom is silence, and silence is what this network produces for every other reason too.

**What this test asserts.** Not that a tunnel comes up - that needs a relay on a host with clean
UDP, and it is out of reach here. It asserts the two things that can be checked without one:

  * the relay's listener transport and the app's peer transport must agree;
  * and the doc must keep recording which half is unmeasured.

A fix has to pick one of two honest shapes, and this test is written to accept either:

  * the relay listens on **UDP** as well, so a stock sing-box WireGuard endpoint can reach it; or
  * Colgram carries the relay's framing itself, as an outbound or endpoint that speaks
    length-prefixed frames over TCP, and the profile names that.

What it will not accept is a UDP peer pointing at a TCP listener, because that is the bug, and it
looks like everything else in this file's family: a configuration that validates, starts, and
routes nothing.

Usage:
    python scripts/test_warp_relay_join.py
"""
from __future__ import annotations

import ast
import importlib.util
import re
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import warp_relay_transports as _transports  # noqa: E402
CORE = HERE.parent / "colgram-core/src/main/java/org/colgram/core"
SERVER = HERE / "warp-relay-server.py"
BUILDER = CORE / "ColgramWarpProfileBuilder.java"
DOC = HERE / "warp-relay-wg-side.md"


def read_server() -> str:
    return SERVER.read_text(encoding="utf-8", errors="replace")


def read_builder() -> str:
    return BUILDER.read_text(encoding="utf-8", errors="replace")


class RelayTransportAgreesTest(unittest.TestCase):
    """A relay whose transport disagrees with the app's peer is not reachable, however correct
    everything else about it is."""

    def test_the_two_sides_agree_on_a_transport(self) -> None:
        server = read_server()
        builder = read_builder()

        # What the relay actually constructs, read from the parse tree rather than the text - see
        # _transports.listener_transports for why that distinction is load-bearing.
        transports = _transports.listener_transports(server)
        relay_accepts_udp = "SOCK_DGRAM" in transports
        relay_accepts_tcp = "SOCK_STREAM" in transports

        self.assertTrue(relay_accepts_tcp or relay_accepts_udp,
                        "the relay listens on neither TCP nor UDP, so this test needs re-reading")

        # What the app can produce. A sing-box WireGuard endpoint dials UDP, full stop; there is
        # no framing field in the profile for anything else.
        profile_dials_udp = '"type", "wireguard"' in builder and "SOCK_STREAM" not in builder
        colgram_carries_framing = any(
            marker in builder for marker in
            ("framing", "length-prefix", "lengthPrefix", "frame_header", "frameHeader"))

        self.assertTrue(
            relay_accepts_udp or colgram_carries_framing,
            "Colgram dials the relay with a sing-box WireGuard endpoint, which speaks UDP, while\n"
            "scripts/warp-relay-server.py listens on TCP with 2-byte length-prefixed frames.\n"
            "Nothing bridges the two, so the handshake never leaves the phone. Fix it one of\n"
            "two ways and this test will pass:\n"
            "  * give the relay a UDP listener alongside the TCP one, so a stock WireGuard\n"
            "    endpoint can reach it; or\n"
            "  * have Colgram speak the length-prefixed framing itself, as an outbound or\n"
            "    endpoint, and name that in the profile.")

        # And the two facts together are the real assertion: a UDP peer needs a UDP listener.
        if profile_dials_udp and relay_accepts_tcp and not relay_accepts_udp:
            self.fail("a UDP peer is pointed at a TCP-only listener - the handshake cannot leave "
                      "the phone")

    def test_the_relay_doc_still_records_the_unmeasured_half(self) -> None:
        """The doc's own table says the far side is unmeasured. It must not quietly claim
        otherwise, because that is how a gap like this gets talked into being a feature."""
        doc = DOC.read_text(encoding="utf-8", errors="replace")
        self.assertIn("not measured", doc,
                      "the relay doc no longer records which half is unmeasured")
        self.assertIn("UDP 2408", doc,
                      "the relay doc should still say the far side dials Cloudflare over UDP")


if __name__ == "__main__":
    unittest.main(verbosity=2)

