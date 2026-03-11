package com.example.jpa.performance.repository;

import com.example.jpa.performance.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {
}
