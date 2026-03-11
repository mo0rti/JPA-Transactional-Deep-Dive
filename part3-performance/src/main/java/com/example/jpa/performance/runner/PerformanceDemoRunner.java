package com.example.jpa.performance.runner;

import com.example.jpa.performance.service.NPlus1ProblemService;
import com.example.jpa.performance.service.OptimizedService;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;

/**
 * Runs all Part 3 demos sequentially.
 *
 * run() is NOT @Transactional. Each service method runs in its own
 * transaction, so query counts are accurate and the Persistence Context
 * is not shared between demos.
 */
@Component
public class PerformanceDemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PerformanceDemoRunner.class);

    private final DataSeeder dataSeeder;
    private final NPlus1ProblemService nPlus1Service;
    private final OptimizedService optimizedService;
    private final EntityManager entityManager;

    public PerformanceDemoRunner(DataSeeder dataSeeder,
                                  NPlus1ProblemService nPlus1Service,
                                  OptimizedService optimizedService,
                                  EntityManager entityManager) {
        this.dataSeeder = dataSeeder;
        this.nPlus1Service = nPlus1Service;
        this.optimizedService = optimizedService;
        this.entityManager = entityManager;
    }

    @Override
    public void run(String... args) {
        dataSeeder.seed();
        Statistics stats = getStatistics();

        demoNPlus1(stats);
        demoJoinFetch(stats);
        demoEntityGraph(stats);
        demoDtoProjection(stats);
        demoPagination(stats);
    }

    private Statistics getStatistics() {
        SessionFactory sessionFactory = entityManager
                .getEntityManagerFactory()
                .unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        return stats;
    }

    private void demoNPlus1(Statistics stats) {
        log.info("========== N+1 PROBLEM DEMO ==========");
        stats.clear();

        nPlus1Service.getAllAuthorsWithBooks_NPlus1();

        long prepareCount = stats.getPrepareStatementCount();
        log.info("RESULT: {} SQL statements prepared", prepareCount);
        log.info("Expected: 11 statements (1 for authors + 10 for books)");
        log.info("========== N+1 DEMO COMPLETE ==========\n");
    }

    private void demoJoinFetch(Statistics stats) {
        log.info("========== JOIN FETCH FIX ==========");
        stats.clear();

        optimizedService.getAllAuthorsWithBooks_JoinFetch();

        long prepareCount = stats.getPrepareStatementCount();
        log.info("RESULT: {} SQL statement(s) (down from 11!)", prepareCount);
        log.info("========== JOIN FETCH COMPLETE ==========\n");
    }

    private void demoEntityGraph(Statistics stats) {
        log.info("========== @EntityGraph FIX ==========");
        stats.clear();

        optimizedService.getAllAuthorsWithBooks_EntityGraph();

        long prepareCount = stats.getPrepareStatementCount();
        log.info("RESULT: {} SQL statement(s)", prepareCount);
        log.info("========== @EntityGraph COMPLETE ==========\n");
    }

    private void demoDtoProjection(Statistics stats) {
        log.info("========== DTO PROJECTION ==========");
        stats.clear();

        log.info("--- Interface projection ---");
        optimizedService.getAllAuthorSummaries();

        log.info("\n--- Class projection ---");
        optimizedService.getAllAuthorWithBookCounts();

        long prepareCount = stats.getPrepareStatementCount();
        log.info("RESULT: {} SQL statements for both projections (no entity overhead)", prepareCount);
        log.info("========== DTO PROJECTION COMPLETE ==========\n");
    }

    private void demoPagination(Statistics stats) {
        log.info("========== PAGINATED FETCH ==========");
        stats.clear();

        optimizedService.getAuthorsWithBooksPaginated(PageRequest.of(0, 3));

        long prepareCount = stats.getPrepareStatementCount();
        log.info("RESULT: {} SQL statements (count + IDs + full fetch)", prepareCount);
        log.info("========== PAGINATED FETCH COMPLETE ==========\n");
    }
}
