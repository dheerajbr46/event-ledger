package io.eventledger.account.web;

import io.eventledger.account.service.AccountService;
import io.eventledger.account.service.AccountService.ApplyResult;
import io.eventledger.account.web.dto.AccountDetailsResponse;
import io.eventledger.account.web.dto.ApplyTransactionRequest;
import io.eventledger.account.web.dto.BalanceResponse;
import io.eventledger.account.web.dto.TransactionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> apply(
            @PathVariable String accountId, @Valid @RequestBody ApplyTransactionRequest request) {
        ApplyResult result = accountService.apply(accountId, request);
        // 201 when newly applied, 200 when this was a duplicate (idempotent replay).
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.transaction());
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse balance(@PathVariable String accountId) {
        return accountService.balance(accountId);
    }

    @GetMapping("/{accountId}")
    public AccountDetailsResponse details(@PathVariable String accountId) {
        return accountService.details(accountId);
    }
}
