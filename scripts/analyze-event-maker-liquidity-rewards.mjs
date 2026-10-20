#!/usr/bin/env node

/**
 * Estimate liquidity-reward share for a paired-bid maker.
 *
 * This is a point-in-time competition snapshot, not a historical backtest.
 * It intentionally uses the aggregate visible book as one conservative
 * competitor: taking min(totalBookOne,totalBookTwo) cannot understate the
 * competitor denominator that results from scoring each maker separately.
 *
 * Run with Node's environment proxy support:
 *   $env:NODE_USE_ENV_PROXY='1'
 *   node scripts/analyze-event-maker-liquidity-rewards.mjs
 */

import fs from "node:fs/promises";
import path from "node:path";

const GAMMA_BASE = "https://gamma-api.polymarket.com";
const CLOB_BASE = "https://clob.polymarket.com";
const SCALING_FACTOR = 3.0;
const TAG_IDS = {
  politics: "2",
  geopolitics: "100265",
};

function parseArgs(argv) {
  const out = {
    tags: ["politics", "geopolitics"],
    pageSize: 100,
    maxMarketsPerTag: 250,
    bookMarkets: 100,
    shares: 0,
    improveTicks: 0,
    minPrice: 0,
    maxPrice: 1,
    minDailyPool: 0,
    minVolume24h: 0,
    minHoursToClose: 0,
    minCompetitorQMin: 1,
    maxBookAgeSeconds: 600,
    concurrency: 6,
    output: null,
    csv: null,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => {
      i += 1;
      if (i >= argv.length) throw new Error(`missing value for ${arg}`);
      return argv[i];
    };
    switch (arg) {
      case "--tags":
        out.tags = next().split(",").map((s) => s.trim()).filter(Boolean);
        break;
      case "--page-size":
        out.pageSize = Number(next());
        break;
      case "--max-markets-per-tag":
        out.maxMarketsPerTag = Number(next());
        break;
      case "--book-markets":
        out.bookMarkets = Number(next());
        break;
      case "--shares":
        out.shares = Number(next());
        break;
      case "--improve-ticks":
        out.improveTicks = Number(next());
        break;
      case "--min-price":
        out.minPrice = Number(next());
        break;
      case "--max-price":
        out.maxPrice = Number(next());
        break;
      case "--min-daily-pool":
        out.minDailyPool = Number(next());
        break;
      case "--min-volume24h":
        out.minVolume24h = Number(next());
        break;
      case "--min-hours-to-close":
        out.minHoursToClose = Number(next());
        break;
      case "--min-competitor-qmin":
        out.minCompetitorQMin = Number(next());
        break;
      case "--max-book-age-seconds":
        out.maxBookAgeSeconds = Number(next());
        break;
      case "--concurrency":
        out.concurrency = Number(next());
        break;
      case "--output":
        out.output = next();
        break;
      case "--csv":
        out.csv = next();
        break;
      case "--help":
        printHelp();
        process.exit(0);
      default:
        throw new Error(`unknown argument: ${arg}`);
    }
  }
  out.concurrency = Math.max(1, Math.floor(out.concurrency));
  out.shares = Math.max(0, out.shares);
  out.bookMarkets = Math.max(1, Math.floor(out.bookMarkets));
  out.maxMarketsPerTag = Math.max(1, Math.floor(out.maxMarketsPerTag));
  out.pageSize = Math.min(100, Math.max(1, Math.floor(out.pageSize)));
  if (!(out.minPrice >= 0 && out.minPrice < out.maxPrice && out.maxPrice <= 1)) {
    throw new Error("price range must satisfy 0 <= min-price < max-price <= 1");
  }
  for (const tag of out.tags) {
    if (!(tag in TAG_IDS)) throw new Error(`unsupported tag ${tag}; supported: ${Object.keys(TAG_IDS).join(",")}`);
  }
  return out;
}

function printHelp() {
  console.log(`Usage: node scripts/analyze-event-maker-liquidity-rewards.mjs [options]

Options:
  --tags politics,geopolitics        Gamma tag groups to scan
  --max-markets-per-tag 250          Discovery cap per tag
  --book-markets 100                 Number of highest-pool markets to analyze
  --shares 0                         Bid shares per token (0 uses the reward minimum)
  --improve-ticks 0                  Bid this many ticks above best bid
  --min-price 0 --max-price 1        Favorite-token midpoint filter
  --min-daily-pool 0                 Ignore markets below this daily reward pool
  --min-volume24h 0                  Ignore markets below this 24h volume
  --min-hours-to-close 0             Ignore markets with less time remaining
  --min-competitor-qmin 1            Reject books with no meaningful competition
  --max-book-age-seconds 600         Reject snapshots whose oldest book is stale
  --concurrency 6                    Parallel CLOB book requests
  --output PATH                      Write full JSON snapshot
  --csv PATH                         Write one row per analyzed market

Node 26 requires NODE_USE_ENV_PROXY=1 to honor HTTPS_PROXY.
`);
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
        const text = await response.text();
        throw new Error(`HTTP ${response.status} ${url}: ${text.slice(0, 240)}`);
      }
      return await response.json();
    } catch (error) {
      lastError = error;
      if (attempt < attempts) await sleep(300 * attempt * attempt);
    }
  }
  throw lastError;
}

function parseJsonArray(value) {
  if (Array.isArray(value)) return value;
  if (typeof value !== "string" || !value.trim()) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function number(value) {
  if (value == null || value === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function utcDateString(ms = Date.now()) {
  return new Date(ms).toISOString().slice(0, 10);
}

function activeDailyReward(market, today = utcDateString()) {
  const rewards = Array.isArray(market.clobRewards) ? market.clobRewards : [];
  let daily = 0;
  let activeAllocations = 0;
  for (const reward of rewards) {
    const rate = number(reward.rewardsDailyRate) ?? 0;
    const active = (!reward.startDate || String(reward.startDate) <= today) &&
      (!reward.endDate || String(reward.endDate) >= today);
    if (active && rate > 0) {
      daily += rate;
      activeAllocations += 1;
    }
  }
  return { daily, activeAllocations };
}

async function discoverTag(tag, options) {
  const tagId = TAG_IDS[tag];
  const discovered = [];
  for (let offset = 0; offset < options.maxMarketsPerTag; offset += options.pageSize) {
    const limit = Math.min(options.pageSize, options.maxMarketsPerTag - offset);
    const url = `${GAMMA_BASE}/markets?active=true&closed=false&limit=${limit}&offset=${offset}&tag_id=${tagId}`;
    const page = await fetchJson(url);
    if (!Array.isArray(page) || page.length === 0) break;
    for (const market of page) discovered.push({ ...market, discoveryTag: tag });
    if (page.length < limit) break;
  }
  return discovered;
}

function normalizeMarket(raw) {
  const tokens = parseJsonArray(raw.clobTokenIds).map(String);
  const outcomes = parseJsonArray(raw.outcomes).map(String);
  const reward = activeDailyReward(raw);
  const minSize = number(raw.rewardsMinSize);
  const maxSpread = number(raw.rewardsMaxSpread);
  const bestBid = number(raw.bestBid);
  const bestAsk = number(raw.bestAsk);
  if (tokens.length < 2 || minSize == null || minSize <= 0 || maxSpread == null || maxSpread <= 0) return null;
  if (reward.daily <= 0) return null;
  const midpoint = bestBid != null && bestAsk != null ? (bestBid + bestAsk) / 2 : null;
  const endTimestamp = raw.endDate ? Date.parse(raw.endDate) : null;
  return {
    conditionId: raw.conditionId || "",
    question: raw.question || "",
    slug: raw.slug || "",
    eventSlug: raw.events?.[0]?.slug || "",
    outcomes,
    tokens,
    negRisk: Boolean(raw.negRisk),
    rewardDailyRate: reward.daily,
    rewardAllocations: reward.activeAllocations,
    rewardsMinSize: minSize,
    rewardsMaxSpread: maxSpread,
    bestBid,
    bestAsk,
    gammaMidpoint: midpoint,
    volume24hr: number(raw.volume24hr) ?? number(raw.volume24hrClob) ?? 0,
    liquidity: number(raw.liquidityNum) ?? number(raw.liquidity) ?? 0,
    endDate: raw.endDate || null,
    endTimestamp: Number.isFinite(endTimestamp) ? endTimestamp : null,
    tickSize: number(raw.orderPriceMinTickSize) ?? 0.001,
    discoveryTag: raw.discoveryTag,
  };
}

function parseBook(raw, tokenId) {
  const bids = (Array.isArray(raw.bids) ? raw.bids : [])
    .map((level) => ({ price: number(level.price), size: number(level.size) }))
    .filter((level) => level.price != null && level.size != null && level.size > 0)
    .sort((a, b) => b.price - a.price);
  const asks = (Array.isArray(raw.asks) ? raw.asks : [])
    .map((level) => ({ price: number(level.price), size: number(level.size) }))
    .filter((level) => level.price != null && level.size != null && level.size > 0)
    .sort((a, b) => a.price - b.price);
  return {
    tokenId,
    bids,
    asks,
    bestBid: bids[0]?.price ?? null,
    bestAsk: asks[0]?.price ?? null,
    tickSize: number(raw.tick_size) ?? 0.001,
    updatedAt: number(raw.timestamp) ?? Date.now(),
  };
}

function scoreOrder(midpoint, price, side, maxSpreadCents) {
  if (midpoint == null || price == null) return 0;
  const spreadCents = side === "BID" ? (midpoint - price) * 100 : (price - midpoint) * 100;
  if (!Number.isFinite(spreadCents) || spreadCents < -1e-9 || spreadCents > maxSpreadCents + 1e-9) return 0;
  const ratio = Math.max(0, Math.min(1, (maxSpreadCents - Math.max(0, spreadCents)) / maxSpreadCents));
  return ratio * ratio;
}

function weightedLevels(book, side, midpoint, maxSpreadCents, minSize) {
  if (!book || midpoint == null) return { weighted: 0, levels: 0, shares: 0 };
  const levels = side === "BID" ? book.bids : book.asks;
  let weighted = 0;
  let count = 0;
  let shares = 0;
  for (const level of levels) {
    if (level.size + 1e-9 < minSize) continue;
    const score = scoreOrder(midpoint, level.price, side, maxSpreadCents);
    if (score <= 0) continue;
    weighted += score * level.size;
    shares += level.size;
    count += 1;
  }
  return { weighted, levels: count, shares };
}

function qMin(qOne, qTwo, midpoint) {
  if (midpoint > 0.10 && midpoint <= 0.90) {
    return Math.max(Math.min(qOne, qTwo), Math.max(qOne / SCALING_FACTOR, qTwo / SCALING_FACTOR));
  }
  return Math.min(qOne, qTwo);
}

function roundToTick(value, tick) {
  if (!(tick > 0)) return value;
  return Math.round(value / tick) * tick;
}

function postOnlyBid(book, improveTicks) {
  if (!book || book.bestBid == null) return null;
  const tick = book.tickSize > 0 ? book.tickSize : 0.001;
  let price = roundToTick(book.bestBid + improveTicks * tick, tick);
  if (book.bestAsk != null) price = Math.min(price, roundToTick(book.bestAsk - tick, tick));
  return price > 0 && price < 1 ? price : null;
}

function analyzeMarket(market, books, options) {
  const [yesBook, noBook] = books;
  const bookAgeMs = Math.max(0, Date.now() - Math.min(yesBook?.updatedAt ?? Date.now(), noBook?.updatedAt ?? Date.now()));
  if (!yesBook || !noBook || yesBook.bestBid == null || yesBook.bestAsk == null ||
      noBook.bestBid == null || noBook.bestAsk == null) {
    return { ...market, status: "missing_two_sided_books", bookAgeMs };
  }
  if (bookAgeMs > options.maxBookAgeSeconds * 1000) {
    return { ...market, status: "stale_book", bookAgeMs };
  }
  const yesMid = (yesBook.bestBid + yesBook.bestAsk) / 2;
  const noMid = (noBook.bestBid + noBook.bestAsk) / 2;
  const midpoint = (yesMid + (1 - noMid)) / 2;
  if (midpoint < options.minPrice || midpoint > options.maxPrice) {
    return { ...market, status: "outside_price_filter", midpoint };
  }

  const minSize = Math.max(market.rewardsMinSize, options.shares);
  const v = market.rewardsMaxSpread;
  const compYes = {
    bids: weightedLevels(yesBook, "BID", yesMid, v, market.rewardsMinSize),
    asks: weightedLevels(yesBook, "ASK", yesMid, v, market.rewardsMinSize),
  };
  const compNo = {
    bids: weightedLevels(noBook, "BID", noMid, v, market.rewardsMinSize),
    asks: weightedLevels(noBook, "ASK", noMid, v, market.rewardsMinSize),
  };
  const competitorQOne = compYes.bids.weighted + compNo.asks.weighted;
  const competitorQTwo = compYes.asks.weighted + compNo.bids.weighted;
  const competitorQMin = qMin(competitorQOne, competitorQTwo, midpoint);
  if (competitorQMin + 1e-9 < options.minCompetitorQMin) {
    return {
      ...market,
      status: "competitor_too_thin",
      midpoint,
      competitorQOne,
      competitorQTwo,
      competitorQMin,
      bookAgeMs,
    };
  }

  const yesBidPrice = postOnlyBid(yesBook, options.improveTicks);
  const noBidPrice = postOnlyBid(noBook, options.improveTicks);
  if (yesBidPrice == null || noBidPrice == null) {
    return { ...market, status: "cannot_place_post_only_bid", midpoint };
  }
  const yesScore = scoreOrder(yesMid, yesBidPrice, "BID", v);
  const noScore = scoreOrder(noMid, noBidPrice, "BID", v);
  if (yesScore <= 0 || noScore <= 0) {
    return { ...market, status: "our_bids_not_reward_eligible", midpoint, yesScore, noScore };
  }
  const ourQOne = yesScore * minSize;
  const ourQTwo = noScore * minSize;
  const ourQMin = qMin(ourQOne, ourQTwo, midpoint);
  const denominator = ourQMin + competitorQMin;
  const share = denominator > 0 ? ourQMin / denominator : 0;
  const dailyReward = market.rewardDailyRate * share;
  const capital = (yesBidPrice + noBidPrice) * minSize;
  const pairPayoff = minSize;
  const pairSpreadProfit = pairPayoff - capital;
  const dailyReturn = capital > 0 ? dailyReward / capital : 0;

  return {
    ...market,
    status: "ok",
    midpoint,
    yesMid,
    noMid,
    yesBidPrice,
    noBidPrice,
    ourSharesPerToken: minSize,
    yesScore,
    noScore,
    ourQOne,
    ourQTwo,
    ourQMin,
    competitorQOne,
    competitorQTwo,
    competitorQMin,
    estimatedShare: share,
    estimatedDailyReward: dailyReward,
    capital,
    pairPayoff,
    pairSpreadProfit,
    dailyReturn,
    annualizedSimpleReturn: dailyReturn * 365,
    eligibleCompetitorLevels: {
      yesBids: compYes.bids.levels,
      yesAsks: compYes.asks.levels,
      noBids: compNo.bids.levels,
      noAsks: compNo.asks.levels,
    },
    bookAgeMs,
  };
}

async function mapLimit(items, limit, worker) {
  const results = new Array(items.length);
  let cursor = 0;
  async function run() {
    while (true) {
      const index = cursor;
      cursor += 1;
      if (index >= items.length) return;
      try {
        results[index] = await worker(items[index], index);
      } catch (error) {
        results[index] = { error: error instanceof Error ? error.message : String(error), item: items[index] };
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, run));
  return results;
}

function summarize(rows) {
  const ok = rows.filter((row) => row.status === "ok");
  const totalDailyReward = ok.reduce((sum, row) => sum + row.estimatedDailyReward, 0);
  const totalCapital = ok.reduce((sum, row) => sum + row.capital, 0);
  const highProb = ok.filter((row) => row.midpoint > 0.90);
  const normal = ok.filter((row) => row.midpoint >= 0.10 && row.midpoint <= 0.90);
  const summarizeGroup = (group) => ({
    markets: group.length,
    poolPerDay: group.reduce((sum, row) => sum + row.rewardDailyRate, 0),
    estimatedRewardPerDay: group.reduce((sum, row) => sum + row.estimatedDailyReward, 0),
    capital: group.reduce((sum, row) => sum + row.capital, 0),
    weightedDailyReturn: group.reduce((sum, row) => sum + row.capital, 0) > 0
      ? group.reduce((sum, row) => sum + row.estimatedDailyReward, 0) / group.reduce((sum, row) => sum + row.capital, 0)
      : 0,
  });
  return {
    analyzedMarkets: rows.length,
    okMarkets: ok.length,
    failedMarkets: rows.length - ok.length,
    poolPerDay: ok.reduce((sum, row) => sum + row.rewardDailyRate, 0),
    estimatedRewardPerDay: totalDailyReward,
    capital: totalCapital,
    weightedDailyReturn: totalCapital > 0 ? totalDailyReward / totalCapital : 0,
    weightedAnnualizedSimpleReturn: totalCapital > 0 ? (totalDailyReward / totalCapital) * 365 : 0,
    highProbability: summarizeGroup(highProb),
    middleProbability: summarizeGroup(normal),
    lowProbability: summarizeGroup(ok.filter((row) => row.midpoint < 0.10)),
  };
}

function csvEscape(value) {
  if (value == null) return "";
  const text = String(value);
  return /[",\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function writeCsv(rows) {
  const columns = [
    "conditionId", "question", "discoveryTag", "status", "midpoint", "rewardDailyRate",
    "rewardsMinSize", "rewardsMaxSpread", "yesBidPrice", "noBidPrice", "ourSharesPerToken",
    "yesScore", "noScore", "estimatedShare", "estimatedDailyReward", "capital",
    "pairSpreadProfit", "dailyReturn", "annualizedSimpleReturn", "competitorQOne",
    "competitorQTwo", "competitorQMin", "volume24hr", "liquidity", "endDate",
  ];
  const lines = [columns.join(",")];
  for (const row of rows) lines.push(columns.map((column) => csvEscape(row[column])).join(","));
  return lines.join("\n") + "\n";
}

function runSelfTest() {
  const close = (actual, expected, name) => {
    if (Math.abs(actual - expected) > 1e-9) throw new Error(`${name}: expected ${expected}, got ${actual}`);
  };
  close(scoreOrder(0.50, 0.48, "BID", 3), 1 / 9, "2c score at v=3");
  close(qMin(111.1111111111, 175, 0.50), 111.1111111111, "balanced qMin");
  close(qMin(10, 0, 0.50), 10 / 3, "single-sided qMin");
  close(qMin(10, 0, 0.95), 0, "high-price requires both sides");
  if (Math.abs(postOnlyBid({ bestBid: 0.95, bestAsk: 0.96, tickSize: 0.01 }, 1) - 0.95) > 1e-9) {
    throw new Error("post-only bid must not cross best ask");
  }
  console.log("reward calculator self-test passed");
}

async function main() {
  if (process.argv.includes("--self-test")) {
    runSelfTest();
    return;
  }
  const options = parseArgs(process.argv.slice(2));
  if ((process.env.HTTPS_PROXY || process.env.HTTP_PROXY) && process.env.NODE_USE_ENV_PROXY !== "1") {
    console.warn("warning: proxy variables are set but NODE_USE_ENV_PROXY is not 1; requests may bypass the proxy");
  }
  console.log(`discovering tags=${options.tags.join(",")}`);
  const discovered = (await Promise.all(options.tags.map((tag) => discoverTag(tag, options)))).flat();
  const byCondition = new Map();
  for (const raw of discovered) {
    const normalized = normalizeMarket(raw);
    if (!normalized || !normalized.conditionId) continue;
    const existing = byCondition.get(normalized.conditionId);
    if (!existing || normalized.rewardDailyRate > existing.rewardDailyRate) {
      byCondition.set(normalized.conditionId, normalized);
    }
  }
  const candidates = [...byCondition.values()]
    .filter((market) => market.rewardDailyRate >= options.minDailyPool)
    .filter((market) => market.volume24hr >= options.minVolume24h)
    .filter((market) => market.endTimestamp != null &&
      market.endTimestamp >= Date.now() + options.minHoursToClose * 3_600_000)
    .filter((market) => market.gammaMidpoint == null || (market.gammaMidpoint >= options.minPrice && market.gammaMidpoint <= options.maxPrice))
    .sort((a, b) => b.rewardDailyRate - a.rewardDailyRate);
  console.log(`discovered=${discovered.length} rewardMarkets=${byCondition.size} eligible=${candidates.length}`);
  const sampled = candidates.slice(0, options.bookMarkets);
  const rows = await mapLimit(sampled, options.concurrency, async (market) => {
    const [yesBookRaw, noBookRaw] = await Promise.all([
      fetchJson(`${CLOB_BASE}/book?token_id=${encodeURIComponent(market.tokens[0])}`),
      fetchJson(`${CLOB_BASE}/book?token_id=${encodeURIComponent(market.tokens[1])}`),
    ]);
    return analyzeMarket(
      market,
      [parseBook(yesBookRaw, market.tokens[0]), parseBook(noBookRaw, market.tokens[1])],
      options,
    );
  });
  const normalizedRows = rows.map((row) => row?.error ? { status: "request_error", error: row.error, question: row.item?.question } : row);
  const summary = summarize(normalizedRows);
  const snapshot = {
    generatedAt: new Date().toISOString(),
    methodology: {
      source: "Polymarket Gamma + CLOB public APIs",
      scoring: "S(v,s)=((v-s)/v)^2*b; paired bids on complementary tokens; c=3",
      caveat: "Point-in-time visible-book competition. Aggregate competitor qMin is conservative for the maker denominator because sum(min_i) <= min(sum). Reward scores are sampled once per minute with random offset, so this is an estimate, not a payout guarantee. Thin or stale books are rejected by default rather than treated as a 100% share.",
      options,
    },
    summary,
    markets: normalizedRows,
  };
  if (options.output) {
    await fs.mkdir(path.dirname(path.resolve(options.output)), { recursive: true });
    await fs.writeFile(options.output, JSON.stringify(snapshot, null, 2), "utf8");
    console.log(`wrote ${options.output}`);
  }
  if (options.csv) {
    await fs.mkdir(path.dirname(path.resolve(options.csv)), { recursive: true });
    await fs.writeFile(options.csv, writeCsv(normalizedRows), "utf8");
    console.log(`wrote ${options.csv}`);
  }

  const ok = normalizedRows.filter((row) => row.status === "ok").sort((a, b) => b.estimatedDailyReward - a.estimatedDailyReward);
  console.log("");
  console.log("TOP ESTIMATED REWARD MARKETS");
  console.log("dailyPool  estReward  share   capital  dailyRet  midpoint  question");
  for (const row of ok.slice(0, 20)) {
    console.log([
      row.rewardDailyRate.toFixed(2).padStart(9),
      row.estimatedDailyReward.toFixed(2).padStart(9),
      (row.estimatedShare * 100).toFixed(2).padStart(6) + "%",
      row.capital.toFixed(2).padStart(8),
      (row.dailyReturn * 100).toFixed(4).padStart(8) + "%",
      row.midpoint.toFixed(3).padStart(8),
      row.question,
    ].join("  "));
  }
  console.log("");
  console.log(JSON.stringify(summary, null, 2));
  if (ok.length === 0) {
    console.error("no market produced an eligible paired-bid reward estimate");
    process.exitCode = 2;
  }
}

main().catch((error) => {
  console.error(error?.stack || error);
  process.exit(1);
});