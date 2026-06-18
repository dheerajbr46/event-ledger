package io.eventledger.gateway.client;

import io.eventledger.gateway.client.dto.AccountBalance;
import io.eventledger.gateway.client.dto.ApplyTransactionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Performs the actual HTTP calls to the Account Service.
 *
 * <p>Retry lives here (inner), the circuit breaker lives in {@link AccountClient} (outer), so the
 * breaker sees one logical call per operation regardless of how many times we retry. Only transient
 * failures (5xx / I/O) are wrapped as {@link RemoteCallException}, which is the sole retryable and
 * breaker-recorded exception. A 4xx propagates unwrapped and is neither retried nor counted as an
 * availability failure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountHttpCaller {

    private final RestClient accountRestClient;

    @Retry(name = "accountService")
    public void applyTransaction(String accountId, ApplyTransactionRequest body) {
        try {
            accountRestClient
                    .post()
                    .uri("/accounts/{accountId}/transactions", accountId)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpServerErrorException e) {
            throw new RemoteCallException("Account Service returned " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new RemoteCallException("Account Service I/O failure", e);
        }
    }

    @Retry(name = "accountService")
    public AccountBalance getBalance(String accountId) {
        try {
            return accountRestClient
                    .get()
                    .uri("/accounts/{accountId}/balance", accountId)
                    .retrieve()
                    .body(AccountBalance.class);
        } catch (HttpServerErrorException e) {
            throw new RemoteCallException("Account Service returned " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new RemoteCallException("Account Service I/O failure", e);
        }
    }
}
