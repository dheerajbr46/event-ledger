package com.example.account.web.dto;

import com.example.account.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;

public record ApplyTransactionRequest(
        @NotBlank String eventId,
        @NotNull TransactionType type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency,
        @NotNull Instant eventTimestamp) {}
