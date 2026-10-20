#!/usr/bin/env node

/**
 * Read-only Polymarket leader PnL snapshot.
 *
 * Uses public Data API positions and closed-positions endpoints. It never
 * reads credentials, signs orders, or submits trades.
 */

const DEFAULT_BASE_URL = "https://data-api.polymarket.com";
const DEFAULT_PAGE_SIZE = 50;
const DEFAULT_MAX_PAGES = 200;
const DEFAULT_ADDRESS = "0x0260366a9add43c25d4f4d35d2210526c6750d88";

const DEFAULTS = Object.freeze({
  address: DEFAULT_ADDRESS,
  pageSize: DEFAULT_PAGE_SIZE,
  maxPages: DEFAULT_MAX_PAGES,
  baseUrl: DEFAULT_BASE_URL,
  includeRows: false,
  output: null,
});

function positiveIntegerArg(arg, raw, minimum) {
  const value = Number(raw);
  if (!Number.isInteger(value) || value < minimum) throw new Error(`${arg} must be an integer >= ${minimum}`);
  return value;
}

export function parseSnapshotArgs(argv) {
  const options = { ...DEFAULTS, help: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => {
      i += 1;
      if (i >= argv.length) throw new Error(`missing value for ${arg}`);
      return argv[i];
    };
    switch (arg) {
      case "--address": options.address = next(); break;
      case "--page-size": options.pageSize = positiveIntegerArg(arg, next(), 1); break;
      case "--max-pages": options.maxPages = positiveIntegerArg(arg, next(), 1); break;
      case "--base-url": options.baseUrl = next().replace(/\/+$/, ""); break;
      case "--include-rows": options.includeRows = true; break;
      case "--output": options.output = next(); break;
      case "--help":
      case "-h": options.help = true; break;
      default: throw new Error(`unknown argument: ${arg}`);
    }
  }
  if (!/^0x[0-9a-fA-F]{40}$/.test(options.address)) throw new Error("--address must be a 0x-prefixed 20-byte address");
  if (options.pageSize > 500) throw new Error("--page-size must be <= 500");
  return options;
}

export function summarizeLeaderPositions(openPositions, closedPositions, meta = {}) {
  const sum = (rows, key) => rows.reduce((total, row) => {
    const value = Number(row?.[key]);
    return total + (Number.isFinite(value) ? value : 0);
  }, 0);
  const openInitial = sum(openPositions, "initialValue");
  const openCurrent = sum(openPositions, "currentValue");
  const openCashPnl = sum(openPositions, "cashPnl");
  const closedRealized = sum(closedPositions, "realizedPnl");
  const closedBought = sum(closedPositions, "totalBought");
  const byYear = new Map();
  for (const row of closedPositions) {
    const endDate = String(row?.endDate ?? "");
    const year = /^\d{4}/.test(endDate) ? endDate.slice(0, 4) : "unknown";
    const entry = byYear.get(year) ?? { count: 0, realizedPnl: 0, totalBought: 0 };
    entry.count += 1;
    entry.realizedPnl += Number(row?.realizedPnl) || 0;
    entry.totalBought += Number(row?.totalBought) || 0;
    byYear.set(year, entry);
  }
  const closedPositive = closedPositions.filter((row) => Number(row?.realizedPnl) > 0).length;
  const closedNegative = closedPositions.filter((row) => Number(row?.realizedPnl) < 0).length;
  return {
    generatedAt: meta.generatedAt ?? new Date().toISOString(),
    address: meta.address ?? null,
    source: meta.source ?? "data-api.polymarket.com positions + closed-positions",
    complete: meta.complete ?? null,
    open: {
      count: openPositions.length,
      initialValue: openInitial,
      currentValue: openCurrent,
      cashPnl: openCashPnl,
      unrealizedReturnPct: openInitial > 0 ? openCashPnl / openInitial * 100 : null,
      totalBought: sum(openPositions, "totalBought"),
      yesCount: openPositions.filter((row) => String(row?.outcome).toLowerCase() === "yes").length,
      noCount: openPositions.filter((row) => String(row?.outcome).toLowerCase() === "no").length,
      negRiskCount: openPositions.filter((row) => row?.negativeRisk === true).length,
    },
    closed: {
      count: closedPositions.length,
      realizedPnl: closedRealized,
      totalBought: closedBought,
      realizedReturnPct: closedBought > 0 ? closedRealized / closedBought * 100 : null,
      positiveCount: closedPositive,
      negativeCount: closedNegative,
      flatCount: closedPositions.length - closedPositive - closedNegative,
      byEndYear: [...byYear.entries()]
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([year, value]) => ({ year, ...value, realizedReturnPct: value.totalBought > 0 ? value.realizedPnl / value.totalBought * 100 : null })),
    },
    caveat: "Closed-position realized PnL and open-position cash PnL are reported separately; do not add them as a full account PnL without cash-flow reconciliation.",
  };
}

async function fetchJson(url, fetchPage) {
  if (fetchPage) return fetchPage(url);
  const response = await fetch(url, {
    headers: { "User-Agent": "PolyHermes-research/1.0", Accept: "application/json" },
    signal: AbortSignal.timeout(30_000),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status} ${url}: ${(await response.text()).slice(0, 300)}`);
  return response.json();
}

export async function collectLeaderPositions(kind, options, fetchPage = null) {
  if (!["positions", "closed-positions"].includes(kind)) throw new Error(`unsupported kind: ${kind}`);
  const rows = [];
  const seen = new Set();
  let pages = 0;
  let lastPageFull = false;
  for (let page = 0; page < options.maxPages; page += 1) {
    const offset = page * options.pageSize;
    const url = `${options.baseUrl}/${kind}?user=${encodeURIComponent(options.address)}` +
      `&limit=${options.pageSize}&offset=${offset}`;
    const pageRows = await fetchJson(url, fetchPage);
    if (!Array.isArray(pageRows)) throw new Error(`invalid ${kind} response`);
    pages += 1;
    lastPageFull = pageRows.length >= options.pageSize;
    for (const row of pageRows) {
      const key = [row?.conditionId, row?.asset, row?.outcome, row?.endDate].join("|");
      if (seen.has(key)) continue;
      seen.add(key);
      rows.push(row);
    }
    if (!lastPageFull) break;
  }
  return { rows, pages, truncated: lastPageFull && pages >= options.maxPages };
}

async function main(argv = process.argv.slice(2)) {
  const options = parseSnapshotArgs(argv);
  if (options.help) {
    console.log("Usage: node scripts/snapshot-leader-pnl.mjs [options]");
    console.log("  --address 0x...       Public proxy wallet");
    console.log("  --page-size 50        Data API page size");
    console.log("  --max-pages 200       Stop before unbounded pagination");
    console.log("  --include-rows        Include raw public rows");
    console.log("  --output PATH         Write JSON instead of stdout");
    return;
  }
  if ((process.env.HTTPS_PROXY || process.env.HTTP_PROXY) && process.env.NODE_USE_ENV_PROXY !== "1") {
    console.warn("warning: NODE_USE_ENV_PROXY is not 1; requests may bypass the proxy");
  }
  const [open, closed] = await Promise.all([
    collectLeaderPositions("positions", options),
    collectLeaderPositions("closed-positions", options),
  ]);
  const summary = summarizeLeaderPositions(open.rows, closed.rows, {
    address: options.address,
    source: options.baseUrl,
    complete: !open.truncated && !closed.truncated,
  });
  const report = {
    ...summary,
    pagination: {
      openPages: open.pages,
      closedPages: closed.pages,
      openTruncated: open.truncated,
      closedTruncated: closed.truncated,
    },
    ...(options.includeRows ? { openPositions: open.rows, closedPositions: closed.rows } : {}),
  };
  const json = `${JSON.stringify(report, null, 2)}\n`;
  if (options.output) {
    const fs = await import("node:fs/promises");
    await fs.writeFile(options.output, json, "utf8");
    console.log(`wrote ${options.output}`);
  } else {
    process.stdout.write(json);
  }
}

import { pathToFileURL } from "node:url";
import path from "node:path";
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch((error) => {
    console.error(error?.stack || error);
    process.exitCode = 1;
  });
}
