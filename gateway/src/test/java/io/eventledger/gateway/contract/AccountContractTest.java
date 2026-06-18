package io.eventledger.gateway.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import io.eventledger.gateway.client.AccountHttpCaller;
import io.eventledger.gateway.client.dto.AccountBalance;
import io.eventledger.gateway.client.dto.ApplyTransactionRequest;
import io.eventledger.gateway.domain.EventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestClient;

/**
 * Consumer-driven contract test. The Gateway (consumer "event-gateway") declares the interactions
 * it expects from the Account Service (provider "account-service"). Pact stands up a mock server
 * from these declarations, and the real Gateway HTTP client ({@link AccountHttpCaller}) is exercised
 * against it. Running this generates target/pacts/event-gateway-account-service.json, which the
 * provider verifies (see the account-service AccountContractVerificationTest).
 *
 * <p>NOTE: the Pact DSL surface shifts a little across versions — if a builder method signature
 * doesn't match, align it with the pinned pact.version.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "account-service")
class AccountContractTest {

    @Pact(consumer = "event-gateway")
    V4Pact contract(PactDslWithProvider builder) {
        return builder
                .given("account acct-1 can accept transactions")
                .uponReceiving("apply a credit transaction")
                .path("/accounts/acct-1/transactions")
                .method("POST")
                .headers(Map.of("Content-Type", "application/json"))
                .body(
                        new PactDslJsonBody()
                                .stringType("eventId", "evt-contract-1")
                                .stringType("type", "CREDIT")
                                .numberType("amount", 150.00)
                                .stringType("currency", "USD")
                                .stringType("eventTimestamp", "2026-05-15T14:02:11Z"))
                .willRespondWith()
                .status(201)
                .given("account acct-1 can accept transactions")
                .uponReceiving("fetch the balance")
                .path("/accounts/acct-1/balance")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(
                        new PactDslJsonBody()
                                .stringType("accountId", "acct-1")
                                .numberType("balance", 150.00)
                                .stringType("currency", "USD"))
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "contract")
    void gatewayClientHonorsTheContract(MockServer mockServer) {
        RestClient client = RestClient.builder().baseUrl(mockServer.getUrl()).build();
        AccountHttpCaller caller = new AccountHttpCaller(client);

        assertThatNoException()
                .isThrownBy(
                        () ->
                                caller.applyTransaction(
                                        "acct-1",
                                        new ApplyTransactionRequest(
                                                "evt-contract-1",
                                                EventType.CREDIT,
                                                new BigDecimal("150.00"),
                                                "USD",
                                                Instant.parse("2026-05-15T14:02:11Z"))));

        AccountBalance balance = caller.getBalance("acct-1");
        assertThat(balance.accountId()).isEqualTo("acct-1");
        assertThat(balance.currency()).isEqualTo("USD");
        assertThat(balance.balance()).isNotNull();
    }
}
