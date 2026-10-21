#!/usr/bin/env node
/* Deterministic regression test for tail-exit clustering in audit-bond-tail-risk.cjs. */
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const repo = path.resolve(__dirname, '..');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'bond-tail-audit-test-'));
const input = path.join(root, 'input.json');
const output = path.join(root, 'output.json');
const base = {
  band: '0.97-0.99', tag: 'Geopolitics', targetCents: 2, queueFactor: 2,
  minHoursToClose: 72, maxHoursToClose: 168, lots: 100, settleLossCount: 0,
  meanRoi: 0.018, avgWin: 0.014, avgLoss: 0,
};
const report = {
  results: [
    {
      ...base,
      tailExitMarkets: 60, tailExitLossMarkets: 0, tailExitLossRate: 0,
      tailExitLossCi95: [0, 0.06], tailExitAvgWin: 0.014, tailExitAvgLoss: 0,
      tailExitBreakEvenLossRate: 0.58, tailStressLossRoi: 0.01,
      settleMarkets: 0, settleLossMarkets: 0,
    },
    {
      ...base,
      lots: 10, meanRoi: 0.004,
      tailExitMarkets: 10, tailExitLossMarkets: 0, tailExitLossRate: 0,
      tailExitLossCi95: [0, 0.278], tailExitAvgWin: 0.004, tailExitAvgLoss: 0,
      tailExitBreakEvenLossRate: 0.017, tailStressLossRoi: 1.0,
      settleMarkets: 10, settleLossMarkets: 0,
    },
  ],
};
fs.writeFileSync(input, JSON.stringify(report), 'utf8');
const run = spawnSync(process.execPath, [path.join(repo, 'scripts', 'audit-bond-tail-risk.cjs'), '--input', input, '--output', output], { encoding: 'utf8' });
if (run.status !== 0) throw new Error(run.stderr || 'audit failed');
const rows = JSON.parse(fs.readFileSync(output, 'utf8')).rows;
if (!rows[0].marketStatisticallyPositive) throw new Error('60-market flatten stress should pass');
if (rows[1].marketStatisticallyPositive) throw new Error('10-market settlement stress should fail');
console.log('bond tail audit regression: 2/2 PASS');
