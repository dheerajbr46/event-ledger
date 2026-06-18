package io.eventledger.account;

import static org.assertj.core.api.Assertions.assertThat;

import io.eventledger.account.domain.TransactionType;
import io.eventledger.account.service.AccountService;
import io.eventledger.account.web.dto.ApplyTransactionRequest;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AccountServiceTests {

    @Autowired AccountService accountService;

    private ApplyTransactionRequest req(
            String eventId, TransactionType type, String amount, String isoTs) {
        return new ApplyTransactionRequest(
                eventId, type, new BigDecimal(amount), "USD", Instant.parse(isoTs));
    }

    @Test
    void appliesCreditAndDebitToNetBalance() {
        String acct = "acct-balance";
        accountService.apply(acct, req("e1", TransactionType.CREDIT, "150.00", "2026-05-15T14:02:11Z"));
        accountService.apply(acct, req("e2", TransactionType.DEBIT, "40.00", "2026-05-15T14:05:00Z"));

        assertThat(accountService.balance(acct).balance()).isEqualByComparingTo("110.00");
    }

    @Test
    void duplicateEventIdDoesNotChangeBalance() {
        String acct = "acct-dup";
        var first = accountService.apply(acct, req("dup-1", TransactionType.CREDIT, "100.00", "2026-05-15T14:02:11Z"));
        var second = accountService.apply(acct, req("dup-1", TransactionType.CREDIT, "100.00", "2026-05-15T14:02:11Z"));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse(); // recognised as a duplicate
        assertThat(accountService.balance(acct).balance()).isEqualByComparingTo("100.00");
    }

    @Test
    void balanceIsIndependentOfArrivalOrder() {
        String acct = "acct-order";
        // Apply the later-timestamped event first, then the earlier one.
        accountService.apply(acct, req("o2", TransactionType.DEBIT, "30.00", "2026-05-15T15:00:00Z"));
        accountService.apply(acct, req("o1", TransactionType.CREDIT, "80.00", "2026-05-15T14:00:00Z"));

        assertThat(accountService.balance(acct).balance()).isEqualByComparingTo("50.00");
    }
}
