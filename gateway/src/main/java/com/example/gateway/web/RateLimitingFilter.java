package com.example.gateway.web;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Throttles event submission per client. Each client (keyed by source IP, honoring
 * {@code X-Forwarded-For}) gets its own Resilience4j {@link RateLimiter} built from the
 * {@code resilience4j.ratelimiter} "default" config. Over the limit returns {@code 429} immediately
 * (the limiter's timeout is 0, so it never blocks the request thread).
 *
 * <p>Scoped to {@code POST /events} only — reads and health checks are not throttled.
 */
@Component
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

    private final RateLimiterRegistry rateLimiterRegistry;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && "/events".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        RateLimiter limiter = rateLimiterRegistry.rateLimiter("client:" + clientKey(request));
        if (limiter.acquirePermission(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setHeader(HttpHeaders.RETRY_AFTER, "1");
            response.getWriter()
                    .write(
                            "{\"title\":\"Too Many Requests\",\"status\":429,"
                                    + "\"detail\":\"Rate limit exceeded for POST /events. Retry shortly.\"}");
        }
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
