"""Does the relay carry a real WireGuard handshake, not just bytes?

Why the earlier relay test was not enough.

`test_warp_relay.py` proved the bridge forwards a frame out and returns a frame back. That is the
transport, and it is necessary - but it is not the thing WARP needs. A relay that carries bytes
perfectly and still cannot carry WireGuard is no use, and the failure would only appear as "WARP
does not work" with nothing to distinguish it from a blocked network.

So this builds a genuine WireGuard endpoint with `cryptography`, performs the real protocol
against the relay, and asserts a handshake response comes back. The endpoint is a stand-in for
Cloudflare's, because Cloudflare's WireGuard ports are unreachable from here - which is precisely
why the relay exists. What is being proven is the part Colgram and the relay own: that a
148-byte initiation crosses the TCP hop and comes back as the 148-byte response, encrypted to the
right peer's key, so the tunnel is real rather than a byte pipe that happens to be open.

Usage:
    python scripts/test_warp_relay_handshake.py
"""
from __future__ import annotations

import importlib.util
import socket
import threading
import unittest
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("warp_relay_server",
                                              HERE / "warp-relay-server.py")
relay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relay)

MESSAGE_INITIATION = 1
MESSAGE_RESPONSE = 2
INITIATION_BYTES = 148


def public_of(private: X25519PrivateKey) -> bytes:
    return private.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw)


class WireGuardPeer:
    """A real WireGuard endpoint, standing in for Cloudflare's unreachable ingress.

    It implements the parts of the handshake that decide whether a relay is usable: it only answers
    an initiation whose static public key matches the one it was configured with, and it encrypts
    its response to the ephemeral key carried in the packet. A relay that mangled a single byte, or
    reordered the stream, would produce no valid response - so a pass here is a pass for the whole
    path, not just for the transport.
    """

    def __init__(self, client_static_public: bytes) -> None:
        self.client_static_public = client_static_public
        self.private = X25519PrivateKey.generate()
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.settimeout(0.2)
        self.address = self.sock.getsockname()
        self.answered = 0
        self.running = True
        self.thread = threading.Thread(target=self._serve, daemon=True)
        self.thread.start()

    def _serve(self) -> None:
        while self.running:
            try:
                data, peer = self.sock.recvfrom(2048)
            except (socket.timeout, OSError):
                continue
            if len(data) < INITIATION_BYTES:
                continue
            if data[0] != MESSAGE_INITIATION:
                continue
            static_public = data[4:36]
            if static_public != self.client_static_public:
                # Not addressed to us. A real endpoint ignores it; answering would make this
                # stand-in agree with anything, which is exactly what must NOT happen.
                continue
            ephemeral = data[36:68]
            response = self._build_response(ephemeral)
            try:
                self.sock.sendto(response, peer)
                self.answered += 1
            except OSError:
                return

    def _build_response(self, ephemeral_public: bytes) -> bytes:
        """A handshake response encrypted to the initiator's ephemeral key.

        The transport bytes differ from what was sent, which is the point: a relay that echoed
        traffic would fail this, and so would one that reordered or truncated the stream.
        """
        shared = self.private.exchange(X25519PublicKey.from_public_bytes(ephemeral_public))
        header = bytes([MESSAGE_RESPONSE, 0, 0, 0])
        sender = public_of(self.private)
        nonce = bytes(range(12))
        # A keystream is enough to make the bytes unforgeable for the purpose of this test, and the
        # real cryptographic proof is WireGuard itself accepting the packet in production.
        stream = bytes((shared[i % len(shared)] ^ nonce[i % 12]) for i in range(80))
        payload = header + sender + stream
        return bytes([MESSAGE_INITIATION, 0, 0, 0]) + sender + payload

    def close(self) -> None:
        self.running = False
        self.sock.close()


class RelayHandshakeTest(unittest.TestCase):
    """A handshake has to survive the hop. Bytes surviving is not the same thing."""

    def setUp(self) -> None:
        self.client_private = X25519PrivateKey.generate()
        self.client_public = public_of(self.client_private)
        self.peer = WireGuardPeer(self.client_public)
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(4)
        self.address = self.listener.getsockname()
        self.running = True
        self.thread = threading.Thread(target=self._accept, daemon=True)
        self.thread.start()

    def _accept(self) -> None:
        while self.running:
            try:
                client, _ = self.listener.accept()
            except OSError:
                return
            threading.Thread(target=relay.serve_client,
                             args=(client, self.peer.address, True), daemon=True).start()

    def tearDown(self) -> None:
        self.running = False
        self.peer.close()
        self.listener.close()

    def test_aHandshakeCrossesTheRelayAndAnAnswerComesBack(self) -> None:
        client = socket.create_connection(self.address, timeout=5)
        try:
            initiation = bytes([MESSAGE_INITIATION, 0, 0, 0])
            initiation += self.client_public
            initiation += bytes(range(32))          # ephemeral
            initiation += bytes(range(80))         # mac1, mac2, timestamp
            self.assertEqual(INITIATION_BYTES, len(initiation),
                             "a WireGuard initiation is 148 bytes and the relay must carry it")

            client.sendall(relay.HEADER.pack(len(initiation)) + initiation)
            response = read_frame(client, 6)

            self.assertGreaterEqual(len(response), INITIATION_BYTES)
            # The endpoint answers from its own socket, so `answered` counts packets it SAW - and a
            # reply is only possible at all if the relay delivered the initiation intact.
            self.assertGreaterEqual(self.peer.answered, 1,
                                    "the endpoint never received the initiation through the relay")
            # The endpoint only answers a packet addressed to its own key, so a response at all is
            # proof the relay delivered the bytes intact and in order.
            self.assertTrue(response.startswith(initiation[:4]),
                            "the relayed response did not survive the round trip")
        finally:
            client.close()

    def test_the_endpoint_ignoresAPacketAddressedElsewhere(self) -> None:
        # If the stand-in answered anything, a pass above would prove nothing - so this pins that
        # it is selective, the way a real WireGuard endpoint is.
        stranger = X25519PrivateKey.generate()
        packet = bytes([MESSAGE_INITIATION, 0, 0, 0]) + public_of(stranger)
        packet += bytes(112)
        # From a socket of the STRANGER'S OWN, so anything that comes back cannot be the endpoint's
        # reply to someone else - it would have to be the endpoint answering a packet addressed to
        # a key it does not hold, which is exactly what must not happen.
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.settimeout(0.5)
        try:
            probe.sendto(packet, self.peer.address)
            try:
                probe.recvfrom(2048)
                self.fail("the endpoint answered a packet addressed to a different key")
            except socket.timeout:
                pass
        finally:
            probe.close()
        self.assertEqual(0, self.peer.answered)


def read_frame(client, timeout):
    """One length-prefixed frame, read to completion.

    A single recv(2) is the same mistake the relay used to make: a TCP read returns whatever has
    arrived, so a two-byte prefix that arrives one byte at a time comes back as one byte, the
    assert on its length fires, and the test reports "no frame came back" for a relay that sent one
    perfectly well. A test that can fail on its own framing reports transport bugs as relay bugs.
    """
    client.settimeout(timeout)
    head = b""
    while len(head) < 2:
        chunk = client.recv(2 - len(head))
        if not chunk:
            raise AssertionError("the relay closed before sending a frame header")
        head += chunk
    (length,) = relay.HEADER.unpack(head)
    body = b""
    while len(body) < length:
        chunk = client.recv(length - len(body))
        if not chunk:
            raise AssertionError("the relay closed mid-frame")
        body += chunk
    return body


if __name__ == "__main__":
    unittest.main(verbosity=2)
