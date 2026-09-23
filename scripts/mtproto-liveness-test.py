"""Find a liveness test that a real Telegram API server actually answers.

The reserve query (resq) turned out to be useless: through SOCKS5 nodes that reach
149.154.175.50:443, the server answers nothing. So a sweep that filters on resq rejects genuine
API servers - which is exactly what ColgramDcRemap was doing, and it means "0 адресов говорят по
MTProto" measured nothing about the network.

What an API server must answer on a fresh, unencrypted connection is the handshake's first
message: req_pq_multi (0xbe7e8ef1) with a 16-byte nonce, replying resq_pq/resPQ. This tries that,
plus the abridged "GET" compatibility prefix and the intermediate tag, through a proxy that can
reach the DC, and prints raw bytes so the answer can be read rather than assumed.
"""
import binascii
import os
import socket
import struct
import sys
import time

REQ_PQ_MULTI = 0xBE7E8EF1
GET_DH_CONFIG = 0x1998C343          # needs a server salt; included for contrast
PROXIES = sys.argv[1:] or ["85.209.156.148:1080", "185.195.71.218:18080",
                           "80.209.243.86:11080", "72.194.42.156:4145"]
DCS = [("149.154.175.50", 443), ("149.154.167.51", 443)]


def socks5(proxy, dst, timeout=8):
    host, _, port = proxy.partition(":")
    s = socket.create_connection((host, int(port or 1080)), timeout)
    s.settimeout(timeout)
    s.sendall(b"\x05\x01\x00")
    if s.recv(2) != b"\x05\x00":
        raise OSError("method refused")
    dhost, dport = dst
    s.sendall(b"\x05\x01\x00\x01" + socket.inet_aton(dhost) + struct.pack(">H", dport))
    rep = s.recv(32)
    if len(rep) < 4 or rep[1] != 0:
        raise OSError("connect refused")
    return s


def mid():
    return (int(time.time() * 1000) << 22) | (int.from_bytes(os.urandom(3), "little") << 2)


def attempt(label, payload, proxy, dc):
    try:
        s = socks5(proxy, dc)
    except Exception as e:
        return "%-22s tunnel-fail %s" % (label, e)
    try:
        s.sendall(payload)
        s.settimeout(6)
        data = s.recv(128)
        if not data:
            return "%-22s closed" % label
        note = ""
        head = struct.unpack("<I", data[:4])[0] if len(data) >= 4 else 0
        if head == 0x10ED1504 or head == 0x05162463 or head == 0x96D411ED:
            note = "  <<< resq_pq / resPQ: this IS a Telegram API server"
        return "%-22s %3d bytes %s%s" % (label, len(data), binascii.hexlify(data[:20]).decode(), note)
    except Exception as e:
        return "%-22s %s" % (label, e)
    finally:
        try:
            s.close()
        except Exception:
            pass


for proxy in PROXIES:
    for dc in DCS:
        body_pq = struct.pack("<Q", mid()) + struct.pack("<I", REQ_PQ_MULTI) + os.urandom(16)
        results = [
            attempt("req_pq_multi/abridged", b"\x00" + body_pq, proxy, dc),
            attempt("req_pq_multi/GET", b"GET\x00" + body_pq, proxy, dc),
            attempt("req_pq_multi/interm",
                    struct.pack("<II", len(body_pq), 0xEF02D0B7) + body_pq, proxy, dc),
        ]
        print("== %s -> %s:%d" % (proxy, dc[0], dc[1]))
        for r in results:
            print("   " + r)
        break
    print()
