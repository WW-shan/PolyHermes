#!/usr/bin/env node
/* Join the leader's FIFO round trips to historical market close times. */
const fs = require('fs');
const zlib = require('zlib');

function arg(name, fallback) { const i = process.argv.indexOf('--' + name); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback; }
function num(v) { const n = Number(v); return Number.isFinite(n) ? n : 0; }
function mean(a) { return a.length ? a.reduce((x, y) => x + y, 0) / a.length : null; }
function median(a) { if (!a.length) return null; const s = [...a].sort((x, y) => x - y); const m = Math.floor(s.length / 2); return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; }
function bucket(hours) {
  if (hours < 0) return 'after-close';
  if (hours < 2) return '0-2h';
  if (hours < 6) return '2-6h';
  if (hours < 12) return '6-12h';
  if (hours < 24) return '12-24h';
  if (hours < 72) return '1-3d';
  if (hours < 168) return '3-7d';
  if (hours < 336) return '7-14d';
  if (hours < 720) return '14-30d';
  return '>30d';
}
function band(price) {
  if (price < 0.9) return '<0.9';
  if (price < 0.95) return '0.9-0.95';
  if (price < 0.97) return '0.95-0.97';
  if (price < 0.99) return '0.97-0.99';
  return '0.99-1.0';
}
function priceNear(trades, timestamp, horizonSeconds, windowSeconds = 120) {
  const target = timestamp + horizonSeconds;
  for (const t of trades) {
    if (t.timestamp < target) continue;
    if (t.timestamp > target + windowSeconds) return null;
    return t.price;
  }
  return null;
}
function summarize(rows) {
  if (!rows.length) return { count: 0, winRate: null, meanRoi: null, portfolioRoi: null, totalPnl: 0, avgHoldingHours: null, medianHoldingHours: null, markout60Mean: null, markout300Mean: null };
  const costs = rows.reduce((s, r) => s + r.cost, 0);
  const pnl = rows.reduce((s, r) => s + r.pnl, 0);
  return {
    count: rows.length,
    winRate: rows.filter(r => r.pnl > 0).length / rows.length,
    meanRoi: mean(rows.map(r => r.roi)),
    portfolioRoi: costs > 0 ? pnl / costs : null,
    totalPnl: pnl,
    avgHoldingHours: mean(rows.map(r => r.holdingHours)),
    medianHoldingHours: median(rows.map(r => r.holdingHours)),
    markout60Mean: mean(rows.map(r => r.markout60).filter(v => v != null)),
    markout300Mean: mean(rows.map(r => r.markout300).filter(v => v != null)),
  };
}

const input = arg('input', 'backend/build/research/leader-ledger-raw-20261021.json');
const datasetPath = arg('dataset', 'backend/build/research/event-maker-full-v2-7d-20261019-dataset.json.gz');
const output = arg('output', 'backend/build/research/leader-time-to-close-20261021.json');
const raw = JSON.parse(fs.readFileSync(input, 'utf8'));
const dataset = JSON.parse(zlib.gunzipSync(fs.readFileSync(datasetPath)).toString('utf8'));
const marketByCondition = new Map(dataset.markets.map(m => [String(m.conditionId).toLowerCase(), m]));
const activity = Array.isArray(raw.activity) ? raw.activity : [];
const trades = (activity.length ? activity : (raw.trades || []))
  .filter(r => String(r.type ?? 'TRADE').toUpperCase() === 'TRADE')
  .map(r => ({ ...r, timestamp: num(r.timestamp), price: num(r.price), size: num(r.size), side: String(r.side || '').toUpperCase(), conditionId: String(r.conditionId || '').toLowerCase(), asset: String(r.asset || ''), outcomeIndex: Number(r.outcomeIndex) }))
  .filter(r => r.timestamp > 0 && r.price > 0 && r.price < 1 && r.size > 0)
  .sort((a, b) => a.timestamp - b.timestamp);
const lots = new Map();
const allRoundTrips = [];
let buys = 0, mappedBuys = 0, unmatchedSells = 0;
for (const t of trades) {
  if (!t.conditionId) continue;
  if (t.side === 'BUY') {
    buys++;
    if (marketByCondition.has(t.conditionId)) mappedBuys++;
    if (!lots.has(t.asset)) lots.set(t.asset, []);
    lots.get(t.asset).push({ ...t });
  } else if (t.side === 'SELL') {
    let remaining = t.size;
    const queue = lots.get(t.asset) || [];
    while (remaining > 1e-9 && queue.length) {
      const lot = queue[0];
      const matched = Math.min(remaining, lot.size);
      const pnl = (t.price - lot.price) * matched;
      const cost = lot.price * matched;
      allRoundTrips.push({ conditionId: lot.conditionId, asset: lot.asset, outcomeIndex: lot.outcomeIndex, buyAt: lot.timestamp, sellAt: t.timestamp, buyPrice: lot.price, sellPrice: t.price, shares: matched, pnl, cost, roi: pnl / cost, holdingHours: (t.timestamp - lot.timestamp) / 3600 });
      lot.size -= matched;
      remaining -= matched;
      if (lot.size <= 1e-9) queue.shift();
    }
    if (remaining > 1e-9) unmatchedSells += remaining;
  }
}
const mapped = [];
for (const rt of allRoundTrips) {
  const market = marketByCondition.get(rt.conditionId);
  if (!market) continue;
  const hoursToClose = (market.closedTimestamp - rt.buyAt) / 3600;
  const marketTrades = (market.trades || []).filter(t => t.outcomeIndex === rt.outcomeIndex && t.timestamp >= rt.buyAt).sort((a, b) => a.timestamp - b.timestamp);
  const inDatasetWindow = rt.buyAt >= market.closedTimestamp - 7 * 86400;
  const p60 = inDatasetWindow ? priceNear(marketTrades, rt.buyAt, 60) : null;
  const p300 = inDatasetWindow ? priceNear(marketTrades, rt.buyAt, 300, 300) : null;
  mapped.push({ ...rt, tag: market.tag, question: market.question, hoursToClose, timeBucket: bucket(hoursToClose), band: band(rt.buyPrice), markout60: p60 == null ? null : (p60 - rt.buyPrice) / rt.buyPrice, markout300: p300 == null ? null : (p300 - rt.buyPrice) / rt.buyPrice });
}
const byTime = {};
for (const r of mapped) (byTime[r.timeBucket] ??= []).push(r);
const byTimeAndBand = {};
for (const r of mapped) (byTimeAndBand[`${r.band}:${r.timeBucket}`] ??= []).push(r);
const report = {
  generatedAt: new Date().toISOString(), input, datasetPath,
  trades: trades.length, buys, mappedBuys, roundTrips: allRoundTrips.length, mappedRoundTrips: mapped.length, unmappedRoundTrips: allRoundTrips.length - mapped.length, unmatchedSellShares: unmatchedSells,
  byTime: Object.fromEntries(Object.entries(byTime).map(([k, rows]) => [k, summarize(rows)])),
  byTimeAndBand: Object.fromEntries(Object.entries(byTimeAndBand).map(([k, rows]) => [k, summarize(rows)])),
  overall: summarize(mapped),
};
fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
console.log(`mappedBuys=${mappedBuys}/${buys} mappedRoundTrips=${mapped.length}/${allRoundTrips.length}`);
console.log('bucket count winRate meanRoi portfolioRoi medHoldH markout60');
for (const [k, v] of Object.entries(report.byTime)) {
  const pct = x => x == null ? '-' : (100 * x).toFixed(2) + '%';
  console.log(`${k.padEnd(11)} ${String(v.count).padStart(5)} ${pct(v.winRate).padStart(8)} ${pct(v.meanRoi).padStart(9)} ${pct(v.portfolioRoi).padStart(12)} ${v.medianHoldingHours == null ? '-' : v.medianHoldingHours.toFixed(1).padStart(7)} ${pct(v.markout60Mean).padStart(9)}`);
}
console.log(`wrote ${output}`);