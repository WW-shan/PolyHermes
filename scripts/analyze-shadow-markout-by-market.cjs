#!/usr/bin/env node
/* Markout by market characteristics for a BOND_SCALP focus run. */
const fs = require('fs');
function arg(name, fallback) { const i = process.argv.indexOf('--' + name); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback; }
function num(v) { const n = Number(v); return Number.isFinite(n) ? n : 0; }
function mean(a) { return a.length ? a.reduce((x, y) => x + y, 0) / a.length : null; }
function parseCsv(text) {
  const rows = []; let row = [], field = '', q = false;
  for (let i = 0; i < text.length; i++) { const c = text[i];
    if (q) { if (c === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else q = false; } else field += c; }
    else if (c === '"') q = true; else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n') { row.push(field); rows.push(row); row = []; field = ''; }
    else if (c !== '\r') field += c;
  }
  if (field.length || row.length) { row.push(field); rows.push(row); }
  return rows.filter(r => r.length > 1 || r[0] !== '');
}
function table(rows) { const head = rows[0]; const idx = Object.fromEntries(head.map((h, i) => [h, i])); return rows.slice(1).map(r => Object.fromEntries(head.map((h, i) => [h, r[i]]))); }
const cyclesPath = arg('cycles'); const marketsPath = arg('markets'); const output = arg('output');
if (!cyclesPath || !marketsPath) { console.error('required: --cycles <csv> --markets <csv>'); process.exit(2); }
const cycles = table(parseCsv(fs.readFileSync(cyclesPath, 'utf8')));
const markets = table(parseCsv(fs.readFileSync(marketsPath, 'utf8')));
const marketById = new Map(markets.map(m => [m.condition_id, m]));
function volBucket(v) { if (v < 100000) return '<100k'; if (v < 500000) return '100k-500k'; return '>=500k'; }
function priceBucket(p) { if (p < 0.95) return '<0.95'; if (p < 0.97) return '0.95-0.97'; if (p < 0.99) return '0.97-0.99'; return '>=0.99'; }
function agg(keyFn) {
  const m = new Map();
  for (const c of cycles) {
    const mk = marketById.get(c.condition_id) || {};
    const mark = num(c.markout_60s_pnl);
    const key = keyFn(c, mk);
    if (!m.has(key)) m.set(key, { key, count: 0, markSum: 0, neg: 0, roiSum: 0 });
    const b = m.get(key); b.count++; b.markSum += mark; if (mark < 0) b.neg++; b.roiSum += num(c.pnl) / Math.max(1e-9, num(c.entry_price) * num(c.shares));
  }
  return [...m.values()].map(b => ({ key: b.key, count: b.count, meanMarkout60: b.markSum / b.count, negativeShare: b.neg / b.count, meanRoi: b.roiSum / b.count })).sort((a, b) => a.key.localeCompare(b.key));
}
const report = {
  cycles: cycles.length,
  byTag: agg((c, m) => m.tag || c.tag || 'unknown'),
  byVolume: agg((c, m) => volBucket(num(m.volume))),
  byPrice: agg((c, m) => priceBucket(num(c.entry_price))),
  byTagVolume: agg((c, m) => (m.tag || c.tag || 'unknown') + ':' + volBucket(num(m.volume)))
};
console.log(JSON.stringify(report, null, 2));
if (output) fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
