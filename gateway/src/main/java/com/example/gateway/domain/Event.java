package com.example.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An event received by the Gateway. {@code eventId} is the primary key, which makes the idempotency
 * guarantee a property of the schema: the same event can never produce two rows.
 */
@Entity
@Table(
        name = "events",
        indexes = @Index(name = "idx_event_account_ts", columnList = "account_id, event_timestamp"))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Lob
    @Column(name = "metadata_json")
    private String metadataJson;

    public Event(
            String eventId,
            String accountId,
            EventType type,
            BigDecimal amount,
            String currency,
            Instant eventTimestamp,
            String metadataJson) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadataJson = metadataJson;
        this.status = EventStatus.RECEIVED;
        this.receivedAt = Instant.now();
    }
}
