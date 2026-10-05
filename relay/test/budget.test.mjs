// Unit tests for the TURN budget logic. These need no server; they run as
// part of `node --test test/*.test.mjs` against either implementation.

import { test } from "node:test";
import assert from "node:assert/strict";

import { GB, STALE_MS, monthStart, usageQuery, parseUsage, decide, parseBudgetGB } from "../src/budget.js";

const T = Date.UTC(2026, 9, 17, 12, 0, 0); // 2026-10-17T12:00Z

test("monthStart is the first instant of the month in UTC", () => {
  assert.equal(monthStart(T).toISOString(), "2026-10-01T00:00:00.000Z");
  assert.equal(monthStart(Date.UTC(2026, 0, 1, 0, 0, 1)).toISOString(), "2026-01-01T00:00:00.000Z");
});

test("usageQuery asks for this month's egress for one account", () => {
  const q = usageQuery("abc123", T);
  assert.match(q.query, /callsTurnUsageAdaptiveGroups/);
  assert.match(q.query, /egressBytes/);
  assert.deepEqual(q.variables, { account: "abc123", from: "2026-10-01T00:00:00.000Z", to: "2026-10-17T12:00:00.000Z" });
});

test("parseUsage sums groups, tolerates none, rejects errors and odd shapes", () => {
  const ok = { data: { viewer: { accounts: [{ callsTurnUsageAdaptiveGroups: [{ sum: { egressBytes: 1500 } }, { sum: { egressBytes: 500 } }] }] } } };
  assert.equal(parseUsage(ok), 2000);
  const none = { data: { viewer: { accounts: [{ callsTurnUsageAdaptiveGroups: [] }] } } };
  assert.equal(parseUsage(none), 0);
  assert.throws(() => parseUsage({ errors: [{ message: "authentication error" }] }), /authentication error/);
  assert.throws(() => parseUsage({ data: { viewer: { accounts: [] } } }), /no account/);
  assert.throws(() => parseUsage(null), /empty/);
});

test("decide allows everything when no budget is configured", () => {
  assert.equal(decide({ budgetBytes: 0, reading: null, now: T }).allow, true);
});

test("decide withholds TURN with no reading, a stale reading, or a spent budget", () => {
  const budget = 900 * GB;
  assert.equal(decide({ budgetBytes: budget, reading: null, now: T }).allow, false);
  assert.equal(decide({ budgetBytes: budget, reading: { bytes: 0, at: T - STALE_MS - 1 }, now: T }).allow, false);
  assert.equal(decide({ budgetBytes: budget, reading: { bytes: 900 * GB, at: T }, now: T }).allow, false);
  assert.equal(decide({ budgetBytes: budget, reading: { bytes: 899 * GB, at: T }, now: T }).allow, true);
});

test("decide resets at the month boundary even before the first refresh", () => {
  const lastMonth = Date.UTC(2026, 8, 30, 23, 0, 0);
  const r = decide({ budgetBytes: 1 * GB, reading: { bytes: 5 * GB, at: lastMonth }, now: Date.UTC(2026, 9, 1, 0, 5, 0) });
  assert.equal(r.allow, true);
});

test("parseBudgetGB accepts decimal GB and ignores nonsense", () => {
  assert.equal(parseBudgetGB("900"), 900 * GB);
  assert.equal(parseBudgetGB("0.5"), 500_000_000);
  assert.equal(parseBudgetGB(""), 0);
  assert.equal(parseBudgetGB("abc"), 0);
  assert.equal(parseBudgetGB("-3"), 0);
  assert.equal(parseBudgetGB(undefined), 0);
});
