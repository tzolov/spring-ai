# Spring AI Memory — Session Module

This module provides a structured, event-sourced session layer for managing conversation
history in Spring AI applications. It goes beyond a simple message list by introducing
**sessions**, **turns**, **compaction strategies**, and **compaction triggers** that keep
context windows manageable without losing conversational coherence.

---

## Table of Contents

- [Core Concepts](#core-concepts)
  - [Session](#session)
  - [SessionEvent](#sessionevent)
  - [Turn](#turn)
  - [Synthetic Summary Turn](#synthetic-summary-turn)
- [Architecture Overview](#architecture-overview)
- [Session Lifecycle](#session-lifecycle)
- [Event Filtering](#event-filtering)
- [Context Compaction](#context-compaction)
  - [Compaction Triggers](#compaction-triggers)
  - [Compaction Strategies](#compaction-strategies)
  - [Turn-boundary Safety](#turn-boundary-safety)
- [ChatClient Integration](#chatclient-integration)
- [Multi-Agent Branch Isolation](#multi-agent-branch-isolation)
  - [Branch format](#branch-format)
  - [Tagging events](#tagging-events)
  - [Filtering by branch](#filtering-by-branch)
  - [Visibility rule](#visibility-rule)
- [Agent Tool: Conversation Search (Recall Storage)](#agent-tool-conversation-search-recall-storage)
- [Package Structure](#package-structure)

---

## Core Concepts

### Session

`Session` is the identity and lifecycle container for a single, continuous conversation
between a user and an agent. It is an **immutable concrete class** — not an interface —
because it is a pure metadata value object with exactly one representation. It holds only
**metadata** — the event log is stored separately in the repository and retrieved on demand
via `SessionService.getEvents()`.

| Field          | Purpose                                                                                                             |
|----------------|---------------------------------------------------------------------------------------------------------------------|
| `id`           | unique session identifier                                                                                                   |
| `userId`       | owning user or agent (required, used for isolation)                                                                 |
| `createdAt`    |                                                                                                                     |
| `expiresAt`    | expiry instant; defaults to 60 days from creation when no TTL is specified; `null` means no expiry. The builder rejects values in the past. |
| `metadata`     | arbitrary key/value pairs (model info, tags, etc.)                                                                  |


Keeping `Session` metadata-only means it can be passed across boundaries cheaply, and
compaction strategies receive the event list as an explicit parameter rather than
extracting it from the session object.

Sessions are created through `SessionService`, which is the primary API for the entire
lifecycle. `CreateSessionRequest` accepts an optional `id` field; when omitted, the
service generates a UUID automatically.

### SessionEvent

`SessionEvent` is an immutable class that acts as a thin wrapper around
the existing Spring AI `Message` types. It adds only what `Message` intentionally lacks:

| Field       | Purpose                                                                                                                                                     |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `id`        | Unique identity per event                                                                                                                                   |
| `sessionId` | Ownership / isolation                                                                                                                                       |
| `timestamp` | Chronological ordering                                                                                                                                      |
| `message`   | The actual Spring AI message (no duplication)                                                                                                               |
| `metadata`  | Framework flags — such as `SessionEvent.METADATA_SYNTHETIC` and `SessionEvent.METADATA_COMPACTION_SOURCE`|
| `branch`    | Dot-separated agent path (e.g. `"orch.researcher"`); `null` for root-level events. Used by `EventFilter.forBranch()` to isolate peer sub-agents in multi-agent sessions |

The `message` field carries the content using the existing Spring AI message types:

| Message type                         | Meaning                                             |
|--------------------------------------|-----------------------------------------------------|
| `UserMessage`                        | Real user input                                     |
| `AssistantMessage` (no tool calls)   | Agent response                                      |
| `AssistantMessage` (with tool calls) | Agent tool invocation                               |
| `ToolResponseMessage`                | Tool output                                         |
| `UserMessage` + `isSynthetic()`      | Synthetic shadow prompt opening a summary turn      |
| `AssistantMessage` + `isSynthetic()` | Synthetic summary text closing a summary turn       |

**Creating events — builder:**

```java
// Root event (no branch) — visible to all agents; timestamped at Instant.now()
SessionEvent event = SessionEvent.builder()
    .sessionId(sessionId)
    .message(new UserMessage("Hello"))
    .build();

// Branched event — attributed to a specific agent in a multi-agent hierarchy
SessionEvent branched = SessionEvent.builder()
    .sessionId(sessionId)
    .message(new AssistantMessage("Research result..."))
    .branch("orch.researcher")
    .build();

// Adding individual metadata entries
SessionEvent custom = SessionEvent.builder()
    .sessionId(sessionId)
    .message(new AssistantMessage("Response"))
    .metadata("model", "gpt-4o")
    .metadata("latencyMs", 230)
    .build();

// For tests or any case where deterministic timestamps are required
Instant t = Instant.parse("2025-06-01T12:00:00Z");
SessionEvent deterministic = SessionEvent.builder()
    .sessionId(sessionId)
    .timestamp(t)
    .message(new UserMessage("Hello"))
    .build();
```

Builder defaults: `id` is a random UUID, `timestamp` is `Instant.now()`, `metadata` is
empty, `branch` is `null`. Only `sessionId` and `message` are required.

**Creating a synthetic summary turn (user + assistant pair):**

A compaction summary is represented as a `[USER, ASSISTANT]` pair where both events
are marked synthetic. Build each event explicitly so the shared timestamp makes them
an atomic unit:

```java
Instant now = Instant.now();
List<SessionEvent> summaryTurn = List.of(
    SessionEvent.builder()
        .sessionId(sessionId)
        .timestamp(now)
        .message(new UserMessage("Summarize the conversation we had so far."))
        .metadata(SessionEvent.METADATA_SYNTHETIC, true)
        .metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "recursive-summarization")
        .build(),
    SessionEvent.builder()
        .sessionId(sessionId)
        .timestamp(now)
        .message(new AssistantMessage("The user asked about X. The assistant..."))
        .metadata(SessionEvent.METADATA_SYNTHETIC, true)
        .metadata(SessionEvent.METADATA_COMPACTION_SOURCE, "recursive-summarization")
        .build()
);
```

Both events share the same `Instant` so the user/assistant turn is treated as a single
atomic unit.

### Turn

A **turn** is the atomic unit of conversation, borrowed from the
[OpenAI Agents SDK](https://developers.openai.com/cookbook/examples/agents_sdk/session_memory):

> One `UserMessage` plus all subsequent events (assistant replies, tool calls, tool
> results) up to the next `UserMessage`.

Using turns instead of raw message counts prevents compaction from splitting a
tool-call/result pair, or from removing an assistant reply while keeping the user question
that prompted it.

Turns are counted via `CompactionRequest.currentTurnCount()`, which counts only
non-synthetic, **root-level** (`branch == null`) `USER` events. Synthetic and sub-agent `USER` messages on named branches are excluded so that a multi-agent session
does not inflate the turn count used by `TurnCountTrigger`.

### Synthetic Summary Turn

When `RecursiveSummarizationCompactionStrategy` compacts a session it replaces the
archived events with two synthetic events that together form a coherent conversation turn:

```
[USER  / synthetic] "Summarize the conversation we had so far."
[ASST  / synthetic] "The user asked about topic X. The assistant explained..."
[USER  / real     ] "Next actual question"
[ASST  / real     ] "Next actual response"
```

This mirrors the OpenAI Agents SDK shadow-prompt pattern and ensures that downstream
models always see a valid user↔assistant alternation rather than an injected system
message.

All compaction strategies treat synthetic events as opaque — they separate them from real
events before any processing, preserve them, and place them first in the compacted result.

> **TODO:** perhaps we will need different strategies where compactions recursively compasts the previous syntentic events as well, or not


---

## Architecture Overview

```
┌─────────────────────────────────────────────┐
│              SessionMemoryAdvisor           │  ← ChatClient integration
└─────────────────────┬───────────────────────┘
                      │ uses
┌─────────────────────▼───────────────────────┐
│                SessionService               │  ← primary API
│  create / find / delete / compact           │
└──────────┬──────────────────────┬───────────┘
           │                      │
┌──────────▼──────────┐  ┌────────▼───────────────────────────────┐
│  SessionRepository  │  │        Compaction Framework            │
│  (persistence SPI)  │  │  Trigger ──► Strategy ──► Result       │
│                     │  │                                        │
│  save               │  │  CompactionRequest(session, events,    │
│  findById           │  │    eventCount, turnCount)              │
│  findByUserId       │  │                                        │
│  findExpiredIds     │  │  Events are fetched from the           │
│  delete             │  │  repository and passed explicitly      │
│  appendEvent        │  │  — no events on Session itself         │
│  replaceEvents      │  └────────────────────────────────────────┘
│  replaceEvents(CAS) │
│  getEventVersion    │
│  findEvents         │
└──────────┬──────────┘
           │
┌──────────▼─────────────────────────────────────┐
│         InMemorySessionRepository              │
│                                                │
│  ConcurrentHashMap<id, SessionData>            │
│  SessionData = (Session, List<Event>, version) │
│  All mutations are atomic via compute()        │
│                                                │
│  Swap for Redis, JDBC, etc. by implementing    │
│  SessionRepository                             │
└────────────────────────────────────────────────┘
```

**Design rationale — why `Session` carries no events:**

Storing the event log inside `Session` would force every consumer that needs to mutate the
list (compaction, archiving) to hold a mutable reference inside what is meant to be an
immutable value object. By keeping `Session` as pure metadata, all event mutations go
through dedicated repository methods (`appendEvent`, `replaceEvents`) and `SessionService`
operates on the event list as an explicit parameter rather than as a field on the session.

**Optimistic concurrency via `getEventVersion` / `replaceEvents(CAS)`:**

`SessionRepository` exposes two methods that together enable lock-free compaction.
`getEventVersion(sessionId)` returns a monotonically increasing counter that is
incremented on every `appendEvent` and `replaceEvents` call. Callers read this version
**before** fetching events, then pass it to `replaceEvents(sessionId, events,
expectedVersion)`. If another writer mutated the log in the interval, the version will
have changed and the CAS variant returns `false` — the caller treats this as a no-op
rather than re-trying. `InMemorySessionRepository` implements this with a `version` field
inside the internal `SessionData` record; durable implementations (JDBC, Redis, etc.)
should map this to a database-level optimistic-lock column or a Redis `WATCH`.

---

## Session Lifecycle

```java
// 1. Create
SessionService service = new DefaultSessionService(new InMemorySessionRepository());

Session session = service.create(
    CreateSessionRequest.builder()
        .id("my-session-id")          // optional; service generates a UUID when omitted
        .userId("alice")
        .timeToLive(Duration.ofHours(2)) // optional; defaults to 60 days when omitted
        .metadata("agentType", "research-assistant")
        .build()
);

// 2. Append events (shorthand wraps message in a SessionEvent automatically)
service.appendMessage(session.id(), new UserMessage("What is Spring AI?"));
service.appendMessage(session.id(), new AssistantMessage("Spring AI is..."));

// 3. Retrieve as Message list (for passing directly to an LLM)
List<Message> history = service.getMessages(session.id());

// 4. Retrieve as SessionEvent list (for filtering, inspection, compaction)
List<SessionEvent> events = service.getEvents(session.id());

// 5. Delete
service.delete(session.id());
```

Deleted sessions are removed from the repository entirely — there is no tombstone state.

---

## Event Filtering

`EventFilter` controls which events are returned by `SessionService.getEvents()`.
Criteria are composable: all non-null conditions must match for an event to be included.

```java
// All events
service.getEvents(id, EventFilter.all());

// Last 20 events
service.getEvents(id, EventFilter.lastN(20));

// Exclude synthetic summary events
service.getEvents(id, EventFilter.realOnly());

// Keyword search — first page (default page size 10)
service.getEvents(id, EventFilter.keywordSearch("Spring AI"));

// Keyword search — explicit page and page size
service.getEvents(id, EventFilter.keywordSearch("Spring AI", 1, 5));

// Branch isolation: events visible to agent "orch.researcher"
service.getEvents(id, EventFilter.forBranch("orch.researcher"));

// Custom: only USER and ASSISTANT messages after a timestamp, excluding synthetics
EventFilter custom = EventFilter.builder()
    .from(Instant.parse("2025-01-01T00:00:00Z"))
    .messageTypes(Set.of(MessageType.USER, MessageType.ASSISTANT))
    .excludeSynthetic(true)
    .build();
```

`EventFilter` fields:

| Field             | Type               | Description                                                                   |
|-------------------|--------------------|-------------------------------------------------------------------------------|
| `from`            | `Instant`          | Exclude events before this instant                                            |
| `to`              | `Instant`          | Exclude events after this instant                                             |
| `messageTypes`    | `Set<MessageType>` | Keep only events of these types                                               |
| `excludeSynthetic`| `boolean`          | When `true`, synthetic summary events are excluded                            |
| `lastN`           | `Integer`          | Keep only the most recent N matching events (must be > 0 if set)             |
| `keyword`         | `String`           | Case-insensitive substring match on `message.getText()`                       |
| `page`            | `Integer`          | Zero-indexed page in chronological order (oldest first); page 0 = oldest matching events; must be ≥ 0 if set |
| `pageSize`        | `Integer`          | Number of results per page (must be > 0 if set; default 10)                    |
| `branch`          | `String`           | Restricts to events visible to this agent branch (own + ancestor events only) |

**Retrieval modifier contract** (enforced in the record's compact constructor):

- `lastN` and `pageSize` are **mutually exclusive** — setting both throws `IllegalArgumentException`.
- Setting `page` without `pageSize` throws `IllegalArgumentException`.
- Setting `pageSize` without `page` is allowed: `page` defaults to `0` (first page).
- `keyword` is normalised on construction: blank or empty strings become `null`; non-null values are lowercased for case-insensitive matching.
- `messageTypes` is normalised on construction: an empty set becomes `null` (equivalent to no type filter).

---

## Context Compaction

Compaction reduces the session's event history to fit within a context window while
preserving conversational coherence. It is driven by two composable abstractions:
**triggers** (when to compact) and **strategies** (how to compact).

`SessionService.compact()` is the single entry point for both concerns. It evaluates the
trigger first and only runs the strategy — and writes back to the repository — when the
trigger fires:

```java
// Compact when turn count exceeds 20, keeping the last 10 events
CompactionResult result = service.compact(
    sessionId,
    new TurnCountTrigger(20),
    new SlidingWindowCompactionStrategy(10)
);
System.out.println(result.eventsRemoved());        // derived: archivedEvents().size()
System.out.println(result.compactedEvents());      // the kept event list
System.out.println(result.archivedEvents());       // the removed event list
System.out.println(result.tokensEstimatedSaved()); // rough token saving estimate

// Compact unconditionally — pass an always-fire trigger
CompactionResult result = service.compact(sessionId, req -> true, strategy);

// Automatic — configure on SessionMemoryAdvisor (see ChatClient Integration)
```

`DefaultSessionService.compact()` looks up the session via `findById`, then reads the
current event-log version (via `SessionRepository.getEventVersion`) **before** fetching
events. It builds a `CompactionRequest`, checks the trigger, and — only if the trigger
fires — delegates to the strategy and writes the compacted list back via the
compare-and-swap variant of `replaceEvents()`. If another writer mutated the event log
between the version read and the CAS write, `replaceEvents()` returns `false` and
compaction is silently skipped — the concurrent writer already handled the session.

No-op results (trigger does not fire, or strategy archives nothing) skip the write
entirely — important for production persistence backends where a redundant write on every
evaluation would be wasteful.

`CompactionResult.eventsRemoved()` is a derived accessor — it returns
`archivedEvents().size()` rather than storing a separate counter, eliminating the
possibility of the two values disagreeing.

### Compaction Triggers

Triggers implement `CompactionTrigger` (a `@FunctionalInterface`) and decide whether
compaction should run based on the current `CompactionRequest` (which carries the event
list, event count, and turn count).

| Trigger                       | Fires when…                                                |
|-------------------------------|------------------------------------------------------------|
| `TurnCountTrigger(n)`         | Session has more than `n` complete turns                   |
| `TokenCountTrigger(n)`        | Total token count ≥ `n` (measured via `TokenCountEstimator`, default `JTokkitTokenCountEstimator`) |
| `CompositeCompactionTrigger`  | Any composed trigger fires (OR semantics)                  |

`TokenCountTrigger` is estimator-injectable, and defaults to `JTokkitTokenCountEstimator` —
the same estimator used by `TokenCountCompactionStrategy` — so the trigger threshold and
the strategy budget are on the same scale:

```java
// Production — uses JTokkitTokenCountEstimator internally
new TokenCountTrigger(4000);

// Custom estimator (e.g. for a different model's tokenizer)
new TokenCountTrigger(4000, myEstimator);
```

```java
// Fire when either 20 turns are exceeded OR 4000 tokens are accumulated
CompactionTrigger trigger = CompositeCompactionTrigger.anyOf(
    new TurnCountTrigger(20),
    new TokenCountTrigger(4000)
);
```

### Compaction Strategies

Strategies implement `CompactionStrategy` (a `@FunctionalInterface`) and define what to
do with the event history. Each strategy receives a `CompactionRequest` that contains
both the session metadata and the event list — no access to the repository is needed
inside the strategy.

#### `SlidingWindowCompactionStrategy`

Keeps the last `N` **real** events. Simple and predictable — no LLM call required.
Synthetic summary events are always preserved and placed first in the output; they do not
count against the `maxEvents` budget.

```java
new SlidingWindowCompactionStrategy(20);                   // keep the last 20 real events
new SlidingWindowCompactionStrategy(20, myEstimator);      // custom token estimator for tokensEstimatedSaved
```

Algorithm:
1. Separate synthetic events (always preserved, placed first in output).
2. Compute raw cut: keep the last `maxEvents` real events (synthetics are independent of this budget).
3. Snap the cut point forward to the nearest `USER` message (turn-boundary safety).
4. Return: `[synthetics] + [kept real events]`.

#### `TurnWindowCompactionStrategy`

Keeps the last `N` complete turns. Unlike the sliding window, this never cuts inside a
turn — it always archives whole user↔agent exchanges.

```java
new TurnWindowCompactionStrategy(10);                      // keep the last 10 turns
new TurnWindowCompactionStrategy(10, myEstimator);         // custom token estimator for tokensEstimatedSaved
```

Algorithm:
1. Strip synthetic events (always preserved, placed first in output).
2. Collect preamble events that appear before the first `USER` message (rare pre-seeded tool state).
3. Group remaining events into turns (each turn starts at a `USER` message).
4. Archive the oldest turns until only `maxTurns` remain.
5. Return: `[synthetics] + [preamble] + [kept turns]`.

#### `TokenCountCompactionStrategy`

Keeps a **contiguous** suffix of events that fits within a token budget, walking from
newest to oldest.

```java
// Stay within 4000 tokens (uses JTokkitTokenCountEstimator by default)
new TokenCountCompactionStrategy(4000);

// Custom estimator
new TokenCountCompactionStrategy(4000, myEstimator);
```

Algorithm:
1. Separate synthetic events (their token cost is deducted from the budget first).
2. Walk real events from newest to oldest, accumulating token cost. **Stop at the first event that would exceed the remaining budget.** This produces a contiguous suffix — skipping individual oversize events and continuing would create non-contiguous gaps that break conversation coherence.
3. Drop any leading kept events that are not `USER` messages (turn-boundary safety).
4. Return: `[synthetics] + [kept events]`.

#### `RecursiveSummarizationCompactionStrategy`

LLM-powered strategy that uses a `ChatClient` to summarize the events being archived.
The summary is stored as a synthetic user+assistant turn so subsequent compaction passes
can build on it (rolling / recursive behaviour).

```java
RecursiveSummarizationCompactionStrategy strategy =
    RecursiveSummarizationCompactionStrategy.builder(chatClient)
        .maxEventsToKeep(10)            // active window size (real events kept intact)
        .overlapSize(2)                 // events from active window fed into summary prompt for continuity
                                        // must be < maxEventsToKeep; defaults to 2
        .systemPrompt("...")            // optional custom system prompt sent to the LLM
        .shadowPrompt("...")            // optional custom USER shadow prompt that opens the summary turn
        .tokenCountEstimator(myEst)    // custom estimator for tokensEstimatedSaved (default: JTokkitTokenCountEstimator)
        .build();
```

Builder validation: `overlapSize` must be `>= 0` and strictly less than `maxEventsToKeep`
(enforced in both `Builder.build()` and the private constructor). Setting
`overlapSize >= maxEventsToKeep` throws `IllegalArgumentException` with a descriptive
message.

**LLM failure handling:** If the LLM returns a null or blank summary, the strategy logs a
`WARN`-level message identifying the session and skips compaction — the event history is
left unchanged. An optional `onSummarizationFailure` callback can be registered to react
programmatically (retry, alert, metrics, etc.):

```java
RecursiveSummarizationCompactionStrategy strategy =
    RecursiveSummarizationCompactionStrategy.builder(chatClient)
        .maxEventsToKeep(10)
        .onSummarizationFailure(req -> {
            log.error("Compaction failed for session {}", req.session().id());
            // retry, alert, increment a metric, etc.
        })
        .build();
```

The warn log is always emitted on failure regardless of whether the callback is registered.

Algorithm:
1. Separate synthetic and real events.
2. Compute the raw cut point: the newest `maxEventsToKeep` real events form the active window.
3. Snap the cut point forward to the nearest turn boundary.
4. Feed `[prior synthetic summaries] + [events to archive] + [overlap events]` to the LLM.
5. Replace the archived events with a new synthetic summary turn `[USER shadow, ASSISTANT summary]`.
6. Return: `[summary turn] + [active window]`.

The **recursive** property: the `ASSISTANT` text from any prior synthetic summary is fed
back to the LLM as `=== PRIOR SUMMARY ===` context, so each summary builds on its
predecessors without starting from scratch — creating a rolling compressed history.

**`archivedEvents` semantics:** `CompactionResult.archivedEvents()` contains only the
real events that were summarized and removed. Prior synthetic summaries are implicitly
replaced by the new summary turn and are not included in `archivedEvents`, keeping the
accounting consistent with the other strategies.

### Turn-boundary Safety

All four compaction strategies share a common safety rule enforced by
`CompactionUtils.snapToTurnStart`: the kept window always starts at a `USER` message.

If a raw cut point would land on an `ASSISTANT` or `TOOL` event in the middle of a turn,
it is advanced forward to the next `USER` message. This prevents keeping a tool result or
assistant reply without the user message that originated its turn.

```
Before snap:  [u1, a1, u2, a2, | a3, u3, a3]   ← cut lands on a3 (middle of turn 2)
After snap:   [u1, a1, u2, a2, a3, | u3, a3]   ← cut moved to u3 (turn start)
```

---

## ChatClient Integration

`SessionMemoryAdvisor` is a `BaseAdvisor` that wires a `SessionService` into a
`ChatClient` pipeline. On every request it:

1. Looks up the session by the `SESSION_ID_CONTEXT_KEY` value in the advisor context
   (falls back to `defaultSessionId`). If the session does not exist it is created
   automatically, using the `USER_ID_CONTEXT_KEY` value (or `defaultUserId`) and passing
   the resolved session ID explicitly so the created session has the expected ID.
2. Retrieves the session's event history (filtered by the configured `eventFilter`,
   default `EventFilter.all()`) and prepends it to the prompt messages. If the request
   context contains an `EVENT_FILTER_CONTEXT_KEY` value, it is merged with the
   advisor-level filter via `EventFilter.merge()` — request-level fields win over
   advisor defaults.
3. Reorders all `SystemMessage` instances to the front of the combined message list,
   preserving their relative order. This ensures that a system message buried in history
   and a second one on the current request both end up at the front.
4. Appends the current user message to the session.
5. After the model responds, appends the assistant message.
6. If a trigger fires, runs compaction **synchronously** before returning the response —
   the full turn (user + assistant) is already written at this point, so there is no race
   between compaction and message appending.

**Scheduler pinning:** Both the blocking (`advise()`) and streaming (`adviseStream()`)
paths run `before()` and `after()` on the configured `Scheduler` (default:
`BaseAdvisor.DEFAULT_SCHEDULER`). In `adviseStream()`, a second `.publishOn(scheduler)`
is applied after `.flatMapMany(chain::nextStream)` so that the aggregation callback and
compaction always run on the scheduler rather than the LLM streaming thread.

**Concurrent compaction safety:** If two requests for the same session complete
concurrently (e.g. parallel fan-out), both `after()` calls may reach the compaction step
simultaneously. Compaction uses an optimistic compare-and-swap write via
`SessionRepository.replaceEvents(sessionId, events, expectedVersion)`. The event-log
version is read before events are fetched; if another writer mutates the log between that
read and the CAS write, `replaceEvents` returns `false` and the second writer skips
silently — no compacted result is lost or corrupted.

```java
SessionMemoryAdvisor advisor = SessionMemoryAdvisor.builder(sessionService)
    .defaultSessionId("session-123")
    .defaultUserId("alice")
    .compactionTrigger(new TurnCountTrigger(20))    // must set both trigger and strategy,
    .compactionStrategy(                             // or neither — setting only one throws
        RecursiveSummarizationCompactionStrategy.builder(chatClient)
            .maxEventsToKeep(10)
            .build()
    )
    .build();

ChatClient client = ChatClient.builder(chatModel)
    .defaultAdvisors(advisor)
    .build();

// Pass a per-request session ID via advisor context
String response = client.prompt()
    .user("Hello!")
    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "session-abc"))
    .call()
    .content();
```

Context keys:

| Key                           | Constant                                           | Purpose                                                       |
|-------------------------------|----------------------------------------------------|---------------------------------------------------------------|
| `chat_memory_session_id`      | `SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY`      | Routes the request to a session                               |
| `chat_memory_user_id`         | `SessionMemoryAdvisor.USER_ID_CONTEXT_KEY`         | Used when auto-creating a session                             |
| `chat_memory_event_filter_id` | `SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY`    | Per-request `EventFilter` merged with the advisor-level filter |

**Per-request filter override:**

Pass an `EventFilter` via `EVENT_FILTER_CONTEXT_KEY` to narrow or adjust history
retrieval on a single call without reconfiguring the advisor. The advisor merges it with
its own filter using `EventFilter.merge()` — fields set on the request filter win; fields
left null fall back to the advisor default:

```java
// Advisor is configured with EventFilter.all() (default).
// This request overrides to see only the last 5 events.
String response = client.prompt()
    .user("Quick summary please")
    .advisors(a -> a
        .param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId)
        .param(SessionMemoryAdvisor.EVENT_FILTER_CONTEXT_KEY, EventFilter.lastN(5))
    )
    .call()
    .content();
```

`EventFilter.merge()` semantics: every non-null field from `other` replaces the
corresponding field from `this`; `excludeSynthetic` is OR-ed so either side can opt in.
A `null` value for `EVENT_FILTER_CONTEXT_KEY` is ignored and the advisor default is used.

---

## Multi-Agent Branch Isolation

When an orchestrator delegates work to several sub-agents running in parallel, all agents
share the same `Session` — but each sub-agent must see only its own history plus its
ancestors'. Peer agents on sibling branches must be invisible to each other.

This design mirrors the [Google ADK Java `Event.branch`](https://github.com/google/adk-java/blob/main/core/src/main/java/com/google/adk/events/Event.java)
field and the isolation semantics it defines.

### Branch format

`SessionEvent.branch` is a dot-separated path that records the agent hierarchy that
produced the event:

```
orchestrator                        branch = "orch"
├── researcher                      branch = "orch.researcher"
│   └── summarizer                  branch = "orch.researcher.summarizer"
└── writer                          branch = "orch.writer"
```

Events produced before any delegation (e.g. the initial user message) have
`branch = null` and are visible to every agent in the session.

### Tagging events

```java
// Root event (no branch) — visible to all agents
service.appendEvent(SessionEvent.builder()
    .sessionId(sessionId)
    .message(new UserMessage("Summarise the news today"))
    .build());

// Orchestrator tags its own planning events
service.appendEvent(SessionEvent.builder()
    .sessionId(sessionId)
    .message(new AssistantMessage("Delegating to researcher and writer"))
    .branch("orch")
    .build());

// Each sub-agent tags its events with its own branch
service.appendEvent(SessionEvent.builder()
    .sessionId(sessionId)
    .message(new AssistantMessage("Research findings..."))
    .branch("orch.researcher")
    .build());

service.appendEvent(SessionEvent.builder()
    .sessionId(sessionId)
    .message(new AssistantMessage("Draft article..."))
    .branch("orch.writer")
    .build());
```

### Filtering by branch

Pass `EventFilter.forBranch(agentBranch)` when loading history for a sub-agent:

```java
// Researcher sees: null-branch events + "orch" events + own "orch.researcher" events
// Hidden from researcher: "orch.writer" (sibling), "orch.researcher.summarizer" (child)
List<SessionEvent> researcherHistory = service.getEvents(sessionId,
    EventFilter.forBranch("orch.researcher"));
```

To apply branch isolation automatically inside `SessionMemoryAdvisor`, configure the
`eventFilter` on the builder:

```java
SessionMemoryAdvisor researcherAdvisor = SessionMemoryAdvisor.builder(sessionService)
    .defaultSessionId(sharedSessionId)
    .eventFilter(EventFilter.forBranch("orch.researcher"))
    .build();
```

This ensures the advisor only injects events visible to `orch.researcher` into the
prompt — root events and its own events — while sibling events from `orch.writer` remain
hidden.

### Visibility rule

`EventFilter.matches()` applies the following rule per event:

| Event branch                   | Visible to `orch.researcher`? | Reason                      |
|--------------------------------|-------------------------------|-----------------------------|
| `null`                         | **yes**                       | Root event — visible to all |
| `"orch"`                       | **yes**                       | Direct ancestor             |
| `"orch.researcher"`            | **yes**                       | Own branch                  |
| `"orch.writer"`                | no                            | Sibling branch              |
| `"orch.researcher.summarizer"` | no                            | Child branch                |

The dot-separator check (`filterBranch.startsWith(eventBranch + ".")`) ensures that a
branch named `"orch"` is never confused with one named `"orchestra"`.

### Synthetic events and branch

Synthetic summary events produced by `RecursiveSummarizationCompactionStrategy` always
have `branch = null`. This ensures compaction summaries remain visible to every agent in
the session after context has been pruned, regardless of which branch was active when
compaction ran.

---

## Agent Tool: Conversation Search (Recall Storage)

`SessionEventTools` implements the MemGPT *Recall Storage* pattern: the full verbatim
event log is always retained and searchable by keyword, even after context compaction has
pruned older events from the active context window. The agent can surface any prior
exchange on demand rather than relying solely on what fits in the prompt.

### Registration

```java
// Default page size (EventFilter.DEFAULT_PAGE_SIZE = 10)
SessionEventTools tools = new SessionEventTools(sessionService);

// Custom page size
SessionEventTools tools = new SessionEventTools(sessionService, 20);

ChatClient client = ChatClient.builder(chatModel)
    .defaultTools(tools)
    .defaultAdvisors(advisor)  // SessionMemoryAdvisor sets chat_memory_session_id
    .build();
```

### Tool signature

The `conversation_search` tool is automatically discovered by Spring AI's tool mechanism.
Its parameters:

| Parameter      | Required | Description                                            |
|----------------|----------|--------------------------------------------------------|
| `innerThought` | yes      | Agent's private reasoning (not returned to the caller) |
| `query`        | yes      | Case-insensitive keyword to search for                 |
| `page`         | no       | Zero-indexed result page; defaults to `0`; negative values are clamped to `0` |

Results are returned in chronological order as a JSON array. Each entry contains:

```json
[
  { "timestamp": "2025-06-01T12:00:00Z", "type": "user",      "text": "Tell me about Spring AI" },
  { "timestamp": "2025-06-01T12:00:01Z", "type": "assistant", "text": "Spring AI is a framework..." }
]
```

Page size defaults to `EventFilter.DEFAULT_PAGE_SIZE` (10) and is configurable at
construction time via `new SessionEventTools(sessionService, pageSize)`. Pass `page=1`,
`page=2`, … to walk through large histories.

### How it works

1. Resolves the session ID from `ToolContext` using the `chat_memory_session_id` key
   (the same key written by `SessionMemoryAdvisor`). If the key is absent or blank, a
   `WARN`-level log is emitted and the tool falls back to the literal session ID
   `"default"`. Register `SessionMemoryAdvisor` alongside this tool to ensure the correct
   session ID is propagated automatically.
2. Calls `SessionService.getEvents(sessionId, EventFilter.keywordSearch(query, page, this.pageSize))`.
3. `EventFilter.matches()` applies a case-insensitive substring check on each event's
   `message.getText()` before pagination is applied.
4. Returns structured JSON, or `"No results found."` when nothing matches.

Synthetic summary events (produced by `RecursiveSummarizationCompactionStrategy`) are
included in the search — their summary text is part of the recall history too.

---

## Package Structure

```
org.springframework.ai.session
├── Session.java                       – immutable metadata-only value object (no events)
├── SessionEvent.java                  – immutable final class wrapping a Spring AI Message
├── SessionService.java                – primary lifecycle + compaction API
├── SessionRepository.java             – persistence SPI:
│                                          save, findById, findByUserId,
│                                          findExpiredSessionIds, delete,
│                                          appendEvent, replaceEvents,
│                                          replaceEvents(CAS), getEventVersion, findEvents
├── CreateSessionRequest.java          – builder for session creation parameters
├── EventFilter.java                   – composable criteria for event retrieval
│
├── advisor/
│   └── SessionMemoryAdvisor.java      – ChatClient advisor with auto-compaction,
│                                          optional eventFilter for branch isolation,
│                                          and per-request EventFilter override via
│                                          EVENT_FILTER_CONTEXT_KEY
│
├── compaction/
│   ├── CompactionRequest.java         – (session, events, eventCount, turnCount)
│   │                                      all metrics pre-computed at construction time; O(1) access
│   │                                      turnCount counts only root-level (branch==null)
│   │                                      non-synthetic USER messages
│   ├── CompactionResult.java          – compacted events + archived events + metrics
│   │                                      eventsRemoved() is derived (archivedEvents.size())
│   ├── CompactionStrategy.java        – strategy SPI: compact(CompactionRequest)
│   ├── CompactionTrigger.java         – trigger SPI: shouldCompact(CompactionRequest)
│   ├── CompactionUtils.java           – turn-boundary snap utility (package-private)
│   ├── CompositeCompactionTrigger.java – OR-composite of triggers
│   ├── TurnCountTrigger.java          – fire when root turns > N
│   ├── TokenCountTrigger.java         – fire when estimated tokens ≥ N
│   ├── SlidingWindowCompactionStrategy.java        – keep last N real events
│   ├── TurnWindowCompactionStrategy.java           – keep last N complete turns
│   ├── TokenCountCompactionStrategy.java           – keep contiguous suffix within token budget
│   └── RecursiveSummarizationCompactionStrategy.java – LLM-powered rolling summary
│
├── internal/
│   ├── DefaultSessionService.java     – default SessionService implementation
│   └── InMemorySessionRepository.java – ConcurrentHashMap-backed repository;
│                                          SessionData record keeps Session metadata,
│                                          event list, and version counter atomically;
│                                          implements CAS replaceEvents via version check
│
└── tool/
    └── SessionEventTools.java         – @Tool conversation_search (Recall Storage)
```
