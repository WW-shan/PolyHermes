import test from "node:test";
import assert from "node:assert/strict";

import {
  buildCalendarPairs,
  evaluateCalendarBooks,
  inferMonotonicDirection,
  questionSkeleton,
  tokensForCalendarPair,
} from "../scan-event-calendar-spread-arbitrage.mjs";

const NOW = Date.parse("2026-10-20T00:00:00Z");

function market(id, question, endDate, extra = {}) {
  return {
    conditionId: `condition-${id}`,
    question,
    eventSlug: "event-1",
    eventTitle: "Event 1",
    endDate,
    active: true,
    closed: false,
    enableOrderBook: true,
    acceptingOrders: true,
    yesTokenId: `yes-${id}`,
    noTokenId: `no-${id}`,
    volume24hr: 10_000,
    minOrderSize: 5,
    ...extra,
  };
}

function book(tokenId, asks, updatedAt = NOW) {
  return { tokenId, bids: [], asks, updatedAt, minOrderSize: 5 };
}

test("question skeleton normalizes only date expressions", () => {
  const a = questionSkeleton("Will X happen by October 31, 2026?");
  const b = questionSkeleton("Will X happen by November 30, 2026?");
  assert.equal(a.skeleton, b.skeleton);
  assert.notDeepEqual(a.dateTokens, b.dateTokens);
  assert.notEqual(
    questionSkeleton("Will X happen by October 31, 2026?").skeleton,
    questionSkeleton("Will Y happen by October 31, 2026?").skeleton,
  );
});

test("calendar pair builder pairs the same question skeleton by later deadline", () => {
  const earlier = market("early", "Will X happen by October 31, 2026?", "2026-11-01T03:59:00Z");
  const later = market("late", "Will X happen by November 30, 2026?", "2026-12-01T04:59:00Z");
  const other = market("other", "Will Y happen by November 30, 2026?", "2026-12-01T04:59:00Z");
  const pairs = buildCalendarPairs([earlier, later, other], { minVolume24h: 0, maxPairs: 100 });
  assert.equal(pairs.length, 1);
  assert.equal(pairs[0].earlier.conditionId, earlier.conditionId);
  assert.equal(pairs[0].later.conditionId, later.conditionId);
});

test("persistence wording stays negative even when YES is the described outcome", () => {
  const positiveDescription = 'This market will resolve to "Yes" if the ceasefire remains in effect through the date. Otherwise it resolves to "No".';
  assert.equal(inferMonotonicDirection("Israel x Iran ceasefire continues through November 15?", positiveDescription), "negative");
});

test("no longer under control is a positive occurrence event", () => {
  assert.equal(inferMonotonicDirection("Kharg Island no longer under Iranian control by December 31?"), "positive");
});
test("resolution description overrides ambiguous question wording", () => {
  const positiveDescription = 'This market will resolve to "Yes" if a meeting occurs by the date. Otherwise it resolves to "No".';
  const negativeDescription = 'This market will resolve to "No" if a strike occurs by the date. Otherwise it resolves to "Yes".';
  assert.equal(inferMonotonicDirection("Will Russia and Ukraine hold any meeting by October 31?", positiveDescription), "positive");
  assert.equal(inferMonotonicDirection("Will the ceasefire continue through October 31?", negativeDescription), "negative");
});
test("direction inference distinguishes positive deadlines from continuation markets", () => {
  assert.equal(inferMonotonicDirection("Will X happen by October 31, 2026?"), "positive");
  assert.equal(inferMonotonicDirection("Will the ceasefire continue through October 31, 2026?"), "negative");
  assert.equal(inferMonotonicDirection("Will X not occur by October 31, 2026?"), "negative");
});

test("calendar token selection follows the inferred direction", () => {
  const positive = {
    direction: "positive",
    earlier: { noTokenId: "no-early" },
    later: { yesTokenId: "yes-late" },
  };
  assert.deepEqual(tokensForCalendarPair(positive), {
    earlierToken: "no-early",
    laterToken: "yes-late",
  });
  const negative = {
    direction: "negative",
    earlier: { yesTokenId: "yes-early" },
    later: { noTokenId: "no-late" },
  };
  assert.deepEqual(tokensForCalendarPair(negative), {
    earlierToken: "yes-early",
    laterToken: "no-late",
  });
});
test("negative calendar direction buys YES earlier and NO later", () => {
  const pair = {
    key: "pair-negative",
    eventSlug: "event-1",
    eventTitle: "Event 1",
    direction: "negative",
    earlier: market("early", "Will the ceasefire continue through October 31, 2026?", "2026-11-01T03:59:00Z"),
    later: market("late", "Will the ceasefire continue through November 30, 2026?", "2026-12-01T04:59:00Z"),
    earlierEndDate: "2026-11-01T03:59:00Z",
    laterEndDate: "2026-12-01T04:59:00Z",
  };
  const books = [
    { book: book("yes-early", [{ price: 0.55, size: 10 }, { price: 0.9, size: 10_000 }]), feeRate: 0 },
    { book: book("no-late", [{ price: 0.44, size: 10 }, { price: 0.9, size: 10_000 }]), feeRate: 0 },
  ];
  const result = evaluateCalendarBooks(books, 5, pair, {
    now: NOW,
    minEdgeCents: 0,
    minProfitUsd: 0,
    gasUsd: 0,
    maxBookAgeMs: 15_000,
  });
  assert.equal(result.direction, "negative");
  assert.equal(result.actionable, true);
  assert.equal(result.requires, "buy_yes_earlier_and_no_later");
});
test("daily on-date markets are not treated as monotone calendar markets", () => {
  const oct5 = market("oct5", "Houthi action on October 5?", "2026-10-06T03:59:00Z");
  const oct14 = market("oct14", "Houthi action on October 14?", "2026-10-15T03:59:00Z");
  const pairs = buildCalendarPairs([oct5, oct14], { minVolume24h: 0, maxPairs: 100 });
  assert.equal(pairs.length, 0);
});
test("calendar spread buys NO earlier and YES later and is profitable after costs", () => {
  const pair = {
    key: "pair-1",
    eventSlug: "event-1",
    eventTitle: "Event 1",
    earlier: market("early", "Will X happen by October 31, 2026?", "2026-11-01T03:59:00Z"),
    later: market("late", "Will X happen by November 30, 2026?", "2026-12-01T04:59:00Z"),
    earlierEndDate: "2026-11-01T03:59:00Z",
    laterEndDate: "2026-12-01T04:59:00Z",
  };
  const books = [
    { book: book("no-early", [{ price: 0.55, size: 10 }, { price: 0.9, size: 10_000 }]), feeRate: 0 },
    { book: book("yes-late", [{ price: 0.44, size: 10 }, { price: 0.9, size: 10_000 }]), feeRate: 0 },
  ];
  const result = evaluateCalendarBooks(books, 5, pair, {
    now: NOW,
    minEdgeCents: 0.5,
    minProfitUsd: 0.01,
    gasUsd: 0,
    maxBookAgeMs: 15_000,
  });
  assert.equal(result.available, true);
  assert.equal(result.actionable, true);
  assert.equal(result.maxShares, 10);
  assert.ok(Math.abs(result.profit - 0.1) < 1e-9);
  assert.ok(Math.abs(result.edgeCents - 1) < 1e-9);
  assert.ok(result.holdingDays > 40);
  assert.equal(result.requires, "buy_no_earlier_and_yes_later");
});

test("stale calendar book cannot become actionable", () => {
  const pair = {
    key: "pair-1",
    eventSlug: "event-1",
    eventTitle: "Event 1",
    earlier: market("early", "Will X happen by October 31, 2026?", "2026-11-01T03:59:00Z"),
    later: market("late", "Will X happen by November 30, 2026?", "2026-12-01T04:59:00Z"),
    earlierEndDate: "2026-11-01T03:59:00Z",
    laterEndDate: "2026-12-01T04:59:00Z",
  };
  const books = [
    { book: book("no-early", [{ price: 0.55, size: 10 }, { price: 0.9, size: 10_000 }]), feeRate: 0 },
    { book: book("yes-late", [{ price: 0.44, size: 10 }], NOW - 20_000), feeRate: 0 },
  ];
  const result = evaluateCalendarBooks(books, 5, pair, {
    now: NOW,
    minEdgeCents: 0,
    minProfitUsd: 0,
    gasUsd: 0,
    maxBookAgeMs: 15_000,
  });
  assert.equal(result.available, true);
  assert.equal(result.actionable, false);
  assert.equal(result.reason, "stale_book");
});