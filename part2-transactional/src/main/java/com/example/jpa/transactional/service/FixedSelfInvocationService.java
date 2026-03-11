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
 * The fix: split into two services so the proxy intercepts the call.
 * This class handles order creation, and delegates confirmation to
 * OrderConfirmationService.
 */
@Service
public class FixedSelfInvocationService {

    private static final Logger log = LoggerFactory.getLogger(FixedSelfInvocationService.class);

    private final OrderRepository orderRepository;
    private final OrderConfirmationService confirmationService;

    public FixedSelfInvocationService(OrderRepository orderRepository,
                                       OrderConfirmationService confirmationService) {
        this.orderRepository = orderRepository;
        this.confirmationService = confirmationService;
    }

    public Order processOrder(String description, BigDecimal amount) {
        log.info("processOrder(): transaction active? {}",
                TransactionSynchronizationManager.isActualTransactionActive());

        Order order = new Order(description, amount);
        order = orderRepository.save(order);

        // Calling through another bean → goes through the proxy → @Transactional works
        confirmationService.confirmOrder(order.getId());

        return order;
    }
}
