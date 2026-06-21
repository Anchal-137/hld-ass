# =====================================================================
#  Simple load + latency probe for the performance report (Phase 14).
#  - Fires N POST /search across a mix of hot/cold queries (drives batching)
#  - Fires M GET /suggest twice over the same prefixes (cold then warm)
#  - Prints p50/p95/p99 suggest latency and the /stats snapshot
#
#  Usage:  ./scripts/loadtest.ps1 -Base http://localhost:8080 -Searches 5000 -Suggests 2000
# =====================================================================
param(
  [string]$Base = "http://localhost:8080",
  [int]$Searches = 5000,
  [int]$Suggests = 2000
)

$ErrorActionPreference = "Stop"
$prefixes = @("ip","iph","ipho","ja","jav","java","sam","sams","lap","mac","head","air","red","sys","doc","python","react","spring","nike","best")
$queries  = @("iphone","iphone 15","java tutorial","samsung galaxy","macbook air","airpods pro",
              "redis caching","system design interview","python pandas","react hooks","docker tutorial")

Write-Host "== Submitting $Searches searches (drives batch writes) =="
for ($i = 0; $i -lt $Searches; $i++) {
  # Bias toward a small hot set so aggregation has duplicates to fold.
  $q = if ($i % 5 -eq 0) { $queries[(Get-Random -Maximum $queries.Count)] } else { $queries[0] }
  $body = @{ query = $q } | ConvertTo-Json -Compress
  Invoke-RestMethod -Uri "$Base/search" -Method Post -Body $body -ContentType "application/json" | Out-Null
}

function Measure-Suggest($warm) {
  $lat = New-Object System.Collections.Generic.List[double]
  for ($i = 0; $i -lt $Suggests; $i++) {
    $p = $prefixes[(Get-Random -Maximum $prefixes.Count)]
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Invoke-RestMethod -Uri "$Base/suggest?q=$p" -Method Get | Out-Null
    $sw.Stop()
    $lat.Add($sw.Elapsed.TotalMilliseconds)
  }
  $sorted = $lat | Sort-Object
  $p = { param($q) $sorted[[int][math]::Floor($q * ($sorted.Count - 1))] }
  $label = if ($warm) { "WARM" } else { "COLD" }
  Write-Host ("== Suggest latency [$label] (ms): p50={0:N2} p95={1:N2} p99={2:N2} avg={3:N2}" -f (& $p 0.50), (& $p 0.95), (& $p 0.99), ($sorted | Measure-Object -Average).Average)
}

Write-Host "== Probing suggest latency (cold cache) =="
Measure-Suggest $false
Write-Host "== Probing suggest latency (warm cache) =="
Measure-Suggest $true

Write-Host "== /stats =="
Invoke-RestMethod -Uri "$Base/stats" -Method Get | ConvertTo-Json -Depth 5

Write-Host "== consistent-hashing routing sample =="
foreach ($p in @("ip","java","samsung","macbook","redis")) {
  $r = Invoke-RestMethod -Uri "$Base/cache/debug?prefix=$p" -Method Get
  Write-Host ("  prefix='{0}' -> {1}  [{2}]" -f $p, $r.ownerNode, $r.status)
}
