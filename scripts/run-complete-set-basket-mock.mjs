#!/usr/bin/env node

/**
 * Run deterministic mock-only basket scenarios against a read-only risk report.
 * This script has no signing or real order adapter.
 */

import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { executeMockSequentialBasket, payoutMatrixFor } from "./complete-set-basket-risk.mjs";

const SCENARIOS = ["all_filled", "partial_second", "fail_last", "no_exit", "fail_last_no_exit"];

function parseArgs(argv) {
  const options = {
    report: null,
    scenario: "all",
    maxLossUsd: 25,
    maxCapitalUsd: 30,
    output: null,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => {
      i += 1;
      if (i >= argv.length) throw new Error(`missing value for ${arg}`);
      return argv[i];
    };
    switch (arg) {
      case "--report": options.report = next(); break;
      case "--scenario": options.scenario = next(); break;
      case "--max-loss-usd": options.maxLossUsd = Number(next()); break;
      case "--max-capital-usd": options.maxCapitalUsd = Number(next()); break;
      case "--output": options.output = next(); break;
      case "--help": options.help = true; break;
      default: throw new Error(`unknown argument: ${arg}`);
    }
  }
  if (options.scenario !== "all" && !SCENARIOS.includes(options.scenario)) {
    throw new Error(`--scenario must be all or one of ${SCENARIOS.join(", ")}`);
  }
  if (!Number.isFinite(options.maxLossUsd) || options.maxLossUsd < 0) throw new Error("--max-loss-usd is invalid");
  if (!Number.isFinite(options.maxCapitalUsd) || options.maxCapitalUsd < 0) throw new Error("--max-capital-usd is invalid");
  return options;
}

class DeterministicMockAdapter {
  constructor(legs, scenario) {
    this.mode = "mock";
    this.legs = legs;
    this.scenario = scenario;
    this.sequence = 0;
    this.orders = new Map();
    this.placed = [];
  }

  async placeLimitBuy({ tokenId, price, shares }) {
    this.sequence += 1;
    const orderId = `mock-${this.sequence}`;
    const index = this.placed.length;
    this.orders.set(orderId, { tokenId, price, shares, index });
    this.placed.push({ orderId, tokenId, price, shares });
    return { orderId };
  }

  async waitForOrder(orderId) {
    const order = this.orders.get(orderId);
    const lastIndex = this.legs.length - 1;
    let filledShares = order.shares;
    let status = "filled";
    if (this.scenario === "partial_second" || this.scenario === "no_exit") {
      if (order.index === 1) {
        filledShares = order.shares / 2;
        status = "partial";
      }
    } else if ((this.scenario === "fail_last" || this.scenario === "fail_last_no_exit") && order.index === lastIndex) {
      filledShares = 0;
      status = "rejected";
    }
    return { status, filledShares, averagePrice: order.price, feeUsd: 0 };
  }

  async cancelOrder(orderId) {
    const order = this.orders.get(orderId);
    const terminal = await this.waitForOrder(orderId);
    return {
      status: "cancelled",
      filledShares: terminal.filledShares,
      averagePrice: terminal.averagePrice,
      feeUsd: terminal.feeUsd,
    };
  }

  async sellAtBid({ shares, limitPrice }) {
    if (this.scenario === "no_exit" || this.scenario === "fail_last_no_exit") {
      return { status: "unfilled", filledShares: 0, averagePrice: 0, feeUsd: 0 };
    }
    return { status: "filled", filledShares: shares, averagePrice: limitPrice, feeUsd: 0 };
  }
}

async function runScenario(report, scenario, options) {
  const adapter = new DeterministicMockAdapter(report.legs, scenario);
  return executeMockSequentialBasket({
    legs: report.legs,
    adapter,
    targetShares: report.targetShares,
    payoutMatrix: payoutMatrixFor(report.side === "no" ? "no" : "yes", report.legs.length),
    gasUsd: 0.02,
    maxLossUsd: options.maxLossUsd,
    maxCapitalUsd: options.maxCapitalUsd,
    autoOrderSequential: false,
  });
}

async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  if (options.help) {
    console.log("Usage: node scripts/run-complete-set-basket-mock.mjs --report PATH [options]");
    console.log("  --scenario all|all_filled|partial_second|fail_last|no_exit|fail_last_no_exit");
    console.log("  --max-loss-usd 25");
    console.log("  --max-capital-usd 30");
    console.log("  --output PATH");
    return;
  }
  if (!options.report) throw new Error("--report is required");
  const report = JSON.parse(await fs.readFile(path.resolve(options.report), "utf8"));
  if (!Array.isArray(report.legs) || !Number.isFinite(report.targetShares)) {
    throw new Error("risk report has no executable legs/targetShares");
  }
  const selected = options.scenario === "all" ? SCENARIOS : [options.scenario];
  const results = [];
  for (const scenario of selected) results.push(await runScenario(report, scenario, options));
  const output = { generatedAt: new Date().toISOString(), sourceReport: path.resolve(options.report), results };
  if (options.output) {
    const outputPath = path.resolve(options.output);
    await fs.mkdir(path.dirname(outputPath), { recursive: true });
    await fs.writeFile(outputPath, `${JSON.stringify(output, null, 2)}\n`, "utf8");
    console.log(`wrote ${outputPath}`);
  }
  for (const result of results) {
    console.log(`${result.status}: orders=${result.orders.length} fills=[${result.originalFills.join(",")}] ` +
      `remaining=[${result.fills.join(",")}] worstPnl=${result.worstCasePnlUsd?.toFixed(4)} ` +
      `breached=${result.maxLossBreached}`);
  }
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
