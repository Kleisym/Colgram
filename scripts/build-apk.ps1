param(
    [ValidateSet('arm64-v8a','armeabi-v7a','x86','x86_64','universal')]
    [string]$Abi = 'arm64-v8a',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$Output = '',
    [string]$BuildStamp = 'mumu-routing-20261114'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$upstream = Join-Path $repo 'Telegram-Src'
$base = 'f2908b14133bbffbf7ab04f641ecb5bfaf533242'
if (-not $JavaHome -and (Test-Path -LiteralPath 'C:\colgram-tools\jdk-17.0.20.1+1')) { $JavaHome = 'C:\colgram-tools\jdk-17.0.20.1+1' }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk -and (Test-Path -LiteralPath 'C:\android-sdk')) { $AndroidSdk = 'C:\android-sdk' }
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'Set JAVA_HOME to JDK 17' }
if (-not $AndroidSdk -or -not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms\android-36\android.jar'))) { throw 'Set ANDROID_HOME to an SDK containing platform 36 and build-tools 36.0.0' }
if (-not $Output) { $Output = Join-Path $repo ("dist\Colgram-$Abi-debug.apk") }
if (-not (Test-Path -LiteralPath $upstream)) {
    & git init $upstream
    if ($LASTEXITCODE -ne 0) { throw 'Upstream initialization failed' }
    & git -C $upstream remote add origin 'https://github.com/DrKLO/Telegram.git'
    if ($LASTEXITCODE -ne 0) { throw 'Upstream remote setup failed' }
    & git -C $upstream fetch --depth 1 origin $base
    if ($LASTEXITCODE -ne 0) { throw 'Upstream download failed' }
    & git -C $upstream checkout --detach FETCH_HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Upstream checkout failed' }
}
$actual = (& git -C $upstream rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $actual -ne $base) { throw "Expected Telegram base $base; found $actual" }
$saved = @{}
foreach ($name in @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','COLGRAM_BUILD_STAMP','PYTHONIOENCODING')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    $env:COLGRAM_BUILD_STAMP = $BuildStamp
    $env:PYTHONIOENCODING = 'utf-8'
    Set-Content -LiteralPath (Join-Path $upstream 'local.properties') -Value ('sdk.dir=' + $AndroidSdk.Replace('\','/')) -Encoding ascii
    & python (Join-Path $PSScriptRoot 'apply-patches.py') $upstream
    if ($LASTEXITCODE -ne 0) { throw 'Colgram patch injection failed' }
    $arguments = @('-cp', 'gradle/wrapper/gradle-wrapper.jar', 'org.gradle.wrapper.GradleWrapperMain',
        '-I', (Join-Path $PSScriptRoot 'target-abis.gradle'), ':TMessagesProj_AppStandalone:assembleAfatDebug',
        '--no-daemon', '--no-build-cache', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8')
    if ($Abi -ne 'universal') { $arguments += "-PcolgramAbis=$Abi" }
    Push-Location -LiteralPath $upstream
    try {
        & (Join-Path $JavaHome 'bin\java.exe') @arguments
        if ($LASTEXITCODE -ne 0) { throw 'Android APK build failed' }
    } finally { Pop-Location }
    $apk = Join-Path $upstream 'TMessagesProj_AppStandalone\build\outputs\apk\afat\debug\app.apk'
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'Gradle produced no debug APK' }
    $destination = [IO.Path]::GetFullPath($Output)
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath $apk -Destination $destination -Force
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "APK BUILD: PASS abi=$Abi sha256=$hash path=$destination"
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
