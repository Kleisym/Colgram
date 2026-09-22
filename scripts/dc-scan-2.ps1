# Colgram — second pass: the DC ranges the first sweep missed.
# The first run (scripts/dc-scan.ps1) covered 21 /24s and found 22 live addresses, all of which
# turned out to serve nginx rather than MTProto. These ranges were left out of it and are exactly
# where the API servers live: 95.161.76.0/24 is DC2's alternate API host, 149.154.171.0/24 is DC5,
# 91.108.20/24 and .24 are DC media/API ranges, 185.76.150.0/24 sits beside DC5's 185.76.151.
# Ports: 443 and 80, because an MTProto server answers on both and a block may be port-scoped.

$ranges = @(
    "95.161.76.", "149.154.171.", "149.154.172.", "91.108.20.", "91.108.24.",
    "91.108.32.", "91.108.36.", "185.76.150.", "149.154.160.", "149.154.164.",
    "149.154.168.", "149.154.166.", "45.64.100.", "64.177.91.", "103.212.102.",
    "139.45.55.", "149.154.167.", "91.108.56.", "149.154.175."
)
$ports = @(443, 80)

$targets = foreach ($r in $ranges) { foreach ($p in $ports) { 1..254 | ForEach-Object { "$r$_`:$p" } } }
Write-Output "scanning $($targets.Count) address:port pairs"

$live = [System.Collections.Generic.List[string]]::new()
$timeoutMs = 900

$i = 0
while ($i -lt $targets.Count) {
    $batch = $targets[$i..([Math]::Min($i + 199, $targets.Count - 1))]
    $i += 200
    $jobs = foreach ($spec in $batch) {
        $parts = $spec.Split(":")
        $c = New-Object System.Net.Sockets.TcpClient
        try {
            $async = $c.BeginConnect($parts[0], [int]$parts[1], $null, $null)
            [pscustomobject]@{ spec = $spec; client = $c; wait = $async.AsyncWaitHandle }
        } catch {
            try { $c.Close() } catch { }
        }
    }
    foreach ($j in $jobs) {
        if ($null -eq $j) { continue }
        try {
            if ($j.wait.WaitOne($timeoutMs) -and $j.client.Connected) {
                $live.Add($j.spec)
                Write-Output "LIVE  $($j.spec)"
            }
        } catch { }
        try { $j.client.Close() } catch { }
    }
}

Write-Output "---"
Write-Output "live count: $($live.Count)"
$live | Sort-Object | ForEach-Object { Write-Output $_ }
