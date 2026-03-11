package com.example.jpa.transactional.runner;

import com.example.jpa.transactional.entity.Order;
import com.example.jpa.transactional.repository.AuditEventRepository;
import com.example.jpa.transactional.repository.OrderRepository;
import com.example.jpa.transactional.service.BrokenSelfInvocationService;
import com.example.jpa.transactional.service.FixedSelfInvocationService;
import com.example.jpa.transactional.service.ModifyingQueryDemoService;
import com.example.jpa.transactional.service.PropagationDemoService;
import com.example.jpa.transactional.service.ReadOnlyDemoService;
import com.example.jpa.transactional.service.RollbackDemoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class TransactionalDemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TransactionalDemoRunner.class);

    private final BrokenSelfInvocationService brokenService;
    private final FixedSelfInvocationService fixedService;
    private final ModifyingQueryDemoService modifyingService;
    private final PropagationDemoService propagationService;
    private final ReadOnlyDemoService readOnlyService;
    private final RollbackDemoService rollbackService;
    private final OrderRepository orderRepository;
    private final AuditEventRepository auditRepository;

    public TransactionalDemoRunner(BrokenSelfInvocationService brokenService,
                                    FixedSelfInvocationService fixedService,
                                    ModifyingQueryDemoService modifyingService,
                                    PropagationDemoService propagationService,
                                    ReadOnlyDemoService readOnlyService,
                                    RollbackDemoService rollbackService,
                                    OrderRepository orderRepository,
                                    AuditEventRepository auditRepository) {
        this.brokenService = brokenService;
        this.fixedService = fixedService;
        this.modifyingService = modifyingService;
        this.propagationService = propagationService;
        this.readOnlyService = readOnlyService;
        this.rollbackService = rollbackService;
        this.orderRepository = orderRepository;
        this.auditRepository = auditRepository;
    }

    @Override
    public void run(String... args) {
        demoSelfInvocation();
        demoPropagation();
        demoReadOnly();
        demoRollback();
        demoModifyingQueries();
    }

    private void demoSelfInvocation() {
        log.info("\n========== SELF-INVOCATION DEMO ==========");

        log.info("--- Broken: self-invocation bypasses proxy ---");
        brokenService.processOrder("Broken order", new BigDecimal("50.00"));
        // Check: confirmOrder's @Transactional was bypassed

        log.info("\n--- Fixed: separate service goes through proxy ---");
        fixedService.processOrder("Fixed order", new BigDecimal("75.00"));
        // Check: confirmOrder's @Transactional worked correctly

        log.info("========== SELF-INVOCATION DEMO COMPLETE ==========\n");
    }

    private void demoPropagation() {
        log.info("\n========== PROPAGATION DEMO ==========");

        log.info("--- REQUIRES_NEW: audit survives outer rollback ---");
        try {
            propagationService.createOrderWithAudit("Doomed order", new BigDecimal("100.00"), true);
        } catch (RuntimeException e) {
            log.info("Outer transaction rolled back: {}", e.getMessage());
        }
        log.info("Orders in DB: {}", orderRepository.count());
        log.info("Audit events in DB: {}", auditRepository.count());
        log.info("The order was rolled back, but the audit event survived!\n");

        log.info("--- REQUIRED: joined transaction is doomed if inner fails ---");
        try {
            propagationService.createOrderJoined("Joined order", new BigDecimal("200.00"));
        } catch (Exception e) {
            log.info("Transaction failed: {}", e.getMessage());
        }
        log.info("Orders after joined test: {}", orderRepository.count());

        log.info("========== PROPAGATION DEMO COMPLETE ==========\n");
    }

    private void demoReadOnly() {
        log.info("\n========== READ-ONLY DEMO ==========");

        // Create an order first (write transaction)
        Order order = readOnlyService.create("ReadOnly test order", new BigDecimal("99.99"));
        log.info("Created order: {}", order);

        // Modify in read-only transaction — change will NOT be saved
        readOnlyService.findAndModify(order.getId());

        // Verify the modification was NOT persisted
        Order reloaded = readOnlyService.findAll().stream()
                .filter(o -> o.getId().equals(order.getId()))
                .findFirst()
                .orElseThrow();
        log.info("After read-only modification, description is still: '{}'", reloaded.getDescription());

        log.info("========== READ-ONLY DEMO COMPLETE ==========\n");
    }

    private void demoRollback() {
        log.info("\n========== ROLLBACK DEMO ==========");
        long countBefore = orderRepository.count();

        log.info("--- Checked exception: transaction COMMITS (dangerous!) ---");
        try {
            rollbackService.createWithCheckedException();
        } catch (Exception e) {
            log.info("Exception caught: {}", e.getMessage());
        }
        long countAfterChecked = orderRepository.count();
        log.info("Orders added: {} (committed despite exception!)\n", countAfterChecked - countBefore);

        log.info("--- Unchecked exception: transaction ROLLS BACK (correct) ---");
        try {
            rollbackService.createWithUncheckedException();
        } catch (RuntimeException e) {
            log.info("Exception caught: {}", e.getMessage());
        }
        long countAfterUnchecked = orderRepository.count();
        log.info("Orders added: {} (rolled back correctly)\n", countAfterUnchecked - countAfterChecked);

        log.info("--- rollbackFor = Exception.class: checked exception ROLLS BACK (fixed!) ---");
        try {
            rollbackService.createWithRollbackFor();
        } catch (Exception e) {
            log.info("Exception caught: {}", e.getMessage());
        }
        long countAfterRollbackFor = orderRepository.count();
        log.info("Orders added: {} (rolled back correctly with rollbackFor)", countAfterRollbackFor - countAfterUnchecked);

        log.info("========== ROLLBACK DEMO COMPLETE ==========\n");
    }

    private void demoModifyingQueries() {
        log.info("\n========== @MODIFYING QUERY DEMO ==========");

        log.info("--- Stale Persistence Context (without clearAutomatically) ---");
        modifyingService.demonstrateStalePersistenceContext();

        log.info("\n--- Fixed with clearAutomatically ---");
        modifyingService.demonstrateClearAutomatically();

        log.info("\n--- @Modifying inside service @Transactional ---");
        modifyingService.demonstrateModifyingInServiceTransaction();

        log.info("========== @MODIFYING QUERY DEMO COMPLETE ==========\n");
    }
}
