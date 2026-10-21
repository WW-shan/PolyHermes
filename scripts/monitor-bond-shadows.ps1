param(
  [string]$Outputs = "build/research/event-maker-bond-scalp-3c-geo-9597-shadow-7d-20261021,build/research/event-maker-bond-scalp-2c-geo-97099-ladder4-shadow-7d-20261021,build/research/event-maker-bond-scalp-2c-geo-97099-flatten24-shadow-7d-20261021,build/research/event-maker-bond-scalp-2c-geo-97099-flatten24-500-shadow-7d-20261021,build/research/event-maker-bond-scalp-3c-geo-97099-flatten24-500-shadow-7d-20261021,build/research/event-maker-bond-scalp-3c-geo-97099-taker00c-shadow-7d-20261021,build/research/event-maker-bond-scalp-2c-geo-97099-taker-shadow-7d-20261021,build/research/event-maker-bond-scalp-2c-geo-9597-dip24h-taker-shadow-7d-20261021,build/research/event-maker-bond-scalp-2c-geo-97099-mom1-taker-shadow-7d-20261021,build/research/event-maker-bond-scalp-1c-geo-97099-mom1-taker-shadow-7d-20261021,build/research/event-maker-bond-scalp-2c-geo-97099-off10-size1k-shadow-7d-20261021",
  [int]$IntervalMinutes = 30,
  [string]$Log = "build/research/bond-shadow-monitor.log",
  [string]$Series = "build/research/bond-shadow-audit-series.jsonl",
  [string]$EtaOutput = "build/research/bond-candidate-eta-latest.json",
  [switch]$Once
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $repo "backend"
$logPath = Join-Path $backend $Log
$seriesPath = Join-Path $backend $Series
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $logPath) | Out-Null

function Write-Log([string]$message) {
  $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"), $message
  Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
  Write-Output $line
}

$dirs = $Outputs.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
Write-Log ("monitor armed: interval={0}m outputs={1}" -f $IntervalMinutes, ($dirs -join ';'))

while ($true) {
  try {
    $etaPath = Join-Path $backend $EtaOutput
    & (Join-Path $PSScriptRoot "report-bond-candidate-eta.ps1") -Output $etaPath | Out-Null
    $eta = Get-Content -LiteralPath $etaPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $nextEta = @($eta.nextFutureCandidates) | Select-Object -First 1
    [pscustomobject]@{
      type = "candidate_eta"
      at = (Get-Date).ToUniversalTime().ToString("o")
      scanned = $eta.scanned
      eligibleNow = @($eta.eligibleNow).Count
      nextFutureCandidates = @($eta.nextFutureCandidates).Count
      highPriceTooClose = @($eta.highPriceTooClose).Count
      nextFutureHours = if ($nextEta) { $nextEta.hoursUntilFutureWindow } else { $null }
      nextFutureQuestion = if ($nextEta) { $nextEta.question } else { $null }
    } | ConvertTo-Json -Compress | Add-Content -LiteralPath $seriesPath -Encoding UTF8
  } catch {
    Write-Log "candidate eta failed: $($_.Exception.Message)"
  }
  foreach ($rel in $dirs) {
    $dir = Join-Path $backend $rel
    $runPath = Join-Path $dir "run.json"
    $armPid = $null
    $processAlive = $false
    if (Test-Path -LiteralPath $runPath) {
      try {
        $run = Get-Content -LiteralPath $runPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $armPid = [int]$run.pid
        $proc = Get-Process -Id $armPid -ErrorAction SilentlyContinue
        $processAlive = ($null -ne $proc -and $proc.ProcessName -eq "java")
      } catch { }
    }
    if (-not (Test-Path (Join-Path $dir "state.json"))) {
      $waitingStatus = if ($processAlive) { "WAITING" } elseif ($armPid) { "DEAD" } else { "MISSING" }
      [pscustomobject]@{
        type = "arm"
        at = (Get-Date).ToUniversalTime().ToString("o")
        output = $rel
        status = $waitingStatus
        pid = $armPid
        processAlive = $processAlive
        markets = $null
        fills = $null
        cycles = $null
        settlements = $null
        settlementLosses = $null
        pendingSettlements = $null
        activeOrders = $null
        inventoryShares = $null
        realizedPnl = $null
        realizedRoi = $null
        markout60Count = $null
        markout60Mean = $null
        markout300Mean = $null
        markout60NegativeShare = $null
        flags = $waitingStatus
      } | ConvertTo-Json -Compress | Add-Content -LiteralPath $seriesPath -Encoding UTF8
      Write-Log "no state yet: $rel status=$waitingStatus pid=$armPid alive=$processAlive"
      continue
    }
    $audit = Join-Path $dir "audit-latest.json"
    & node (Join-Path $PSScriptRoot "audit-bond-shadow.cjs") --dir $dir --output $audit 2>> $logPath | Out-Null
    if ($LASTEXITCODE -ne 0) {
      Write-Log "audit failed: $rel"
      continue
    }
    $a = Get-Content -LiteralPath $audit -Raw | ConvertFrom-Json
    $markoutPath = Join-Path $dir "markouts.csv"
    $m60Count = 0
    $m60Mean = $null
    $m300Mean = $null
    $m60NegShare = $null
    if (Test-Path $markoutPath) {
      $mrows = @(Import-Csv -LiteralPath $markoutPath)
      $m60 = @($mrows | Where-Object { [string]$_.horizon_seconds -eq "60" })
      $m300 = @($mrows | Where-Object { [string]$_.horizon_seconds -eq "300" })
      $m60Count = $m60.Count
      if ($m60.Count -gt 0) {
        $vals = @($m60 | ForEach-Object { [double]$_.markout_pnl })
        $m60Mean = ($vals | Measure-Object -Average).Average
        $m60NegShare = @($vals | Where-Object { $_ -lt 0 }).Count / $vals.Count
      }
      if ($m300.Count -gt 0) {
        $vals300 = @($m300 | ForEach-Object { [double]$_.markout_pnl })
        $m300Mean = ($vals300 | Measure-Object -Average).Average
      }
    }
    $record = [pscustomobject]@{
      type = "arm"
      at = (Get-Date).ToUniversalTime().ToString("o")
      output = $rel
      status = "ACTIVE"
      pid = $armPid
      processAlive = $processAlive
      markets = $a.state.markets
      fills = $a.state.fills
      cycles = $a.state.cycles
      settlements = $a.state.settlements
      settlementLosses = $a.state.settlementLosses
      pendingSettlements = $a.state.pendingSettlements
      activeOrders = $a.state.activeOrders
      inventoryShares = $a.state.inventoryShares
      realizedPnl = $a.state.realizedPnl
      realizedRoi = $a.state.realizedRoi
      markout60Count = $m60Count
      markout60Mean = $m60Mean
      markout300Mean = $m300Mean
      markout60NegativeShare = $m60NegShare
      flags = ($a.flags -join ';')
    }
    $record | ConvertTo-Json -Compress | Add-Content -LiteralPath $seriesPath -Encoding UTF8
  }
  if ($Once) { break }
  Start-Sleep -Seconds ($IntervalMinutes * 60)
}
