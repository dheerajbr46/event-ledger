package io.eventledger.account.web;

import io.eventledger.account.repo.TransactionRepository;
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

    private final TransactionRepository repository;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            repository.count(); // lightweight DB round-trip
            return ResponseEntity.ok(
                    Map.of("service", "account-service", "status", "UP", "db", "UP"));
        } catch (Exception e) {
            log.warn("Health check failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("service", "account-service", "status", "DOWN", "db", "DOWN"));
        }
    }
}
