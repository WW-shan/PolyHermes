#!/usr/bin/env node
/*
 * Stop-loss study for the adonpoli-style bond-carry / BOND_SCALP family.
 *
 * This is the same all-eligible upper-bound model as analyze-leader-bond-style.cjs,
 * but adds a post-fill stop-loss exit. The leader's disclosed loss exits were
 * 10%-24%, so the goal is to test whether a mechanical stop reduces the
 * catastrophic settlement tail without destroying the +1c/+3c edge.
 *
 * Assumptions:
 * - Entry: resting maker BUY at the last favorite print at (close - horizon).
 *   Every eligible market is assumed filled (upper bound, no queue model).
 * - Target exit: resting maker SELL at entry + N cents when a later print reaches it.
 * - Stop exit: taker SELL when a later print trades at/below entry - stop cents.
 *   Taker fee follows the market category (Politics 4%, Geopolitics 0%; other
 *   prediction-market categories 4%-7%, configurable via --default-taker-fee).
 * - Otherwise hold to settlement.
 *
 * Usage:
 *   node scripts/analyze-bond-stop-loss.cjs \
 *     --dataset backend/build/research/event-maker-full-v2-7d-20261019-dataset.json.gz \
 *     --output backend/build/research/bond-stop-loss-20261021.json
 */
const fs = require('fs');
const zlib = require('zlib');

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
function numArg(name, fallback) {
  const v = Number(arg(name, fallback));
  if (!Number.isFinite(v)) throw new Error(`invalid --${name}`);
  return v;
}
function listArg(name, fallback) {
  return String(arg(name, fallback)).split(',').map(Number).filter(Number.isFinite);
}

const datasetPath = arg('dataset');
const outputPath = arg('output');
if (!datasetPath || !outputPath) {
  console.error('required: --dataset <gz> --output <json>');
  process.exit(2);
}
const horizonDays = numArg('horizon-days', 7);
const notional = numArg('notional', 100);
const defaultTakerFee = numArg('default-taker-fee', 0.04);
const stopSlippageTicks = numArg('stop-slippage-ticks', 1);
const maxHoldingDays = numArg('max-holding-days', 0);
const timeExitSlippageTicks = numArg('time-exit-slippage-ticks', 1);
const bands = listArg('bands', '0.9,0.95,0.97,0.99,1.0000001');
const targets = listArg('targets', '1,2,3');
const stops = listArg('stops', '0,5,10,15,20,25');
const bootstrapRuns = numArg('bootstrap-runs', 5000);

const BAND_LABELS = ['0.9-0.95', '0.95-0.97', '0.97-0.99', '0.99-1.0'];
const TAKER_FEE_BY_TAG = { Politics: 0.04, Geopolitics: 0.0 };

function takerFeeRate(tag) {
  return Object.prototype.hasOwnProperty.call(TAKER_FEE_BY_TAG, tag) ? TAKER_FEE_BY_TAG[tag] : defaultTakerFee;
}

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

function exitFor(market, favorite, entryTimestamp, entryPrice, targetCents, stopCents, tick, won, maxHoldingSeconds, timeSlippageTicks) {
  const target = Math.min(0.999, entryPrice + targetCents / 100);
  const stopPrice = stopCents > 0 ? Math.max(0.001, entryPrice - stopCents / 100) : null;
  for (const trade of market.trades) {
    if (trade.timestamp <= entryTimestamp) continue;
    if (trade.outcomeIndex !== favorite) continue;
    if (trade.price <= 0 || trade.price >= 1) continue;
    if (stopPrice != null && trade.price <= stopPrice) {
      const exitPrice = Math.max(0.001, trade.price - tick * stopSlippageTicks);
      return { price: exitPrice, type: 'STOP', timestamp: trade.timestamp, stopPrice };
    }
    if (trade.price >= target) return { price: target, type: 'SELL', timestamp: trade.timestamp, stopPrice };
  }
  if (maxHoldingSeconds > 0) {
    const deadline = entryTimestamp + maxHoldingSeconds;
    let last = null;
    for (const trade of market.trades) {
      if (trade.timestamp <= entryTimestamp) continue;
      if (trade.timestamp > deadline) break;
      if (trade.outcomeIndex !== favorite) continue;
      if (trade.price <= 0 || trade.price >= 1) continue;
      last = trade;
    }
    if (last) {
      const exitPrice = Math.max(0.001, last.price - tick * timeSlippageTicks);
      return { price: exitPrice, type: 'TIME', timestamp: last.timestamp, stopPrice };
    }
  }
  return { price: won ? 1 : 0, type: 'SETTLE', timestamp: market.closedTimestamp, stopPrice };
}

function mean(a) { return a.length ? a.reduce((x, y) => x + y, 0) / a.length : 0; }
function median(a) {
  if (!a.length) return 0;
  const s = [...a].sort((x, y) => x - y);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}
function percentile(sorted, p) {
  if (!sorted.length) return 0;
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.floor((sorted.length - 1) * p)));
  return sorted[idx];
}
function wilson(p, n, z = 1.96) {
  if (!n) return [0, 0];
  const denom = 1 + z * z / n;
  const center = (p + z * z / (2 * n)) / denom;
  const half = z * Math.sqrt((p * (1 - p) + z * z / (4 * n)) / n) / denom;
  return [Math.max(0, center - half), Math.min(1, center + half)];
}
function bootstrapMeanCi(values, runs) {
  if (values.length < 2) return [values[0] || 0, values[0] || 0];
  const means = new Array(runs);
  for (let r = 0; r < runs; r++) {
    let sum = 0;
    for (let i = 0; i < values.length; i++) sum += values[(Math.random() * values.length) | 0];
    means[r] = sum / values.length;
  }
  means.sort((a, b) => a - b);
  return [percentile(means, 0.025), percentile(means, 0.975)];
}
function maxDrawdownUsd(trades) {
  const ordered = [...trades].sort((a, b) => a.exitTimestamp - b.exitTimestamp);
  let cumulative = 0, peak = 0, maxDd = 0;
  for (const t of ordered) {
    cumulative += t.pnl;
    peak = Math.max(peak, cumulative);
    maxDd = Math.max(maxDd, peak - cumulative);
  }
  return maxDd;
}

const dataset = JSON.parse(zlib.gunzipSync(fs.readFileSync(datasetPath)).toString('utf8'));
const horizonSeconds = horizonDays * 86400;
const refs = [];
for (const market of dataset.markets) {
  const ref = referenceAt(market, market.closedTimestamp - horizonSeconds);
  if (ref) refs.push({ market, ref });
}

const results = [];
for (let b = 0; b < BAND_LABELS.length; b++) {
  const low = bands[b];
  const high = bands[b + 1];
  if (low == null || high == null) continue;
  const eligible = refs.filter(({ ref }) => ref.price >= low && ref.price < high);
  for (const targetCents of targets) {
    for (const stopCents of stops) {
      const trades = [];
      for (const { market, ref } of eligible) {
        const entryTimestamp = market.closedTimestamp - horizonSeconds;
        const won = ref.favorite === market.winnerIndex;
        const tick = market.tickSize > 0 ? market.tickSize : 0.001;
        const resolved = exitFor(market, ref.favorite, entryTimestamp, ref.price, targetCents, stopCents, tick, won, maxHoldingDays > 0 ? maxHoldingDays * 86400 : 0, timeExitSlippageTicks);
        const shares = notional / ref.price;
        const entryFee = 0;
        const exitFee = (resolved.type === 'STOP' || resolved.type === 'TIME')
          ? shares * takerFeeRate(market.tag) * resolved.price * (1 - resolved.price)
          : 0;
        const pnl = (resolved.price - ref.price) * shares - entryFee - exitFee;
        trades.push({
          conditionId: market.conditionId,
          tag: market.tag,
          entryPrice: ref.price,
          exitPrice: resolved.price,
          exitType: resolved.type,
          exitTimestamp: resolved.timestamp,
          won,
          shares,
          pnl,
          roi: pnl / notional,
          fee: entryFee + exitFee,
          holdingSeconds: Math.max(0, resolved.timestamp - entryTimestamp)
        });
      }
      const rois = trades.map(t => t.roi);
      const pnls = trades.map(t => t.pnl);
      const totalPnl = pnls.reduce((a, b) => a + b, 0);
      const wins = rois.filter(r => r > 0).length;
      const losses = rois.filter(r => r < 0).length;
      const winRois = rois.filter(r => r > 0);
      const lossRois = rois.filter(r => r < 0);
      const avgWin = mean(winRois);
      const avgLoss = mean(lossRois);
      const breakEvenLossRate = avgWin > 0 && avgLoss < 0 ? avgWin / (avgWin + Math.abs(avgLoss)) : null;
      const lossRate = trades.length ? losses / trades.length : 0;
      const lossCi = wilson(lossRate, trades.length);
      const roiCi = bootstrapMeanCi(rois, bootstrapRuns);
      results.push({
        band: BAND_LABELS[b],
        targetCents,
        stopCents,
        n: trades.length,
        wins,
        losses,
        winRate: trades.length ? wins / trades.length : 0,
        meanRoi: mean(rois),
        medianRoi: median(rois),
        portfolioRoi: trades.length ? totalPnl / (trades.length * notional) : 0,
        roiCi95: roiCi,
        worstRoi: rois.length ? Math.min(...rois) : 0,
        totalPnl,
        maxDrawdownUsd: maxDrawdownUsd(trades),
        avgWin,
        avgLoss,
        breakEvenLossRate,
        lossRate,
        lossRateCi95: lossCi,
        stopCount: trades.filter(t => t.exitType === 'STOP').length,
        timeExitCount: trades.filter(t => t.exitType === 'TIME').length,
        sellCount: trades.filter(t => t.exitType === 'SELL').length,
        settleCount: trades.filter(t => t.exitType === 'SETTLE').length,
        settleLossCount: trades.filter(t => t.exitType === 'SETTLE' && t.pnl < 0).length,
        avgHoldingDays: trades.length ? mean(trades.map(t => t.holdingSeconds)) / 86400 : 0,
        totalFees: trades.reduce((a, b) => a + b.fee, 0)
      });
    }
  }
}

const report = {
  generatedAt: new Date().toISOString(),
  datasetPath,
  datasetMarkets: dataset.markets.length,
  assumptions: {
    horizonDays,
    notionalPerTrade: notional,
    entry: 'all eligible filled at last favorite print at close - horizon (upper bound)',
    targetExit: 'maker SELL at entry + target cents',
    stopExit: 'taker SELL at first print <= entry - stop cents, plus slippage ticks',
    defaultTakerFeeRate: defaultTakerFee,
    takerFeeByTag: TAKER_FEE_BY_TAG,
    stopSlippageTicks,
    maxHoldingDays,
    timeExitSlippageTicks,
    bootstrapRuns
  },
  results
};
fs.writeFileSync(outputPath, JSON.stringify(report, null, 2));

console.log('band target stop n win% meanRoi roiCI portfolioRoi worst stop settleLoss lossRate lossCI maxDD');
for (const r of results) {
  console.log([
    r.band.padEnd(9),
    String(r.targetCents).padStart(3),
    String(r.stopCents).padStart(3),
    String(r.n).padStart(4),
    (100 * r.winRate).toFixed(1).padStart(5),
    (100 * r.meanRoi).toFixed(2).padStart(7),
    `[${(100 * r.roiCi95[0]).toFixed(2)},${(100 * r.roiCi95[1]).toFixed(2)}]`.padStart(15),
    (100 * r.portfolioRoi).toFixed(2).padStart(7),
    (100 * r.worstRoi).toFixed(1).padStart(7),
    String(r.stopCount).padStart(4),
    String(r.settleLossCount).padStart(3),
    (100 * r.lossRate).toFixed(1).padStart(5),
    `[${(100 * r.lossRateCi95[0]).toFixed(1)},${(100 * r.lossRateCi95[1]).toFixed(1)}]`.padStart(11),
    r.maxDrawdownUsd.toFixed(1).padStart(7)
  ].join(' '));
}
console.log(`wrote ${outputPath}`);