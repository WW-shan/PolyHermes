import test from "node:test";
import assert from "node:assert/strict";

import {
  buildGroups,
  costForShares,
  evaluateAskArb,
  evaluateBidArb,
  feeRateOf,
  normalizeMarket,
  parseArgs,
  parseBook,
  revenueForShares,
  validateNegRiskEvent,
} from "../scan-event-complete-set-arbitrage.mjs";

const NOW = 1_800_000_000_000;

function book(tokenId, bids = [], asks = [], updatedAt = NOW) {
  return { tokenId, bids, asks, updatedAt, minOrderSize: 5, tickSize: 0.01 };
}

function entry(tokenId, bids, asks, feeRate = 0) {
  return { book: book(tokenId, bids, asks), feeRate };
}

function market(id, tokens, extra = {}) {
  return {
    id,
    conditionId: `condition-${id}`,
    question: `Question ${id}`,
    slug: `slug-${id}`,
    active: true,
    closed: false,
    enableOrderBook: true,
    acceptingOrders: true,
    negRisk: true,
    negRiskMarketID: "nr-1",
    orderMinSize: 5,
    orderPriceMinTickSize: 0.01,
    feesEnabled: false,
    clobTokenIds: JSON.stringify(tokens),
    outcomes: JSON.stringify(["Yes", "No"]),
    events: [{ id: "event-1", slug: "event-1", title: "Event 1" }],
    ...extra,
  };
}

test("parseArgs accepts named and numeric tags and preserves safety defaults", () => {
  const parsed = parseArgs(["--tags", "politics,123", "--max-groups", "12"]);
  assert.deepEqual(parsed.tags, ["politics", "123"]);
  assert.equal(parsed.maxGroups, 12);
  assert.equal(parsed.minEdgeCents, 0.5);
  assert.equal(parsed.maxBookAgeMs, 15_000);
  assert.throws(() => parseArgs(["--tags", "unknown-tag"]), /unsupported tag/);
});

test("fee rates never silently become zero when enabled without a schedule", () => {
  assert.equal(feeRateOf({ feesEnabled: false }), 0);
  assert.equal(feeRateOf({ feesEnabled: true }), null);
  assert.equal(feeRateOf({ feesEnabled: true, feeSchedule: { rate: 0.04 } }), 0.04);
  assert.equal(feeRateOf({ feesEnabled: true, feeType: "sports_fees_v2" }), 0.03);
});

test("normalizeMarket selects the YES token by outcome label", () => {
  const raw = market("m1", ["no-token", "yes-token"]);
  raw.outcomes = JSON.stringify(["No", "Yes"]);
  const normalized = normalizeMarket(raw);
  assert.equal(normalized.yesTokenId, "yes-token");
  assert.equal(normalized.noTokenId, "no-token");
});

test("parseBook sorts levels and rejects invalid prices and sizes", () => {
  const parsed = parseBook({
    timestamp: "1800000000000",
    bids: [
      { price: "0.40", size: "10" },
      { price: "0.50", size: "2" },
      { price: "1.00", size: "5" },
      { price: "0.30", size: "0" },
    ],
    asks: [
      { price: "0.70", size: "5" },
      { price: "0.60", size: "3" },
    ],
    min_order_size: "5",
  }, "token");
  assert.deepEqual(parsed.bids.map((level) => level.price), [0.5, 0.4]);
  assert.deepEqual(parsed.asks.map((level) => level.price), [0.6, 0.7]);
  assert.equal(parsed.updatedAt, NOW);
});

test("depth costing and revenue include the official price-dependent taker fee", () => {
  const books = [entry("a", [], [
    { price: 0.4, size: 10 },
    { price: 0.5, size: 10 },
  ], 0.05)];
  const cost = costForShares(books, 15);
  assert.ok(Math.abs(cost.cash - 6.5) < 1e-9);
  assert.ok(Math.abs(cost.fees - 0.1825) < 1e-9);

  const sellBooks = [entry("b", [
    { price: 0.6, size: 10 },
    { price: 0.5, size: 10 },
  ], [], 0.05)];
  const revenue = revenueForShares(sellBooks, 15);
  assert.ok(Math.abs(revenue.revenue - 8.5) < 1e-9);
  assert.ok(Math.abs(revenue.fees - 0.1825) < 1e-9);
  assert.ok(Math.abs(revenue.net - (-6.6825)) < 1e-9);
});

test("ask arbitrage is profitable after costs and rounds shares down to the order step", () => {
  const books = [
    entry("yes", [], [{ price: 0.48, size: 10.129 }]),
    entry("no", [], [{ price: 0.49, size: 10.129 }]),
  ];
  const result = evaluateAskArb(books, 5, {
    now: NOW,
    minEdgeCents: 0.5,
    minProfitUsd: 0.001,
    mergeGasUsd: 0,
    sizeStep: 0.01,
  });
  assert.equal(result.available, true);
  assert.equal(result.actionable, true);
  assert.ok(Math.abs(result.maxShares - 10.12) < 1e-9);
  assert.ok(result.profit > 0.3);
});

test("stale books remain measurable but cannot become actionable", () => {
  const books = [
    entry("yes", [], [{ price: 0.48, size: 10 }], 0),
    entry("no", [], [{ price: 0.49, size: 10 }], 0),
  ];
  books[1].book.updatedAt = NOW - 20_000;
  const result = evaluateAskArb(books, 5, {
    now: NOW,
    maxBookAgeMs: 15_000,
    minEdgeCents: 0,
    minProfitUsd: 0,
    mergeGasUsd: 0,
  });
  assert.equal(result.available, true);
  assert.equal(result.actionable, false);
  assert.equal(result.reason, "stale_book");
});

test("bid arbitrage explicitly requires minting and accounts for mint gas", () => {
  const books = [
    entry("yes", [{ price: 0.51, size: 10 }], []),
    entry("no", [{ price: 0.50, size: 10 }], []),
  ];
  const result = evaluateBidArb(books, 5, {
    now: NOW,
    minEdgeCents: 0.5,
    minProfitUsd: 0.01,
    mintGasUsd: 0.02,
  });
  assert.equal(result.available, true);
  assert.equal(result.actionable, true);
  assert.equal(result.requiresMint, true);
  assert.equal(result.nonAtomicLegs, 2);
  assert.equal(result.maxShares, 10);
  assert.ok(Math.abs(result.profit - 0.08) < 1e-9);
});

test("minimum order size is enforced on every complete-set leg", () => {
  const books = [
    entry("yes", [], [{ price: 0.48, size: 10 }]),
    entry("no", [], [{ price: 0.49, size: 2 }]),
  ];
  const result = evaluateAskArb(books, 5, { now: NOW, mergeGasUsd: 0 });
  assert.equal(result.available, false);
});

test("NegRisk validation requires a complete, active, consistent event", () => {
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
      market("m1", ["y1", "n1"]),
      market("m2", ["y2", "n2"]),
    ],
  };
  assert.equal(validateNegRiskEvent(event, group, { maxOutcomesPerGroup: 60 }).ok, true);

  const closed = structuredClone(event);
  closed.markets[1].closed = true;
  assert.equal(validateNegRiskEvent(closed, group, { maxOutcomesPerGroup: 60 }).reason, "incomplete_or_closed_group");

  const mismatched = structuredClone(event);
  mismatched.markets[1].negRiskMarketID = "nr-2";
  assert.equal(validateNegRiskEvent(mismatched, group, { maxOutcomesPerGroup: 60 }).reason, "neg_risk_id_mismatch");

  const duplicate = structuredClone(event);
  duplicate.markets[1].clobTokenIds = JSON.stringify(["y1", "n2"]);
  assert.equal(validateNegRiskEvent(duplicate, group, { maxOutcomesPerGroup: 60 }).reason, "duplicate_token_id");
});

test("group building deduplicates markets and excludes closed or untradable books", () => {
  const raw = market("m1", ["y1", "n1"]);
  const first = normalizeMarket(raw);
  const second = normalizeMarket(raw);
  const closed = normalizeMarket(market("m2", ["y2", "n2"], { closed: true }));
  const groups = buildGroups([first, second, closed], {
    minVolume24h: 0,
    maxGroups: 10,
  });
  assert.equal(groups.length, 1);
  assert.equal(groups[0].type, "negRisk");
  assert.equal(groups[0].conditionIds.length, 1);
});