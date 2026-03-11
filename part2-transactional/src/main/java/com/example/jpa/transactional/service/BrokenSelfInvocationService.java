package com.example.jpa.transactional.service;

import com.example.jpa.transactional.entity.Order;
import com.example.jpa.transactional.entity.OrderStatus;
import com.example.jpa.transactional.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;

/**
 * Demonstrates the self-invocation trap.
 *
 * When processOrder() calls this.confirmOrder(), the call goes directly
 * to the real object — NOT through the Spring proxy. So @Transactional
 * on confirmOrder() has no effect.
 */
@Service
public class BrokenSelfInvocationService {

    private static final Logger log = LoggerFactory.getLogger(BrokenSelfInvocationService.class);

    private final OrderRepository orderRepository;

    public BrokenSelfInvocationService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * This method is NOT @Transactional.
     * It calls confirmOrder() which IS @Transactional — but via self-invocation.
     */
    public Order processOrder(String description, BigDecimal amount) {
        log.info("processOrder(): transaction active? {}",
                TransactionSynchronizationManager.isActualTransactionActive());

        Order order = new Order(description, amount);
        order = orderRepository.save(order); // repo has its own tx, commits immediately

        // Self-invocation: calls directly on 'this', bypassing the proxy
        this.confirmOrder(order.getId());

        return order;
    }

    @Transactional
    public void confirmOrder(Long orderId) {
        log.info("confirmOrder(): transaction active? {}",
                TransactionSynchronizationManager.isActualTransactionActive());

        // When called via self-invocation, there is NO transaction here.
        // The log above will print false.
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.CONFIRMED);
        // Without a wrapping transaction, the entity loaded by findById()
        // becomes detached immediately after the repository call returns.
        // This setStatus() change will NOT be persisted.
    }
}
