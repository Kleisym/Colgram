param(
    [string]$LlamaSrc = "F:\brom\Colgram\build-tmp\llama.cpp",
    [string]$NdkDir = "C:\android-sdk\ndk\26.1.10909125",
    [string]$CmakeBin = "C:\android-sdk\cmake\3.22.1\bin\cmake.exe",
    [string]$NinjaBin = "C:\android-sdk\cmake\3.22.1\bin\ninja.exe",
    [string[]]$Abis = @("x86_64", "arm64-v8a")
)

$ErrorActionPreference = "Stop"

$cppDir = "F:\brom\Colgram\vendor\colgram-llama\cpp"
$buildRoot = "F:\brom\Colgram\build-tmp\llama-android-build"
$outRoot = "F:\brom\Colgram\vendor\colgram-llama\src\main\jniLibs"
$toolchain = Join-Path $NdkDir "build\cmake\android.toolchain.cmake"
$stripBin = Join-Path $NdkDir "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"

if (-not (Test-Path $LlamaSrc)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LlamaSrc) | Out-Null
    & git clone --depth 1 https://github.com/ggml-org/llama.cpp.git $LlamaSrc
    if ($LASTEXITCODE -ne 0) { throw "Failed to clone llama.cpp into $LlamaSrc" }
}
if (-not (Test-Path $NinjaBin)) {
    $NinjaBin = (Get-Command ninja -ErrorAction Stop).Source
}

foreach ($abi in $Abis) {
    Write-Host "=== Building libcolgram_llama.so for $abi ==="
    $bdir = Join-Path $buildRoot $abi
    $jdir = Join-Path $outRoot $abi
    New-Item -ItemType Directory -Force -Path $bdir | Out-Null
    New-Item -ItemType Directory -Force -Path $jdir | Out-Null

    & $CmakeBin `
        -S $cppDir `
        -B $bdir `
        -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$NinjaBin" `
        "-DCMAKE_TOOLCHAIN_FILE=$toolchain" `
        "-DANDROID_ABI=$abi" `
        "-DANDROID_PLATFORM=android-24" `
        "-DCMAKE_BUILD_TYPE=Release" `
        "-DLLAMA_SRC=$LlamaSrc"

    if ($LASTEXITCODE -ne 0) {
        throw "CMake configure failed for $abi (exit $LASTEXITCODE)"
    }

    & $CmakeBin --build $bdir --target colgram_llama -j 4
    if ($LASTEXITCODE -ne 0) {
        throw "CMake build failed for $abi (exit $LASTEXITCODE)"
    }

    $builtSo = Join-Path $bdir "libcolgram_llama.so"
    if (-not (Test-Path $builtSo)) {
        throw "Expected .so not found at $builtSo"
    }

    $destSo = Join-Path $jdir "libcolgram_llama.so"
    Copy-Item -Force $builtSo $destSo
    if (Test-Path $stripBin) {
        & $stripBin --strip-unneeded $destSo
    }
    $sizeMb = [math]::Round((Get-Item $destSo).Length / 1MB, 2)
    Write-Host "Built $destSo ($sizeMb MB)"
}
