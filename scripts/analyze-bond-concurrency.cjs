#!/usr/bin/env node
/* Count how many ladder-eligible markets overlap in time. Read-only. */
const fs = require('fs');
const zlib = require('zlib');

function arg(name, fallback) { const i = process.argv.indexOf('--' + name); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback; }
function numArg(name, fallback) { const v = Number(arg(name, fallback)); if (!Number.isFinite(v)) throw new Error('invalid --' + name); return v; }
function referenceAt(market, ts) {
  let l0 = null, l1 = null;
  for (const t of market.trades) {
    if (t.timestamp > ts || t.price <= 0 || t.price >= 1) continue;
    if (t.outcomeIndex === 0) l0 = t; else if (t.outcomeIndex === 1) l1 = t;
  }
  const p0 = l0 ? l0.price : (l1 ? 1 - l1.price : null);
  const p1 = l1 ? l1.price : (l0 ? 1 - l0.price : null);
  if (p0 == null || p1 == null) return null;
  return p0 >= p1 ? { favorite: 0, price: p0 } : { favorite: 1, price: p1 };
}

const datasetPath = arg('dataset');
const output = arg('output');
if (!datasetPath) { console.error('required: --dataset'); process.exit(2); }
const tag = arg('tag', 'Geopolitics');
const minPrice = numArg('min-price', 0.97);
const maxPrice = numArg('max-price', 0.99);
const minHours = numArg('min-hours-to-close', 72);
const maxHours = numArg('max-hours-to-close', 168);
const data = JSON.parse(zlib.gunzipSync(fs.readFileSync(datasetPath)).toString('utf8'));
const events = [];
const markets = [];
for (const market of data.markets) {
  if (market.tag !== tag) continue;
  const entryTs = market.closedTimestamp - maxHours * 3600;
  const exitTs = market.closedTimestamp - minHours * 3600;
  if (exitTs <= entryTs) continue;
  const ref = referenceAt(market, entryTs);
  if (!ref || ref.price < minPrice || ref.price >= maxPrice) continue;
  markets.push({ conditionId: market.conditionId, question: market.question, entryTs, exitTs, referencePrice: ref.price });
  events.push([entryTs, 1], [exitTs, -1]);
}
events.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
let current = 0, maxConcurrent = 0, weightedArea = 0, last = events.length ? events[0][0] : 0;
for (const [ts, delta] of events) {
  weightedArea += current * (ts - last);
  last = ts;
  current += delta;
  if (current > maxConcurrent) maxConcurrent = current;
}
const span = events.length ? events[events.length - 1][0] - events[0][0] : 0;
const report = {
  generatedAt: new Date().toISOString(), datasetPath, tag, minPrice, maxPrice, minHours, maxHours,
  markets: markets.length, maxConcurrent, timeWeightedAverage: span > 0 ? weightedArea / span : 0,
  intervals: markets,
};
if (output) fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ markets: markets.length, maxConcurrent, timeWeightedAverage: report.timeWeightedAverage }, null, 2));
if (output) console.log('wrote ' + output);