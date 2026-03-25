# Why `SessionRepository` Is Not a Spring Data Repository

## Short Answer

`SessionRepository` cannot directly extend Spring Data's `CrudRepository` or `JpaRepository`, and `Session`/`SessionEvent` should not be annotated as JPA `@Entity` classes. However, Spring Data **can and should** live inside a `SessionRepository` implementation as a persistence adapter.

---

## Reasons

### 1. The API Contract Is Fundamentally Different

Spring Data's `CrudRepository`/`JpaRepository` provides generic `save()`, `findById()`, `findAll()`, `delete()`. `SessionRepository` has operations that encode domain invariants with no Spring Data equivalent:

| `SessionRepository` method | Why Spring Data can't express it |
|---|---|
| `appendEvent(SessionEvent)` | Append-only semantics — not an upsert |
| `replaceEvents(sessionId, events, expectedVersion)` | Compare-and-swap (CAS) for optimistic concurrency — no Spring Data equivalent |
| `getEventVersion(sessionId)` | Version counter for CAS safety — not a standard query |
| `findEvents(sessionId, EventFilter)` | Custom filter DSL with `lastN`, `branch` visibility, `excludeSynthetic` — incompatible with query methods or `Specification` |

These aren't convenience wrappers around CRUD — they encode correctness guarantees. `appendEvent` must throw when the session doesn't exist (preventing orphaned events). `replaceEvents` with a version is how concurrent compaction avoids data loss: the second writer detects the version mismatch and silently no-ops rather than overwriting a more recent compaction. Routing these through Spring Data would either require working around it entirely or losing those guarantees.

### 2. `Session` and `SessionEvent` Are Intentionally Immutable

JPA entities require:
- A no-arg constructor (public or protected)
- Mutable state for Hibernate's dirty checking
- An `@Id` field and lifecycle callbacks

`Session` is a final class with a private constructor and a builder that returns new instances on every mutation. `SessionEvent` wraps it with the same immutability. Annotating them with `@Entity` breaks the design contract that makes these objects safe to share across threads without defensive copying.

### 3. `SessionEvent.message` Is a Polymorphic Spring AI Type

The `message` field holds a `Message` interface with four concrete subtypes:

```
Message
├── UserMessage       (user input, or synthetic shadow prompt)
├── AssistantMessage  (model response, or synthetic compaction summary)
├── ToolResponseMessage (tool call result)
└── SystemMessage     (system prompt)
```

Each subtype has a different shape. Mapping this to a relational schema requires either:
- A JSON blob column with a custom `@Converter`, or
- A discriminator-based inheritance hierarchy with separate tables

Neither belongs on a domain object. This complexity is an adapter concern.

### 4. `Map<String, Object> metadata` Cannot Map to a Relational Column Directly

Both `Session` and `SessionEvent` carry an arbitrary `Map<String, Object> metadata`. There is no standard JPA mapping for this type — it requires a JSON `@Converter` or an `@ElementCollection` with a secondary table. Again, this is a persistence adapter concern, not a domain object concern.

---

## The Right Pattern: Adapter / Anti-Corruption Layer

Implement `SessionRepository` using Spring Data **internally**, behind a translation layer:

```
SessionRepository          ← domain interface (your public contract)
  └── JdbcSessionRepository implements SessionRepository
          ├── SessionJpaRepository  extends JpaRepository<SessionEntity, String>
          └── SessionEventJpaRepository extends JpaRepository<SessionEventEntity, String>
```

- `SessionEntity` and `SessionEventEntity` are persistence-layer classes — mutable, annotated, column-mapped. They never appear in the public API.
- They carry `toSession()` and `toEvent()` converters back to domain objects.
- `Message` is serialized as a JSON blob via `@Convert(converter = MessageJsonConverter.class)`.
- The CAS `replaceEvents(sessionId, events, expectedVersion)` is implemented with a native query:

```sql
UPDATE session_event_log
SET events = :events, version = version + 1
WHERE session_id = :sessionId AND version = :expectedVersion
```

Rows updated = 0 → version mismatch → silent no-op (same semantics as `InMemorySessionRepository`).

---

## Summary

| Question | Answer |
|---|---|
| Make `SessionRepository` extend a Spring Data repository? | No — the API semantics are incompatible |
| Annotate `Session` / `SessionEvent` with `@Entity`? | No — breaks immutability; `Message` cannot map cleanly |
| Use Spring Data inside a `SessionRepository` implementation? | Yes — adapter pattern, correct approach |
| Create separate `SessionEntity` / `SessionEventEntity`? | Yes — persistence-layer only, never exposed through the public API |

The current design is correct precisely because it keeps domain and persistence concerns separate. `SessionRepository` is the boundary. `InMemorySessionRepository` is the dev/test implementation. A production implementation backed by JDBC/JPA follows the same interface without changing the domain model.
