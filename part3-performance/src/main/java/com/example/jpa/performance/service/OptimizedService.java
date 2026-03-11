package com.example.jpa.performance.service;

import com.example.jpa.performance.dto.AuthorSummary;
import com.example.jpa.performance.dto.AuthorWithBookCount;
import com.example.jpa.performance.entity.Author;
import com.example.jpa.performance.repository.AuthorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Demonstrates the solutions to N+1: JOIN FETCH, @EntityGraph, DTO projections,
 * and the two-query pagination approach.
 */
@Service
@Transactional(readOnly = true)
public class OptimizedService {

    private static final Logger log = LoggerFactory.getLogger(OptimizedService.class);

    private final AuthorRepository authorRepository;

    public OptimizedService(AuthorRepository authorRepository) {
        this.authorRepository = authorRepository;
    }

    /**
     * FIX 1: JOIN FETCH — loads authors + books in 1 query.
     */
    public List<Author> getAllAuthorsWithBooks_JoinFetch() {
        log.info("JOIN FETCH: Loading authors with books in 1 query...");
        List<Author> authors = authorRepository.findAllWithBooks();

        for (Author author : authors) {
            log.info("  Author '{}' has {} book(s) (already loaded, no extra query)",
                    author.getName(), author.getBooks().size());
        }

        return authors;
    }

    /**
     * FIX 2: @EntityGraph — loads authors + books via entity graph.
     */
    public List<Author> getAllAuthorsWithBooks_EntityGraph() {
        log.info("@EntityGraph: Loading authors with books...");
        List<Author> authors = authorRepository.findAllWithBooksEntityGraph();

        for (Author author : authors) {
            log.info("  Author '{}' has {} book(s)", author.getName(), author.getBooks().size());
        }

        return authors;
    }

    /**
     * FIX 3: Interface-based DTO projection — no entities, no overhead.
     */
    public List<AuthorSummary> getAllAuthorSummaries() {
        log.info("DTO Projection (interface): Loading summaries...");
        List<AuthorSummary> summaries = authorRepository.findAllSummaries();

        for (AuthorSummary summary : summaries) {
            log.info("  '{}' has {} book(s)", summary.getName(), summary.getBookCount());
        }

        return summaries;
    }

    /**
     * FIX 4: Class-based DTO projection — constructor expression.
     */
    public List<AuthorWithBookCount> getAllAuthorWithBookCounts() {
        log.info("DTO Projection (class): Loading with constructor expression...");
        List<AuthorWithBookCount> results = authorRepository.findAllWithBookCounts();

        for (AuthorWithBookCount result : results) {
            log.info("  {}", result);
        }

        return results;
    }

    /**
     * FIX 5: Two-query pagination — correct pagination with JOIN FETCH.
     */
    public Page<Author> getAuthorsWithBooksPaginated(Pageable pageable) {
        log.info("Two-query pagination: page={}, size={}",
                pageable.getPageNumber(), pageable.getPageSize());

        // Query 1: Get paginated IDs
        Page<Long> idPage = authorRepository.findAllIds(pageable);
        log.info("  Query 1: Found {} IDs (total {})", idPage.getContent().size(), idPage.getTotalElements());

        if (idPage.isEmpty()) {
            return Page.empty(pageable);
        }

        // Query 2: Load full entities with books for those IDs
        List<Author> authors = authorRepository.findAllWithBooksByIds(idPage.getContent());
        log.info("  Query 2: Loaded {} authors with books", authors.size());

        return new PageImpl<>(authors, pageable, idPage.getTotalElements());
    }
}
