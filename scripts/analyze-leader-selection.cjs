const fs = require('fs');
function arg(name, fallback) { const i = process.argv.indexOf('--' + name); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback; }
function num(v) { const n = Number(v); return Number.isFinite(n) ? n : 0; }
function mean(a) { return a.length ? a.reduce((x, y) => x + y, 0) / a.length : null; }
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
function band(p) { if (p < 0.5) return '0-0.5'; if (p < 0.8) return '0.5-0.8'; if (p < 0.9) return '0.8-0.9'; if (p < 0.95) return '0.9-0.95'; if (p < 0.97) return '0.95-0.97'; if (p < 0.99) return '0.97-0.99'; return '0.99-1.0'; }
function sizeBucket(v) { if (v < 100) return '<100'; if (v < 500) return '100-500'; if (v < 2000) return '500-2k'; return '>=2k'; }
const input = arg('input'); const output = arg('output');
if (!input) { console.error('required: --input'); process.exit(2); }
const raw = JSON.parse(fs.readFileSync(input, 'utf8'));
const rows = (raw.closedPositions || []).map(r => ({
  ts: num(r.timestamp), title: r.title, outcome: r.outcome, price: num(r.avgPrice),
  bought: num(r.totalBought), pnl: num(r.realizedPnl),
  category: category(r.title), band: band(num(r.avgPrice)), size: sizeBucket(num(r.totalBought)),
})).filter(r => r.price > 0 && r.price < 1 && r.bought > 0).sort((a, b) => a.ts - b.ts);
const split = Math.floor(rows.length * 0.7);
const train = rows.slice(0, split), test = rows.slice(split);
function stats(group) {
  const bought = group.reduce((s, r) => s + r.bought, 0);
  const pnl = group.reduce((s, r) => s + r.pnl, 0);
  const wins = group.filter(r => r.pnl > 0).length;
  return { count: group.length, bought, pnl, roi: bought ? pnl / bought : 0, winRate: group.length ? wins / group.length : 0 };
}
function groupBy(rows, keyFn) {
  const map = new Map();
  for (const r of rows) { const k = keyFn(r); if (!map.has(k)) map.set(k, []); map.get(k).push(r); }
  return map;
}
const out = { generatedAt: new Date().toISOString(), input, baseline: { train: stats(train), test: stats(test), all: stats(rows) }, buckets: {}, rules: [] };
for (const key of ['band', 'category', 'outcome', 'size']) {
  const tr = groupBy(train, r => r[key]), te = groupBy(test, r => r[key]);
  out.buckets[key] = [...new Set([...tr.keys(), ...te.keys()])].map(k => ({ key: k, train: stats(tr.get(k) || []), test: stats(te.get(k) || []) }))
    .sort((a, b) => b.train.roi - a.train.roi);
}
const keys = ['band', 'category', 'outcome', 'size'];
for (const a of keys) for (const b of keys) if (a < b) {
  const tr = groupBy(train, r => r[a] + '|' + r[b]), te = groupBy(test, r => r[a] + '|' + r[b]);
  for (const k of new Set([...tr.keys(), ...te.keys()])) {
    const t = stats(tr.get(k) || []), v = stats(te.get(k) || []);
    if (t.count >= 20 && v.count >= 8) out.rules.push({ rule: a + '=' + k.split('|')[0] + ' & ' + b + '=' + k.split('|')[1], train: t, test: v });
  }
}
out.rules.sort((a, b) => b.test.roi - a.test.roi);
if (output) fs.writeFileSync(output, JSON.stringify(out, null, 2) + '\n');
console.log('baseline', JSON.stringify(out.baseline));
console.log('top test rules:');
for (const r of out.rules.slice(0, 20)) console.log(JSON.stringify(r));