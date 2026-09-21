"""Probe a SOCKS5 listener and report whether a TLS handshake to api.telegram.org works.

Usage:  python socks_probe.py [host] [port]
Reads the target from argv so the same script works against the device's forwarded
port or any local ByeDPI/desync listener.
"""
import socket, struct, ssl, sys

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 9876
TARGET = ("api.telegram.org", 443)

s = socket.create_connection((HOST, PORT), timeout=8)
s.sendall(b"\x05\x01\x00")
greeting = s.recv(2)
print("greeting:", greeting.hex())
if greeting != b"\x05\x00":
    print("RESULT: SOCKS server refused no-auth")
    sys.exit(1)

host_b = TARGET[0].encode()
s.sendall(b"\x05\x01\x00\x03" + bytes([len(host_b)]) + host_b + struct.pack(">H", TARGET[1]))
resp = s.recv(10)
print("connect resp:", resp.hex())
if len(resp) < 2 or resp[1] != 0:
    print("RESULT: SOCKS connect FAILED, rep=", resp[1] if len(resp) > 1 else "?")
    sys.exit(1)

try:
    tls = ssl.create_default_context().wrap_socket(s, server_hostname=TARGET[0])
    print("RESULT: TLS OK", tls.version())
    tls.close()
except Exception as e:
    print("RESULT: TLS FAILED", type(e).__name__, e)
