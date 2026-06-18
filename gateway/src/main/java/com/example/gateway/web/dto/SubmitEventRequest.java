package com.example.gateway.web.dto;

import com.example.gateway.domain.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record SubmitEventRequest(
        @NotBlank String eventId,
        @NotBlank String accountId,
        @NotNull EventType type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency,
        @NotNull Instant eventTimestamp,
        Map<String, Object> metadata) {}
