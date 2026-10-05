# WARP: the tunnel carries packets in both directions — 2026-10-01, final session

Eleven real defects found and fixed, each measured on the device (MuMuPlayer, x86_64,
`127.0.0.1:16384`). The interface now receives what the tunnel sends it.

## The measurement that ended it

```
I ColgramMasqueVpn: pump packet 1 len=76 native=true nativeError=null lastError=null stage=none in=0 dropped=0 bytes=0
I ColgramMasqueVpn: reply written, 44 bytes
I ColgramMasqueVpn: pump packet 2 len=48 native=true nativeError=null lastError=null stage=open (carrying packets to 162.159.198.2:443) in=2 dropped=0 bytes=88
```

```
tun0: TX 48 packets, RX 9 packets
up 2252 pkts / 251753 bytes   down 2263 pkts / 154860 bytes   sessions 1
```

`rx=9` is the first time that number has been anything but zero. Everything before it produced the
same three figures -- interface up, tx rising, rx zero -- and the whole difficulty was that none of
them said which of two very different faults was in play.

## Defect 11: the pump was writing TCP payload where an IP packet belongs

`colgram_masque_exchange` returned `inbuf`, which is the TCP payload stream after `feed()` has
stripped the IP and TCP headers. The Java pump writes whatever it gets straight into the tun
descriptor:

```java
byte[] reply = ColgramMasqueNative.exchangeIpPacket(packet, bind, edge);
if (reply != null && reply.length > 0) {
    out.write(reply, 0, reply.length);
    out.flush();
}
```

A tun write needs a whole IP packet. Given a TCP segment instead, the kernel reads the first nibble
as an IP version -- it is a TCP flag byte -- and drops it on the way in. So the interface stayed
silent no matter how much traffic arrived, which is precisely what the counters said:

    in=2 dropped=0 bytes=88

capsules arriving, none discarded, and rx at 0. The capsule counters are what made the fault
nameable; without them this reads exactly like a dead network.

`feed()` now queues each inbound packet whole in a `pending` queue, bounded at 256, and
`takeIPPacket()` hands the pump one of those. `inbuf` keeps its own job, which is reading the flow's
payload stream.

## What the three diagnostics are for

They exist because the absence of an error message was being read as success for an entire session:

- `lastError` -- what went wrong. Fixed to read `lastExchangeErr`; it was reporting `bridgeErr`,
  which the tunnel never writes.
- `stage` -- how far the session got: `none`, `enrolling`, `dialing`, `connecting`, `opening-peer`,
  `open`, or `failed`. `open` means enrolment returned, QUIC completed, the extended CONNECT was
  accepted and the inner SYN was acknowledged.
- `capsuleStats` -- `in=`, `dropped=`, `bytes=`. Separates "nothing came back" from "everything came
  back and was thrown away", which is the difference between a network fault and a bug, and they
  call for opposite fixes.

Together they turned a single ambiguous symptom into a position on a line.

## The eleven, briefly

1. `ColgramSettingsActivity` declared as an activity, in the wrong package and not an Activity at all
2. `android.permission.BIND_VPN`, a permission that does not exist, for the VPN service
3. the native library had no JNI entry points -- cgo exports the VM cannot see
4. the libraries were Windows PE files that happened to exist and be large
5. the pump reported nothing, so a load failure and a dead tunnel looked identical
6. `open_session()` ignored the SOCKS5 front that `measureWith()` honoured
7. `last_error` read `bridgeErr` instead of `lastExchangeErr`
8. `quic.Dial` had no deadline, so a filtered path hung forever and blocked the pump
9. the SOCKS5 front was inside the tunnel, so the app could not reach the front meant to carry it
10. inbound capsules bypassed flow matching and went to whichever peer existed
11. the pump wrote TCP payload into the tun instead of an IP packet

Checkers that fail on 1, 3, 4 and on a VpnService with the wrong permission:
`check_manifest_components.py`, `check_jni_symbols.py`, `check_masque_build.py`. Full suite green.

## Where it stands

The tunnel is up, the session is open, capsules arrive, replies are written to the interface, and
the bytes are carried by a SOCKS5 front over TCP because the device's own UDP is filtered.

What is not yet shown is `warp=on` read from inside the app on the phone. The measurement path does
that and has for several sessions, but through a different code path than the tunnel: it opens its
own flow to `connectivity.cloudflareclient.com` and reads Cloudflare's trace. The tunnel currently
carries a single peer to the edge and returns whatever that flow answers, so proving WARP end to end
from the device means routing the trace request through the same interface and reading the response
back -- which is the next piece of work.