package io.eventledger.gateway.web;

import io.eventledger.gateway.service.EventService;
import io.eventledger.gateway.service.EventService.SubmitResult;
import io.eventledger.gateway.web.dto.BalanceResponse;
import io.eventledger.gateway.web.dto.EventResponse;
import io.eventledger.gateway.web.dto.SubmitEventRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping("/events")
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody SubmitEventRequest request) {
        SubmitResult result = eventService.submit(request);
        if (result.created()) {
            return ResponseEntity.created(URI.create("/events/" + result.event().eventId()))
                    .body(result.event());
        }
        return ResponseEntity.status(HttpStatus.OK).body(result.event()); // idempotent replay
    }

    @GetMapping("/events/{id}")
    public EventResponse getEvent(@PathVariable String id) {
        return eventService.getEvent(id);
    }

    // Reads depend only on the Gateway's local store, so they keep working if Account is down.
    @GetMapping("/events")
    public List<EventResponse> listByAccount(@RequestParam("account") String accountId) {
        return eventService.listByAccount(accountId);
    }

    // Proxied balance query. Returns 503 (via advice) when the Account Service is unreachable.
    @GetMapping("/accounts/{accountId}/balance")
    public BalanceResponse balance(@PathVariable String accountId) {
        return eventService.getBalance(accountId);
    }
}
