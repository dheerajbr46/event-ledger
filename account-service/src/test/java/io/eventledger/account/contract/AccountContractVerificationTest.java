package io.eventledger.account.contract;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Provider side of the contract test. Boots the real Account Service on a random port and replays
 * each interaction from the committed pact (src/test/resources/pacts) against it over HTTP,
 * verifying the responses satisfy what the Gateway expects.
 *
 * <p>The pact is committed here for a self-contained repo. In a real setup the consumer would
 * publish it to a Pact Broker and this test would load it from there (swap @PactFolder for
 * @PactBroker), enabling can-i-deploy checks across independent pipelines.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Provider("account-service")
@PactFolder("pacts")
class AccountContractVerificationTest {

    @Value("${local.server.port}")
    int port;

    @BeforeEach
    void setTarget(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @State("account acct-1 can accept transactions")
    void accountCanAcceptTransactions() {
        // No setup needed: a fresh in-memory account accepts a new credit and reports a numeric
        // balance, which is all the contract asserts. Idempotency/ordering invariants are covered
        // by the service and integration tests.
    }
}
