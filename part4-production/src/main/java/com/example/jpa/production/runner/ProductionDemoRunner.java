package com.example.jpa.production.runner;

import com.example.jpa.production.entity.Account;
import com.example.jpa.production.entity.Order;
import com.example.jpa.production.outbox.OutboxPublisher;
import com.example.jpa.production.repository.OrderRepository;
import com.example.jpa.production.service.AccountService;
import com.example.jpa.production.service.OrderService;
import com.example.jpa.production.service.ProgrammaticTxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class ProductionDemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionDemoRunner.class);

    private final OrderService orderService;
    private final AccountService accountService;
    private final ProgrammaticTxService programmaticTxService;
    private final OutboxPublisher outboxPublisher;
    private final OrderRepository orderRepository;

    public ProductionDemoRunner(OrderService orderService,
                                 AccountService accountService,
                                 ProgrammaticTxService programmaticTxService,
                                 OutboxPublisher outboxPublisher,
                                 OrderRepository orderRepository) {
        this.orderService = orderService;
        this.accountService = accountService;
        this.programmaticTxService = programmaticTxService;
        this.outboxPublisher = outboxPublisher;
        this.orderRepository = orderRepository;
    }

    @Override
    public void run(String... args) {
        demoTransactionalEventListener();
        demoRollbackPreventsEvent();
        demoOutboxPattern();
        demoTransfer();
        demoProgrammaticTransactions();
    }

    private void demoTransactionalEventListener() {
        log.info("\n========== @TransactionalEventListener DEMO ==========");

        Order order = orderService.createOrderWithEvent("Laptop", new BigDecimal("999.99"));
        log.info("Order returned to caller: {}", order);
        log.info("Check log above — the AFTER_COMMIT event handler should have fired.");

        log.info("========== @TransactionalEventListener DEMO COMPLETE ==========\n");
    }

    private void demoRollbackPreventsEvent() {
        log.info("\n========== ROLLBACK PREVENTS EVENT DEMO ==========");

        try {
            orderService.createOrderThatFails("Doomed order", new BigDecimal("1.00"));
        } catch (RuntimeException e) {
            log.info("Transaction rolled back: {}", e.getMessage());
        }
        log.info("Check log above — the AFTER_COMMIT event handler should NOT have fired.");
        log.info("The AFTER_ROLLBACK handler should have fired instead.");

        log.info("========== ROLLBACK PREVENTS EVENT DEMO COMPLETE ==========\n");
    }

    private void demoOutboxPattern() {
        log.info("\n========== OUTBOX PATTERN DEMO ==========");

        orderService.createOrderWithOutbox("Keyboard", new BigDecimal("79.99"));
        orderService.createOrderWithOutbox("Mouse", new BigDecimal("49.99"));
        log.info("Two orders created with outbox events in the same transaction.");

        log.info("\nSimulating outbox publisher (in production this would be @Scheduled)...");
        int published = outboxPublisher.publishPendingEvents();
        log.info("Published {} outbox event(s).", published);

        log.info("========== OUTBOX PATTERN DEMO COMPLETE ==========\n");
    }

    private void demoTransfer() {
        log.info("\n========== ACCOUNT TRANSFER DEMO ==========");

        Account alice = accountService.createAccount("Alice", new BigDecimal("1000.00"));
        Account bob = accountService.createAccount("Bob", new BigDecimal("500.00"));
        log.info("Created accounts: {} and {}", alice, bob);

        accountService.transfer(alice.getId(), bob.getId(), new BigDecimal("250.00"));

        Account aliceAfter = accountService.findById(alice.getId());
        Account bobAfter = accountService.findById(bob.getId());
        log.info("After transfer: Alice={}, Bob={}", aliceAfter.getBalance(), bobAfter.getBalance());

        log.info("========== ACCOUNT TRANSFER DEMO COMPLETE ==========\n");
    }

    private void demoProgrammaticTransactions() {
        log.info("\n========== PROGRAMMATIC TRANSACTION DEMO ==========");

        // Get all orders from previous demos
        List<Order> orders = programmaticTxService.findAllOrders();
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        log.info("Found {} orders to process", orderIds.size());

        // Process each in its own transaction
        programmaticTxService.processOrdersIndividually(orderIds);

        log.info("========== PROGRAMMATIC TRANSACTION DEMO COMPLETE ==========\n");
    }
}
