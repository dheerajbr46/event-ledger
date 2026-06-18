package com.example.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically drains the backlog of events that could not be applied while the Account Service was
 * unavailable. The circuit breaker does the heavy lifting: while the breaker is open these calls
 * fast-fail and the backlog simply waits; once the service recovers and the breaker closes, the
 * next tick clears the queue.
 *
 * <p>Single-instance assumption: in a multi-instance deployment you'd guard the batch with a
 * shedlock-style lock or a {@code SELECT ... FOR UPDATE SKIP LOCKED} claim so two gateways don't
 * replay the same rows. The Account Service dedup keeps it correct regardless, but the lock avoids
 * wasted calls.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gateway.replay.enabled", havingValue = "true", matchIfMissing = true)
public class ReplayScheduler {

    private final EventService eventService;

    @Value("${gateway.replay.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${gateway.replay.interval-ms:10000}")
    public void replayPendingEvents() {
        try {
            eventService.replayPending(batchSize);
        } catch (Exception e) {
            // Never let a replay failure kill the scheduler thread.
            log.warn("Replay pass failed: {}", e.getMessage());
        }
    }
}
