#!/usr/bin/env node
/*
 * Event Maker base-rate / expected-value study.
 *
 * For each entry horizon, take every dataset market whose favorite traded in
 * [0.97, 0.99) at (close - horizon), and count how often that favorite lost.
 * Compare that unconditional tail rate against the strategy's per-cycle profit
 * and break-even loss rate. This is the structural question: can ~1% per win
 * cover the observed tail frequency?
 *
 * Usage:
 *   node scripts/analyze-event-maker-tail-base-rate.cjs `
 *     --dataset backend/build/research/event-maker-full-v2-7d-20261019-dataset.json.gz `
 *     --output  backend/build/research/event-maker-tail-base-rate-20261020.json
 */
const fs = require('fs');
const zlib = require('zlib');

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

function referenceAt(market, timestamp) {
  const last = {};
  for (const trade of market.trades) {
    if (trade.timestamp <= timestamp && trade.price > 0 && trade.price < 1) {
      last[trade.outcomeIndex] = trade.price;
    }
  }
  let favorite = -1;
  let price = -1;
  for (const key of Object.keys(last)) {
    if (last[key] > price) {
      price = last[key];
      favorite = Number(key);
    }
  }
  return favorite < 0 ? null : { favorite, price };
}

const BANDS = [
  { label: '0.95-0.97', low: 0.95, high: 0.97 },
  { label: '0.97-0.99', low: 0.97, high: 0.99 },
  { label: '0.985-0.995', low: 0.985, high: 0.995 },
  { label: '0.99-0.999', low: 0.99, high: 0.999 }
];

const HORIZONS = [
  { label: '12h', hours: 12 },
  { label: '1d', hours: 24 },
  { label: '2d', hours: 48 },
  { label: '3d', hours: 72 },
  { label: '4d', hours: 96 },
  { label: '5d', hours: 120 },
  { label: '6d', hours: 144 },
  { label: '7d', hours: 168 }
];

// Candidate economics from the 1d backtest: ~1.02% gross profit per winning
// cycle for the 0.97..0.99 band, and a -100% notional loss when the favorite
// resolves to zero.
const WIN_PROFIT = 0.0102;
const BREAK_EVEN_LOSS_RATE = WIN_PROFIT / (WIN_PROFIT + 1.0);

const datasetPath = arg('dataset');
const outputPath = arg('output');
if (!datasetPath || !outputPath) {
  console.error('required: --dataset <gz> --output <json>');
  process.exit(2);
}

const dataset = JSON.parse(zlib.gunzipSync(fs.readFileSync(datasetPath)).toString('utf8'));
const rows = [];
const bandRows = [];
for (const band of BANDS) {
  for (const horizon of HORIZONS) {
    const seconds = horizon.hours * 3600;
    let eligible = 0;
    let favoriteLost = 0;
    const lost = [];
    for (const market of dataset.markets) {
      const reference = referenceAt(market, market.closedTimestamp - seconds);
      if (!reference) continue;
      if (reference.price < band.low || reference.price >= band.high) continue;
      eligible++;
      if (reference.favorite !== market.winnerIndex) {
        favoriteLost++;
        lost.push({ question: market.question, tag: market.tag, reference: reference.price });
      }
    }
    const lossRate = eligible > 0 ? favoriteLost / eligible : 0;
    const expectedValue = (1 - lossRate) * WIN_PROFIT - lossRate;
    const row = {
      band: band.label,
      window: horizon.label,
      eligible,
      favoriteLost,
      lossRate,
      breakEvenLossRate: BREAK_EVEN_LOSS_RATE,
      expectedValuePerCycle: expectedValue,
      structurallyPositive: expectedValue > 0,
      lostMarketSample: lost.slice(0, 10)
    };
    bandRows.push(row);
    if (band.label === '0.97-0.99') rows.push(row);
  }
}

const report = {
  generatedAt: new Date().toISOString(),
  datasetMarkets: dataset.markets.length,
  priceBand: [0.97, 0.99],
  winProfit: WIN_PROFIT,
  breakEvenLossRate: BREAK_EVEN_LOSS_RATE,
  rows,
  bandRows
};
fs.writeFileSync(outputPath, JSON.stringify(report, null, 2));

console.log('window eligible lost lossRate breakEven EVPerCycle structurallyPositive');
for (const row of rows) {
  console.log(
    `${row.window.padStart(4)} ${String(row.eligible).padStart(6)} ${String(row.favoriteLost).padStart(5)} ` +
    `${(100 * row.lossRate).toFixed(2).padStart(7)}% ${(100 * row.breakEvenLossRate).toFixed(2).padStart(8)}% ` +
    `${(100 * row.expectedValuePerCycle).toFixed(3).padStart(9)}% ${row.structurallyPositive}`
  );
}
console.log(`wrote ${outputPath}`);