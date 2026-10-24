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
 * Subclasses provide the definitions for listing ({@link #definitions},
 * {@link #templateDefinitions}) and the specifications for dispatch ({@link #lookup},
 * {@link #lookupTemplate}), and may restrict what a caller sees through the two
 * {@code visible} hooks, which every path consults and which default to the optional
 * {@link McpVisibilityFilter} given to the constructor. This class provides paging and
 * the list cache scope for both list methods, {@code resources/read} dispatch that first
 * tries an exact URI match and then the templates in order, URI template matching
 * (matchers are compiled once per template), and the list-changed and resource-updated
 * broadcasts. {@link InMemorySyncResourceRepository} is the map-backed implementation.
 *
 * @author Christian Tzolov
 * @since 2.2.0
 */
public abstract class AbstractSyncResourceRepository implements McpSyncResourceRepository {

	private final @Nullable McpChangeBroadcaster changeBroadcaster;

	private final @Nullable Integer pageSize;

	private final boolean subscribeSupported;

	private final @Nullable McpVisibilityFilter visibility;

	private final McpUriTemplateManagerFactory uriTemplateManagerFactory;

	/**
	 * Upper bound of the matcher cache. Compiling a template is cheap, so when the bound
	 * is reached the cache is simply cleared; this keeps repositories with per-caller or
	 * churning template sets from growing without limit.
	 */
	static final int MAX_CACHED_MATCHERS = 1024;

	private final Map<String, McpUriTemplateManager> matchers = new ConcurrentHashMap<>();

	/**
	 * Creates a repository that serves every resource on one page to every caller,
	 * broadcasts no changes and does not advertise per-resource subscriptions.
	 */
	protected AbstractSyncResourceRepository() {
		this(null, null, false, null);
	}

	/**
	 * @param changeBroadcaster receives list-changed and resource-updated broadcasts, or
	 * {@code null} to broadcast nothing
	 * @param pageSize the maximum number of items per list page, or {@code null} to serve
	 * everything on one page
	 * @param subscribeSupported whether the server advertises
	 * {@code resources.subscribe}; when true, {@link #broadcastResourceUpdated(String)}
	 * reaches subscribers
	 */
	protected AbstractSyncResourceRepository(@Nullable McpChangeBroadcaster changeBroadcaster,
			@Nullable Integer pageSize, boolean subscribeSupported) {
		this(changeBroadcaster, pageSize, subscribeSupported, null);
	}

	/**
	 * @param visibility restricts what each caller sees, or {@code null} to show every
	 * resource and template to every caller
	 */
	protected AbstractSyncResourceRepository(@Nullable McpChangeBroadcaster changeBroadcaster,
			@Nullable Integer pageSize, boolean subscribeSupported, @Nullable McpVisibilityFilter visibility) {
		this(changeBroadcaster, pageSize, subscribeSupported, visibility, new DefaultMcpUriTemplateManagerFactory());
	}

	/**
	 * @param uriTemplateManagerFactory compiles the URI templates used to match
	 * {@code resources/read} requests against resource templates
	 */
	protected AbstractSyncResourceRepository(@Nullable McpChangeBroadcaster changeBroadcaster,
			@Nullable Integer pageSize, boolean subscribeSupported, @Nullable McpVisibilityFilter visibility,
			McpUriTemplateManagerFactory uriTemplateManagerFactory) {
		Assert.isTrue(pageSize == null || pageSize > 0, "pageSize must be positive");
		Assert.notNull(uriTemplateManagerFactory, "uriTemplateManagerFactory must not be null");
		this.changeBroadcaster = changeBroadcaster;
		this.pageSize = pageSize;
		this.subscribeSupported = subscribeSupported;
		this.visibility = visibility;
		this.uriTemplateManagerFactory = uriTemplateManagerFactory;
	}

	/**
	 * Returns every static resource definition. Order is irrelevant; pages are sorted by
	 * URI. Visibility is applied by the caller through
	 * {@link #visible(McpRequestContext, Resource)}.
	 */
	protected abstract List<Resource> definitions(McpRequestContext ctx);

	/**
	 * Returns every resource template definition. The order decides which template serves
	 * a URI that several templates match; pages are sorted by URI template. Visibility is
	 * applied by the caller through
	 * {@link #visible(McpRequestContext, ResourceTemplate)}.
	 */
	protected abstract List<ResourceTemplate> templateDefinitions(McpRequestContext ctx);

	/**
	 * Returns the specification of the resource with exactly this URI, or {@code null}
	 * when there is none. Templates are matched separately.
	 */
	protected abstract @Nullable SyncResourceSpecification lookup(McpRequestContext ctx, String uri);

	/**
	 * Returns the specification of the template with exactly this URI template, or
	 * {@code null} when there is none.
	 */
	protected abstract @Nullable SyncResourceTemplateSpecification lookupTemplate(McpRequestContext ctx,
			String uriTemplate);

	/**
	 * Decides whether the caller may see {@code resource}. A hidden resource is neither
	 * listed nor readable: a read of its URI is answered {@code Unknown resource} and is
	 * not handed to a template that would match it. Defaults to the constructor's
	 * {@link McpVisibilityFilter}, or {@code true} without one.
	 */
	protected boolean visible(McpRequestContext ctx, Resource resource) {
		return this.visibility == null || this.visibility.resource(ctx, resource);
	}

	/**
	 * Decides whether the caller may see {@code template}. A hidden template is neither
	 * listed nor used to serve reads. Defaults to the constructor's
	 * {@link McpVisibilityFilter}, or {@code true} without one.
	 */
	protected boolean visible(McpRequestContext ctx, ResourceTemplate template) {
		return this.visibility == null || this.visibility.resourceTemplate(ctx, template);
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
	public ResourcesPage list(McpRequestContext ctx, @Nullable String cursor) {
		List<Resource> visibleResources = definitions(ctx).stream().filter(r -> visible(ctx, r)).toList();
		Pagination.Page<Resource> page = Pagination.page(visibleResources, Resource::uri, cursor, this.pageSize);
		return new ResourcesPage(page.items(), page.nextCursor(), null, listCacheScope(ctx));
	}

	@Override
	public ResourceTemplatesPage listTemplates(McpRequestContext ctx, @Nullable String cursor) {
		List<ResourceTemplate> visibleTemplates = visibleTemplates(ctx);
		Pagination.Page<ResourceTemplate> page = Pagination.page(visibleTemplates, ResourceTemplate::uriTemplate,
				cursor, this.pageSize);
		return new ResourceTemplatesPage(page.items(), page.nextCursor(), null, listCacheScope(ctx));
	}

	@Override
	public McpSyncResponse<ReadResourceOutcome> read(McpRequestContext ctx, ReadResourceRequest request) {
		String uri = request.uri();
		SyncResourceSpecification resource = lookup(ctx, uri);
		if (resource != null) {
			// A hidden resource must not be served by a template that happens to match
			// its URI either, so it does not fall through.
			if (!visible(ctx, resource.resource())) {
				throw unknownResource(uri);
			}
			return resource.handler().read(ctx, request);
		}
		ResourceTemplate template = matchTemplate(ctx, uri);
		if (template != null) {
			SyncResourceTemplateSpecification spec = lookupTemplate(ctx, template.uriTemplate());
			if (spec != null) {
				return spec.handler().read(ctx, request);
			}
		}
		throw unknownResource(uri);
	}

	private static McpException unknownResource(String uri) {
		return McpException.invalidParams("Unknown resource: " + uri, Map.of("uri", uri));
	}

	@Override
	public boolean supportsSubscribe() {
		return this.subscribeSupported;
	}

	/**
	 * Returns the first visible template whose URI template matches {@code uri}, or
	 * {@code null}.
	 */
	protected @Nullable ResourceTemplate matchTemplate(McpRequestContext ctx, String uri) {
		for (ResourceTemplate template : visibleTemplates(ctx)) {
			if (matcher(template.uriTemplate()).matches(uri)) {
				return template;
			}
		}
		return null;
	}

	private List<ResourceTemplate> visibleTemplates(McpRequestContext ctx) {
		return templateDefinitions(ctx).stream().filter(t -> visible(ctx, t)).toList();
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
		if (this.changeBroadcaster != null) {
			this.changeBroadcaster.broadcast(new ServerChange.ResourcesListChanged());
		}
	}

	/**
	 * Broadcasts {@code notifications/resources/updated} for {@code uri} to its
	 * subscribers, if a {@link McpChangeBroadcaster} is configured. Subscriptions are
	 * matched by the SDK without consulting this repository, so visibility does not apply
	 * to them.
	 */
	protected final void broadcastResourceUpdated(String uri) {
		Assert.hasText(uri, "uri must not be empty");
		if (this.changeBroadcaster != null) {
			this.changeBroadcaster.broadcast(new ServerChange.ResourceUpdated(uri));
		}
	}

}
