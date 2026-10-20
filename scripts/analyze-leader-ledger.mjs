#!/usr/bin/env node
/*
 * FIFO inventory ledger for a Polymarket leader using the public snapshot.
 * It separates:
 *   - matched round-trip PnL (BUY -> SELL)
 *   - settlement PnL on remaining inventory (closed positions marked 0/1)
 *   - open-inventory mark-to-market
 * and reconciles against the Data API closed-position and open-position summaries.
 */
import fs from 'node:fs';

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
function num(value) { const n = Number(value); return Number.isFinite(n) ? n : 0; }
function median(values) { if (!values.length) return 0; const s=[...values].sort((a,b)=>a-b); const m=Math.floor(s.length/2); return s.length%2?s[m]:(s[m-1]+s[m])/2; }
function percentile(values, p) { if (!values.length) return 0; const s=[...values].sort((a,b)=>a-b); return s[Math.min(s.length-1,Math.max(0,Math.floor((s.length-1)*p)))]; }
function category(title) {
  const s = String(title ?? '').toLowerCase();
  if (/iran|israel|ukraine|russia|china|taiwan|venezuela|war|ceasefire|nato|military|strike|hormuz|mandeb|hezbollah|hamas|gaza|syria|yemen|north korea|kim jong|putin|zelensky|netanyahu|khamenei/.test(s)) return 'geopolitics';
  if (/fed|interest rate|inflation|gdp|recession|tariff|economy|treasury|stock|s&p|nasdaq|earnings|oil|gold/.test(s)) return 'macro_finance';
  if (/bitcoin|ethereum|xrp|solana|crypto|stablecoin|defi|token|blockchain/.test(s)) return 'crypto';
  if (/election|president|senate|congress|prime minister|nominee|trump|biden|harris|democrat|republican|parliament|mayor|governor/.test(s)) return 'politics';
  if (/nba|nfl|mlb|nhl|soccer|football|tennis|ufc|f1|olympic|world cup|champions league|match|game/.test(s)) return 'sports';
  if (/oscar|grammy|movie|music|celebrity|tweet|musk/.test(s)) return 'culture';
  return 'other';
}
function band(price) {
  if (price < 0.5) return '0-0.5';
  if (price < 0.8) return '0.5-0.8';
  if (price < 0.9) return '0.8-0.9';
  if (price < 0.95) return '0.9-0.95';
  if (price < 0.97) return '0.95-0.97';
  if (price < 0.99) return '0.97-0.99';
  return '0.99-1.0';
}
function roundTripsFor(rows) {
  return rows.map(r => ({ ...r, pnl: Number(r.pnl.toFixed(6)) })).sort((a,b)=>b.pnl-a.pnl);
}

const input = arg('input');
const output = arg('output');
if (!input || !output) {
  console.error('required: --input <snapshot.json> --output <ledger.json>');
  process.exit(2);
}
const raw = JSON.parse(fs.readFileSync(input, 'utf8'));
const activityTrades = (raw.activity ?? []).filter(r => String(r.type ?? "").toUpperCase() === "TRADE");
const trades = [...(activityTrades.length ? activityTrades : (raw.trades ?? []))].sort((a,b)=>a.timestamp-b.timestamp);
const closed = raw.closedPositions ?? [];
const positions = raw.positions ?? [];
const activity = raw.activity ?? [];
const closedByAsset = new Map(closed.map(r => [String(r.asset), r]));
const positionByAsset = new Map(positions.map(r => [String(r.asset), r]));

const lots = new Map();
const roundTrips = [];
const settlements = [];
let buyNotional = 0;
let sellNotional = 0;
let unmatchedSellShares = 0;

for (const t of trades) {
  const asset = String(t.asset ?? '');
  if (!asset) continue;
  const price = num(t.price);
  const size = num(t.size);
  if (price <= 0 || price >= 1 || size <= 0) continue;
  const side = String(t.side ?? '').toUpperCase();
  if (side === 'BUY') {
    buyNotional += price * size;
    if (!lots.has(asset)) lots.set(asset, []);
    lots.get(asset).push({ asset, conditionId: t.conditionId, title: t.title, outcome: t.outcome, shares: size, price, timestamp: t.timestamp });
  } else if (side === 'SELL') {
    sellNotional += price * size;
    let remaining = size;
    const queue = lots.get(asset) ?? [];
    while (remaining > 1e-9 && queue.length) {
      const lot = queue[0];
      const matched = Math.min(remaining, lot.shares);
      const pnl = (price - lot.price) * matched;
      roundTrips.push({
        conditionId: t.conditionId, asset, title: t.title, outcome: t.outcome,
        buyPrice: lot.price, sellPrice: price, shares: matched,
        buyAt: lot.timestamp, sellAt: t.timestamp, holdingSeconds: t.timestamp - lot.timestamp,
        pnl, roi: (price - lot.price) / lot.price
      });
      lot.shares -= matched;
      remaining -= matched;
      if (lot.shares <= 1e-9) queue.shift();
    }
    if (remaining > 1e-9) unmatchedSellShares += remaining;
  }
}

let openShares = 0;
let openCost = 0;
let settlementPnl = 0;
let openMarkPnl = 0;
const openLots = [];
for (const [asset, queue] of lots.entries()) {
  if (!queue.length) continue;
  const c = closedByAsset.get(asset);
  const p = positionByAsset.get(asset);
  const mark = c ? num(c.curPrice) : (p ? num(p.curPrice) : queue[queue.length - 1].price);
  const isSettled = !!c && (mark === 0 || mark === 1);
  for (const lot of queue) {
    openShares += lot.shares;
    openCost += lot.price * lot.shares;
    const pnl = (mark - lot.price) * lot.shares;
    if (isSettled) {
      const endTs = c.endDate ? Math.floor(Date.parse(c.endDate) / 1000) : lot.timestamp;
      settlementPnl += pnl;
      settlements.push({
        conditionId: lot.conditionId, asset, title: lot.title, outcome: lot.outcome,
        buyPrice: lot.price, markPrice: mark, shares: lot.shares,
        buyAt: lot.timestamp, settledAt: endTs, holdingSeconds: Math.max(0, endTs - lot.timestamp),
        pnl
      });
    } else {
      openMarkPnl += pnl;
      openLots.push({
        conditionId: lot.conditionId, asset, title: lot.title, outcome: lot.outcome,
        buyPrice: lot.price, markPrice: mark, shares: lot.shares,
        buyAt: lot.timestamp, pnl
      });
    }
  }
}

const matchedPnl = roundTrips.reduce((s,r)=>s+r.pnl,0);
const matchedCost = roundTrips.reduce((s,r)=>s+r.buyPrice*r.shares,0);
const allClosedPnl = [...roundTrips, ...settlements].reduce((s,r)=>s+r.pnl,0);
const allClosedCost = [...roundTrips, ...settlements].reduce((s,r)=>s+r.buyPrice*r.shares,0);
const allEconomicPnl = allClosedPnl + openMarkPnl;
const closedApiRealized = closed.reduce((s,r)=>s+num(r.realizedPnl),0);
const closedApiBought = closed.reduce((s,r)=>s+num(r.totalBought),0);
const openApiCashPnl = positions.reduce((s,r)=>s+num(r.cashPnl),0);
const openApiCurrentValue = positions.reduce((s,r)=>s+num(r.currentValue),0);
const openApiInitialValue = positions.reduce((s,r)=>s+num(r.initialValue),0);

function aggregate(items, keyFn) {
  const m = new Map();
  for (const item of items) {
    const key = keyFn(item);
    const e = m.get(key) ?? { key, count: 0, shares: 0, cost: 0, pnl: 0, wins: 0, losses: 0 };
    e.count += 1; e.shares += item.shares; e.cost += item.buyPrice * item.shares; e.pnl += item.pnl;
    if (item.pnl > 0) e.wins += 1; if (item.pnl < 0) e.losses += 1;
    m.set(key, e);
  }
  return [...m.values()].map(e => ({ ...e, roi: e.cost > 0 ? e.pnl / e.cost : 0, winRate: e.count ? e.wins / e.count : 0 }))
    .sort((a,b)=>a.key.localeCompare(b.key));
}

const allClosed = [...roundTrips, ...settlements];
const activityByType = {};
for (const row of activity) {
  const type = String(row.type ?? 'UNKNOWN');
  const e = activityByType[type] ?? { count: 0, usdc: 0 };
  e.count += 1; e.usdc += num(row.usdcSize); activityByType[type] = e;
}
const holdingSeconds = allClosed.map(r=>r.holdingSeconds).filter(Number.isFinite);
const report = {
  generatedAt: new Date().toISOString(),
  address: raw.address,
  source: raw.source,
  counts: raw.counts,
  ledger: {
    buyNotional, sellNotional,
    matchedShares: roundTrips.reduce((s,r)=>s+r.shares,0),
    matchedPnl, matchedRoi: matchedCost > 0 ? matchedPnl / matchedCost : 0,
    settlementPnl, settlementShares: settlements.reduce((s,r)=>s+r.shares,0),
    allClosedPnl, allClosedRoi: allClosedCost > 0 ? allClosedPnl / allClosedCost : 0,
    openShares, openCost, openMarkPnl, allEconomicPnl,
    unmatchedSellShares,
    roundTripCount: roundTrips.length,
    settlementCount: settlements.length,
    openLotCount: openLots.length
  },
  reconciliation: {
    closedApiRealized, closedApiBought, closedApiRoi: closedApiBought > 0 ? closedApiRealized / closedApiBought : 0,
    openApiCashPnl, openApiCurrentValue, openApiInitialValue,
    ledgerVsClosedApiPnl: allClosedPnl - closedApiRealized
  },
  activity: activityByType,
  bands: aggregate(allClosed, r => band(r.buyPrice)),
  outcomes: aggregate(allClosed, r => r.outcome || 'unknown'),
  categories: aggregate(allClosed, r => category(r.title)),
  holding: {
    count: holdingSeconds.length,
    medianSeconds: median(holdingSeconds),
    p90Seconds: percentile(holdingSeconds, 0.9),
    under1hShare: holdingSeconds.filter(s=>s<3600).length / Math.max(1,holdingSeconds.length),
    under1dShare: holdingSeconds.filter(s=>s<86400).length / Math.max(1,holdingSeconds.length),
    oneTo7dShare: holdingSeconds.filter(s=>s>=86400 && s<7*86400).length / Math.max(1,holdingSeconds.length),
    over7dShare: holdingSeconds.filter(s=>s>=7*86400).length / Math.max(1,holdingSeconds.length)
  },
  topRoundTrips: roundTripsFor(roundTrips).slice(0, 15),
  worstRoundTrips: roundTripsFor(roundTrips).slice(-15).reverse(),
  topSettlements: settlements.map(r=>({...r,pnl:Number(r.pnl.toFixed(6))})).sort((a,b)=>b.pnl-a.pnl).slice(0,10),
  worstSettlements: settlements.map(r=>({...r,pnl:Number(r.pnl.toFixed(6))})).sort((a,b)=>a.pnl-b.pnl).slice(0,10),
  openLots: openLots.sort((a,b)=>b.pnl-a.pnl).slice(0,20)
};
fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({
  counts: report.counts,
  matchedPnl: report.ledger.matchedPnl,
  settlementPnl: report.ledger.settlementPnl,
  openMarkPnl: report.ledger.openMarkPnl,
  allClosedPnl: report.ledger.allClosedPnl,
  closedApiRealized: report.reconciliation.closedApiRealized,
  openApiCashPnl: report.reconciliation.openApiCashPnl,
  openCost: report.ledger.openCost,
  openShares: report.ledger.openShares
}, null, 2));
console.log(`wrote ${output}`);