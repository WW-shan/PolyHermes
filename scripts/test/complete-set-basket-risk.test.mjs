import test from "node:test";
import assert from "node:assert/strict";

import {
  analyzeBasketExecutionRisk,
  analyzeLongBasketFills,
  buildFillScenarios,
  computeHoldingYield,
  executeMockSequentialBasket,
  feeForFill,
  payoutMatrixFor,
} from "../complete-set-basket-risk.mjs";

const close = (actual, expected, tolerance = 1e-9) =>
  assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} != ${expected}`);

function leg(tokenId, extra = {}) {
  return {
    tokenId,
    entryPrice: 0.30,
    entryFeeRate: 0,
    bidPrice: 0.20,
    bidFeeRate: 0,
    bidDepth: 100,
    ...extra,
  };
}

class ScriptedMockAdapter {
  constructor({ fills = {}, cancels = {}, bids = {} } = {}) {
    this.mode = "mock";
    this.fills = fills;
    this.cancels = cancels;
    this.bids = bids;
    this.orders = new Map();
    this.orderSequence = 0;
    this.placedBuys = [];
    this.sellCalls = [];
    this.cancelCalls = [];
  }

  async placeLimitBuy({ tokenId, price, shares }) {
    this.orderSequence += 1;
    const orderId = `order-${this.orderSequence}`;
    this.orders.set(orderId, { tokenId, price, shares });
    this.placedBuys.push({ orderId, tokenId, price, shares });
    return { orderId };
  }

  async waitForOrder(orderId) {
    const order = this.orders.get(orderId);
    const script = this.fills[order.tokenId] ?? { filledShares: order.shares, status: "filled" };
    return {
      status: script.status ?? "filled",
      filledShares: script.filledShares ?? order.shares,
      averagePrice: script.averagePrice ?? order.price,
      feeUsd: script.feeUsd ?? 0,
    };
  }

  async cancelOrder(orderId) {
    const order = this.orders.get(orderId);
    this.cancelCalls.push({ orderId, tokenId: order.tokenId });
    const script = this.cancels[order.tokenId] ?? this.fills[order.tokenId] ?? {};
    return {
      status: "cancelled",
      filledShares: script.cancelFilledShares ?? script.filledShares ?? 0,
      averagePrice: script.averagePrice ?? order.price,
      feeUsd: script.feeUsd ?? 0,
    };
  }

  async sellAtBid({ tokenId, shares, limitPrice }) {
    this.sellCalls.push({ tokenId, shares, limitPrice });
    const script = this.bids[tokenId] ?? { soldShares: shares };
    return {
      status: script.status ?? "filled",
      filledShares: script.soldShares ?? 0,
      averagePrice: script.averagePrice ?? limitPrice,
      feeUsd: script.feeUsd ?? 0,
    };
  }
}

test("official price-dependent fee formula is shared by entry and exit", () => {
  close(feeForFill(10, 0.4, 0.05), 0.12);
  close(feeForFill(5, 0.5, 0.05), 0.0625);
});

test("YES and NO payout matrices encode the complete-set resolution semantics", () => {
  assert.deepEqual(payoutMatrixFor("yes", 3), [
    [1, 0, 0],
    [0, 1, 0],
    [0, 0, 1],
  ]);
  assert.deepEqual(payoutMatrixFor("no", 3), [
    [0, 1, 1],
    [1, 0, 1],
    [1, 1, 0],
  ]);
});

test("a fully filled YES basket locks the complete-set payout", () => {
  const result = analyzeLongBasketFills({
    legs: [leg("a"), leg("b"), leg("c")],
    fills: [10, 10, 10],
    payoutMatrix: payoutMatrixFor("yes", 3),
    gasUsd: 0.02,
  });
  close(result.entryCost, 9);
  close(result.holdPnl, 0.98);
  close(result.worstPnl, -3.02);
  close(result.pairedShares, 10);
});

test("a fully filled NO basket pays n-1 per complete share", () => {
  const result = analyzeLongBasketFills({
    legs: [leg("a"), leg("b"), leg("c")],
    fills: [10, 10, 10],
    payoutMatrix: payoutMatrixFor("no", 3),
    gasUsd: 0,
  });
  close(result.entryCost, 9);
  close(result.holdPnl, 11);
});

test("one filled leg is directional and can lose its full cost if no exit exists", () => {
  const legs = [
    leg("a", { bidPrice: 0.20, bidDepth: 10 }),
    leg("b", { bidPrice: 0.20, bidDepth: 10 }),
    leg("c", { bidPrice: 0.20, bidDepth: 10 }),
  ];
  const result = analyzeLongBasketFills({
    legs,
    fills: [10, 0, 0],
    payoutMatrix: payoutMatrixFor("yes", 3),
    gasUsd: 0,
  });
  close(result.holdPnl, -3);
  close(result.flattenAllPnl, -1);
  close(result.worstPnl, -3);
  assert.equal(result.worstPolicy, "hold");
  assert.equal(result.worstWinner, 1);
});

test("flattening a partial basket records bid depth, fees and residual shares", () => {
  const legs = [
    leg("a", { bidPrice: 0.20, bidDepth: 4, bidFeeRate: 0.04 }),
    leg("b"),
    leg("c"),
  ];
  const result = analyzeLongBasketFills({
    legs,
    fills: [10, 0, 0],
    payoutMatrix: payoutMatrixFor("yes", 3),
    gasUsd: 0,
  });
  close(result.flattenAllPnl, -3 + (0.8 - 0.0256));
  close(result.flattenAllResidualShares[0], 6);
  assert.equal(result.worstPolicy, "hold");
});

test("execution risk enumerates partial-fill states and applies a hard loss bound", () => {
  const legs = [leg("a"), leg("b"), leg("c")];
  const result = analyzeBasketExecutionRisk({
    legs,
    targetShares: 10,
    payoutMatrix: payoutMatrixFor("yes", 3),
    gasUsd: 0,
    executionMode: "parallel",
    maxLossUsd: 10,
  });
  assert.equal(result.exhaustive, true);
  assert.equal(result.decision, "eligible_for_mock");
  close(result.allFilled.holdPnl, 1);
  close(result.maxObservedLossUsd, 6);
  close(result.capitalAtRiskUsd, 9);
  close(result.maxSharesForLossBudget, 10 * 10 / 6);
});

test("loss-budget gate rejects a trade whose worst notional exceeds the cap", () => {
  const legs = [leg("a"), leg("b"), leg("c")];
  const result = analyzeBasketExecutionRisk({
    legs,
    targetShares: 10,
    payoutMatrix: payoutMatrixFor("yes", 3),
    gasUsd: 0,
    maxLossUsd: 5,
  });
  assert.equal(result.decision, "reject_loss_budget");
  assert.equal(result.lossGatePassed, false);
});

test("sequential fill scenarios never continue after the first incomplete leg", () => {
  const generated = buildFillScenarios(3, {
    mode: "sequential",
    targetShares: 10,
    partialFractions: [0.5],
  });
  assert.equal(generated.exhaustive, true);
  for (const fills of generated.scenarios) {
    const firstIncomplete = fills.findIndex((value) => value < 10 - 1e-9);
    if (firstIncomplete >= 0) {
      assert.ok(fills.slice(firstIncomplete + 1).every((value) => value <= 1e-9));
    }
  }
});

test("mock executor completes all legs and reports locked profit", async () => {
  const adapter = new ScriptedMockAdapter();
  const report = await executeMockSequentialBasket({
    legs: [leg("a"), leg("b"), leg("c")],
    adapter,
    targetShares: 10,
    payoutMatrix: payoutMatrixFor("yes", 3),
    maxLossUsd: 20,
  });
  assert.equal(report.status, "complete");
  assert.equal(report.orders.length, 3);
  assert.equal(report.unwindAttempted, false);
  close(report.lockedProfitUsd, 1);
  close(report.worstCasePnlUsd, 1);
  assert.equal(adapter.cancelCalls.length, 0);
});

test("mock executor stops after a partial leg, cancels it and unwinds filled inventory", async () => {
  const adapter = new ScriptedMockAdapter({
    fills: {
      a: { status: "filled", filledShares: 10 },
      b: { status: "partial", filledShares: 4, averagePrice: 0.30 },
      c: { status: "filled", filledShares: 10 },
    },
    cancels: {
      b: { cancelFilledShares: 4, averagePrice: 0.30 },
    },
  });
  const report = await executeMockSequentialBasket({
    legs: [leg("a"), leg("b"), leg("c")],
    adapter,
    targetShares: 10,
    payoutMatrix: payoutMatrixFor("yes", 3),
    maxLossUsd: 20,
  });
  assert.equal(report.status, "failed_closed");
  assert.equal(adapter.placedBuys.length, 2);
  assert.deepEqual(adapter.placedBuys.map((order) => order.tokenId), ["a", "b"]);
  assert.equal(adapter.cancelCalls.length, 1);
  assert.equal(report.unwind.length, 2);
  close(report.entryCostUsd, 4.2);
  close(report.unwindProceedsUsd, 2.8);
  close(report.worstCasePnlUsd, -1.4);
  assert.equal(report.maxLossBreached, false);
});

test("mock executor marks an insufficient-liquidity unwind as unhedged", async () => {
  const adapter = new ScriptedMockAdapter({
    fills: {
      a: { status: "filled", filledShares: 10 },
      b: { status: "partial", filledShares: 4, averagePrice: 0.30 },
      c: { status: "filled", filledShares: 10 },
    },
    cancels: {
      b: { cancelFilledShares: 4, averagePrice: 0.30 },
    },
    bids: {
      b: { soldShares: 0 },
    },
  });
  const report = await executeMockSequentialBasket({
    legs: [leg("a"), leg("b"), leg("c")],
    adapter,
    targetShares: 10,
    payoutMatrix: payoutMatrixFor("yes", 3),
    maxLossUsd: 20,
  });
  assert.equal(report.status, "unhedged");
  close(report.fills[1], 4);
  close(report.worstCasePnlUsd, -2.2);
});

test("mock executor rejects live-mode adapters and loss-budget breaches before ordering", async () => {
  const liveAdapter = new ScriptedMockAdapter();
  liveAdapter.mode = "live";
  await assert.rejects(() => executeMockSequentialBasket({
    legs: [leg("a"), leg("b"), leg("c")],
    adapter: liveAdapter,
    targetShares: 10,
    payoutMatrix: payoutMatrixFor("yes", 3),
    maxLossUsd: 20,
  }), /adapter.mode must be mock/);

  const adapter = new ScriptedMockAdapter();
  const rejected = await executeMockSequentialBasket({
    legs: [leg("a"), leg("b"), leg("c")],
    adapter,
    targetShares: 10,
    payoutMatrix: payoutMatrixFor("yes", 3),
    maxLossUsd: 5,
  });
  assert.equal(rejected.status, "rejected_preflight");
  assert.equal(adapter.placedBuys.length, 0);
});

test("duplicate tokens fail closed before any mock order", async () => {
  const adapter = new ScriptedMockAdapter();
  await assert.rejects(() => executeMockSequentialBasket({
    legs: [leg("same"), leg("same"), leg("c")],
    adapter,
    targetShares: 10,
    payoutMatrix: payoutMatrixFor("yes", 3),
    maxLossUsd: 20,
  }), /duplicate tokenId/);
  assert.equal(adapter.placedBuys.length, 0);
});
test("explicit depth-derived entry cash and fees override the top-price approximation", () => {
  const legs = [
    leg("a", { entryPrice: 0.30, entryCashPerShare: 0.31, entryFeePerShare: 0.002 }),
    leg("b", { entryPrice: 0.30, entryCashPerShare: 0.31, entryFeePerShare: 0.002 }),
  ];
  const result = analyzeLongBasketFills({
    legs,
    fills: [10, 10],
    payoutMatrix: payoutMatrixFor("yes", 2),
    gasUsd: 0,
  });
  close(result.entryCash, 6.2);
  close(result.entryFees, 0.04);
  close(result.entryCost, 6.24);
});

test("sequential mock execution orders cheap legs first and permutes payout columns", async () => {
  const legs = [
    leg("expensive", { entryPrice: 0.50, bidPrice: 0.40 }),
    leg("cheap", { entryPrice: 0.10, bidPrice: 0.05 }),
    leg("middle", { entryPrice: 0.30, bidPrice: 0.20 }),
  ];
  const adapter = new ScriptedMockAdapter();
  const report = await executeMockSequentialBasket({
    legs,
    adapter,
    targetShares: 10,
    payoutMatrix: payoutMatrixFor("yes", 3),
    maxLossUsd: 25,
  });
  assert.deepEqual(report.executionOrder, [1, 2, 0]);
  assert.deepEqual(adapter.placedBuys.map((order) => order.tokenId), ["cheap", "middle", "expensive"]);
  assert.equal(report.status, "complete");
  close(report.lockedProfitUsd, 1);
});

test("maxCapitalUsd rejects a profitable basket whose committed cash exceeds the cap", () => {
  const result = analyzeBasketExecutionRisk({
    legs: [leg("a"), leg("b"), leg("c")],
    targetShares: 10,
    payoutMatrix: payoutMatrixFor("yes", 3),
    maxLossUsd: 10,
    maxCapitalUsd: 8,
  });
  assert.equal(result.decision, "reject_capital_bound");
  assert.equal(result.capitalGatePassed, false);
});
test("large sequential baskets use exact prefix scenarios instead of unordered one-leg failures", () => {
  const generated = buildFillScenarios(7, {
    mode: "sequential",
    targetShares: 10,
    partialFractions: [0.5],
  });
  assert.equal(generated.exhaustive, true);
  assert.equal(generated.scenarios.length, 15);
  for (const fills of generated.scenarios) {
    const firstIncomplete = fills.findIndex((value) => value < 10 - 1e-9);
    if (firstIncomplete >= 0) {
      assert.ok(fills.slice(firstIncomplete + 1).every((value) => value <= 1e-9));
      assert.ok(fills.slice(0, firstIncomplete).every((value) => Math.abs(value - 10) < 1e-9));
    }
  }
});
test("holding yield distinguishes immediate conversion from terminal capital lockup", () => {
  const now = Date.parse("2026-10-20T00:00:00Z");
  const locked = computeHoldingYield({
    profitUsd: 5,
    capitalUsd: 100,
    endDate: "2027-10-20T00:00:00Z",
    now,
    conversionPath: "hold_to_resolution",
  });
  assert.equal(locked.capitalLocked, true);
  close(locked.maxHoldDays, 365);
  close(locked.terminalRoi, 0.05);
  close(locked.annualizedRoiAtEnd, 0.05);

  const immediate = computeHoldingYield({
    profitUsd: 5,
    capitalUsd: 100,
    endDate: "2027-10-20T00:00:00Z",
    now,
    conversionPath: "merge_positions_immediately",
  });
  assert.equal(immediate.capitalLocked, false);
  assert.equal(immediate.annualizedRoiAtEnd, null);
  close(immediate.terminalRoi, 0.05);
});
