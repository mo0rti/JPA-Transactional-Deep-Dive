package com.example.jpa.internals.runner;

import com.example.jpa.internals.entity.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Demonstrates flush modes: AUTO vs COMMIT.
 *
 * AUTO (default): Hibernate flushes before queries (JPQL, Criteria, and native) when
 * there are pending changes that might affect the query results.
 * COMMIT: Hibernate only flushes at transaction commit — never before queries.
 */
@Component
@Order(3)
public class FlushModeRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FlushModeRunner.class);

    private final EntityManager entityManager;

    public FlushModeRunner(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void run(String... args) {
        log.info("========== FLUSH MODE DEMO ==========");

        // --- AUTO flush mode (default) ---
        log.info("--- Part A: AUTO flush mode ---");
        log.info("Current flush mode: {}", entityManager.getFlushMode());

        Product webcam = new Product("Webcam", new BigDecimal("79.99"));
        entityManager.persist(webcam);
        log.info("Persisted 'Webcam'. INSERT is pending (not sent to DB yet).");

        // JPQL query triggers auto-flush because it queries the same entity type
        log.info("Running JPQL query on Product table...");
        List<Product> jpqlResults = entityManager
                .createQuery("SELECT p FROM Product p WHERE p.name = :name", Product.class)
                .setParameter("name", "Webcam")
                .getResultList();
        log.info("JPQL found {} result(s). Auto-flush sent the INSERT before the SELECT.", jpqlResults.size());

        // Clean up for next demo
        entityManager.remove(webcam);
        entityManager.flush();
        entityManager.clear();

        // --- COMMIT flush mode ---
        log.info("\n--- Part B: COMMIT flush mode ---");
        entityManager.setFlushMode(FlushModeType.COMMIT);
        log.info("Flush mode changed to: {}", entityManager.getFlushMode());

        Product monitor = new Product("Monitor", new BigDecimal("299.99"));
        entityManager.persist(monitor);
        log.info("Persisted 'Monitor'. INSERT is pending.");

        // JPQL query does NOT trigger flush in COMMIT mode
        log.info("Running JPQL query on Product table (COMMIT mode)...");
        List<Product> commitResults = entityManager
                .createQuery("SELECT p FROM Product p WHERE p.name = :name", Product.class)
                .setParameter("name", "Monitor")
                .getResultList();
        log.info("JPQL found {} result(s). In COMMIT mode, no auto-flush before queries.", commitResults.size());
        log.info("The pending INSERT has NOT been sent to PostgreSQL yet.");

        // Restore default flush mode
        entityManager.setFlushMode(FlushModeType.AUTO);
        log.info("Flush mode restored to AUTO.");

        log.info("========== FLUSH MODE DEMO COMPLETE ==========\n");
        // Transaction commit will flush remaining changes
    }
}
