param(
  [int]$Minutes = 120,
  [double]$MinPrice = 0.95,
  [double]$MaxPrice = 0.97,
  [string]$Tags = "Politics,Geopolitics",
  [double]$OrderNotional = 100,
  [int]$MaxMarkets = 20,
  [double]$MinVolume = 10000,
  [int]$MinRemainingMinutes = 120,
  [int]$MaxRemainingMinutes = 43200,
  [int]$WaitForMarketMinutes = 0,
  [string]$Output = "build/research/event-maker-bond-shadow-20261020",
  [string]$Proxy = "http://127.0.0.1:10808",
  [int]$FlowGuardWindowSeconds = 3600,
  [double]$ExitSpreadCents = 100,
  [int]$MaxCyclesPerMarket = 1,
  [double]$FillVolumeFactor = 1,
  [double]$EntryOffsetCents = 0,
  [int]$MinSpreadTicks = 1,
  [int]$MaxQueue = 500,
  [int]$ImproveTicks = 1,
  [int]$ExitImproveTicks = 1,
  [switch]$TakerEntry,
  [int]$TakerEntrySlippageTicks = 0,
  [int]$RequoteSeconds = 120,
  [int]$MaxOrderAgeSeconds = 3600,
  [double]$MaxTotalInventoryNotional = 100,
  [double]$MaxDailyLoss = 5,
  [int]$MinRecentTrades = 0,
  [double]$MinRecentNotional = 0,
  [int]$MinRecentTradesPerSide = 0,
  [int]$MinRecentTrades2h = 0,
  [double]$MinRecentNotional2h = 0,
  [int]$MinRecentTradesPerSide2h = 0,
  [double]$EntryMomentumMinCents = -999,
  [double]$EntryMomentumMaxCents = 999,
  [long]$EntryMomentumLookbackSeconds = 86400
)
$ErrorActionPreference = "Stop"
$backend = Join-Path (Split-Path -Parent $PSScriptRoot) "backend"
$env:HTTPS_PROXY = $Proxy
$env:HTTP_PROXY = $Proxy
$env:NODE_USE_ENV_PROXY = "1"
$logDir = Join-Path $backend $Output
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$classpathFile = Join-Path $backend "build/research/eventmaker-shadow-classpath.txt"
if (-not (Test-Path -LiteralPath $classpathFile)) {
  & (Join-Path $backend "gradlew.bat") writeEventMakerShadowClasspath --console=plain
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $classpathFile)) {
    throw "failed to create event-maker Shadow classpath"
  }
}
$classpath = (Get-Content -LiteralPath $classpathFile -Raw -Encoding UTF8).Trim()
if (-not $classpath) { throw "empty event-maker Shadow classpath" }

$appArgs = @(
  "--proxy", $Proxy,
  "--max-markets", "$MaxMarkets",
  "--min-volume", "$MinVolume",
  "--min-remaining-minutes", "$MinRemainingMinutes",
  "--max-remaining-minutes", "$MaxRemainingMinutes",
  "--wait-for-market-minutes", "$WaitForMarketMinutes",
  "--min-price", "$MinPrice",
  "--max-price", "$MaxPrice",
  "--tags", $Tags,
  "--minutes", "$Minutes",
  "--max-queue", "$MaxQueue",
  "--min-spread-ticks", "$MinSpreadTicks",
  "--improve-ticks", "$ImproveTicks",
  "--entry-offset-cents", "$EntryOffsetCents",
  "--exit-improve-ticks", "$ExitImproveTicks",
  "--taker-entry-slippage-ticks", "$TakerEntrySlippageTicks",
  "--exit-spread-cents", "$ExitSpreadCents",
  "--requote-seconds", "$RequoteSeconds",
  "--max-order-age-seconds", "$MaxOrderAgeSeconds",
  "--flow-guard-window-seconds", "$FlowGuardWindowSeconds",
  "--order-notional", "$OrderNotional",
  "--fill-volume-factor", "$FillVolumeFactor",
  "--max-cycles-per-market", "$MaxCyclesPerMarket",
  "--max-total-inventory-notional", "$MaxTotalInventoryNotional",
  "--max-daily-loss", "$MaxDailyLoss",
  "--min-recent-trades", "$MinRecentTrades",
  "--min-recent-notional", "$MinRecentNotional",
  "--min-recent-trades-per-side", "$MinRecentTradesPerSide",
  "--min-recent-trades-2h", "$MinRecentTrades2h",
  "--min-recent-notional-2h", "$MinRecentNotional2h",
  "--min-recent-trades-per-side-2h", "$MinRecentTradesPerSide2h",
  "--entry-momentum-min-cents", "$EntryMomentumMinCents",
  "--entry-momentum-max-cents", "$EntryMomentumMaxCents",
  "--entry-momentum-lookback-seconds", "$EntryMomentumLookbackSeconds",
  "--output", $Output
)
if ($TakerEntry) { $appArgs += "--taker-entry" }

function Quote-JavaArg([string]$value) {
  if ($value -match '\s') { return '"' + $value + '"' }
  return $value
}
$javaArgsFile = Join-Path $logDir "java.args"
$javaLines = @("-Xms64m", "-Xmx512m", "-cp", $classpath, "com.wrbug.polymarketbot.research.eventmaker.EventMakerShadowCliKt") + $appArgs
$javaLines = $javaLines | ForEach-Object { Quote-JavaArg $_ }
[IO.File]::WriteAllText($javaArgsFile, (($javaLines -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))

$javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java.exe" } else { (Get-Command java.exe).Source }
$proc = Start-Process -FilePath $javaExe -ArgumentList "@$javaArgsFile" -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDir "stdout.log") -RedirectStandardError (Join-Path $logDir "stderr.log") -PassThru
$info = [pscustomobject]@{
  startedAt = (Get-Date).ToUniversalTime().ToString("o")
  pid = $proc.Id
  minutes = $Minutes
  minPrice = $MinPrice
  maxPrice = $MaxPrice
  tags = $Tags
  orderNotional = $OrderNotional
  maxMarkets = $MaxMarkets
  exitSpreadCents = $ExitSpreadCents
  maxCyclesPerMarket = $MaxCyclesPerMarket
  fillVolumeFactor = $FillVolumeFactor
  entryOffsetCents = $EntryOffsetCents
  minSpreadTicks = $MinSpreadTicks
  maxQueue = $MaxQueue
  improveTicks = $ImproveTicks
  exitImproveTicks = $ExitImproveTicks
  takerEntry = [bool]$TakerEntry
  takerEntrySlippageTicks = $TakerEntrySlippageTicks
  requoteSeconds = $RequoteSeconds
  maxOrderAgeSeconds = $MaxOrderAgeSeconds
  minRemainingMinutes = $MinRemainingMinutes
  maxRemainingMinutes = $MaxRemainingMinutes
  waitForMarketMinutes = $WaitForMarketMinutes
  flowGuardWindowSeconds = $FlowGuardWindowSeconds
  maxTotalInventoryNotional = $MaxTotalInventoryNotional
  maxDailyLoss = $MaxDailyLoss
  minRecentTrades = $MinRecentTrades
  minRecentNotional = $MinRecentNotional
  minRecentTradesPerSide = $MinRecentTradesPerSide
  minRecentTrades2h = $MinRecentTrades2h
  minRecentNotional2h = $MinRecentNotional2h
  minRecentTradesPerSide2h = $MinRecentTradesPerSide2h
  entryMomentumMinCents = $EntryMomentumMinCents
  entryMomentumMaxCents = $EntryMomentumMaxCents
  entryMomentumLookbackSeconds = $EntryMomentumLookbackSeconds
  output = $logDir
  classpathFile = $classpathFile
  javaArgsFile = $javaArgsFile
}
$info | ConvertTo-Json -Depth 3 | Set-Content -Path (Join-Path $logDir "run.json") -Encoding UTF8
$info | ConvertTo-Json -Depth 3
