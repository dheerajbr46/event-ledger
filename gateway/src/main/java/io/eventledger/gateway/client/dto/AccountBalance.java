package io.eventledger.gateway.client.dto;

import java.math.BigDecimal;

/** Wire contract for GET /accounts/{id}/balance on the Account Service. */
public record AccountBalance(String accountId, BigDecimal balance, String currency) {}
