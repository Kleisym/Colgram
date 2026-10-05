# Who ends the connection: the call stack says a timer, and the reason is not TLS

## What the stack actually shows

Capturing the call stack at the moment the `ConnectionTerminated` event is delivered:

```
asyncio/_run_once -> handle._run
  aioquic/asyncio/protocol.py:198  _handle_timer
  aioquic/asyncio/protocol.py:233  _process_events
  aioquic/asyncio/protocol.py:233  quic_event_received
```

So the event is delivered from the timer path. Not from `close()`, which was never called from
Python; not from `_handle_connection_close_frame`, which never fired; not from a TLS alert raised
inside `tls.handle_message`, which never fired either - all three were hooked directly and all three
recorded nothing.

The connection is holding a close event that nobody in the obvious path created:

```
held close_event : code=372 frame=0 reason=''
```

`frame_type=0` means it was not attached to any frame, and the empty reason means no branch in
`_close_begin` or the idle-timeout path wrote it - those two set `INTERNAL_ERROR` with
`reason_phrase="Idle timeout"`.

## What is ruled out, with the instrumentation that rules it out

```
peer CONNECTION_CLOSE frame   hooked _handle_connection_close_frame          -> never fired
TLS alert from received data  hooked tls.handle_message                    -> never fired
close() from Python           hooked QuicConnection.close                  -> never called
idle timeout                  read: peer max_idle_timeout = 56.0 s
```

The idle timeout is the finding that closes the most tempting reading. The edge asks for 56 seconds
of patience, the client dies at 1.2 seconds, and so the 0.84-1.22 s window is not a timeout anyone
asked for. The other edge on the same network, same code, advertises 180 s and stays alive.

## The remaining possibility, stated honestly

The close event is created inside aioquic's error path - the branch that catches a
`QuicConnectionError` in `_process_events`, stores it as the close event, and closes - but wrapping
`_process_events` to catch it produced a stream that neither handshook nor terminated, so that
instrument is itself broken and proved nothing.

So the precise state of knowledge is this: the termination is not a QUIC close frame from the edge,
not a TLS alert from the edge, not an idle timeout, and not a local `close()`. It originates inside
the client's own error handling, and the code it carries, `CRYPTO_ERROR + 116`, is a TLS
`certificate_required` that this client raises for itself.

That reframes the whole picture in a way that matters: the edge may never have refused anything. The
client may be refusing to continue because it cannot satisfy a certificate request that arrives
after the handshake - TLS 1.3 permits that - and the 0.84-1.22 s is the client giving up rather than
the edge closing. Which also explains why holding the connection for six seconds changes nothing:
the local decision was made at the first opportunity.

The practical consequence is the same either way, and it is not about the network. What a third-party
client must produce is a client certificate the edge accepts. `conf.json` and the service log show
the official client using a locally generated P-256 key, with `pkix_config: None` and
`install_root_ca: false`, so the certificate is self-signed and built at runtime from DPAPI- or
TPM-held material that this project cannot obtain.

## What this adds to the closed list

Everything previously measured still holds - the edge is reachable from host and device, the
transport is fine, ALPN is h3, post-quantum is not required, twelve request shapes leave the wire and
get nothing, the registered-key certificate match is disproved, no public endpoint issues a
certificate, and the secret is in no file and no registry key.

What is new is the direction of the failure: it is initiated locally, so "the edge blocks us" was
never the right frame. It is "this client cannot present the identity the edge asks for", which is a
build-and-material problem rather than a network one.

**No `warp=on` measurement exists. The tunnel does not work.**
