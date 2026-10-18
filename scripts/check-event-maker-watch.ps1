param(
    [string]$OutputDir = "D:\Project\PolyHermes\backend\build\research\event-maker-shadow-watch-20261019"
)

$ErrorActionPreference = "Stop"
$launchFile = Join-Path $OutputDir "watch-process.json"
if (-not (Test-Path -LiteralPath $launchFile)) {
    throw "watch launch file not found: $launchFile"
}
$launch = Get-Content -LiteralPath $launchFile -Raw -Encoding UTF8 | ConvertFrom-Json
$wrapper = Get-Process -Id ([int]$launch.pid) -ErrorAction SilentlyContinue
$outputLeaf = Split-Path $OutputDir -Leaf
$shadow = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match "EventMakerShadowCliKt" -and $_.CommandLine -match [regex]::Escape($outputLeaf) } |
    Select-Object -First 1

Write-Output "output=$OutputDir"
Write-Output "wrapperPid=$($launch.pid) wrapperAlive=$([bool]$wrapper)"
Write-Output "shadowPid=$($shadow.ProcessId) shadowAlive=$([bool]$shadow)"

$attemptFile = Join-Path $OutputDir "discovery-attempts.jsonl"
if (Test-Path -LiteralPath $attemptFile) {
    $attempts = @(Get-Content -LiteralPath $attemptFile -Encoding UTF8 | Where-Object { $_.Trim().Length -gt 0 })
    Write-Output "attempts=$($attempts.Count)"
    if ($attempts.Count -gt 0) {
        Write-Output "lastAttempt=$($attempts[-1])"
    }
}

$summaryFile = Join-Path $OutputDir "summary.json"
$timeoutFile = Join-Path $OutputDir "market-watch-timeout.json"
if (Test-Path -LiteralPath $summaryFile) {
    $summary = Get-Content -LiteralPath $summaryFile -Raw -Encoding UTF8 | ConvertFrom-Json
    Write-Output "state=summary"
    Write-Output "markets=$($summary.markets) fills=$($summary.fills) cycles=$($summary.completedCycles) totalPnl=$($summary.totalPnl) haltedReason=$($summary.haltedReason)"
    exit 0
}
if (Test-Path -LiteralPath $timeoutFile) {
    $timeout = Get-Content -LiteralPath $timeoutFile -Raw -Encoding UTF8 | ConvertFrom-Json
    Write-Output "state=timeout attempts=$($timeout.attempts) stopped=$($timeout.lastDiscoveryStoppedReason) reasons=$($timeout.lastSelectionReasons | ConvertTo-Json -Compress)"
    exit 2
}
if ($wrapper -or $shadow) {
    Write-Output "state=waiting"
    exit 0
}
Write-Output "state=stopped-without-summary-or-timeout"
exit 3