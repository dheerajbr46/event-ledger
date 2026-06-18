# CLAUDE.md — Event Ledger

## Project layout

Multi-module Maven project. Two independently runnable Spring Boot 4.1 services:

| Module | Port | Role |
|---|---|---|
| `gateway` | 8080 | Public-facing. Accepts events, persists them, calls Account Service. |
| `account-service` | 8081 | Internal only. Applies transactions, computes balances. |

Each service has its own in-memory H2 database (state resets on restart).

## Build & run

```bash
# Two terminals — account-service must start first
mvn -pl account-service spring-boot:run   # terminal 1
mvn -pl gateway spring-boot:run           # terminal 2

# Or full stack with tracing (Jaeger at http://localhost:16686)
docker compose up --build

# Skip tests on build
mvn -pl gateway,account-service package -DskipTests
```

Maven may not be on PATH. Check `~/.m2/wrapper/dists/` for the binary if `mvn` is not found.

## Tests

```bash
mvn test                          # all modules
mvn -pl gateway test              # gateway only (WireMock-based integration tests)
mvn -pl account-service test      # account-service + Pact provider verification

# If Maven runs on JDK 21 instead of JDK 25:
mvn test -Djava.version=21
```

## Quick end-to-end check

```bash
# Option A — Docker Compose (services start automatically)
docker compose up --build -d
bash scripts/e2e.sh
docker compose down

# Option B — Maven (two terminals, account-service first)
mvn -pl account-service spring-boot:run   # terminal 1
mvn -pl gateway spring-boot:run           # terminal 2
bash scripts/e2e.sh                       # terminal 3
```

## Key env var

`ACCOUNT_SERVICE_URL` — gateway uses `http://localhost:8081` when unset.

---

## Spring Boot 4.1 / Framework 7 notes (things that tripped us up)

These are non-obvious and differ from Spring Boot 3.x — re-check here before editing these areas.

**`RestClient.Builder` is not auto-configured.**
Do not inject `RestClient.Builder` as a bean — it won't be found. Use `RestClient.builder()` static factory directly in `@Bean` methods.

**`org.springframework.resilience.annotation.*` does not exist.**
This package was hallucinated by the AI that generated the initial code. Retry is handled by Resilience4j `@Retry` (not spring-retry, not a Spring Framework native annotation).

**`spring-retry` and `spring-boot-starter-aop` are not BOM-managed.**
Adding them without a version causes `unknown` resolution errors. Use Resilience4j for both retry and circuit breaker — the `resilience4j-spring-boot4` starter covers both and is already on the classpath.

**`spring-boot-starter-aspectj` does not exist.**
The initial code referenced this hallucinated artifact. AOP is pulled in transitively by JPA and Resilience4j — no explicit AOP starter is needed.

**`org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder/Settings` does not resolve.**
Use `SimpleClientHttpRequestFactory` from `spring-web` instead. It lives in `java.base`, has no JPMS module graph issues, and supports `Duration`-based timeouts.

**`TestRestTemplate` does not exist in Spring Boot 4.1.**
`org.springframework.boot.test.web.client.TestRestTemplate` was removed. In `@SpringBootTest(webEnvironment = RANDOM_PORT)` tests, inject `@LocalServerPort int port`, build a plain `RestTemplate`, and suppress error-throwing with a no-op `DefaultResponseErrorHandler` so 4xx/5xx come back as `ResponseEntity` rather than exceptions — same behaviour as the old `TestRestTemplate`.

**Tracing (`traceparent`) requires explicit `ObservationRegistry` wiring.**
Since `RestClient.Builder` is not auto-configured, the Micrometer observation hook is not applied automatically. Inject `ObservationRegistry` into your `@Bean` method and call `.observationRegistry(registry)` on the builder, otherwise the W3C `traceparent` header is never added to outgoing requests and the tracing integration test will fail.

**Jackson 3 package names.**
Spring Boot 4 ships with Jackson 3. Package prefix changed from `com.fasterxml.jackson` to `tools.jackson`. The `JsonMapper` class is at `tools.jackson.databind.json.JsonMapper`.

**Maven `--release 25` fails on JDK 21.**
The root pom sets `<java.version>25</java.version>`. If Maven is running on JDK 21 (e.g. Corretto 21), compilation fails with `release version 25 not supported`. Override at the CLI: `mvn test -Djava.version=21`. IntelliJ is unaffected because it uses the project SDK directly.

**Lombok annotation processing breaks under JDK 25 in Docker.**
The Spring Boot 4.1 parent POM does not configure `annotationProcessorPaths`, so Lombok is picked up implicitly from the compile classpath. This works on JDK 21 but silently fails on JDK 25 (all `@Slf4j`/`@Getter`/`@Setter` symbols unresolved). Fixed by adding an explicit `<annotationProcessorPaths>` block to the `maven-compiler-plugin` in the root `pom.xml`. Without this, `docker compose up --build` fails at compile time.

## Resilience wiring (summary)

```
POST /events
  └─ AccountClient (@CircuitBreaker)           ← Resilience4j circuit breaker
       └─ AccountHttpCaller (@Retry)           ← Resilience4j retry (2 attempts, 200ms, 2× backoff)
            └─ RestClient → Account Service
```

Only `RemoteCallException` (wraps 5xx / I/O failures) is retried and counted by the breaker. 4xx errors propagate unwrapped and never affect breaker state.

## Actuator endpoints (gateway)

```
/actuator/health       — includes circuit breaker state
/actuator/prometheus   — ledger_events_total{outcome} custom metric
/actuator/metrics
```
