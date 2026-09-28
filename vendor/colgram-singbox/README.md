# colgram-singbox

The sing-box engine, wired for a subscription the user bought in a Telegram bot.

## What is here and where it came from

`src/main/java/io/nekohasekai/libbox` and `src/main/java/go` are the gomobile binding for
sing-box 1.14.2. There is no Maven artifact and the binding's sources are in no public repository,
so both were recovered by decompiling the shipped `SFA-1.14.2-universal.apk` from the project's own
releases with jadx. The decompiler is clean here: these are thin JNI wrappers, and the recovered
source compiles and runs unchanged on the device.

Decompiling the whole APK also produced every unrelated class of the host application under
`defpackage` - 8,000-odd files belonging to another app. They are excluded in `build.gradle` and
were never added here. Four of them were imported by `go/Seq`; all four turned out to be
R8-obfuscated logging helpers that only ever reported a bad refnum, so they were replaced with
`android.util.Log` and the dependency is gone.

`src/main/java/org/colgram/singbox` is ours: the Android half of the binding, the VpnService that
owns the TUN, and the notifications the engine raises.

## The native library

`libbox.so` is not in this repository. It is 77MB per ABI and four ABIs are 300MB, which is not
something to carry in git. Drop it in as:

```
src/main/jniLibs/arm64-v8a/libbox.so
src/main/jniLibs/armeabi-v7a/libbox.so
src/main/jniLibs/x86/libbox.so
src/main/jniLibs/x86_64/libbox.so
```

taken from `SFA-<version>-universal.apk` at
`https://github.com/SagerNet/sing-box/releases/download/v<version>/`.

## Why the binding matters

gomobile resolves every callback the engine makes into Java BY NAME. A missing method, or one
with a different signature, is not a compile error - it is a crash the first time the engine
reaches that code, usually in the middle of the first connection. The 27 methods in
`ColgramPlatformInterface` were therefore read out of the binding rather than written from
documentation, and a device test asserts that every one of them is present and instantiable.

## What the engine said about our own profiles

The profile builder is checked against sing-box itself on the device, because a profile that is
wrong in one field comes up as a tunnel that routes nothing. Each of these was found that way:

  * the flat DNS `address` form was removed in 1.14 - the new shape is a transport type with the
    resolver nested under `server`;
  * Reality without uTLS is refused outright, because unshaped it announces a VPN client to
    exactly the censor it exists to hide from;
  * a routing rule must name an outbound TAG, not the outbound object - the object produced a
    rule that matched nothing;
  * with no failover group the tunnel binds to the first node and stays on it when that node is
    blocked, which is the whole reason to hold several;
  * Hysteria v1 takes `auth` where v2 takes `password`, and v1 requires TLS;
  * `socks5://` and `socks://` are one protocol, and the unnormalised spelling was dropped from
    subscriptions.
