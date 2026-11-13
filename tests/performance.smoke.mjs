/**
 * Measures a small, closed-loop PayBuddy workflow through the public gateway.
 * Uses the isolated second merchant and normal simulator settings, never real money.
 * Reports intent acceptance separately from payment/order/ledger convergence.
 * Usage: node tests/performance.smoke.mjs --samples 30 --concurrency 5,10 --repeats 2
 */
import { performance } from "node:perf_hooks";
import { randomUUID } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, x, i, a) => {
    if (i % 2 === 0) pairs.push([x.replace(/^--/, ""), a[i + 1]]);
    return pairs;
  }, []),
);
const count = Number(args.samples || 30),
  repeats = Number(args.repeats || 2),
  levels = (args.concurrency || "5,10").split(",").map(Number);
if (
  !Number.isInteger(count) ||
  count < 10 ||
  count > 200 ||
  !Number.isInteger(repeats) ||
  repeats < 1 ||
  repeats > 5 ||
  levels.some((n) => !Number.isInteger(n) || n < 1 || n > 25)
)
  throw Error("Use samples 10–200, repeats 1–5, concurrency 1–25.");
const base = process.env.PAYBUDDY_URL || "http://127.0.0.1:8090";
async function api(path, token, body, headers = {}) {
  const r = await fetch(base + path, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {}),
      ...headers,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw Error(`${path}: HTTP ${r.status} ${data.message || ""}`);
  return data;
}
async function poll(check, description) {
  const deadline = performance.now() + 60000;
  while (performance.now() < deadline) {
    const result = await check();
    if (result) return result;
    await new Promise((r) => setTimeout(r, 250));
  }
  throw Error(`60-second deadline: ${description}`);
}
const user = await api("/auth/login", null, {
  email: "merchant2@paybuddy.local",
  password: "PayBuddy123!",
});
const ops = await api("/auth/login", null, {
  email: "ops@paybuddy.local",
  password: "OpsBuddy123!",
});
const scenarios = await api("/operations/providers/scenarios", ops.token);
if (scenarios.some((x) => x.mode !== "NORMAL"))
  throw Error(
    "Restore all provider scenarios to NORMAL before benchmarking. No settings were changed.",
  );
const providers = await api("/providers", user.token);
if (!providers.some((p) => p.method === "UPI" && p.enabled))
  throw Error("Benchmark merchant has no eligible UPI provider.");
async function workflow(label) {
  const order = await api("/orders", user.token, {
    description: `Benchmark: ${label}`,
    amountMinor: 10000,
    currency: "INR",
  });
  const started = performance.now();
  const intent = await api(
    "/payment-intents",
    user.token,
    { orderId: order.id, method: "UPI" },
    { "Idempotency-Key": randomUUID() },
  );
  const acceptanceMs = performance.now() - started;
  const ready = await poll(async () => {
    const i = await api("/payment-intents/" + intent.id, user.token);
    return i.checkoutToken ? i : false;
  }, "checkout dispatch");
  await api("/simulator/payments/" + ready.checkoutToken + "/complete", null, {
    status: "SUCCEEDED",
  });
  const approved = performance.now();
  await poll(async () => {
    const [i, o, l] = await Promise.all([
      api("/payment-intents/" + intent.id, user.token),
      api("/orders/" + order.id, user.token),
      api("/ledger/" + intent.id, user.token),
    ]);
    if (l.length > 1) throw Error("Duplicate ledger effect");
    return i.status === "SUCCEEDED" && o.status === "PAID" && l.length === 1;
  }, "three-service convergence");
  return {
    intentId: intent.id,
    orderId: order.id,
    acceptanceMs,
    submissionToConvergenceMs: performance.now() - started,
    approvalToConvergenceMs: performance.now() - approved,
  };
}
function percentile(values, p) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(sorted.length * p) - 1)];
}
function summary(values) {
  if (!values.length) return null;
  return {
    p50Ms: +percentile(values, 0.5).toFixed(2),
    p95Ms: +percentile(values, 0.95).toFixed(2),
    meanMs: +(values.reduce((a, b) => a + b, 0) / values.length).toFixed(2),
  };
}
for (let i = 0; i < 3; i++) await workflow("warmup-" + i);
const runs = [];
for (const concurrency of levels) {
  for (let repeat = 1; repeat <= repeats; repeat++) {
    const samples = [],
      errors = [];
    let cursor = 0;
    const start = performance.now();
    await Promise.all(
      Array.from({ length: concurrency }, async () => {
        while (cursor < count) {
          const index = cursor++;
          try {
            samples.push(await workflow(`c${concurrency}-r${repeat}-${index}`));
          } catch (e) {
            errors.push({ index, message: e.message });
          }
        }
      }),
    );
    const durationSeconds = (performance.now() - start) / 1000;
    const row = {
      concurrency,
      repeat,
      attempted: count,
      completed: samples.length,
      errors,
      errorRate: errors.length / count,
      durationSeconds: +durationSeconds.toFixed(3),
      completedWorkflowsPerSecond: +(samples.length / durationSeconds).toFixed(
        3,
      ),
      intentAcceptance: summary(samples.map((s) => s.acceptanceMs)),
      submissionToConvergence: summary(
        samples.map((s) => s.submissionToConvergenceMs),
      ),
      approvalToConvergence: summary(
        samples.map((s) => s.approvalToConvergenceMs),
      ),
      samples,
    };
    runs.push(row);
    console.log(JSON.stringify({ ...row, samples: undefined }));
  }
}
const environment = execFileSync(
  "docker",
  [
    "info",
    "--format",
    "CPUs={{.NCPU}} MemoryBytes={{.MemTotal}} Architecture={{.Architecture}}",
  ],
  { encoding: "utf8" },
).trim();
const report = {
  runAt: new Date().toISOString(),
  kind: "Small local closed-loop smoke benchmark, not capacity/load certification",
  base,
  environment,
  settings: {
    samplesPerRun: count,
    repeats,
    concurrencyLevels: levels,
    warmupWorkflows: 3,
    simulatedHumanApprovalDelayMs: 0,
    pollIntervalMs: 250,
    workflowDeadlineMs: 60000,
    providerMode: "NORMAL",
    serviceMemoryLimitMiB: 512,
  },
  definitions: {
    acceptance:
      "Public payment-intent HTTP request start through parsed response; includes order lookup and database transaction.",
    submissionToConvergence:
      "Intent submission through observed SUCCEEDED payment, PAID order, and exactly one ledger entry.",
    approvalToConvergence:
      "Simulator approval HTTP response through observed three-service convergence.",
    throughput:
      "Completed full workflows divided by closed-loop run elapsed time, including order creation and polling. Not API capacity or real-bank throughput.",
  },
  runs,
};
mkdirSync("tests/artifacts", { recursive: true });
writeFileSync(
  "tests/artifacts/performance-results.json",
  JSON.stringify(report, null, 2) + "\n",
);
if (runs.some((r) => r.errors.length)) process.exitCode = 1;
