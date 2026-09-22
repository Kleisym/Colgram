# Colgram — which Telegram DC addresses actually answer on this network?
# Scans TCP 443 across Telegram's owned /24s in parallel and prints the live ones.
# Run from the same network the phone/emulator is on; egress behaviour is identical.

$subnets = @(
    "149.154.167.", "149.154.175.", "149.154.160.", "149.154.164.",
    "91.108.4.", "91.108.8.", "91.108.12.", "91.108.16.", "91.108.56.", "91.108.152.",
    "185.76.151.", "5.142.99.", "5.142.134.", "31.13.32.", "31.13.68.", "31.13.70.",
    "67.198.55.", "67.228.98.", "109.239.140.", "139.45.54.", "149.154.168."
)

$targets = foreach ($s in $subnets) { 1..254 | ForEach-Object { "$s$_" } }
Write-Output "scanning $($targets.Count) addresses on tcp/443"

$live = [System.Collections.Generic.List[string]]::new()
$timeoutMs = 900

# .NET async connects in batches; far faster than one-socket-at-a-time loops.
$i = 0
while ($i -lt $targets.Count) {
    $batch = $targets[$i..([Math]::Min($i + 199, $targets.Count - 1))]
    $i += 200
    $jobs = foreach ($ip in $batch) {
        $c = New-Object System.Net.Sockets.TcpClient
        $async = $c.BeginConnect($ip, 443, $null, $null)
        [pscustomobject]@{ ip = $ip; client = $c; wait = $async.AsyncWaitHandle }
    }
    foreach ($j in $jobs) {
        try {
            if ($j.wait.WaitOne($timeoutMs) -and $j.client.Connected) {
                $live.Add($j.ip)
                Write-Output "LIVE  $($j.ip):443"
            }
        } catch { }
        try { $j.client.Close() } catch { }
    }
}

Write-Output "---"
Write-Output "live count: $($live.Count)"
$live | Sort-Object | ForEach-Object { Write-Output $_ }
