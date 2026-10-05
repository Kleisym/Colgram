# The Connect-IP capsule carries a whole IP packet

## What connect-ip-go requires of a datagram

The package the reference client dials through is explicit on both halves. Sending:

```
func (c *Conn) composeDatagram(b []byte) ([]byte, error) {
    if err := validateOutgoingPacket(b); err != nil { return nil, err }
    data := append(contextIDZero, b...)   // context id 0, then the whole IP packet
    return data, nil
}
```

Validation, which is what rejected the earlier probes:

```
func validateOutgoingPacket(b []byte) error {
    case 4:
        if len(b) < ipv4.HeaderLen { return errors.New("IPv4 packet too short") }
        if ttl := b[8]; ttl <= 1 { return fmt.Errorf("TTL too small: %d", ttl) }
        b[8]--
        binary.BigEndian.PutUint16(b[10:12], calculateIPv4Checksum(b[:20]))
```

And on the receive side the peer is read straight out of the header:

```
case 4:
    src = netip.AddrFrom4(data[12:16])
    dst = netip.AddrFrom4(data[16:20])
    ipProto = data[9]
if !slices.ContainsFunc(assignedAddresses, ...) {
    return fmt.Errorf("destination address / protocol not allowed: %s (protocol: %d)", dst, ipProto)
}
```

So a capsule carrying a bare DNS query is dropped for having no IP header at all. That is exactly what
the earlier probes sent, and it is why the session opened and then went quiet.

## The packet now built, verified locally

```
length: 60
ver   : 4 ihl: 5      ttl: 64     proto: 17
src   : 172.16.0.2    dst: 1.1.1.1
cksum : 0xba69        verify: computed=0xba69  (correct)
```

Two well-formed IP/UDP packets are sent - a DNS query to 1.1.1.1 and to 8.8.8.8, both measured
answering on this network - and the session holds for 10 s with `:status = 200` and no termination.
No response capsule has come back yet, with `pq-enabled` false or true.

```
established    handshake, ALPN h3, extended CONNECT :protocol cf-connect-ip -> 200,
               session stable, capsules accepted by the edge
not yet shown  a response capsule, and therefore no warp=on trace from a device
```

**The tunnel opens. `warp=on` through it is not yet measured.**
