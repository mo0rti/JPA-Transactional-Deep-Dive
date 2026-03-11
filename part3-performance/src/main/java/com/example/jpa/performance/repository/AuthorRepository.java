package com.example.jpa.performance.repository;

import com.example.jpa.performance.dto.AuthorSummary;
import com.example.jpa.performance.dto.AuthorWithBookCount;
import com.example.jpa.performance.entity.Author;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuthorRepository extends JpaRepository<Author, Long> {

    // --- JOIN FETCH examples ---

    @Query("SELECT DISTINCT a FROM Author a LEFT JOIN FETCH a.books")
    List<Author> findAllWithBooks();

    @Query("SELECT a FROM Author a LEFT JOIN FETCH a.books WHERE a.id = :id")
    Optional<Author> findByIdWithBooks(@Param("id") Long id);

    // --- @EntityGraph example ---

    @EntityGraph(attributePaths = {"books"})
    @Query("SELECT a FROM Author a")
    List<Author> findAllWithBooksEntityGraph();

    @EntityGraph(attributePaths = {"books", "books.reviews"})
    @Query("SELECT a FROM Author a WHERE a.id = :id")
    Optional<Author> findByIdWithBooksAndReviews(@Param("id") Long id);

    // --- DTO Projections ---

    @Query("SELECT a.name AS name, SIZE(a.books) AS bookCount FROM Author a")
    List<AuthorSummary> findAllSummaries();

    @Query("SELECT new com.example.jpa.performance.dto.AuthorWithBookCount(a.name, SIZE(a.books)) FROM Author a")
    List<AuthorWithBookCount> findAllWithBookCounts();

    // --- Pagination support (two-query approach) ---

    @Query("SELECT a.id FROM Author a")
    Page<Long> findAllIds(Pageable pageable);

    @Query("SELECT DISTINCT a FROM Author a LEFT JOIN FETCH a.books WHERE a.id IN :ids")
    List<Author> findAllWithBooksByIds(@Param("ids") List<Long> ids);
}
