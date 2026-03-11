package com.example.jpa.transactional.service;

import com.example.jpa.transactional.entity.AuditEvent;
import com.example.jpa.transactional.entity.Order;
import com.example.jpa.transactional.entity.OrderStatus;
import com.example.jpa.transactional.repository.AuditEventRepository;
import com.example.jpa.transactional.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Demonstrates REQUIRED vs REQUIRES_NEW propagation.
 *
 * Scenario: Creating an order and logging an audit event.
 * The audit event should be saved even if the order creation fails.
 */
@Service
public class PropagationDemoService {

    private static final Logger log = LoggerFactory.getLogger(PropagationDemoService.class);

    private final OrderRepository orderRepository;
    private final AuditService auditService;

    public PropagationDemoService(OrderRepository orderRepository, AuditService auditService) {
        this.orderRepository = orderRepository;
        this.auditService = auditService;
    }

    /**
     * Creates an order and logs an audit event.
     * If this method fails after the audit is logged, the audit survives
     * because it runs in REQUIRES_NEW.
     */
    @Transactional
    public Order createOrderWithAudit(String description, BigDecimal amount, boolean shouldFail) {
        log.info("--- Creating order in OUTER transaction ---");
        Order order = orderRepository.save(new Order(description, amount));

        // Audit runs in its own transaction (REQUIRES_NEW)
        auditService.logEvent("Order created: " + order.getId());
        log.info("Audit logged in separate transaction.");

        if (shouldFail) {
            log.info("Simulating failure AFTER audit was committed...");
            throw new RuntimeException("Simulated failure — order will be rolled back, but audit survives!");
        }

        order.setStatus(OrderStatus.CONFIRMED);
        return order;
    }

    /**
     * Demonstrates REQUIRED (default): inner method joins the outer transaction.
     * If the inner method fails, the entire transaction is doomed.
     */
    @Transactional
    public Order createOrderJoined(String description, BigDecimal amount) {
        log.info("--- Creating order (REQUIRED propagation) ---");
        Order order = orderRepository.save(new Order(description, amount));

        try {
            auditService.logEventJoined("Order created: " + order.getId());
            // This runs in the SAME transaction.
        } catch (RuntimeException e) {
            log.warn("Caught exception from joined method: {}", e.getMessage());
            // Even though we caught the exception, the transaction is
            // ALREADY marked for rollback. Commit will fail.
        }

        return order;
    }
}
