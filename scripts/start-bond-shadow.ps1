param(
  [int]$Minutes = 120,
  [double]$MinPrice = 0.95,
  [double]$MaxPrice = 0.97,
  [double]$OrderNotional = 100,
  [int]$MaxMarkets = 20,
  [double]$MinVolume = 10000,
  [int]$MaxRemainingMinutes = 43200,
  [string]$Output = "build/research/event-maker-bond-shadow-20261020",
  [string]$Proxy = "http://127.0.0.1:10808"
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
  "-PshadowExitSpreadCents=100",
  "-PshadowMaxCyclesPerMarket=1",
  "-PshadowOrderNotional=$OrderNotional",
  "-PshadowMaxMarkets=$MaxMarkets",
  "-PshadowMinVolume=$MinVolume",
  "-PshadowMinRemainingMinutes=120",
  "-PshadowMaxRemainingMinutes=$MaxRemainingMinutes",
  "-PshadowMinSpreadTicks=1",
  "-PshadowImproveTicks=1",
  "-PshadowEntryOffsetCents=0",
  "-PshadowRequoteSeconds=120",
  "-PshadowMaxOrderAgeSeconds=3600",
  "-PshadowFillVolumeFactor=1",
  "-PshadowOutput=$Output",
  "--console=plain"
)
$logDir = Join-Path $backend $Output
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$proc = Start-Process -FilePath (Join-Path $backend "gradlew.bat") -ArgumentList $argsList -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDir "stdout.log") -RedirectStandardError (Join-Path $logDir "stderr.log") -PassThru
$info = [pscustomobject]@{ startedAt = (Get-Date).ToUniversalTime().ToString("o"); pid = $proc.Id; minutes = $Minutes; minPrice = $MinPrice; maxPrice = $MaxPrice; output = $logDir }
$info | ConvertTo-Json -Depth 3 | Set-Content -Path (Join-Path $logDir "run.json") -Encoding UTF8
$info | ConvertTo-Json -Depth 3
