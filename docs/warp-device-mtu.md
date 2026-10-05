# The device client sends 1280-byte Initials and the edge answers 1200-byte ones

## The measurement

The device reaches the MASQUE edge - proven with a Java probe that sends a 1200-byte QUIC Initial and
gets a Retry back in about 100 ms on 443, 500, 8443 and 8095. The reference Go client, registered and
running on the same device against the same edge, does not get through:

```
11:38:02.250276 IP 10.0.2.15.59868 > 162.159.198.2.443: UDP, length 1280
11:38:02.453733 IP 10.0.2.15.59868 > 162.159.198.2.443: UDP, length 1280
11:38:02.851821 IP 10.0.2.15.59868 > 162.159.198.2.443: UDP, length 1280
11:38:03.654481 IP 10.0.2.15.59868 > 162.159.198.2.443: UDP, length 1280
11:38:05.255386 IP 10.0.2.15.59868 > 162.159.198.2.443: UDP, length 1280
                                        ... no inbound packet on that filter, at all
```

Side by side, on the same device, same edge, same second:

```
1200-byte Initial  ->  95-byte Retry, rtt 100 ms      (Java probe)
1280-byte Initial  ->  nothing                         (Go client)
```

The client is not filtered, not firewalled and not misrouted. It sends the wrong first packet, and
`no recent network activity` is what a QUIC stack reports when it never receives a Retry to answer.

## The client says so itself

```
Warning: MTU is not the default 1280. This is not supported. Packet loss and other issues may occur.
```

with `-m 1200` and `-i 1200`, and the failure is unchanged - so the knob it exposes is not the thing
that governs the size of the first flight. The Initial is sized before that setting applies, and it
stays 1280.

## Why the host worked and the device does not

The host measurement in this project was made with `max_datagram_frame_size = 65536` and an initial
packet size that stayed at or below 1200, which is what the edge answers. The device client defaults to
1280, and this edge silently drops anything above the size it answers.

That is a property of this edge, not of the network: the 1280-byte datagrams leave the device on
10.0.2.15 and are seen by the capture on the wire, so the path carries them. The edge simply does not
answer them.

## What would close it

The Initial has to be padded to exactly 1200 bytes on the device, which means either a client that
sizes its first flight for this edge or a QUIC path-MTU probe that starts smaller than 1280. The
reference client's own `initial packet size` flag exists and defaults to auto with PMTU discovery, and
auto is choosing 1280 here - so the working configuration is one the client does not pick by itself.

**The device tunnel does not come up because its first QUIC packet is the wrong size. The protocol,
registration, enrolment and certificate are all confirmed correct there.**

## What the capture actually shows, and the correction

Two earlier statements in this file need narrowing, because a wider capture contradicted them.

The 1280-byte datagrams were real and were seen leaving the device, so the size difference is real.
But with `-i 1200` the client's datagrams stop appearing altogether, and a capture with no filter at
all during a dial shows the only UDP on the device going to an unrelated address:

```
11:43:05.785257 wlan0 In  IP 128.116.13.34 > 10.0.2.15:  [|udp]
11:43:05.799308 wlan0 Out IP 10.0.2.15 > 128.116.13.34:  [|udp]
```

and nothing at all to 162.159.198.2. The routing state is not the explanation: the device has one
default route, `default via 10.0.2.2 dev wlan0`, plus the link-local subnet, and `tunl0` is DOWN and
holds no route. `ip rule` shows only the standard Android rules.

So the client is failing before it puts a packet on the wire in the `-i 1200` case, and the 1280-byte
packets in the default case are the only time it emits anything at all. quic-go does honour the flag -
`config.InitialPacketSize` is clamped to `protocol.MinInitialPacketSize`, which the library's own test
asserts is 1200 - so the size is not what changes the outcome here.

## Where the device leg stands

```
confirmed on the device   registration (v0a4471) and key enrolment, config written locally
confirmed on the device   the edge is reachable - a 1200-byte Initial gets a Retry in ~100 ms
confirmed on the device   the reference client starts, listens on SOCKS, and attempts the MASQUE dial
not established           the tunnel never comes up, so no warp=on trace exists from the phone
```

The host measurement remains the one that carries traffic: UDP to 1.1.1.1 and 8.8.8.8 came back
through the tunnel with correct headers, and an ICMP echo reply returned with our own identifier and
sequence. The device leg is a separate failure and it is not explained by the network, the certificate,
the registration or the packet size.
