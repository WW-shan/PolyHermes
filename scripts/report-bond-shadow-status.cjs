#!/usr/bin/env node
/* Consolidated status for the BOND_SCALP Shadow arms. Read-only. */
const fs = require('fs');
const path = require('path');

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
function num(v) { const n = Number(v); return Number.isFinite(n) ? n : 0; }
function mean(a) { return a.length ? a.reduce((x, y) => x + y, 0) / a.length : null; }
function parseCsv(text) {
  const rows = []; let row = [], field = '', q = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (q) { if (c === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else q = false; } else field += c; }
    else if (c === '"') q = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n') { row.push(field); rows.push(row); row = []; field = ''; }
    else if (c !== '\r') field += c;
  }
  if (field.length || row.length) { row.push(field); rows.push(row); }
  return rows.filter(r => r.length > 1 || r[0] !== '');
}

const defaultDirs = [
  'backend/build/research/event-maker-bond-scalp-3c-geo-9597-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-3c-geo-97099-off00-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-3c-geo-97099-off05-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-3c-geo-97099-off10-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-3c-geo-97099-off20-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-3c-geo-97099-taker00c-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-2c-geo-97099-taker-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-2c-geo-97099-mom1-taker-shadow-7d-20261021',
];
const dirs = (arg('dirs') || defaultDirs.join(',')).split(',').map(s => s.trim()).filter(Boolean);
const lines = [];
lines.push('| arm | status | pid | stateAgeMin | markets | active | fills | cycles | inventory | realizedPnl | unrealizedPnl | totalPnl | markout60 | markout300 | neg60 |');
lines.push('|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|');
let totalFills = 0, totalCycles = 0, totalRealized = 0, totalUnrealized = 0;
const stale = [];
for (const dir of dirs) {
  const statePath = path.join(dir, 'state.json');
  const arm = path.basename(dir);
  if (!fs.existsSync(statePath)) {
    lines.push(`| ${arm} | MISSING | - | - | - | - | - | - | - | - | - | - | - | - | - |`);
    stale.push(arm + ' (missing)');
    continue;
  }
  const stateMtime = fs.statSync(statePath).mtimeMs;
  const stateAgeMin = (Date.now() - stateMtime) / 60000;
  let pid = null;
  let procAlive = null;
  const runPath = path.join(dir, 'run.json');
  if (fs.existsSync(runPath)) {
    try {
      const run = JSON.parse(fs.readFileSync(runPath, 'utf8').replace(/^\uFEFF/, ''));
      pid = Number(run.pid) || null;
      if (pid) { try { process.kill(pid, 0); procAlive = true; } catch (e) { procAlive = false; } }
    } catch (e) { /* ignore malformed run.json */ }
  }
  const status = procAlive === false ? 'DEAD' : (stateAgeMin <= 5 ? 'LIVE' : 'STALE');
  if (status !== 'LIVE') stale.push(arm + ` (${status}, ${stateAgeMin.toFixed(1)}m)`);
  const state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
  const markets = Array.isArray(state.markets) ? state.markets : [];
  const fills = markets.reduce((s, m) => s + (Array.isArray(m.fills) ? m.fills.length : 0), 0);
  const cycles = markets.reduce((s, m) => s + (Array.isArray(m.cycles) ? m.cycles.length : 0), 0);
  const active = markets.filter(m => m.activeOrderId).length;
  const inventory = markets.reduce((s, m) => s + num(m.inventoryShares), 0);
  const realized = markets.reduce((s, m) => s + num(m.realizedPnl), 0);
  let unrealized = 0;
  const snapPath = path.join(dir, 'snapshots.csv');
  if (fs.existsSync(snapPath)) {
    const rows = parseCsv(fs.readFileSync(snapPath, 'utf8'));
    const head = rows[0] || [];
    const idx = Object.fromEntries(head.map((h, i) => [h, i]));
    if (idx.unrealized_pnl != null) {
      const latest = new Map();
      for (const r of rows.slice(1)) latest.set(r[idx.market_id], r);
      for (const r of latest.values()) unrealized += num(r[idx.unrealized_pnl]);
    }
  }
  let m60 = null, m300 = null, neg60 = null;
  const markoutPath = path.join(dir, 'markouts.csv');
  if (fs.existsSync(markoutPath)) {
    const rows = parseCsv(fs.readFileSync(markoutPath, 'utf8'));
    const head = rows[0] || [];
    const idx = Object.fromEntries(head.map((h, i) => [h, i]));
    const data = rows.slice(1);
    const vals60 = data.filter(r => String(r[idx.horizon_seconds]) === '60').map(r => num(r[idx.markout_pnl]));
    const vals300 = data.filter(r => String(r[idx.horizon_seconds]) === '300').map(r => num(r[idx.markout_pnl]));
    m60 = mean(vals60); m300 = mean(vals300);
    neg60 = vals60.length ? vals60.filter(v => v < 0).length / vals60.length : null;
  }
  totalFills += fills; totalCycles += cycles; totalRealized += realized; totalUnrealized += unrealized;
  const fmt = v => v == null ? '-' : (Math.round(v * 100) / 100).toString();
  const pct = v => v == null ? '-' : (Math.round(v * 1000) / 10).toString() + '%';
  lines.push(`| ${arm} | ${status} | ${pid ?? '-'} | ${stateAgeMin.toFixed(1)} | ${markets.length} | ${active} | ${fills} | ${cycles} | ${inventory.toFixed(2)} | ${fmt(realized)} | ${fmt(unrealized)} | ${fmt(realized + unrealized)} | ${fmt(m60)} | ${fmt(m300)} | ${pct(neg60)} |`);
}
lines.push(`| **total** | | | | | | **${totalFills}** | **${totalCycles}** | | **${Math.round(totalRealized * 100) / 100}** | **${Math.round(totalUnrealized * 100) / 100}** | **${Math.round((totalRealized + totalUnrealized) * 100) / 100}** | | | |`);
if (stale.length) { lines.push(''); lines.push(`**NOT LIVE:** ${stale.join(', ')}`); }
const text = lines.join('\n');
console.log(text);
const output = arg('output');
if (output) { fs.writeFileSync(output, text + '\n'); console.log(`wrote ${output}`); }
