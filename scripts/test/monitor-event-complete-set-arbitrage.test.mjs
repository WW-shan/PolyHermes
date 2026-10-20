import test from "node:test";
import assert from "node:assert/strict";

import { compactRow, parseMonitorArgs, proxySums, updateConfirmationStats } from "../monitor-event-complete-set-arbitrage.mjs";

function rawMarket(id, yesToken, noToken, bestAsk) {
  return {
    id,
    conditionId: `condition-${id}`,
    question: `Question ${id}`,
    active: true,
    closed: false,
    enableOrderBook: true,
    acceptingOrders: true,
    negRisk: true,
    negRiskMarketID: "nr-1",
    orderMinSize: 5,
    orderPriceMinTickSize: 0.01,
    feesEnabled: false,
    bestAsk,
    clobTokenIds: JSON.stringify([yesToken, noToken]),
    outcomes: JSON.stringify(["Yes", "No"]),
    events: [{ id: "event-1", slug: "event-1", title: "Event 1" }],
  };
}

test("monitor defaults to a non-overwriting timestamped output directory", () => {
  const options = parseMonitorArgs([]);
  assert.match(options.outputDir, /complete-set-monitor-/);
  assert.equal(options.pollIntervalMs, 15_000);
  assert.equal(options.discoveryIntervalMs, 600_000);
  assert.equal(options.durationMs, 86_400_000);
  assert.equal(options.minEdgeCents, 0.5);
});

test("binary proxy uses YES top prices and its complement", () => {
  const group = {
    type: "binary",
    markets: [{ bestAsk: 0.52, bestBid: 0.49 }],
  };
  const proxy = proxySums(group);
  assert.ok(Math.abs(proxy.ask - 1.03) < 1e-9);
  assert.ok(Math.abs(proxy.bid - 0.97) < 1e-9);
});

test("NegRisk proxy sums every executable YES ask", () => {
  const group = { type: "negRisk", negRiskMarketId: "nr-1", discoveryTag: "politics" };
  const event = {
    id: "event-1",
    slug: "event-1",
    title: "Event 1",
    active: true,
    closed: false,
    negRisk: true,
    enableNegRisk: true,
    markets: [
      rawMarket("m1", "y1", "n1", 0.4),
      rawMarket("m2", "y2", "n2", 0.5),
    ],
  };
  const proxy = proxySums(group, event);
  assert.ok(Math.abs(proxy.ask - 0.9) < 1e-9);
  assert.equal(proxy.bid, null);
  assert.equal(proxy.validation.ok, true);
});

test("NegRisk proxy exposes the sum of YES bids for NO-basket prefiltering", () => {
  const group = { type: "negRisk", negRiskMarketId: "nr-1", discoveryTag: "politics" };
  const m1 = rawMarket("m1", "y1", "n1", 0.4); m1.bestBid = 0.39;
  const m2 = rawMarket("m2", "y2", "n2", 0.5); m2.bestBid = 0.49;
  const event = {
    id: "event-1", slug: "event-1", title: "Event 1", active: true, closed: false,
    negRisk: true, enableNegRisk: true, markets: [m1, m2],
  };
  const proxy = proxySums(group, event);
  assert.ok(Math.abs(proxy.bid - 0.88) < 1e-9);
});
test("NegRisk proxy refuses an incomplete event", () => {
  const group = { type: "negRisk", negRiskMarketId: "nr-1", discoveryTag: "politics" };
  const event = {
    id: "event-1",
    slug: "event-1",
    title: "Event 1",
    active: true,
    closed: false,
    negRisk: true,
    enableNegRisk: true,
    markets: [rawMarket("m1", "y1", "n1", 0.4)],
  };
  const proxy = proxySums(group, event);
  assert.equal(proxy.ask, null);
  assert.equal(proxy.validation.ok, false);
});
test("compact rows retain execution risk and confirmation stats update fail-closed", () => {
  const row = {
    groupKey: "neg:test",
    groupType: "negRisk",
    eventTitle: "Event",
    eventSlug: "event",
    outcomeCount: 3,
    status: "ok",
    ask: {
      available: true,
      actionable: true,
      topAskSum: 0.98,
      edgeCents: 0.7,
      reason: "actionable",
      executionRisk: {
        capitalAtRiskUsd: 500,
        maxObservedLossUsd: 42,
        exhaustive: true,
        payoutKind: "yes",
        worstScenario: { worstPnl: -42, worstPolicy: "hold", fills: [10, 10, 0] },
      },
    },
    noBasket: {},
    actionable: true,
  };
  const compact = compactRow(row);
  assert.equal(compact.askRiskCapitalUsd, 500);
  assert.equal(compact.askRiskMaxObservedLossUsd, 42);
  assert.deepEqual(compact.askRiskWorstFills, [10, 10, 0]);
  assert.equal(compact.askRiskExhaustive, true);

  const summary = {
    confirmedEpisodes: 0,
    confirmationAttempts: 0,
    failedConfirmations: 0,
    confirmationFailureReasons: {},
    lastConfirmationAt: null,
    lastConfirmedAt: null,
    maxConfirmedEdgeCents: null,
    maxConfirmedProfit: null,
    maxConfirmedCapitalUsd: null,
    maxConfirmedRiskLossUsd: null,
  };
  updateConfirmationStats(summary, {
    confirmed: true,
    side: "ask",
    edgeCents: 0.7,
    profit: 3,
    row: compact,
  });
  assert.equal(summary.confirmationAttempts, 1);
  assert.equal(summary.confirmedEpisodes, 1);
  assert.equal(summary.maxConfirmedCapitalUsd, 500);
  assert.equal(summary.maxConfirmedRiskLossUsd, 42);

  updateConfirmationStats(summary, { confirmed: false, reason: "stale_book" });
  assert.equal(summary.failedConfirmations, 1);
  assert.equal(summary.confirmationFailureReasons.stale_book, 1);
});
