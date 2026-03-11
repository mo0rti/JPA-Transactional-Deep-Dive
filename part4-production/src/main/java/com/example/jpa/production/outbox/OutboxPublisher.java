package com.example.jpa.production.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Simulates the outbox publisher.
 *
 * In a real application, this would be a @Scheduled method that polls
 * the outbox table and publishes events to Kafka or another message broker.
 * Here we simulate it as a manual call for the demo.
 */
@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public int publishPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findBySentFalseOrderByCreatedAtAsc();
        log.info("Found {} pending outbox event(s)", pending.size());

        int published = 0;
        for (OutboxEvent event : pending) {
            // In production: kafkaTemplate.send(event.getAggregateType(), event.getPayload());
            log.info("  Publishing event: {} → simulated Kafka send", event);
            event.markSent();
            published++;
        }

        log.info("Published {} event(s)", published);
        return published;
    }
}
