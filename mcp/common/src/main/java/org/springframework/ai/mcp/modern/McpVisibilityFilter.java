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
import java.util.function.BiPredicate;

import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.McpSchema.Resource;
import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;

import org.springframework.util.Assert;

/**
 * Decides, per request, which primitives a caller may see. The in-memory repositories
 * apply a filter on every path: a hidden tool, resource, template or prompt is neither
 * listed nor reachable by name, and listings become {@code cacheScope: private}.
 * <p>
 * Every method defaults to {@code true}, so a filter implements only the primitives it
 * cares about. The static factories build single-primitive filters from a predicate;
 * {@link #allOf(List)} combines several filters, all of which must allow an item.
 * <p>
 * The request's transport context carries whatever the transport extracted from the HTTP
 * request, by default its headers; see {@link McpTransportContexts}. A definition's
 * {@code _meta} is {@code null} unless something set it, so guard it:
 *
 * <pre>{@code
 * &#64;Bean
 * McpVisibilityFilter adminOnlyUnlessPublic() {
 *     return McpVisibilityFilter.tools((ctx, tool) ->
 *         "admin".equals(McpTransportContexts.header(ctx, "x-role"))
 *             || (tool.meta() != null && "public".equals(tool.meta().get("visibility"))));
 * }
 * }</pre>
 * <p>
 * The in-memory repositories apply the filter beans automatically; a repository built on
 * an {@code AbstractSync*Repository} receives the filter through its constructor.
 * {@code completion/complete} is guarded through the prompt and resource repositories;
 * resource subscriptions on {@code subscriptions/listen} are matched by the SDK without
 * consulting the repository and are not filtered.
 *
 * @author Christian Tzolov
 * @since 2.2.0
 */
public interface McpVisibilityFilter {

	default boolean tool(McpRequestContext ctx, Tool tool) {
		return true;
	}

	default boolean resource(McpRequestContext ctx, Resource resource) {
		return true;
	}

	default boolean resourceTemplate(McpRequestContext ctx, ResourceTemplate template) {
		return true;
	}

	default boolean prompt(McpRequestContext ctx, Prompt prompt) {
		return true;
	}

	/**
	 * A filter that restricts tools only.
	 */
	static McpVisibilityFilter tools(BiPredicate<McpRequestContext, Tool> predicate) {
		Assert.notNull(predicate, "predicate must not be null");
		return new McpVisibilityFilter() {
			@Override
			public boolean tool(McpRequestContext ctx, Tool tool) {
				return predicate.test(ctx, tool);
			}
		};
	}

	/**
	 * A filter that restricts static resources only.
	 */
	static McpVisibilityFilter resources(BiPredicate<McpRequestContext, Resource> predicate) {
		Assert.notNull(predicate, "predicate must not be null");
		return new McpVisibilityFilter() {
			@Override
			public boolean resource(McpRequestContext ctx, Resource resource) {
				return predicate.test(ctx, resource);
			}
		};
	}

	/**
	 * A filter that restricts resource templates only.
	 */
	static McpVisibilityFilter resourceTemplates(BiPredicate<McpRequestContext, ResourceTemplate> predicate) {
		Assert.notNull(predicate, "predicate must not be null");
		return new McpVisibilityFilter() {
			@Override
			public boolean resourceTemplate(McpRequestContext ctx, ResourceTemplate template) {
				return predicate.test(ctx, template);
			}
		};
	}

	/**
	 * A filter that restricts prompts only.
	 */
	static McpVisibilityFilter prompts(BiPredicate<McpRequestContext, Prompt> predicate) {
		Assert.notNull(predicate, "predicate must not be null");
		return new McpVisibilityFilter() {
			@Override
			public boolean prompt(McpRequestContext ctx, Prompt prompt) {
				return predicate.test(ctx, prompt);
			}
		};
	}

	/**
	 * A filter that allows an item only when every given filter allows it. An empty list
	 * allows everything.
	 */
	static McpVisibilityFilter allOf(List<? extends McpVisibilityFilter> filters) {
		Assert.notNull(filters, "filters must not be null");
		List<McpVisibilityFilter> copy = List.copyOf(filters);
		if (copy.size() == 1) {
			return copy.get(0);
		}
		return new McpVisibilityFilter() {
			@Override
			public boolean tool(McpRequestContext ctx, Tool tool) {
				return copy.stream().allMatch(filter -> filter.tool(ctx, tool));
			}

			@Override
			public boolean resource(McpRequestContext ctx, Resource resource) {
				return copy.stream().allMatch(filter -> filter.resource(ctx, resource));
			}

			@Override
			public boolean resourceTemplate(McpRequestContext ctx, ResourceTemplate template) {
				return copy.stream().allMatch(filter -> filter.resourceTemplate(ctx, template));
			}

			@Override
			public boolean prompt(McpRequestContext ctx, Prompt prompt) {
				return copy.stream().allMatch(filter -> filter.prompt(ctx, prompt));
			}
		};
	}

}
