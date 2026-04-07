# Why Spring AI Session Is Not Modeled After Spring Session

Spring Session (`org.springframework.session`) is a natural reference point — it lives in the same
portfolio, uses similar naming, and solves a related problem (session management).  
This document explains why directly modeling Spring AI Session after Spring Session's API would be
the wrong call, and where the two can still align naturally.

---

## The Core Domain Mismatch

| Dimension | Spring Session | Spring AI Session |
|---|---|---|
| **What is a Session?** | Mutable `Map<String,Object>` of HTTP attributes | Immutable metadata envelope (id, userId, TTL) |
| **What is content?** | Stored directly on the session via `setAttribute` | Stored as separate, immutable `SessionEvent` objects |
| **Data model** | Mutable key-value blob | Append-only immutable event log |
| **Primary operations** | CRUD on session attributes | Append, filter, version-CAS, compact |
| **Repository factory** | `createSession()` lives on the repository | Session created externally, saved via `save(Session)` |

Spring Session was designed to transparently replace the servlet container's `HttpSession`.
Its `Session` interface is essentially a `Map` with a TTL — the session *is* the content container.

Spring AI Session separates concerns: the `Session` is metadata-only (identity, ownership, expiry)
while all content lives in `SessionEvent` objects stored in an append-only log.
Implementing Spring Session's `Session` interface would force a misleading API and hide the
event-sourced nature of the design.

---

## The "Event" Naming Collision

This is the most dangerous alignment issue.

In the Spring Session ecosystem, *events* means Spring `ApplicationEvent` lifecycle callbacks:

- `SessionCreatedEvent`
- `SessionDeletedEvent`
- `SessionExpiredEvent`

In Spring AI Session, `SessionEvent` is a **domain object** — a conversation message or turn
stored in the event log. It has nothing to do with application event publishing.

Adopting Spring Session's event model would create one of two bad outcomes:

1. `SessionEvent` (the conversation message) would need to be renamed to avoid the collision, or
2. Developers familiar with Spring Session would misread the API and expect infrastructure events
   when they see `SessionEvent`.

The right mental model for Spring AI Session's events is closer to **event sourcing** than to
Spring's `ApplicationEvent` infrastructure.

---

## What Spring AI Session Is Actually Closer To

The design aligns with **event sourcing / CQRS** patterns:

- Append-only log of immutable `SessionEvent` objects
- Optimistic concurrency control for compaction via CAS version counters
- Rich read-side filtering via the composable `EventFilter`
- Branch isolation for multi-agent scenarios

None of these concepts exist in Spring Session, and mapping them there would be a regression
in expressiveness.

---

## Where Natural Alignment Still Makes Sense

Spring AI Session already follows Spring portfolio conventions in the right places — there is no
need for a wholesale redesign. The areas of natural alignment include:

- **Naming conventions**: `findById`, `delete`, `save` in `SessionRepository` match Spring Data
  and Spring Session naming patterns.
- **SPI / implementation separation**: `SessionRepository` (interface) + `InMemorySessionRepository`
  (internal impl) mirrors the pattern used across the Spring portfolio.
- **Builder patterns and immutability**: consistent with modern Spring API design.

The one genuinely useful thing Spring Session-style integration *could add* is publishing Spring
`ApplicationEvent`s for session **lifecycle** milestones (session created, deleted, expired) from
`DefaultSessionService`. This would enable observability, audit trails, and integration hooks
without changing the domain model. That is an additive feature, not an API redesign.

---

## Summary

| Question | Answer |
|---|---|
| Should `Session` implement Spring Session's `Session` interface? | No — the Session is metadata-only, not a Map. |
| Should `SessionRepository` extend Spring Session's `SessionRepository`? | No — the operations are incompatible (append, CAS, compaction). |
| Should `SessionEvent` follow Spring Session's event model? | No — naming collision; different concept entirely. |
| Is any alignment with Spring Session useful? | Yes — publish Spring `ApplicationEvent`s for lifecycle hooks. |

**Bottom line**: Spring AI Session is the right design for its domain.
The existing abstractions should be kept as-is. The only concrete addition worth considering is
optional Spring `ApplicationEvent` publishing for session lifecycle, as a small integration surface
for observability and cross-cutting concerns.

---

*See also: [why-not-spring-data-repository.md](why-not-spring-data-repository.md)*
