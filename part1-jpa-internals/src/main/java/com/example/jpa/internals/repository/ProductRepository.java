package com.example.jpa.internals.repository;

import com.example.jpa.internals.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
