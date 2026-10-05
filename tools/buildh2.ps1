$env:GOTOOLCHAIN='auto'
$env:NDK_ROOT='C:\Colgram\tools\ndk\android-ndk-r27c'
$TC = "$env:NDK_ROOT\toolchains\llvm\prebuilt\windows-x86_64"
$JDK = 'C:\Colgram\tools\jdk17\jdk-17.0.20.1+1\include'
$env:GOPATH='C:\Colgram\tools\gopath'
$env:GOCACHE='C:\Colgram\tools\gocache'
$env:GOOS='android'
$env:CGO_ENABLED='1'
$env:CGO_LDFLAGS=''
$src='C:\Colgram\tools\warpgo\native'
Set-Location $src
$abis = @(
  @{arch='arm64'; pfx='aarch64-linux-android21'; name='arm64'; extra=''},
  @{arch='386';   pfx='i686-linux-android21';   name='x86';   extra=''},
  @{arch='amd64'; pfx='x86_64-linux-android21'; name='x86_64'; extra='-fdeclspec'}
)
foreach ($a in $abis) {
  $env:GOARCH = $a.arch
  $env:CC = "$TC\bin\$($a.pfx)-clang.cmd"
  $env:CGO_CFLAGS = "--sysroot=$TC\sysroot $($a.extra) -I$src\jniinc\linux -I$src\jniinc\win32 -I$JDK"
  $out = "h2_$($a.name).so"
  # Remove the previous library first.
  #
  # A failed `go build` does not clear its -o target, and this script went on to print that target's size
  # as though it had just been produced:
  #
  #   # warpverdict
  #   .\main.go:2627:41: cannot use string(...) as []byte value in argument to head
  #   h2_x86_64.so 13225800        <- the build failed; that is the file from an hour earlier
  #
  # So a compile error read as a successful build of a library that was then staged into the APK and
  # measured on the device, and every measurement was of the previous build. The failure is now a failure:
  # the old file is deleted, the exit code is honoured, and a non-zero exit stops the script.
  if (Test-Path $out) { Remove-Item $out -Force }
  $err = & C:\Colgram\tools\go\bin\go.exe build -buildmode=c-shared -o $out . 2>&1
  $code = $LASTEXITCODE
  $err = $err |
      Select-String -NotMatch '__stdcall|JNICALL|expanded from|jni.h|__declspec|JNIEXPORT|JNIIMPORT'
  if ($err) { $err | Out-String }
  if ($code -ne 0 -or -not (Test-Path $out)) {
    "FAILED $out (go build exit $code)"
    exit 1
  }
  "$out $((Get-Item $out).Length)"
}
