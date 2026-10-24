# MCP 2026-07-28 ("modern") server integration

This folder contains the design documents for the Spring AI support of the MCP 2026-07-28 ("modern") server.
Each document answers one question, and new content goes to the document that owns that question.

| Document | Question it answers | Add content here when |
|---|---|---|
| [MODERN.md](MODERN.md) | How is it built? Design, diagrams, specification coverage, properties, extension points and repository customisation | the code changes shape |
| [MODERN-INTEGRATION-PLAN.md](MODERN-INTEGRATION-PLAN.md) | What is delivered, what is open, and what was decided and why? Backlog with the design of the open items as appendices, SDK proposals, trade-offs and verification | work is planned, finished, rejected or traded off |
| [MODERN-MIGRATION-PATHS.md](MODERN-MIGRATION-PATHS.md) | How does an application developer move an existing server to MODERN? | a user-visible behaviour or property changes |

`WEBFLUX-MODERN-TRANSPORT-COMPARISON.md` is a local, git-ignored review that documents the adoption of `WebFluxMcpTransport`.
The reference documentation lives under `spring-ai-docs` (`mcp-modern-server-boot-starter-docs.adoc` and `mcp-annotations-server.adoc`); its property and extension-point tables mirror MODERN.md sections 6 and 7.
