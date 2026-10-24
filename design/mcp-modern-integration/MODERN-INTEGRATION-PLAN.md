# Spring AI × MCP 2026-07-28 ("modern") server — status and backlog

This document tracks the integration of the MCP Java SDK's `io.modelcontextprotocol.modern` server into Spring AI.
It records what has been delivered, what is still open, the decisions that shape the design and how to verify the current state.
The design itself is described in [MODERN.md](MODERN.md).

| | |
|---|---|
| Status date | 2026-10-24 |
| Branch | `modern-mcp-server` (plus the uncommitted repository reshape and document consolidation) |
| SDK | `mcp.sdk.version` = `2.2.0-SNAPSHOT`, built locally from `java-sdk` |
| Activation | `spring.ai.mcp.server.protocol=MODERN` (`type=SYNC` only) |
| Owner | Christian Tzolov |

Legend: ✅ delivered · ⏳ planned · ❌ rejected

## 1. Goals

1. Serve `@McpTool`, `@McpResource`, `@McpPrompt`, `@McpComplete` methods and `ToolCallback` beans through the modern SDK server without changes to user code.
2. Expose the modern-only features (MRTR, cache hints, `subscriptions/listen`, `server/discover`) to users who opt in with modern parameter and return types.
3. Keep every Spring-side copy of SDK-shaped code (repositories, converter, transport helpers) in a shape that can later be moved into the SDK by a delegation swap.
4. Do not touch the legacy (SSE, STREAMABLE) and STATELESS paths beyond what the shared annotation module requires.

The guiding rule is that the legacy conversion is a compatibility baseline, not a limit: the modern request, context and result types are passed straight through, while the legacy types are converted at the boundary.

## 2. Delivered

Phase 1 is complete.
The following table lists the delivered areas and the sections of [MODERN.md](MODERN.md) that describe them.

| Area | Where |
|---|---|
| `spring-ai-mcp` core: specifications, abstract and in-memory repositories, `McpVisibilityFilter`, `McpTransportContexts`, `McpSchemaConverter`, `McpModernToolUtils`, `McpModernServerCustomizer` | MODERN.md §2, §3.1 |
| Annotations: modern callbacks, providers, `McpModernSyncRequestContext` (progress, MRTR inputs, per-request logging), MRTR compliance (`-32021`), definition attributes (`@McpIcon`, `icons`, `title`, `size`, `header`), `@McpResource(ttlMs, cacheScope)` | MODERN.md §1.1, §3.2 |
| Transports: `WebMvcModernServerTransport`, `WebFluxMcpTransport` (contributed by Dariusz Jędrzejczyk) | MODERN.md §3.3, §5.1 |
| Auto-configuration: server, repositories with filter beans and completion guard, specification factories, header extractor default, properties | MODERN.md §3.3, §4.1, §6, §7 |
| Documents | this plan; [MODERN.md](MODERN.md); [MODERN-MIGRATION-PATHS.md](MODERN-MIGRATION-PATHS.md) for application developers; `mcp-modern-server-boot-starter-docs.adoc` and `mcp-annotations-server.adoc` reference pages; `WEBFLUX-MODERN-TRANSPORT-COMPARISON.md` local only (git-ignored) |

## 3. Backlog

The open items are listed in the recommended order of implementation.
The design of the larger items is attached as appendices.

| # | Item | Plan |
|---|---|---|
| 1 | **Keyset paging hook on the abstract repositories** | `definitions(ctx)` returns the whole catalogue and the base sorts it per page; replace with `definitionsAfter(ctx, afterKey, limit)` (resources add `templateDefinitionsAfter`), base fetches `limit + 1`, loops past hidden items, in-memory uses `tailMap`. Design and snippets in Appendix A. Do it before 2.2.0 while the hook names are unreleased |
| 2 | **ASYNC API type** (`type=ASYNC` with `protocol=MODERN`) | async callbacks and providers; the SDK `SyncAdapters` already bridge sync repositories, so repositories need no async twin initially |
| 3 | **Mapper-aware converter plumbing** | `McpSchemaConverter` and `DefaultMcpModernSyncRequestContext.inputResponse` use `McpJsonDefaults.getMapper()`. Plan: optional `McpJsonMapper` on the four modern callback builders, overloads of `SyncMcpAnnotationProviders.modern*(beans, mapper)`, pass the server's `JacksonMcpJsonMapper` from the factory auto-configuration. No static setter |
| 4 | **De-duplicate tool metadata derivation** | `SyncModernMcpToolProvider.toSpecification` repeats ~40 lines of `SyncMcpToolProvider`; move shared logic into `AbstractMcpToolProvider` |
| 5 | **Declarative MRTR** (`@McpElicit`, `@McpSample`, `@McpRoots`, `@McpInputResponse`, `@McpRequestState`) | design in Appendix B; wait for the SDK 2.2.0 final API; elicitation first, sampling and roots are deprecated in the spec. Smaller items in the same appendix: `ctx.isCancelled()`, hand-written schema attributes |
| 6 | **Eager spec snapshot in repository beans** | `InMemorySync*Repository` (and the legacy `McpSyncServer`) collect the `List<Sync*Specification>` beans at construction; a user bean that injects the repository directly forces early creation before the annotation scanner has registered the `@McpTool` beans, and the server starts with no tools. Workaround: `ObjectProvider` or `@Lazy`. Consider resolving the lists lazily on first use, or failing fast when the scanner has not completed |
| 7 | **SDK extraction** | proposals 1 to 14 in Appendix C; Spring-side candidates: `InMemorySync*Repository` + abstract bases + `McpVisibilityFilter`, `McpSchemaConverter`, `ModernTransportSupport`, WebMvc/WebFlux transports. Shapes already mirror SDK naming so Spring can delegate afterwards |
| 8 | **Upstream fixes outside this branch** | coordinate with Dariusz on landing `WebFluxMcpTransport` upstream (package and class names, the 415 check and `no-transform` added here); `StatelessServerSpecificationFactoryAutoConfiguration` wrongly carries `ToolCallbackConverterCondition`; `ToolCallbackConverterCondition` is duplicated between the legacy and stateless converter auto-configurations; `NonStatelessServerCondition` is misnamed now that it also excludes MODERN |
| 9 | **Release pin** | blocked: replace `2.2.0-SNAPSHOT` once the SDK releases |

### Rejected items

The following options were considered and rejected:

| Item | Reason |
|---|---|
| Static mapper setter on `McpSchemaConverter` | global mutable state leaks across contexts in tests; use builder plumbing (#3) |
| Abstract base for the completion repository | interface is a single method; nothing to share |
| Failing startup on legacy context parameters under MODERN | providers skip with a warning (as stateless does) so one unconverted method does not block the application; callbacks built directly still fail fast |
| Per-resource `subscribe` flag on `@McpResource` | the 2026-07-28 revision has no per-resource opt-in: `resources.subscribe` is server-wide and means the `subscriptions/listen` `resourceSubscriptions` filter is supported (MODERN.md §1.1 row 12) |
| Per-request `listTtlMs(ctx)` hook on the repositories | the TTL comes from `list-ttl`; a per-request scope hook is enough for personalised listings |
| `RepositoryOptions` record | duplicated the builders and the properties; constructors take the broadcaster, page size and filter directly |

## 4. Accepted trade-offs

The following trade-offs are accepted for now, together with the condition under which each one goes away.

| Trade-off | Impact | Exit |
|---|---|---|
| SDK `2.2.0-SNAPSHOT` pin | cannot release | SDK release (#9) |
| Converter uses the SDK default JSON mapper | hand-built legacy results with POJOs in `structuredContent`/`_meta` are serialised without the app's Jackson modules; two mappers in one pipeline | #3 |
| Header-validation / SSE helpers duplicated (WebMvc `ModernTransportSupport`, `WebFluxMcpTransport`, SDK servlet) | drift risk | SDK helper (#7, Appendix C items 1–3) |
| Abstract repositories are an inheritance contract | harder to move than final classes | move the whole family together (#7) |
| Per-request logging is emitted but never advertised | the SDK hard-codes the `logging` capability to null, so spec-following clients never set `_meta.logLevel` and the `log*` API is inert for them | Appendix C item 11, or drop the API if the answer is no |
| Resource subscriptions ignore visibility | `subscriptions/listen` matches `resourceSubscriptions` against raw URIs in the SDK, so a hidden resource's updates still reach a subscriber who knows the URI | Appendix C item 13 |
| `tool-callback-converter=false` keeps annotated tools | intended; documented on the starter page | — |

## 5. Design decisions

The following decisions shape the design and should be kept in mind when extending it.

| Decision | Why |
|---|---|
| Private Spring-side copies instead of waiting for SDK changes | the SDK has no WebMvc/WebFlux transport, no list-backed repositories and a package-private `McpRequestContext` constructor |
| Annotated methods keep legacy return types; conversion at the boundary; modern types always pass through | one programming model across protocols without capping the modern features |
| MRTR is explicit: `InputRequiredResult` plus `inputResponse(...)` on retry | the declarative form waits for the SDK API to settle (#5) |
| SYNC only; `type=ASYNC` fails fast | starting with no server would be silent |
| Unexpected handler exceptions report `-32603 Internal error` without the cause | SDK behaviour; tool handlers keep returning `isError` results |
| `@McpResource` cache hints use the SDK `CacheScope` enum and the wire name `ttlMs`; legacy servers warn and ignore | one vocabulary with the protocol |
| Repository bases are shaped around where definitions come from, not storage; cursors are key-based | stable paging under concurrent add and remove |
| List cache hints are feature defaults; repositories carry only explicit per-page scope | defaults cover custom repositories too |
| Visibility is one hook consulted on every path and a filtered listing is always private | hiding must not leak through call-by-name or a shared cache |
| Request headers reach the transport context by default, `Authorization` and `Cookie` included | the common filter rule needs them; an extractor bean narrows the set |
| HMAC request-state codec is always built, random key without a secret | `request-state-ttl` is honoured either way |

## 6. Verification

```bash
# From spring-ai/. Disable the build cache; it can skip tests silently.
./mvnw -o -Dmaven.build.cache.enabled=false -Ddisable.checks=true \
  -pl mcp/common,mcp/mcp-annotations install

./mvnw -o -Dmaven.build.cache.enabled=false -Ddisable.checks=true \
  -pl auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-common,\
auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-webmvc,\
auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-webflux \
  install -Dtest='McpServerModern*' -Dsurefire.failIfNoSpecifiedTests=false

./mvnw -o -Dmaven.build.cache.enabled=false -Pcheckstyle-check -pl <modules> process-sources
```

Last verified 2026-10-24: `mcp/common` 159 tests, `mcp/mcp-annotations` 1403 tests, modern auto-configuration ITs 13 + 6 + 5, all passing; checkstyle clean.

NOTE: Checkstyle is skipped with `-Ddisable.checks=true`, and the SDK must be installed locally from `java-sdk` before building.

IMPORTANT: Do not install a module with `-DskipTests`.
In this build it has produced an empty jar, and the modules compiled against that jar fail at runtime with a `NoClassDefFoundError` that names a class without its package.
Select the tests to run with `-Dtest=...` instead.

## Appendix A. Keyset paging hook (backlog item 1)

**Problem.**
The `definitions(ctx)` hook returns every definition, and the base class pages that list in memory: `Pagination.page` copies and sorts the whole catalogue on every `*/list` request and then cuts one page out of it.
For a database-backed repository or a large file tree this means a full scan plus an `n log n` sort per page, and the cost grows the further a client pages.
The in-memory repositories pay the same price: their `ConcurrentSkipListMap` is already sorted, yet each call copies and re-sorts it.
The SDK's `list(ctx, cursor)` method, which returns a `ToolsPage`, is the right primitive because it lets the repository decide how to produce a page; the base class currently hides it.

**Approach.**
The cursor is already a keyset cursor: `k:` plus the last key served, base64 encoded.
The query "up to N items with a key greater than X, in key order" is a single indexed query in SQL, a `tailMap` call on a sorted map, or a seek in any ordered store, so the hook only has to express that query.
One abstract hook per base class replaces `definitions(ctx)`.
The base asks for `limit + 1` items to learn whether a next page exists without counting, never sorts, and encodes the next cursor from the last item it served.

```java
public abstract class AbstractSyncToolRepository implements McpSyncToolRepository {

    /**
     * Returns at most {@code limit} tool definitions whose name sorts after
     * {@code afterName}, in ascending name order. {@code afterName} is null for the
     * first page; {@code limit} is null when no page size is configured, meaning all.
     * Visibility is applied by the caller; a subclass that hides most of its catalogue
     * should apply {@link #visibility()} inside its query instead.
     */
    protected abstract List<Tool> definitionsAfter(McpRequestContext ctx,
            @Nullable String afterName, @Nullable Integer limit);

    @Override
    public ToolsPage list(McpRequestContext ctx, @Nullable String cursor) {
        Pagination.Page<Tool> page = Pagination.collect(
                cursor, this.pageSize, Tool::name,
                (afterKey, limit) -> definitionsAfter(ctx, afterKey, limit),
                tool -> visible(ctx, tool));
        return new ToolsPage(page.items(), page.nextCursor(), null, listCacheScope(ctx));
    }
}
```

The paging logic is implemented once, in `Pagination`, for all four keyed collections.
It keeps fetching past hidden items until the page is full, so clients always receive full pages and never an empty page with a cursor.
The cost is proportional to the number of hidden items skipped, which is the expected price of filtering outside the query.

```java
final class Pagination {

    interface Fetch<T> {
        List<T> after(@Nullable String afterKey, @Nullable Integer limit);
    }

    static <T> Page<T> collect(@Nullable String cursor, @Nullable Integer pageSize,
            Function<T, String> key, Fetch<T> fetch, Predicate<T> visible) {
        String afterKey = cursor != null ? decode(cursor) : null;   // -32602 on garbage
        if (pageSize == null) {                                     // unbounded: one page
            if (cursor != null) throw invalidCursor(cursor);
            return new Page<>(fetch.after(null, null).stream().filter(visible).toList(), null);
        }
        List<T> items = new ArrayList<>(pageSize);
        boolean more;
        do {
            // Ask for one extra so we learn whether a next page exists without counting.
            List<T> batch = fetch.after(afterKey, pageSize + 1);
            more = batch.size() > pageSize;
            for (T item : more ? batch.subList(0, pageSize) : batch) {
                if (visible.test(item) && items.size() < pageSize) items.add(item);
            }
            if (!batch.isEmpty()) afterKey = key.apply(batch.get(Math.min(batch.size(), pageSize) - 1));
        }
        while (more && items.size() < pageSize);   // keep going past hidden items
        String next = more ? encode(key.apply(items.get(items.size() - 1))) : null;
        return new Page<>(List.copyOf(items), next);
    }

    /** Sort-and-cut for catalogues that are small enough to hold in a list. */
    static <T> List<T> slice(Collection<T> all, Function<T, String> key,
            @Nullable String afterKey, @Nullable Integer limit) {
        Stream<T> s = all.stream().sorted(Comparator.comparing(key));
        if (afterKey != null) s = s.filter(t -> key.apply(t).compareTo(afterKey) > 0);
        return (limit != null ? s.limit(limit) : s).toList();
    }
}
```

NOTE: The next cursor must be the key of the last item *served*, not of the last item *fetched*; otherwise the client would skip the hidden-but-fetched tail. The loop therefore re-fetches internally from the last fetched key but encodes the cursor from `items`.

The in-memory repositories become logarithmic instead of performing a full sort:

```java
// ConcurrentSkipListMap<String, SyncToolSpecification> tools, keyed by name
@Override
protected List<Tool> definitionsAfter(McpRequestContext ctx, @Nullable String afterName,
        @Nullable Integer limit) {
    Collection<SyncToolSpecification> tail = afterName == null
            ? this.tools.values()
            : this.tools.tailMap(afterName, false).values();
    Stream<Tool> s = tail.stream().map(SyncToolSpecification::tool);
    return (limit != null ? s.limit(limit) : s).toList();
}
```

A small custom repository remains a one-liner thanks to the helper:

```java
@Override
protected List<Tool> definitionsAfter(McpRequestContext ctx, @Nullable String afterName,
        @Nullable Integer limit) {
    return Pagination.slice(catalog.toolsOf(tenant(ctx)), Tool::name, afterName, limit);
}
```

A database-backed repository issues a keyset query and pushes the visibility rule into it; the subsequent `visible` check in the base class then becomes a cheap no-op:

```java
@Override
protected List<Tool> definitionsAfter(McpRequestContext ctx, @Nullable String afterName,
        @Nullable Integer limit) {
    String sql = "select name, title, description, input_schema from tools"
            + " where tenant = :tenant"
            + (afterName != null ? " and name > :after" : "")
            + " order by name"
            + (limit != null ? " fetch first :limit rows only" : "");
    return jdbc.sql(sql)
            .param("tenant", tenant(ctx))
            .param("after", afterName).param("limit", limit)
            .query(this::toTool).list();
}
```

The file-system scenario cannot seek within a directory walk, so it still visits every file, but it stops materialising `Resource` records beyond the page.
A repository that needs true seeking keeps a sorted index of paths and uses `tailMap` on it, like the in-memory class.

```java
@Override
protected List<Resource> definitionsAfter(McpRequestContext ctx, @Nullable String afterUri,
        @Nullable Integer limit) {
    try (Stream<Path> files = Files.walk(this.root)) {
        Stream<Resource> s = files.filter(Files::isRegularFile)
                .map(this::toResource)
                .sorted(Comparator.comparing(Resource::uri));
        if (afterUri != null) s = s.filter(r -> r.uri().compareTo(afterUri) > 0);
        return (limit != null ? s.limit(limit) : s).toList();
    }
}
```

The resource base has two keyed collections and therefore needs two hooks:

```java
protected abstract List<Resource> definitionsAfter(McpRequestContext ctx,
        @Nullable String afterUri, @Nullable Integer limit);

protected abstract List<ResourceTemplate> templateDefinitionsAfter(McpRequestContext ctx,
        @Nullable String afterUriTemplate, @Nullable Integer limit);
```

`read` still needs every visible template in declaration order to pick the first match, so `matchTemplate` calls `templateDefinitionsAfter(ctx, null, null)`; this is unchanged in cost and acceptable as long as template sets stay small.

**Deliberately unchanged.**

* Template matching remains a scan, because the first-match-wins rule is order dependent by design.
* The completion guard scans the template pages to find a template by its exact URI template; the proper fix is an SDK `findTemplate(ctx, uriTemplate)` method next to `find` for tools and prompts (Appendix C, item 14).
* Offset cursors would be simpler to implement but are unstable under concurrent add and remove operations, which the key cursor handles today and the tests rely on.
* Mirroring the names of Spring Data's `Window` and `KeysetScrollPosition` is tempting, but a Spring Data dependency in `mcp/common` is not justified.

**Tests.**

* A fake repository that records every `(afterKey, limit)` pair it receives: paging 1000 items with a page size of 10 must request `limit = 11` each time and never `null`.
* Hidden items spanning a page boundary: the client receives full pages, and the cursor never skips a visible item.
* The unbounded case still rejects any cursor.

**Cost.**
One abstract method per base class gets a new signature, the three in-memory implementations are rewritten onto `tailMap`, the paging helper becomes reusable, the fetch-until-full loop is added, and the three tests above are written.
The hook names are not released yet, so the rename is free until 2.2.0 ships.

## Appendix B. Annotation proposals (backlog item 5)

### B.1 Declarative multi round-trip requests

**Specification.**
`tools/call`, `prompts/get` and `resources/read` may answer with `resultType: "input_required"`, carrying `inputRequests` (a map from key to `ElicitRequest`, `CreateMessageRequest` or `ListRootsRequest`) and an opaque `requestState`.
The client retries the same call with a new id, `inputResponses` keyed in the same way, and the unmodified `requestState`.
Servers MUST NOT request a capability that the client did not declare in `_meta.clientCapabilities`.
Roots and sampling are deprecated as of this revision; elicitation is not.

**Current state.**
A method declares a `McpModernSyncRequestContext` parameter, returns `CallToolOutcome` (or `ReadResourceOutcome`, `GetPromptOutcome`), builds the `InputRequiredResult` by hand and reads the answers with `ctx.inputResponse(key, Class)`.
This works, but every handler repeats the same steps: check for the answer, build the form schema, seal the state and branch.

**Proposal.**
Declarative inputs are expressed as parameter annotations.
A parameter annotated with one of the following annotations is bound from `inputResponses` when present.
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

The annotations are sketched below.

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

**Supporting types.**

* `ElicitAnswer<T>(Action action, @Nullable T content)` with an `accepted()` method; the form schema is generated from `T` by `McpJsonSchemaGenerator` (a flat object of primitives, enums and arrays, as the specification requires).
* `SampledMessage(role, content, model, stopReason)` with a `text()` method.
* `StructuredElicitResult<T>` from the legacy context could be reused instead of `ElicitAnswer`, at the cost of a dependency on the legacy `ElicitResult.Action` enum.

**Handling.**
The modern callbacks collect the annotated parameters, consult `ctx.inputResponses()` and short-circuit before invoking the method.
The request-state payload is `{arguments, userState}`: the arguments so the retry sees a consistent view, and the method's own state when a `@McpRequestState` parameter is declared and the method returned an `InputRequiredResult` with state.
Schema generation excludes the annotated parameters from the tool input schema, exactly as it excludes the context types today.
MRTR is allowed only on `tools/call`, `prompts/get` and `resources/read`; `@McpComplete` methods reject these annotations at startup.
The callback knows the required capabilities before invoking the method and raises `-32021` first; the existing check on returned `InputRequiredResult`s stays for imperative handlers.
No annotation attribute advertises requirements in `tools/list`: the spec has no per-tool capability declaration.

### B.2 Cancellation

**Specification.**
Over Streamable HTTP, closing the response stream cancels the request; `notifications/cancelled` is used for stdio and for servers that tear down a `subscriptions/listen` stream.

**Current state.**
Cancellation reaches a handler only as a Reactor cancel signal; on the WebMvc container thread a blocking handler runs to completion.

**Proposal (optional).**
Add `ctx.isCancelled()` for long-running synchronous handlers that want to stop early, backed by the transport's disconnect signal.
No annotation change is required.

### B.3 Hand-written schemas

**Specification (SEP-2106).**
`inputSchema` and `outputSchema` may use any JSON Schema 2020-12 keyword, and `structuredContent` may be any JSON value.

**Proposal (optional).**
Add `@McpTool(inputSchema = "...json...", outputSchema = "...json...")` as escape hatches that override schema generation, for tools whose schema uses keywords the generator does not produce (`oneOf`, `pattern`, `$ref`).
This is low priority, since the generator covers the common cases.

## Appendix C. Proposed MCP Java SDK changes (backlog item 7)

Items 1 to 10 and 12 come from three transports: the SDK's `HttpServletMcpTransport`, Spring AI's `WebMvcModernServerTransport` and `WebFluxMcpTransport`; items 11, 13 and 14 from the annotation and repository work.

### C.1 Transports

Each transport re-implements the same protocol logic, copied from private members of the servlet transport: the header consistency checks (`Mcp-Method`, `MCP-Protocol-Version`, `Mcp-Name` with base64), the error-code to HTTP-status table, the keep-alive framing, the `HeaderAccessor` adapter, the `instanceof McpServer` shutdown hook and the `subscriptions/listen` special case.
About 350 lines are duplicated across the two Spring transports, and the gaps found in review (a missing 415 response, a missing early flush, a missing catch-all 405) were all in the copied code.

| # | Change | Why |
|---|---|---|
| 1 | Public `HttpTransportSupport`: `validateHeaders`, `decodeMcpNameHeader`, `protocolVersionFromMeta`, `httpStatusFor`, `isJsonContentType`, header constants, `DEFAULT_ERROR_STATUSES`; include `x-mcp-header` mirrored parameters in the header-versus-body check | removes ~120 lines per transport; one place for spec changes |
| 2 | `SseFrame` record + `SseFrames.frames(messages, mapper, keepAlive, leadingComment)` | the keep-alive merge is the subtlest copied code; leading comment becomes the default |
| 3 | Move `ServerHttpHeaderValidator`, `HeaderAccessor`, `DefaultServerTransportSecurityValidator`, `McpTransportContextExtractor` out of the legacy packages; add `HeaderAccessor.of(values, names)` | modern transports stop importing legacy API and writing anonymous adapters |
| 4 | `McpTransportResponse.Streaming.longLived()` set by the server, or `McpRequestManager.isLongLived(request)` | transports stop hard-coding `subscriptions/listen` to choose `handle()` over `handleBlocking()` |
| 5 | `default void closeGracefully()` on `McpRequestManager` | no downcast to `McpServer`; decorators keep shutdown working |
| 6 | Implement `notifications/cancelled` in `McpServer`, or drop the javadoc claim that transports handle it | no transport keeps in-flight bookkeeping today |
| 7 | `McpRequestContext` factory plus modern request builders and an in-memory tool repository in `mcp-test` | repository and callback tests without a full server; `mcp-test` has no modern fixtures |
| 8 | Document that the modern server ignores `McpTransportContext.KEY` in the Reactor context | removes dead `contextWrite` calls copied from legacy transports |
| 9 | Ship `Sync*Specification` records, in-memory and abstract specification-backed repositories | a runnable modern server without framework code; Spring AI's copies are already SDK-shaped |
| 10 | Transport contract in the docs plus an abstract conformance test in `mcp-test` | keeps future ports complete; the WebFlux unit tests are a ready seed |
| 12 | Default `McpTransportContextExtractor` in `HttpServletMcpTransport` that exposes the request headers (lower-cased, `Mcp-*` dropped) under a documented context key, instead of `McpTransportContext.EMPTY` | every context-dependent repository or visibility rule otherwise starts with a custom extractor; Spring AI's HTTP auto-configurations do this (`McpTransportContexts.HEADERS`), and the SDK transport should match so handlers behave the same on both |

The suggested order is: items 1, 2, 3 and 5 together, as additive changes with the servlet transport refactored onto them; then items 4 and 6, since they change the `McpRequestManager` semantics; then items 7, 10 and 12; item 9 as a feature of its own; item 8 at any time.

What remains transport-specific is the body reading under a size limit with charset handling, the mapping of `SseFrame` to the framework's writer and thread model, the adaptation of the request to `HeaderAccessor` and the context extractor, and the routing table.

### C.2 Server features

| # | Change | Why |
|---|---|---|
| 11 | Let a feature opt in to the `logging` server capability in `ServerCapabilities.Builder.build()` (today hard-coded to null, "not offered by modern servers"), and reject an unrecognised `_meta.io.modelcontextprotocol/logLevel` with `-32602` in `McpServer` | the 2026-07-28 logging page says a server that emits `notifications/message` MUST declare `logging` and SHOULD reject bad levels; Spring AI's per-request `log*` API on `McpModernSyncRequestContext` emits correctly but cannot advertise, so spec-following clients never ask for logs. Logging is deprecated by SEP-2577, so a deliberate "no" is a valid answer; Spring AI then drops the API |
| 13 | A visibility hook on `SubscriptionsFeature`, e.g. `McpSyncResourceRepository.find(ctx, uri)` consulted before honouring a `resourceSubscriptions` entry, or a `Predicate<String>` per listen request | the repositories can hide a resource from `list` and `read`, but `subscriptions/listen` matches `notifications/resources/updated` against the raw URI list, so a hidden resource's updates still reach a client that knows its URI |
| 14 | `McpSyncResourceRepository.findTemplate(ctx, uriTemplate)` (and the async twin), mirroring `find` on the tool and prompt repositories | a server that must check whether a caller may see one template, for example to guard `completion/complete` for a `ref/resource`, has only `listTemplates` and must page through all of them |
