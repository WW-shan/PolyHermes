#!/usr/bin/env node

/**
 * Read-only execution-risk report for one complete-set event.
 * No order signing or submission code is present in this script.
 */

import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import {
  analyzeBasketExecutionRisk,
  computeHoldingYield,
  feeForFill,
  orderLegsForSequentialExecution,
  payoutMatrixFor,
} from "./complete-set-basket-risk.mjs";
import {
  bookHealth,
  buildGroups,
  costForShares,
  getBook,
  getEvent,
  maxPositiveShares,
  normalizeMarket,
  resolveFeeRate,
  totalBidDepth,
  validateNegRiskEvent,
} from "./scan-event-complete-set-arbitrage.mjs";

const DEFAULTS = Object.freeze({
  eventSlug: null,
  side: "yes",
  targetShares: null,
  maxLossUsd: 10,
  maxCapitalUsd: 25,
  entrySlippageCents: 0,
  exitSlippageCents: 0.2,
  expectedHoldDays: null,
  maxBookAgeMs: 15_000,
  gasUsd: 0.02,
  sizeStep: 0.01,
  executionMode: "sequential",
  output: null,
  help: false,
});

function numberArg(arg, raw, minimum, { allowZero = false } = {}) {
  const value = Number(raw);
  if (!Number.isFinite(value) || (allowZero ? value < minimum : value <= minimum)) {
    throw new Error(`${arg} must be ${allowZero ? ">=" : ">"} ${minimum}`);
  }
  return value;
}

export function parseRiskArgs(argv) {
  const options = { ...DEFAULTS };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => {
      i += 1;
      if (i >= argv.length) throw new Error(`missing value for ${arg}`);
      return argv[i];
    };
    switch (arg) {
      case "--event-slug": options.eventSlug = next(); break;
      case "--side": options.side = next().toLowerCase(); break;
      case "--target-shares": options.targetShares = numberArg(arg, next(), 0); break;
      case "--max-loss-usd": options.maxLossUsd = numberArg(arg, next(), 0, { allowZero: true }); break;
      case "--max-capital-usd": options.maxCapitalUsd = numberArg(arg, next(), 0, { allowZero: true }); break;
      case "--entry-slippage-cents": options.entrySlippageCents = numberArg(arg, next(), 0, { allowZero: true }); break;
      case "--exit-slippage-cents": options.exitSlippageCents = numberArg(arg, next(), 0, { allowZero: true }); break;
      case "--expected-hold-days": options.expectedHoldDays = numberArg(arg, next(), 0); break;
      case "--max-book-age-ms": options.maxBookAgeMs = Math.floor(numberArg(arg, next(), 0)); break;
      case "--gas-usd": options.gasUsd = numberArg(arg, next(), 0, { allowZero: true }); break;
      case "--size-step": options.sizeStep = numberArg(arg, next(), 0); break;
      case "--execution-mode": options.executionMode = next().toLowerCase(); break;
      case "--output": options.output = next(); break;
      case "--help": options.help = true; break;
      default: throw new Error(`unknown argument: ${arg}`);
    }
  }
  if (!["yes", "no"].includes(options.side)) throw new Error("--side must be yes or no");
  if (!["parallel", "sequential"].includes(options.executionMode)) {
    throw new Error("--execution-mode must be parallel or sequential");
  }
  if (options.sizeStep > 1) throw new Error("--size-step must be <= 1");
  return options;
}

function eventMarket(market, event) {
  return normalizeMarket({
    ...market,
    events: [{ id: event.id, slug: event.slug, title: event.title }],
  });
}

function selectGroup(event, options) {
  const normalized = (event.markets ?? []).map((market) => eventMarket(market, event)).filter(Boolean);
  const groups = buildGroups(normalized, {
    minVolume24h: 0,
    maxGroups: 10,
    maxOutcomesPerGroup: 256,
  });
  if (groups.length === 0) throw new Error("event has no tradable complete-set group");
  const group = groups[0];
  if (options.side === "no" && group.type !== "negRisk") {
    throw new Error("--side no requires a NegRisk event");
  }
  return group;
}

async function loadBooks(entries) {
  const loaded = await Promise.all(entries.map(async (entry) => {
    const [book, fee] = await Promise.all([
      getBook(entry.tokenId),
      resolveFeeRate(entry.market, entry.tokenId),
    ]);
    if (fee.rate == null) throw new Error(`fee rate unavailable for ${entry.tokenId}`);
    return {
      ...entry,
      book,
      feeRate: fee.rate,
      feeSource: fee.source,
    };
  }));
  return loaded;
}

function minimumShares(markets, books, step) {
  return Math.max(
    step,
    5,
    ...markets.map((market) => market.minOrderSize ?? 5),
    ...books.map((entry) => entry.book.minOrderSize ?? 5),
  );
}

function buildBookEntries(group, options) {
  if (group.type === "binary") {
    const market = group.markets[0];
    return [
      { tokenId: market.yesTokenId, market, label: `${market.question} [YES]` },
      { tokenId: market.noTokenId, market, label: `${market.question} [NO]` },
    ];
  }
  const tokenKey = options.side === "yes" ? "yesTokenId" : "noTokenId";
  return group.markets.map((market) => ({
    tokenId: market[tokenKey],
    market,
    label: market.question || market.slug || market.conditionId,
  }));
}

export async function buildExecutionRiskReport(options) {
  if (!options.eventSlug) throw new Error("--event-slug is required");
  const event = await getEvent(options.eventSlug);
  if (!event) throw new Error(`event not found: ${options.eventSlug}`);
  const group = selectGroup(event, options);
  if (group.type === "negRisk") {
    const validation = validateNegRiskEvent(event, group, { maxOutcomesPerGroup: 256 });
    if (!validation.ok) throw new Error(`incomplete NegRisk event: ${validation.reason}`);
    group.markets = validation.markets;
  }

  const entries = await loadBooks(buildBookEntries(group, options));
  const now = Date.now();
  const health = bookHealth(entries, now, options.maxBookAgeMs);
  if (!health.ok) throw new Error(`invalid book set: ${JSON.stringify(health)}`);
  const step = options.sizeStep;
  const minShares = minimumShares(group.markets, entries, step);
  const payoutPerShare = options.side === "no" ? group.markets.length - 1 : 1;
  const maxShares = maxPositiveShares(entries, minShares, step, "ask", payoutPerShare);
  const targetShares = options.targetShares == null
    ? maxShares
    : Math.floor((options.targetShares + 1e-12) / step) * step;
  if (!(targetShares >= minShares)) {
    return {
      generatedAt: new Date().toISOString(),
      status: "no_trade",
      reason: maxShares == null ? "no_positive_basket_at_current_depth" : "target_below_minimum",
      eventSlug: options.eventSlug,
      side: options.side,
      minShares,
      maxShares,
      health,
    };
  }

  const legs = [];
  for (const entry of entries) {
    const legCost = costForShares([entry], targetShares);
    if (!legCost) {
      return {
        generatedAt: new Date().toISOString(),
        status: "no_trade",
        reason: `insufficient_depth:${entry.tokenId}`,
        eventSlug: options.eventSlug,
        side: options.side,
        targetShares,
        health,
      };
    }
    const entryCashPerShare = legCost.cash / targetShares + options.entrySlippageCents / 100;
    if (entryCashPerShare >= 1) throw new Error(`entry slippage makes ${entry.tokenId} unpurchasable`);
    const topBid = entry.book.bids[0]?.price ?? 0;
    legs.push({
      tokenId: entry.tokenId,
      label: entry.label,
      entryPrice: legCost.cash / targetShares,
      entryFeeRate: entry.feeRate,
      entryCashPerShare,
      entryFeePerShare: legCost.fees / targetShares,
      bidPrice: topBid,
      bidFeeRate: entry.feeRate,
      bidDepth: totalBidDepth(entry.book),
      feeSource: entry.feeSource,
      topAsk: entry.book.asks[0]?.price ?? null,
      topBid,
      bidDepthShares: totalBidDepth(entry.book),
    });
  }

  let payoutMatrix = payoutMatrixFor(options.side === "no" ? "no" : "yes", legs.length);
  let executionLegs = legs;
  let executionOrder = legs.map((_, index) => index);
  let orderRationale = "input order";
  if (options.executionMode === "sequential") {
    const ordered = orderLegsForSequentialExecution(legs, payoutMatrix);
    executionLegs = ordered.legs;
    payoutMatrix = ordered.payoutMatrix;
    executionOrder = ordered.order;
    orderRationale = ordered.rationale;
  }
  const risk = analyzeBasketExecutionRisk({
    legs: executionLegs,
    targetShares,
    payoutMatrix,
    gasUsd: options.gasUsd,
    liquidationSlippageCents: options.exitSlippageCents,
    executionMode: options.executionMode,
    maxLossUsd: options.maxLossUsd,
    maxCapitalUsd: options.maxCapitalUsd,
    failClosedOnNonExhaustive: true,
  });
  const topAskSum = entries.reduce((sum, entry) => sum + (entry.book.asks[0]?.price ?? 0), 0);
  const eventEndDate = event.endDate ?? group.endDate ?? group.markets[0]?.endDate ?? null;
  const conversionPath = group.type === "binary"
    ? "merge_positions_immediately"
    : options.side === "no" ? "convert_positions_immediately" : "hold_to_resolution";
  const holding = computeHoldingYield({
    profitUsd: risk.allFilled.holdPnl,
    capitalUsd: risk.capitalAtRiskUsd,
    endDate: eventEndDate,
    conversionPath,
    expectedHoldDays: options.expectedHoldDays,
  });
  return {
    generatedAt: new Date().toISOString(),
    status: risk.decision === "eligible_for_mock" ? "eligible_for_mock" : "blocked",
    eventSlug: options.eventSlug,
    eventTitle: event.title ?? group.eventTitle,
    eventEndDate,
    holding,
    groupType: group.type,
    side: options.side,
    outcomeCount: group.markets.length,
    payoutPerShare,
    minShares,
    maxSharesAtCurrentDepth: maxShares,
    targetShares,
    topAskSum,
    bookHealth: health,
    legs: executionLegs,
    executionOrder,
    orderRationale,
    risk,
    notice: "read-only research report; no orders were signed or submitted",
  };
}

function printReport(report) {
  if (report.status === "no_trade") {
    console.log(JSON.stringify(report, null, 2));
    return;
  }
  const r = report.risk;
  console.log(`event: ${report.eventTitle}`);
  console.log(`side=${report.side} outcomes=${report.outcomeCount} shares=${report.targetShares} topAskSum=${report.topAskSum}`);
  console.log(`complete PnL=${r.allFilled.holdPnl.toFixed(6)} USDC ` +
    `profit/share=${(r.allFilled.holdPnl / report.targetShares * 100).toFixed(4)}c`);
  console.log(`capital=${r.capitalAtRiskUsd.toFixed(4)} maxObservedLoss=${r.maxObservedLossUsd.toFixed(4)} ` +
    `riskBudget=${r.lossBudgetUsd.toFixed(4)} decision=${r.decision}`);
  console.log(`scenarios=${r.scenarioCount} exhaustive=${r.exhaustive} mode=${r.executionMode}`);
  console.log(`conversion=${report.holding.conversionPath} terminalROI=${(report.holding.terminalRoi * 100).toFixed(3)}% ` +
    `maxHoldDays=${report.holding.maxHoldDays?.toFixed(2) ?? "n/a"} annualizedAtEnd=${report.holding.annualizedRoiAtEnd == null ? "n/a" : (report.holding.annualizedRoiAtEnd * 100).toFixed(3) + "%"}`);
  if (report.holding.expectedAnnualizedRoi != null) {
    console.log(`expectedHoldDays=${report.holding.expectedHoldDays.toFixed(2)} ` +
      `expectedAnnualized=${(report.holding.expectedAnnualizedRoi * 100).toFixed(3)}%`);
  }
  if (r.worstScenario) {
    console.log(`worst fills=[${r.worstScenario.fills.map((value) => value.toFixed(2)).join(",")}] ` +
      `pnl=${r.worstScenario.worstPnl.toFixed(4)} policy=${r.worstScenario.worstPolicy}`);
  }
  console.log("legs:");
  for (const leg of report.legs) {
    console.log(`  ${leg.tokenId} ask=${leg.topAsk} bid=${leg.topBid} bidDepth=${leg.bidDepthShares} ${leg.label}`);
  }
}

async function main(argv = process.argv.slice(2)) {
  const options = parseRiskArgs(argv);
  if (options.help) {
    console.log("Usage: node scripts/analyze-complete-set-execution-risk.mjs --event-slug SLUG [options]");
    console.log("  --side yes|no");
    console.log("  --target-shares N");
    console.log("  --max-loss-usd 10");
    console.log("  --max-capital-usd 25");
    console.log("  --entry-slippage-cents 0");
    console.log("  --exit-slippage-cents 0.2");
    console.log("  --expected-hold-days N");
    console.log("  --max-book-age-ms 15000");
    console.log("  --gas-usd 0.02");
    console.log("  --execution-mode sequential|parallel");
    console.log("  --output PATH");
    return;
  }
  if ((process.env.HTTPS_PROXY || process.env.HTTP_PROXY) && process.env.NODE_USE_ENV_PROXY !== "1") {
    console.warn("warning: NODE_USE_ENV_PROXY is not 1; requests may bypass the proxy");
  }
  const report = await buildExecutionRiskReport(options);
  if (options.output) {
    const output = path.resolve(options.output);
    await fs.mkdir(path.dirname(output), { recursive: true });
    await fs.writeFile(output, `${JSON.stringify(report, null, 2)}\n`, "utf8");
    console.log(`wrote ${output}`);
  }
  printReport(report);
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
