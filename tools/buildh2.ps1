param(
    [string]$NdkRoot = $env:ANDROID_NDK_HOME,
    [string]$Go = 'go',
    [string]$OutputRoot = ''
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$source = Join-Path $PSScriptRoot 'warpgo\native'
if (-not $NdkRoot) { $NdkRoot = $env:NDK_ROOT }
if (-not $NdkRoot) {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if ($sdk) {
        $available = Get-ChildItem -LiteralPath (Join-Path $sdk 'ndk') -Directory | Sort-Object { [version]$_.Name }
        if ($available) { $NdkRoot = $available[-1].FullName }
    }
}
if (-not $NdkRoot) { throw 'Set ANDROID_NDK_HOME/NDK_ROOT or pass -NdkRoot' }
$toolchain = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'
if (-not (Test-Path -LiteralPath $toolchain -PathType Container)) { throw "NDK toolchain missing: $toolchain" }
if (-not $OutputRoot) { $OutputRoot = Join-Path $repo 'colgram-core\src\main\jniLibs' }
$Go = (Get-Command $Go -ErrorAction Stop).Source
$stage = Join-Path $source 'out'
New-Item -ItemType Directory -Path $stage -Force | Out-Null
$abis = @(
    @{ Arch = 'arm64'; Prefix = 'aarch64-linux-android24'; Abi = 'arm64-v8a' },
    @{ Arch = '386'; Prefix = 'i686-linux-android24'; Abi = 'x86' },
    @{ Arch = 'amd64'; Prefix = 'x86_64-linux-android24'; Abi = 'x86_64' }
)
$saved = @{}
foreach ($name in @('GOOS','GOARCH','CGO_ENABLED','CC','CGO_CFLAGS','CGO_LDFLAGS','GOTOOLCHAIN')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Push-Location -LiteralPath $source
try {
    $env:GOOS = 'android'
    $env:CGO_ENABLED = '1'
    $env:CGO_CFLAGS = ''
    $env:CGO_LDFLAGS = ''
    $env:GOTOOLCHAIN = 'auto'
    foreach ($abi in $abis) {
        $env:GOARCH = $abi.Arch
        $env:CC = Join-Path $toolchain ($abi.Prefix + '-clang.cmd')
        if (-not (Test-Path -LiteralPath $env:CC -PathType Leaf)) { throw "Cross compiler missing: $env:CC" }
        $target = Join-Path $stage ('libcolgrammasque-' + $abi.Abi + '.so')
        & $Go build -trimpath -buildmode=c-shared -o $target .
        if ($LASTEXITCODE -ne 0) { throw "Native build failed for $($abi.Abi), exit $LASTEXITCODE" }
        $bytes = [IO.File]::ReadAllBytes($target)
        if ($bytes.Length -lt 1024 -or $bytes[0] -ne 127 -or [Text.Encoding]::ASCII.GetString($bytes, 1, 3) -ne 'ELF') {
            throw "Invalid ELF output for $($abi.Abi)"
        }
        $destination = Join-Path $OutputRoot $abi.Abi
        New-Item -ItemType Directory -Path $destination -Force | Out-Null
        $library = Join-Path $destination 'libcolgrammasque.so'
        Copy-Item -LiteralPath $target -Destination $library -Force
        $hash = (Get-FileHash -LiteralPath $library -Algorithm SHA256).Hash.ToLowerInvariant()
        Write-Output "NATIVE $($abi.Abi): PASS bytes=$($bytes.Length) sha256=$hash"
    }
} finally {
    Pop-Location
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
