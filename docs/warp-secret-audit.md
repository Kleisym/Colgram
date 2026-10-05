# Where the identity actually lives, and why 16 ms is not the mystery it looks like

## The 16 ms is not fast secret reading

The successful trace shows `EnsuringMtlsIdentity` work fitting between the handshake and the flow
creation, which looked like ~16 ms of reading protected secrets. Reading DPAPI is not that fast when
the blob is cold, and it is not fast at all when the daemon has to generate a keypair. So the working
run was not doing that work inline - the daemon had already created and cached the identity at
startup, and at connect time it was attaching material that was already in memory.

That is consistent with the rest of the evidence: `warp.db` has an empty `tpm_keys` table and did not
change during the successful tunnel, so the key is not written to the database on connect, and the
registration is what populates it.

## The audit, and what it rules out

Every storage location that could hold the material was enumerated and checked:

```
C:\ProgramData\Cloudflare\              32 files, all logs, plus conf.json, settings.json, warp.db
C:\Users\virsu\AppData\Local\Cloudflare\  2 logs, ipc.log, and the installer MSI
Registry HKLM\SOFTWARE\Cloudflare\CloudflareWARP    Version, DataFolder (2 values, no blob)
Registry HKCU\SOFTWARE\Cloudflare\CloudflareWARP    installed (1 value, no blob)
Registry HKCU\SOFTWARE\Cloudflare\Cloudflare One Client   0 values
Registry HKCU\SOFTWARE\com.cloudflare\                   0 values
Registry classes com.cloudflare.warp, warp                 URL protocol handlers only
```

`warp.db` is 32768 bytes of SQLite, 95% non-text, with a `SQLite format 3` header and no DPAPI blob
signature (`01 00 01 00 00 00 00 00 d0 0c 9c 79`) anywhere in it. Its tables are `update_request`,
`update_status`, `sqlite_sequence`, `tpm_keys` - the last empty, and unchanged since 27.08.

So: no private key on disk, no DPAPI blob on disk, no registry blob, no task-dump copy. The public
halves are readable in two places and the private halves in neither:

```
RegistrationInfo.public_key = b205ae9a...e466115   32 bytes, X25519  (from the service log)
conf.json own_public_key    = 91-byte SPKI, secp256r1                (from disk)
conf.json public_key PEM   = 91-byte SPKI, point 6fff30b3...776beb (from disk)
```

## What the binary says about how the secret is held

```
warp_storage::registration::secret_data::platform::PlatformSecretDataStorage
CryptProtectData / CryptUnprotectData
D:P(A;;KA;;;SY)(A;;KA;;;BA)secrets key SDDL produced no DACL
```

DPAPI, with the key granted only to SYSTEM and Administrators - which is what a LocalSystem service
uses, and which is exactly why a third-party process cannot read it even if the blob were findable.
There is also `event warp-storage\src\registration\secret_data.rs:268` and a TPM path
(`actors\actor-configuration\src\handler\registration\tpm.rs`, `handle_rotate_keys_response`,
`Failed to build renewal CSR from existing TPM key`), so on some machines the private key never leaves
a TPM at all.

## Why this is the wall, stated precisely

The identity is not a document that can be requested, and it is not derivable from anything public:

- the registration returns a public key and a token, never a private key
- no public endpoint returns a certificate, and the renewal path is 404 on every shape tried
- the certificate that the edge accepts is built from a private key that is DPAPI-protected, and on
  a TPM-backed install, sealed in hardware
- the edge's 781 ms deadline is measured from the connection ID it issues, and the client that
  satisfied it had the material already in memory

Anything else - ALPN, SNI, post-quantum, transport parameters, request shape, certificate shape,
subject, curve - has been measured and eliminated. What remains cannot be obtained without the
daemon holding that key, or without the TPM holding it.

## The one thing that would settle it

Enable WARP on this host for one minute. The daemon starts, `EnsuringMtlsIdentity` runs with the
material in hand, the tunnel comes up, and either the certificate is written somewhere readable or it
is generated in memory from the TPM - in which case the capture is a packet trace rather than a file,
and either way the `warp=on` measurement this project is missing finally exists.

That needs the user's explicit go-ahead. Everything reachable without it has been reached.

**No `warp=on` measurement exists. The tunnel does not work.**
