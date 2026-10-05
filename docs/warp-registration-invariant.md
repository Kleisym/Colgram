# The registration is shape-invariant, and two leads closed as errors in my own reasoning

## The registration field sweep

Every probe in this project copied the application body verbatim, because that body is known to be
accepted. That left untested the fields the binary lists in `WarpApiRegistrationPayload` but the app
does not send: `gateway_device_id`, `os_version`, `os_version_extra`, `identifiers`,
`tunnel_key_data`. The official client did send the last of those
(`RegistrationTunnelKeys { key_type: NistP256, tunnel_type: Masque }` is in the log), and
`conf.json` stores `device_identifiers: system_user`, so the device-identity fields had never been
varied. Seven fresh registrations, one per shape:

```
app body, bare           HTTP 200  type=a  protocol=masque  cfg=[client_id, interface, peers, services]
plus tunnel_key_data     HTTP 200  type=a  protocol=masque  cfg=[client_id, interface, peers, services]
plus os_version          HTTP 200  type=a  protocol=masque  cfg=[client_id, interface, peers, services]
plus identifiers         HTTP 200  type=a  protocol=masque  cfg=[client_id, interface, peers, services]
plus gateway_device_id   HTTP 200  type=a  protocol=masque  cfg=[client_id, interface, peers, services]
plus physical/hardware   HTTP 200  type=a  protocol=masque  cfg=[client_id, interface, peers, services]
```

Identical in every respect. No variant returned `device_identifiers`, `mtls`, `ca_bundle` or
`pkix_config`. The registration is shape-invariant: the request fields do not select a different
response, so no request-side variation is left to find.

## A bug I thought I found in the app, which is not one

`RegistrationInfo.public_key` from the service log is 32 bytes, and its name says public key. It was
tempting to conclude the app registers the wrong half: `ColgramWarp.java:263` does
`body.put("key", pubB64)`, where `pubB64` is the X25519 public key. If the edge wanted a private
scalar, the app would be sending the wrong thing.

Tested rather than assumed. An X25519 private scalar is clamped: low three bits of byte 0 cleared,
byte 31 with a high nibble of 0x40.

```
byte[0]  and 0x07  = 2      (not 0, so not clamped)
byte[31] and 0xF0  = 0x10   (not 0x40, so not clamped)
```

Not clamped, therefore not a private scalar, therefore a public key. And it differs from the P-256
point in `conf.json` (`b205ae9a...` versus `6fff30b3...`), so the two are distinct identities rather
than one key recorded twice. The app is correct; the reading was the error.

## Capturing what the client actually sends: blocked by privilege

The decisive evidence would be the bytes the official client puts on the wire during the 781 ms
window. `warp-dex.exe pcap` exists for that and takes an interface index, and it failed:

```
pcap --interface-idx 21 --time-limit-min 1
  -> {"code":"FailedToRunPktMon","error":"... Отказано в доступе."}
```

It drives PktMon, which needs elevation, and the WARP service runs as LocalSystem so its traffic is
not capturable as an unprivileged user anyway. Npcap, WinPcap, tshark and dumpcap are all absent, so
there is no unprivileged capture path on this machine.

## Where this stands

Closed by measurement, across every layer:

| Layer | Result |
|---|---|
| Network, host and device | QUIC Retry to 162.159.198.2, 6 ports, 105-189 ms on device |
| Official confirmation | `warp-diag` gets HTTP/3 200 over QUIC, tunnel off |
| Protocol | `masque`, from the registration itself |
| TLS alert reading | 0x174 is the edge's own deadline, not a certificate error |
| ALPN | only h3 answered, and h3 always fails on this edge |
| SNI | `sni: None` worked for the client; no difference here |
| Post-quantum | `post_quantum_enabled: false` on the connection that worked |
| Transport parameters | ten variations, all identical |
| Request shape | twelve shapes, three targets, sent and ignored |
| Request timing | immediate, +100 ms, after SETTINGS, keepalive, silent |
| Registration fields | seven shapes, response identical |
| Secret storage | DPAPI, SYSTEM-only, TPM path exists, no blob on disk or in registry |

The edge terminates on a 781 ms timer that starts when it issues a connection ID, and the only thing
that has ever satisfied it is identity material the desktop daemon holds, generated at registration
and sealed by DPAPI or a TPM.

**No `warp=on` measurement exists. The tunnel does not work.**
