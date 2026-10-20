param(
  [int64]$DurationMs = 0,
  [string]$Suffix = "resume-20261020",
  [string]$Proxy = "http://127.0.0.1:10808"
)

$ErrorActionPreference = "Stop"
$base = Split-Path -Parent $PSScriptRoot
if ($DurationMs -le 0) {
  $end = [datetime]::Parse("2026-10-21T06:50:16Z").ToUniversalTime()
  $DurationMs = [int64]($end - [datetime]::UtcNow).TotalMilliseconds
}
if ($DurationMs -lt 600000) { $DurationMs = 600000 }

$env:HTTPS_PROXY = $Proxy
$env:HTTP_PROXY = $Proxy
$env:NODE_USE_ENV_PROXY = "1"
$node = (Get-Command node).Source

$common = @(
  "--max-markets-per-tag", "1000",
  "--max-groups", "1000",
  "--min-volume", "1000",
  "--poll-interval-ms", "15000",
  "--discovery-interval-ms", "600000",
  "--duration-ms", "$DurationMs",
  "--max-watch-groups", "30",
  "--min-edge-cents", "0.5",
  "--max-book-age-ms", "10000",
  "--confirm-delay-ms", "1000"
)

$runs = @(
  @{ Name = "v6"; Tags = "politics,geopolitics"; Dir = "backend\build\research\complete-set-monitor-24h-v6-$Suffix" },
  @{ Name = "crypto-finance-v3"; Tags = "crypto,finance"; Dir = "backend\build\research\complete-set-monitor-24h-crypto-finance-v3-$Suffix" }
)

$results = @()
$stamp = (Get-Date).ToString("HHmmss")
foreach ($run in $runs) {
  $dir = Join-Path $base $run.Dir
  if (Test-Path (Join-Path $dir "run.json")) {
    $run.Dir = "$($run.Dir)-$stamp"
    $dir = Join-Path $base $run.Dir
  }
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $args = @("scripts/monitor-event-complete-set-arbitrage.mjs", "--tags", $run.Tags) + $common + @("--output-dir", $dir)
  $proc = Start-Process -FilePath $node -ArgumentList $args -WorkingDirectory $base -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $dir "stdout.log") `
    -RedirectStandardError (Join-Path $dir "stderr.log") -PassThru
  $results += [pscustomobject]@{ name = $run.Name; pid = $proc.Id; outputDir = $dir }
}

$pidsPath = Join-Path $base "backend\build\research\complete-set-monitor-pids-$Suffix.json"
$payload = [pscustomobject]@{ startedAt = (Get-Date).ToUniversalTime().ToString("o"); durationMs = $DurationMs; proxy = $Proxy; runs = $results }
$payload | ConvertTo-Json -Depth 5 | Set-Content -Path $pidsPath -Encoding UTF8
$payload | ConvertTo-Json -Depth 5
