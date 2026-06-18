package com.example.gateway.client;

import com.example.gateway.client.dto.AccountBalance;
import com.example.gateway.client.dto.ApplyTransactionRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Circuit-breaker-protected facade over {@link AccountHttpCaller}. When the Account Service is
 * repeatedly failing, the breaker opens and calls fail fast via the fallback, which raises {@link
 * AccountServiceUnavailableException} (mapped to HTTP 503 by the controller advice).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountClient {

    private final AccountHttpCaller caller;

    @CircuitBreaker(name = "accountService", fallbackMethod = "applyFallback")
    public void applyTransaction(String accountId, ApplyTransactionRequest body) {
        caller.applyTransaction(accountId, body);
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "balanceFallback")
    public AccountBalance getBalance(String accountId) {
        return caller.getBalance(accountId);
    }

    @SuppressWarnings("unused")
    private void applyFallback(String accountId, ApplyTransactionRequest body, Throwable t) {
        if (isAvailabilityFailure(t)) {
            log.warn("Account Service unavailable applying eventId={}: {}", body.eventId(), t.toString());
            throw new AccountServiceUnavailableException("Account Service is unavailable");
        }
        throw new IllegalStateException("Unexpected failure calling Account Service", t);
    }

    @SuppressWarnings("unused")
    private AccountBalance balanceFallback(String accountId, Throwable t) {
        if (isAvailabilityFailure(t)) {
            log.warn("Account Service unavailable for balance accountId={}: {}", accountId, t.toString());
            throw new AccountServiceUnavailableException("Account Service is unavailable");
        }
        throw new IllegalStateException("Unexpected failure calling Account Service", t);
    }

    private boolean isAvailabilityFailure(Throwable t) {
        return t instanceof RemoteCallException || t instanceof CallNotPermittedException;
    }
}
