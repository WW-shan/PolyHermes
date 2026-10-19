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

$fillAnalysisScript = Join-Path $PSScriptRoot "analyze-event-maker-shadow-fills.ps1"
$fillAnalysisFile = Join-Path $OutputDir "fill-analysis.json"
$fillVolumeFactor = if ($null -ne $execution.fillVolumeFactor) { [double]$execution.fillVolumeFactor } else { -1 }
& $fillAnalysisScript -OutputDir $OutputDir -FillVolumeFactor $fillVolumeFactor -WriteReport | Out-Null
$fillAnalysis = Get-Content -LiteralPath $fillAnalysisFile -Raw -Encoding UTF8 | ConvertFrom-Json
$modelFillOrders = [int]$fillAnalysis.summary.ordersMeetingModelFill
$actualFilledOrders = [int]$fillAnalysis.summary.actualFilledOrders

$failures = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()
if ($modelFillOrders -gt $actualFilledOrders) {
    $failures.Add("fillModelMismatch:modelFillOrders=$modelFillOrders actualFilledOrders=$actualFilledOrders")
} elseif ($modelFillOrders -ne $actualFilledOrders) {
    $warnings.Add("fillModelCountMismatch:modelFillOrders=$modelFillOrders actualFilledOrders=$actualFilledOrders")
}
$stateRealizedPnl = 0.0
$stateCompletedCycles = 0
$stateSettlements = 0
foreach ($market in $stateMarkets) {
    $conditionId = [string]$market.conditionId
    $replayInventory = [decimal]0
    $replayEntryPrice = [decimal]0
    $replayEntryAt = [long]0
    $replayCurrentEntryOrderId = $null
    $replayCurrentEntryShares = [decimal]0
    $replayCurrentCycleSoldShares = [decimal]0
    $replayRealized = 0.0
    $replayCompletedCycles = 0
    $partialCyclePnl = 0.0
    $lastTimestamp = [long]::MinValue
    foreach ($fill in @($market.fills | Where-Object { $null -ne $_ })) {
        $fillMarketId = [string]$fill.marketId
        if (-not [string]::IsNullOrWhiteSpace($fillMarketId) -and $fillMarketId -ne $conditionId) {
            $failures.Add("fillMarketMismatch:${conditionId}:$fillMarketId")
        }
        $side = [string]$fill.side
        $price = [decimal]::Parse([string]$fill.price, [Globalization.CultureInfo]::InvariantCulture)
        $shares = [decimal]::Parse([string]$fill.shares, [Globalization.CultureInfo]::InvariantCulture)
        $fillTimestamp = [long]$fill.timestamp
        if ($shares -le 0) {
            $failures.Add("invalidFillShares:${conditionId}:$shares")
            continue
        }
        if ($price -le 0 -or $price -ge 1) {
            $failures.Add("invalidFillPrice:${conditionId}:$price")
            continue
        }
        if ($fillTimestamp -lt $lastTimestamp) {
            $failures.Add("fillTimestampOutOfOrder:${conditionId}:$fillTimestamp<$lastTimestamp")
            continue
        }
        $lastTimestamp = $fillTimestamp
        if ($side -eq 'BUY') {
            if ($replayInventory -le 0) {
                $replayEntryPrice = $price
                $replayEntryAt = $fillTimestamp
                $replayCurrentEntryOrderId = [string]$fill.orderId
                $replayCurrentEntryShares = $shares
                $replayCurrentCycleSoldShares = [decimal]0
                $partialCyclePnl = 0.0
            } else {
                if ($replayCurrentEntryOrderId -ne [string]$fill.orderId -or $replayEntryPrice -ne $price) {
                    $failures.Add("buyFillChainInvalid:${conditionId}:$($fill.orderId)")
                    continue
                }
                $replayCurrentEntryShares += $shares
            }
            $replayInventory += $shares
        } elseif ($side -eq 'SELL') {
            if ($replayInventory -le 0 -or $shares -gt $replayInventory) {
                $failures.Add("sellExceedsInventory:${conditionId}:$shares>$replayInventory")
                continue
            }
            if ([string]::IsNullOrWhiteSpace([string]$replayCurrentEntryOrderId)) {
                $failures.Add("sellMissingEntryOrder:${conditionId}:$($fill.orderId)")
                continue
            }
            $pnl = ([double]($price - $replayEntryPrice)) * ([double]$shares)
            $replayRealized += $pnl
            $partialCyclePnl += $pnl
            $replayCurrentCycleSoldShares += $shares
            $replayInventory -= $shares
            if ($replayInventory -le 0) {
                if ($replayCurrentCycleSoldShares -ne $replayCurrentEntryShares) {
                    $failures.Add("cycleSharesReplayMismatch:${conditionId}:$replayCurrentCycleSoldShares!=$replayCurrentEntryShares")
                }
                $replayInventory = [decimal]0
                $replayEntryPrice = [decimal]0
                $replayEntryAt = [long]0
                $replayCurrentEntryOrderId = $null
                $replayCurrentEntryShares = [decimal]0
                $replayCurrentCycleSoldShares = [decimal]0
                $partialCyclePnl = 0.0
                $replayCompletedCycles++
            }
        } else {
            $failures.Add("unknownFillSide:${conditionId}:$side")
        }
    }

    $settlements = @($market.settlements | Where-Object { $null -ne $_ })
    $settlementByCycleExit = @{}
    $settlementCycleExpectations = @{}
    foreach ($settlement in $settlements) {
        $stateSettlements++
        $settlementMarketId = [string]$settlement.marketId
        $settlementFavoriteTokenId = [string]$settlement.favoriteTokenId
        $settlementEntryOrderId = [string]$settlement.entryOrderId
        $cycleExitOrderId = [string]$settlement.cycleExitOrderId
        $settledAt = [long]$settlement.settledAt
        $settlementEntryAt = [long]$settlement.entryAt
        $settlementShares = [decimal]::Parse([string]$settlement.shares, [Globalization.CultureInfo]::InvariantCulture)
        $settlementEntryPrice = [decimal]::Parse([string]$settlement.entryPrice, [Globalization.CultureInfo]::InvariantCulture)
        $settlementExitPrice = [decimal]::Parse([string]$settlement.exitPrice, [Globalization.CultureInfo]::InvariantCulture)
        if ($settlementMarketId -ne $conditionId -or $settlementEntryOrderId.Length -eq 0 -or $cycleExitOrderId.Length -eq 0) {
            $failures.Add("settlementRecordInvalid:$conditionId")
            continue
        }
        $marketFavoriteTokenId = [string]$market.favoriteTokenId
        if (-not [string]::IsNullOrWhiteSpace($marketFavoriteTokenId) -and $settlementFavoriteTokenId -ne $marketFavoriteTokenId) {
            $failures.Add("settlementFavoriteTokenMismatch:${conditionId}:$settlementFavoriteTokenId")
        }
        if ($null -ne $market.favoriteIndex -and [int]$settlement.outcomeIndex -ne [int]$market.favoriteIndex) {
            $failures.Add("settlementOutcomeMismatch:${conditionId}:$($settlement.outcomeIndex)")
        }
        if ($null -ne $market.negativeRisk -and [bool]$settlement.negativeRisk -ne [bool]$market.negativeRisk) {
            $failures.Add("settlementNegativeRiskMismatch:${conditionId}:$($settlement.negativeRisk)")
        }
        if ($settlementExitPrice -ne 0 -and $settlementExitPrice -ne 1) {
            $failures.Add("settlementExitPriceInvalid:${conditionId}:$settlementExitPrice")
        }
        if ($settledAt -lt $lastTimestamp) {
            $failures.Add("settlementTimestampOutOfOrder:${conditionId}:$settledAt<$lastTimestamp")
        }
        $lastTimestamp = [math]::Max($lastTimestamp, $settledAt)
        if ($settlementByCycleExit.ContainsKey($cycleExitOrderId)) {
            $failures.Add("duplicateSettlementExit:${conditionId}:$cycleExitOrderId")
            continue
        }
        $settlementByCycleExit[$cycleExitOrderId] = $settlement
        if ($replayInventory -le 0 -or
            $replayCurrentEntryOrderId -ne $settlementEntryOrderId -or
            $replayEntryPrice -ne $settlementEntryPrice -or
            $replayEntryAt -ne $settlementEntryAt -or
            $settlementShares -ne $replayInventory) {
            $failures.Add("settlementLedgerMismatch:${conditionId}:$settlementEntryOrderId")
            continue
        }
        if ($replayCurrentEntryShares -le 0 -or
            ($replayCurrentCycleSoldShares + $settlementShares) -ne $replayCurrentEntryShares) {
            $failures.Add("settlementCycleShareMismatch:${conditionId}:$settlementEntryOrderId")
            continue
        }
        $settlementPnl = ([double]($settlementExitPrice - $replayEntryPrice)) * ([double]$settlementShares)
        if ([math]::Abs([double]$settlement.pnl - $settlementPnl) -gt 1e-7) {
            $failures.Add("settlementPnlMismatch:${conditionId}:$settlementEntryOrderId")
        }
        $cyclePnl = $partialCyclePnl + $settlementPnl
        $settlementCycleExpectations[$cycleExitOrderId] = [pscustomobject]@{
            shares = $replayCurrentEntryShares
            pnl = $cyclePnl
        }
        $replayRealized += $settlementPnl
        $replayInventory = [decimal]0
        $replayEntryPrice = [decimal]0
        $replayEntryAt = [long]0
        $replayCurrentEntryOrderId = $null
        $replayCurrentEntryShares = [decimal]0
        $replayCurrentCycleSoldShares = [decimal]0
        $partialCyclePnl = 0.0
        $replayCompletedCycles++
    }

    $settlementCycleCount = 0
    foreach ($cycle in @($market.cycles | Where-Object { $null -ne $_ })) {
        if ([string]$cycle.exitType -eq 'SETTLE') {
            $settlementCycleCount++
            $cycleExitOrderId = [string]$cycle.exitOrderId
            if (-not $settlementByCycleExit.ContainsKey($cycleExitOrderId)) {
                $failures.Add("settlementCycleMissingRecord:${conditionId}:$cycleExitOrderId")
            } elseif (-not $settlementCycleExpectations.ContainsKey($cycleExitOrderId)) {
                $failures.Add("settlementCycleExpectationMissing:${conditionId}:$cycleExitOrderId")
            } else {
                $expectedCycle = $settlementCycleExpectations[$cycleExitOrderId]
                $cycleShares = [decimal]::Parse([string]$cycle.shares, [Globalization.CultureInfo]::InvariantCulture)
                if ($cycleShares -ne $expectedCycle.shares -or
                    [math]::Abs([double]$cycle.pnl - [double]$expectedCycle.pnl) -gt 1e-7) {
                    $failures.Add("settlementCycleMismatch:${conditionId}:$cycleExitOrderId")
                }
            }
        }
    }
    if ($settlementCycleCount -ne $settlements.Count) {
        $failures.Add("settlementCycleCountMismatch:${conditionId}:$settlementCycleCount!=$($settlements.Count)")
    }
    $stateInventory = [decimal]::Parse([string]$market.inventoryShares, [Globalization.CultureInfo]::InvariantCulture)
    $stateRealized = [double]$market.realizedPnl
    if ($replayInventory -ne $stateInventory) {
        $failures.Add("inventoryReplayMismatch:${conditionId}:$replayInventory!=$stateInventory")
    }
    if ([math]::Abs($replayRealized - $stateRealized) -gt 1e-6) {
        $failures.Add("realizedReplayMismatch:${conditionId}:$replayRealized!=$stateRealized")
    }
    if ($replayCompletedCycles -ne [int]$market.completedCycles) {
        $failures.Add("cycleCountReplayMismatch:${conditionId}:$replayCompletedCycles!=$($market.completedCycles)")
    }
    $cyclePnl = 0.0
    foreach ($cycle in @($market.cycles | Where-Object { $null -ne $_ })) {
        $cyclePnl += [double]$cycle.pnl
    }
    if ([math]::Abs(($cyclePnl + $partialCyclePnl) - $replayRealized) -gt 1e-6) {
        $failures.Add("cyclePnlReplayMismatch:${conditionId}:$cyclePnl+$partialCyclePnl!=$replayRealized")
    }
    $stateRealizedPnl += $stateRealized
    $stateCompletedCycles += [int]$market.completedCycles
}
if ([math]::Abs([double]$summary.realizedPnl - $stateRealizedPnl) -gt 1e-6) {
    $failures.Add("summary.realizedPnl=$($summary.realizedPnl) stateRealizedPnl=$stateRealizedPnl")
}
if ([int]$summary.completedCycles -ne $stateCompletedCycles) {
    $failures.Add("summary.completedCycles=$($summary.completedCycles) stateCompletedCycles=$stateCompletedCycles")
}
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
$eventFile = Join-Path $OutputDir "events.jsonl"
$eventRows = @()
if (Test-Path -LiteralPath $eventFile) {
    $eventRows = @(Get-Content -LiteralPath $eventFile -Encoding UTF8 | Where-Object { $_.Trim().Length -gt 0 } | ForEach-Object { $_ | ConvertFrom-Json })
}
$engineEvents = @($eventRows | Where-Object { $_.type -eq 'engine' })
$placedEvents = @($engineEvents | ForEach-Object { @($_.placed) })
$canceledEvents = @($engineEvents | ForEach-Object { @($_.canceled) })
$placedOrderIds = @($placedEvents | ForEach-Object { [string]$_.order.orderId })
$canceledOrderIds = @($canceledEvents | ForEach-Object { [string]$_.orderId })
$diagnosticOrderIds = @($execution.orders | ForEach-Object { [string]$_.orderId })

if ($duplicateTradeRows -ne 0) {
    $failures.Add("duplicateTradeRows=$duplicateTradeRows")
}
if ($eventRows.Count -eq 0) {
    $warnings.Add("events.jsonl missing or empty")
} else {
    if ($placedEvents.Count -ne [int]$summary.quotesPlaced) {
        $failures.Add("events.placed=$($placedEvents.Count) summary.quotesPlaced=$($summary.quotesPlaced)")
    }
    if ($canceledEvents.Count -ne [int]$summary.cancels) {
        $failures.Add("events.canceled=$($canceledEvents.Count) summary.cancels=$($summary.cancels)")
    }
    $duplicatePlacedOrders = @($placedOrderIds | Group-Object | Where-Object { $_.Count -gt 1 })
    if ($duplicatePlacedOrders.Count -gt 0) {
        $failures.Add("duplicatePlacedOrderIds=$($duplicatePlacedOrders.Name -join ',')")
    }
    $missingDiagnostics = @($placedOrderIds | Where-Object { $_ -notin $diagnosticOrderIds })
    if ($missingDiagnostics.Count -gt 0) {
        $failures.Add("placedOrdersMissingDiagnostics=$($missingDiagnostics -join ',')")
    }
    $unknownCancelIds = @($canceledOrderIds | Where-Object { $_ -notin $placedOrderIds })
    if ($unknownCancelIds.Count -gt 0) {
        $failures.Add("canceledOrdersNotPlaced=$($unknownCancelIds -join ',')")
    }
}
if ([double]$summary.openInventoryShares -ne 0.0) {
    $warnings.Add("openInventoryShares=$($summary.openInventoryShares)")
}
if ([int]$summary.fills -gt 0 -and $markoutRows.Count -eq 0) {
    $warnings.Add("fills>0 but markouts.csv is empty")
}
$droppedMarkouts = 0
if ($null -ne $summary.droppedPendingMarkouts) {
    $droppedMarkouts = [int]$summary.droppedPendingMarkouts
}
if ($droppedMarkouts -gt 0) {
    $warnings.Add("droppedPendingMarkouts=$droppedMarkouts (fills too close to run end for their horizon)")
}
$expectedNonFatalErrors = @($summary.lastErrors | Where-Object {
    $_ -like 'risk_blocked:*' -or $_ -like 'flow_guard_blocked:*'
})
$unexpectedLastErrors = @($summary.lastErrors | Where-Object {
    $_ -notlike 'risk_blocked:*' -and $_ -notlike 'flow_guard_blocked:*'
})
if ($unexpectedLastErrors.Count -gt 0) {
    $failures.Add("lastErrors=$($unexpectedLastErrors | ConvertTo-Json -Compress)")
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
    settlements = $stateSettlements
    expectedNonFatalErrors = $expectedNonFatalErrors
    completedCycles = $summary.completedCycles
    stateCycles = $stateCycles
    realizedPnl = $summary.realizedPnl
    unrealizedPnl = $summary.unrealizedPnl
    totalPnl = $summary.totalPnl
    openInventoryShares = $summary.openInventoryShares
    activeOrders = $execution.activeOrders
    crossingOrders = $summary.crossingOrders
    crossingTrades = $summary.crossingTrades
    modelFillOrders = $modelFillOrders
    actualFilledOrders = $actualFilledOrders
    queueConsumedShares = $summary.queueConsumedShares
    duplicateTradeRows = $duplicateTradeRows
    markoutsByHorizon = $markoutsByHorizon
    droppedPendingMarkouts = $droppedMarkouts
    failures = @($failures)
    warnings = @($warnings)
    passed = ($failures.Count -eq 0)
}
$auditFile = Join-Path $OutputDir "shadow-audit.json"
[IO.File]::WriteAllText($auditFile, ($audit | ConvertTo-Json -Depth 8), [Text.Encoding]::UTF8)
$audit | ConvertTo-Json -Depth 8
if ($failures.Count -gt 0) { exit 1 }
exit 0
