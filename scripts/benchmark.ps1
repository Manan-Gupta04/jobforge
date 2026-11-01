# Throughput benchmark: queues N echo jobs and measures how long the cluster takes to drain them.
#   .\scripts\benchmark.ps1 -Count 5000 -BaseUrl http://localhost:8080
param(
    [int]$Count = 5000,
    [string]$BaseUrl = "http://localhost:8080"
)

$stats = Invoke-RestMethod "$BaseUrl/api/v1/stats"
$before = [long]$stats.byStatus.SUCCEEDED
Write-Host "Node $($stats.nodeId): $($stats.workers) workers, $($stats.nodes.Count) node(s) live"

$body = @{ type = "echo"; count = $Count; payload = @{ bench = $true } } | ConvertTo-Json
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$r = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/jobs/bulk" -ContentType "application/json" -Body $body
Write-Host "Queued $($r.submitted) jobs in $($r.elapsedMs) ms"

do {
    Start-Sleep -Milliseconds 200
    $s = Invoke-RestMethod "$BaseUrl/api/v1/stats"
    $done = [long]$s.byStatus.SUCCEEDED - $before
} while ($done -lt $Count)
$sw.Stop()

$secs = [math]::Round($sw.Elapsed.TotalSeconds, 2)
Write-Host ("Drained {0} jobs in {1}s  ->  {2} jobs/s" -f $Count, $secs, [math]::Round($Count / $secs))
