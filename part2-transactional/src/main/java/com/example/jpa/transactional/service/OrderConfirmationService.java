package com.example.jpa.transactional.service;

import com.example.jpa.transactional.entity.Order;
import com.example.jpa.transactional.entity.OrderStatus;
import com.example.jpa.transactional.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Separated service for order confirmation.
 * When called from FixedSelfInvocationService, the call goes through
 * the Spring proxy, and @Transactional works correctly.
 */
@Service
public class OrderConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmationService.class);

    private final OrderRepository orderRepository;

    public OrderConfirmationService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public void confirmOrder(Long orderId) {
        log.info("confirmOrder(): transaction active? {}",
                TransactionSynchronizationManager.isActualTransactionActive());
        // This will print true — the proxy is involved.

        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.CONFIRMED);
        // Dirty checking works. UPDATE is generated at commit.
    }
}
