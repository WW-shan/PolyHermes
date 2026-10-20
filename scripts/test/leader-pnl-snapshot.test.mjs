import test from "node:test";
import assert from "node:assert/strict";

import {
  collectLeaderPositions,
  parseSnapshotArgs,
  summarizeLeaderPositions,
} from "../snapshot-leader-pnl.mjs";

const ADDRESS = "0x0260366a9add43c25d4f4d35d2210526c6750d88";

test("leader snapshot CLI defaults to the researched public address", () => {
  const options = parseSnapshotArgs([]);
  assert.equal(options.address, ADDRESS);
  assert.equal(options.pageSize, 50);
  assert.equal(options.maxPages, 200);
  assert.equal(options.includeRows, false);
  assert.throws(() => parseSnapshotArgs(["--address", "0x1234"]), /20-byte address/);
});

test("leader position pagination deduplicates rows and reports truncation conservatively", async () => {
  const urls = [];
  const page = [
    { conditionId: "c1", asset: "a1", outcome: "No", endDate: "2027-01-01", initialValue: 10 },
    { conditionId: "c1", asset: "a1", outcome: "No", endDate: "2027-01-01", initialValue: 10 },
  ];
  const result = await collectLeaderPositions("positions", {
    address: ADDRESS,
    baseUrl: "https://example.test",
    pageSize: 2,
    maxPages: 2,
  }, async (url) => {
    urls.push(url);
    return page;
  });
  assert.equal(urls.length, 2);
  assert.match(urls[0], /offset=0/);
  assert.match(urls[1], /offset=2/);
  assert.equal(result.rows.length, 1);
  assert.equal(result.pages, 2);
  assert.equal(result.truncated, true);
});

test("leader position snapshot separates open and closed PnL", () => {
  const report = summarizeLeaderPositions([
    { outcome: "No", initialValue: 100, currentValue: 103, cashPnl: 3, totalBought: 300, negativeRisk: true },
    { outcome: "Yes", initialValue: 50, currentValue: 45, cashPnl: -5, totalBought: 100, negativeRisk: false },
  ], [
    { endDate: "2026-01-01", realizedPnl: 20, totalBought: 1000 },
    { endDate: "2026-02-01", realizedPnl: -5, totalBought: 100 },
    { endDate: "2027-01-01", realizedPnl: 0, totalBought: 50 },
  ], { address: ADDRESS, generatedAt: "2026-10-20T00:00:00Z", complete: true });
  assert.equal(report.open.count, 2);
  assert.equal(report.open.initialValue, 150);
  assert.equal(report.open.currentValue, 148);
  assert.equal(report.open.cashPnl, -2);
  assert.equal(report.open.yesCount, 1);
  assert.equal(report.open.noCount, 1);
  assert.equal(report.open.negRiskCount, 1);
  assert.equal(report.closed.count, 3);
  assert.equal(report.closed.realizedPnl, 15);
  assert.equal(report.closed.totalBought, 1150);
  assert.equal(report.closed.positiveCount, 1);
  assert.equal(report.closed.negativeCount, 1);
  assert.equal(report.closed.flatCount, 1);
  assert.equal(report.closed.byEndYear[0].year, "2026");
  assert.ok(Math.abs(report.closed.byEndYear[0].realizedReturnPct - (15 / 1100 * 100)) < 1e-9);
});
