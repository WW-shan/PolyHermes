#!/usr/bin/env node
/*
 * Reproducible adonpoli-style bond-carry study.
 *
 * Assumptions:
 * - Entry is a resting maker BUY at the last observable favorite price at
 *   (close - horizon). This is an upper-bound fill assumption: every eligible
 *   market is assumed to fill, regardless of queue position.
 * - Hold exits pay 1 or 0 at resolution. Limit exits sell at entry + N cents
 *   when a later print reaches that price; otherwise they hold to resolution.
 * - Taker=true applies the official taker fee formula on entry and exit.
 *   Taker=false models a pure maker fill with the current protocol maker fee 0.
 *
 * Usage:
 *   node scripts/analyze-leader-bond-style.cjs \
 *     --dataset backend/build/research/event-maker-full-v2-7d-20261019-dataset.json.gz \
 *     --output backend/build/research/leader-bond-style-backtest-20261020-repro.json
 */
const fs = require('fs');
const zlib = require('zlib');

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
function numArg(name, fallback) {
  const value = Number(arg(name, fallback));
  if (!Number.isFinite(value)) throw new Error(`invalid --${name}`);
  return value;
}

const BANDS = [
  { label: '0.5-0.8', low: 0.5, high: 0.8 },
  { label: '0.8-0.9', low: 0.8, high: 0.9 },
  { label: '0.9-0.95', low: 0.9, high: 0.95 },
  { label: '0.95-0.97', low: 0.95, high: 0.97 },
  { label: '0.97-0.99', low: 0.97, high: 0.99 },
  { label: '0.99-1.0', low: 0.99, high: 1.0000001 }
];
const EXITS = [
  { label: 'hold', cents: null },
  { label: '+0.5c', cents: 0.5 },
  { label: '+1c', cents: 1.0 },
  { label: '+2c', cents: 2.0 }
];

function referenceAt(market, timestamp) {
  let last0 = null;
  let last1 = null;
  for (const trade of market.trades) {
    if (trade.timestamp > timestamp || trade.price <= 0 || trade.price >= 1) continue;
    if (trade.outcomeIndex === 0) last0 = trade;
    else if (trade.outcomeIndex === 1) last1 = trade;
  }
  const p0 = last0 ? last0.price : (last1 ? 1 - last1.price : null);
  const p1 = last1 ? last1.price : (last0 ? 1 - last0.price : null);
  if (p0 == null || p1 == null) return null;
  return p0 >= p1
    ? { favorite: 0, price: p0, timestamp: last0 ? last0.timestamp : last1.timestamp }
    : { favorite: 1, price: p1, timestamp: last1 ? last1.timestamp : last0.timestamp };
}

function exitFor(market, favorite, entryTimestamp, entryPrice, cents, won) {
  if (cents == null) {
    return { price: won ? 1 : 0, type: 'SETTLE', found: true };
  }
  const target = Math.min(0.999, entryPrice + cents / 100);
  for (const trade of market.trades) {
    if (trade.timestamp <= entryTimestamp) continue;
    if (trade.outcomeIndex !== favorite) continue;
    if (trade.price >= target) return { price: target, type: 'SELL', found: true };
  }
  return { price: won ? 1 : 0, type: 'SETTLE', found: false };
}

function median(values) {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

const datasetPath = arg('dataset');
const outputPath = arg('output');
if (!datasetPath || !outputPath) {
  console.error('required: --dataset <gz> --output <json>');
  process.exit(2);
}
const notional = numArg('notional', 100);
const feeRate = numArg('fee-rate', 0.04);
const maxStaleSeconds = numArg('max-stale-seconds', 0);
const horizons = arg('horizons', '1,3,7').split(',').map(Number).filter(Number.isFinite);

const dataset = JSON.parse(zlib.gunzipSync(fs.readFileSync(datasetPath)).toString('utf8'));
const results = [];
for (const horizonDays of horizons) {
  const horizonSeconds = horizonDays * 86400;
  const refs = [];
  for (const market of dataset.markets) {
    const ref = referenceAt(market, market.closedTimestamp - horizonSeconds);
    if (ref && (maxStaleSeconds <= 0 || ref.timestamp >= market.closedTimestamp - horizonSeconds - maxStaleSeconds)) refs.push({ market, ref });
  }
  for (const band of BANDS) {
    const eligible = refs.filter(({ ref }) => ref.price >= band.low && ref.price < band.high);
    for (const exit of EXITS) {
      for (const taker of [false, true]) {
        const trades = [];
        for (const { market, ref } of eligible) {
          const entryTimestamp = market.closedTimestamp - horizonSeconds;
          const won = ref.favorite === market.winnerIndex;
          const resolved = exitFor(market, ref.favorite, entryTimestamp, ref.price, exit.cents, won);
          const shares = notional / ref.price;
          const entryFee = taker ? shares * feeRate * ref.price * (1 - ref.price) : 0;
          const exitFee = taker ? shares * feeRate * resolved.price * (1 - resolved.price) : 0;
          const pnl = (resolved.price - ref.price) * shares - entryFee - exitFee;
          trades.push({
            conditionId: market.conditionId,
            question: market.question,
            tag: market.tag,
            favorite: ref.favorite,
            entryPrice: ref.price,
            won,
            exitPrice: resolved.price,
            exitType: resolved.type,
            pnl,
            roi: pnl / notional,
            fees: entryFee + exitFee
          });
        }
        const rois = trades.map(t => t.roi);
        const pnls = trades.map(t => t.pnl);
        const totalPnl = pnls.reduce((a, b) => a + b, 0);
        const wins = rois.filter(r => r > 0).length;
        const lossCount = rois.filter(r => r < 0).length;
        const winRois = rois.filter(r => r > 0);
        const lossRois = rois.filter(r => r < 0);
        const avgWin = winRois.length ? winRois.reduce((a, b) => a + b, 0) / winRois.length : 0;
        const avgLoss = lossRois.length ? lossRois.reduce((a, b) => a + b, 0) / lossRois.length : 0;
        const breakEvenLossRate = avgWin > 0 && avgLoss < 0 ? avgWin / (avgWin + Math.abs(avgLoss)) : null;
        results.push({
          horizonDays,
          band: band.label,
          exit: exit.label,
          taker,
          n: trades.length,
          wins,
          winRate: trades.length ? wins / trades.length : 0,
          meanRoi: trades.length ? rois.reduce((a, b) => a + b, 0) / trades.length : 0,
          medRoi: median(rois),
          worst: rois.length ? Math.min(...rois) : 0,
          portfolioRoi: trades.length ? totalPnl / (trades.length * notional) : 0,
          sellCount: trades.filter(t => t.exitType === 'SELL').length,
          resolutionCount: trades.filter(t => t.exitType === 'SETTLE').length,
          totalFees: trades.reduce((a, b) => a + b.fees, 0),
          avgFee: trades.length ? trades.reduce((a, b) => a + b.fees, 0) / trades.length : 0,
          lossCount,
          lossRate: trades.length ? lossCount / trades.length : 0,
          avgWin,
          avgLoss,
          breakEvenLossRate,
          worstTrade: trades.length ? trades.reduce((a, b) => (a.roi <= b.roi ? a : b)) : null,
          sample: trades.slice(0, 5)
        });
      }
    }
  }
}

const report = {
  generatedAt: new Date().toISOString(),
  datasetMarkets: dataset.markets.length,
  datasetPath,
  assumptions: {
    entry: 'resting maker BUY at last favorite print at close - horizon; all eligible assumed filled',
    exits: 'hold to resolution, or limit sell at entry + N cents if a later print reaches it',
    notionalPerTrade: notional,
    takerFeeRate: feeRate,
    makerFeeRate: 0,
    maxStaleSeconds
  },
  results
};
fs.writeFileSync(outputPath, JSON.stringify(report, null, 2));
console.log('horizon band exit taker n wins winRate meanRoi worst portfolioRoi');
for (const r of results) {
  if (r.horizonDays !== 7) continue;
  console.log(`${String(r.horizonDays).padStart(2)}d ${r.band.padEnd(9)} ${r.exit.padEnd(6)} ${String(r.taker).padEnd(5)} ${String(r.n).padStart(4)} ${String(r.wins).padStart(4)} ${(100 * r.winRate).toFixed(1).padStart(6)}% ${(100 * r.meanRoi).toFixed(2).padStart(8)}% ${(100 * r.worst).toFixed(2).padStart(8)}% ${(100 * r.portfolioRoi).toFixed(2).padStart(9)}%`);
}
console.log(`wrote ${outputPath}`);