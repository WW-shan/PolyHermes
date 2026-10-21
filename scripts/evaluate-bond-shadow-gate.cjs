#!/usr/bin/env node
/* Read-only pre-live gate for BOND / event-maker Shadow arms. */
const fs = require('fs');
const path = require('path');

function arg(name, fallback) { const i = process.argv.indexOf('--' + name); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback; }
function argNum(name, fallback) { const n = Number(arg(name, fallback)); if (!Number.isFinite(n)) throw new Error(`invalid --${name}`); return n; }
function num(v) { const n = Number(v); return Number.isFinite(n) ? n : 0; }
function mean(a) { return a.length ? a.reduce((x, y) => x + y, 0) / a.length : null; }
function breakEvenWithTail(avgWin, avgLoss, hasSettlementTail, maxFlattenLossRoi) {
  if (avgWin != null && avgLoss != null && avgWin > 0 && avgLoss < 0) {
    return avgWin / (avgWin + Math.abs(avgLoss));
  }
  if (avgWin == null || avgWin <= 0) return null;
  // FLATTEN-only tails are bounded by slippage. A SETTLE tail keeps the
  // conservative 100% wipeout fallback.
  return avgWin / (avgWin + (hasSettlementTail ? 1.0 : maxFlattenLossRoi));
}
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
  'backend/build/research/event-maker-bond-scalp-2c-geo-97099-ladder4-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-2c-geo-97099-flatten24-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-2c-geo-97099-flatten24-500-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-3c-geo-97099-flatten24-500-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-3c-geo-97099-taker00c-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-2c-geo-97099-taker-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-2c-geo-97099-mom1-taker-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-1c-geo-97099-mom1-taker-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-2c-geo-9597-dip24h-taker-shadow-7d-20261021',
  'backend/build/research/event-maker-bond-scalp-2c-geo-97099-off10-size1k-shadow-7d-20261021',
];
const candidateDirs = [
  'backend/build/research/event-maker-bond-scalp-2c-geo-97099-flatten24-500-shadow-7d-20261021',
];
const dirs = String(arg('dirs', defaultDirs.join(','))).split(',').map(s => s.trim()).filter(Boolean);
const candidates = String(arg('candidates', candidateDirs.join(','))).split(',').map(s => s.trim()).filter(Boolean);
const minRunHours = argNum('min-run-hours', 24);
const minCandidateFills = argNum('min-candidate-fills', 20);
const minCycles = argNum('min-cycles', 50);
const minRealizedRoi = argNum('min-realized-roi', 0);
const minSettlementMarkets = argNum('min-settlement-markets', argNum('min-settlements', 50));
const maxSettlementLossUpper = argNum('max-settlement-loss-upper', 1.0);
const minMarkout60Roi = argNum('min-markout60-roi', -0.01);
const maxFlattenLossRoi = argNum('max-flatten-loss-roi', 0.01);

function emptyMarkouts() { return { 60: [], 300: [] }; }
function readRunStartedAt(runPath) {
  if (!fs.existsSync(runPath)) return null;
  try {
    return JSON.parse(fs.readFileSync(runPath, 'utf8').replace(/^\uFEFF/, '')).startedAt ?? null;
  } catch (e) {
    return null;
  }
}

function readArm(dir) {
  const statePath = path.join(dir, 'state.json');
  if (!fs.existsSync(statePath)) {
    let alive = false;
    const runPath = path.join(dir, 'run.json');
    if (fs.existsSync(runPath)) {
      try {
        const run = JSON.parse(fs.readFileSync(runPath, 'utf8').replace(/^\uFEFF/, ''));
        const pid = Number(run.pid);
        if (pid) { try { process.kill(pid, 0); alive = true; } catch (e) { alive = false; } }
      } catch (e) { /* ignore */ }
    }
    return {
      dir, name: path.basename(dir), missing: true, waitingForMarket: alive,
      runHours: null, fills: 0, cycles: 0, settlements: 0, settleLosses: 0,
      pendingSettlements: 0, openInventoryShares: 0, deployed: 0, realized: 0,
      _settlementRois: [], _markoutRois: emptyMarkouts(), reasons: [alive ? 'WAITING_FOR_MARKET' : 'MISSING_STATE'],
    };
  }
  const state = JSON.parse(fs.readFileSync(statePath, 'utf8').replace(/^\uFEFF/, ''));
  const markets = Array.isArray(state.markets) ? state.markets : [];
  const fills = markets.flatMap(m => Array.isArray(m.fills) ? m.fills : []);
  const cycles = markets.flatMap(m => Array.isArray(m.cycles) ? m.cycles : []);
  const settlements = markets.flatMap(m => Array.isArray(m.settlements) ? m.settlements : []);
  const tailExitCycles = cycles.filter(c => c.exitType === 'SETTLE' || c.exitType === 'FLATTEN');
  const hasSettlementTail = tailExitCycles.some(c => c.exitType === 'SETTLE');
  const cycleRoi = cycles.map(c => { const cost = num(c.entryPrice) * num(c.shares); return cost > 0 ? num(c.pnl) / cost : 0; });
  // Legs on the same condition settle from one resolution event. Cluster them
  // so the gate's sample size is the number of resolved markets, not legs.
  const settlementPnlByMarket = new Map();
  const settlementCostByMarket = new Map();
  for (const cycle of tailExitCycles) {
    const marketId = String(cycle.marketId ?? cycle.conditionId ?? 'unknown');
    settlementPnlByMarket.set(marketId, (settlementPnlByMarket.get(marketId) || 0) + num(cycle.pnl));
    settlementCostByMarket.set(
      marketId,
      (settlementCostByMarket.get(marketId) || 0) + num(cycle.entryPrice) * num(cycle.shares)
    );
  }
  const settlementRoiByMarket = Object.fromEntries(
    [...settlementPnlByMarket.entries()].map(([marketId, pnl]) => {
      const cost = settlementCostByMarket.get(marketId) || 0;
      return [marketId, cost > 0 ? pnl / cost : 0];
    })
  );
  const settleRois = Object.values(settlementRoiByMarket);
  const deaths = settleRois.filter(v => v < 0);
  const wins = settleRois.filter(v => v > 0);
  const avgLoss = mean(deaths);
  const avgWin = mean(wins);
  const breakEvenLossRate = breakEvenWithTail(avgWin, avgLoss, hasSettlementTail, maxFlattenLossRoi);
  const lossRate = settleRois.length ? deaths.length / settleRois.length : null;
  const lossCi = lossRate == null ? [0, 1] : wilson(lossRate, settleRois.length);
  const markoutPath = path.join(dir, 'markouts.csv');
  const markoutRois = emptyMarkouts();
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
  const persistedStartedAt = Number(state.startedAt);
  const startedAt = persistedStartedAt > 0
    ? new Date(persistedStartedAt).toISOString()
    : readRunStartedAt(runPath);
  const startedMs = startedAt ? Date.parse(startedAt) : NaN;
  const runHours = Number.isFinite(startedMs) ? (Date.now() - startedMs) / 3600000 : null;
  const realized = cycles.reduce((s, c) => s + num(c.pnl), 0);
  const deployed = cycles.reduce((s, c) => s + num(c.entryPrice) * num(c.shares), 0);
  const reasons = [];
  if (runHours == null || runHours < minRunHours) reasons.push('RUN_TIME_BELOW_MIN');
  if (fills.length < minCandidateFills) reasons.push('FILLS_BELOW_MIN');
  if (cycles.length < minCycles) reasons.push('CYCLES_BELOW_MIN');
  const realizedRoi = deployed > 0 ? realized / deployed : null;
  if (cycles.length >= minCycles && realizedRoi != null && realizedRoi < minRealizedRoi) reasons.push('REALIZED_ROI_BELOW_MIN');
  if (settleRois.length === 0) reasons.push('NO_TAIL_EXITS');
  if (settleRois.length > 0 && wins.length === 0) reasons.push('NO_WINNING_TAIL_EXITS');
  if (settleRois.length > 0 && lossCi[1] > maxSettlementLossUpper) reasons.push('SETTLEMENT_LOSS_CI_ABOVE_LIMIT');
  if (settleRois.length > 0 && lossCi[0] > maxSettlementLossUpper) reasons.push('SETTLEMENT_LOSS_CI_LOWER_ABOVE_LIMIT');
  if (breakEvenLossRate != null && lossCi[1] > breakEvenLossRate) reasons.push('SETTLEMENT_LOSS_CI_ABOVE_BREAK_EVEN');
  if (breakEvenLossRate != null && lossCi[0] > breakEvenLossRate) reasons.push('SETTLEMENT_LOSS_CI_LOWER_ABOVE_BREAK_EVEN');
  const m60 = mean(markoutRois[60]);
  if (m60 != null && m60 < minMarkout60Roi) reasons.push('MARKOUT60_BELOW_MIN');
  return {
    dir, name: path.basename(dir), missing: false, waitingForMarket: false, startedAt, runHours,
    fills: fills.length, cycles: cycles.length, settlements: settlements.length,
    settlementMarkets: settleRois.length,
    settleLosses: deaths.length,
    pendingSettlements: settlements.filter(s => s.redemptionStatus === 'PENDING').length,
    openInventoryShares: markets.reduce((s, m) => s + num(m.inventoryShares), 0),
    deployed, realized, realizedRoi: realizedRoi,
    cycleLossRate: cycleRoi.length ? cycleRoi.filter(v => v < 0).length / cycleRoi.length : null,
    settlementLossRate: lossRate, settlementLossCi95: lossCi, breakEvenLossRate,
    tailExitTypes: [...new Set(tailExitCycles.map(c => c.exitType))], maxFlattenLossRoi, _tailExitTypes: [...new Set(tailExitCycles.map(c => c.exitType))],
    markout60Roi: m60, markout300Roi: mean(markoutRois[300]), reasons,
    _settlementRois: settleRois, _settlementRoiByMarket: settlementRoiByMarket, _markoutRois: markoutRois,
  };
}

function readCandidateGroup(name, groupDirs) {
  const members = groupDirs.map(d => byDir.get(path.resolve(d)) ?? {
    dir: d, name: path.basename(d), missing: true, waitingForMarket: false, runHours: null,
    fills: 0, cycles: 0, settlements: 0, settleLosses: 0, pendingSettlements: 0,
    openInventoryShares: 0, deployed: 0, realized: 0, settlementMarkets: 0,
    _settlementRois: [], _settlementRoiByMarket: {}, _markoutRois: emptyMarkouts(),
    reasons: ['MISSING_STATE'],
  });
  const missingMembers = members.filter(m => m.missing);
  const settlementRoiByMarket = new Map();
  for (const member of members) {
    for (const [marketId, roi] of Object.entries(member._settlementRoiByMarket || {})) {
      settlementRoiByMarket.set(marketId, roi);
    }
  }
  const settleRois = [...settlementRoiByMarket.values()];
  const markout60 = members.flatMap(m => (m._markoutRois || emptyMarkouts())[60]);
  const markout300 = members.flatMap(m => (m._markoutRois || emptyMarkouts())[300]);
  const deaths = settleRois.filter(v => v < 0);
  const wins = settleRois.filter(v => v > 0);
  const avgLoss = mean(deaths);
  const avgWin = mean(wins);
  const hasSettlementTail = members.some(m => (m._tailExitTypes || []).includes('SETTLE'));
  const breakEvenLossRate = breakEvenWithTail(avgWin, avgLoss, hasSettlementTail, maxFlattenLossRoi);
  const lossRate = settleRois.length ? deaths.length / settleRois.length : null;
  const lossCi = lossRate == null ? [0, 1] : wilson(lossRate, settleRois.length);
  const runHours = missingMembers.length === 0 && members.every(m => m.runHours != null)
    ? Math.min(...members.map(m => m.runHours))
    : null;
  const fills = members.reduce((s, m) => s + m.fills, 0);
  const cycles = members.reduce((s, m) => s + m.cycles, 0);
  const settlements = members.reduce((s, m) => s + m.settlements, 0);
  const realized = members.reduce((s, m) => s + m.realized, 0);
  const deployed = members.reduce((s, m) => s + m.deployed, 0);
  const m60 = mean(markout60);
  const reasons = [];
  if (missingMembers.length > 0) {
    reasons.push(missingMembers.every(m => m.waitingForMarket) ? 'MEMBER_WAITING_FOR_MARKET' : 'MEMBER_MISSING_STATE');
  }
  if (runHours == null || runHours < minRunHours) reasons.push('RUN_TIME_BELOW_MIN');
  if (fills < minCandidateFills) reasons.push('FILLS_BELOW_MIN');
  if (cycles < minCycles) reasons.push('CYCLES_BELOW_MIN');
  const realizedRoi = deployed > 0 ? realized / deployed : null;
  if (cycles >= minCycles && realizedRoi != null && realizedRoi < minRealizedRoi) reasons.push('REALIZED_ROI_BELOW_MIN');
  if (settleRois.length === 0) reasons.push('NO_TAIL_EXITS');
  if (settleRois.length > 0 && wins.length === 0) reasons.push('NO_WINNING_TAIL_EXITS');
  if (settlements > 0 && lossCi[1] > maxSettlementLossUpper) reasons.push('SETTLEMENT_LOSS_CI_ABOVE_LIMIT');
  if (settlements > 0 && lossCi[0] > maxSettlementLossUpper) reasons.push('SETTLEMENT_LOSS_CI_LOWER_ABOVE_LIMIT');
  if (breakEvenLossRate != null && lossCi[1] > breakEvenLossRate) reasons.push('SETTLEMENT_LOSS_CI_ABOVE_BREAK_EVEN');
  if (breakEvenLossRate != null && lossCi[0] > breakEvenLossRate) reasons.push('SETTLEMENT_LOSS_CI_LOWER_ABOVE_BREAK_EVEN');
  if (m60 != null && m60 < minMarkout60Roi) reasons.push('MARKOUT60_BELOW_MIN');
  return {
    name, dirs: groupDirs, members: members.map(m => m.name), missingMembers: missingMembers.map(m => m.name),
    waitingForMarket: missingMembers.length > 0 && missingMembers.every(m => m.waitingForMarket),
    runHours, fills, cycles, settlements, settlementMarkets: settleRois.length,
    settleLosses: deaths.length,
    pendingSettlements: members.reduce((s, m) => s + m.pendingSettlements, 0),
    openInventoryShares: members.reduce((s, m) => s + m.openInventoryShares, 0),
    deployed, realized, realizedRoi: realizedRoi,
    settlementLossRate: lossRate, settlementLossCi95: lossCi, breakEvenLossRate,
    tailExitTypes: [...new Set(members.flatMap(m => m._tailExitTypes || []))], maxFlattenLossRoi,
    markout60Roi: m60, markout300Roi: mean(markout300), reasons,
  };
}

const arms = dirs.map(readArm);
const byDir = new Map(arms.map(a => [path.resolve(a.dir), a]));
const candidateGroups = [readCandidateGroup('smallcap-500-flatten24-2c', candidates)];
const settlementMarkets = candidateGroups.reduce((s, g) => s + (g.settlementMarkets || 0), 0);
const totals = {
  arms: arms.length,
  cycles: arms.reduce((s, a) => s + (a.cycles || 0), 0),
  settlements: arms.reduce((s, a) => s + (a.settlements || 0), 0),
  settlementMarkets,
  settleLosses: arms.reduce((s, a) => s + (a.settleLosses || 0), 0),
};
const blockers = new Set();
if (settlementMarkets < minSettlementMarkets) blockers.add(`TOTAL_SETTLEMENT_MARKETS_BELOW_${minSettlementMarkets}`);
for (const group of candidateGroups) (group.reasons || []).forEach(r => blockers.add(r));
const unavailable = arms.some(a => a.missing && !a.waitingForMarket) ||
  candidateGroups.some(g => g.missingMembers.length > 0 && !g.waitingForMarket);
const fatalBlockers = [...blockers].filter(r =>
  r.startsWith('SETTLEMENT_LOSS_CI_LOWER_') || r === 'MEMBER_MISSING_STATE' || r === 'MARKOUT60_BELOW_MIN' || r === 'NO_WINNING_TAIL_EXITS' || r === 'REALIZED_ROI_BELOW_MIN'
);
const status = blockers.size === 0 ? 'PASS' : ((unavailable || fatalBlockers.length > 0) ? 'FAIL' : 'WAIT');
const publicArm = a => {
  const { _settlementRois, _markoutRois, ...rest } = a;
  return rest;
};
const report = {
  generatedAt: new Date().toISOString(), status,
  thresholds: { minRunHours, minCandidateFills, minCycles, minRealizedRoi, minSettlementMarkets, minSettlements: minSettlementMarkets, maxSettlementLossUpper, minMarkout60Roi, maxFlattenLossRoi },
  totals, blockers: [...blockers], fatalBlockers, candidates: candidateGroups, candidateGroups,
  arms: arms.map(publicArm),
};

const output = arg('output');
if (output) fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
console.log(`BOND Shadow gate: ${status}`);
console.log(`cycles=${totals.cycles} settlements=${totals.settlements} settlementMarkets=${totals.settlementMarkets} settleLosses=${totals.settleLosses} blockers=${[...blockers].join(',') || '-'}`);
console.log('| candidate group | runH | fills | cycles | settlements | settlementMarkets | settleLosses | pending | openShares | realizedRoi | settleLossRate | lossCI95 | markout60ROI |');
console.log('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|');
const pct = v => v == null ? '-' : (100 * v).toFixed(2) + '%';
for (const g of candidateGroups) {
  console.log(`| ${g.name} | ${g.runHours == null ? '-' : g.runHours.toFixed(1)} | ${g.fills} | ${g.cycles} | ${g.settlements} | ${g.settlementMarkets} | ${g.settleLosses} | ${g.pendingSettlements} | ${g.openInventoryShares.toFixed(2)} | ${pct(g.realizedRoi)} | ${pct(g.settlementLossRate)} | ${g.settlementLossCi95 ? `[${pct(g.settlementLossCi95[0])}, ${pct(g.settlementLossCi95[1])}]` : '-'} | ${pct(g.markout60Roi)} |`);
  if (g.reasons.length) console.log(`  reasons: ${g.reasons.join(', ')}`);
}
console.log('');
console.log('| arm | runH | fills | cycles | settlements | settleLosses | pending | openShares | realizedRoi | settleLossRate | lossCI95 | markout60ROI |');
console.log('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|');
for (const a of arms) {
  console.log(`| ${a.name} | ${a.runHours == null ? '-' : a.runHours.toFixed(1)} | ${a.fills ?? '-'} | ${a.cycles ?? '-'} | ${a.settlements ?? '-'} | ${a.settleLosses ?? '-'} | ${a.pendingSettlements ?? '-'} | ${a.openInventoryShares == null ? '-' : a.openInventoryShares.toFixed(2)} | ${pct(a.realizedRoi)} | ${pct(a.settlementLossRate)} | ${a.settlementLossCi95 ? `[${pct(a.settlementLossCi95[0])}, ${pct(a.settlementLossCi95[1])}]` : '-'} | ${pct(a.markout60Roi)} |`);
}
if (output) console.log(`wrote ${output}`);