param(
  [string]$TagId = "100265",
  [string]$Proxy = "http://127.0.0.1:10808",
  [double]$MinVolume = 10000,
  [double]$MinBid = 0.97,
  [double]$MaxBidExclusive = 0.99,
  [double]$MinHours = 72,
  [double]$MaxHours = 168,
  [switch]$AllowNegRisk,
  [int]$MaxPages = 20,
  [string]$Output = "backend/build/research/bond-candidate-eta-latest.json"
)
$ErrorActionPreference = "Stop"
$base = "https://gamma-api.polymarket.com/markets/keyset?active=true&closed=false&tag_id=$TagId&limit=100&order=volume24hr&ascending=false"
$cursor = $null
$rows = @()
for ($page = 0; $page -lt $MaxPages; $page++) {
  $url = $base
  if ($cursor) { $url += "&after_cursor=" + [uri]::EscapeDataString($cursor) }
  $response = Invoke-RestMethod -Uri $url -Proxy $Proxy -TimeoutSec 30
  if ($response.markets.Count -eq 0) { break }
  $rows += $response.markets
  $cursor = $response.next_cursor
  if (-not $cursor) { break }
}
$now = [DateTimeOffset]::UtcNow
$evaluated = foreach ($row in $rows) {
  $end = [DateTimeOffset]::Parse($row.endDate)
  $hours = ($end - $now).TotalHours
  $bid = [double]$row.bestBid
  $volume = [double]$row.volume24hr
  $negRisk = [bool]$row.negRisk
  $priceOk = $bid -ge $MinBid -and $bid -lt $MaxBidExclusive
  $riskOk = $AllowNegRisk -or (-not $negRisk)
  $eligibleNow = $hours -ge $MinHours -and $hours -le $MaxHours -and $priceOk -and $riskOk -and $volume -ge $MinVolume
  $futureEntryHours = if ($hours -gt $MaxHours -and $priceOk -and $riskOk -and $volume -ge $MinVolume) {
    [math]::Max(0, $hours - $MaxHours)
  } else { $null }
  [pscustomobject]@{
    conditionId = [string]$row.conditionId
    question = [string]$row.question
    hoursToClose = [math]::Round($hours, 2)
    volume24hr = [math]::Round($volume, 2)
    bestBid = $bid
    bestAsk = $row.bestAsk
    negRisk = $negRisk
    priceOk = $priceOk
    withinWindow = $hours -ge $MinHours -and $hours -le $MaxHours
    eligibleNow = $eligibleNow
    hoursUntilFutureWindow = if ($null -eq $futureEntryHours) { $null } else { [math]::Round($futureEntryHours, 2) }
  }
}
$eligible = @($evaluated | Where-Object eligibleNow | Sort-Object hoursToClose)
$next = @($evaluated | Where-Object { $null -ne $_.hoursUntilFutureWindow } | Sort-Object hoursUntilFutureWindow | Select-Object -First 10)
$tooClose = @($evaluated | Where-Object { $_.hoursToClose -gt 0 -and $_.hoursToClose -lt $MinHours -and $_.priceOk -and (-not $_.negRisk -or $AllowNegRisk) -and $_.volume24hr -ge $MinVolume } | Sort-Object hoursToClose)
$report = [pscustomobject]@{
  generatedAt = $now.ToString("o")
  tagId = $TagId
  filters = [pscustomobject]@{ minVolume = $MinVolume; minBid = $MinBid; maxBidExclusive = $MaxBidExclusive; minHours = $MinHours; maxHours = $MaxHours; allowNegRisk = [bool]$AllowNegRisk }
  scanned = $rows.Count
  eligibleNow = $eligible
  nextFutureCandidates = $next
  highPriceTooClose = $tooClose
}
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $Output -Encoding UTF8
Write-Output "scanned=$($rows.Count) eligibleNow=$($eligible.Count) nextFuture=$($next.Count) tooClose=$($tooClose.Count)"
if ($eligible.Count -gt 0) {
  $eligible | Select-Object -First 10 hoursToClose, bestBid, volume24hr, question | Format-Table -AutoSize
} elseif ($next.Count -gt 0) {
  $next | Select-Object -First 10 hoursUntilFutureWindow, hoursToClose, bestBid, volume24hr, question | Format-Table -AutoSize
}
Write-Output "wrote $Output"