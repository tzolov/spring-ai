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

import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpSyncResponse;

import org.springframework.util.Assert;

/**
 * A tool exposed on a 2026-07-28 server: its definition and the handler that serves
 * {@code tools/call}.
 *
 * @param tool the tool definition
 * @param handler the handler invoked for {@code tools/call}
 * @author Christian Tzolov
 * @since 2.2.0
 */
public record SyncToolSpecification(Tool tool, Handler handler) {

	public SyncToolSpecification {
		Assert.notNull(tool, "tool must not be null");
		Assert.notNull(handler, "handler must not be null");
	}

	/**
	 * Serves a {@code tools/call} request. The response is either a plain result or a
	 * streaming body that may push progress notifications before the result. A protocol
	 * failure is signalled with {@link io.modelcontextprotocol.modern.McpException}.
	 */
	@FunctionalInterface
	public interface Handler {

		McpSyncResponse<CallToolOutcome> call(McpRequestContext context, CallToolRequest request);

	}
}
