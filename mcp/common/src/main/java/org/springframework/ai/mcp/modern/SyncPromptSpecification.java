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

import io.modelcontextprotocol.modern.McpSchema.GetPromptOutcome;
import io.modelcontextprotocol.modern.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpSyncResponse;

import org.springframework.util.Assert;

/**
 * A prompt exposed on a 2026-07-28 server: its definition and the handler that serves
 * {@code prompts/get}.
 *
 * @param prompt the prompt definition
 * @param handler the handler invoked for {@code prompts/get}
 * @author Christian Tzolov
 * @since 2.2.0
 */
public record SyncPromptSpecification(Prompt prompt, Handler handler) {

	public SyncPromptSpecification {
		Assert.notNull(prompt, "prompt must not be null");
		Assert.notNull(handler, "handler must not be null");
	}

	/**
	 * Serves a {@code prompts/get} request.
	 */
	@FunctionalInterface
	public interface Handler {

		McpSyncResponse<GetPromptOutcome> get(McpRequestContext context, GetPromptRequest request);

	}
}
