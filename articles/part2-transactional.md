# @Transactional - The Annotation You Use Everywhere But Don't Fully Understand

Here is a production bug that cost a fintech startup three days of debugging: a `@Transactional` method that was not transactional. The code looked perfect. The annotation was there. The tests passed. And yet, in production, data was partially committed.

The cause? The transactional method was called from another method in the same class. Spring's proxy never intercepted the call. The annotation did nothing.

This kind of bug is invisible. There are no errors, no warnings, no exceptions. The code runs. It just does not run inside a transaction.

This article explains how `@Transactional` really works - the proxy mechanism underneath it, the propagation levels that control transaction boundaries, and the five most common mistakes that silently break your transactional code.

---

## How @Transactional Actually Works - The Proxy

When you put `@Transactional` on a method, Spring does not magically add transaction code to your class. Instead, it creates a **proxy** - a wrapper object that sits in front of your real bean.

Here is what happens at startup:

1. Spring scans your class and sees `@Transactional` on one or more methods.
2. Spring creates a proxy - a new object that implements the same interface (or extends the same class).
3. The proxy is registered in the application context instead of your real bean.
4. When other beans inject your service, they get the proxy, not the real object.

When someone calls a `@Transactional` method on the proxy, here is what happens:

```
Caller → Proxy → Begin Transaction → Your Real Method → Commit/Rollback → Return
```

The proxy does three things:
1. Opens a transaction before calling your method
2. Calls your actual method
3. Commits the transaction if the method returns normally, or rolls back if it throws an unchecked exception

This is regular Spring AOP (Aspect-Oriented Programming). The proxy intercepts the call and wraps it in transaction management code.

### Seeing the Proxy

You can prove the proxy exists with a simple test:

```java
@Service
public class OrderService {

    @Transactional
    public void createOrder() {
        // ...
    }
}

@Component
public class ProxyChecker implements CommandLineRunner {

    @Autowired
    private OrderService orderService;

    @Override
    public void run(String... args) {
        System.out.println(orderService.getClass().getName());
        // Output: com.example.jpa.transactional.service.OrderService$$SpringCGLIB$$0
        // The $$SpringCGLIB$$ part tells you this is a proxy, not the real class.
    }
}
```

Spring Boot uses CGLIB proxies by default. CGLIB creates a subclass of your bean at runtime. This means `@Transactional` works on concrete classes - you do not need interfaces. The proxy subclass overrides your methods, adds the transaction logic, and then delegates to the target bean instance to run your actual code.

---

## The Self-Invocation Trap - Why @Transactional Silently Fails

This is the single most common `@Transactional` bug. It affects every Spring project, and most developers discover it the hard way.

The rule is simple: **`@Transactional` only works when the method is called from outside the class, through the proxy.**

If you call a `@Transactional` method from another method in the same class, the proxy is bypassed. The annotation has no effect.

```java
@Service
public class OrderService {

    public void processOrder(Long orderId) {
        // Some logic...
        this.applyDiscount(orderId); // ← Direct call via 'this'
        // The proxy is NOT involved. No transaction is created.
    }

    @Transactional
    public void applyDiscount(Long orderId) {
        // This runs WITHOUT a transaction when called from processOrder()
        // Even though @Transactional is right here on the method.
    }
}
```

Why does this happen? When `processOrder()` calls `this.applyDiscount()`, the call goes directly to the real object. It never passes through the proxy. The proxy only intercepts calls that come from **outside** the bean - from other beans that hold a reference to the proxy.

```
External Caller → Proxy.processOrder() → RealObject.processOrder()
                                           → this.applyDiscount()  ← No proxy!
```

### How to Fix It

There are three correct solutions:

**Solution 1: Move the method to a separate service (recommended)**

```java
@Service
public class OrderService {

    private final DiscountService discountService;

    public OrderService(DiscountService discountService) {
        this.discountService = discountService;
    }

    public void processOrder(Long orderId) {
        // Some logic...
        discountService.applyDiscount(orderId); // ← Goes through the proxy
    }
}

@Service
public class DiscountService {

    @Transactional
    public void applyDiscount(Long orderId) {
        // This now correctly runs inside a transaction
    }
}
```

This is the cleanest solution. It respects the single responsibility principle and avoids any workarounds.

**Solution 2: Inject yourself (works but looks unusual)**

```java
@Service
public class OrderService {

    @Lazy
    @Autowired
    private OrderService self;

    public void processOrder(Long orderId) {
        self.applyDiscount(orderId); // ← 'self' is the proxy
    }

    @Transactional
    public void applyDiscount(Long orderId) {
        // Now runs inside a transaction
    }
}
```

Spring Boot 3.x rejects circular references by default (`spring.main.allow-circular-references=false`). The `@Lazy` prevents the cycle detection from triggering - Spring creates a lazy proxy immediately without resolving the target bean upfront. `self` refers to the proxy, so the call goes through transaction management.

**Solution 3: Use `TransactionTemplate` (programmatic approach)**

```java
@Service
public class OrderService {

    private final TransactionTemplate txTemplate;

    public OrderService(PlatformTransactionManager txManager) {
        this.txTemplate = new TransactionTemplate(txManager);
    }

    public void processOrder(Long orderId) {
        txTemplate.executeWithoutResult(status -> {
            applyDiscount(orderId);
        });
    }

    private void applyDiscount(Long orderId) {
        // Runs inside a transaction, managed by TransactionTemplate
    }
}
```

This avoids the proxy issue entirely because you are managing the transaction programmatically. We will cover `TransactionTemplate` in more detail in Part 4.

---

## Propagation - Controlling Transaction Boundaries

Propagation defines what happens when a `@Transactional` method calls another `@Transactional` method. Should they share the same transaction? Should the second method get its own transaction? Should it run without a transaction?

There are seven propagation levels in Spring, but in practice you only need to know three. The rest are rarely used.

### REQUIRED (The Default)

This is the default. It says: "I need a transaction. If there is already one, join it. If there is none, create a new one."

```java
@Transactional(propagation = Propagation.REQUIRED) // same as just @Transactional
public void methodA() {
    methodB(); // called via proxy
}

@Transactional(propagation = Propagation.REQUIRED)
public void methodB() {
    // If called from methodA: joins methodA's transaction
    // If called directly (no existing tx): creates a new transaction
}
```

This is what you want 90% of the time. Your service methods share a transaction, so they all see the same Persistence Context, they all commit together, and if anything fails, everything rolls back.

**Important:** When methodB joins methodA's transaction, it is fully joined. If methodB throws an exception that marks the transaction for rollback, **the entire transaction is rolled back** - including methodA's work. Even if methodA catches the exception, the transaction is already marked for rollback, and the commit will fail with `UnexpectedRollbackException`.

```java
@Transactional
public void methodA() {
    try {
        proxyB.methodB(); // methodB throws and marks tx for rollback
    } catch (Exception e) {
        // You caught the exception, but the transaction is ALREADY marked for rollback.
        // When methodA tries to commit, Spring throws UnexpectedRollbackException.
        log.info("Caught exception, continuing..."); // This will NOT save you
    }
}
```

This surprises many developers. Catching the exception does not "un-mark" the rollback. The only way to isolate the failure is to use `REQUIRES_NEW`.

### REQUIRES_NEW

This says: "I need my own, new transaction. If there is an existing transaction, suspend it until I am done."

```java
@Service
public class AuditService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAuditEvent(String event) {
        // This runs in its own transaction, separate from the caller.
        // Even if the caller's transaction rolls back,
        // this audit log entry is committed independently.
        auditRepository.save(new AuditEvent(event));
    }
}
```

Use `REQUIRES_NEW` when:
- You need a side effect to survive even if the main transaction fails (audit logs, notification records)
- You need to isolate a failure so it does not roll back the outer transaction
- You need to read committed data that the outer transaction just flushed

**Be careful:** `REQUIRES_NEW` suspends the outer transaction and opens a new database connection. If you use it inside a loop, you can exhaust your connection pool. Each new transaction needs its own connection.

### NOT_SUPPORTED

This says: "I do not want a transaction. If there is one, suspend it."

```java
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public void sendNotification(String message) {
    // Runs without a transaction. The outer transaction (if any) is suspended.
    // Useful for operations that should not hold a database connection.
    emailService.send(message);
}
```

Use this for methods that do not touch the database and should not hold a connection - like sending emails, calling external APIs, or processing files.

### The Other Propagation Levels

- `SUPPORTS` - Use the current transaction if one exists. Otherwise, run without one. Rarely useful.
- `MANDATORY` - Throw an exception if there is no current transaction. Useful for methods that must always be called within an existing transaction.
- `NEVER` - Throw an exception if there IS a current transaction. The opposite of MANDATORY.
- `NESTED` - Create a savepoint within the existing transaction. If the nested part fails, roll back to the savepoint without rolling back the outer transaction. Only works with JDBC `DataSource` transactions - not JTA. Rarely used in practice.

---

## Isolation Levels - How Transactions See Each Other's Data

Isolation controls what happens when two transactions run at the same time and access the same data. PostgreSQL supports four isolation levels, but in practice, you will use two.

### READ COMMITTED (The Default)

This is PostgreSQL's default isolation level. Spring's `@Transactional` uses `Isolation.DEFAULT`, which means "use whatever the database defaults to" - and for PostgreSQL, that is READ COMMITTED. Each query in your transaction sees only data that was committed before that query started. If another transaction commits while yours is running, your next query will see those changes.

```java
@Transactional(isolation = Isolation.READ_COMMITTED) // default
public void transfer(Long fromId, Long toId, BigDecimal amount) {
    // Each SELECT sees the latest committed data at the time of that SELECT.
    // Two SELECT queries in the same transaction might see different data
    // if another transaction committed between them.
}
```

This is fine for most applications. It prevents dirty reads (seeing uncommitted data from other transactions) but allows **non-repeatable reads** (reading the same row twice and getting different values because another transaction committed in between).

### REPEATABLE READ

This gives you a consistent snapshot of the database from the moment your transaction starts. Every query sees the same data, no matter what other transactions commit.

```java
@Transactional(isolation = Isolation.REPEATABLE_READ)
public void generateReport() {
    // Every query in this transaction sees the database as it was
    // when the transaction started. Other commits are invisible.
    // Perfect for reports that need a consistent view.
}
```

PostgreSQL implements `REPEATABLE READ` using **MVCC (Multi-Version Concurrency Control)**. It does not lock rows. Instead, it keeps multiple versions of each row and shows your transaction the version that was current when your transaction started.

The trade-off: if your transaction tries to update a row that another transaction already modified and committed, PostgreSQL throws a serialization error. Your transaction cannot proceed because it would overwrite changes it never saw. You must retry the transaction. We will cover retry strategies in Part 4.

### SERIALIZABLE

The strictest level. Transactions behave as if they ran one after another, with no overlap. PostgreSQL uses a technique called **Serializable Snapshot Isolation (SSI)** to detect conflicts.

You rarely need this. It is useful for financial calculations where absolute consistency is required, but it comes with a performance cost - more transactions will fail with serialization errors and need retries.

### READ UNCOMMITTED

PostgreSQL does not support this level. If you set it, PostgreSQL silently upgrades it to READ COMMITTED. No configuration needed - you cannot get dirty reads in PostgreSQL.

---

## readOnly = true - More Than You Think

Many developers use `readOnly = true` as a best practice label. They think it is just documentation. It is not. It triggers a chain of real optimizations.

```java
@Transactional(readOnly = true)
public List<Product> findAllProducts() {
    return productRepository.findAll();
}
```

Here is what `readOnly = true` actually does:

### 1. Hibernate Skips Dirty Checking

This is the biggest optimization. In a read-only transaction, Hibernate does not take snapshots of loaded entities and does not run dirty checking at flush time. If you load 500 entities, that means 500 fewer snapshot copies in memory and zero comparison work at the end of the transaction.

### 2. Hibernate Sets FlushMode to MANUAL

In a read-only transaction, Hibernate sets the flush mode to `MANUAL`. This means Hibernate will never automatically flush, even if you run a query. No pending changes should exist in a read-only transaction, so flushing is unnecessary.

### 3. Spring Sets the JDBC Connection to Read-Only

Spring calls `connection.setReadOnly(true)` on the JDBC connection. PostgreSQL uses this hint to optimize query execution. In some configurations, PostgreSQL can route read-only transactions to read replicas.

### 4. PostgreSQL Gets a Read-Only Transaction Hint

The database receives `SET TRANSACTION READ ONLY`. PostgreSQL will reject any INSERT, UPDATE, or DELETE statements within this transaction. This is a safety net - if your "read-only" code accidentally tries to modify data, the database will catch it.

### When to Use readOnly = true

Use it on every method that only reads data. It is not just documentation - it is a real performance improvement, especially for methods that load many entities.

```java
@Service
public class ProductService {

    @Transactional(readOnly = true)
    public Product findById(Long id) {
        return productRepository.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Product> searchByName(String name) {
        return productRepository.findByNameContaining(name);
    }

    @Transactional // readOnly defaults to false
    public Product create(String name, BigDecimal price) {
        return productRepository.save(new Product(name, price));
    }
}
```

You can also put `@Transactional(readOnly = true)` at the class level and override it on methods that need to write:

```java
@Service
@Transactional(readOnly = true) // Default for all methods
public class ProductService {

    public Product findById(Long id) { ... }       // read-only
    public List<Product> searchByName(String name) { ... }  // read-only

    @Transactional // Override: readOnly = false for this method
    public Product create(String name, BigDecimal price) { ... }
}
```

---

## Rollback Behavior - The Exception Trap

By default, `@Transactional` rolls back on **unchecked exceptions** (subclasses of `RuntimeException`) and **errors**. It does **not** roll back on **checked exceptions**.

This catches many developers by surprise.

```java
@Transactional
public void processPayment() throws PaymentException {
    orderRepository.save(order);

    try {
        paymentGateway.charge(amount);
    } catch (GatewayException e) {
        // GatewayException is a checked exception
        throw new PaymentException("Payment failed", e);
    }
    // If PaymentException is a checked exception,
    // the transaction COMMITS even though we threw an exception!
    // The order is saved. The payment failed. Data is inconsistent.
}
```

This is a dangerous default. If your checked exception means "something went wrong," you probably want a rollback. But Spring will commit.

### Fix: Use rollbackFor

```java
@Transactional(rollbackFor = Exception.class)
public void processPayment() throws PaymentException {
    // Now the transaction rolls back on ANY exception,
    // checked or unchecked.
}
```

My recommendation: **always use `rollbackFor = Exception.class`** on transactional methods that throw checked exceptions. It is safer to roll back on everything and explicitly not roll back when needed (using `noRollbackFor`) than to accidentally commit after a failure.

```java
// Safe default: roll back on everything
@Transactional(rollbackFor = Exception.class)
public void riskyOperation() throws BusinessException {
    // ...
}

// If you need to commit despite a specific exception:
@Transactional(
    rollbackFor = Exception.class,
    noRollbackFor = NonCriticalException.class
)
public void operationWithExpectedException() throws NonCriticalException {
    // ...
}
```

---

## Where to Put @Transactional

This is not a matter of taste. There is a correct answer.

**Put `@Transactional` on your service layer.** Not on controllers. Not on repositories.

### Why Not on Controllers?

Controllers handle HTTP concerns - parsing requests, validating input, formatting responses. A controller should not decide when a transaction starts and ends. If you put `@Transactional` on a controller, your transaction stays open while Spring serializes the response to JSON, writes HTTP headers, and flushes the response body. That is wasted time holding a database connection.

```java
// BAD: Transaction is open during response serialization
@RestController
public class OrderController {

    @Transactional // Don't do this
    @PostMapping("/orders")
    public OrderResponse createOrder(@RequestBody OrderRequest request) {
        Order order = orderService.create(request);
        return new OrderResponse(order); // Transaction still open here
    }
}
```

### Why Not on Repositories?

Spring Data JPA repositories already run each method in a transaction by default (using `@Transactional` internally, with `readOnly = true` for query methods). If you add your own `@Transactional` to a repository, you create individual transactions per repository call. That means two repository calls in the same service method run in separate transactions - they do not see each other's changes and do not roll back together.

```java
// BAD: Each repository call is its own transaction
public void transferFunds(Long fromId, Long toId, BigDecimal amount) {
    accountRepo.debit(fromId, amount);   // Transaction 1 - commits
    accountRepo.credit(toId, amount);    // Transaction 2 - what if this fails?
    // First debit is already committed. Money disappeared.
}
```

### The Right Way

```java
@Service
public class TransferService {

    @Transactional
    public void transferFunds(Long fromId, Long toId, BigDecimal amount) {
        Account from = accountRepository.findById(fromId).orElseThrow();
        Account to = accountRepository.findById(toId).orElseThrow();

        from.debit(amount);
        to.credit(amount);
        // Both changes are in the same transaction.
        // If anything fails, everything rolls back.
    }
}
```

---

## @Modifying Queries - What They Do and What They Don't

Spring Data JPA lets you write custom queries using the `@Query` annotation. When your query changes data - an UPDATE, DELETE, or INSERT - you must also add `@Modifying`. Without it, Spring Data tries to execute your query as a SELECT and it fails.

But here is the important part: **`@Modifying` does not manage transactions.** It does not start a transaction. It does not provide a transaction. Its primary job is to tell Spring Data to call `executeUpdate()` instead of `getResultList()`. It also provides two Persistence Context hooks - `clearAutomatically` and `flushAutomatically` - which we will cover shortly. But transaction management? That is not its job.

### How SimpleJpaRepository Manages Transactions

Before we talk about `@Modifying` and transactions, you need to understand how Spring Data JPA handles transactions on your repository methods by default. When Spring creates your repository at runtime, the actual implementation behind it is `SimpleJpaRepository`. It looks like this:

```java
@Repository
@Transactional(readOnly = true)  // Class-level: all methods default to read-only
public class SimpleJpaRepository<T, ID> implements JpaRepository<T, ID> {

    @Transactional  // Override: read-write
    public <S extends T> S save(S entity) { ... }

    @Transactional  // Override: read-write
    public void delete(T entity) { ... }

    @Transactional  // Override: read-write
    public void flush() { ... }

    // findById(), findAll(), count(), existsById()
    // → inherit class-level @Transactional(readOnly = true)
}
```

So the layout is:
- **Read methods** (`findById`, `findAll`, `count`, `existsById`) run in a `readOnly = true` transaction
- **Write methods** (`save`, `saveAll`, `delete`, `deleteAll`, `flush`) run in a read-write transaction

Each call gets its own transaction - **unless** there is already an active transaction from a calling service method. In that case, the repository method joins the existing transaction via `REQUIRED` propagation (the default).

This means if you call `save()` from a `@Transactional` service method, it joins your service transaction. If you call `save()` directly from a controller (with no `@Transactional`), it creates its own short transaction.

### The Problem: @Modifying Without @Transactional

Now here is where developers get confused. The `@Transactional(readOnly = true)` on `SimpleJpaRepository` applies to the **built-in CRUD methods** that are implemented inside that class. But custom `@Query` methods defined on your repository **interface** are different - they are handled by Spring Data's query execution infrastructure, not by `SimpleJpaRepository`.

According to the Spring Data JPA documentation: **"Declared query methods do not get any transaction configuration applied by default."**

So when you write:

```java
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Modifying
    @Query("UPDATE Order o SET o.status = 'CANCELLED' WHERE o.status = 'CREATED' AND o.createdAt < :cutoff")
    int cancelStaleOrders(@Param("cutoff") LocalDateTime cutoff);
}
```

This method has **no transactional configuration**. It does not inherit `readOnly = true` from `SimpleJpaRepository`, and it does not get its own transaction either.

If you call `cancelStaleOrders()` directly - not from within an existing `@Transactional` service method - there is no active transaction. JPA requires `executeUpdate()` to run inside a transaction. Without one, you get a `TransactionRequiredException`. The query simply cannot execute.

### Two Ways to Fix It

Both approaches are valid. The [Spring Data JPA documentation](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html) itself shows `@Transactional` directly on `@Modifying` methods. At the same time, the docs note: *"we generally recommend declaring transaction boundaries when starting a unit of work."* Vlad Mihalcea takes a [stronger position](https://vladmihalcea.com/spring-transactional-annotation/): *"The @Transactional annotation belongs to the Service layer because it is the Service layer's responsibility to define the transaction boundaries."*

In practice, it depends on what your method does.

**Option 1: Add `@Transactional` on the repository method**

```java
@Modifying
@Transactional
@Query("UPDATE Order o SET o.status = 'CANCELLED' WHERE o.status = 'CREATED' AND o.createdAt < :cutoff")
int cancelStaleOrders(@Param("cutoff") LocalDateTime cutoff);
```

This gives the method its own read-write transaction. It is simple and self-contained - the method works correctly no matter where you call it from. Use this when the `@Modifying` query is a standalone operation that does not need to be grouped with other database calls.

**Option 2: Call it from a `@Transactional` service method**

```java
@Service
public class OrderService {

    @Transactional
    public int cancelStaleOrders(LocalDateTime cutoff) {
        return orderRepository.cancelStaleOrders(cutoff);
        // The @Modifying query joins this service transaction. Works fine.
    }
}
```

Use this when the `@Modifying` query is part of a larger operation - for example, when you need to cancel orders, create audit records, and send notifications in the same transaction. The service method gives you one atomic unit of work across multiple repository calls.

**Tip:** You can also add `@Transactional(readOnly = true)` at the repository interface level to cover all your custom query methods, then override with `@Transactional` on specific `@Modifying` methods:

```java
@Transactional(readOnly = true)  // Default for all custom query methods
public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByStatus(OrderStatus status);  // gets readOnly = true

    @Modifying
    @Transactional  // Override: read-write for this modifying query
    @Query("UPDATE Order o SET o.status = 'CANCELLED' WHERE o.status = 'CREATED' AND o.createdAt < :cutoff")
    int cancelStaleOrders(@Param("cutoff") LocalDateTime cutoff);
}
```

### The Stale Persistence Context Trap

There is another catch with `@Modifying` that has nothing to do with transactions. A modifying query goes straight to the database via JPQL. Hibernate does not know about it. The Persistence Context still holds the old versions of any entities you loaded before the query.

```java
@Transactional
public void demo() {
    Order order = orderRepository.findById(1L).orElseThrow();
    log.info("Status before: {}", order.getStatus()); // CREATED

    orderRepository.cancelStaleOrders(LocalDateTime.now());

    Order same = orderRepository.findById(1L).orElseThrow();
    log.info("Status after: {}", same.getStatus()); // Still CREATED!
    // The Persistence Context returned the cached entity.
    // The database says CANCELLED, but Hibernate does not know that.
}
```

The second `findById` does not go to the database. It returns the same managed entity from the Persistence Context - with the old status. This is a real source of bugs.

To fix this, use `clearAutomatically`:

```java
@Modifying(clearAutomatically = true)
@Query("UPDATE Order o SET o.status = 'CANCELLED' WHERE o.status = 'CREATED' AND o.createdAt < :cutoff")
int cancelStaleOrders(@Param("cutoff") LocalDateTime cutoff);
```

`clearAutomatically = true` calls `entityManager.clear()` after the query runs, forcing any subsequent reads to go to the database. There is also `flushAutomatically = true`, which flushes pending entity changes to the database **before** the modifying query runs - so the query sees your uncommitted changes.

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("UPDATE Order o SET o.status = 'CANCELLED' WHERE o.status = 'CREATED' AND o.createdAt < :cutoff")
int cancelStaleOrders(@Param("cutoff") LocalDateTime cutoff);
```

Use both when you mix entity operations with bulk `@Modifying` queries in the same transaction.

---

## The Five Most Common @Transactional Mistakes

Let us recap the traps we covered, plus a few more:

### 1. Self-Invocation
Calling a `@Transactional` method from within the same class. The proxy is bypassed. No transaction is created.

### 2. Checked Exception Rollback
Throwing a checked exception. The transaction commits instead of rolling back. Use `rollbackFor = Exception.class`.

### 3. Private Methods
Putting `@Transactional` on a private method. CGLIB proxies cannot override private methods, so the annotation is silently ignored. Always use public methods.

```java
@Transactional
private void doWork() {
    // @Transactional is IGNORED. CGLIB cannot override private methods.
    // No compiler warning. No runtime error. It just does not work.
}
```

### 4. Catching Exceptions Inside the Transaction
Catching a `RuntimeException` inside a `@Transactional` method can lead to unexpected behavior. If the exception comes from code that does not pass through a transactional proxy (a plain method call, a utility, etc.), catching it prevents the rollback and the transaction commits - potentially with inconsistent data.

```java
@Transactional
public void riskyMethod() {
    try {
        someUtility.doWork(); // throws RuntimeException, NOT @Transactional
    } catch (RuntimeException e) {
        log.error("Error occurred", e);
        // Transaction commits! The exception was caught before Spring saw it.
        // If doWork() partially modified managed entities, those changes are committed.
    }
}
```

But if the exception comes from a `@Transactional(propagation = REQUIRED)` method - one that joins your transaction - the situation is different. Spring's proxy sees the exception on the way out, marks the transaction as rollback-only, and then the exception reaches your catch block. You can catch it, but it is too late. The transaction is doomed. When your method finishes and Spring tries to commit, it throws `UnexpectedRollbackException`.

```java
@Transactional
public void riskyMethod() {
    try {
        transactionalService.doWork(); // @Transactional, throws RuntimeException
    } catch (RuntimeException e) {
        log.error("Caught it, but too late", e);
        // The transaction is already marked rollback-only.
        // Commit will throw UnexpectedRollbackException.
    }
}
```

### 5. Missing Spring Context
Using `@Transactional` on a class that is not a Spring bean. If you create an object with `new MyService()` instead of letting Spring inject it, there is no proxy, and `@Transactional` does nothing. This can also happen in unit tests where you instantiate the class directly.

---

## Key Takeaways

1. **`@Transactional` works through proxies.** Spring creates a wrapper around your bean that manages the transaction. The proxy only intercepts calls from outside the class.

2. **Self-invocation bypasses the proxy.** If you call a `@Transactional` method from within the same class, no transaction is created. Extract the method to a separate service.

3. **REQUIRED (default) joins existing transactions.** If the inner method fails, the entire transaction rolls back - even if you catch the exception. Use `REQUIRES_NEW` when you need isolation.

4. **`readOnly = true` is a real optimization**, not just documentation. It skips dirty checking, disables auto-flush, and tells PostgreSQL to optimize for reads.

5. **Checked exceptions do not trigger rollback by default.** Use `rollbackFor = Exception.class` on any transactional method that throws checked exceptions.

6. **Put `@Transactional` on service methods.** Not on controllers (wastes connection time) or repositories (breaks atomicity).

---

## What Is Next

Now that you understand how `@Transactional` manages your database operations, the next question is: are those operations efficient? In Part 3, we will hunt down the N+1 query problem, explore fetch strategies, and learn how to make your JPA code fast without sacrificing readability.

---

*This is Part 2 of a 4-part series on JPA and @Transactional. The complete source code with runnable examples is available on [GitHub](https://github.com/yourusername/jpa-transactional-deep-dive).*
