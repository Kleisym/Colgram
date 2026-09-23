"""Validate the MTProto liveness probe against a real Telegram API server.

Why this matters for the bypass: ColgramDcRemap only hands tgnet an address that answers the
reserve query. If that query is built wrongly, a reachable DC is filtered out before tgnet ever
sees it, and "no address speaks MTProto" would be an artifact of the test rather than a fact about
the network. So the frame has to be proven against a genuine API server.

The DC addresses are dropped on this network, but a few public SOCKS5 nodes reach them (measured:
85.209.156.148:1080, 185.195.71.218:18080, 80.209.243.86:11080 -> 149.154.175.50:443). Running the
exact same bytes through one of those is the ground truth.

Usage: python scripts/mtproto-probe-validation.py [proxy:port ...]
"""
import binascii
import os
import socket
import struct
import sys
import time

DC_TARGETS = [("149.154.175.50", 443), ("149.154.167.51", 443), ("91.108.56.100", 443)]
DEFAULT_PROXIES = ["85.209.156.148:1080", "185.195.71.218:18080", "80.209.243.86:11080",
                   "72.194.42.156:4145"]


def socks5_connect(proxy_host, proxy_port, dst_host, dst_port, timeout=8):
    s = socket.create_connection((proxy_host, proxy_port), timeout)
    s.settimeout(timeout)
    s.sendall(b"\x05\x01\x00")
    if s.recv(2) != b"\x05\x00":
        raise OSError("proxy refused no-auth")
    s.sendall(b"\x05\x01\x00\x01" + socket.inet_aton(dst_host) + struct.pack(">H", dst_port))
    rep = s.recv(32)
    if len(rep) < 4 or rep[1] != 0:
        raise OSError("CONNECT refused: %s" % (rep[1:2].hex() if len(rep) > 1 else "short"))
    return s


def msg_id():
    # Low two bits must be zero for a query; timestamp-ish in the high bits.
    return (int(time.time() * 1000) << 22) | (int.from_bytes(os.urandom(3), "little") << 2)


def frame():
    return struct.pack("<Q", msg_id()) + struct.pack("<Q", 0x7b) + os.urandom(8)


def try_variant(name, prefix, sock):
    try:
        sock.sendall(prefix + frame())
        sock.settimeout(6)
        data = sock.recv(64)
        if not data:
            return "%s: closed" % name
        hexs = binascii.hexlify(data[:24]).decode()
        verdict = []
        if len(data) >= 16 and data[8:12] == b"\x7b\x00\x00\x00":
            verdict.append("<<< matches the 16-byte resq answer my Java probe expects")
        if len(data) >= 16 and data[0:4] == b"\x7b\x00\x00\x00":
            verdict.append("<<< resq id at offset 0, NOT offset 8 (my probe would reject this)")
        if data[:5] == b"HTTP/":
            verdict.append("<<< HTTP")
        return "%s: %d bytes %s %s" % (name, len(data), hexs, " ".join(verdict))
    except Exception as e:
        return "%s: %r" % (name, e)


def main():
    proxies = sys.argv[1:] or DEFAULT_PROXIES
    for spec in proxies:
        host, _, port = spec.partition(":")
        for dc, dc_port in DC_TARGETS:
            try:
                s = socks5_connect(host, int(port or 1080), dc, dc_port)
            except Exception as e:
                print("%s -> %s:%d  proxy failed: %r" % (spec, dc, dc_port, e))
                continue
            print("%s -> %s:%d  connected" % (spec, dc, dc_port))
            for label, prefix in (("abridged-zero", b"\x00"),
                                  ("abridged-get", b"GET\x00"),
                                  ("intermediate", None)):
                if prefix is None:
                    body = frame()
                    payload = struct.pack("<II", len(body), 0xEF02D0B7) + body
                else:
                    payload = None
                try:
                    conn = socks5_connect(host, int(port or 1080), dc, dc_port)
                except Exception as e:
                    print("    %s: reconnect failed %r" % (label, e))
                    continue
                if payload is not None:
                    conn.sendall(payload)
                    conn.settimeout(6)
                    try:
                        d = conn.recv(64)
                        print("    %-16s %d bytes %s" % (label, len(d),
                                                          binascii.hexlify(d[:24]).decode()))
                    except Exception as e:
                        print("    %-16s %r" % (label, e))
                else:
                    print("    " + try_variant(label, prefix, conn))
                try:
                    conn.close()
                except Exception:
                    pass
            try:
                s.close()
            except Exception:
                pass
            break  # one working DC per proxy is enough


if __name__ == "__main__":
    main()
