package io.eventledger.account.service;

import io.eventledger.account.domain.Transaction;
import io.eventledger.account.domain.TransactionType;
import io.eventledger.account.repo.TransactionRepository;
import io.eventledger.account.web.dto.AccountDetailsResponse;
import io.eventledger.account.web.dto.ApplyTransactionRequest;
import io.eventledger.account.web.dto.BalanceResponse;
import io.eventledger.account.web.dto.TransactionResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private static final int RECENT_LIMIT = 10;
    private static final String DEFAULT_CURRENCY = "USD";

    private final TransactionRepository repository;
    private final MeterRegistry meterRegistry;

    /**
     * Applies a transaction idempotently. If a transaction with the same {@code eventId} already
     * exists, the original is returned unchanged and the balance is not affected.
     *
     * @return the applied (or pre-existing) transaction, and {@code true} if it was newly created
     */
    @Transactional
    public ApplyResult apply(String accountId, ApplyTransactionRequest request) {
        var existing = repository.findByEventId(request.eventId());
        if (existing.isPresent()) {
            log.info(
                    "Duplicate transaction ignored eventId={} accountId={}",
                    request.eventId(),
                    accountId);
            counter("duplicate", request.type()).increment();
            return new ApplyResult(TransactionResponse.from(existing.get()), false);
        }

        var tx =
                new Transaction(
                        request.eventId(),
                        accountId,
                        request.type(),
                        request.amount(),
                        request.currency(),
                        request.eventTimestamp());
        try {
            var saved = repository.save(tx);
            log.info(
                    "Applied transaction eventId={} accountId={} type={} amount={}",
                    saved.getEventId(),
                    accountId,
                    saved.getType(),
                    saved.getAmount());
            counter("applied", request.type()).increment();
            return new ApplyResult(TransactionResponse.from(saved), true);
        } catch (DataIntegrityViolationException raced) {
            // A concurrent request inserted the same eventId first; treat as a duplicate.
            var winner =
                    repository
                            .findByEventId(request.eventId())
                            .orElseThrow(() -> raced);
            counter("duplicate", request.type()).increment();
            return new ApplyResult(TransactionResponse.from(winner), false);
        }
    }

    @Transactional(readOnly = true)
    public BalanceResponse balance(String accountId) {
        BigDecimal balance = repository.computeBalance(accountId, TransactionType.CREDIT);
        return new BalanceResponse(accountId, balance, DEFAULT_CURRENCY);
    }

    @Transactional(readOnly = true)
    public AccountDetailsResponse details(String accountId) {
        BigDecimal balance = repository.computeBalance(accountId, TransactionType.CREDIT);
        List<Transaction> recent =
                repository.findByAccountId(
                        accountId,
                        Limit.of(RECENT_LIMIT),
                        Sort.by(Sort.Direction.DESC, "eventTimestamp"));
        long count = repository.countByAccountId(accountId);
        return new AccountDetailsResponse(
                accountId,
                balance,
                (int) count,
                recent.stream().map(TransactionResponse::from).toList());
    }

    private Counter counter(String outcome, TransactionType type) {
        return Counter.builder("ledger.transactions")
                .tag("outcome", outcome)
                .tag("type", type.name())
                .description("Transactions processed by the account service")
                .register(meterRegistry);
    }

    public record ApplyResult(TransactionResponse transaction, boolean created) {}
}
