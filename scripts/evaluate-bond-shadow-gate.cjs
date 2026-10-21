#!/usr/bin/env node
/* Read-only pre-live gate for BOND / event-maker Shadow arms. */
const fs = require('fs');
const path = require('path');

function arg(name, fallback) { const i = process.argv.indexOf('--' + name); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback; }
function argNum(name, fallback) { const n = Number(arg(name, fallback)); if (!Number.isFinite(n)) throw new Error(`invalid --${name}`); return n; }
function num(v) { const n = Number(v); return Number.isFinite(n) ? n : 0; }
function mean(a) { return a.length ? a.reduce((x, y) => x + y, 0) / a.length : null; }
function wilson(p, n, z = 1.96) { if (!n) return [0, 1]; const d = 1 + z * z / n; const c = (p + z * z / (2 * n)) / d; const h = z * Math.sqrt((p * (1 - p) + z * z / (4 * n)) / n) / d; return [Math.max(0, c - h), Math.min(1, c + h)]; }
function parseCsv(text) {
  const rows = []; let row = [], field = '', q = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (q) { if (c === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else q = false; } else field += c; }
    else if (c === '"') q = true; else if (c === ',') { row.push(field); field = ''; }
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
  'backend/build/research/event-maker-bond-scalp-1c-geo-97099-mom1-taker-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-2c-geo-9597-dip24h-taker-shadow-7d-20261021',
];
const candidateDirs = [
  'backend/build/research/event-maker-bond-scalp-3c-geo-97099-off10-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-2c-geo-97099-taker-shadow-7d-20261021',
];
const dirs = String(arg('dirs', defaultDirs.join(','))).split(',').map(s => s.trim()).filter(Boolean);
const candidates = String(arg('candidates', candidateDirs.join(','))).split(',').map(s => s.trim()).filter(Boolean);
const minRunHours = argNum('min-run-hours', 24);
const minCandidateFills = argNum('min-candidate-fills', 20);
const minSettlements = argNum('min-settlements', 50);
const maxSettlementLossUpper = argNum('max-settlement-loss-upper', 1.0);
const minMarkout60Roi = argNum('min-markout60-roi', -0.01);

function readArm(dir) {
  const statePath = path.join(dir, 'state.json');
  if (!fs.existsSync(statePath)) return { dir, name: path.basename(dir), missing: true, reasons: ['MISSING_STATE'] };
  const state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
  const markets = Array.isArray(state.markets) ? state.markets : [];
  const fills = markets.flatMap(m => Array.isArray(m.fills) ? m.fills : []);
  const cycles = markets.flatMap(m => Array.isArray(m.cycles) ? m.cycles : []);
  const settlements = markets.flatMap(m => Array.isArray(m.settlements) ? m.settlements : []);
  const settleCycles = cycles.filter(c => c.exitType === 'SETTLE');
  const cycleRoi = cycles.map(c => { const cost = num(c.entryPrice) * num(c.shares); return cost > 0 ? num(c.pnl) / cost : 0; });
  const settleRois = settleCycles.map(c => { const cost = num(c.entryPrice) * num(c.shares); return cost > 0 ? num(c.pnl) / cost : 0; });
  const deaths = settleRois.filter(v => v < 0);
  const wins = settleRois.filter(v => v > 0);
  const avgLoss = mean(deaths);
  const avgWin = mean(wins);
  const breakEvenLossRate = avgWin != null && avgLoss != null && avgWin > 0 && avgLoss < 0 ? avgWin / (avgWin + Math.abs(avgLoss)) : null;
  const lossRate = settleRois.length ? deaths.length / settleRois.length : null;
  const lossCi = lossRate == null ? [0, 1] : wilson(lossRate, settleRois.length);
  const markoutPath = path.join(dir, 'markouts.csv');
  const markoutRois = { 60: [], 300: [] };
  if (fs.existsSync(markoutPath)) {
    const rows = parseCsv(fs.readFileSync(markoutPath, 'utf8'));
    const idx = Object.fromEntries((rows[0] || []).map((h, i) => [h, i]));
    for (const row of rows.slice(1)) {
      const horizon = num(row[idx.horizon_seconds]);
      const cost = num(row[idx.fill_price] ?? row[idx.price]) * num(row[idx.shares]);
      if ((horizon === 60 || horizon === 300) && cost > 0) markoutRois[horizon].push(num(row[idx.markout_pnl]) / cost);
    }
  }
  const runPath = path.join(dir, 'run.json');
  let startedAt = null;
  if (fs.existsSync(runPath)) { try { startedAt = JSON.parse(fs.readFileSync(runPath, 'utf8').replace(/^\uFEFF/, '')).startedAt ?? null; } catch (e) {} }
  const runHours = startedAt ? (Date.now() - Date.parse(startedAt)) / 3600000 : null;
  const realized = cycles.reduce((s, c) => s + num(c.pnl), 0);
  const deployed = cycles.reduce((s, c) => s + num(c.entryPrice) * num(c.shares), 0);
  const reasons = [];
  if (runHours == null || runHours < minRunHours) reasons.push('RUN_TIME_BELOW_MIN');
  if (fills.length < minCandidateFills) reasons.push('FILLS_BELOW_MIN');
  if (settlements.length === 0) reasons.push('NO_SETTLEMENTS');
  if (settlements.length > 0 && lossCi[1] > maxSettlementLossUpper) reasons.push('SETTLEMENT_LOSS_CI_ABOVE_LIMIT');
  if (breakEvenLossRate != null && lossCi[1] > breakEvenLossRate) reasons.push('SETTLEMENT_LOSS_CI_ABOVE_BREAK_EVEN');
  const m60 = mean(markoutRois[60]);
  if (m60 != null && m60 < minMarkout60Roi) reasons.push('MARKOUT60_BELOW_MIN');
  return { dir, name: path.basename(dir), missing: false, startedAt, runHours, fills: fills.length, cycles: cycles.length, settlements: settlements.length, settleLosses: settleCycles.filter(c => num(c.pnl) < 0).length, pendingSettlements: settlements.filter(s => s.redemptionStatus === 'PENDING').length, openInventoryShares: markets.reduce((s, m) => s + num(m.inventoryShares), 0), deployed, realized, realizedRoi: deployed > 0 ? realized / deployed : null, cycleLossRate: cycleRoi.length ? cycleRoi.filter(v => v < 0).length / cycleRoi.length : null, settlementLossRate: lossRate, settlementLossCi95: lossCi, breakEvenLossRate, markout60Roi: m60, markout300Roi: mean(markoutRois[300]), reasons };
}

const arms = dirs.map(readArm);
const byDir = new Map(arms.map(a => [path.resolve(a.dir), a]));
const candidateArms = candidates.map(d => byDir.get(path.resolve(d)) ?? { dir: d, name: path.basename(d), missing: true, reasons: ['MISSING_STATE'] });
const totals = {
  arms: arms.length,
  cycles: arms.reduce((s, a) => s + (a.cycles || 0), 0),
  settlements: arms.reduce((s, a) => s + (a.settlements || 0), 0),
  settleLosses: arms.reduce((s, a) => s + (a.settleLosses || 0), 0),
};
const blockers = new Set();
if (totals.settlements < minSettlements) blockers.add(`TOTAL_SETTLEMENTS_BELOW_${minSettlements}`);
for (const a of candidateArms) (a.reasons || []).forEach(r => blockers.add(r));
const status = blockers.size === 0 ? 'PASS' : ((arms.some(a => a.missing) || candidateArms.some(a => a.missing)) ? 'FAIL' : 'WAIT');
const report = { generatedAt: new Date().toISOString(), status, thresholds: { minRunHours, minCandidateFills, minSettlements, maxSettlementLossUpper, minMarkout60Roi }, totals, blockers: [...blockers], candidates: candidateArms, arms };
const output = arg('output');
if (output) fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
console.log(`BOND Shadow gate: ${status}`);
console.log(`cycles=${totals.cycles} settlements=${totals.settlements} settleLosses=${totals.settleLosses} blockers=${[...blockers].join(',') || '-'}`);
console.log('| arm | runH | fills | cycles | settlements | settleLosses | pending | openShares | realizedRoi | settleLossRate | lossCI95 | markout60ROI |');
console.log('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|');
for (const a of arms) {
  const pct = v => v == null ? '-' : (100 * v).toFixed(2) + '%';
  console.log(`| ${a.name} | ${a.runHours == null ? '-' : a.runHours.toFixed(1)} | ${a.fills ?? '-'} | ${a.cycles ?? '-'} | ${a.settlements ?? '-'} | ${a.settleLosses ?? '-'} | ${a.pendingSettlements ?? '-'} | ${a.openInventoryShares == null ? '-' : a.openInventoryShares.toFixed(2)} | ${pct(a.realizedRoi)} | ${pct(a.settlementLossRate)} | ${a.settlementLossCi95 ? `[${pct(a.settlementLossCi95[0])}, ${pct(a.settlementLossCi95[1])}]` : '-'} | ${pct(a.markout60Roi)} |`);
}
if (output) console.log(`wrote ${output}`);