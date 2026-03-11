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
 * Demonstrates the differences between persist(), merge(), and Spring Data's save().
 *
 * Key takeaways:
 * - persist(): makes a transient entity managed. The original object IS the managed one.
 * - merge(): returns a NEW managed copy. The original stays detached.
 * - save(): calls persist() if entity is new (id is null), merge() otherwise.
 */
@Component
@Order(4)
public class PersistVsMergeRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PersistVsMergeRunner.class);

    private final EntityManager entityManager;

    public PersistVsMergeRunner(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void run(String... args) {
        log.info("========== PERSIST vs MERGE DEMO ==========");

        // --- persist() ---
        log.info("--- Part A: persist() ---");
        Product headset = new Product("Headset", new BigDecimal("89.99"));
        log.info("Before persist: id={}", headset.getId());

        entityManager.persist(headset);
        log.info("After persist: id={} (assigned from sequence immediately)", headset.getId());
        log.info("The original object IS managed. contains()={}", entityManager.contains(headset));

        entityManager.flush();

        // --- merge() with a detached entity ---
        log.info("\n--- Part B: merge() with detached entity ---");
        entityManager.clear(); // Detach everything
        log.info("Context cleared. 'headset' is now DETACHED. contains()={}", entityManager.contains(headset));

        // Modify the detached entity
        headset.setPrice(new BigDecimal("99.99"));
        log.info("Modified detached entity price to 99.99");

        // merge() returns a NEW managed copy
        Product managedCopy = entityManager.merge(headset);
        log.info("merge() called.");
        log.info("  Original (headset)  is managed? {}", entityManager.contains(headset));
        log.info("  Returned (managedCopy) is managed? {}", entityManager.contains(managedCopy));
        log.info("  Same object? {}", headset == managedCopy);
        log.info("  IMPORTANT: The original stays detached. Use the returned copy!");

        entityManager.flush();
        log.info("Flush: check SQL — Hibernate did a SELECT (to load into context) then UPDATE.");

        // --- The common merge() mistake ---
        log.info("\n--- Part C: The common merge() mistake ---");
        entityManager.clear();
        headset.setName("Pro Headset");
        entityManager.merge(headset);
        // MISTAKE: continuing to modify the original detached object
        headset.setName("Ultra Headset");
        log.info("Modified ORIGINAL after merge. This change will be LOST.");

        entityManager.flush();
        // Verify: the database has "Pro Headset", NOT "Ultra Headset"
        entityManager.clear();
        Product fromDb = entityManager.find(Product.class, headset.getId());
        log.info("Value in DB: '{}'. The 'Ultra Headset' change was lost because we modified the detached copy.",
                fromDb.getName());

        log.info("========== PERSIST vs MERGE DEMO COMPLETE ==========\n");
    }
}
