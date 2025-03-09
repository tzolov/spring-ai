/*
 * Copyright 2025-2025 the original author or authors.
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

package org.springframework.ai.mcp;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Implementation of {@link ToolCallback} that adapts MCP tools to Spring AI's tool
 * interface with asynchronous execution support.
 * <p>
 * This class acts as a bridge between the Model Context Protocol (MCP) and Spring AI's
 * tool system, allowing MCP tools to be used seamlessly within Spring AI applications.
 * It:
 * <ul>
 * <li>Converts MCP tool definitions to Spring AI tool definitions</li>
 * <li>Handles the asynchronous execution of tool calls through the MCP client</li>
 * <li>Manages JSON serialization/deserialization of tool inputs and outputs</li>
 * </ul>
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * McpAsyncClient mcpClient = // obtain MCP client
 * Tool mcpTool = // obtain MCP tool definition
 * ToolCallback callback = new AsyncMcpToolCallback(mcpClient, mcpTool);
 *
 * // Use the tool through Spring AI's interfaces
 * ToolDefinition definition = callback.getToolDefinition();
 * String result = callback.call("{\"param\": \"value\"}");
 * }</pre>
 *
 * @author Christian Tzolov
 * @see ToolCallback
 * @see McpAsyncClient
 * @see Tool
 */
public class AsyncMcpToolCallback implements ToolCallback {

	private final McpAsyncClient asyncMcpClient;

	private final McpSchema.Tool tool;

	public AsyncMcpToolCallback(McpAsyncClient asyncMcpClient, McpSchema.Tool tool) {
		this.asyncMcpClient = asyncMcpClient;
		this.tool = tool;
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return ToolDefinition.builder()
			.name(this.tool.name())
			.description(this.tool.description())
			.inputSchema(ModelOptionsUtils.toJsonString(this.tool.inputSchema()))
			.build();
	}

	@Override
	public String call(String functionInput) {
		Map<String, Object> arguments = ModelOptionsUtils.jsonToMap(functionInput);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> result = new AtomicReference<>();
		AtomicReference<Throwable> error = new AtomicReference<>();

		this.asyncMcpClient.callTool(new McpSchema.CallToolRequest(this.getToolDefinition().name(), arguments))
			.map(response -> ModelOptionsUtils.toJsonString(response.content()))
			.subscribe(value -> {
				result.set(value);
				latch.countDown();
			}, throwable -> {
				error.set(throwable);
				latch.countDown();
			});

		try {
			latch.await();
			if (error.get() != null) {
				if (error.get() instanceof RuntimeException) {
					throw (RuntimeException) error.get();
				}
				else {
					throw new RuntimeException("Error during tool execution", error.get());
				}
			}
			return result.get();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Tool execution was interrupted", e);
		}
	}

}
