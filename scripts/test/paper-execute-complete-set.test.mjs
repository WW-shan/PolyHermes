import test from "node:test";
import assert from "node:assert/strict";

import {
  computePaperBasketPnl,
  parsePaperArgs,
  permutedPayoutMatrix,
  simulateBuy,
  simulateSell,
} from "../paper-execute-complete-set.mjs";

function book(asks = [], bids = []) {
  return { asks, bids };
}

test("paper executor defaults are live-book-only and delayed between legs", () => {
  const options = parsePaperArgs(["--event-slug", "event", "--target-shares", "20"]);
  assert.equal(options.side, "yes");
  assert.equal(options.targetShares, 20);
  assert.equal(options.legDelayMs, 1000);
  assert.equal(options.unwindDelayMs, 500);
  assert.equal(options.entryToleranceCents, 0.5);
  assert.equal(options.paperSkipLeg, null);
  assert.equal(parsePaperArgs(["--event-slug", "event", "--paper-skip-leg", "4"]).paperSkipLeg, 4);
  assert.throws(() => parsePaperArgs(["--event-slug", "event", "--side", "both"]), /--side/);
});

test("buy simulation respects the limit price and charges official fees", () => {
  const result = simulateBuy(book([
    { price: 0.4, size: 10 },
    { price: 0.41, size: 10 },
    { price: 0.45, size: 10 },
  ]), 15, 0.41, 0.05);
  assert.equal(result.filled, 15);
  assert.equal(result.remaining, 0);
  assert.ok(Math.abs(result.cash - 6.05) < 1e-9);
  assert.ok(Math.abs(result.fees - (0.12 + 0.060475)) < 1e-9);
  assert.equal(result.averagePrice, 6.05 / 15);
});

test("sell simulation consumes bid depth down to its boundary", () => {
  const result = simulateSell(book([], [
    { price: 0.6, size: 4 },
    { price: 0.59, size: 4 },
    { price: 0.5, size: 10 },
  ]), 7, 0.59, 0.05);
  assert.equal(result.sold, 7);
  assert.ok(Math.abs(result.cash - 4.17) < 1e-9);
  assert.ok(result.fees > 0);
});

test("paper PnL computes a locked complete basket and an unhedged worst case", () => {
  const matrix = permutedPayoutMatrix("yes", [0, 1]);
  const complete = computePaperBasketPnl({
    fills: [10, 10],
    sold: [0, 0],
    entryCash: 9,
    entryFees: 0.1,
    gasUsd: 0.02,
    payoutMatrix: matrix,
  });
  assert.ok(Math.abs(complete.worstPnl - 0.88) < 1e-9);

  const failed = computePaperBasketPnl({
    fills: [10, 0],
    sold: [0, 0],
    entryCash: 4,
    entryFees: 0,
    gasUsd: 0,
    payoutMatrix: matrix,
  });
  assert.equal(failed.worstResolutionPayout, 0);
  assert.equal(failed.worstPnl, -4);
});

test("execution-order permutation keeps payout semantics", () => {
  const matrix = permutedPayoutMatrix("no", [1, 0, 2]);
  assert.deepEqual(matrix, [
    [1, 0, 1],
    [0, 1, 1],
    [1, 1, 0],
  ]);
});
