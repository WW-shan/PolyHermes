param(
  [Parameter(Mandatory = $true)][int]$WaitPid,
  [int]$WaitJavaPid = 0,
  [Parameter(Mandatory = $true)][string]$Output,
  [string]$Log = "build/research/bond-shadow-watch.log"
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $repo "backend"
$dir = Join-Path $backend $Output
$logPath = Join-Path $backend $Log
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $logPath) | Out-Null

function Write-Log([string]$message) {
  $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"), $message
  Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
  Write-Output $line
}

Write-Log "watch armed: wait wrapper=$WaitPid java=$WaitJavaPid output=$Output"
while ($true) {
  $wrapperAlive = [bool](Get-Process -Id $WaitPid -ErrorAction SilentlyContinue)
  $javaAlive = if ($WaitJavaPid -gt 0) { [bool](Get-Process -Id $WaitJavaPid -ErrorAction SilentlyContinue) } else { $false }
  if (-not $wrapperAlive -and -not $javaAlive) { break }
  Start-Sleep -Seconds 30
}

Write-Log "shadow processes exited; starting offline audit"
if (-not (Test-Path (Join-Path $dir "state.json"))) {
  Write-Log "state.json missing under $dir; nothing to audit"
  exit 1
}
$auditPath = Join-Path $dir "audit-final.json"
& node (Join-Path $PSScriptRoot "audit-bond-shadow.cjs") --dir $dir --output $auditPath *>> $logPath
if ($LASTEXITCODE -ne 0) {
  Write-Log "audit failed with exit code $LASTEXITCODE"
  exit $LASTEXITCODE
}
Write-Log "audit written: $auditPath"
