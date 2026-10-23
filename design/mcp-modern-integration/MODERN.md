# Spring AI integration for the MCP 2026-07-28 ("modern") server

This document describes how Spring AI exposes `@McpTool`, `@McpResource`, `@McpPrompt`, `@McpComplete` methods and `ToolCallback` beans through the MCP Java SDK's `io.modelcontextprotocol.modern` server, selected with `spring.ai.mcp.server.protocol=MODERN`. Status, backlog and decision log live in [MODERN-INTEGRATION-PLAN.md](MODERN-INTEGRATION-PLAN.md). Migration guidance for existing servers is in [MODERN-MIGRATION-PATHS.md](MODERN-MIGRATION-PATHS.md); the annotation gap analysis against the 2026-07-28 revision is in [ANNOTATIONS-GAP-ANALYSIS.md](ANNOTATIONS-GAP-ANALYSIS.md).

The diagrams are Mermaid and render on GitHub and in most IDEs.

## 1. What the modern protocol changes

| Aspect | Session-based servers (STREAMABLE, STATELESS) | Modern server (2026-07-28) |
|---|---|---|
| Handshake | `initialize` + session id | None. `server/discover` is cacheable; every request carries `_meta.protocolVersion` and `_meta.clientCapabilities` |
| HTTP verbs | POST, GET (SSE), DELETE | POST only. A handler that emits progress answers with a short-lived `text/event-stream` |
| Server-initiated input | `ctx.elicit(...)`, `ctx.sample(...)`, `ctx.roots()` over the session | Multi round-trip request (MRTR): the handler returns `InputRequiredResult`; the client retries with `inputResponses` and the server-sealed `requestState` |
| Notifications | Per-session | `subscriptions/listen` stream backed by `McpChangeFeed` |
| Logging, ping | `logging/setLevel` + `notifications/message`, `ping` | `ping` removed. Logging deprecated but live: the client sets `_meta.logLevel` per request and the server may send `notifications/message` on that request's stream before the result (`McpModernSyncRequestContext.log*`, silent unless the client set the level) |
| Cache hints | None | `ttlMs` / `cacheScope` on `server/discover`, list results and `ReadResourceResult` |

Spring AI keeps one programming model across all three protocols. A method keeps its legacy `McpSchema` parameter and return types, and the modern path converts them at the boundary. The conversion is a floor, not a ceiling: each primitive also accepts the modern request, context and `*Outcome` types directly, and those pass through untouched.

## 2. Module layout

| Module | Package | Contents |
|---|---|---|
| `spring-ai-mcp` (`mcp/common`) | `org.springframework.ai.mcp.modern` | `Sync*Specification` records, `AbstractSync*Repository` bases and `InMemorySync*Repository` implementations of the SDK repository interfaces, `RepositoryOptions`, `McpSchemaConverter` (legacy ↔ modern), `McpModernToolUtils` (`ToolCallback` → specification) |
| `spring-ai-mcp-annotations` | `...annotation.context` | `McpModernSyncRequestContext` and its default implementation |
| | `...annotation.common` | `ModernMethodSupport`: parameter rules, context injection, error translation shared by the callbacks |
| | `...annotation.method.{tool,resource,prompt,complete}` | `SyncModernMcp*MethodCallback`: one per primitive, implements the matching specification `Handler` |
| | `...annotation.provider.*` | `SyncModernMcp*Provider`: scan beans and build specifications |
| | `...annotation.spring` | `SyncMcpAnnotationProviders.modern*Specifications(...)` entry points |
| `mcp-spring-webmvc`, `mcp-spring-webflux` | `...server.webmvc.transport`, `...server.webflux.transport.modern` | `WebMvcModernServerTransport` (+ package-private `ModernTransportSupport`), `WebFluxMcpTransport` (contributed by Dariusz Jędrzejczyk, self-contained) |
| `spring-ai-autoconfigure-mcp-server-common` | `...server.common.autoconfigure` | `McpServerModernAutoConfiguration`, `ModernToolCallbackConverterAutoConfiguration`, `ModernServerSpecificationFactoryAutoConfiguration`, `McpServerModernProperties` |
| `spring-ai-autoconfigure-mcp-server-{webmvc,webflux}` | `...server.{webmvc,webflux}.autoconfigure` | `McpServerModernWebMvcAutoConfiguration`, `McpServerModernWebFluxAutoConfiguration` |

## 3. Class diagrams

### 3.1 Specifications and repositories

The SDK server pulls primitives from repository interfaces. Spring AI contributes list-backed, mutable implementations and a specification record per primitive that pairs the schema object with a handler.

```mermaid
classDiagram
    direction TB

    class McpSyncToolRepository {
        <<SDK interface>>
        +list(ctx, cursor) ToolsPage
        +find(ctx, name) Tool
        +call(ctx, request) McpSyncResponse~CallToolOutcome~
    }
    class McpSyncResourceRepository {
        <<SDK interface>>
        +list(ctx, cursor) ResourcesPage
        +listTemplates(ctx, cursor) ResourceTemplatesPage
        +read(ctx, request) McpSyncResponse~ReadResourceOutcome~
        +supportsSubscribe() boolean
    }
    class McpSyncPromptRepository {
        <<SDK interface>>
        +list(ctx, cursor) PromptsPage
        +get(ctx, request) McpSyncResponse~GetPromptOutcome~
    }
    class McpSyncCompletionRepository {
        <<SDK interface>>
        +complete(ctx, request) CompleteResult
    }

    class AbstractSyncToolRepository {
        <<abstract>>
        #lookup(ctx, name)* SyncToolSpecification
        #specifications(ctx)* List
        #listTtlMs(ctx) Long
        #listCacheScope(ctx) CacheScope
        #broadcastListChanged()
    }
    class AbstractSyncResourceRepository {
        <<abstract>>
        #lookup(ctx, uri)* SyncResourceSpecification
        #specifications(ctx)* List
        #templateSpecifications(ctx)* List
        #matchTemplate(ctx, uri) SyncResourceTemplateSpecification
        #broadcastListChanged()
        #broadcastResourceUpdated(uri)
    }
    class AbstractSyncPromptRepository {
        <<abstract>>
        #lookup(ctx, name)* SyncPromptSpecification
        #specifications(ctx)* List
        #broadcastListChanged()
    }
    class RepositoryOptions {
        <<record>>
        +McpChangeBroadcaster changeBroadcaster
        +Integer pageSize
        +Long ttlMs
        +CacheScope cacheScope
    }

    class InMemorySyncToolRepository {
        +add(SyncToolSpecification)
        +remove(name) boolean
        +tools() List~Tool~
        +builder()$ Builder
    }
    class InMemorySyncResourceRepository {
        +add(SyncResourceSpecification)
        +add(SyncResourceTemplateSpecification)
        +remove(uri) boolean
        +resourceUpdated(uri)
        +builder()$ Builder
    }
    class InMemorySyncPromptRepository {
        +add(SyncPromptSpecification)
        +remove(name) boolean
        +builder()$ Builder
    }
    class InMemorySyncCompletionRepository {
        +add(SyncCompletionSpecification)
        +builder()$ Builder
    }

    class SyncToolSpecification {
        <<record>>
        +Tool tool
        +Handler handler
    }
    class SyncResourceSpecification {
        <<record>>
        +Resource resource
        +Handler handler
    }
    class SyncResourceTemplateSpecification {
        <<record>>
        +ResourceTemplate resourceTemplate
        +SyncResourceSpecification.Handler handler
    }
    class SyncPromptSpecification {
        <<record>>
        +Prompt prompt
        +Handler handler
    }
    class SyncCompletionSpecification {
        <<record>>
        +CompleteReference reference
        +Handler handler
    }

    McpSyncToolRepository <|.. AbstractSyncToolRepository
    McpSyncResourceRepository <|.. AbstractSyncResourceRepository
    McpSyncPromptRepository <|.. AbstractSyncPromptRepository
    AbstractSyncToolRepository <|-- InMemorySyncToolRepository
    AbstractSyncResourceRepository <|-- InMemorySyncResourceRepository
    AbstractSyncPromptRepository <|-- InMemorySyncPromptRepository
    McpSyncCompletionRepository <|.. InMemorySyncCompletionRepository
    AbstractSyncToolRepository --> RepositoryOptions
    AbstractSyncResourceRepository --> RepositoryOptions
    AbstractSyncPromptRepository --> RepositoryOptions

    InMemorySyncToolRepository o-- "*" SyncToolSpecification
    InMemorySyncResourceRepository o-- "*" SyncResourceSpecification
    InMemorySyncResourceRepository o-- "*" SyncResourceTemplateSpecification
    InMemorySyncPromptRepository o-- "*" SyncPromptSpecification
    InMemorySyncCompletionRepository o-- "*" SyncCompletionSpecification
```

The tool, resource and prompt repositories come in two layers. `AbstractSync{Tool,Resource,Prompt}Repository` implement the SDK interface on top of `Sync*Specification`s: they provide `*/list` paging with opaque key cursors (`RepositoryOptions.pageSize`, unset = one page and any cursor is `-32602`), the `ttlMs`/`cacheScope` list cache hints (overridable per request via `listTtlMs(ctx)`/`listCacheScope(ctx)`), dispatch to the specification handler, URI template matching for resources (`matchTemplate`, matchers compiled once per template) and the `broadcastListChanged()`/`broadcastResourceUpdated(uri)` helpers. The only abstract methods are `lookup(ctx, key)` and `specifications(ctx)` (plus `templateSpecifications(ctx)` for resources); both receive the `McpRequestContext`, so a custom repository can be tenant- or role-aware by filtering on the transport context.

`InMemorySync*Repository` are the map-backed, final subclasses used by the auto-configuration: every caller sees everything, and `add`/`remove`/`resourceUpdated(uri)` broadcast the matching change event. The completion repository has no abstract layer; its interface is a single method.

```java
public class TenantToolRepository extends AbstractSyncToolRepository {
    TenantToolRepository(McpChangeFeed feed) {
        super(RepositoryOptions.defaults().withChangeBroadcaster(feed).withPageSize(50));
    }
    @Override protected SyncToolSpecification lookup(McpRequestContext ctx, String name) { /* per tenant */ }
    @Override protected List<SyncToolSpecification> specifications(McpRequestContext ctx) { /* per tenant */ }
    @Override protected CacheScope listCacheScope(McpRequestContext ctx) { return CacheScope.PRIVATE; }
}
```

How the server consumes them, and the two helpers at the legacy/modern boundary:

```mermaid
classDiagram
    direction LR

    class McpServer {
        <<SDK>>
        +builder()$ Builder
        +handle(ctx, request) Mono~McpTransportResponse~
        +handleBlocking(ctx, request) Mono~McpTransportResponse~
        +closeGracefully()
    }
    class McpChangeFeed {
        <<SDK>>
        +broadcast(ServerChange)
        +changes() Flux~ServerChange~
    }
    class McpSyncToolRepository { <<SDK interface>> }
    class McpSyncResourceRepository { <<SDK interface>> }
    class McpSyncPromptRepository { <<SDK interface>> }
    class McpSyncCompletionRepository { <<SDK interface>> }

    class McpSchemaConverter {
        <<utility>>
        +toModern(legacyResult)$ modern result
        +toModern(legacyDescriptor)$ Tool, Resource, Prompt, ...
        +toModern(McpError)$ McpException
        +toLegacy(modernRequest)$ legacy request
    }
    class McpModernToolUtils {
        <<utility>>
        +toSyncToolSpecifications(List~ToolCallback~)$
        +toSyncToolSpecification(ToolCallback, MimeType)$
        +getMcpRequestContext(ToolContext)$ Optional
    }

    McpServer --> McpSyncToolRepository : ToolsFeature.ofSync
    McpServer --> McpSyncResourceRepository : ResourcesFeature.ofSync
    McpServer --> McpSyncPromptRepository : PromptsFeature.ofSync
    McpServer --> McpSyncCompletionRepository : CompletionsFeature.ofSync
    McpServer --> McpChangeFeed : subscriptions(feed)
    McpSyncToolRepository ..> McpChangeFeed : in-memory impls broadcast
    McpModernToolUtils ..> McpSchemaConverter
    McpModernToolUtils ..> McpSyncToolRepository : SyncToolSpecification per ToolCallback
```

Handler signatures, one per specification:

| Specification | `Handler` method |
|---|---|
| `SyncToolSpecification` | `McpSyncResponse<CallToolOutcome> call(McpRequestContext, CallToolRequest)` |
| `SyncResourceSpecification` | `McpSyncResponse<ReadResourceOutcome> read(McpRequestContext, ReadResourceRequest)` |
| `SyncPromptSpecification` | `McpSyncResponse<GetPromptOutcome> get(McpRequestContext, GetPromptRequest)` |
| `SyncCompletionSpecification` | `CompleteResult complete(McpRequestContext, CompleteRequest)` |

### 3.2 Annotation callbacks and providers

Each modern callback extends the existing abstract callback of its primitive to reuse argument binding and result conversion, and implements the matching specification `Handler`. The modern-only concerns live in `ModernMethodSupport`.

```mermaid
classDiagram
    direction TB

    class ModernMethodSupport {
        <<utility>>
        +isStreamingParameter(Class)$ boolean
        +isContextParameter(Class)$ boolean
        +rejectLegacyContextParameter(Class, Method)$
        +injectContextArguments(args, params, ctx, inputResponses, requestState, notifier)$
        +translate(Throwable, primitive, Method, bean)$ McpException
    }

    class McpModernSyncRequestContext {
        <<interface>>
        +context() McpRequestContext
        +protocolVersion() String
        +clientCapabilities() ClientCapabilities
        +clientInfo() Implementation
        +requestMeta() Map
        +transportContext() McpTransportContext
        +progressToken() Object
        +isRetry() boolean
        +inputResponses() Map
        +inputResponse(key, type) Optional~T~
        +requestState() String
        +progressEnabled() boolean
        +progress(int)
        +progress(double, total, message)
        +notify(method, params)
    }
    class DefaultMcpModernSyncRequestContext {
        -McpRequestContext context
        -McpSyncNotifier notifier
        +builder()$ Builder
    }
    DefaultMcpModernSyncRequestContext ..|> McpModernSyncRequestContext

    class AbstractMcpToolMethodCallback~T, RC~ {
        #buildMethodArguments(ctx, args, legacyRequest) Object[]
        #convertValueToCallToolResult(Object) CallToolResult
        #isExchangeOrContextType(Class)* boolean
    }
    class AbstractSyncMcpToolMethodCallback~T, RC~ {
        #processResult(Object) CallToolResult
        #createSyncErrorResult(Exception) CallToolResult
    }
    class AbstractMcpResourceMethodCallback {
        #buildArgsWithUriVariables(...)
        #buildArgsWithoutUriVariables(...)
        #validateReturnType(Method)
    }
    class AbstractMcpPromptMethodCallback {
        #buildArgs(ctx, legacyRequest) Object[]
        #validateMethod(Method)
    }
    class AbstractMcpCompleteMethodCallback {
        #buildArgs(ctx, legacyRequest) Object[]
    }

    class SyncModernMcpToolMethodCallback {
        -boolean streaming
        +call(ctx, CallToolRequest) McpSyncResponse~CallToolOutcome~
        +isModernInfrastructureParameter(Class)$ boolean
    }
    class SyncModernMcpResourceMethodCallback {
        -boolean streaming
        +read(ctx, ReadResourceRequest) McpSyncResponse~ReadResourceOutcome~
        +builder()$ Builder
    }
    class SyncModernMcpPromptMethodCallback {
        -boolean streaming
        +get(ctx, GetPromptRequest) McpSyncResponse~GetPromptOutcome~
    }
    class SyncModernMcpCompleteMethodCallback {
        +complete(ctx, CompleteRequest) CompleteResult
    }

    AbstractSyncMcpToolMethodCallback --|> AbstractMcpToolMethodCallback
    SyncModernMcpToolMethodCallback --|> AbstractSyncMcpToolMethodCallback
    SyncModernMcpResourceMethodCallback --|> AbstractMcpResourceMethodCallback
    SyncModernMcpPromptMethodCallback --|> AbstractMcpPromptMethodCallback
    SyncModernMcpCompleteMethodCallback --|> AbstractMcpCompleteMethodCallback

    SyncModernMcpToolMethodCallback ..|> SyncToolSpecification_Handler
    SyncModernMcpResourceMethodCallback ..|> SyncResourceSpecification_Handler
    SyncModernMcpPromptMethodCallback ..|> SyncPromptSpecification_Handler
    SyncModernMcpCompleteMethodCallback ..|> SyncCompletionSpecification_Handler

    class SyncToolSpecification_Handler { <<interface>> }
    class SyncResourceSpecification_Handler { <<interface>> }
    class SyncPromptSpecification_Handler { <<interface>> }
    class SyncCompletionSpecification_Handler { <<interface>> }

    SyncModernMcpToolMethodCallback ..> ModernMethodSupport
    SyncModernMcpResourceMethodCallback ..> ModernMethodSupport
    SyncModernMcpPromptMethodCallback ..> ModernMethodSupport
    SyncModernMcpCompleteMethodCallback ..> ModernMethodSupport
    ModernMethodSupport ..> DefaultMcpModernSyncRequestContext : builds
    ModernMethodSupport ..> McpSchemaConverter : McpError to McpException

    class SyncModernMcpToolProvider {
        +getToolSpecifications() List~SyncToolSpecification~
    }
    class SyncModernMcpResourceProvider {
        +getModernResourceSpecifications() List
        +getModernResourceTemplateSpecifications() List
    }
    class SyncModernMcpPromptProvider {
        +getModernPromptSpecifications() List
    }
    class SyncModernMcpCompleteProvider {
        +getModernCompleteSpecifications() List
    }
    class SyncMcpAnnotationProviders {
        <<utility>>
        +modernToolSpecifications(beans)$
        +modernResourceSpecifications(beans)$
        +modernResourceTemplateSpecifications(beans)$
        +modernPromptSpecifications(beans)$
        +modernCompleteSpecifications(beans)$
    }
    SyncMcpAnnotationProviders ..> SyncModernMcpToolProvider
    SyncMcpAnnotationProviders ..> SyncModernMcpResourceProvider
    SyncMcpAnnotationProviders ..> SyncModernMcpPromptProvider
    SyncMcpAnnotationProviders ..> SyncModernMcpCompleteProvider
    SyncModernMcpToolProvider ..> SyncModernMcpToolMethodCallback : creates
    SyncModernMcpResourceProvider ..> SyncModernMcpResourceMethodCallback : creates
    SyncModernMcpPromptProvider ..> SyncModernMcpPromptMethodCallback : creates
    SyncModernMcpCompleteProvider ..> SyncModernMcpCompleteMethodCallback : creates
```

Parameter and return types accepted by every modern callback, on top of the legacy ones:

| Primitive | Injected parameters | Pass-through return types |
|---|---|---|
| `@McpTool` | `McpModernSyncRequestContext`, `McpRequestContext`, `McpSyncNotifier`, `McpTransportContext`, modern `CallToolRequest` | `CallToolOutcome` (`CallToolResult`, `InputRequiredResult`) |
| `@McpResource` | same, plus modern `ReadResourceRequest` | `ReadResourceOutcome` (`ReadResourceResult` with `ttlMs`/`cacheScope`, `InputRequiredResult`) |
| `@McpPrompt` | same, plus modern `GetPromptRequest` | `GetPromptOutcome` (`GetPromptResult`, `InputRequiredResult`) |
| `@McpComplete` | `McpModernSyncRequestContext`, `McpRequestContext`, `McpTransportContext`, modern `CompleteRequest` (no notifier: completions are plain results) | `CompleteResult` |

`@McpResource` has two modern-only attributes, `ttlMs` (default `0`, not cacheable) and `cacheScope` (default `PRIVATE`). `SyncModernMcpResourceProvider` passes them to the callback, which stamps them on every *converted* legacy result. A method that returns a modern `ReadResourceResult` keeps its own hints and the attributes are ignored. The legacy providers (`SyncMcpResourceProvider` and friends) log a warning via `McpResourceCacheHints.warnIfIgnored` when the attributes are set, because the legacy `resources/read` result has no field for them.

```java
@McpResource(uri = "catalog://products", ttlMs = 60_000, cacheScope = CacheScope.PUBLIC)
String products() { ... }   // served with ttlMs=60000, cacheScope=public
```

Declaring `McpModernSyncRequestContext` or `McpSyncNotifier` makes the response streaming. The legacy `McpSyncServerExchange` and `McpSyncRequestContext` are rejected (callback) or skipped with a warning (provider), because there is no session to call back on.

### 3.3 Auto-configuration and transports

```mermaid
classDiagram
    direction TB

    class McpServerProperties {
        +protocol : STREAMABLE | STATELESS | MODERN
        +type : SYNC | ASYNC
        +name, version, instructions
        +capabilities.tool/resource/prompt/completion
    }
    class McpServerModernProperties {
        +mcpEndpoint = "/mcp"
        +supportedVersions
        +discoverTtl, discoverCacheScope
        +listTtl, listCacheScope
        +keepAliveInterval
        +maxRequestSize = 16MB
        +requestStateSecret, requestStateTtl
        +subscriptions, resourceSubscribe
    }

    class ModernServerSpecificationFactoryAutoConfiguration {
        +modernToolSpecs(ServerMcpAnnotatedBeans) List~SyncToolSpecification~
        +modernResourceSpecs(...) List~SyncResourceSpecification~
        +modernResourceTemplateSpecs(...) List
        +modernPromptSpecs(...) List~SyncPromptSpecification~
        +modernCompletionSpecs(...) List~SyncCompletionSpecification~
    }
    class ModernToolCallbackConverterAutoConfiguration {
        +modernSyncTools(ObjectProvider~List~ToolCallback~~) List~SyncToolSpecification~
    }
    class McpServerModernAutoConfiguration {
        +mcpChangeFeed() McpChangeFeed
        +mcpModernToolRepository(...) InMemorySyncToolRepository
        +mcpModernResourceRepository(...) InMemorySyncResourceRepository
        +mcpModernPromptRepository(...) InMemorySyncPromptRepository
        +mcpModernCompletionRepository(...) InMemorySyncCompletionRepository
        +mcpModernServer(...) McpServer
        +mcpModernStdioTransport(...) StdioMcpTransport
    }
    class McpModernServerCustomizer {
        <<interface>>
        +customize(McpServer.Builder)
    }
    class McpServerModernWebMvcAutoConfiguration {
        +webMvcModernServerTransport(McpServer, ...) WebMvcModernServerTransport
        +webMvcModernServerRouterFunction(transport) RouterFunction
        +webMvcModernServerTransportLifecycle(transport) SmartLifecycle
    }
    class McpServerModernWebFluxAutoConfiguration {
        +webFluxMcpTransport(McpServer, ...) WebFluxMcpTransport
        +webFluxModernServerRouterFunction(transport) RouterFunction
        +webFluxMcpTransportLifecycle(transport) SmartLifecycle
    }

    class WebMvcModernServerTransport {
        -McpRequestManager requestManager
        -int requestMaxSize
        -ServerHttpHeaderValidator httpHeaderValidator
        -McpTransportContextExtractor contextExtractor
        +getRouterFunction() RouterFunction
        +closeGracefully() Mono~Void~
        -handlePost(ServerRequest) ServerResponse
        -streamOnContainerThread(...)
        -streamOverAsyncContext(...)
    }
    class WebFluxMcpTransport {
        -McpRequestManager requestManager
        -int maxRequestSize
        -ServerHttpHeaderValidator httpHeaderValidator
        +getRouterFunction() RouterFunction
        +closeGracefully() void
        -handlePost(ServerRequest) Mono~ServerResponse~
        -events(Flux~JSONRPCMessage~) Flux~ServerSentEvent~
        -validateHeaders(request, JSONRPCRequest)$ String
    }
    class ModernTransportSupport {
        <<package-private, WebMvc module>>
        +validateHeaders(headers, JSONRPCRequest)$ String
        +statusFor(errorStatuses, JSONRPCResponse)$ int
        +frames(messages, keepAlive)$ Flux
        +headerAccessor(ServerRequest)$ HeaderAccessor
    }

    McpServerModernAutoConfiguration ..> McpServerProperties
    McpServerModernAutoConfiguration ..> McpServerModernProperties
    McpServerModernAutoConfiguration ..> McpModernServerCustomizer : applies all
    ModernServerSpecificationFactoryAutoConfiguration ..> SyncMcpAnnotationProviders
    ModernToolCallbackConverterAutoConfiguration ..> McpModernToolUtils
    ModernServerSpecificationFactoryAutoConfiguration --> McpServerModernAutoConfiguration : List beans feed in-memory repositories
    ModernToolCallbackConverterAutoConfiguration --> McpServerModernAutoConfiguration : List beans feed in-memory repositories
    McpServerModernAutoConfiguration --> McpServer : builds
    McpServerModernWebMvcAutoConfiguration --> WebMvcModernServerTransport : builds from McpServer
    McpServerModernWebFluxAutoConfiguration --> WebFluxMcpTransport : builds from McpServer
    WebMvcModernServerTransport ..> ModernTransportSupport
    WebMvcModernServerTransport --> McpServer : handleBlocking / handle
    WebFluxMcpTransport --> McpServer : handle
```

Conditions, in order of evaluation:

- All modern auto-configurations require `McpServer` on the classpath, `spring.ai.mcp.server.enabled` not `false`, and `protocol=MODERN`.
- `McpServerModernAutoConfiguration` asserts `type=SYNC` in its constructor and fails the context otherwise, instead of starting with no server.
- Each in-memory repository bean is `@ConditionalOnMissingBean` on the SDK interface for that primitive and on the matching `capabilities.*` property.
- The `McpServer` bean is `@ConditionalOnMissingBean`; the stdio transport is created only with `stdio=true`.

## 4. Sequence diagrams

### 4.1 Startup wiring

```mermaid
sequenceDiagram
    autonumber
    participant Boot as Spring Boot
    participant Scan as McpServerAnnotationScannerAutoConfiguration
    participant Spec as ModernServerSpecificationFactoryAutoConfiguration
    participant TC as ModernToolCallbackConverterAutoConfiguration
    participant AC as McpServerModernAutoConfiguration
    participant Repo as InMemorySync*Repository
    participant Server as McpServer (SDK)
    participant Web as McpServerModernWebMvc/WebFluxAutoConfiguration
    participant T as *ModernServerTransport

    Boot->>AC: new (McpServerProperties)
    AC->>AC: Assert type == SYNC
    Boot->>Scan: ServerMcpAnnotatedBeans
    Boot->>Spec: modern*Specs(annotatedBeans)
    Spec->>Spec: SyncMcpAnnotationProviders.modern*Specifications(...)
    Note over Spec: providers create SyncModernMcp*MethodCallback per method
    Boot->>TC: modernSyncTools(ToolCallbacks)
    TC->>TC: McpModernToolUtils.toSyncToolSpecifications(...)
    Boot->>AC: mcpChangeFeed()
    loop per primitive
        alt user defined McpSync*Repository bean
            Note over AC,Repo: in-memory bean backs off, specification lists are ignored for that primitive
        else
            Boot->>AC: mcpModern*Repository(List<Sync*Specification>..., changeFeed, props)
            AC->>Repo: builder().specs(all lists).changeBroadcaster(feed).cacheHints(listTtl, scope).build()
        end
    end
    Boot->>AC: mcpModernServer(jsonMapper, props, ObjectProvider<McpSync*Repository>..., changeFeed, codec, customizers)
    AC->>Server: builder().serverInfo().instructions().supportedVersions().jsonMapper().discoverCache()
    AC->>Server: feature(ToolsFeature.ofSync(toolRepo, mapper, validator, ttl, scope))
    AC->>Server: feature(ResourcesFeature/PromptsFeature/CompletionsFeature.ofSync(...))
    AC->>Server: subscriptions(changeFeed) if enabled
    AC->>Server: requestStateCodec(HmacRequestStateCodec) if secret configured
    AC->>Server: customizers.forEach(c -> c.customize(builder))
    AC-->>Boot: McpServer
    Boot->>Web: webMvc/webFluxModernServerTransport(mcpServer, headerValidator?, contextExtractor?)
    Web->>T: builder(server).messageEndpoint().maxRequestSize().keepAliveInterval()...build()
    Boot->>Web: RouterFunction bean (POST /mcp), SmartLifecycle (closeGracefully on stop)
```

### 4.2 `tools/call` with progress (WebMvc)

```mermaid
sequenceDiagram
    autonumber
    participant C as MCP client
    participant T as WebMvcModernServerTransport
    participant S as McpServer (SDK)
    participant R as InMemorySyncToolRepository
    participant CB as SyncModernMcpToolMethodCallback
    participant M as @McpTool method

    C->>T: POST /mcp  Mcp-Method: tools/call  Mcp-Name: index  {jsonrpc, id, method, params{_meta, name, arguments}}
    T->>T: validate Origin/Host, Content-Type, bounded body read (<= maxRequestSize)
    T->>T: deserialize, ModernTransportSupport.validateHeaders(headers vs body)
    T->>S: handleBlocking(transportContext, request)
    S->>S: resolve _meta (protocol version, client capabilities), validate arguments against input schema
    S->>R: call(ctx, CallToolRequest)
    R->>CB: handler.call(ctx, request)
    CB-->>R: McpSyncResponse.streaming(notifier -> invoke(...))
    R-->>S: streaming response
    S-->>T: McpTransportResponse.Streaming(messages)
    T->>C: 200 text/event-stream
    T->>CB: subscribe on container thread -> invoke(ctx, request, notifier)
    CB->>CB: buildMethodArguments(ctx, toolArguments, toLegacy(request))
    CB->>CB: ModernMethodSupport.injectContextArguments(args, params, ctx, inputResponses, requestState, notifier)
    CB->>M: invoke(McpModernSyncRequestContext ctx, paths)
    loop per unit of work
        M->>CB: ctx.progress(n)
        CB-->>T: notifications/progress frame
        T-->>C: event: message  data: {method: notifications/progress, params{progressToken, progress}}
    end
    M-->>CB: "Indexed 3 paths"
    CB->>CB: processResult -> legacy CallToolResult -> McpSchemaConverter.toModern(...)
    CB-->>T: final CallToolResult frame
    T-->>C: event: message  data: {id, result{content[], resultType: complete}}
    T-->>C: stream complete
```

A method without a streaming parameter follows the same path but answers `McpSyncResponse.result(...)` and the transport writes a single `application/json` response.

### 4.3 Multi round-trip request (resource read)

```mermaid
sequenceDiagram
    autonumber
    participant C as MCP client
    participant T as *ModernServerTransport
    participant S as McpServer (SDK)
    participant R as InMemorySyncResourceRepository
    participant CB as SyncModernMcpResourceMethodCallback
    participant M as @McpResource method

    C->>T: POST resources/read {uri: "test://gated"}
    T->>S: handle(ctx, request)
    S->>R: read(ctx, ReadResourceRequest)
    R->>CB: read(ctx, request)
    CB->>M: readGated(ctx, uri)
    M->>CB: ctx.inputResponse("pin", Map.class) -> empty
    M-->>CB: InputRequiredResult.builder().elicit("pin", form).requestState("pin-asked").build()
    CB-->>S: ReadResourceOutcome (InputRequiredResult passes through)
    S->>S: seal requestState with RequestStateCodec (HMAC, ttl)
    S-->>T: Result {resultType: input_required, inputRequests[], requestState: <sealed>}
    T-->>C: 200 application/json

    C->>C: collect the answer from the user
    C->>T: POST resources/read {uri, inputResponses{pin: {...}}, requestState: <sealed>}
    T->>S: handle(ctx, request)
    S->>S: verify and unseal requestState (reject if tampered or expired)
    S->>R: read(ctx, request with inputResponses + unsealed state)
    R->>CB: read(ctx, request)
    CB->>M: readGated(ctx, uri)
    M->>CB: ctx.inputResponse("pin", Map.class) -> present, ctx.requestState() == "pin-asked"
    M-->>CB: ReadResourceResult(contents, ttlMs, cacheScope)
    CB-->>S: ReadResourceOutcome
    S-->>T: Result {resultType: complete, contents[], ttlMs, cacheScope}
    T-->>C: 200 application/json
```

The same flow applies to `tools/call` (`CallToolOutcome`) and `prompts/get` (`GetPromptOutcome`). The SDK only accepts `InputRequiredResult` from features that declare MRTR support, which the Spring AI features do.

### 4.4 `subscriptions/listen` and a runtime change

```mermaid
sequenceDiagram
    autonumber
    participant C as MCP client
    participant T as *ModernServerTransport
    participant S as McpServer (SDK)
    participant F as McpChangeFeed
    participant App as Application code
    participant R as InMemorySyncToolRepository

    C->>T: POST /mcp  Mcp-Method: subscriptions/listen
    T->>S: handle(ctx, request)  (async path, never blocks the container thread)
    S->>F: changes()
    S-->>T: Streaming(ack + change notifications)
    T-->>C: 200 text/event-stream, acknowledgement frame
    loop keep-alive interval
        T-->>C: ": " comment frame
    end
    App->>R: add(new SyncToolSpecification(tool, handler))
    R->>F: broadcast(new ServerChange.ToolsListChanged())
    F-->>S: ServerChange
    S-->>T: notifications/tools/list_changed frame
    T-->>C: event: message  data: {method: notifications/tools/list_changed, params{_meta.subscriptionId}}
    C->>T: POST tools/list (cache invalidated)
    Note over T,S: on shutdown closeGracefully() ends every listen stream and new POSTs get 503
```

`InMemorySyncResourceRepository.resourceUpdated(uri)` broadcasts `notifications/resources/updated` the same way, when `resource-subscribe=true` advertises the capability.

## 5. Activity diagrams

### 5.1 Transport request handling (WebMvc and WebFlux)

```mermaid
flowchart TD
    A([POST /mcp]) --> B{closing?}
    B -- yes --> B1[503 Service Unavailable]
    B -- no --> C[ServerHttpHeaderValidator.validate Origin/Host]
    C -- rejected --> C1[status from exception, e.g. 403]
    C -- ok --> D{Content-Type is JSON?}
    D -- no --> D1[415 Unsupported Media Type]
    D -- yes --> E{Content-Length > maxRequestSize?}
    E -- yes --> E1[413 Payload Too Large]
    E -- no --> F[extract McpTransportContext]
    F --> G[read body in bounded chunks]
    G -- exceeds maxRequestSize --> E1
    G -- ok --> H[JsonRpc.deserializeMessage]
    H -- IOException --> H1[JSON-RPC -32700 Parse error]
    H -- InvalidMessageException --> H2[JSON-RPC -32600 Invalid request]
    H -- notification --> I[requestManager.handleNotification] --> I1[202 Accepted]
    H -- request --> J[validate headers\nMcp-Method, Mcp-Name, MCP-Protocol-Version vs body]
    J -- mismatch --> J1[JSON-RPC -32020 Header mismatch]
    J -- ok --> K{method == subscriptions/listen?}
    K -- yes --> L[requestManager.handle - async]
    K -- no --> M[WebMvc: handleBlocking on container thread\nWebFlux: handle]
    L --> N{McpTransportResponse}
    M --> N
    N -- Result --> N1[200 application/json]
    N -- Error --> N2[status from error code table\n-32602 to 400, -32601 to 404, ...]
    N -- Streaming --> O[200 text/event-stream\nframes + keep-alive comments]
    O --> O1[WebMvc listen: async context + boundedElastic\nWebMvc others: block on container thread\nWebFlux: Flux of ServerSentEvent]
```

### 5.2 Modern method callback invocation

```mermaid
flowchart TD
    A([Handler.call / read / get / complete]) --> B{method declares\nMcpModernSyncRequestContext\nor McpSyncNotifier?}
    B -- yes, not completion --> C[McpSyncResponse.streaming\nnotifier available]
    B -- no --> D[McpSyncResponse.result\nnotifier = null]
    C --> E
    D --> E
    E[convert modern request to legacy\nMcpSchemaConverter.toLegacy] --> F[base class binds arguments\nby parameter name, URI variables,\n@McpProgressToken, McpMeta,\nlegacy request type]
    F --> G[ModernMethodSupport.injectContextArguments\nMcpModernSyncRequestContext, McpSyncNotifier,\nMcpRequestContext, McpTransportContext]
    G --> H[fill the modern request slot\nCallToolRequest / ReadResourceRequest /\nGetPromptRequest / CompleteRequest]
    H --> I[invoke the method reflectively]
    I -- returns --> J{result type}
    J -- modern *Outcome / CompleteResult --> K[pass through untouched]
    J -- legacy McpSchema result,\nString, POJO, void --> L[base class result converter\nthen McpSchemaConverter.toModern]
    K --> Z([response])
    L --> Z
    I -- throws --> M{exception type}
    M -- McpException --> M1[rethrow as is]
    M -- McpError legacy --> M2[McpSchemaConverter.toModern\nsame code and message]
    M -- anything else, tool --> M3[CallToolResult isError=true\nwith the message]
    M -- anything else, resource/prompt/completion --> M4[log warn with stack trace\nMcpException -32603 Internal error]
```

Validation at construction time, per callback: at most one parameter of each injected type, legacy `McpSyncServerExchange`/`McpSyncRequestContext` rejected with a message naming the replacement, `McpSyncNotifier` rejected on completion methods, return type must be a legacy type or the primitive's modern `*Outcome`.

### 5.3 Repository selection at auto-configuration

```mermaid
flowchart TD
    A([protocol = MODERN, type = SYNC]) --> B{capabilities.tool enabled?}
    B -- no --> B1[no tools feature on the server]
    B -- yes --> C{user bean implementing\nMcpSyncToolRepository present?}
    C -- yes --> D[in-memory bean backs off\nList of SyncToolSpecification beans are not collected\nuser repository is the single source of truth]
    C -- no --> E[InMemorySyncToolRepository bean\nfed by every List of SyncToolSpecification bean\nannotated methods + ToolCallbacks]
    D --> F[mcpModernServer: ObjectProvider.ifAvailable\nToolsFeature.ofSync repository, mapper, validator, listTtl, listCacheScope]
    E --> F
    F --> G{two candidates of the same type?}
    G -- yes --> G1[NoUniqueBeanDefinitionException at startup]
    G -- no --> H([server built])
    H -.-> I[same decision for resources, prompts, completions]
```

## 6. Properties

| Property | Default | Purpose |
|---|---|---|
| `spring.ai.mcp.server.protocol` | `STREAMABLE` | `MODERN` selects this integration |
| `spring.ai.mcp.server.type` | `SYNC` | Only `SYNC` is supported; `ASYNC` fails startup |
| `spring.ai.mcp.server.modern.mcp-endpoint` | `/mcp` | POST endpoint |
| `spring.ai.mcp.server.modern.supported-versions` | `2026-07-28` | Versions accepted in `_meta` and advertised by `server/discover` |
| `spring.ai.mcp.server.modern.discover-ttl` / `discover-cache-scope` | `0` / `PRIVATE` | `server/discover` cache hints |
| `spring.ai.mcp.server.modern.list-ttl` / `list-cache-scope` | `0` / `PRIVATE` | `*/list` cache hints |
| `spring.ai.mcp.server.modern.page-size` | unset | Entries per `*/list` page of the in-memory repositories; unset = single page |
| `spring.ai.mcp.server.modern.keep-alive-interval` | `30s` | SSE comment interval on streams |
| `spring.ai.mcp.server.modern.max-request-size` | `16MB` | Enforced by the transports while reading the body |
| `spring.ai.mcp.server.modern.request-state-secret` / `request-state-ttl` | unset / SDK default | HMAC sealing of MRTR `requestState`; set the secret on every instance behind a load balancer |
| `spring.ai.mcp.server.modern.subscriptions` | `true` | Serve `subscriptions/listen` |
| `spring.ai.mcp.server.modern.resource-subscribe` | `false` | Advertise `resources.subscribe` |

## 7. Extension points

| Bean type | Effect |
|---|---|
| `McpSyncToolRepository`, `McpSyncResourceRepository`, `McpSyncPromptRepository`, `McpSyncCompletionRepository` | Replaces the in-memory repository for that primitive |
| `McpModernServerCustomizer` | Receives the `McpServer.Builder` before `build()` |
| `RequestStateCodec` | Replaces the HMAC codec built from the properties |
| `ServerHttpHeaderValidator` | Origin and Host validation on every POST |
| `McpTransportContextExtractor<ServerRequest>` | Derives the `McpTransportContext` handlers see |
| `List<SyncToolSpecification>` etc. | Extra specifications merged into the in-memory repositories |
| `InMemorySync*Repository` injection | Runtime `add`/`remove`/`resourceUpdated`, broadcast to listeners |

## 8. Known trade-offs

- The WebMvc module keeps a package-private `ModernTransportSupport` copy of private helpers of the SDK's `HttpServletMcpTransport`; the WebFlux transport (`WebFluxMcpTransport`, from Dariusz Jędrzejczyk's `modern-webflux-transport` branch) is self-contained and duplicates the same header-validation logic. Both are meant to be replaced by an SDK-provided helper.
- `McpSchemaConverter` uses `McpJsonDefaults.getMapper()` rather than the configured `mcpServerJsonMapper` bean.
- The integration builds against the SDK `2.2.0-SNAPSHOT`; release is gated on the SDK release.
- `ASYNC` API type and an MRTR helper DSL are not implemented yet.
