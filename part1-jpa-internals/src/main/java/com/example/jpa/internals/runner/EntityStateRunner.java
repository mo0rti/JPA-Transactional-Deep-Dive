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
 * Demonstrates the four entity states: Transient, Managed, Detached, Removed.
 *
 * Run this and watch the console output to see exactly when SQL is generated
 * for each state transition.
 */
@Component
@Order(1)
public class EntityStateRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EntityStateRunner.class);

    private final EntityManager entityManager;

    public EntityStateRunner(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void run(String... args) {
        log.info("========== ENTITY STATE DEMO ==========");

        // --- TRANSIENT STATE ---
        log.info("--- Step 1: Creating a new Product (TRANSIENT) ---");
        Product product = new Product("Keyboard", new BigDecimal("49.99"));
        log.info("Product created: {}. No SQL executed. Hibernate does not know about it.", product);

        // --- MANAGED STATE via persist() ---
        log.info("--- Step 2: Calling persist() → TRANSIENT to MANAGED ---");
        entityManager.persist(product);
        log.info("persist() called. Product id={} (assigned from sequence). INSERT is NOT yet sent to DB.", product.getId());

        // Prove the entity is managed: Hibernate returns the same instance
        Product found = entityManager.find(Product.class, product.getId());
        log.info("find() returned same object? {} (no SELECT — served from Persistence Context)", product == found);

        // --- MANAGED STATE: dirty checking ---
        log.info("--- Step 3: Modifying a managed entity (dirty checking) ---");
        product.setPrice(new BigDecimal("59.99"));
        log.info("Price changed to 59.99. No save() call needed. Hibernate will detect this at flush.");

        // Force flush so we can see the SQL in order
        log.info("--- Step 4: Flushing to see INSERT + UPDATE ---");
        entityManager.flush();
        log.info("Flush complete. Check the SQL above: you should see INSERT then UPDATE.");

        // --- REMOVED STATE ---
        log.info("--- Step 5: Calling remove() → MANAGED to REMOVED ---");
        entityManager.remove(product);
        log.info("remove() called. DELETE is pending.");

        entityManager.flush();
        log.info("Flush complete. DELETE executed.");

        log.info("========== ENTITY STATE DEMO COMPLETE ==========\n");
    }
}
