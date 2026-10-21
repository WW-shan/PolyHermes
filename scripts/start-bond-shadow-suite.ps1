param(
  [switch]$Restart,
  [switch]$DryRun,
  [switch]$NoMonitor,
  [int]$MonitorIntervalMinutes = 30
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $repo "backend"
$starter = Join-Path $PSScriptRoot "start-bond-shadow.ps1"
$monitor = Join-Path $PSScriptRoot "monitor-bond-shadows.ps1"

$common = @{
  Minutes = 10080
  Tags = "Geopolitics"
  MaxMarkets = 20
  MinVolume = 10000
  MinRemainingMinutes = 4320
  MaxRemainingMinutes = 10080
  WaitForMarketMinutes = 10080
  OrderNotional = 100
  ExitSpreadCents = 2
  MaxCyclesPerMarket = 10
  FillVolumeFactor = 2
  MinSpreadTicks = 1
  MaxQueue = 500
  # The historical ladder quotes at the reference price and exits at entry+target.
  # Do not add an implicit one-tick improvement/undercut.
  ImproveTicks = 0
  ExitImproveTicks = 0
  RequoteSeconds = 300
  MaxOrderAgeSeconds = 1800
  FlattenBeforeCloseMinutes = 0
  FlattenSlippageTicks = 0
  FlowGuardWindowSeconds = 0
  MaxTotalInventoryNotional = 400
  MaxDailyLoss = 1000
  EntryMomentumMaxCents = 1
  LadderOffsetsCents = ""
}
function Merge-Hashtable([hashtable]$base, [hashtable]$overrides) {
  $result = @{}
  foreach ($key in $base.Keys) { $result[$key] = $base[$key] }
  foreach ($key in $overrides.Keys) { $result[$key] = $overrides[$key] }
  return $result
}
$arms = @(
  @{ Name = "3c-geo-9597"; Args = (Merge-Hashtable $common @{ MinPrice = 0.95; MaxPrice = 0.97; Output = "build/research/event-maker-bond-scalp-3c-geo-9597-shadow-7d-20261021"; ExitSpreadCents = 3; MaxCyclesPerMarket = 3; EntryOffsetCents = 0; MaxTotalInventoryNotional = 1000; EntryMomentumMaxCents = 999 }) },
  @{ Name = "ladder4"; Args = (Merge-Hashtable $common @{ MinPrice = 0.97; MaxPrice = 0.99; Output = "build/research/event-maker-bond-scalp-2c-geo-97099-ladder4-shadow-7d-20261021"; LadderOffsetsCents = "0,0.5,1,2"; EntryOffsetCents = 0; EntryMomentumMaxCents = 999; MaxTotalInventoryNotional = 400 }) },
  @{ Name = "flatten24"; Args = (Merge-Hashtable $common @{ MinPrice = 0.97; MaxPrice = 0.99; Output = "build/research/event-maker-bond-scalp-2c-geo-97099-flatten24-shadow-7d-20261021"; LadderOffsetsCents = "0,0.5,1,2"; EntryOffsetCents = 0; EntryMomentumMaxCents = 999; MaxTotalInventoryNotional = 400; FlattenBeforeCloseMinutes = 1440; FlattenSlippageTicks = 2 }) },
  @{ Name = "smallcap-flatten24-2c"; Args = (Merge-Hashtable $common @{ MinPrice = 0.97; MaxPrice = 0.99; Output = "build/research/event-maker-bond-scalp-2c-geo-97099-flatten24-500-shadow-7d-20261021"; LadderOffsetsCents = "0,0.5,1,2"; EntryOffsetCents = 0; EntryMomentumMaxCents = 999; OrderNotional = 500; MaxMarkets = 3; MaxTotalInventoryNotional = 2000; MaxDailyLoss = 100; FlattenBeforeCloseMinutes = 1440; FlattenSlippageTicks = 2 }) },
  @{ Name = "smallcap-flatten24-3c"; Args = (Merge-Hashtable $common @{ MinPrice = 0.97; MaxPrice = 0.99; Output = "build/research/event-maker-bond-scalp-3c-geo-97099-flatten24-500-shadow-7d-20261021"; LadderOffsetsCents = "0,0.5,1,2"; EntryOffsetCents = 0; EntryMomentumMaxCents = 999; OrderNotional = 500; MaxMarkets = 3; MaxTotalInventoryNotional = 2000; MaxDailyLoss = 100; FlattenBeforeCloseMinutes = 1440; FlattenSlippageTicks = 2; ExitSpreadCents = 3 }) },
  @{ Name = "smallcap-flatten24-q1-4c"; Args = (Merge-Hashtable $common @{ MinPrice = 0.97; MaxPrice = 0.99; Output = "build/research/event-maker-bond-scalp-q1-4c-geo-97099-flatten24-650-shadow-7d-20261021"; LadderOffsetsCents = "0,0.5,1,2,3,4,5"; EntryOffsetCents = 0; EntryMomentumMaxCents = 999; OrderNotional = 650; MaxMarkets = 3; MaxTotalInventoryNotional = 2600; MaxDailyLoss = 130; FlattenBeforeCloseMinutes = 1440; FlattenSlippageTicks = 2; ExitSpreadCents = 4; FillVolumeFactor = 1 }) },
  @{ Name = "taker00c"; Args = (Merge-Hashtable $common @{ MinPrice = 0.97; MaxPrice = 0.99; Output = "build/research/event-maker-bond-scalp-3c-geo-97099-taker00c-shadow-7d-20261021"; ExitSpreadCents = 3; EntryOffsetCents = 0; ExitImproveTicks = 0; TakerEntry = $true; EntryMomentumMaxCents = 999 }) },
  @{ Name = "taker2c"; Args = (Merge-Hashtable $common @{ MinPrice = 0.97; MaxPrice = 0.99; Output = "build/research/event-maker-bond-scalp-2c-geo-97099-taker-shadow-7d-20261021"; EntryOffsetCents = 0; ExitImproveTicks = 0; TakerEntry = $true; EntryMomentumMaxCents = 999 }) },
  @{ Name = "mom1-taker2c"; Args = (Merge-Hashtable $common @{ MinPrice = 0.97; MaxPrice = 0.99; Output = "build/research/event-maker-bond-scalp-2c-geo-97099-mom1-taker-shadow-7d-20261021"; EntryOffsetCents = 0; ExitImproveTicks = 0; TakerEntry = $true }) },
  @{ Name = "mom1-taker1c"; Args = (Merge-Hashtable $common @{ MinPrice = 0.97; MaxPrice = 0.99; Output = "build/research/event-maker-bond-scalp-1c-geo-97099-mom1-taker-shadow-7d-20261021"; ExitSpreadCents = 1; EntryOffsetCents = 0; ExitImproveTicks = 0; TakerEntry = $true }) },
  @{ Name = "dip24h-taker2c"; Args = (Merge-Hashtable $common @{ MinPrice = 0.95; MaxPrice = 0.97; Output = "build/research/event-maker-bond-scalp-2c-geo-9597-dip24h-taker-shadow-7d-20261021"; EntryOffsetCents = 0; ExitImproveTicks = 0; TakerEntry = $true; EntryMomentumMaxCents = -2 }) },
  @{ Name = "off10-size1k"; Args = (Merge-Hashtable $common @{ MinPrice = 0.97; MaxPrice = 0.99; Output = "build/research/event-maker-bond-scalp-2c-geo-97099-off10-size1k-shadow-7d-20261021"; OrderNotional = 1000; EntryOffsetCents = 1; EntryMomentumMaxCents = 999; MaxTotalInventoryNotional = 4000 }) }
)

if ($DryRun) {
  $arms | ForEach-Object { [pscustomobject]@{ Name = $_.Name; Output = $_.Args.Output; OrderNotional = $_.Args.OrderNotional; LadderOffsetsCents = $_.Args.LadderOffsetsCents; FlattenBeforeCloseMinutes = $_.Args.FlattenBeforeCloseMinutes; FlattenSlippageTicks = $_.Args.FlattenSlippageTicks; EntryOffsetCents = $_.Args.EntryOffsetCents; ExitSpreadCents = $_.Args.ExitSpreadCents; ImproveTicks = $_.Args.ImproveTicks; ExitImproveTicks = $_.Args.ExitImproveTicks; FillFactor = $_.Args.FillVolumeFactor; MomentumMaxCents = $_.Args.EntryMomentumMaxCents; MaxCycles = $_.Args.MaxCyclesPerMarket; MinRemaining = $_.Args.MinRemainingMinutes; MaxRemaining = $_.Args.MaxRemainingMinutes; Taker = [bool]$_.Args.TakerEntry } } | Format-Table -AutoSize
  return
}

if ($Restart) {
  foreach ($arm in $arms) {
    $runPath = Join-Path $backend (Join-Path $arm.Args.Output "run.json")
    if (-not (Test-Path -LiteralPath $runPath)) { continue }
    try {
      $run = Get-Content -LiteralPath $runPath -Raw -Encoding UTF8 | ConvertFrom-Json
      $pidValue = [int]$run.pid
      $proc = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
      if ($proc -and $proc.ProcessName -eq "java") { Stop-Process -Id $pidValue -Force }
    } catch { Write-Warning "failed to stop $($arm.Name): $($_.Exception.Message)" }
  }
  Start-Sleep -Seconds 2
}

$started = @()
foreach ($arm in $arms) {
  $params = $arm.Args
  $result = & $starter @params
  $parsed = $result | ConvertFrom-Json
  $started += [pscustomobject]@{ arm = $arm.Name; pid = $parsed.pid; output = $parsed.output; startedAt = $parsed.startedAt }
  Start-Sleep -Seconds 1
}

if (-not $NoMonitor) {
  $existing = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq "powershell.exe" -and $_.CommandLine -and $_.CommandLine.Contains("monitor-bond-shadows.ps1") }
  foreach ($proc in $existing) { Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue }
  $logDir = Join-Path $backend "build/research"
  New-Item -ItemType Directory -Force -Path $logDir | Out-Null
  $monitorProc = Start-Process -FilePath "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $monitor, "-IntervalMinutes", "$MonitorIntervalMinutes", "-Series", "build/research/bond-shadow-audit-series.jsonl") -WorkingDirectory $repo -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDir "bond-shadow-monitor-stdout.log") -RedirectStandardError (Join-Path $logDir "bond-shadow-monitor-stderr.log") -PassThru
  Start-Sleep -Seconds 2
  $started += [pscustomobject]@{ arm = "monitor"; pid = $monitorProc.Id; output = $logDir; startedAt = (Get-Date).ToUniversalTime().ToString("o") }
}

$out = Join-Path $backend "build/research/bond-shadow-suite-start-$(Get-Date -Format 'yyyyMMdd-HHmmss').json"
$started | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $out -Encoding UTF8
$started | Format-Table -AutoSize
Write-Output "wrote $out"