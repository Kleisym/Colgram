# Where the tunnel stands, and what is left

## Established, by measurement

```
1) POST  /v0a4471/reg                     200, tunnel_protocol=masque
2) PATCH /v0a4471/reg/{device_id}         200, key echoed, key_type=secp256r1, tun_type=masque
3) certificate                           empty subject, zero extensions, 24h validity, same P-256 key
4) QUIC handshake to 162.159.198.2        0.187-0.204 s, ALPN h3
5) CONNECT /  :protocol cf-connect-ip      :status = 200
   with capsule-protocol: ?1, cf-connect-proto: cf-connect-ip, pq-enabled
6) session                               holds 10 s, no termination
7) edge SETTINGS                         max_datagram_frame_size = 65536, max_idle_timeout = 56 s,
                                         max_streams_bidi = 25000
8) HTTP Datagram capsule                 queued and emitted on the wire
```

The edge accepts the certificate, accepts the extended CONNECT, permits datagrams, and keeps the
session open. Every shape tried before this - more than twenty across five sessions - terminated at
1.03-1.22 s with alert 116 or 49 and never got a response.

## Not established

No response capsule has come back. The session opens and the edge accepts what is sent into it, but
nothing is returned, so:

```
no `warp=on` trace exists
no end-to-end traffic has been shown through this tunnel on this network
the tunnel is open, not yet shown to carry
```

## One thing that is not a failure

```
ValueError: Cannot send data on peer-initiated unidirectional stream
```

This appears in every run and is not a protocol fault. The full traceback shows it comes from
`StreamWriter.__del__` during interpreter teardown:

```
asyncio/streams.py:410  __del__ -> self.close()
aioquic/asyncio/protocol.py:269  close -> self.write_eof()
aioquic/quic/connection.py:1138  send_stream_data(self.stream_id, b"", end_stream=True)
```

The diagnostic process exits while a stream writer is still registered, and the writer tries to send
its EOF on a server-initiated unidirectional stream. It happens after the measurement, prints with
"Exception ignored in", and never reaches the network. Runs with it and without it report the same
status, the same held time, and the same datagram count.

## Where the remaining gap most likely is

The capsule format is settled by the reference implementation: context id 0 followed by a complete IP
packet, with a valid header checksum and a TTL above 1. What has not been tested is the destination
choice. `connect-ip-go` rejects a datagram whose destination is not inside a range the session
advertised or was assigned:

```
if !isAllowedDst {
    return fmt.Errorf("destination address / protocol not allowed: %s (protocol: %d)", dst, ipProto)
}
```

The registration assigned `172.16.0.2` and the session's own advertised ranges are what the edge will
accept. Whether the tunnel's default route permits arbitrary destinations is not established, and a
silently dropped datagram is exactly what that check produces.

The reference client routes a real TUN device through this, so it exercises whatever ranges the edge
grants. A client that has not advertised routes has to discover them, and that is the next thing to
try.

**The tunnel opens. `warp=on` through it is not yet measured.**
