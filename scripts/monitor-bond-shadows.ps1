param(
  [string]$Outputs = "build/research/event-maker-bond-scalp-shadow-7d-20261021-v2,build/research/event-maker-bond-scalp-1c-shadow-7d-20261021",
  [int]$IntervalMinutes = 30,
  [string]$Log = "build/research/bond-shadow-monitor.log",
  [string]$Series = "build/research/bond-shadow-audit-series.jsonl"
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
  foreach ($rel in $dirs) {
    $dir = Join-Path $backend $rel
    if (-not (Test-Path (Join-Path $dir "state.json"))) {
      Write-Log "skip missing state: $rel"
      continue
    }
    $audit = Join-Path $dir "audit-latest.json"
    & node (Join-Path $PSScriptRoot "audit-bond-shadow.cjs") --dir $dir --output $audit 2>> $logPath | Out-Null
    if ($LASTEXITCODE -ne 0) {
      Write-Log "audit failed: $rel"
      continue
    }
    $a = Get-Content -LiteralPath $audit -Raw | ConvertFrom-Json
    $record = [pscustomobject]@{
      at = (Get-Date).ToUniversalTime().ToString("o")
      output = $rel
      markets = $a.state.markets
      fills = $a.state.fills
      cycles = $a.state.cycles
      activeOrders = $a.state.activeOrders
      inventoryShares = $a.state.inventoryShares
      realizedPnl = $a.state.realizedPnl
      realizedRoi = $a.state.realizedRoi
      flags = ($a.flags -join ';')
    }
    $record | ConvertTo-Json -Compress | Add-Content -LiteralPath $seriesPath -Encoding UTF8
  }
  Start-Sleep -Seconds ($IntervalMinutes * 60)
}
