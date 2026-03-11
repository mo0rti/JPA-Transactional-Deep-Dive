# Production-Grade @Transactional - Patterns, Testing, and the Things Nobody Tells You

Your `@Transactional` works perfectly in tests. It passes code review. Then, under load in production, it starts holding database connections for 12 seconds, your connection pool drains, and your entire service goes down.

This article is about everything that happens after you learn the basics - the production patterns that separate "it works" from "it works at scale." We will cover event listeners, retry strategies, programmatic transactions, the outbox pattern, and how to test transactional code without falling into the rollback trap.

If you have read Parts 1 through 3 of this series, you understand JPA internals, `@Transactional` mechanics, and performance. Now let us put it all together for real production code.

---

## Transaction Boundaries in Layered Architecture

Before we get into advanced patterns, let us establish where transactions belong in a real application.

```
Controller Layer     →  No @Transactional. Handles HTTP. Thin.
Service Layer        →  @Transactional here. Business logic. Transaction boundaries.
Repository Layer     →  Spring Data manages its own transactions internally.
```

A single service method is one unit of work. It starts a transaction, does its work, and commits or rolls back. The controller calls the service and gets back a result. The controller does not know or care about transactions.

```java
@RestController
public class AccountController {

    private final AccountService accountService;

    @PostMapping("/transfer")
    public ResponseEntity<TransferResult> transfer(@RequestBody TransferRequest request) {
        // No @Transactional here. Just call the service.
        TransferResult result = accountService.transfer(
                request.fromId(), request.toId(), request.amount());
        return ResponseEntity.ok(result);
    }
}

@Service
public class AccountService {

    @Transactional
    public TransferResult transfer(Long fromId, Long toId, BigDecimal amount) {
        // All database work happens here, inside one transaction.
        Account from = accountRepository.findById(fromId).orElseThrow();
        Account to = accountRepository.findById(toId).orElseThrow();

        from.debit(amount);
        to.credit(amount);

        return new TransferResult(from.getBalance(), to.getBalance());
    }
}
```

This is the foundation. Everything in this article builds on this layering.

---

## @TransactionalEventListener - Decoupling Side Effects

When a transaction commits, you often need to trigger side effects - send an email, publish a message to Kafka, update a cache. But these side effects should only happen if the transaction actually commits. If it rolls back, the email should not go out.

Spring's `@TransactionalEventListener` solves this. It listens for events and runs only after the transaction reaches a specific phase - by default, after commit.

### How It Works

```java
// Step 1: Define the event
public record OrderCreatedEvent(Long orderId, String customerEmail) {}

// Step 2: Publish the event inside the transaction
@Service
public class OrderService {

    private final ApplicationEventPublisher eventPublisher;
    private final OrderRepository orderRepository;

    @Transactional
    public Order createOrder(String description, BigDecimal amount) {
        Order order = orderRepository.save(new Order(description, amount));

        // Publish event. It is NOT delivered yet - it waits for commit.
        eventPublisher.publishEvent(new OrderCreatedEvent(order.getId(), "customer@example.com"));

        return order;
    }
}

// Step 3: Handle the event AFTER the transaction commits
@Component
public class OrderEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderEventHandler.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("Order {} committed. Sending email to {}", event.orderId(), event.customerEmail());
        // Send email, publish to Kafka, etc.
        // This runs AFTER the transaction committed successfully.
    }
}
```

If the transaction rolls back, the event handler never runs. The email is never sent. Your side effects are safe.

### The Phases

- `AFTER_COMMIT` (default) - Runs after successful commit. Most common.
- `AFTER_ROLLBACK` - Runs after rollback. Useful for compensating actions.
- `AFTER_COMPLETION` - Runs after commit or rollback.
- `BEFORE_COMMIT` - Runs before commit, still inside the transaction. Use for validation that must happen at the very end.

### Important: No Transaction in AFTER_COMMIT

The event handler runs **after** the transaction is committed and closed. If you need to do database work inside the handler, you need a new transaction. The safest approach is `REQUIRES_NEW`:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void handleOrderCreated(OrderCreatedEvent event) {
    // This runs in a NEW transaction, separate from the original.
    notificationRepository.save(new Notification(event.orderId(), "Email sent"));
}
```

In Spring Framework 6.1+ (Spring Boot 3.2+), plain `@Transactional` (REQUIRED) also works here because Spring now properly cleans up the transaction synchronization state from the thread before invoking AFTER_COMMIT listeners. But `REQUIRES_NEW` is the safer, version-independent approach.

**Note:** If an event is published outside a transactional context, the `@TransactionalEventListener` silently ignores it by default - the handler never runs. If you need the handler to execute even without a transaction, add `fallbackExecution = true`:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
public void handleEvent(OrderCreatedEvent event) {
    // Runs after commit if published inside a transaction.
    // Runs immediately if published outside a transaction.
}
```

---

## Async + @Transactional - Why @Async @Transactional Is Almost Always Wrong

A common mistake is combining `@Async` and `@Transactional` on the same method:

```java
// WRONG: @Async runs in a new thread. @Transactional cannot bind to the caller's transaction.
@Async
@Transactional
public void processOrderAsync(Long orderId) {
    // This runs in a new thread with a NEW transaction.
    // It does NOT join the caller's transaction.
    // If the caller's transaction rolls back, this still commits.
}
```

The problem is that `@Async` runs the method in a different thread. Transactions are bound to threads. So the `@Transactional` on an `@Async` method always creates a new, independent transaction - it never joins the caller's transaction.

This means:
1. If the caller's transaction rolls back after the async call is dispatched, the async work still commits.
2. If the async work fails, the caller's transaction is unaffected - it may have already committed.
3. You have no transactional consistency between the caller and the async work.

### The Correct Approach

Use `@TransactionalEventListener` to trigger the async work only after the transaction commits:

```java
@Service
public class OrderService {

    @Transactional
    public Order createOrder(String description, BigDecimal amount) {
        Order order = orderRepository.save(new Order(description, amount));
        eventPublisher.publishEvent(new OrderCreatedEvent(order.getId()));
        return order;
    }
}

@Component
public class AsyncOrderProcessor {

    private final OrderProcessingService processingService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCreated(OrderCreatedEvent event) {
        // Runs async, AFTER the order is committed.
        // Safe: the order definitely exists in the database.
        processingService.processOrder(event.orderId());
        // Called through the proxy → @Transactional works
    }
}

@Service
public class OrderProcessingService {

    @Transactional
    public void processOrder(Long orderId) {
        // New transaction in the async thread. Fully independent.
        Order order = orderRepository.findById(orderId).orElseThrow();
        // ... heavy processing ...
    }
}
```

This gives you: guaranteed commit before async processing starts, a clean transaction boundary for the async work, and no thread-safety issues.

---

## TransactionTemplate - When Annotations Are Not Enough

Sometimes `@Transactional` annotations are too rigid. You need to run part of a method in a transaction and part outside. Or you need fine-grained control over transaction boundaries in a loop. `TransactionTemplate` is the programmatic alternative.

```java
@Service
public class BatchProcessingService {

    private final TransactionTemplate txTemplate;
    private final OrderRepository orderRepository;

    public BatchProcessingService(PlatformTransactionManager txManager,
                                   OrderRepository orderRepository) {
        this.txTemplate = new TransactionTemplate(txManager);
        this.orderRepository = orderRepository;
    }

    /**
     * Processes orders in individual transactions.
     * If one order fails, others are not affected.
     */
    public void processOrdersIndividually(List<Long> orderIds) {
        for (Long orderId : orderIds) {
            try {
                txTemplate.executeWithoutResult(status -> {
                    Order order = orderRepository.findById(orderId).orElseThrow();
                    order.setStatus(OrderStatus.PROCESSED);
                    // Transaction commits when the lambda returns
                });
            } catch (Exception e) {
                log.error("Failed to process order {}: {}", orderId, e.getMessage());
                // Continue with the next order. The failed one was rolled back.
            }
        }
    }
}
```

### When to Use TransactionTemplate

- **Batch processing with individual transaction per item** - If one item fails, others should not be affected.
- **Mixed transactional and non-transactional work** - Call an external API outside the transaction, then save the result inside a transaction.
- **Self-invocation workaround** - When you cannot extract a method to a separate service.
- **Dynamic transaction configuration** - Set isolation level or timeout based on runtime conditions.

```java
// Dynamic timeout based on data size
TransactionTemplate customTx = new TransactionTemplate(txManager);
customTx.setTimeout(dataSize > 1000 ? 60 : 10);
customTx.execute(status -> {
    // ...
    return null;
});
```

### Read-Only TransactionTemplate

```java
TransactionTemplate readOnlyTx = new TransactionTemplate(txManager);
readOnlyTx.setReadOnly(true);

List<Order> orders = readOnlyTx.execute(status -> {
    return orderRepository.findAll();
});
```

---

## Retryable Transactions - Handling PostgreSQL Serialization Failures

When you use `REPEATABLE READ` or `SERIALIZABLE` isolation levels, PostgreSQL may throw serialization errors if two transactions conflict. These errors look like:

```
ERROR: could not serialize access due to concurrent update
```

The correct response is to retry the transaction. Spring Retry makes this easy.

### Setup

Add Spring Retry to your `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.retry</groupId>
    <artifactId>spring-retry</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-aspects</artifactId>
</dependency>
```

Enable retry:

```java
@SpringBootApplication
@EnableRetry
public class Application { }
```

### The Ordering Problem

When combining `@Retryable` and `@Transactional`, the order of the annotations matters. `@Retryable` must wrap `@Transactional`, not the other way around. If the transaction wraps the retry, retries happen inside a doomed transaction - the first failure marks the transaction for rollback, and retries cannot save it.

```java
// CORRECT: @Retryable wraps @Transactional.
// Each retry gets a fresh transaction.
@Service
public class TransferService {

    @Retryable(
        retryFor = CannotSerializeTransactionException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void transfer(Long fromId, Long toId, BigDecimal amount) {
        Account from = accountRepository.findById(fromId).orElseThrow();
        Account to = accountRepository.findById(toId).orElseThrow();

        from.debit(amount);
        to.credit(amount);
    }

    @Recover
    public void transferRecover(CannotSerializeTransactionException e, Long fromId, Long toId, BigDecimal amount) {
        log.error("Transfer failed after retries: from={}, to={}, amount={}", fromId, toId, amount);
        throw new TransferFailedException("Transfer failed after 3 retries", e);
    }
}
```

For this to work correctly, the `@Retryable` advice must have higher priority (lower order value) than `@Transactional`. By default, both Spring's transaction advisor and Spring Retry's advisor have `Ordered.LOWEST_PRECEDENCE`, which makes the ordering **undefined**. You should configure explicit ordering:

```java
@EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1) // Higher priority than transaction
@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE) // Default, shown for clarity
```

But the simplest and most reliable approach is to **separate retry and transaction into different beans**. Put `@Retryable` on the calling service and `@Transactional` on the inner service. This makes the ordering explicit with no proxy ambiguity:

```java
@Service
public class TransferFacade {

    private final TransferService transferService;

    @Retryable(
        retryFor = CannotSerializeTransactionException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public void transfer(Long fromId, Long toId, BigDecimal amount) {
        transferService.doTransfer(fromId, toId, amount);
        // Each retry calls doTransfer() through the proxy → fresh transaction
    }
}

@Service
public class TransferService {

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void doTransfer(Long fromId, Long toId, BigDecimal amount) {
        // ...
    }
}
```

---

## The Outbox Pattern - Reliable Event Publishing

What happens if your service saves an order to the database and then publishes a message to Kafka, but the Kafka publish fails? The order is committed, but the event is lost. Or worse: the Kafka publish succeeds but the database transaction rolls back. Now you have a phantom event with no matching data.

The **outbox pattern** solves this by storing events in the same database transaction as the business data. A separate process then reads the events from the outbox table and publishes them to Kafka.

### How It Works

```
1. Save order + save outbox event in the SAME transaction → both commit or both rollback
2. A separate poller/CDC reads the outbox table and publishes to Kafka
3. After successful publish, mark the event as sent (or delete it)
```

### Implementation

```java
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "outbox_seq")
    @SequenceGenerator(name = "outbox_seq", sequenceName = "outbox_seq", allocationSize = 1)
    private Long id;

    private String aggregateType;  // e.g., "Order"
    private String aggregateId;    // e.g., "12345"
    private String eventType;      // e.g., "OrderCreated"

    @Column(columnDefinition = "TEXT")
    private String payload;        // JSON payload

    private LocalDateTime createdAt;
    private boolean sent;

    // constructors, getters, setters...
}

@Service
public class OrderService {

    @Transactional
    public Order createOrder(String description, BigDecimal amount) {
        Order order = orderRepository.save(new Order(description, amount));

        // Save the outbox event in the SAME transaction
        OutboxEvent event = new OutboxEvent(
                "Order", order.getId().toString(),
                "OrderCreated",
                toJson(order)
        );
        outboxEventRepository.save(event);

        return order;
        // Both order and outbox event commit together.
        // If anything fails, both are rolled back.
    }
}
```

The publishing side:

```java
@Service
public class OutboxPublisher {

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findBySentFalseOrderByCreatedAtAsc();

        for (OutboxEvent event : pending) {
            try {
                kafkaTemplate.send(event.getAggregateType(), event.getPayload());
                event.setSent(true); // Mark as published
            } catch (Exception e) {
                log.error("Failed to publish event {}: {}", event.getId(), e.getMessage());
                break; // Stop processing - maintain order
            }
        }
    }
}
```

This is a simplified version. In production, you might use Debezium for CDC-based outbox (no polling needed) or add a `sentAt` timestamp and a `retryCount` for better error handling.

---

## Testing Transactional Code - The Rollback Trap

Spring's test framework has a feature that trips up almost everyone: **`@Transactional` on a test method rolls back after the test completes.** This means your test data is never actually committed to the database.

```java
@SpringBootTest
@Transactional // This makes every test roll back automatically
class OrderServiceTest {

    @Test
    void createOrder_shouldSaveToDatabase() {
        orderService.createOrder("Test", new BigDecimal("100"));

        // This passes! But the data was never committed to PostgreSQL.
        // @TransactionalEventListener handlers NEVER fire.
        // REQUIRES_NEW methods inside the service still see the data
        // because they join the test's transaction... wait, they don't.
        // They create a new transaction and CANNOT see the uncommitted test data.
    }
}
```

### The Problems with @Transactional Tests

1. **`@TransactionalEventListener` does not fire.** Because the transaction never commits, `AFTER_COMMIT` event listeners are never triggered. Your tests pass, but the event logic is untested.

2. **`REQUIRES_NEW` creates a separate transaction.** The new transaction cannot see the test's uncommitted data. You get `EntityNotFoundException` for data that "should" be there.

3. **Auto-generated IDs might not behave as expected.** Sequences are called, but rollback does not reset them. Your test might rely on ID = 1, but the sequence is at 47 because of previous rolled-back tests.

4. **It gives false confidence.** The test passes in a single-transaction world that does not match production, where multiple transactions interact.

### The Correct Approach: No @Transactional on Tests

Write your tests without `@Transactional`. Use a real database (Testcontainers), commit real data, and clean up after each test.

```java
@SpringBootTest
class OrderServiceTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @AfterEach
    void cleanup() {
        orderRepository.deleteAll();
    }

    @Test
    void createOrder_shouldSaveToDatabase() {
        Order order = orderService.createOrder("Test", new BigDecimal("100"));

        // Data is actually committed. We can verify it with a fresh query.
        Order found = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(found.getDescription()).isEqualTo("Test");
    }
}
```

### Testcontainers for Real PostgreSQL

Do not use H2 for testing. H2 behaves differently from PostgreSQL in subtle ways - different SQL syntax, different locking behavior, different sequence handling. Use Testcontainers to run a real PostgreSQL instance in Docker.

```java
@SpringBootTest
@Testcontainers
class OrderServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // Spring Boot 3.1+: @ServiceConnection auto-configures the datasource
    // from the container - no manual property registration needed.
    // For older versions, use @DynamicPropertySource instead.

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private OrderService orderService;

    @Test
    void transfer_shouldBeAtomic() {
        // Real PostgreSQL. Real transactions. Real behavior.
    }
}
```

Add to your test `pom.xml`:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

---

## Connection Pool Monitoring - HikariCP

Your application is only as healthy as its connection pool. HikariCP is the default connection pool in Spring Boot, and it needs monitoring.

### Key Settings

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10        # Max connections in the pool
      minimum-idle: 5              # Min idle connections (HikariCP recommends leaving this equal to maximum-pool-size for most workloads)
      connection-timeout: 30000    # Wait for a connection (ms) before throwing
      idle-timeout: 600000         # Remove idle connections after 10 min
      max-lifetime: 1800000        # Recycle connections after 30 min
      leak-detection-threshold: 60000  # Log warning if connection held > 60s
```

The most important setting is `maximum-pool-size`. A good starting formula:

```
pool-size = (core_count * 2) + number_of_disk_spindles
```

For most cloud instances with SSDs, 10-20 connections is plenty. More connections is not better - it leads to more context switching and lock contention in PostgreSQL.

### Leak Detection

`leak-detection-threshold` is invaluable for development. If any connection is held for longer than the threshold (in milliseconds), HikariCP logs a warning with the stack trace showing where the connection was acquired. This catches long-running transactions and connection leaks.

```
WARN HikariPool - Connection leak detection triggered for connection
com.zaxxer.hikari.pool.ProxyConnection@abcdef,
on thread http-nio-8080-exec-1, stack trace follows:
    at com.example.service.SlowService.processEverything(SlowService.java:42)
```

### Exposing Metrics

With Spring Actuator and Micrometer, HikariCP metrics are exposed automatically:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics

# Then query: GET /actuator/metrics/hikaricp.connections.active
```

Key metrics to monitor:
- `hikaricp.connections.active` - How many connections are in use right now
- `hikaricp.connections.pending` - Threads waiting for a connection (danger sign)
- `hikaricp.connections.idle` - Available connections
- `hikaricp.connections.timeout` - Connection acquisition timeouts (very bad)

If `pending` is consistently above 0, your pool is too small or your transactions are too slow.

---

## Putting It All Together - A Production Checklist

Here is what a production-ready Spring Boot + JPA + PostgreSQL setup looks like:

### application.yml

```yaml
spring:
  jpa:
    open-in-view: false
    properties:
      hibernate:
        default_batch_fetch_size: 25
        order_inserts: true
        order_updates: true
        jdbc:
          batch_size: 25

  datasource:
    hikari:
      maximum-pool-size: 10
      leak-detection-threshold: 60000
```

### Service Layer Pattern

```java
@Service
@Transactional(readOnly = true) // Safe default
public class OrderService {

    @Transactional(rollbackFor = Exception.class)
    public Order createOrder(CreateOrderCommand command) {
        // Write method: override class-level readOnly
        // rollbackFor: safe default for checked exceptions
    }

    public OrderView findById(Long id) {
        // Read method: uses class-level readOnly = true
        // Returns a DTO/view, not an entity
    }
}
```

### Testing Pattern

```java
@SpringBootTest
@Testcontainers
class OrderServiceTest {
    // Real PostgreSQL via Testcontainers
    // No @Transactional on tests
    // Clean up with @AfterEach
    // Assert real committed state
}
```

---

## Key Takeaways

1. **Keep transactions in the service layer.** Controllers handle HTTP. Services own transaction boundaries. Repositories do not need their own `@Transactional`.

2. **Use `@TransactionalEventListener`** for side effects that must only happen after commit - emails, messages, cache updates. Combine with `@Async` for non-blocking processing.

3. **Never combine `@Async` and `@Transactional` on the same method.** The async thread gets its own transaction regardless. Use event listeners to trigger async work after commit.

4. **Use `TransactionTemplate`** for fine-grained control - batch processing with per-item transactions, mixed transactional/non-transactional work, or dynamic transaction settings.

5. **Retry serialization failures** with Spring Retry. Make sure `@Retryable` wraps `@Transactional`, not the other way around. The simplest approach: put retry on the caller, transaction on the callee.

6. **Use the outbox pattern** for reliable event publishing. Store events in the same transaction as business data. Publish them separately.

7. **Do not use `@Transactional` on tests.** It hides real transaction behavior, prevents event listeners from firing, and breaks `REQUIRES_NEW` interactions. Use Testcontainers with real PostgreSQL and clean up after each test.

8. **Monitor your connection pool.** Set `leak-detection-threshold`, watch `hikaricp.connections.pending`, and keep your pool size small (10-20 for most applications).

---

## Series Wrap-Up

Over four articles, we have gone from "what happens when you call `save()`" to production-grade transaction management:

- **Part 1** - JPA internals: Persistence Context, entity states, dirty checking, flushing
- **Part 2** - `@Transactional`: proxies, propagation, isolation, rollback behavior
- **Part 3** - Performance: N+1 queries, fetch strategies, DTO projections
- **Part 4** - Production patterns: events, retries, outbox, testing, monitoring

The common thread through all of these is understanding. JPA and `@Transactional` are not magic. They are well-defined mechanisms with clear rules. When you understand those rules, you write better code, debug faster, and build systems that work under load.

---

*This is Part 4 of a 4-part series on JPA and @Transactional. The complete source code with runnable examples is available on [GitHub](https://github.com/yourusername/jpa-transactional-deep-dive).*
