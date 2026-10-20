param(
  [int]$Minutes = 120,
  [double]$MinPrice = 0.95,
  [double]$MaxPrice = 0.97,
  [double]$OrderNotional = 100,
  [int]$MaxMarkets = 20,
  [double]$MinVolume = 10000,
  [int]$MaxRemainingMinutes = 43200,
  [string]$Output = "build/research/event-maker-bond-shadow-20261020",
  [string]$Proxy = "http://127.0.0.1:10808",
  [int]$FlowGuardWindowSeconds = 3600,
  [double]$ExitSpreadCents = 100,
  [int]$MaxCyclesPerMarket = 1,
  [double]$FillVolumeFactor = 1,
  [double]$EntryOffsetCents = 0,
  [int]$MinSpreadTicks = 1,
  [int]$MaxQueue = 500,
  [int]$RequoteSeconds = 120,
  [int]$MaxOrderAgeSeconds = 3600
)
$ErrorActionPreference = "Stop"
$backend = Join-Path (Split-Path -Parent $PSScriptRoot) "backend"
$env:HTTPS_PROXY = $Proxy
$env:HTTP_PROXY = $Proxy
$env:NODE_USE_ENV_PROXY = "1"
$argsList = @(
  "eventMakerShadow",
  "-PshadowProxy=$Proxy",
  "-PshadowMinutes=$Minutes",
  "-PshadowMinPrice=$MinPrice",
  "-PshadowMaxPrice=$MaxPrice",
  "-PshadowExitSpreadCents=$ExitSpreadCents",
  "-PshadowMaxCyclesPerMarket=$MaxCyclesPerMarket",
  "-PshadowOrderNotional=$OrderNotional",
  "-PshadowMaxMarkets=$MaxMarkets",
  "-PshadowMinVolume=$MinVolume",
  "-PshadowMinRemainingMinutes=120",
  "-PshadowMaxRemainingMinutes=$MaxRemainingMinutes",
  "-PshadowMinSpreadTicks=$MinSpreadTicks",
  "-PshadowMaxQueue=$MaxQueue",
  "-PshadowImproveTicks=1",
  "-PshadowEntryOffsetCents=$EntryOffsetCents",
  "-PshadowRequoteSeconds=$RequoteSeconds",
  "-PshadowMaxOrderAgeSeconds=$MaxOrderAgeSeconds",
  "-PshadowFlowGuardWindowSeconds=$FlowGuardWindowSeconds",
  "-PshadowFillVolumeFactor=$FillVolumeFactor",
  "-PshadowOutput=$Output",
  "--console=plain"
)
$logDir = Join-Path $backend $Output
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$proc = Start-Process -FilePath (Join-Path $backend "gradlew.bat") -ArgumentList $argsList -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDir "stdout.log") -RedirectStandardError (Join-Path $logDir "stderr.log") -PassThru
$info = [pscustomobject]@{
  startedAt = (Get-Date).ToUniversalTime().ToString("o")
  pid = $proc.Id
  minutes = $Minutes
  minPrice = $MinPrice
  maxPrice = $MaxPrice
  orderNotional = $OrderNotional
  maxMarkets = $MaxMarkets
  exitSpreadCents = $ExitSpreadCents
  maxCyclesPerMarket = $MaxCyclesPerMarket
  fillVolumeFactor = $FillVolumeFactor
  entryOffsetCents = $EntryOffsetCents
  minSpreadTicks = $MinSpreadTicks
  maxQueue = $MaxQueue
  requoteSeconds = $RequoteSeconds
  maxOrderAgeSeconds = $MaxOrderAgeSeconds
  flowGuardWindowSeconds = $FlowGuardWindowSeconds
  output = $logDir
}
$info | ConvertTo-Json -Depth 3 | Set-Content -Path (Join-Path $logDir "run.json") -Encoding UTF8
$info | ConvertTo-Json -Depth 3
