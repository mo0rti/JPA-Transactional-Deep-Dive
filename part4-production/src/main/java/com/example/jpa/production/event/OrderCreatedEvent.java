package com.example.jpa.production.event;

public record OrderCreatedEvent(Long orderId, String description) {
}
