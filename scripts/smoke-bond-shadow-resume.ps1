param(
  [string]$Proxy = "http://127.0.0.1:10808",
  [int]$TimeoutSeconds = 120,
  [switch]$Ladder,
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
$legs = @(if ($Ladder) {
  @(
    [pscustomobject]@{ offset = 0.0; inventory = "10.00"; entry = "0.96"; buyOrder = "old-buy-0"; sellOrder = "old-sell-0"; sellPrice = "0.98" },
    [pscustomobject]@{ offset = 1.0; inventory = "5.00"; entry = "0.95"; buyOrder = "old-buy-1"; sellOrder = "old-sell-1"; sellPrice = "0.96" }
  )
} else {
  @([pscustomobject]@{ offset = $null; inventory = "10.00"; entry = "0.96"; buyOrder = "old-buy"; sellOrder = "old-sell"; sellPrice = "0.98" })
})
$state = [pscustomobject]@{
  version = 4; mode = "SHADOW"; accountId = $null; startedAt = $nowMs - 3600000
  cumulativeRealizedPnl = 0.0; dailyPnlDay = $day; dailyPnlBaseline = 0.0
  markets = @($legs | ForEach-Object {
    [pscustomobject]@{
      conditionId = "resume-smoke"; legOffsetCents = $_.offset; phase = "SELL_PENDING"
      inventoryShares = $_.inventory; entryPrice = $_.entry; entryAt = $nowMs - 3600000
      realizedPnl = 0.0; completedCycles = 0
      activeOrderId = $_.sellOrder; activeOrderSide = "SELL"; activeOrderPrice = $_.sellPrice
      activeOrderOriginalShares = $_.inventory; activeOrderMatchedShares = "0.00"; activeOrderStatus = "LIVE"
      totalBuyNotional = [double]$_.entry * [double]$_.inventory; activeOrderCreatedAt = $nowMs - 1800000
      currentEntryOrderId = $_.buyOrder; currentEntryShares = $_.inventory
      fills = @([pscustomobject]@{ marketId = "resume-smoke"; orderId = $_.buyOrder; side = "BUY"; price = $_.entry; shares = $_.inventory; timestamp = $nowMs - 3600000 })
      cycles = @(); favoriteTokenId = "resume-token"; settlements = @()
      favoriteIndex = 0; negativeRisk = $false; endTimestamp = $endSec; tickSize = 0.001
    }
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
  FillVolumeFactor = 2; EntryOffsetCents = 0; LadderOffsetsCents = $(if ($Ladder) { '0,1' } else { '' }); ImproveTicks = 0; ExitImproveTicks = 0
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
$restoredMarkets = @($restored.markets)
$expectedInventory = if ($Ladder) { 15.0 } else { 10.0 }
$expectedOrders = if ($Ladder) { 2 } else { 1 }
$errors = @()
if (-not $summary.resumed) { $errors += "summary.resumed=false" }
if ([double]$summary.recoveredInventoryShares -ne $expectedInventory) { $errors += "recoveredInventoryShares=$($summary.recoveredInventoryShares)" }
if ([int]$summary.abandonedRecoveredOrders -ne $expectedOrders) { $errors += "abandonedRecoveredOrders=$($summary.abandonedRecoveredOrders)" }
if ($summary.haltedReason) { $errors += "haltedReason=$($summary.haltedReason)" }
if ($restoredMarkets.Count -ne $legs.Count) { $errors += "markets=$($restoredMarkets.Count)" }
for ($i = 0; $i -lt $legs.Count; $i++) {
  $market = $restoredMarkets[$i]
  if ($null -ne $market.activeOrderId) { $errors += "leg$i.activeOrderId=$($market.activeOrderId)" }
  if ([string]$market.inventoryShares -ne $legs[$i].inventory) { $errors += "leg$i.inventoryShares=$($market.inventoryShares)" }
  if ($market.fills.Count -ne 1) { $errors += "leg$i.fills=$($market.fills.Count)" }
  if ($Ladder -and [double]$market.legOffsetCents -ne [double]$legs[$i].offset) { $errors += "leg$i.offset=$($market.legOffsetCents)" }
}
if (@($summary.lastErrors).Count -ne 0) { $errors += "lastErrors=$(@($summary.lastErrors) -join ';')" }
if ($errors.Count -gt 0) { throw "resume smoke failed: $($errors -join ', ')" }

Write-Output "resume smoke PASS output=$output ladder=$([bool]$Ladder)"
Write-Output "resumed=$($summary.resumed) recoveredInventory=$($summary.recoveredInventoryShares) abandonedOrders=$($summary.abandonedRecoveredOrders) markets=$($restoredMarkets.Count) fills=$((@($restoredMarkets | ForEach-Object { $_.fills.Count }) | Measure-Object -Sum).Sum)"
if (-not $KeepArtifacts) {
  $research = [IO.Path]::GetFullPath((Join-Path $backend "build/research"))
  $target = [IO.Path]::GetFullPath($output)
  if (-not $target.StartsWith($research + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "refusing to remove outside research: $target"
  }
  [IO.Directory]::Delete($target, $true)
  Write-Output "removed $target"
}