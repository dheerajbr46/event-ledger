package com.example.gateway.web;

public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException(String eventId) {
        super("No event found with id " + eventId);
    }
}
