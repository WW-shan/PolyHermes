#!/usr/bin/env node
/* Statistical tail-risk audit for maker ladder replay results. Read-only. */
const fs = require('fs');

function arg(name, fallback) { const i = process.argv.indexOf('--' + name); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback; }
function num(v) { const n = Number(v); return Number.isFinite(n) ? n : 0; }
function wilson(p, n, z = 1.96) {
  if (!n) return [0, 1];
  const d = 1 + z * z / n;
  const c = (p + z * z / (2 * n)) / d;
  const h = z * Math.sqrt((p * (1 - p) + z * z / (4 * n)) / n) / d;
  return [Math.max(0, c - h), Math.min(1, c + h)];
}
function pct(v) { return v == null ? '-' : (100 * v).toFixed(3) + '%'; }

const input = arg('input');
const output = arg('output');
if (!input) { console.error('required: --input'); process.exit(2); }
const report = JSON.parse(fs.readFileSync(input, 'utf8'));
const rows = (report.results || []).map(r => {
  const lots = num(r.lots);
  const losses = num(r.settleLossCount);
  const lossRate = lots > 0 ? losses / lots : null;
  const ci = lossRate == null ? [0, 1] : wilson(lossRate, lots);
  const breakEven = r.breakEvenLossRate == null
    ? (r.meanRoi > 0 ? num(r.meanRoi) / (num(r.meanRoi) + 1.0) : null)
    : num(r.breakEvenLossRate);
  const margin = breakEven == null ? null : breakEven - ci[1];
  const statisticallyPositive = r.meanRoi > 0 && breakEven != null && ci[1] < breakEven;
  const settleMarkets = r.settleMarkets == null ? null : num(r.settleMarkets);
  const settleLossMarkets = r.settleLossMarkets == null ? null : num(r.settleLossMarkets);
  const marketLossRate = settleMarkets ? settleLossMarkets / settleMarkets : null;
  const marketLossCi95 = settleMarkets ? wilson(marketLossRate, settleMarkets) : [0, 1];
  const marketBreakEven = r.marketBreakEvenLossRate == null ? breakEven : num(r.marketBreakEvenLossRate);
  const marketMargin = marketBreakEven == null ? null : marketBreakEven - marketLossCi95[1];
  const marketStatisticallyPositive = settleMarkets != null && r.meanRoi > 0 &&
    marketBreakEven != null && marketLossCi95[1] < marketBreakEven;
  return {
    band: r.band, tag: r.tag, targetCents: r.targetCents, queueFactor: r.queueFactor,
    minHoursToClose: r.minHoursToClose, maxHoursToClose: r.maxHoursToClose,
    lots, settleLossCount: losses, lossRate, lossCi95: ci,
    settleMarkets, settleLossMarkets, marketLossRate, marketLossCi95,
    meanRoi: r.meanRoi, avgWin: r.avgWin, avgLoss: r.avgLoss,
    breakEvenLossRate: breakEven, edgeMarginVsCiUpper: margin,
    marketBreakEvenLossRate: marketBreakEven, marketEdgeMarginVsCiUpper: marketMargin,
    statisticallyPositive, marketStatisticallyPositive,
  };
});
const payload = { generatedAt: new Date().toISOString(), input, rows };
if (output) fs.writeFileSync(output, JSON.stringify(payload, null, 2) + '\n');
console.log('band tag target q legs mkts settleLossMkts mktLossRate mktLossCI95 meanROI mktBreakEven mktMargin mktVerdict legVerdict');
for (const r of rows) {
  console.log([
    String(r.band).padEnd(9), String(r.tag).padEnd(12), String(r.targetCents).padStart(3),
    String(r.queueFactor).padStart(3), String(r.lots).padStart(5), String(r.settleMarkets ?? '-').padStart(5),
    String(r.settleLossMarkets ?? '-').padStart(3),
    pct(r.marketLossRate).padStart(10),
    `[${pct(r.marketLossCi95[0])},${pct(r.marketLossCi95[1])}]`.padStart(17),
    pct(r.meanRoi).padStart(8), pct(r.marketBreakEvenLossRate).padStart(10),
    pct(r.marketEdgeMarginVsCiUpper).padStart(10),
    r.marketStatisticallyPositive ? 'PASS' : 'FAIL',
    r.statisticallyPositive ? 'PASS' : 'FAIL'
  ].join(' '));
}
if (output) console.log('wrote ' + output);
