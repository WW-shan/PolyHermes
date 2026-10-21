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
  const breakEven = r.tailExitBreakEvenLossRate != null
    ? num(r.tailExitBreakEvenLossRate)
    : (r.breakEvenLossRate == null
      ? (r.meanRoi > 0 ? num(r.meanRoi) / (num(r.meanRoi) + 1.0) : null)
      : num(r.breakEvenLossRate));
  const margin = breakEven == null ? null : breakEven - ci[1];
  const statisticallyPositive = r.meanRoi > 0 && breakEven != null && ci[1] < breakEven;
  const settleMarkets = r.settleMarkets == null ? null : num(r.settleMarkets);
  const settleLossMarkets = r.settleLossMarkets == null ? null : num(r.settleLossMarkets);
  const hasTailCluster = r.tailExitMarkets != null;
  const tailExitMarkets = hasTailCluster ? num(r.tailExitMarkets) : settleMarkets;
  const tailExitLossMarkets = hasTailCluster ? num(r.tailExitLossMarkets) : settleLossMarkets;
  const tailExitLossRate = tailExitMarkets ? tailExitLossMarkets / tailExitMarkets : null;
  const tailExitLossCi95 = tailExitMarkets ? wilson(tailExitLossRate, tailExitMarkets) : [0, 1];
  const tailBreakEven = r.tailExitBreakEvenLossRate != null
    ? num(r.tailExitBreakEvenLossRate)
    : (r.marketBreakEvenLossRate == null ? breakEven : num(r.marketBreakEvenLossRate));
  const tailMargin = tailBreakEven == null ? null : tailBreakEven - tailExitLossCi95[1];
  const tailStatisticallyPositive = tailExitMarkets != null && r.meanRoi > 0 &&
    tailBreakEven != null && tailExitLossCi95[1] < tailBreakEven;
  return {
    band: r.band, tag: r.tag, targetCents: r.targetCents, queueFactor: r.queueFactor,
    minHoursToClose: r.minHoursToClose, maxHoursToClose: r.maxHoursToClose,
    lots, settleLossCount: losses, lossRate, lossCi95: ci,
    settleMarkets, settleLossMarkets,
    tailExitMarkets, tailExitLossMarkets, tailExitLossRate, tailExitLossCi95,
    marketLossRate: tailExitLossRate, marketLossCi95: tailExitLossCi95,
    meanRoi: r.meanRoi, avgWin: r.tailExitAvgWin ?? r.avgWin, avgLoss: r.tailExitAvgLoss ?? r.avgLoss,
    breakEvenLossRate: breakEven, edgeMarginVsCiUpper: margin,
    marketBreakEvenLossRate: tailBreakEven, marketEdgeMarginVsCiUpper: tailMargin,
    tailExitBreakEvenLossRate: tailBreakEven, tailStressLossRoi: r.tailStressLossRoi,
    statisticallyPositive, marketStatisticallyPositive: tailStatisticallyPositive,
  };
});
const payload = { generatedAt: new Date().toISOString(), input, rows };
if (output) fs.writeFileSync(output, JSON.stringify(payload, null, 2) + '\n');
console.log('band tag target q legs tailMkts tailLossMkts tailLossRate tailLossCI95 meanROI tailBreakEven tailMargin tailVerdict legVerdict');
for (const r of rows) {
  console.log([
    String(r.band).padEnd(9), String(r.tag).padEnd(12), String(r.targetCents).padStart(3),
    String(r.queueFactor).padStart(3), String(r.lots).padStart(5), String(r.tailExitMarkets ?? '-').padStart(7),
    String(r.tailExitLossMarkets ?? '-').padStart(3),
    pct(r.tailExitLossRate).padStart(10),
    `[${pct(r.tailExitLossCi95[0])},${pct(r.tailExitLossCi95[1])}]`.padStart(17),
    pct(r.meanRoi).padStart(8), pct(r.tailExitBreakEvenLossRate).padStart(10),
    pct(r.marketEdgeMarginVsCiUpper).padStart(10),
    r.marketStatisticallyPositive ? 'PASS' : 'FAIL',
    r.statisticallyPositive ? 'PASS' : 'FAIL'
  ].join(' '));
}
if (output) console.log('wrote ' + output);
