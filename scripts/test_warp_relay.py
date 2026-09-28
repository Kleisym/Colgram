"""Does the WARP relay actually bridge, or does it merely accept connections?

The point of the test is the RELAY, not Cloudflare. A relay that accepts a connection and quietly
drops everything would pass any check that only looks at the accept, so the far side is made
trivially answerable and the assertion is that a frame comes back.
"""
from __future__ import annotations

import importlib.util
import socket
import struct
import threading
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("warp_relay_server",
                                              HERE / "warp-relay-server.py")
relay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relay)


class EchoEndpoint:
    """A UDP endpoint that answers anything, standing in for a reachable WireGuard ingress."""

    def __init__(self) -> None:
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.settimeout(0.2)
        self.address = self.sock.getsockname()
        self.running = True
        self.thread = threading.Thread(target=self._serve, daemon=True)
        self.thread.start()

    def _serve(self) -> None:
        while self.running:
            try:
                data, peer = self.sock.recvfrom(2048)
            except (socket.timeout, OSError):
                continue
            try:
                self.sock.sendto(b"ANSWERED:" + str(len(data)).encode(), peer)
            except OSError:
                return

    def close(self) -> None:
        self.running = False
        self.sock.close()


class RelayBridgeTest(unittest.TestCase):
    """A relay that only accepts connections is not a relay."""

    def setUp(self) -> None:
        self.endpoint = EchoEndpoint()
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
                             args=(client, self.endpoint.address, True), daemon=True).start()

    def tearDown(self) -> None:
        self.running = False
        self.endpoint.close()
        self.listener.close()

    def test_aFrameGoesOutAndAnAnswerComesBack(self) -> None:
        client = socket.create_connection(self.address, timeout=5)
        try:
            # A WireGuard message-initiation is 148 bytes. The size matters: a small UDP payload is
            # exactly what the blocked network drops, so a relay that only worked with large
            # frames would look fine and carry nothing.
            initiation = struct.pack("<IB", 1, 0) + bytes(range(32)) + bytes(range(32, 64))
            initiation += b"\x00" * (148 - len(initiation))
            self.assertEqual(148, len(initiation))
            client.sendall(relay.HEADER.pack(len(initiation)) + initiation)
            client.settimeout(6)
            head = client.recv(2)
            self.assertEqual(2, len(head), "the relay sent no frame header back")
            (length,) = relay.HEADER.unpack(head)
            self.assertGreater(length, 0)
            body = client.recv(length)
            self.assertIn(b"ANSWERED", body,
                          "the relay connected but never forwarded the payload onward")
        finally:
            client.close()

    def test_aFrameLongerThanTheLimitIsRefusedRatherThanBuffered(self) -> None:
        # A hostile or broken client must not be able to make the relay allocate without bound.
        client = socket.create_connection(self.address, timeout=5)
        try:
            client.sendall(relay.HEADER.pack(relay.MAX_FRAME + 1))
            client.settimeout(3)
            self.assertEqual(b"", client.recv(2),
                             "an oversized frame must be dropped, not answered")
        finally:
            client.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
