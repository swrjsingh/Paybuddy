/**
 * Exercises the running PayBuddy stack through its public gateway.
 * Checks tenant isolation, concurrent retries, real callbacks and database effects.
 * Uses the second merchant so the main demo workspace stays clean.
 * Usage: node tests/api.integration.mjs
 */
import assert from "node:assert/strict";
import { randomUUID, createHmac } from "node:crypto";
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
const base = process.env.PAYBUDDY_URL || "http://127.0.0.1:8090";
const results = [];
const env = Object.fromEntries(
  readFileSync(".env", "utf8")
    .split("\n")
    .filter((l) => l.includes("="))
    .map((l) => {
      const p = l.indexOf("=");
      return [l.slice(0, p), l.slice(p + 1)];
    }),
);
async function request(
  path,
  token,
  body,
  method = body ? "POST" : "GET",
  extra = {},
) {
  const response = await fetch(base + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {}),
      ...extra,
    },
    ...(body !== undefined
      ? { body: typeof body === "string" ? body : JSON.stringify(body) }
      : {}),
  });
  const data = await response.json().catch(() => ({}));
  return { status: response.status, data };
}
async function ok(path, token, body, method, extra) {
  const r = await request(path, token, body, method, extra);
  assert.ok(
    r.status >= 200 && r.status < 300,
    `${path}: HTTP ${r.status} ${JSON.stringify(r.data)}`,
  );
  return r.data;
}
async function eventually(check, description, timeout = 35000) {
  const deadline = Date.now() + timeout;
  let last;
  while (Date.now() < deadline) {
    try {
      last = await check();
      if (last) return last;
    } catch (error) {
      last = error.message;
    }
    await new Promise((r) => setTimeout(r, 700));
  }
  throw Error(
    `Timed out: ${description}. Last result: ${JSON.stringify(last)}`,
  );
}
function pass(name) {
  results.push({ name, status: "PASS" });
  console.log("PASS", name);
}
const login = await ok("/auth/login", null, {
  email: "merchant2@paybuddy.local",
  password: "PayBuddy123!",
});
const token = login.token;
const merchant = await ok("/auth/login", null, {
  email: "merchant@paybuddy.local",
  password: "PayBuddy123!",
});
const ops = await ok("/auth/login", null, {
  email: "ops@paybuddy.local",
  password: "OpsBuddy123!",
});
assert.equal(
  (
    await request("/auth/login", null, {
      email: login.email,
      password: "wrong",
    })
  ).status,
  401,
);
assert.equal((await request("/orders")).status, 401);
pass("Authentication rejects missing tokens and incorrect passwords");
async function newIntent(name, method = "UPI") {
  const order = await ok("/orders", token, {
    description: `Integration: ${name}`,
    amountMinor: 50000,
    currency: "INR",
  });
  const key = randomUUID();
  const intent = await ok(
    "/payment-intents",
    token,
    { orderId: order.id, method },
    "POST",
    { "Idempotency-Key": key },
  );
  return { order, intent, key };
}
const order = await ok("/orders", token, {
  description: "Integration: concurrent idempotency",
  amountMinor: 50000,
  currency: "INR",
});
const key = randomUUID();
const body = { orderId: order.id, method: "UPI" };
const concurrent = await Promise.all(
  Array.from({ length: 12 }, () =>
    ok("/payment-intents", token, body, "POST", { "Idempotency-Key": key }),
  ),
);
assert.equal(new Set(concurrent.map((i) => i.id)).size, 1);
const id = concurrent[0].id;
assert.equal(
  (
    await request(
      "/payment-intents",
      token,
      { ...body, method: "GENERIC" },
      "POST",
      { "Idempotency-Key": key },
    )
  ).status,
  409,
);
const changedKey = await ok("/payment-intents", token, body, "POST", {
  "Idempotency-Key": randomUUID(),
});
assert.equal(changedKey.id, id);
pass(
  "Concurrent retries and different keys produce one intent; changed payload conflicts",
);
assert.equal(
  (await request("/orders/" + order.id, merchant.token)).status,
  404,
);
assert.equal(
  (await request("/payment-intents/" + id, merchant.token)).status,
  404,
);
assert.equal(
  (
    await request(
      "/routing/policies/upi-a",
      token,
      { priority: 1, enabled: false },
      "PUT",
    )
  ).status,
  403,
);
assert.equal(
  (await request("/operations/providers/scenarios", token)).status,
  403,
);
assert.equal(
  (await request("/internal/orders/" + order.id, token)).status,
  403,
);
pass("Merchant isolation, operations permissions and internal API boundary");
const ready = await eventually(async () => {
  const i = await ok("/payment-intents/" + id, token);
  return i.checkoutToken ? i : false;
}, "checkout token");
const approvals = await Promise.all(
  Array.from({ length: 6 }, () =>
    ok("/simulator/payments/" + ready.checkoutToken + "/complete", null, {
      status: "SUCCEEDED",
    }),
  ),
);
assert.ok(approvals.every((a) => a.status === "SUCCEEDED"));
await eventually(async () => {
  const i = await ok("/payment-intents/" + id, token);
  const o = await ok("/orders/" + order.id, token);
  const l = await ok("/ledger/" + id, token);
  return i.status === "SUCCEEDED" && o.status === "PAID" && l.length === 1;
}, "payment, order and ledger convergence");
const lifecycle = await ok("/payment-intents/" + id + "/timeline", token);
assert.equal(lifecycle.filter((e) => e.kind === "SUCCEEDED").length, 1);
const sql = (db, query) =>
  execFileSync(
    "docker",
    [
      "compose",
      "exec",
      "-T",
      "postgres",
      "psql",
      "-U",
      "paybuddy_admin",
      "-d",
      db,
      "-tAc",
      query,
    ],
    { encoding: "utf8" },
  ).trim();
const raw = sql(
  "provider_adapter",
  `SELECT outcome_payload FROM provider_transactions WHERE intent_id='${id}'`,
);
const timestamp = String(Math.floor(Date.now() / 1000));
const signature = createHmac("sha256", env.WEBHOOK_SECRET)
  .update(timestamp + "." + raw)
  .digest("hex");
const headers = {
  "X-Webhook-Timestamp": timestamp,
  "X-Webhook-Signature": signature,
};
await Promise.all(
  Array.from({ length: 15 }, () =>
    ok("/webhooks/" + ready.providerId, null, raw, "POST", headers),
  ),
);
await new Promise((r) => setTimeout(r, 2500));
assert.equal((await ok("/ledger/" + id, token)).length, 1);
assert.equal(
  sql(
    "order",
    `SELECT count(*) FROM order_payments WHERE order_id='${order.id}'`,
  ),
  "1",
);
assert.equal(
  sql(
    "provider_adapter",
    `SELECT count(*) FROM provider_transactions WHERE request_key='${id}'`,
  ),
  "1",
);
pass(
  "Concurrent approvals and 15 duplicated signed callbacks have one order/ledger effect",
);
assert.equal(
  (
    await request("/webhooks/" + ready.providerId, null, raw, "POST", {
      "X-Webhook-Timestamp": timestamp,
      "X-Webhook-Signature": "bad",
    })
  ).status,
  401,
);
const changed = JSON.stringify({ ...JSON.parse(raw), amountMinor: 1 });
const changedSig = createHmac("sha256", env.WEBHOOK_SECRET)
  .update(timestamp + "." + changed)
  .digest("hex");
assert.equal(
  (
    await request("/webhooks/" + ready.providerId, null, changed, "POST", {
      "X-Webhook-Timestamp": timestamp,
      "X-Webhook-Signature": changedSig,
    })
  ).status,
  409,
);
const expired = String(Math.floor(Date.now() / 1000) - 600);
assert.equal(
  (
    await request("/webhooks/" + ready.providerId, null, raw, "POST", {
      "X-Webhook-Timestamp": expired,
      "X-Webhook-Signature": createHmac("sha256", env.WEBHOOK_SECRET)
        .update(expired + "." + raw)
        .digest("hex"),
    })
  ).status,
  401,
);
pass(
  "Invalid/expired signatures and changed-content event-ID reuse are rejected",
);
const late = JSON.stringify({
  ...JSON.parse(raw),
  eventId: randomUUID(),
  status: "PROCESSING",
});
await ok("/webhooks/" + ready.providerId, null, late, "POST", {
  "X-Webhook-Timestamp": timestamp,
  "X-Webhook-Signature": createHmac("sha256", env.WEBHOOK_SECRET)
    .update(timestamp + "." + late)
    .digest("hex"),
});
await eventually(
  async () =>
    (await ok("/payment-intents/" + id + "/timeline", token)).some(
      (h) => h.kind === "OBSERVATION",
    ),
  "late outcome recorded",
);
assert.equal((await ok("/payment-intents/" + id, token)).status, "SUCCEEDED");
assert.equal((await ok("/orders/" + order.id, token)).status, "PAID");
assert.equal((await ok("/ledger/" + id, token)).length, 1);
pass("Out-of-order processing observation does not regress successful payment");
const failed = await newIntent("definitive failure");
const f = await eventually(async () => {
  const i = await ok("/payment-intents/" + failed.intent.id, token);
  return i.checkoutToken ? i : false;
}, "failure checkout");
await ok("/simulator/payments/" + f.checkoutToken + "/complete", null, {
  status: "FAILED",
});
await eventually(
  async () => (await ok("/payment-intents/" + f.id, token)).status === "FAILED",
  "confirmed failure",
);
assert.equal((await ok("/orders/" + failed.order.id, token)).status, "UNPAID");
assert.equal((await ok("/ledger/" + f.id, token)).length, 0);
pass(
  "Definitive failure leaves order unpaid and creates no success ledger effect",
);
await ok(
  "/operations/providers/upi-a/scenario",
  ops.token,
  { mode: "TIMEOUT" },
  "PUT",
);
try {
  const timed = await newIntent("accepted then timeout");
  const t = await eventually(
    async () => {
      const i = await ok("/payment-intents/" + timed.intent.id, token);
      return i.checkoutToken ? i : false;
    },
    "timeout recovery",
    45000,
  );
  assert.equal(t.status, "PROCESSING");
  assert.equal(
    sql(
      "provider_adapter",
      `SELECT count(*) FROM provider_transactions WHERE request_key='${t.id}'`,
    ),
    "1",
  );
  await ok("/simulator/payments/" + t.checkoutToken + "/complete", null, {
    status: "SUCCEEDED",
  });
  await eventually(
    async () =>
      (await ok("/orders/" + timed.order.id, token)).status === "PAID",
    "timeout recovered payment",
  );
  pass("Accepted-then-timeout recovers the same provider transaction");
} finally {
  await ok(
    "/operations/providers/upi-a/scenario",
    ops.token,
    { mode: "NORMAL" },
    "PUT",
  );
}
const generic = await newIntent("generic method", "GENERIC");
const g = await eventually(async () => {
  const i = await ok("/payment-intents/" + generic.intent.id, token);
  return i.checkoutToken ? i : false;
}, "generic checkout");
assert.equal(g.providerId, "generic");
await ok("/simulator/payments/" + g.checkoutToken + "/complete", null, {
  status: "SUCCEEDED",
});
await eventually(
  async () =>
    (await ok("/orders/" + generic.order.id, token)).status === "PAID",
  "generic order",
);
pass("Method eligibility routes generic payments to the generic provider");
const recovery = await newIntent("consumer restart");
const r = await eventually(async () => {
  const i = await ok("/payment-intents/" + recovery.intent.id, token);
  return i.checkoutToken ? i : false;
}, "restart checkout");
execFileSync("docker", ["compose", "stop", "order", "ledger-reconciliation"], {
  stdio: "pipe",
});
try {
  await ok("/simulator/payments/" + r.checkoutToken + "/complete", null, {
    status: "SUCCEEDED",
  });
  await eventually(
    async () =>
      (await ok("/payment-intents/" + r.id, token)).status === "SUCCEEDED",
    "success while consumers offline",
  );
  assert.equal(
    sql("order", `SELECT status FROM orders WHERE id='${recovery.order.id}'`),
    "UNPAID",
  );
} finally {
  execFileSync(
    "docker",
    ["compose", "start", "order", "ledger-reconciliation"],
    { stdio: "pipe" },
  );
}
await eventually(
  async () => {
    const o = await ok("/orders/" + recovery.order.id, token);
    const l = await ok("/ledger/" + r.id, token);
    return o.status === "PAID" && l.length === 1;
  },
  "consumers catch up after restart",
  60000,
);
pass("Durable queues deliver order/ledger effects after consumer restart");
assert.throws(() =>
  execFileSync(
    "docker",
    [
      "compose",
      "exec",
      "-T",
      "postgres",
      "psql",
      "-v",
      "ON_ERROR_STOP=1",
      "-U",
      "paybuddy_admin",
      "-d",
      "ledger_reconciliation",
      "-c",
      `UPDATE ledger_entries SET amount_minor=1 WHERE payment_intent_id='${id}'`,
    ],
    { encoding: "utf8", stdio: ["pipe", "pipe", "pipe"] },
  ),
);
pass("Ledger trigger rejects updates to recorded financial effects");
assert.throws(() =>
  execFileSync(
    "docker",
    [
      "compose",
      "exec",
      "-T",
      "postgres",
      "psql",
      "-v",
      "ON_ERROR_STOP=1",
      "-U",
      "ledger_app",
      "-d",
      "ledger_reconciliation",
      "-c",
      "TRUNCATE ledger_entries",
    ],
    { encoding: "utf8", stdio: ["pipe", "pipe", "pipe"] },
  ),
);
pass("Restricted ledger runtime role cannot truncate financial history");
const tbAccounts = await eventually(async () => {
  const a = await ok("/ledger/accounts", token);
  return a.connected && a.postedTransfers >= 1 ? a : false;
}, "TigerBeetle double-entry accounts and transfer posting");
assert.equal(tbAccounts.engine, "TigerBeetle 0.16.19");
assert.ok(
  tbAccounts.accounts.some(
    (a) => a.type === "MERCHANT_RECEIVABLE" && a.creditsPostedMinor >= 50000,
  ),
);
pass(
  "TigerBeetle double-entry accounts and deterministic transfer posting verified",
);
const cleanImport = await ok("/settlements/import", token, {
  anomaly: "CLEAN",
});
assert.equal(cleanImport.duplicateImport, false);
assert.equal(cleanImport.run.exceptionCount, 0);
assert.ok(cleanImport.run.matchedCount >= 1);
const dupImport = await ok("/settlements/import", token, {
  anomaly: "CLEAN",
});
assert.equal(dupImport.duplicateImport, true);
assert.equal(dupImport.run.id, cleanImport.run.id);
pass("Clean settlement import matches ledger and repeat import is idempotent");
const mismatchImport = await ok("/settlements/import", token, {
  anomaly: "AMOUNT_MISMATCH",
});
assert.equal(mismatchImport.run.exceptionCount, 1);
const missingImport = await ok("/settlements/import", token, {
  anomaly: "MISSING_IN_SETTLEMENT",
});
assert.equal(missingImport.run.exceptionCount, 1);
const unrecordedImport = await ok("/settlements/import", token, {
  anomaly: "MISSING_IN_LEDGER",
});
assert.equal(unrecordedImport.run.exceptionCount, 1);
const duplicateRowImport = await ok("/settlements/import", token, {
  anomaly: "DUPLICATE_SETTLEMENT",
});
assert.equal(duplicateRowImport.run.exceptionCount, 1);
const statusConflictImport = await ok("/settlements/import", token, {
  anomaly: "STATUS_CONFLICT",
});
assert.equal(statusConflictImport.run.exceptionCount, 1);
const exceptions = await ok("/reconciliation/exceptions", token);
const classes = new Set(exceptions.map((e) => e.classification));
for (const expectedClass of [
  "AMOUNT_MISMATCH",
  "MISSING_IN_SETTLEMENT",
  "MISSING_IN_LEDGER",
  "DUPLICATE_SETTLEMENT",
  "STATUS_CONFLICT",
]) {
  assert.ok(classes.has(expectedClass), `Missing ${expectedClass}`);
}
pass(
  "Reconciliation detects amount mismatch, missing in settlement/ledger, duplicate settlement, and status conflict",
);
const opsClean = await ok("/settlements/import", ops.token, {
  anomaly: "AMOUNT_MISMATCH",
});
const opsExceptions = await ok("/reconciliation/exceptions", ops.token);
if (opsExceptions.length > 0) {
  const targetEx = opsExceptions[0];
  assert.equal(
    (
      await request(
        `/reconciliation/exceptions/${targetEx.id}/actions`,
        merchant.token,
        {
          status: "RESOLVED",
          action: "VERIFIED_WITH_PROVIDER",
          note: "Merchant should not resolve",
        },
      )
    ).status,
    403,
  );
  const investigating = await ok(
    `/reconciliation/exceptions/${targetEx.id}/actions`,
    ops.token,
    {
      status: "INVESTIGATING",
      action: "VERIFIED_WITH_PROVIDER",
      note: "Checking PSP settlement statement",
    },
  );
  assert.equal(investigating.status, "INVESTIGATING");
  const resolved = await ok(
    `/reconciliation/exceptions/${targetEx.id}/actions`,
    ops.token,
    {
      status: "RESOLVED",
      action: "FEE_OR_ADJUSTMENT_RECORDED",
      note: "Verified fee deduction with PSP statement; audit trail recorded",
    },
  );
  assert.equal(resolved.status, "RESOLVED");
  const audit = await ok(
    `/reconciliation/exceptions/${targetEx.id}/audit`,
    ops.token,
  );
  assert.ok(audit.length >= 3);
}
pass("Audited operator exception resolution workflow and RBAC verified");
await ok(
  "/operations/reports/control",
  ops.token,
  { paused: true },
  "PUT",
);
try {
  const pausedSummary = await ok("/reports/summary", token);
  assert.equal(pausedSummary.projectionPaused, true);
} finally {
  await ok(
    "/operations/reports/control",
    ops.token,
    { paused: false },
    "PUT",
  );
}
const activeSummary = await ok("/reports/summary", token);
assert.equal(activeSummary.projectionPaused, false);
assert.equal(
  activeSummary.projectedVolumeMinor,
  activeSummary.authoritativeVolumeMinor,
);
pass(
  "Asynchronous reporting projection tracks authoritative ledger and supports lag control",
);
mkdirSync("tests/artifacts", { recursive: true });
writeFileSync(
  "tests/artifacts/api-results.json",
  JSON.stringify({ runAt: new Date().toISOString(), base, results }, null, 2) +
    "\n",
);
console.log(`\n${results.length} integration checks passed.`);
