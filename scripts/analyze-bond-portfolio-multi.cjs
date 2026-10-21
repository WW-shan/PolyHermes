const fs = require('fs');
function arg(name, fallback) { const i = process.argv.indexOf('--' + name); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback; }
function num(v) { const n = Number(v); return Number.isFinite(n) ? n : 0; }
function numArg(name, fallback) { const n = Number(arg(name, fallback)); if (!Number.isFinite(n)) throw new Error('invalid --' + name); return n; }
function mean(a) { return a.length ? a.reduce((x, y) => x + y, 0) / a.length : null; }
function wilson(p, n, z = 1.96) { if (!n) return [0, 1]; const d = 1 + z * z / n; const c = (p + z * z / (2 * n)) / d; const h = z * Math.sqrt((p * (1 - p) + z * z / (4 * n)) / n) / d; return [Math.max(0, c - h), Math.min(1, c + h)]; }

const input = arg('input');
const output = arg('output');
const capital = numArg('capital', 3000);
const maxMarkets = numArg('max-markets', 12);
const target = numArg('target-cents', 4);
// pairs: "0.97-0.99:Geopolitics,0.99-1.0:Geopolitics,0.99-1.0:Politics"
const pairsRaw = arg('pairs', '');
if (!input) { console.error('required: --input'); process.exit(2); }
const pairs = pairsRaw ? pairsRaw.split(',').map(s => s.trim()).filter(Boolean).map(s => {
  const idx = s.lastIndexOf(':');
  return { band: s.slice(0, idx), tag: s.slice(idx + 1) };
}) : null;
const key = (band, tag) => band + ':' + tag;

const raw = JSON.parse(fs.readFileSync(input, 'utf8'));
const all = (raw.lots || [])
  .filter(l => (!pairs || pairs.some(p => p.band === l.band && p.tag === l.tag)) && num(l.targetCents) === target)
  .map(l => ({ ...l, entryTs: num(l.entryTs), exitTs: num(l.exitTs), entryPrice: num(l.entryPrice), shares: num(l.shares), pnl: num(l.pnl) }))
  .sort((a, b) => a.entryTs - b.entryTs || a.exitTs - b.exitTs);

function simulate(lots) {
  const active = [];
  const included = [];
  const skipped = [];
  let maxCapital = 0;
  let maxActiveMarkets = 0;
  const capitalAtEntries = [];
  for (const lot of lots) {
    for (let i = active.length - 1; i >= 0; i--) if (active[i].exitTs <= lot.entryTs) active.splice(i, 1);
    const cost = lot.entryPrice * lot.shares;
    const activeCapital = active.reduce((s, l) => s + l.entryPrice * l.shares, 0);
    const markets = new Set(active.map(l => l.conditionId));
    const newMarket = !markets.has(lot.conditionId);
    if (activeCapital + cost <= capital + 1e-6 && markets.size + (newMarket ? 1 : 0) <= maxMarkets) {
      active.push(lot); included.push(lot);
      maxCapital = Math.max(maxCapital, activeCapital + cost);
      maxActiveMarkets = Math.max(maxActiveMarkets, markets.size + (newMarket ? 1 : 0));
    } else skipped.push(lot);
    capitalAtEntries.push(active.reduce((s, l) => s + l.entryPrice * l.shares, 0));
  }
  const pnl = included.reduce((s, l) => s + l.pnl, 0);
  const deployed = included.reduce((s, l) => s + l.entryPrice * l.shares, 0);
  const tail = included.filter(l => l.exitType === 'SETTLE' || l.exitType === 'FLATTEN');
  const tailByMarket = new Map(), tailCostByMarket = new Map();
  for (const l of tail) {
    tailByMarket.set(l.conditionId, (tailByMarket.get(l.conditionId) || 0) + l.pnl);
    tailCostByMarket.set(l.conditionId, (tailCostByMarket.get(l.conditionId) || 0) + l.entryPrice * l.shares);
  }
  const tailRois = [...tailByMarket.entries()].map(([id, p]) => p / (tailCostByMarket.get(id) || 1));
  const tailLosses = tailRois.filter(x => x < 0).length;
  const tailRate = tailRois.length ? tailLosses / tailRois.length : 0;
  const span = lots.length ? Math.max(...lots.map(l => l.exitTs)) - Math.min(...lots.map(l => l.entryTs)) : 0;
  const avgCapital = capitalAtEntries.length ? mean(capitalAtEntries) : 0;
  return {
    lotsAvailable: lots.length, lotsIncluded: included.length, lotsSkipped: skipped.length,
    deployed, pnl, meanRoi: deployed ? pnl / deployed : 0,
    maxConcurrentCapital: maxCapital, maxConcurrentMarkets: maxActiveMarkets,
    avgCapitalAtEntries: avgCapital, capitalUtilization: capital ? avgCapital / capital : null,
    spanDays: span / 86400,
    annualizedPnl: span > 0 ? pnl * 365 * 86400 / span : null,
    annualizedRoiOnCapital: capital && span > 0 ? (pnl * 365 * 86400 / span) / capital : null,
    lotsPerYear: span > 0 ? included.length * 365 * 86400 / span : null,
    tailExitMarkets: tailByMarket.size, tailExitLossMarkets: tailLosses,
    tailExitLossRate: tailRate, tailExitLossCi95: wilson(tailRate, tailByMarket.size),
  };
}

const result = {
  generatedAt: new Date().toISOString(), input, filters: { pairs: pairs || 'ALL', targetCents: target },
  capital, maxMarkets, overall: simulate(all),
  byPair: Object.fromEntries([...new Set(all.map(l => key(l.band, l.tag)))].sort().map(k => [k, simulate(all.filter(l => key(l.band, l.tag) === k))])),
};
if (output) fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result, null, 2));
