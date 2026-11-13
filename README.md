# PayBuddy

A working, local merchant-payment sandbox with seven independently deployable Spring Boot services, PostgreSQL database-per-service isolation, RabbitMQ event choreography, a TigerBeetle double-entry financial ledger, and an automated settlement reconciliation engine. Create an order, start a simulated UPI or card/wallet payment, approve it, watch the verified outcome update the order, append an immutable ledger entry with a TigerBeetle double-entry transfer, and reconcile against provider settlement batches.

**No real money is transferred.** All payment providers (`upi-a`, `upi-b`, `generic-sim`) are local deterministic simulators with configurable fault and settlement-anomaly injection.

## Run

Prerequisites: Docker Desktop running, Docker Compose, and approximately 6 GB available container memory. Java and Maven do not need to be installed on the host. Ports 5173 and 8090 must be available.

```sh
cp .env.example .env
# Review the local-only demo settings in .env, then:
docker compose up --build -d --wait --wait-timeout 180
```

Open [PayBuddy](http://127.0.0.1:5173). The API gateway is [http://127.0.0.1:8090](http://127.0.0.1:8090/actuator/health). Initial image/dependency downloads may take several minutes.

| Account | Email | Password |
|---|---|---|
| Merchant demo | `merchant@paybuddy.local` | `PayBuddy123!` |
| Operations demo | `ops@paybuddy.local` | `OpsBuddy123!` |
| Isolated test merchant | `merchant2@paybuddy.local` | `PayBuddy123!` |

The merchant and operations demo share the same merchant workspace. The second merchant has separate data. These are public demo credentials for a loopback-only sandbox. Do not expose this configuration publicly. JWTs expire after two hours; sign in again when needed. No registration, credential management, external identity provider, or refresh-token flow is implemented.

## Try the app

1. Sign in as Merchant. Choose **Create order**, enter a description and INR amount.
2. In **Orders**, choose UPI or the generic simulator, then **Pay order**.
3. The payment drawer displays the provider, QR/approval link, financial state, order status, ledger availability, and timeline.
4. Choose **Simulate success**, or open the simulator link and approve there. After a few seconds, payment becomes successful, the order becomes paid, and the ledger records both an append-only PostgreSQL entry and a TigerBeetle double-entry transfer (`POSTED`).
5. Choose **Repeat request safely** to see the same intent returned. Repeated approval never creates another provider transaction or payment effect.
6. Sign in as Operations. In **Providers**, change routing priority/availability or select a fault scenario. In a finalized payment, choose **Replay callback ×3**, then inspect **Webhooks** for the delivery count.
7. Open **Ledger** to inspect real-time TigerBeetle double-entry clearing and merchant receivable account balances (`1001`, `1002`, `1003`, `2001/2002`, `3001`) alongside each transfer's posting status.
8. Open **Reconciliation**, select a settlement batch mode (`CLEAN`, `AMOUNT_MISMATCH`, `MISSING_IN_SETTLEMENT`, `MISSING_IN_LEDGER`, `DUPLICATE_SETTLEMENT`, or `STATUS_CONFLICT`), and choose **Import & Reconcile**. Inspect row-by-row comparisons and resolve any detected exceptions (`OPEN → INVESTIGATING → RESOLVED`) with an immutable audit note.
9. In **Overview**, inspect the **Asynchronous Reporting Projection** banner (`reporting.payments` consumer) and use Operations to pause/resume the projection to observe eventual-consistency lag against the authoritative ledger.

Provider scenarios apply only to new attempts: normal, duplicate callbacks, delayed callback, accepted-then-timeout, and definitive failure. The delayed callback may be overtaken by provider-status polling, intentionally demonstrating a second recovery path. A transport timeout never automatically means payment failure. An existing attempt keeps its provider when policy changes. Terminal failed intents are not reopened; create a new order for another simulated purchase.

The QR links to the local origin; it works in a browser on this machine. A phone cannot reach `127.0.0.1` on this computer. Phone/LAN access is not enabled in this deployment.

## Services and data ownership

| Container | Own database / store | Responsibility |
|---|---|---|
| gateway-identity | gateway_identity | Spring Cloud Gateway MVC, BCrypt login, signed JWT |
| order | order | Merchant orders and unique successful payment association |
| payment-intent | payment_intent | Idempotency, versioned state, history, persisted dispatch/recovery |
| routing-policy | routing_policy | Method eligibility, merchant priority and enabled-provider policy |
| provider-adapter | provider_adapter | UPI-A/UPI-B/generic simulators, stable attempt keys, callback jobs, independent settlement CSV export |
| webhook-ingestion | webhook_ingestion | Raw-body HMAC checks, durable inbox, duplicate/conflict detection |
| ledger-reconciliation | ledger_reconciliation + TigerBeetle | Append-only payment ledger, TigerBeetle double-entry postings, settlement import, reconciliation engine, audited exception workflow, async reporting projections |

Each service builds its own executable JAR/image and authenticates its APIs. They share small infrastructure/security helpers, not domain entities or databases. PostgreSQL contains seven databases with service-specific users and a restricted ledger runtime role (`SELECT, INSERT` only on `ledger_entries`). TigerBeetle (`0.16.19`) runs as a dedicated financial accounting engine accessed exclusively by `ledger-reconciliation`.

Implementation: Java 21 / Spring Boot 4.1.1 / Spring Cloud 2025.1.2 / Spring JDBC / Flyway / Spring Security / Spring AMQP / TigerBeetle Java Client 0.16.19; React / TypeScript / TanStack Query / Vite / bespoke responsive CSS. SQL transactions and explicit locking are used instead of the originally proposed JPA layer. Docker DNS/environment configuration is used instead of discovery/configuration servers.

## Correctness and recovery

- Merchant-scoped request keys are claimed in the same transaction as intent creation. Changed payloads conflict; another key for the same order still resolves to one intent.
- A provider request key is saved before calling the provider. If acceptance times out, query that same provider/key. Do not charge another provider blindly.
- Callbacks require a fresh timestamp and raw-body HMAC signature. Event-ID/content reuse conflicts rather than silently overwriting the original event.
- Payment transitions lock the intent row, check its version and validate merchant/order/amount/currency/provider references. Success cannot regress to processing. Conflicting terminal observations set review required.
- Local outcome/history/outbox writes commit together. Publishers check confirms and unroutable returns. Consumers commit deduplication with their effect before acknowledgement. Delivery can repeat; successful order/ledger effects are unique.
- RabbitMQ uses persistent messages, durable queues and consumer dead-letter queues. Outbox retries persist. Callback jobs and dispatch progress are persisted; no correctness depends on an HTTP request or in-memory task surviving.
- The ledger runtime role has only SELECT/INSERT permission on `ledger_entries`; a database trigger also rejects update/delete; there is no public mutation API. Each ledger entry is paired in the same local transaction with a durable `tigerbeetle_postings` outbox job using a deterministic 128-bit transfer ID (`payment_intent_id` UUID bits) that posts a double-entry transfer (debiting provider clearing `1001/1002/1003` and crediting merchant receivable `2001/2002`) to TigerBeetle with idempotent `Exists` handling.
- Settlement imports validate schema, currency, and `gross - fee == net` invariants, enforce SHA-256 content-hash idempotency per `reportId`, and classify every row into `MATCHED`, `AMOUNT_MISMATCH`, `MISSING_IN_SETTLEMENT`, `MISSING_IN_LEDGER`, `DUPLICATE_SETTLEMENT`, or `STATUS_CONFLICT`.

The UI intentionally shows payment/order/ledger independently because they converge asynchronously. Summary cards cover the latest 200 records, while the asynchronous reporting projection (`GET /reports/summary`) maintains merchant-level volume and method breakdowns updated via the `reporting.payments` queue.

## Verify

With the app running and Node.js installed:

```sh
node tests/api.integration.mjs
```

This runs **17 end-to-end integration checks** exercising the public gateway, PostgreSQL, RabbitMQ, and TigerBeetle: concurrent intent retries, tenant/role boundaries, concurrent approvals and duplicate signed callbacks, signature/content rejection, definitive failure, accepted-then-timeout recovery, generic routing, offline order/ledger consumers catching up, append-only ledger protection, TigerBeetle double-entry transfer posting & balance verification, clean settlement reconciliation + duplicate import idempotency, all five reconciliation anomaly classifications, audited operator exception resolution workflow (`OPEN → INVESTIGATING → RESOLVED`) with RBAC enforcement, and asynchronous reporting projection lag control.

Results are written to `tests/artifacts/api-results.json`. The course documents, recorded results, presentation deck, and 50 conceptual viva Q&As are in the sibling `../docs/` folder, which is deliberately outside this zip-ready application directory.

A local performance benchmark is also available:

```sh
node tests/performance.smoke.mjs --samples 30 --concurrency 5,10 --repeats 2
```

Run with all provider scenarios NORMAL. It creates records in the second merchant workspace and writes raw samples and timer definitions to `tests/artifacts/performance-results.json`. See `../docs/Demo-Guide.md` and `../docs/Final-Report.md` for the measured tables, evaluation framing, and live demo walkthrough.

Frontend typecheck/build:

```sh
npm ci --prefix frontend
npm run build --prefix frontend
```

Source contracts: `contracts/openapi.json`, `contracts/events.schema.json`. Architecture and final report: `../docs/Architecture.md`, `../docs/Final-Report.md`, `../docs/50-Conceptual-Questions.md`.

## Operate

```sh
docker compose ps
docker compose logs --tail=100 payment-intent webhook-ingestion ledger-reconciliation
docker compose restart payment-intent
# Stop without removing persisted data:
docker compose down
# Start existing images again:
docker compose up -d --wait
```

Do not use `docker compose down -v` unless deliberately deleting all local demo data. The initialization SQL runs only on a new PostgreSQL volume; subsequent changes use Flyway migrations. The frontend and gateway bind to loopback. Internal REST, database, TigerBeetle, and broker ports are not published to the host.