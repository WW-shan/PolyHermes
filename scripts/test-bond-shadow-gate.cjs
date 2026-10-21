#!/usr/bin/env node
/* Deterministic regression test for the read-only pre-live gate. */
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const repo = path.resolve(__dirname, '..');
const gate = path.join(repo, 'scripts', 'evaluate-bond-shadow-gate.cjs');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'bond-gate-test-'));
const arms = ['off00', 'off05', 'off10', 'off20'];
const startedAt = Date.now() - 48 * 3600 * 1000;

function stateFor(n, pnl) {
  const fills = [];
  const cycles = [];
  const settlements = [];
  for (let i = 0; i < n; i++) {
    fills.push({ marketId: 'm', orderId: `b-${i}`, side: 'BUY', price: 0.98, shares: 25, timestamp: startedAt + i });
    cycles.push({
      marketId: 'm', entryOrderId: `b-${i}`, exitOrderId: `s-${i}`,
      entryAt: startedAt, exitAt: startedAt + 1000, entryPrice: 0.98, exitPrice: 1.0,
      shares: 25, pnl, holdingSeconds: 1000, exitType: 'SETTLE',
    });
    settlements.push({
      marketId: 'm', favoriteTokenId: 't', entryOrderId: `b-${i}`, cycleExitOrderId: `s-${i}`,
      settledAt: startedAt + 1000, entryAt: startedAt, entryPrice: 0.98, exitPrice: 1.0,
      shares: 25, pnl, outcomeIndex: 0, negativeRisk: false, redemptionStatus: 'ALREADY_ABSENT',
    });
  }
  return {
    version: 4, mode: 'SHADOW', accountId: null, startedAt,
    cumulativeRealizedPnl: n * pnl, dailyPnlDay: 1, dailyPnlBaseline: 0,
    markets: [{
      conditionId: 'm', phase: 'FLAT', inventoryShares: '0', entryPrice: '0', entryAt: 0,
      realizedPnl: n * pnl, completedCycles: n, activeOrderId: null, activeOrderSide: null,
      activeOrderPrice: null, activeOrderOriginalShares: null, activeOrderMatchedShares: null,
      activeOrderStatus: null, totalBuyNotional: 0, activeOrderCreatedAt: 0, currentEntryOrderId: null,
      currentEntryShares: '0', fills, cycles, favoriteTokenId: 't', settlements,
      favoriteIndex: 0, negativeRisk: false, endTimestamp: 1, tickSize: 0.001,
    }],
  };
}

function writeScenario(name, n, pnl) {
  const dirs = [];
  for (const arm of arms) {
    const dir = path.join(root, `${name}-${arm}`);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'state.json'), '\uFEFF' + JSON.stringify(stateFor(n, pnl)), 'utf8');
    dirs.push(dir);
  }
  return dirs;
}

function runGate(dirs) {
  const joined = dirs.join(',');
  const result = spawnSync(process.execPath, [gate, '--dirs', joined, '--candidates', joined], {
    encoding: 'utf8',
    cwd: repo,
  });
  const line = String(result.stdout || '').split(/\r?\n/).find(l => l.startsWith('BOND Shadow gate:'));
  if (!line) throw new Error(`gate produced no status line: ${result.stderr}`);
  return line.slice('BOND Shadow gate:'.length).trim();
}

const cases = [
  { name: 'pass', n: 250, pnl: 0.5, expected: 'PASS' },
  { name: 'wait', n: 25, pnl: 0.5, expected: 'WAIT' },
  { name: 'fail', n: 25, pnl: -24.5, expected: 'FAIL' },
];
try {
  let passed = 0;
  for (const test of cases) {
    const actual = runGate(writeScenario(test.name, test.n, test.pnl));
    if (actual !== test.expected) throw new Error(`${test.name}: expected ${test.expected}, got ${actual}`);
    console.log(`gate ${test.name}: ${actual}`);
    passed++;
  }
  console.log(`bond gate regression: ${passed}/${cases.length} PASS`);
} finally {
  fs.rmSync(root, { recursive: true, force: true });
}