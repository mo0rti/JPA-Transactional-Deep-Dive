package com.example.jpa.performance.dto;

/**
 * Class-based DTO projection for Author.
 * Constructed directly from JPQL using the constructor expression (SELECT new ...).
 */
public class AuthorWithBookCount {

    private final String name;
    private final long bookCount;

    public AuthorWithBookCount(String name, long bookCount) {
        this.name = name;
        this.bookCount = bookCount;
    }

    public String getName() {
        return name;
    }

    public long getBookCount() {
        return bookCount;
    }

    @Override
    public String toString() {
        return "AuthorWithBookCount{name='" + name + "', bookCount=" + bookCount + "}";
    }
}
