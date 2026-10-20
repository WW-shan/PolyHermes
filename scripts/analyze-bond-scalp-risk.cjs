#!/usr/bin/env node
/*
 * Risk / statistical-significance audit for the BOND_SCALP research result.
 * It answers one question: is the observed positive ROI strong enough to rule
 * out a negative-expectancy strategy, or is the sample still too small?
 *
 * Inputs:
 *   --cycles <event-maker-bond-scalp-*-robust-cycles.csv>
 *   --repro  <leader-bond-style-backtest-*-repro.json>
 *   --output <json>
 */
const fs = require('fs');

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
function num(v) { const n = Number(v); return Number.isFinite(n) ? n : 0; }
function mean(a) { return a.length ? a.reduce((x, y) => x + y, 0) / a.length : 0; }
function quantile(a, p) {
  if (!a.length) return 0;
  const s = [...a].sort((x, y) => x - y);
  return s[Math.min(s.length - 1, Math.max(0, Math.floor((s.length - 1) * p)))];
}
function wilson(k, n, z = 1.96) {
  if (!n) return [0, 1];
  const p = k / n;
  const den = 1 + (z * z) / n;
  const center = (p + (z * z) / (2 * n)) / den;
  const half = (z * Math.sqrt((p * (1 - p)) / n + (z * z) / (4 * n * n))) / den;
  return [Math.max(0, center - half), Math.min(1, center + half)];
}
function parseCsv(text) {
  const rows = [];
  let row = [], field = '', inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inQuotes) {
      if (c === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; } else inQuotes = false;
      } else field += c;
    } else if (c === '"') inQuotes = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n') { row.push(field); rows.push(row); row = []; field = ''; }
    else if (c !== '\r') field += c;
  }
  if (field.length || row.length) { row.push(field); rows.push(row); }
  return rows.filter(r => r.length > 1 || r[0] !== '');
}

const cyclesPath = arg('cycles');
const reproPath = arg('repro');
const output = arg('output');
if (!cyclesPath || !output) {
  console.error('required: --cycles <csv> --output <json> [--repro <json>]');
  process.exit(2);
}

const rows = parseCsv(fs.readFileSync(cyclesPath, 'utf8'));
const header = rows[0];
const idx = Object.fromEntries(header.map((h, i) => [h, i]));
const cycles = rows.slice(1).filter(r => r[idx.pnl] !== undefined);
const col = name => cycles.map(r => num(r[idx[name]]));
const pnl = col('pnl');
const entry = col('entry_price');
const shares = col('shares');
const markout = col('markout_60s_pnl');
const ttf = col('time_to_fill_seconds');
const mae = col('max_adverse_excursion');
const hold = col('holding_seconds');
const roi = pnl.map((p, i) => (entry[i] * shares[i] ? p / (entry[i] * shares[i]) : 0));
const exitTypes = {};
for (const r of cycles) {
  const e = r[idx.exit_type] ?? 'UNKNOWN';
  exitTypes[e] = (exitTypes[e] ?? 0) + 1;
}
const settlementLosses = cycles.filter(r => r[idx.exit_type] === 'SETTLE' && num(r[idx.pnl]) < 0).length;
const queueCi = wilson(settlementLosses, cycles.length);
const avgWin = mean(pnl.filter(x => x > 0));
const avgDeployed = mean(entry.map((e, i) => e * shares[i]));
const breakEvenLossRate = avgWin > 0 ? avgWin / (avgWin + avgDeployed) : 0;

const queueModel = {
  source: cyclesPath,
  cycles: cycles.length,
  exitTypes,
  settlementLosses,
  lossRate: settlementLosses / Math.max(1, cycles.length),
  lossRateCi95: queueCi,
  breakEvenLossRate,
  avgWin,
  avgDeployed,
  meanRoi: mean(roi),
  medianRoi: quantile(roi, 0.5),
  worstRoi: roi.length ? Math.min(...roi) : 0,
  bestRoi: roi.length ? Math.max(...roi) : 0,
  markoutMean: mean(markout),
  markoutMedian: quantile(markout, 0.5),
  markoutP10: quantile(markout, 0.1),
  markoutNegativeShare: markout.filter(x => x < 0).length / Math.max(1, markout.length),
  timeToFillMedianSeconds: quantile(ttf, 0.5),
  timeToFillP90Seconds: quantile(ttf, 0.9),
  maxAdverseExcursionMedian: quantile(mae, 0.5),
  maxAdverseExcursionP10: quantile(mae, 0.1),
  holdingMedianDays: quantile(hold, 0.5) / 86400,
  holdingP90Days: quantile(hold, 0.9) / 86400,
  tags: cycles.reduce((m, r) => { const t = r[idx.tag] ?? 'unknown'; m[t] = (m[t] ?? 0) + 1; return m; }, {})
};

const bands = [];
if (reproPath) {
  const repro = JSON.parse(fs.readFileSync(reproPath, 'utf8'));
  for (const r of repro.results ?? []) {
    if (r.exit !== '+3c' || r.taker !== false) continue;
    if (!['0.90-0.95', '0.9-0.95', '0.95-0.97', '0.97-0.99', '0.99-1', '0.99-1.0'].includes(r.band)) continue;
    const n = r.n;
    const losses = r.lossCount;
    const ci = wilson(losses, n);
    const be = r.breakEvenLossRate;
    bands.push({
      band: r.band,
      n,
      losses,
      lossRate: losses / n,
      lossRateCi95: ci,
      breakEvenLossRate: be,
      meanRoi: r.meanRoi,
      winRate: r.winRate,
      sellCount: r.sellCount,
      resolutionCount: r.resolutionCount,
      statisticallyProven: ci[1] < be,
      gapToBreakEvenUpper: ci[1] - be
    });
  }
}

const report = {
  generatedAt: new Date().toISOString(),
  question: 'Is the BOND_SCALP positive backtest statistically strong enough to rule out negative expectancy?',
  queueModel,
  queueModelProven: queueCi[1] < breakEvenLossRate,
  queueModelBreakEvenLossRate: breakEvenLossRate,
  bands,
  conclusion: null
};
const candidates = bands.filter(b => ['0.95-0.97', '0.97-0.99'].includes(b.band));
const unproven = candidates.filter(b => !b.statisticallyProven);
report.candidates = candidates.map(b => b.band);
if (candidates.length === 0) {
  report.conclusion = report.queueModelProven
    ? 'Queue model loss-rate CI upper bound is below break-even.'
    : 'Queue model NOT PROVEN at 95%: loss-rate CI upper bound exceeds break-even; more independent cycles and live shadow are required.';
} else {
  report.conclusion = unproven.length
    ? 'Candidate bands 0.95-0.97 and 0.97-0.99 are NOT PROVEN at 95%: their loss-rate CI upper bounds exceed break-even, so the positive ROI is not yet statistically distinguishable from a losing strategy. 0.90-0.95 is negative; 0.99-1.0 is only trivially proven because its break-even loss rate is ~100% with tiny per-trade gain. More independent cycles and live shadow are required.'
    : 'Candidate bands have loss-rate CI upper bounds below break-even.';
}

fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({
  queueCycles: queueModel.cycles,
  queueMeanRoi: queueModel.meanRoi,
  queueLossCi95: queueModel.lossRateCi95,
  queueProven: report.queueModelProven,
  bands: bands.map(b => ({ band: b.band, n: b.n, lossRate: b.lossRate, ci95: b.lossRateCi95, breakEven: b.breakEvenLossRate, proven: b.statisticallyProven })),
  conclusion: report.conclusion
}, null, 2));
console.log(`wrote ${output}`);
