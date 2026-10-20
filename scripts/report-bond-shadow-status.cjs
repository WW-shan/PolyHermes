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
  'backend/build/research/event-maker-bond-scalp-3c-cap1000-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-1c-cap1000-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-1c-joinbid-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-1c-passive-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-1c-wide-universe-shadow-7d-20261021'
];
const dirs = (arg('dirs') || defaultDirs.join(',')).split(',').map(s => s.trim()).filter(Boolean);
const lines = [];
lines.push('| arm | markets | active | fills | cycles | inventory | realizedPnl | markout60 | markout300 | neg60 |');
lines.push('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|');
let totalFills = 0, totalCycles = 0, totalPnl = 0;
for (const dir of dirs) {
  const statePath = path.join(dir, 'state.json');
  if (!fs.existsSync(statePath)) { lines.push(`| ${path.basename(dir)} | - | - | - | - | - | - | - | - | - |`); continue; }
  const state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
  const markets = Array.isArray(state.markets) ? state.markets : [];
  const fills = markets.reduce((s, m) => s + (Array.isArray(m.fills) ? m.fills.length : 0), 0);
  const cycles = markets.reduce((s, m) => s + (Array.isArray(m.cycles) ? m.cycles.length : 0), 0);
  const active = markets.filter(m => m.activeOrderId).length;
  const inventory = markets.reduce((s, m) => s + num(m.inventoryShares), 0);
  const realized = markets.reduce((s, m) => s + num(m.realizedPnl), 0);
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
  totalFills += fills; totalCycles += cycles; totalPnl += realized;
  const fmt = v => v == null ? '-' : (Math.round(v * 100) / 100).toString();
  const pct = v => v == null ? '-' : (Math.round(v * 1000) / 10).toString() + '%';
  lines.push(`| ${path.basename(dir)} | ${markets.length} | ${active} | ${fills} | ${cycles} | ${inventory.toFixed(2)} | ${fmt(realized)} | ${fmt(m60)} | ${fmt(m300)} | ${pct(neg60)} |`);
}
lines.push(`| **total** | | | **${totalFills}** | **${totalCycles}** | | **${Math.round(totalPnl * 100) / 100}** | | | |`);
const text = lines.join('\n');
console.log(text);
const output = arg('output');
if (output) { fs.writeFileSync(output, text + '\n'); console.log(`wrote ${output}`); }
