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
      at = (Get-Date).ToUniversalTime().ToString("o")
      output = $rel
      markets = $a.state.markets
      fills = $a.state.fills
      cycles = $a.state.cycles
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
  Start-Sleep -Seconds ($IntervalMinutes * 60)
}
