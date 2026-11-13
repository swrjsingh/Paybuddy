# PayBuddy

A working, local merchant-payment sandbox with seven independently deployable Spring Boot services. Create an order, start a simulated UPI or card/wallet payment, approve it, and watch the verified outcome update the order and append a ledger record.

**No real money is transferred.** TigerBeetle, settlement reconciliation, cloud deployment, Eureka/Config Server, and comprehensive performance benchmarking are endsem work. A preliminary local smoke benchmark is available. The current ledger is an append-only PostgreSQL payment-effect ledger, not double-entry accounting.

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
4. Choose **Simulate success**, or open the simulator link and approve there. After a few seconds, payment becomes successful, the order becomes paid, and the ledger has one entry.
5. Choose **Repeat request safely** to see the same intent returned. Repeated approval never creates another provider transaction or payment effect.
6. Sign in as Operations. In **Providers**, change routing priority/availability or select a fault scenario. In a finalized payment, choose **Replay callback ×3**, then inspect **Webhooks** for the delivery count.

Provider scenarios apply only to new attempts: normal, duplicate callbacks, delayed callback, accepted-then-timeout, and definitive failure. The delayed callback may be overtaken by provider-status polling, intentionally demonstrating a second recovery path. A transport timeout never automatically means payment failure. An existing attempt keeps its provider when policy changes. Terminal failed intents are not reopened; create a new order for another simulated purchase.

The QR links to the local origin; it works in a browser on this machine. A phone cannot reach `127.0.0.1` on this computer. Phone/LAN access is not enabled in this deployment.

## Services and data ownership

| Container | Own database | Responsibility |
|---|---|---|
| gateway-identity | gateway_identity | Spring Cloud Gateway MVC, BCrypt login, signed JWT |
| order | order | Merchant orders and unique successful payment association |
| payment-intent | payment_intent | Idempotency, versioned state, history, persisted dispatch/recovery |
| routing-policy | routing_policy | Method eligibility, merchant priority and enabled-provider policy |
| provider-adapter | provider_adapter | UPI-A/UPI-B/generic simulators, stable attempt keys, callback jobs |
| webhook-ingestion | webhook_ingestion | Raw-body HMAC checks, durable inbox, duplicate/conflict detection |
| ledger-reconciliation | ledger_reconciliation | Append-only successful-payment ledger; reconciliation pending |

Each service builds its own executable JAR/image and authenticates its APIs. They share small infrastructure/security helpers, not domain entities or databases. PostgreSQL contains seven databases with service-specific users and a restricted ledger runtime role. RabbitMQ and PostgreSQL remain single-instance infrastructure; persisted-volume restart recovery is implemented, cluster failover is not.

Implementation: Java 21 / Spring Boot 4.1.1 / Spring Cloud 2025.1.2 / Spring JDBC / Flyway / Spring Security / Spring AMQP; React / TypeScript / TanStack Query / Vite / bespoke responsive CSS. SQL transactions and explicit locking are used instead of the originally proposed JPA layer. Docker DNS/environment configuration is used instead of discovery/configuration servers for this milestone.

## Correctness and recovery

- Merchant-scoped request keys are claimed in the same transaction as intent creation. Changed payloads conflict; another key for the same order still resolves to one intent.
- A provider request key is saved before calling the provider. If acceptance times out, query that same provider/key. Do not charge another provider blindly.
- Callbacks require a fresh timestamp and raw-body HMAC signature. Event-ID/content reuse conflicts rather than silently overwriting the original event.
- Payment transitions lock the intent row, check its version and validate merchant/order/amount/currency/provider references. Success cannot regress to processing. Conflicting terminal observations set review required.
- Local outcome/history/outbox writes commit together. Publishers check confirms and unroutable returns. Consumers commit deduplication with their effect before acknowledgement. Delivery can repeat; successful order/ledger effects are unique.
- RabbitMQ uses persistent messages, durable queues and consumer dead-letter queues. Outbox retries persist. Callback jobs and dispatch progress are persisted; no correctness depends on an HTTP request or in-memory task surviving.
- The ledger runtime role has only SELECT/INSERT permission on financial entries; a database trigger also rejects update/delete; there is no public mutation API. A database administrator can change the schema/disable the trigger, so this is not tamper-proof storage.

The UI intentionally shows payment/order/ledger independently because they converge asynchronously. Summary cards cover the latest 200 records, not unrestricted all-time totals. The ledger API returns a list; an empty result is also returned for intents outside a merchant's workspace, avoiding information disclosure.

## Verify

With the app running and Node.js installed:

```sh
node tests/api.integration.mjs
```

This exercises the actual public gateway, PostgreSQL and RabbitMQ: concurrent intent retries, tenant/role boundaries, concurrent approvals and duplicate signed callbacks, signature/content rejection, definitive failure, accepted-then-timeout recovery, generic routing, offline order/ledger consumers catching up, and append-only ledger protection. It creates data in the second merchant workspace and temporarily stops/starts the Order and Ledger services. Avoid interacting with the demo during that restart scenario.

Results are written to `tests/artifacts/api-results.json`. The course documents, recorded results and browser verification are in the sibling `../docs/` folder, which is deliberately outside this zip-ready application directory. These are correctness checks.

A separate preliminary local performance baseline is available:

```sh
node tests/performance.smoke.mjs --samples 30 --concurrency 5,10 --repeats 2
```

Run with all provider scenarios NORMAL. It creates records in the second merchant workspace and writes raw samples and timer definitions to `tests/artifacts/performance-results.json`. This is a small closed-loop simulator benchmark, not maximum capacity or real-bank latency. See the external `../docs/Demo-Guide.md` for the measured table, evaluation framing and live demo walkthrough.

Frontend typecheck/build:

```sh
npm ci --prefix frontend
npm run build --prefix frontend
```

Source contracts: `contracts/openapi.json`, `contracts/events.schema.json`. Architecture and submission status: `../docs/Architecture.md`, `../docs/Midsem-Progress.md`.

## Operate

```sh
docker compose ps
docker compose logs --tail=100 payment-intent webhook-ingestion
docker compose restart payment-intent
# Stop without removing persisted data:
docker compose down
# Start existing images again:
docker compose up -d --wait
```

Do not use `docker compose down -v` unless deliberately deleting all local demo data. The initialization SQL runs only on a new PostgreSQL volume; subsequent changes use Flyway migrations. The frontend and gateway bind to loopback. Internal REST, database and broker ports are not published to the host.

## Next milestone

Use TigerBeetle through its Java client as the authoritative double-entry financial ledger, with stable transfer IDs and a durable PostgreSQL posting workflow to recover cross-database interruptions. Add independent provider settlement exports, imports, matching windows and audited exception resolution; complete reporting, monitoring and measured fault/load experiments. TigerBeetle does not establish external payment success or remove the need for reconciliation.

Progress ongoing