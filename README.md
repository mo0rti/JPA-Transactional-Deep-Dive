# JPA & @Transactional Deep Dive

Companion repository for the **JPA & @Transactional** article series. Each module contains runnable code examples that demonstrate the concepts covered in the corresponding article.

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker & Docker Compose (for PostgreSQL)

## Getting Started

**1. Start PostgreSQL:**

```bash
docker compose up -d
```

**2. Run a specific module:**

```bash
cd part1-jpa-internals
mvn spring-boot:run
```

Watch the console output — each runner prints labeled sections showing exactly what SQL Hibernate generates and when.

## Modules

| Module | Article | What It Demonstrates |
|--------|---------|---------------------|
| `part1-jpa-internals` | Part 1 — JPA Internals | Entity states, Persistence Context, dirty checking, flush modes, persist vs merge |
| `part2-transactional` | Part 2 — @Transactional | Proxy mechanics, propagation, readOnly, rollback behavior |
| `part3-performance` | Part 3 — Performance Traps | N+1 queries, fetch strategies, DTO projections |
| `part4-production` | Part 4 — Production Patterns | Event listeners, retries, outbox pattern, testing |

## SQL Logging

All modules have SQL logging enabled by default. You will see:
- The actual SQL statements (`org.hibernate.SQL=DEBUG`)
- Bound parameter values (`org.hibernate.orm.jdbc.bind=TRACE`)
- Hibernate statistics (`hibernate.generate_statistics=true`)
