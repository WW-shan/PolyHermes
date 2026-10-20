param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDir,
    [int]$PollSeconds = 60
)

$ErrorActionPreference = "Stop"
$outputPath = [IO.Path]::GetFullPath($OutputDir)
$watchFile = Join-Path $outputPath "watch-process.json"
if (-not (Test-Path -LiteralPath $watchFile)) { throw "watch-process.json not found: $watchFile" }
$watch = Get-Content -LiteralPath $watchFile -Raw | ConvertFrom-Json
$pids = @()
if ($null -ne $watch.pid) { $pids += [int]$watch.pid }
$stateFile = Join-Path $outputPath "state.json"
Write-Output "watching output=$outputPath pids=$($pids -join ',')"

while ($true) {
    $alive = @($pids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
    if ($alive.Count -eq 0) { break }
    Start-Sleep -Seconds $PollSeconds
}

Write-Output "watched processes exited; waiting for summary"
$summaryFile = Join-Path $outputPath "summary.json"
$deadline = (Get-Date).AddMinutes(10)
while (-not (Test-Path -LiteralPath $summaryFile) -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
}
if (-not (Test-Path -LiteralPath $summaryFile)) {
    Write-Output "summary.json missing; audit may fail, running anyway"
}

$auditScript = Join-Path $PSScriptRoot "audit-event-maker-shadow.ps1"
& $auditScript -OutputDir $outputPath
$code = $LASTEXITCODE
Write-Output "AUDIT_EXIT=$code"
exit $code