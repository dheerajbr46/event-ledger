package com.example.gateway.client.dto;

import com.example.gateway.domain.EventType;
import java.math.BigDecimal;
import java.time.Instant;

/** Wire contract for POST /accounts/{id}/transactions on the Account Service. */
public record ApplyTransactionRequest(
        String eventId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp) {}
