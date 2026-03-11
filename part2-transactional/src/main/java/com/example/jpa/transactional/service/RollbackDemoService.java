package com.example.jpa.transactional.service;

import com.example.jpa.transactional.entity.Order;
import com.example.jpa.transactional.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Demonstrates rollback behavior with checked vs unchecked exceptions.
 *
 * Key insight: @Transactional only rolls back on unchecked exceptions by default.
 * Checked exceptions cause a COMMIT — which is usually not what you want.
 */
@Service
public class RollbackDemoService {

    private static final Logger log = LoggerFactory.getLogger(RollbackDemoService.class);

    private final OrderRepository orderRepository;

    public RollbackDemoService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Throws a CHECKED exception. The transaction COMMITS (not rolls back).
     * This is the dangerous default behavior.
     */
    @Transactional
    public Order createWithCheckedException() throws Exception {
        Order order = orderRepository.save(new Order("Checked exception order", new BigDecimal("100")));
        log.info("Order saved: {}", order);

        // Simulate a checked exception
        throw new Exception("Checked exception — transaction will COMMIT, not rollback!");
        // The order IS saved to the database. This is usually a bug.
    }

    /**
     * Throws an UNCHECKED exception. The transaction ROLLS BACK (correct default).
     */
    @Transactional
    public Order createWithUncheckedException() {
        Order order = orderRepository.save(new Order("Unchecked exception order", new BigDecimal("200")));
        log.info("Order saved: {}", order);

        // Simulate an unchecked exception
        throw new RuntimeException("Unchecked exception — transaction will ROLLBACK.");
        // The order is NOT saved to the database. Correct behavior.
    }

    /**
     * The fix: use rollbackFor to roll back on checked exceptions too.
     */
    @Transactional(rollbackFor = Exception.class)
    public Order createWithRollbackFor() throws Exception {
        Order order = orderRepository.save(new Order("RollbackFor order", new BigDecimal("300")));
        log.info("Order saved: {}", order);

        throw new Exception("Checked exception — but rollbackFor catches it. Transaction ROLLS BACK.");
        // The order is NOT saved to the database. Correct behavior with rollbackFor.
    }
}
