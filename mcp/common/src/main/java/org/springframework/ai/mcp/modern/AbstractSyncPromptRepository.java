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
import io.modelcontextprotocol.modern.McpSchema.GetPromptOutcome;
import io.modelcontextprotocol.modern.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpChangeBroadcaster;
import io.modelcontextprotocol.modern.server.feature.McpSyncPromptRepository;
import io.modelcontextprotocol.modern.server.feature.PromptsPage;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;

/**
 * Base class for {@link McpSyncPromptRepository} implementations whose prompts are
 * described by {@link SyncPromptSpecification}s. See {@link AbstractSyncToolRepository}
 * for the design; {@link InMemorySyncPromptRepository} is the map-backed implementation.
 *
 * @author Christian Tzolov
 * @since 2.2.0
 */
public abstract class AbstractSyncPromptRepository implements McpSyncPromptRepository {

	private final @Nullable McpChangeBroadcaster changeBroadcaster;

	private final @Nullable Integer pageSize;

	private final @Nullable McpVisibilityFilter visibility;

	/**
	 * Creates a repository that serves every prompt on one page to every caller and
	 * broadcasts no changes.
	 */
	protected AbstractSyncPromptRepository() {
		this(null, null, null);
	}

	/**
	 * @param changeBroadcaster receives {@code prompts/list_changed} broadcasts, or
	 * {@code null} to broadcast nothing
	 * @param pageSize the maximum number of prompts per {@code prompts/list} page, or
	 * {@code null} to serve every prompt on one page
	 */
	protected AbstractSyncPromptRepository(@Nullable McpChangeBroadcaster changeBroadcaster,
			@Nullable Integer pageSize) {
		this(changeBroadcaster, pageSize, null);
	}

	/**
	 * @param visibility restricts what each caller sees, or {@code null} to show every
	 * prompt to every caller; see {@link #visible(McpRequestContext, Prompt)}
	 */
	protected AbstractSyncPromptRepository(@Nullable McpChangeBroadcaster changeBroadcaster, @Nullable Integer pageSize,
			@Nullable McpVisibilityFilter visibility) {
		Assert.isTrue(pageSize == null || pageSize > 0, "pageSize must be positive");
		this.changeBroadcaster = changeBroadcaster;
		this.pageSize = pageSize;
		this.visibility = visibility;
	}

	/**
	 * Returns every prompt definition the repository holds. Order is irrelevant; pages
	 * are sorted by prompt name. Visibility is applied by the caller through
	 * {@link #visible(McpRequestContext, Prompt)}.
	 */
	protected abstract List<Prompt> definitions(McpRequestContext ctx);

	/**
	 * Returns the specification of the named prompt, or {@code null} when there is none.
	 * Visibility is applied by the caller through
	 * {@link #visible(McpRequestContext, Prompt)}.
	 */
	protected abstract @Nullable SyncPromptSpecification lookup(McpRequestContext ctx, String name);

	/**
	 * Decides whether the caller may see {@code prompt}. Consulted by
	 * {@code prompts/list}, {@code prompts/find} and {@code prompts/get} alike. Defaults
	 * to the constructor's {@link McpVisibilityFilter}, or {@code true} without one.
	 */
	protected boolean visible(McpRequestContext ctx, Prompt prompt) {
		return this.visibility == null || this.visibility.prompt(ctx, prompt);
	}

	/**
	 * The {@code cacheScope} cache hint of a list page, or {@code null} to use the
	 * feature-level default (the TTL always comes from that default). Defaults to
	 * {@link CacheScope#PRIVATE} when a {@link McpVisibilityFilter} is set.
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
	public PromptsPage list(McpRequestContext ctx, @Nullable String cursor) {
		List<Prompt> visiblePrompts = definitions(ctx).stream().filter(prompt -> visible(ctx, prompt)).toList();
		Pagination.Page<Prompt> page = Pagination.page(visiblePrompts, Prompt::name, cursor, this.pageSize);
		return new PromptsPage(page.items(), page.nextCursor(), null, listCacheScope(ctx));
	}

	@Override
	public @Nullable Prompt find(McpRequestContext ctx, String name) {
		SyncPromptSpecification spec = visibleSpecification(ctx, name);
		return spec != null ? spec.prompt() : null;
	}

	@Override
	public McpSyncResponse<GetPromptOutcome> get(McpRequestContext ctx, GetPromptRequest request) {
		SyncPromptSpecification spec = visibleSpecification(ctx, request.name());
		if (spec == null) {
			throw McpException.invalidParams("Unknown prompt: " + request.name());
		}
		return spec.handler().get(ctx, request);
	}

	private @Nullable SyncPromptSpecification visibleSpecification(McpRequestContext ctx, String name) {
		SyncPromptSpecification spec = lookup(ctx, name);
		return spec != null && visible(ctx, spec.prompt()) ? spec : null;
	}

	/**
	 * Broadcasts {@code notifications/prompts/list_changed} to listening clients, if a
	 * {@link McpChangeBroadcaster} is configured.
	 */
	protected final void broadcastListChanged() {
		if (this.changeBroadcaster != null) {
			this.changeBroadcaster.broadcast(new ServerChange.PromptsListChanged());
		}
	}

}
