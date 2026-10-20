import test from "node:test";
import assert from "node:assert/strict";

import { parseRiskArgs } from "../analyze-complete-set-execution-risk.mjs";

test("risk CLI defaults are read-only and conservative", () => {
  const options = parseRiskArgs(["--event-slug", "event"]);
  assert.equal(options.side, "yes");
  assert.equal(options.maxLossUsd, 10);
  assert.equal(options.maxCapitalUsd, 25);
  assert.equal(options.entrySlippageCents, 0);
  assert.equal(options.exitSlippageCents, 0.2);
  assert.equal(options.executionMode, "sequential");
});

test("risk CLI parses budgets, slippage and target shares", () => {
  const options = parseRiskArgs([
    "--event-slug", "event",
    "--side", "no",
    "--target-shares", "25",
    "--max-loss-usd", "4",
    "--max-capital-usd", "20",
    "--entry-slippage-cents", "0.3",
    "--exit-slippage-cents", "0.5",
    "--execution-mode", "parallel",
  ]);
  assert.equal(options.side, "no");
  assert.equal(options.targetShares, 25);
  assert.equal(options.maxLossUsd, 4);
  assert.equal(options.maxCapitalUsd, 20);
  assert.equal(options.entrySlippageCents, 0.3);
  assert.equal(options.exitSlippageCents, 0.5);
  assert.equal(options.executionMode, "parallel");
});

test("risk CLI rejects unsupported side and execution mode", () => {
  assert.throws(() => parseRiskArgs(["--event-slug", "event", "--side", "both"]), /--side/);
  assert.throws(() => parseRiskArgs(["--event-slug", "event", "--execution-mode", "atomic"]), /--execution-mode/);
});
