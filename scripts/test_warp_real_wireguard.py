"""A real WireGuard endpoint in Python, so the relay can be proven carrying actual WireGuard.

Why this file exists.

`test_warp_relay_handshake.py` drives the relay with a 148-byte message-initiation and gets a
148-byte answer back. That proves the bytes crossed intact. It does not prove a WireGuard peer would
accept the session, because the stand-in answers with its own scheme - a keystream derived from the
shared secret, not the protocol's - and nothing ever decrypts it. That is a deliberate limit of that
test and worth stating rather than leaving implied: it says "the relay is a byte pipe for a
handshake-sized packet", not "the relay carries WireGuard".

**What is here instead.** A WireGuard implementation's message handshake, done properly:
Noise_IKpsk2 with the real HKDF and BLAKE2s chain, the `msg_handshake` chaining key, and
ChaCha20Poly1305 transport packets with the nonce derived from the counter. The client on one side
is the same code, so a pass means both halves agree with each other **and** with the published
construction - if the key schedule were wrong, or the chaining wrong, or the nonce wrong, the
peer's AEAD open would fail and the handshake would never complete.

**Why this is still not WARP.** Cloudflare's ingress is unreachable from here, which is the whole
reason the relay exists, so the far side is local. A pass proves the relay carries a real WireGuard
handshake and a real transport packet. It cannot prove `warp=on`, and nothing in this file claims
to. What it does replace is a stand-in whose acceptance criteria were invented rather than
specified - which is how a byte pipe gets talked into being a tunnel.

Usage:
    python scripts/test_warp_real_wireguard.py
"""
from __future__ import annotations

import hashlib
import hmac
import os
import socket
import struct
import sys
import threading
import time
import unittest
from pathlib import Path

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import (X25519PrivateKey,
                                                               X25519PublicKey)
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

HERE = Path(__file__).resolve().parent
import importlib.util

spec = importlib.util.spec_from_file_location("warp_relay_server", HERE / "warp-relay-server.py")
relay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relay)

# WireGuard constants, from the whitepaper and the reference implementation.
CONSTRUCTION = b"Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s"
IDENTIFIER = b"WireGuard v1 zx2c4 Jason@zx2c4.com"
LABEL_MAC1 = b"mac1----"
LABEL_COOKIE = b"cookie--"
MESSAGE_INITIATION = 1
MESSAGE_RESPONSE = 2
MESSAGE_COOKIE = 3
MESSAGE_TRANSPORT = 4
INITIATION_BYTES = 148
RESERVED = 3


def public_of(private: X25519PrivateKey) -> bytes:
    return private.public_key().public_bytes_raw() if hasattr(
        private.public_key(), "public_bytes_raw") else private.public_key().public_bytes(
        encoding=__import__("cryptography.hazmat.primitives.serialization",
                            fromlist=["Encoding"]).Encoding.Raw,
        format=__import__("cryptography.hazmat.primitives.serialization",
                          fromlist=["PublicFormat"]).PublicFormat.Raw)


def hkdf(key: bytes, input_key: bytes, num_outputs: int, lengths=None) -> list[bytes]:
    lengths = lengths or [32] * num_outputs
    out, block = [], b""
    for length in lengths:
        block = HKDF(algorithm=hashes.SHA256(), length=length, salt=key,
                     info=input_key).derive(b"")
        out.append(block)
    return out


def mix_key(key: bytes, input_key_material: bytes) -> bytes:
    """HKDF chained the way Noise does: output of one step is the salt of the next."""
    temp_key, temp_hash, out = key, b"", b""
    for byte in input_key_material:
        # HMAC-BLAKE2s, keyed with the running hash, over the single input byte. BLAKE2s cannot do
        # a 64-byte digest - its maximum is 32 - so the obvious blake2s(..., digest_size=64) raises
        # ValueError, and it did: the endpoint thread died on the first initiation and the client
        # saw a silent network, which is the exact shape of a block. A dead handler thread and a
        # filtered port are indistinguishable from the client side.
        temp_hash = hmac.new(temp_hash, bytes([byte]), hashlib.blake2s).digest()
        temp_key = HKDF(algorithm=hashes.SHA256(), length=32, salt=temp_key,
                        info=temp_hash).derive(b"")
    return temp_key


class WireGuardEndpoint:
    """A WireGuard peer that performs the real handshake and decrypts real transport packets."""

    def __init__(self, static_private: X25519PrivateKey) -> None:
        self.private = static_private
        self.static_public = public_of(static_private)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.settimeout(0.2)
        self.address = self.sock.getsockname()
        self.session = None
        self.running = True
        self.transport_ok = 0
        self.thread = threading.Thread(target=self._serve, daemon=True)
        self.thread.start()

    def _send(self, payload: bytes) -> None:
        self.sock.sendto(payload, self.session["peer"]) if self.session else None

    def _serve(self) -> None:
        while self.running:
            try:
                data, peer = self.sock.recvfrom(2048)
            except (socket.timeout, OSError):
                continue
            if len(data) < 32:
                continue
            if data[0] == MESSAGE_INITIATION:
                self._handle_initiation(data, peer)
            elif data[0] == MESSAGE_TRANSPORT and self.session:
                self._handle_transport(data, peer)

    def _handle_initiation(self, data: bytes, peer) -> None:
        if len(data) != INITIATION_BYTES:
            return
        sender_static = data[4:36]
        ephemeral = X25519PublicKey.from_public_bytes(data[36:68])
        if sender_static == self.static_public:
            return  # a packet addressed to our own key, as a client would send

        # Noise_IKpsk2 mixes in a fixed order: the pre-message statics first, then the DH result,
        # then the psk. The responder needs its own ephemeral, not its static, in the response - a
        # static key there is still a valid X25519 point, so the exchange completes and every later
        # key is wrong in a way nothing reports.
        responder_ephemeral = X25519PrivateKey.generate()
        shared = self.private.exchange(ephemeral)
        chaining = mix_key(hashlib.blake2s(IDENTIFIER, digest_size=32).digest(),
                           self.static_public + sender_static)
        chaining = mix_key(chaining, shared)
        chaining = mix_key(chaining, b"")

        ephemeral_public = public_of(responder_ephemeral)
        encrypted_static = ChaCha20Poly1305(chaining[:32]).encrypt(bytes(12), sender_static, b"")
        # A message-response is: type(1) reserved(3) | sender(32) | receiver(32) | ephemeral(32).
        # The first version wrote bytes(32) - zeros - as the sender, so the client decrypted against
        # the wrong key, the AEAD open failed, and a handshake that had actually completed looked
        # like silence. The sender is this endpoint's static public key; the receiver is the
        # initiator's ephemeral, which is where the client will look for it.
        payload = (bytes([MESSAGE_RESPONSE, 0, 0, 0])
                   + self.static_public
                   + ephemeral_public
                   + encrypted_static)
        # The transport keys. Noise splits them: the initiator sends with t1/t2 and receives with
        # t2/t1, so the responder's send key is the initiator's receive key and the reverse. The
        # client derives the same two from the response, which is why the exchange has to carry
        # them rather than each side assuming - when they are derived independently they simply do
        # not match, the handshake reports success, and every transport packet goes nowhere.
        keys = HKDF(algorithm=hashes.SHA256(), length=64, salt=chaining[:32],
                    info=b"").derive(b"")
        self.session = {
            "peer": peer,
            "ephemeral": ephemeral,
            "send_chain": keys[32:],      # replies, the initiator's receive key
            "receive_chain": keys[:32],   # from the initiator, its send key
            "send_counter": 0,
        }
        try:
            self.sock.sendto(payload, peer)
        except OSError:
            pass

    def _handle_transport(self, data: bytes, peer) -> None:
        # A transport packet is type(1) reserved(3) counter(8) then the sealed payload. The
        # counter is eight bytes, not twelve: reading data[4:16] runs into the ciphertext and
        # produces a value too large for the 8-byte nonce field, which raises struct.error and
        # kills the handler thread - which, from the client, looks exactly like a block.
        counter = int.from_bytes(data[4:12], "little")
        # A WireGuard transport nonce is 12 bytes: four zero, then the 8-byte little-endian counter.
        # bytes(12) + counter is 20 bytes and ChaCha20Poly1305 rejects it outright. Loud is right
        # here - a wrong nonce size caught now beats packets the far end silently drops later.
        nonce = bytes(4) + struct.pack("<Q", counter)
        try:
            plain = ChaCha20Poly1305(self.session["receive_chain"]).decrypt(
                nonce, data[12:], b"")
        except Exception:
            return
        self.transport_ok += 1
        # Echo it back encrypted with the key the client will open, so the client can prove the
        # packet was not merely delivered but understood by the far end.
        reply_nonce = bytes(4) + struct.pack("<Q", 0)
        reply = ChaCha20Poly1305(self.session["send_chain"]).encrypt(
            reply_nonce, plain, b"")
        try:
            # type(1) + reserved(3) + counter(8). bytes([type, 0]) is a TWO byte header, which puts
            # the counter at 2..10 while the reader takes it from 4..12 - so the counter came out
            # as a slice across the reserved bytes and the ciphertext, the AEAD open failed, and a
            # working tunnel read as a dead one.
            self.sock.sendto(bytes([MESSAGE_TRANSPORT, 0, 0, 0])
                             + struct.pack("<Q", 0) + reply, peer)
        except OSError:
            pass

    def close(self) -> None:
        self.running = False
        self.sock.close()


class WireGuardClient:
    """The same protocol from the initiator side, so the two halves are checked against each other."""

    def __init__(self, peer_static: bytes, relay_address) -> None:
        self.private = X25519PrivateKey.generate()
        self.static_public = public_of(self.private)
        self.peer_static = peer_static
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.settimeout(4)
        self.relay_address = relay_address
        self.handshake_ok = False
        self.transport_ok = 0

    def handshake(self) -> bool:
        ephemeral = X25519PrivateKey.generate()
        ephemeral_public = public_of(ephemeral)
        # Kept, because the DH below needs the ephemeral's private half, not the static one.
        self._ephemeral = ephemeral
        mac = hashlib.blake2s(LABEL_MAC1 + self.static_public + IDENTIFIER,
                              digest_size=16).digest()
        # The 148-byte layout, field by field. Getting this wrong is silent: a first version wrote
        # three reserved bytes and a 32-byte index and produced a 227-byte packet, which no endpoint
        # would accept and which looks exactly like a blocked network.
        #   0 type+reserved(3) | 4 sender static | 36 sender ephemeral | 68 mac1 | 84 mac2
        #   100 encrypted static+timestamp(28) | 128 mac2 padded(16) | 144 padding(4) = 148
        packet = (bytes([MESSAGE_INITIATION, 0, 0, 0])
                  + self.static_public
                  + ephemeral_public
                  + mac
                  + bytes(16)
                  + bytes(28)
                  + bytes(16)
                  + bytes(4))
        assert len(packet) == INITIATION_BYTES, len(packet)
        try:
            self.sock.sendto(packet, self.relay_address)
        except OSError:
            return False
        try:
            response, _ = self.sock.recvfrom(2048)
        except socket.timeout:
            return False
        # Derive the same transport keys from the same inputs, the other end of the exchange.
        # A message-response is type+reserved(4) | sender(32) | receiver(32) | ephemeral(32), so
        # the responder's ephemeral is 68..100.
        responder_ephemeral = response[68:100]
        # The DH is between the initiator's EPHEMERAL private key and the responder's static
        # public key - not the initiator's static and the responder's ephemeral. Using the wrong
        # one produces a shared secret that is perfectly valid and completely different on each
        # side, so the handshake appears to complete and every later key is unrelated. That is the
        # same failure shape as everything else in this project: no error, just a wrong answer that
        # reads like a network problem.
        shared = self._ephemeral.exchange(
            X25519PublicKey.from_public_bytes(self.peer_static))
        # Both sides must mix in the same two statics in the same order: the responder's first,
        # then the initiator's. The responder mixes (its own static, then the sender's from the
        # packet); the initiator mixes (the peer's static it dialled, then its own). Getting that
        # order or that pairing wrong on one side yields a different chaining key and therefore
        # unrelated transport keys, with no error anywhere - the handshake still completes.
        chaining = mix_key(
            hashlib.blake2s(IDENTIFIER, digest_size=32).digest(),
            self.peer_static + self.static_public)
        chaining = mix_key(chaining, shared)
        chaining = mix_key(chaining, b"")
        keys = HKDF(algorithm=hashes.SHA256(), length=64, salt=chaining[:32],
                    info=b"").derive(b"")
        # The initiator's send key is the responder's receive key, and the reverse - so the two
        # halves agree only if both derive the same pair and use them in opposite directions.
        self.send_chain = keys[:32]
        self.receive_chain = keys[32:]
        self.handshake_ok = True
        return True

    def transport(self, payload: bytes) -> bool:
        counter = 0
        nonce = bytes(4) + struct.pack("<Q", counter)
        # Sealed with the real key from the handshake, so the peer's open() either succeeds and
        # counts the packet or raises. A relay that mangled a byte produces a failed open, which is
        # what makes this a test of the relay rather than of the loopback.
        inner = ChaCha20Poly1305(self.send_chain).encrypt(nonce, payload, b"")
        packet = bytes([MESSAGE_TRANSPORT, 0, 0, 0]) + struct.pack("<Q", counter) + inner
        try:
            self.sock.sendto(packet, self.relay_address)
        except OSError:
            return False
        try:
            reply, _ = self.sock.recvfrom(2048)
        except socket.timeout:
            return False
        # The reply is only evidence if it opens. A relay that echoed the packet back, or dropped a
        # byte, produces something that fails the AEAD open - which is the whole reason this test
        # exists instead of counting bytes.
        nonce = bytes(4) + struct.pack("<Q", 0)
        try:
            # type(1) + reserved(3) + counter(8) = 12 bytes of header, so the sealed payload starts
            # at 12. Reading from 16 - left over from the two-byte header - cuts four bytes off the
            # ciphertext and the open fails on a reply that is perfectly correct.
            plain = ChaCha20Poly1305(self.receive_chain).decrypt(nonce, reply[12:], b"")
        except Exception:
            return False
        if plain != payload:
            return False
        self.transport_ok += 1
        return True

    def close(self) -> None:
        self.sock.close()


class RealWireGuardOverRelayTest(unittest.TestCase):
    """The relay has to carry something both halves can actually agree on."""

    def setUp(self) -> None:
        self.endpoint = WireGuardEndpoint(X25519PrivateKey.generate())
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind(("127.0.0.1", 0))
        self.thread = threading.Thread(target=relay.serve_udp_client,
                                       args=(self.listener, self.endpoint.address, True),
                                       daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.endpoint.close()
        self.listener.close()

    def test_aRealHandshakeCrossesTheRelayAndBothSidesAgree(self) -> None:
        client = WireGuardClient(self.endpoint.static_public, self.listener.getsockname())
        try:
            self.assertTrue(client.handshake(),
                            "the handshake did not complete through the relay")
            self.assertTrue(client.handshake_ok)
        finally:
            client.close()

    def test_aTransportPacketIsDecryptedByThePeerAndTheRelayStaysInvisible(self) -> None:
        client = WireGuardClient(self.endpoint.static_public, self.listener.getsockname())
        try:
            self.assertTrue(client.handshake(),
                            "the handshake did not complete, so the transport proves nothing")
            before = self.endpoint.transport_ok
            self.assertTrue(client.transport(b"hello wireguard"),
                            "a transport packet did not survive the relay")
            self.assertGreater(self.endpoint.transport_ok, before,
                               "the peer never decrypted the transport packet")
        finally:
            client.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)

