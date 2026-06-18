package com.example.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A transaction applied to an account.
 *
 * <p>{@code eventId} carries a unique constraint: this is the idempotency key. The Gateway may
 * re-send the same transaction (e.g. a retry after a timeout), so duplicate applies are rejected
 * at the database level and the original row is returned instead of double-counting the balance.
 */
@Entity
@Table(
        name = "transactions",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(columnNames = "event_id"),
        indexes = {
            @Index(name = "idx_tx_account", columnList = "account_id"),
            @Index(name = "idx_tx_event_ts", columnList = "event_timestamp")
        })
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private String eventId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    public Transaction(
            String eventId,
            String accountId,
            TransactionType type,
            BigDecimal amount,
            String currency,
            Instant eventTimestamp) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.appliedAt = Instant.now();
    }
}
