package com.example.gateway.domain;

/**
 * Lifecycle of an event at the Gateway.
 *
 * <ul>
 *   <li>{@code RECEIVED} – persisted locally, not yet applied to the account.
 *   <li>{@code APPLIED} – successfully applied by the Account Service.
 *   <li>{@code FAILED} – the Account Service could not be reached; eligible for later replay.
 * </ul>
 */
public enum EventStatus {
    RECEIVED,
    APPLIED,
    FAILED
}
