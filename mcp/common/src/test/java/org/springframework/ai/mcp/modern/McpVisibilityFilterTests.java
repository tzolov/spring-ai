/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.mcp.modern;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.GetPromptResult;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.McpSchema.PromptMessage;
import io.modelcontextprotocol.modern.McpSchema.PromptReference;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.McpSchema.Resource;
import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.McpSchema.Role;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.TextResourceContents;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.McpTransportResponse;
import io.modelcontextprotocol.modern.server.feature.CompletionsFeature;
import io.modelcontextprotocol.modern.server.feature.PromptsFeature;
import io.modelcontextprotocol.modern.server.feature.ResourcesFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link McpVisibilityFilter} on the in-memory repositories and the
 * {@link McpTransportContexts} header convention it is usually driven by.
 *
 * @author Christian Tzolov
 */
class McpVisibilityFilterTests {

	private static final Map<String, Object> EMPTY_SCHEMA = Map.of("type", "object", "properties", Map.of());

	private static final McpTransportContext ADMIN = McpTransportContexts
		.fromHeaders(each -> each.accept("X-Role", List.of("admin")));

	private static final McpTransportContext GUEST = McpTransportContexts
		.fromHeaders(each -> each.accept("X-Role", List.of("guest")));

	private static final McpVisibilityFilter ADMIN_OR_PUBLIC = new McpVisibilityFilter() {
		@Override
		public boolean tool(McpRequestContext ctx, Tool tool) {
			return allowed(ctx, tool.meta());
		}

		@Override
		public boolean resource(McpRequestContext ctx, Resource resource) {
			return allowed(ctx, resource.meta());
		}

		@Override
		public boolean resourceTemplate(McpRequestContext ctx, ResourceTemplate template) {
			return allowed(ctx, template.meta());
		}

		@Override
		public boolean prompt(McpRequestContext ctx, Prompt prompt) {
			return allowed(ctx, prompt.meta());
		}

		private boolean allowed(McpRequestContext ctx, Map<String, Object> meta) {
			return "admin".equals(McpTransportContexts.header(ctx, "x-role"))
					|| (meta != null && "public".equals(meta.get("visibility")));
		}
	};

	private static final Map<String, Object> PUBLIC = Map.of("visibility", "public");

	private static Map<String, Object> params(Object... keyValues) {
		Map<String, Object> params = new HashMap<>();
		Map<String, Object> meta = new HashMap<>();
		meta.put(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION);
		meta.put(MetaKeys.CLIENT_CAPABILITIES, Map.of());
		params.put("_meta", meta);
		for (int i = 0; i < keyValues.length; i += 2) {
			params.put((String) keyValues[i], keyValues[i + 1]);
		}
		return params;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> call(McpServer server, McpTransportContext transportContext, String method,
			Map<String, Object> params) {
		McpTransportResponse response = server.handleBlocking(transportContext, new JSONRPCRequest(method, 1, params))
			.block();
		JSONRPCResponse jsonRpc = response instanceof McpTransportResponse.Result result ? result.response()
				: ((McpTransportResponse.Error) response).response();
		if (jsonRpc.error() != null) {
			return Map.of("code", jsonRpc.error().code(), "message", jsonRpc.error().message());
		}
		return (Map<String, Object>) jsonRpc.result();
	}

	@SuppressWarnings("unchecked")
	private static List<String> names(Map<String, Object> page, String field, String nameField) {
		return ((List<Map<String, Object>>) page.get(field)).stream().map(m -> (String) m.get(nameField)).toList();
	}

	private static SyncToolSpecification tool(String name, Map<String, Object> meta) {
		return new SyncToolSpecification(Tool.builder(name, EMPTY_SCHEMA).meta(meta).build(),
				(ctx, request) -> McpSyncResponse
					.result(CallToolResult.builder().addContent(TextContent.builder("from " + name).build()).build()));
	}

	private static SyncResourceSpecification resource(String uri, Map<String, Object> meta) {
		return new SyncResourceSpecification(Resource.builder(uri, uri).meta(meta).build(),
				(ctx, request) -> McpSyncResponse.result(ReadResourceResult
					.builder(List.of(TextResourceContents.builder(request.uri(), "body of " + uri).build()))
					.build()));
	}

	private static SyncResourceTemplateSpecification template(String uriTemplate, Map<String, Object> meta) {
		return new SyncResourceTemplateSpecification(
				ResourceTemplate.builder(uriTemplate, uriTemplate).meta(meta).build(),
				(ctx, request) -> McpSyncResponse.result(ReadResourceResult
					.builder(List.of(TextResourceContents.builder(request.uri(), "via " + uriTemplate).build()))
					.build()));
	}

	private static SyncPromptSpecification prompt(String name, Map<String, Object> meta) {
		return new SyncPromptSpecification(Prompt.builder(name).meta(meta).build(),
				(ctx, request) -> McpSyncResponse.result(GetPromptResult
					.builder(List.of(PromptMessage.builder(Role.USER, TextContent.builder(name).build()).build()))
					.build()));
	}

	@Test
	void hiddenToolsAreNeitherListedNorCallableAndListingsArePrivate() {
		InMemorySyncToolRepository repository = InMemorySyncToolRepository.builder()
			.tools(List.of(tool("admin-report", Map.of()), tool("weather", PUBLIC)))
			.visibility(ADMIN_OR_PUBLIC)
			.build();
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("test", "1").build())
			.feature(ToolsFeature.ofSync(repository))
			.build();

		Map<String, Object> guestList = call(server, GUEST, McpSchema.METHOD_TOOLS_LIST, params());
		assertThat(names(guestList, "tools", "name")).containsExactly("weather");
		assertThat(guestList.get("cacheScope")).isEqualTo("private");
		assertThat(names(call(server, ADMIN, McpSchema.METHOD_TOOLS_LIST, params()), "tools", "name"))
			.containsExactly("admin-report", "weather");

		Map<String, Object> denied = call(server, GUEST, McpSchema.METHOD_TOOLS_CALL,
				params("name", "admin-report", "arguments", Map.of()));
		assertThat(denied.get("code")).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
		assertThat((String) denied.get("message")).contains("Unknown tool");
		assertThat(
				call(server, ADMIN, McpSchema.METHOD_TOOLS_CALL, params("name", "admin-report", "arguments", Map.of())))
			.containsKey("content");
		// The administrative view is unfiltered.
		assertThat(repository.tools()).hasSize(2);
	}

	@Test
	void unfilteredRepositoriesLeaveTheCacheScopeToTheFeature() {
		InMemorySyncToolRepository repository = InMemorySyncToolRepository.builder()
			.tool(tool("weather", Map.of()))
			.build();
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("test", "1").build())
			.feature(ToolsFeature.ofSync(repository, McpJsonDefaults.getMapper(), McpJsonDefaults.getSchemaValidator(),
					0L, CacheScope.PUBLIC))
			.build();
		// The repository leaves the scope null, so the feature default wins; a filter
		// would have forced "private".
		assertThat(call(server, GUEST, McpSchema.METHOD_TOOLS_LIST, params()).get("cacheScope")).isEqualTo("public");
	}

	@Test
	void hiddenResourcesAndTemplatesAreNeitherListedNorReadable() {
		InMemorySyncResourceRepository repository = InMemorySyncResourceRepository.builder()
			.resources(List.of(resource("secret://plan", Map.of()), resource("public://faq", PUBLIC)))
			.resourceTemplates(List.of(template("secret://{id}", Map.of()), template("public://{id}", PUBLIC)))
			.visibility(ADMIN_OR_PUBLIC)
			.build();
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("test", "1").build())
			.feature(ResourcesFeature.ofSync(repository))
			.build();

		assertThat(names(call(server, GUEST, McpSchema.METHOD_RESOURCES_LIST, params()), "resources", "uri"))
			.containsExactly("public://faq");
		assertThat(names(call(server, GUEST, McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, params()), "resourceTemplates",
				"uriTemplate"))
			.containsExactly("public://{id}");
		// The hidden static resource does not fall through to the hidden template either.
		assertThat(call(server, GUEST, McpSchema.METHOD_RESOURCES_READ, params("uri", "secret://plan")).get("code"))
			.isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
		assertThat(call(server, GUEST, McpSchema.METHOD_RESOURCES_READ, params("uri", "secret://other")).get("code"))
			.isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
		assertThat(call(server, GUEST, McpSchema.METHOD_RESOURCES_READ, params("uri", "public://other")))
			.containsKey("contents");
		assertThat(call(server, ADMIN, McpSchema.METHOD_RESOURCES_READ, params("uri", "secret://plan")))
			.containsKey("contents");
	}

	@Test
	void hiddenResourceIsNotServedByAMatchingVisibleTemplate() {
		InMemorySyncResourceRepository repository = InMemorySyncResourceRepository.builder()
			.resource(resource("docs://secret", Map.of()))
			.resourceTemplate(template("docs://{path}", PUBLIC))
			.visibility(ADMIN_OR_PUBLIC)
			.build();
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("test", "1").build())
			.feature(ResourcesFeature.ofSync(repository))
			.build();

		// The catch-all template is visible, but the hidden exact resource must not fall
		// through to it.
		Map<String, Object> denied = call(server, GUEST, McpSchema.METHOD_RESOURCES_READ,
				params("uri", "docs://secret"));
		assertThat(denied.get("code")).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
		assertThat((String) denied.get("message")).contains("Unknown resource");
		assertThat(call(server, GUEST, McpSchema.METHOD_RESOURCES_READ, params("uri", "docs://other")))
			.containsKey("contents");
		assertThat(call(server, ADMIN, McpSchema.METHOD_RESOURCES_READ, params("uri", "docs://secret")))
			.containsKey("contents");
	}

	@Test
	void completionReferenceGuardAnswersLikeAnUnknownReference() {
		InMemorySyncCompletionRepository repository = InMemorySyncCompletionRepository.builder()
			.completion(new SyncCompletionSpecification(new PromptReference("internal"),
					(ctx, request) -> CompleteResult.of(new CompleteResult.Completion(List.of("a")))))
			.referenceGuard((ctx, reference) -> "admin".equals(McpTransportContexts.header(ctx, "x-role")))
			.build();
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("test", "1").build())
			.feature(CompletionsFeature.ofSync(repository))
			.build();
		Map<String, Object> request = params("ref", Map.of("type", "ref/prompt", "name", "internal"), "argument",
				Map.of("name", "x", "value", ""));

		Map<String, Object> denied = call(server, GUEST, McpSchema.METHOD_COMPLETION_COMPLETE, request);
		assertThat(denied.get("code")).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
		assertThat((String) denied.get("message")).contains("No completion handler");
		assertThat(call(server, ADMIN, McpSchema.METHOD_COMPLETION_COMPLETE, request)).containsKey("completion");
	}

	@Test
	void abstractBaseAppliesTheConstructorFilter() {
		AbstractSyncToolRepository repository = new AbstractSyncToolRepository(null, null, ADMIN_OR_PUBLIC) {
			private final Map<String, SyncToolSpecification> tools = Map.of("admin-report",
					tool("admin-report", Map.of()), "weather", tool("weather", PUBLIC));

			@Override
			protected List<Tool> definitions(McpRequestContext ctx) {
				return this.tools.values().stream().map(SyncToolSpecification::tool).toList();
			}

			@Override
			protected SyncToolSpecification lookup(McpRequestContext ctx, String name) {
				return this.tools.get(name);
			}
		};
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("test", "1").build())
			.feature(ToolsFeature.ofSync(repository))
			.build();

		Map<String, Object> guestList = call(server, GUEST, McpSchema.METHOD_TOOLS_LIST, params());
		assertThat(names(guestList, "tools", "name")).containsExactly("weather");
		assertThat(guestList.get("cacheScope")).isEqualTo("private");
		assertThat(
				call(server, GUEST, McpSchema.METHOD_TOOLS_CALL, params("name", "admin-report", "arguments", Map.of()))
					.get("code"))
			.isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void hiddenPromptsAreNeitherListedNorGettable() {
		InMemorySyncPromptRepository repository = InMemorySyncPromptRepository.builder()
			.prompts(List.of(prompt("internal", Map.of()), prompt("greet", PUBLIC)))
			.visibility(ADMIN_OR_PUBLIC)
			.build();
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("test", "1").build())
			.feature(PromptsFeature.ofSync(repository))
			.build();

		assertThat(names(call(server, GUEST, McpSchema.METHOD_PROMPTS_LIST, params()), "prompts", "name"))
			.containsExactly("greet");
		assertThat(call(server, GUEST, McpSchema.METHOD_PROMPTS_GET, params("name", "internal")).get("code"))
			.isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
		assertThat(call(server, ADMIN, McpSchema.METHOD_PROMPTS_GET, params("name", "internal")))
			.containsKey("messages");
	}

	@Test
	void factoriesRestrictOnePrimitiveAndAllOfCombinesWithAnd() {
		McpVisibilityFilter noAdminTools = McpVisibilityFilter.tools((ctx, tool) -> !tool.name().startsWith("admin"));
		McpVisibilityFilter noSecretResources = McpVisibilityFilter
			.resources((ctx, resource) -> !resource.uri().startsWith("secret"));
		McpVisibilityFilter combined = McpVisibilityFilter.allOf(List.of(noAdminTools, noSecretResources));

		Tool adminTool = Tool.builder("admin-x", EMPTY_SCHEMA).build();
		Tool weather = Tool.builder("weather", EMPTY_SCHEMA).build();
		Resource secret = Resource.builder("secret://x", "x").build();
		assertThat(noAdminTools.tool(null, adminTool)).isFalse();
		assertThat(noAdminTools.resource(null, secret)).isTrue();
		assertThat(noAdminTools.prompt(null, Prompt.builder("p").build())).isTrue();
		assertThat(combined.tool(null, adminTool)).isFalse();
		assertThat(combined.tool(null, weather)).isTrue();
		assertThat(combined.resource(null, secret)).isFalse();
		assertThat(McpVisibilityFilter.allOf(List.of()).tool(null, adminTool)).isTrue();
		assertThat(McpVisibilityFilter.allOf(List.of(noAdminTools))).isSameAs(noAdminTools);
	}

	@Test
	void headerContextLowerCasesNamesAndDropsProtocolHeaders() {
		McpTransportContext context = McpTransportContexts.fromHeaders(each -> {
			each.accept("X-Role", List.of("admin"));
			each.accept("Authorization", List.of("Bearer t"));
			each.accept("Mcp-Method", List.of("tools/list"));
			each.accept("MCP-Protocol-Version", List.of("2026-07-28"));
			each.accept("Accept", List.of("application/json", "text/event-stream"));
		});

		assertThat(McpTransportContexts.headers(context)).containsOnlyKeys("x-role", "authorization", "accept");
		assertThat(McpTransportContexts.header(context, "X-ROLE")).isEqualTo("admin");
		assertThat(McpTransportContexts.header(context, "accept")).isEqualTo("application/json");
		assertThat(McpTransportContexts.header(context, "missing")).isNull();
		assertThat(McpTransportContexts.headers(McpTransportContext.EMPTY)).isEmpty();
		assertThat(McpTransportContexts.header(McpTransportContext.EMPTY, "x-role")).isNull();
	}

	@Test
	void headerContextMergesCasingCollisionsAndReadsSingleStringValues() {
		McpTransportContext merged = McpTransportContexts.fromHeaders(each -> {
			each.accept("X-Role", List.of("admin"));
			each.accept("x-role", List.of("auditor"));
		});
		assertThat(McpTransportContexts.headers(merged).get("x-role")).containsExactly("admin", "auditor");

		// A custom extractor that stores one string per header is read too.
		McpTransportContext custom = McpTransportContext
			.create(Map.of(McpTransportContexts.HEADERS, Map.of("X-Role", "admin", "odd", 42)));
		assertThat(McpTransportContexts.header(custom, "x-role")).isEqualTo("admin");
		assertThat(McpTransportContexts.headers(custom)).containsOnlyKeys("x-role");
		assertThat(McpTransportContexts.headers(McpTransportContext.create(Map.of(McpTransportContexts.HEADERS, "no"))))
			.isEmpty();
	}

}
