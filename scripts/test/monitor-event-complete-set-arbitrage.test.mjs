import test from "node:test";
import assert from "node:assert/strict";

import { parseMonitorArgs, proxySums } from "../monitor-event-complete-set-arbitrage.mjs";

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