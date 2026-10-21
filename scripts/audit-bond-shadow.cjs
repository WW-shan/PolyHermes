#!/usr/bin/env node
/*
 * Offline audit for a BOND / event-maker Shadow output directory.
 * Reads state.json and optional summary.json; never touches live orders.
 */
const fs = require('fs');
const path = require('path');

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
function num(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

const dir = arg('dir');
if (!dir) {
  console.error('required: --dir <shadow-output-directory>');
  process.exit(2);
}
const statePath = path.join(dir, 'state.json');
if (!fs.existsSync(statePath)) throw new Error(`state.json not found: ${statePath}`);
const state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
const summaryPath = path.join(dir, 'summary.json');
const summary = fs.existsSync(summaryPath) ? JSON.parse(fs.readFileSync(summaryPath, 'utf8')) : null;

const markets = Array.isArray(state.markets) ? state.markets : [];
const cycles = markets.flatMap(m => (Array.isArray(m.cycles) ? m.cycles.map(c => ({ ...c, conditionId: m.conditionId })) : []));
const fills = markets.flatMap(m => (Array.isArray(m.fills) ? m.fills.map(f => ({ ...f, conditionId: m.conditionId })) : []));
const settlements = markets.flatMap(m => (Array.isArray(m.settlements) ? m.settlements.map(s => ({ ...s, conditionId: m.conditionId })) : []));
const settlementCycleIds = new Set(cycles.filter(c => c.exitType === 'SETTLE').map(c => c.exitOrderId));
const settlementIdCounts = new Map();
for (const s of settlements) settlementIdCounts.set(s.cycleExitOrderId, (settlementIdCounts.get(s.cycleExitOrderId) ?? 0) + 1);
const duplicateSettlementIds = [...settlementIdCounts.values()].filter(n => n > 1).length;
const pendingSettlements = settlements.filter(s => s.redemptionStatus === 'PENDING').length;
const settlementLosses = settlements.filter(s => num(s.pnl) < 0).length;
const missingSettlementCycles = settlements.filter(s => !settlementCycleIds.has(s.cycleExitOrderId)).length;
const fillKeyCounts = new Map();
for (const f of fills) {
  const key = [f.conditionId, f.orderId, f.side, f.price, f.shares, f.timestamp].join('|');
  fillKeyCounts.set(key, (fillKeyCounts.get(key) ?? 0) + 1);
}
const duplicateFillKeys = [...fillKeyCounts.values()].filter(n => n > 1).length;
const phases = {};
for (const m of markets) phases[m.phase ?? 'UNKNOWN'] = (phases[m.phase ?? 'UNKNOWN'] ?? 0) + 1;

let deployed = 0;
let realized = 0;
let holdingSeconds = 0;
let worst = null;
let best = null;
for (const c of cycles) {
  const entry = num(c.entryPrice);
  const shares = num(c.shares);
  const pnl = num(c.pnl);
  deployed += entry * shares;
  realized += pnl;
  const entryAt = num(c.entryAt);
  const exitAt = num(c.exitAt);
  if (entryAt > 0 && exitAt >= entryAt) holdingSeconds += exitAt - entryAt;
  if (worst == null || pnl < num(worst.pnl)) worst = c;
  if (best == null || pnl > num(best.pnl)) best = c;
}
const inventoryShares = markets.reduce((s, m) => s + num(m.inventoryShares), 0);
const inventoryNotional = markets.reduce((s, m) => s + num(m.entryPrice) * num(m.inventoryShares), 0);
const activeOrders = markets.filter(m => m.activeOrderId || m.activeOrderStatus === 'LIVE').length;
const realizedRoi = deployed > 0 ? realized / deployed : 0;
const audit = {
  generatedAt: new Date().toISOString(),
  directory: path.resolve(dir),
  hasSummary: !!summary,
  summary: summary ? {
    mode: summary.mode,
    durationSeconds: summary.durationSeconds,
    markets: summary.markets,
    quotesPlaced: summary.quotesPlaced,
    cancels: summary.cancels,
    fills: summary.fills,
    duplicateTradeEvents: summary.duplicateTradeEvents,
    completedCycles: summary.completedCycles,
    winningCycles: summary.winningCycles,
    losingCycles: summary.losingCycles,
    realizedPnl: summary.realizedPnl,
    unrealizedPnl: summary.unrealizedPnl,
    totalPnl: summary.totalPnl,
    openInventoryShares: summary.openInventoryShares,
    lastErrors: summary.lastErrors
  } : null,
  state: {
    markets: markets.length,
    phases,
    activeOrders,
    inventoryShares,
    inventoryNotional,
    cycles: cycles.length,
    fills: fills.length,
    settlements: settlements.length,
    settlementLosses,
    pendingSettlements,
    duplicateSettlementIds,
    missingSettlementCycles,
    settlementRealizedPnl: settlements.reduce((s, x) => s + num(x.pnl), 0),
    duplicateFillKeys,
    deployedNotional: deployed,
    realizedPnl: realized,
    realizedRoi,
    averageHoldingSeconds: cycles.length ? holdingSeconds / cycles.length : 0,
    worstCycle: worst,
    bestCycle: best
  },
  flags: []
};
if (cycles.length === 0) audit.flags.push('NO_FILLS_OR_CYCLES');
if (duplicateFillKeys > 0) audit.flags.push('DUPLICATE_FILLS');
if (duplicateSettlementIds > 0) audit.flags.push('DUPLICATE_SETTLEMENTS');
if (pendingSettlements > 0) audit.flags.push('PENDING_SETTLEMENTS');
if (missingSettlementCycles > 0) audit.flags.push('SETTLEMENT_WITHOUT_CYCLE');
if (settlementLosses > 0) audit.flags.push('SETTLEMENT_LOSS_PRESENT');
if (summary && Array.isArray(summary.lastErrors) && summary.lastErrors.length > 0) audit.flags.push('LAST_ERRORS_PRESENT');
if (cycles.length > 0 && realizedRoi > 0.01) audit.flags.push('REALIZED_ROI_ABOVE_1PCT_CHECK_FILL_ASSUMPTIONS');
if (inventoryShares > 0 && activeOrders === 0) audit.flags.push('OPEN_INVENTORY_WITHOUT_ACTIVE_ORDER');

console.log(JSON.stringify(audit, null, 2));
const out = arg('output');
if (out) fs.writeFileSync(out, JSON.stringify(audit, null, 2) + '\n');