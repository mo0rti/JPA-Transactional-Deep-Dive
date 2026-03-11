package com.example.jpa.performance.repository;

import com.example.jpa.performance.entity.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BookRepository extends JpaRepository<Book, Long> {

    @Query("SELECT DISTINCT b FROM Book b LEFT JOIN FETCH b.reviews WHERE b.author.id = :authorId")
    List<Book> findByAuthorIdWithReviews(@Param("authorId") Long authorId);
}
