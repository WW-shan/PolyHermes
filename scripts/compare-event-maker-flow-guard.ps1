param(
    [Parameter(Mandatory = $true)]
    [string]$Baseline,
    [Parameter(Mandatory = $true)]
    [string]$Guard,
    [string]$Output = ""
)

$ErrorActionPreference = "Stop"
$invariant = [Globalization.CultureInfo]::InvariantCulture
$baselineFile = [IO.Path]::GetFullPath("$Baseline-summary.csv")
$guardFile = [IO.Path]::GetFullPath("$Guard-summary.csv")
foreach ($required in @($baselineFile, $guardFile)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "required summary missing: $required" }
}

function To-Double([object]$Value) {
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) { return 0.0 }
    return [double]::Parse([string]$Value, $invariant)
}
function Median([double[]]$Values) {
    if ($Values.Count -eq 0) { return 0.0 }
    $sorted = @($Values | Sort-Object)
    $middle = [int][math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return [double]$sorted[$middle] }
    return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2.0
}
function F2([double]$Value) { return $Value.ToString("0.00", $invariant) }
function F3([double]$Value) { return $Value.ToString("0.000", $invariant) }

$baselineRows = @(Import-Csv -Encoding utf8 -LiteralPath $baselineFile)
$guardRows = @(Import-Csv -Encoding utf8 -LiteralPath $guardFile)
$baselineById = @{}
foreach ($row in $baselineRows) { $baselineById[[string]$row.config] = $row }
$guardById = @{}
foreach ($row in $guardRows) { $guardById[[string]$row.config] = $row }
$paired = @()
foreach ($name in $baselineById.Keys) {
    if (-not $guardById.ContainsKey($name)) { continue }
    $base = $baselineById[$name]
    $guarded = $guardById[$name]
    $paired += [pscustomobject]@{
        Config = $name
        BaseCycles = [int](To-Double $base.total_cycles)
        GuardCycles = [int](To-Double $guarded.total_cycles)
        BasePnl = To-Double $base.total_pnl
        GuardPnl = To-Double $guarded.total_pnl
        BaseRoi = To-Double $base.total_roi
        GuardRoi = To-Double $guarded.total_roi
        BaseWorst = To-Double $base.worst_market_pnl
        GuardWorst = To-Double $guarded.worst_market_pnl
        Triggers = [int](To-Double $guarded.flow_guard_triggers)
    }
}

function Measure-Set([object[]]$Rows, [bool]$GuardSet) {
    $rois = [double[]]@($Rows | ForEach-Object { To-Double $_.total_roi })
    $pnls = [double[]]@($Rows | ForEach-Object { To-Double $_.total_pnl })
    $catastrophic = @($Rows | Where-Object { (To-Double $_.worst_market_pnl) -le -99.0 }).Count
    [pscustomobject]@{
        Configs = $Rows.Count
        FilledMarkets = ($Rows | ForEach-Object { [int](To-Double $_.filled_markets) } | Measure-Object -Sum).Sum
        Cycles = ($Rows | ForEach-Object { [int](To-Double $_.total_cycles) } | Measure-Object -Sum).Sum
        ProfitableConfigs = @($Rows | Where-Object { (To-Double $_.total_pnl) -gt 0.0 }).Count
        CatastrophicConfigs = $catastrophic
        MeanRoi = ($rois | Measure-Object -Average).Average
        MedianRoi = Median $rois
        TotalPnlAcrossConfigs = ($pnls | Measure-Object -Sum).Sum
        Triggers = if ($GuardSet) { ($Rows | ForEach-Object { [int](To-Double $_.flow_guard_triggers) } | Measure-Object -Sum).Sum } else { 0 }
    }
}

$baseMetrics = Measure-Set $baselineRows $false
$guardMetrics = Measure-Set $guardRows $true
$lostPnlConfigs = @($paired | Where-Object { $_.BasePnl -gt 0.0 -and $_.GuardPnl -lt $_.BasePnl })
$improvedConfigs = @($paired | Where-Object { $_.GuardPnl -gt $_.BasePnl })
$removedCatastrophic = @($paired | Where-Object { $_.BaseWorst -le -99.0 -and $_.GuardWorst -gt -99.0 })
$selectedNames = @(
    "p0.97-0.99_off0.5_spread2.0_q1.0_c3_r0",
    "p0.97-0.99_off0.5_spread1.0_q2.0_c3_r0",
    "p0.97-0.99_off0.5_spread2.0_q1.0_c1_r0",
    "p0.97-0.99_off0.5_spread1.0_q2.0_c1_r0"
)
$selectedRows = @($paired | Where-Object { $selectedNames -contains $_.Config } | Sort-Object Config)

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("# Event Maker 逆势流 Guard 前后对照")
$lines.Add("")
$lines.Add('> 基线：`' + $baselineFile + '`<br>')
$lines.Add('> Guard：`' + $guardFile + '`<br>')
$lines.Add("> 只比较同名参数配置；不是实盘收益证明。")
$lines.Add("")
$lines.Add("## 1. 汇总")
$lines.Add("")
$lines.Add("| 指标 | 基线 | Guard |")
$lines.Add("|---|---:|---:|")
$lines.Add("| 配置数 | $($baseMetrics.Configs) | $($guardMetrics.Configs) |")
$lines.Add("| 成交市场数（跨配置累计） | $($baseMetrics.FilledMarkets) | $($guardMetrics.FilledMarkets) |")
$lines.Add("| 回合数（跨配置累计） | $($baseMetrics.Cycles) | $($guardMetrics.Cycles) |")
$lines.Add("| 正 PnL 配置数 | $($baseMetrics.ProfitableConfigs) | $($guardMetrics.ProfitableConfigs) |")
$lines.Add("| 出现单市场亏损 <= -99 的配置数 | $($baseMetrics.CatastrophicConfigs) | $($guardMetrics.CatastrophicConfigs) |")
$lines.Add("| 配置平均 ROI | $(F3 (100.0 * $baseMetrics.MeanRoi))% | $(F3 (100.0 * $guardMetrics.MeanRoi))% |")
$lines.Add("| 配置 ROI 中位数 | $(F3 (100.0 * $baseMetrics.MedianRoi))% | $(F3 (100.0 * $guardMetrics.MedianRoi))% |")
$lines.Add("| Guard 触发次数 | 0 | $($guardMetrics.Triggers) |")
$lines.Add("")
$lines.Add("## 2. 配对变化")
$lines.Add("")
$lines.Add("- 可比配置：$($paired.Count)")
$lines.Add("- Guard 后 PnL 改善的配置：$($improvedConfigs.Count)")
$lines.Add("- 基线为正、Guard 后 PnL 下降的配置：$($lostPnlConfigs.Count)")
$lines.Add("- 被移除的单市场归零配置：$($removedCatastrophic.Count)")
$lines.Add("- 注意：跨配置累计成交/回合会重复计同一市场，不能当作组合收益。")
$lines.Add("")
$lines.Add("## 3. 重点配置")
$lines.Add("")
$lines.Add("| 配置 | 基线回合 | Guard 回合 | 基线 PnL | Guard PnL | 基线 ROI | Guard ROI | Guard 触发 |")
$lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|")
foreach ($row in $selectedRows) {
    $lines.Add("| $($row.Config) | $($row.BaseCycles) | $($row.GuardCycles) | $(F2 $row.BasePnl) | $(F2 $row.GuardPnl) | $(F3 (100.0 * $row.BaseRoi))% | $(F3 (100.0 * $row.GuardRoi))% | $($row.Triggers) |")
}
$lines.Add("")
$lines.Add("## 4. 结论边界")
$lines.Add("")
$lines.Add("- Guard 只能使用已观察到的成交；与成交同一笔打印出现的卖压不能事前拦截。")
$lines.Add("- 必须结合 Shadow 的真实撤单竞态、盘口年龄和 markout 继续验证。")
$lines.Add("- 即使 Guard 消除了历史样本中的归零，也不能据此直接开启真实资金。")

$report = $lines -join [Environment]::NewLine
if ([string]::IsNullOrWhiteSpace($Output)) {
    Write-Output $report
} else {
    $outputPath = [IO.Path]::GetFullPath($Output)
    $parent = [IO.Path]::GetDirectoryName($outputPath)
    if ($parent) { [IO.Directory]::CreateDirectory($parent) | Out-Null }
    [IO.File]::WriteAllText($outputPath, $report, [Text.UTF8Encoding]::new($false))
    Write-Output $outputPath
}