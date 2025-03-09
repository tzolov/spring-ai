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

import java.util.List;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncMcpToolCallbackTests {

	@Mock
	private McpAsyncClient asyncMcpClient;

	@Mock
	private Tool tool;

	@Test
	void getToolDefinitionShouldReturnCorrectDefinition() {
		when(tool.name()).thenReturn("testAsyncTool");
		when(tool.description()).thenReturn("Test async tool description");

		AsyncMcpToolCallback callback = new AsyncMcpToolCallback(asyncMcpClient, tool);

		var toolDefinition = callback.getToolDefinition();

		assertThat(toolDefinition.name()).isEqualTo("testAsyncTool");
		assertThat(toolDefinition.description()).isEqualTo("Test async tool description");
		assertThat(toolDefinition.inputSchema()).isNotNull();
	}

	@Test
	void callShouldHandleJsonInputAndOutput() {
		when(tool.name()).thenReturn("testAsyncTool");

		CallToolResult callResult = mock(CallToolResult.class);
		TextContent textContent = mock(TextContent.class);
		when(textContent.text()).thenReturn("result text");
		when(callResult.content()).thenReturn(List.of(textContent));

		when(asyncMcpClient.callTool(any(CallToolRequest.class))).thenReturn(Mono.just(callResult));

		AsyncMcpToolCallback callback = new AsyncMcpToolCallback(asyncMcpClient, tool);

		String response = callback.call("{\"param\":\"value\"}");

		assertThat(response).isNotNull();
		assertThat(response).contains("result text");
	}

	@Test
	void callShouldHandleEmptyResponse() {

		when(tool.name()).thenReturn("testAsyncTool");

		CallToolResult callResult = mock(CallToolResult.class);
		when(callResult.content()).thenReturn(List.of());

		when(asyncMcpClient.callTool(any(CallToolRequest.class))).thenReturn(Mono.just(callResult));

		AsyncMcpToolCallback callback = new AsyncMcpToolCallback(asyncMcpClient, tool);

		String response = callback.call("{\"param\":\"value\"}");

		assertThat(response).isNotNull();
		assertThat(response).isEqualTo("[]");
	}

	@Test
	void callShouldHandleErrorsGracefully() {
		when(tool.name()).thenReturn("testAsyncTool");
		when(asyncMcpClient.callTool(any(CallToolRequest.class)))
			.thenReturn(Mono.error(new RuntimeException("Test error")));

		AsyncMcpToolCallback callback = new AsyncMcpToolCallback(asyncMcpClient, tool);

		try {
			callback.call("{\"param\":\"value\"}");
		}
		catch (Exception e) {
			assertThat(e).isInstanceOf(RuntimeException.class);
			assertThat(e).hasMessageContaining("Test error");
		}
	}

	@Test
	void callShouldHandleComplexJsonInput() {

		when(tool.name()).thenReturn("testAsyncTool");

		CallToolResult callResult = mock(CallToolResult.class);
		TextContent textContent = mock(TextContent.class);
		when(textContent.text()).thenReturn("complex result");
		when(callResult.content()).thenReturn(List.of(textContent));

		when(asyncMcpClient.callTool(any(CallToolRequest.class))).thenReturn(Mono.just(callResult));

		AsyncMcpToolCallback callback = new AsyncMcpToolCallback(asyncMcpClient, tool);

		String response = callback.call("{\"nested\":{\"param\":\"value\"},\"array\":[1,2,3]}");

		assertThat(response).isNotNull();
		assertThat(response).contains("complex result");
	}

}
