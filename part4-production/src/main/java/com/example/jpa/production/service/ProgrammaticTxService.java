package com.example.jpa.production.service;

import com.example.jpa.production.entity.Order;
import com.example.jpa.production.entity.OrderStatus;
import com.example.jpa.production.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Demonstrates programmatic transaction management with TransactionTemplate.
 *
 * Useful when you need per-item transactions in a loop, or when you need
 * to mix transactional and non-transactional work in the same method.
 */
@Service
public class ProgrammaticTxService {

    private static final Logger log = LoggerFactory.getLogger(ProgrammaticTxService.class);

    private final TransactionTemplate txTemplate;
    private final TransactionTemplate readOnlyTxTemplate;
    private final OrderRepository orderRepository;

    public ProgrammaticTxService(PlatformTransactionManager txManager,
                                  OrderRepository orderRepository) {
        this.txTemplate = new TransactionTemplate(txManager);

        this.readOnlyTxTemplate = new TransactionTemplate(txManager);
        this.readOnlyTxTemplate.setReadOnly(true);

        this.orderRepository = orderRepository;
    }

    /**
     * Processes orders one at a time, each in its own transaction.
     * If one fails, the others are not affected.
     */
    public void processOrdersIndividually(List<Long> orderIds) {
        log.info("Processing {} orders individually...", orderIds.size());
        int successCount = 0;
        int failCount = 0;

        for (Long orderId : orderIds) {
            try {
                txTemplate.executeWithoutResult(status -> {
                    Order order = orderRepository.findById(orderId).orElseThrow();
                    log.info("  Processing order: {}", order);
                    order.setStatus(OrderStatus.PROCESSED);
                    // Transaction commits when lambda returns
                });
                successCount++;
            } catch (Exception e) {
                log.error("  Failed to process order {}: {}", orderId, e.getMessage());
                failCount++;
            }
        }

        log.info("Batch complete: {} succeeded, {} failed", successCount, failCount);
    }

    /**
     * Demonstrates read-only TransactionTemplate.
     */
    public List<Order> findAllOrders() {
        return readOnlyTxTemplate.execute(status -> orderRepository.findAll());
    }
}
