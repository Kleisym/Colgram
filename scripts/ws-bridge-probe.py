"""Does any reachable Telegram front carry MTProto over WebSocket?

Why this is the last no-proxy idea worth testing: the web clients reach Telegram's DCs through
Telegram's own WebSocket bridges, and a bridge run by Telegram is not a third-party proxy - it is
the same service on an address the block has to keep open. tgnet already speaks this transport
(ProxySettings.Type.WEB -> WebProxyTransport), so a positive answer here is directly usable.

Raw upgrade request, no client library: a bridge answers 101, nginx without that vhost answers
400/404, and a plain static host answers 404 or closes.
"""
import socket
import ssl
import sys

HOSTS = ["web.telegram.org", "t.me", "telegram.org", "api.telegram.org"]
PATHS = ["/0/ws", "/1/ws", "/2/ws", "/4/ws", "/5/ws", "/ws", "/proxy", "/api/ws"]

TARGETS = sys.argv[1:] or ["91.108.32.1", "91.108.24.1", "149.154.167.220"]


def request(ip, host, path):
    req = (
        "GET %s HTTP/1.1\r\n"
        "Host: %s\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "Origin: https://web.telegram.org\r\n"
        "User-Agent: Mozilla/5.0\r\n\r\n"
    ) % (path, host)
    try:
        raw = socket.create_connection((ip, 443), 4)
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        s = ctx.wrap_socket(raw, server_hostname=host)
        s.settimeout(4)
        s.sendall(req.encode())
        data = s.recv(512)
        s.close()
        first = data.split(b"\r\n", 1)[0].decode("latin-1")
        if b"101" in first.encode():
            return "WS-UPGRADED  " + first
        return first[:60]
    except Exception as e:
        return "fail %s" % type(e).__name__


for ip in TARGETS:
    for host in HOSTS:
        for path in PATHS:
            r = request(ip, host, path)
            if "fail" not in r and "404" not in r:
                print("%-16s %-18s %-8s %s" % (ip, host, path, r))
        # one host per ip is enough unless something interesting shows up
        break
