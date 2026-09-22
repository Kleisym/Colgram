"""Is any reachable Telegram address actually an MTProto server?

A no-proxy bypass needs one: the block here drops the DC addresses outright, so the only question
that matters is whether some Telegram-owned IP that still answers TCP also speaks MTProto. This
tries every framing a Telegram server accepts on a plain socket, with correctly built msg_ids (a
msg_id whose low two bits are not zero is not a query, and real servers hang up on it - which is
what an earlier pass mistook for "MTProto server that rejected us").

Framings:
  abridged-zero   0x00 | msg_id | resq#7b | nonce
  abridged-get    "GET\\0" | msg_id | resq#7b | nonce
  intermediate    len | 0xef02d0b7 | msg_id | resq#7b | nonce
  intermediate_dc len | 0xee9be7a3 | msg_id | resq#7b | nonce
  obfuscated-ish  64 random bytes then the abridged frame (some servers only accept the
                  obfuscated2 header; without the key derivation this can only show a difference
                  in behaviour, which is still information)

A live MTProto server answers the resq with 16 bytes whose second half starts 7b 00 00 00.
nginx answers "HTTP/1.1 400". A dead-end listener closes.
"""
import binascii
import os
import socket
import struct
import sys
import time

RESQ = struct.pack("<Q", 0x7b)


def msg_id():
    # Milliseconds in the high bits, low two bits zero: a query id a server will accept.
    return (int(time.time() * 1000) << 22) | (int.from_bytes(os.urandom(3), "little") << 2)


def frame():
    return struct.pack("<Q", msg_id()) + RESQ + os.urandom(8)


def probe(ip, port, mode):
    try:
        s = socket.create_connection((ip, port), 3)
    except Exception as e:
        return "connect-fail", type(e).__name__
    try:
        s.settimeout(3)
        body = frame()
        if mode == "abridged-zero":
            s.sendall(b"\x00" + body)
        elif mode == "abridged-get":
            s.sendall(b"GET\x00" + body)
        elif mode == "intermediate":
            s.sendall(struct.pack("<II", len(body), 0xEF02D0B7) + body)
        elif mode == "intermediate_dc":
            s.sendall(struct.pack("<II", len(body), 0xEE9BE7A3) + body)
        elif mode == "obf2-then-abridged":
            s.sendall(os.urandom(64))
            s.sendall(b"\x00" + body)
        d = s.recv(64)
        if not d:
            return "closed", ""
        if len(d) >= 16 and d[8:12] == b"\x7b\x00\x00\x00":
            return "MTOPROTO-ALIVE", binascii.hexlify(d[:16]).decode()
        if d[:5] == b"HTTP/":
            return "nginx/http", d[:24].decode("ascii", "replace")
        return "%d bytes" % len(d), binascii.hexlify(d[:16]).decode()
    except Exception as e:
        return "no-reply", type(e).__name__
    finally:
        s.close()


MODES = ["abridged-zero", "abridged-get", "intermediate", "intermediate_dc", "obf2-then-abridged"]

targets = sys.argv[1:]
if not targets:
    print("usage: mtproto_alive.py ip[:port] ...")
    raise SystemExit(2)

for spec in targets:
    ip, _, port = spec.partition(":")
    port = int(port or 443)
    for mode in MODES:
        kind, detail = probe(ip, port, mode)
        print("%-18s %-5d %-17s %-16s %s" % (ip, port, mode, kind, detail))
        if kind == "MTOPROTO-ALIVE":
            break
