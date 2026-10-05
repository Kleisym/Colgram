# The h2-only path is on the same edge, and it is closed the same way

## Where h2-only actually dials

The service log narrows the HTTP/2 endpoint set to exactly one address:

```
h2_tun: Restricted endpoints for HTTP/2
  from=[162.159.198.2:443, :500, :1701, :4500, :4443, :8443, :8095]
  to=[162.159.198.2:443]
```

So `warp-cli tunnel masque-options set h2-only` dials the same 162.159.198.2:443 this project has
been dialling, over TCP instead of QUIC. It is a different transport to the H3 attempt and it is the
officially supported mode for networks without working QUIC, which is exactly this network.

## What it returns

Four runs, each forcing the exchange by sending the HTTP/2 preface and SETTINGS and then reading:

```
no cert, no request    TLSV13_ALERT_CERTIFICATE_REQUIRED   rtt 397ms
with cert, no request  TLSV1_ALERT_ACCESS_DENIED            rtt 659ms
no cert, CONNECT-UDP   server closed with 0 bytes (silent)
with cert, CONNECT-UDP server closed with 0 bytes (silent)
```

`alpn=None` on the last two: the connection is closed before ALPN is ever negotiated, so the request
never leaves. This is the same shape as the H3 path - a client certificate is demanded, and whatever
is presented is refused - expressed over TCP.

## The TCP path is a bad instrument, and this is what it looks like

The same host, port and certificate gives `TLSV13_ALERT_CERTIFICATE_REQUIRED` on one run, a silent
close on the next, and `alpn=None` on the runs that send a request. The official client's log shows
the same edge failing H2 with:

```
13:02:06.804  Happy Eyeballs check failed v4=162.159.198.2:443
                err=TLS handshake failed unexpected EOF
              ERROR Happy eyeballs error AllChecksFailed
```

while MASQUE over QUIC on the same address and at the same moment succeeded. `unexpected EOF` and
`0 bytes` are the same observation, and both are what a post-handshake alert looks like on a socket
that has already been closed. That is why this file forces the exchange and reports the alert rather
than trusting the socket error, and why the QUIC framing was the reliable instrument throughout.

## What this closes

Both official transports are accounted for, and both fail at the same place:

```
masque over H3 (QUIC)    handshake ok, ALPN h3, SETTINGS received,
                         ConnectionIdIssued, then 0x174 at 1.03-1.22s over 23 observations
masque over H2 (TCP)     handshake refused: alert 116 without a certificate,
                         alert 49 with one, connection closed before ALPN in both cases
```

The official client's own record agrees: it raced `primary="masque" secondary="H2"` on this host,
H2 lost every time with a TLS failure, and the QUIC path won. The 781 ms window and the identity
material behind it are the same requirement in both transports.

## Where this leaves it

Network, protocol, edge, ALPN, SNI, post-quantum, ten transport parameters, twelve request shapes,
three request timings, seven registration shapes, every public API path, every storage location, and
now both official transports have been measured rather than assumed.

What satisfies the edge's window is identity material the desktop daemon produces at registration and
holds under DPAPI or a TPM. It is not derivable from the registration response, not obtainable from
any public endpoint, and not present in any file on this machine.

**No `warp=on` measurement exists. The tunnel does not work.**
