package com.example.jpa.production.service;

import com.example.jpa.production.entity.Order;
import com.example.jpa.production.event.OrderCreatedEvent;
import com.example.jpa.production.outbox.OutboxEvent;
import com.example.jpa.production.outbox.OutboxEventRepository;
import com.example.jpa.production.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Demonstrates:
 * - @TransactionalEventListener (event published after commit)
 * - Outbox pattern (event stored in same transaction as business data)
 */
@Service
@Transactional(readOnly = true)
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository,
                        OutboxEventRepository outboxEventRepository,
                        ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Creates an order and publishes a Spring event.
     * The event handler runs ONLY after the transaction commits.
     */
    @Transactional(rollbackFor = Exception.class)
    public Order createOrderWithEvent(String description, BigDecimal amount) {
        Order order = orderRepository.save(new Order(description, amount));
        log.info("Order saved: {}", order);

        // Publish event — NOT delivered yet, waits for commit
        eventPublisher.publishEvent(new OrderCreatedEvent(order.getId(), description));
        log.info("Event published (pending commit).");

        return order;
    }

    /**
     * Creates an order and stores an outbox event in the SAME transaction.
     * Both commit or both rollback — guaranteed consistency.
     */
    @Transactional(rollbackFor = Exception.class)
    public Order createOrderWithOutbox(String description, BigDecimal amount) {
        Order order = orderRepository.save(new Order(description, amount));
        log.info("Order saved: {}", order);

        OutboxEvent outboxEvent = new OutboxEvent(
                "Order", order.getId().toString(),
                "OrderCreated",
                "{\"orderId\":" + order.getId() + ",\"description\":\"" + description + "\"}"
        );
        outboxEventRepository.save(outboxEvent);
        log.info("Outbox event saved in same transaction: {}", outboxEvent);

        return order;
    }

    /**
     * Creates an order that fails — demonstrates rollback prevents event delivery.
     */
    @Transactional(rollbackFor = Exception.class)
    public Order createOrderThatFails(String description, BigDecimal amount) {
        Order order = orderRepository.save(new Order(description, amount));
        eventPublisher.publishEvent(new OrderCreatedEvent(order.getId(), description));

        // Simulate failure
        throw new RuntimeException("Simulated failure — order and event should both be rolled back");
    }
}
