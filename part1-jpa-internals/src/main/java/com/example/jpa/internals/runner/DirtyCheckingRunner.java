package com.example.jpa.internals.runner;

import com.example.jpa.internals.entity.Product;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Demonstrates dirty checking — how Hibernate automatically detects
 * changes to managed entities and generates UPDATE statements.
 *
 * Key takeaway: You do NOT need to call save() on managed entities.
 */
@Component
@Order(2)
public class DirtyCheckingRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DirtyCheckingRunner.class);

    private final EntityManager entityManager;

    public DirtyCheckingRunner(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void run(String... args) {
        log.info("========== DIRTY CHECKING DEMO ==========");

        // Insert a product first
        Product product = new Product("Mouse", new BigDecimal("29.99"));
        entityManager.persist(product);
        entityManager.flush();
        entityManager.clear(); // Clear context so the next find() hits the database
        log.info("Setup: Product persisted and context cleared.\n");

        // Load the product — Hibernate takes a snapshot of its state
        log.info("--- Step 1: Loading entity (Hibernate takes a snapshot) ---");
        Product loaded = entityManager.find(Product.class, product.getId());
        log.info("Loaded: {}. Hibernate snapshot: name='Mouse', price=29.99", loaded);

        // Modify the name — no save() call
        log.info("--- Step 2: Changing name (no save() call) ---");
        loaded.setName("Wireless Mouse");
        log.info("Name changed in Java object. No SQL yet.");

        // Modify the price too
        loaded.setPrice(new BigDecimal("39.99"));
        log.info("Price also changed. Still no SQL.");

        // Flush — Hibernate compares current state vs snapshot
        log.info("--- Step 3: Flushing — Hibernate runs dirty check ---");
        entityManager.flush();
        log.info("Check the SQL above: UPDATE with BOTH name and price (all columns updated by default).");

        // Now modify nothing and flush again — no UPDATE should appear
        log.info("--- Step 4: Flushing with no changes — no UPDATE expected ---");
        entityManager.flush();
        log.info("No UPDATE generated. Dirty checking found no differences.");

        log.info("========== DIRTY CHECKING DEMO COMPLETE ==========\n");
    }
}
