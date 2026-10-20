#!/usr/bin/env node

/**
 * Sequential live-book paper executor for complete-set baskets.
 *
 * It reads public order books, simulates taker fills leg by leg with a delay,
 * and unwinds filled inventory at displayed bids if a later leg fails. It does
 * not sign or submit orders.
 */

import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { buildExecutionRiskReport } from "./analyze-complete-set-execution-risk.mjs";
import { feeForFill } from "./complete-set-basket-risk.mjs";
import { bookHealth, clearBookCache, getBook } from "./scan-event-complete-set-arbitrage.mjs";

const EPSILON = 1e-9;

const DEFAULTS = Object.freeze({
  eventSlug: null,
  side: "yes",
  targetShares: null,
  maxLossUsd: 50,
  maxCapitalUsd: 500,
  entryToleranceCents: 0.5,
  unwindSlippageCents: 0.5,
  legDelayMs: 1000,
  unwindDelayMs: 500,
  maxBookAgeMs: 15_000,
  gasUsd: 0.02,
  sizeStep: 0.01,
  paperSkipLeg: null,
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

export function parsePaperArgs(argv) {
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
      case "--entry-tolerance-cents": options.entryToleranceCents = numberArg(arg, next(), 0, { allowZero: true }); break;
      case "--unwind-slippage-cents": options.unwindSlippageCents = numberArg(arg, next(), 0, { allowZero: true }); break;
      case "--leg-delay-ms": options.legDelayMs = Math.floor(numberArg(arg, next(), 0, { allowZero: true })); break;
      case "--unwind-delay-ms": options.unwindDelayMs = Math.floor(numberArg(arg, next(), 0, { allowZero: true })); break;
      case "--max-book-age-ms": options.maxBookAgeMs = Math.floor(numberArg(arg, next(), 0)); break;
      case "--gas-usd": options.gasUsd = numberArg(arg, next(), 0, { allowZero: true }); break;
      case "--size-step": options.sizeStep = numberArg(arg, next(), 0); break;
      case "--paper-skip-leg": options.paperSkipLeg = Math.floor(numberArg(arg, next(), 0, { allowZero: true })); break;
      case "--output": options.output = next(); break;
      case "--help": options.help = true; break;
      default: throw new Error(`unknown argument: ${arg}`);
    }
  }
  if (!["yes", "no"].includes(options.side)) throw new Error("--side must be yes or no");
  if (options.sizeStep > 1) throw new Error("--size-step must be <= 1");
  return options;
}

export function permutedPayoutMatrix(kind, executionOrder) {
  if (!Array.isArray(executionOrder) || executionOrder.length < 2) throw new Error("executionOrder is invalid");
  const legCount = executionOrder.length;
  return Array.from({ length: legCount }, (_, winner) =>
    executionOrder.map((originalLeg) => {
      if (kind === "yes") return originalLeg === winner ? 1 : 0;
      if (kind === "no") return originalLeg === winner ? 0 : 1;
      throw new Error(`unsupported basket kind: ${kind}`);
    }));
}

export function simulateBuy(book, shares, limitPrice, feeRate) {
  if (!Array.isArray(book?.asks)) throw new Error("book asks are missing");
  if (![shares, limitPrice, feeRate].every(Number.isFinite) || shares < 0 || limitPrice < 0 || feeRate < 0) {
    throw new Error("invalid buy simulation inputs");
  }
  let remaining = shares;
  let cash = 0;
  let fees = 0;
  const fills = [];
  for (const level of book.asks) {
    if (remaining <= EPSILON) break;
    if (!Number.isFinite(level.price) || !Number.isFinite(level.size)) continue;
    if (level.price > limitPrice + EPSILON) break;
    const take = Math.min(remaining, level.size);
    if (take <= EPSILON) continue;
    const fee = feeForFill(take, level.price, feeRate);
    cash += take * level.price;
    fees += fee;
    fills.push({ price: level.price, shares: take, fee });
    remaining -= take;
  }
  const filled = shares - remaining;
  return {
    requested: shares,
    filled,
    remaining,
    cash,
    fees,
    averagePrice: filled > EPSILON ? cash / filled : null,
    fills,
  };
}

export function simulateSell(book, shares, minimumPrice, feeRate) {
  if (!Array.isArray(book?.bids)) throw new Error("book bids are missing");
  if (![shares, minimumPrice, feeRate].every(Number.isFinite) || shares < 0 || minimumPrice < 0 || feeRate < 0) {
    throw new Error("invalid sell simulation inputs");
  }
  let remaining = shares;
  let cash = 0;
  let fees = 0;
  const fills = [];
  for (const level of book.bids) {
    if (remaining <= EPSILON) break;
    if (!Number.isFinite(level.price) || !Number.isFinite(level.size)) continue;
    if (level.price < minimumPrice - EPSILON) break;
    const take = Math.min(remaining, level.size);
    if (take <= EPSILON) continue;
    const fee = feeForFill(take, level.price, feeRate);
    cash += take * level.price;
    fees += fee;
    fills.push({ price: level.price, shares: take, fee });
    remaining -= take;
  }
  const sold = shares - remaining;
  return {
    requested: shares,
    sold,
    remaining,
    cash,
    fees,
    averagePrice: sold > EPSILON ? cash / sold : null,
    fills,
  };
}

export function computePaperBasketPnl({
  fills,
  sold = fills.map(() => 0),
  entryCash,
  entryFees,
  unwindCash = 0,
  unwindFees = 0,
  gasUsd = 0,
  payoutMatrix,
}) {
  if (!Array.isArray(fills) || fills.length < 2) throw new Error("fills are invalid");
  if (!Array.isArray(sold) || sold.length !== fills.length) throw new Error("sold is invalid");
  if (!Array.isArray(payoutMatrix) || payoutMatrix.length !== fills.length) throw new Error("payoutMatrix is invalid");
  const residual = fills.map((value, index) => Math.max(0, value - sold[index]));
  const payouts = payoutMatrix.map((row) => row.reduce((sum, multiplier, index) => sum + multiplier * residual[index], 0));
  const worstPayout = Math.min(...payouts);
  const netEntry = entryCash + entryFees;
  const netUnwind = unwindCash - unwindFees;
  return {
    entryCost: netEntry,
    netUnwindProceeds: netUnwind,
    residualShares: residual,
    worstResolutionPayout: worstPayout,
    worstPnl: netUnwind + worstPayout - netEntry - gasUsd,
    residualUnsoldShares: residual.reduce((sum, value) => sum + value, 0),
  };
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function assertFreshBook(book, tokenId, maxBookAgeMs) {
  const health = bookHealth([{ book, tokenId }], Date.now(), maxBookAgeMs);
  if (!health.ok || health.staleBook.length > 0 || health.crossedBook.length > 0) {
    throw new Error(`unexecutable book: ${JSON.stringify(health)}`);
  }
}

export async function executeLivePaperBasket(options) {
  const riskReport = await buildExecutionRiskReport({
    eventSlug: options.eventSlug,
    side: options.side,
    targetShares: options.targetShares,
    maxLossUsd: options.maxLossUsd,
    maxCapitalUsd: options.maxCapitalUsd,
    entrySlippageCents: 0,
    exitSlippageCents: options.unwindSlippageCents,
    expectedHoldDays: null,
    maxBookAgeMs: options.maxBookAgeMs,
    gasUsd: options.gasUsd,
    sizeStep: options.sizeStep,
    executionMode: "sequential",
  });
  const result = {
    mode: "live_book_paper",
    generatedAt: new Date().toISOString(),
    eventSlug: options.eventSlug,
    side: options.side,
    status: "blocked",
    riskStatus: riskReport.status,
    riskDecision: riskReport.risk?.decision ?? null,
    targetShares: riskReport.targetShares ?? options.targetShares,
    executionOrder: riskReport.executionOrder ?? null,
    legs: riskReport.legs ?? [],
    attempts: [],
    unwind: [],
    caveat: "No orders were signed or submitted. Fills are simulations against observed public depth.",
  };
  if (riskReport.status !== "eligible_for_mock") {
    result.reason = riskReport.reason ?? riskReport.risk?.decision ?? riskReport.status;
    return result;
  }
  const targetShares = riskReport.targetShares;
  const legs = riskReport.legs;
  const completeFills = new Array(legs.length).fill(0);
  const entryCashByLeg = new Array(legs.length).fill(0);
  const entryFeesByLeg = new Array(legs.length).fill(0);
  let failed = false;
  for (let index = 0; index < legs.length; index += 1) {
    if (index > 0 && options.legDelayMs > 0) await sleep(options.legDelayMs);
    const leg = legs[index];
    if (options.paperSkipLeg === index) {
      result.attempts.push({
        index,
        tokenId: leg.tokenId,
        label: leg.label,
        observedAt: new Date().toISOString(),
        injectedFailure: true,
      });
      failed = true;
      break;
    }
    try {
      clearBookCache();
      const book = await getBook(leg.tokenId);
      assertFreshBook(book, leg.tokenId, options.maxBookAgeMs);
      const topAsk = book.asks[0]?.price;
      if (!Number.isFinite(topAsk)) throw new Error("missing ask");
      const limitPrice = Math.min(0.999, topAsk + options.entryToleranceCents / 100);
      const fill = simulateBuy(book, targetShares, limitPrice, leg.entryFeeRate);
      completeFills[index] = fill.filled;
      entryCashByLeg[index] = fill.cash;
      entryFeesByLeg[index] = fill.fees;
      result.attempts.push({
        index,
        tokenId: leg.tokenId,
        label: leg.label,
        observedAt: new Date().toISOString(),
        topAsk,
        limitPrice,
        requestedShares: targetShares,
        filledShares: fill.filled,
        averagePrice: fill.averagePrice,
        cash: fill.cash,
        fees: fill.fees,
        fills: fill.fills,
      });
      if (fill.filled < targetShares - EPSILON) failed = true;
    } catch (error) {
      result.attempts.push({
        index,
        tokenId: leg.tokenId,
        label: leg.label,
        observedAt: new Date().toISOString(),
        error: error instanceof Error ? error.message : String(error),
      });
      failed = true;
    }
    if (failed) break;
  }

  const soldByLeg = new Array(legs.length).fill(0);
  let unwindCash = 0;
  let unwindFees = 0;
  if (failed) {
    if (options.unwindDelayMs > 0) await sleep(options.unwindDelayMs);
    for (let index = 0; index < legs.length; index += 1) {
      const shares = completeFills[index];
      if (shares <= EPSILON) continue;
      const leg = legs[index];
      try {
        clearBookCache();
        const book = await getBook(leg.tokenId);
        assertFreshBook(book, leg.tokenId, options.maxBookAgeMs);
        const topBid = book.bids[0]?.price;
        if (!Number.isFinite(topBid)) throw new Error("missing bid");
        const minimumPrice = Math.max(0, topBid - options.unwindSlippageCents / 100);
        const sale = simulateSell(book, shares, minimumPrice, leg.bidFeeRate);
        soldByLeg[index] = sale.sold;
        unwindCash += sale.cash;
        unwindFees += sale.fees;
        result.unwind.push({
          index,
          tokenId: leg.tokenId,
          label: leg.label,
          observedAt: new Date().toISOString(),
          topBid,
          minimumPrice,
          requestedShares: shares,
          soldShares: sale.sold,
          averagePrice: sale.averagePrice,
          cash: sale.cash,
          fees: sale.fees,
          fills: sale.fills,
        });
      } catch (error) {
        result.unwind.push({
          index,
          tokenId: leg.tokenId,
          label: leg.label,
          observedAt: new Date().toISOString(),
          requestedShares: shares,
          soldShares: 0,
          error: error instanceof Error ? error.message : String(error),
        });
      }
    }
  }

  const entryCash = entryCashByLeg.reduce((sum, value) => sum + value, 0);
  const entryFees = entryFeesByLeg.reduce((sum, value) => sum + value, 0);
  const matrix = permutedPayoutMatrix(options.side, riskReport.executionOrder);
  const pnl = computePaperBasketPnl({
    fills: completeFills,
    sold: soldByLeg,
    entryCash,
    entryFees,
    unwindCash,
    unwindFees,
    gasUsd: options.gasUsd,
    payoutMatrix: matrix,
  });
  const complete = completeFills.every((value) => value >= targetShares - EPSILON);
  result.status = complete ? "paper_complete" : "paper_unhedged";
  result.finishedAt = new Date().toISOString();
  result.fills = completeFills;
  result.sold = soldByLeg;
  result.entryCash = entryCash;
  result.entryFees = entryFees;
  result.unwindCash = unwindCash;
  result.unwindFees = unwindFees;
  result.pnl = pnl;
  result.maxLossBreached = pnl.worstPnl < -options.maxLossUsd - EPSILON;
  return result;
}

async function main(argv = process.argv.slice(2)) {
  const options = parsePaperArgs(argv);
  if (options.help) {
    console.log("Usage: node scripts/paper-execute-complete-set.mjs --event-slug SLUG [options]");
    console.log("  --side yes|no");
    console.log("  --target-shares N");
    console.log("  --max-loss-usd 50");
    console.log("  --max-capital-usd 500");
    console.log("  --entry-tolerance-cents 0.5");
    console.log("  --unwind-slippage-cents 0.5");
    console.log("  --leg-delay-ms 1000");
    console.log("  --unwind-delay-ms 500");
    console.log("  --max-book-age-ms 15000");
    console.log("  --gas-usd 0.02");
    console.log("  --size-step 0.01");
    console.log("  --paper-skip-leg N    Paper-only fault injection; 0-based execution leg");
    console.log("  --output PATH");
    return;
  }
  if (!options.eventSlug) throw new Error("--event-slug is required");
  if ((process.env.HTTPS_PROXY || process.env.HTTP_PROXY) && process.env.NODE_USE_ENV_PROXY !== "1") {
    console.warn("warning: NODE_USE_ENV_PROXY is not 1; requests may bypass the proxy");
  }
  const result = await executeLivePaperBasket(options);
  const json = `${JSON.stringify(result, null, 2)}\n`;
  if (options.output) {
    const outputPath = path.resolve(options.output);
    await fs.mkdir(path.dirname(outputPath), { recursive: true });
    await fs.writeFile(outputPath, json, "utf8");
    console.log(`wrote ${outputPath}`);
  } else {
    process.stdout.write(json);
  }
  console.log(`${result.status}: attempts=${result.attempts.length} fills=[${(result.fills ?? []).join(",")}] ` +
    `pnl=${result.pnl?.worstPnl?.toFixed(4) ?? "n/a"} breached=${result.maxLossBreached ?? "n/a"}`);
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
