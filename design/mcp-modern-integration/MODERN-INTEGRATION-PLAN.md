# Spring AI × MCP 2026-07-28 ("modern") server — integration plan and status

Tracking document for the integration of the MCP Java SDK's `io.modelcontextprotocol.modern` server into Spring AI. The design itself is described in [MODERN.md](MODERN.md); this file records what is done, what is open, the decisions behind it and how to verify the state.

| | |
|---|---|
| Status date | 2026-10-23 |
| Branch | `main`, uncommitted working tree (53 changed or new paths) |
| SDK | `mcp.sdk.version` = `2.2.0-SNAPSHOT`, built locally from `java-sdk` |
| Activation | `spring.ai.mcp.server.protocol=MODERN` (`type=SYNC` only) |
| Owner | Christian Tzolov |

Legend: ✅ done · 🔄 in progress · ⏳ planned · ❌ rejected / not planned

---

## 1. Goals

1. Serve `@McpTool`, `@McpResource`, `@McpPrompt`, `@McpComplete` methods and `ToolCallback` beans through the modern SDK server without changes to user code.
2. Expose the modern-only features (MRTR, cache hints, `subscriptions/listen`, `server/discover`) to users who opt in with modern parameter and return types.
3. Keep every Spring-side copy of SDK-shaped code (repositories, converter, transport helpers) in a shape that can later be moved into the SDK by a delegation swap.
4. Do not touch the legacy (SSE, STREAMABLE) and STATELESS paths beyond what the shared annotation module requires.

Guiding rule (2026-10-22): **legacy conversion is a compatibility floor, never a ceiling.** Modern request, context and result types pass straight through; legacy types are converted at the boundary.

---

## 2. Phase 1 — modern server integration ✅

Delivered 2026-10-21/22. Design in MODERN.md §2–§5.

### 2.1 Core (`spring-ai-mcp`, package `org.springframework.ai.mcp.modern`) ✅

| Item | Status | Notes |
|---|---|---|
| `Sync{Tool,Resource,ResourceTemplate,Prompt,Completion}Specification` records | ✅ | definition + handler pairs, SDK-shaped |
| `InMemorySync{Tool,Resource,Prompt,Completion}Repository` | ✅ | renamed from `*Registry` 2026-10-22; runtime `add`/`remove`, change broadcasting |
| `AbstractSync{Tool,Resource,Prompt}Repository` + `RepositoryOptions` | ✅ | added 2026-10-22; context-aware `lookup`/`specifications`, key-cursor paging (`Pagination`), per-request cache-hint hooks, template matching with bounded matcher cache. Completion repository has no abstract layer (single-method interface) |
| `McpSchemaConverter` (legacy ↔ modern) | ✅ | mapper round trip; uses SDK default mapper — see §4 |
| `McpModernToolUtils` (`ToolCallback` → `SyncToolSpecification`) | ✅ | |
| `McpModernServerCustomizer` | ✅ | receives `McpServer.Builder` before `build()` |

### 2.2 Annotations (`spring-ai-mcp-annotations`) ✅

| Item | Status | Notes |
|---|---|---|
| `SyncModernMcp{Tool,Resource,Prompt,Complete}MethodCallback` + `ModernMethodSupport` | ✅ | modern context/request params injected; `*Outcome` returns pass through; legacy returns converted; legacy exchange/context params rejected at construction (tool callback fixed 2026-10-22) |
| `McpModernSyncRequestContext` / default impl | ✅ | `progress`, `log`, `inputResponses()`, `inputResponse(key, type)`; no `elicit`/`sample`/`roots` (MRTR replaces them) |
| `SyncModernMcp{Tool,Resource,Prompt,Complete}Provider` + `SyncMcpAnnotationProviders.modern*` | ✅ | skip legacy-context methods with a modern-specific warning (`ModernMethodSupport.filterLegacyContextMethods`, 2026-10-22) |
| `@McpResource(ttlMs, cacheScope)` | ✅ | added 2026-10-22; applied to converted legacy results, modern `ReadResourceResult` keeps its own; legacy providers warn via `McpResourceCacheHints` |
| `mcp-annotations` depends on `spring-ai-mcp` | ✅ | pom change |

### 2.3 Transports ✅

| Item | Status | Notes |
|---|---|---|
| `WebMvcModernServerTransport` + `ModernTransportSupport` | ✅ | bounded body read under `max-request-size`; SSE streaming with keep-alive comments; 2026-10-22: early header commit, catch-all 405 + `Allow`, SDK default Origin/Host validator |
| `WebFluxMcpTransport` (package `...webflux.transport.modern`) | ✅ | adopted 2026-10-22 from Dariusz Jędrzejczyk's `chemicL/spring-ai:modern-webflux-transport` (commit `70afa598`), replacing `WebFluxModernServerTransport`; carried over: 415 on non-JSON content type, `no-transform`; 41 unit + 4 integration tests |
| Header-validation logic duplicated (WebMvc helper, WebFlux transport, SDK servlet) | ⚠️ accepted | all mirror the SDK servlet transport — see §4 |

### 2.4 Auto-configuration ✅

| Item | Status | Notes |
|---|---|---|
| `McpServerModernAutoConfiguration` | ✅ | repositories back off on user `McpSync*Repository` beans; `McpChangeFeed`; HMAC `RequestStateCodec` (always built, 2026-10-22); stdio transport |
| `ModernServerSpecificationFactoryAutoConfiguration` | ✅ | gated on `McpServer` **and** `McpTool` classes + modern protocol only (converter-flag condition removed 2026-10-22) |
| `ModernToolCallbackConverterAutoConfiguration` | ✅ | gated on modern protocol + `tool-callback-converter` flag |
| `McpServerModern{WebMvc,WebFlux}AutoConfiguration` | ✅ | keep-alive zero/negative → disabled (2026-10-22) |
| `McpServerModernProperties` | ✅ | endpoint, supported versions, discover/list cache hints, `page-size` (2026-10-22), keep-alive, max request size, request-state secret/TTL, subscriptions, resource-subscribe |
| `protocol=MODERN` + `type=ASYNC` fails fast | ✅ | constructor assert |
| Mutual exclusion with legacy/stateless auto-configurations | ✅ verified | test `legacyAndStatelessAutoConfigurationsBackOffForModernProtocol` |

### 2.5 Docs and tests ✅

| Item | Status |
|---|---|
| `MODERN.md` (design, diagrams, properties, extension points, trade-offs) | ✅ |
| `MODERN-MIGRATION-PATHS.md` (SSE/STREAMABLE/STATELESS → MODERN matrices, property mapping, recipes, checklist) | ✅ added 2026-10-22 |
| `WEBFLUX-MODERN-TRANSPORT-COMPARISON.md` (review of Dariusz's WebFlux transport vs. the original one; adoption decision) | ✅ added 2026-10-22, kept local (git-ignored) |
| `SDK-TRANSPORT-PROPOSALS.md` (ten proposed SDK changes that would let the WebMvc/WebFlux transports shed their duplicated protocol code) | ✅ added 2026-10-22 |
| `ANNOTATIONS-GAP-ANALYSIS.md` (2026-07-28 features vs. the server annotations: proposals, handling changes, dropped items) | ✅ added 2026-10-23 |
| `mcp-modern-server-boot-starter-docs.adoc` + nav entry | ✅ |
| `@McpResource` attribute table in `mcp-annotations-server.adoc` | ✅ |
| Unit/IT coverage: `ModernAnnotationProvidersTests` (17), `InMemoryRepositoriesTests`, `AbstractSyncRepositoriesTests` (5), `McpSchemaConverterTests`, `McpModernToolUtilsTests`, `McpServerModernAutoConfigurationIT` (12), WebMvc/WebFlux transport ITs and auto-configuration ITs | ✅ |

### 2.6 Review rounds

| Date | Scope | Outcome |
|---|---|---|
| 2026-10-22 (round 2) | handler errors, request body limits | unexpected handler exceptions → `-32603`; both transports cap chunked bodies |
| 2026-10-22 (code review, 10 findings) | whole working tree | 7 fixed (see §3.1); 1 rejected as incorrect (double `validateMethod` — the base constructor does not validate); 2 deferred to Phase 2 (§3.2 items 3 and 4) |
| 2026-10-22 (code review round 2, 10 findings) | whole working tree incl. adopted WebFlux transport | 7 applied: `PromptAdapter` no longer advertises injected modern parameters as prompt arguments; WebMvc transport logs throwables and answers failing notifications with a JSON-RPC error; WebMvc validator javadoc matches the SDK default; import order fixed in 4 tests; `@since 2.2.0` on all new public API; one `ModernMethodSupport.isInfrastructureParameter` predicate (schema generator no longer depends on the tool callback); legacy cache-hint warning moved into `AbstractMcpResourceMethodCallback.AbstractBuilder` (`supportsCacheHints()` hook). Deferred: shared header-validation helper (with upstream, §3.2 #6/#8), `Pagination` fast path for sorted maps, SNAPSHOT pin (#7) |

---

## 3. Work log and backlog

### 3.1 Completed on 2026-10-22 (this iteration)

- ✅ `@McpResource(ttlMs, cacheScope)` with legacy-path warning.
- ✅ Removed `ToolCallbackConverterCondition` from the modern annotation factory; regression test.
- ✅ Abstract repository bases, `RepositoryOptions`, cursor paging, `page-size` property, docs.
- ✅ Adopted Dariusz's `WebFluxMcpTransport` (see the local `WEBFLUX-MODERN-TRANSPORT-COMPARISON.md`), retired `WebFluxModernServerTransport` and the WebFlux `ModernTransportSupport`; auto-configuration re-pointed (`endpoint()`, `void closeGracefully()`). Ported to WebMvc: early header commit, catch-all 405 with `Allow`, SDK default header validator, `CONTENT_TOO_LARGE`.
- ✅ Review fixes: `@ConditionalOnClass(McpTool)` on the annotation factory; HMAC codec honours `request-state-ttl` without a secret; `keep-alive-interval=0s` disables keep-alives; modern-specific skip warning; tool callback fails fast on legacy context params; matcher cache eviction + bound; list cache hints only as feature defaults.

### 3.2 Phase 2 — backlog ⏳

Ordered by recommended priority.

| # | Item | Status | Rationale / plan |
|---|---|---|---|
| 1 | **ASYNC API type** (`type=ASYNC` with `protocol=MODERN`) | ⏳ | async callbacks and providers; SDK `SyncAdapters` already bridges sync repositories, so repositories need no async twin initially |
| 2 | ~~Resource subscribe flag on `@McpResource`~~ | ❌ dropped 2026-10-23 | the 2026-07-28 revision has no per-resource opt-in: `resources.subscribe` is server-wide and means the `subscriptions/listen` `resourceSubscriptions` filter is supported (see `ANNOTATIONS-GAP-ANALYSIS.md` §2.8) |
| 3 | **Mapper-aware converter plumbing** | ⏳ | `McpSchemaConverter` and `DefaultMcpModernSyncRequestContext.inputResponse` use `McpJsonDefaults.getMapper()`. Plan: optional `McpJsonMapper` on the four modern callback builders, overloads of `SyncMcpAnnotationProviders.modern*(beans, mapper)`, pass the server's `JacksonMcpJsonMapper` from the factory auto-configuration. Avoid a static setter |
| 4 | **De-duplicate tool metadata derivation** | ⏳ | `SyncModernMcpToolProvider.toSpecification` repeats ~40 lines of `SyncMcpToolProvider`; move shared logic into `AbstractMcpToolProvider` |
| 5 | **Declarative MRTR** (`@McpElicit`, `@McpSample`, `@McpRoots`, `@McpInputResponse`, `@McpRequestState`) | ⏳ | design in `ANNOTATIONS-GAP-ANALYSIS.md` §2.1; wait for the SDK 2.2.0 final API before implementing; elicitation first, sampling/roots are deprecated in the spec |
| 5a | **Spec compliance on the modern callbacks** | ⏳ | retry results must carry `ttlMs = 0`; raise `-32021` when an `InputRequiredResult` needs an undeclared client capability (gap analysis §3) |
| 5b | **Per-request logging on `McpModernSyncRequestContext`** | ⏳ | `logLevel()`, `logEnabled()`, `log*()` gated by `_meta.logLevel`, emitting `notifications/message` on the request stream (gap analysis §2.4) |
| 5c | **Definition attributes** | ⏳ | `icons` on `@McpTool`/`@McpResource`/`@McpPrompt`, `@McpArg.title`, `@McpResource.size`, `@McpToolParam.header` (gap analysis §2.5, §2.6) |
| 6 | **SDK extraction** | ⏳ | see `SDK-TRANSPORT-PROPOSALS.md` for the transport side; propose upstream: `InMemorySync*Repository` + abstract bases + `RepositoryOptions`, `McpSchemaConverter`, `ModernTransportSupport`, WebMvc/WebFlux transports. Shapes already mirror SDK naming so Spring can delegate afterwards |
| 7 | **Release pin** | ⏳ blocked | replace `2.2.0-SNAPSHOT` once the SDK releases |
| 8 | **Upstream fixes outside this branch** | ⏳ | (0) coordinate with Dariusz on landing `WebFluxMcpTransport` upstream (package/class names, the 415 check and `no-transform` added here); (a) `StatelessServerSpecificationFactoryAutoConfiguration` wrongly carries `ToolCallbackConverterCondition`; (b) `ToolCallbackConverterCondition` is duplicated between the legacy and stateless converter auto-configurations; (c) `NonStatelessServerCondition` is misnamed now that it also excludes MODERN |
| 9a | **Eager spec snapshot in repository beans** | ⏳ | `InMemorySync*Repository` (and the legacy `McpSyncServer`) collect the `List<Sync*Specification>` beans at construction; a user bean that injects the repository directly (e.g. a controller) forces early creation before the annotation scanner has registered the `@McpTool` beans, and the server starts with no tools. Found 2026-10-23 in the examples; workaround is `ObjectProvider`/`@Lazy`. Consider resolving the specification lists lazily on first use, or failing fast when the scanner has not completed |
| 9 | **Build check** | 🔄 | a multi-module `install` run reported success without executing the auto-configuration module tests (stale surefire reports). Cause unknown; until found, run `test` on those modules explicitly |

### 3.3 Rejected / not planned ❌

| Item | Reason |
|---|---|
| Static mapper setter on `McpSchemaConverter` | global mutable state leaks across contexts in tests; use builder plumbing (§3.2 #3) |
| Abstract base for the completion repository | interface is a single method; nothing to share |
| Failing startup on legacy context parameters under MODERN | providers skip with a warning (as stateless does) so one unconverted method does not block the application; callbacks built directly still fail fast |

---

## 4. Accepted trade-offs (open)

| Trade-off | Impact | Exit |
|---|---|---|
| SDK `2.2.0-SNAPSHOT` pin | cannot release | SDK release (§3.2 #7) |
| Converter uses SDK default JSON mapper | hand-built legacy results with POJOs in `structuredContent`/`_meta` are serialized without the app's Jackson modules; two mappers in one pipeline | §3.2 #3 |
| Header-validation / SSE helpers duplicated (WebMvc `ModernTransportSupport`, `WebFluxMcpTransport`, SDK servlet) | drift risk | SDK-provided helper (§3.2 #6) |
| Abstract repositories are an inheritance contract | harder to move than final classes | move the whole family together (§3.2 #6) |
| `tool-callback-converter=false` keeps annotated tools | intended; documented on the starter page | — |

---

## 5. Decision log

| Date | Decision |
|---|---|
| 2026-10-21 | Start with Phase 1 using private Spring-side copies instead of waiting for SDK changes (SDK has no WebMvc/WebFlux transport, no list-backed repositories, package-private `McpRequestContext` constructor). |
| 2026-10-21 | Annotated methods keep legacy return types; conversion at the boundary. MRTR is explicit (`InputRequiredResult` + `inputResponse(...)` on retry). |
| 2026-10-22 | Legacy conversion is a floor, not a ceiling: modern types always pass through. |
| 2026-10-22 | Phase 1 is SYNC only; `type=ASYNC` fails fast. |
| 2026-10-22 | Unexpected handler exceptions on the modern path report `-32603 Internal error` (SDK behaviour), not `-32602`. |
| 2026-10-22 | `@McpResource` cache hints use the SDK `CacheScope` enum directly; attribute named `ttlMs` to match the wire field; legacy servers warn and ignore. |
| 2026-10-22 | Repositories get abstract bases shaped around *where specifications come from* (per-request lookup), not storage; cursor is key-based for stability under concurrent changes. |
| 2026-10-22 | List cache hints are feature defaults so they cover custom repositories; repositories only carry explicit per-page hints. |
| 2026-10-22 | HMAC codec is always built so `request-state-ttl` is honoured; random per-process key when no secret (SDK warns). |

---

## 6. Verification

```bash
# From spring-ai/. The build cache silently skips tests, so disable it.
./mvnw -o -Dmaven.build.cache.enabled=false -Ddisable.checks=true \
  -pl mcp/common,mcp/mcp-annotations install

./mvnw -o -Dmaven.build.cache.enabled=false -Ddisable.checks=true \
  -pl auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-common,\
auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-webmvc,\
auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-webflux \
  test -Dtest='*Modern*IT' -Dsurefire.failIfNoSpecifiedTests=false

./mvnw -o -q spring-javaformat:validate -pl mcp/common,mcp/mcp-annotations
```

Last verified 2026-10-22: `mcp/common` 148 tests, `mcp/mcp-annotations` 1393 tests, modern auto-configuration ITs 12 + 4 + 3, all passing.

Notes: checkstyle is skipped with `-Ddisable.checks=true` (not `-Dcheckstyle.skip`). The SDK must be installed locally from `java-sdk` first.
