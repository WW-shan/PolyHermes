param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDir,
    [int]$Spread = 2,
    [int]$WaitMinutes = 720,
    [int]$Minutes = 720,
    [int]$MaxMarkets = 18,
    [int]$MinRemainingMinutes = 720,
    [int]$MaxRemainingMinutes = 10080,
    [string]$Proxy = "http://127.0.0.1:10808",
    [double]$MinVolume = 100000.0,
    [double]$MinPrice = 0.97,
    [double]$MaxPrice = 0.99,
    [long]$FlowGuardWindowSeconds = 3600,
    [double]$FlowGuardMaxSignedFlow = -0.4,
    [double]$FlowGuardMinPriceDropCents = 0.5,
    [int]$MaxCyclesPerMarket = 1,
    [double]$FillVolumeFactor = 20.0,
    [double]$EntryOffsetCents = 0.0,
    [double]$ExitSpreadCents = 1.0
    [long]$TailWindowMinutes = 0
)

$ErrorActionPreference = "Stop"
if ($Spread -lt 1) { throw "Spread must be >= 1" }
if ($WaitMinutes -lt 0 -or $Minutes -lt 1) { throw "invalid duration" }
if ($MinRemainingMinutes -lt 1 -or $MaxRemainingMinutes -lt $MinRemainingMinutes) {
    throw "invalid remaining-minute window"
}
if ($FlowGuardWindowSeconds -lt 0) { throw "FlowGuardWindowSeconds must be non-negative" }
if ($FlowGuardMaxSignedFlow -lt -1.0 -or $FlowGuardMaxSignedFlow -gt 1.0) { throw "invalid FlowGuardMaxSignedFlow" }
if ($FlowGuardMinPriceDropCents -lt 0.0) { throw "invalid FlowGuardMinPriceDropCents" }
if ($MaxCyclesPerMarket -lt 1) { throw "MaxCyclesPerMarket must be >= 1" }
if ($FillVolumeFactor -lt 0.0) { throw "FillVolumeFactor must be non-negative" }
if ($EntryOffsetCents -lt 0.0) { throw "EntryOffsetCents must be non-negative" }
if ($ExitSpreadCents -le 0.0) { throw "ExitSpreadCents must be positive" }

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$backendDir = Join-Path $repoRoot "backend"
$outputPath = [IO.Path]::GetFullPath($OutputDir)
$researchRoot = [IO.Path]::GetFullPath((Join-Path $backendDir "build\research"))
if (-not $outputPath.StartsWith($researchRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputDir must be inside $researchRoot"
}
[IO.Directory]::CreateDirectory($outputPath) | Out-Null
$stdout = Join-Path $outputPath "stdout.log"
$stderr = Join-Path $outputPath "stderr.log"
$gradlew = Join-Path $backendDir "gradlew.bat"

# These values mirror the validated runbook and keep the main/experiment arms
# comparable. Only spread differs between the two arms.
$argsList = @(
    "eventMakerShadow",
    "-PshadowProxy=$Proxy",
    "-PshadowMaxMarkets=$MaxMarkets",
    "-PshadowDiscoveryPages=50",
    "-PshadowWaitForMarketMinutes=$WaitMinutes",
    "-PshadowDiscoveryRetrySeconds=60",
    "-PshadowMinVolume=$MinVolume",
    "-PshadowMinPrice=$MinPrice",
    "-PshadowMaxPrice=$MaxPrice",
    "-PshadowMinRecentTrades=3",
    "-PshadowMinRecentNotional=50",
    "-PshadowMinRecentTradesPerSide=1",
    "-PshadowMinRecentTrades2h=20",
    "-PshadowMinRecentNotional2h=500",
    "-PshadowMinRecentTradesPerSide2h=3",
    "-PshadowMinRemainingMinutes=$MinRemainingMinutes",
    "-PshadowMaxRemainingMinutes=$MaxRemainingMinutes",
    "-PshadowMinutes=$Minutes",
    "-PshadowPollSeconds=3",
    "-PshadowSnapshotSeconds=10",
    "-PshadowMinSpreadTicks=$Spread",
    "-PshadowMaxQueue=100000",
    "-PshadowImproveTicks=0",
    "-PshadowEntryOffsetCents=$EntryOffsetCents",
    "-PshadowExitImproveTicks=0",
    "-PshadowExitSpreadCents=$ExitSpreadCents",
    "-PshadowRequoteSeconds=300",
    "-PshadowMaxOrderAgeSeconds=1800"
    "-PshadowTailWindowMinutes=$TailWindowMinutes",
    "-PshadowStaleBookSeconds=300",
    "-PshadowActiveOrderStaleSeconds=1800",
    "-PshadowOrderNotional=25",
    "-PshadowFillVolumeFactor=$FillVolumeFactor",
    "-PshadowMaxCyclesPerMarket=$MaxCyclesPerMarket",
    "-PshadowFlowGuardWindowSeconds=$FlowGuardWindowSeconds",
    "-PshadowFlowGuardMaxSignedFlow=$FlowGuardMaxSignedFlow",
    "-PshadowFlowGuardMinPriceDropCents=$FlowGuardMinPriceDropCents",
    "-PshadowAllowNegRisk=true",
    "-PshadowOutput=$outputPath",
    "--console=plain"
)
$process = Start-Process -FilePath $gradlew -ArgumentList $argsList -WorkingDirectory $backendDir `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
$record = [ordered]@{
    pid = $process.Id
    startedAt = [DateTimeOffset]::Now.ToString("o")
    outputDir = $outputPath
    spread = $Spread
    waitMinutes = $WaitMinutes
    minutes = $Minutes
    maxMarkets = $MaxMarkets
    minRemainingMinutes = $MinRemainingMinutes
    maxRemainingMinutes = $MaxRemainingMinutes
    proxy = $Proxy
    minVolume = $MinVolume
    minPrice = $MinPrice
    maxPrice = $MaxPrice
    minRecentTrades30m = 3
    minRecentNotional30m = 50
    minRecentTradesPerSide30m = 1
    minRecentTrades2h = 20
    minRecentNotional2h = 500
    minRecentTradesPerSide2h = 3
    maxQueueAheadShares = 100000
    improveTicks = 0
    entryOffsetCents = $EntryOffsetCents
    exitImproveTicks = 0
    exitSpreadCents = $ExitSpreadCents
    requoteSeconds = 300
    maxOrderAgeSeconds = 1800
    tailWindowMinutes = $TailWindowMinutes
    staleBookSeconds = 300
    activeOrderStaleBookSeconds = 1800
    orderNotional = 25
    shadowFillVolumeFactor = $FillVolumeFactor
    maxCyclesPerMarket = $MaxCyclesPerMarket
    flowGuardWindowSeconds = $FlowGuardWindowSeconds
    flowGuardMaxSignedFlow = $FlowGuardMaxSignedFlow
    flowGuardMinPriceDropCents = $FlowGuardMinPriceDropCents
    stdout = $stdout
    stderr = $stderr
}
$json = $record | ConvertTo-Json -Depth 4
[IO.File]::WriteAllText((Join-Path $outputPath "watch-process.json"), $json, [Text.UTF8Encoding]::new($false))
Write-Output $json