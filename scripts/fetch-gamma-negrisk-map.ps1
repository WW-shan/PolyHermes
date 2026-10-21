param(
  [Parameter(Mandatory = $true)][string]$CandidatesPath,
  [Parameter(Mandatory = $true)][string]$Output,
  [string]$Proxy = "http://127.0.0.1:10808",
  [int]$DelayMilliseconds = 150,
  [int]$SaveEvery = 20
)
$ErrorActionPreference = "Stop"
$candidates = Get-Content -LiteralPath $CandidatesPath -Raw -Encoding UTF8 | ConvertFrom-Json
$map = @{}
$failures = @()
$i = 0
foreach ($market in $candidates.markets) {
  $i++
  $conditionId = [string]$market.conditionId
  try {
    $url = "https://gamma-api.polymarket.com/markets?closed=true&condition_ids=$conditionId"
    $rows = Invoke-RestMethod -Uri $url -Proxy $Proxy -TimeoutSec 30
    if ($rows.Count -ne 1) {
      $failures += [pscustomobject]@{ conditionId = $conditionId; error = "row_count_$($rows.Count)" }
    } else {
      $row = $rows[0]
      $map[$conditionId] = [pscustomobject]@{
        negRisk = [bool]$row.negRisk
        question = [string]$row.question
        endDate = [string]$row.endDate
        closed = [bool]$row.closed
        outcomePrices = [string]$row.outcomePrices
      }
    }
  } catch {
    $failures += [pscustomobject]@{ conditionId = $conditionId; error = $_.Exception.Message }
  }
  if ($i % $SaveEvery -eq 0 -or $i -eq $candidates.markets.Count) {
    $payload = [pscustomobject]@{
      generatedAt = (Get-Date).ToUniversalTime().ToString("o")
      source = "gamma-api:/markets?closed=true&condition_ids="
      candidatesPath = $CandidatesPath
      requested = $candidates.markets.Count
      resolved = $map.Count
      failures = $failures
      markets = $map
    }
    $payload | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $Output -Encoding UTF8
    Write-Output "progress=$i/$($candidates.markets.Count) resolved=$($map.Count) failures=$($failures.Count)"
  }
  if ($DelayMilliseconds -gt 0) { Start-Sleep -Milliseconds $DelayMilliseconds }
}
Write-Output "wrote $Output"