#!/usr/bin/env node

/**
 * Scan same-event calendar spreads for monotone-date arbitrage.
 *
 * For two YES/NO markets E1 and E2 in the same event, if E1 ("by earlier
 * date") implies E2 ("by later date"), then:
 *
 *   BUY NO(E1) + BUY YES(E2)
 *
 * pays at least 1 per share in every state. This scanner only selects pairs
 * with matching date-normalized question text, then checks executable ask
 * depth, fees, staleness and a gas/safety buffer.
 *
 * The output is research evidence, not an automatic live signal. Resolution
 * rules must still be reviewed before trading.
 */

import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import {
  bookHealth,
  clearBookCache,
  clearRunCaches,
  costForShares,
  discoverTag,
  floorToStep,
  getBook,
  maxPositiveShares,
  normalizeMarket,
  resolveFeeRate,
} from "./scan-event-complete-set-arbitrage.mjs";

const DEFAULTS = Object.freeze({
  tags: ["politics", "geopolitics"],
  maxMarketsPerTag: 1000,
  minVolume24h: 0,
  minEdgeCents: 0.5,
  minProfitUsd: 0.01,
  maxBookAgeMs: 15_000,
  gasUsd: 0.02,
  sizeStep: 0.01,
  maxPairs: 5000,
  concurrency: 8,
  output: null,
  csv: null,
});

function numberArg(arg, raw, minimum, { allowZero = false } = {}) {
  const value = Number(raw);
  if (!Number.isFinite(value) || (allowZero ? value < minimum : value <= minimum)) {
    throw new Error(`${arg} must be ${allowZero ? ">=" : ">"} ${minimum}`);
  }
  return value;
}

export function parseArgs(argv) {
  const options = { ...DEFAULTS, tags: [...DEFAULTS.tags], help: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => {
      i += 1;
      if (i >= argv.length) throw new Error(`missing value for ${arg}`);
      return argv[i];
    };
    switch (arg) {
      case "--tags":
        options.tags = [...new Set(next().split(",").map((value) => value.trim()).filter(Boolean))];
        break;
      case "--max-markets-per-tag":
        options.maxMarketsPerTag = Math.floor(numberArg(arg, next(), 0));
        break;
      case "--min-volume":
        options.minVolume24h = numberArg(arg, next(), 0, { allowZero: true });
        break;
      case "--min-edge-cents":
        options.minEdgeCents = numberArg(arg, next(), 0, { allowZero: true });
        break;
      case "--min-profit-usd":
        options.minProfitUsd = numberArg(arg, next(), 0, { allowZero: true });
        break;
      case "--max-book-age-ms":
        options.maxBookAgeMs = Math.floor(numberArg(arg, next(), 0));
        break;
      case "--gas-usd":
        options.gasUsd = numberArg(arg, next(), 0, { allowZero: true });
        break;
      case "--size-step":
        options.sizeStep = numberArg(arg, next(), 0);
        break;
      case "--max-pairs":
        options.maxPairs = Math.floor(numberArg(arg, next(), 0));
        break;
      case "--concurrency":
        options.concurrency = Math.floor(numberArg(arg, next(), 0));
        break;
      case "--output":
        options.output = next();
        break;
      case "--csv":
        options.csv = next();
        break;
      case "--help":
        options.help = true;
        break;
      default:
        throw new Error(`unknown argument: ${arg}`);
    }
  }
  if (options.tags.length === 0) throw new Error("at least one tag is required");
  return options;
}

const DATE_PATTERNS = [
  /\b(?:january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s+(?:19|20)\d{2})?\b/giu,
  /\b(?:19|20)\d{2}-\d{1,2}-\d{1,2}\b/gu,
  /\b\d{1,2}\/\d{1,2}(?:\/\d{2,4})?\b/gu,
  /\bq[1-4](?:\s+(?:19|20)\d{2})?\b/giu,
  /\b(?:19|20)\d{2}\b/gu,
];

function normaliseText(value) {
  return String(value ?? "").normalize("NFKC").toLowerCase();
}

export function questionSkeleton(question) {
  let text = normaliseText(question);
  const dateTokens = [];
  for (const pattern of DATE_PATTERNS) {
    pattern.lastIndex = 0;
    text = text.replace(pattern, (match) => {
      dateTokens.push(match);
      return " <date> ";
    });
  }
  const skeleton = text
    .replace(/[^\p{L}\p{N}<>]+/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
  return { skeleton, dateTokens };
}

function hasCalendarWording(skeleton) {
  return /\b(by|before|through|until|no later than|end of)\b/.test(skeleton);
}

export function inferMonotonicDirection(question, description = "") {
  const text = normaliseText(question);
  if (/\bno\s+longer\b/.test(text)) return "positive";
  if (/\b(continues?|remains?|stays?|survives?)\b[\s\S]*\b(through|until)\b/.test(text)) return "negative";
  if (/\b(?:ceasefire|truce)\s+holds?\b/.test(text)) return "negative";
  if (/\b(?:without|avoids?|fails?|prevents?)\b/.test(text)) return "negative";
  if (/\b(?:no|not)\b/.test(text)) return "negative";

  const desc = normaliseText(description);
  const yesPosition = desc.search(/resolve(?:s)? to\s+["']?yes\b/);
  const noPosition = desc.search(/resolve(?:s)? to\s+["']?no\b/);
  const otherwisePosition = desc.indexOf("otherwise");
  if (otherwisePosition >= 0) {
    if (yesPosition >= 0 && yesPosition < otherwisePosition && (noPosition < 0 || yesPosition < noPosition)) return "positive";
    if (noPosition >= 0 && noPosition < otherwisePosition && (yesPosition < 0 || noPosition < yesPosition)) return "negative";
  }
  return "positive";
}
function timestamp(value) {
  const parsed = Date.parse(value ?? "");
  return Number.isFinite(parsed) ? parsed : null;
}

export function buildCalendarPairs(markets, options = DEFAULTS) {
  const byEvent = new Map();
  for (const market of markets) {
    if (!market || market.closed || !market.active || !market.enableOrderBook || !market.acceptingOrders) continue;
    if (!market.eventSlug || !market.yesTokenId || !market.noTokenId) continue;
    if (market.volume24hr < options.minVolume24h) continue;
    const list = byEvent.get(market.eventSlug) ?? [];
    list.push(market);
    byEvent.set(market.eventSlug, list);
  }

  const pairs = [];
  for (const [eventSlug, list] of byEvent) {
    if (list.length < 2) continue;
    const annotated = list.map((market) => ({
      market,
      ...questionSkeleton(market.question),
      endTimestamp: timestamp(market.endDate),
    })).filter((item) => item.endTimestamp != null);
    for (let i = 0; i < annotated.length; i += 1) {
      for (let j = i + 1; j < annotated.length; j += 1) {
        const a = annotated[i];
        const b = annotated[j];
        if (a.skeleton !== b.skeleton || !hasCalendarWording(a.skeleton)) continue;
        const descriptionA = questionSkeleton(a.market.description).skeleton;
        const descriptionB = questionSkeleton(b.market.description).skeleton;
        if (descriptionA !== descriptionB) continue;
        const earlier = a.endTimestamp < b.endTimestamp ? a : b;
        const later = a.endTimestamp < b.endTimestamp ? b : a;
        if (later.endTimestamp <= earlier.endTimestamp) continue;
        const sameQuestionDates = earlier.dateTokens.join("|") === later.dateTokens.join("|");
        if (sameQuestionDates && later.endTimestamp - earlier.endTimestamp < 2 * 86_400_000) continue;
        const key = `${eventSlug}:${earlier.market.conditionId}:${later.market.conditionId}`;
        pairs.push({
          key,
          eventSlug,
          eventTitle: earlier.market.eventTitle || earlier.market.question,
          skeleton: earlier.skeleton,
          earlier: earlier.market,
          later: later.market,
          direction: inferMonotonicDirection(earlier.market.question, earlier.market.description),
          earlierEndDate: earlier.market.endDate,
          laterEndDate: later.market.endDate,
        });
      }
    }
  }
  return pairs
    .sort((a, b) => {
      const volumeA = Math.min(a.earlier.volume24hr, a.later.volume24hr);
      const volumeB = Math.min(b.earlier.volume24hr, b.later.volume24hr);
      return volumeB - volumeA;
    })
    .slice(0, options.maxPairs);
}
export function evaluateCalendarBooks(books, minShares, pair, rawOptions = {}) {
  const options = { ...DEFAULTS, ...rawOptions };
  const now = rawOptions.now ?? Date.now();
  const health = bookHealth(books, now, options.maxBookAgeMs);
  const topAskSum = books.some((entry) => entry.book.asks.length === 0)
    ? null
    : books.reduce((sum, entry) => sum + entry.book.asks[0].price, 0);
  const laterTimestamp = timestamp(pair.laterEndDate);
  const holdingDays = laterTimestamp == null ? null : Math.max(1 / 24, (laterTimestamp - now) / 86_400_000);
  const base = {
    status: "ok",
    key: pair.key,
    eventSlug: pair.eventSlug,
    eventTitle: pair.eventTitle,
    earlierQuestion: pair.earlier.question,
    laterQuestion: pair.later.question,
    earlierEndDate: pair.earlierEndDate,
    laterEndDate: pair.laterEndDate,
    earlierConditionId: pair.earlier.conditionId,
    laterConditionId: pair.later.conditionId,
    direction: pair.direction ?? "positive",
    feeRates: [...new Set(books.map((entry) => entry.feeRate))],
    minShares,
    oldestBookAgeMs: health.oldestBookAgeMs,
    holdingDays,
    requires: pair.direction === "negative" ? "buy_yes_earlier_and_no_later" : "buy_no_earlier_and_yes_later",
    nonAtomicLegs: 2,
    ruleReviewRequired: true,
    health,
  };

  if (topAskSum == null) {
    return { ...base, available: false, actionable: false, reason: "missing_ask", topAskSum: null };
  }
  const maxShares = maxPositiveShares(books, minShares, options.sizeStep, "ask");
  if (maxShares == null) {
    return {
      ...base,
      available: false,
      actionable: false,
      reason: topAskSum < 1 ? "no_positive_after_fees_or_depth" : "ask_sum_not_below_one",
      topAskSum,
    };
  }

  const cost = costForShares(books, maxShares);
  const gas = options.gasUsd ?? 0;
  const grossProfit = maxShares - cost.total;
  const profit = grossProfit - gas;
  const available = profit > 1e-9;
  const edgeCents = maxShares > 0 ? (profit / maxShares) * 100 : 0;
  let reason = "actionable";
  if (!available) reason = "not_profitable_after_costs";
  else if (!health.ok) reason = "invalid_or_crossed_book";
  else if (health.staleBook.length > 0) reason = "stale_book";
  else if (edgeCents < options.minEdgeCents) reason = "below_edge_buffer";
  else if (profit < options.minProfitUsd) reason = "below_min_profit";
  return {
    ...base,
    available,
    actionable: reason === "actionable",
    reason,
    topAskSum,
    maxShares,
    cash: cost.cash,
    fees: cost.fees,
    gas,
    grossProfit,
    profit,
    edgeCents,
    returnOnCapital: cost.total > 0 ? profit / cost.total : 0,
    annualizedReturn: holdingDays != null && cost.total > 0
      ? (profit / cost.total) * (365 / holdingDays)
      : null,
  };
}

export function tokensForCalendarPair(pair) {
  return pair.direction === "negative"
    ? { earlierToken: pair.earlier.yesTokenId, laterToken: pair.later.noTokenId }
    : { earlierToken: pair.earlier.noTokenId, laterToken: pair.later.yesTokenId };
}
export async function evaluateCalendarPair(pair, options) {
  const { earlierToken, laterToken } = tokensForCalendarPair(pair);
  const [earlierBook, laterBook] = await Promise.all([
    getBook(earlierToken),
    getBook(laterToken),
  ]);
  const [earlierFee, laterFee] = await Promise.all([
    resolveFeeRate(pair.earlier, earlierToken),
    resolveFeeRate(pair.later, laterToken),
  ]);
  if (earlierFee.rate == null || laterFee.rate == null) {
    return {
      status: "fee_rate_unavailable",
      key: pair.key,
      eventSlug: pair.eventSlug,
      eventTitle: pair.eventTitle,
      earlierQuestion: pair.earlier.question,
      laterQuestion: pair.later.question,
      direction: pair.direction,
      feeErrors: [earlierFee, laterFee],
    };
  }
  const books = [
    { book: earlierBook, feeRate: earlierFee.rate, feeSource: earlierFee.source },
    { book: laterBook, feeRate: laterFee.rate, feeSource: laterFee.source },
  ];
  const minShares = Math.max(
    options.sizeStep,
    5,
    pair.earlier.minOrderSize ?? 5,
    pair.later.minOrderSize ?? 5,
    earlierBook.minOrderSize ?? 5,
    laterBook.minOrderSize ?? 5,
  );
  return evaluateCalendarBooks(books, minShares, pair, { ...options, now: Date.now() });
}

async function mapLimit(items, limit, worker) {
  const out = new Array(items.length);
  let cursor = 0;
  async function run() {
    while (true) {
      const index = cursor;
      cursor += 1;
      if (index >= items.length) return;
      try {
        out[index] = await worker(items[index], index);
      } catch (error) {
        out[index] = {
          status: "request_error",
          error: error instanceof Error ? error.message : String(error),
          item: items[index],
        };
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, run));
  return out;
}

export function summarizeCalendar(rows, options, startedAt, finishedAt = Date.now()) {
  const ok = rows.filter((row) => row.status === "ok");
  const available = ok.filter((row) => row.available);
  const actionable = available.filter((row) => row.actionable);
  const topSums = ok.map((row) => row.topAskSum).filter(Number.isFinite);
  return {
    generatedAt: new Date(finishedAt).toISOString(),
    durationMs: finishedAt - startedAt,
    options,
    markets: options.discoveredMarkets ?? null,
    events: options.discoveredEvents ?? null,
    pairs: rows.length,
    evaluated: ok.length,
    availableArbitrages: available.length,
    actionableArbitrages: actionable.length,
    staleEvaluations: ok.filter((row) => row.health?.staleBook?.length > 0).length,
    bestTopAskSum: topSums.length > 0 ? Math.min(...topSums) : null,
    actionableProfit: actionable.reduce((sum, row) => sum + row.profit, 0),
    statuses: rows.reduce((map, row) => {
      map[row.status] = (map[row.status] ?? 0) + 1;
      return map;
    }, {}),
    top: actionable.sort((a, b) => b.profit - a.profit).slice(0, 20).map((row) => ({
      eventSlug: row.eventSlug,
      earlierQuestion: row.earlierQuestion,
      laterQuestion: row.laterQuestion,
      topAskSum: row.topAskSum,
      edgeCents: row.edgeCents,
      profit: row.profit,
      maxShares: row.maxShares,
      holdingDays: row.holdingDays,
      annualizedReturn: row.annualizedReturn,
    })),
  };
}

function csvEscape(value) {
  if (value == null) return "";
  const text = String(value);
  return /[",\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

export function toCsv(rows) {
  const columns = [
    "status", "available", "actionable", "reason", "eventSlug", "earlierQuestion", "laterQuestion",
    "earlierEndDate", "laterEndDate", "holdingDays", "feeRates", "minShares", "oldestBookAgeMs",
    "topAskSum", "maxShares", "cash", "fees", "gas", "profit", "edgeCents",
    "returnOnCapital", "annualizedReturn", "ruleReviewRequired",
  ];
  const lines = [columns.join(",")];
  for (const row of rows) {
    const flat = {
      ...row,
      feeRates: row.feeRates?.join("|"),
      ruleReviewRequired: row.ruleReviewRequired,
    };
    lines.push(columns.map((column) => csvEscape(flat[column])).join(","));
  }
  return `${lines.join("\n")}\n`;
}

async function writeText(file, contents) {
  await fs.mkdir(path.dirname(path.resolve(file)), { recursive: true });
  await fs.writeFile(file, contents, "utf8");
}

async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  if (options.help) {
    console.log("Usage: node scripts/scan-event-calendar-spread-arbitrage.mjs [options]");
    console.log("  --tags politics,geopolitics");
    console.log("  --min-volume 0");
    console.log("  --min-edge-cents 0.5");
    console.log("  --max-book-age-ms 15000");
    console.log("  --gas-usd 0.02");
    console.log("  --max-pairs 5000");
    console.log("  --output PATH");
    console.log("  --csv PATH");
    return;
  }
  if ((process.env.HTTPS_PROXY || process.env.HTTP_PROXY) && process.env.NODE_USE_ENV_PROXY !== "1") {
    console.warn("warning: NODE_USE_ENV_PROXY is not 1; requests may bypass the proxy");
  }

  const startedAt = Date.now();
  clearRunCaches();
  const raw = [];
  for (const tag of options.tags) raw.push(...(await discoverTag(tag, options.maxMarketsPerTag)));
  const byCondition = new Map();
  for (const item of raw) {
    const market = normalizeMarket(item);
    if (market) byCondition.set(market.conditionId, market);
  }
  const markets = [...byCondition.values()];
  options.discoveredMarkets = markets.length;
  options.discoveredEvents = new Set(markets.map((market) => market.eventSlug).filter(Boolean)).size;
  const pairs = buildCalendarPairs(markets, options);
  console.log(`markets=${markets.length} events=${options.discoveredEvents} pairs=${pairs.length}`);
  clearBookCache();
  const rows = await mapLimit(pairs, options.concurrency, (pair) => evaluateCalendarPair(pair, options));
  const summary = summarizeCalendar(rows, options, startedAt);
  if (options.output) {
    await writeText(options.output, `${JSON.stringify({ summary, results: rows }, null, 2)}\n`);
    console.log(`wrote ${options.output}`);
  }
  if (options.csv) {
    await writeText(options.csv, toCsv(rows));
    console.log(`wrote ${options.csv}`);
  }

  console.log("\nTOP CALENDAR-SPREAD ACTIONABLE");
  const top = rows.filter((row) => row.actionable).sort((a, b) => b.profit - a.profit).slice(0, 20);
  if (top.length === 0) console.log("(none)");
  for (const row of top) {
    console.log([
      row.edgeCents.toFixed(4).padStart(7),
      row.profit.toFixed(4).padStart(9),
      Number(row.holdingDays).toFixed(1).padStart(7),
      row.earlierQuestion,
      "=>",
      row.laterQuestion,
    ].join("  "));
  }
  console.log(`\n${JSON.stringify(summary, null, 2)}`);
}

const invokedDirectly = process.argv[1]
  ? import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
  : false;

if (invokedDirectly) {
  main().catch((error) => {
    console.error(error?.stack || error);
    process.exitCode = 1;
  });
}
