package io.eventledger.gateway.client.dto;

import io.eventledger.gateway.domain.EventType;
import java.math.BigDecimal;
import java.time.Instant;

/** Wire contract for POST /accounts/{id}/transactions on the Account Service. */
public record ApplyTransactionRequest(
        String eventId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp) {}
