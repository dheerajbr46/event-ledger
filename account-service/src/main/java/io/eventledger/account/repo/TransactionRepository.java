package io.eventledger.account.repo;

import io.eventledger.account.domain.Transaction;
import io.eventledger.account.domain.TransactionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByEventId(String eventId);

    List<Transaction> findByAccountId(String accountId, Sort sort);

    List<Transaction> findByAccountId(String accountId, Limit limit, Sort sort);

    long countByAccountId(String accountId);

    /**
     * Net balance = sum(CREDIT) - sum(DEBIT). Computed in the database so the result is independent
     * of the order in which transactions arrived (summation is commutative).
     */
    @Query(
            """
            select coalesce(sum(case when t.type = :credit then t.amount else -t.amount end), 0)
            from Transaction t
            where t.accountId = :accountId
            """)
    BigDecimal computeBalance(
            @Param("accountId") String accountId, @Param("credit") TransactionType credit);
}
