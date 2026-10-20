param(
  [Parameter(Mandatory = $true)][string]$Pids,
  [Parameter(Mandatory = $true)][string]$Dirs,
  [string]$Output = "build/research/complete-set-monitor-final-audit.json",
  [string]$Log = "build/research/complete-set-monitor-watch.log"
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $repo "backend"
$logPath = Join-Path $backend $Log
$outputPath = Join-Path $backend $Output
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $logPath) | Out-Null

function Write-Log([string]$message) {
  $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"), $message
  Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
  Write-Output $line
}

$pidList = @($Pids.Split(',') | ForEach-Object { [int]$_.Trim() })
$dirList = @($Dirs.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
Write-Log ("complete-set monitor watch armed: pids={0} dirs={1}" -f ($pidList -join ','), ($dirList -join ';'))

while ($true) {
  $alive = @($pidList | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
  if ($alive.Count -eq 0) { break }
  Start-Sleep -Seconds 60
}
Write-Log "all monitored processes exited; writing final audit"

$results = @()
foreach ($rel in $dirList) {
  $dir = Join-Path $backend $rel
  $summaryPath = Join-Path $dir "monitor-summary.json"
  $oppPath = Join-Path $dir "opportunities.jsonl"
  $summary = $null
  if (Test-Path $summaryPath) { $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json }
  $opps = @()
  if (Test-Path $oppPath) {
    foreach ($line in (Get-Content -LiteralPath $oppPath)) {
      if (-not $line.Trim()) { continue }
      try { $opps += ($line | ConvertFrom-Json) } catch { }
    }
  }
  $actionable = @($opps | Where-Object { $_.best -and $_.best.actionable -eq $true }).Count
  $riskFreeCount = 0
  $nakedRiskCount = 0
  foreach ($o in $opps) {
    $b = $o.best
    if ($null -eq $b) { continue }
    $askAct = $b.askActionable -eq $true
    $bidAct = $b.bidActionable -eq $true
    if (-not ($askAct -or $bidAct)) { continue }
    $askOk = $askAct -and ($null -eq $b.askRiskWorstPnlUsd -or [double]$b.askRiskWorstPnlUsd -ge 0)
    $bidOk = $bidAct -and ($null -eq $b.bidRiskWorstPnlUsd -or [double]$b.bidRiskWorstPnlUsd -ge 0)
    if ($askOk -or $bidOk) { $riskFreeCount++ } else { $nakedRiskCount++ }
  }
  $kinds = @{}
  foreach ($o in $opps) { $k = [string]$o.kind; if ($k) { if ($kinds.ContainsKey($k)) { $kinds[$k]++ } else { $kinds[$k] = 1 } } }
  $reasons = @{}
  foreach ($o in $opps) {
    $r = $null
    if ($o.initial) {
      if ($o.initial.askReason) { $r = [string]$o.initial.askReason }
      elseif ($o.initial.bidReason) { $r = [string]$o.initial.bidReason }
      elseif ($o.initial.noBasketReason) { $r = [string]$o.initial.noBasketReason }
    }
    if ($r) { if ($reasons.ContainsKey($r)) { $reasons[$r]++ } else { $reasons[$r] = 1 } }
  }
  $verdict = if ($riskFreeCount -gt 0) { "RISK_FREE_ACTIONABLE_FOUND" } elseif ($nakedRiskCount -gt 0) { "PARTIAL_BASKET_NAKED_RISK_ONLY" } elseif ($null -eq $summary) { "MISSING_SUMMARY" } else { "NO_ACTIONABLE_OPPORTUNITY" }
  $results += [pscustomobject]@{
    output = $rel
    startedAt = if ($summary) { $summary.startedAt } else { $null }
    lastUpdatedAt = if ($summary) { $summary.lastUpdatedAt } else { $null }
    durationMs = if ($summary) { $summary.durationMs } else { $null }
    pollCycles = if ($summary) { $summary.pollCycles } else { $null }
    discoveryCycles = if ($summary) { $summary.discoveryCycles } else { $null }
    errorRows = if ($summary) { $summary.errorRows } else { $null }
    lastPollErrors = if ($summary) { $summary.lastPollErrors } else { $null }
    confirmedEpisodes = if ($summary) { $summary.confirmedEpisodes } else { $null }
    openEpisodes = if ($summary) { $summary.openEpisodes } else { $null }
    closedEpisodes = if ($summary) { $summary.closedEpisodes } else { $null }
    maxConfirmedEdgeCents = if ($summary) { $summary.maxConfirmedEdgeCents } else { $null }
    maxConfirmedProfit = if ($summary) { $summary.maxConfirmedProfit } else { $null }
    opportunities = $opps.Count
    actionable = $actionable
    riskFreeActionable = $riskFreeCount
    nakedRiskActionable = $nakedRiskCount
    opportunityKinds = $kinds
    rejectionReasons = $reasons
    verdict = $verdict
  }
}
$results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $outputPath -Encoding UTF8
Write-Log "final audit written: $outputPath"
