// TURN usage budget: the pure parts, kept free of Workers APIs so they can
// be unit-tested with plain Node (test/budget.test.mjs). index.js wires
// them to a cron trigger and a Durable Object.
//
// Cloudflare bills Realtime TURN on egress bytes per calendar month, with
// the first 1,000 GB free. The rendezvous sums the month's egress through
// the GraphQL analytics API and, once past the configured budget, hands
// hosts STUN only until the month rolls over. Transfers still work when a
// direct path exists; only the paid fallback is withheld.

export const GB = 1_000_000_000; // Cloudflare's GB is decimal

// How long a successful reading stays trustworthy. Past this, with a
// budget configured, TURN is withheld rather than risk an unseen overrun.
export const STALE_MS = 48 * 60 * 60_000;

// First instant of the month `now` falls in, UTC.
export function monthStart(now) {
  const d = new Date(now);
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1));
}

// The GraphQL query that sums this month's TURN egress for one account.
export function usageQuery(accountId, now) {
  const from = monthStart(now).toISOString();
  const to = new Date(now).toISOString();
  return {
    query: `query TurnUsage($account: string!, $from: Time!, $to: Time!) {
  viewer {
    accounts(filter: { accountTag: $account }) {
      callsTurnUsageAdaptiveGroups(
        filter: { datetimeMinute_gt: $from, datetimeMinute_lt: $to }
        limit: 1
      ) {
        sum { egressBytes }
      }
    }
  }
}`,
    variables: { account: accountId, from, to },
  };
}

// Extracts the egress byte count from a GraphQL response, or throws when
// the shape is not what we asked for. No groups means no usage yet.
export function parseUsage(body) {
  if (!body || typeof body !== "object") throw new Error("empty analytics response");
  if (Array.isArray(body.errors) && body.errors.length > 0) {
    throw new Error("analytics error: " + String(body.errors[0].message || "unknown"));
  }
  const accounts = body.data && body.data.viewer && body.data.viewer.accounts;
  if (!Array.isArray(accounts) || accounts.length === 0) throw new Error("analytics: no account in response");
  const groups = accounts[0].callsTurnUsageAdaptiveGroups;
  if (!Array.isArray(groups)) throw new Error("analytics: no usage groups in response");
  let bytes = 0;
  for (const g of groups) {
    const v = g && g.sum && g.sum.egressBytes;
    if (typeof v === "number" && Number.isFinite(v) && v >= 0) bytes += v;
  }
  return bytes;
}

// Decides whether TURN credentials may be issued right now.
//
//   budgetBytes  the monthly cap (0 or less: budget disabled, always allow)
//   reading      { bytes, at } from the last successful refresh, or null
//   now          current time (ms)
//
// Returns { allow, reason }.
export function decide({ budgetBytes, reading, now }) {
  if (!(budgetBytes > 0)) return { allow: true, reason: "no budget configured" };
  if (!reading) return { allow: false, reason: "usage unknown" };
  if (reading.at < monthStart(now).getTime()) return { allow: true, reason: "reading from a previous month; usage reset" };
  if (now - reading.at > STALE_MS) return { allow: false, reason: "usage reading is stale" };
  if (reading.bytes >= budgetBytes) return { allow: false, reason: "monthly TURN budget reached" };
  return { allow: true, reason: "within budget" };
}

// Parses the TURN_BUDGET_GB variable: a decimal number of GB, or nothing.
export function parseBudgetGB(raw) {
  if (raw === undefined || raw === null || String(raw).trim() === "") return 0;
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0) return 0;
  return Math.round(n * GB);
}
