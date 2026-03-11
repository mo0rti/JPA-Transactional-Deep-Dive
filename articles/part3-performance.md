# N+1 Queries, LazyInitializationException, and the Performance Traps Hiding in Your JPA Code

I reviewed a Spring Boot service last month that made 847 SQL queries to render a single API response. The developer had no idea. Hibernate was silently firing one query per item in a collection, across three nested relationships. The fix was two lines of code.

This is the N+1 problem, and it is the most common performance killer in JPA applications. It does not throw errors. It does not log warnings. It just makes your application slow, and it gets worse as your data grows.

In this article, we will learn how to find N+1 queries, understand why they happen, and fix them using fetch strategies, entity graphs, and DTO projections. By the end, you will know how to make your JPA code fast - without giving up the convenience that JPA provides.

---

## The N+1 Problem - What It Is and Why It Hurts

Let us start with a simple domain model: authors who write books.

```java
@Entity
@Table(name = "authors")
public class Author {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "author_seq")
    @SequenceGenerator(name = "author_seq", sequenceName = "author_seq", allocationSize = 1)
    private Long id;

    private String name;

    @OneToMany(mappedBy = "author", fetch = FetchType.LAZY)
    private List<Book> books = new ArrayList<>();
}

@Entity
@Table(name = "books")
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "book_seq")
    @SequenceGenerator(name = "book_seq", sequenceName = "book_seq", allocationSize = 1)
    private Long id;

    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private Author author;

    @OneToMany(mappedBy = "book", fetch = FetchType.LAZY)
    private List<Review> reviews = new ArrayList<>();
}

@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "review_seq")
    @SequenceGenerator(name = "review_seq", sequenceName = "review_seq", allocationSize = 1)
    private Long id;

    private String content;
    private int rating;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id")
    private Book book;
}
```

Notice that every relationship is `FetchType.LAZY`. This is the correct default. We will explain why shortly.

Now let us write a service method that loads all authors and their books:

```java
@Transactional(readOnly = true)
public List<Author> getAllAuthorsWithBooks() {
    List<Author> authors = authorRepository.findAll();
    // SQL: SELECT * FROM authors  (1 query)

    for (Author author : authors) {
        author.getBooks().size(); // Force lazy loading
        // SQL: SELECT * FROM books WHERE author_id = ?  (1 query per author)
    }

    return authors;
}
```

If you have 100 authors, this generates **101 queries**: 1 for the authors + 100 for their books. That is the N+1 problem. N is the number of parent records. You get 1 query for the parents and N queries to load their children.

With nested relationships, it gets worse. If each author has 5 books, and you also load reviews:

```
1 query for authors (100 authors)
+ 100 queries for books (one per author)
+ 500 queries for reviews (one per book)
= 601 queries for one API call
```

### Why Does This Happen?

It happens because of **lazy loading**. When you load an author, Hibernate does not load their books immediately. The `books` list is replaced with a special proxy that loads data from the database only when you access it. This is called a **lazy proxy** or **lazy collection**.

Lazy loading is actually a good default. You do not always need the related data, and loading everything eagerly would waste memory and database bandwidth. The problem is not lazy loading itself - the problem is loading lazy collections **inside a loop**.

---

## LAZY vs EAGER - Why Defaults Matter

JPA defines default fetch types for each relationship:

| Relationship | Default FetchType |
|-------------|------------------|
| `@ManyToOne` | **EAGER** |
| `@OneToOne` | **EAGER** |
| `@OneToMany` | **LAZY** |
| `@ManyToMany` | **LAZY** |

The defaults for `@ManyToOne` and `@OneToOne` are **EAGER**. This means every time you load an entity, JPA automatically loads its `@ManyToOne` and `@OneToOne` relationships too - whether you need them or not.

This is dangerous. Imagine a `Book` entity with a `@ManyToOne` to `Author`, a `@ManyToOne` to `Publisher`, and a `@ManyToOne` to `Category`. If all three are EAGER, every query that loads a book also joins three tables and loads three extra objects. And if those objects have their own EAGER relationships, the chain continues.

**Best practice: set every relationship to LAZY.**

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "author_id")
private Author author;

@OneToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "publisher_id")
private Publisher publisher;
```

When everything is LAZY, you are in control. You decide what to load and when, using the techniques we will cover next.

### The EAGER Trap

Once you set a relationship to EAGER, it is very hard to undo for a specific query. A plain JPQL query cannot suppress an EAGER fetch - the data will still be loaded (Hibernate fires extra SELECT queries behind the scenes). In theory, a `FETCH`-type `@EntityGraph` should treat non-listed attributes as LAZY per the JPA spec. In practice, Hibernate still eagerly fetches `@ManyToOne` and `@OneToOne` associations that are declared as EAGER, regardless of the entity graph. Once a relationship is EAGER, you cannot reliably make it LAZY for a specific query.

But if you set a relationship to LAZY, you can always choose to load it eagerly for a specific query using `JOIN FETCH` or `@EntityGraph`. LAZY gives you flexibility. EAGER takes it away.

---

## Fixing N+1 - The Solutions

### Solution 1: JOIN FETCH

The most common fix. You write a JPQL query that joins the related table and loads everything in one query.

```java
public interface AuthorRepository extends JpaRepository<Author, Long> {

    @Query("SELECT a FROM Author a JOIN FETCH a.books")
    List<Author> findAllWithBooks();
}
```

This generates a single SQL query:

```sql
SELECT a.*, b.*
FROM authors a
INNER JOIN books b ON b.author_id = a.id
```

One query instead of 101. Hibernate populates the `books` collection for each author from the join result.

**Important details about JOIN FETCH:**

1. **It uses INNER JOIN by default.** Authors without books will be excluded. If you want all authors including those without books, use `LEFT JOIN FETCH`:

```java
@Query("SELECT a FROM Author a LEFT JOIN FETCH a.books")
List<Author> findAllWithBooks();
```

2. **Duplicate results.** A JOIN produces one row per book. If an author has 3 books, that author appears 3 times in the result set. Hibernate deduplicates using the Persistence Context (each author is stored only once), but the result `List` may still contain duplicates. Use `DISTINCT` to fix this:

```java
@Query("SELECT DISTINCT a FROM Author a LEFT JOIN FETCH a.books")
List<Author> findAllWithBooks();
```

Since Hibernate 6 (Spring Boot 3.x), Hibernate automatically handles the deduplication for `JOIN FETCH` queries and the `DISTINCT` keyword is passed to SQL only when necessary. But it is still good practice to include `DISTINCT` for clarity.

3. **Be careful when JOIN FETCH-ing two collections at the same time.** If both collections are typed as `List`, Hibernate 5 throws `MultipleBagFetchException`. Hibernate 6 (Spring Boot 3.x) handles some multi-collection fetch scenarios without throwing this exception, but the underlying problem remains - the SQL produces a Cartesian product between the two collections, which can explode the result set size. If you try:

```java
// Risky: Cartesian product between books and awards
@Query("SELECT a FROM Author a JOIN FETCH a.books JOIN FETCH a.awards")
List<Author> findAllWithBooksAndAwards();
```

There are two safer approaches:

- Use `Set` instead of `List` for one of the collections (this avoids the `MultipleBagFetchException` in Hibernate 5, and signals to Hibernate 6 that duplicates should be eliminated - but the Cartesian product still exists in the SQL, so use only with small collections).
- Use two separate queries and let Hibernate's Persistence Context merge the results.

### Solution 2: @EntityGraph

Entity graphs let you define which relationships to load eagerly, without writing JPQL. You define the graph on the repository method.

```java
public interface AuthorRepository extends JpaRepository<Author, Long> {

    @EntityGraph(attributePaths = {"books"})
    List<Author> findAll();
}
```

This overrides the default `findAll()` to eagerly load books using a `LEFT JOIN`. The SQL is similar to `LEFT JOIN FETCH`.

You can also load nested relationships:

```java
@EntityGraph(attributePaths = {"books", "books.reviews"})
List<Author> findAll();
```

**Advantages of @EntityGraph over JOIN FETCH:**
- No JPQL needed - works with Spring Data derived query methods
- Cleaner syntax for simple cases

**Disadvantages:**
- Less control over the join type (always LEFT JOIN)
- Can be harder to debug because the query is generated, not written
- Has the **same pagination problem** as `JOIN FETCH` with collections (Hibernate applies pagination in memory). Use the two-query approach described later in this article for both

### Solution 3: @BatchSize - The Middle Ground

Sometimes you do need lazy loading - you just want it to be less chatty. `@BatchSize` tells Hibernate to load lazy collections in batches instead of one at a time.

```java
@Entity
public class Author {

    @OneToMany(mappedBy = "author", fetch = FetchType.LAZY)
    @BatchSize(size = 25)
    private List<Book> books = new ArrayList<>();
}
```

Now when Hibernate lazy-loads books for one author, it loads books for up to 25 authors at once using an `IN` clause:

```sql
SELECT * FROM books WHERE author_id IN (?, ?, ?, ... ?)  -- up to 25 IDs
```

With 100 authors and a batch size of 25, you get:
- 1 query for authors
- 4 queries for books (100 / 25 = 4 batches)
- Total: 5 queries instead of 101

`@BatchSize` is excellent when:
- You cannot use `JOIN FETCH` (e.g., multiple collections)
- The access pattern is unpredictable (you do not know in advance which collections will be accessed)
- You want to keep lazy loading but reduce the query count

You can also set a global default batch size in `application.yml`:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 25
```

This applies to all lazy relationships without needing `@BatchSize` on each one.

### Solution 4: @Fetch(FetchMode.SUBSELECT)

This is similar to `@BatchSize`, but instead of using `IN` with a limited batch, it uses a subselect to load all related records in one query.

```java
@Entity
public class Author {

    @OneToMany(mappedBy = "author", fetch = FetchType.LAZY)
    @Fetch(FetchMode.SUBSELECT)
    private List<Book> books = new ArrayList<>();
}
```

When any author's books are accessed, Hibernate loads books for **all** previously loaded authors in one query:

```sql
SELECT * FROM books
WHERE author_id IN (SELECT id FROM authors)  -- subselect repeats the original query
```

With 100 authors, you get:
- 1 query for authors
- 1 query for all books (using subselect)
- Total: 2 queries

This is very efficient for large datasets. The trade-off is that it always loads all related data, not just for the entities you need.

---

## DTO Projections - When You Should Stop Using Entities

Every solution so far loads full entities. But often, you do not need the full entity. You need a few fields for an API response or a report. Loading full entities wastes memory (Hibernate keeps snapshots for dirty checking) and bandwidth.

DTO projections tell Hibernate to select only the columns you need and map them directly to a simple object - no entity management, no dirty checking, no snapshots.

### Interface-Based Projection

The simplest approach. Define an interface with getter methods matching the columns you want:

```java
public interface AuthorSummary {
    String getName();
    long getBookCount();
}

public interface AuthorRepository extends JpaRepository<Author, Long> {

    @Query("SELECT a.name AS name, SIZE(a.books) AS bookCount FROM Author a")
    List<AuthorSummary> findAllSummaries();
}
```

Hibernate generates:

```sql
SELECT a.name, (SELECT COUNT(*) FROM books b WHERE b.author_id = a.id) AS bookCount
FROM authors a
```

No entity is created. No Persistence Context involvement. No dirty checking overhead. Just a lightweight proxy that implements your interface.

### Class-Based Projection (Constructor Expression)

For more control, use a DTO class with a constructor:

```java
public class AuthorWithBookCount {
    private final String name;
    private final long bookCount;

    public AuthorWithBookCount(String name, long bookCount) {
        this.name = name;
        this.bookCount = bookCount;
    }

    // getters
}

public interface AuthorRepository extends JpaRepository<Author, Long> {

    @Query("SELECT new com.example.jpa.performance.dto.AuthorWithBookCount(a.name, SIZE(a.books)) FROM Author a")
    List<AuthorWithBookCount> findAllWithBookCounts();
}
```

The `new` keyword in JPQL creates the DTO directly from the query result. No entity involved.

### When to Use DTO Projections

Use DTOs when:
- You need a read-only view (API responses, reports, lists)
- You need aggregated data (counts, sums, averages)
- Performance matters and you are loading many records
- You do not need to modify the data

Keep using entities when:
- You need to modify data (dirty checking requires entities)
- You need the full object graph for business logic
- The query is simple and the entity is small

**A good rule:** use entities for write operations and DTOs for read operations. This maps naturally to the `readOnly = true` pattern from Part 2.

---

## LazyInitializationException - The Three Correct Solutions

If you turned off OSIV (as we recommended in Part 1), you will eventually hit this exception:

```
org.hibernate.LazyInitializationException: failed to lazily initialize a collection -
could not initialize proxy - no Session
```

This happens when you try to access a lazy-loaded relationship outside of a transaction. The Persistence Context is closed, and Hibernate cannot load the data.

### Wrong Solutions (Avoid These)

**Do not turn OSIV back on.** It hides the problem and creates worse problems in production (see Part 1).

**Do not use `Hibernate.initialize()` everywhere.** It triggers a separate SELECT for each collection you initialize - you are back to N+1.

```java
// BAD: triggers separate SELECT queries
@Transactional(readOnly = true)
public Author getAuthor(Long id) {
    Author author = authorRepository.findById(id).orElseThrow();
    Hibernate.initialize(author.getBooks()); // extra SELECT
    for (Book book : author.getBooks()) {
        Hibernate.initialize(book.getReviews()); // extra SELECT per book - N+1 again!
    }
    return author;
}
```

### Correct Solution 1: JOIN FETCH or @EntityGraph

Load everything you need in one query, inside the service layer:

```java
@Transactional(readOnly = true)
public Author getAuthorWithBooks(Long id) {
    return authorRepository.findByIdWithBooks(id)
            .orElseThrow(() -> new EntityNotFoundException("Author not found"));
}

// In the repository:
@Query("SELECT a FROM Author a LEFT JOIN FETCH a.books WHERE a.id = :id")
Optional<Author> findByIdWithBooks(@Param("id") Long id);
```

This is the best solution for most cases. One query. No lazy loading issues.

### Correct Solution 2: DTO Projection

If you only need specific fields, skip entities entirely:

```java
@Transactional(readOnly = true)
public AuthorSummary getAuthorSummary(Long id) {
    return authorRepository.findSummaryById(id);
}
```

No lazy collections to worry about because there are no entities.

### Correct Solution 3: Fetch in the Service Layer

When you have complex conditional logic about what to load, use the service layer to coordinate:

```java
@Transactional(readOnly = true)
public AuthorResponse getAuthor(Long id, boolean includeBooks, boolean includeReviews) {
    Author author;

    if (includeBooks && includeReviews) {
        author = authorRepository.findWithBooksAndReviews(id).orElseThrow();
    } else if (includeBooks) {
        author = authorRepository.findByIdWithBooks(id).orElseThrow();
    } else {
        author = authorRepository.findById(id).orElseThrow();
    }

    return AuthorResponse.from(author);
}
```

The key principle: **all data loading happens inside the `@Transactional` service method.** The controller receives a fully populated response object. Nothing is loaded lazily after the transaction ends.

---

## Detecting N+1 Queries

You cannot fix what you cannot see. Here are three ways to detect N+1 queries.

### 1. Enable Hibernate Statistics

Add to `application.yml`:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        generate_statistics: true

logging:
  level:
    org.hibernate.stat: DEBUG
```

At the end of each session, Hibernate logs a summary:

```
Session Metrics {
    23456 nanoseconds spent acquiring 1 JDBC connections;
    0 nanoseconds spent releasing 0 JDBC connections;
    468234 nanoseconds spent preparing 102 JDBC statements;
    ...
}
```

If you see 102 JDBC statements for what should be a simple query, you have an N+1 problem.

### 2. Count Queries in Tests

Use Hibernate's `Statistics` interface to assert the exact number of queries in your tests:

```java
@DataJpaTest
class AuthorRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    void findAllWithBooks_shouldUseOneQuery() {
        // arrange: insert test data

        SessionFactory sessionFactory = entityManager
                .getEntityManagerFactory()
                .unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        // act
        List<Author> authors = authorRepository.findAllWithBooks();
        authors.forEach(a -> a.getBooks().size()); // access books

        // assert
        assertThat(stats.getQueryExecutionCount()).isEqualTo(1);
    }
}
```

This is the most reliable way to catch N+1 regressions. Put these tests in your CI pipeline.

### 3. SQL Log Counting

Turn on SQL logging and count the queries visually:

```yaml
logging:
  level:
    org.hibernate.SQL: DEBUG
```

Quick and useful during development, but not automated.

---

## Pagination with JOIN FETCH - A Common Trap

When you combine pagination with `JOIN FETCH`, Hibernate logs a warning:

```
HHH90003004: firstResult/maxResults specified with collection fetch;
applying in memory!
```

This means Hibernate loaded **all** the data into memory and then applied pagination in Java, not in the database. For large datasets, this can crash your application with an OutOfMemoryError.

Why does this happen? When you `JOIN FETCH` a collection (like `books`), each parent row appears multiple times in the result (once per child). PostgreSQL's `LIMIT` and `OFFSET` operate on rows, not on entities. If you ask for 10 authors with `LIMIT 10`, you might get 10 rows that represent only 3 authors (because they have many books).

### The Fix: Two-Query Approach

First, fetch the parent IDs with pagination. Then, load the full entities with their collections using those IDs.

```java
public interface AuthorRepository extends JpaRepository<Author, Long> {

    @Query("SELECT a.id FROM Author a")
    Page<Long> findAllIds(Pageable pageable);

    @Query("SELECT DISTINCT a FROM Author a LEFT JOIN FETCH a.books WHERE a.id IN :ids")
    List<Author> findAllWithBooksByIds(@Param("ids") List<Long> ids);
}

// In the service:
@Transactional(readOnly = true)
public Page<Author> getAuthorsWithBooks(Pageable pageable) {
    Page<Long> idPage = authorRepository.findAllIds(pageable);
    List<Author> authors = authorRepository.findAllWithBooksByIds(idPage.getContent());
    return new PageImpl<>(authors, pageable, idPage.getTotalElements());
}
```

This executes two queries (plus a count query for `Page` total elements):
1. `SELECT id FROM authors LIMIT ? OFFSET ?` - paginated, no join
2. `SELECT authors + books WHERE id IN (?, ?, ...)` - full fetch for just the page

Clean, efficient, and correct pagination.

---

## Key Takeaways

1. **The N+1 problem** is the most common JPA performance issue. It happens when you access lazy collections inside a loop - each access fires a separate query.

2. **Set every relationship to `FetchType.LAZY`.** This is the safe default. You can always load eagerly on a per-query basis using `JOIN FETCH` or `@EntityGraph`. You cannot make an EAGER relationship lazy.

3. **`JOIN FETCH`** is the primary fix for N+1. It loads related data in one SQL join. Remember to use `LEFT JOIN FETCH` to include entities without children, and `DISTINCT` for clean results.

4. **`@BatchSize` and `@Fetch(FetchMode.SUBSELECT)`** are good alternatives when `JOIN FETCH` is not practical - especially for multiple collections or unpredictable access patterns.

5. **Use DTO projections for read-only data.** They skip entity management entirely - no snapshots, no dirty checking, lower memory usage.

6. **Fix `LazyInitializationException` by loading data in the service layer**, not by turning OSIV back on. Use `JOIN FETCH`, `@EntityGraph`, or DTOs.

7. **Test your query counts.** Use Hibernate statistics in tests to assert the exact number of queries. This catches N+1 regressions in your CI pipeline.

8. **Do not paginate with `JOIN FETCH` directly.** Use the two-query approach: fetch IDs with pagination, then load entities by those IDs.

---

## What Is Next

We have covered the fundamentals (Part 1), transaction management (Part 2), and performance (this article). In Part 4, we will put it all together with production-grade patterns - event listeners, retry strategies, the outbox pattern, and how to test transactional code correctly.

---

*This is Part 3 of a 4-part series on JPA and @Transactional. The complete source code with runnable examples is available on [GitHub](https://github.com/yourusername/jpa-transactional-deep-dive).*
