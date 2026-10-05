# My request experiments were sending nothing, and the fix is 50 ms

## The finding

Wrapping `QuicConnection.datagrams_to_send` - the exact call `QuicConnectionProtocol.transmit`
iterates to hand bytes to the transport - counts what aioquic actually emits:

```
no request              datagrams=4  (3820B)  term=372
with CONNECT-UDP         datagrams=4  (3914B)  term=372   <- 94B more output, 0 extra datagram

sent inside the handshake handler   datagrams=4  (3914B)
sent +50ms later                    datagrams=5  (3953B)   <- the fifth datagram is the request
sent +200ms later                   datagrams=4  (3821B)
```

So the request does reach the wire, but only when it is issued from a `call_later` after the
handshake event has been processed. Sent inline from inside the `quic_event_received` handler, the
write lands in the stream buffer during the same turn the connection is still draining its own
handshake state, and no datagram is produced for it.

Every request experiment in this project sent inline, from inside the handler. All of them - twelve
shapes across three targets, three timings, the plain GET, the request with and without
`capsule-protocol` - were writing into a buffer that was never flushed. The `0x174` they all produced
was the timer, and the request was never part of it.

## How the earlier readings were wrong, specifically

Three separate mistakes, each caught by measuring rather than reasoning:

1. **A sweep that tested nothing.** `masque_request_shapes.py` reported `sent=False` on all twelve
   rows because it waited 50 ms for a handshake that completes at 218 ms. It looked like twelve
   failures and was zero attempts.
2. **A count that could not be installed.** `socket.sendto` is read-only on a Python socket object,
   and `QuicConnectionProtocol` has no `_write`. Both raised visibly, so neither produced a false
   negative - but they meant the question stayed unanswered for several attempts.
3. **A counter that is always zero.** The congestion-control `sent_packets` reads 0 in this build,
   which made a working request look like a stuck one. That produced a wrong "the request is stuck
   in the client" reading, which was then itself corrected.

The reliable counting point, found by reading `transmit` rather than guessing:

```
def transmit(self) -> None:
    for data, addr in self._quic.datagrams_to_send(now=self._loop.time()):
        self._transport.sendto(data, addr)
```

## What the peer actually allows, for the record

Transport parameters read after the handshake, which also killed an intermediate wrong conclusion
that the edge had `max_streams_bidi = 0`:

```
WARP MASQUE edge     bidi=25000  uni=100  maxdata=10000000  stream_bidi=1000000
cloudflare-quic.com  bidi=100    uni=3    maxdata=10485760  stream_bidi=0
1.1.1.1 resolver     bidi=100    uni=3    maxdata=10485760  stream_bidi=0
```

The WARP edge is the most generous of the three on stream counts. The zeros seen earlier were the
initial values of the attributes, read before the handshake had completed.

## What this means for the conclusion

The sweep was redone with the request leaving. Seven shapes, all sent at +50 ms, all counted:

```
shape                sent   dgrams status   term
connect-capsule      True   5      None     372
connect-plain        True   5      None     372
connect-edge         True   5      None     372
connect-doh          True   5      None     372
connect-selfpath     True   5      None     372
get                  True   5      None     372
get-trace            True   5      None     372
```

The fifth datagram is present on every row, so every request was on the wire. No status, no body, and
the same termination on all seven - including a plain `GET /` and a `GET /cdn-cgi/trace`, which the
edge serves normally to other clients. So the request is delivered and the connection is closed
before any response, at the same moment in every case.

That is a stronger statement than "it ignores the request": the edge processes the connection through
the request and terminates on its own schedule regardless of what arrived.

## The control-stream question, settled the same way

Every HTTP/3 endpoint must open a control stream carrying SETTINGS, and a plausible explanation for
"delivered request, no response" was that the edge gates requests on the client's own control
stream. Tested with the correct API this time - `H3Connection._create_uni_stream(0x04)`, after an
earlier attempt called a private `get_next_available_stream_id` that does not exist, raised
AttributeError and sent nothing:

```
neither (control)         ctrl=False dgrams= 4  req=False status=None term=372 at=0.860
request only              ctrl=False dgrams= 5  req=True  status=None term=372 at=0.844
control stream only       ctrl=True  dgrams= 5  req=False status=None term=372 at=1.000
control stream + request  ctrl=True  dgrams= 5  req=True  status=None term=372 at=0.859
```

The fifth datagram is present on every row that does anything, so the control stream does reach the
edge. The termination is unchanged, and it moves neither with the control stream nor with the
request.

So the edge receives: a completed QUIC handshake with ALPN h3, the client's SETTINGS, a client
control stream, and a request - and returns no response and closes at 0.84-1.00 s. The official
client did the same handshake and got `Connected to 162.159.198.2:443` in 1.24 s. The difference is
identity material this client cannot produce.

Nothing about the certificate requirement changes: `alert 116` with nothing offered, `alert 49` with a
certificate, both stable over repeated runs. And nothing about the host changes - no WARP, no tunnel
settings touched.

**No `warp=on` measurement exists. The tunnel does not work.**
