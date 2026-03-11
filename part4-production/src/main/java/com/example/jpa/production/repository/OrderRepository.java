package com.example.jpa.production.repository;

import com.example.jpa.production.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
