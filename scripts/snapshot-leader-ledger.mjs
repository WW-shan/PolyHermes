#!/usr/bin/env node
/*
 * Read-only snapshot of a Polymarket leader's public ledger inputs.
 * Fetches trades, open positions, closed positions and activity, with pagination.
 * Never reads credentials or signs orders.
 */
const DEFAULT_BASE = "https://data-api.polymarket.com";
const DEFAULT_ADDRESS = "0x0260366a9add43c25d4f4d35d2210526c6750d88";

function arg(name, fallback) {
  const i = process.argv.indexOf("--" + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
function keyFor(kind, row) {
  if (kind === "trades") return [row.transactionHash, row.asset, row.side, row.timestamp, row.size, row.price].join("|");
  if (kind === "activity") return [row.transactionHash, row.type, row.timestamp, row.usdcSize, row.conditionId, row.asset].join("|");
  return [row.conditionId, row.asset, row.outcome, row.endDate].join("|");
}
async function fetchPages(kind, base, address, limit, maxPages) {
  const rows = [];
  const seen = new Set();
  let pages = 0;
  let truncated = false;
  for (let page = 0; page < maxPages; page += 1) {
    const offset = page * limit;
    const url = `${base}/${kind}?user=${encodeURIComponent(address)}&limit=${limit}&offset=${offset}`;
    const response = await fetch(url, { headers: { "User-Agent": "PolyHermes-research/1.0", Accept: "application/json" }, signal: AbortSignal.timeout(30_000) });
    if (response.status === 400 && kind === "activity") {
      // Activity has a server-side offset cap; keep the rows fetched so far.
      break;
    }
    if (!response.ok) throw new Error(`${kind} HTTP ${response.status}: ${(await response.text()).slice(0, 300)}`);
    const pageRows = await response.json();
    if (!Array.isArray(pageRows)) throw new Error(`${kind} returned non-array`);
    pages += 1;
    for (const row of pageRows) {
      const key = keyFor(kind, row);
      if (seen.has(key)) continue;
      seen.add(key);
      rows.push(row);
    }
    if (pageRows.length < limit) break;
    if (page + 1 >= maxPages) truncated = true;
  }
  return { rows, pages, truncated };
}

const address = arg("address", DEFAULT_ADDRESS);
const base = arg("base-url", DEFAULT_BASE).replace(/\/+$/, "");
const output = arg("output");
if (!/^0x[0-9a-fA-F]{40}$/.test(address)) throw new Error("invalid --address");
if (!output) throw new Error("required: --output <json>");

const [trades, positions, closed, activity] = await Promise.all([
  fetchPages("trades", base, address, 500, 40),
  fetchPages("positions", base, address, 50, 40),
  fetchPages("closed-positions", base, address, 50, 40),
  fetchPages("activity", base, address, 500, 20)
]);
const snapshot = {
  generatedAt: new Date().toISOString(),
  address,
  source: base,
  counts: {
    trades: trades.rows.length,
    positions: positions.rows.length,
    closedPositions: closed.rows.length,
    activity: activity.rows.length
  },
  pagination: {
    trades: { pages: trades.pages, truncated: trades.truncated },
    positions: { pages: positions.pages, truncated: positions.truncated },
    closedPositions: { pages: closed.pages, truncated: closed.truncated },
    activity: { pages: activity.pages, truncated: activity.truncated }
  },
  trades: trades.rows,
  positions: positions.rows,
  closedPositions: closed.rows,
  activity: activity.rows
};
const fs = await import("node:fs/promises");
await fs.writeFile(output, JSON.stringify(snapshot, null, 2) + "\n", "utf8");
console.log(JSON.stringify(snapshot.counts));
console.log(`wrote ${output}`);