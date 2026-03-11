package com.example.jpa.transactional.service;

import com.example.jpa.transactional.entity.Order;
import com.example.jpa.transactional.entity.OrderStatus;
import com.example.jpa.transactional.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Demonstrates @Modifying queries and their interaction with
 * @Transactional and the Persistence Context.
 */
@Service
public class ModifyingQueryDemoService {

    private static final Logger log = LoggerFactory.getLogger(ModifyingQueryDemoService.class);

    private final OrderRepository orderRepository;

    public ModifyingQueryDemoService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Shows the stale Persistence Context problem.
     *
     * updateStatusById() uses @Modifying WITHOUT clearAutomatically.
     * After the bulk UPDATE, the Persistence Context still holds the old entity.
     * A subsequent findById() returns the cached (stale) version.
     */
    @Transactional
    public void demonstrateStalePersistenceContext() {
        // Step 1: Create and save an order
        Order order = orderRepository.save(new Order("Modifying demo", new BigDecimal("25.00")));
        log.info("Created order id={} with status={}", order.getId(), order.getStatus());

        // Step 2: Bulk update via @Modifying (no clearAutomatically)
        int updated = orderRepository.updateStatusById(order.getId(), OrderStatus.CONFIRMED);
        log.info("@Modifying UPDATE affected {} row(s)", updated);

        // Step 3: Load the same entity — Persistence Context returns the CACHED version
        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        log.info("After @Modifying, findById returns status={} (STALE — still CREATED!)", reloaded.getStatus());
        log.info("The database says CONFIRMED, but Hibernate returned the cached entity.");
    }

    /**
     * Shows the correct approach with clearAutomatically.
     *
     * cancelStaleOrders() uses @Modifying(clearAutomatically = true).
     * After the bulk UPDATE, the Persistence Context is cleared.
     * A subsequent findById() goes to the database and returns fresh data.
     */
    @Transactional
    public void demonstrateClearAutomatically() {
        // Step 1: Create an order with a past timestamp
        Order order = orderRepository.save(new Order("Clear auto demo", new BigDecimal("30.00")));
        log.info("Created order id={} with status={}", order.getId(), order.getStatus());

        // Step 2: Bulk update via @Modifying(clearAutomatically = true)
        // We set the cutoff in the future so our order qualifies
        int updated = orderRepository.cancelStaleOrders(
                OrderStatus.CANCELLED,
                LocalDateTime.now().plusMinutes(1)
        );
        log.info("@Modifying(clearAutomatically=true) UPDATE affected {} row(s)", updated);

        // Step 3: Load the entity — Persistence Context was cleared, so this goes to the DB
        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        log.info("After @Modifying with clearAutomatically, findById returns status={} (FRESH!)", reloaded.getStatus());
    }

    /**
     * Shows the recommended approach: calling @Modifying queries from a
     * @Transactional service method.
     *
     * updateStatusById() has NO @Transactional on the repository method.
     * Custom @Query methods do NOT inherit transactional configuration from
     * SimpleJpaRepository — they get no transaction by default.
     *
     * This works because our service method provides the transaction.
     * The @Modifying query joins our existing transaction via REQUIRED propagation.
     *
     * Without this service-level @Transactional, calling updateStatusById()
     * directly would throw TransactionRequiredException.
     */
    @Transactional
    public void demonstrateModifyingInServiceTransaction() {
        Order order = orderRepository.save(new Order("Service tx demo", new BigDecimal("15.00")));
        log.info("Created order id={} with status={}", order.getId(), order.getStatus());

        // updateStatusById() has no @Transactional on the repository.
        // It works here because it joins our service-level transaction.
        int updated = orderRepository.updateStatusById(order.getId(), OrderStatus.CONFIRMED);
        log.info("Updated {} order(s) from within service @Transactional — no issues", updated);
    }
}
