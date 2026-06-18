package io.eventledger.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import io.eventledger.gateway.service.EventService;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTests {

    static final WireMockServer ACCOUNT;

    static {
        ACCOUNT = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        ACCOUNT.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.account-service.base-url", ACCOUNT::baseUrl);
        // Drive replay explicitly from the test rather than the background scheduler.
        registry.add("gateway.replay.enabled", () -> "false");
        // Neutralize the per-client rate limit (shared bean across test methods).
        registry.add("gateway.ratelimit.requests-per-second", () -> "1000000");
    }

    @AfterAll
    static void stop() {
        ACCOUNT.stop();
    }

    @LocalServerPort int port;
    @Autowired EventService eventService;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;

    RestTemplate rest;

    @BeforeEach
    void resetState() {
        rest = new RestTemplate();
        rest.setUriTemplateHandler(new DefaultUriBuilderFactory("http://localhost:" + port));
        // Don't throw on 4xx/5xx — let each test assert the status code directly.
        rest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) { return false; }
        });
        ACCOUNT.resetAll();
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
    }

    private Map<String, Object> event(String eventId, String account, String type, String amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventId", eventId);
        m.put("accountId", account);
        m.put("type", type);
        m.put("amount", new java.math.BigDecimal(amount));
        m.put("currency", "USD");
        m.put("eventTimestamp", "2026-05-15T14:02:11Z");
        return m;
    }

    @Test
    void fullFlow_submitIsIdempotentAcrossGatewayAndAccount() {
        ACCOUNT.stubFor(
                post(urlPathMatching("/accounts/.*/transactions"))
                        .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{}")));

        var first = rest.postForEntity("/events", event("evt-1", "acct-1", "CREDIT", "150.00"), Map.class);
        var second = rest.postForEntity("/events", event("evt-1", "acct-1", "CREDIT", "150.00"), Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK); // duplicate -> 200, no re-apply
        // The Account Service is only called once; the duplicate short-circuits at the Gateway.
        ACCOUNT.verify(1, postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    void traceparentHeaderPropagatesToAccountService() {
        ACCOUNT.stubFor(
                post(urlPathMatching("/accounts/.*/transactions"))
                        .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{}")));

        rest.postForEntity("/events", event("evt-trace", "acct-trace", "CREDIT", "10.00"), Map.class);

        ACCOUNT.verify(
                postRequestedFor(urlPathMatching("/accounts/.*/transactions"))
                        .withHeader("traceparent", matching(".+")));
    }

    @Test
    void circuitBreakerOpensWhenAccountKeepsFailing() {
        ACCOUNT.stubFor(post(urlPathMatching("/accounts/.*/transactions")).willReturn(aResponse().withStatus(500)));

        // Drive enough failing calls to satisfy minimumNumberOfCalls (5) at a 100% failure rate.
        for (int i = 0; i < 6; i++) {
            var resp = rest.postForEntity("/events", event("fail-" + i, "acct-x", "CREDIT", "5.00"), Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        int requestsAfterTrip = ACCOUNT.getAllServeEvents().size();

        // A further call should be short-circuited by the open breaker -> no new request reaches Account.
        var blocked = rest.postForEntity("/events", event("fail-after-open", "acct-x", "CREDIT", "5.00"), Map.class);
        int requestsAfterBlocked = ACCOUNT.getAllServeEvents().size();

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(requestsAfterBlocked).isEqualTo(requestsAfterTrip); // breaker is open, call fast-failed
    }

    @Test
    void readsStillWorkWhenAccountIsUnavailable() {
        ACCOUNT.stubFor(post(urlPathMatching("/accounts/.*/transactions")).willReturn(aResponse().withStatus(500)));

        // POST fails (503) but the event is still persisted locally.
        var post = rest.postForEntity("/events", event("read-1", "acct-read", "CREDIT", "20.00"), Map.class);
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // GET by id and list-by-account read only local data and must succeed.
        ResponseEntity<Map> byId = rest.getForEntity("/events/read-1", Map.class);
        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byId.getBody()).containsEntry("eventId", "read-1");

        ResponseEntity<List> list = rest.getForEntity("/events?account=acct-read", List.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).isNotEmpty();
    }

    // --- Validation ---

    @Test
    void blankEventId_returns400() {
        var req = event("", "acct-v", "CREDIT", "10.00");
        var resp = rest.postForEntity("/events", req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void negativeAmount_returns400() {
        var req = event("evt-neg", "acct-v", "CREDIT", "-5.00");
        var resp = rest.postForEntity("/events", req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void zeroAmount_returns400() {
        var req = event("evt-zero", "acct-v", "CREDIT", "0.00");
        var resp = rest.postForEntity("/events", req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownEventType_returns400() {
        var req = event("evt-type", "acct-v", "WIRE_TRANSFER", "10.00");
        var resp = rest.postForEntity("/events", req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingAccountId_returns400() {
        var req = event("evt-no-acct", "placeholder", "CREDIT", "10.00");
        req.remove("accountId");
        var resp = rest.postForEntity("/events", req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingCurrency_returns400() {
        var req = event("evt-no-curr", "acct-v", "CREDIT", "10.00");
        req.remove("currency");
        var resp = rest.postForEntity("/events", req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingEventTimestamp_returns400() {
        var req = event("evt-no-ts", "acct-v", "CREDIT", "10.00");
        req.remove("eventTimestamp");
        var resp = rest.postForEntity("/events", req, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- Read endpoints ---

    @Test
    void getUnknownEventId_returns404() {
        var resp = rest.getForEntity("/events/does-not-exist-xyz-99", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void eventListOrderedByEventTimestamp() {
        ACCOUNT.stubFor(
                post(urlPathMatching("/accounts/.*/transactions"))
                        .willReturn(aResponse().withStatus(201)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{}")));

        // Submit later timestamp first, then earlier — list must be chronological regardless.
        var late = event("order-late", "acct-ordering", "DEBIT", "10.00");
        late.put("eventTimestamp", "2026-05-15T16:00:00Z");
        var early = event("order-early", "acct-ordering", "CREDIT", "20.00");
        early.put("eventTimestamp", "2026-05-15T12:00:00Z");

        rest.postForEntity("/events", late, Map.class);
        rest.postForEntity("/events", early, Map.class);

        ResponseEntity<List> list = rest.getForEntity("/events?account=acct-ordering", List.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> events = list.getBody();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("eventId")).isEqualTo("order-early");
        assertThat(events.get(1).get("eventId")).isEqualTo("order-late");
    }

    @Test
    void replayAppliesPendingEventsOnceAccountRecovers() {
        // Account down -> POST returns 503 but the event is persisted as FAILED.
        ACCOUNT.stubFor(post(urlPathMatching("/accounts/.*/transactions")).willReturn(aResponse().withStatus(500)));
        var failed = rest.postForEntity("/events", event("replay-1", "acct-replay", "CREDIT", "12.00"), Map.class);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // Account recovers.
        ACCOUNT.resetAll();
        ACCOUNT.stubFor(
                post(urlPathMatching("/accounts/.*/transactions"))
                        .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{}")));

        // The replay pass (normally fired by the scheduler) re-applies the backlog.
        int applied = eventService.replayPending(50);
        assertThat(applied).isGreaterThanOrEqualTo(1);

        ResponseEntity<Map> byId = rest.getForEntity("/events/replay-1", Map.class);
        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byId.getBody()).containsEntry("status", "APPLIED");
    }
}
