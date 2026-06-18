package io.eventledger.gateway.client;

/** Thrown on a transient failure calling the Account Service (5xx or I/O). Retryable. */
public class RemoteCallException extends RuntimeException {
    public RemoteCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
