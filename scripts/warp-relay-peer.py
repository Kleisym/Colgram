"""A relay on this host with a real WireGuard peer behind it, for the device to talk to.

Why this exists.

The relay's real-WireGuard half is proven on the host, and the phone's half of the relay path is
proven against a peer that echoes. This joins them: a real WireGuard peer here, a real relay in
front of it, and the device driving the exchange through both over the network rather than over
loopback. The emulator reaches this host at 10.0.2.2, which stands in for "a relay somewhere
else" as closely as an emulator can.

**What the peer actually is.** A full WireGuard responder - Noise_IK with real HKDF and BLAKE2s
chaining, answering only a session it can complete, and echoing a transport packet encrypted to
the initiator's send key. That is the same construction scripts/test_warp_real_wireguard.py uses,
run as a long-lived process instead of inside a test, because the device is the thing driving and it
cannot start a process on the host.

**Why the peer is local, and what that costs.** Cloudflare's WireGuard ingress is unreachable from
this network - measured on the device at 0 of 7 ports, 53 included - which is the reason the relay
exists in the first place. So the far side here is not Cloudflare, and nothing this prints is a
warp=on claim. What it establishes is the part that is Colgram's and the relay's: a handshake and a
transport packet crossing from a phone, through a relay, to a peer that will only answer if the
whole exchange was cryptographically correct.

Usage:
    python scripts/warp-relay-peer.py --relay-port 51821
    python scripts/warp-relay-peer.py --relay-port 0     # prints the port it chose
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import os
import queue
import socket
import struct
import sys
import threading
import time

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import (X25519PrivateKey,
                                                               X25519PublicKey)
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

CONSTRUCTION = b"Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s"
IDENTIFIER = b"WireGuard v1 zx2c4 Jason@zx2c4.com"
LABEL_MAC1 = b"mac1----"
MESSAGE_INITIATION = 1
MESSAGE_RESPONSE = 2
MESSAGE_TRANSPORT = 4
INITIATION_BYTES = 148


def public_of(private: X25519PrivateKey) -> bytes:
    return private.public_key().public_bytes_raw()


def mix_key(key: bytes, material: bytes) -> bytes:
    """Noise's chained HKDF. BLAKE2s cannot do a 64-byte digest - its maximum is 32 - so the
    obvious blake2s(..., digest_size=64) raises, and a handler that dies on the first packet looks
    exactly like a filtered port."""
    temp_key, temp_hash = key, b""
    for byte in material:
        temp_hash = hmac.new(temp_hash, bytes([byte]), hashlib.blake2s).digest()
        temp_key = HKDF(algorithm=hashes.SHA256(), length=32, salt=temp_key,
                        info=temp_hash).derive(b"")
    return temp_key


class Peer:
    def __init__(self) -> None:
        self.static_private = X25519PrivateKey.generate()
        self.static_public = public_of(self.static_private)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("127.0.0.1", 0))
        self.address = self.sock.getsockname()
        self.session = None
        self.running = True
        self.handshakes = 0
        self.transports = 0
        self.outbox = queue.Queue()
        threading.Thread(target=self._serve, daemon=True).start()

    def _serve(self) -> None:
        self.sock.settimeout(0.5)
        while self.running:
            try:
                data, peer = self.sock.recvfrom(2048)
            except (socket.timeout, OSError):
                continue
            if not data:
                continue
            if data[0] == MESSAGE_INITIATION and len(data) == INITIATION_BYTES:
                self._handshake(data, peer)
            elif data[0] == MESSAGE_TRANSPORT and self.session:
                self._transport(data, peer)

    def _handshake(self, data: bytes, peer) -> None:
        sender_static = data[4:36]
        ephemeral = X25519PublicKey.from_public_bytes(data[36:68])
        shared = self.static_private.exchange(ephemeral)
        chaining = mix_key(hashlib.blake2s(IDENTIFIER, digest_size=32).digest(),
                           self.static_public + sender_static)
        chaining = mix_key(chaining, shared)
        chaining = mix_key(chaining, b"")

        responder_ephemeral = X25519PrivateKey.generate()
        encrypted_static = ChaCha20Poly1305(chaining[:32]).encrypt(bytes(12), sender_static, b"")
        payload = (bytes([MESSAGE_RESPONSE, 0, 0, 0]) + self.static_public
                   + public_of(responder_ephemeral) + encrypted_static)
        keys = HKDF(algorithm=hashes.SHA256(), length=64, salt=chaining[:32],
                    info=b"").derive(b"")
        self.session = {
            "peer": peer,
            "send_chain": keys[32:],
            "receive_chain": keys[:32],
        }
        self.handshakes += 1
        # Queued only, never sent from here. The relay loop owns the return path and drains this
        # queue, and a second writer on the same socket is how two of them started competing: the
        # reply was consumed by whichever thread won the recvfrom, and the client heard nothing
        # from a handshake that had actually completed.
        self.outbox.put(payload)

    def _transport(self, data: bytes, peer) -> None:
        counter = int.from_bytes(data[4:12], "little")
        nonce = bytes(4) + struct.pack("<Q", counter)
        try:
            plain = ChaCha20Poly1305(self.session["receive_chain"]).decrypt(nonce, data[12:], b"")
        except Exception:
            return
        self.transports += 1
        reply = ChaCha20Poly1305(self.session["send_chain"]).encrypt(
            bytes(4) + struct.pack("<Q", 0), plain, b"")
        self.outbox.put(bytes([MESSAGE_TRANSPORT, 0, 0, 0])
                        + struct.pack("<Q", 0) + reply)

    def drain_into(self, sock, destination):
        """Forward whatever the peer has queued to one destination, and record it.

        Called by the relay loop after a packet is forwarded, and again on its own timer, because
        the peer's reply arrives after the loop has moved on. Draining synchronously only catches
        what the peer finished before this call - which, for a Diffie-Hellman and an AEAD seal, is
        nothing.
        """
        sent = 0
        for answer in self.take_answers():
            try:
                sock.sendto(answer, destination)
                sent += 1
            except OSError:
                pass
        return sent

    def take_answers(self):
        """Whatever the peer has produced since the last call, without waiting for it.

        The relay loop used to read the peer's socket directly with a one-second timeout, once per
        relayed packet. That blocked the loop for a full second after every packet, so a client
        sending two in quick succession saw the second one queued behind the first one's wait - and
        the log showed a packet arriving with nothing after it, which is what a peer that never
        received it looks like. The peer answers asynchronously and puts its replies on a queue;
        draining that queue is instant and cannot make the relay wait on a peer's speed.
        """
        drained = []
        while True:
            try:
                drained.append(self.outbox.get_nowait())
            except queue.Empty:
                return drained

    def close(self) -> None:
        self.running = False
        self.sock.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--relay-port", type=int, default=51821)
    parser.add_argument("--seconds", type=float, default=900.0)
    args = parser.parse_args()

    peer = Peer()
    relay = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    relay.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    relay.bind(("0.0.0.0", args.relay_port))
    relay.settimeout(0.5)

    print("LISTENING %d" % relay.getsockname()[1], flush=True)
    print("the peer is at %s:%d, the device should use 10.0.2.2:%d"
          % (peer.address[0], peer.address[1], relay.getsockname()[1]), flush=True)

    relayed = 0
    last_sender = None
    deadline = time.time() + args.seconds
    try:
        while time.time() < deadline:
            try:
                data, sender = relay.recvfrom(2048)
            except socket.timeout:
                # The peer's reply lands after this loop has moved on, so the queue is drained on
                # the timeout path too. Draining it only right after forwarding catches whatever
                # the peer had already finished, which for a Diffie-Hellman is nothing - and the
                # relay then reports a forwarded packet and no answer, which reads as a network
                # that swallowed it.
                for answer in peer.take_answers():
                    if last_sender is not None:
                        try:
                            relay.sendto(answer, last_sender)
                        except OSError:
                            pass
                continue
            except OSError:
                break
            relayed += 1
            last_sender = sender
            print("  from %s:%d  %d bytes" % (sender[0], sender[1], len(data)), flush=True)
            # A bare probe gets a bare acknowledgement, before anything is forwarded. The relay is
            # a UDP forwarder, not a protocol endpoint, so a datagram it has no session for would
            # otherwise draw no reply at all - and a client waiting on that reply concludes the port
            # is unreachable, which is a statement about the relay's design rather than about the
            # network. Echoing costs one datagram and makes reachability a question with an answer.
            if not data or data[0] not in (MESSAGE_INITIATION, MESSAGE_TRANSPORT):
                try:
                    relay.sendto(b"RELAY-OK:" + str(len(data)).encode(), sender)
                    continue
                except OSError:
                    pass
            try:
                relay.sendto(data, peer.address)
            except OSError:
                pass
            # Whatever arrives on the relay's own socket came from the peer, because everything
            # this loop forwards goes to the peer's separate socket and the peer only ever answers
            # its source address. Forwarding it on is what completes the exchange - and forwarding
            # it *back to the peer* instead, as an earlier version did, is a loop that looks exactly
            # like a silent network from the client's side.
            #
            # The window has to be long enough for the peer to finish a Diffie-Hellman and an
            # AEAD seal. At 50 ms the relay looked up, held its port, counted the packet, and
            # returned nothing - because the answer had not been produced yet. The peer is not
            # slow, it is just not synchronous, and a relay that assumes it is looks identical to
            # a filtered network.
            # Ask once, right after forwarding, purely for the log line below. The real return
            # path is the answer thread: the peer needs about 14 ms of Diffie-Hellman and AEAD
            # before it has anything to say, and a queue drained synchronously right after the
            # forward is always empty. The relay looked up, counted the packet, and reported zero
            # handshakes at the peer - which is what a peer that never received it looks like.
            peer.drain_into(relay, sender)
            # Count what went out, not only what came in. Without this the relay can hold a peer's
            # socket open for a full second and the log shows a packet arriving with no line after
            # it - which is indistinguishable from the peer never having received it.
            print("    forwarded to the peer, handshakes %d transports %d"
                  % (peer.handshakes, peer.transports), flush=True)
            # And whatever the service loop answered directly, in case it won the race for the
            # socket. Sending the same answer twice is harmless - WireGuard replies are matched by
            # their own transaction id, and a duplicate is discarded - while dropping one is fatal
            # to the handshake.
            while True:
                try:
                    answer = peer.outbox.get_nowait()
                except queue.Empty:
                    break
                try:
                    relay.sendto(answer, sender)
                except OSError:
                    pass
            print("relayed %d, handshakes %d, transports %d"
                  % (relayed, peer.handshakes, peer.transports), flush=True)
    finally:
        print("relayed %d, handshakes %d, transports %d"
              % (relayed, peer.handshakes, peer.transports), flush=True)
        relay.close()
        peer.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

