#!/usr/bin/env node

/**
 * Low-cost continuous monitor for complete-set / NegRisk arbitrage.
 *
 * Discovery uses Gamma top-of-book prices to build a small watchlist. The
 * watchlist is then refreshed from executable CLOB books at a faster cadence.
 * Any candidate is confirmed again after a configurable delay before being
 * recorded as an opportunity.
 */

import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import {
  buildGroups,
  clearBookCache,
  clearRunCaches,
  discoverTag,
  evaluateGroup,
  getEvent,
  normalizeMarket,
  validateNegRiskEvent,
} from "./scan-event-complete-set-arbitrage.mjs";

const DEFAULTS = Object.freeze({
  tags: ["politics", "geopolitics"],
  maxMarketsPerTag: 1000,
  maxGroups: 2000,
  maxOutcomesPerGroup: 256,
  minVolume24h: 1000,
  pollIntervalMs: 15_000,
  discoveryIntervalMs: 600_000,
  durationMs: 86_400_000,
  candidateAskSumMax: 1.02,
  candidateBidSumMin: 0.99,
  maxWatchGroups: 100,
  minEdgeCents: 0.5,
  minProfitUsd: 0.01,
  maxBookAgeMs: 10_000,
  confirmDelayMs: 1_000,
  mergeGasUsd: 0.02,
  mintGasUsd: 0.02,
  sizeStep: 0.01,
  concurrency: 4,
  outputDir: null,
  help: false,
});

function numberArg(arg, raw, minimum, { allowZero = false } = {}) {
  const value = Number(raw);
  if (!Number.isFinite(value) || (allowZero ? value < minimum : value <= minimum)) {
    throw new Error(`${arg} must be ${allowZero ? ">=" : ">"} ${minimum}`);
  }
  return value;
}

export function parseMonitorArgs(argv) {
  const options = { ...DEFAULTS, tags: [...DEFAULTS.tags] };
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
      case "--max-groups":
        options.maxGroups = Math.floor(numberArg(arg, next(), 0));
        break;
      case "--max-outcomes-per-group":
        options.maxOutcomesPerGroup = Math.floor(numberArg(arg, next(), 1));
        break;
      case "--min-volume":
        options.minVolume24h = numberArg(arg, next(), 0, { allowZero: true });
        break;
      case "--poll-interval-ms":
        options.pollIntervalMs = Math.floor(numberArg(arg, next(), 0));
        break;
      case "--discovery-interval-ms":
        options.discoveryIntervalMs = Math.floor(numberArg(arg, next(), 0));
        break;
      case "--duration-ms":
        options.durationMs = Math.floor(numberArg(arg, next(), 0));
        break;
      case "--candidate-ask-sum":
        options.candidateAskSumMax = numberArg(arg, next(), 0);
        break;
      case "--candidate-bid-sum":
        options.candidateBidSumMin = numberArg(arg, next(), 0);
        break;
      case "--max-watch-groups":
        options.maxWatchGroups = Math.floor(numberArg(arg, next(), 0));
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
      case "--confirm-delay-ms":
        options.confirmDelayMs = Math.floor(numberArg(arg, next(), 0, { allowZero: true }));
        break;
      case "--merge-gas-usd":
        options.mergeGasUsd = numberArg(arg, next(), 0, { allowZero: true });
        break;
      case "--mint-gas-usd":
        options.mintGasUsd = numberArg(arg, next(), 0, { allowZero: true });
        break;
      case "--size-step":
        options.sizeStep = numberArg(arg, next(), 0);
        break;
      case "--concurrency":
        options.concurrency = Math.floor(numberArg(arg, next(), 0));
        break;
      case "--output-dir":
        options.outputDir = next();
        break;
      case "--help":
        options.help = true;
        break;
      default:
        throw new Error(`unknown argument: ${arg}`);
    }
  }
  if (options.tags.length === 0) throw new Error("at least one tag is required");
  if (!options.outputDir) {
    const stamp = new Date().toISOString().replaceAll(":", "-").replace(/\.\d{3}Z$/, "Z");
    options.outputDir = path.join("backend", "build", "research", `complete-set-monitor-${stamp}`);
  }
  return options;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
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
        out[index] = { error: error instanceof Error ? error.message : String(error) };
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, run));
  return out;
}

function finite(value) {
  return Number.isFinite(value) ? value : null;
}

/**
 * Gamma only exposes one CLOB token's top-of-book on a binary market (the YES
 * token). The opposite side is approximated by the complement, which is enough
 * for a prefilter. Executable books are still fetched before a group is kept.
 */
export function proxySums(group, event = null, maxOutcomesPerGroup = 256) {
  if (group.type === "binary") {
    const market = group.markets?.[0];
    if (market?.bestAsk == null || market?.bestBid == null) return { ask: null, bid: null };
    return {
      ask: market.bestAsk + (1 - market.bestBid),
      bid: market.bestBid + (1 - market.bestAsk),
    };
  }
  const validation = validateNegRiskEvent(event, group, { maxOutcomesPerGroup });
  if (!validation.ok) return { ask: null, bid: null, validation };
  const asks = validation.markets.map((market) => market.bestAsk);
  if (asks.some((value) => value == null)) return { ask: null, bid: null, validation };
  return {
    ask: asks.reduce((sum, value) => sum + value, 0),
    bid: null,
    validation,
  };
}

function compactRow(row) {
  const ask = row.ask ?? {};
  const bid = row.bid ?? {};
  return {
    groupKey: row.groupKey,
    groupType: row.groupType,
    title: row.eventTitle,
    slug: row.eventSlug,
    outcomeCount: row.outcomeCount,
    status: row.status,
    feeRates: row.feeRates,
    oldestBookAgeMs: finite(row.oldestBookAgeMs),
    askTop: finite(ask.topAskSum),
    askEdgeCents: finite(ask.edgeCents),
    askAvailable: Boolean(ask.available),
    askActionable: Boolean(ask.actionable),
    askReason: ask.reason ?? null,
    bidTop: finite(bid.topBidSum),
    bidEdgeCents: finite(bid.edgeCents),
    bidAvailable: Boolean(bid.available),
    bidActionable: Boolean(bid.actionable),
    bidReason: bid.reason ?? null,
    actionable: Boolean(row.actionable),
  };
}
async function buildCandidateGroups(options) {
  clearRunCaches();
  const raw = (await Promise.all(options.tags.map((tag) => discoverTag(tag, options.maxMarketsPerTag)))).flat();
  const byCondition = new Map();
  for (const item of raw) {
    const market = normalizeMarket(item);
    if (market) byCondition.set(market.conditionId, market);
  }
  const groups = buildGroups([...byCondition.values()], options);
  const prepared = await mapLimit(groups, options.concurrency, async (group) => {
    if (group.type === "binary") {
      return { group, proxy: proxySums(group) };
    }
    try {
      const event = await getEvent(group.eventSlug);
      const proxy = proxySums(group, event, options.maxOutcomesPerGroup);
      if (proxy.validation?.ok) {
        const fullGroup = {
          ...group,
          markets: proxy.validation.markets,
          conditionIds: proxy.validation.markets.map((market) => market.conditionId),
          volume24hr: proxy.validation.markets.reduce((sum, market) => sum + market.volume24hr, 0),
          liquidity: proxy.validation.markets.reduce((sum, market) => sum + market.liquidity, 0),
        };
        return { group: fullGroup, proxy };
      }
      return { group, proxy };
    } catch (error) {
      return { group, proxy: { ask: null, bid: null }, error: error instanceof Error ? error.message : String(error) };
    }
  });

  const candidates = prepared.filter((item) =>
    (item.proxy?.ask != null && item.proxy.ask <= options.candidateAskSumMax) ||
    (item.proxy?.bid != null && item.proxy.bid >= options.candidateBidSumMin));
  return {
    markets: byCondition.size,
    groups: groups.length,
    prepared,
    candidates,
  };
}

function candidateScore(item) {
  const ask = item.row?.ask?.topAskSum;
  if (Number.isFinite(ask)) return ask;
  const bid = item.row?.bid?.topBidSum;
  if (Number.isFinite(bid)) return 1 - bid;
  const proxyAsk = item.candidate.proxy?.ask;
  if (Number.isFinite(proxyAsk)) return proxyAsk;
  return Number.POSITIVE_INFINITY;
}

async function verifyCandidates(candidates, options, previousWatchlist = new Map()) {
  clearBookCache();
  const verified = await mapLimit(candidates, options.concurrency, async (candidate) => {
    const row = await evaluateGroup(candidate.group, options);
    if (row.status !== "ok") return { candidate, row, keep: false };
    const keep = Boolean(
      row.ask?.available ||
      row.bid?.available ||
      (row.ask?.topAskSum != null && row.ask.topAskSum <= options.candidateAskSumMax + 0.01) ||
      (row.bid?.topBidSum != null && row.bid.topBidSum >= options.candidateBidSumMin - 0.01)
    );
    return { candidate, row, keep };
  });
  const kept = verified.filter((item) => item.keep).sort((a, b) => candidateScore(a) - candidateScore(b));
  const limited = kept.slice(0, options.maxWatchGroups);
  const next = new Map();
  for (const item of limited) {
    const key = item.candidate.group.key;
    const previous = previousWatchlist.get(key);
    next.set(key, {
      group: item.candidate.group,
      proxy: item.candidate.proxy,
      initialRow: compactRow(item.row),
      addedAt: previous?.addedAt ?? Date.now(),
      lastVerifiedAt: Date.now(),
    });
  }
  return {
    watchlist: next,
    verifiedCount: verified.length,
    keptCount: kept.length,
    truncatedCount: Math.max(0, kept.length - limited.length),
    rejectedCount: verified.filter((item) => !item.keep).length,
  };
}

function openWatchlist(list) {
  return [...list.values()].map((item) => ({
    key: item.group.key,
    groupType: item.group.type,
    title: item.group.eventTitle,
    eventSlug: item.group.eventSlug,
    outcomes: item.group.markets.length,
    addedAt: new Date(item.addedAt).toISOString(),
    lastVerifiedAt: new Date(item.lastVerifiedAt).toISOString(),
    initial: item.initialRow,
    proxy: {
      ask: item.proxy?.ask ?? null,
      bid: item.proxy?.bid ?? null,
      validation: item.proxy?.validation ? {
        ok: item.proxy.validation.ok,
        reason: item.proxy.validation.reason,
        outcomeCount: item.proxy.validation.outcomeCount,
      } : null,
    },
  }));
}

async function appendJsonl(file, value) {
  await fs.appendFile(file, `${JSON.stringify(value)}\n`, "utf8");
}

function trackEpisodes(rows, episodes, now) {
  const seen = new Set();
  for (const row of rows) {
    for (const side of ["ask", "bid"]) {
      const metric = row[side];
      if (!metric?.available) continue;
      const key = `${row.groupKey}:${side}`;
      seen.add(key);
      let episode = episodes.get(key);
      if (!episode) {
        episode = {
          key,
          groupKey: row.groupKey,
          groupType: row.groupType,
          side,
          title: row.eventTitle,
          eventSlug: row.eventSlug,
          startedAt: now,
          lastSeenAt: now,
          maxEdgeCents: metric.edgeCents,
          maxProfit: metric.profit,
          initial: compactRow(row),
          confirmPending: true,
          confirmed: false,
        };
        episodes.set(key, episode);
      }
      episode.lastSeenAt = now;
      if (metric.edgeCents > episode.maxEdgeCents) {
        episode.maxEdgeCents = metric.edgeCents;
        episode.maxProfit = metric.profit;
        episode.best = compactRow(row);
      }
    }
  }
  return seen;
}

async function closeMissingEpisodes(seen, episodes, file, now) {
  const closed = [];
  for (const [key, episode] of episodes) {
    if (seen.has(key)) continue;
    episode.endedAt = now;
    episode.durationMs = episode.lastSeenAt - episode.startedAt;
    episode.open = false;
    episodes.delete(key);
    closed.push(episode);
    await appendJsonl(file, {
      kind: "episode",
      recordedAt: new Date(now).toISOString(),
      ...episode,
    });
  }
  return closed;
}

async function confirmEpisode(episode, watchItem, options) {
  await sleep(options.confirmDelayMs);
  clearBookCache();
  const row = await evaluateGroup(watchItem.group, options);
  const metric = row[episode.side];
  const confirmed = Boolean(metric?.available && metric.actionable);
  episode.confirmPending = false;
  episode.confirmed = confirmed;
  episode.confirmation = {
    at: Date.now(),
    confirmed,
    row: compactRow(row),
    edgeCents: metric?.edgeCents ?? null,
    profit: metric?.profit ?? null,
    maxShares: metric?.maxShares ?? null,
    reason: metric?.reason ?? row.status,
  };
  return episode.confirmation;
}

async function confirmPendingEpisodes(episodes, watchlist, options, opportunitiesFile) {
  const pending = [...episodes.values()].filter((episode) => episode.confirmPending);
  const results = [];
  await mapLimit(pending, Math.min(2, options.concurrency), async (episode) => {
    const watchItem = watchlist.get(episode.groupKey);
    if (!watchItem) {
      episode.confirmPending = false;
      episode.confirmation = { confirmed: false, reason: "group_left_watchlist" };
      results.push({ key: episode.key, groupKey: episode.groupKey, side: episode.side, confirmed: false });
      return;
    }
    try {
      await confirmEpisode(episode, watchItem, options);
      await appendJsonl(opportunitiesFile, {
        kind: "confirmation",
        recordedAt: new Date().toISOString(),
        key: episode.key,
        groupKey: episode.groupKey,
        side: episode.side,
        title: episode.title,
        eventSlug: episode.eventSlug,
        initial: episode.initial,
        best: episode.best ?? episode.initial,
        confirmation: episode.confirmation,
      });
      results.push({
        key: episode.key,
        groupKey: episode.groupKey,
        side: episode.side,
        ...episode.confirmation,
      });
    } catch (error) {
      episode.confirmPending = false;
      episode.confirmation = { confirmed: false, error: error instanceof Error ? error.message : String(error) };
      results.push({ key: episode.key, confirmed: false, error: episode.confirmation.error });
    }
  });
  return results;
}
async function writeSummary(outputDir, state) {
  state.summary.lastUpdatedAt = new Date().toISOString();
  await fs.writeFile(
    path.join(outputDir, "monitor-summary.json"),
    `${JSON.stringify(state.summary, null, 2)}\n`,
    "utf8",
  );
}

async function refreshWatchlist(options, state, outputDir) {
  const discovered = await buildCandidateGroups(options);
  const verified = await verifyCandidates(discovered.candidates, options, state.watchlist);
  state.watchlist = verified.watchlist;
  state.summary.discoveryCycles += 1;
  state.summary.lastDiscoveryAt = new Date().toISOString();
  state.summary.lastDiscoveredMarkets = discovered.markets;
  state.summary.lastDiscoveredGroups = discovered.groups;
  state.summary.lastProxyCandidates = discovered.candidates.length;
  state.summary.lastVerifiedCandidates = verified.verifiedCount;
  state.summary.lastKeptCandidates = verified.keptCount;
  state.summary.lastRejectedCandidates = verified.rejectedCount;
  state.summary.lastTruncatedCandidates = verified.truncatedCount;
  state.summary.watchlistSize = state.watchlist.size;
  await fs.writeFile(
    path.join(outputDir, "watchlist.json"),
    `${JSON.stringify(openWatchlist(state.watchlist), null, 2)}\n`,
    "utf8",
  );
  console.log(
    `discovery markets=${discovered.markets} groups=${discovered.groups} ` +
    `proxy=${discovered.candidates.length} verified=${verified.verifiedCount} ` +
    `kept=${verified.keptCount} watchlist=${state.watchlist.size}`,
  );
}

async function pollOnce(options, state, outputDir) {
  clearBookCache();
  const watchItems = [...state.watchlist.values()];
  const rows = await mapLimit(watchItems, options.concurrency, (item) => evaluateGroup(item.group, options));
  const now = Date.now();
  const compact = rows.map(compactRow);
  state.summary.pollCycles += 1;
  state.summary.lastPollAt = new Date(now).toISOString();
  state.summary.watchlistSize = state.watchlist.size;
  state.summary.lastPollRows = compact.length;
  state.summary.lastPollErrors = rows.filter((row) => row.status !== "ok").length;
  state.summary.errorRows += state.summary.lastPollErrors;

  const askTops = compact.map((row) => row.askTop).filter(Number.isFinite);
  const bidTops = compact.map((row) => row.bidTop).filter(Number.isFinite);
  if (askTops.length > 0) {
    const best = Math.min(...askTops);
    state.summary.bestAskSum = state.summary.bestAskSum == null
      ? best
      : Math.min(state.summary.bestAskSum, best);
  }
  if (bidTops.length > 0) {
    const best = Math.max(...bidTops);
    state.summary.bestBidSum = state.summary.bestBidSum == null
      ? best
      : Math.max(state.summary.bestBidSum, best);
  }

  const seen = trackEpisodes(rows, state.episodes, now);
  const closed = await closeMissingEpisodes(seen, state.episodes, path.join(outputDir, "opportunities.jsonl"), now);
  state.summary.closedEpisodes += closed.length;

  await appendJsonl(path.join(outputDir, "snapshots.jsonl"), {
    timestamp: new Date(now).toISOString(),
    poll: state.summary.pollCycles,
    rows: compact,
  });

  const confirmations = await confirmPendingEpisodes(
    state.episodes,
    state.watchlist,
    options,
    path.join(outputDir, "opportunities.jsonl"),
  );
  for (const confirmation of confirmations) {
    if (confirmation.confirmed) {
      state.summary.confirmedEpisodes += 1;
      console.log(`CONFIRMED ${confirmation.key} edge=${confirmation.edgeCents?.toFixed(4)}c profit=${confirmation.profit?.toFixed(4)}`);
    }
  }
  state.summary.openEpisodes = state.episodes.size;
  state.summary.latestRows = compact.slice(0, 20);
}

async function finishOpenEpisodes(state, outputDir) {
  const file = path.join(outputDir, "opportunities.jsonl");
  const now = Date.now();
  for (const episode of state.episodes.values()) {
    episode.endedAt = now;
    episode.durationMs = episode.lastSeenAt - episode.startedAt;
    episode.open = true;
    await appendJsonl(file, {
      kind: "episode",
      recordedAt: new Date(now).toISOString(),
      ...episode,
    });
  }
  state.episodes.clear();
  state.summary.openEpisodes = 0;
}

async function main(argv = process.argv.slice(2)) {
  const options = parseMonitorArgs(argv);
  if (options.help) {
    console.log("Usage: node scripts/monitor-event-complete-set-arbitrage.mjs [options]");
    console.log("  --tags politics,geopolitics");
    console.log("  --poll-interval-ms 15000");
    console.log("  --discovery-interval-ms 600000");
    console.log("  --duration-ms 86400000");
    console.log("  --candidate-ask-sum 1.02");
    console.log("  --candidate-bid-sum 0.99");
    console.log("  --max-watch-groups 100");
    console.log("  --min-edge-cents 0.5");
    console.log("  --max-book-age-ms 10000");
    console.log("  --confirm-delay-ms 1000");
    console.log("  --output-dir PATH");
    return;
  }

  const outputDir = path.resolve(options.outputDir);
  await fs.mkdir(outputDir, { recursive: true });
  try {
    await fs.access(path.join(outputDir, "run.json"));
    throw new Error(`output directory already contains run.json: ${outputDir}`);
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }

  if ((process.env.HTTPS_PROXY || process.env.HTTP_PROXY) && process.env.NODE_USE_ENV_PROXY !== "1") {
    console.warn("warning: NODE_USE_ENV_PROXY is not 1; requests may bypass the proxy");
  }

  const startedAt = Date.now();
  const state = {
    watchlist: new Map(),
    episodes: new Map(),
    summary: {
      startedAt: new Date(startedAt).toISOString(),
      lastUpdatedAt: null,
      options,
      durationMs: options.durationMs,
      pollCycles: 0,
      discoveryCycles: 0,
      errorRows: 0,
      lastPollAt: null,
      lastDiscoveryAt: null,
      watchlistSize: 0,
      lastDiscoveredMarkets: 0,
      lastDiscoveredGroups: 0,
      lastProxyCandidates: 0,
      lastVerifiedCandidates: 0,
      lastKeptCandidates: 0,
      lastRejectedCandidates: 0,
      lastTruncatedCandidates: 0,
      bestAskSum: null,
      bestBidSum: null,
      openEpisodes: 0,
      closedEpisodes: 0,
      confirmedEpisodes: 0,
      latestRows: [],
    },
  };
  await fs.writeFile(path.join(outputDir, "run.json"), `${JSON.stringify({
    pid: process.pid,
    startedAt: new Date(startedAt).toISOString(),
    options,
  }, null, 2)}\n`, "utf8");
  await fs.writeFile(path.join(outputDir, "monitor-summary.json"), `${JSON.stringify(state.summary, null, 2)}\n`, "utf8");
  await fs.writeFile(path.join(outputDir, "opportunities.jsonl"), "", "utf8");
  await fs.writeFile(path.join(outputDir, "snapshots.jsonl"), "", "utf8");

  let stopping = false;
  process.on("SIGINT", () => {
    console.log("SIGINT received; finishing monitor");
    stopping = true;
  });
  process.on("SIGTERM", () => {
    console.log("SIGTERM received; finishing monitor");
    stopping = true;
  });

  const endAt = startedAt + options.durationMs;
  let nextDiscoveryAt = startedAt;
  let nextSummaryAt = startedAt;
  console.log(`monitor started pid=${process.pid} output=${outputDir} durationMs=${options.durationMs}`);

  while (!stopping && Date.now() < endAt) {
    const cycleStart = Date.now();
    try {
      if (cycleStart >= nextDiscoveryAt) {
        await refreshWatchlist(options, state, outputDir);
        nextDiscoveryAt = Date.now() + options.discoveryIntervalMs;
      }
      await pollOnce(options, state, outputDir);
    } catch (error) {
      state.summary.errorRows += 1;
      console.error(`monitor cycle failed: ${error instanceof Error ? error.stack : error}`);
    }
    if (Date.now() >= nextSummaryAt) {
      await writeSummary(outputDir, state);
      nextSummaryAt = Date.now() + 60_000;
    }
    if (state.summary.pollCycles % 20 === 0) {
      console.log(
        `poll=${state.summary.pollCycles} watch=${state.watchlist.size} ` +
        `bestAsk=${state.summary.bestAskSum} bestBid=${state.summary.bestBidSum} ` +
        `episodes=${state.episodes.size}`,
      );
    }
    const elapsed = Date.now() - cycleStart;
    const wait = Math.max(0, Math.min(options.pollIntervalMs - elapsed, endAt - Date.now()));
    if (wait > 0 && !stopping) await sleep(wait);
  }

  await finishOpenEpisodes(state, outputDir);
  state.summary.stoppedAt = new Date().toISOString();
  state.summary.stopReason = stopping ? "signal" : "duration";
  await writeSummary(outputDir, state);
  console.log(`monitor stopped reason=${state.summary.stopReason}`);
}

const invokedDirectly = process.argv[1]
  ? import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
  : false;

if (invokedDirectly) {
  main().catch(async (error) => {
    console.error(error?.stack || error);
    process.exitCode = 1;
  });
}
