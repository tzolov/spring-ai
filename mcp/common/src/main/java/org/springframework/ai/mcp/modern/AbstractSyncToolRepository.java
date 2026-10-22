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

import java.util.List;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpChangeBroadcaster;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;

/**
 * Base class for {@link McpSyncToolRepository} implementations whose tools are described
 * by {@link SyncToolSpecification}s.
 * <p>
 * Subclasses decide which specifications exist and who may see them, through
 * {@link #lookup(McpRequestContext, String)} and
 * {@link #specifications(McpRequestContext)}; both receive the request context so that a
 * repository can be tenant- or role-aware. This class provides {@code tools/list} paging
 * with opaque cursors and cache hints, {@code tools/call} dispatch and list-changed
 * broadcasting. {@link InMemorySyncToolRepository} is the map-backed implementation.
 *
 * @author Christian Tzolov
 * @since 2.2.0
 */
public abstract class AbstractSyncToolRepository implements McpSyncToolRepository {

	private final RepositoryOptions options;

	protected AbstractSyncToolRepository(RepositoryOptions options) {
		Assert.notNull(options, "options must not be null");
		this.options = options;
	}

	/**
	 * Returns the specification of the named tool as visible to the caller, or
	 * {@code null} when there is none.
	 */
	protected abstract @Nullable SyncToolSpecification lookup(McpRequestContext ctx, String name);

	/**
	 * Returns every specification visible to the caller. Order is irrelevant; pages are
	 * sorted by tool name.
	 */
	protected abstract List<SyncToolSpecification> specifications(McpRequestContext ctx);

	/**
	 * The {@code ttlMs} cache hint of a list page. Defaults to the configured value;
	 * override to vary it per request.
	 */
	protected @Nullable Long listTtlMs(McpRequestContext ctx) {
		return this.options.ttlMs();
	}

	/**
	 * The {@code cacheScope} cache hint of a list page. Defaults to the configured value;
	 * override to return {@link CacheScope#PRIVATE} for personalised listings.
	 */
	protected @Nullable CacheScope listCacheScope(McpRequestContext ctx) {
		return this.options.cacheScope();
	}

	protected final RepositoryOptions options() {
		return this.options;
	}

	@Override
	public ToolsPage list(McpRequestContext ctx, @Nullable String cursor) {
		Pagination.Page<SyncToolSpecification> page = Pagination.page(specifications(ctx), spec -> spec.tool().name(),
				cursor, this.options.pageSize());
		List<Tool> tools = page.items().stream().map(SyncToolSpecification::tool).toList();
		return new ToolsPage(tools, page.nextCursor(), listTtlMs(ctx), listCacheScope(ctx));
	}

	@Override
	public @Nullable Tool find(McpRequestContext ctx, String name) {
		SyncToolSpecification spec = lookup(ctx, name);
		return spec != null ? spec.tool() : null;
	}

	@Override
	public McpSyncResponse<CallToolOutcome> call(McpRequestContext ctx, CallToolRequest request) {
		SyncToolSpecification spec = lookup(ctx, request.name());
		if (spec == null) {
			throw McpException.invalidParams("Unknown tool: " + request.name());
		}
		return spec.handler().call(ctx, request);
	}

	/**
	 * Broadcasts {@code notifications/tools/list_changed} to listening clients, if a
	 * {@link McpChangeBroadcaster} is configured.
	 */
	protected final void broadcastListChanged() {
		McpChangeBroadcaster broadcaster = this.options.changeBroadcaster();
		if (broadcaster != null) {
			broadcaster.broadcast(new ServerChange.ToolsListChanged());
		}
	}

}
