package com.example.jpa.transactional.service;

import com.example.jpa.transactional.entity.Order;
import com.example.jpa.transactional.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Demonstrates readOnly = true optimizations.
 *
 * readOnly = true triggers:
 * 1. Hibernate skips dirty checking (no snapshots taken)
 * 2. Hibernate sets flush mode to MANUAL
 * 3. Spring sets JDBC connection to read-only
 * 4. PostgreSQL receives SET TRANSACTION READ ONLY
 */
@Service
@Transactional(readOnly = true) // Class-level default: all methods are read-only
public class ReadOnlyDemoService {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyDemoService.class);

    private final OrderRepository orderRepository;

    public ReadOnlyDemoService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Read-only transaction. Hibernate does not snapshot entities
     * and does not run dirty checking at the end.
     */
    public List<Order> findAll() {
        log.info("findAll() — readOnly=true, no dirty checking overhead");
        return orderRepository.findAll();
    }

    /**
     * Read-only transaction. Even if we modify the entity, the change
     * will NOT be persisted because flush mode is MANUAL and no flush
     * is triggered.
     */
    public Order findAndModify(Long id) {
        Order order = orderRepository.findById(id).orElseThrow();
        log.info("Loaded order: {}", order);

        order.setDescription("Modified in read-only transaction");
        log.info("Modified description. But this change will NOT be saved.");
        log.info("FlushMode is MANUAL in readOnly=true, so no flush occurs.");

        return order;
    }

    /**
     * Override class-level readOnly for this specific write method.
     */
    @Transactional // readOnly defaults to false, overriding the class-level
    public Order create(String description, BigDecimal amount) {
        log.info("create() — readOnly=false (overridden), writes are allowed");
        return orderRepository.save(new Order(description, amount));
    }
}
