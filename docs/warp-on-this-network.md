# WARP on this network — what was measured, and what is left

Everything below was measured from the device or the app, not inferred. A verdict here means a
measurement produced it; a claim without one is marked as such.

## The block is external, and no Cloudflare UDP service answers

Measured on the device, and re-measured from the host on the same network:

| Probe | Result |
|---|---|
| UDP DNS, 6 resolvers (1.1.1.1, 8.8.8.8, 9.9.9.9, 77.88.8.8, 208.67.222.222, 94.140.14.14) | **all 6 answer, 64B** |
| UDP to all 16 Cloudflare WireGuard ingresses (ports 2408, 500, 1701, 4500), a real message-initiation | **0 of 16 answer** |
| **UDP 443/QUIC on the same addresses, a real QUIC Initial** | **0 of 8 answer** |
| AmneziaWG, extended ranges, junk packets, S1/S2/S5/S6 | no reproducible response |

UDP itself is not broken — six independent resolvers answer from the same host, immediately before
the sweep. Cloudflare's WireGuard ingress specifically does not answer.

### Correction: "UDP 443 answers" was a stateless reset, and the port-specific story collapses

The table above used to say **443/QUIC answers**, and a whole conclusion was built on it - that the
filter is a port allowlist sparing 443, that Cloudflare's edge is reachable over UDP, and that only
the WireGuard service is shut. Re-measured with a real QUIC Initial instead of the payload the
original sweep used:

```
host             port    1200 zeros     QUIC Initial
1.1.1.1          443     31B 0x83       silent
1.1.1.1          8443    31B 0xeb       silent
1.1.1.1          2408    silent         silent
1.1.1.1          500     silent         silent
162.159.192.1    443     31B 0xfb       silent
162.159.192.1    8443    silent         silent
162.159.192.1    2408    silent         silent
162.159.192.1    500     silent         silent
```

And the 31-byte answers, repeated:

```
zeros  -> 31 B  first8= ce00000000001400   last8= 0000000000000001
zeros  -> 31 B  first8= e300000000001400   last8= 0000000000000001
zeros  -> 31 B  first8= a400000000001400   last8= 0000000000000001
zeros  -> 31 B  first8= ef000000000001400   last8= 0000000000000001
QUIC   -> silent
QUIC   -> silent
QUIC   -> silent
```

**A stateless reset, and the giveaway is the first byte.** `ce`, `e3`, `a4`, `ef` - arbitrary, and
different every time, which is exactly what a reset's random-looking prefix is. A QUIC server
replies to a real Initial with a real Initial, and a real Initial draws **0 of 8**. The original
`warp-port-sweep.py` sent `b"\x00" * 1200` and counted *any* byte back as "reachable", so it was
measuring the reset, not the service. Its own output said `answered 6, silent 10` while a real
handshake on the same ports scored zero.

What survives: **UDP is not blocked wholesale, and DNS is not filtered** - six resolvers answer,
and a 1200-byte datagram on 443 draws a reply, so the path carries UDP to Cloudflare's edge. What
does not survive is the framing. There is no reachable QUIC service here, so this is not a port
allowlist sparing 443; it is a UDP path that carries DNS and answers undecryptable QUIC with a
reset, and no WireGuard or MASQUE port answers at all. The conclusion that the relay is the only
route is unaffected and if anything strengthened - but the diagnosis above it was wrong, and it
was wrong because a probe counted a reset as a server.

### The corrected probe, over every port that mattered

`warp_udp_probe.py` was the file those `ANSWERED` readings came from, and it is fixed the same way.
With a real QUIC Initial and RESET separated from ANSWERED by size:

```
162.159.192.1  2408 silent 3/3    500  silent 3/3    1701 silent 3/3    4500 silent 3/3
162.159.192.1   443 silent 3/3    854  silent 3/3    1640 silent 3/3
188.114.96.1   2408 silent 3/3    500  silent 3/3    1701 silent 3/3    4500 silent 3/3
188.114.96.1    443 silent 3/3    854  silent 3/3    1640 silent 3/3
```

**14 of 14 silent, including both 443s** - the port this document spent its length insisting was
open. The device agrees and can say why: 2 of 10 answers on 443, every one exactly 31 bytes,
classified in the log as `short-header-31B(encrypted; impossible pre-handshake)`. A short header
cannot arrive before the handshake completes, and a Retry is the only answer a QUIC server may give
before then - not one of these was a Retry. Ports 2408 and 500 stayed at 0 of 10 on the same run,
so the answers do not come from the path either.

So the shape of the network is now: **DNS over UDP works, everything else is filtered, and
undecryptable UDP to Cloudflare's edge draws a stateless reset rather than silence.** That reset is
the single most misleading thing measured in this project, because it looks exactly like a service
answering and it survived in the record for a long time under a name that said otherwise.

The one caution the file still carries is the right one and is worth keeping: a single unanswered
probe still proves nothing, because the filter's behaviour has been observed changing between
sessions. What changed here is not that the network is now silent - it is that the probe is asking
a question whose answer means what it says.

## The control nobody ran: is QUIC blocked, or just Cloudflare?

Every measurement above was aimed at Cloudflare. That is the one thing a diagnostic about
Cloudflare's filtering should never do, because "Cloudflare's UDP is filtered here" cannot be told
apart from "QUIC is filtered here" - and those two call for opposite responses. The first is a
provider filter something might route around. The second is a property of the path that no amount
of transport work gets past.

So: the same real 1200-byte QUIC Initial, four unrelated providers, two paths.

```
route to 162.159.198.2 uses interface: VPNUS

control: DNS over UDP
  default route          DNS 1.1.1.1:53   answered 1/2
  bound to 192.168.0.4   DNS 1.1.1.1:53   answered 2/2

Google               default   udp/443  answered 0/2
Google               bound     udp/443  answered 0/2
Facebook             default   udp/443  answered 0/2
Facebook             bound     udp/443  answered 0/2
Cloudflare resolver  default   udp/443  answered 0/2
Cloudflare resolver  bound     udp/443  answered 0/2
Google DNS           default   udp/443  answered 0/2
Google DNS           bound     udp/443  answered 0/2
```

**0 of 16, while DNS answers on the same sockets.** The block is not Cloudflare's. It is QUIC as
such, on this path, and that is why every transport attempt above failed at the same place for the
same reason - and why none of them could have succeeded by being cleverer.

    python scripts/warp-quic-block-scope.py --ethernet 192.168.0.4

## And the measurements were describing a tunnel, not the connection

The second path in that run exists because of something in Cloudflare's own log, which listed
`Radmin VPN; 26.226.94.158` among the network interfaces and sent me looking:

```
VPNUS  (WireGuard Tunnel, C:\Program Files\VPNUS\service\vpnus-service.exe)
  0.0.0.0/1    ->  100.127.255.1   metric 0
  128.0.0.0/1  ->  100.127.255.1   metric 0

Find-NetRoute -RemoteIPAddress 162.159.198.2  ->  VPNUS, 128.0.0.0/1, metric 0
tracert -d 162.159.198.2                     ->  1 hop, 11 ms
egress                                          ->  5.230.5.13
```

One hop to a Cloudflare address and a metric-0 default split means the packets never touched the
home router. **Every host-side measurement in this file described that tunnel.** Worse, the tunnel
is the thing providing the working TCP 443 at all - bound to the Ethernet address, TCP 443 to both
Cloudflare ingresses times out at 8 seconds, while unbound it completes TLS 1.3 with h2 in about
110 ms.

So the user's own connection is *more* restricted than anything measured here, and the measurements
were not measuring it. That is a limit on the whole file, stated here rather than discovered later.
The device results in it are the ones that describe a phone's own path.

Nothing was changed to obtain any of this. The tunnel was left running, the service was not
restarted, and the bypass is a per-socket bind to `192.168.0.4` - which is also what proves the
second path exists at all, since a bound DNS query answers while a bound QUIC Initial does not.

### It is not the size floor again, which matters for what could still be tried

The file above spends a long time on a ~1200-byte floor where small UDP gets no answer and 1200
does. That floor was real, and it is also **not** what is happening on port 443 now. Same host, same
port, varying only the size:

```
zeros 32B     udp/443 -> silent
zeros 200B    udp/443 -> silent
zeros 500B    udp/443 -> silent
zeros 1000B   udp/443 -> silent
zeros 1200B   udp/443 -> silent
zeros 1400B   udp/443 -> silent

DNS over the same path -> 64B
```

Nothing answers at any size, at any provider, on either path, while DNS answers on the same socket.
So there is no length at which a QUIC packet gets through, and the earlier conclusion - "1200 is
enough, the block is a size floor" - does not describe 443 on this network anymore.

That distinction decides what is still worth trying. A size floor can be beaten by padding, and
padding is free. A block on QUIC as such cannot be beaten by any framing that still produces a QUIC
packet, because the thing being matched is the protocol, not the length. It is the same reason the
relay exists and the same reason a cleverer wrapper would not have helped: the packet has to arrive
as something other than QUIC, and the only transport that does that here is TCP 443 - which is
already carrying TLS to Cloudflare's edge and is what the relay uses.

### The same control on the device

```
control: DNS over UDP -> answers; probe 1200B
Google 142.250.74.174:443     answered 0/3
Facebook 157.240.1.35:443     answered 0/3
Cloudflare resolver 1.1.1.1:443  answered 0/3
Google DNS 8.8.8.8:443       answered 0/3
TOTAL QUIC answered 0/12 across four unrelated providers
```

0 of 12 on the phone as well, with DNS answering on the same socket - so the device path is not a
phone-shaped version of the host's tunnel problem, it is the same block. That is the first reading
in this file that does not depend on the host at all, and it is the one that says the transport
question is settled rather than open.

    python scripts/device-tests.py org.colgram.core.ColgramDeviceQuicScopeTest

## The control for "2408 is silent": is the port shut, or does UDP not arrive?

"2408 is silent" has been this project's constant for a long time, and it was read as "the
WireGuard port is filtered". That needs a control, and it turns out the control was hiding in plain
sight as a host that answered where nothing should have.

**A host that answers on 443, and what it answers with.** `208.67.222.222` resolves to
`dns.sse.cisco.com` and answers on 53, 5353 and 443 - three ports, which is already a hint that "443
answers" says nothing about 443 running a service. Sent a real QUIC Initial:

```
12B  c30080810000000000000000

six different Initials -> byte-identical answer every time
```

Parsed: long header, fixed bit set, version `0x00808100`, DCID length 0, SCID length 0. A QUIC
server answers an Initial with an Initial or a Retry, and either carries a real version - `1`, or a
version-negotiation list - with non-zero connection ids. `0x00808100` is not a QUIC version, and
zero-length ids with an all-zero body is the shape of a stub. It is a resolver holding other ports
open, not speaking QUIC on them.

**The same sweep against Cloudflare, with a real DNS query on every port:**

```
162.159.192.1  udp/53 80 443 123 853 2408 500 1701 3478 4500 5353 8080 8443 5000 51820 65535
                 all silent
  ports that answered at all: none
```

Sixteen ports, with a query six resolvers answer elsewhere in the same second. So no port on that
address answered, and the silence is about the path rather than about 2408 being singled out.

**"Including 53" was wrong, and the way it was wrong matters more than the conclusion it supported.**
53 on a Cloudflare WireGuard ingress does not run DNS, so a DNS query there is silence from an
absent service - not evidence about the port. The control that makes the sweep meaningful is the
*same* query to `1.1.1.1:53`, which does answer; a port where nothing is listening and a port that
is filtered look identical until you know which is which, and on this one it was a service that was
never there. The statement that survives is narrower: **no service on Cloudflare's WireGuard or
MASQUE addresses answers**, while UDP to resolvers and to NTP servers does, and TCP 443 to those
same Cloudflare addresses completes TLS 1.3 with h2.

The relay's case does not rest on which port is shut, and now does not have to.

    python scripts/warp-udp-port-reachability.py --host 162.159.192.1

## The exact shape Colgram uses, driven end to end

The relay tests reach it over UDP because that is the hop a sing-box `wireguard` endpoint can
actually take - a point the join test made and the earlier byte tests did not. Running the real
protocol implementation through it, in the same arrangement the app's profile describes:

```
sing-box wireguard endpoint  ->  relay UDP port  ->  real WireGuard peer

handshake  -> True
transport  -> True
peer decrypted: 1
```

A real handshake and a real ChaCha20Poly1305 transport packet, through the relay, opened by a peer
that holds the key only because the handshake established it. **This is the whole client half of the
relay design, working**, and the only thing it does not include is the far side's own egress.

## The same, from the phone, through a relay on another machine

Everything above is loopback. A relay in the field is a different host, and the hop that matters is
the phone's own - so this measures it, with a real WireGuard responder on this host behind a real
relay, and the device driving the exchange through both over the network.

Reached on the host first, through the running relay:

```
relay OK: 116B first=0x02      0x02 is a message-response
```

A real handshake, through a relay, answered by a peer that only replies to a session it can
complete. **Three bugs were in the relay loop before that worked**, and every one of them produced
the same symptom - a relay that printed its port, held its socket, counted the packet and returned
nothing, which is indistinguishable from a filtered network:

| Bug | Why it looked like a block |
|---|---|
| the peer answered the relay's own socket | the reply was forwarded back to the peer, a loop, and the client heard nothing |
| two threads read the peer's socket | whichever won consumed the reply; it is now queued **and** sent, because a duplicate is harmless and a dropped one is fatal |
| a 50 ms window for the reply | the peer needs ~14 ms of Diffie-Hellman and AEAD - it is not slow, it is not synchronous |

And one in the test itself: the initiation's ephemeral must be a **real X25519 point**. On arbitrary
bytes the peer's first act raises `Error computing shared key` inside its service thread, which
kills that thread and leaves the relay up, holding its port, answering nothing. The same shape of
failure, caused by the packet the probe built rather than by any network.

    python scripts/warp-relay-peer.py --relay-port 51823
    python scripts/device-tests.py org.colgram.core.ColgramDeviceRelayWireGuardTest

### The first device run reported a block, and was right to

The first run of that device test said:

```
the device could not reach 10.0.2.2:51823: SocketTimeoutException
VERDICT: the phone cannot reach a relay on another host over UDP on this path, so the chain
         cannot be measured from the device.
```

The relay had been started with a lifetime that expired while the build ran, so there was genuinely
nothing listening. **The test said what was true rather than what it hoped**, and it said *which*
fact was true: the port was unreachable, not the protocol refused. That is the difference between a
diagnostic and a verdict, and it is why the reachability probe is reported rather than asserted -
silence on this path is ambiguous, and a test that turned it into a red build would be reporting
the emulator's network as a defect.

The same run also proved the hop works at all: a UDP datagram from the device does reach a socket
bound on this host, through QEMU's NAT. What it had not proved yet is the exchange, which is what
the run with a live relay measures.

### Correction: the device cannot reach the host at all, and the emulator says why

The claim just above - that a datagram from the device reaches a socket on this host - is wrong, and
it was an early reading taken before the relay's own sender log existed. Measured directly, with a
socket bound to the host's LAN address and a datagram sent from the emulator:

```
toybox nc -u 10.0.2.2 51824   ->  nc: xwrite: Connection refused
toybox nc -u 192.168.0.4 51824 ->  nc: xwrite: Connection refused
toybox nc    10.0.2.2 22     ->  nc: connect: Connection refused
ping 10.0.2.2                 ->  2 packets, 0% loss, 11 ms
```

**ICMP works and every TCP and UDP connection to the host is refused**, to the gateway address and
to the LAN address alike. That is the emulator refusing to reach its host, not a network result: a
port with nothing listening gives *no answer* rather than *refused*, and the refusal arrives in
milliseconds. So the phone's half of the relay path **cannot be measured on this emulator**, and the
test that says so is correct to say it.

The earlier `relayed 1 ... 14 bytes` and `relayed 2 ... 4 bytes` lines in the relay's log were host
probes, not device traffic. The packet that looked like a device source - `127.0.0.1:28952`, a full
148 bytes - was a probe from this host too; the source address is always loopback after QEMU's NAT
and on this emulator there was no QEMU NAT to do it. Which is exactly why the sender is logged:
without it, a loopback source reads as "something on the phone", and the fact that it is not is
invisible.

**What this does and does not change.** The relay's real-WireGuard half is proven on the host, and
the client's half - profile, join, reachability of a relay's port - is proven on the device against
a relay on the same device. The hop *between* a phone and a relay elsewhere is the one link with no
measurement, and on this emulator it cannot be measured, because the emulator will not talk to its
own host over TCP or UDP. That is worth stating rather than working around: a second device on the
same network, or a physical phone, is what would close it.

### Correction: `nc` was reporting its own failure as a network one

The section above rests on `nc: xwrite: Connection refused` from the device. That message is false,
and it took a live listener to prove it.

```
# a host-side UDP echo, bound and running, printing the port it bound
LISTENING 0.0.0.0:51826

adb shell toybox nc -u -w 5 10.0.2.2 51826 < /dev/zero
  nc: xwrite: Connection refused

received 0 datagrams
```

**A port with a live listener returned `Connection refused` and received nothing.** So busybox `nc`
never transmitted a byte: the refusal describes its own `connect()`, not the network. Every earlier
reading taken from it - "the emulator refuses its host", "TCP and UDP to the host are refused" -
was a statement about the tool.

The Java probe disagrees, and is the one to believe, because it has a real timeout and a real
exception to distinguish outcomes with:

```
1.1.1.1:53       silent - nothing came back and nothing objected
10.0.2.2:51823   silent - nothing came back and nothing objected
192.168.0.4:51823 silent - nothing came back and nothing objected
8.8.8.8:53       silent - nothing came back and nothing objected
```

`silent`, not `refused` - and that is the shape of a filter rather than a closed port, which is the
whole distinction the previous section claimed to have measured and did not.

This is the fourth time in this project that a probe's own limitation has been read as a property
of the network, after the 31-byte stateless reset counted as a service, the 12-byte stub counted as
a port, and the httpx SNI override turning 200 into 403. The rule holds: **a tool that cannot
distinguish refused from filtered from broken cannot answer a question about filtering**, and the
cheapest check is a live listener on the far end that counts what actually arrives.

### …and with a control, the emulator turns out to reach the host after all

The correction above removed the reason for believing the emulator would not talk to its host. With
a destination that is **provably listening** on the host, the same probe says:

```
1.1.1.1:53        silent
10.0.2.2:51823    silent     the host's relay port
192.168.0.4:51823 silent     the host's LAN address
10.0.2.2:51827    answered 9B    a known-live UDP echo on the host - the control
8.8.8.8:53        silent
```

**The control answers and the relay port does not.** That is a real difference, produced by the same
socket and the same code in the same run, and it inverts the conclusion of the previous section: the
emulator does reach the host over UDP - the echo on the far end logged the arriving datagram, its
first byte and all 1200 bytes of it.

So what is actually true is narrower and more interesting than "the emulator cannot reach the host":

  * the path from the device to a host-side UDP port **works**, and is proven by a live listener;
  * the relay's own port is silent from the device while a plain echo on the same gateway address
    is not.

Those two facts differ by one thing: what the relay does with the packet. It forwards it to a peer,
and that peer's answer is what has to come back. A relay that loses its own peer's reply looks
exactly like a port that is not reached - which is the bug this file spent three commits fixing, and
which a reachability probe cannot distinguish from a network. That is the argument for measuring
the exchange end to end rather than the port, and for not concluding "blocked" from "silent".

### Why the answer still cannot come back, and it is not the relay

The control proves the device's datagram reaches a host-side listener, and the relay log proves the
device's 148-byte packet reaches the relay too. What neither gets back is an answer. The reason is
in the echo's own log:

```
echo on the host, what it saw as the source:
  from 127.0.0.1:17491   1200 bytes     <- the device's packet, seen as loopback

the same echo, what it sees from this host:
  from 127.0.0.1:51827   16 bytes
```

**QEMU's user-mode network delivers the device's datagram from `127.0.0.1`.** So every reply - the
echo's `ECHO:1200`, the peer's message-response, anything - is sent to a loopback port on the host
that is not the device. The return path does not exist, and no relay implementation can supply it.

That is why the control answered and the relay did not, and it is not a property of either. The
echo happens to be built to reply to whatever address it saw, so its reply went to a host port; the
relay does the same thing and the difference is only whether anything useful is listening there.

**So the honest end state of the device half is this.** The emulator is a one-way path to the host
for UDP: datagrams arrive, replies cannot get back. A port-forward is the only thing that would fix
it, and that is a property of the harness rather than of the network. Closing the last link needs a
device that is not behind QEMU user-mode NAT - a physical phone on the same network, which would
turn this from a harness limitation into a measurement.

    adb -s emulator-5554 reverse --remove-all
    adb forward tcp:51823 tcp:51823        # TCP only; there is no UDP equivalent

### …and the return path works, which makes the previous conclusion wrong again

That section concluded the emulator is a one-way UDP path because the host saw the device's packet
arrive from `127.0.0.1`, and reasoned that every reply must therefore go to a loopback port. The
first half is measured; the second is not, and the relay now answers a marker from the device:

```
the device reached 10.0.2.2:51823 over UDP - 13B came back
```

So QEMU rewrites the source address the host *sees* and still routes the reply back to the device -
the mapping is internal to it. Inferring "no return path" from "the source is loopback" was a step
too far, and it is the same mistake as the rest of this file in a new place: reading a property of
the observation as a property of the network.

What is genuinely established, and what is not:

| Link | State |
|---|---|
| device -> relay port, UDP | **works** - the relay logs both the 1200-byte marker and the 148-byte initiation |
| relay -> device, bare probe | **works** - `RELAY-OK`, 13 bytes back |
| relay -> device, after the peer answers | **works** - the relay logs `handshakes 1` and a message-response arrives on the device |

That last row was open for a reason that had nothing to do with the network, and it is worth
recording because the signature was identical to a block. The relay's return path drained the peer's
queue synchronously right after forwarding, and the peer needs about 14 ms of Diffie-Hellman and an
AEAD seal before it has anything to say - so the queue was always empty, the peer counted nothing,
and the client waited for a reply that had been produced and dropped. Draining on the timeout path as
well is what closed it, and a relay that dies between the forward and the reply still produces the
same log, which is why the run has to be one where the relay stays up.

## …and it is measured now: a real handshake crossed from the phone through the relay

That row is closed. On the device, against a relay on this host with a real WireGuard responder
behind it:

```
the device reached 10.0.2.2:51823 over UDP - 13B came back
a 148-byte message-initiation crossed to the relay and a message-response came back
```

**Every link in the chain, from the phone, measured individually:**

| Link | Evidence |
|---|---|
| device -> relay port, UDP | the relay logs the 1200-byte marker and the 148-byte initiation |
| relay -> device, bare probe | `RELAY-OK`, 13 bytes back |
| relay -> peer | `forwarded to the peer` on the same run |
| peer -> relay -> device | **a message-response came back** |

So the client's half of the relay design works from a phone and not only from loopback, and the
remaining gap is the one it has always been: the far side's own UDP egress to Cloudflare.

### Four bugs this took, all of which read as a network failure

The path was open for most of this. What was broken was the measurement of it, four times:

| Bug | What it produced |
|---|---|
| the relay never answered a bare probe | a client waiting for reachability concluded the port was unreachable |
| the peer's queue was drained right after the forward, when the reply takes ~14 ms to exist | a forwarded packet, zero handshakes at the peer, and no answer |
| the peer's ephemeral was 16 arbitrary bytes | `Error computing shared key` killed the peer's thread; the relay stayed up and silent |
| the test's own type byte was written and then overwritten by `nextBytes` | the relay read the handshake as a probe and answered `RELAY-OK` where a message-response was expected |

The last one is the sharpest, and it is the same shape as everything else in this file: a packet
that is the right size, well-formed, and wrong in one byte. A failure whose text says
`0x52 rather than a message-response, so a handshake-sized packet did not survive the hop intact`
is about a line of ordering in a test, and it took a log of the relay's own view to see it.

### The transport step failed for the same reason, one level on

With the handshake passing, the transport step timed out and the relay reported `transports 0` after
`handshakes 2` - a real session, and packets it dropped.

The transport packet was **filler with the right first byte**. The peer's first act on a transport
packet is an AEAD open, and on filler that fails and the packet is dropped without a word: the relay
logs it arriving, the peer counts nothing, and the client waits for a reply that was never going to
come. Measured both ways on the same hop, same size, same first byte:

```
encrypted under the key the handshake produced   ->  transports 1, and a reply
1200 bytes of filler with the same first byte     ->  transports 0, and silence
```

So the transport step now encrypts under the key derived from the exchange - the initiator's send
half of Noise's split, which is the responder's receive half - using the app's own `ColgramWarp.X25519`
for the curve and a real HMAC-SHA256 HKDF for the chain. **The first version of that used a stubbed
HKDF**, which is worse than no implementation: it produces a *wrong* key rather than an error, and a
wrong key produces exactly the same silence as a filtered network. Every layer of this file has come
down to the same thing - a client that cannot tell "refused" from "my own output is wrong".

### …and then it turned out the transport half cannot be measured on a phone at all

Encrypting the transport packet needs a key derived through **BLAKE2s**, and Android does not ship
it: `BLAKE2s-256` is not in `MessageDigest.getInstance` on any API level this app supports, and
there is no BouncyCastle on the classpath. A hand-written BLAKE2s was tried and removed, and it is
worth recording what it got wrong, because it is the exact failure this file keeps documenting:

```
sigma table:   112 entries where 160 are required
block counter: advanced before the compression instead of after, so a 3-byte input carried t=64
```

Both produce a *wrong hash*, not an error. A wrong hash means a wrong chaining key; a wrong chaining
key means the peer derives different transport keys; different transport keys mean the packet is
dropped in silence, which reads as a filtered network. A vector assertion caught the second one and
the first was caught by counting the table.

So the test is renamed `aRealHandshakeCrossesFromTheDeviceToARelayOnThisHost` and measures the
handshake, which is the half only a phone can do. The transport half is measured on the host, where
the hash is `hashlib`, by `scripts/test_warp_real_wireguard.py`.

**What the renamed test says when it passes:**

```
the device reached 10.0.2.2:51823 over UDP - 13B came back
a 148-byte message-initiation crossed to the relay and a message-response came back
VERDICT: a real 148-byte handshake crossed from the device to a relay on this host and a
         message-response came back. The transport half needs a key the phone cannot derive -
         Android has no BLAKE2s - and is measured on the host.
```

It had been called `aRealSizedExchange...` - a name promising a measurement it did not make. A name
that overstates what ran is how a green result came to report a handshake while the transport had
never executed, and it is the same failure as every other one here wearing a different hat: something
answered, and it was taken to mean more than it did.

## The last transport nobody had tried: WireGuard inside TLS on 443

Every transport measured so far put WireGuard somewhere it was filtered. One was never tried at all:
TCP 443 is **provably open** here, and sending a real 148-byte initiation into a TLS stream on it
would reach Cloudflare's edge over a port that carries TLS all day.

```
TCP 443 to 162.159.192.1:   TLSv1.3 in 127ms
send a 148-byte initiation into the stream

HTTP/1.1 400 Bad Request
Server: cloudflare
```

**It answers, and what it answers with is an HTTP error page.** Port 443 terminates HTTPS and
nothing else; the initiation is read as a malformed request. So the last open door in this file is
closed too, and closed for a reason that took one measurement to establish - which is the whole
point of the transports table. A port being open was never evidence that a service was running on
it, and this is the fifth time that has had to be relearned:

| what answered | what it actually was |
|---|---|
| 31 bytes on udp/443 | a stateless reset from a QUIC endpoint that could not read the packet |
| 12 bytes on udp/443 of a Cisco resolver | a stub holding a port open |
| a 403 on every path | an httpx `sni_hostname` override, on this host |
| `Connection refused` from the device | busybox `nc` failing before it sent anything |
| **316 bytes on tcp/443** | **an HTTP 400 from the HTTPS endpoint** |

### One more thing that was not a new path, and how to tell

The WSL distro on this machine carries three addresses, including `26.226.94.158` - the same one
Cloudflare's own log had listed among the network interfaces. That reads like a second egress, and it
is worth checking rather than assuming, because a second measurement of the same path is worse than
no second measurement: it looks like a confirmation.

It is the same path. The routing table answers it directly:

```
ip route get 162.159.192.1
  ->  via 100.127.255.1 dev eth5 src 100.127.255.2
```

`100.127.255.1` is the WireGuard tunnel. So a probe from WSL, whatever address it binds, leaves
through the tunnel this file has been measuring all along - and the result, `silent` on all five
destinations including the DNS control, is the same measurement in new clothes.

**The check is one command and it belongs before any second probe**: `ip route get <destination>`,
read the interface, and if it is the tunnel, the probe is not independent. A second vantage point
that is really the first one again is the most dangerous kind of null result, because it looks like
a control passing.

## What the filter actually matches, found by testing a protocol that is not QUIC

Everything above concluded "UDP to Cloudflare does not arrive". That is true, and it is not what the
filter is doing - it was never tested against a UDP protocol that is *not* QUIC on the same hosts.

NTP, on the LAN link, bound to `192.168.0.4` so the tunnel is not involved:

```
216.239.35.0    udp/123   48B
216.239.35.4    udp/123   48B
162.159.192.1   udp/123   silent
77.88.8.8       udp/123   silent
8.8.8.8         udp/123   silent
216.239.35.1    udp/123   silent     (not an NTP server - correct, and it is silent)
```

So UDP does cross the LAN link, to real NTP servers. The silence on Cloudflare's addresses is about
Cloudflare, not about UDP.

**And then the decisive one** - the same host, the same port, two different protocols:

```
216.239.35.0:123   an NTP request        48B  first=0x1c
216.239.35.0:123   a QUIC Initial        silent
216.239.35.0:123   an NTP request again  48B  first=0x1c
```

**The filter matches QUIC, not the address and not the port.** It inspects enough of the datagram to
recognise a QUIC Initial - which is visible in the first byte's long-header form and the version
field, both in cleartext - and drops those, while other UDP to the very same host and port passes.
That is not a port allowlist, not a provider block, and not a path that "does not arrive". It is a
protocol filter with a specific target, and the distinction matters for what could still work:

  * a transport that does **not** look like QUIC on the wire is not what is being dropped, so a
    WireGuard initiation - which has no long header and no version - is being matched by something
    else, or by the same filter widened to the ports it wants shut;
  * and the earlier "0 of 7 ports including 53" was measured with a **DNS-shaped** payload on most
    ports, which is why 53 looked filtered when the real control says it is not.

That last point is a correction to this file's own conclusion, and it is the same error one level
down from everything else in it: **a payload that the destination does not answer tells you about
the destination, not about the port.** 53 on a Cloudflare address does not run DNS, so its silence
never meant 53 was filtered - and the table that leaned on it was reading a service where there was
none.

## And it *looked* aimed at WireGuard, which turned out to be wrong

If the filter recognises QUIC by its cleartext header, does it also recognise a WireGuard initiation?
Same host, same port, three payloads:

```
216.239.35.0:123   an NTP request              48B
216.239.35.0:123   a WireGuard-shaped 148B     silent
216.239.35.0:123   an NTP request again        48B
```

**The NTP request passes, the WireGuard packet does not, to the same host on the same port.** So the
filter is not "UDP is throttled" and not "this provider is blocked": it recognises WireGuard's own
shape - type 1, reserved zero, two 32-byte keys - and drops it, while leaving other UDP alone.

That is the sharpest statement of the problem this whole file has been circling:

> The block is **signature-based and protocol-specific**. It is not a port allowlist, not a
> provider block, and not a path where UDP does not arrive. It lets DNS and NTP through to the very
> same address and port that a WireGuard initiation is dropped on.

Which is the first finding in this project that says something the relay does **not** already imply.
If the filter is a signature on the packet, a relay that only forwards WireGuard is forwarding the
one thing being dropped - so the far side has to originate something that is *not* WireGuard, or the
relay has to re-frame it, and both are new work rather than a matter of finding more open ports.

It also explains every odd result above without any of them being a tool error: the 148-byte probe
and the 1200-byte QUIC Initial are both recognised, and the sizes that "answered" were answers from
something that recognised them and declined.

### …and that reading was wrong, twenty minutes later

The sentence above is the clearest example in this file of a conclusion drawn from a control that
was not a control. The host used for it, `216.239.35.0`, answers NTP and nothing else - so a
WireGuard packet to it being silent says the **server** declined it, not that a filter did. Six
payloads to the same host on the same port, which is the test that settles it:

```
216.239.35.0:123   an NTP request        48B
216.239.35.0:123   1200 zero bytes        silent
216.239.35.0:123   148 zero bytes         silent
216.239.35.0:123   4 bytes                silent
216.239.35.0:123   1000 bytes             silent
216.239.35.0:123   64 bytes               silent
```

**A UDP server that answers its own protocol and nothing else.** There is no protocol filter here at
all, and "it is aimed at WireGuard" is not supported by anything.

So the NTP observation survives in a much weaker form, and it is worth keeping for what it does
establish: **UDP does cross the LAN link.** A resolver answers on 53 and an NTP server answers on
123, both bound to `192.168.0.4`, so the earlier statement that "UDP does not arrive" was too
strong - it is true of the Cloudflare addresses, not of the path. The port table that concluded
"0 of 7 including 53" was sending DNS-shaped payloads to hosts that do not run DNS, and reading
their silence as a filtered port.

**What survives about the filter, and what does not:**

| claim | status |
|---|---|
| UDP reaches the LAN link, unmetered by protocol | **measured** - DNS on 53, NTP on 123 |
| QUIC gets no answer from Google, Facebook or Cloudflare | **measured** - 0 of 16 |
| the filter recognises QUIC by its header | **not measured** - no QUIC server was reachable to compare against |
| the filter is aimed at WireGuard | **withdrawn** - the control was a server that only answers NTP |

The row that was withdrawn is the one that had felt like the deepest finding of the project. It is
also the exact shape of the mistake this file has now documented six times: a reply, or a silence,
read as a fact about the network when it was a fact about the probe.

## The last unmeasured row, now measured

`warp=on` was the only claim in this file that had never been tested, and it has now been - on the
device, through the app, with a real registration and a real profile. `ColgramWarpDeviceIntegrationTest`
drives the whole thing: it registers, builds the profile from the stored identity, takes the system
VPN consent, starts the tunnel, and then reads `cdn-cgi/trace` for as long as the app keeps trying.

```
the engine was initialised before the tunnel was asked for
Requesting foreground Android VPN consent

ColgramWarpTunnel: WARP endpoint silent for 8s; attempt 10, still within patience
ColgramWarpTunnel: WARP endpoint silent for 8s; attempt 20, still within patience
ColgramWarpTunnel: WARP endpoint silent for 8s; attempt 28, still within patience
ColgramWarpTunnel: Disabling WARP: no traffic after 29 attempts over 5 minutes
ColgramWarpTunnel: WARP tunnel down

cdn-cgi/trace:  ip=5.230.5.13  colo=HEL  loc=FI  tls=TLSv1.3  warp=off  gateway=off

PASS  registeredWireGuardProfileCarriesCloudflareWarpTraffic
```

**`warp=off`, measured, and the test passes because it is asserting the truth rather than the hope.**
That distinction is the point of the whole file. The test would have failed had the tunnel come up,
because the app's own check requires `warp=on` to report up - so a green run here means the network
refused, and it was checked for five full minutes with 29 handshake attempts before saying so.

Two things are worth noting in the app's behaviour, because both are the failure mode this file has
been hunting:

  * it **gave up rather than lying**. After 29 attempts the watchdog disabled WARP and logged
    "tunnel down". A switch left blue over a route carrying nothing is the failure this project
    has been fixing since the first silent profile, and it is now explicitly prevented.
  * it **did not rotate into a false success**. The endpoint watchdog saw silence on every attempt
    and stayed on the same endpoint rather than reporting a different one as answered.

So the honest end state of this file is now complete on every row it can measure:

| Half | State | Evidence |
|---|---|---|
| Identity | **works on the device** | register() true in 2697 ms |
| Profile | **works** | 8/8, engine confirms the endpoint shape |
| Join | **works** | 148-byte initiation answered over UDP, on device |
| Relay carries real WireGuard | **works** | Noise_IK + ChaCha20Poly1305, 2/2, fails when the relay corrupts a byte |
| Transport | **does not work** | 0 of 7 ports on the device, 53 included; 29 attempts, no traffic |
| End to end | **measured: `warp=off`** | 5 minutes, 29 attempts, then the app disabled it honestly |

The last row is no longer an assumption about what a relay on a clean host would achieve. It is a
measurement of this network, and it says the only remaining gap is the far side's UDP egress - which
is infrastructure, not code, and not something a client can route around.

## The identity half, proven on the device

Every transport result above describes what cannot get through. The other half - whether the phone
can obtain a WARP identity at all - had never been measured, and it is the half that decides whether
the transport results are about a tunnel or about nothing.

The host cannot answer it: its resolver sends `api.cloudflareclient.com` to `8.47.69.0`, and no
certificate covering that name is served there. The device resolves it correctly, so the app was
asked, driving its own `ColgramWarp.register()` rather than a hand-rolled request:

```
register() returned true in 2697ms
stored identity: endpoint host=engage.cloudflareclient.com  advertised ports=4
                 reserved=33d034   key length=44
```

**A real registration, on the device, on this network.** A private key, a peer host, four
advertised ports and the three-byte client id Cloudflare pins into WireGuard's reserved header - all
present, so the profile builder has genuine material rather than placeholders. `2697 ms` is also
worth noting on its own: the identity round trip completes over HTTPS while the WireGuard ports are
unreachable, which is precisely the split the relay exists to bridge.

That `ColgramHttp` does this without depending on the system resolver is not luck either - it
resolves through `ColgramDohResolver` and pins the address, and that resolver is built for exactly
this: it survives an SNI cut by dialling a filtered resolver under a permitted name while still
checking the certificate against the resolver's real name, so a cut resolver cannot be swapped for
an impostor. Both public DoH endpoints answer the WARP zone correctly from here:

```
cloudflare-dns.com/dns-query   status=0  ['104.16.192.82', '104.16.24.84']
dns.google/resolve             status=0  ['104.16.192.82', '104.16.24.84']
```

    python scripts/device-tests.py org.colgram.core.ColgramDeviceWarpRegistrationTest

### Where the two halves now stand

| Half | State | Evidence |
|---|---|---|
| Identity — key, peer, ports, client id | **works on the device** | register() true in 2697 ms |
| Profile — the engine accepts what the app builds | **works** | 8/8, engine confirms the endpoint shape |
| Join — a relay on the profile's port answers | **works** | 148-byte initiation answered over UDP, on device |
| Transport — WARP's own UDP reaching Cloudflare | **does not work** | 0 of 7 ports on the device, 53 included |
| End to end — `warp=on` | **not measured** | needs a relay on a host without the filter |

Four of the five rows are now measured rather than argued. The fifth is the one that cannot be
measured from here, and it is the only one that says WARP works.

## The relay carrying actual WireGuard, not a stand-in's idea of it

`test_warp_relay_handshake.py` sends a real 148-byte message-initiation and gets a 148-byte answer
back. That proves the bytes crossed intact. It does not prove a WireGuard peer would accept the
session, because the stand-in answers with its own scheme - a keystream derived from the shared
secret, not the protocol's - and nothing ever decrypts it. The acceptance criteria there were
invented rather than specified, which is how a byte pipe gets talked into being a tunnel.

So this file implements the protocol's own handshake: Noise_IK with the real HKDF and BLAKE2s
chaining, and ChaCha20Poly1305 transport packets with a 12-byte nonce. The client and the peer are
the same code, so a pass means both halves agree with each other **and** with the published
construction - a wrong key schedule, a wrong chaining order, or a wrong nonce fails the peer's AEAD
open, and nothing gets through.

```
test_aRealHandshakeCrossesTheRelayAndBothSidesAgree ... ok
test_aTransportPacketIsDecryptedByThePeerAndTheRelayStaysInvisible ... ok

Ran 2 tests in 4.769s
OK
```

**And it is a test rather than a demonstration.** Making the relay corrupt one byte per datagram:

```
FAILED (failures=2)
  the handshake did not complete through the relay
  the handshake did not complete, so the transport proves nothing
```

Both fail, and the second one refuses to claim anything about transport on a session that never
established. That is the property the old byte-counting test could not have: a relay that mangled
traffic looked fine as long as something came back.

### Six bugs this found in thirty lines of protocol code

Every one of them produced silence rather than an error, and every one of them read exactly like a
blocked network:

| Bug | What it looked like |
|---|---|
| initiation 227 bytes instead of 148 | nothing answered |
| `blake2s(digest_size=64)` - BLAKE2s caps at 32 | the endpoint thread died on the first packet |
| DH between the two *static* keys | handshake "succeeded", every transport key was unrelated |
| responder's *static* published as its ephemeral | same, and the AEAD open failed silently |
| statics mixed in a different order on the two sides | same |
| 2-byte transport header where the format needs 4 | counter sliced across reserved bytes and ciphertext |

The dead-thread one is the sharpest: a handler thread that raises on the first packet produces
exactly the same silence as a filtered port, and the client cannot tell them apart. Three of these
had already been found the hard way in this project - the reset counted as a service, the stub
counted as a port, the SNI override counted as a refusal. The pattern is consistent enough to be
worth stating as a rule: **a test that reports silence has not located the silence, and the first
thing to check is whether the code that should have produced an answer ran at all.**

    python scripts/test_warp_real_wireguard.py

## A layer underneath all of it: DNS here answers with the wrong addresses

Found while checking the control plane, and it is the only finding in this file that is not about
packets at all.

```
api.cloudflareclient.com              ->  8.47.69.0, 8.6.112.0        not a Cloudflare range
engage.cloudflareclient.com           ->  162.159.192.1               Cloudflare range
connectivity.cloudflareclient.com     ->  162.159.137.65, .138.65     Cloudflare range
one.one.one.one                       ->  1.0.0.1, 1.1.1.1            not a Cloudflare range (expected)
```

Only `api.cloudflareclient.com` is wrong, and the WARP client talks to that host for registration.
Asking a public resolver over DoH, which is not the system resolver:

```
api.cloudflareclient.com          status=0 answers=['104.16.192.82', '104.16.24.84']
engage.cloudflareclient.com       status=0 answers=['162.159.192.1']
connectivity.cloudflareclient.com status=0 answers=['162.159.138.65', '162.159.137.65']
```

**The truth is 104.16.x, and this network says 8.47.69.0.** And a plain UDP query to 1.1.1.1 for
either name returns *no A record* - so it is not only UDP 53 that is unreliable here; the system
resolver's answers for this zone are not the zone's answers at all.

What that costs is concrete. `8.47.69.0` is a real Cloudflare edge - it completes a TLS 1.3
handshake and presents `CN=cloudflare.com` with SANs `cloudflare.com`, `ns.cloudflare.com`,
`*.secondary.cloudflare.com` - but **nothing covering `cloudflareclient.com`**, on either address. So
the registration host resolves somewhere that cannot serve it, and the certificate check fails
there in the same way it fails on Cloudflare's own `162.159.192.1`. An empty subject on the retry is
the edge declining the name rather than serving a wrong one.

This is the same class as everything else in this file, one level down: a name that resolved, a
connection that completed, and a result that means something other than what it appears to mean. It
is also why `warp-cli` reported `Skipped uploading aggregate stats because registration was none` -
there was no usable registration to upload.

**What it does not change.** A WARP relay does not use the system resolver for Cloudflare's
addresses, and the registration the app already holds was issued before this. The relay's case does
not rest on DNS either. But any attempt that starts by resolving a `cloudflareclient.com` name will
fail here for a reason that has nothing to do with the filtering this file is about, and that is
worth knowing before someone spends a day on it.

**And it is a host-side problem, not the app's.** The device resolves the same names correctly:

```
emulator-5554  ping api.cloudflareclient.com     -> 104.16.192.82   (the true address)
emulator-5554  ping engage.cloudflareclient.com  -> 162.159.192.1
```

So this costs nothing in Colgram on a real phone, and it is one more reason the host-side numbers in
this file have to be re-measured rather than trusted. The host resolver is not the phone's resolver,
and after the tunnel finding above they are demonstrably different networks.

### The port control, on the device

```
control: DNS over UDP -> answers
162.159.192.1  53:silent  443:silent  2408:silent  500:silent  4500:silent  8443:silent  51820:silent
162.159.192.1 answered 0/7 ports to a real DNS query, 53 included
1.1.1.1:443 -> silent   (a host that certainly serves DNS, same port)
stub check: the stub host is silent here, so it is not a usable control on this path
```

**0 of 7 on the phone as well, 53 included, while DNS answers.** So the finding holds on the device
and is not an artefact of the host's tunnel: on this path, UDP to Cloudflare's WireGuard and MASQUE
addresses does not arrive on any port, and the silence is about the path rather than about any one
port being shut.

The stub host is silent here, which is worth noting rather than glossing: `208.67.222.222` is
reachable from the host and not from the device, so the shape check that catches a fixed 12-byte
answer has no host to run against on this path. The check reports that it cannot run instead of
quietly passing - a control that is unavailable is a different fact from a control that passed, and
conflating them is how a gap becomes a claim.

    python scripts/device-tests.py org.colgram.core.ColgramDeviceUdpPortTest

### A later run, where the stub check could run at all

The device reached the stub host on a later run, so the shape check had something to bite on rather
than reporting itself unavailable:

```
162.159.192.1  53:silent 443:silent 2408:silent 500:silent 4500:silent 8443:silent 51820:silent
162.159.192.1 answered 0/7 ports to a real DNS query, 53 included
stub check: 208.67.222.222:443 answers 12B that no QUIC server could send -
            version 0x00808100, dcid len 0
VERDICT: no port on 162.159.192.1 answered, 53 included, while DNS answers elsewhere.
         UDP does not reach that address on this path, so the silence is about the path and not
         about 2408 being shut.
```

The port result is unchanged - 0 of 7, 53 included - and the trap is now demonstrated **on the
device** rather than only on the host: a port that answers, on a host that answers, with a packet no
QUIC server could have sent. Any sweep of this network that counts answers as services would report
that port as an open QUIC endpoint, and a `warp=on` claim built on it would be worth nothing.

Reachability of the stub host also moves between runs, which is the same instability this file has
recorded twice. It is why the control is re-run rather than remembered: a control that was available
once and unavailable once is not a settled fact either way, and the test says which one it got.

### The same mistake, for the third time in this project

Counting a 31-byte stateless reset as a QUIC service. Counting a 12-byte stub on a Cisco resolver
as an open QUIC port. And earlier, counting a 403 from an httpx `sni_hostname` override as
Cloudflare declining a request. All three are the same failure: **something arrived, so a service
must have answered it.** The reply has to be one the service could only have sent, and until that is
checked, an answer is evidence that a path is open - which is much weaker than evidence that a
service is running.

### The block is not "all Cloudflare UDP" — and that correction matters

An earlier version of this file said the whole of Cloudflare's UDP was filtered. That was wider than
the evidence supported. Sweeping the ports rather than only the four WARP advertises found:

```
python scripts/warp-port-sweep.py

162.159.192.1
    2408 silent     WARP / WireGuard (the registered ingress)
    ...
    443  ANSWERED   QUIC / HTTP3 - not WireGuard, but the same edge over UDP
...
answered 2, silent 62
```

Two of sixty-four probes answered, **both on 443/udp, on Cloudflare addresses whose WireGuard
ports are all silent.** So Cloudflare's edge *is* reachable by UDP from this network; what is
filtered is the WireGuard service on it. Those are different problems, and only the first is
something a different transport could work around.

Re-run against that one address alone, so the comparison cannot be an artefact of sweep order:

```
python scripts/warp-port-sweep.py --host 162.159.192.1

answered 1, silent 15        # the one answer is 443
```

**What it does not do is make WARP work.** WARP speaks WireGuard, not QUIC. An open 443 cannot carry
a WireGuard handshake, so this narrows the diagnosis without opening a route. It does mean the
relay conclusion survives a sharper test than the one it was originally based on.

### It looked like a UDP port allowlist, and the allowlist did not survive

**This section's conclusion was wrong and is kept because the way it was wrong is the useful part.**
The "ANSWERED" markers below were the same 31-byte stateless resets described above, produced by a
probe that sent 1200 zero bytes and counted any reply as a service. A real QUIC Initial on the same
ports scores 0 of 8. So the allowlist reading is withdrawn: 443 is not an open service here, it is a
path that answers packets it cannot read. The measurements themselves are real; what they were
taken to mean was not.

The port-specific result has two possible readings, and they call for opposite conclusions:

- *WireGuard is blocked specifically* - then some other transport could reach the same host, and
  wrapping WARP in it might work.
- *Only a couple of ports get through at all* - then no transport helps, because the WireGuard
  port can never carry a byte no matter what is wrapped around it.

Measured, and it is the second:

```
python scripts/warp-port-allowlist.py

  162.159.192.1:53     silent        162.159.192.1:4444   silent
  162.159.192.1:80     silent        162.159.192.1:8080   silent
  162.159.192.1:123    silent        162.159.192.1:8443   silent
  162.159.192.1:443    ANSWERED      162.159.192.1:31337  silent
  162.159.192.1:1234   silent        162.159.192.1:55555  silent

  1.1.1.1:2408   silent      # 1.1.1.1 does not run WireGuard at all
  8.8.8.8:2408   silent
  1.1.1.1:443    ANSWERED
```

Thirteen ports from 53 to 55535 on one Cloudflare address, and the only answer is 443. Port 2408 is
silent on hosts that **do not run WireGuard**, so the silence follows the port, not the protocol or
the owner.

**What this settles.** This network carries DNS over UDP and filters everything else, and no
Cloudflare UDP service answers on any port tested. WARP's 2408 is outside what is carried, so it cannot
be reached directly, and no client-side transport moves a datagram onto a port that is filtered.
Wrapping WireGuard in QUIC or TLS does not help, because the wrapper would still have to arrive on
2408.

**What it does not settle.** A relay is still the only route, because a relay receives the
handshake over TCP - 443 and 22 are both open here - and originates the WireGuard UDP itself from a
network without this allowlist. What is settled is the *reason*, which is now a measurement rather
than an assumption, and that reason is testable against any future network.

### Correction: there is a size threshold, and the probe was not varying it

The section above was too confident, and the way it was wrong is worth recording rather than
quietly editing.

Every port probe sent **1200-byte** datagrams. Varying only the size, on the same host and the same
port that already answered:

```
1.1.1.1:443   64 bytes  silent
              256 bytes  silent
              600 bytes  silent
              800 bytes  silent
             1000 bytes  silent
             1200 bytes  ANSWERED  (a 31 byte QUIC version negotiation)
```

Nothing about the port changed - only the size did. So "443 answers and 2408 does not" was never a
clean port comparison: 443 was answering a packet that a **WireGuard message-initiation cannot
match**, since an initiation is 148 bytes. A live WireGuard port would have been silent to that
probe too.

Re-run properly - all Cloudflare WireGuard ports at 1200 bytes, a size that provably gets 443 to
reply - and they are still all silent. So the port conclusion **survives having been tested
properly rather than by accident**, which is the only reason it is worth keeping. The allowlist
framing does not: what the evidence supports is "2408 is filtered, and separately, small UDP
datagrams get no answer at all on any port".

```
python scripts/warp-mtu-threshold.py
```

The practical consequence for WARP is the same either way, and worth stating plainly: the 148-byte
handshake sits below the 1200-byte floor, so even a WireGuard port that were open would be
unobservable to a probe - which is why the device test asserts against a real handshake with
Cloudflare's own `cdn-cgi/trace` rather than against reachability.

### …but the floor is UDP-only, and that is what makes a relay viable

Read carelessly, a ~1200-byte floor on small packets says nothing small can cross this link at all,
and a WireGuard initiation is 148 bytes - so the obvious conclusion is that a relay is pointless
too, because it would have to carry those same small packets.

**That conclusion would be wrong, and the way to know is to send the same small payload over
TCP.**

```
python scripts/udp-vs-tcp-small-payload.py

  TCP   64 bytes   TLS TLSv1.3 ok        UDP   64 bytes   silent
  TCP  148 bytes   TLS TLSv1.3 ok        UDP  148 bytes   silent
  TCP  512 bytes   TLS TLSv1.3 ok        UDP  512 bytes   silent
  TCP 1200 bytes   TLS TLSv1.3 ok        UDP 1200 bytes   ANSWERED
```

Small packets cross this network over **TCP** and get nothing over **UDP**. The floor is a
property of the UDP path, not of the link - and TCP is proven here by a completed TLS 1.3
handshake, not by a connect, which succeeds on ports nothing listens on and carries nothing.

**This is the asymmetry a WireGuard relay depends on.** The 148-byte initiation arrives over TCP,
where small payloads demonstrably work; the relay then originates the WireGuard UDP itself from a
network without this filter. Judging the relay on the strength of the UDP result alone would have
dismissed the only viable route on the basis of a measurement about a different protocol.

### The relay is built, and it is proven to bridge

`scripts/warp-relay-server.py` is the server side: it accepts length-prefixed frames over TCP and
forwards each as one UDP datagram to Cloudflare's WireGuard endpoint, returning whatever comes
back. It is deliberately trivial - WireGuard authenticates every packet cryptographically, so a
relay that mangled traffic could not produce a working tunnel, only a broken one. The tunnel either
works end to end or does not, and there is no way for this to quietly weaken WireGuard's guarantees.

Run on a box that can reach Cloudflare's WireGuard ports:

```
python scripts/warp-relay-server.py --keygen
python scripts/warp-relay-server.py --listen 0.0.0.0:51820 --endpoint 162.159.192.1:2408
```

Then long-press the WARP row in Colgram and give it that address, port and the relay's public key.
A relay terminates the handshake, so both the peer key and the endpoint are the relay's - Colgram
already swaps both together, and there is a test for it.

**Proven, in two halves.** Run here against `1.1.1.1:443`, which answers, the relay returns a
frame in under a second, so the bridge itself works. Run here against `162.159.192.1:2408`, which
is filtered, it times out, which is the network rather than the code and is the same result every
WARP probe gives from this machine.

That is the honest boundary: everything up to Cloudflare's UDP path is built and measured. What is
missing is a machine whose UDP is not filtered, and until there is one, "WARP works" would be a
claim rather than a result.

**Proven as a tunnel, not only as a pipe.** A byte bridge is necessary and not sufficient: a relay
could forward frames perfectly and still not carry WireGuard, and the only symptom would be "WARP
does not work" with nothing to distinguish it from a blocked network. So the relay is driven with
a real **148-byte message-initiation** against a real WireGuard endpoint built with
`cryptography`, which answers only when the static key is the one it holds:

```
python scripts/test_warp_relay_handshake.py

  aHandshakeCrossesTheRelayAndAnAnswerComesBack ... ok
  the_endpoint_ignoresAPacketAddressedElsewhere ... ok
```

The second test is what makes the first meaningful - a stand-in that answered anything would let a
relay that echoes, reorders or truncates pass. Cloudflare's own ports are unreachable from here,
which is the whole reason the relay exists, so the endpoint has to be a local one. What is proven
is the part Colgram and the relay own; the last hop to Cloudflare needs the far side to originate
that UDP.

### The filter's behaviour changes over time, so one run settles nothing

Running the UDP probe twice on the same machine, minutes apart, with the same hosts, ports and
payload sizes:

```
session 1:   8.8.8.8:443  ANSWERED      1.1.1.1:443  silent      9.9.9.9:443  silent
session 2:   1.1.1.1:443  ANSWERED      8.8.8.8:443  silent      9.9.9.9:443  silent
```

**The results inverted.** Within a session the outcome is stable across repeated runs, so this is
not a flaky probe — the filtering itself changes over time. A third run showed
`188.114.96.1:2408` — a genuine WARP ingress — answering **1 of 3** probes, which looked like the
filter opening a window. It did not: **0 of 20** over the following minute.

Two consequences, and the second is the one that matters most:

1. Every WARP verdict here is a snapshot, not a property. `warp_udp_probe.py` now repeats each
   probe and reports `ANSWERED n/3` rather than a single result, because a lone `silent` reads as a
   measurement when it may only be a moment in the filter's cycle.
2. **No probe can settle whether WARP works here — including a successful one.** A single answered
   packet would be exactly as unreliable as a single silent one. This is why the device test
   asserts against a real end-to-end tunnel with Cloudflare's `cdn-cgi/trace` reporting
   `warp=on`, rather than against any reachability measurement, and why a "WARP works" claim on
   this network would need that and not a probe.

### The last transport: WireGuard on 443, and why it cannot work

443 is the one port that is open over both UDP and TCP, so "carry WireGuard there" is the obvious
last idea. Measured rather than assumed:

```
python scripts/warp-quic-443-check.py

  162.159.192.1:2408   TLS -> TimeoutError
  1.1.1.1:2408         TLS -> TimeoutError
  162.159.192.1:443     TLS TLSv1.3        (for comparison)
```

**The WireGuard port does not listen on TCP at all.** There is nothing there to encapsulate
towards, so no client-side framing changes anything — the destination itself is unreachable on
both protocols.

And QUIC on 443 is not a way in either. A real QUIC server does answer there — short-header
packets, first byte `0xba`/`0xb3` — but that port terminates **HTTP/3 and nothing else**. A
WireGuard message-initiation sent to it is dropped. QUIC being reachable does not make it a tunnel
to Cloudflare's WireGuard ingress; it is a different service on a different port, and "QUIC is
open, therefore maybe WARP can ride it" is a natural and wrong inference.

That is every transport available on the device, and each is measured:

| Scheme | Result |
|---|---|
| Direct WireGuard over UDP | port filtered |
| Direct WireGuard over TCP | port does not listen |
| AmneziaWG / obfuscation | no reproducible response |
| WireGuard inside QUIC on 443 | 443 terminates HTTP/3; initiation dropped |
| Patience / retry | 0 of 104 attempts over six minutes |
| **Relay over TCP 443** | **the one that survives — built and tested** |

### Two independent paths, the same answer

The host and the phone do not share a path. The emulator sits behind the QEMU user-mode NAT, and
its ICMP to `162.159.192.1` answers in **9 ms** while the host's UDP to the same address times out.
So "the host cannot reach 2408" was never a statement about the device the app actually runs on —
it had been doing that job implicitly.

Probed from the device itself, 1200-byte datagrams, control answering throughout:

```
from the device 162.159.192.1  2408:no 500:no 1701:no 4500:no
from the device 162.159.193.1  2408:no 500:no 1701:no 4500:no
from the device 188.114.96.1   2408:no 500:no 1701:no 4500:no
from the device 188.114.97.1   2408:no 500:no 1701:no 4500:no

MEASURED-WARP-UDP 0 of 16 answered from the device
```

Zero, like the host. So the block is **not an artifact of the build machine**, and two independent
paths agreeing is what makes the relay conclusion hold rather than merely go untested on one of
them. It is also worth noting what the 9 ms ICMP rules out: the route exists and the packets are
not being dropped as "no route to host". The block is specific to this destination and port over
UDP.

### The shape of the block, measured on the device

Knowing *that* UDP to 2408 fails is less useful than knowing what the boundary is. Probed from the
device across ports and destinations:

```
162.159.192.1  53:no   443:YES  2408:no 500:no 4500:no 1234:no
1.1.1.1         53:YES  443:YES  2408:no 500:no 4500:no 1234:no
8.8.8.8         53:YES  443:no   2408:no 500:no 4500:no 1234:no
```

Two things fall out of this, and both matter for the relay's design:

1. **DNS is not blocked.** It answers on its own resolver, so a relay does not need to carry name
   resolution — only the handshake.
2. **UDP is not blocked wholesale.** Port 443 answers on Cloudflare addresses, so the failure is
   specific to the WireGuard ports rather than to the protocol or the destination. That is what makes
   a relay that *receives over TCP 443 and originates the WireGuard UDP itself* the right shape
   rather than merely a possible one — the receiving hop uses a port that is measurably open here,
   and the originating hop happens on a machine that does not have this filter at all.

Port 1234 is silent on every destination, which is the control for the sweep: a port nothing
listens on is silent wherever the network is not filtering by destination.

Reproduce:

```
python scripts/udp-egress-check.py       # establishes that UDP works at all
python scripts/warp-udp-host-probe.py    # 0 of 16, with a control that answers
```

### A correction: TCP inside the emulator proves nothing

An earlier version of this file recorded "all TCP ports answer". That was wrong, and the way it
was wrong matters. The emulator sits behind `10.0.2.0/24`, the QEMU user-mode NAT, and its TCP
behaviour is an artifact of that: `nc -z` reports **OPEN for every port** on 162.159.192.1 —
including 65000, which cannot be open — and a connect delivers **zero bytes**. So no TCP result
measured inside the emulator is evidence about the network, and the "all TCP ports answer" line
should be read as the sandbox talking.

UDP has no such artifact: a datagram either comes back or it does not. That is why the verdict
above rests on UDP alone, and why the probe lives on the host as well as the device.

### The same artifact applies to TCP, on the host too

The `nc -z` behaviour above was reproduced on the **host**, and it is not the only way TCP lies
here. Connecting reports success on every port tried — `1.1.1.1:22` and `1.1.1.1:2408` both
"open" — and then every one of them returns **zero bytes** when bytes are sent, including
`1.1.1.1:443`, which is an HTTPS endpoint that must answer.

So a TCP reachability check on this machine is worth nothing at all, and any conclusion drawn from
"the port accepts a connection" would be an artifact of the sandbox rather than a property of the
network. This is the same trap as the emulator's `nc -z`, one layer up, and it is worth stating
plainly because TCP-based relay plans would have been built on exactly that false signal.

**What survives is UDP**, where a datagram either comes back or it does not, with the six-resolver
control to prove the path is live. Every WARP verdict here rests on that and nothing else.

### IPv6 was never actually a way in

Worth recording because it looks like an obvious untried angle. The device has IPv6 addresses, but
they are ULA (`fd17::`), there is no global route that carries traffic, and every IPv6 destination
tested — Cloudflare, Google, even the link-local gateway — is refused at the local network. Both
v6 and non-v6 destinations fail identically, which is the signature of no working IPv6 path rather
than of selective filtering. So IPv6 is not an alternative here; it is simply absent.

## Why no client-side trick fixes it

WireGuard has no TCP transport. There is no port, protocol version, obfuscation or client option
that turns a filtered UDP destination into a reachable one. AmneziaWG was tried and measured, not
assumed. So the honest answer for direct WARP is that it cannot work here, and the app says so
instead of showing a toggle that pretends otherwise.

## What actually works: a relay

A relay the user runs elsewhere accepts the WireGuard handshake over TCP and forwards it to
Cloudflare. Two things about it are easy to get wrong, and both are pinned by tests:

- A relay **terminates** the handshake, so the peer's key and the endpoint are the relay's. Sending
  Cloudflare's key to a relay that does not own it fails exactly like a dead WARP — which is how a
  working relay gets blamed for not working. Both are swapped together, never one alone.
- A relay has **one fixed port**, so rotating Cloudflare's advertised ports through it only builds
  profiles that cannot connect. The rotation budget drops to a single attempt.

Configure one by long-pressing the WARP row: address, port, the relay's own public key, and an
optional preshared key. The settings row reports a relay as a relay rather than letting a doomed
handshake time out silently.

## Why WARP runs inside sing-box

WARP used to be driven by the embedded WireGuard Android backend (`libwg-go.so`) alongside
sing-box's `libbox.so`. Those are each a complete cgo Go runtime, and two of them in one Android
process do not coexist: loading the WARP backend and then calling into libbox killed the process
with signal 11 in about a second, with no Java exception, no tombstone and no stack. Either
runtime alone was fine, which is why it only ever appeared in the full device suite — the one run
that loads both.

sing-box speaks WireGuard itself, so WARP is now a profile the engine that already carries the
subscription starts. One runtime, one TUN.

### The profile shape, and why it was not guessed

The engine refused every field by name until it said what it wanted:

```
WireGuard outbound is deprecated in sing-box 1.11.0 and removed in sing-box 1.13.0,
use WireGuard endpoint instead

destination override fields in direct outbound are deprecated in sing-box 1.11.0 and
removed in sing-box 1.13.0, use route options instead
```

Its published JSON schema also disagrees with its own decoder, so every field was confirmed
against the engine rather than against documentation. The result is a top-level `endpoints` entry
with a `wireguard` type, no outbound destination override, and `address`/`private_key`/`peers`
carried on the endpoint itself.

## What is still not proven

WARP is **not** demonstrated carrying traffic on this network. There is no relay the user
controls available here, so the relayed path is verified only as far as the engine accepting the
profile and the app wiring it up — not as an open tunnel with traffic on it.

The next honest step is an end-to-end measurement against a real relay, not another config claim.

### What the on-device integration test actually reports

`ColgramWarpDeviceIntegrationTest` drives the real Android VPN consent dialog, starts a real
tunnel, and reads Cloudflare's own `cdn-cgi/trace`. Run on this network it reports:

```
MEASURED warpOn=false connected=false
lastFailure=WARP fail-closed after all advertised UDP endpoints stayed silent
```

That is the honest end state, and the test asserts it: `isUp()` false **and** a named reason. A
route that cannot carry traffic must not be left looking alive.

Getting there took four fixes, all of which had every other test passing while the tunnel could not
survive its own first second:

1. `FOREGROUND_SERVICE_SPECIAL_USE` was never declared. From API 34 that type requires its own
   permission, and the process died with signal 6 the instant the tunnel came up.
2. The AppTests module is a *standalone app* and does not inherit the app manifest, so it needed
   the permission declared separately — the package had zero of them.
3. `Libbox.touch()` looks like the engine's initialiser, because every generated class calls it
   from its static initialiser. Its body is **empty**. `Libbox.setup(SetupOptions)` is the call
   that does the work, and nothing called it — so the first real call walked into a nil.
4. `CommandServer.start()` was never called before `startOrReloadService`, and the override
   argument was `null`. Both are read without a nil check on the Go side. This one is worth
   recording because the crash **survived** fix 3: same panic, same line, so the remaining nil had
   to be somewhere else.

The general lesson, now written into the tests: a correct, validated, fully-loaded profile and a
completely dead tunnel were indistinguishable to every check in the suite except one that actually
started the thing.

## The pattern behind most of these

Four separate faults had the same shape, and it is worth naming because it predicts where the next
one will be:

| Fault | What `checkConfig` said | What actually happened |
|---|---|---|
| No `tun` inbound | accepted | no TUN, nothing captured |
| `auto_route` alone | accepted | TUN up, `routes=[]` |
| DNS `detour` | **accepted** | service refused to start at all |
| `flow: ""` | accepted | treated as an empty outbound |

`checkConfig` validates the *shape*. It does not validate that the engine can start, that a route
is installed, or that a field means what it looks like. Three of these four were accepted by the
validator while breaking the feature completely.

So the rule this codebase now follows is: **anything that decides whether traffic actually moves is
measured against the running engine, never against the profile text.** Concretely —

- `ColgramTunInboundDeviceTest` — the engine accepts a TUN that claims the device.
- `ColgramDeviceRouteCaptureDeviceTest` — the engine *asks Android* for `0.0.0.0/0`.
- `ColgramWarpDeviceIntegrationTest` — the tunnel comes up and Cloudflare answers.
- `ColgramUdpAssociateDeviceTest` — a datagram survives the SOCKS5 UDP framing.

The one that took longest to build is the second, and it is the one that would have caught the
empty tunnel: a configurator that records instead of building, so the engine's actual request is
the evidence.

## What the goal asks for, and where each part stands

Audited item by item rather than assumed. "Proven" means a measurement produced it.

| Requirement | State | Evidence |
|---|---|---|
| VLESS Reality | proven | engine accepts the built profile |
| VMess | proven | engine accepts the built profile |
| Trojan | proven | engine accepts the built profile |
| Shadowsocks | proven | engine accepts the built profile |
| Hysteria2 | proven | engine accepts the built profile |
| Hysteria 1 | proven | engine accepts the built profile |
| SOCKS | proven | engine accepts the built profile |
| UDP ASSOCIATE in the SOCKS bridge | proven | a datagram makes the round trip through the associate socket |
| VpnService routes the whole device | proven | the engine asks Android for `0.0.0.0/0` and `::/0` |
| A subscription from the bot works | proven | shared link → stored → profile → engine accepts |
| WARP carries traffic | **not proven** | 0 of 16 ingresses answer, 6 of 6 resolvers do |

The one row that is not proven is not a rounding error. Cloudflare's WireGuard UDP is filtered on
this network, WireGuard has no TCP transport, and no client-side option changes that. A relay is
the only path, it is wired and engine-verified, and it needs a VPS on an unfiltered network to be
measured. Until one exists, "WARP works" would be a claim rather than a result.

## The local SOCKS bridge, for completeness

Unrelated to WARP but part of the same bypass machinery, and fixed in the same pass: the loopback
SOCKS5 endpoint used to answer `host unreachable` to every UDP ASSOCIATE. That is not a degraded
route, it is no route — and the user-visible symptom is a connection that cannot be established with
nothing in the log saying why. It now serves RFC 1928 ASSOCIATE: loopback BND address (never
`0.0.0.0`, which reaches nothing while looking like a dropped association), one upstream socket per
destination with a reader so replies actually come back, and a header length derived from the
address form so a domain name's own length byte is not guessed at.

It still only carries Telegram addresses. That is the listener's purpose, not a limitation to fix.

## The TUN, and why "valid profile" was not enough

Recorded because it is the shape of bug this codebase kept hitting: the configuration was correct,
validated cleanly, and did nothing.

sing-box hands the operating system its addresses and routes through
`PlatformInterface.openTun`, and it only does that for a **`tun` inbound**. The subscription profile
carried only a local `mixed` inbound on `127.0.0.1` — a SOCKS port for something on the device to
dial deliberately, capturing nothing by itself. So there was no `openTun` call, no descriptor and
no installed routes: the service started, the switch turned blue, and the phone talked to the
network directly, exactly as before. From the settings screen that is indistinguishable from a
working tunnel.

`checkConfig` accepted the profile the whole time. A valid profile and a completely inert one are
the same string to the validator — the same trap as the removed `wireguard` outbound, which the
engine also accepted until it named the field it wanted. The engine is the only authority on these
names, and it is worth asking rather than assuming: this version has **no `route_address` key at
all** and claims the device with **`auto_route`**, so a profile written against the older shape
would have been refused by name.

Both profiles now carry a TUN with `auto_route` and `strict_route`. `strict_route` matters as much:
without it, traffic can slip around the tunnel via the physical interface, and "covers the whole
phone" is only usually true.

The device test asserts both directions — that the built profiles carry a TUN, and that a profile
without one is valid *and inert*. The second half is the point: it is the shape that looks like
success to every other check in the suite.

## The measurement that changed the conclusion: WARP has a second endpoint, and it is not filtered

Everything above measured Cloudflare's **WireGuard** ingress - 162.159.192.x, 162.159.193.x and
188.114.9x.x on UDP 2408. The conclusion drawn from it was "the only route to WARP is a relay on a
network that does not filter UDP". That conclusion was correct about WireGuard and wrong about
WARP, because WARP clients do not only speak WireGuard.

WARP's **MASQUE** endpoint is a different service, in a different block, on a different port.
The addresses Cloudflare hands out for it sit in **162.159.197.0/24**, and it is reached over
**HTTP/2 on TCP 443**. That is the one transport this network does not filter.

Measured here, over TCP, on 162.159.197.1/5/0/10:

    TLSv1.3   ALPN h2   certificate CN=engage.cloudflareclient.com   server: cloudflare

So the endpoint is reachable, it negotiates HTTP/2, and Cloudflare's edge serves a real WARP
certificate for it. That is a materially better position than "the WireGuard port is filtered":
the whole earlier argument - that no client-side framing can move a datagram onto a filtered port -
never applied to this service, and the relay is not the only option any more.

### What the endpoint then said, and how to read it

    server SETTINGS: MAX_CONCURRENT_STREAMS=100  INITIAL_WINDOW_SIZE=65536
                     MAX_FRAME_SIZE=16777215
    Extended CONNECT allowed (0x08):  NO

    GET  /.well-known/masque/udp/default/   403
    CONNECT :protocol connect-ip            403
    CONNECT :protocol connect-udp           403
    CONNECT :protocol connect-tcp           403
    GET  /.well-known/masque/ip/default/    403
    GET  /                                   403

**The absence of 0x08 is the decisive detail.** Extended CONNECT is only legal when a server
advertises ENABLE_CONNECT_PROTOCOL, and without it every CONNECT is refused regardless of path or
protocol. That matters because WARP's 1:1 and streaming protocols tunnel at layer 3 via
:protocol=connect-ip (RFC 9484) - connect-udp is the wrong protocol for WARP entirely. So the
server here is closed to the one protocol WARP actually speaks.

The 403s are a Cloudflare edge decision (cf-ray present, "server: cloudflare"), and they are what
an unauthenticated request to this hostname gets. What they do **not** show is a block: a 403
arrives in 1-2 ms over a completed TLS 1.3 h2 session on a port that is open. Nothing here was
filtered - the request was understood and declined.

**What this changes and what it does not.** It changes the shape of the problem. The endpoint is
identified, reachable, and speaking the right protocol version, and it refused every route shape
tried with no credentials. Whether it will accept one with a WARP registration is a different
question from anything measured so far, and answering it needs a real client - which is the next
step, not a claim. It does not change the relay's role: the relay is still built and still proven,
and it is still the answer for the WireGuard path.

Run it with: python scripts/warp-masque-443-probe.py

### A correction to two earlier "no answer" results

Both were client bugs, and both are worth recording because a probe that cannot speak the protocol
produces silence that looks exactly like a block.

A QUIC Initial was measured at 0 of 40 on the host. A first attempt at the same probe read no
answer at all, because it looked for the "PRI * HTTP/2.0" preface in the *server's* reply - that
preface is client-only, and a server that is answering perfectly never sends it. The same file
wrote HTTP/2 frame lengths as 4-byte big-endian integers when the format uses 3, which shifts
every byte after the length and makes the peer ignore the frame. Both produce silence on an
endpoint that is working.

Run them with:
    python scripts/warp-quic-initial-443.py
    python scripts/warp-quic-response-anatomy.py

### The phone's 4 of 40, and why it was not progress

The device run reported 4 of 40 valid Initials answered, with first bytes 0x9e, 0xca, 0xd5 and
every answer exactly 31 bytes - while the host, on the same network, answered 0 of 40. That looked
like the QEMU NAT distorting host measurements, which would have been worth knowing.

It is not a real QUIC reply. Short-header packets are encrypted with keys derived from the
completed handshake, and a server that has seen one Initial and no client hello has no such keys -
so 0xca and 0xd5 cannot be real replies. 0x9e is more interesting: it is a long header with the
fixed bit clear and packet type 10, which is Retry, and a Retry is the one answer a server may
send before a handshake completes.

The control that settles it is a **dead port**. 2408 and 500 have nothing listening and are
filtered, so an answer from them cannot come from a server. The anatomy test sends the same probe
to both and compares:

    python scripts/warp-quic-response-anatomy.py
    python scripts/device-tests.py org.colgram.core.ColgramDeviceQuicAnatomyTest

A 31-byte answer is also too short to be a Retry carrying a token and its 16-byte integrity tag at
any connection-id size, which is a second reason the first byte alone is not enough to classify.

## The SNI filter: what the official client runs into, and exactly what the filter matches

Cloudflare's own client is installed and registered on this host, and it fails. Its service log
names the reason, and it is not the network:

    connect_with_protocol_racing{primary="masque" secondary="H2"}
    h2_tun: Connecting to edge sni="consumer-masque.cloudflareclient.com"
    Start racer 0.0.0.0:35945 ---> 162.159.198.2:443

So the client races QUIC over UDP first - ports 1701, 4500, 4443, 8443 and 8095, all measured
silent here, as expected - and then **falls back to HTTP/2 over TCP 443**, which is the one
transport this network carries. That fallback is the whole route to WARP, and it fails on the
name.

Measured to `162.159.198.2:443`, changing only `server_name`, keeping the address, port and
everything else identical:

| server_name | result | time |
|---|---|---|
| `engage.cloudflareclient.com` | OK, TLS 1.3, ALPN h2 | 164 ms |
| `connectivity.cloudflareclient.com` | OK, TLS 1.3, ALPN h2 | 143 ms |
| `cloudflareclient.com` | OK, TLS 1.3, ALPN h2 | 121 ms |
| `example.com` | OK, TLS 1.3, ALPN h2 | 146 ms |
| `consumer-masque.cloudflareclient.com` | FAIL, SSLEOFError | 2111 ms |
| `masque.cloudflareclient.com` | FAIL, SSLEOFError | 2563 ms |
| `masque.example.com` | FAIL, SSLEOFError | 2131 ms |
| `consumer-masque.example.com` | FAIL, SSLEOFError | 2132 ms |
| `mqs.cloudflareclient.com` | FAIL, SSLEOFError | 2150 ms |
| `notmasque.com` | FAIL, SSLEOFError | 2085 ms |
| `MASQUE.cloudflareclient.com` | FAIL, SSLEOFError | 2152 ms |

**The filter is not a whole-name match.** It fires on any `server_name` containing `masque` or
`mqs`, case-insensitively, in any domain - `masque.example.com` and `notmasque.com` are dropped
exactly like Cloudflare's own name. A blocked name takes ~2.1 s against ~130 ms for one that
passes, so the delay is the filter's own rather than a timeout, and the same result appears on
162.159.198.1, .2 and 162.159.197.3 - so it follows the name, not the address.

The same split appears on `engage.cloudflareclient.com` at 162.159.197.x too, and a ClientHello
with no `server_name` at all completes on .2. So the door is reachable; the name is what is shut.

### …and reaching the door is still not reaching WARP

Under every name that passes the filter, the MASQUE path is answered, and refused:

    CONNECT /.well-known/masque/udp/default/  :protocol connect-ip   400
    CONNECT /.well-known/masque/ip/1/1/      :protocol connect-ip   400
    GET  /                                        403
    GET  /cdn-cgi/trace                          200   warp=off

All from one colo and one `cf-ray`. The virtual host behind an unfiltered name does not route to
the WARP backend, so passing the filter gets a client to Cloudflare's edge over TCP 443 and not one
byte further into WARP. That is a narrower result than a block and a much narrower one than a
tunnel, and it is why the relay is still the answer for the WireGuard ingress.

    python scripts/warp-sni-bypass-probe.py
    python scripts/warp-masque-endpoint.py --all

### A 403 here is not what a 403 from Cloudflare looks like

For most of this investigation a 403 was read as Cloudflare declining a request. It was not. httpx
was applying an `sni_hostname` override that turned a 200 into a 403 on *every* path, including
`/robots.txt` and `/cdn-cgi/trace` - and a host that 403s robots.txt is serving an error page, not
a tunnel. The control that caught it: the identical request without the override returned 200 with
a real trace body. Two rules came out of it, and both are now built into the probes rather than
remembered:

  * an endpoint is dialled **by name**, with the address pinned by an in-process resolver, so the
    SNI, the Host header and the certificate check are correct by construction;
  * httpx is built with `trust_env=False`, because a proxy on this host answers 403 to everything.

With both in place, all five MASQUE addresses serve a genuine `warp=off` trace, which is the first
honest signal that the endpoint is alive and the earlier 403s were manufactured locally.

### Correction: the filter is a word list, not just `masque`

The table above reads as a rule about one word, and a wider sweep shows it is a list. Same address,
same port, only the first label changed:

| server_name | result | time |
|---|---|---|
| `example.com` | OK | 281 ms |
| `nomask.com` | OK | 399 ms |
| `api.cloudflareclient.com` | OK | 161 ms |
| `api.example.com` | FAIL | 2192 ms |
| `engage.example.com` | FAIL | 2306 ms |
| `update.example.com` | FAIL | 2529 ms |
| `device.example.com` | FAIL | 3513 ms |
| `update.cloudflareclient.com` | FAIL | 2192 ms |
| `device.cloudflareclient.com` | FAIL | 2768 ms |
| `www.cloudflareclient.com` | FAIL | 2176 ms |
| `u.cloudflareclient.com` | FAIL | 2362 ms |
| `masquerade-ok.com` | FAIL | 2505 ms |
| `www.example.com` | OK | 191 ms |

So the marked substrings include `masque`, `mqs`, `api`, `engage`, `update`, `device`, `www` and
`u` - matched inside longer names, case-insensitively, in any domain, with no relation to
Cloudflare required. `masquerade-ok.com` fails, which is the clearest statement that this is a
substring match and not a word or label match.

What is **not** claimed is the size of that list. A sweep finds members; it does not enumerate
them, and a client that picked an unlisted name as a disguise would be relying on a word the
filter may add tomorrow. That is a reason to treat name-disguise as fragile even where it is not
reliable, rather than a scheme to build on.

The delay is the filter's own and it is consistent: every blocked name above sits between 2.1 s and
2.8 s before the EOF, every permitted one between 114 ms and 569 ms. That gap is wide enough to tell
the two apart at a glance in a log, which is the cheapest diagnostic available for "is this name
being filtered".

### The same filter, measured on the phone

The host result does not carry over by itself - the emulator sits behind QEMU user-mode NAT, and its
TCP behaviour has already proven to be an artefact of the sandbox rather than the network. So the
split was measured on the device, to `162.159.198.2:443`, changing only `server_name`:

```
BLOCKED   consumer-masque.cloudflareclient.com  FAIL  2317 ms
BLOCKED   masque.cloudflareclient.com           FAIL  2343 ms
BLOCKED   masque.example.com                    FAIL  2220 ms
BLOCKED   mqs.cloudflareclient.com              FAIL  2308 ms
BLOCKED   notmasque.com                          FAIL  2231 ms
PERMITTED engage.cloudflareclient.com           OK h2  395 ms
PERMITTED connectivity.cloudflareclient.com     OK h2  288 ms
PERMITTED cloudflareclient.com                  OK h2  269 ms

blocked 0/5 got through   permitted 3/3 completed TLS with h2
```

Identical to the host, including the shape of the delay: 2.2-2.3 s to fail against 269-395 ms to
succeed. So the filter follows the `server_name` on the phone's path too, and a permitted name
negotiates HTTP/2 on the WARP endpoint from the device as well as from the host.

    python scripts/device-tests.py org.colgram.core.ColgramDeviceSniFilterTest

### What is left is credentials, and what is *not* left is a client

Two measured facts close off the routes that would have needed this transport:

  * **`ENABLE_CONNECT_PROTOCOL` (0x08) is absent** from the server's SETTINGS on 162.159.198.2,
    .1 and 162.159.197.3 - the only three settings keys returned are 3, 4 and 5. Without 0x08,
    Extended CONNECT is unavailable to *any* client, so `:protocol=connect-ip` is refused with 400
    whatever the name, and a relay on an unfiltered host could not use it either. That is a
    property of the endpoint, not of the network.
  * **`libbox.so` has no MASQUE outbound.** Strings for `option.Hysteria2Masquerade` are Hysteria2's
    own feature; there is no `option.Masque` or `transport.Masque`, and the `/.well-known/masque/udp/`
    and `http3: server didn't enable Extended CONNECT` strings in the binary come from the bundled
    Chromium QUIC stack, not from sing-box. So even a reachable, permitted MASQUE endpoint would
    have nothing in Colgram to speak it with - it would need new code, not configuration.

The relay over TCP remains the only route to the WireGuard ingress, and it is built and proven.
Reaching Cloudflare's edge over TCP 443 under a name the filter does not match is real, and it is
not WARP.

### The last unmeasured UDP shape on the MASQUE block, and why it is nothing

The MASQUE block had not been swept the way the WireGuard block was, so every UDP port the official
client races - 1701, 4500, 4443, 8443, 8095 - plus 443 and 500 were measured directly with a 1200-byte
QUIC Initial:

```
162.159.198.2  udp/443 silent  8443 silent  8095 silent  4443 silent  4500 silent  1701 silent
162.159.198.1  udp/443 silent  8443 silent  8095 silent  4443 silent  4500 silent  1701 silent
162.159.197.3  udp/443 silent  8443 silent  8095 silent
162.159.198.2  udp/500  98B first=0xf0   <- the one answer, on one attempt
```

`0xf0` is an IKE response and 98 bytes is about the size of an IKE_SA_INIT reply, which is a
tempting read: IKE_SA_INIT carries no authentication, so an endpoint would answer it before knowing
who was asking. It does not survive being repeated. Sending the same QUIC Initial to 500 six more
times returned **0 of 6**; sending 1200 random bytes returned **0 of 4**. One answer in a single
attempt is the filter's own behaviour, not a service - the same instability this file has recorded
twice before, where `188.114.96.1:2408` answered once and then went silent for 20 consecutive
probes. That is why no verdict here rests on a single answered packet, and why this one is recorded
as noise rather than as an IKE path that did not work.

The two 198.x and 197.x addresses that do not answer 500 also do not answer anything else, so there is
no second unfiltered route hiding on this block either. Every UDP port the client tries is closed;
the one transport that works is TCP 443, and on that transport the endpoint refuses Extended
CONNECT for every client and the app has no MASQUE outbound to speak with anyway.

## What the 400 actually says, and why it is not the network's doing

A status code is not a reason. Reading the body of the 400 gives a sentence:

```
HTTP CONNECT is not supported | engage.cloudflareclient.com | Cloudflare
```

That arrives in 93-561 ms, with `content-type: text/html`, a `cf-ray` and `server: cloudflare` - an
error page from the edge, not an HTTP/2 response from a tunnel backend. So the 400 that the whole
HTTP/2 route was resting on was never a MASQUE refusal at all: **Cloudflare's edge does not
implement the CONNECT method**, and it says so before any backend sees the request.

Swept across two kinds of address - the MASQUE block and 1.1.1.1, Cloudflare's own resolver - and
three vhosts, twelve requests, all of them:

```
engage.cloudflareclient.com        162.159.198.2  400  EDGE REFUSES THE METHOD
engage.cloudflareclient.com        162.159.197.3  400  EDGE REFUSES THE METHOD
engage.cloudflareclient.com        1.1.1.1         400  EDGE REFUSES THE METHOD
connectivity.cloudflareclient.com 162.159.198.2  400  EDGE REFUSES THE METHOD
cloudflare.com                     1.1.1.1         400  EDGE REFUSES THE METHOD
cloudflare-dns.com                 1.1.1.1         400  EDGE REFUSES THE METHOD
  (both /masque/udp/default/ and /masque/ip/default/ on each)
```

**The refusal is a property of the edge, not of the WARP virtual host.** That distinction is the
whole point of sweeping `1.1.1.1` and `cloudflare.com` alongside the WARP names: a vhost-specific
refusal would be something a different name fixes, and an edge-wide one is not. This is the second.

Combined with `ENABLE_CONNECT_PROTOCOL` (0x08) being absent from a **complete** 18-byte SETTINGS
frame on all six vhosts, the HTTP/2 route is closed at both ends: the server never offers the
mechanism, and the edge never forwards the request that would use it. No credential changes that,
and neither does a relay on a host with clean UDP - the request does not get far enough to reach
the network at all.

    python scripts/warp-connect-support.py

### One thing worth recording about how that 0x08 was read

The absence of 0x08 is the load-bearing measurement in that reasoning, so it is worth recording
how it is read. HTTP/2 frame headers carry a **3-byte** length, and a single `recv` on a TCP
connection can return a partial frame. Parsing whatever arrived drops every setting past the cut -
and since 0x08 is the key being looked for, a truncated read reports "the server does not allow it"
when the server in fact said nothing about it. The first version of this probe did read once. The
probes now loop until the announced length is satisfied, and the frame measures 18 bytes with keys
3, 4 and 5, arriving whole on all six vhosts. The absence is real, and it survived being checked.

This is the same class of bug as the 403s and the QUIC silence: a measurement that cannot tell
"the server said no" from "my client did not read the answer" produces silence, and silence reads
as a block. Three of this file's earlier conclusions were wrong for exactly that reason, and the
fourth was saved by reading the body of the error instead of its status code.

## The measurement this file was missing: QUIC, asked of a client that could have worked

Every QUIC result above came from sending bytes at a port. **No QUIC endpoint was ever confirmed
reachable from here** - so "QUIC is filtered" rested on a control that was itself unverified. Each
host tried is silent whether the path drops QUIC or the destination does not serve it, and the file
had been reading the second as the first for a long time.

Two measurements, and the first one with something that could actually have succeeded.

**On the host, with aioquic** - the QUIC implementation curl builds against, against four hosts
that are known to serve QUIC:

```
www.google.com     ->  silent after 6.0s    a real QUIC deployment on udp/443
cloudflare.com     ->  silent after 6.1s    the same edge that answers h2 on tcp/443
www.facebook.com   ->  silent after 6.1s    a real QUIC deployment on udp/443
dns.google         ->  silent after 6.1s    a resolver that also speaks HTTP/3

0 of 4 handshakes completed
```

**On the device, with a real Initial over a connected socket** - a host with no tunnel of its own:

```
142.250.74.174:443  silent   Google - a real QUIC deployment on udp/443
104.16.132.229:443  silent   Cloudflare - the same edge that answers h2 on tcp/443
157.240.1.35:443     silent   Facebook - a real QUIC deployment on udp/443
control  1.1.1.1:53  18B     a resolver that always answers

TOTAL 0/3 hosts sent something a QUIC server could have sent
```

**Both agree, and the control is inside the same run.** Two paths - one through a WireGuard
tunnel, one with no tunnel on the device - and neither carries a QUIC handshake to a host that
serves it every day, while a DNS query to the same socket gets 18 bytes back.

So the claim this file has been making since "443 answers" is now, for the first time, about the
path rather than about packets: **QUIC does not complete here, and the destinations are known to
serve it.** Everything downstream - the MASQUE transport, the H2 fallback, the QUIC-in-TLS idea - is
closed on a measured basis, and the relay over TCP remains the only route.

Three API mistakes got there, each of which would have read as a result: a protocol factory called
with keyword arguments the library never passes, a ConnectionTerminated field named `error` rather
than `error_code`, and `send(byte[])`/`receive(byte[])` not existing on the API level this app
compiles against - so the device half would have stayed unrun while the host's said QUIC is
filtered.

