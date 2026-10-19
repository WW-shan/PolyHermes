param(
    [string]$BasePath = "backend/build/research/event-maker-queue-grid-full-v2-20261019",
    [int]$BootstrapIterations = 10000,
    [int]$Seed = 20261019,
    [string]$Output = "",
    [string]$DataSuffix = "robust",
    [string]$MarketCsv = "",
    [string]$CycleCsv = "",
    [string]$SelectedConfig = ""
)

$ErrorActionPreference = "Stop"
$invariant = [Globalization.CultureInfo]::InvariantCulture
$base = [IO.Path]::GetFullPath($BasePath)
if ([string]::IsNullOrWhiteSpace($DataSuffix) -and
    ([string]::IsNullOrWhiteSpace($MarketCsv) -or [string]::IsNullOrWhiteSpace($CycleCsv))) {
    throw "DataSuffix cannot be empty unless both MarketCsv and CycleCsv are supplied"
}
$marketFile = if ([string]::IsNullOrWhiteSpace($MarketCsv)) { "$base-$DataSuffix-markets.csv" } else { $MarketCsv }
$cycleFile = if ([string]::IsNullOrWhiteSpace($CycleCsv)) { "$base-$DataSuffix-cycles.csv" } else { $CycleCsv }
$summaryFile = "$base-summary.csv"
foreach ($required in @($marketFile, $cycleFile, $summaryFile)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "required backtest file missing: $required" }
}

function To-Double([object]$Value) {
    return [double]::Parse([string]$Value, $invariant)
}
function F2([double]$Value) { return $Value.ToString("0.00", $invariant) }
function F3([double]$Value) { return $Value.ToString("0.000", $invariant) }
function Median([double[]]$Values) {
    if ($Values.Count -eq 0) { return 0.0 }
    $sorted = @($Values | Sort-Object)
    $middle = [int][math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return [double]$sorted[$middle] }
    return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2.0
}

$filledMarkets = @(Import-Csv -Encoding utf8 -LiteralPath $marketFile | Where-Object { $_.filled -eq "true" })
$cycles = @(Import-Csv -Encoding utf8 -LiteralPath $cycleFile)
$summaries = @(Import-Csv -Encoding utf8 -LiteralPath $summaryFile)
$marketsById = @{}
foreach ($market in $filledMarkets) { $marketsById[$market.condition_id] = $market }
$cutoff = [DateTimeOffset]::Parse("2026-01-01T00:00:00+00:00", $invariant).ToUnixTimeSeconds()

$enriched = foreach ($cycle in $cycles) {
    $market = $marketsById[$cycle.condition_id]
    if ($null -eq $market) { throw "cycle references unknown filled market: $($cycle.condition_id)" }
    $referencePrice = To-Double $market.reference_price
    [pscustomobject]@{
        condition_id = $cycle.condition_id
        tag = [string]$market.tag
        period = if ([long]$market.closed_timestamp -lt $cutoff) { "Train" } else { "Test" }
        price_bucket = if ($referencePrice -lt 0.98) { "0.97-0.98" } else { "0.98-0.99" }
        month = [DateTimeOffset]::FromUnixTimeSeconds([long]$market.closed_timestamp).ToString("yyyy-MM")
        pnl = To-Double $cycle.pnl
        deployed = (To-Double $cycle.entry_price) * (To-Double $cycle.shares)
        settled_loss = ([string]$cycle.exit_type -eq "SETTLE" -and (To-Double $cycle.pnl) -lt 0.0)
        win = (To-Double $cycle.pnl) -gt 0.0
    }
}

function Measure-Groups {
    param([object[]]$Rows, [string]$Segment, [scriptblock]$GroupBy)
    $output = @()
    foreach ($group in ($Rows | Group-Object $GroupBy)) {
        $pnl = ($group.Group | Measure-Object pnl -Sum).Sum
        $deployed = ($group.Group | Measure-Object deployed -Sum).Sum
        $output += [pscustomobject]@{
            Segment = $Segment
            Group = $group.Name
            Cycles = $group.Count
            Markets = @($group.Group.condition_id | Sort-Object -Unique).Count
            PnL = $pnl
            ROI = if ($deployed -gt 0.0) { 100.0 * $pnl / $deployed } else { 0.0 }
            WinRate = 100.0 * @($group.Group | Where-Object win).Count / $group.Count
            SettledLosses = @($group.Group | Where-Object settled_loss).Count
        }
    }
    return $output
}

$segments = @()
$segments += Measure-Groups $enriched "标签 × 期间" { "$($_.tag) / $($_.period)" }
$segments += Measure-Groups $enriched "价格桶 × 期间" { "$($_.price_bucket) / $($_.period)" }
$segments += Measure-Groups @($enriched | Where-Object { $_.tag -eq "Politics" }) "Politics 价格桶 × 期间" {
    "$($_.price_bucket) / $($_.period)"
}

function Bootstrap-Roi {
    param([object[]]$Items, [int]$Iterations, [int]$RandomSeed)
    $pnl = [double[]]@($Items | ForEach-Object { [double]$_.pnl })
    $deployed = [double[]]@($Items | ForEach-Object { [double]$_.deployed })
    $random = [Random]::new($RandomSeed)
    $values = [double[]]::new($Iterations)
    for ($i = 0; $i -lt $Iterations; $i++) {
        [double]$samplePnl = 0.0
        [double]$sampleDeployed = 0.0
        for ($j = 0; $j -lt $pnl.Length; $j++) {
            $index = $random.Next($pnl.Length)
            $samplePnl += $pnl[$index]
            $sampleDeployed += $deployed[$index]
        }
        $values[$i] = 100.0 * $samplePnl / $sampleDeployed
    }
    [array]::Sort($values)
    return [pscustomobject]@{
        N = $pnl.Length
        Observed = 100.0 * (($pnl | Measure-Object -Sum).Sum) / (($deployed | Measure-Object -Sum).Sum)
        P2_5 = $values[[int][math]::Floor(($Iterations - 1) * 0.025)]
        P97_5 = $values[[int][math]::Floor(($Iterations - 1) * 0.975)]
        ProbabilityNonPositive = 100.0 * @($values | Where-Object { $_ -le 0.0 }).Count / $Iterations
    }
}

$marketRows = @($filledMarkets | ForEach-Object {
    [pscustomobject]@{
        pnl = To-Double $_.pnl
        deployed = To-Double $_.deployed_notional
        closed_timestamp = [long]$_.closed_timestamp
    }
})
$bootstrapAll = Bootstrap-Roi $marketRows $BootstrapIterations $Seed
$bootstrapTrain = Bootstrap-Roi @($marketRows | Where-Object { $_.closed_timestamp -lt $cutoff }) $BootstrapIterations ($Seed + 1)
$bootstrapTest = Bootstrap-Roi @($marketRows | Where-Object { $_.closed_timestamp -ge $cutoff }) $BootstrapIterations ($Seed + 2)

$monthClusters = @($marketRows | Group-Object {
    [DateTimeOffset]::FromUnixTimeSeconds([long]$_.closed_timestamp).ToString("yyyy-MM")
} | ForEach-Object {
    [pscustomobject]@{
        pnl = ($_.Group | Measure-Object pnl -Sum).Sum
        deployed = ($_.Group | Measure-Object deployed -Sum).Sum
    }
})
$bootstrapMonths = Bootstrap-Roi $monthClusters $BootstrapIterations ($Seed + 3)

$exclusionRows = @()
foreach ($percent in @(1, 5, 10)) {
    $excludeCount = [int][math]::Ceiling($marketRows.Count * $percent / 100.0)
    $ordered = @($filledMarkets | Sort-Object { To-Double $_.pnl } -Descending)
    $kept = @($ordered | Select-Object -Skip $excludeCount)
    $pnl = ($kept | ForEach-Object { To-Double $_.pnl } | Measure-Object -Sum).Sum
    $deployed = ($kept | ForEach-Object { To-Double $_.deployed_notional } | Measure-Object -Sum).Sum
    $exclusionRows += [pscustomobject]@{
        ExcludedPercent = $percent
        KeptMarkets = $kept.Count
        PnL = $pnl
        ROI = 100.0 * $pnl / $deployed
    }
}

$eligibleConfigs = @($summaries | Where-Object {
    [int]$_.train_filled_markets -ge 20 -and (To-Double $_.train_roi) -gt 0.0
})
if (-not [string]::IsNullOrWhiteSpace($SelectedConfig)) {
    $selected = $summaries | Where-Object { [string]$_.config -eq $SelectedConfig } | Select-Object -First 1
    if ($null -eq $selected) { throw "selected config not found in summary: $SelectedConfig" }
} else {
    $selected = $eligibleConfigs | Sort-Object { To-Double $_.train_roi } -Descending | Select-Object -First 1
    if ($null -eq $selected) { throw "no config has >=20 training fills and positive training ROI" }
}
$testPositive = @($eligibleConfigs | Where-Object { (To-Double $_.test_roi) -gt 0.0 }).Count
$testNegative = @($eligibleConfigs | Where-Object { (To-Double $_.test_roi) -le 0.0 }).Count
$testOrdered = @($eligibleConfigs | Sort-Object { To-Double $_.test_roi } -Descending)
$testConfigNames = @($testOrdered | ForEach-Object { [string]$_.config })
$selectedTestIndex = [array]::IndexOf($testConfigNames, [string]$selected.config)
$selectedTestRank = if ($selectedTestIndex -ge 0) { $selectedTestIndex + 1 } else { -1 }
$trainMedian = Median @($eligibleConfigs | ForEach-Object { 100.0 * (To-Double $_.train_roi) })
$testMedian = Median @($eligibleConfigs | ForEach-Object { 100.0 * (To-Double $_.test_roi) })

$totalPnl = ($enriched | Measure-Object pnl -Sum).Sum
$topCycleCount = [int][math]::Ceiling($enriched.Count * 0.10)
$topCyclePnl = ($enriched | Sort-Object pnl -Descending | Select-Object -First $topCycleCount | Measure-Object pnl -Sum).Sum
$topCycleShare = 100.0 * $topCyclePnl / $totalPnl
$settledLossCount = @($enriched | Where-Object settled_loss).Count
$ruleOfThreeUpper = if ($enriched.Count -gt 0) { 300.0 / $enriched.Count } else { 0.0 }
$breakEvenLossRate = 100.0 * (To-Double $selected.break_even_loss_rate)
$negativeSegmentCount = @($segments | Where-Object { $_.ROI -le 0.0 -or $_.SettledLosses -gt 0 }).Count

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("# Event Maker 稳健性交叉核验")
$lines.Add("")
$selectedArgument = if ([string]::IsNullOrWhiteSpace($SelectedConfig)) { "" } else { " -SelectedConfig `"$SelectedConfig`"" }
$commandLine = '> 生成命令：`.\scripts\analyze-event-maker-robustness.ps1 -BasePath "{0}" -DataSuffix "{1}"{2}<br>' -f $BasePath, $DataSuffix, $selectedArgument
$lines.Add($commandLine)
$lines.Add("> 数据：$($filledMarkets.Count) 个成交市场、$($enriched.Count) 个回合；Bootstrap $BootstrapIterations 次，seed $Seed。<br>")
$lines.Add("> 说明：这是事后稳健性审查，测试集没有参与参数选择。")
$lines.Add("> 数据文件：$marketFile；$cycleFile。<br>")
$lines.Add("")
$lines.Add("## 1. 交叉分段")
$lines.Add("")
$lines.Add("| 分段 | 分组 | 回合 | 市场 | PnL | ROI | 胜率 | 结算亏损 |")
$lines.Add("|---|---|---:|---:|---:|---:|---:|---:|")
foreach ($row in $segments) {
    $lines.Add("| $($row.Segment) | $($row.Group) | $($row.Cycles) | $($row.Markets) | $(F2 $row.PnL) | $(F3 $row.ROI)% | $(F3 $row.WinRate)% | $($row.SettledLosses) |")
}
$lines.Add("")
$lines.Add("## 2. Cluster Bootstrap")
$lines.Add("")
$lines.Add("按成交市场重采样，训练/测试分别报告；另按自然月聚类重采样以计入时间相关性。")
$lines.Add("")
$lines.Add("| 样本 | 簇数 | 观察 ROI | 95% 区间 | ROI<=0 概率 |")
$lines.Add("|---|---:|---:|---:|---:|")
$lines.Add("| 全部市场 | $($bootstrapAll.N) | $(F3 $bootstrapAll.Observed)% | [$(F3 $bootstrapAll.P2_5)%, $(F3 $bootstrapAll.P97_5)%] | $(F2 $bootstrapAll.ProbabilityNonPositive)% |")
$lines.Add("| 训练市场 | $($bootstrapTrain.N) | $(F3 $bootstrapTrain.Observed)% | [$(F3 $bootstrapTrain.P2_5)%, $(F3 $bootstrapTrain.P97_5)%] | $(F2 $bootstrapTrain.ProbabilityNonPositive)% |")
$lines.Add("| 测试市场 | $($bootstrapTest.N) | $(F3 $bootstrapTest.Observed)% | [$(F3 $bootstrapTest.P2_5)%, $(F3 $bootstrapTest.P97_5)%] | $(F2 $bootstrapTest.ProbabilityNonPositive)% |")
$lines.Add("| 自然月聚类 | $($bootstrapMonths.N) | $(F3 $bootstrapMonths.Observed)% | [$(F3 $bootstrapMonths.P2_5)%, $(F3 $bootstrapMonths.P97_5)%] | $(F2 $bootstrapMonths.ProbabilityNonPositive)% |")
$lines.Add("")
$lines.Add("## 3. 集中度与参数选择脆弱性")
$lines.Add("")
$lines.Add("| 排除盈利最高的市场 | 保留市场 | 剩余 PnL | 剩余 ROI |")
$lines.Add("|---:|---:|---:|---:|")
foreach ($row in $exclusionRows) {
    $lines.Add("| $($row.ExcludedPercent)% | $($row.KeptMarkets) | $(F2 $row.PnL) | $(F3 $row.ROI)% |")
}
$lines.Add("")
$lines.Add("- 盈利最高的 10% 回合贡献 $([math]::Round($topCycleShare, 2))% 的总 PnL。")
$lines.Add("- 训练期成交数 >=20 且 ROI>0 的配置：$($eligibleConfigs.Count) 个。")
$lines.Add("- 选中配置：``$($selected.config)``；训练 ROI $(F3 (100.0 * (To-Double $selected.train_roi)))%，测试 ROI $(F3 (100.0 * (To-Double $selected.test_roi)))%。")
$lines.Add("- 这些训练期正收益配置中，测试期为正 $testPositive 个、非正 $testNegative 个（$([math]::Round(100.0 * $testNegative / $eligibleConfigs.Count, 1))% 失败）。")
$lines.Add("- 训练期正收益配置的训练 ROI 中位数 $(F3 $trainMedian)%，测试 ROI 中位数 $(F3 $testMedian)%。")
$lines.Add("- 选中配置在 $($eligibleConfigs.Count) 个训练期正收益配置中的测试排名：$selectedTestRank。")
$lines.Add("- 候选回合中结算亏损 $settledLossCount 次；rule-of-three 的 95% 亏损率上界约 $(F3 $ruleOfThreeUpper)%，盈亏平衡线约 $(F3 $breakEvenLossRate)%。")
if ($settledLossCount -eq 0 -and $ruleOfThreeUpper -gt $breakEvenLossRate) {
    $requiredZeroLossRounds = [math]::Ceiling(300.0 / $breakEvenLossRate)
    $lines.Add("- **统计尾部仍未被证明安全**：零结算亏损样本不足，保守上界高于盈亏平衡线；至少需要约 $requiredZeroLossRounds 个零亏损独立回合才接近该门槛。")
}
$lines.Add("")
$lines.Add("## 4. 解释")
$lines.Add("")
$lines.Add("- 训练集正、测试集正只能说明方向上复现，不能说明 edge 已显著；应结合上面的 cluster bootstrap 区间判断。")
$lines.Add("- 有 $negativeSegmentCount 个分段分组 ROI<=0 或出现结算亏损；不能用测试集结果反选标签、价格桶或参数。")
$lines.Add("- 结论仍是不进入真实资金；先等待严格 Shadow 的真实 fill/cycle/markout 证据。")

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
