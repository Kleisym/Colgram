"""Does SNI-based blocking still apply, and does a different SNI to the same IP survive?

From net4people/bbs #81: DoH/DoT is blocked by TCP RST *after the TLS ClientHello*, keyed on
the SNI, and "TLS connections to the same IP address with a different SNI do not get RST".
If that still holds, the mitigation is not a new resolver but a different SNI on the same
endpoint - which is exactly what the app's own desync front already does for Telegram.
"""
import socket, ssl, time

def tls_sni(host, ip, sni, label):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    raw = socket.create_connection((ip, 443), timeout=8)
    t0 = time.time()
    try:
        s = ctx.wrap_socket(raw, server_hostname=sni)
        cert = s.getpeercert(binary_form=True)
        s.close()
        return '%-34s OK  %.0fms  cert=%dB' % (label, (time.time() - t0) * 1000, len(cert))
    except Exception as e:
        return '%-34s %s: %s' % (label, type(e).__name__, str(e)[:60])
    finally:
        try: raw.close()
        except Exception: pass

print('=== 1.1.1.1 with the DoH SNI (what a normal client sends) ===')
print(tls_sni('cloudflare-dns.com', '1.1.1.1', 'cloudflare-dns.com', 'SNI=cloudflare-dns.com'))
print()
print('=== same IP, different SNI (the bypass the report describes) ===')
print(tls_sni('one.one.one.one', '1.1.1.1', 'cloudflare.com', 'SNI=cloudflare.com'))
print(tls_sni('one.one.one.one', '1.1.1.1', 'www.cloudflare.com', 'SNI=www.cloudflare.com'))
print(tls_sni('one.one.one.one', '1.1.1.1', 'developers.cloudflare.com', 'SNI=developers.cloudflare.com'))
print()
print('=== a plain web server, for comparison ===')
print(tls_sni('example.com', '1.1.1.1', 'example.com', 'SNI=example.com'))
