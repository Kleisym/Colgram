# Connection base and measured transport states

The in-process base is mandatory. `isDpiBypassEnabled()` is no longer a route selector. An old saved `false` is ignored. Startup binds the listener and attaches it to Telegram; a three-second watchdog repairs a lost listener without replacing an explicitly selected remote route. Disabling a remote proxy persists the OFF choice and returns to the base, not to unshaped direct sockets. Both settings screens show read-only live status for the base: no switch, click handler, or OFF action exists. Pending SOCKS handshakes have a 32-slot admission limit; a failed Telegram connect waits one second before returning an error, preventing a cached connect failure from becoming an immediate native retry/logging loop.

## Layer composition

- Native direct Telegram connections use the local SOCKS listener. Its status separates bind, TCP connect, first reply bytes and protocol verification.
- A selected public TCP proxy has a byte-preserving local front for the exact verified endpoint. The saved identity remains the real proxy, never its ephemeral loopback port. Anonymous verification uses the same front. Bytes, requested port, credentials and FakeTLS authentication are not rewritten.
- WARP registration keeps the actual API hostname and certificate verification. Go's own TLS ClientHello is divided into valid TLS records without changing its handshake transcript. The JSON media type is sent, and MASQUE enrollment uses the actual `tunnel_type` field. The endpoint public key is still pinned. A successful HEAD is not a tunnel: the runtime requires an encrypted trace reporting `warp=on`.
- TCP subscription outbounds use an authenticated protected local underlay, so the selected VPN does not feed its own outgoing socket back into its TUN. QUIC retains its protected UDP transport; it is not mislabeled as TCP fragmentation. The real VLESS fixture checks the selected UUID, an actual TUN, and bidirectional payload carriage to a local test peer. Stopping explicitly releases that TUN before waiting for Android to unbind the VPN service; a generation guard preserves a newer queued profile.
- The calls picker still has no unprotected Direct option. Keeping the base alive does not claim an unreachable call peer has become reachable.

## Catalog

All 24 currently configured compatible feeds are scheduled in a bounded four-worker batch, rather than rotating only 11 feeds per refresh. Twelve confirmed 404 registrations, a 403-only API, a text pointer mislabeled as JSON, and a duplicate branch alias were removed. HTTPS download, successful parsing, TCP, SOCKS CONNECT and an actual anonymous Telegram `help.getConfig` remain separate outcomes. Source diagnostics include per-URL failures and parsed record counts; duplicates do not become false format failures.

Parsing rotates through records within each feed instead of repeatedly importing only its head. Candidate storage is bounded to 384 records per native protocol. A background wave screens at most 96 endpoints and performs at most five genuine protocol checks in a fifty-second window. It runs independently of the external-proxy switch; a persistent network-constrained Android job also runs bounded checks while the UI is hidden.

A positive protocol verdict lasts at most 180 seconds on the same network. Positive refreshes are prioritized even after expiration; successful waves reschedule after fifteen seconds. Network changes immediately invalidate old verdicts. This fixes the previous sixty-second validity versus up-to-ninety-second refresh cycle, which made verified rows disappear before their recheck.

## Acceptance evidence

`F:/brom/artifacts/connection-recovery/VERIFICATION.txt` records the exact tested APK hash, commands, literal results, errors and rollback evidence. Tests only operate on the disposable bridged MuMu guest `192.168.0.59:5555`. No host VPN changes, account logins or user tokens are required.

On a stalled direct local route the watchdog requests Telegram’s signed DC configuration through the upstream resolver without selecting a public proxy. A system VPN, WARP, or an off-screen app does not trigger that direct recovery. The background status row opens per-source HTTP and parser outcomes.

The direct acceptance test demands `TG_CONFIG_OK` with no Android VPN and no public proxy. A listener, TCP SYN, enabled switch or successful tunneled test does not satisfy that test. The separate WARP + base test explicitly reports `vpn_free=false`. An unreachable TCP destination has no TLS/MTProto payload to reshape; such a run remains a failed direct acceptance result rather than a fabricated successful bypass.
