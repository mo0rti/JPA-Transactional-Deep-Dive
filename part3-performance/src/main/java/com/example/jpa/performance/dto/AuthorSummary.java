package com.example.jpa.performance.dto;

/**
 * Interface-based projection for Author.
 * Hibernate creates a lightweight proxy — no entity management overhead.
 */
public interface AuthorSummary {
    String getName();
    long getBookCount();
}
