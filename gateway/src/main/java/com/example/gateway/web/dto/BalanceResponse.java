package com.example.gateway.web.dto;

import java.math.BigDecimal;

public record BalanceResponse(String accountId, BigDecimal balance, String currency) {}
