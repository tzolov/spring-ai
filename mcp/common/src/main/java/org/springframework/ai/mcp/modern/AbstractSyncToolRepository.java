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
 * Subclasses answer two questions: which tool definitions exist, through
 * {@link #definitions(McpRequestContext)}, and how a named tool is dispatched, through
 * {@link #lookup(McpRequestContext, String)}. A third hook,
 * {@link #visible(McpRequestContext, Tool)}, decides what the caller may see; it is
 * consulted on every path, so a hidden tool is neither listed nor callable by name. Its
 * default delegates to the optional {@link McpVisibilityFilter} given to the constructor,
 * and a filtered listing is {@code cacheScope: private}. All three receive the request
 * context so that a repository can be tenant- or role-aware. This class provides
 * {@code tools/list} paging with opaque cursors, the list cache scope, {@code tools/call}
 * dispatch and list-changed broadcasting. {@link InMemorySyncToolRepository} is the
 * map-backed implementation.
 *
 * @author Christian Tzolov
 * @since 2.2.0
 */
public abstract class AbstractSyncToolRepository implements McpSyncToolRepository {

	private final @Nullable McpChangeBroadcaster changeBroadcaster;

	private final @Nullable Integer pageSize;

	private final @Nullable McpVisibilityFilter visibility;

	/**
	 * Creates a repository that serves every tool on one page to every caller and
	 * broadcasts no changes.
	 */
	protected AbstractSyncToolRepository() {
		this(null, null, null);
	}

	/**
	 * @param changeBroadcaster receives {@code tools/list_changed} broadcasts, or
	 * {@code null} to broadcast nothing
	 * @param pageSize the maximum number of tools per {@code tools/list} page, or
	 * {@code null} to serve every tool on one page
	 */
	protected AbstractSyncToolRepository(@Nullable McpChangeBroadcaster changeBroadcaster, @Nullable Integer pageSize) {
		this(changeBroadcaster, pageSize, null);
	}

	/**
	 * @param visibility restricts what each caller sees, or {@code null} to show every
	 * tool to every caller; see {@link #visible(McpRequestContext, Tool)}
	 */
	protected AbstractSyncToolRepository(@Nullable McpChangeBroadcaster changeBroadcaster, @Nullable Integer pageSize,
			@Nullable McpVisibilityFilter visibility) {
		Assert.isTrue(pageSize == null || pageSize > 0, "pageSize must be positive");
		this.changeBroadcaster = changeBroadcaster;
		this.pageSize = pageSize;
		this.visibility = visibility;
	}

	/**
	 * Returns every tool definition the repository holds. Order is irrelevant; pages are
	 * sorted by tool name. Visibility is applied by the caller through
	 * {@link #visible(McpRequestContext, Tool)}, so this method need not filter.
	 */
	protected abstract List<Tool> definitions(McpRequestContext ctx);

	/**
	 * Returns the specification of the named tool, or {@code null} when there is none.
	 * Visibility is applied by the caller through
	 * {@link #visible(McpRequestContext, Tool)}.
	 */
	protected abstract @Nullable SyncToolSpecification lookup(McpRequestContext ctx, String name);

	/**
	 * Decides whether the caller may see {@code tool}. Consulted by {@code tools/list},
	 * {@code tools/find} and {@code tools/call} alike, so a hidden tool behaves as if it
	 * did not exist. Defaults to the constructor's {@link McpVisibilityFilter}, or
	 * {@code true} without one.
	 */
	protected boolean visible(McpRequestContext ctx, Tool tool) {
		return this.visibility == null || this.visibility.tool(ctx, tool);
	}

	/**
	 * The {@code cacheScope} cache hint of a list page, or {@code null} to use the
	 * feature-level default (the TTL always comes from that default). Defaults to
	 * {@link CacheScope#PRIVATE} when a {@link McpVisibilityFilter} is set, since a
	 * filtered listing is personal to the caller; override to return {@code PRIVATE} for
	 * other personalised listings.
	 */
	protected @Nullable CacheScope listCacheScope(McpRequestContext ctx) {
		return this.visibility != null ? CacheScope.PRIVATE : null;
	}

	protected final @Nullable Integer pageSize() {
		return this.pageSize;
	}

	protected final @Nullable McpChangeBroadcaster changeBroadcaster() {
		return this.changeBroadcaster;
	}

	protected final @Nullable McpVisibilityFilter visibility() {
		return this.visibility;
	}

	@Override
	public ToolsPage list(McpRequestContext ctx, @Nullable String cursor) {
		List<Tool> visibleTools = definitions(ctx).stream().filter(tool -> visible(ctx, tool)).toList();
		Pagination.Page<Tool> page = Pagination.page(visibleTools, Tool::name, cursor, this.pageSize);
		return new ToolsPage(page.items(), page.nextCursor(), null, listCacheScope(ctx));
	}

	@Override
	public @Nullable Tool find(McpRequestContext ctx, String name) {
		SyncToolSpecification spec = visibleSpecification(ctx, name);
		return spec != null ? spec.tool() : null;
	}

	@Override
	public McpSyncResponse<CallToolOutcome> call(McpRequestContext ctx, CallToolRequest request) {
		SyncToolSpecification spec = visibleSpecification(ctx, request.name());
		if (spec == null) {
			throw McpException.invalidParams("Unknown tool: " + request.name());
		}
		return spec.handler().call(ctx, request);
	}

	private @Nullable SyncToolSpecification visibleSpecification(McpRequestContext ctx, String name) {
		SyncToolSpecification spec = lookup(ctx, name);
		return spec != null && visible(ctx, spec.tool()) ? spec : null;
	}

	/**
	 * Broadcasts {@code notifications/tools/list_changed} to listening clients, if a
	 * {@link McpChangeBroadcaster} is configured.
	 */
	protected final void broadcastListChanged() {
		if (this.changeBroadcaster != null) {
			this.changeBroadcaster.broadcast(new ServerChange.ToolsListChanged());
		}
	}

}
