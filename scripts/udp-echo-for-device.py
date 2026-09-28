"""A UDP echo server, so the device can be shown reaching a relay on this host.

Why this exists.

The relay carries a real WireGuard session, and that has been proven on the host: a real Noise_IK
handshake and a real ChaCha20Poly1305 transport packet, through the relay, opened by a peer that
holds the key only because the handshake established it.

It has not been proven **from the device**, and that distinction has bitten this project before.
The join test asserts the app's profile names a port a relay answers on, using a socket on the same
device - which proves the app and the relay agree, but not that a relay on a *different machine* is
reachable from the phone. The emulator reaches the host through QEMU's 10.0.2.2 gateway, and
whether a UDP datagram survives that NAT is a separate question from whether one survives loopback.

So this is a plain UDP echo on a port it prints, and the device sends to 10.0.2.2:<port>. A reply
proves the hop; silence is ambiguous - it could be the host firewall, the NAT, or the device - so the
script also accepts an address to try and says which it used.

Usage:
    python scripts/udp-echo-for-device.py --port 51820
    python scripts/udp-echo-for-device.py --seconds 120
"""
from __future__ import annotations

import argparse
import socket
import sys
import time


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--seconds", type=float, default=120.0)
    parser.add_argument("--bind", default="0.0.0.0")
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((args.bind, args.port))
    sock.settimeout(1.0)
    port = sock.getsockname()[1]
    print("LISTENING %s:%d" % (args.bind, port), flush=True)
    print("from the device, send a datagram to 10.0.2.2:%d" % port, flush=True)

    deadline = time.time() + args.seconds
    received = 0
    while time.time() < deadline:
        try:
            data, peer = sock.recvfrom(2048)
        except socket.timeout:
            continue
        except OSError:
            break
        received += 1
        print("  from %s:%d  %d bytes  %s" % (peer[0], peer[1], len(data), data[:16].hex()),
              flush=True)
        try:
            sock.sendto(b"ECHO:" + str(len(data)).encode(), peer)
        except OSError:
            pass
    print("received %d datagrams" % received, flush=True)
    sock.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

