"""A WireGuard-over-TCP relay, for networks that filter Cloudflare's WireGuard UDP.

Why this exists, and what it is measured to be.

Measured on this network: a UDP datagram under ~1200 bytes gets no answer on any port, while a
1200-byte one gets a QUIC reply on 443. A WireGuard message-initiation is 148 bytes, so WARP's own
handshake cannot be seen here - and 2408 is silent even at 1200 bytes, on every Cloudflare ingress.

The same small payload crosses fine over **TCP**: a 64-byte TCP payload completes a real TLS 1.3
handshake to Cloudflare. So the floor is a property of the UDP path, not of the link, and that is
the whole reason a relay is worth building rather than a dead end. A relay receives the 148-byte
initiation over TCP, where small packets work, and originates the WireGuard UDP itself from a
network without this filter.

The wire format is deliberately trivial, because the whole point is that there is nothing to
debug on the blocked side: a client connects over TCP, sends 2-byte-length-prefixed frames, and
each frame is one UDP payload the relay sends to the WireGuard endpoint and, if an answer comes
back before the deadline, returns as a frame. No crypto here - WireGuard authenticates every
packet cryptographically, so a relay that mangled traffic could not produce a working tunnel, it
could only produce a broken one. That is a feature: the tunnel either works end to end or it does
not, and there is no way for this to quietly weaken WireGuard's guarantees.

Run it on a box that can reach Cloudflare's WireGuard ports:

    python warp-relay-server.py --listen 0.0.0.0:51820 --endpoint 162.159.192.1:2408

Then in Colgram, long-press the WARP row and give it that address, port and the relay's WireGuard
public key (see --keygen).
"""
from __future__ import annotations

import argparse
import os
import socket
import struct
import sys
import threading
import time

HEADER = struct.Struct("!H")
MAX_FRAME = 2048


def keygen() -> tuple[str, str]:
    """A WireGuard keypair, so the relay can be brought up with nothing else prepared."""
    try:
        from cryptography.hazmat.primitives.asymmetric.x25519 import (  # type: ignore
            X25519PrivateKey, X25519PublicKey)
        from cryptography.hazmat.primitives import serialization  # type: ignore
        import base64

        private = X25519PrivateKey.generate()
        private_bytes = private.private_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PrivateFormat.Raw,
            encryption_algorithm=serialization.NoEncryption())
        public_bytes = private.public_key().public_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PublicFormat.Raw)
        return (base64.b64encode(private_bytes).decode(),
                base64.b64encode(public_bytes).decode())
    except ImportError:
        pass
    # Without the cryptography module, use the kernel: /dev/urandom is the only randomness here
    # and X25519 clamping is four fixed bits, so this is the same key a library would produce.
    import base64

    raw = bytearray(os.urandom(32))
    raw[0] &= 248
    raw[31] &= 127
    raw[31] |= 64
    return base64.b64encode(bytes(raw)).decode(), "(install `cryptography` to derive the public key)"


def read_exactly(sock: socket.socket, count: int) -> bytes | None:
    """Read exactly count bytes, or None if the peer closed first."""
    data = b""
    while len(data) < count:
        try:
            chunk = sock.recv(count - len(data))
        except OSError:
            return None
        if not chunk:
            return None
        data += chunk
    return data


def serve_client(client: socket.socket, endpoint: tuple[str, int], quiet: bool) -> None:
    """Bridge one client's frames to the WireGuard endpoint and back."""
    upstream = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    upstream.settimeout(1.0)
    client.settimeout(1.0)
    peer_closed = False
    try:
        while not peer_closed:
            # Drain anything the client has sent us and forward it.
            try:
                # read_exactly, not recv(2). A TCP read returns whatever has arrived rather
                # than the two bytes asked for, so a length prefix split across two segments -
                # one byte in each - makes recv(2) return 1, unpacks it as a 256-byte frame,
                # and the next real frame's first byte is then consumed as the rest of that
                # length. Framing desynchronises and the relay drops the connection mid-stream,
                # which is the failure a WireGuard handshake is most likely to cause: a 148-byte
                # initiation written in one burst is free to arrive as three segments, and a
                # relay that only survives whole writes works on the author's machine and
                # nowhere else.
                head = read_exactly(client, 2)
            except socket.timeout:
                head = b""
            except OSError:
                break
            if head:
                if len(head) < 2:
                    break
                (length,) = HEADER.unpack(head)
                if length == 0 or length > MAX_FRAME:
                    break
                payload = read_exactly(client, length)
                if payload is None:
                    break
                try:
                    upstream.sendto(payload, endpoint)
                except OSError as e:
                    if not quiet:
                        print(f"send to {endpoint} failed: {e}", flush=True)

            # Forward back whatever came home, until there is nothing for a whole second.
            quiet_rounds = 0
            while quiet_rounds < 2:
                try:
                    data, _ = upstream.recvfrom(MAX_FRAME)
                except socket.timeout:
                    quiet_rounds += 1
                    continue
                except OSError:
                    return
                try:
                    client.sendall(HEADER.pack(len(data)) + data)
                except OSError:
                    return
                quiet_rounds = 0
    finally:
        upstream.close()
        try:
            client.close()
        except OSError:
            pass


def serve_udp_client(listener: socket.socket, endpoint: tuple[str, int], quiet: bool) -> None:
    """Bridge one UDP client's datagrams to the WireGuard endpoint and back.

    This is the half that makes the relay reachable from Colgram at all, and it exists because of a
    measured mismatch rather than a preference. With only the TCP listener, Colgram cannot use
    this relay: the app puts the relay's address into a sing-box `wireguard` **endpoint**, which
    dials its peer over **UDP**, while the TCP listener reads a TCP handshake and expects a
    length-prefixed frame. The two never meet, so the WireGuard initiation never leaves the phone.
    From the settings screen that is indistinguishable from a blocked network - the switch turns
    blue and nothing is routed.

    UDP is also the only form that works without new client code. Carrying the length-prefixed
    framing over UDP would mean inventing a transport Colgram does not speak; speaking plain
    WireGuard over UDP means the stock endpoint reaches it, because that is what a WireGuard peer
    is. The framing stays on the TCP listener for anyone driving this by hand.
    """
    listener.settimeout(1.0)
    peer: tuple[str, int] | None = None
    upstream = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    upstream.settimeout(1.0)
    try:
        while True:
            try:
                data, address = listener.recvfrom(MAX_FRAME)
            except socket.timeout:
                data = None
            except OSError:
                return
            if data:
                # One client at a time per socket, chosen by whoever speaks first. A WireGuard
                # endpoint opens no session of its own to learn this, so there is nothing to
                # authenticate the choice on - which is why the UDP listener is opt-in and why a
                # real deployment runs one per client behind a firewall.
                peer = address
                try:
                    upstream.sendto(data, endpoint)
                except OSError as e:
                    if not quiet:
                        print(f"send to {endpoint} failed: {e}", flush=True)

            if peer is None:
                continue
            while True:
                try:
                    answer, _ = upstream.recvfrom(MAX_FRAME)
                except socket.timeout:
                    break
                except OSError:
                    return
                try:
                    listener.sendto(answer, peer)
                except OSError:
                    return
    finally:
        upstream.close()
        try:
            listener.close()
        except OSError:
            pass


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen", default="0.0.0.0:51820", help="TCP address to accept on")
    parser.add_argument("--udp-listen", default="",
                        help="UDP address to accept on, same wire format as --listen")
    parser.add_argument("--endpoint", default="162.159.192.1:2408",
                        help="Cloudflare WireGuard UDP endpoint to forward to")
    parser.add_argument("--keygen", action="store_true",
                        help="print a WireGuard keypair and exit")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    if args.keygen:
        private, public = keygen()
        print("private (keep on the relay box): " + private)
        print("public  (put in Colgram):        " + public)
        return 0

    host, _, port = args.listen.rpartition(":")
    ehost, _, eport = args.endpoint.rpartition(":")
    endpoint = (ehost, int(eport))

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind((host or "0.0.0.0", int(port)))
    listener.listen(16)
    if not args.quiet:
        print(f"relay listening on {args.listen}, forwarding to {args.endpoint} over UDP",
              flush=True)
        print("check the far side can reach it first:", flush=True)
        print(f"  python -c \"import socket;s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);"
              f"s.settimeout(3);s.sendto(b'0'*1200,('{ehost}',{eport}));print('ok')\"",
              flush=True)

    if args.udp_listen:
        uhost, _, uport = args.udp_listen.rpartition(":")
        udp_listener = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp_listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        udp_listener.bind((uhost or "0.0.0.0", int(uport)))
        if not args.quiet:
            # Worth saying out loud: this is the port Colgram can actually reach. Without it the
            # TCP listener is the only way in, and no sing-box profile can name a TCP peer - the
            # tunnel would never start and would look exactly like a blocked network.
            print(f"UDP listener on {args.udp_listen} - this is the port to put in Colgram",
                  flush=True)
        threading.Thread(target=serve_udp_client,
                         args=(udp_listener, endpoint, args.quiet), daemon=True).start()

    while True:
        try:
            client, address = listener.accept()
        except OSError:
            break
        if not args.quiet:
            print(f"client from {address[0]}:{address[1]}", flush=True)
        threading.Thread(target=serve_client, args=(client, endpoint, args.quiet),
                         daemon=True).start()
    return 0


if __name__ == "__main__":
    sys.exit(main())
