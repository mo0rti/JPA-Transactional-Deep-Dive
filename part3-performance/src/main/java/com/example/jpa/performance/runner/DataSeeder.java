package com.example.jpa.performance.runner;

import com.example.jpa.performance.entity.Author;
import com.example.jpa.performance.entity.Book;
import com.example.jpa.performance.entity.Review;
import com.example.jpa.performance.repository.AuthorRepository;
import com.example.jpa.performance.repository.BookRepository;
import com.example.jpa.performance.repository.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final AuthorRepository authorRepository;
    private final BookRepository bookRepository;
    private final ReviewRepository reviewRepository;

    public DataSeeder(AuthorRepository authorRepository,
                      BookRepository bookRepository,
                      ReviewRepository reviewRepository) {
        this.authorRepository = authorRepository;
        this.bookRepository = bookRepository;
        this.reviewRepository = reviewRepository;
    }

    @Transactional
    public void seed() {
        log.info("========== SEEDING TEST DATA ==========");
        for (int i = 1; i <= 10; i++) {
            Author author = authorRepository.save(new Author("Author " + i));
            for (int j = 1; j <= 3; j++) {
                Book book = bookRepository.save(new Book("Book " + j + " by Author " + i, author));
                for (int k = 1; k <= 2; k++) {
                    reviewRepository.save(new Review("Review " + k, (k * 2) + 1, book));
                }
            }
        }
        log.info("Seeded 10 authors, 30 books, 60 reviews.\n");
    }
}
