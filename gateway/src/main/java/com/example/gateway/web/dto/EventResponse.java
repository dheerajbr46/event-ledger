package com.example.gateway.web.dto;

import com.example.gateway.domain.Event;
import com.example.gateway.domain.EventStatus;
import com.example.gateway.domain.EventType;
import java.math.BigDecimal;
import java.time.Instant;

public record EventResponse(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        EventStatus status,
        Instant receivedAt,
        Instant appliedAt) {

    public static EventResponse from(Event e) {
        return new EventResponse(
                e.getEventId(),
                e.getAccountId(),
                e.getType(),
                e.getAmount(),
                e.getCurrency(),
                e.getEventTimestamp(),
                e.getStatus(),
                e.getReceivedAt(),
                e.getAppliedAt());
    }
}
