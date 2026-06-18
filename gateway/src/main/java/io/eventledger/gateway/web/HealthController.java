package io.eventledger.gateway.web;

import io.eventledger.gateway.repo.EventRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final EventRepository repository;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            repository.count();
            return ResponseEntity.ok(Map.of("service", "gateway", "status", "UP", "db", "UP"));
        } catch (Exception e) {
            log.warn("Health check failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("service", "gateway", "status", "DOWN", "db", "DOWN"));
        }
    }
}
