# Event Ledger

Two cooperating microservices that ingest financial transaction events and maintain per-account balances.

- **Gateway** (`gateway`, port `8080`) — the only public-facing service. Accepts events, persists them, and forwards them to the Account Service through a resilient client.
- **Account Service** (`account-service`, port `8081`) — internal only. Applies transactions idempotently and computes balances. Never exposed to external clients.

Each service is an independent Spring Boot application with its **own embedded H2 database**. They share no database and no in-process state; they communicate only over HTTP.

```
client ──▶ Gateway (8080) ──HTTP──▶ Account Service (8081)
            │  H2: events             │  H2: transactions
            └─ circuit breaker + retry ┘
```

---

## Tech stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 25 (virtual threads enabled) |
| Framework | Spring Boot 4.1 (Spring MVC + JPA) |
| Datastore | H2, in-memory, one per service |
| Resilience | Resilience4j circuit breaker + Resilience4j `@Retry` + Resilience4j rate limiter |
| Tracing | Micrometer Tracing → OTLP → OpenTelemetry Collector → Jaeger; W3C `traceparent` propagation |
| Logging | Spring Boot native structured logging (ECS JSON) |
| Metrics | Micrometer + Prometheus endpoint |
| Resilience to outages | Scheduled replay job that re-applies events the Account Service missed |
| Contract testing | Pact consumer-driven contract between gateway (consumer) and account-service (provider) |
| Build | Maven (multi-module) |
| Boilerplate | Lombok (`@Slf4j`, `@RequiredArgsConstructor`) |

**Why MVC and not WebFlux:** the workload is correctness-bound, not throughput-bound, and the tracing requirement (trace IDs in MDC log lines) is trivial with thread-bound context under MVC but awkward in a reactive pipeline. Java 25 virtual threads (`spring.threads.virtual.enabled=true`) give the concurrency benefit without the reactive programming model.

---

## Prerequisites

- **JDK 25**
- **Maven 3.9+** (a wrapper is intentionally not bundled — run `mvn wrapper:wrapper` if you want one)
- **Docker + Docker Compose** (optional, for the containerised path)

---

## Running

### Option A — Docker Compose (recommended)

```bash
docker compose up --build
```

Gateway on `http://localhost:8080`, Account Service on `http://localhost:8081`. The gateway reaches the account service by container name via `ACCOUNT_SERVICE_URL`.

Compose also starts the tracing backend:

- **Jaeger UI** → `http://localhost:16686` — pick the `gateway` service and "Find Traces" to see a request span flow from the gateway into the account service (the two are linked by the propagated `traceparent`).
- **OpenTelemetry Collector** receives OTLP spans from both services on `4317`/`4318` and forwards them to Jaeger.

Send a request, then look it up in Jaeger:

```bash
curl -X POST http://localhost:8080/events -H 'Content-Type: application/json' \
  -d '{"eventId":"t1","accountId":"a1","type":"CREDIT","amount":10,"currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}'
```

### Option B — Maven, two terminals

```bash
mvn -pl account-service spring-boot:run     # terminal 1 (starts :8081)
mvn -pl gateway spring-boot:run             # terminal 2 (starts :8080)
```

The gateway defaults to `http://localhost:8081` for the account service when `ACCOUNT_SERVICE_URL` is unset.

### Tests

```bash
mvn test
```

---

## API

### Gateway (public)

```bash
# Submit an event (201 first time, 200 on idempotent replay)
curl -i -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{
        "eventId": "evt-1001",
        "accountId": "acct-42",
        "type": "CREDIT",
        "amount": 150.00,
        "currency": "USD",
        "eventTimestamp": "2026-05-15T14:02:11Z",
        "metadata": {"source": "atm"}
      }'

# Fetch one event
curl http://localhost:8080/events/evt-1001

# List events for an account (ordered by eventTimestamp)
curl "http://localhost:8080/events?account=acct-42"

# Balance (proxied to the Account Service; 503 if it is unreachable)
curl http://localhost:8080/accounts/acct-42/balance

# Health
curl http://localhost:8080/health
```

### Account Service (internal)

`POST /accounts/{accountId}/transactions`, `GET /accounts/{accountId}/balance`, `GET /accounts/{accountId}`, `GET /health`.

---

## Design decisions

### Idempotency
`eventId` is the **primary key** of the gateway's `events` table and carries a **unique constraint** in the account service's `transactions` table. Duplicate submission can therefore never create a second row. The gateway returns the original event on a duplicate (`200` instead of `201`); the account service returns the original transaction without touching the balance. Because retries can re-send a transaction, the account-service dedup is what makes retrying safe — the two features are designed together.

### Out-of-order tolerance
Balance is computed as `sum(CREDIT) − sum(DEBIT)` directly in the database, which is order-independent. Event listings are explicitly sorted by `eventTimestamp`, not by arrival or insertion order.

### Resiliency: circuit breaker + retry (the hybrid)
The Gateway→Account call is wrapped with **two patterns on two separate beans**:

- `AccountHttpCaller` (inner) — Resilience4j **`@Retry`** with exponential backoff and jitter, retrying only transient failures (`RemoteCallException`, raised on 5xx / I/O). The RestClient read timeout is the "timeout" half of timeout+retry.
- `AccountClient` (outer) — Resilience4j **`@CircuitBreaker`**. When the account service is repeatedly failing, the breaker opens and calls fail fast through a fallback.

They sit on different beans so the breaker wraps the retries: one logical operation counts as **one** breaker call regardless of how many times it was retried, which avoids a retry burst prematurely tripping the breaker. The breaker only records `RemoteCallException`, so a `4xx`/validation error never affects its state.

Both patterns are driven entirely by Resilience4j (`resilience4j-spring-boot4`), which auto-publishes circuit-breaker and retry metrics to Micrometer.

### Graceful degradation
- **POST /events** when the account service is down → `503` (not a hang or `500`). The event is still persisted (status `FAILED`), so it is retained for inspection and future replay.
- **GET /events**, **GET /events/{id}** → always served from the gateway's local store, unaffected by the account service.
- **GET /accounts/{id}/balance** → `503` with a clear `ProblemDetail` body when the account service is unreachable.

Event lifecycle at the gateway: `RECEIVED → APPLIED` on success, `RECEIVED → FAILED` when the account service can't be reached.

### Distributed tracing
`micrometer-tracing-bridge-otel` generates a trace ID at the gateway and propagates it to the account service via the W3C `traceparent` header (the `RestClient` is built with `RestClient.builder()` and the tracing bridge propagates W3C `traceparent` via Micrometer's observation instrumentation). Trace and span IDs land in the MDC and therefore in every structured log line in both services. Sampling is set to `1.0` for the demo.

### Structured logging
Spring Boot's native structured logging (`logging.structured.format.console: ecs`) emits JSON log lines including the MDC trace/span IDs — no logging dependency added.

### Metrics
At least one custom metric per service: `ledger.events` (gateway, tagged by outcome) and `ledger.transactions` (account service, tagged by outcome and type). Resilience4j additionally publishes circuit-breaker metrics. All are visible at `/actuator/metrics` and `/actuator/prometheus`.

### Health
Each service exposes a simple `GET /health` that performs a lightweight DB round-trip and reports `{service, status, db}`, alongside the richer Actuator `/actuator/health` (which includes the circuit-breaker health indicator on the gateway).

### Span visualisation (OTel Collector + Jaeger)
Both services export spans over OTLP to an OpenTelemetry Collector (`management.opentelemetry.tracing.export.otlp.endpoint`), which forwards them to Jaeger. A single submitted event produces one trace spanning both services, linked by the propagated `traceparent`. Metrics still go to Prometheus (OTLP metrics export is disabled to avoid double-publishing). Topology lives in `docker-compose.yml` + `otel-collector-config.yaml`.

### Async replay job
`ReplayScheduler` runs on a fixed delay and calls `EventService.replayPending(...)`, which re-applies events still in `RECEIVED`/`FAILED` (oldest first). While the breaker is open these fast-fail and the backlog waits; once the Account Service recovers, the next tick drains it. Replay is safe because the Account Service dedupes on `eventId`. Tunable via `gateway.replay.*`; single-instance assumption is noted in the class doc.

### Rate limiting
`RateLimitingFilter` throttles `POST /events` per client (source IP, honoring `X-Forwarded-For`) using a Resilience4j `RateLimiter` from the `default` config. Over the limit returns `429` with a `Retry-After` header and never blocks the request thread (timeout `0`). Tunable via `gateway.ratelimit.requests-per-second`.

### Consumer-driven contract tests (Pact)
The gateway (consumer `event-gateway`) declares the interactions it needs from the account-service (provider) in `AccountContractTest`, exercising the real HTTP client against a Pact mock server. The account-service verifies the committed contract (`src/test/resources/pacts/…json`) against the running app over HTTP in `AccountContractVerificationTest`. In production the consumer would publish to a Pact Broker rather than committing the file; the broker is what makes `can-i-deploy` gating across independent pipelines work.

---

## Tradeoffs and notes

- **Per-service DTOs, no shared module.** Wire DTOs are duplicated in each service rather than extracted into a shared library. This honors service independence (no shared build artifact coupling the two), at the cost of a little duplication. A shared `contracts` module would be the alternative if the contract churned often.
- **Added a Gateway balance endpoint.** The spec lists balance only under the (internal) Account Service but also requires graceful degradation for balance queries from clients. Since the account service is internal, the gateway proxies balance at `GET /accounts/{id}/balance`. This fills that gap while keeping the account service private.
- **In-memory H2.** State resets on restart. Swapping in a persistent datasource is a config change only.
- **Build verification.** Confirmed compiling and running on Spring Boot 4.1 / Spring Framework 7.0.8 / Jackson 3.1.4 / Java 25. Key dependency notes:
  - `resilience4j-spring-boot4` (v2.4.0) provides both `@CircuitBreaker` and `@Retry`; no separate spring-retry dependency is needed.
  - `RestClientConfig` uses `SimpleClientHttpRequestFactory` (from `spring-web`, in `java.base`) for connect/read timeouts — `ClientHttpRequestFactoryBuilder` is not used.
  - The OpenTelemetry OTLP exporter will log connection errors to `localhost:4318` when running outside Docker Compose; this is harmless.
  - **Pact** (`pact.version`, pinned to `4.6.17`): the most version-sensitive piece — confirm/bump if the consumer DSL or provider extension doesn't resolve (a 5.x line may supersede 4.6.x). The provider test uses `HttpTestTarget` to stay on the most stable code path.

---

## Bonus extensions — implemented

All four stretch goals are in place: **OTel Collector + Jaeger** span visualisation, the **scheduled replay job** for `FAILED`/`RECEIVED` events, **per-client rate limiting** at the gateway, and **Pact consumer-driven contract tests** between the two services. See the corresponding subsections under *Design decisions* above.

Still open if taken further: swap the committed pact for a Pact Broker with `can-i-deploy` gating; add Grafana/Prometheus dashboards alongside Jaeger; and move replay claiming to `SELECT … FOR UPDATE SKIP LOCKED` for multi-instance gateways.
