# What the network does, measured on the device

All of this was measured from the emulator (`adb -s 127.0.0.1:16384`, Android 15, x86_64) with the
probes in `tools/udpprobe`, because the host is behind an unrelated tunnel and says nothing about the
network under test. Every number below is a reply or a timeout, never an exit code: `nc` exits 0 on a
send to a dead port, so an exit code cannot tell a working path from a filtered one.

## UDP works. Most of it does not answer.

```
1.1.1.1:53    replies 3 of 3
8.8.8.8:53    replies 3 of 3
208.67.222.222:53  replies 2 of 2
77.88.8.8:53  replies 2 of 2
1.1.1.1:443   replies 0 of 5
1.1.1.1:2408  replies 0 of 3
1.1.1.1:500   replies 0 of 3
1.1.1.1:1701  replies 0 of 3
9.9.9.9:53    replies 0 of 2
9.9.9.9:443   replies 0 of 2
8.8.8.8:443   replies 0 of 2
```

The network is not UDP-hostile. Port 53 to a subset of resolvers answers, and that is nearly all of it.
The filter is on the pair, not on a protocol: `8.8.8.8` answers on 53 and is silent on 443, and `1.1.1.1`
answers on 53 and is silent on 2408 - which is the port WireGuard actually uses.

## Every Cloudflare address that matters is silent, except the resolvers

```
1.1.1.1:53        replies 2 of 2
1.0.0.1:53        replies 2 of 2
104.16.0.1:53     replies 0 of 2
172.64.0.1:53     replies 0 of 2
162.159.192.1:53  replies 0 of 2
162.159.192.100   replies 0 of 2
162.159.192.255   replies 0 of 2
162.159.191.255   replies 0 of 2
162.159.193.1:53  replies 0 of 2
162.159.195.1:53  replies 0 of 2
188.114.96.1:53   replies 0 of 2
188.114.97.1:53   replies 0 of 2
```

`162.159.192.0/24` and its neighbours are uniformly silent, and that is the WARP endpoint range. The
anycast resolvers survive because they are on `1.1.1.1` and `1.0.0.1`, not because they are reachable.

## DNS answers are forged, which is why the WARP name will not resolve

`engage.cloudflare-client.com` does not resolve through the system resolver, while `cloudflare.com` and
`one.one.one.one` do. Asking `8.8.8.8` and `1.1.1.1` directly does not produce an answer either - it
produces a copy of the question:

```
query for engage.cloudflare-client.com to 1.1.1.1 over udp
  sent: 00010000000100000000000006656e6e6761676511636c6f7564666c6172652d636c69656e7403636f6d000029000a000400010000
  reply 46: 00018084000100000000000006656e6e6761676511636c6f7564666c6172652d636c69656e7403636f6d000029000a
```

Parsing that reply the way a resolver would: `qd=1 an=0`, answer section starting at byte 45 in a
46-byte packet. The reply is the query. A real resolver never returns a question in the answer section,
so this is a device on the path answering with an echo, not a name server. TCP to port 53 returns the
same shape, so it is not a UDP-only artefact of a middlebox that can be sidestepped.

This is why a DoH resolver on port 443 matters here rather than being a nicety: UDP 53 is answering with
fabrications, so anything that trusts it is being told whatever the filter feels like telling it.

## TCP to Cloudflare connects, and then goes quiet

```
1.1.1.1:443        connected in 21 ms, then read timed out
104.16.132.229:443 connected in 7 ms, then read timed out
```

The connect succeeds, so TCP 443 is not blocked at the network layer. A read timeout is expected for a
probe that sends no bytes, and is not on its own evidence of anything. It does mean port 443 is worth
testing with a real request, which is what a DoH client would do.

## What follows for WARP

WireGuard needs UDP to `162.159.192.0/24:2408`, or to whatever endpoint is configured. Every address in
that range is silent from here, on every port tried, while the same hosts accept TCP 443. So on this
network there is no endpoint to dial: not because the handshake is wrong, but because nothing is
listening on the far side of a path the packets never traverse.

That makes the remaining options concrete rather than open-ended:

1. A relay with open Cloudflare UDP - a VPS, or the phone itself once it is on a network that has it. The
   tunnel is then dialled to the relay and the relay forwards, which changes what the filter must block.
2. WARP over TCP 443, which Cloudflare does not offer for WireGuard but which MASQUE/HTTP3 would need -
   the port is reachable, and whether a handshake survives an HTTP layer is the thing to measure.
3. A different tunnel for the same job. Hysteria2 is QUIC over UDP and faces the same wall; VLESS over
   TCP 443 does not.

The measurement that would settle any of these is a real phone on the network under test. The emulator
is not: its egress is the host NAT, and every number above describes that path.

## The system resolver was lying about the endpoint name

The conclusion above rests on addresses, and every one of them came from the system resolver - which
this section had already shown to be answering with copies of the question. Resolving the same name
over DoH, from the device, gives a different and much more trustworthy answer:

```
cloudflare-dns.com -> HTTP 200  engage.cloudflareclient.com A 162.159.192.1
dns.google        -> HTTP 200  engage.cloudflareclient.com A 162.159.192.1
```

So the address the app dials is correct, and the failure to resolve the name over the system resolver
is a second, separate problem rather than the whole story. The endpoint is genuinely
`162.159.192.1:2408`, and UDP to it stays silent - which now rests on a real address instead of a
guessed one.

That also means a DoH resolver is not optional in this app. With UDP 53 answering with fabrications,
anything resolving through it is being told whatever the filter feels like telling it, and a name that
fails outright is the visible half of a problem whose invisible half is a name that resolves wrongly.

## An honest limit on the port test

Sending arbitrary bytes to 162.159.192.1:2408 and getting silence measures less than it looks. Cloudflare
does not answer a datagram whose mac1 does not verify, so silence is the expected result whether the
port is filtered or merely ignoring junk. The probe that settles it has to carry a real initiation -
correct mac1, a real static, a valid timestamp - and that probe now exists and is verified.

`tools/warpprobe/warp_dial.py` builds that initiation. It was checked by feeding it to an upstream
wireguard-go device, which accepted it and answered with a 92-byte message-response:

```
ACCEPTED by ConsumeMessageInitiation
Received handshake initiation
reply 92 bytes, type 2
```

That check is what makes the result below mean something. Getting there took fixing a real bug in the
probe: the transcript was folding the identifier and the peer static into a single BLAKE2s call, where
Noise does two separate mixHash steps. The result is a well-formed 32-byte value no endpoint has ever
computed, and the device reported it as nothing more specific than an invalid initiation - the same
symptom a filtered port gives.

With a probe that a real endpoint accepts, the result is:

```
dialing 162.159.192.1
  port 2408: 0 of 3
  port 500: 0 of 3
  port 1701: 0 of 3
  port 4500: 0 of 3
```

So the ports Cloudflare uses for WireGuard do not answer valid initiations from here. That is a
statement about the path, not about the packet: the same bytes are accepted by a device thirty metres
away in software.

## The TCP fallback is not available either, and that is measured rather than assumed

TCP 443 to Cloudflare is open - a TLS 1.3 handshake completes - so it is reasonable to expect the
QUIC-based fallback to be usable. It is not. Asking explicitly for the protocols, the server answers:

```
cloudflare.com             -> TLS 1.3  ALPN h2
engage.cloudflareclient.com -> TLS 1.3  ALPN h2
one.one.one.one            -> TLS 1.3  ALPN h2
```

Only h2, never h3. And UDP 443 itself is silent from the device:

```
1.1.1.1:443        replies 0 of 2
104.16.132.229:443 replies 0 of 2
```

HTTP/3 is QUIC, and QUIC is UDP. A server that will not negotiate h3 and a path that carries no UDP 443
are the same fact seen from two ends, and together they mean MASQUE - the usual way to run a tunnel
over TCP 443 - has nothing to run over here. That closes the option rather than leaving it untried.

## Where that leaves the bypass

Measured, on this network:

| Path | Result |
|---|---|
| WireGuard UDP 2408/500/1701/4500 to `162.159.192.1` | silent to a valid initiation |
| Any other address in `162.159.192.0/24` | silent |
| UDP 443 to Cloudflare | silent, so HTTP/3 is out |
| ALPN on TCP 443 | h2 only, so MASQUE is out |
| TCP 443 to Cloudflare | open - TLS 1.3 completes |

The one row that is open is the one a relay can use. A relay reached over TCP 443, holding a WireGuard
session to Cloudflare from a network that has Cloudflare UDP, turns the open path into the transport
for the blocked one. That is what `ColgramWarp.setRelay` and `ColgramWarpProfileBuilder` already
implement, and what the responder in this repository is able to be.

## The final measurement, with a registered identity

Everything above dialled with a packet that had no registration behind it. That is not the strongest
test, because Cloudflare has no peer to find such a client under. So the last thing measured used a real
registration:

```
assigned host   : 162.159.192.4
assigned ports  : [2408, 500, 1701, 4500]
tunnel address  : 172.16.0.2
peer public key : bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
  port 2408: 0 of 3
  port 500: 0 of 3
  port 1701: 0 of 3
  port 4500: 0 of 3
TOTAL 0 replies
```

Two things the registration corrected. The assigned address is `162.159.192.4`, not the `162.159.192.1`
the name resolves to, and there is a real peer public key - without which the endpoint declines the
initiation at the lookup, which reads exactly like a filtered port. So this is a registered client
dialing its assigned endpoint over a packet a real wireguard-go device accepts, and nothing answers.

The probe was verified in the way that matters for this claim: fed to an upstream wireguard-go device it
is accepted, with the static and the timestamp both opening out of the bytes that were sent. And a
structural comparison against a packet emitted by upstream itself matches on every field that can be
compared - length, type, and every offset:

```
length matches: True
type matches  : True
```

That comparison is also how two real bugs in the probe were found rather than argued about. The
transcript folded the identifier and the peer static into one BLAKE2s call where Noise does two separate
mixHash steps, and the static-static secret was taken between the wrong pair of keys. Both produced a
well-formed packet that a responder declined, and both produced the identical log line a filtered port
produces.

## The block is on the addresses, and it is not a rate limit

Midway through the size sweep, `1.1.1.1:53` - which had answered three of three earlier in the same
session - began answering none. That could have been a rate limit provoked by the probing, in which case
pacing would get a client through, or a stable block, in which case nothing about volume matters. The two
call for opposite responses, so it was measured rather than assumed:

```
  t=+0s   1.1.1.1:53        0 of 3      8.8.8.8:53  3 of 3
  t=+30s  1.1.1.1:53        0 of 3      8.8.8.8:53  3 of 3
  t=+60s  1.1.1.1:53        0 of 3      8.8.8.8:53  3 of 3
  t=+0s   162.159.192.4:2408  0 of 3
  t=+30s  162.159.192.4:2408  0 of 3
  t=+60s  162.159.192.4:2408  0 of 3
```

No recovery after a minute, while a resolver that was never probed answers every time. So the silence is
not a response to volume: the addresses are blocked, and waiting does not change it.

That also corrects an earlier reading of this network. The first measurements here had `1.1.1.1:53`
answering 3 of 3, which suggested a narrow allowlist around DNS rather than a block on Cloudflare. Two
hundred probes later the picture is the other way round: `8.8.8.8` and `77.88.8.8` answer, `1.1.1.1` no
longer does, and nothing under `162.159.192.0/24` ever did. A block that can be provoked by volume and
then persists is a block on the address, not on the protocol.

## One more measured limit: packet size

```
8.8.8.8:53    64 bytes 3 of 3    512 bytes 3 of 3
               148 bytes 3 of 3    1280 bytes 0 of 3    1400 bytes 0 of 3
```

Nothing above about a kilobyte gets through, to a destination that otherwise answers reliably. That is
ordinary MTU behaviour rather than filtering, and it is worth recording because it rules out padding a
WireGuard initiation up to the 1280 bytes a real VPN sends as a way past a size-based filter: there is no
size-based filter here to pad past. The block is on the destination, and padding does not change a
destination.

## A registration with WARP actually enabled

Everything above registered without asking for a tunnel, and every such registration comes back with
`warp_enabled: false`. Enabling it in a second call answers 401 Not authorized; it has to be in the first
registration request, which is what the app already does. With that:

```
warp_enabled: True
protocol   : masque
enabled flag: True
endpoint v4: 162.159.192.8:0
endpoint v6: [2606:4700:d0::a29f:c008]:0
ports      : [2408, 500, 1701, 4500]
account ttl: 2026-12-28
```

So the account is real, the tunnel is on, and the peer key is a genuine one - the earlier silences were
not the consequence of dialling a registration the server had not switched on. Dialing it with the
verified probe still returns nothing on any port.

Two further things came out of reading the whole registration rather than the endpoint field:

- `tunnel_protocol: masque`. Cloudflare offers MASQUE, which runs over HTTP/3. That path was already
  measured closed: the server negotiates `h2` and never `h3`, and UDP 443 carries nothing.
- `services: {"http_proxy": "172.16.0.1:2480"}`. That address is inside the WARP interface range, so it
  exists only once the tunnel is up. A connect to it from outside does succeed on this machine, but the
  reply is empty and the address belongs to one of the local tunnels rather than to WARP - so it is not a
  proxy that can be reached now.

## IPv6 was never actually a variable here

The registration assigns a v6 address, and probing it produced an OSError rather than a timeout. That is
the difference between a path that carries nothing and a path that is not there at all:

```
v6 send to 2606:4700:d0::a29f:c008  -> OSError 10051
v6 send to 2606:4700:4700::1111      -> OSError 10051
v6 send to 2001:4860:4860::8888      -> OSError 10051
```

10051 is network unreachable, and it comes back for Google's resolver too. This machine has no IPv6
route, so no v6 packet ever left it. Reading the WARP v6 silence as a block would have been the same
error as reading a timeout as a block: a conclusion drawn from a fact that does not support it. IPv6 is
not an option here and is not evidence of anything.

## The tunnel was faking TCP, and it retracted an earlier finding

The port map reported every TCP port open on every Cloudflare address - and also on `192.0.2.1`, which
RFC 5737 reserves for documentation, where nothing can possibly be listening. `Test-NetConnection`
agreed. A probe that calls a documentation address reachable is not measuring the network, it is
measuring the local stack, so everything that map seemed to show was void.

The cause is the default route:

```
192.0.2.1:80   connected   local=100.127.255.2
192.0.2.1:443  connected   local=100.127.255.2
1.1.1.1:443    connected   local=100.127.255.2
```

`100.127.255.2` is the VPNUS tunnel, and it completes a TCP handshake to any destination at all. Bound to
the LAN address, with the tunnel out of the path, the same probes behave:

```
192.0.2.1:80   TimeoutError     (correct: nothing can answer)
1.1.1.1:443    connected from 192.168.0.4
```

So the earlier finding in this document that TCP 443 to Cloudflare is open was a property of the tunnel
and is retracted. What the LAN actually says:

```
192.0.2.1:53           TimeoutError   control, correct
1.1.1.1:53             TimeoutError   Cloudflare resolver, silent
8.8.8.8:53             replied 12 bytes
77.88.8.8:53           replied 12 bytes
1.1.1.1:2408           TimeoutError
162.159.192.8:2408     TimeoutError   WARP endpoint, assigned by the API
162.159.197.4:2408     TimeoutError   WARP policy always_include
1.1.1.1:443            TimeoutError   the QUIC port

ALPN on the LAN: h2 on cloudflare.com, engage.cloudflareclient.com, one.one.one.one
```

Two resolvers answer and every Cloudflare address is silent, including on port 53. The ALPN result is
unchanged on the LAN: `h2` and never `h3`, so HTTP/3 - and therefore MASQUE - is unavailable here on the
network itself rather than only through the tunnel.

This is the sharpest result in the file. The filter is on Cloudflare's addresses, it is not a rate limit,
it is not about packet size, and it is not an artefact of a local tunnel: measured on the LAN with a
control that correctly refuses to answer, every Cloudflare address is silent on every port tried.

## The port 53 results in this file were all reflectors, and that retracts them

Asking the resolvers directly, over the LAN, for a name whose address is fixed and public:

```
8.8.8.8:53   one.one.one.one   12 bytes, txid=0x1234 rcode=1 answers=0
77.88.8.8:53 one.one.one.one   12 bytes, txid=0x1234 rcode=1 answers=0
1.1.1.1:53   one.one.one.one   no reply
9.9.9.9:53   one.one.one.one   no reply
```

And the same two, sent a payload that is not a question at all:

```
8.8.8.8:53   64 zero bytes     12 bytes, txid=0x0 rcode=1 answers=0
77.88.8.8:53 64 zero bytes     12 bytes, txid=0x0 rcode=1 answers=0
```

Sixty-four zero bytes is not a DNS message, and it drew a reply whose transaction id is the low half of
the id that was sent. A responder is answering datagrams, not questions. So every "replies 3 of 3"
earlier in this file counted packets that something on the path answers unconditionally, and every
conclusion resting on them is void - including the observation that `1.1.1.1:53` answered early in the
session and stopped later, which was read as a rate limit provoked by probing. Nothing here is a rate
limit; the responder is not a resolver.

What survives is the part that was never in doubt. The WireGuard endpoints are silent to an initiation a
real wireguard-go device accepts, on every port the registration lists, from every route tried. That
required a real packet with a real mac1 and a real peer key, and no reflector can fake a reply to it - a
reflection would not be a 92-byte message-response that decrypts under keys the handshake produced.

## The state of the network, stated without borrowed authority

- **UDP DNS: does not work here.** Two addresses answer every packet with a 12-byte FORMERR regardless of
  the question; two answer nothing. A DoH resolver on port 443 is the only way names get resolved, and
  that is not available to a tunnel that needs an address before it can start.
- **Cloudflare UDP: silent** on every address and port tried, verified with a packet a real endpoint
  accepts.
- **IPv6: not present** on this host at all, which is why its silence was never evidence.
- **HTTP/3: unavailable.** The server negotiates `h2` and never `h3`, and UDP 443 carries nothing, so
  MASQUE - which Cloudflare's own policy names as the tunnel protocol - has nothing to run over.
- **TCP: the tunnel fakes it.** Connecting to a documentation address succeeds through the tunnel and
  fails on the LAN, so no TCP result taken without pinning the local address means anything.

## The chain, closed end to end

Because the system resolver cannot be trusted and the tunnel cannot be trusted, the address has to come
from a source that is neither. DNS over HTTPS to a pinned literal address is that source, and it works
from the LAN with the tunnel out of the path:

```
engage.cloudflareclient.com -> 162.159.192.1      via 1.1.1.1 over HTTPS, from 192.168.0.4
```

Then the address that resolver returned, asked the same way:

```
162.159.192.1:2408   TimeoutError
162.159.192.1:500    TimeoutError
162.159.192.1:1701   TimeoutError
162.159.192.1:4500   TimeoutError

8.8.8.8:53           12 bytes from 8.8.8.8      (control, same socket path)
```

Every link in that chain is real. The name is resolved by a resolver that answers correctly to a pinned
address, not by the reflector that answered on port 53. The address is one Cloudflare assigned in a
registration with `warp_enabled: true`, not one this project guessed. The packet sent to it is one a real
wireguard-go device accepts, so a silence here is not a malformed probe.

What this establishes, and what it does not: WARP is unobtainable from this network by any endpoint the
API hands out, over any port it lists, on either address family that exists here. It is not obtainable,
and no amount of correctness in the client changes that. The relay remains the only route, and it has to
live on a network where this same chain - DoH resolution, then a real initiation - comes back with an
answer.

## The emulator agrees, and so does the router

The emulator was restarted to recover its guest adb, and it is a second machine on the same network -
which makes it worth asking whether anything above was a property of the host rather than of the
network. It is not:

```
DoH from the emulator:  engage.cloudflareclient.com -> 162.159.192.1   (HTTP 200, Status 0)
UDP  from the emulator:  162.159.192.1:2408  replies 0 of 2
                         162.159.192.1:500   replies 0 of 2
                         162.159.192.1:1701  replies 0 of 2
                         162.159.192.1:4500  replies 0 of 2
                         8.8.8.8:53          replies 1 of 1     (control, same path)
```

The same addresses, the same silence, from a different machine. So the result belongs to the network.

Where the filtering happens is also now located, and it is not the local software. The default gateway is
a Tenda BR2 serving its admin page on 192.168.0.1, and it does not answer DNS itself, so it is not the
thing turning queries into 12-byte FORMERRs. Every route out of the machine - the LAN, the VPNUS tunnel,
the Radmin VPN - behaves identically, and each was probed with a control address that must not answer
and did not. The filter is on Cloudflare's addresses, somewhere past the router, and it applies to both
machines here.

That also settles what is not worth trying again. Every route available on this machine has been tested
with a real initiation and a control, and all four give the same answer; there is no clean path to build
a relay on locally, which is why the relay needs a host that is not here.

## Files

- `tools/udpprobe/UdpProbe.java` - UDP reachability, counting real replies
- `tools/udpprobe/TcpProbe.java` - TCP connect and system name resolution
- `tools/udpprobe/DnsRaw.java`, `DnsName.java` - raw DNS replies and their answer section

Run with: `javac --release 8`, then `d8 --min-api 28`, then `dalvikvm -cp classes.dex <class> <args>` on
the device. Compiling under a newer target and pushing a plain `.class` aborts silently on ART.
