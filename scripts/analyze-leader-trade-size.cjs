#!/usr/bin/env node
/* Entry markout by leader trade size. Read-only research. */
const fs = require('fs');
function arg(name, fallback) { const i = process.argv.indexOf('--' + name); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback; }
function num(v) { const n = Number(v); return Number.isFinite(n) ? n : 0; }
function mean(a) { return a.length ? a.reduce((x, y) => x + y, 0) / a.length : null; }
const input = arg('input'); const output = arg('output');
if (!input) { console.error('required: --input <snapshot.json> [--output <json>]'); process.exit(2); }
const raw = JSON.parse(fs.readFileSync(input, 'utf8'));
const activity = (raw.activity ?? []).filter(r => String(r.type ?? '').toUpperCase() === 'TRADE');
const trades = activity.length ? activity : (raw.trades ?? []);
const byAsset = new Map();
for (const t of trades) {
  const asset = String(t.asset ?? ''); const price = num(t.price);
  if (!asset || price <= 0 || price >= 1) continue;
  if (!byAsset.has(asset)) byAsset.set(asset, []);
  byAsset.get(asset).push({ timestamp: num(t.timestamp), price });
}
for (const arr of byAsset.values()) arr.sort((a, b) => a.timestamp - b.timestamp);
function firstAtOrAfter(arr, ts) { let lo = 0, hi = arr.length - 1, ans = null; while (lo <= hi) { const mid = (lo + hi) >> 1; if (arr[mid].timestamp >= ts) { ans = arr[mid].price; hi = mid - 1; } else lo = mid + 1; } return ans; }
function sizeBucket(size) { if (size < 100) return '<100'; if (size < 500) return '100-500'; if (size < 2000) return '500-2000'; return '>=2000'; }
const buckets = {};
let total = 0, withMarkout = 0;
for (const t of trades) {
  if (String(t.side ?? '').toUpperCase() !== 'BUY') continue;
  const asset = String(t.asset ?? ''); const price = num(t.price); const size = num(t.size);
  if (!asset || price <= 0 || price >= 1 || size <= 0) continue;
  total++;
  const arr = byAsset.get(asset) ?? [];
  const p60 = firstAtOrAfter(arr, num(t.timestamp) + 60);
  if (p60 == null) continue;
  withMarkout++;
  const m = (p60 - price) / price;
  const key = sizeBucket(size);
  if (!buckets[key]) buckets[key] = { key, count: 0, sum: 0, neg: 0, notional: 0 };
  const b = buckets[key]; b.count++; b.sum += m; b.notional += price * size; if (m < 0) b.neg++;
}
const rows = Object.values(buckets).map(b => ({ sizeBucket: b.key, count: b.count, meanMarkout60: b.sum / b.count, negativeShare: b.neg / b.count, meanNotional: b.notional / b.count }))
  .sort((a, b) => a.sizeBucket.localeCompare(b.sizeBucket));
const report = { input, totalBuyTrades: total, buysWithMarkout: withMarkout, buckets: rows };
console.log(JSON.stringify(report, null, 2));
if (output) fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
