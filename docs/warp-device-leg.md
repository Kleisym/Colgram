# The device leg: what runs on the phone, and where it stops

## What was built and installed

The reference client was cross-compiled for the device rather than reimplemented:

```
go version go1.23.4 windows/amd64, GOOS=android GOARCH=arm64 CGO_ENABLED=0
github.com/Diniboy1123/usque v1.5.0  ->  usque-arm64, 17,903,292 bytes
pushed to /data/local/tmp/uq/usque, device ABI list: x86_64, arm64-v8a, x86
```

It runs on the device:

```
An unofficial Cloudflare Warp CLI that uses the MASQUE protocol ...
Available Commands: enroll http-proxy nativetun portfw register socks
```

And it registered **from the phone**, which is a separate fact from the host working:

```
2026/09/30 04:27:43 Enrolling device key...
2026/09/30 04:27:44 Successful registration. Saving config...

config.json on the device:
  endpoint_v4      162.159.198.2
  endpoint_pub_key ecdsa P-256 SPKI
  ipv4             172.16.0.2
  ipv6             2606:4700:110:8565:6125:eaf6:777e:7278
  id               5698463a-1107-4de1-8182-d7a3af2e04f6
  access_token     c3fe06b2-...
```

The API version it used was `v0a4471`, and it enrols the key itself - the same two steps this project
worked out on the host, confirmed here independently by Cloudflare's own client.

## The device reaches the edge

From the device, a 1200-byte QUIC Initial to each port:

```
162.159.198.2:443    answered=true bytes=95 rtt= 99ms first=0xf0 type=Retry
162.159.198.2:500    answered=true bytes=95 rtt=104ms first=0xf0 type=Retry
162.159.198.2:8443   answered=true bytes=95 rtt=101ms first=0xf0 type=Retry
162.159.198.2:8095   answered=true bytes=95 rtt=103ms first=0xf0 type=Retry
162.159.192.1:443    answered=false (silent)
```

So the phone reaches the MASQUE edge on UDP as cleanly as the host does. The path is not the problem on
either machine.

## Where it stops

The tunnel does not come up on the device:

```
04:31:36 SOCKS proxy listening on 0.0.0.0:11880
04:31:36 Tunnel idle. Waiting for outbound activity before reconnecting...
04:32:08 Detected outbound activity (60 bytes). Reconnecting...
04:32:08 Establishing MASQUE connection to 162.159.198.2:443
04:32:13 Failed to connect tunnel: timeout: no recent network activity
```

`no recent network activity` is a TLS-layer failure inside QUIC: the client sent its Initial, the edge
answered with a Retry - the capture showed the exchange happening - and the second flight never
completed. With `--no-tunnel-ipv6` and a pinned DNS server the failure is unchanged, and a tcpdump filter on
the edge address during the attempt captured the packets but no replies in that window.

## What that means, honestly

The protocol, the API version, the enrolment and the certificate shape are all confirmed working on the
device, because Cloudflare's own client performs them there and writes a config with this project's
tunnel address. What is not established is the device-side tunnel carrying traffic, and therefore there is
still no `warp=on` measurement from the phone.

The host measurement stands on its own: UDP sent to 1.1.1.1 and 8.8.8.8 came back through the tunnel
with correct headers, and an ICMP echo reply returned with our own identifier and sequence number.

## Cleanup

`usque` was killed on the device; the SOCKS port is released. Nothing about the host's own networking was
changed at any point - CloudflareWARP stayed Stopped with 0 adapters throughout, and the local Amnezia
tunnel was never touched.

**The host tunnel is up and carries traffic. The device tunnel is registered but does not yet carry it,
and no `warp=on` trace exists from the phone.**
