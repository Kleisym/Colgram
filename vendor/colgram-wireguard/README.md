# Colgram's pinned WireGuard userspace backend

This Android library is the `com.wireguard.android:tunnel:1.0.20230706` Java backend and its
four official JNI companion libraries, copied from the upstream Android source at the pinned
artifact release. `wireguard-go/` is the exact Go revision pinned by that release's `go.mod`.

The fork adds one private UAPI setting, `reserved=xxxxxx`. Colgram sources its six hex digits
from the Cloudflare WARP registration and the Go backend puts those three bytes into outgoing
WireGuard initiation and transport headers before MAC1 is calculated. The receiver classifies
packets by the first type byte while retaining the raw authenticated datagram. No external VPN
app or device-specific configuration is involved.

Native libraries are built with Go 1.24.3 and Android NDK 26.1.10909125 for arm64-v8a,
armeabi-v7a, x86, and x86_64. The module uses the Android SDK NDK toolchain and Gradle package
build; the corresponding device-level handshake and byte-counter test remains part of release
validation.
