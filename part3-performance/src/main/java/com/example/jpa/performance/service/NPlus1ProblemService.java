package com.example.jpa.performance.service;

import com.example.jpa.performance.entity.Author;
import com.example.jpa.performance.repository.AuthorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Demonstrates the N+1 problem.
 *
 * This service loads all authors and accesses their books,
 * triggering a separate SQL query for each author's book collection.
 */
@Service
public class NPlus1ProblemService {

    private static final Logger log = LoggerFactory.getLogger(NPlus1ProblemService.class);

    private final AuthorRepository authorRepository;

    public NPlus1ProblemService(AuthorRepository authorRepository) {
        this.authorRepository = authorRepository;
    }

    /**
     * BAD: N+1 queries.
     * 1 query to load all authors + N queries to load each author's books.
     */
    @Transactional(readOnly = true)
    public List<Author> getAllAuthorsWithBooks_NPlus1() {
        log.info("Loading all authors (1 query)...");
        List<Author> authors = authorRepository.findAll();

        log.info("Accessing books for each author (N queries)...");
        for (Author author : authors) {
            int bookCount = author.getBooks().size(); // Triggers lazy load → 1 query
            log.info("  Author '{}' has {} book(s)", author.getName(), bookCount);
        }

        return authors;
    }
}
