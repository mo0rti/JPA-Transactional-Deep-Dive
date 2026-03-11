package com.example.jpa.production.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Handles order events AFTER the transaction commits.
 *
 * This handler only runs if the transaction that published the event
 * committed successfully. If the transaction rolled back, this never fires.
 */
@Component
public class OrderEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderEventHandler.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("EVENT: Order {} committed successfully. Description: '{}'",
                event.orderId(), event.description());
        log.info("This is where you would send an email, publish to Kafka, etc.");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void handleOrderRollback(OrderCreatedEvent event) {
        log.info("EVENT: Order creation was rolled back. No side effects triggered.");
    }
}
