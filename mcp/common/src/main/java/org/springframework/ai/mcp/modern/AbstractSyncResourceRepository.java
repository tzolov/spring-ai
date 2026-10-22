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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.modern.McpSchema.Resource;
import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpChangeBroadcaster;
import io.modelcontextprotocol.modern.server.feature.McpSyncResourceRepository;
import io.modelcontextprotocol.modern.server.feature.ResourceTemplatesPage;
import io.modelcontextprotocol.modern.server.feature.ResourcesPage;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.util.DefaultMcpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.McpUriTemplateManager;
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory;
import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;

/**
 * Base class for {@link McpSyncResourceRepository} implementations whose resources and
 * resource templates are described by {@link SyncResourceSpecification}s and
 * {@link SyncResourceTemplateSpecification}s.
 * <p>
 * Subclasses provide the visible specifications per request; this class provides paging
 * and cache hints for both list methods, {@code resources/read} dispatch that first tries
 * an exact URI match and then the templates in order, URI template matching (matchers are
 * compiled once per template), and the list-changed and resource-updated broadcasts.
 * {@link InMemorySyncResourceRepository} is the map-backed implementation.
 *
 * @author Christian Tzolov
 * @since 2.2.0
 */
public abstract class AbstractSyncResourceRepository implements McpSyncResourceRepository {

	private final RepositoryOptions options;

	private final McpUriTemplateManagerFactory uriTemplateManagerFactory;

	private final boolean subscribeSupported;

	/**
	 * Upper bound of the matcher cache. Compiling a template is cheap, so when the bound
	 * is reached the cache is simply cleared; this keeps repositories with per-caller or
	 * churning template sets from growing without limit.
	 */
	static final int MAX_CACHED_MATCHERS = 1024;

	private final Map<String, McpUriTemplateManager> matchers = new ConcurrentHashMap<>();

	/**
	 * Creates a repository with the default URI template manager and without per-resource
	 * subscriptions.
	 */
	protected AbstractSyncResourceRepository(RepositoryOptions options) {
		this(options, new DefaultMcpUriTemplateManagerFactory(), false);
	}

	/**
	 * @param uriTemplateManagerFactory compiles the URI templates used to match
	 * {@code resources/read} requests against resource templates
	 * @param subscribeSupported whether the server advertises
	 * {@code resources.subscribe}; when true, {@link #broadcastResourceUpdated(String)}
	 * notifies subscribers
	 */
	protected AbstractSyncResourceRepository(RepositoryOptions options,
			McpUriTemplateManagerFactory uriTemplateManagerFactory, boolean subscribeSupported) {
		Assert.notNull(options, "options must not be null");
		Assert.notNull(uriTemplateManagerFactory, "uriTemplateManagerFactory must not be null");
		this.options = options;
		this.uriTemplateManagerFactory = uriTemplateManagerFactory;
		this.subscribeSupported = subscribeSupported;
	}

	/**
	 * Returns the specification of the resource with exactly this URI as visible to the
	 * caller, or {@code null} when there is none. Templates are matched separately.
	 */
	protected abstract @Nullable SyncResourceSpecification lookup(McpRequestContext ctx, String uri);

	/**
	 * Returns every static resource specification visible to the caller. Order is
	 * irrelevant; pages are sorted by URI.
	 */
	protected abstract List<SyncResourceSpecification> specifications(McpRequestContext ctx);

	/**
	 * Returns every resource template specification visible to the caller. The order
	 * decides which template serves a URI that several templates match; pages are sorted
	 * by URI template.
	 */
	protected abstract List<SyncResourceTemplateSpecification> templateSpecifications(McpRequestContext ctx);

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
	public ResourcesPage list(McpRequestContext ctx, @Nullable String cursor) {
		Pagination.Page<SyncResourceSpecification> page = Pagination.page(specifications(ctx),
				spec -> spec.resource().uri(), cursor, this.options.pageSize());
		List<Resource> resources = page.items().stream().map(SyncResourceSpecification::resource).toList();
		return new ResourcesPage(resources, page.nextCursor(), listTtlMs(ctx), listCacheScope(ctx));
	}

	@Override
	public ResourceTemplatesPage listTemplates(McpRequestContext ctx, @Nullable String cursor) {
		Pagination.Page<SyncResourceTemplateSpecification> page = Pagination.page(templateSpecifications(ctx),
				spec -> spec.resourceTemplate().uriTemplate(), cursor, this.options.pageSize());
		List<ResourceTemplate> templates = page.items()
			.stream()
			.map(SyncResourceTemplateSpecification::resourceTemplate)
			.toList();
		return new ResourceTemplatesPage(templates, page.nextCursor(), listTtlMs(ctx), listCacheScope(ctx));
	}

	@Override
	public McpSyncResponse<ReadResourceOutcome> read(McpRequestContext ctx, ReadResourceRequest request) {
		String uri = request.uri();
		SyncResourceSpecification resource = lookup(ctx, uri);
		if (resource != null) {
			return resource.handler().read(ctx, request);
		}
		SyncResourceTemplateSpecification template = matchTemplate(ctx, uri);
		if (template != null) {
			return template.handler().read(ctx, request);
		}
		throw McpException.invalidParams("Unknown resource: " + uri, Map.of("uri", uri));
	}

	@Override
	public boolean supportsSubscribe() {
		return this.subscribeSupported;
	}

	/**
	 * Returns the first visible template whose URI template matches {@code uri}, or
	 * {@code null}.
	 */
	protected @Nullable SyncResourceTemplateSpecification matchTemplate(McpRequestContext ctx, String uri) {
		for (SyncResourceTemplateSpecification spec : templateSpecifications(ctx)) {
			if (matcher(spec.resourceTemplate().uriTemplate()).matches(uri)) {
				return spec;
			}
		}
		return null;
	}

	/**
	 * Returns the matcher for a URI template, compiling it on first use. The cache is
	 * bounded by {@link #MAX_CACHED_MATCHERS}; call {@link #evictMatcher(String)} when a
	 * template is removed.
	 */
	protected final McpUriTemplateManager matcher(String uriTemplate) {
		McpUriTemplateManager matcher = this.matchers.get(uriTemplate);
		if (matcher != null) {
			return matcher;
		}
		if (this.matchers.size() >= MAX_CACHED_MATCHERS) {
			this.matchers.clear();
		}
		return this.matchers.computeIfAbsent(uriTemplate, this.uriTemplateManagerFactory::create);
	}

	/**
	 * Drops the cached matcher of a template that is no longer served.
	 */
	protected final void evictMatcher(String uriTemplate) {
		this.matchers.remove(uriTemplate);
	}

	int cachedMatcherCount() {
		return this.matchers.size();
	}

	/**
	 * Broadcasts {@code notifications/resources/list_changed} to listening clients, if a
	 * {@link McpChangeBroadcaster} is configured.
	 */
	protected final void broadcastListChanged() {
		McpChangeBroadcaster broadcaster = this.options.changeBroadcaster();
		if (broadcaster != null) {
			broadcaster.broadcast(new ServerChange.ResourcesListChanged());
		}
	}

	/**
	 * Broadcasts {@code notifications/resources/updated} for {@code uri} to its
	 * subscribers, if a {@link McpChangeBroadcaster} is configured.
	 */
	protected final void broadcastResourceUpdated(String uri) {
		Assert.hasText(uri, "uri must not be empty");
		McpChangeBroadcaster broadcaster = this.options.changeBroadcaster();
		if (broadcaster != null) {
			broadcaster.broadcast(new ServerChange.ResourceUpdated(uri));
		}
	}

}
