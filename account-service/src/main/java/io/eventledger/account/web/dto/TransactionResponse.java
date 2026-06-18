package io.eventledger.account.web.dto;

import io.eventledger.account.domain.Transaction;
import io.eventledger.account.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TransactionResponse(
        String eventId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Instant appliedAt) {

    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getEventId(),
                t.getAccountId(),
                t.getType(),
                t.getAmount(),
                t.getCurrency(),
                t.getEventTimestamp(),
                t.getAppliedAt());
    }
}
