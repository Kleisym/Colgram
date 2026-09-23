# Colgram — the Telegram ranges the first two sweeps left out.
# dc-scan.ps1 covered the well-known /24s; dc-scan-2.ps1 added 91.108.24 and .32 (both largely
# live, both nginx). Telegram announces /22 aggregates, so the neighbours of a live /24 are just
# as likely to be reachable - and one of them may be an API server rather than a web front.
# This pass walks the remaining neighbours of every Telegram block on 443.

$ranges = @(
    "91.108.5.", "91.108.6.", "91.108.7.", "91.108.9.", "91.108.10.", "91.108.11.",
    "91.108.13.", "91.108.14.", "91.108.15.", "91.108.17.", "91.108.18.", "91.108.19.",
    "91.108.21.", "91.108.22.", "91.108.23.", "91.108.25.", "91.108.26.", "91.108.27.",
    "91.108.33.", "91.108.34.", "91.108.35.", "91.108.57.", "91.108.58.", "91.108.59.",
    "149.154.161.", "149.154.162.", "149.154.163.", "149.154.165.", "149.154.169.",
    "149.154.170.", "149.154.173.", "149.154.174.", "185.76.148.", "185.76.149.",
    "5.142.98.", "5.142.100.", "95.161.77.", "95.161.78."
)

$targets = foreach ($r in $ranges) { 1..254 | ForEach-Object { "$r$_" } }
Write-Output "scanning $($targets.Count) addresses on tcp/443"

$live = [System.Collections.Generic.List[string]]::new()
$timeoutMs = 800

$i = 0
while ($i -lt $targets.Count) {
    $batch = $targets[$i..([Math]::Min($i + 249, $targets.Count - 1))]
    $i += 250
    $jobs = foreach ($ip in $batch) {
        $c = New-Object System.Net.Sockets.TcpClient
        try {
            $async = $c.BeginConnect($ip, 443, $null, $null)
            [pscustomobject]@{ ip = $ip; client = $c; wait = $async.AsyncWaitHandle }
        } catch {
            try { $c.Close() } catch { }
        }
    }
    foreach ($j in $jobs) {
        if ($null -eq $j) { continue }
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
