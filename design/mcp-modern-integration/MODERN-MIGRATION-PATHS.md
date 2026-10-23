# Migrating a Spring AI MCP server to the 2026-07-28 ("modern") protocol

Audience: application developers running a Spring AI MCP **server** today (Spring Boot, `spring-ai-starter-mcp-server-webmvc` or `-webflux`) who want to switch to `spring.ai.mcp.server.protocol=MODERN`. Scope: server side, `type=SYNC` only (the modern integration does not support `ASYNC` yet). Design background is in [MODERN.md](MODERN.md); status and backlog in [MODERN-INTEGRATION-PLAN.md](MODERN-INTEGRATION-PLAN.md).

Three starting points are covered, selected today by `spring.ai.mcp.server.protocol`:

| Start | Property value | Transport | Session |
|---|---|---|---|
| **SSE** | `SSE` | HTTP+SSE, 2024-11-05 revision (`/sse` + `/mcp/message`) | stateful |
| **STREAMABLE** | `STREAMABLE` (default) | Streamable HTTP, 2025-03-26 and later (`/mcp`) | stateful |
| **STATELESS** | `STATELESS` | Streamable HTTP without sessions (`/mcp`) | none |

---

## 1. One-page summary

| Path | Overall effort | What drives it |
|---|---|---|
| **STATELESS → MODERN** | **Low** | Same single endpoint, no session, no server-initiated requests to replace. Mostly property renames and one SDK type swap for programmatic specifications. |
| **STREAMABLE → MODERN** | **Low to Medium** | Low if methods only use annotations and `ToolCallback`s. Medium if they call `ctx.elicit`, `ctx.sample`, `ctx.roots`, `ctx.log` or use `McpSyncServerExchange`: those become multi round-trip requests (MRTR) and need a rewrite per method. |
| **SSE → MODERN** | **Medium** | Everything in the STREAMABLE row plus new endpoints (two paths become one POST) and client reconfiguration. |

Nothing changes for: `@McpTool`/`@McpResource`/`@McpPrompt`/`@McpComplete` methods that use only plain arguments, `McpMeta`, `@McpProgressToken` or `McpTransportContext`; `ToolCallback` and `ToolCallbackProvider` beans; `spring.ai.mcp.server.name/version/instructions/capabilities.*/tool-callback-converter/tool-response-mime-type/expose-mcp-client-tools`; the annotation scanner.

---

## 2. Capability matrix

What each server offers. ✅ supported · ⚠️ partial / different · ❌ not available · ➡️ replaced by

| Aspect | SSE | STREAMABLE | STATELESS | MODERN |
|---|---|---|---|---|
| Handshake | `initialize`, session id | `initialize`, session id (`Mcp-Session-Id`) | `initialize` per request, no session | ❌ none ➡️ `server/discover` (cacheable); every request carries `_meta.protocolVersion` + `_meta.clientCapabilities` |
| HTTP surface | GET `/sse` stream + POST `/mcp/message` | POST/GET/DELETE `/mcp` | POST `/mcp` | POST `/mcp` only; progress answered as a short-lived `text/event-stream` |
| Required headers | — | `Mcp-Session-Id`, `MCP-Protocol-Version` | `MCP-Protocol-Version` | `Mcp-Method`, `MCP-Protocol-Version`, and `Mcp-Name` on `tools/call`, `prompts/get`, `resources/read`; mismatch → `-32020` |
| Plain annotated methods | ✅ | ✅ | ✅ | ✅ unchanged |
| `McpMeta`, `@McpProgressToken`, `McpTransportContext` params | ✅ | ✅ | ✅ | ✅ unchanged |
| `McpSyncRequestContext` param | ✅ | ✅ | ❌ method skipped at startup with a warning | ❌ method skipped at startup with a warning ➡️ `McpModernSyncRequestContext` |
| `McpSyncServerExchange` param | ✅ | ✅ | ❌ skipped at startup | ❌ skipped at startup ➡️ `McpRequestContext` / `McpTransportContext` |
| Elicitation (`ctx.elicit`) | ✅ blocking | ✅ blocking | ❌ | ➡️ MRTR: return `InputRequiredResult.elicit(...)`, read `ctx.inputResponse(...)` on retry |
| Sampling (`ctx.sample`) | ✅ blocking | ✅ blocking | ❌ | ➡️ MRTR `createMessage(key, request)` |
| Roots (`ctx.roots`, roots-change consumer bean) | ✅ | ✅ | ❌ | ➡️ MRTR `listRoots(key)`; no change consumer |
| Logging to client (`ctx.log/info/...`) | ✅ | ✅ | ❌ | ⚠️ deprecated in the spec, still available per request (`_meta.logLevel` → `notifications/message` on the request stream); not yet exposed by `McpModernSyncRequestContext`, use `ctx.notify("notifications/message", params)` meanwhile |
| `ctx.ping()` | ✅ | ✅ | ❌ | ❌ removed |
| Progress | ✅ | ✅ | ⚠️ token only, no notifications | ✅ `McpModernSyncRequestContext.progress(...)`, streamed |
| List-changed notifications | ✅ per session, `*-change-notification` props | ✅ per session | ❌ | ✅ via `subscriptions/listen` + `McpChangeFeed`; `InMemorySync*Repository.add/remove` broadcast |
| Resource updated / subscribe | ✅ `McpSyncServer.notifyResourcesUpdated` | ✅ | ❌ | ✅ `resourceUpdated(uri)` + `modern.resource-subscribe` |
| Cache hints (`ttlMs`, `cacheScope`) | ❌ | ❌ | ❌ | ✅ discover, lists, `ReadResourceResult`, `@McpResource(ttlMs, cacheScope)` |
| List pagination | ❌ single page | ❌ | ❌ | ✅ `modern.page-size`, opaque cursors |
| Runtime add/remove | `McpSyncServer.addTool/removeTool/...` | same | ❌ | `InMemorySync*Repository.add/remove` (inject the repository bean) |
| Programmatic specifications | `McpServerFeatures.Sync*Specification` (`McpSyncServerExchange` handler) | same | `McpStatelessServerFeatures.Sync*Specification` (`McpTransportContext` handler) | `org.springframework.ai.mcp.modern.Sync*Specification` (`McpRequestContext` + modern request → `McpSyncResponse<*Outcome>`) |
| Server customizer bean | `McpSyncServerCustomizer` | same | `McpStatelessSyncServerCustomizer` | `McpModernServerCustomizer` (`McpServer.Builder`) |
| Transport context extraction | SDK extractor on the transport provider | same | same | `McpTransportContextExtractor<ServerRequest>` bean |
| Origin/Host validation | ⚠️ transport-specific | ⚠️ | ⚠️ | ✅ `ServerHttpHeaderValidator` bean on every POST |
| Request body limit | ❌ container default | ❌ | ❌ | ✅ `modern.max-request-size` (default 16MB) |
| Multi-instance deployment | session affinity needed | session affinity needed | ✅ free | ✅ free, **but** set `modern.request-state-secret` on all instances when using MRTR |
| STDIO variant | ✅ `stdio=true` | ✅ | ❌ | ✅ `stdio=true` (modern stdio transport) |
| `type=ASYNC` | ✅ | ✅ | ✅ | ❌ fails at startup |

---

## 3. Migration effort matrix

Effort per dimension and path. **None**: no change. **Low**: property or import change, mechanical. **Medium**: per-method rewrite with a clear recipe. **High**: redesign.

| Dimension | SSE → MODERN | STREAMABLE → MODERN | STATELESS → MODERN |
|---|---|---|---|
| Switch protocol property | Low | Low | Low |
| Endpoint and client URL | **Medium** (two paths → one POST; clients change URL and transport) | None/Low (`/mcp` stays; verbs shrink to POST) | None (`/mcp` stays) |
| Properties | Low (drop `sse-*`, `base-url`; move keep-alive) | Low (move `streamable-http.*` to `modern.*`; drop `disallow-delete`) | Low (same as STREAMABLE) |
| Plain annotated methods | None | None | None |
| `McpMeta` / `@McpProgressToken` / `McpTransportContext` | None | None | None |
| Methods with `McpSyncRequestContext` using only `progress` | Low (rename type) | Low | n/a (already skipped on STATELESS) |
| Methods using `elicit` / `sample` / `roots` | **Medium** per method (MRTR rewrite) | **Medium** | None (not possible before) |
| Methods using `log` / `ping` | Low (`ping` has no equivalent; logging becomes per-request and opt-in by the client) | Low | n/a |
| Methods with `McpSyncServerExchange` | **Medium** (replace with `McpRequestContext`; session id and client info come from `McpModernSyncRequestContext`) | **Medium** | n/a |
| `ToolCallback` / `ToolCallbackProvider` beans | None | None | None |
| Programmatic `List<Sync*Specification>` beans | **Medium** (new record types, new handler signature) | **Medium** | **Medium** (smaller: handler already session-free) |
| `McpSyncServerCustomizer` | Low (new customizer type, different builder) | Low | Low |
| Runtime `McpSyncServer.add*/remove*/notify*` | Low (inject `InMemorySync*Repository`) | Low | n/a |
| Roots-change consumer bean | **Medium** (no equivalent; poll via MRTR `listRoots` where needed) | **Medium** | n/a |
| `*-change-notification` properties | Low (replaced by `modern.subscriptions`) | Low | n/a |
| Security (Spring Security filter chain) | Low (one path to secure instead of two) | None/Low | None |
| Load-balanced deployment | Low (drop sticky sessions; add `request-state-secret` if MRTR) | Low | None/Low (secret if MRTR) |
| Client fleet | **High** if clients are not upgraded (see §7) | **High** | **High** |

---

## 4. Property mapping

| Legacy property | Modern equivalent | Note |
|---|---|---|
| `spring.ai.mcp.server.protocol=SSE\|STREAMABLE\|STATELESS` | `spring.ai.mcp.server.protocol=MODERN` | one value per application |
| `spring.ai.mcp.server.type` | unchanged, must be `SYNC` | `ASYNC` fails fast |
| `name`, `version`, `instructions` | unchanged | advertised by `server/discover` |
| `capabilities.tool/resource/prompt/completion` | unchanged | each disables the matching repository bean |
| `tool-callback-converter`, `tool-response-mime-type`, `expose-mcp-client-tools` | unchanged | |
| `stdio` | unchanged | modern stdio transport |
| `request-timeout` | — | applied to server-initiated requests; none exist in MODERN |
| `sse-endpoint`, `sse-message-endpoint`, `base-url` | `modern.mcp-endpoint` (default `/mcp`) | SSE only |
| `streamable-http.mcp-endpoint` | `modern.mcp-endpoint` | STREAMABLE and STATELESS |
| `streamable-http.keep-alive-interval` / SSE `keep-alive-interval` | `modern.keep-alive-interval` (default `30s`, `0` disables) | |
| `streamable-http.disallow-delete` | — | no DELETE verb |
| `tool-change-notification`, `resource-change-notification`, `prompt-change-notification` | `modern.subscriptions` (default `true`) | one switch for the `subscriptions/listen` stream |
| — | `modern.supported-versions` | default `2026-07-28` |
| — | `modern.discover-ttl`, `discover-cache-scope`, `list-ttl`, `list-cache-scope`, `page-size` | new caching and paging knobs |
| — | `modern.request-state-secret`, `request-state-ttl` | MRTR state sealing; secret required for multi-instance |
| — | `modern.max-request-size` | default 16MB |
| — | `modern.resource-subscribe` | advertise `resources.subscribe` |

---

## 5. Code recipes

### 5.1 Progress (STREAMABLE/SSE → MODERN): rename the context type

```java
// before
@McpTool(name = "index")
String index(McpSyncRequestContext ctx, @McpToolParam String path) {
    ctx.progress(50);
    return "ok";
}
// after
@McpTool(name = "index")
String index(McpModernSyncRequestContext ctx, @McpToolParam String path) {
    ctx.progress(50);           // response becomes a short SSE stream automatically
    return "ok";
}
```

### 5.2 Elicitation → multi round-trip request

```java
// before: blocking round trip inside the handler
@McpTool(name = "book")
String book(McpSyncRequestContext ctx, @McpToolParam int guests) {
    var answer = ctx.elicit(Confirmation.class);
    return answer.structuredContent().ok() ? "Booked" : "Cancelled";
}

// after: return input_required, the client retries with the answers
@McpTool(name = "book")
CallToolOutcome book(McpModernSyncRequestContext ctx, @McpToolParam int guests) {
    Optional<Map> confirm = ctx.inputResponse("confirm", Map.class);
    if (confirm.isEmpty()) {
        return InputRequiredResult.builder()
            .elicit("confirm", ElicitFormRequest.builder("Book a table for " + guests + "?", CONFIRM_SCHEMA).build())
            .requestState("guests=" + guests)      // sealed by the server, verified on retry
            .build();
    }
    boolean ok = Boolean.TRUE.equals(confirm.get().get("ok"));
    return CallToolResult.builder().addContent(TextContent.builder(ok ? "Booked" : "Cancelled").build()).build();
}
```

Sampling and roots follow the same shape with `createMessage(key, CreateMessageRequest)` and `listRoots(key)`. Anything the handler needs on the retry goes into `requestState`; the client cannot tamper with it. Resources and prompts return `ReadResourceOutcome` / `GetPromptOutcome` the same way.

### 5.3 Logging and ping

`ctx.ping()` has no protocol equivalent; delete it. Logging is deprecated but still part of the revision: a client that wants messages sets `_meta.logLevel` on the request, and the server may answer with `notifications/message` frames on that request's stream. Until the modern context gains a `log` API, keep a server-side logger, or send the frame yourself with `ctx.notify("notifications/message", new LoggingMessageParams(level, logger, data))` only when `ctx.requestMeta()` contains `io.modelcontextprotocol/logLevel`. For user-visible progress text use `ctx.progress(progress, total, message)`.

### 5.4 `McpSyncServerExchange` → `McpRequestContext` / `McpModernSyncRequestContext`

| Exchange call | Modern replacement |
|---|---|
| `exchange.transportContext()` | `McpTransportContext` parameter, or `ctx.transportContext()` |
| `exchange.getClientCapabilities()` / `getClientInfo()` | `ctx.clientCapabilities()` / `ctx.clientInfo()` (per request, from `_meta`) |
| `exchange.sessionId()` | none; derive identity from the transport context (headers, principal) |
| `createMessage`, `createElicitation`, `listRoots` | MRTR (§5.2) |
| `loggingNotification`, `ping` | `ping` removed; logging per request via `_meta.logLevel` (see §5.3) |
| `progressNotification` | `ctx.progress(...)` |

### 5.5 Runtime changes: `McpSyncServer` → repositories

```java
// before
@Autowired McpSyncServer server;
server.addTool(spec); server.notifyToolsListChanged();

// after
@Autowired InMemorySyncToolRepository tools;
tools.add(new SyncToolSpecification(tool, (ctx, request) -> McpSyncResponse.result(result)));
// list_changed is broadcast to subscriptions/listen clients automatically
```

Resources: `InMemorySyncResourceRepository.add/remove/resourceUpdated(uri)`. For catalogues that are dynamic or per caller, extend `AbstractSync{Tool,Resource,Prompt}Repository` instead and declare it as a bean; it replaces the in-memory one.

### 5.6 Programmatic specifications

```java
// before (STREAMABLE/SSE)
new McpServerFeatures.SyncToolSpecification(tool, null, (exchange, request) -> result);
// before (STATELESS)
new McpStatelessServerFeatures.SyncToolSpecification(tool, (transportContext, request) -> result);
// after
new org.springframework.ai.mcp.modern.SyncToolSpecification(modernTool,
        (McpRequestContext ctx, CallToolRequest request) -> McpSyncResponse.result(modernResult));
```

`McpSchemaConverter.toModern(...)` converts legacy `Tool`/`CallToolResult` records if you want to keep building them with the legacy types.

### 5.7 Customizer and transport hooks

| Before | After |
|---|---|
| `McpSyncServerCustomizer` / `McpStatelessSyncServerCustomizer` | `McpModernServerCustomizer` → `customize(McpServer.Builder)` |
| Transport provider bean (`WebMvcStreamableServerTransportProvider`, ...) | none; the transport is built from properties. Hooks: `McpTransportContextExtractor<ServerRequest>` and `ServerHttpHeaderValidator` beans |
| `RouterFunction` bean override (`webMvcStreamableServerRouterFunction`, `webMvcStatelessServerRouterFunction`, `webMvcSseServerRouterFunction`) | define a bean named `webMvcModernServerRouterFunction` / `webFluxModernServerRouterFunction`; the auto-configured one backs off |

---

## 6. Step-by-step

**Common to all paths**

1. Build against the SDK version carrying `io.modelcontextprotocol.modern` (currently `2.2.0-SNAPSHOT`, see the plan). Starters stay the same.
2. Set `spring.ai.mcp.server.protocol=MODERN`. Start the application: methods with `McpSyncRequestContext`/`McpSyncServerExchange` are **skipped with a warning** naming the method and the replacement type; anything built directly fails fast. Treat every such warning as a work item.
3. Apply the property mapping (§4).
4. Rewrite skipped methods with the recipes (§5.1–5.4).
5. Replace programmatic specifications, customizers and runtime-change code (§5.5–5.7).
6. Decide caching: set `modern.list-ttl`/`discover-ttl` and per-resource `@McpResource(ttlMs, cacheScope)`. Keep `cacheScope=PRIVATE` unless the data is identical for every caller.
7. If any handler returns `InputRequiredResult` and you run more than one instance, set the same `modern.request-state-secret` everywhere.
8. Re-run your client integration tests against the new endpoint (§7).

**SSE additionally**: remove the `/sse` and `/mcp/message` routes from gateways and security rules; a single POST `/mcp` remains. Clients must move from the SSE client transport to a modern one.

**STREAMABLE additionally**: drop sticky-session configuration at the load balancer; remove any handling of `Mcp-Session-Id` and DELETE in proxies or filters.

**STATELESS additionally**: nothing beyond the common steps. The stateless providers already skip methods with `McpSyncRequestContext` or `McpSyncServerExchange`, so no served handler depends on them. You gain progress, notifications and MRTR for free.

---

## 7. Clients and rollout (server-side view)

The protocol property holds one value, so an application serves exactly one protocol. A modern server only accepts requests whose `_meta.protocolVersion` is in `modern.supported-versions` (default `2026-07-28`) and that carry the modern headers; anything else is answered with JSON-RPC error `-32022 Unsupported protocol version` or `-32020`, never downgraded. Plan the switch as a coordinated cut-over, or run the legacy and the modern server as two deployments behind different paths or hosts until the client fleet is upgraded. Client-side migration is out of scope here.

---

## 8. Behaviour differences to expect after the switch

- **Error codes.** Unexpected exceptions in resource, prompt and completion handlers are reported as `-32603 Internal error` without the cause (SDK behaviour). Tool handlers keep returning `isError: true` results. Header/body mismatches return `-32020`.
- **`tool-callback-converter=false`** removes `ToolCallback` tools only; annotated primitives stay (controlled by `spring.ai.mcp.server.annotation-scanner.enabled`).
- **No per-session state.** Anything keyed by session id must move to the transport context (authenticated principal, headers) or into MRTR `requestState`.
- **Streaming responses** appear only when a handler declares `McpModernSyncRequestContext` or `McpSyncNotifier`; plain methods answer with a single JSON response, as before.
- **Request bodies** above `modern.max-request-size` are rejected before parsing.
- **Lists may be paged** once `modern.page-size` is set; clients must follow `nextCursor`.

---

## 9. Pre-switch checklist

- [ ] `type=SYNC` (ASYNC not supported on MODERN)
- [ ] No method declares `McpSyncRequestContext` or `McpSyncServerExchange`, or each has an MRTR rewrite
- [ ] No `ctx.ping()` calls remain; `ctx.log/info/warn/error` calls replaced (server-side logger or per-request `notifications/message`)
- [ ] Programmatic `McpServerFeatures.*` / `McpStatelessServerFeatures.*` beans converted to `org.springframework.ai.mcp.modern.Sync*Specification`
- [ ] `McpSyncServerCustomizer` → `McpModernServerCustomizer`
- [ ] Runtime `McpSyncServer.add*/remove*/notify*` → repository calls
- [ ] Roots-change consumer beans removed or redesigned
- [ ] Properties mapped (§4); `sse-*`, `streamable-http.*`, `*-change-notification` removed
- [ ] `request-state-secret` set on every instance if MRTR is used
- [ ] Security rules cover the single POST endpoint
- [ ] Clients upgraded or a parallel legacy deployment planned
