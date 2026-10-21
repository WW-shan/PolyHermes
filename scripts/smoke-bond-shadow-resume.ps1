param(
  [string]$Proxy = "http://127.0.0.1:10808",
  [int]$TimeoutSeconds = 120,
  [switch]$KeepArtifacts
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $repo "backend"
$output = Join-Path $backend "build/research/resume-smoke-latest"
$starter = Join-Path $PSScriptRoot "start-bond-shadow.ps1"

Push-Location $backend
try {
  & .\gradlew.bat writeEventMakerShadowClasspath --console=plain | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "failed to build Shadow classes/classpath" }
} finally {
  Pop-Location
}

New-Item -ItemType Directory -Force -Path $output | Out-Null
foreach ($name in @("state.json", "recovery.json", "summary.json", "stdout.log", "stderr.log", "events.jsonl", "snapshots.csv", "trades.csv", "markouts.csv", "discovery-attempts.jsonl")) {
  $path = Join-Path $output $name
  if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
}

$nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$nowSec = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$endSec = $nowSec + 172800
$day = [math]::Floor($nowMs / 86400000)
$state = [pscustomobject]@{
  version = 4; mode = "SHADOW"; accountId = $null; startedAt = $nowMs - 3600000
  cumulativeRealizedPnl = 0.0; dailyPnlDay = $day; dailyPnlBaseline = 0.0
  markets = @([pscustomobject]@{
    conditionId = "resume-smoke"; phase = "SELL_PENDING"; inventoryShares = "10.00"
    entryPrice = "0.96"; entryAt = $nowMs - 3600000; realizedPnl = 0.0; completedCycles = 0
    activeOrderId = "old-sell"; activeOrderSide = "SELL"; activeOrderPrice = "0.98"
    activeOrderOriginalShares = "10.00"; activeOrderMatchedShares = "0.00"; activeOrderStatus = "LIVE"
    totalBuyNotional = 9.6; activeOrderCreatedAt = $nowMs - 1800000
    currentEntryOrderId = "old-buy"; currentEntryShares = "10.00"
    fills = @([pscustomobject]@{ marketId = "resume-smoke"; orderId = "old-buy"; side = "BUY"; price = "0.96"; shares = "10.00"; timestamp = $nowMs - 3600000 })
    cycles = @(); favoriteTokenId = "resume-token"; settlements = @()
    favoriteIndex = 0; negativeRisk = $false; endTimestamp = $endSec; tickSize = 0.001
  })
}
$recovery = [pscustomobject]@{
  version = 1; savedAt = $nowMs
  markets = @([pscustomobject]@{
    conditionId = "resume-smoke"; question = "Resume smoke"; tag = "Geopolitics"
    volume24hr = 100000.0; endTimestamp = $endSec; tickSize = 0.001
    outcomes = @("Yes", "No"); tokenIds = @("resume-token", "other-token"); favoriteIndex = 0
    negRisk = $false; rewardDailyRate = 0.0; rewardsMinSize = 0.0; rewardsMaxSpread = 0.0; holdingRewardsEnabled = $false
  })
}
$state | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $output "state.json") -Encoding UTF8
$recovery | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output "recovery.json") -Encoding UTF8

$params = @{
  Minutes = 1; MinPrice = 0.97; MaxPrice = 0.99; Tags = "Geopolitics"; MaxMarkets = 1
  MinVolume = 10000; MinRemainingMinutes = 4320; MaxRemainingMinutes = 10080
  WaitForMarketMinutes = 0; OrderNotional = 100; MaxCyclesPerMarket = 1
  FillVolumeFactor = 2; EntryOffsetCents = 0; ImproveTicks = 0; ExitImproveTicks = 0
  ExitSpreadCents = 2; FlowGuardWindowSeconds = 0; MaxTotalInventoryNotional = 400
  MaxDailyLoss = 1000; Output = "build/research/resume-smoke-latest"; Proxy = $Proxy
}
$started = & $starter @params | ConvertFrom-Json
$pidValue = [int]$started.pid
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
  $proc = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
  if (-not $proc) { break }
  Start-Sleep -Seconds 2
}
if (Get-Process -Id $pidValue -ErrorAction SilentlyContinue) { throw "resume smoke timed out after ${TimeoutSeconds}s" }
Start-Sleep -Seconds 1

$summary = Get-Content -LiteralPath (Join-Path $output "summary.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$restored = Get-Content -LiteralPath (Join-Path $output "state.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$market = $restored.markets[0]
$errors = @()
if (-not $summary.resumed) { $errors += "summary.resumed=false" }
if ([double]$summary.recoveredInventoryShares -ne 10.0) { $errors += "recoveredInventoryShares=$($summary.recoveredInventoryShares)" }
if ([int]$summary.abandonedRecoveredOrders -ne 1) { $errors += "abandonedRecoveredOrders=$($summary.abandonedRecoveredOrders)" }
if ($summary.haltedReason) { $errors += "haltedReason=$($summary.haltedReason)" }
if ($null -ne $market.activeOrderId) { $errors += "activeOrderId=$($market.activeOrderId)" }
if ([string]$market.inventoryShares -ne "10.00") { $errors += "inventoryShares=$($market.inventoryShares)" }
if ($market.fills.Count -ne 1) { $errors += "fills=$($market.fills.Count)" }
if (@($summary.lastErrors).Count -ne 0) { $errors += "lastErrors=$(@($summary.lastErrors) -join ';')" }
if ($errors.Count -gt 0) { throw "resume smoke failed: $($errors -join ', ')" }

Write-Output "resume smoke PASS output=$output"
Write-Output "resumed=$($summary.resumed) recoveredInventory=$($summary.recoveredInventoryShares) abandonedOrders=$($summary.abandonedRecoveredOrders) activeOrderId=$($market.activeOrderId) inventory=$($market.inventoryShares) fills=$($market.fills.Count)"
if (-not $KeepArtifacts) {
  $research = [IO.Path]::GetFullPath((Join-Path $backend "build/research"))
  $target = [IO.Path]::GetFullPath($output)
  if (-not $target.StartsWith($research + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "refusing to remove outside research: $target"
  }
  [IO.Directory]::Delete($target, $true)
  Write-Output "removed $target"
}