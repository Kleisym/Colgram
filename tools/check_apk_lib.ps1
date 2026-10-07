# Fails if the APK's libcolgrammasque.so is not the one that was just built and staged.
#
# A build can report BUILD SUCCESSFUL and still ship the previous library. `--rerun-tasks` was already
# past `mergeDebugNativeLibs` when the new .so was staged into jniLibs, so the package step took the
# merge that was on disk and every measurement afterwards was of the build before it. The log said
# nothing wrong with it; only comparing the entry in the zip against the file on disk did.
param(
  [string]$Apk = 'C:\Colgram\Telegram-Src\TMessagesProj_AppTests\build\outputs\apk\afat\debug\TMessagesProj_AppTests-afat-debug.apk',
  [string]$SourceDir = 'C:\Colgram\tools\warpgo\native'
)

$map = @{ 'arm64' = 'arm64-v8a'; 'x86' = 'x86'; 'x86_64' = 'x86_64' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
try {
  $bad = 0
  # afat ships x86_64 alone; all three ABIs live in the fat APK's sibling build, not this one.
  foreach ($abi in @('x86_64')) {
    $want = (Get-Item (Join-Path $SourceDir "h2_$abi.so")).Length
    $entry = $zip.Entries | Where-Object { $_.FullName -eq "lib/$($map[$abi])/libcolgrammasque.so" }
    if (-not $entry) { "MISSING  lib/$($map[$abi])/libcolgrammasque.so"; $bad++; continue }
    if ($entry.Length -ne $want) {
      "STALE    lib/$($map[$abi])/libcolgrammasque.so  apk=$($entry.Length) built=$want"
      $bad++
    } else {
      "ok       lib/$($map[$abi])/libcolgrammasque.so  $want bytes"
    }
  }
} finally {
  $zip.Dispose()
}
if ($bad -gt 0) {
  'The APK does not carry the library that was just built. Re-run assembleAfatDebug without --rerun-tasks.'
  exit 1
}
'The APK carries the current library.'
