param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDir,
    [double]$FillVolumeFactor = -1,
    [switch]$WriteReport
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $OutputDir)) {
    throw "output directory not found: $OutputDir"
}

$watchFile = Join-Path $OutputDir "watch-process.json"
if ($FillVolumeFactor -lt 0) {
    if (-not (Test-Path -LiteralPath $watchFile)) {
        throw "FillVolumeFactor was not provided and watch-process.json is missing"
    }
    $watch = Get-Content -LiteralPath $watchFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $FillVolumeFactor = [double]$watch.shadowFillVolumeFactor
}
if ($FillVolumeFactor -le 0) {
    throw "FillVolumeFactor must be greater than zero for the conservative audit"
}

$eventsFile = Join-Path $OutputDir "events.jsonl"
$tradesFile = Join-Path $OutputDir "trades.csv"
$stateFile = Join-Path $OutputDir "state.json"
if (-not (Test-Path -LiteralPath $eventsFile)) {
    throw "events.jsonl not found"
}
if (-not (Test-Path -LiteralPath $tradesFile)) {
    throw "trades.csv not found"
}

$events = @(Get-Content -LiteralPath $eventsFile -Encoding UTF8 | Where-Object { $_.Trim().Length -gt 0 } | ForEach-Object { $_ | ConvertFrom-Json })
$orders = [ordered]@{}
$ends = @{}
foreach ($event in $events) {
    if ($event.type -ne "engine") { continue }
    foreach ($placement in @($event.placed)) {
        if (-not $placement) { continue }
        $order = $placement.order
        $orders[$order.orderId] = [pscustomobject]@{
            orderId = [string]$order.orderId
            side = [string]$order.side
            price = [double]$order.price
            shares = [decimal]$order.originalShares
            tokenId = [string]$order.tokenId
            queueAhead = [decimal]$placement.queueAheadShares
            createdAt = [long]$order.createdAt
        }
    }
    foreach ($cancel in @($event.canceled)) {
        if ($cancel) { $ends[[string]$cancel.orderId] = [long]$event.timestamp }
    }
}

$trades = @(Import-Csv -LiteralPath $tradesFile)
$actualFills = @{}
if (Test-Path -LiteralPath $stateFile) {
    $state = Get-Content -LiteralPath $stateFile -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($market in @($state.markets)) {
        foreach ($fill in @($market.fills)) {
            if (-not $fill) { continue }
            $orderId = [string]$fill.orderId
            if (-not $actualFills.ContainsKey($orderId)) { $actualFills[$orderId] = [decimal]0 }
            $actualFills[$orderId] += [decimal]$fill.shares
        }
    }
}

$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$rows = @()
foreach ($order in $orders.Values) {
    $end = if ($ends.ContainsKey($order.orderId)) { [long]$ends[$order.orderId] } else { $now }
    $oppositeSide = if ($order.side -eq "BUY") { "SELL" } else { "BUY" }
    $crossing = @($trades | Where-Object {
        ([long]$_.timestamp -ge $order.createdAt) -and
        ([long]$_.timestamp -le $end) -and
        ($_.token_id -eq $order.tokenId) -and
        ($_.side -eq $oppositeSide) -and
        $(if ($order.side -eq "BUY") { [double]$_.price -le $order.price + 1e-9 } else { [double]$_.price -ge $order.price - 1e-9 })
    })
    $crossingShares = if ($crossing.Count -eq 0) { [decimal]0 } else {
        [decimal](($crossing | Measure-Object -Property size -Sum).Sum)
    }
    $queueConsumed = [Math]::Min([double]$order.queueAhead, [double]$crossingShares)
    $eligibleShares = [Math]::Max(0.0, [double]$crossingShares - [double]$order.queueAhead)
    $requiredShares = [double]$order.shares * $FillVolumeFactor
    $modelFill = $eligibleShares + 1e-9 -ge $requiredShares
    $actualFillShares = if ($actualFills.ContainsKey($order.orderId)) { $actualFills[$order.orderId] } else { [decimal]0 }
    $firstCrossing = if ($crossing.Count -gt 0) {
        [DateTimeOffset]::FromUnixTimeMilliseconds([long]$crossing[0].timestamp).ToOffset([TimeSpan]::FromHours(8)).ToString("HH:mm:ss")
    } else { "" }
    $rows += [pscustomobject]@{
        orderId = $order.orderId
        side = $order.side
        price = $order.price
        shares = $order.shares
        queueAhead = $order.queueAhead
        lifetimeMinutes = [Math]::Round(($end - $order.createdAt) / 60000.0, 2)
        crossingTrades = $crossing.Count
        crossingShares = $crossingShares
        queueConsumed = [decimal]$queueConsumed
        eligibleShares = [decimal]$eligibleShares
        requiredShares = [decimal]$requiredShares
        modelFill = $modelFill
        actualFillShares = $actualFillShares
        firstCrossing = $firstCrossing
    }
}

$summary = [pscustomobject]@{
    outputDir = (Resolve-Path -LiteralPath $OutputDir).Path
    fillVolumeFactor = $FillVolumeFactor
    orders = $rows.Count
    ordersWithCrossing = @($rows | Where-Object { $_.crossingTrades -gt 0 }).Count
    ordersMeetingModelFill = @($rows | Where-Object { $_.modelFill }).Count
    actualFilledOrders = @($rows | Where-Object { $_.actualFillShares -gt 0 }).Count
    totalCrossingShares = [decimal](($rows | Measure-Object -Property crossingShares -Sum).Sum)
    generatedAt = [DateTimeOffset]::Now.ToString("o")
}

Write-Output ("orders={0} crossingOrders={1} modelFillOrders={2} actualFillOrders={3} fillVolumeFactor={4}" -f $summary.orders, $summary.ordersWithCrossing, $summary.ordersMeetingModelFill, $summary.actualFilledOrders, $summary.fillVolumeFactor)
$rows | Format-Table orderId,side,price,shares,queueAhead,lifetimeMinutes,crossingTrades,crossingShares,queueConsumed,eligibleShares,requiredShares,modelFill,actualFillShares,firstCrossing -AutoSize

if ($WriteReport) {
    $report = [pscustomobject]@{ summary = $summary; orders = $rows }
    $reportFile = Join-Path $OutputDir "fill-analysis.json"
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportFile -Encoding UTF8
    Write-Output "report=$reportFile"
}
