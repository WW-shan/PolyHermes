#!/usr/bin/env node
/*
 * Event Maker research recheck: true markouts + stop-loss feasibility.
 *
 * Background: the historical backtest wrote `markout_60s_pnl` using
 * `entryTimestamp + 60_000` while the research dataset timestamps are epoch
 * SECONDS, so the column actually measured ~16.7 hours. This script recomputes
 * the true 60s/300s/1800s markouts directly from the dataset and also tests
 * whether a post-fill stop-loss could contain the settlement tail risk.
 *
 * Usage (PowerShell):
 *   node scripts/analyze-event-maker-markout-and-stops.js `
 *     --dataset backend/build/research/event-maker-full-v2-7d-20261019-dataset.json.gz `
 *     --cycles  backend/build/research/event-maker-gated-markoutfix-horizon-1d-20261020-focus-cycles.csv `
 *     --output  backend/build/research/event-maker-markout-recheck-1d-20261020.json
 */
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

function parseArgs(argv) {
  const out = {};
  for (let i = 2; i < argv.length; i++) {
    const key = argv[i];
    if (!key.startsWith('--')) continue;
    out[key.slice(2)] = argv[i + 1];
    i++;
  }
  return out;
}

function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = '';
  let inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; } else inQuotes = false;
      } else {
        field += ch;
      }
    } else if (ch === '"') {
      inQuotes = true;
    } else if (ch === ',') {
      row.push(field); field = '';
    } else if (ch === '\n') {
      row.push(field); rows.push(row); row = []; field = '';
    } else if (ch !== '\r') {
      field += ch;
    }
  }
  if (field.length > 0 || row.length > 0) { row.push(field); rows.push(row); }
  const header = rows[0];
  return rows.slice(1)
    .filter(r => r.length === header.length)
    .map(r => { const o = {}; header.forEach((h, i) => { o[h] = r[i]; }); return o; });
}

const FEE_RATE_BY_TAG = { Politics: 0.04, Geopolitics: 0.0 };

function takerFee(shares, price, rate) {
  if (!rate || price <= 0 || price >= 1) return 0;
  return Math.round(shares * rate * price * (1 - price) * 1e5) / 1e5;
}

function markoutAt(trades, entryTs, horizonSeconds, entryPrice, shares) {
  const target = entryTs + horizonSeconds;
  let hit = trades.find(t => t.timestamp >= target);
  if (!hit) {
    const within = trades.filter(t => t.timestamp >= entryTs && t.timestamp <= target);
    hit = within.length ? within[within.length - 1] : null;
  }
  return hit ? { price: hit.price, timestamp: hit.timestamp, pnl: (hit.price - entryPrice) * shares } : null;
}

function stopLossStudy(rows, stopCents) {
  const detail = [];
  for (const r of rows) {
    const stopPrice = r.entryPrice - stopCents / 100;
    let hit = null;
    for (const t of r.trades) {
      if (r.exitType === 'SELL' && t.timestamp >= r.exitTs) break;
      if (t.price <= stopPrice) { hit = t; break; }
    }
    if (hit) {
      const pnl = (hit.price - r.entryPrice) * r.shares - takerFee(r.shares, hit.price, r.rate);
      detail.push({
        question: r.question, tag: r.tag, entryPrice: r.entryPrice, actualPnl: r.actualPnl,
        stopPnl: pnl, stopPrice: hit.price, delaySeconds: hit.timestamp - r.entryTs,
        whipsaw: r.actualPnl > 0 && pnl < 0, tailSaved: r.actualPnl < -50
      });
    } else {
      detail.push({
        question: r.question, tag: r.tag, entryPrice: r.entryPrice, actualPnl: r.actualPnl,
        stopPnl: r.actualPnl, stopPrice: null, delaySeconds: null, whipsaw: false, tailSaved: false
      });
    }
  }
  const triggered = detail.filter(d => d.delaySeconds !== null);
  return {
    stopCents,
    totalPnl: detail.reduce((a, d) => a + d.stopPnl, 0),
    actualTotalPnl: detail.reduce((a, d) => a + d.actualPnl, 0),
    triggered: triggered.length,
    worstCyclePnl: Math.min(...detail.map(d => d.stopPnl)),
    lossCycles: detail.filter(d => d.stopPnl < 0).length,
    whipsaws: detail.filter(d => d.whipsaw).length,
    tailsSaved: detail.filter(d => d.tailSaved).length,
    detail
  };
}

function main() {
  const args = parseArgs(process.argv);
  if (!args.dataset || !args.cycles || !args.output) {
    console.error('required: --dataset <gz> --cycles <csv> --output <json>');
    process.exit(2);
  }
  const cycles = parseCsv(fs.readFileSync(args.cycles, 'utf8'));
  const dataset = JSON.parse(zlib.gunzipSync(fs.readFileSync(args.dataset)).toString('utf8'));
  const byId = new Map(dataset.markets.map(m => [m.conditionId, m]));

  const rows = [];
  const markouts = [];
  for (const c of cycles) {
    const market = byId.get(c.condition_id);
    if (!market) { markouts.push({ condition_id: c.condition_id, missing: true }); continue; }
    const favorite = Number(c.favorite_outcome);
    const entryTs = Number(c.entry_timestamp);
    const entryPrice = Number(c.entry_price);
    const shares = Number(c.shares);
    const exitTs = Number(c.exit_timestamp);
    const trades = market.trades
      .filter(t => t.outcomeIndex === favorite && t.timestamp >= entryTs && t.price > 0 && t.price < 1 && t.size > 0)
      .sort((a, b) => a.timestamp - b.timestamp);

    const m60 = markoutAt(trades, entryTs, 60, entryPrice, shares);
    const m300 = markoutAt(trades, entryTs, 300, entryPrice, shares);
    const m1800 = markoutAt(trades, entryTs, 1800, entryPrice, shares);
    const firstBelow = level => {
      const hit = trades.find(t => t.price <= level);
      return hit ? hit.timestamp - entryTs : null;
    };

    markouts.push({
      condition_id: c.condition_id, question: c.question, tag: c.tag,
      entryTimestamp: entryTs, entryPrice, shares, exitType: c.exit_type, pnl: Number(c.pnl),
      csvMarkout60s: c.markout_60s_pnl === '' ? null : Number(c.markout_60s_pnl),
      trueMarkout60s: m60 ? m60.pnl : null,
      trueMarkout300s: m300 ? m300.pnl : null,
      trueMarkout1800s: m1800 ? m1800.pnl : null,
      firstSecondsBelow: {
        '0.95': firstBelow(0.95), '0.90': firstBelow(0.90), '0.75': firstBelow(0.75),
        '0.50': firstBelow(0.50), '0.25': firstBelow(0.25), '0.10': firstBelow(0.10)
      },
      printsAfterEntry: trades.length
    });
    rows.push({
      question: c.question, tag: c.tag, entryTs, entryPrice, shares, exitTs,
      exitType: c.exit_type, actualPnl: Number(c.pnl),
      rate: FEE_RATE_BY_TAG[c.tag] ?? 0, trades
    });
  }

  const with60 = markouts.filter(m => m.trueMarkout60s !== null && !m.missing);
  const sum = with60.reduce((a, m) => a + m.trueMarkout60s, 0);
  const stops = [1, 2, 3, 5, 8, 10, 15, 20].map(c => stopLossStudy(rows, c));
  const report = {
    generatedAt: new Date().toISOString(),
    datasetMarkets: dataset.markets.length,
    cycles: cycles.length,
    trueMarkout60sSummary: {
      samples: with60.length,
      sum: Number(sum.toFixed(4)),
      mean: with60.length ? Number((sum / with60.length).toFixed(4)) : null,
      negative: with60.filter(m => m.trueMarkout60s < 0).length
    },
    stopLossSummary: stops.map(s => ({
      stopCents: s.stopCents, totalPnl: Number(s.totalPnl.toFixed(4)),
      actualTotalPnl: Number(s.actualTotalPnl.toFixed(4)), triggered: s.triggered,
      worstCyclePnl: Number(s.worstCyclePnl.toFixed(4)), lossCycles: s.lossCycles,
      whipsaws: s.whipsaws, tailsSaved: s.tailsSaved
    })),
    markouts,
    stopLossDetail: stops.map(s => ({ stopCents: s.stopCents, detail: s.detail }))
  };
  fs.writeFileSync(args.output, JSON.stringify(report, null, 2));
  console.log(`cycles=${report.cycles} samples60s=${report.trueMarkout60sSummary.samples} ` +
    `sum60s=${report.trueMarkout60sSummary.sum} mean60s=${report.trueMarkout60sSummary.mean} ` +
    `negative60s=${report.trueMarkout60sSummary.negative}`);
  for (const s of report.stopLossSummary) {
    console.log(`stop=-${s.stopCents}c total=${s.totalPnl} triggered=${s.triggered} ` +
      `lossCycles=${s.lossCycles} whipsaws=${s.whipsaws} tailsSaved=${s.tailsSaved}`);
  }
  console.log(`wrote ${path.resolve(args.output)}`);
}

main();