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

import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.server.feature.McpChangeBroadcaster;
import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;

/**
 * Settings shared by the specification-backed repositories
 * ({@link AbstractSyncToolRepository}, {@link AbstractSyncResourceRepository},
 * {@link AbstractSyncPromptRepository}).
 *
 * @param changeBroadcaster receives {@code *ListChanged} and {@code ResourceUpdated}
 * events; {@code null} disables broadcasting
 * @param pageSize maximum number of entries per {@code *\/list} page; {@code null}
 * returns everything on one page and rejects any cursor
 * @param ttlMs {@code ttlMs} cache hint on list pages; {@code null} lets the SDK default
 * apply
 * @param cacheScope {@code cacheScope} cache hint on list pages; {@code null} lets the
 * SDK default apply
 * @author Christian Tzolov
 * @since 2.2.0
 */
public record RepositoryOptions(@Nullable McpChangeBroadcaster changeBroadcaster, @Nullable Integer pageSize,
		@Nullable Long ttlMs, @Nullable CacheScope cacheScope) {

	public RepositoryOptions {
		Assert.isTrue(pageSize == null || pageSize > 0, "pageSize must be positive");
		Assert.isTrue(ttlMs == null || ttlMs >= 0, "ttlMs must not be negative");
	}

	/**
	 * No broadcasting, a single unbounded page and SDK default cache hints.
	 */
	public static RepositoryOptions defaults() {
		return new RepositoryOptions(null, null, null, null);
	}

	public RepositoryOptions withChangeBroadcaster(@Nullable McpChangeBroadcaster changeBroadcaster) {
		return new RepositoryOptions(changeBroadcaster, this.pageSize, this.ttlMs, this.cacheScope);
	}

	public RepositoryOptions withPageSize(@Nullable Integer pageSize) {
		return new RepositoryOptions(this.changeBroadcaster, pageSize, this.ttlMs, this.cacheScope);
	}

	public RepositoryOptions withCacheHints(@Nullable Long ttlMs, @Nullable CacheScope cacheScope) {
		return new RepositoryOptions(this.changeBroadcaster, this.pageSize, ttlMs, cacheScope);
	}

}
