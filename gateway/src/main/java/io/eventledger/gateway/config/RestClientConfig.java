package io.eventledger.gateway.config;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    RestClient accountRestClient(
            ObservationRegistry observationRegistry,
            @Value("${gateway.account-service.base-url}") String baseUrl,
            @Value("${gateway.account-service.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${gateway.account-service.read-timeout-ms:2000}") long readTimeoutMs) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .observationRegistry(observationRegistry)
                .build();
    }
}
