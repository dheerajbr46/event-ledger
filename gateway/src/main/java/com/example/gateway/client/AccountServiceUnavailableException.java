package com.example.gateway.client;

/** Raised when the Account Service is unreachable (circuit open or retries exhausted). -> 503. */
public class AccountServiceUnavailableException extends RuntimeException {
    public AccountServiceUnavailableException(String message) {
        super(message);
    }
}
