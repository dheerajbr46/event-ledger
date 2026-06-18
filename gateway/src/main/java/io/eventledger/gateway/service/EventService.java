package io.eventledger.gateway.service;

import io.eventledger.gateway.client.AccountClient;
import io.eventledger.gateway.client.AccountServiceUnavailableException;
import io.eventledger.gateway.client.dto.AccountBalance;
import io.eventledger.gateway.client.dto.ApplyTransactionRequest;
import io.eventledger.gateway.domain.Event;
import io.eventledger.gateway.domain.EventStatus;
import io.eventledger.gateway.repo.EventRepository;
import io.eventledger.gateway.web.dto.BalanceResponse;
import io.eventledger.gateway.web.dto.EventResponse;
import io.eventledger.gateway.web.dto.SubmitEventRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository repository;
    private final AccountClient accountClient;
    private final MeterRegistry meterRegistry;
    private final JsonMapper jsonMapper;

    /**
     * Submits an event. Idempotent on {@code eventId}: an event already applied is returned as-is; a
     * previously received-but-not-applied event is re-driven (safe, because the Account Service also
     * dedupes on {@code eventId}); a new event is persisted then applied.
     *
     * @throws AccountServiceUnavailableException if the Account Service cannot be reached
     */
    public SubmitResult submit(SubmitEventRequest request) {
        var existing = repository.findById(request.eventId());
        if (existing.isPresent() && existing.get().getStatus() == EventStatus.APPLIED) {
            log.info("Duplicate event ignored eventId={}", request.eventId());
            counter("duplicate").increment();
            return new SubmitResult(EventResponse.from(existing.get()), false);
        }

        boolean isNew = existing.isEmpty();
        Event event = existing.orElseGet(() -> repository.save(toEntity(request)));

        try {
            accountClient.applyTransaction(event.getAccountId(), toClientRequest(event));
            event.setStatus(EventStatus.APPLIED);
            event.setAppliedAt(Instant.now());
            repository.save(event);
            log.info("Event applied eventId={} accountId={}", event.getEventId(), event.getAccountId());
            counter(isNew ? "applied" : "reapplied").increment();
            return new SubmitResult(EventResponse.from(event), isNew);
        } catch (AccountServiceUnavailableException e) {
            event.setStatus(EventStatus.FAILED);
            repository.save(event); // retained locally for replay; reads still work
            counter("failed").increment();
            throw e;
        }
    }

    public EventResponse getEvent(String eventId) {
        return repository
                .findById(eventId)
                .map(EventResponse::from)
                .orElseThrow(() -> new io.eventledger.gateway.web.EventNotFoundException(eventId));
    }

    public List<EventResponse> listByAccount(String accountId) {
        return repository
                .findByAccountId(accountId, Sort.by(Sort.Direction.ASC, "eventTimestamp"))
                .stream()
                .map(EventResponse::from)
                .toList();
    }

    public BalanceResponse getBalance(String accountId) {
        AccountBalance b = accountClient.getBalance(accountId);
        return new BalanceResponse(b.accountId(), b.balance(), b.currency());
    }

    /**
     * Re-applies events that are still {@code RECEIVED}/{@code FAILED} (the Account Service was down
     * when they arrived). Safe to run repeatedly: the Account Service dedupes on {@code eventId}, so
     * a replay never double-counts. Stops the batch at the first availability failure — if the
     * service is still down, the remaining events would fail too and are left for the next run.
     *
     * @return the number of events successfully applied in this pass
     */
    public int replayPending(int batchSize) {
        List<Event> pending =
                repository.findByStatusIn(
                        List.of(EventStatus.RECEIVED, EventStatus.FAILED),
                        Limit.of(batchSize),
                        Sort.by(Sort.Direction.ASC, "eventTimestamp"));
        if (pending.isEmpty()) {
            return 0;
        }

        int applied = 0;
        for (Event event : pending) {
            try {
                accountClient.applyTransaction(event.getAccountId(), toClientRequest(event));
                event.setStatus(EventStatus.APPLIED);
                event.setAppliedAt(Instant.now());
                repository.save(event);
                counter("replayed").increment();
                applied++;
            } catch (AccountServiceUnavailableException stillDown) {
                log.debug("Replay halted, Account Service still unavailable after {} applied", applied);
                break;
            }
        }
        if (applied > 0) {
            log.info("Replay pass applied {} of {} pending events", applied, pending.size());
        }
        return applied;
    }

    private Event toEntity(SubmitEventRequest r) {
        return new Event(
                r.eventId(),
                r.accountId(),
                r.type(),
                r.amount(),
                r.currency(),
                r.eventTimestamp(),
                serializeMetadata(r));
    }

    private ApplyTransactionRequest toClientRequest(Event e) {
        return new ApplyTransactionRequest(
                e.getEventId(), e.getType(), e.getAmount(), e.getCurrency(), e.getEventTimestamp());
    }

    private String serializeMetadata(SubmitEventRequest r) {
        if (r.metadata() == null || r.metadata().isEmpty()) {
            return null;
        }
        try {
            // Jackson 3 throws unchecked exceptions; guard so bad metadata never fails the request.
            return jsonMapper.writeValueAsString(r.metadata());
        } catch (RuntimeException ex) {
            log.warn("Could not serialize metadata for eventId={}", r.eventId());
            return null;
        }
    }

    private Counter counter(String outcome) {
        return Counter.builder("ledger.events")
                .tag("outcome", outcome)
                .description("Events processed by the gateway")
                .register(meterRegistry);
    }

    public record SubmitResult(EventResponse event, boolean created) {}
}
