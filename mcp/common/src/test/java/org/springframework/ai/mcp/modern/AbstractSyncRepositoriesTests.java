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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.McpSchema.Resource;
import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.TextResourceContents;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.McpTransportResponse;
import io.modelcontextprotocol.modern.server.feature.ResourcesFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the paging, dispatch and per-caller visibility that the abstract repositories
 * provide, through a real {@link McpServer}.
 *
 * @author Christian Tzolov
 */
class AbstractSyncRepositoriesTests {

	private static final Map<String, Object> EMPTY_SCHEMA = Map.of("type", "object", "properties", Map.of());

	private static final String TENANT = "tenant";

	private static Map<String, Object> params(Object... keyValues) {
		Map<String, Object> params = new HashMap<>();
		Map<String, Object> meta = new HashMap<>();
		meta.put(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION);
		meta.put(MetaKeys.CLIENT_CAPABILITIES, Map.of());
		params.put("_meta", meta);
		for (int i = 0; i < keyValues.length; i += 2) {
			if (keyValues[i + 1] != null) {
				params.put((String) keyValues[i], keyValues[i + 1]);
			}
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
			Map<String, Object> error = new HashMap<>();
			error.put("code", jsonRpc.error().code());
			error.put("message", jsonRpc.error().message());
			return error;
		}
		return (Map<String, Object>) jsonRpc.result();
	}

	private static Map<String, Object> call(McpServer server, String method, Map<String, Object> params) {
		return call(server, McpTransportContext.EMPTY, method, params);
	}

	private static SyncToolSpecification tool(String name) {
		return new SyncToolSpecification(Tool.builder(name, EMPTY_SCHEMA).description(name).build(),
				(ctx, request) -> McpSyncResponse
					.result(CallToolResult.builder().addContent(TextContent.builder("from " + name).build()).build()));
	}

	private static SyncResourceTemplateSpecification template(String uriTemplate) {
		return new SyncResourceTemplateSpecification(ResourceTemplate.builder(uriTemplate, uriTemplate).build(),
				(ctx, request) -> McpSyncResponse.result(ReadResourceResult
					.builder(List.of(TextResourceContents.builder(request.uri(), "via " + uriTemplate).build()))
					.build()));
	}

	@SuppressWarnings("unchecked")
	private static List<String> names(Map<String, Object> page, String field, String nameField) {
		return ((List<Map<String, Object>>) page.get(field)).stream().map(m -> (String) m.get(nameField)).toList();
	}

	@Test
	void listIsPagedWithOpaqueKeyCursors() {
		InMemorySyncToolRepository repository = InMemorySyncToolRepository.builder()
			.tools(IntStream.rangeClosed(1, 5).mapToObj(i -> tool("tool" + i)).toList())
			.pageSize(2)
			.build();
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("test", "1").build())
			.feature(ToolsFeature.ofSync(repository, McpJsonDefaults.getMapper(), McpJsonDefaults.getSchemaValidator(),
					1000L, CacheScope.PUBLIC))
			.build();

		Map<String, Object> page1 = call(server, McpSchema.METHOD_TOOLS_LIST, params());
		assertThat(names(page1, "tools", "name")).containsExactly("tool1", "tool2");
		// The repository leaves the hints null, so the feature defaults apply.
		assertThat(page1.get("ttlMs")).isEqualTo(1000L);
		assertThat(page1.get("cacheScope")).isEqualTo("public");
		String cursor1 = (String) page1.get("nextCursor");
		assertThat(cursor1).isNotBlank().doesNotContain("tool2");

		// A removal between pages does not shift the window: paging is by key.
		repository.remove("tool1");
		Map<String, Object> page2 = call(server, McpSchema.METHOD_TOOLS_LIST, params("cursor", cursor1));
		assertThat(names(page2, "tools", "name")).containsExactly("tool3", "tool4");

		Map<String, Object> page3 = call(server, McpSchema.METHOD_TOOLS_LIST,
				params("cursor", page2.get("nextCursor")));
		assertThat(names(page3, "tools", "name")).containsExactly("tool5");
		assertThat(page3).doesNotContainKey("nextCursor");

		Map<String, Object> garbage = call(server, McpSchema.METHOD_TOOLS_LIST, params("cursor", "not-a-cursor"));
		assertThat(garbage.get("code")).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
		Map<String, Object> wrongPrefix = call(server, McpSchema.METHOD_TOOLS_LIST,
				params("cursor", Pagination.encode("x").replace("a", "b")));
		assertThat(wrongPrefix.get("code")).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void unboundedRepositoriesStillRejectCursors() {
		assertThatThrownBy(() -> Pagination.page(List.of("a"), s -> s, Pagination.encode("a"), null))
			.hasMessageContaining("Invalid cursor");
		assertThatThrownBy(() -> InMemorySyncToolRepository.builder().pageSize(0).build())
			.hasMessageContaining("pageSize");
	}

	@Test
	void subclassControlsVisibilityPerCaller() {
		TenantToolRepository repository = new TenantToolRepository();
		repository.register("acme", tool("acme-report"));
		repository.register("acme", tool("shared"));
		repository.register("globex", tool("globex-report"));
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("test", "1").build())
			.feature(ToolsFeature.ofSync(repository))
			.build();
		McpTransportContext acme = McpTransportContext.create(Map.of(TENANT, "acme"));
		McpTransportContext globex = McpTransportContext.create(Map.of(TENANT, "globex"));

		assertThat(names(call(server, acme, McpSchema.METHOD_TOOLS_LIST, params()), "tools", "name"))
			.containsExactly("acme-report", "shared");
		assertThat(names(call(server, globex, McpSchema.METHOD_TOOLS_LIST, params()), "tools", "name"))
			.containsExactly("globex-report");
		// Listing for a tenant is personalised, so the subclass downgrades the scope.
		assertThat(call(server, acme, McpSchema.METHOD_TOOLS_LIST, params()).get("cacheScope")).isEqualTo("private");

		Map<String, Object> ok = call(server, acme, McpSchema.METHOD_TOOLS_CALL,
				params("name", "acme-report", "arguments", Map.of()));
		assertThat(ok).containsKey("content");
		Map<String, Object> denied = call(server, globex, McpSchema.METHOD_TOOLS_CALL,
				params("name", "acme-report", "arguments", Map.of()));
		assertThat(denied.get("code")).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
		assertThat((String) denied.get("message")).contains("Unknown tool");
	}

	@Test
	void resourceBaseMatchesTemplatesInSubclassOrderAndPagesTemplates() {
		InMemorySyncResourceRepository repository = InMemorySyncResourceRepository.builder()
			.resource(new SyncResourceSpecification(Resource.builder("files://readme", "readme").build(),
					(ctx, request) -> McpSyncResponse.result(ReadResourceResult
						.builder(List.of(TextResourceContents.builder(request.uri(), "static").build()))
						.build())))
			.resourceTemplates(
					List.of(template("files://{name}"), template("files://{dir}/{name}"), template("db://{table}")))
			.pageSize(2)
			.build();
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("test", "1").build())
			.feature(ResourcesFeature.ofSync(repository))
			.build();

		// Exact match wins over templates; otherwise the first matching template serves.
		assertThat(read(server, "files://readme")).isEqualTo("static");
		assertThat(read(server, "files://other")).isEqualTo("via files://{name}");
		assertThat(read(server, "files://docs/guide")).isEqualTo("via files://{dir}/{name}");
		assertThat(read(server, "db://users")).isEqualTo("via db://{table}");
		assertThat(call(server, McpSchema.METHOD_RESOURCES_READ, params("uri", "mail://x")).get("code"))
			.isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);

		Map<String, Object> page1 = call(server, McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, params());
		assertThat(names(page1, "resourceTemplates", "uriTemplate")).containsExactly("db://{table}",
				"files://{dir}/{name}");
		Map<String, Object> page2 = call(server, McpSchema.METHOD_RESOURCES_TEMPLATES_LIST,
				params("cursor", page1.get("nextCursor")));
		assertThat(names(page2, "resourceTemplates", "uriTemplate")).containsExactly("files://{name}");
	}

	@Test
	void matcherCacheIsEvictedOnRemoveAndBounded() {
		InMemorySyncResourceRepository repository = InMemorySyncResourceRepository.builder()
			.resourceTemplate(template("files://{name}"))
			.build();
		assertThat(repository.cachedMatcherCount()).isEqualTo(1);
		assertThat(repository.remove("files://{name}")).isTrue();
		assertThat(repository.cachedMatcherCount()).isZero();

		for (int i = 0; i < AbstractSyncResourceRepository.MAX_CACHED_MATCHERS + 10; i++) {
			repository.matcher("tenant" + i + "://{id}");
		}
		assertThat(repository.cachedMatcherCount())
			.isLessThanOrEqualTo(AbstractSyncResourceRepository.MAX_CACHED_MATCHERS);
	}

	@Test
	void fileSystemRepositoryListsAndReadsFilesThroughTheDefinitionHooks(@TempDir Path root) throws IOException {
		Files.writeString(root.resolve("a.txt"), "alpha");
		Files.writeString(root.resolve("b.md"), "# beta");
		Files.createDirectories(root.resolve("sub"));
		Files.writeString(root.resolve("sub/c.txt"), "gamma");
		FileSystemResourceRepository repository = new FileSystemResourceRepository(root, 2);
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("test", "1").build())
			.feature(ResourcesFeature.ofSync(repository))
			.build();

		Map<String, Object> page1 = call(server, McpSchema.METHOD_RESOURCES_LIST, params());
		assertThat(names(page1, "resources", "uri")).containsExactly("file:///docs/a.txt", "file:///docs/b.md");
		Map<String, Object> page2 = call(server, McpSchema.METHOD_RESOURCES_LIST,
				params("cursor", page1.get("nextCursor")));
		assertThat(names(page2, "resources", "uri")).containsExactly("file:///docs/sub/c.txt");
		assertThat(page2).doesNotContainKey("nextCursor");
		// Listing never asked for a handler: definitions are plain Resource records.
		assertThat(repository.handlersCreated).isZero();

		assertThat(read(server, "file:///docs/sub/c.txt")).isEqualTo("gamma");
		assertThat(repository.handlersCreated).isEqualTo(1);
		assertThat(
				call(server, McpSchema.METHOD_RESOURCES_READ, params("uri", "file:///docs/../etc/passwd")).get("code"))
			.isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
		assertThat(call(server, McpSchema.METHOD_RESOURCES_READ, params("uri", "file:///docs/missing.txt")).get("code"))
			.isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
	}

	@SuppressWarnings("unchecked")
	private static String read(McpServer server, String uri) {
		Map<String, Object> result = call(server, McpSchema.METHOD_RESOURCES_READ, params("uri", uri));
		return (String) ((List<Map<String, Object>>) result.get("contents")).get(0).get("text");
	}

	/**
	 * A repository whose catalogue depends on the caller: only the tenant named in the
	 * transport context sees its tools.
	 */
	static final class TenantToolRepository extends AbstractSyncToolRepository {

		private final Map<String, List<SyncToolSpecification>> byTenant = new HashMap<>();

		void register(String tenant, SyncToolSpecification specification) {
			this.byTenant.computeIfAbsent(tenant, t -> new ArrayList<>()).add(specification);
		}

		private List<SyncToolSpecification> ownedBy(McpRequestContext ctx) {
			Object tenant = ctx.transportContext().get(TENANT);
			return tenant != null ? this.byTenant.getOrDefault(tenant, List.of()) : List.of();
		}

		@Override
		protected @Nullable SyncToolSpecification lookup(McpRequestContext ctx, String name) {
			return ownedBy(ctx).stream().filter(spec -> spec.tool().name().equals(name)).findFirst().orElse(null);
		}

		@Override
		protected List<Tool> definitions(McpRequestContext ctx) {
			return ownedBy(ctx).stream().map(SyncToolSpecification::tool).toList();
		}

		@Override
		protected @Nullable CacheScope listCacheScope(McpRequestContext ctx) {
			return CacheScope.PRIVATE;
		}

	}

	/**
	 * Scenario from MODERN.md §3.1 (customising the repositories): files under a root served as resources.
	 * Listing walks the tree and returns definitions only; a handler is created on read.
	 */
	static final class FileSystemResourceRepository extends AbstractSyncResourceRepository {

		private static final String PREFIX = "file:///docs/";

		private final Path root;

		int handlersCreated;

		FileSystemResourceRepository(Path root, int pageSize) {
			super(null, pageSize, false);
			this.root = root.toAbsolutePath().normalize();
		}

		private @Nullable Path resolve(String uri) {
			if (!uri.startsWith(PREFIX)) {
				return null;
			}
			Path path = this.root.resolve(uri.substring(PREFIX.length())).normalize();
			return path.startsWith(this.root) && Files.isRegularFile(path) ? path : null;
		}

		private String uriOf(Path path) {
			return PREFIX + this.root.relativize(path).toString().replace('\\', '/');
		}

		@Override
		protected List<Resource> definitions(McpRequestContext ctx) {
			try (Stream<Path> files = Files.walk(this.root)) {
				return files.filter(Files::isRegularFile)
					.map(path -> Resource.builder(uriOf(path), path.getFileName().toString()).build())
					.toList();
			}
			catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		protected List<ResourceTemplate> templateDefinitions(McpRequestContext ctx) {
			return List.of();
		}

		@Override
		protected @Nullable SyncResourceSpecification lookup(McpRequestContext ctx, String uri) {
			Path path = resolve(uri);
			if (path == null) {
				return null;
			}
			this.handlersCreated++;
			return new SyncResourceSpecification(Resource.builder(uri, path.getFileName().toString()).build(),
					(context, request) -> {
						try {
							return McpSyncResponse.result(ReadResourceResult
								.builder(List.of(TextResourceContents.builder(uri, Files.readString(path)).build()))
								.build());
						}
						catch (IOException e) {
							throw new IllegalStateException(e);
						}
					});
		}

		@Override
		protected @Nullable SyncResourceTemplateSpecification lookupTemplate(McpRequestContext ctx,
				String uriTemplate) {
			return null;
		}

	}

}
