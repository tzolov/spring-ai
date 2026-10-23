# MCP 2026-07-28 vs. the Spring AI server annotations: gap analysis

Status date: 2026-10-23.
Scope: the server-side annotations `@McpTool`, `@McpToolParam`, `@McpResource`, `@McpPrompt`, `@McpArg`, `@McpComplete`, `@McpMeta`, `@McpProgressToken`, the `MetaProvider` hook and the modern request context `McpModernSyncRequestContext`.
Client-side handler annotations are out of scope.
Stance: additive, one annotation set; every proposal keeps the current annotations working on SSE, STREAMABLE, STATELESS and MODERN.
Sources: the SDK's `io.modelcontextprotocol.modern.McpSchema` (`2.2.0-SNAPSHOT`), a field-level diff against the legacy schema, and the published 2026-07-28 specification (changelog, MRTR, discover, subscriptions, caching, logging, elicitation, cancellation, tools, resources, prompts pages).

## 1. Summary

| # | Spec feature (SEP) | Expressible today | Proposal | Kind |
|---|---|---|---|---|
| 1 | MRTR: elicitation form and URL, sampling, roots (SEP-2322) | yes, imperatively: return `InputRequiredResult`, read `ctx.inputResponse(key, type)` on retry | parameter annotations `@McpElicit`, `@McpSample`, `@McpRoots`, `@McpInputResponse`, `@McpRequestState`; callback generates the `InputRequiredResult` | new annotations + handling |
| 2 | `-32021 MissingRequiredClientCapability` | ✅ done 2026-10-23 (was: no); a handler that asks for elicitation from a client without the capability gets an opaque failure | callback checks `clientCapabilities` before invoking an MRTR method and before returning an `InputRequiredResult`; raises `-32021` with `requiredCapabilities` | handling |
| 3 | Cache hints on `resources/read` (SEP-2549) | yes: `@McpResource(ttlMs, cacheScope)` | ✅ verified 2026-10-23: the SDK's `McpServer.resultResponse` already forces `ttlMs = 0`/`PRIVATE` on retry; covered by a Spring AI test, no callback code | handling (SDK-side) |
| 4 | Logging per request: `_meta.logLevel` + `notifications/message` on the request stream (deprecated, SEP-2577) | ✅ done 2026-10-23 (was: no log API on the modern context) | `McpModernSyncRequestContext.log(level, message)` family honouring `_meta.logLevel`; `ctx.logLevel()`; nothing on annotations. Open: the SDK hard-codes the `logging` capability to null, see SDK proposal #11 | context API |
| 5 | Progress | yes: `McpModernSyncRequestContext.progress(...)`, `@McpProgressToken` | none | — |
| 6 | Icons on `Tool`, `Resource`, `ResourceTemplate`, `Prompt` | ✅ done 2026-10-23 (`@McpIcon`) | `@McpIcon` nested annotation, `icons = {...}` attribute on the three annotations | new attribute |
| 7 | `PromptArgument.title` | ✅ done 2026-10-23 | `@McpArg(title = ...)` | new attribute |
| 8 | `Resource.size` | ✅ done 2026-10-23 | `@McpResource(size = ...)`, default unset | new attribute, low value |
| 9 | `x-mcp-header`: mirror a tool parameter into an `Mcp-Param-{name}` HTTP header (SEP-2243) | ✅ schema side done 2026-10-23 (`@McpToolParam(header = true)`); transport validation still an SDK item | `@McpToolParam(header = true)` emits the schema keyword; transport validation is an SDK item | new attribute + SDK |
| 10 | Loosened `inputSchema` / `outputSchema` (any JSON Schema 2020-12, SEP-2106) | partly: generated from Java types; `generateOutputSchema` | optional `inputSchema` / `outputSchema` string attributes to hand-write a schema | new attribute, optional |
| 11 | Deterministic `tools/list` order | yes: providers sort by method name, repositories by tool name | none | — |
| 12 | `subscriptions/listen`, `resources.subscribe` redefined as the `resourceSubscriptions` filter | server-wide capability, repository API `resourceUpdated(uri)` | drop the planned per-resource `subscribe` flag on `@McpResource`: the spec has no per-resource opt-in | remove planned item |
| 13 | Cancellation (`notifications/cancelled`, stream close) | implicit: stream close cancels the Reactor chain | optional `ctx.isCancelled()` / cooperative check for long sync handlers | context API, optional |
| 14 | Sampling with tools (`sampling.tools`, `tools`, `toolChoice`) | no, and the SDK's modern `CreateMessageRequest` has no `tools` field | SDK first; `@McpSample(tools = ...)` later | SDK gap |
| 15 | `extensions` capability maps, `Implementation.description/websiteUrl/icons`, `server/discover.instructions` | server-level only | properties and `McpModernServerCustomizer`, not annotations | adjacent |
| 16 | Tasks extension (`io.modelcontextprotocol/tasks`, SEP-2663) | no, not in the SDK | out of scope until the SDK models it | future |
| 17 | `McpSyncRequestContext`, `McpSyncServerExchange` parameters on MODERN | rejected at construction, skipped by providers | keep; no annotation removal needed | — |

No server-side annotation needs removal.
The removals the revision makes (`initialize`, `ping`, `logging/setLevel`, `resources/subscribe`, `notifications/elicitation/complete`, `elicitationId`) touch the context types and the client, not the annotations.
One planned addition should be dropped (#12).

## 2. Findings in detail

### 2.1 Multi round-trip requests

Spec.
`tools/call`, `prompts/get` and `resources/read` may answer `resultType: "input_required"` with `inputRequests` (a map of key to `ElicitRequest`, `CreateMessageRequest` or `ListRootsRequest`) and an opaque `requestState`.
The client retries the same call with a new id, `inputResponses` keyed the same way, and the untouched `requestState`.
Servers MUST NOT request a capability the client did not declare in `_meta.clientCapabilities`.
Roots and sampling are deprecated as of this revision; elicitation is not.

Today.
A method declares `McpModernSyncRequestContext`, returns `CallToolOutcome` (or `ReadResourceOutcome`, `GetPromptOutcome`), builds `InputRequiredResult` by hand and reads answers with `ctx.inputResponse(key, Class)`.
It works, but every handler repeats the same shape: check for the answer, build the form schema, seal state, branch.

Proposal: declarative inputs as parameter annotations.
A parameter annotated with one of the following is bound from `inputResponses` when present.
When absent, the callback returns an `InputRequiredResult` carrying the request for every unanswered annotated parameter, and seals the tool arguments into `requestState` so the method sees the same arguments on retry.

```java
@McpTool(name = "delete_task", description = "Deletes a task after confirmation")
public String deleteTask(@McpToolParam String id,
        @McpElicit(key = "confirm", message = "Delete task {id}?") ElicitAnswer<Confirmation> confirm) {
    if (!confirm.accepted() || !confirm.content().confirmed()) {
        return "Kept " + id;
    }
    return tasks.delete(id) ? "Deleted " + id : "No such task";
}

@McpTool(name = "summarize_tasks", description = "Summarises the tasks with the client's model")
public String summarize(@McpSample(key = "summary", prompt = "Summarise: {tasks}", maxTokens = 300) SampledMessage summary) {
    return summary.text();
}

@McpTool(name = "connect_account", description = "Links an external account")
public String connect(@McpElicit(key = "oauth", mode = ElicitMode.URL, url = "https://example.com/link",
        message = "Authorise access") ElicitAnswer<Void> consent, @McpRequestState LinkState state) {
    // URL mode: accept means consent only; the outcome is learned here, on the retry
    return state.linked() ? "Linked" : "Pending";
}
```

Annotation sketches.

```java
@Target(PARAMETER) @Retention(RUNTIME)
public @interface McpElicit {
    String key();                        // inputRequests / inputResponses key
    String message();                    // may reference tool arguments as {name}
    ElicitMode mode() default ElicitMode.FORM;
    String url() default "";             // URL mode only
}

@Target(PARAMETER) @Retention(RUNTIME)
public @interface McpSample {             // deprecated in the spec; ship behind elicitation
    String key();
    String prompt();
    int maxTokens() default 1000;
    String systemPrompt() default "";
    String[] modelHints() default {};
}

@Target(PARAMETER) @Retention(RUNTIME)
public @interface McpRoots { String key() default "roots"; }

@Target(PARAMETER) @Retention(RUNTIME)
public @interface McpInputResponse { String key(); }     // raw access to one answer, any type

@Target(PARAMETER) @Retention(RUNTIME)
public @interface McpRequestState { }                     // bound from the unsealed requestState
```

Supporting types.
`ElicitAnswer<T>(Action action, @Nullable T content)` with `accepted()`, where the form schema is generated from `T` by `McpJsonSchemaGenerator` (flat object of primitives, enums and arrays, as the spec restricts).
`SampledMessage(role, content, model, stopReason)` with `text()`.
`StructuredElicitResult<T>` from the legacy context could be reused instead of `ElicitAnswer`, at the cost of a dependency on the legacy `ElicitResult.Action` enum.

Handling changes.
The modern callbacks collect the annotated parameters, consult `ctx.inputResponses()`, and short-circuit before invoking the method.
The request-state payload is `{arguments, userState}`: the arguments so the retry sees a consistent view, and the method's own state when a `@McpRequestState` parameter is declared and the method returned an `InputRequiredResult` with state.
Schema generation excludes the annotated parameters from the tool input schema, exactly as it excludes the context types today.
MRTR is allowed only on `tools/call`, `prompts/get` and `resources/read`; `@McpComplete` methods reject these annotations at startup.

### 2.2 Required client capability

Spec.
A server MUST NOT send `inputRequests` for capabilities the client did not declare; the matching error is `-32021` with `data.requiredCapabilities`, HTTP 400.

Today.
`McpRequestContext.clientCapabilities()` exposes `supportsElicitationForm()`, `supportsElicitationUrl()`, `supportsSampling()`, `supportsRoots()`, but nothing in Spring AI checks them.
A handler that returns an elicitation to a client without the capability produces an SDK-level failure that reaches the client as an internal error.

Proposal.
With the declarative annotations the callback knows the required capabilities before invoking the method; it raises `McpException(-32021, MissingRequiredClientCapabilityData)` first.
For imperative handlers, `ModernMethodSupport` inspects a returned `InputRequiredResult` and converts an undeclared capability into the same error.
No annotation attribute is needed: the spec has no per-tool capability declaration, so advertising requirements in `tools/list` would be non-standard.

### 2.3 Cache hints and MRTR retries

Spec.
`ttlMs` and `cacheScope` are required on complete `resources/read` results; results of requests that carried `inputResponses` or `requestState` MUST NOT be cached.

Today.
`@McpResource(ttlMs, cacheScope)` is stamped on every converted legacy result, including retries.

Proposal.
The SDK already applies this in `McpServer.resultResponse` for every `CacheableResult` when `ctx.isRetry()` is true, so the callback needs no code.
Spring AI keeps a test that reads a gated resource twice and asserts the retry answer carries `ttlMs = 0`.
Document the rule on the attribute.

### 2.4 Logging

Spec.
`logging/setLevel` is gone.
A client that wants log messages for one request sets `_meta["io.modelcontextprotocol/logLevel"]`; the server MAY then send `notifications/message` on that request's response stream before the final response, and MUST NOT send any when the key is absent.
The feature is deprecated with a twelve-month horizon, but it is live in this revision.

Today.
`McpModernSyncRequestContext` has `notify(method, params)` and `requestMeta()`, so a handler can do it by hand, but there is no `log` API and `MODERN.md` and the migration guide said logging was removed.
Both documents are corrected alongside this analysis.

Proposal.
Add to the context: `Optional<LoggingLevel> logLevel()`, `boolean logEnabled(LoggingLevel)`, `void log(LoggingLevel level, String logger, Object data)` and the `debug/info/warn/error` shorthands, all no-ops when the client did not ask for logs or asked for a higher threshold.
Declaring the context already makes the response a stream, which is what `notifications/message` needs.
No annotation change.

Caveat found after implementation.
The logging page also says a server that emits `notifications/message` MUST declare the `logging` capability and SHOULD reject an unrecognised level with `-32602`.
The SDK's `ServerCapabilities.Builder.build()` hard-codes `logging` to null on purpose ("not offered by modern servers") and does not validate the level, so a Spring AI server emits logs it never advertises.
Both points are raised as SDK proposal #11 in [SDK-TRANSPORT-PROPOSALS.md](SDK-TRANSPORT-PROPOSALS.md); until answered, a spec-following client will not set the key and the API is a no-op.

### 2.5 Icons, titles and sizes on definitions

Spec.
`Tool`, `Resource`, `ResourceTemplate` and `Prompt` carry `icons` (`src`, `mimeType`, `sizes`, `theme`); `PromptArgument` carries `title`; `Resource` carries `size`.
These exist in the legacy schema too, so the gap predates the revision, but the modern server exposes them on `server/discover`-driven clients that render catalogues.

Proposal.

```java
@Retention(RUNTIME) @Target(ANNOTATION_TYPE)
public @interface McpIcon {
    String src();
    String mimeType() default "";
    String[] sizes() default {};
    String theme() default "";       // "light" | "dark"
}

// on @McpTool, @McpResource, @McpPrompt
McpIcon[] icons() default {};

// on @McpArg
String title() default "";

// on @McpResource (optional)
long size() default -1;              // -1 = unset
```

The providers already build definitions through the SDK builders, which accept `icons(List<Icon>)`, `title` and `size`; this is wiring only.

### 2.6 Header-mirrored tool parameters

Spec (SEP-2243).
A property in `inputSchema` may carry `"x-mcp-header": "Mcp-Param-<name>"`, allowed only on string, integer and boolean parameters; Streamable HTTP clients then also send the value as that header, and the transport can validate consistency or route on it.

Proposal.
`@McpToolParam(header = true)` makes `McpJsonSchemaGenerator` emit the keyword with the conventional header name, and rejects non-primitive parameters at startup.
Validating the header against the body belongs in the transports and is recorded as an SDK proposal.

### 2.7 Hand-written schemas

Spec (SEP-2106).
`inputSchema` and `outputSchema` may use any JSON Schema 2020-12 keyword; `structuredContent` may be any JSON value.

Today.
Schemas are generated from Java types; `generateOutputSchema` toggles the output schema.

Proposal, optional.
`@McpTool(inputSchema = "...json...", outputSchema = "...json...")` as escape hatches that override generation, for tools whose schema uses keywords the generator does not produce (`oneOf`, `pattern`, `$ref`).
Low priority: the generator already covers the common cases.

### 2.8 Subscriptions

Spec.
`resources/subscribe` and `resources/unsubscribe` are gone.
A client subscribes through `subscriptions/listen` with `notifications.resourceSubscriptions: [uri...]`; `ServerCapabilities.resources.subscribe` now means that filter is supported, server-wide.

Consequence.
The Phase 2 item "resource subscribe flag on `@McpResource`" has no counterpart in the protocol: there is no per-resource opt-in to advertise.
Emitting updates stays an API concern (`InMemorySyncResourceRepository.resourceUpdated(uri)`, or `broadcastResourceUpdated` in a custom repository).
The item is removed from the backlog.

### 2.9 Cancellation

Spec.
Over Streamable HTTP, closing the response stream cancels the request; `notifications/cancelled` is for stdio and for servers tearing down a `subscriptions/listen` stream.

Today.
Cancellation reaches a handler only as a Reactor cancel; on the WebMvc container thread a blocking handler runs to completion.

Proposal, optional.
`ctx.isCancelled()` for long-running sync handlers that want to stop early, backed by the transport's disconnect signal.
No annotation change.

### 2.10 Out of the annotations' reach

Server-level features belong in properties or `McpModernServerCustomizer`: `extensions` capability maps, `Implementation.description`, `websiteUrl` and `icons`, `server/discover.instructions` (already `spring.ai.mcp.server.instructions`), list cache hints and page size.
Sampling with tools needs `tools` and `toolChoice` on the SDK's modern `CreateMessageRequest` first.
The Tasks extension is not modelled by the SDK.

## 3. Handling changes that need no new annotation

| Change | Where | Why |
|---|---|---|
| Retry results carry `ttlMs = 0` | SDK `McpServer.resultResponse`, tested in Spring AI | spec: MRTR retry results MUST NOT be cached |
| `-32021` when an `InputRequiredResult` needs an undeclared client capability | `ModernMethodSupport` | spec: MUST NOT request undeclared capabilities |
| `log*` on the modern context, gated by `_meta.logLevel` | `DefaultMcpModernSyncRequestContext` | spec: MUST NOT send `notifications/message` unless asked |
| `@McpComplete` rejects MRTR parameter annotations | completion callback | spec: MRTR only on call, read, get |

## 4. Suggested order

1. Compliance fixes with no API change: §3 rows one and two.
2. Context API: logging (§2.4), then `isCancelled()` (§2.9).
3. Definition attributes: `icons`, `@McpArg.title`, `size` (§2.5), `@McpToolParam.header` (§2.6).
4. Declarative MRTR (§2.1), elicitation first; sampling and roots helpers only if there is demand, since both are deprecated.
5. Schema escape hatches (§2.7) on request.

## 5. Corrections to earlier documents

`MODERN.md` §1 and `MODERN-MIGRATION-PATHS.md` stated that logging was removed from the protocol.
It is deprecated and per request: the client sets `_meta.logLevel`, the server answers with `notifications/message` on the same stream.
Both documents now say so, and the migration guide no longer tells readers to delete `ctx.info(...)` calls unconditionally; the modern context will offer an equivalent once §2.4 lands.
