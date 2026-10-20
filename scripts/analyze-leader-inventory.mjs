#!/usr/bin/env node
/* Per-asset inventory reconstruction from the leader's raw activity ledger. */
import fs from 'fs';

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
const input = arg('input');
const output = arg('output');
if (!input || !output) { console.error('required: --input <raw.json> --output <json>'); process.exit(2); }
const raw = JSON.parse(fs.readFileSync(input, 'utf8'));
const trades = (raw.activity ?? []).filter(r => String(r.type ?? '').toUpperCase() === 'TRADE')
  .map(r => ({
    asset: String(r.asset ?? ''),
    conditionId: String(r.conditionId ?? ''),
    title: String(r.title ?? ''),
    outcome: String(r.outcome ?? ''),
    side: String(r.side ?? '').toUpperCase(),
    size: Number(r.size ?? 0),
    price: Number(r.price ?? 0),
    timestamp: Number(r.timestamp ?? 0)
  }))
  .filter(r => r.asset && r.size > 0 && r.price > 0 && r.price < 1)
  .sort((a, b) => a.timestamp - b.timestamp);

const byAsset = new Map();
for (const t of trades) {
  if (!byAsset.has(t.asset)) byAsset.set(t.asset, {
    asset: t.asset, conditionId: t.conditionId, title: t.title, outcome: t.outcome,
    buys: 0, sells: 0, buyShares: 0, sellShares: 0, buyNotional: 0, sellNotional: 0,
    shares: 0, maxShares: 0, minShares: 0, avgSharesSum: 0, avgSharesN: 0,
    firstTs: t.timestamp, lastTs: t.timestamp, buysWhileLong: 0, sellsWhileLong: 0,
    flatAfterFirstSell: false, hadSell: false, everWentFlat: false
  });
  const a = byAsset.get(t.asset);
  a.lastTs = t.timestamp;
  if (t.side === 'BUY') {
    if (a.shares > 1e-9) a.buysWhileLong++;
    a.buys++; a.buyShares += t.size; a.buyNotional += t.price * t.size; a.shares += t.size;
  } else if (t.side === 'SELL') {
    if (a.shares > 1e-9) a.sellsWhileLong++;
    a.hadSell = true;
    a.sells++; a.sellShares += t.size; a.sellNotional += t.price * t.size; a.shares -= t.size;
  }
  a.maxShares = Math.max(a.maxShares, a.shares);
  a.minShares = Math.min(a.minShares, a.shares);
  a.avgSharesSum += a.shares; a.avgSharesN++;
  if (a.hadSell && Math.abs(a.shares) <= 1e-6) a.everWentFlat = true;
}

const rows = [...byAsset.values()].map(a => ({
  ...a,
  finalShares: a.shares,
  avgShares: a.avgSharesN ? a.avgSharesSum / a.avgSharesN : 0,
  holdingSeconds: Math.max(0, a.lastTs - a.firstTs),
  netShares: a.buyShares - a.sellShares,
  bothSides: a.buys > 0 && a.sells > 0
}));
const withBoth = rows.filter(r => r.bothSides);
const withCore = rows.filter(r => r.finalShares > 1e-6);
const everFlat = rows.filter(r => r.everWentFlat);
const multiBuy = rows.filter(r => r.buys > 1);
const multiSell = rows.filter(r => r.sells > 1);
const summary = {
  assets: rows.length,
  trades: trades.length,
  bothSides: withBoth.length,
  bothSidesShare: rows.length ? withBoth.length / rows.length : 0,
  finalLong: withCore.length,
  finalLongShare: rows.length ? withCore.length / rows.length : 0,
  everWentFlat: everFlat.length,
  everWentFlatShare: rows.length ? everFlat.length / rows.length : 0,
  multiBuy: multiBuy.length,
  multiSell: multiSell.length,
  medianBuys: median(rows.map(r => r.buys)),
  medianSells: median(rows.map(r => r.sells)),
  medianMaxShares: median(rows.map(r => r.maxShares)),
  medianAvgShares: median(rows.map(r => r.avgShares)),
  medianFinalShares: median(rows.map(r => r.finalShares)),
  medianHoldingHours: median(rows.map(r => r.holdingSeconds)) / 3600,
  p90HoldingHours: percentile(rows.map(r => r.holdingSeconds), 0.9) / 3600
};
function median(a){ if(!a.length) return 0; const s=[...a].sort((x,y)=>x-y); const m=Math.floor(s.length/2); return s.length%2?s[m]:(s[m-1]+s[m])/2; }
function percentile(a,p){ if(!a.length) return 0; const s=[...a].sort((x,y)=>x-y); return s[Math.min(s.length-1,Math.max(0,Math.floor((s.length-1)*p)))]; }
fs.writeFileSync(output, JSON.stringify({ generatedAt: new Date().toISOString(), input, summary, assets: rows }, null, 2));
console.log(JSON.stringify(summary, null, 2));
console.log('wrote ' + output);