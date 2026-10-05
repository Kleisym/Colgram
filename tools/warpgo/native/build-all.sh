#!/bin/sh
# Builds libcolgrammasque.so for every ABI the APK ships.
#
# Why a script rather than a single `go build`: GOOS=android alone is not enough. cgo needs a
# cross compiler, and passing `--target` or `--sysroot` through CGO_LDFLAGS is rejected outright
# (go:cgo_ldflag), because the NDK clang wrappers already carry both. So each ABI pins CC to its
# wrapper and passes only the JDK include path, which nothing supplies on its own.
#
# The JNI bridge in jni_bridge.c is compiled as part of the build because it lives in this package.
# jniinc/linux must precede the JDK's include/win32 on the include path: the win32 jni_md.h defines
# JNICALL as __stdcall, which the NDK targets do not have, and every bridge function warns about it.
#
# Windows host: run through Git Bash or WSL. The Go toolchain, GOPATH and GOCACHE come from the
# environment; NDK_ROOT must point at an unpacked NDK.
set -e

cd "$(dirname "$0")"

NDK_ROOT="${NDK_ROOT:?set NDK_ROOT to an unpacked Android NDK}"
TC="$NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64"
[ -d "$TC" ] || TC="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TC" ] || TC="$NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64"
SYSROOT="$TC/sysroot"
JDK_INCLUDE="${JDK_INCLUDE:-C:/Colgram/tools/jdk17/jdk-17.0.20.1+1/include}"

# abi:goarch:clang-prefix
ABIS="arm64-v8a:arm64:aarch64-linux-android21
x86_64:amd64:x86_64-linux-android21
x86:386:i686-linux-android21"

for entry in $ABIS; do
  abi="${entry%%:*}"
  rest="${entry#*:}"
  goarch="${rest%%:*}"
  prefix="${rest#*:}"

  CC="$TC/bin/$prefix-clang"
  [ -x "$CC" ] || CC="$TC/bin/$prefix-clang.cmd"

  echo "building $abi ($goarch) with $prefix"
  GOOS=android GOARCH="$goarch" CGO_ENABLED=1 \
  CC="$CC" \
  CGO_CFLAGS="--sysroot=$SYSROOT -I${PWD}/jniinc/linux -I${PWD}/jniinc/win32 -I$JDK_INCLUDE" \
  CGO_LDFLAGS="" \
    go build -buildmode=c-shared -o "out_$abi.so" .
done

echo
for entry in $ABIS; do
  abi="${entry%%:*}"
  echo "  out_$abi.so  $(wc -c < "out_$abi.so") bytes"
done