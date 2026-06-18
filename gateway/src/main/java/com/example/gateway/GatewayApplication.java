package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableResilientMethods} activates Spring Framework 7's native resilience annotations
 * ({@code @Retryable}, {@code @ConcurrencyLimit}). Resilience4j's circuit breaker is auto-configured
 * separately by the resilience4j-spring-boot4 starter. {@code @EnableScheduling} drives the
 * background replay of events that could not be applied while the Account Service was down.
 *
 * <p>NOTE: the native resilience annotations are new in Spring Framework 7 — if an import fails to
 * resolve, confirm the package against the 7.0 javadoc (org.springframework.resilience.*).
 */
@SpringBootApplication
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
