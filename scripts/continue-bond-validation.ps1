param(
  [int]$WaitPid = 24440,
  [int]$WaitJavaPid = 7304,
  [int]$Minutes = 10080,
  [double]$MinPrice = 0.95,
  [double]$MaxPrice = 0.99,
  [int]$MaxMarkets = 20,
  [double]$MinVolume = 10000,
  [string]$Output = "build/research/event-maker-bond-shadow-7d-20261020",
  [string]$Proxy = "http://127.0.0.1:10808",
  [string]$Log = "build/research/bond-continuation-20261020.log",
  [int]$FlowGuardWindowSeconds = 0
)
$ErrorActionPreference = "Stop"
$backend = Join-Path (Split-Path -Parent $PSScriptRoot) "backend"
$logPath = Join-Path $backend $Log
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $logPath) | Out-Null
function Write-Log([string]$message) {
  $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"), $message
  Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
  Write-Output $line
}
Write-Log "continuation armed: wait wrapper=$WaitPid java=$WaitJavaPid then run tests and start ${Minutes}m BOND shadow flowGuard=$FlowGuardWindowSeconds"
while ((Get-Process -Id $WaitPid -ErrorAction SilentlyContinue) -or (Get-Process -Id $WaitJavaPid -ErrorAction SilentlyContinue)) {
  Start-Sleep -Seconds 30
}
Write-Log "BOND 2h process exited; starting full test suite"
$testLog = Join-Path $backend "build/research/bond-continuation-tests-20261020.log"
& (Join-Path $backend "gradlew.bat") test --console=plain *>> $testLog
if ($LASTEXITCODE -ne 0) {
  Write-Log "full test suite failed; not starting long shadow. See $testLog"
  exit 1
}
Write-Log "full test suite passed; starting ${Minutes}m BOND shadow"
& (Join-Path $PSScriptRoot "start-bond-shadow.ps1") -Minutes $Minutes -MinPrice $MinPrice -MaxPrice $MaxPrice -MaxMarkets $MaxMarkets -MinVolume $MinVolume -Output $Output -Proxy $Proxy -FlowGuardWindowSeconds $FlowGuardWindowSeconds *>> $logPath
if ($LASTEXITCODE -ne 0) {
  Write-Log "long shadow start failed"
  exit 1
}
Write-Log "long BOND shadow start command completed"