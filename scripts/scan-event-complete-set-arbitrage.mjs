#!/usr/bin/env node

/**
 * Public Polymarket complete-set arbitrage scanner.
 *
 * Binary: buy both outcome asks, or mint a pair and sell both bids.
 * NegRisk: buy YES for every outcome when the executable ask sum is below 1.
 *
 * The scanner includes taker fees, depth, order-size rounding, stale-book
 * checks, gas buffers and a configurable safety edge. Independent legs are
 * not assumed to execute atomically.
 */

import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

const GAMMA_BASE = "https://gamma-api.polymarket.com";
const CLOB_BASE = "https://clob.polymarket.com";

export const TAG_IDS = {
  sports: "1",
  politics: "2",
  crypto: "21",
  finance: "120",
  geopolitics: "100265",
};

const FEE_TYPE_RATES = {
  crypto_fees_v2: 0.07,
  sports_fees_v2: 0.03,
  sports_fees_v3: 0.05,
  politics_fees: 0.04,
  finance_prices_fees: 0.04,
  finance_fees: 0.04,
  economics_fees: 0.05,
  culture_fees: 0.05,
  weather_fees: 0.05,
  tech_fees: 0.04,
  mentions_fees: 0.04,
  geopolitics_fees: 0,
  zero_fees: 0,
};

const CATEGORY_RATES = {
  crypto: 0.07,
  sports: 0.05,
  finance: 0.04,
  politics: 0.04,
  economics: 0.05,
  culture: 0.05,
  weather: 0.05,
  other: 0.05,
  general: 0.05,
  mentions: 0.04,
  tech: 0.04,
  geopolitics: 0,
};

const DEFAULTS = Object.freeze({
  tags: ["politics", "geopolitics"],
  maxMarketsPerTag: 1000,
  maxGroups: 1000,
  maxOutcomesPerGroup: 256,
  minVolume24h: 1000,
  prefilterAskSumMax: 1.10,
  prefilterBidSumMin: 0.90,
  prefilterNoBidSumMin: 1.00,
  minEdgeCents: 0.5,
  minProfitUsd: 0.01,
  maxBookAgeMs: 15_000,
  mergeGasUsd: 0.02,
  mintGasUsd: 0.02,
  sizeStep: 0.01,
  concurrency: 8,
  output: null,
  csv: null,
});

function finiteNumber(value) {
  if (value == null || value === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function positiveNumberArg(arg, raw, minimum, { allowZero = false } = {}) {
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
        options.maxMarketsPerTag = Math.floor(positiveNumberArg(arg, next(), 0));
        break;
      case "--max-groups":
        options.maxGroups = Math.floor(positiveNumberArg(arg, next(), 0));
        break;
      case "--max-outcomes-per-group":
        options.maxOutcomesPerGroup = Math.floor(positiveNumberArg(arg, next(), 1));
        break;
      case "--min-volume":
        options.minVolume24h = positiveNumberArg(arg, next(), 0, { allowZero: true });
        break;
      case "--prefilter-ask-sum":
        options.prefilterAskSumMax = positiveNumberArg(arg, next(), 0);
        break;
      case "--prefilter-bid-sum":
        options.prefilterBidSumMin = positiveNumberArg(arg, next(), 0, { allowZero: true });
        break;
      case "--prefilter-no-bid-sum":
        options.prefilterNoBidSumMin = positiveNumberArg(arg, next(), 0, { allowZero: true });
        break;
      case "--min-edge-cents":
        options.minEdgeCents = positiveNumberArg(arg, next(), 0, { allowZero: true });
        break;
      case "--min-profit-usd":
        options.minProfitUsd = positiveNumberArg(arg, next(), 0, { allowZero: true });
        break;
      case "--max-book-age-ms":
        options.maxBookAgeMs = Math.floor(positiveNumberArg(arg, next(), 0));
        break;
      case "--merge-gas-usd":
        options.mergeGasUsd = positiveNumberArg(arg, next(), 0, { allowZero: true });
        break;
      case "--mint-gas-usd":
        options.mintGasUsd = positiveNumberArg(arg, next(), 0, { allowZero: true });
        break;
      case "--size-step":
        options.sizeStep = positiveNumberArg(arg, next(), 0);
        break;
      case "--concurrency":
        options.concurrency = Math.floor(positiveNumberArg(arg, next(), 0));
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
  for (const tag of options.tags) tagIdFor(tag);
  if (options.sizeStep > 1) throw new Error("--size-step must be <= 1");
  return options;
}

export function tagIdFor(tag) {
  if (/^\d+$/.test(tag)) return tag;
  const id = TAG_IDS[tag];
  if (!id) throw new Error(`unsupported tag: ${tag}; use a configured name or numeric Gamma tag id`);
  return id;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function fetchJson(url, attempts = 4) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const response = await fetch(url, {
        headers: { "User-Agent": "PolyHermes-research/1.0", Accept: "application/json" },
        signal: AbortSignal.timeout(30_000),
      });
      if (!response.ok) {
        const body = (await response.text()).slice(0, 300);
        throw new Error(`HTTP ${response.status} ${url}: ${body}`);
      }
      return await response.json();
    } catch (error) {
      lastError = error;
      if (attempt < attempts) await sleep(300 * attempt * attempt);
    }
  }
  throw lastError;
}

export function parseJsonArray(value) {
  if (Array.isArray(value)) return value;
  if (typeof value !== "string" || !value.trim()) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function normalizedText(value) {
  return String(value ?? "").trim().toLowerCase();
}

export function fallbackFeeRate(feeType, category) {
  const normalizedFeeType = normalizedText(feeType);
  if (normalizedFeeType && FEE_TYPE_RATES[normalizedFeeType] != null) {
    return FEE_TYPE_RATES[normalizedFeeType];
  }
  const normalizedCategory = normalizedText(category);
  if (normalizedCategory && CATEGORY_RATES[normalizedCategory] != null) {
    return CATEGORY_RATES[normalizedCategory];
  }
  if (normalizedFeeType) {
    const base = normalizedFeeType.split("_fees")[0];
    if (CATEGORY_RATES[base] != null) return CATEGORY_RATES[base];
  }
  return null;
}

/**
 * Gamma feeSchedule.rate is authoritative. Explicit feesEnabled=false is
 * fee-free. If fees may be enabled but the rate is missing, return null so
 * the caller must query /fee-rate instead of silently assuming zero.
 */
export function feeRateOf(raw) {
  if (raw?.feesEnabled === false) return 0;
  const scheduleRate = finiteNumber(raw?.feeSchedule?.rate);
  if (scheduleRate != null) return Math.max(0, scheduleRate);
  const fallback = fallbackFeeRate(raw?.feeType, raw?.category);
  if (fallback != null) return fallback;
  return null;
}
export async function discoverTag(tag, maxMarkets) {
  const tagId = tagIdFor(tag);
  const rows = [];
  for (let offset = 0; offset < maxMarkets; offset += 100) {
    const limit = Math.min(100, maxMarkets - offset);
    const page = await fetchJson(
      `${GAMMA_BASE}/markets?active=true&closed=false&limit=${limit}&offset=${offset}` +
      `&tag_id=${encodeURIComponent(tagId)}&order=volume24hr&ascending=false`,
    );
    if (!Array.isArray(page) || page.length === 0) break;
    rows.push(...page.map((market) => ({ ...market, discoveryTag: tag })));
    if (page.length < limit) break;
  }
  return rows;
}

export function normalizeMarket(raw) {
  const tokens = parseJsonArray(raw?.clobTokenIds).map(String);
  const outcomes = parseJsonArray(raw?.outcomes).map(String);
  const conditionId = raw?.conditionId || null;
  if (!conditionId || tokens.length !== 2 || outcomes.length !== 2) return null;
  if (new Set(tokens).size !== 2) return null;
  const yesIndex = outcomes.findIndex((outcome) => normalizedText(outcome) === "yes");
  return {
    id: raw.id == null ? null : String(raw.id),
    conditionId,
    question: raw.question || conditionId,
    description: raw.description || "",
    slug: raw.slug || "",
    eventId: raw.events?.[0]?.id == null ? null : String(raw.events[0].id),
    eventSlug: raw.events?.[0]?.slug || "",
    eventTitle: raw.events?.[0]?.title || "",
    tokens,
    outcomes,
    yesTokenId: yesIndex >= 0 ? tokens[yesIndex] : null,
    noTokenId: yesIndex >= 0 ? tokens[1 - yesIndex] : null,
    active: raw.active !== false,
    closed: raw.closed === true,
    enableOrderBook: raw.enableOrderBook !== false,
    acceptingOrders: raw.acceptingOrders !== false,
    negRisk: Boolean(raw.negRisk),
    negRiskMarketId: raw.negRiskMarketID || raw.negRiskMarketId || null,
    bestBid: finiteNumber(raw.bestBid) ?? finiteNumber(raw.best_bid),
    bestAsk: finiteNumber(raw.bestAsk) ?? finiteNumber(raw.best_ask),
    volume24hr: finiteNumber(raw.volume24hr) ?? finiteNumber(raw.volume24hrClob) ?? 0,
    liquidity: finiteNumber(raw.liquidityNum) ?? finiteNumber(raw.liquidity) ?? 0,
    endDate: raw.endDate || null,
    tickSize: finiteNumber(raw.orderPriceMinTickSize) ?? 0.001,
    minOrderSize: finiteNumber(raw.orderMinSize) ?? 5,
    feesEnabled: raw.feesEnabled,
    feeRate: feeRateOf(raw),
    feeType: raw.feeType || null,
    category: raw.category || null,
    discoveryTag: raw.discoveryTag,
  };
}

function validPrice(value) {
  const parsed = finiteNumber(value);
  return parsed != null && parsed > 0 && parsed < 1 ? parsed : null;
}

function validSize(value) {
  const parsed = finiteNumber(value);
  return parsed != null && parsed > 0 ? parsed : null;
}

export function parseBook(raw, tokenId) {
  const parseLevels = (levels) => (Array.isArray(levels) ? levels : [])
    .map((level) => ({ price: validPrice(level.price), size: validSize(level.size) }))
    .filter((level) => level.price != null && level.size != null);
  let timestamp = finiteNumber(raw?.timestamp);
  if (timestamp != null && timestamp > 0 && timestamp < 1e12) timestamp *= 1000;
  return {
    tokenId,
    bids: parseLevels(raw?.bids).sort((a, b) => b.price - a.price),
    asks: parseLevels(raw?.asks).sort((a, b) => a.price - b.price),
    updatedAt: timestamp,
    minOrderSize: finiteNumber(raw?.min_order_size) ?? 5,
    tickSize: finiteNumber(raw?.tick_size),
  };
}

export function totalAskDepth(book) {
  return book.asks.reduce((sum, level) => sum + level.size, 0);
}

export function totalBidDepth(book) {
  return book.bids.reduce((sum, level) => sum + level.size, 0);
}

function feeForFill(shares, price, feeRate) {
  return shares * feeRate * price * (1 - price);
}

export function costForShares(books, shares) {
  let cash = 0;
  let fees = 0;
  for (const entry of books) {
    if (!Number.isFinite(entry.feeRate) || entry.feeRate < 0) return null;
    let remaining = shares;
    for (const level of entry.book.asks) {
      if (remaining <= 1e-12) break;
      const take = Math.min(remaining, level.size);
      cash += take * level.price;
      fees += feeForFill(take, level.price, entry.feeRate);
      remaining -= take;
    }
    if (remaining > 1e-9) return null;
  }
  return { cash, fees, total: cash + fees };
}

export function revenueForShares(books, shares) {
  let revenue = 0;
  let fees = 0;
  for (const entry of books) {
    if (!Number.isFinite(entry.feeRate) || entry.feeRate < 0) return null;
    let remaining = shares;
    for (const level of entry.book.bids) {
      if (remaining <= 1e-12) break;
      const take = Math.min(remaining, level.size);
      revenue += take * level.price;
      fees += feeForFill(take, level.price, entry.feeRate);
      remaining -= take;
    }
    if (remaining > 1e-9) return null;
  }
  return { revenue, fees, net: revenue - shares - fees };
}

export function floorToStep(value, step) {
  if (!Number.isFinite(value) || !Number.isFinite(step) || step <= 0) return 0;
  return Math.floor((value + 1e-12) / step) * step;
}

export function maxPositiveShares(books, minShares, step, side = "ask", payoutPerShare = 1) {
  if (!Array.isArray(books) || books.length === 0) return null;
  const levelsByBook = books.map((entry) => side === "ask" ? entry.book?.asks : entry.book?.bids);
  if (levelsByBook.some((levels) => !Array.isArray(levels) || levels.length === 0)) return null;
  const indices = books.map(() => 0);
  const remaining = levelsByBook.map((levels) => levels[0].size);
  let shares = 0;

  while (true) {
    if (indices.some((index, bookIndex) => index >= levelsByBook[bookIndex].length)) break;
    let marginal = 0;
    for (let index = 0; index < books.length; index += 1) {
      const level = levelsByBook[index][indices[index]];
      const feeRate = books[index].feeRate;
      if (!Number.isFinite(feeRate) || feeRate < 0) return null;
      const fee = feeRate * level.price * (1 - level.price);
      marginal += side === "ask" ? level.price + fee : level.price - fee;
    }
    const positive = side === "ask" ? marginal < payoutPerShare - 1e-12 : marginal > 1 + 1e-12;
    if (!positive) break;
    let take = Number.POSITIVE_INFINITY;
    for (let index = 0; index < remaining.length; index += 1) take = Math.min(take, remaining[index]);
    if (!(take > 1e-12) || !Number.isFinite(take)) break;
    shares += take;
    for (let index = 0; index < remaining.length; index += 1) {
      remaining[index] -= take;
      if (remaining[index] <= 1e-12) {
        indices[index] += 1;
        remaining[index] = levelsByBook[index][indices[index]]?.size ?? 0;
      }
    }
  }

  const stepped = floorToStep(shares, step);
  return stepped + 1e-9 >= Math.max(minShares, step) ? stepped : null;
}

export function bookHealth(books, now, maxAgeMs) {
  const missingBook = [];
  const crossedBook = [];
  const staleBook = [];
  const futureTimestamp = [];
  const ages = [];
  for (const entry of books) {
    const book = entry.book;
    if (!book || !Array.isArray(book.asks) || !Array.isArray(book.bids)) {
      missingBook.push(book?.tokenId ?? entry.tokenId ?? "unknown");
      continue;
    }
    const ageMs = book.updatedAt == null ? null : now - book.updatedAt;
    ages.push(ageMs);
    if (ageMs == null || ageMs > maxAgeMs) staleBook.push(book.tokenId);
    if (ageMs != null && ageMs < -120_000) futureTimestamp.push(book.tokenId);
    if (book.asks.length > 0 && book.bids.length > 0 && book.bids[0].price >= book.asks[0].price) {
      crossedBook.push(book.tokenId);
    }
  }
  const oldestBookAgeMs = ages.length === 0
    ? null
    : Math.max(...ages.map((age) => age == null ? Number.POSITIVE_INFINITY : age));
  return {
    ok: missingBook.length === 0 && crossedBook.length === 0 && futureTimestamp.length === 0,
    oldestBookAgeMs,
    missingBook,
    crossedBook,
    staleBook,
    futureTimestamp,
  };
}

function executionReason({ available, health, edgeCents, profit, options }) {
  if (!available) return "not_profitable_after_costs";
  if (!health.ok) return "invalid_or_crossed_book";
  if (health.staleBook.length > 0) return "stale_book";
  if (edgeCents < options.minEdgeCents) return "below_edge_buffer";
  if (profit < options.minProfitUsd) return "below_min_profit";
  return "actionable";
}
export function evaluateAskArb(books, minShares, rawOptions = {}) {
  const options = { ...DEFAULTS, ...rawOptions };
  const now = rawOptions.now ?? Date.now();
  const health = bookHealth(books, now, options.maxBookAgeMs);
  const topAskSum = books.some((entry) => entry.book.asks.length === 0)
    ? null
    : books.reduce((sum, entry) => sum + entry.book.asks[0].price, 0);
  if (topAskSum == null) {
    return { available: false, actionable: false, reason: "missing_ask", topAskSum: null, health };
  }

  const payoutPerShare = rawOptions.payoutPerShare ?? 1;
  const maxShares = maxPositiveShares(books, minShares, options.sizeStep, "ask", payoutPerShare);
  if (maxShares == null) {
    return {
      available: false,
      actionable: false,
      reason: topAskSum < payoutPerShare ? "no_positive_after_fees_or_depth" : "ask_sum_not_below_one",
      topAskSum,
      health,
    };
  }

  const cost = costForShares(books, maxShares);
  const gas = options.mergeGasUsd ?? 0;
  const grossProfit = maxShares * payoutPerShare - cost.total;
  const profit = grossProfit - gas;
  const available = profit > 1e-9;
  const edgeCents = maxShares > 0 ? (profit / maxShares) * 100 : 0;
  const reason = executionReason({ available, health, edgeCents, profit, options });
  return {
    available,
    actionable: reason === "actionable",
    reason,
    topAskSum,
    payoutPerShare,
    maxShares,
    cash: cost.cash,
    fees: cost.fees,
    gas,
    grossProfit,
    profit,
    edgePerShare: maxShares > 0 ? profit / maxShares : 0,
    edgeCents,
    returnOnCapital: cost.total > 0 ? profit / cost.total : 0,
    requires: rawOptions.requires ?? (books.length === 2 ? "binary_complete_set" : "all_neg_risk_yes_outcomes"),
    health,
  };
}

export function evaluateBidArb(books, minShares, rawOptions = {}) {
  const options = { ...DEFAULTS, ...rawOptions };
  const now = rawOptions.now ?? Date.now();
  const health = bookHealth(books, now, options.maxBookAgeMs);
  const topBidSum = books.some((entry) => entry.book.bids.length === 0)
    ? null
    : books.reduce((sum, entry) => sum + entry.book.bids[0].price, 0);
  if (topBidSum == null) {
    return { available: false, actionable: false, reason: "missing_bid", topBidSum: null, health };
  }

  const maxShares = maxPositiveShares(books, minShares, options.sizeStep, "bid");
  if (maxShares == null) {
    return {
      available: false,
      actionable: false,
      reason: topBidSum > 1 ? "no_positive_after_fees_or_depth" : "bid_sum_not_above_one",
      topBidSum,
      health,
    };
  }

  const revenue = revenueForShares(books, maxShares);
  const gas = options.mintGasUsd ?? 0;
  const grossProfit = revenue.net;
  const profit = grossProfit - gas;
  const available = profit > 1e-9;
  const edgeCents = maxShares > 0 ? (profit / maxShares) * 100 : 0;
  const reason = executionReason({ available, health, edgeCents, profit, options });
  return {
    available,
    actionable: reason === "actionable",
    reason,
    topBidSum,
    maxShares,
    revenue: revenue.revenue,
    fees: revenue.fees,
    gas,
    grossProfit,
    profit,
    edgePerShare: maxShares > 0 ? profit / maxShares : 0,
    edgeCents,
    returnOnCapital: maxShares > 0 ? profit / maxShares : 0,
    requiresMint: true,
    nonAtomicLegs: books.length,
    health,
  };
}

export function buildGroups(markets, options = DEFAULTS) {
  const groups = new Map();
  for (const market of markets) {
    if (!market || market.closed || !market.active || !market.enableOrderBook || !market.acceptingOrders) continue;
    if (!market.conditionId) continue;
    const key = market.negRisk && market.negRiskMarketId
      ? `neg:${market.negRiskMarketId}`
      : `bin:${market.conditionId}`;
    let group = groups.get(key);
    if (!group) {
      group = {
        key,
        type: key.startsWith("neg:") ? "negRisk" : "binary",
        negRiskMarketId: market.negRiskMarketId,
        markets: [],
        conditionIds: new Set(),
        volume24hr: 0,
        liquidity: 0,
        eventSlug: market.eventSlug,
        eventTitle: market.eventTitle || market.question,
        discoveryTag: market.discoveryTag,
      };
      groups.set(key, group);
    }
    if (!group.conditionIds.has(market.conditionId)) {
      group.markets.push(market);
      group.conditionIds.add(market.conditionId);
      group.volume24hr += market.volume24hr;
      group.liquidity += market.liquidity;
    }
  }

  return [...groups.values()]
    .map((group) => ({ ...group, conditionIds: [...group.conditionIds] }))
    .filter((group) => group.type === "binary" || Boolean(group.eventSlug))
    .filter((group) => group.volume24hr >= options.minVolume24h)
    .sort((a, b) => b.volume24hr - a.volume24hr)
    .slice(0, options.maxGroups);
}

export function validateNegRiskEvent(event, group, options = {}) {
  const maxOutcomes = options.maxOutcomesPerGroup ?? DEFAULTS.maxOutcomesPerGroup;
  if (!event || event.active === false || event.closed === true) {
    return { ok: false, reason: "incomplete_or_closed_group", outcomeCount: 0 };
  }
  if (event.negRisk === false || event.enableNegRisk === false) {
    return { ok: false, reason: "event_not_neg_risk", outcomeCount: 0 };
  }
  const rawMarkets = Array.isArray(event.markets) ? event.markets : [];
  if (rawMarkets.length < 2) {
    return { ok: false, reason: "incomplete_or_closed_group", outcomeCount: rawMarkets.length };
  }
  if (rawMarkets.length > maxOutcomes) {
    return { ok: false, reason: "incomplete_or_oversized_group", outcomeCount: rawMarkets.length };
  }
  for (const market of rawMarkets) {
    if (market.active !== true || market.closed !== false || market.enableOrderBook !== true ||
        market.acceptingOrders === false) {
      return { ok: false, reason: "incomplete_or_closed_group", outcomeCount: rawMarkets.length };
    }
  }

  const eventMarkets = rawMarkets.map((market) => normalizeMarket({
    ...market,
    discoveryTag: group.discoveryTag,
    events: [{ id: event.id, slug: event.slug, title: event.title }],
  }));
  if (eventMarkets.some((market) => market == null)) {
    return { ok: false, reason: "invalid_event_market_shape", outcomeCount: rawMarkets.length };
  }
  if (eventMarkets.some((market) => !market.negRisk || !market.negRiskMarketId)) {
    return { ok: false, reason: "event_market_missing_neg_risk_id", outcomeCount: rawMarkets.length };
  }
  if (eventMarkets.some((market) => market.negRiskMarketId !== group.negRiskMarketId)) {
    return { ok: false, reason: "neg_risk_id_mismatch", outcomeCount: rawMarkets.length };
  }
  if (eventMarkets.some((market) => !market.yesTokenId)) {
    return { ok: false, reason: "missing_yes_outcome", outcomeCount: rawMarkets.length };
  }
  const conditionIds = new Set(eventMarkets.map((market) => market.conditionId));
  if (conditionIds.size !== eventMarkets.length) {
    return { ok: false, reason: "duplicate_condition_id", outcomeCount: rawMarkets.length };
  }
  const tokenIds = eventMarkets.flatMap((market) => market.tokens);
  if (new Set(tokenIds).size !== tokenIds.length) {
    return { ok: false, reason: "duplicate_token_id", outcomeCount: rawMarkets.length };
  }
  return { ok: true, reason: "ok", markets: eventMarkets, outcomeCount: eventMarkets.length };
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
const bookCache = new Map();
const eventCache = new Map();
const feeRateCache = new Map();

export function clearBookCache() {
  bookCache.clear();
}

export function clearRunCaches() {
  bookCache.clear();
  eventCache.clear();
  feeRateCache.clear();
}

export function getEvent(slug) {
  if (!slug) return Promise.resolve(null);
  if (!eventCache.has(slug)) {
    const request = fetchJson(`${GAMMA_BASE}/events/slug/${encodeURIComponent(slug)}`)
      .catch((error) => {
        eventCache.delete(slug);
        throw error;
      });
    eventCache.set(slug, request);
  }
  return eventCache.get(slug);
}

export function getBook(tokenId) {
  if (!bookCache.has(tokenId)) {
    const request = fetchJson(`${CLOB_BASE}/book?token_id=${encodeURIComponent(tokenId)}`)
      .then((raw) => parseBook(raw, tokenId))
      .catch((error) => {
        bookCache.delete(tokenId);
        throw error;
      });
    bookCache.set(tokenId, request);
  }
  return bookCache.get(tokenId);
}

async function getFeeRate(tokenId) {
  if (feeRateCache.has(tokenId)) return feeRateCache.get(tokenId);
  try {
    const raw = await fetchJson(`${CLOB_BASE}/fee-rate?token_id=${encodeURIComponent(tokenId)}`, 3);
    const baseFee = finiteNumber(raw?.base_fee);
    if (baseFee == null || baseFee < 0) throw new Error("invalid base_fee response");
    const result = { rate: baseFee / 10_000, source: "clob_fee_rate" };
    feeRateCache.set(tokenId, result);
    return result;
  } catch (error) {
    return { rate: null, source: "unavailable", error: error instanceof Error ? error.message : String(error) };
  }
}

export async function resolveFeeRate(market, tokenId) {
  if (market.feeRate != null) {
    return { rate: Math.max(0, market.feeRate), source: "gamma" };
  }
  return getFeeRate(tokenId);
}

function minSharesFor(markets, books, options) {
  return Math.max(
    options.sizeStep,
    5,
    ...markets.map((market) => market.minOrderSize ?? 5),
    ...books.map((entry) => entry.book.minOrderSize ?? 5),
  );
}

function failureRow(group, status, extra = {}) {
  return {
    status,
    groupKey: group.key,
    groupType: group.type,
    conditionId: group.type === "binary" ? group.markets[0]?.conditionId ?? null : null,
    conditionIds: group.type === "binary" ? group.conditionIds : [],
    eventTitle: group.eventTitle,
    eventSlug: group.eventSlug,
    question: group.type === "binary" ? group.markets[0]?.question ?? group.eventTitle : group.eventTitle,
    outcomeCount: group.markets.length,
    volume24hr: group.volume24hr,
    ...extra,
  };
}

export async function evaluateGroup(group, options) {
  const now = Date.now();
  let books;
  let noBooks = null;
  let markets;
  if (group.type === "binary") {
    const market = group.markets[0];
    if (!market || market.closed || !market.active || !market.enableOrderBook || !market.acceptingOrders) {
      return failureRow(group, "incomplete_or_closed_market");
    }
    markets = [market];
    const proxyAskSum = market.bestAsk != null && market.bestBid != null
      ? market.bestAsk + (1 - market.bestBid)
      : null;
    const proxyBidSum = market.bestAsk != null && market.bestBid != null
      ? market.bestBid + (1 - market.bestAsk)
      : null;
    if (Number.isFinite(options.prefilterAskSumMax) && proxyAskSum != null &&
        proxyAskSum > options.prefilterAskSumMax &&
        (!Number.isFinite(options.prefilterBidSumMin) || proxyBidSum == null || proxyBidSum < options.prefilterBidSumMin)) {
      return failureRow(group, "prefiltered", { proxyAskSum, proxyBidSum });
    }
    const [first, second] = await Promise.all([getBook(market.tokens[0]), getBook(market.tokens[1])]);
    const fees = await Promise.all([
      resolveFeeRate(market, market.tokens[0]),
      resolveFeeRate(market, market.tokens[1]),
    ]);
    if (fees.some((fee) => fee.rate == null)) {
      return failureRow(group, "fee_rate_unavailable", { feeErrors: fees });
    }
    books = [
      { book: first, feeRate: fees[0].rate, feeSource: fees[0].source },
      { book: second, feeRate: fees[1].rate, feeSource: fees[1].source },
    ];
  } else {
    let event;
    try {
      event = await getEvent(group.eventSlug);
    } catch (error) {
      return failureRow(group, "request_error", { error: error instanceof Error ? error.message : String(error) });
    }
    const validation = validateNegRiskEvent(event, group, options);
    if (!validation.ok) {
      return failureRow(group, validation.reason, { outcomeCount: validation.outcomeCount });
    }
    markets = validation.markets;
    group.markets = markets;
    group.conditionIds = markets.map((market) => market.conditionId);
    group.volume24hr = markets.reduce((sum, market) => sum + market.volume24hr, 0);
    group.liquidity = markets.reduce((sum, market) => sum + market.liquidity, 0);

    const eventAskPrices = markets.map((market) => market.bestAsk);
    const eventBidPrices = markets.map((market) => market.bestBid);
    const proxyAskSum = eventAskPrices.every(Number.isFinite)
      ? eventAskPrices.reduce((sum, price) => sum + price, 0)
      : null;
    const proxyBidSum = eventBidPrices.every(Number.isFinite)
      ? eventBidPrices.reduce((sum, price) => sum + price, 0)
      : null;
    const needsNoSide = Number.isFinite(options.prefilterNoBidSumMin) &&
      proxyBidSum != null && proxyBidSum > options.prefilterNoBidSumMin;
    if (Number.isFinite(options.prefilterAskSumMax) && proxyAskSum != null &&
        proxyAskSum > options.prefilterAskSumMax && !needsNoSide) {
      return failureRow(group, "prefiltered", { proxyAskSum, proxyBidSum, outcomeCount: markets.length });
    }

    const fetched = await mapLimit(markets, Math.min(4, options.concurrency), async (market) => {
      const yesPromise = Promise.all([
        getBook(market.yesTokenId),
        resolveFeeRate(market, market.yesTokenId),
      ]);
      const noPromise = needsNoSide
        ? Promise.all([getBook(market.noTokenId), resolveFeeRate(market, market.noTokenId)])
        : Promise.resolve(null);
      const [[book, fee], noResult] = await Promise.all([yesPromise, noPromise]);
      return {
        market,
        book,
        fee,
        noBook: noResult?.[0] ?? null,
        noFee: noResult?.[1] ?? null,
      };
    });
    if (fetched.some((entry) => !entry || entry.status === "request_error")) {
      return failureRow(group, "request_error", { outcomeCount: markets.length });
    }
    const feeErrors = fetched.flatMap((entry) => [entry.fee, entry.noFee].filter((fee) => fee && fee.rate == null));
    if (feeErrors.length > 0) {
      return failureRow(group, "fee_rate_unavailable", {
        outcomeCount: markets.length,
        feeErrors,
      });
    }
    books = fetched.map((entry) => ({
      book: entry.book,
      feeRate: entry.fee.rate,
      feeSource: entry.fee.source,
    }));
    noBooks = needsNoSide ? fetched.map((entry) => ({
      book: entry.noBook,
      feeRate: entry.noFee.rate,
      feeSource: entry.noFee.source,
    })) : null;
  }

  const minShares = minSharesFor(markets, books, options);
  const commonOptions = { ...options, now };
  const ask = evaluateAskArb(books, minShares, commonOptions);
  const bid = group.type === "binary" ? evaluateBidArb(books, minShares, commonOptions) : null;
  const noBasket = noBooks
    ? evaluateAskArb(noBooks, minShares, {
        ...commonOptions,
        payoutPerShare: markets.length - 1,
        requires: "all_neg_risk_no_outcomes",
      })
    : null;
  const allBooks = noBooks ? [...books, ...noBooks] : books;
  const healths = [ask.health, bid?.health, noBasket?.health].filter(Boolean);
  const oldestBookAgeMs = healths.reduce((max, health) => {
    if (health.oldestBookAgeMs == null) return Number.POSITIVE_INFINITY;
    return Math.max(max, health.oldestBookAgeMs);
  }, 0);
  return {
    status: "ok",
    groupKey: group.key,
    groupType: group.type,
    conditionId: group.type === "binary" ? markets[0].conditionId : null,
    conditionIds: markets.map((market) => market.conditionId),
    eventTitle: group.eventTitle,
    eventSlug: group.eventSlug,
    question: group.type === "binary" ? markets[0].question : group.eventTitle,
    outcomeCount: markets.length,
    volume24hr: group.volume24hr,
    liquidity: group.liquidity,
    feeRates: [...new Set(allBooks.map((entry) => entry.feeRate))],
    feeSources: [...new Set(allBooks.map((entry) => entry.feeSource))],
    minShares,
    oldestBookAgeMs,
    ask,
    bid,
    noBasket,
    actionable: Boolean(ask.actionable || bid?.actionable || noBasket?.actionable),
    maxProfit: Math.max(ask.available ? ask.profit : 0, bid?.available ? bid.profit : 0, noBasket?.available ? noBasket.profit : 0),
    maxEdgeCents: Math.max(ask.available ? ask.edgeCents : 0, bid?.available ? bid.edgeCents : 0, noBasket?.available ? noBasket.edgeCents : 0),
  };
}
export function summarize(rows, options, startedAt, finishedAt = Date.now()) {
  const ok = rows.filter((row) => row.status === "ok");
  const askAvailable = ok.filter((row) => row.ask?.available);
  const askActionable = askAvailable.filter((row) => row.ask.actionable);
  const bidAvailable = ok.filter((row) => row.bid?.available);
  const bidActionable = bidAvailable.filter((row) => row.bid.actionable);
  const noBasketAvailable = ok.filter((row) => row.noBasket?.available);
  const noBasketActionable = noBasketAvailable.filter((row) => row.noBasket.actionable);
  const sortedAskActionable = [...askActionable].sort((a, b) => b.ask.profit - a.ask.profit);
  const sortedBidActionable = [...bidActionable].sort((a, b) => b.bid.profit - a.bid.profit);
  const sortedNoBasketActionable = [...noBasketActionable].sort((a, b) => b.noBasket.profit - a.noBasket.profit);
  const askTopSums = ok.map((row) => row.ask?.topAskSum).filter((value) => Number.isFinite(value));
  const bidTopSums = ok.map((row) => row.bid?.topBidSum).filter((value) => Number.isFinite(value));
  return {
    generatedAt: new Date(finishedAt).toISOString(),
    durationMs: finishedAt - startedAt,
    options,
    markets: options.discoveredMarkets ?? null,
    groups: rows.length,
    evaluated: ok.length,
    rawAskArbitrages: askAvailable.length,
    actionableAskArbitrages: askActionable.length,
    rawBidArbitragesRequiringMint: bidAvailable.length,
    actionableBidArbitragesRequiringMint: bidActionable.length,
    rawNegRiskNoBasketArbitrages: noBasketAvailable.length,
    actionableNegRiskNoBasketArbitrages: noBasketActionable.length,
    actionableAskProfit: askActionable.reduce((sum, row) => sum + row.ask.profit, 0),
    actionableBidProfit: bidActionable.reduce((sum, row) => sum + row.bid.profit, 0),
    actionableNoBasketProfit: noBasketActionable.reduce((sum, row) => sum + row.noBasket.profit, 0),
    bestAskSum: askTopSums.length > 0 ? Math.min(...askTopSums) : null,
    bestBidSum: bidTopSums.length > 0 ? Math.max(...bidTopSums) : null,
    staleEvaluations: ok.filter((row) =>
      row.ask?.health?.staleBook.length > 0 || row.bid?.health?.staleBook.length > 0 ||
      row.noBasket?.health?.staleBook.length > 0).length,
    statuses: rows.reduce((map, row) => {
      map[row.status] = (map[row.status] ?? 0) + 1;
      return map;
    }, {}),
    topAskActionable: sortedAskActionable.slice(0, 20).map((row) => ({
      eventTitle: row.eventTitle,
      eventSlug: row.eventSlug,
      groupType: row.groupType,
      profit: row.ask.profit,
      edgeCents: row.ask.edgeCents,
      maxShares: row.ask.maxShares,
    })),
    topBidActionable: sortedBidActionable.slice(0, 20).map((row) => ({
      eventTitle: row.eventTitle,
      eventSlug: row.eventSlug,
      profit: row.bid.profit,
      edgeCents: row.bid.edgeCents,
      maxShares: row.bid.maxShares,
    })),
    topNoBasketActionable: sortedNoBasketActionable.slice(0, 20).map((row) => ({
      eventTitle: row.eventTitle,
      eventSlug: row.eventSlug,
      payoutPerShare: row.noBasket.payoutPerShare,
      profit: row.noBasket.profit,
      edgeCents: row.noBasket.edgeCents,
      maxShares: row.noBasket.maxShares,
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
    "status", "actionable", "groupType", "conditionId", "question", "eventSlug", "outcomeCount",
    "volume24hr", "feeRates", "feeSources", "minShares", "oldestBookAgeMs",
    "askAvailable", "askActionable", "askReason", "askTopSum", "askMaxShares", "askCash",
    "askFees", "askGas", "askProfit", "askEdgeCents", "askStaleTokens",
    "bidAvailable", "bidActionable", "bidReason", "bidTopSum", "bidMaxShares",
    "bidRevenue", "bidFees", "bidGas", "bidProfit", "bidEdgeCents",
    "noBasketAvailable", "noBasketActionable", "noBasketReason", "noBasketPayout", "noBasketTopSum",
    "noBasketMaxShares", "noBasketCash", "noBasketFees", "noBasketGas", "noBasketProfit", "noBasketEdgeCents",
  ];
  const lines = [columns.join(",")];
  for (const row of rows) {
    const stale = [...new Set([
      ...(row.ask?.health?.staleBook ?? []),
      ...(row.bid?.health?.staleBook ?? []),
      ...(row.noBasket?.health?.staleBook ?? []),
    ])];
    const flat = {
      status: row.status,
      actionable: row.actionable,
      groupType: row.groupType,
      conditionId: row.conditionId,
      question: row.question,
      eventSlug: row.eventSlug,
      outcomeCount: row.outcomeCount,
      volume24hr: row.volume24hr,
      feeRates: row.feeRates?.join("|"),
      feeSources: row.feeSources?.join("|"),
      minShares: row.minShares,
      oldestBookAgeMs: row.oldestBookAgeMs,
      askAvailable: row.ask?.available,
      askActionable: row.ask?.actionable,
      askReason: row.ask?.reason,
      askTopSum: row.ask?.topAskSum,
      askMaxShares: row.ask?.maxShares,
      askCash: row.ask?.cash,
      askFees: row.ask?.fees,
      askGas: row.ask?.gas,
      askProfit: row.ask?.profit,
      askEdgeCents: row.ask?.edgeCents,
      askStaleTokens: stale.join("|"),
      bidAvailable: row.bid?.available,
      bidActionable: row.bid?.actionable,
      bidReason: row.bid?.reason,
      bidTopSum: row.bid?.topBidSum,
      bidMaxShares: row.bid?.maxShares,
      bidRevenue: row.bid?.revenue,
      bidFees: row.bid?.fees,
      bidGas: row.bid?.gas,
      bidProfit: row.bid?.profit,
      bidEdgeCents: row.bid?.edgeCents,
      noBasketAvailable: row.noBasket?.available,
      noBasketActionable: row.noBasket?.actionable,
      noBasketReason: row.noBasket?.reason,
      noBasketPayout: row.noBasket?.payoutPerShare,
      noBasketTopSum: row.noBasket?.topAskSum,
      noBasketMaxShares: row.noBasket?.maxShares,
      noBasketCash: row.noBasket?.cash,
      noBasketFees: row.noBasket?.fees,
      noBasketGas: row.noBasket?.gas,
      noBasketProfit: row.noBasket?.profit,
      noBasketEdgeCents: row.noBasket?.edgeCents,
    };
    lines.push(columns.map((column) => csvEscape(flat[column])).join(","));
  }
  return `${lines.join("\n")}\n`;
}

async function writeText(file, contents) {
  await fs.mkdir(path.dirname(path.resolve(file)), { recursive: true });
  await fs.writeFile(file, contents, "utf8");
}

function printTop(rows, side, limit = 20) {
  const actionable = rows
    .filter((row) => row[side]?.actionable)
    .sort((a, b) => b[side].profit - a[side].profit)
    .slice(0, limit);
  console.log(`\nTOP ${side.toUpperCase()}-SIDE ACTIONABLE ARBITRAGE`);
  if (actionable.length === 0) {
    console.log("(none)");
    return;
  }
  for (const row of actionable) {
    console.log([
      row.groupType.padEnd(10),
      row[side].edgeCents.toFixed(4).padStart(7),
      row[side].maxShares.toFixed(2).padStart(10),
      row[side].profit.toFixed(4).padStart(9),
      row.question,
    ].join("  "));
  }
}

async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  if (options.help) {
    console.log("Usage: node scripts/scan-event-complete-set-arbitrage.mjs [options]");
    console.log("  --tags politics,geopolitics      Tag names or numeric Gamma tag ids");
    console.log("  --max-markets-per-tag 1000");
    console.log("  --max-groups 1000");
    console.log("  --max-outcomes-per-group 256");
    console.log("  --min-volume 1000");
  console.log("  --prefilter-ask-sum 1.10");
  console.log("  --prefilter-bid-sum 0.90");
  console.log("  --prefilter-no-bid-sum 1.00");
    console.log("  --min-edge-cents 0.5");
    console.log("  --min-profit-usd 0.01");
    console.log("  --max-book-age-ms 15000");
    console.log("  --merge-gas-usd 0.02");
    console.log("  --mint-gas-usd 0.02");
    console.log("  --size-step 0.01");
    console.log("  --concurrency 8");
    console.log("  --output PATH");
    console.log("  --csv PATH");
    return;
  }
  if ((process.env.HTTPS_PROXY || process.env.HTTP_PROXY) && process.env.NODE_USE_ENV_PROXY !== "1") {
    console.warn("warning: NODE_USE_ENV_PROXY is not 1; requests may bypass the proxy");
  }

  const startedAt = Date.now();
  console.log(`discovering tags=${options.tags.join(",")} marketsPerTag=${options.maxMarketsPerTag}`);
  const raw = (await Promise.all(options.tags.map((tag) => discoverTag(tag, options.maxMarketsPerTag)))).flat();
  const byCondition = new Map();
  for (const row of raw) {
    const market = normalizeMarket(row);
    if (market) byCondition.set(market.conditionId, market);
  }
  options.discoveredMarkets = byCondition.size;
  const groups = buildGroups([...byCondition.values()], options);
  console.log(`markets=${byCondition.size} groups=${groups.length}`);
  const rows = await mapLimit(groups, options.concurrency, (group) => evaluateGroup(group, options));
  const summary = summarize(rows, options, startedAt);
  const snapshot = { summary, results: rows };

  if (options.output) {
    await writeText(options.output, `${JSON.stringify(snapshot, null, 2)}\n`);
    console.log(`wrote ${options.output}`);
  }
  if (options.csv) {
    await writeText(options.csv, toCsv(rows));
    console.log(`wrote ${options.csv}`);
  }

  printTop(rows, "ask");
  printTop(rows, "bid");
  printTop(rows, "noBasket");
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
