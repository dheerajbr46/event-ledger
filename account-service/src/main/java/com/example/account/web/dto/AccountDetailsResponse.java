package com.example.account.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record AccountDetailsResponse(
        String accountId,
        BigDecimal balance,
        int transactionCount,
        List<TransactionResponse> recentTransactions) {}
