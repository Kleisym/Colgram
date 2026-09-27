#!/usr/bin/env python3
"""Probe: MTProto-over-WebSocket to Telegram's own web endpoints (kws{dc}.web.telegram.org).

Mirrors the webk client exactly: binary WebSocket (subprotocol 'binary'), MTProto obfuscated
transport (64-byte init, AES-CTR both directions), intermediate codec, an unencrypted req_pq
handshake inside. PASS = the server answered resPQ, which means Telegram's own
Cloudflare-fronted transport is usable from this network - no proxy, no VPN, no third party.
"""
import os, socket, ssl, struct, sys, time

HOSTS = ["venus.web.telegram.org", "pluto.web.telegram.org", "aurora.web.telegram.org",
         "vesta.web.telegram.org", "flora.web.telegram.org", "kws2.web.telegram.org"]

# --- pure-python AES (encrypt-only ECB) for CTR; payloads here are tiny -----------------

SBOX = [
0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16]
INV = [0]*256
for i, v in enumerate(SBOX):
    INV[v] = i
RCON = [0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36,0x6c,0xd8,0xab,0x4d]

def xtime(a):
    a <<= 1
    if a & 0x100: a ^= 0x11b
    return a & 0xFF

def expand_key(key):
    words = [list(key[i:i+4]) for i in range(0, 16, 4)]
    for i in range(4, 44):
        t = list(words[i-1])
        if i % 4 == 0:
            t = t[1:] + t[:1]
            t = [SBOX[b] for b in t]
            t[0] ^= RCON[i//4 - 1]
        words.append([a ^ b for a, b in zip(words[i-4], t)])
    rks = []
    for r in range(11):
        rk = bytearray()
        for c in range(4):
            rk += bytes(words[4*r+c])
        rks.append(bytes(rk))
    return rks

def aes_block(block, round_keys):
    state = bytearray(16)
    for i in range(16):
        state[i] = block[i] ^ round_keys[0][i]
    for rnd in range(1, 11):
        # SubBytes
        state = bytearray(SBOX[b] for b in state)
        # ShiftRows
        s = state
        state = bytearray(16)
        for c in range(4):
            for r in range(4):
                state[4*c + r] = s[4*((c + r) % 4) + r]
        # MixColumns (skipped on the last round)
        if rnd != 10:
            ns = bytearray(16)
            for c in range(4):
                a = state[4*c:4*c+4]
                t = a[0] ^ a[1] ^ a[2] ^ a[3]
                ns[4*c+0] = a[0] ^ t ^ xtime(a[0] ^ a[1])
                ns[4*c+1] = a[1] ^ t ^ xtime(a[1] ^ a[2])
                ns[4*c+2] = a[2] ^ t ^ xtime(a[2] ^ a[3])
                ns[4*c+3] = a[3] ^ t ^ xtime(a[3] ^ a[0])
            state = ns
        state = bytearray(st ^ rk for st, rk in zip(state, round_keys[rnd]))
    return bytes(state)

class Ctr:
    def __init__(self, key, iv):
        self.rk = expand_key(key)
        self.counter = int.from_bytes(iv, "big")
        self.pos = 16
        self.chunk = b""
    def _fill(self, n):
        while len(self.chunk) < n:
            self.chunk += aes_block(self.counter.to_bytes(16, "big"), self.rk)
            self.counter = (self.counter + 1) % (1 << 128)
        out, self.chunk = self.chunk[:n], self.chunk[n:]
        return out
    def crypt(self, data):
        stream = self._fill(len(data))
        return bytes(a ^ b for a, b in zip(data, stream))

def ws_connect(host):
    sock = socket.create_connection((host, 443), timeout=8)
    ctx = ssl.create_default_context()
    s = ctx.wrap_socket(sock, server_hostname=host)
    key = __import__("base64").b64encode(os.urandom(16)).decode()
    req = (f"GET /apiws HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\n"
           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
           f"Sec-WebSocket-Protocol: binary\r\nOrigin: https://web.telegram.org\r\n"
           f"User-Agent: Mozilla/5.0\r\n\r\n")
    s.sendall(req.encode())
    resp = b""
    while b"\r\n\r\n" not in resp:
        chunk = s.recv(4096)
        if not chunk:
            raise RuntimeError("no handshake response")
        resp += chunk
    head = resp.split(b"\r\n\r\n")[0].decode("latin1")
    if " 101 " not in head.split("\r\n")[0]:
        raise RuntimeError("handshake refused: " + head.split("\r\n")[0])
    return s, resp.split(b"\r\n\r\n", 1)[1]

def ws_send(s, payload):
    mask = os.urandom(4)
    header = bytearray([0x82])
    n = len(payload)
    if n < 126:
        header.append(0x80 | n)
    elif n < 65536:
        header.append(0x80 | 126)
        header += struct.pack(">H", n)
    else:
        header.append(0x80 | 127)
        header += struct.pack(">Q", n)
    header += mask
    s.sendall(bytes(header) + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

def ws_recv(s, want_seconds):
    s.settimeout(want_seconds)
    def read(n):
        buf = b""
        while len(buf) < n:
            chunk = s.recv(n - len(buf))
            if not chunk:
                raise RuntimeError("closed")
            buf += chunk
        return buf
    b1, b2 = read(2)
    opcode = b1 & 0x0F
    ln = b2 & 0x7F
    if ln == 126:
        ln = struct.unpack(">H", read(2))[0]
    elif ln == 127:
        ln = struct.unpack(">Q", read(8))[0]
    return opcode, (read(ln) if ln else b"")

def try_dc(host):
    s, leftover = ws_connect(host)
    while True:
        init = bytearray(os.urandom(64))
        val = int.from_bytes(init[0:4], "little")
        val2 = int.from_bytes(init[4:8], "little")
        if (init[0] != 0xEF and val not in (0x44414548, 0x54534F50, 0x20544547,
                                            0x4954504F, 0xEEEEEEEE, 0xDDDDDDDD) and val2 != 0):
            break
    enc = Ctr(bytes(init[8:40]), bytes(init[40:56]))
    rev = bytes(reversed(init))
    dec = Ctr(bytes(rev[8:40]), bytes(rev[40:56]))

    # webk: codec tag goes to init[56:60], the WHOLE init is run through the enc-CTR and
    # bytes [56:64] on the wire are replaced with the encrypted tail (server recovers the
    # tag from it to pick the codec). Key derivation uses the pre-overwrite init.
    init[56:60] = b"\xef\xef\xef\xef"
    wire = bytearray(init)
    enc_init = enc.crypt(bytes(init))
    wire[56:64] = enc_init[56:64]

    nonce = os.urandom(16)
    inner = struct.pack("<I", 0x60469b78) + nonce
    msg_id = (int(time.time() * (2 ** 32)) << 32) & 0x7FFFFFFFFFFFFFFF
    packet = (struct.pack("<q", 0) + struct.pack("<q", msg_id)
              + struct.pack("<I", len(inner)) + inner)
    # abridged codec: first byte 0xEF, then one packet per frame: (len/4) header byte
    quads = len(packet) // 4
    assert len(packet) % 4 == 0 and quads < 127
    plain = b"\xef" + bytes([quads]) + packet
    ws_send(s, bytes(wire))
    ws_send(s, enc.crypt(plain))

    deadline = time.time() + 8
    recv_buf = b""
    while time.time() < deadline:
        try:
            op, payload = ws_recv(s, 4)
        except (socket.timeout, RuntimeError) as e:
            return f"no answer ({e})"
        if op == 0x9:
            continue
        if op == 0x8:
            return "closed by server"
        if op != 0x2:
            continue
        recv_buf += dec.crypt(payload)
        # abridged stream: optional 0xEF lead, then len-byte + quads
        if len(recv_buf) >= 1 and recv_buf[0:1] == b"\xef" and len(recv_buf) < 5:
            recv_buf = recv_buf[1:]
        if len(recv_buf) >= 1:
            qlen = recv_buf[0]
            need = 1 + qlen * 4
            if qlen in (1, 2, 3):  # ping/pong/close handshake frames, not our data
                recv_buf = recv_buf[1:]
                continue
            if len(recv_buf) >= need and need > 1:
                out = recv_buf[1:need]
                auth_id = out[0:8]
                if auth_id == b"\x00" * 8:
                    return f"PASS: resPQ {len(out)}B body starts {out[24:32].hex()}"
                return f"got {len(out)}B unexpected: {out[:24].hex()}"
    return "timeout waiting"

if __name__ == "__main__":
    hosts = sys.argv[1:] or HOSTS
    for h in hosts:
        try:
            print(h, "->", try_dc(h), flush=True)
        except Exception as e:
            print(h, "-> ERROR", repr(e), flush=True)
