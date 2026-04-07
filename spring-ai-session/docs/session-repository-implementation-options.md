# SessionRepository Implementation Options: Spring Session vs Spring Data

This document answers two practical questions:

1. Can Spring Session serve as an implementation backend for `SessionRepository`?
2. Is Spring Data a better alternative?

*(For why the Spring AI Session API is not modeled after Spring Session, see
[why-not-spring-session-api.md](why-not-spring-session-api.md).  
For why `SessionRepository` does not extend `CrudRepository`, see
[why-not-spring-data-repository.md](why-not-spring-data-repository.md).)*

---

## Option 1: Spring Session as Implementation Backend

**Technically possible, but a poor fit.**

Spring Session stores a session as a **single blob of attributes** (a `Map`). To back Spring AI
Session on top of it you would have to serialize the entire `List<SessionEvent>` into one session
attribute and reload it wholesale on every operation.

| Spring AI Session need | Spring Session capability | Gap |
|---|---|---|
| `appendEvent` (atomic append) | Load whole blob, modify, save | No atomic append; race conditions |
| `findEvents(EventFilter)` — query by time, type, keyword, branch | No query on attribute contents | Impossible without full deserialization + in-process scan |
| `getEventVersion` + CAS `replaceEvents` | No versioning on attributes | Must be bolted on manually, fragile |
| Large event histories (1000s of messages) | Single serialized attribute | Unbounded blob; no pagination at storage layer |
| `findByUserId` | `FindByIndexNameSessionRepository` (principal name index) | Could work, but HTTP-session-focused and awkward |

**Verdict**: Spring Session is an HTTP session attribute store. Using it as an AI conversation
event log is using the wrong tool — you lose all event granularity and query capability.

---

## Option 2: Spring Data as Implementation Backend

**Yes — this is the right approach for production implementations.**

The key insight (see also [why-not-spring-data-repository.md](why-not-spring-data-repository.md))
is: do not make `SessionRepository` *extend* a Spring Data repository. Instead, write concrete
implementations that *use* Spring Data internally — the Anti-Corruption Layer pattern.

```
SessionRepository  (Spring AI SPI)
        ↑ implements
JpaSessionRepository
        → uses → Spring Data JPA repositories internally
```

This maps cleanly across storage backends:

| Spring AI concern | Relational (JPA/JDBC) | MongoDB | Redis |
|---|---|---|---|
| `Session` metadata | `ai_sessions` table | `sessions` collection | Hash |
| `SessionEvent` log | `ai_session_events` table (FK → session) | Embedded array or `events` collection | Sorted Set by timestamp |
| `appendEvent` | `INSERT` one row | `$push` | `ZADD` |
| `findEvents(EventFilter)` | SQL `WHERE` clauses | MongoDB query | `ZRANGEBYSCORE` + scan |
| CAS `replaceEvents` | `DELETE + INSERT` in a transaction with version check | Atomic `findAndModify` | `MULTI/EXEC` |
| `findByUserId` | `SELECT WHERE user_id = ?` | field query | secondary index |

Each backend gets its own module, following the same pattern as Spring Session itself:

```
spring-ai-session               ← SPI + InMemorySessionRepository (dev/test)
spring-ai-session-jdbc          ← JPA/JDBC impl via Spring Data JPA
spring-ai-session-mongodb       ← MongoDB impl via Spring Data MongoDB
spring-ai-session-redis         ← Redis impl via Spring Data Redis
```

---

## Which Spring Data Backend Fits Best?

| Backend | Strengths for this domain | Weaknesses |
|---|---|---|
| **JPA / JDBC** | Natural two-table model, rich SQL queries for `EventFilter`, transactions for CAS, works everywhere | Keyword search needs `LIKE` or a full-text extension |
| **MongoDB** | Document model fits event log well, rich query API, atomic `findAndModify` for CAS | Extra infrastructure to operate |
| **Redis** | Very fast, native TTL, Sorted Set for time-ordered events | Keyword search requires RediSearch module; large histories get expensive |

For most projects, **JDBC/JPA is the lowest-friction starting point** — two tables, standard SQL,
no extra infrastructure. MongoDB is a strong second if it is already in the stack.

---

## Summary

| Option | Verdict |
|---|---|
| Model the API after Spring Session | No — wrong abstraction for the domain |
| Use Spring Session as storage backend | No — single-blob model loses event granularity and query capability |
| Use Spring Data as storage backend | **Yes** — right approach via Anti-Corruption Layer pattern |
| Recommended first implementation | Spring Data JDBC/JPA (`spring-ai-session-jdbc`) |
