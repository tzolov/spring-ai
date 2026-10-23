# Proposed MCP Java SDK changes for modern server transports

Status date: 2026-10-23, SDK `2.2.0-SNAPSHOT`.
Based on three transports: the SDK's `HttpServletMcpTransport`, Spring AI's `WebMvcModernServerTransport`, and `WebFluxMcpTransport`.

## Problem

Each transport re-implements the same protocol logic from private members of the servlet transport: header consistency checks (`Mcp-Method`, `MCP-Protocol-Version`, `Mcp-Name` with base64), the error-code to HTTP-status table, keep-alive framing, the `HeaderAccessor` adapter, the `instanceof McpServer` shutdown hook, and the `subscriptions/listen` special case.
About 350 lines are duplicated across the two Spring transports, and the review-found gaps (missing 415, missing early flush, missing catch-all 405) were all in copied code.

## Proposals

| # | Change | Why |
|---|---|---|
| 1 | Public `HttpTransportSupport`: `validateHeaders`, `decodeMcpNameHeader`, `protocolVersionFromMeta`, `httpStatusFor`, `isJsonContentType`, header constants, `DEFAULT_ERROR_STATUSES` | removes ~120 lines per transport; one place for spec changes |
| 2 | `SseFrame` record + `SseFrames.frames(messages, mapper, keepAlive, leadingComment)` | the keep-alive merge is the subtlest copied code; leading comment becomes the default |
| 3 | Move `ServerHttpHeaderValidator`, `HeaderAccessor`, `DefaultServerTransportSecurityValidator`, `McpTransportContextExtractor` out of the legacy packages; add `HeaderAccessor.of(values, names)` | modern transports stop importing legacy API and writing anonymous adapters |
| 4 | `McpTransportResponse.Streaming.longLived()` set by the server, or `McpRequestManager.isLongLived(request)` | transports stop hard-coding `subscriptions/listen` to choose `handle()` over `handleBlocking()` |
| 5 | `default void closeGracefully()` on `McpRequestManager` | no downcast to `McpServer`; decorators keep shutdown working |
| 6 | Implement `notifications/cancelled` in `McpServer`, or drop the javadoc claim that transports handle it | no transport keeps in-flight bookkeeping today |
| 7 | `McpRequestContext` factory plus modern request builders and an in-memory tool repository in `mcp-test` | repository and callback tests without a full server; `mcp-test` has no modern fixtures |
| 8 | Document that the modern server ignores `McpTransportContext.KEY` in the Reactor context | removes dead `contextWrite` calls copied from legacy transports |
| 9 | Ship `Sync*Specification` records, in-memory and abstract specification-backed repositories | a runnable modern server without framework code; Spring AI's copies are already SDK-shaped |
| 10 | Transport contract in the docs plus an abstract conformance test in `mcp-test` | keeps future ports complete; the WebFlux unit tests are a ready seed |

## Order

1. Items 1, 2, 3, 5 together, additive, with the servlet transport refactored onto them.
2. Items 4 and 6, since they change `McpRequestManager` semantics.
3. Items 7 and 10.
4. Item 9 as its own feature; item 8 anytime.

## General asks beyond transports

| # | Change | Why |
|---|---|---|
| 11 | Let a feature opt in to the `logging` server capability in `ServerCapabilities.Builder.build()` (today hard-coded to null, "not offered by modern servers"), and reject an unrecognised `_meta.io.modelcontextprotocol/logLevel` with `-32602` in `McpServer` | the 2026-07-28 logging page says a server that emits `notifications/message` MUST declare `logging` and SHOULD reject bad levels; Spring AI's per-request `log*` API on `McpModernSyncRequestContext` emits correctly but cannot advertise, so spec-following clients never ask for logs |

Logging is deprecated by SEP-2577, so a deliberate "no" is a valid answer; then Spring AI drops or hides its `log*` API.

## What remains transport-specific

Body reading under a size limit with charset, mapping `SseFrame` to the framework's writer and thread model, adapting the request to `HeaderAccessor` and the context extractor, and the routing table.

See [MODERN-INTEGRATION-PLAN.md](MODERN-INTEGRATION-PLAN.md) §3.2 #6 for tracking; the side-by-side comparison of the two WebFlux transports is kept locally and not committed.
