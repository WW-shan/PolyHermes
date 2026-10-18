param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDir
)

$ErrorActionPreference = "Stop"
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
$summaryFile = Join-Path $OutputDir "summary.json"
$executionFile = Join-Path $OutputDir "execution-diagnostics.json"
$stateFile = Join-Path $OutputDir "state.json"
foreach ($required in @($summaryFile, $executionFile, $stateFile)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "required audit evidence missing: $required"
    }
}

$summary = Get-Content -LiteralPath $summaryFile -Raw -Encoding UTF8 | ConvertFrom-Json
$execution = Get-Content -LiteralPath $executionFile -Raw -Encoding UTF8 | ConvertFrom-Json
$state = Get-Content -LiteralPath $stateFile -Raw -Encoding UTF8 | ConvertFrom-Json
$stateMarkets = @($state.markets)
$stateFills = 0
$stateCycles = 0
foreach ($market in $stateMarkets) {
    $stateFills += @($market.fills).Count
    $stateCycles += @($market.cycles).Count
}

$tradeFile = Join-Path $OutputDir "trades.csv"
$duplicateTradeRows = 0
if (Test-Path -LiteralPath $tradeFile) {
    $trades = @(Import-Csv -LiteralPath $tradeFile)
    $duplicateTradeRows = @($trades | Group-Object {
        "$($_.transaction_hash)|$($_.token_id)|$($_.side)|$($_.size)"
    } | Where-Object { $_.Count -gt 1 }).Count
}

$markoutFile = Join-Path $OutputDir "markouts.csv"
$markoutRows = @()
if (Test-Path -LiteralPath $markoutFile) {
    $markoutRows = @(Import-Csv -LiteralPath $markoutFile)
}
$markoutsByHorizon = @{}
foreach ($row in $markoutRows) {
    $horizon = [string]$row.horizon_seconds
    if (-not $markoutsByHorizon.ContainsKey($horizon)) {
        $markoutsByHorizon[$horizon] = 0
    }
    $markoutsByHorizon[$horizon]++
}

$failures = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()
if (-not [string]::IsNullOrWhiteSpace([string]$summary.haltedReason)) {
    $failures.Add("haltedReason=$($summary.haltedReason)")
}
if ([int]$execution.activeOrders -ne 0) {
    $failures.Add("activeOrders=$($execution.activeOrders)")
}
if ([int]$summary.fills -ne $stateFills) {
    $failures.Add("summary.fills=$($summary.fills) stateFills=$stateFills")
}
if ([int]$summary.completedCycles -ne $stateCycles) {
    $failures.Add("summary.completedCycles=$($summary.completedCycles) stateCycles=$stateCycles")
}
if ($duplicateTradeRows -ne 0) {
    $failures.Add("duplicateTradeRows=$duplicateTradeRows")
}
if ([double]$summary.openInventoryShares -ne 0.0) {
    $warnings.Add("openInventoryShares=$($summary.openInventoryShares)")
}
if ([int]$summary.fills -gt 0 -and $markoutRows.Count -eq 0) {
    $warnings.Add("fills>0 but markouts.csv is empty")
}
if (@($summary.lastErrors).Count -gt 0) {
    $failures.Add("lastErrors=$((@($summary.lastErrors) | ConvertTo-Json -Compress))")
}

$audit = [ordered]@{
    auditedAt = (Get-Date).ToString("o")
    outputDir = $OutputDir
    mode = $summary.mode
    durationSeconds = $summary.durationSeconds
    markets = $summary.markets
    trades = $summary.trades
    quotesPlaced = $summary.quotesPlaced
    cancels = $summary.cancels
    fills = $summary.fills
    stateFills = $stateFills
    completedCycles = $summary.completedCycles
    stateCycles = $stateCycles
    realizedPnl = $summary.realizedPnl
    unrealizedPnl = $summary.unrealizedPnl
    totalPnl = $summary.totalPnl
    openInventoryShares = $summary.openInventoryShares
    activeOrders = $execution.activeOrders
    crossingOrders = $summary.crossingOrders
    crossingTrades = $summary.crossingTrades
    queueConsumedShares = $summary.queueConsumedShares
    duplicateTradeRows = $duplicateTradeRows
    markoutsByHorizon = $markoutsByHorizon
    failures = @($failures)
    warnings = @($warnings)
    passed = ($failures.Count -eq 0)
}
$auditFile = Join-Path $OutputDir "shadow-audit.json"
[IO.File]::WriteAllText($auditFile, ($audit | ConvertTo-Json -Depth 8), [Text.Encoding]::UTF8)
$audit | ConvertTo-Json -Depth 8
if ($failures.Count -gt 0) { exit 1 }
exit 0