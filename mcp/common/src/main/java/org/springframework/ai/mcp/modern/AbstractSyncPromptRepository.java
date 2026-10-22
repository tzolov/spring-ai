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

	private final RepositoryOptions options;

	protected AbstractSyncPromptRepository(RepositoryOptions options) {
		Assert.notNull(options, "options must not be null");
		this.options = options;
	}

	/**
	 * Returns the specification of the named prompt as visible to the caller, or
	 * {@code null} when there is none.
	 */
	protected abstract @Nullable SyncPromptSpecification lookup(McpRequestContext ctx, String name);

	/**
	 * Returns every specification visible to the caller. Order is irrelevant; pages are
	 * sorted by prompt name.
	 */
	protected abstract List<SyncPromptSpecification> specifications(McpRequestContext ctx);

	protected @Nullable Long listTtlMs(McpRequestContext ctx) {
		return this.options.ttlMs();
	}

	protected @Nullable CacheScope listCacheScope(McpRequestContext ctx) {
		return this.options.cacheScope();
	}

	protected final RepositoryOptions options() {
		return this.options;
	}

	@Override
	public PromptsPage list(McpRequestContext ctx, @Nullable String cursor) {
		Pagination.Page<SyncPromptSpecification> page = Pagination.page(specifications(ctx),
				spec -> spec.prompt().name(), cursor, this.options.pageSize());
		List<Prompt> prompts = page.items().stream().map(SyncPromptSpecification::prompt).toList();
		return new PromptsPage(prompts, page.nextCursor(), listTtlMs(ctx), listCacheScope(ctx));
	}

	@Override
	public @Nullable Prompt find(McpRequestContext ctx, String name) {
		SyncPromptSpecification spec = lookup(ctx, name);
		return spec != null ? spec.prompt() : null;
	}

	@Override
	public McpSyncResponse<GetPromptOutcome> get(McpRequestContext ctx, GetPromptRequest request) {
		SyncPromptSpecification spec = lookup(ctx, request.name());
		if (spec == null) {
			throw McpException.invalidParams("Unknown prompt: " + request.name());
		}
		return spec.handler().get(ctx, request);
	}

	/**
	 * Broadcasts {@code notifications/prompts/list_changed} to listening clients, if a
	 * {@link McpChangeBroadcaster} is configured.
	 */
	protected final void broadcastListChanged() {
		McpChangeBroadcaster broadcaster = this.options.changeBroadcaster();
		if (broadcaster != null) {
			broadcaster.broadcast(new ServerChange.PromptsListChanged());
		}
	}

}
