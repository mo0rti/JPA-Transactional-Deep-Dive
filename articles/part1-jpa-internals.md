# What Actually Happens When You Save an Entity - JPA & Hibernate Internals Demystified

You call `repository.save(entity)` ten times a day. But can you answer this without running the code - does it generate an INSERT or an UPDATE? And when exactly does that SQL hit PostgreSQL?

Most senior developers cannot answer this confidently. They use JPA like a black box. It works, until it does not. Then they spend hours debugging strange behaviors - duplicate inserts, lost updates, unexpected queries.

This article opens the box. We will follow a simple entity through its entire lifecycle and watch exactly what Hibernate does at every step. No magic. No hand-waving. Just code, SQL output, and clear explanations.

By the end, you will understand JPA better than most developers who have used it for years.

---

## What We Are Building

We will work with a simple `Product` entity throughout this article. Every concept will be demonstrated with runnable code and real SQL output from PostgreSQL.

```java
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "product_seq")
    @SequenceGenerator(name = "product_seq", sequenceName = "product_seq", allocationSize = 1)
    private Long id;

    private String name;
    private BigDecimal price;

    // constructors, getters, setters
}
```

Why `SEQUENCE` and not `IDENTITY`? That choice matters a lot, and we will explain why later in this article. For now, just know that PostgreSQL sequences give Hibernate more room to optimize.

---

## The Persistence Context - Your Invisible Middle Layer

When you work with JPA, you never talk to the database directly. There is always something sitting between your code and PostgreSQL. That something is the **Persistence Context**.

Think of it as a smart map. It holds every entity you have loaded or saved during the current transaction. It tracks changes. It decides when to write to the database. It makes sure that if you load the same row twice, you get the same Java object - not two separate copies.

In Hibernate, the Persistence Context lives inside a `Session` object. Spring wraps that Session inside an `EntityManager`. When you use Spring Data JPA repositories, Spring wraps the EntityManager too. So the chain looks like this:

```
Your Code → Repository → EntityManager → Session → Persistence Context → Database
```

Every layer adds convenience, but the Persistence Context is where the real decisions happen.

### One Transaction, One Persistence Context

By default, Spring creates one Persistence Context per transaction. When the transaction starts, the Persistence Context is empty. As you load and save entities, they get stored in this context. When the transaction commits, the Persistence Context is flushed - meaning all pending changes are written to the database - and then it is destroyed.

```
Transaction Starts  →  Persistence Context Created (empty)
  Load entity       →  Entity added to context
  Modify entity     →  Change tracked automatically
  Load same entity  →  Returned from context (no SQL)
Transaction Commits →  Flush + Context Destroyed
```

This is the single most important concept in JPA. Everything else builds on top of it.

---

## The Four States of an Entity

Every JPA entity exists in one of four states. Understanding these states is the key to understanding why Hibernate generates the SQL it does.

### 1. Transient - The Database Does Not Know About It

When you create an entity with `new`, it is **transient**. Hibernate has no idea it exists. The Persistence Context does not track it. It is just a regular Java object.

```java
Product product = new Product();
product.setName("Keyboard");
product.setPrice(new BigDecimal("49.99"));
// This is transient. No SQL. No tracking. Just a Java object.
```

### 2. Managed - Hibernate Is Watching

When an entity enters the Persistence Context, it becomes **managed**. This happens when you:

- Call `entityManager.persist(entity)` on a transient entity
- Load an entity from the database using `find()`, `getReference()`, or a query
- Call `entityManager.merge(entity)` on a detached entity (the *returned copy* is managed)

A managed entity is special. Hibernate takes a snapshot of its field values the moment it enters the context. From that point on, Hibernate watches it. If you change any field, Hibernate will notice the difference at flush time and generate an UPDATE - even if you never explicitly call save again.

```java
@Transactional
public void updatePrice(Long productId, BigDecimal newPrice) {
    Product product = entityManager.find(Product.class, productId);
    // product is now managed. Hibernate took a snapshot.

    product.setPrice(newPrice);
    // That is it. No save() call needed.
    // At commit time, Hibernate compares current state vs snapshot.
    // It sees the price changed. It generates an UPDATE.
}
```

This surprises many developers. You do not need to call `save()` or `update()` on a managed entity. Hibernate detects the change automatically. This feature is called **dirty checking**, and we will explore it in detail later.

### 3. Detached - Once Managed, Now Disconnected

When the Persistence Context is destroyed (typically when the transaction ends), all managed entities become **detached**. The entity still has data - including its ID - but Hibernate no longer tracks it.

```java
@Transactional
public Product findProduct(Long id) {
    Product product = entityManager.find(Product.class, id);
    return product;
    // After this method returns, the transaction commits,
    // the Persistence Context is destroyed,
    // and 'product' becomes detached.
}
```

If you modify a detached entity and want those changes saved, you need to bring it back into a Persistence Context using `merge()`.

### 4. Removed - Marked for Deletion

When you call `entityManager.remove(entity)` on a managed entity, it becomes **removed**. Hibernate will generate a DELETE statement at flush time.

```java
@Transactional
public void deleteProduct(Long productId) {
    Product product = entityManager.find(Product.class, productId);
    entityManager.remove(product);
    // product is now in 'removed' state.
    // DELETE SQL will be generated at flush time.
}
```

You cannot remove a transient or detached entity. It must be managed first. That is why you typically load the entity before removing it.

### The State Diagram

```
                            persist()
    new Product()        ┌──────────────→┐
  ┌────────────────┐     │ ┌───────────┐ │
  │   TRANSIENT    │─────┘ │  MANAGED  │←───── find() / query
  └────────────────┘       └───────────┘
                             │  ↑    │
                  remove()   │  │    │ transaction ends /
                             │  │    │ context cleared
                             ↓  │    ↓
                        ┌──────────┐  ┌──────────┐
                        │ REMOVED  │  │ DETACHED │
                        └──────────┘  └──────────┘
                                          │
                                merge()   │ (returned copy
                                (returns  │  becomes managed)
                                 managed  │
                                  copy)───┘→ back to MANAGED
```

---

## Dirty Checking - How Hibernate Detects Changes

This is one of the most important mechanisms in Hibernate, and it is invisible. You never call it. You never see it. But it runs every time the Persistence Context flushes.

Here is how it works:

1. When an entity becomes managed (loaded or persisted), Hibernate stores a **snapshot** - a copy of all the entity's field values at that moment.
2. At flush time, Hibernate compares the **current state** of every managed entity against its snapshot.
3. For every entity where the values differ, Hibernate generates an UPDATE statement.

```java
@Transactional
public void demonstrateDirtyChecking() {
    Product product = productRepository.findById(1L).orElseThrow();
    // Hibernate snapshot: {name="Keyboard", price=49.99}

    product.setName("Mechanical Keyboard");
    // Current state: {name="Mechanical Keyboard", price=49.99}
    // Snapshot:      {name="Keyboard", price=49.99}
    // Difference detected → UPDATE will be generated at flush

    // No save() call. The transaction commit triggers flush.
    // Hibernate generates:
    // UPDATE products SET name='Mechanical Keyboard', price=49.99 WHERE id=1
}
```

Notice something important. The UPDATE includes **all columns**, not just the changed one. Hibernate updates `price` too, even though it did not change. This is the default behavior. Hibernate does this because it can reuse prepared statements - the same UPDATE shape works for any combination of changed fields.

If you want Hibernate to update only the changed columns, you can use `@DynamicUpdate` on the entity:

```java
@Entity
@DynamicUpdate
@Table(name = "products")
public class Product {
    // ...
}
// Now generates: UPDATE products SET name='Mechanical Keyboard' WHERE id=1
```

Use `@DynamicUpdate` when your table has many columns and you frequently update only a few. For small entities like our Product, the default is fine.

### The Cost of Dirty Checking

Dirty checking is not free. At flush time, Hibernate iterates through **every managed entity** in the Persistence Context and compares every field. If you load 1,000 entities in a single transaction, Hibernate will check all 1,000 at every flush - even if you only modified one.

This is one reason why you should keep your transactions focused and avoid loading large numbers of entities unnecessarily. We will revisit this topic in Part 3 when we discuss performance.

---

## Flushing - When SQL Actually Hits the Database

Here is a question that trips up many developers: if you call `persist()` on an entity, does the INSERT happen immediately?

**No.** In most cases, it does not.

Hibernate delays SQL execution as long as possible. The act of actually sending SQL to the database is called **flushing**. Hibernate flushes at specific points, and the behavior depends on the **flush mode**.

### FlushModeType.AUTO (The Default)

With AUTO, Hibernate flushes:

1. **Before the transaction commits** - This is the most common trigger. When Spring's `@Transactional` method returns normally, the transaction commits, and Hibernate flushes all pending changes first.

2. **Before a query that might be affected by pending changes** - If you persist a new Product and then run a query like `SELECT * FROM products`, Hibernate flushes the pending INSERT first. It does this to keep query results consistent with pending changes.

```java
@Transactional
public void autoFlushDemo() {
    Product product = new Product();
    product.setName("Mouse");
    product.setPrice(new BigDecimal("29.99"));
    entityManager.persist(product);
    // No INSERT yet. The entity is managed but the SQL is pending.

    // This query triggers a flush because it queries the products table
    List<Product> all = entityManager
        .createQuery("SELECT p FROM Product p", Product.class)
        .getResultList();
    // INSERT was executed BEFORE the SELECT, so 'Mouse' appears in results.
}
```

**A note about native queries:** In modern Hibernate (5.2+, which includes all Spring Boot 3.x versions), AUTO mode **also flushes before native SQL queries**. Since Hibernate cannot parse native SQL to determine which tables are involved, it takes the safe approach and flushes everything. This is a change from older Hibernate versions, where native queries did not trigger auto-flush.

If you are working with an older codebase (pre-Hibernate 5.2), native queries could bypass auto-flush. But with any modern Spring Boot project, you do not need to worry about this.

### FlushModeType.COMMIT

With COMMIT mode, Hibernate only flushes when the transaction commits. It never flushes before queries. This gives better performance - fewer round trips to the database - but you might get stale query results within the same transaction.

```java
entityManager.setFlushMode(FlushModeType.COMMIT);
```

In practice, most applications should stay with AUTO. COMMIT mode is useful only in specific batch processing scenarios where you control the query order carefully.

### Manual Flush

You can always force a flush by calling:

```java
entityManager.flush();
```

This sends all pending SQL to the database immediately, but it does **not** commit the transaction. The changes are visible to the current database connection but not to other connections (depending on isolation level). If the transaction rolls back after a flush, all those changes are undone.

---

## persist() vs merge() vs save() - The Real Differences

This is where many developers get confused. Let us clear it up.

### entityManager.persist()

- Takes a **transient** entity and makes it **managed**
- Does **not** return anything (void)
- The original object becomes managed - Hibernate tracks it
- An INSERT is scheduled (but not executed immediately unless using IDENTITY strategy)
- If the entity is already managed, the call is ignored - it is a no-op. If you pass a **detached** entity, Hibernate throws `PersistentObjectException`. Note that `persist()` does not check the database - if you manually set an ID that already exists in the database, the error will only show up later at flush time as a constraint violation

```java
Product product = new Product();
product.setName("Monitor");
entityManager.persist(product);
// The 'product' object is now managed.
// Hibernate called the sequence to get an ID.
// INSERT is pending, will execute at flush.
```

With `GenerationType.SEQUENCE`, calling `persist()` triggers an immediate call to the PostgreSQL sequence (`SELECT nextval('product_seq')`) to get the ID. But the INSERT itself is delayed until flush. This is an important optimization - Hibernate can batch multiple inserts together.

With `GenerationType.IDENTITY`, Hibernate **must** execute the INSERT immediately because the ID is generated by the database during insertion. This means Hibernate cannot batch IDENTITY inserts, which is one reason SEQUENCE is preferred for PostgreSQL.

### entityManager.merge()

- Takes a **detached** or **transient** entity
- **Returns a new managed copy** - the original object stays detached (or transient)
- If the entity is transient (no ID), `merge()` behaves like `persist()` - it creates a new managed copy and schedules an INSERT. But unlike `persist()`, the original object is still not managed
- If the entity has an ID, Hibernate checks if it is already in the Persistence Context. If not, it loads it from the database (SELECT). Then it copies the field values from your detached entity onto the managed copy.

```java
Product detached = // some entity from outside this transaction
Product managed = entityManager.merge(detached);

// 'detached' is still detached - changes to it are NOT tracked
// 'managed' is the one Hibernate watches
// Common mistake: continuing to use 'detached' after merge
```

This is a common source of bugs. Developers call `merge()` and then continue modifying the original object, expecting those changes to be persisted. They are not. You must use the returned object.

### repository.save() - Spring Data's Wrapper

Spring Data JPA's `save()` method is a convenience wrapper. Here is what it actually does (simplified):

```java
public <S extends T> S save(S entity) {
    if (entityInformation.isNew(entity)) {
        entityManager.persist(entity);
        return entity;
    } else {
        return entityManager.merge(entity);
    }
}
```

It checks if the entity is "new" using the `isNew()` method. By default:
- If the `@Id` field is `null` (for object types) or `0` (for primitives) → the entity is new → calls `persist()`
- If the `@Id` field has a value → the entity is not new → calls `merge()`

This is why the ID generation strategy matters so much:

```java
// SEQUENCE strategy
Product product = new Product(); // id is null
productRepository.save(product);
// isNew() returns true → persist() is called
// Result: SELECT nextval('product_seq') + INSERT (at flush)

// But what if you manually set the ID?
Product product = new Product();
product.setId(999L); // id is NOT null
productRepository.save(product);
// isNew() returns false → merge() is called
// Result: SELECT (to load existing) + INSERT (if not found)
// This is wasteful! Hibernate does an unnecessary SELECT.
```

This is a real problem when you use assigned IDs (like UUIDs that you generate in code). Spring thinks the entity already exists and calls `merge()`, which triggers an unnecessary SELECT. The solution is to implement `Persistable<T>`:

```java
@Entity
public class Product implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
```

But for most cases, just use `GenerationType.SEQUENCE` and let Hibernate handle the IDs. It avoids this problem entirely.

---

## Open Session in View (OSIV) - The Silent Default

Spring Boot enables a feature called **Open Session in View** by default. This means the Hibernate Session (and the Persistence Context) stays open for the entire HTTP request - not just during the `@Transactional` service method, but also during view rendering or JSON serialization in the controller.

```
HTTP Request arrives
  → Session opened (OSIV filter)
  → Controller method called
    → @Transactional service method runs
    → Transaction commits, but Session stays open
  → Controller serializes response (lazy loading still works!)
  → Session closed
```

Why does Spring do this? Because without it, any lazy-loaded relationship that you access outside a transaction throws a `LazyInitializationException`. OSIV keeps the Session alive so lazy loading works everywhere.

### Why OSIV Is a Problem

It sounds convenient, but OSIV causes real problems in production:

1. **Lazy loading fires outside your transaction, grabbing database connections on demand.** After the `@Transactional` method returns and the transaction commits, the JDBC connection is released back to the pool. But the Hibernate Session is still open. If lazy loading triggers during JSON serialization, Hibernate needs to acquire a connection from the pool again to run those queries. Under load, these on-demand connections outside your transaction boundary pile up and can put pressure on your connection pool.

2. **It hides bad code.** When lazy loading works everywhere, developers never learn to think about fetch strategies. They load an entity in the service, return it to the controller, and access lazy collections during serialization. This works, but it generates SQL queries outside your transaction boundary - queries you cannot see in your service layer.

3. **N+1 queries become invisible.** Because lazy loading silently fires in the controller, N+1 problems happen where you do not expect them - and they are much harder to detect.

### Turn It Off

Add this to your `application.yml`:

```yaml
spring:
  jpa:
    open-in-view: false
```

When you turn off OSIV, you will get `LazyInitializationException` if you access unloaded lazy relationships outside a transaction. That is a good thing. It forces you to load everything you need inside the service layer, where you have control. We will cover how to do this properly in Part 3.

Spring Boot logs a warning at startup if OSIV is enabled:

```
WARN: spring.jpa.open-in-view is enabled by default.
Therefore, database queries may be performed during view rendering.
Explicitly configure spring.jpa.open-in-view to disable this warning.
```

Most developers ignore this warning. Do not be one of them.

---

## Putting It All Together - A Complete Example

Let us trace through a realistic scenario step by step. We have a service that creates a product, queries for products, and updates a price - all in one transaction.

```java
@Service
public class ProductService {

    @Autowired
    private EntityManager entityManager;

    @Transactional
    public void completeDemo() {
        // Step 1: persist a new product
        Product keyboard = new Product();
        keyboard.setName("Keyboard");
        keyboard.setPrice(new BigDecimal("49.99"));
        entityManager.persist(keyboard);
        // SQL: SELECT nextval('product_seq')  ← ID generated immediately
        // No INSERT yet. Entity is managed with id=1.

        // Step 2: query all products
        List<Product> products = entityManager
            .createQuery("SELECT p FROM Product p", Product.class)
            .getResultList();
        // SQL: INSERT INTO products (id, name, price) VALUES (1, 'Keyboard', 49.99)
        //      ↑ Auto-flush! Hibernate flushes before the query.
        // SQL: SELECT p.id, p.name, p.price FROM products p

        // Step 3: modify the product - no save needed
        keyboard.setPrice(new BigDecimal("59.99"));
        // No SQL yet. Dirty checking will catch this at commit.

        // Step 4: method returns, transaction commits
        // SQL: UPDATE products SET name='Keyboard', price=59.99 WHERE id=1
        //      ↑ Dirty checking detected the price change.
    }
}
```

Total SQL executed:
1. `SELECT nextval('product_seq')` - at persist() time
2. `INSERT INTO products ...` - auto-flush before query
3. `SELECT ... FROM products` - the JPQL query
4. `UPDATE products ...` - flush at commit

Four SQL statements. If you did not understand the Persistence Context, you might have expected a different order - or missed the UPDATE entirely because there is no `save()` call.

---

## Key Takeaways

1. **The Persistence Context is a smart cache** that sits between your code and the database. It tracks entity states, detects changes, and decides when to write SQL.

2. **Entities have four states:** transient (new, unknown to Hibernate), managed (tracked, changes auto-detected), detached (previously managed, no longer tracked), removed (scheduled for deletion).

3. **Dirty checking** means you do not need to call `save()` on managed entities. Hibernate compares current state against a snapshot at flush time and generates UPDATEs automatically.

4. **Flushing is not committing.** SQL is sent to the database at flush time, but it is only permanent when the transaction commits. AUTO flush mode sends SQL before queries and at commit time.

5. **`persist()` is for new entities, `merge()` is for detached entities.** `save()` decides which one to call based on the entity's ID. When in doubt, use `persist()` for new entities - it is simpler and avoids the unnecessary SELECT that `merge()` can cause.

6. **Turn off OSIV** (`spring.jpa.open-in-view: false`). It hides problems and wastes database connections. Load everything you need inside your service layer.

---

## What Is Next

Now that you understand what happens inside JPA, we are ready to explore the annotation that controls when and how all of this runs - `@Transactional`. In Part 2, we will look at how Spring creates proxies, what propagation levels actually mean, and the silent bugs that happen when `@Transactional` does not work the way you think it does.

---

*This is Part 1 of a 4-part series on JPA and @Transactional. The complete source code with runnable examples is available on [GitHub](https://github.com/yourusername/jpa-transactional-deep-dive).*
