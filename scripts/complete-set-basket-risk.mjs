#!/usr/bin/env node

/**
 * Non-atomic complete-set basket risk analysis and a mock-only executor.
 *
 * This module never signs or submits real orders. The mock executor requires an
 * adapter with mode === "mock" and fails closed for every other mode.
 */

const EPSILON = 1e-9;

export function feeForFill(shares, price, feeRate) {
  if (![shares, price, feeRate].every(Number.isFinite) || shares < 0 || price < 0 || feeRate < 0) {
    throw new Error("invalid fee inputs");
  }
  return shares * feeRate * price * (1 - price);
}

export function computeHoldingYield({
  profitUsd,
  capitalUsd,
  endDate,
  now = Date.now(),
  conversionPath = "hold_to_resolution",
  expectedHoldDays = null,
}) {
  if (!Number.isFinite(profitUsd) || !Number.isFinite(capitalUsd) || capitalUsd <= 0) {
    throw new Error("profitUsd and capitalUsd are invalid");
  }
  const endMs = endDate == null ? NaN : Date.parse(endDate);
  const holdDays = Number.isFinite(endMs) ? Math.max(0, (endMs - now) / 86_400_000) : null;
  const terminalRoi = profitUsd / capitalUsd;
  const capitalLocked = conversionPath === "hold_to_resolution";
  const annualizedRoiAtEnd = capitalLocked && holdDays != null && holdDays > 0 && 1 + terminalRoi > 0
    ? Math.pow(1 + terminalRoi, 365 / holdDays) - 1
    : null;
  const expectedAnnualizedRoi = capitalLocked && Number.isFinite(expectedHoldDays) && expectedHoldDays > 0 && 1 + terminalRoi > 0
    ? Math.pow(1 + terminalRoi, 365 / expectedHoldDays) - 1
    : null;
  return {
    conversionPath,
    capitalLocked,
    endDate: Number.isFinite(endMs) ? new Date(endMs).toISOString() : null,
    maxHoldDays: holdDays,
    capitalLockupDays: capitalLocked ? holdDays : 0,
    terminalRoi,
    annualizedRoiAtEnd,
    expectedHoldDays: Number.isFinite(expectedHoldDays) ? expectedHoldDays : null,
    expectedAnnualizedRoi,
    expectedProfitPerDay: capitalLocked && Number.isFinite(expectedHoldDays) && expectedHoldDays > 0 ? profitUsd / expectedHoldDays : null,
    profitPerLockupDay: capitalLocked && holdDays != null && holdDays > 0 ? profitUsd / holdDays : null,
  };
}
export function payoutMatrixFor(kind, legCount) {
  if (!Number.isInteger(legCount) || legCount < 2 || legCount > 256) {
    throw new Error("legCount must be an integer between 2 and 256");
  }
  if (kind === "yes") {
    return Array.from({ length: legCount }, (_, winner) =>
      Array.from({ length: legCount }, (_, leg) => leg === winner ? 1 : 0));
  }
  if (kind === "no") {
    return Array.from({ length: legCount }, (_, winner) =>
      Array.from({ length: legCount }, (_, leg) => leg === winner ? 0 : 1));
  }
  throw new Error(`unsupported basket kind: ${kind}`);
}

export function orderLegsForSequentialExecution(legs, payoutMatrix) {
  validateLegs(legs);
  validatePayoutMatrix(payoutMatrix, legs.length);
  const costPerShare = (leg) => (leg.entryCashPerShare ?? leg.entryPrice) +
    (leg.entryFeePerShare ?? feeForFill(1, leg.entryPrice, leg.entryFeeRate));
  const order = legs.map((leg, index) => ({ leg, index, cost: costPerShare(leg) }))
    .sort((a, b) => a.cost - b.cost || a.index - b.index)
    .map((item) => item.index);
  return {
    order,
    legs: order.map((index) => legs[index]),
    payoutMatrix: payoutMatrix.map((row) => order.map((index) => row[index])),
    rationale: "cheapest legs first so a late failure leaves the smallest unhedged cost",
  };
}
function validateLegs(legs) {
  if (!Array.isArray(legs) || legs.length < 2) throw new Error("at least two legs are required");
  const tokenIds = new Set();
  for (const [index, leg] of legs.entries()) {
    if (!leg || !leg.tokenId) throw new Error(`leg ${index} is missing tokenId`);
    if (tokenIds.has(leg.tokenId)) throw new Error(`duplicate tokenId: ${leg.tokenId}`);
    tokenIds.add(leg.tokenId);
    for (const field of ["entryPrice", "entryFeeRate", "bidPrice", "bidFeeRate"]) {
      if (!Number.isFinite(leg[field])) throw new Error(`leg ${index} has invalid ${field}`);
    }
    if (leg.entryPrice < 0 || leg.entryPrice >= 1) throw new Error(`leg ${index} entryPrice out of range`);
    if (leg.bidPrice < 0 || leg.bidPrice >= 1) throw new Error(`leg ${index} bidPrice out of range`);
    if (leg.entryFeeRate < 0 || leg.bidFeeRate < 0) throw new Error(`leg ${index} fee rate is negative`);
    if (leg.bidDepth != null && (!Number.isFinite(leg.bidDepth) || leg.bidDepth < 0)) {
      throw new Error(`leg ${index} has invalid bidDepth`);
    }
  }
}

function validatePayoutMatrix(payoutMatrix, legCount) {
  if (!Array.isArray(payoutMatrix) || payoutMatrix.length !== legCount) {
    throw new Error("payoutMatrix row count must equal leg count");
  }
  for (const row of payoutMatrix) {
    if (!Array.isArray(row) || row.length !== legCount || row.some((value) => !Number.isFinite(value) || value < 0)) {
      throw new Error("payoutMatrix contains an invalid row");
    }
  }
}

function resolutionWorst(fills, payoutMatrix) {
  let worstPayout = Number.POSITIVE_INFINITY;
  let worstWinner = null;
  for (const [winner, row] of payoutMatrix.entries()) {
    let payout = 0;
    for (let leg = 0; leg < fills.length; leg += 1) payout += fills[leg] * row[leg];
    if (payout < worstPayout - EPSILON) {
      worstPayout = payout;
      worstWinner = winner;
    }
  }
  return { payout: Number.isFinite(worstPayout) ? worstPayout : 0, winner: worstWinner };
}

function sellAtBid(leg, shares, liquidationSlippageCents) {
  const requested = Math.max(0, shares);
  const available = leg.bidDepth == null ? requested : Math.max(0, leg.bidDepth);
  const soldShares = Math.min(requested, available);
  const exitPrice = Math.max(0, leg.bidPrice - liquidationSlippageCents / 100);
  const fee = feeForFill(soldShares, exitPrice, leg.bidFeeRate);
  return {
    soldShares,
    exitPrice,
    fee,
    proceeds: soldShares * exitPrice - fee,
  };
}

/**
 * Evaluate one concrete partial-fill state under three possible failure
 * policies: hold to resolution, flatten every filled leg at the current bid,
 * or sell only the excess above the largest complete set.
 */
export function analyzeLongBasketFills({
  legs,
  fills,
  payoutMatrix,
  gasUsd = 0,
  liquidationSlippageCents = 0,
}) {
  validateLegs(legs);
  if (!Array.isArray(fills) || fills.length !== legs.length) throw new Error("fills must match leg count");
  if (fills.some((shares) => !Number.isFinite(shares) || shares < 0)) throw new Error("fills contain an invalid share count");
  if (!Number.isFinite(gasUsd) || gasUsd < 0) throw new Error("gasUsd is invalid");
  if (!Number.isFinite(liquidationSlippageCents) || liquidationSlippageCents < 0) {
    throw new Error("liquidationSlippageCents is invalid");
  }
  validatePayoutMatrix(payoutMatrix, legs.length);

  let entryCash = 0;
  let entryFees = 0;
  for (let index = 0; index < legs.length; index += 1) {
    const leg = legs[index];
    entryCash += fills[index] * (leg.entryCashPerShare ?? leg.entryPrice);
    entryFees += fills[index] * (leg.entryFeePerShare ?? feeForFill(1, leg.entryPrice, leg.entryFeeRate));
  }
  const entryCost = entryCash + entryFees;
  const holdResolution = resolutionWorst(fills, payoutMatrix);
  const holdPnl = holdResolution.payout - entryCost - gasUsd;

  const flattenSales = legs.map((leg, index) => sellAtBid(leg, fills[index], liquidationSlippageCents));
  const flattenRemaining = legs.map((_, index) => fills[index] - flattenSales[index].soldShares);
  const flattenResolution = resolutionWorst(flattenRemaining, payoutMatrix);
  const flattenPnl = flattenSales.reduce((sum, sale) => sum + sale.proceeds, 0) +
    flattenResolution.payout - entryCost - gasUsd;

  const pairedShares = Math.min(...fills);
  const excessSales = legs.map((leg, index) =>
    sellAtBid(leg, Math.max(0, fills[index] - pairedShares), liquidationSlippageCents));
  const excessRemaining = legs.map((_, index) => fills[index] - excessSales[index].soldShares);
  const excessResolution = resolutionWorst(excessRemaining, payoutMatrix);
  const sellExcessPnl = excessSales.reduce((sum, sale) => sum + sale.proceeds, 0) +
    excessResolution.payout - entryCost - gasUsd;

  const policies = [
    { policy: "hold", pnl: holdPnl, resolutionPayout: holdResolution.payout, winner: holdResolution.winner, sales: [] },
    { policy: "flatten_all", pnl: flattenPnl, resolutionPayout: flattenResolution.payout, winner: flattenResolution.winner, sales: flattenSales },
    { policy: "sell_excess_only", pnl: sellExcessPnl, resolutionPayout: excessResolution.payout, winner: excessResolution.winner, sales: excessSales },
  ];
  policies.sort((a, b) => a.pnl - b.pnl);
  const worst = policies[0];

  return {
    fills: [...fills],
    entryCash,
    entryFees,
    entryCost,
    pairedShares,
    excessShares: fills.map((shares) => Math.max(0, shares - pairedShares)),
    holdPnl,
    flattenAllPnl: flattenPnl,
    sellExcessPnl,
    worstPnl: worst.pnl,
    worstPolicy: worst.policy,
    worstWinner: worst.winner,
    worstResolutionPayout: worst.resolutionPayout,
    worstSales: worst.sales,
    flattenAllResidualShares: flattenRemaining,
    sellExcessResidualShares: excessRemaining,
    residualUnsoldShares: worst.policy === "flatten_all"
      ? flattenRemaining
      : worst.policy === "sell_excess_only" ? excessRemaining : [...fills],
    capitalCommittedUsd: entryCost + gasUsd,
  };
}

function pushScenario(target, seen, fills) {
  const key = fills.map((value) => Number(value).toFixed(6)).join("|");
  if (!seen.has(key)) {
    seen.add(key);
    target.push([...fills]);
  }
}

/**
 * Generate adversarial fill states. Small baskets are enumerated over every
 * combination of zero, partial and full fills. Larger baskets use a bounded
 * set of stress patterns and are marked non-exhaustive.
 */
export function buildFillScenarios(legCount, {
  mode = "parallel",
  targetShares = 1,
  partialFractions = [0.25, 0.5, 0.75],
  maxExhaustiveLegs = 6,
} = {}) {
  if (!Number.isInteger(legCount) || legCount < 2) throw new Error("legCount must be at least two");
  if (!Number.isFinite(targetShares) || targetShares <= 0) throw new Error("targetShares must be positive");
  if (!Array.isArray(partialFractions) || partialFractions.length === 0) throw new Error("partialFractions is empty");
  const fractions = [...new Set(partialFractions)]
    .filter((value) => Number.isFinite(value) && value > 0 && value < 1)
    .sort((a, b) => a - b);
  if (fractions.length === 0) throw new Error("partialFractions has no value in (0,1)");
  const scenarios = [];
  const seen = new Set();

  const add = (fractionSource) => pushScenario(scenarios, seen,
    fractionSource.map((fraction) => Math.max(0, Math.min(1, fraction)) * targetShares));

  if (mode === "sequential") {
    add(new Array(legCount).fill(1));
    for (let failedIndex = 0; failedIndex < legCount; failedIndex += 1) {
      for (const failedState of [0, ...fractions]) {
        const fills = new Array(legCount).fill(0);
        for (let index = 0; index < failedIndex; index += 1) fills[index] = 1;
        fills[failedIndex] = failedState;
        add(fills);
      }
    }
    return { scenarios, exhaustive: true, testedStatesPerLeg: 1 + fractions.length };
  }

  if (legCount <= maxExhaustiveLegs) {
    const states = [0, ...fractions, 1];
    const current = new Array(legCount).fill(0);
    const visit = (index) => {
      if (index === legCount) {
        if (mode === "sequential") {
          const firstPartial = current.findIndex((value) => value < 1 - EPSILON);
          if (firstPartial >= 0 && current.slice(firstPartial + 1).some((value) => value > EPSILON)) return;
        }
        add(current);
        return;
      }
      for (const state of states) {
        current[index] = state;
        visit(index + 1);
      }
    };
    visit(0);
    return { scenarios, exhaustive: true, testedStatesPerLeg: states.length };
  }

  const all = new Array(legCount).fill(1);
  add(all);
  add(new Array(legCount).fill(0));
  for (let failed = 0; failed < legCount; failed += 1) {
    const oneFailed = [...all];
    oneFailed[failed] = 0;
    add(oneFailed);
    const oneFilled = new Array(legCount).fill(0);
    oneFilled[failed] = 1;
    add(oneFilled);
    for (const fraction of fractions) {
      const partialOnly = new Array(legCount).fill(0);
      partialOnly[failed] = fraction;
      add(partialOnly);
      const partialFailed = [...all];
      partialFailed[failed] = fraction;
      add(partialFailed);
    }
  }
  for (const fraction of fractions) add(new Array(legCount).fill(fraction));

  return {
    scenarios,
    exhaustive: false,
    testedStatesPerLeg: 2 + fractions.length * 2,
  };
}

function entryHardLossBound(legs, targetShares, gasUsd) {
  let maxLoss = gasUsd;
  for (const leg of legs) {
    maxLoss += targetShares * (leg.entryCashPerShare ?? leg.entryPrice) +
      targetShares * (leg.entryFeePerShare ?? feeForFill(1, leg.entryPrice, leg.entryFeeRate));
  }
  return maxLoss;
}

export function analyzeBasketExecutionRisk({
  legs,
  targetShares,
  payoutMatrix,
  gasUsd = 0,
  liquidationSlippageCents = 0,
  executionMode = "parallel",
  maxLossUsd = Number.POSITIVE_INFINITY,
  maxCapitalUsd = Number.POSITIVE_INFINITY,
  minProfitUsd = 0,
  failClosedOnNonExhaustive = true,
}) {
  if (!Number.isFinite(targetShares) || targetShares <= 0) throw new Error("targetShares must be positive");
  validateLegs(legs);
  validatePayoutMatrix(payoutMatrix, legs.length);
  if (maxLossUsd !== Number.POSITIVE_INFINITY && (!Number.isFinite(maxLossUsd) || maxLossUsd < 0)) {
    throw new Error("maxLossUsd is invalid");
  }
  if (maxCapitalUsd !== Number.POSITIVE_INFINITY && (!Number.isFinite(maxCapitalUsd) || maxCapitalUsd < 0)) {
    throw new Error("maxCapitalUsd is invalid");
  }
  if (!Number.isFinite(minProfitUsd) || minProfitUsd < 0) throw new Error("minProfitUsd is invalid");

  const generated = buildFillScenarios(legs.length, { mode: executionMode, targetShares });
  const evaluated = generated.scenarios.map((fills) => analyzeLongBasketFills({
    legs,
    fills,
    payoutMatrix,
    gasUsd,
    liquidationSlippageCents,
  }));
  evaluated.sort((a, b) => a.worstPnl - b.worstPnl);
  const worst = evaluated[0];
  const full = analyzeLongBasketFills({
    legs,
    fills: new Array(legs.length).fill(targetShares),
    payoutMatrix,
    gasUsd,
    liquidationSlippageCents,
  });
  const capitalAtRiskUsd = entryHardLossBound(legs, targetShares, gasUsd);
  const maxObservedLossUsd = Math.max(0, -worst.worstPnl);
  const lossBudgetUsd = generated.exhaustive ? maxObservedLossUsd : capitalAtRiskUsd;
  const allFilledWorstPnl = full.holdPnl;
  const profitGatePassed = allFilledWorstPnl >= minProfitUsd - EPSILON;
  const capitalGatePassed = capitalAtRiskUsd <= maxCapitalUsd + EPSILON;
  const lossGatePassed = lossBudgetUsd <= maxLossUsd + EPSILON;
  const coverageGatePassed = generated.exhaustive || !failClosedOnNonExhaustive;

  let decision = "eligible_for_mock";
  if (!profitGatePassed) decision = "reject_no_profitable_complete_fill";
  else if (!capitalGatePassed) decision = "reject_capital_bound";
  else if (!lossGatePassed) decision = "reject_loss_budget";
  else if (!coverageGatePassed) decision = "reject_non_exhaustive";

  const costPerShare = legs.reduce((sum, leg) =>
    sum + (leg.entryCashPerShare ?? leg.entryPrice) +
      (leg.entryFeePerShare ?? leg.entryFeeRate * leg.entryPrice * (1 - leg.entryPrice)), 0);
  const maxSharesForCapitalBudget = costPerShare > 0
    ? Math.max(0, (maxCapitalUsd - gasUsd) / costPerShare)
    : 0;
  const maxSharesForLossBudget = maxObservedLossUsd > 0
    ? targetShares * maxLossUsd / maxObservedLossUsd
    : Number.POSITIVE_INFINITY;

  return {
    targetShares,
    legCount: legs.length,
    executionMode,
    scenarioCount: evaluated.length,
    exhaustive: generated.exhaustive,
    testedStatesPerLeg: generated.testedStatesPerLeg,
    allFilled: full,
    worstScenario: worst,
    maxObservedLossUsd,
    capitalAtRiskUsd,
    lossBudgetUsd,
    lossBudgetBasis: generated.exhaustive ? "exhaustive_observed" : "conservative_capital_bound",
    maxSharesForLossBudget,
    maxSharesForCapitalBudget,
    maxLossUsd,
    maxCapitalUsd,
    minProfitUsd,
    profitGatePassed,
    capitalGatePassed,
    lossGatePassed,
    coverageGatePassed,
    decision,
    profitToWorstObservedLossRatio: maxObservedLossUsd > 0 ? allFilledWorstPnl / maxObservedLossUsd : null,
    profitToCapitalRatio: capitalAtRiskUsd > 0 ? allFilledWorstPnl / capitalAtRiskUsd : null,
  };
}

function normalizeTerminalOrder(order, requestedShares) {
  if (!order || typeof order !== "object") throw new Error("mock adapter returned an invalid order result");
  const filledShares = Number(order.filledShares ?? 0);
  if (!Number.isFinite(filledShares) || filledShares < 0) throw new Error("mock order has invalid filledShares");
  return {
    ...order,
    filledShares: Math.min(filledShares, requestedShares),
    averagePrice: Number(order.averagePrice ?? 0),
    feeUsd: Number(order.feeUsd ?? 0),
  };
}

async function placeAndConfirmBuy(adapter, leg, shares) {
  const placed = await adapter.placeLimitBuy({
    tokenId: leg.tokenId,
    price: leg.entryPrice,
    shares,
  });
  if (!placed?.orderId) throw new Error(`mock adapter did not return orderId for ${leg.tokenId}`);
  let terminal = normalizeTerminalOrder(
    await adapter.waitForOrder(placed.orderId, { timeoutMs: adapter.orderTimeoutMs ?? 5_000 }),
    shares,
  );
  if (terminal.filledShares < shares - EPSILON) {
    const cancelled = await adapter.cancelOrder(placed.orderId);
    const finalFilled = Math.max(
      terminal.filledShares,
      Number(cancelled?.filledShares ?? 0),
    );
    terminal = normalizeTerminalOrder({
      ...terminal,
      ...cancelled,
      status: cancelled?.status ?? terminal.status,
      filledShares: finalFilled,
    }, shares);
  }
  return { placed, terminal };
}

/**
 * Execute a sequential all-or-continue mock basket. It waits for each leg
 * before sending the next, cancels the failed/partial leg, and optionally
 * flattens every filled leg at the adapter-provided bid.
 */
export async function executeMockSequentialBasket({
  legs,
  adapter,
  targetShares,
  payoutMatrix,
  gasUsd = 0,
  maxLossUsd,
  maxCapitalUsd = Number.POSITIVE_INFINITY,
  unwind = true,
  autoOrderSequential = true,
  mode = "mock",
}) {
  if (mode !== "mock") throw new Error("real execution is disabled; only mode=mock is accepted");
  if (!adapter || adapter.mode !== "mock") throw new Error("adapter.mode must be mock");
  if (typeof adapter.placeLimitBuy !== "function" || typeof adapter.waitForOrder !== "function" ||
      typeof adapter.cancelOrder !== "function") {
    throw new Error("mock adapter is missing required order methods");
  }
  validateLegs(legs);
  validatePayoutMatrix(payoutMatrix, legs.length);
  let executionOrder = legs.map((_, index) => index);
  if (autoOrderSequential) {
    const ordered = orderLegsForSequentialExecution(legs, payoutMatrix);
    legs = ordered.legs;
    payoutMatrix = ordered.payoutMatrix;
    executionOrder = ordered.order;
  }
  if (!Number.isFinite(targetShares) || targetShares <= 0) throw new Error("targetShares must be positive");
  if (!Number.isFinite(maxLossUsd) || maxLossUsd < 0) throw new Error("maxLossUsd is invalid");
  if (maxCapitalUsd !== Number.POSITIVE_INFINITY && (!Number.isFinite(maxCapitalUsd) || maxCapitalUsd < 0)) {
    throw new Error("maxCapitalUsd is invalid");
  }

  const preflight = analyzeBasketExecutionRisk({
    legs,
    targetShares,
    payoutMatrix,
    gasUsd,
    liquidationSlippageCents: 0,
    executionMode: "sequential",
    maxLossUsd,
    maxCapitalUsd,
    minProfitUsd: 1e-9,
  });
  const report = {
    mode: "mock",
    startedAt: new Date().toISOString(),
    targetShares,
    executionOrder,
    preflight,
    orders: [],
    originalFills: new Array(legs.length).fill(0),
    fills: new Array(legs.length).fill(0),
    completedAllLegs: false,
    unwindAttempted: false,
    unwind: [],
    status: "not_started",
  };
  if (preflight.decision !== "eligible_for_mock") {
    report.status = "rejected_preflight";
    report.finishedAt = new Date().toISOString();
    return report;
  }

  for (let index = 0; index < legs.length; index += 1) {
    const leg = legs[index];
    let result;
    try {
      result = await placeAndConfirmBuy(adapter, leg, targetShares);
    } catch (error) {
      report.orders.push({
        index,
        tokenId: leg.tokenId,
        status: "error",
        error: error instanceof Error ? error.message : String(error),
      });
      report.status = "order_error";
      break;
    }
    const filledShares = result.terminal.filledShares;
    report.originalFills[index] = filledShares;
    report.fills[index] = filledShares;
    report.orders.push({
      index,
      tokenId: leg.tokenId,
      orderId: result.placed.orderId,
      requestedShares: targetShares,
      status: result.terminal.status,
      filledShares,
      averagePrice: result.terminal.averagePrice,
      feeUsd: result.terminal.feeUsd,
    });
    if (filledShares < targetShares - EPSILON) break;
    if (index === legs.length - 1) report.completedAllLegs = true;
  }

  if (!report.completedAllLegs && unwind) {
    report.unwindAttempted = true;
    if (typeof adapter.sellAtBid !== "function") throw new Error("mock adapter is missing sellAtBid");
    for (let index = 0; index < legs.length; index += 1) {
      const shares = report.fills[index];
      if (shares <= EPSILON) continue;
      const leg = legs[index];
      try {
        const sale = normalizeTerminalOrder(await adapter.sellAtBid({
          tokenId: leg.tokenId,
          shares,
          limitPrice: leg.bidPrice,
        }), shares);
        report.unwind.push({
          index,
          tokenId: leg.tokenId,
          requestedShares: shares,
          status: sale.status,
          soldShares: sale.filledShares,
          averagePrice: sale.averagePrice,
          feeUsd: sale.feeUsd,
        });
        report.fills[index] -= sale.filledShares;
      } catch (error) {
        report.unwind.push({
          index,
          tokenId: leg.tokenId,
          requestedShares: shares,
          status: "error",
          soldShares: 0,
          error: error instanceof Error ? error.message : String(error),
        });
      }
    }
  }

  let entryCostUsd = 0;
  for (const order of report.orders) {
    if (order.status === "error") continue;
    const fillPrice = order.averagePrice > 0 ? order.averagePrice : legs[order.index].entryPrice;
    const cash = order.filledShares * fillPrice;
    const fee = order.feeUsd > 0
      ? order.feeUsd
      : feeForFill(order.filledShares, fillPrice, legs[order.index].entryFeeRate);
    entryCostUsd += cash + fee;
  }
  let unwindProceedsUsd = 0;
  for (const sale of report.unwind) {
    if (sale.status === "error") continue;
    const fillPrice = sale.averagePrice > 0 ? sale.averagePrice : legs[sale.index].bidPrice;
    const cash = sale.soldShares * fillPrice;
    const fee = sale.feeUsd > 0 ? sale.feeUsd : feeForFill(sale.soldShares, fillPrice, legs[sale.index].bidFeeRate);
    unwindProceedsUsd += cash - fee;
  }
  const residualResolution = resolutionWorst(report.fills, payoutMatrix);
  const actualWorstPnlUsd = unwindProceedsUsd + residualResolution.payout - entryCostUsd - gasUsd;
  const remainingRisk = analyzeLongBasketFills({
    legs,
    fills: report.fills,
    payoutMatrix,
    gasUsd: 0,
    liquidationSlippageCents: 0,
  });

  report.entryCostUsd = entryCostUsd;
  report.unwindProceedsUsd = unwindProceedsUsd;
  report.residualResolutionPayoutUsd = residualResolution.payout;
  report.residualWorstWinner = residualResolution.winner;
  report.remaining = remainingRisk;
  report.lockedProfitUsd = report.completedAllLegs
    ? residualResolution.payout - entryCostUsd - gasUsd
    : null;
  report.worstCasePnlUsd = actualWorstPnlUsd;
  report.maxLossBreached = actualWorstPnlUsd < -maxLossUsd - EPSILON;
  if (report.completedAllLegs) report.status = "complete";
  else if (report.fills.every((shares) => shares <= EPSILON)) report.status = "failed_closed";
  else report.status = "unhedged";
  report.finishedAt = new Date().toISOString();
  return report;
}