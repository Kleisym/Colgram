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
import time
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

    def test_aLengthPrefixSplitAcrossTwoSegmentsStillFrames(self) -> None:
        """A TCP read returns what has arrived, not the two bytes asked for.

        This is the case a relay gets wrong by accident. `client.recv(2)` on a prefix that
        arrived one byte at a time returns a single byte, which unpacks as a 256-byte frame, and
        the *next* frame's first byte is then consumed as the rest of that length. The framing
        desynchronises and the connection dies mid-stream. A 148-byte WireGuard initiation is
        exactly the traffic that triggers it: written in one burst, free to arrive as three
        segments, and the first segment boundary can fall inside the two-byte length.
        """
        client = socket.create_connection(self.address, timeout=5)
        try:
            initiation = self._initiation()
            header = relay.HEADER.pack(len(initiation))
            self.assertEqual(2, len(header))
            # One byte, pause, then the rest - a segment boundary inside the length prefix.
            client.sendall(header[:1])
            time.sleep(0.3)
            client.sendall(header[1:] + initiation)

            body = self._read_frame(client, timeout=6)
            self.assertIn(b"ANSWERED", body,
                          "a length prefix split across segments desynchronised the framing")
        finally:
            client.close()

    def test_severalFramesInOneWriteAreAllForwarded(self) -> None:
        """A real client does not flush per frame; three in one write is the normal case.

        Each of these also checks the answers come back in order, because a relay that
        reorders or coalesces them is not a bridge even when every individual frame survives.
        """
        client = socket.create_connection(self.address, timeout=5)
        try:
            sizes = (148, 92, 256)
            burst = b""
            for size in sizes:
                burst += relay.HEADER.pack(size) + bytes(size)
            client.sendall(burst)

            for size in sizes:
                body = self._read_frame(client, timeout=6)
                self.assertEqual(b"ANSWERED:" + str(size).encode(), body,
                                 "frames were lost, reordered or merged in a single write")
        finally:
            client.close()

    def test_theUdpListenerCarriesARealHandshakeToTheEndpoint(self) -> None:
        """The UDP path is the one Colgram can actually use, so it gets the same proof.

        A TCP bridge and a UDP bridge look identical in a test that only checks "something came
        back", and this file already has the TCP half. What matters about UDP is that a sing-box
        WireGuard endpoint can reach it at all: a WireGuard peer is a UDP peer, so the datagram has
        to cross unmodified and come back unmodified. A real 148-byte message-initiation going out
        and a real answer coming back is that, and a listener that accepted the socket and dropped
        everything would fail it.
        """
        listener = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 0))
        client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        client.settimeout(6)
        try:
            threading.Thread(target=relay.serve_udp_client,
                             args=(listener, self.endpoint.address, True),
                             daemon=True).start()
            initiation = self._initiation()
            client.sendto(initiation, listener.getsockname())
            answer, _ = client.recvfrom(2048)
            self.assertEqual(b"ANSWERED:" + str(len(initiation)).encode(), answer,
                             "the UDP listener did not carry the datagram out and back")
        finally:
            client.close()

    @staticmethod
    def _initiation() -> bytes:
        initiation = struct.pack("<IB", 1, 0) + bytes(range(32)) + bytes(range(32, 64))
        return initiation + b"\x00" * (148 - len(initiation))

    @staticmethod
    def _read_frame(client: socket.socket, timeout: float) -> bytes:
        """One length-prefixed frame, read to completion - the client side needs the same care
        the relay's own reading needed, or a test can pass on a relay that is actually broken."""
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
